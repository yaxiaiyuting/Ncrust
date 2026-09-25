/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.2.1 · P0 回归单测：**可视化 tee 不得因为声道数把播放器搞崩**。
 *
 * 被钉住的事实（真机 + media3 1.5.0 字节码双重验证）：
 *  - QQ「臻品音质」档回的 `Q001…flac` 是 **6 声道** FLAC；
 *  - media3 的 `ChannelMixingMatrix.createMixingCoefficients` 只实现
 *    「N→N / 1→2 / 2→1」，**6→1 抛 UnsupportedOperationException**；
 *  - 旧代码 `WaveformAudioBufferSink(barsPerSecond, 1, …)` 正好请求 6→1，
 *    于是切一下「杜比」就把 AudioSink 打坏，之后同一 player 实例任何档位都播不出来。
 */

package com.takahashirinta.ncrust.player

import androidx.media3.common.C
import androidx.media3.common.audio.ChannelMixingMatrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransparentWaveformSinkTest {

    private fun pcm16(vararg samples: Short): ByteBuffer =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder()).also { b ->
            samples.forEach { b.putShort(it) }
            b.flip()
        }

    private fun pcmFloat(vararg samples: Float): ByteBuffer =
        ByteBuffer.allocate(samples.size * 4).order(ByteOrder.nativeOrder()).also { b ->
            samples.forEach { b.putFloat(it) }
            b.flip()
        }

    /**
     * 核心回归：**旧配置在 6 声道输入上必然抛异常**。
     * 这条用例把「为什么必须换掉 WaveformAudioBufferSink(…, 1, …)」固定成证据，
     * 而不是一句注释 —— 谁想退回单声道混音，先看它红。
     */
    @Test
    fun `mono output config is what used to throw for six channel input`() {
        assertThrows(UnsupportedOperationException::class.java) {
            ChannelMixingMatrix.create(2, 1).let { /* 2→1 支持 */ }
            ChannelMixingMatrix.create(6, 1)
        }
    }

    /** 新的 sink 对 6 声道输入**不抛异常**（它根本不建矩阵）。 */
    @Test
    fun `six channel pcm16 buffer is handled without throwing`() {
        val sink = TransparentWaveformSink()
        sink.flush(44_100, 6, C.ENCODING_PCM_16BIT)
        // 两个 frame × 6 声道，全零 ⇒ RMS = 0，且**绝不抛**。
        sink.handleBuffer(pcm16(*ShortArray(12)))
        Unit
    }

    /** RMS 数学本身：满幅 = 1.0、静音 = 0.0，与声道数无关（6 声道也照算）。 */
    @Test
    fun `rms is channel count agnostic`() {
        val monoFull = pcm16(Short.MAX_VALUE, Short.MAX_VALUE, Short.MAX_VALUE, Short.MAX_VALUE)
        assertEquals(1.0, PcmRms.of(monoFull, C.ENCODING_PCM_16BIT), 0.001)

        val silence = pcm16(*ShortArray(12))
        assertEquals(0.0, PcmRms.of(silence, C.ENCODING_PCM_16BIT), 0.0001)

        // 6 声道（QQ 臻品档的实测布局）：旧实现在这一档上抛异常，新实现照常算出数值。
        val sixChannel = pcm16(
            Short.MAX_VALUE, Short.MAX_VALUE, Short.MAX_VALUE, Short.MAX_VALUE, Short.MAX_VALUE, Short.MAX_VALUE,
            Short.MIN_VALUE, Short.MIN_VALUE, Short.MIN_VALUE, Short.MIN_VALUE, Short.MIN_VALUE, Short.MIN_VALUE,
        )
        assertEquals(1.0, PcmRms.of(sixChannel, C.ENCODING_PCM_16BIT), 0.001)
    }

    @Test
    fun `rms of float samples is in unit scale`() {
        val half = pcmFloat(0.5f, -0.5f, 0.5f, -0.5f)
        assertEquals(0.5, PcmRms.of(half, C.ENCODING_PCM_FLOAT), 0.001)
    }

    @Test
    fun `float and unknown encodings never throw`() {
        val sink = TransparentWaveformSink()
        sink.flush(48_000, 2, C.ENCODING_PCM_FLOAT)
        sink.handleBuffer(pcmFloat(0.5f, -0.5f, 0.25f, -0.25f))
        // 24bit / 32bit 整数等未支持编码：静默返回，绝不把异常抛到音频线程上。
        sink.flush(48_000, 6, C.ENCODING_PCM_24BIT)
        sink.handleBuffer(pcm16(1, 2, 3, 4, 5, 6))
        sink.flush(48_000, 0, C.ENCODING_INVALID)
        sink.handleBuffer(pcm16(1))
        Unit
    }

    /** 空缓冲区 / 半个采样也不能炸（tee 的 flush 边界会送来空 buffer）。 */
    @Test
    fun `empty and ragged buffers are ignored`() {
        val sink = TransparentWaveformSink()
        sink.flush(44_100, 2, C.ENCODING_PCM_16BIT)
        sink.handleBuffer(ByteBuffer.allocate(0))
        sink.handleBuffer(ByteBuffer.allocate(3))
        Unit
    }

}
