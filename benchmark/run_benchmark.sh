#!/usr/bin/env bash
# 生产式性能测量: 对"已安装的 release app"做冷启动/滚动帧率基线。
#
# 与 connectedCheck 的区别: 不重装、不卸载被测应用。测的是真实使用状态
# (登录态、Coil 磁盘缓存、ART profile 都在), debug 包与 release 包冷启动
# 差 2~7 倍(R8 + 非 debuggable), 只有 release 数字才有参考价值。
#
# 用法:
#   benchmark/run_benchmark.sh [startup|scroll|settings|expand|all]
#
# 流程:
#   1. 构建 release 并安装(若系统无 keystore.properties, 用临时 key 签,
#      仅用于本机测试, 不影响正式签名配置)
#   2. 打开 app 扫码登录一次(登录态保存在设备, 不再被卸载)
#   3. 正常使用几分钟让封面缓存与 ART profile 热起来
#   4. 跑本脚本测量。迭代间 app 数据不动。
set -euo pipefail

cd "$(dirname "$0")/.."

ADB="${ADB_BINARY:-adb}"
BENCH_APK="benchmark/build/outputs/apk/debug/benchmark-debug.apk"
APP_APK="app/build/outputs/apk/release/app-release-unsigned.apk"
PACKAGE="com.takahashirinta.ncrust"
BENCH_PKG="$PACKAGE.benchmark"
TARGET="${1:-all}"
ADB_BIN="$(command -v "$ADB")"

# 定位 SDK build-tools（跨平台）：优先 ANDROID_HOME/SDK_ROOT, 其次 local.properties 的 sdk.dir
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK_DIR" ] && [ -f local.properties ]; then
  SDK_DIR="$(grep -E '^sdk\.dir=' local.properties | head -1 | cut -d= -f2-)"
fi
SDK_BUILD_TOOLS=""
if [ -n "$SDK_DIR" ] && [ -d "$SDK_DIR/build-tools" ]; then
  SDK_BUILD_TOOLS="$(ls -d "$SDK_DIR"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
fi
APKSIGNER="apksigner"
if [ -n "$SDK_BUILD_TOOLS" ]; then
  if [ -x "${SDK_BUILD_TOOLS}apksigner" ]; then
    APKSIGNER="${SDK_BUILD_TOOLS}apksigner"
  elif [ -f "${SDK_BUILD_TOOLS}apksigner.bat" ]; then
    APKSIGNER="${SDK_BUILD_TOOLS}apksigner.bat"
  fi
fi

# ---------- release 构建与安装 ----------
install_release() {
  echo "== 构建 release =="
  ./gradlew :app:assembleRelease --console=plain -q

  local signed_apk="$APP_APK"
  if [ -f keystore.properties ]; then
    echo "== 项目有正式签名, assembleRelease 已产出签名包 =="
    signed_apk="app/build/outputs/apk/release/app-release.apk"
  else
    echo "== 无 keystore.properties, 用临时 key 签名(仅本机测量用) =="
    local ks="$PWD/build/benchmark-sign.jks"
    [ -f "$ks" ] || keytool -genkeypair -keystore "$ks" -alias bench -keyalg RSA \
      -keysize 2048 -validity 10000 -storepass bench123 -keypass bench123 \
      -dname "CN=NcrustBenchmark" >/dev/null 2>&1
    "$APKSIGNER" sign --ks "$ks" --ks-pass pass:bench123 \
      --key-pass pass:bench123 --out /tmp/app-release-bench.apk "$APP_APK"
    signed_apk=/tmp/app-release-bench.apk
  fi

  echo "== 安装 release =="
  if ! $ADB install "$signed_apk" 2>/tmp/install_err.txt; then
    if grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE" /tmp/install_err.txt; then
      echo "!! 已有签名不同的旧版, 卸载后重装(登录态会清, 需重新扫码)"
      $ADB uninstall "$PACKAGE" >/dev/null
      $ADB install "$signed_apk"
    else
      cat /tmp/install_err.txt; exit 1
    fi
  fi
  echo "!! 新装/重装后请在手机上打开 app 扫码登录一次, 并用几分钟让缓存热起来"
}

