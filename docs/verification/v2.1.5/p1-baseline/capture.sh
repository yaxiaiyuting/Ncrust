#!/usr/bin/env bash
# Read-only baseline capture for Ncrust media-session metadata.
# Usage: capture.sh <serial>
# Writes raw stdout+stderr of each adb command into the serial's own directory.
# Does NOT modify the device: only `am start` (launch), `dumpsys`, `getprop`.
set -u

ADB=/usr/bin/adb
S="${1:?usage: capture.sh <serial>}"
BASE=/home/duanjb666/deepseek/ncrust-gpl/Ncrust/docs/verification/v2.1.5/p1-baseline
D="$BASE/$S"
mkdir -p "$D"

# cmd#1 - launch the app (task-specified command)
"$ADB" -s "$S" shell am start -n com.takahashirinta.ncrust/.MainActivity >"$D/am_start.txt" 2>&1
echo "exit=$?" >>"$D/am_start.txt"

# cmd#2 - package/process presence right after launch
"$ADB" -s "$S" shell pidof com.takahashirinta.ncrust >"$D/pidof.txt" 2>&1
echo "exit=$?" >>"$D/pidof.txt"

# give the app ~15 s to spin up its sessions / notification
sleep 15

# cmd#3 - raw media session dump (task-specified command)
"$ADB" -s "$S" shell dumpsys media_session >"$D/media_session.txt" 2>&1
echo "exit=$?" >"$D/media_session.exit"

# cmd#4 - raw notification dump, unredacted (task-specified command)
"$ADB" -s "$S" shell dumpsys notification --noredact >"$D/notification.txt" 2>&1
echo "exit=$?" >"$D/notification.exit"

# cmd#5 - audio playback state at capture time (context: was anything playing?)
"$ADB" -s "$S" shell dumpsys audio >"$D/audio.txt" 2>&1
echo "exit=$?" >"$D/audio.exit"

# cmd#6 - property snapshot for provenance
{
  echo "\$ adb -s $S shell getprop ro.product.model        # $(  "$ADB" -s "$S" shell getprop ro.product.model)"
  echo "\$ adb -s $S shell getprop ro.product.brand        # $(  "$ADB" -s "$S" shell getprop ro.product.brand)"
  echo "\$ adb -s $S shell getprop ro.product.manufacturer # $(  "$ADB" -s "$S" shell getprop ro.product.manufacturer)"
  echo "\$ adb -s $S shell getprop ro.build.version.release # $(  "$ADB" -s "$S" shell getprop ro.build.version.release)"
  echo "\$ adb -s $S shell getprop ro.build.version.sdk    # $(  "$ADB" -s "$S" shell getprop ro.build.version.sdk)"
  echo "\$ adb -s $S shell getprop ro.build.display.id     # $(  "$ADB" -s "$S" shell getprop ro.build.display.id)"
  echo "\$ adb -s $S shell getprop ro.build.characteristics # $(  "$ADB" -s "$S" shell getprop ro.build.characteristics)"
  echo "\$ adb -s $S shell date                            # $(  "$ADB" -s "$S" shell date)"
} >"$D/device_props.txt" 2>&1

echo "captured -> $D"
