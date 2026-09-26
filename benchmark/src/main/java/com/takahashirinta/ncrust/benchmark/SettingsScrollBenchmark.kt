package com.takahashirinta.ncrust.benchmark

import android.os.SystemClock
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "com.takahashirinta.ncrust"

/**
 * v2.5.4 · A：**设置页（「用户」页）滚动**的帧率基线。
 *
 * ## 为什么必须单开一条
 *
 * 这一页是**转发属性（forwarding property）最密集**的界面：
 * `UserScreen` 里有 66 处扁平读点（`strings.xxx`），其中相当一部分是 v2.5.3
 * 搬进 `SettingsStrings` 之后由类体 getter 转发出来的
 * （见 `docs/verification/v2.5.4/probe-forwarding-attr.md` §1）。
 * 首页一个读点都没有、播放器 44 处 —— 所以「转发属性有没有运行期开销」这个问题
 * 只能在设置页上问。
 *
 * ## 指标与判据
 *
 * 指标是 `FrameTimingMetric`（`frameDurationCpuMs` 的 p50/p90/p95/p99 + jank 计数）。
 * 判据（写死在 `probe-forwarding-attr.md` §5）：
 * `frameDurationCpuMs` p90 的 Δ ≤ 1.0ms 且不超过噪声带的 2 倍，`janky%` Δ ≤ 2pp。
 *
 * ⚠️ **必须跑在 release 包上**（铁律 16：debug 数据不得作基线）。
 * `benchmark/run_benchmark.sh` 的生产式流程就是为此存在的：不重装、不卸载被测应用。
 *
 * ## 前置条件
 *
 * 被测应用已安装并**保持登录态**。设置页本身不要求登录也能渲染，
 * 但登录态下才会出现账号块与音质块 —— 那正是读点最密的两段。
 *
 * ## 为什么用文本选择器而不是资源 id
 *
 * 本应用的 UI 文案走**运行时 i18n**（`ui/i18n`，不是 Android 资源字符串），
 * 所以 `By.res()` 找不到任何东西。既有两条 benchmark 也是这么做的
 * （`HomeScrollBenchmark` 找 `By.text("首页")`）。
 */
@RunWith(AndroidJUnit4::class)
class SettingsScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun settingsScroll() = benchmarkRule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        // v2.5.5 · F：可被 `-e ncrust.bench.iterations 3` 覆盖（见 BenchArgs）。
        iterations = BenchArgs.iterations(default = 6),
        // COLD：与 HomeScrollBenchmark 同口径，让两页的数字可比。
        // 转发属性的开销发生在**组合期**（每次进页面都会重新读一遍文案），
        // 冷启动正好把「首次组合整页」这件事包含进来。
        startupMode = StartupMode.COLD,
        compilationMode = BenchArgs.compilationMode(),
    ) {
        startActivityAndWait()
        device.waitForIdle()

        val list = findSettingsList(device, timeoutMs = 8_000)
        repeat(6) { swipe(list, Direction.UP); device.waitForIdle() }
        repeat(3) { swipe(list, Direction.DOWN); device.waitForIdle() }
    }

    /** 滚动一次；节点被回收时重查一次（与 HomeScrollBenchmark 同一套容错）。 */
    private fun swipe(list: UiObject2, direction: Direction) {
        repeat(2) { attempt ->
            try {
                list.swipe(direction, 1.0f)
                return
            } catch (stale: StaleObjectException) {
                if (attempt == 1) throw AssertionError("设置页列表节点持续失效，滚动失败", stale)
            }
        }
    }

    /**
     * 切到「用户」页并轮询直至出现它的纵向列表。
     *
     * 设置页**不是一条路由**，它是底部导航（窄屏）或左侧栏（宽屏）的第 4 项
     * （`MainActivity` 的 `navTabs` 顺序：首页 / 库 / 搜索 / 用户）。
     * 两种形态的文本都是「用户」（`zh_CN.kt` 的 `tabUser`），
     * 所以直接按文本点；找不到时退回英文文案，与 `HomeScrollBenchmark` 同形。
     */
    private fun findSettingsList(device: UiDevice, timeoutMs: Long): UiObject2 {
        val tab = device.wait(Until.findObject(By.text("用户")), timeoutMs)
            ?: device.wait(Until.findObject(By.textContains("Profile")), 3_000)
            ?: device.wait(Until.findObject(By.textContains("User")), 3_000)
            ?: error("未见底部导航/侧栏的「用户」入口（可能 splash 未结束）")
        tab.click()

        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val vertical = try {
                // 取可见高度最大的 scrollable 作为设置页主列表 —— 不用 height>width 判定：
                // 平板/横屏下主列表宽度大于高度，会被误排除（与 HomeScrollBenchmark 同一条教训）。
                device.findObjects(By.scrollable(true))
                    .maxByOrNull { it.visibleBounds.height() }
            } catch (_: StaleObjectException) {
                null
            }
            if (vertical != null) return vertical
            SystemClock.sleep(250)
        }
        error("${timeoutMs}ms 内未找到设置页纵向列表")
    }
}
