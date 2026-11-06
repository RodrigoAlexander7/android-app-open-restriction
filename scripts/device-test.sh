#!/usr/bin/env bash
# End-to-end checks on a real phone over adb (complements the JVM unit tests).
#
# Requires: debug build installed, accessibility service enabled, phone unlocked.
# Usage:    scripts/device-test.sh [test_package]   (default: com.android.settings)
#
# The script backs up OpenLimit's data (limits, counters, pause) and restores it on
# exit, even if a check fails. The 4th-open check shows the block screen: tap
# "Entendido" afterwards (MIUI doesn't let adb inject taps).
set -u

APP=net.jakstech.openlimit
PKG=${1:-com.android.settings}
FILES="limits.properties counts.properties settings.properties"
BACKUP=$(mktemp -d)
PASS=0
FAIL=0

say()  { printf '\n== %s\n' "$*"; }
ok()   { printf '  PASS  %s\n' "$*"; PASS=$((PASS + 1)); }
bad()  { printf '  FAIL  %s\n' "$*"; FAIL=$((FAIL + 1)); }
check() { if [ "$2" = "$3" ]; then ok "$1 ($2)"; else bad "$1: expected '$3', got '$2'"; fi; }

runas()   { adb shell run-as "$APP" "$@"; }
put()     { printf '%b' "$2" | adb exec-in run-as "$APP" sh -c "cat > files/$1"; }
home()    { adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1; }
launch()  { adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }
count()   { runas cat files/counts.properties 2>/dev/null | grep "^$PKG=" | cut -d= -f2 | tr -d '\r'; }
decided() { adb logcat -d -s OpenLimit:D | grep -c "$1 $PKG"; }

backup() {
  for f in $FILES; do
    if runas test -f "files/$f"; then adb exec-out run-as "$APP" cat "files/$f" > "$BACKUP/$f"; fi
  done
}
restore() {
  for f in $FILES; do
    if [ -f "$BACKUP/$f" ]; then adb exec-in run-as "$APP" sh -c "cat > files/$f" < "$BACKUP/$f"
    else runas rm -f "files/$f"; fi
  done
  rm -rf "$BACKUP"
  home
}

# Fresh state: only $PKG limited to 3, given pause, zero counters.
reset() {
  put limits.properties "$PKG=3\n"
  put settings.properties "pause_seconds=$1\n"
  runas rm -f files/counts.properties
  home; sleep 1.5
  adb logcat -c
}

# ---------------------------------------------------------------------------
adb get-state >/dev/null 2>&1 || { echo "No device. Connect the phone (USB debugging on)."; exit 2; }
runas true 2>/dev/null || { echo "$APP debug build not installed."; exit 2; }

say "Service"
if adb shell dumpsys accessibility | grep -q "Bound services:{Service\[label=OpenLimit"; then
  ok "accessibility service bound"
else
  bad "service not running: enable it in Accessibility (or toggle it off/on)"; exit 1
fi
if adb shell dumpsys activity services "$APP" | grep -q "isForeground=true"; then
  ok "foreground service (survives MIUI 'clear all')"
else
  bad "service is not in the foreground"
fi

backup
trap restore EXIT

say "Counting: limit 3, pause 0 -> 1, 2, 3, then BLOCK"
reset 0
for _ in 1 2 3 4; do launch; sleep 2; home; sleep 1.5; done
check "counter after 4 opens" "$(count)" "3"
check "BLOCK decisions" "$(decided BLOCK)" "1"
echo "  (tap 'Entendido' on the block screen if it's showing)"

say "Minimum pause 45 s: open, minimize, return after 2 s -> same session"
reset 45
launch; sleep 2; home; sleep 2; launch; sleep 2; home; sleep 1
check "counter" "$(count)" "1"
check "resume decisions" "$(decided resume)" "1"

say "Late delivery: process paused, 2 opens -> both counted after resume"
reset 0
WATCHER=$(adb shell pidof "$APP:watcher" | tr -d '\r')
if [ -z "$WATCHER" ]; then
  bad "watcher process not found"
else
  runas kill -STOP "$WATCHER"
  for _ in 1 2; do launch; sleep 2; home; sleep 1.5; done
  runas kill -CONT "$WATCHER"; sleep 2
  check "counter after delayed burst" "$(count)" "2"
fi

printf '\nResult: %d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
