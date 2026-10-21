#!/usr/bin/env bash
# Tests scripts/sign-release with a THROWAWAY key made here, in a temp dir
# outside the repo and deleted at exit. Needs the unsigned release APK:
#   ./gradlew assembleRelease && scripts/tests/sign-release-test.sh
set -euo pipefail
root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
apk=${1:-$root/app/build/outputs/apk/release/app-release-unsigned.apk}
[[ -f "$apk" ]] || { echo "no unsigned APK at $apk; run ./gradlew assembleRelease" >&2; exit 2; }
sign=$root/scripts/sign-release

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
export TEST_KS_PASS=throwaway-test-only
keytool -genkeypair -keystore "$tmp/t.p12" -storetype PKCS12 -storepass "$TEST_KS_PASS" \
    -alias t -keyalg EC -groupname secp256r1 -validity 2 -dname "CN=throwaway test key" >/dev/null 2>&1
fp=$(keytool -list -v -keystore "$tmp/t.p12" -storepass "$TEST_KS_PASS" -alias t | grep -m1 'SHA256:' | sed 's/.*SHA256: *//')
other=$(printf 'ab%.0s' {1..32})
cp "$apk" "$tmp/in-unsigned.apk"
export KS_PASS=env:TEST_KS_PASS

pass=0 fail=0
check() { # name, want-exit, got-exit
    if [[ "$2" == "$3" ]]; then echo "ok   $1"; pass=$((pass + 1)); else echo "FAIL $1: exit $3, want $2"; fail=$((fail + 1)); fi
}

set +e
"$sign" "$tmp/in-unsigned.apk" "$tmp/t.p12" t "$fp" "$tmp/out.apk" >"$tmp/log" 2>&1; rc=$?
set -e
check "signs and verifies with the recorded fingerprint" 0 "$rc"
[[ -f "$tmp/out.apk" ]] && grep -q "matches the recorded fingerprint" "$tmp/log" && check "output exists" 0 0 || check "output exists" 0 1

set +e
"$sign" "$tmp/in-unsigned.apk" "$tmp/t.p12" t "$other" "$tmp/wrong.apk" >"$tmp/log" 2>&1; rc=$?
set -e
check "refuses another certificate (exit 4)" 4 "$rc"
grep -q "REFUSED: the signing certificate" "$tmp/log" && [[ ! -e "$tmp/wrong.apk" ]] && check "no output left" 0 0 || check "no output left" 0 1

set +e
APK_SHA256=$other "$sign" "$tmp/in-unsigned.apk" "$tmp/t.p12" t "$fp" "$tmp/x.apk" >"$tmp/log" 2>&1; rc=$?
set -e
check "refuses an APK whose sha256 is not the published one (exit 3)" 3 "$rc"

set +e
APK_SHA256=$(sha256sum "$tmp/in-unsigned.apk" | cut -d' ' -f1) "$sign" "$tmp/in-unsigned.apk" "$tmp/t.p12" t "$fp" "$tmp/y.apk" >"$tmp/log" 2>&1; rc=$?
set -e
check "accepts the published sha256" 0 "$rc"

echo "$pass passed, $fail failed"
[[ $fail -eq 0 ]]
