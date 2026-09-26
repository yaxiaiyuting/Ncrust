#!/usr/bin/env bash
# v2.5.5 macrobenchmark 前置条件探针（只读为主；动画比例改 0 后立即恢复原值）
# 用法: probe_device.sh <serial> <outdir>
# 所有输出原样落到 <outdir>/<serial>.txt，不做任何加工。
set -u

SER="${1:?serial}"
OUT="${2:?outdir}"
ADB="${ADB:-/usr/bin/adb}"
mkdir -p "$OUT"
F="$OUT/$SER.txt"
: > "$F"

cap() { # cap <label> <adb args...>
  local label="$1"; shift
  {
    echo "----- [$label] \$ adb -s $SER $*"
    timeout 90 "$ADB" -s "$SER" "$@" 2>&1
    echo "[exit=$?]"
  } >> "$F"
}

echo "== probe_device.sh serial=$SER date=$(date -Is) adb=$("$ADB" version | head -1) ==" >> "$F"

cap "getprop/basic" shell getprop | grep -E '\[ro\.(build\.(version\.sdk|version\.release|type|tags|fingerprint|characteristics|version\.security_patch)|debuggable|secure|product\.(cpu\.abi|model|manufacturer|device|board))\]|\[persist\.sys\.(usb\.config|timezone)\]'
cap "getprop/abi_list" shell getprop ro.product.cpu.abilist
cap "shell/id" shell id
cap "shell/getenforce" shell getenforce
cap "adb-root" root
cap "shell/id-after-root" shell id
cap "adb-unroot" unroot

# ---- 已安装包 ----
cap "pm/version" shell dumpsys package com.takahashirinta.ncrust | grep -E 'versionCode|versionName|codePath|pkgFlags|flags=|dataDir|primaryCpuAbi|lastUpdateTime|firstInstallTime|installerPackageName' | head -40
cap "pm/debuggable_flag" shell dumpsys package com.takahashirinta.ncrust | grep -i -E 'DEBUGGABLE|debuggable=true'
cap "pm/list-benchmark-pkgs" shell pm list packages | grep -i -E 'ncrust|benchmark'
cap "pm/list-instrumentation" shell pm list instrumentation
cap "pm/path" shell pm path com.takahashirinta.ncrust

# ---- 动画比例（读 -> 改 -> 读回 -> 恢复 -> 读回验证）----
ORIG_W="$(timeout 30 "$ADB" -s "$SER" shell settings get global window_animation_scale 2>&1 | tr -d '\r')"
ORIG_T="$(timeout 30 "$ADB" -s "$SER" shell settings get global transition_animation_scale 2>&1 | tr -d '\r')"
ORIG_A="$(timeout 30 "$ADB" -s "$SER" shell settings get global animator_duration_scale 2>&1 | tr -d '\r')"
{
  echo "----- [animations/before]"
  echo "window_animation_scale     = [$ORIG_W]"
  echo "transition_animation_scale = [$ORIG_T]"
  echo "animator_duration_scale    = [$ORIG_A]"
} >> "$F"

cap "animations/put-0" shell settings put global window_animation_scale 0
timeout 30 "$ADB" -s "$SER" shell settings put global transition_animation_scale 0 >>"$F" 2>&1
timeout 30 "$ADB" -s "$SER" shell settings put global animator_duration_scale 0 >>"$F" 2>&1
{
  echo "----- [animations/while-zero]"
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do
    echo "$k = [$(timeout 30 "$ADB" -s "$SER" shell settings get global "$k" 2>&1 | tr -d '\r')]"
  done
} >> "$F"

restore_one() { # restore_one <key> <orig>
  local key="$1" orig="$2"
  if [ "$orig" = "null" ] || [ -z "$orig" ]; then
    timeout 30 "$ADB" -s "$SER" shell settings delete global "$key" >>"$F" 2>&1
    echo "restore $key: original was null -> settings delete global $key" >> "$F"
  else
    timeout 30 "$ADB" -s "$SER" shell settings put global "$key" "$orig" >>"$F" 2>&1
    echo "restore $key: settings put global $key $orig" >> "$F"
  fi
}
{
  echo "----- [animations/restore]"
} >> "$F"
restore_one window_animation_scale "$ORIG_W"
restore_one transition_animation_scale "$ORIG_T"
restore_one animator_duration_scale "$ORIG_A"
{
  echo "----- [animations/after-restore]"
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do
    echo "$k = [$(timeout 30 "$ADB" -s "$SER" shell settings get global "$k" 2>&1 | tr -d '\r')]"
  done
  echo "[restore-check] window: [$ORIG_W] -> [$ORIG_W], transition: [$ORIG_T], animator: [$ORIG_A]  (上面三行必须与 before 一致)"
} >> "$F"

# ---- 锁屏 / 屏幕 / 电源 ----
cap "window/dreaming-lockscreen" shell dumpsys window | grep -i -E 'mDreamingLockscreen|mShowingLockscreen|mAwake|mScreenOnFully'
cap "window/focus" shell dumpsys window | grep -i -E 'mCurrentFocus|mFocusedApp' | head -10
cap "window/screen-state" shell dumpsys window policy | grep -i -E 'screenState|mScreenOn|Keyguard|showing=' | head -20
cap "power/wakefulness" shell dumpsys power | grep -i -E 'mWakefulness|mIsPowered|mScreenBrightness|mHoldingDisplay'
cap "deviceidle" shell dumpsys deviceidle
cap "settings/screen_off_timeout" shell settings get system screen_off_timeout
cap "settings/stay_on_while_plugged_in" shell settings get global stay_on_while_plugged_in
cap "settings/airplane_mode_on" shell settings get global airplane_mode_on
cap "settings/development_settings_enabled" shell settings get global development_settings_enabled
cap "battery" shell dumpsys battery
cap "batterystats/charge" shell dumpsys batterystats --charged | head -20
cap "thermal/thermalservice" shell dumpsys thermalservice
cap "thermal/zone0" shell cat /sys/class/thermal/thermal_zone0/temp

# ---- 屏幕 / 硬件 ----
cap "wm/size" shell wm size
cap "wm/density" shell wm density
cap "cpuinfo/cores" shell cat /proc/cpuinfo | grep -E -c 'processor'
cap "cpuinfo/hardware" shell cat /proc/cpuinfo | grep -i -E 'Hardware|model name' | head -4
cap "meminfo" shell cat /proc/meminfo | head -4
cap "df/data" shell df -h /data
cap "df/sdcard" shell df -h /storage/emulated/0
cap "uptime" shell uptime

# ---- macrobenchmark 依赖的 shell 能力 ----
cap "gfxinfo/framestats-head" shell dumpsys gfxinfo com.takahashirinta.ncrust framestats | head -20
cap "cmd-package-usage" shell cmd package
cap "cmd-package-compile-bogus" shell cmd package compile -m speed -f com.takahashirinta.nonexistentprobe
cap "cmd-activity-kill" shell am kill com.takahashirinta.nonexistentprobe
cap "perfetto-present" shell which perfetto
cap "atrace-present" shell which atrace
cap "trace-categories" shell atrace --list_categories | head -30
cap "settrace-readonly" shell getprop persist.traced.enable
cap "tracefs" shell ls -l /sys/kernel/tracing 2>&1 | head -5
cap "logcat-clear" logcat -c

echo "== done ==" >> "$F"
echo "wrote $F"
