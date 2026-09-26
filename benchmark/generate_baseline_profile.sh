#!/usr/bin/env bash
# v2.5.6 · P0：**生成** baseline profile 并落到 app/src/main/baseline-prof.txt。
#
# ## 为什么需要这个脚本（而不是「记得手动跑一下」）
#
# profile 是**代码形状的函数**：类被改名、方法被内联、依赖换版本，旧 profile 里的条目
# 就指向不存在的方法。AGP/R8 会把解析不到的条目丢掉（不报错），
# 于是「profile 过期」在构建日志里**完全安静**，只在冷启动数字上慢慢退化。
# 所以生成必须是一条**一条命令可复现**的路径，而不是一段口口相传的 adb 操作。
#
# ## 前置条件
#
# - 一台连着 adb 的设备，**且必须满足 `BaselineProfileRule` 的 API 前提**。
#   v2.5.6 实测（S6 / API 24）：
#
#     java.lang.IllegalArgumentException: Baseline Profile collection requires API 33+,
#     or a rooted device running API 28 or higher and rooted adb session (via `adb root`).
#
#   ⇒ **采样设备不能是 API 24 的 S6**。本仓库可用的是 PLC110（API 36）。
#   这条限制不影响 profile 的用途：profile 是**方法命中集合**，与 API 级别无关
#   （同一份 dex），API 36 上采到的条目在 API 24~30 上同样成立 ——
#   但「在哪台机器上采的」必须写进证据，所以本脚本会把它打出来；
# - 同一台设备上**不能有第三方无障碍服务占用 UiAutomation**
#   （v2.5.5 在 PLC110 上撞过 `UiAutomationService … already registered!`，
#   根因是 Scene / GKD 两个无障碍服务）；
# - 设备已装 release 包并**保持登录态**（未登录时旅程 5/6 会 SKIP，
#   脚本会把覆盖情况打进日志并给出警告 —— 不伪造覆盖）；
# - 设备已装 `:benchmark` 的 debug 包（本脚本负责装）。
#
# ## 流程
#
#   1. `:app:assembleRelease` 并安装（**release**：profile 必须按 R8 之后的 dex 采样，
#      debug 包的 dex 与发布产物不同，采样出来的 profile 对不上）；
#   2. `:benchmark:assembleDebug` 并安装；
#   3. 关系统动画（采样期间要稳定；退出时还原）；
#   4. `am instrument` 跑 BaselineProfileGenerator；
#   5. 从设备拉回生成的 profile；
#   6. 覆盖 `app/src/main/baseline-prof.txt`（先备份旧文件到 `*.bak`）；
#   7. 打印新旧行数对比 + 覆盖率摘要（来自 logcat 的 `NcrustProfileGen` 行）。
#
# 用法：
#   benchmark/generate_baseline_profile.sh [--iterations N] [--dry-run]
set -uo pipefail

cd "$(dirname "$0")/.."

ADB="${ADB:-adb}"
SERIAL="${ANDROID_SERIAL:-}"
if [ -n "$SERIAL" ]; then ADB="$ADB -s $SERIAL"; fi

PACKAGE="com.takahashirinta.ncrust"
BENCH_PKG="$PACKAGE.benchmark"
BENCH_APK="benchmark/build/outputs/apk/debug/benchmark-debug.apk"
APP_APK="app/build/outputs/apk/release/app-release.apk"
TARGET_PROFILE="app/src/main/baseline-prof.txt"
GENERATOR_CLASS="com.takahashirinta.ncrust.benchmark.BaselineProfileGenerator"

ITERATIONS=""
DRY_RUN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --iterations) ITERATIONS="${2:?}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

echo "== 设备 =="
$ADB shell getprop ro.product.model | tr -d '\r'
$ADB shell getprop ro.build.version.sdk | tr -d '\r'

echo "== 构建 release（profile 必须按 R8 之后的 dex 采样） =="
./gradlew :app:assembleRelease --console=plain -q || exit 1

echo "== 安装 release =="
$ADB install -r "$APP_APK" || exit 1

echo "== 构建并安装 benchmark =="
./gradlew :benchmark:assembleDebug --console=plain -q || exit 1
$ADB install -r -t "$BENCH_APK" || exit 1

if [ "$DRY_RUN" = "1" ]; then
  echo "== --dry-run：到此为止（不采样、不改 $TARGET_PROFILE） =="
  exit 0
fi

