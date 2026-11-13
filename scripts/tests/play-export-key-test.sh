#!/usr/bin/env bash
# Tests scripts/play-export-key with a THROWAWAY keystore and a THROWAWAY
# "Google" encryption key made here, in a temp dir outside the repo and
# deleted at exit. Needs Google's pepk.jar (not in the repo, not in CI):
#   PEPK_JAR=/path/to/pepk.jar scripts/tests/play-export-key-test.sh
set -euo pipefail
root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
[[ -f "${PEPK_JAR:-}" ]] || { echo "set PEPK_JAR to pepk.jar" >&2; exit 2; }
export PEPK_JAR
exp=$root/scripts/play-export-key

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
secret=throwaway-$RANDOM-$RANDOM-pw
printf '%s\n' "$secret" >"$tmp/pass"
keytool -genkeypair -keystore "$tmp/t.p12" -storetype PKCS12 -storepass:file "$tmp/pass" \
    -alias t -keyalg RSA -keysize 2048 -validity 2 -dname "CN=throwaway test key" >/dev/null 2>&1
fp=$(keytool -list -v -keystore "$tmp/t.p12" -storepass:file "$tmp/pass" -alias t | grep -m1 'SHA256:' | sed 's/.*SHA256: *//')
other=$(printf 'ab%.0s' {1..32})
# A stand-in for Play Console's encryption_public_key.pem: an RSA public key.
keytool -genkeypair -keystore "$tmp/enc.p12" -storetype PKCS12 -storepass throwaway-enc \
    -alias e -keyalg RSA -keysize 2048 -validity 2 -dname "CN=enc" >/dev/null 2>&1
keytool -exportcert -rfc -keystore "$tmp/enc.p12" -storepass throwaway-enc -alias e >"$tmp/enc.crt"
# pepk wants a PEM PUBLIC KEY, not a certificate.
java_pub() {
    cat >"$tmp/Pub.java" <<'EOF'
import java.io.FileInputStream; import java.security.cert.*; import java.util.Base64;
public class Pub { public static void main(String[] a) throws Exception {
  X509Certificate c = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new FileInputStream(a[0]));
  System.out.println("-----BEGIN PUBLIC KEY-----");
  System.out.println(Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(c.getPublicKey().getEncoded()));
  System.out.println("-----END PUBLIC KEY-----"); } }
EOF
    java "$tmp/Pub.java" "$tmp/enc.crt"
}
java_pub >"$tmp/enc.pem"
mkdir -m 700 "$tmp/private"

pass=0 fail=0
check() { # name, want-exit, got-exit
    if [[ "$2" == "$3" ]]; then echo "ok   $1"; pass=$((pass + 1)); else echo "FAIL $1: exit $3, want $2"; sed 's/^/     | /' "$tmp/log"; fail=$((fail + 1)); fi
}

# The happy path, watching every process's argv for the password meanwhile.
set +e
KS_PASS=file:$tmp/pass "$exp" "$tmp/t.p12" t "$tmp/enc.pem" "$fp" "$tmp/private/out.zip" >"$tmp/log" 2>&1 &
pid=$!
seen=0
# pgrep scans every process's command line in one pass, fast enough to
# catch the short-lived java (a per-process shell loop was too slow and
# missed it: a mutant that put the password in argv survived).
while kill -0 "$pid" 2>/dev/null; do
    if pgrep -f -- "$secret" >/dev/null; then seen=1; fi
done
wait "$pid"; rc=$?
set -e
check "exports and checks the certificate" 0 "$rc"
check "the password is in no process's argv" 0 "$seen"
if grep -qF -- "$secret" "$tmp/log"; then check "the password is not in the output" 0 1; else check "the password is not in the output" 0 0; fi
[[ "$(stat -c %a "$tmp/private/out.zip" 2>/dev/null)" == 600 ]] && check "the zip is mode 0600" 0 0 || check "the zip is mode 0600" 0 1
jar tf "$tmp/private/out.zip" | sort | tr '\n' ' ' | grep -qx 'certificate.pem encryptedPrivateKey ' \
    && check "the zip holds the encrypted key and the certificate" 0 0 || check "the zip holds the encrypted key and the certificate" 0 1

# Positive control for the argv watch: a process carrying the secret IS seen.
set +e
bash -c 'sleep 3; :' "$secret" &
ctl=$!
seen=0
sleep 0.5
if pgrep -f -- "$secret" >/dev/null; then seen=1; fi
kill "$ctl" 2>/dev/null; wait "$ctl" 2>/dev/null
set -e
check "control: the argv watch sees a process carrying the secret" 1 "$seen"

set +e
KS_PASS=file:$tmp/pass "$exp" "$tmp/t.p12" t "$tmp/enc.pem" "$other" "$tmp/private/wrong.zip" >"$tmp/log" 2>&1; rc=$?
set -e
check "refuses another certificate (exit 4)" 4 "$rc"
[[ ! -e "$tmp/private/wrong.zip" ]] && check "no zip left" 0 0 || check "no zip left" 0 1

set +e
KS_PASS=file:$tmp/pass "$exp" "$tmp/t.p12" t "$tmp/enc.pem" "$fp" "$root/out.zip" >"$tmp/log" 2>&1; rc=$?
set -e
check "refuses an output inside a git work tree (exit 2)" 2 "$rc"
[[ ! -e "$root/out.zip" ]] && check "nothing written in the work tree" 0 0 || { rm -f "$root/out.zip"; check "nothing written in the work tree" 0 1; }

set +e
KS_PASS=file:$tmp/pass "$exp" "$tmp/t.p12" t "$tmp/enc.pem" "$fp" "$tmp/private/out.zip" >"$tmp/log" 2>&1; rc=$?
set -e
check "refuses to overwrite an existing zip (exit 2)" 2 "$rc"

printf 'wrong"quote\n' >"$tmp/badpass"
set +e
KS_PASS=file:$tmp/badpass "$exp" "$tmp/t.p12" t "$tmp/enc.pem" "$fp" "$tmp/private/q.zip" >"$tmp/log" 2>&1; rc=$?
set -e
check "refuses a password the argfile cannot carry (exit 2)" 2 "$rc"

echo "$pass passed, $fail failed"
[[ $fail -eq 0 ]]
