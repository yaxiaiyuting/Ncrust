/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.2.1 · P0：**可视化 tee 不得改变音频输出，也不得因为声道数而抛异常。**
 *
 * ## 这一版修的是什么（真机实测的完整因果，别再退回旧写法）
 *
 * v1.8.0 引入可视化时用的是 media3 自带的 `WaveformAudioBufferSink(barsPerSecond, 1, …)`，
 * 第二个参数 `1` 表示「把输入混成单声道」（当时的注释还写着"media3 内部走默认的
 * ChannelMixingMatrix"——**那是一条没有验证过的平台假设**）。而 media3 1.5.0 的
 * `ChannelMixingMatrix.createMixingCoefficients(int, int)` 只实现了三种矩阵：
 *
 *   | 输入 → 输出 | 结果 |
 *   |---|---|
 *   | N → N | 单位矩阵 |
 *   | 1 → 2 | `{1, 1}` |
 *   | 2 → 1 | `{0.5, 0.5}` |
 *   | **其它（含 6 → 1）** | `UnsupportedOperationException: Default channel mixing coefficients for 6->1 are not yet implemented.` |
 *
 * 而 QQ 音乐的「臻品音质 / 臻品全景声」档（本应用统一档位表里的 `dolby` / `jyeffect`）
 * 实测回的**就是 6 声道 FLAC**（`Q001…flac` = FLAC 44100Hz **6ch** 16bit，
 * 见 `docs/verification/v2.2.1/p0-quality-loop/PROBE.md`），于是：
 *
 *   1. `TeeAudioProcessor.flush()` → `WaveformAudioBufferSink.flush()` → `ChannelMixingMatrix.create(6, 1)` **抛异常**；
 *   2. 异常把 AudioSink 打进不可恢复状态（logcat：`Disable failed` / `Reset failed`）；
 *   3. **同一个 player 实例之后任何档位都播不出来**（连 128k mp3 也报同一个错）；
 *   4. `PlayerViewModel.handlePlaybackError` 沿 8 档阶梯一路重试 → 每次 `play()` 抢一次音频焦点；
 *   5. 最低档仍失败 → 跳歌 → 下一首一样失败 → 无限跳歌。
 *
 * 所以本文件的契约有三条，缺一不可：
 *
 *  - **不混音**：输出声道数 = 输入声道数（`onConfigure` 里保持 format 不变），
 *    因此**永远不会**构造 ChannelMixingMatrix，任何声道数都能播；
 *  - **不抛异常**：RMS 计算对未知编码直接跳过（返回 0），绝不把音频线程上的异常抛给播放器；
 *  - **不改变输出格式**：`TeeAudioProcessor` 只旁路读 PCM，本 sink 的输出只是旁路数据。
 *
 * 立体声/单声道的听感、以及可视化柱高语义与旧版**一致**（RMS 定义相同，
 * 只是旧版在 6 声道时直接崩，新版对全部声道一起求 RMS）。
 */

package com.takahashirinta.ncrust.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.takahashirinta.ncrust.ui.player.WaveformStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 声道数无关的波形旁路 sink。
 *
 * 实现 [TeeAudioProcessor.AudioBufferSink]（**不是** AudioProcessor）：它只是 tee 的下游消费者，
 * 读一份 PCM 副本算 RMS，然后把结果丢给 [WaveformStore.onBar] —— 不返回任何数据给播放链，
 * 因此不可能改变输出格式，也不可能参与声道矩阵。
 */
@UnstableApi
class TransparentWaveformSink : TeeAudioProcessor.AudioBufferSink {

    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID
    private var bytesPerSample = 0

    /** 只更新格式，**不做任何可能抛异常的推导**（旧实现在这里建矩阵，就是崩在这）。 */
    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.channelCount = if (channelCount > 0) channelCount else 0
        this.encoding = encoding
        this.bytesPerSample = bytesPerSampleOf(encoding)
    }

    /**
     * **音频线程**。零分配、零锁、零异常（任务书对可视化的性能契约）。
     *
     * 未知编码（24bit / 32bit 整数等）直接返回：宁可没有可视化，也不能让播放失败。
     */
    override fun handleBuffer(buffer: ByteBuffer) {
        val bps = bytesPerSample
        if (bps == 0 || channelCount == 0) return
        // tee 交过来的是**输入**缓冲区；这里只读，不改 position/limit 语义 ——
        // 用 duplicate() 避免影响上游游标。
        val rms = runCatching { rootMeanSquare(buffer.duplicate().order(ByteOrder.nativeOrder()), encoding) }
            .getOrDefault(0.0)
        WaveformStore.onBar(rms)
    }

    private fun rootMeanSquare(view: ByteBuffer, encoding: Int): Double =
        PcmRms.of(view, encoding)

    private fun bytesPerSampleOf(encoding: Int): Int = PcmRms.bytesPerSample(encoding)
}

/**
 * 交错 PCM 的 RMS。**纯函数、纯数学、无 Android 依赖**（所以能被 JVM 单测直接断言数值）。
 *
 * 声道数无关：把所有声道一起算一个 RMS。旧实现（media3 的 WaveformAudioBufferSink）
 * 走的是「先混音到 1 声道再算」，而它的混音矩阵对 6→1 直接抛异常 —— 我们不需要那个矩阵，
 * 只要一个数值给柱状图用。
 */
@UnstableApi
internal object PcmRms {
    fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }

    fun of(view: ByteBuffer, encoding: Int): Double {
        val bps = bytesPerSample(encoding)
        if (bps == 0) return 0.0
        if (view.remaining() / bps <= 0) return 0.0
        var sum = 0.0
        var n = 0
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val shorts = view.order(ByteOrder.nativeOrder()).asShortBuffer()
                while (shorts.hasRemaining()) {
                    val v = shorts.get() / 32768.0
                    sum += v * v
                    n++
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                val floats = view.order(ByteOrder.nativeOrder()).asFloatBuffer()
                while (floats.hasRemaining()) {
                    val v = floats.get().toDouble()
                    sum += v * v
                    n++
                }
            }
            else -> return 0.0
        }
        if (n == 0) return 0.0
        return sqrt(sum / n)
    }
}
