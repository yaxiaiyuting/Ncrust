#!/usr/bin/env bash
# 单应用动态取证：播一首 → 采证 → 停掉。只读命令 + am start + input keyevent。
# usage: capture_app.sh <tag> <pkg> <activity> <playmode>
#   playmode: none | keyevent | keyevent2
set -u
S=WVQ6R22124000968
OUT=/home/duanjb666/deepseek/ncrust-gpl/Ncrust/docs/verification/v2.1.6/p1-huawei-whitelist
TAG="$1"; PKG="$2"; ACT="$3"; MODE="${4:-keyevent}"
MODE="${MODE%$'\r'}"
TS="$(date -Is)"

{
echo "############ capture: $TAG ($PKG)  开始时间 $TS  设备 $S"
echo "\$ adb -s $S shell am force-stop <四个候选应用>   # 清场，避免别的会话干扰"
for p in com.takahashirinta.ncrust com.netease.cloudmusic com.huawei.music com.ss.android.ugc.aweme; do
  adb -s $S shell am force-stop $p
done
sleep 2

echo "\$ adb -s $S logcat -c"
adb -s $S logcat -c

echo "\$ adb -s $S shell am start -n $ACT"
adb -s $S shell am start -n "$ACT"
echo "exit=$?"
sleep 6

if [ "$MODE" = "keyevent" ] || [ "$MODE" = "keyevent2" ]; then
  echo "\$ adb -s $S shell input keyevent 126   # KEYCODE_MEDIA_PLAY"
  adb -s $S shell input keyevent 126; echo "exit=$?"
fi
if [ "$MODE" = "keyevent2" ]; then
  sleep 5
  echo "\$ adb -s $S shell input keyevent 126   # 第二次（首页可能先要进入播放器）"
  adb -s $S shell input keyevent 126; echo "exit=$?"
fi

echo "## 轮询等待 state=3（最多 60s）"
STATE=""
for i in $(seq 1 20); do
  sleep 3
  STATE=$(adb -s $S shell dumpsys media_session 2>/dev/null | grep -A14 "package=$PKG" | grep -m1 "state=PlaybackState" | sed 's/.*state=PlaybackState {//; s/,.*//')
  echo "  t=$((i*3))s  state=$STATE"
  [ "$STATE" = "state=3" ] && break
done
echo "## 最终 state = ${STATE:-<未取到>}"
if [ "$STATE" != "state=3" ]; then
  echo "## ⚠️ 未在播放状态，证据无效（state=${STATE:-none}）"
fi

echo
echo "################ (1) dumpsys media_session   [$(date -Is)]"
echo "\$ adb -s $S shell dumpsys media_session"
adb -s $S shell dumpsys media_session > "$OUT/dumpsys-media-session-$TAG.txt" 2>&1
echo "  -> dumpsys-media-session-$TAG.txt exit=$?  bytes=$(stat -c%s "$OUT/dumpsys-media-session-$TAG.txt")"
echo "  -- Media button session 原文:"
grep -i "Media button session" "$OUT/dumpsys-media-session-$TAG.txt"
echo "  -- 本包会话原文:"
grep -B6 -A16 "package=$PKG" "$OUT/dumpsys-media-session-$TAG.txt" | head -60
echo "  -- uid/packages 行:"
grep -E "^  uid=.*packages=" "$OUT/dumpsys-media-session-$TAG.txt"

echo
echo "################ (2) dumpsys notification --noredact  [$(date -Is)]"
echo "\$ adb -s $S shell dumpsys notification --noredact"
adb -s $S shell dumpsys notification --noredact > "$OUT/dumpsys-notification-$TAG.txt" 2>&1
echo "  -> dumpsys-notification-$TAG.txt exit=$? bytes=$(stat -c%s "$OUT/dumpsys-notification-$TAG.txt")"
echo "  -- 本包通知（含 MediaStyle/style 关键字）:"
grep -nE "$PKG|android.app.Notification\\\$MediaStyle|MediaStyle" "$OUT/dumpsys-notification-$TAG.txt" | head -30

echo
echo "################ (3) logcat -d 过滤  [$(date -Is)]"
echo "\$ adb -s $S logcat -d | grep -iE \"HwMediaSession|MediaControlUtils|media white list|Media button session|MediaSessionService\""
adb -s $S logcat -d 2>/dev/null | grep -iE "HwMediaSession|MediaControlUtils|media white list|Media button session|MediaSessionService" > "$OUT/logcat-hwmediasession-$TAG.txt" 2>&1
echo "  -> logcat-hwmediasession-$TAG.txt 行数=$(wc -l < "$OUT/logcat-hwmediasession-$TAG.txt")"
cat "$OUT/logcat-hwmediasession-$TAG.txt"

echo
echo "################ (4) settings list global | grep -i media_button_receiver  [$(date -Is)]"
echo "\$ adb -s $S shell settings list global | grep -i media_button_receiver"
adb -s $S shell settings list global 2>/dev/null | grep -i media_button_receiver
echo "exit=$?  （exit=1 表示无该设置项）"
echo "\$ adb -s $S shell settings list global | grep -iE \"media|music\""
adb -s $S shell settings list global 2>/dev/null | grep -iE "media|music"

echo
echo "################ (5) 控制中心 uiautomator + 截图  [$(date -Is)]"
echo "\$ adb -s $S shell cmd statusbar expand-settings"
adb -s $S shell cmd statusbar expand-settings; echo "exit=$?"
sleep 3
echo "\$ adb -s $S shell uiautomator dump /sdcard/ui.xml"
adb -s $S shell uiautomator dump /sdcard/ui.xml; echo "exit=$?"
adb -s $S pull /sdcard/ui.xml "$OUT/uiautomator-$TAG.xml" 2>&1
echo "  -> uiautomator-$TAG.xml bytes=$(stat -c%s "$OUT/uiautomator-$TAG.xml" 2>/dev/null)"
echo "  -- 媒体卡文本节点（content-desc / text，过滤空值）:"
grep -oE '(content-desc|text)="[^"]+"' "$OUT/uiautomator-$TAG.xml" 2>/dev/null | grep -vE '="(null)?"$' | sort -u | head -40
echo "\$ adb -s $S exec-out screencap -p > screenshots/$TAG-controlcenter.png"
adb -s $S exec-out screencap -p > "$OUT/screenshots/$TAG-controlcenter.png" 2>/dev/null
echo "  -> screenshots/$TAG-controlcenter.png bytes=$(stat -c%s "$OUT/screenshots/$TAG-controlcenter.png")"
echo "\$ adb -s $S shell cmd statusbar collapse"
adb -s $S shell cmd statusbar collapse

echo
echo "## 结束：force-stop $PKG"
adb -s $S shell am force-stop "$PKG"
echo "############ capture 结束 $(date -Is)"
} > "$OUT/tmp/capture-$TAG.log" 2>&1
echo "wrote $OUT/tmp/capture-$TAG.log"
tail -5 "$OUT/tmp/capture-$TAG.log"
