#!/usr/bin/env bash
# v2.5.6 · P0：baseline profile 的**冷启动 A/B 测量**（release 包，API 24 真机）。
#
# ## 为什么不用 :benchmark 模块的 StartupBenchmark
#
# v2.5.6 实测：`StartupBenchmark`（MacrobenchmarkRule + CompilationMode.Partial）
# 在 S6 / API 24 上**挂死** —— 19:33:08 起 `AndroidJUnitRunner` 打完
# `Output Directory: …` 之后 24 分钟零输出，进程 `S (sleeping)` 在 `SyS_epoll_wait`，
# `mResumedActivity` 是桌面上而不是被测应用。`am instrument` 不打印增量输出，
# 所以它在报告里表现为「一条永远不结束的命令」，而 v2.5.4/v2.5.5 也各有一次
# 「跑不完」—— 这是本仓库第三次撞上同一个形状。
#
# 本脚本因此走**平台自带的两条命令**，它们都在 API 24 上可用且各自可自证：
#
#   cmd package compile -m <filter> -f <pkg>   # 确定性地设置 ART 编译状态
#   am start -W -n <pkg>/.MainActivity         # 冷启动，打印 TotalTime
#
# ## 为什么这样能隔离「profile 内容」这一个变量
#
# `-m speed-profile` 让 dex2oat 按**已安装的 profile** 编译（profile 由
# `androidx.profileinstaller` 在 `INSTALL_PROFILE` 广播时写进 ART profile 目录）。
# 于是「只换 APK 里的 assets/dexopt/baseline.prof」就等于只换 profile 内容。
#
# 另外跑 `speed` 作对照（全量 AOT，**不看 profile**）。本仓库的实测值：
#
#   speed           ~490ms   （profile-independent 上界）
#   speed-profile   ~597ms   （按 profile 编译）
#
# 两者相差 ~107ms（18%）⇒ 这个协议**确实区分了编译档**，不会是「两次都等价于同一档」。
#
# ⚠️ 更正一处（本脚本第一版写错、已实测纠正）：API 24 **没有** `verify` / `quicken`
# 这两个 filter（实测 `Error: "verify" is not a valid compilation filter.`），
# 所以拿不到「完全不 AOT」的下界。API 24 只接受 `speed` / `speed-profile` / `everything`。
# 少一个下界不影响结论：A/B 两组只差 profile 内容，
# 若 `speed-profile` 根本不读 profile，两组的数字会**完全相同** ——
# 也就是说这个 A/B **自带反证**，不需要外部下界。
#
# 用法：
#   coldstart-ab.sh <label> <iterations> [compile-mode ...]
# 例：
#   coldstart-ab.sh A-handwritten 10 speed-profile speed
set -uo pipefail

PKG="com.takahashirinta.ncrust"
ACTIVITY="$PKG/.MainActivity"
ADB="${ADB:-adb}"
SERIAL="${ANDROID_SERIAL:-}"
if [ -n "$SERIAL" ]; then ADB="$ADB -s $SERIAL"; fi

LABEL="${1:?用法: coldstart-ab.sh <label> <iterations> [compile-mode ...]}"
ITERS="${2:?用法: coldstart-ab.sh <label> <iterations> [compile-mode ...]}"
shift 2
MODES=("$@")
if [ "${#MODES[@]}" -eq 0 ]; then MODES=(speed-profile); fi

echo "### label=$LABEL iterations=$ITERS modes=${MODES[*]}"
echo "### device=$($ADB shell getprop ro.product.model | tr -d '\r') api=$($ADB shell getprop ro.build.version.sdk | tr -d '\r')"
echo "### installed=$($ADB shell dumpsys package $PKG | grep -m1 versionName | tr -d '\r')"
echo "### animations: window=$($ADB shell settings get global window_animation_scale | tr -d '\r') transition=$($ADB shell settings get global transition_animation_scale | tr -d '\r') animator=$($ADB shell settings get global animator_duration_scale | tr -d '\r')"

for mode in "${MODES[@]}"; do
  echo
  echo "--- compile mode: $mode ---"
  # 1) 把 APK 里的 baseline profile 装进 ART profile 目录（若接收器不存在会打印错误，
  #    此时仍然继续 —— 那一档就退化成「无 profile」，是有意义的对照）。
  $ADB shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE \
    "$PKG/androidx.profileinstaller.ProfileInstallReceiver" >/dev/null 2>&1
  sleep 2
  # 2) 确定性编译。
  echo "compile: $($ADB shell cmd package compile -m "$mode" -f "$PKG" 2>&1 | tr -d '\r' | head -1)"
  # 3) 冷启动 N 次。
  for i in $(seq 1 "$ITERS"); do
    $ADB shell am force-stop "$PKG" >/dev/null 2>&1
    sleep 1
    out="$($ADB shell am start -W -n "$ACTIVITY" 2>&1 | tr -d '\r')"
    total="$(printf '%s\n' "$out" | sed -n 's/^TotalTime: *//p' | head -1)"
    wait_t="$(printf '%s\n' "$out" | sed -n 's/^WaitTime: *//p' | head -1)"
    if [ -z "$total" ]; then
      echo "iter=$i FAILED"
      printf '%s\n' "$out" | sed 's/^/    | /'
    else
      echo "iter=$i TotalTime=${total}ms WaitTime=${wait_t}ms"
    fi
    sleep 1
  done
done
echo
echo "### done label=$LABEL"