# ---------- 测量 ----------
ensure_bench_apk() {
  echo "== 构建并安装 benchmark APK =="
  ./gradlew :benchmark:assembleDebug --console=plain -q
  $ADB install -r -t "$BENCH_APK" >/dev/null
}

run() {
  local cls="$1"
  echo "== $cls =="
  # v2.5.5 · F：迭代数与编译模式走 `am instrument -e`（见 benchmark 的 BenchArgs）。
  # 默认与 v2.5.4 相同（各基准自己的迭代数 / CompilationMode.DEFAULT）；
  # 换设备复跑时用 BENCH_ITERATIONS / BENCH_COMPILATION 覆盖，例如
  #   BENCH_ITERATIONS=3 BENCH_COMPILATION=ignore benchmark/run_benchmark.sh startup
  # 这样「降迭代」不需要改源码、也不会在一轮之后没人记得当时传了什么
  # （实际取值由 BenchArgs 打进 logcat，tag NcrustBench）。
  local extra=()
  if [ -n "${BENCH_ITERATIONS:-}" ]; then
    extra+=(-e ncrust.bench.iterations "$BENCH_ITERATIONS")
  fi
  if [ -n "${BENCH_COMPILATION:-}" ]; then
    extra+=(-e ncrust.bench.compilation "$BENCH_COMPILATION")
  fi
  if [ "${#extra[@]}" -gt 0 ]; then
    echo "   参数: ${extra[*]}"
  fi
  # self-instrumenting 模块的 instrumentation 组件没有 .test 后缀(target 是自己)
  $ADB shell am instrument -w -e class "$cls" "${extra[@]}" \
    "$BENCH_PKG/androidx.test.runner.AndroidJUnitRunner"
}

echo "== 检查被测应用 =="
if ! $ADB shell pm list packages | grep -q "$PACKAGE"; then
  install_release
fi

# v2.5.5 · F：**硬断言**被测应用不是 debuggable。
#
# 铁律 16「性能验证必须用 release 包」在 v2.5.4 之前只是一句警告 —— 而一段
# 「被忽略的警告」等于没有这条规则（v2.5.0 的遗留清单里就有一次真实的错误示范：
# 拿 debug 包的 1223ms/1711ms 去讨论「性能」）。
# 而且 benchmark 模块**没有任何结构性手段**保证这件事：AGP 8.5.1 的 `com.android.test`
# DSL 只有 `targetProjectPath`，没有 `targetVariant`/`signingConfig`，
# `:benchmark:assembleDebug` 也不构建被测 app ⇒ 唯一的保证就是这个运行时断言。
PKG_DUMP="$($ADB shell dumpsys package "$PACKAGE" 2>/dev/null || true)"
if echo "$PKG_DUMP" | grep -q "DEBUGGABLE"; then
  # pkgFlags 里的大小写在不同 API 上不一致（[ DEBUGGABLE ] / flags=[ DEBUGGABLE ]），
  # 所以用不区分大小写的匹配，再单独排除掉 "not debuggable" 这类描述。
  if echo "$PKG_DUMP" | grep -qiE "flags=\[[^]]*debuggable|\bDEBUGGABLE\b"; then
    if [ "${ALLOW_DEBUG_BUILD:-0}" = "1" ]; then
      echo "!! ALLOW_DEBUG_BUILD=1：明知是 debuggable 包仍然测量 —— 数字**不得**写进发布说明当基线。"
    else
      echo "!! 被测应用是 debuggable 版本 —— 性能验证必须用 release 包（铁律 16）。" >&2
      echo "!! 先跑：benchmark/run_benchmark.sh install_release（或 adb install -r 正式签名的 release APK）" >&2
      echo "!! 确实要用 debug 包定位问题：ALLOW_DEBUG_BUILD=1 重跑，但那些数字不能当基线。" >&2
      exit 3
    fi
  fi
fi

# v2.5.5 · F：打印**实际生效**的版本号，让报告里的「测的是哪个包」可自证。
# debug 与 release 的 versionName 相同，所以还要打 flags —— 两者合起来才够。
echo "== 被测应用 =="
$ADB shell dumpsys package "$PACKAGE" | grep -E "versionCode=|versionName=" | head -2 || true

