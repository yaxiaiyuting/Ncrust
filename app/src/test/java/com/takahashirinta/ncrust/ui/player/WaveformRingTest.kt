/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.8.0 · T3：可视化环形缓冲的纯逻辑单测。
 *
 * 为什么必须单测：音频线程的写入路径**在真机上无法观测**（没有日志、没有断点，
 * 打日志本身就会引入分配），而它一旦出错的表现是"波形不动 / 爆音 / NaN 画满屏"，
 * 靠肉眼很难定位。这里把"写入 → 消费 → 衰减"三个纯函数行为钉死。
 */
class WaveformRingTest {

    @Test
    fun `pump consumes bars in order and slides the window`() {
        val ring = WaveformRing(capacity = 16, barCount = 4)
        ring.push(0.1f); ring.push(0.2f); ring.push(0.3f); ring.push(0.4f)
        assertTrue(ring.pump(active = true))
        // 窗口下标 0 = 最旧
        assertEquals(0.1f, ring.barAt(0), 1e-6f)
        assertEquals(0.2f, ring.barAt(1), 1e-6f)
        assertEquals(0.3f, ring.barAt(2), 1e-6f)
        assertEquals(0.4f, ring.barAt(3), 1e-6f)

        ring.push(0.9f)
        assertTrue(ring.pump(active = true))
        assertEquals(0.2f, ring.barAt(0), 1e-6f)
        assertEquals(0.9f, ring.barAt(3), 1e-6f)
    }

    @Test
    fun `pump without new data reports no repaint`() {
        val ring = WaveformRing(capacity = 8, barCount = 4)
        ring.push(0.5f)
        assertTrue(ring.pump(active = true))
        // 没有新柱 ⇒ 返回 false。这是"静态时零帧调度"的保证：
        // UI 侧拿 false 就不再标记重绘，一分钟不动的波形不会白烧 GPU。
        assertFalse(ring.pump(active = true))
        assertFalse(ring.pump(active = true))
    }

    @Test
    fun `overflow drops the oldest bars instead of blocking the writer`() {
        val ring = WaveformRing(capacity = 4, barCount = 4)
        // 写 10 根但一次都不消费（模拟 UI 卡了 10 帧）
        repeat(10) { ring.push(it / 10f) }
        assertEquals(10, ring.pendingCount)
        assertTrue(ring.pump(active = true))
        // 容量 4 ⇒ 只保留最后 4 根：0.6 0.7 0.8 0.9
        assertEquals(0.6f, ring.barAt(0), 1e-6f)
        assertEquals(0.9f, ring.barAt(3), 1e-6f)
        assertEquals(0, ring.pendingCount)
    }

    @Test
    fun `paused decays to zero then stops repainting`() {
        val ring = WaveformRing(capacity = 8, barCount = 2)
        ring.push(1f); ring.push(1f); ring.pump(active = true)
        var frames = 0
        while (ring.pump(active = false)) {
            frames++
            assertTrue("衰减必须在有限帧内归零", frames < 100)
        }
        assertEquals(0f, ring.barAt(0), 0f)
        assertEquals(0f, ring.barAt(1), 0f)
        // 归零之后不再要求重绘
        assertFalse(ring.pump(active = false))
    }

    @Test
    fun `non finite and out of range samples are clamped`() {
        val ring = WaveformRing(capacity = 8, barCount = 3)
        ring.push(Float.NaN)
        ring.push(Float.POSITIVE_INFINITY)
        ring.push(2.5f)
        ring.pump(active = true)
        assertEquals(0f, ring.barAt(0), 0f)
        assertEquals(0f, ring.barAt(1), 0f)
        assertEquals(1f, ring.barAt(2), 0f)
    }

    @Test
    fun `clear resets the window and the read cursor`() {
        val ring = WaveformRing(capacity = 8, barCount = 2)
        ring.push(0.8f); ring.pump(active = true)
        ring.clear()
        assertEquals(0f, ring.barAt(0), 0f)
        assertEquals(0f, ring.barAt(1), 0f)
        assertEquals(0, ring.pendingCount)
    }

    @Test
    fun `copyInto reuses the destination array`() {
        val ring = WaveformRing(capacity = 8, barCount = 3)
        ring.push(0.25f)
        ring.pump(active = true)
        val dst = FloatArray(3)
        ring.copyInto(dst)
        // 只有最后一根柱有值（窗口左端是历史）
        assertEquals(0f, dst[0], 0f)
        assertEquals(0.25f, dst[2], 1e-6f)
        // 目标数组比窗口长/短都不崩（越界不写）
        val short = FloatArray(1)
        ring.copyInto(short)
        assertEquals(0f, short[0], 0f)
    }
}
