#!/usr/bin/env bash
# 用 logcat 的 "Displayed <pkg>/.MainActivity: +NNNms" 行测被测应用**自身**首帧耗时。
# 与 macrobenchmark StartupTimingMetric 同量级（都是 activity 首帧），
# 且不受顶层权限对话框干扰（am start -W 会返回对话框那条记录）。
# 只做 force-stop + start + logcat -d，不改任何持久设置。
set -u
SER="${1:?serial}"; OUT="${2:?outdir}"; N="${3:-5}"
ADB="${ADB:-/usr/bin/adb}"
F="$OUT/$SER-displayed.txt"; : > "$F"
echo "== displayed_probe.sh serial=$SER n=$N date=$(date -Is) ==" >> "$F"
for i in $(seq 1 "$N"); do
  {
    echo "--- run $i"
    timeout 30 "$ADB" -s "$SER" logcat -c 2>&1
    timeout 60 "$ADB" -s "$SER" shell am force-stop com.takahashirinta.ncrust 2>&1
    sleep 2
    timeout 60 "$ADB" -s "$SER" shell am start -n com.takahashirinta.ncrust/.MainActivity 2>&1
    sleep 6
    echo "\$ logcat -d | grep -E 'Displayed|Fully drawn|GrantPermissions|permissioncontroller'"
    timeout 60 "$ADB" -s "$SER" logcat -d 2>&1 | grep -E "Displayed com.takahashirinta|Fully drawn|GrantPermissionsActivity|permissioncontroller.*ncrust" | tail -10
    echo "\$ 当前前台窗口"
    timeout 60 "$ADB" -s "$SER" shell dumpsys window 2>&1 | grep -E "mCurrentFocus|mFocusedApp" | head -3
    sleep 2
  } >> "$F"
done
timeout 60 "$ADB" -s "$SER" shell am force-stop com.takahashirinta.ncrust >> "$F" 2>&1
echo "== done ==" >> "$F"
echo "wrote $F"
