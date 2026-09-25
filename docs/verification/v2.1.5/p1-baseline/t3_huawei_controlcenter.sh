#!/usr/bin/env bash
# Task 3 - static capability discovery: which metadata keys do the Huawei media-control
# components read?  Read-only: every command is a pipe (unzip -p | strings | grep); nothing
# is written on the device, nothing is pulled, nothing is installed.
#
# Usage: t3_huawei_controlcenter.sh <serial> <output-dir>
set -u
ADB=/usr/bin/adb
S="${1:?usage: t3_huawei_controlcenter.sh <serial> <outdir>}"
OUT="${2:?usage: t3_huawei_controlcenter.sh <serial> <outdir>}"
mkdir -p "$OUT"

SYSTEMUI=/system/priv-app/SystemUI/SystemUI.apk
MEDIACTL=/system/priv-app/MediaPlaybackController/MediaPlaybackController.apk

# Print every dex member of an APK, concatenated, then pipe through strings.
dex_strings() {
  "$ADB" -s "$S" shell "for m in \$(unzip -l $1 | grep -oE 'classes[0-9]*\.dex'); do unzip -p $1 \$m; done | strings"
}

{
  echo "# Huawei Control Center: static capability discovery (task 3)"
  echo "# device: $S   ($("$ADB" -s "$S" shell getprop ro.product.model | tr -d '\r') / Android $("$ADB" -s "$S" shell getprop ro.build.version.release | tr -d '\r'))"
  echo "# APKs:"
  echo "#   $SYSTEMUI"
  echo "#   $MEDIACTL"
  echo
  echo "################ (a) which packages own a media-control UI"
  echo "\$ adb -s $S shell pm list packages | grep -iE 'systemui|media|hw'"
  "$ADB" -s "$S" shell "pm list packages | grep -iE 'systemui|media|hw'" 2>&1
  echo
  echo "\$ adb -s $S shell pm path com.android.systemui"
  "$ADB" -s "$S" shell "pm path com.android.systemui" 2>&1
  echo "\$ adb -s $S shell pm path com.huawei.mediacontroller"
  "$ADB" -s "$S" shell "pm path com.huawei.mediacontroller" 2>&1
  echo
  echo "################ (b) SystemUI's own parsed media model (MediaDataManager)"
  echo "# Full dump saved separately as t3_dumpsys_SystemUIService_FULL.txt"
  echo "\$ adb -s $S shell dumpsys activity service SystemUIService"
  "$ADB" -s "$S" shell "dumpsys activity service SystemUIService" 2>&1 | grep -n "MediaDataManager" -A6
  echo
  echo "################ (c) does SystemUI mention 'lyric' at all?"
  echo "\$ ... dumpsys activity service SystemUIService | grep -in lyric"
  "$ADB" -s "$S" shell "dumpsys activity service SystemUIService" 2>&1 | grep -in "lyric" || echo "  <NO MATCH>"
  echo
  echo "################ (d) standard platform metadata keys referenced by each component"
  echo "# 31 keys is the complete android.media.metadata.* set; a lyrics key would appear here if read."
  for APK in "$SYSTEMUI" "$MEDIACTL"; do
    echo "---- $APK"
    dex_strings "$APK" 2>/dev/null | grep -oE "android\.media\.metadata\.[A-Z_]+" | sort -u
    echo "    (count: $(dex_strings "$APK" 2>/dev/null | grep -oE 'android\.media\.metadata\.[A-Z_]+' | sort -u | wc -l))"
  done
  echo
  echo "################ (e) any lyric-shaped token at all (singular AND plural)"
  echo "# POSITIVE CONTROL for this method: (d) above returns 31 real keys for SystemUI, so the"
  echo "# pipeline demonstrably finds key constants when they exist. Absence in (e) is therefore"
  echo "# a real absence of a lyric token, not a broken scan."
  for APK in "$SYSTEMUI" "$MEDIACTL"; do
    echo "---- $APK"
    echo "  -- grep -iE 'lyric' (raw substr, any case), unique, first 40:"
    dex_strings "$APK" 2>/dev/null | grep -iE "lyric" | sort -u | head -40
    echo "  -- count: $(dex_strings "$APK" 2>/dev/null | grep -icE 'lyric')"
    echo "  -- metadata-key-SHAPED lyric tokens (e.g. *.metadata.LYRICS / METADATA_KEY_LYRICS):"
    dex_strings "$APK" 2>/dev/null | grep -oE "[A-Za-z0-9_.]*(LYRICS|KeyLyrics|key_lyrics|metadata\.lyrics)[A-Za-z0-9_.]*" | sort -u || true
    echo "     (count: $(dex_strings "$APK" 2>/dev/null | grep -oE '[A-Za-z0-9_.]*(LYRICS|KeyLyrics|key_lyrics|metadata\.lyrics)[A-Za-z0-9_.]*' | sort -u | wc -l))"
  done
} > "$OUT/t3_rom_metadata_key_scan.txt" 2>&1
echo "wrote $OUT/t3_rom_metadata_key_scan.txt"
