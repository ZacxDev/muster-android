#!/usr/bin/env python3
"""Tiny uiautomator driver for the Phase 0 spikes.

  ui.py dump [regex]      print visible text/desc nodes (optionally filtered)
  ui.py tap <regex>       tap the first node whose text or content-desc matches
  ui.py has <regex>       exit 0 if a node matches, 1 if not

Uses `adb` on PATH and $SERIAL (default emulator-5554).
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

SERIAL = os.environ.get("SERIAL", "emulator-5554")
ADB = os.environ.get("ADB", "adb")


def adb(*args):
    return subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True, check=False).stdout


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("exec-out", "cat", "/sdcard/ui.xml")
    root = ET.fromstring(xml[xml.index("<"):])
    for n in root.iter("node"):
        label = (n.get("text") or "") + ("|" + n.get("content-desc") if n.get("content-desc") else "")
        if label.strip("|"):
            yield label, n.get("bounds"), n.get("resource-id")


def center(bounds):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", bounds))
    return (x1 + x2) // 2, (y1 + y2) // 2


def main():
    cmd, pat = sys.argv[1], (sys.argv[2] if len(sys.argv) > 2 else ".")
    rx = re.compile(pat)
    found = [(l, b, r) for l, b, r in nodes() if rx.search(l)]
    if cmd == "dump":
        for l, b, r in found:
            print(f"{l!r} {b} {r}")
    elif cmd == "has":
        sys.exit(0 if found else 1)
    elif cmd == "expand":
        # Tap the "Expand" button on the notification row whose title matches.
        if not found:
            sys.exit(f"no node matches {pat!r}")
        ty = center(found[0][1])[1]
        for l, b, r in nodes():
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", b))
            if r == "android:id/expand_button" and "Expand" in l and y1 <= ty <= y2:
                adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                print(f"expanded row of {found[0][0]!r}")
                return
        print("row already expanded or no expand button")
    elif cmd == "tap":
        if not found:
            print(f"no node matches {pat!r}", file=sys.stderr)
            sys.exit(1)
        x, y = center(found[0][1])
        adb("shell", "input", "tap", str(x), str(y))
        print(f"tapped {found[0][0]!r} at {x},{y}")


if __name__ == "__main__":
    main()
