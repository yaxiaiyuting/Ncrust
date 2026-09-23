/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import kotlinx.coroutines.delay

/**
 * v1.8.0 · T3：「音频可视化」开关的**唯一读写入口**（与 RotationSetting 同一套写法）。
 *
 * 默认**开**：可视化只出现在横屏大屏模式，不影响竖屏日常使用；关掉时
 * [AudioVisualizerBars] 整个不挂载（连帧时钟都不跑），所以关掉等于这个功能不存在。
 */
object VisualizerSetting {

    private const val PREFS = "ncrust_settings"
    private const val KEY = "audio_visualizer"

    const val DEFAULT_ENABLED = true

    private val stateHolder = mutableStateOf(DEFAULT_ENABLED)
    private var loadedFromDisk = false

    val state: MutableState<Boolean> get() = stateHolder

    fun read(context: Context): Boolean {
        if (!loadedFromDisk) {
            val enabled = prefs(context).getBoolean(KEY, DEFAULT_ENABLED)
            stateHolder.value = enabled
            WaveformStore.enabled = enabled
            loadedFromDisk = true
        }
        return stateHolder.value
    }

    fun write(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY, enabled).apply()
        loadedFromDisk = true
        stateHolder.value = enabled
        // 音频线程侧只读这一个 volatile 布尔：关掉之后 render 循环里每次回调
        // 只剩一次 volatile 读，等于零开销（不会去动 AudioProcessor 链，
        // 因为重建 ExoPlayer 的代价远大于这点开销）。
        WaveformStore.enabled = enabled
    }

    internal fun resetForTest() {
        stateHolder.value = DEFAULT_ENABLED
        loadedFromDisk = false
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * v1.8.0 · T3：音频柱状数据的**进程内单例**（音频线程写 / UI 线程读的唯一交汇点）。
 *
 * 数据来源：[com.takahashirinta.ncrust.player.VisualizerRenderersFactory] 注入的
 * `TeeAudioProcessor + WaveformAudioBufferSink`（media3 官方 API，**不需要任何权限**，
 * 也绕开了 `android.media.audiofx.Visualizer` 那条要 RECORD_AUDIO 的路）。
 *
 * 为什么 [generation] 用 Compose 状态而不是回调：柱状图必须在 **draw 阶段**读取数据，
 * 用状态失效只触发重绘、不触发重组（与播放器卡片的零重组原则一致）。
 */
object WaveformStore {

    /** 画面上的柱子数。28 根在 PCL110（左栏 352dp 宽）上单根约 9dp，看得清又不糊。 */
    const val BAR_COUNT = 28

    /**
     * 每秒生成多少根柱。
     *
     * 20 与 media3 的建议区间（10–30）一致：再高只会让音频线程多做无用的聚合，
     * 再低波形就跟不上鼓点。注意这个值**只影响音频线程的聚合粒度**，
     * 画面刷新率由 [VISUALIZER_FRAME_INTERVAL_MS] 单独控制。
     */
    const val BARS_PER_SECOND = 20

    /** 环形缓冲容量：UI 卡顿时最多积压 12.8 秒，再多丢最旧的（绝不回压音频线程）。 */
    private const val CAPACITY = 256

    private val ring = WaveformRing(capacity = CAPACITY, barCount = BAR_COUNT)

    /**
     * 开关的**音频线程侧镜像**。volatile：关掉后 render 回调里只剩一次读。
     * 之所以不动态拆掉 AudioProcessor：ExoPlayer 建好之后改不了 audio sink，
     * 为了一个开关重建播放器会打断播放 —— 而这里省下的开销本来就是纳秒级。
     */
    @Volatile
    var enabled: Boolean = VisualizerSetting.DEFAULT_ENABLED

    private val generationState = mutableIntStateOf(0)

    /** 只应在 draw 阶段读（在组合阶段读会变成每帧重组）。 */
    val generation: Int get() = generationState.intValue

    /** **音频线程**调用：零分配、零锁。 */
    fun onBar(rootMeanSquare: Double) {
        if (enabled) ring.push(rootMeanSquare.toFloat())
    }

    /** UI 线程按帧率调用；有新数据（或衰减未结束）时才让画面失效。 */
    fun pump(active: Boolean) {
        if (ring.pump(active)) generationState.intValue++
    }

    /** UI 线程：把滚动窗口拷进复用数组。 */
    fun snapshot(destination: FloatArray) = ring.copyInto(destination)

    /** 单测 / 调试用。 */
    internal fun resetForTest() {
        ring.clear()
        enabled = VisualizerSetting.DEFAULT_ENABLED
    }
}

/** 画面刷新间隔（毫秒）。20fps 与数据速率 1:1 —— 画得更快没有新信息，只会白烧 GPU。 */
const val VISUALIZER_FRAME_INTERVAL_MS = 50L

/**
 * v1.8.0 · T3：横屏大屏模式左栏的**音频可视化条**（封面下、歌名/作者上）。
 *
 * 性能契约（S6 基线是硬指标，写在这里防止被"顺手优化"掉）：
 *
 *  - **不重组**：[WaveformStore.generation] 只在 [Canvas] 的 draw lambda 里读，
 *    失效范围是"重绘这一块画布"；柱高数组是 `remember` 出来的 `FloatArray`，逐帧原地更新，
 *    **零分配**。
 *  - **不空转**：暂停 / 缓冲时走 `delay` 而不是帧时钟，衰减到 0 之后 [WaveformStore.pump]
 *    返回 false，画面不再失效（不会出现"暂停了还在 20fps 重绘"）。
 *  - **不拖音频线程**：音频线程只做一次数组写；聚合（RMS）由 media3 的
 *    `WaveformAudioBufferSink` 在它自己的回调里完成。
 *
 * @param activeProvider 播放中且未在缓冲。用 lambda 而不是布尔参数：这个值只在
 *   帧循环里读，传布尔会让 PlayerCard 订阅 isPlaying/isBuffering 而整树重组
 *   （AGENTS.md「GPU 零重组」）。
 */
@Composable
fun AudioVisualizerBars(
    activeProvider: () -> Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = WaveformStore.BAR_COUNT,
    frameIntervalMs: Long = VISUALIZER_FRAME_INTERVAL_MS,
) {
    val barColor = LocalMetroColors.current.primary
    val currentActive = rememberUpdatedState(activeProvider)
    val bars = remember(barCount) { FloatArray(barCount) }

    LaunchedEffect(barCount, frameIntervalMs) {
        val budgetNs = frameIntervalMs * 1_000_000L
        var lastFrameNs = 0L
        while (true) {
            if (currentActive.value()) {
                withFrameNanos { now ->
                    if (lastFrameNs == 0L || now - lastFrameNs >= budgetNs) {
                        lastFrameNs = now
                        WaveformStore.pump(active = true)
                    }
                }
            } else {
                // 暂停 / 缓冲：用 delay 而不是帧时钟 —— 归零过程不需要跟着刷新率走。
                delay(frameIntervalMs)
                lastFrameNs = 0L
                WaveformStore.pump(active = false)
            }
        }
    }

    Canvas(modifier) {
        // 在 **draw 阶段**读状态：只让这块画布失效重绘，不触发任何重组。
        WaveformStore.generation
        WaveformStore.snapshot(bars)
        val n = bars.size
        if (n == 0 || size.width <= 0f || size.height <= 0f) return@Canvas
        val gap = size.width * 0.28f / n
        val barWidth = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        val half = size.height / 2f
        val minBar = 1.dp.toPx()
        for (i in 0 until n) {
            // 幅度用**平方根**映射到高度，而不是线性。
            // 音乐（尤其母带压缩过的流行乐）的 RMS 通常落在 0.05~0.3，线性映射只能画出
            // 带宽 5%~30% 的一排小方块，肉眼像"没在动"；sqrt 把 0.09→0.3、0.25→0.5，
            // 既保留相对强弱，又让整条带子用得上高度。一次 sqrt/柱/帧（28 次）可忽略。
            val amplitude = kotlin.math.sqrt(bars[i].coerceIn(0f, 1f))
            val height = (amplitude * size.height).coerceAtLeast(minBar)
            // 越靠左（越旧）越淡 —— 不用渐变对象，一次 alpha 计算换来"余韵"观感。
            val alpha = 0.30f + 0.70f * (i + 1).toFloat() / n
            drawRect(
                color = barColor.copy(alpha = alpha),
                topLeft = androidx.compose.ui.geometry.Offset(
                    x = i * (barWidth + gap),
                    y = half - height / 2f,
                ),
                size = androidx.compose.ui.geometry.Size(barWidth, height),
            )
        }
    }
}
