#!/usr/bin/env bash
# S11: what does the LOCK SCREEN show for a VISIBILITY_PRIVATE notification?
#   s11-lockscreen.sh <allow_private: 0|1|default>
# Posts two notifications from the spike app while locked:
#   111  VISIBILITY_PRIVATE + public version ("PUBLICWHO"/"PUBLICWHERE")
#   112  VISIBILITY_PRIVATE, no public version
# Private text contains "SECRETWHAT-<id>". Dumps the lock-screen text and
# reports which strings are visible. Needs a PIN set.
set -u
mode=$1
ADB=${ADB:-adb}; SERIAL=${SERIAL:-emulator-5554}
here=$(cd "$(dirname "$0")" >/dev/null && pwd)
a() { "$ADB" -s "$SERIAL" "$@"; }
u() { ADB=$ADB SERIAL=$SERIAL python3 "$here/ui.py" "$@"; }

if [ "$mode" = default ]; then
  a shell settings delete secure lock_screen_allow_private_notifications >/dev/null
else
  a shell settings put secure lock_screen_allow_private_notifications "$mode"
fi
echo "settings: show=$(a shell settings get secure lock_screen_show_notifications) allow_private=$(a shell settings get secure lock_screen_allow_private_notifications)"

a shell input keyevent KEYCODE_SLEEP; sleep 1.5
P=dev.zacx.muster.spikes/.SpikeActivity
a shell am start -n $P --es cmd notify-private >/dev/null 2>&1; sleep 1
a shell am start -n $P --es cmd notify-nopublic >/dev/null 2>&1; sleep 1
a shell input keyevent KEYCODE_WAKEUP; sleep 2.5
echo "keyguard: $(a shell dumpsys window | grep -o 'isKeyguardShowing=[a-z]*')"
u dump 'SECRETWHAT|PUBLICWHO|PUBLICWHERE|agent-x|Contents hidden|muster spikes|new notification|Sensitive'
for s in SECRETWHAT-111 SECRETWHAT-112 PUBLICWHO; do
  if u has "$s"; then echo "VISIBLE  $s"; else echo "hidden   $s"; fi
done
