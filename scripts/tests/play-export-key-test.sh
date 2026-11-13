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

# A `java` first on PATH that records every argument it is given (one per
# line), and the contents of any @argfile among them, then runs the real
# java. Deterministic, unlike sampling the process table: a pgrep loop missed
# a short-lived java that had the password in its argv.
real_java=$(command -v java)
mkdir "$tmp/bin"
cat >"$tmp/bin/java" <<EOF
#!/usr/bin/env bash
for a in "\$@"; do
    printf '%s\n' "\$a" >>"$tmp/argv.log"
    if [[ "\$a" == @* && -f "\${a#@}" ]]; then cat "\${a#@}" >>"$tmp/argfile.log"; fi
done
exec "$real_java" "\$@"
EOF
chmod +x "$tmp/bin/java"
: >"$tmp/argv.log"; : >"$tmp/argfile.log"

# The happy path, through the recording java.
set +e
PATH=$tmp/bin:$PATH KS_PASS=file:$tmp/pass "$exp" "$tmp/t.p12" t "$tmp/enc.pem" "$fp" "$tmp/private/out.zip" >"$tmp/log" 2>&1; rc=$?
set -e
check "exports and checks the certificate" 0 "$rc"
# Positive controls: the recording java saw pepk's launch, and the password
# did reach java (through the argfile), so the zero below is a measurement.
grep -qx -- "--alias=t" "$tmp/argv.log" && check "control: the recording java saw pepk's launch" 0 0 \
    || check "control: the recording java saw pepk's launch" 0 1
grep -qF -- "$secret" "$tmp/argfile.log" && check "control: the password reached java through the argfile" 0 0 \
    || check "control: the password reached java through the argfile" 0 1
n=$(grep -cF -- "$secret" "$tmp/argv.log" || true)
if [[ "$n" -eq 0 ]]; then
    check "the password is in java's argv 0 times" 0 0
else
    echo "the password is in java's argv $n time(s)" >"$tmp/log"
    check "the password is in java's argv 0 times" 0 1
fi
if grep -qF -- "$secret" "$tmp/log"; then check "the password is not in the output" 0 1; else check "the password is not in the output" 0 0; fi
[[ "$(stat -c %a "$tmp/private/out.zip" 2>/dev/null)" == 600 ]] && check "the zip is mode 0600" 0 0 || check "the zip is mode 0600" 0 1
jar tf "$tmp/private/out.zip" | sort | tr '\n' ' ' | grep -qx 'certificate.pem encryptedPrivateKey ' \
    && check "the zip holds the encrypted key and the certificate" 0 0 || check "the zip holds the encrypted key and the certificate" 0 1

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

# The same refusal with no working git on PATH (a stub that fails like a
# missing binary): it must not depend on git.
mkdir "$tmp/nogit" && printf '#!/bin/sh\nexit 127\n' >"$tmp/nogit/git" && chmod +x "$tmp/nogit/git"
set +e
PATH=$tmp/nogit:$PATH KS_PASS=file:$tmp/pass "$exp" "$tmp/t.p12" t "$tmp/enc.pem" "$fp" "$root/out.zip" >"$tmp/log" 2>&1; rc=$?
set -e
check "refuses an output inside a git work tree without git (exit 2)" 2 "$rc"
[[ ! -e "$root/out.zip" ]] && check "nothing written in the work tree without git" 0 0 || { rm -f "$root/out.zip"; check "nothing written in the work tree without git" 0 1; }

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
