#!/usr/bin/env bash
# S9: against a LOCAL ntfy (nixpkgs ntfy-sh), configured the way UnifiedPush
# needs it: deny-all by default, `up*` topics write-only for everyone.
#   nix develop .#spikes -c spikes/ntfy/s9-ntfy.sh <workdir>
# Measures: is VAPID enforced on up* publishes; what happens to a message
# over the 4096-byte limit; does a non-up topic stay closed (control).
set -u
W=${1:?workdir}; mkdir -p "$W"; PORT=${PORT:-2586}; B=http://127.0.0.1:$PORT
cat >"$W/server.yml" <<EOF
base-url: "$B"
listen-http: "127.0.0.1:$PORT"
cache-file: "$W/cache.db"
auth-file: "$W/auth.db"
auth-default-access: "deny-all"
EOF
ntfy serve --config "$W/server.yml" >"$W/serve.log" 2>&1 &
PID=$!; echo "ntfy pid $PID"
trap 'kill "$PID" 2>/dev/null' EXIT
for i in $(seq 1 30); do curl -fs "$B/v1/health" >/dev/null && break; sleep 0.3; done
# The auth DB exists once the server has started; the server reads it live.
export NTFY_PASSWORD=spike-admin-pw   # throwaway local-only user
ntfy user --config "$W/server.yml" add --role=admin admin
ntfy access --config "$W/server.yml" everyone 'up*' write-only
ntfy_ver=$(ntfy --version 2>/dev/null || ntfy version 2>/dev/null | head -1); echo "version: $ntfy_ver"

T=upS9$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 10)   # 14 chars, like UnifiedPush
ct=$(mktemp); head -c 200 /dev/urandom >"$ct"; big=$(mktemp); head -c 6000 /dev/urandom >"$big"
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

echo "1 up* publish, no Authorization:            $(code -X POST -H 'Content-Encoding: aes128gcm' --data-binary @"$ct" "$B/$T")"
echo "2 up* publish, bogus 'vapid t=...,k=...':   $(code -X POST -H 'Content-Encoding: aes128gcm' -H 'Authorization: vapid t=a.b.c, k=BAAA' --data-binary @"$ct" "$B/$T")"
echo "3 up* publish, bogus 'WebPush <jwt>':       $(code -X POST -H 'Content-Encoding: aes128gcm' -H 'Authorization: WebPush a.b.c' --data-binary @"$ct" "$B/$T")"
echo "4 non-up topic publish (control, expect 403): $(code -X POST --data x "$B/notup$RANDOM")"
echo "5 up* READ anonymously (expect 403):         $(code "$B/$T/json?poll=1")"
echo "6 up* publish 6000 B:                        $(code -X POST -H 'Content-Encoding: aes128gcm' --data-binary @"$big" "$B/$T")"
echo "--- stored messages on $T (read as admin):"
curl -s -u "admin:$NTFY_PASSWORD" "$B/$T/json?poll=1" | python3 -c '
import json, sys, base64
for line in sys.stdin:
    m = json.loads(line)
    raw = m.get("message", "")
    n = len(base64.b64decode(raw)) if m.get("encoding") == "base64" else len(raw.encode())
    enc = m.get("encoding", "-")
    print("  id=%s encoding=%s decoded_bytes=%d attachment=%s" % (m["id"], enc, n, "attachment" in m))
'