# ---------- 系统动画：关掉并保证还原 ----------
ORIG_WINDOW="$($ADB shell settings get global window_animation_scale | tr -d '\r')"
ORIG_TRANSITION="$($ADB shell settings get global transition_animation_scale | tr -d '\r')"
ORIG_ANIMATOR="$($ADB shell settings get global animator_duration_scale | tr -d '\r')"
restore_one() {
  if [ "$2" = "null" ] || [ -z "$2" ]; then
    $ADB shell settings delete global "$1" >/dev/null 2>&1 || true
  else
    $ADB shell settings put global "$1" "$2" >/dev/null 2>&1 || true
  fi
}
# 与 run_benchmark.sh 同一套还原（含「原值是 null 时要 delete 而不是 put 0」那条教训）。
restore_animations() {
  restore_one window_animation_scale "$ORIG_WINDOW"
  restore_one transition_animation_scale "$ORIG_TRANSITION"
  restore_one animator_duration_scale "$ORIG_ANIMATOR"
  echo "== 动画已还原: window=$($ADB shell settings get global window_animation_scale | tr -d '\r') transition=$($ADB shell settings get global transition_animation_scale | tr -d '\r') animator=$($ADB shell settings get global animator_duration_scale | tr -d '\r')"
}
trap restore_animations EXIT

echo "== 关闭系统动画（采样期间；退出时还原） =="
$ADB shell settings put global window_animation_scale 0
$ADB shell settings put global transition_animation_scale 0
$ADB shell settings put global animator_duration_scale 0

echo "== 采样 =="
$ADB logcat -c >/dev/null 2>&1 || true
EXTRA=()
if [ -n "$ITERATIONS" ]; then EXTRA=(-e ncrust.bench.iterations "$ITERATIONS"); fi
$ADB shell am instrument -w -e class "$GENERATOR_CLASS" "${EXTRA[@]}" \
  "$BENCH_PKG/androidx.test.runner.AndroidJUnitRunner"
INSTR_RC=$?

# ---------- 旅程覆盖（唯一可信来源：采样器自己的日志） ----------
echo
echo "== 旅程覆盖（tag NcrustProfileGen） =="
$ADB logcat -d 2>/dev/null | grep "NcrustProfileGen" | tail -20 || true
OK_COUNT="$($ADB logcat -d 2>/dev/null | grep -c -- "-> OK" || true)"
echo "OK 计数（粗略）: ${OK_COUNT:-0}"
if [ "${OK_COUNT:-0}" = "0" ]; then
  echo "!! 一条旅程都没有 OK —— 不要拿这份 profile 覆盖 $TARGET_PROFILE。" >&2
  echo "!! （未登录是常见原因：旅程 5「播放开始」/6「切歌」需要收藏里有歌。）" >&2
  exit 4
fi

# ---------- 拉回生成的 profile ----------
# BaselineProfileRule 把结果写进 benchmark 包的外部存储；文件名形如
# `<ClassName>_<method>-baseline-prof.txt`（`outputFilePrefix` 参与命名）。
echo
echo "== 定位生成的 profile =="
REMOTE_DIRS="/storage/emulated/0/Android/media/$BENCH_PKG /storage/emulated/0/Android/data/$BENCH_PKG/files"
FOUND=""
for d in $REMOTE_DIRS; do
  hit="$($ADB shell "ls $d 2>/dev/null" | tr -d '\r' | grep -i "baseline-prof" | head -1)"
  if [ -n "$hit" ]; then FOUND="$d/$hit"; break; fi
done
if [ -z "$FOUND" ]; then
  echo "!! 设备上找不到生成的 baseline-prof 文件。已查: $REMOTE_DIRS" >&2
  echo "!! instrumentation 返回码 = $INSTR_RC" >&2
  exit 5
fi
echo "找到: $FOUND"

TMP="$(mktemp -d)"
$ADB pull "$FOUND" "$TMP/generated-baseline-prof.txt" >/dev/null || exit 6
NEW_LINES="$(wc -l < "$TMP/generated-baseline-prof.txt")"
OLD_LINES=0
[ -f "$TARGET_PROFILE" ] && OLD_LINES="$(wc -l < "$TARGET_PROFILE")"

echo
echo "== 对比 =="
echo "旧 $TARGET_PROFILE: $OLD_LINES 行"
echo "新（生成）:         $NEW_LINES 行"
# 方法级条目形如 `HSPLcom/foo/Bar;->method()V`；class-only 形如 `Lcom/foo/Bar;`。
NEW_METHODS="$(grep -c '^HSPL' "$TMP/generated-baseline-prof.txt" || true)"
echo "新文件里的方法级条目（HSPL…）: ${NEW_METHODS:-0}"

if [ "${NEW_LINES:-0}" -lt 10 ]; then
  echo "!! 生成的 profile 行数过少（$NEW_LINES），判定为无效，不覆盖。" >&2
  exit 7
fi

cp "$TARGET_PROFILE" "$TARGET_PROFILE.bak" 2>/dev/null || true
cp "$TMP/generated-baseline-prof.txt" "$TARGET_PROFILE"
echo
echo "== 已覆盖 $TARGET_PROFILE（旧文件备份为 $TARGET_PROFILE.bak） =="
echo "下一步：./gradlew :app:assembleRelease，然后"
echo "  docs/verification/v2.5.6/verification/coldstart-ab.sh <label> 10 speed-profile speed"
echo "对比 profile 生成前后的冷启动。"
