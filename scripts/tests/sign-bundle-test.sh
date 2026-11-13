#!/usr/bin/env bash
# Tests scripts/sign-bundle with a THROWAWAY key made here, in a temp dir
# outside the repo and deleted at exit. Needs the unsigned release bundle:
#   ./gradlew bundleRelease && scripts/tests/sign-bundle-test.sh
set -euo pipefail
root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
aab=${1:-$root/app/build/outputs/bundle/release/app-release.aab}
[[ -f "$aab" ]] || { echo "no unsigned bundle at $aab; run ./gradlew bundleRelease" >&2; exit 2; }
sign=$root/scripts/sign-bundle

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
export TEST_KS_PASS=throwaway-test-only
keytool -genkeypair -keystore "$tmp/t.p12" -storetype PKCS12 -storepass "$TEST_KS_PASS" \
    -alias t -keyalg RSA -keysize 2048 -validity 2 -dname "CN=throwaway test key" >/dev/null 2>&1
fp=$(keytool -list -v -keystore "$tmp/t.p12" -storepass "$TEST_KS_PASS" -alias t | grep -m1 'SHA256:' | sed 's/.*SHA256: *//')
printf '%s\n' "$TEST_KS_PASS" >"$tmp/pass"
other=$(printf 'ab%.0s' {1..32})
cp "$aab" "$tmp/in.aab"

pass=0 fail=0
check() { # name, want-exit, got-exit
    if [[ "$2" == "$3" ]]; then echo "ok   $1"; pass=$((pass + 1)); else echo "FAIL $1: exit $3, want $2"; sed 's/^/     | /' "$tmp/log"; fail=$((fail + 1)); fi
}
run() { set +e; "$@" >"$tmp/log" 2>&1; rc=$?; set -e; }

KS_PASS=env:TEST_KS_PASS run "$sign" "$tmp/in.aab" "$tmp/t.p12" t "$fp" "$tmp/out.aab"
check "signs and verifies with the recorded fingerprint (KS_PASS=env:)" 0 "$rc"
if [[ -f "$tmp/out.aab" ]] && grep -q "matches the recorded fingerprint" "$tmp/log" \
    && jarsigner -verify "$tmp/out.aab" 2>&1 | grep -qx 'jar verified.'; then
    check "output exists and jarsigner verifies it" 0 0
else check "output exists and jarsigner verifies it" 0 1; fi

KS_PASS=file:$tmp/pass run "$sign" "$tmp/in.aab" "$tmp/t.p12" t "$fp" "$tmp/out2.aab"
check "signs with KS_PASS=file:" 0 "$rc"

KS_PASS=env:TEST_KS_PASS run "$sign" "$tmp/in.aab" "$tmp/t.p12" t "$other" "$tmp/wrong.aab"
check "refuses another certificate (exit 4)" 4 "$rc"
grep -q "REFUSED: the signing certificate" "$tmp/log" && [[ ! -e "$tmp/wrong.aab" ]] && check "no output left" 0 0 || check "no output left" 0 1

AAB_SHA256=$other KS_PASS=env:TEST_KS_PASS run "$sign" "$tmp/in.aab" "$tmp/t.p12" t "$fp" "$tmp/x.aab"
check "refuses a bundle whose sha256 is not the expected one (exit 3)" 3 "$rc"

AAB_SHA256=$(sha256sum "$tmp/in.aab" | cut -d' ' -f1) KS_PASS=env:TEST_KS_PASS run "$sign" "$tmp/in.aab" "$tmp/t.p12" t "$fp" "$tmp/y.aab"
check "accepts the expected sha256" 0 "$rc"

KS_PASS=env:TEST_KS_PASS run "$sign" "$tmp/out.aab" "$tmp/t.p12" t "$fp" "$tmp/twice.aab"
check "refuses an already-signed bundle (exit 2)" 2 "$rc"
grep -q "already signed" "$tmp/log" && check "says it is already signed" 0 0 || check "says it is already signed" 0 1

run "$sign" --verify "$tmp/out.aab" "$fp"
check "--verify accepts the signed bundle" 0 "$rc"

run "$sign" --verify "$tmp/out.aab" "$other"
check "--verify refuses another certificate (exit 4)" 4 "$rc"

run "$sign" --verify "$tmp/in.aab" "$fp"
check "--verify refuses the unsigned bundle (exit 5)" 5 "$rc"

# jarsigner -verify alone exits 0 and prints "jar verified." for a bundle with
# an unsigned entry added after signing; the script must not.
cp "$tmp/out.aab" "$tmp/tampered.aab"
mkdir "$tmp/extra" && echo extra >"$tmp/extra/added.txt"
jar uf "$tmp/tampered.aab" -C "$tmp/extra" added.txt
if jarsigner -verify "$tmp/tampered.aab" 2>&1 | grep -qx 'jar verified.'; then
    check "control: jarsigner alone calls the tampered bundle verified" 0 0
else check "control: jarsigner alone calls the tampered bundle verified" 0 1; fi
run "$sign" --verify "$tmp/tampered.aab" "$fp"
check "--verify refuses a bundle with an unsigned entry (exit 5)" 5 "$rc"
grep -q "of .* entries are signed" "$tmp/log" && check "says which entries are unsigned" 0 0 || check "says which entries are unsigned" 0 1

KS_PASS= run "$sign" "$tmp/in.aab" "$tmp/t.p12" t "$fp" "$tmp/np.aab"
check "refuses to run without KS_PASS (exit 2)" 2 "$rc"
grep -q "there is no prompt" "$tmp/log" && check "says why" 0 0 || check "says why" 0 1

KS_PASS=pass:literal run "$sign" "$tmp/in.aab" "$tmp/t.p12" t "$fp" "$tmp/z.aab"
check "refuses a password spec other than file:/env: (exit 2)" 2 "$rc"

echo "$pass passed, $fail failed"
[[ $fail -eq 0 ]]