echo "== 系统动画 =="
# 保存原始值, 退出时(含 Ctrl-C / 出错)还原, 避免脚本异常结束把设备动画永久关掉。
ORIG_WINDOW_ANIM="$($ADB shell settings get global window_animation_scale | tr -d '\r')"
ORIG_TRANSITION_ANIM="$($ADB shell settings get global transition_animation_scale | tr -d '\r')"
ORIG_ANIMATOR_ANIM="$($ADB shell settings get global animator_duration_scale | tr -d '\r')"
# v2.5.5 · F：**原值为 null 时要 delete，不是"什么都不做"**。
#
# v2.5.4 的写法是 `[ "$X" = "null" ] || $ADB shell settings put ...`：
# 当原值确实是 `null`（= 这个 setting 从来没被写过，PCL110 / Cuttlefish 实测就是）
# 时整行短路 ⇒ **0 被留在设备上**，而这个脚本下一次跑「scroll / expand」时
# 看到的是一个动画被永久关掉的设备（对 ExpandPlayer 是致命污染）。
# 探针是在逐台设备比对动画原值时才发现的 —— 它的表现是「下一轮数字莫名变好」。
restore_one_anim() {
  local key="$1" val="$2"
  if [ "$val" = "null" ] || [ -z "$val" ]; then
    $ADB shell settings delete global "$key" >/dev/null 2>&1 || true
  else
    $ADB shell settings put global "$key" "$val" >/dev/null 2>&1 || true
  fi
}
restore_animations() {
  restore_one_anim window_animation_scale "$ORIG_WINDOW_ANIM"
  restore_one_anim transition_animation_scale "$ORIG_TRANSITION_ANIM"
  restore_one_anim animator_duration_scale "$ORIG_ANIMATOR_ANIM"
  # 自证：还原之后回读一次，把实际值打进日志（`null` = 已删除）。
  local after_w after_t after_a
  after_w="$($ADB shell settings get global window_animation_scale | tr -d '\r')"
  after_t="$($ADB shell settings get global transition_animation_scale | tr -d '\r')"
  after_a="$($ADB shell settings get global animator_duration_scale | tr -d '\r')"
  echo "== 动画已还原: window=$after_w transition=$after_t animator=$after_a (原值 $ORIG_WINDOW_ANIM/$ORIG_TRANSITION_ANIM/$ORIG_ANIMATOR_ANIM)"
}
trap restore_animations EXIT

# 只有 startup 需要关系统动画(避免窗口转场动画混入启动帧)。
# scroll / expand 必须保持开启: Compose 动画时长受 animator_duration_scale 控制
# (MotionDurationScale), 关掉会让展开动画瞬间完成、完全测不到真实帧。
disable_animations() {
  echo "== 关闭系统动画(仅 startup; 退出时自动还原) =="
  $ADB shell settings put global window_animation_scale 0
  $ADB shell settings put global transition_animation_scale 0
  $ADB shell settings put global animator_duration_scale 0
}

ensure_bench_apk

case "$TARGET" in
  startup) disable_animations; run com.takahashirinta.ncrust.benchmark.StartupBenchmark ;;
  scroll)  run com.takahashirinta.ncrust.benchmark.HomeScrollBenchmark ;;
  # v2.5.4 · A：设置页（转发属性最密集的一面）的滚动帧率。
  settings) run com.takahashirinta.ncrust.benchmark.SettingsScrollBenchmark ;;
  expand)  run com.takahashirinta.ncrust.benchmark.ExpandPlayerBenchmark ;;
  install_release) install_release; exit 0 ;;
  all)     disable_animations; run com.takahashirinta.ncrust.benchmark.StartupBenchmark
           restore_animations
           run com.takahashirinta.ncrust.benchmark.HomeScrollBenchmark
           run com.takahashirinta.ncrust.benchmark.SettingsScrollBenchmark
           run com.takahashirinta.ncrust.benchmark.ExpandPlayerBenchmark ;;
  *) echo "用法: $0 [startup|scroll|settings|expand|all|install_release]"; exit 2 ;;
esac

echo ""
echo "== 完成。perfetto trace 与结果 JSON 在设备: =="
echo "  /storage/emulated/0/Android/media/$BENCH_PKG/"
echo "把需要的 trace pull 回: adb pull /storage/emulated/0/Android/media/$BENCH_PKG/ benchmark/output/"