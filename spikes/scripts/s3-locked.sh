#!/usr/bin/env bash
# S3 lock-screen path: lock the phone, post the step-up notification, wake,
# expand it on the lock screen, tap "Sign", then present the fingerprint.
#   s3-locked.sh <alias> <authreq:true|false> [variant]
# Needs: adb on PATH (or $ADB), $SERIAL, a PIN + enrolled emulator fingerprint.
set -u
alias=$1; authreq=$2; variant=${3:-plain}
ADB=${ADB:-adb}; SERIAL=${SERIAL:-emulator-5554}
here=$(cd "$(dirname "$0")" >/dev/null && pwd)
a() { "$ADB" -s "$SERIAL" "$@"; }
u() { ADB=$ADB SERIAL=$SERIAL python3 "$here/ui.py" "$@"; }
finger() { a emu finger touch 1 >/dev/null; sleep 0.5; a emu finger remove 1 >/dev/null; }

a shell cmd notification cancel_all >/dev/null 2>&1 || true
a shell input keyevent KEYCODE_SLEEP; sleep 1.5
a shell am start -n dev.zacx.muster.spikes/.SpikeActivity --es cmd notify-activity \
  --es alias "$alias" --ez authreq "$authreq" --es variant "$variant" >/dev/null 2>&1
sleep 1; a shell input keyevent KEYCODE_WAKEUP; sleep 2
a logcat -c
u expand "S3 activity $alias" >/dev/null; sleep 1.5
u tap '^Sign' || { echo "Sign not found"; u dump; exit 1; }
sleep 2.5
echo "after tap: $(a shell dumpsys window | grep -o 'isKeyguardShowing=[a-z]*')"
u dump 'Confirm|PIN|Cancel|fingerprint|Touch' | head -5
finger; sleep 2.5
echo "after 1st finger: $(a shell dumpsys window | grep -o 'isKeyguardShowing=[a-z]*')"
u dump 'Confirm|PIN|Cancel|fingerprint|Touch' | head -5
if ! a logcat -d -s MUSTER_SPIKE | grep -q RESULT; then finger; sleep 2.5; echo "after 2nd finger"; fi
a logcat -d -s MUSTER_SPIKE | grep -v 'cmd='
