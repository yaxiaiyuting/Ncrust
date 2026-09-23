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
 * 靠肉眼很难定位。这里把"写入 → 消费 → 平滑 → 收敛"几个纯函数行为钉死。
 *
 * v1.8.1 起多了一条**与刷新率无关**的约束：平滑系数由 dt 算出，60fps 与 30fps
 * 必须画出同一条曲线（低端机降帧率不能让观感变形）。
 */
class WaveformRingTest {

    private fun settle(ring: WaveformRing, frames: Int = 40, dtMs: Float = 16f) {
        repeat(frames) { ring.pump(active = true, dtMs = dtMs) }
    }

    @Test
    fun `pump consumes bars in order into the sliding window`() {
        val ring = WaveformRing(capacity = 16, barCount = 4)
        ring.push(0.1f); ring.push(0.2f); ring.push(0.3f); ring.push(0.4f)
        assertTrue(ring.pump(active = true, dtMs = 16f))
        assertEquals(0.1f, ring.targetAt(0), 1e-6f)
        assertEquals(0.2f, ring.targetAt(1), 1e-6f)
        assertEquals(0.3f, ring.targetAt(2), 1e-6f)
        assertEquals(0.4f, ring.targetAt(3), 1e-6f)

        ring.push(0.9f)
        assertTrue(ring.pump(active = true, dtMs = 16f))
        assertEquals(0.2f, ring.targetAt(0), 1e-6f)
        assertEquals(0.9f, ring.targetAt(3), 1e-6f)
    }

    @Test
    fun `displayed bars converge to the data values`() {
        val ring = WaveformRing(capacity = 16, barCount = 2)
        ring.push(0.8f)
        // 第一帧只走了一部分 —— 这正是"不再一跳一跳"的原因
        ring.pump(active = true, dtMs = 16f)
        assertTrue("首帧不应直接跳到目标值", ring.barAt(1) < 0.8f)
        settle(ring)
        assertEquals(0.8f, ring.barAt(1), 1e-6f)
    }

    @Test
    fun `fully settled ring reports no repaint`() {
        val ring = WaveformRing(capacity = 8, barCount = 4)
        ring.push(0.5f)
        settle(ring)
        // 没有新柱且已收敛 ⇒ false。这是"静止时零帧调度"的保证。
        assertFalse(ring.pump(active = true, dtMs = 16f))
        assertFalse(ring.pump(active = true, dtMs = 16f))
    }

    @Test
    fun `smoothing is frame rate independent`() {
        // 同样 64ms：60fps 走 4 步、30fps 走 2 步，结果必须几乎一致。
        val fast = WaveformRing(capacity = 8, barCount = 1)
        fast.push(1f)
        repeat(4) { fast.pump(active = true, dtMs = 16f) }

        val slow = WaveformRing(capacity = 8, barCount = 1)
        slow.push(1f)
        repeat(2) { slow.pump(active = true, dtMs = 32f) }

        assertEquals(fast.barAt(0), slow.barAt(0), 0.02f)
    }

    @Test
    fun `attack is faster than release`() {
        val up = WaveformRing(capacity = 8, barCount = 1)
        up.push(1f); up.pump(active = true, dtMs = 16f)
        val afterRise = up.barAt(0)

        val down = WaveformRing(capacity = 8, barCount = 1)
        down.push(1f); settle(down)
        down.push(0f); down.pump(active = true, dtMs = 16f)
        val afterFall = 1f - down.barAt(0)

        assertTrue("起音应比回落快", afterRise > afterFall)
    }

    @Test
    fun `overflow drops the oldest bars instead of blocking the writer`() {
        val ring = WaveformRing(capacity = 4, barCount = 4)
        repeat(10) { ring.push(it / 10f) }
        assertEquals(10, ring.pendingCount)
        assertTrue(ring.pump(active = true, dtMs = 16f))
        assertEquals(0.6f, ring.targetAt(0), 1e-6f)
        assertEquals(0.9f, ring.targetAt(3), 1e-6f)
        assertEquals(0, ring.pendingCount)
    }

    @Test
    fun `paused decays to zero then stops repainting`() {
        val ring = WaveformRing(capacity = 8, barCount = 2)
        ring.push(1f); ring.push(1f); settle(ring)
        var frames = 0
        while (ring.pump(active = false, dtMs = 16f)) {
            frames++
            assertTrue("衰减必须在有限帧内归零", frames < 200)
        }
        assertEquals(0f, ring.barAt(0), 0f)
        assertEquals(0f, ring.barAt(1), 0f)
        assertFalse(ring.pump(active = false, dtMs = 16f))
    }

    @Test
    fun `non finite and out of range samples are clamped`() {
        val ring = WaveformRing(capacity = 8, barCount = 3)
        ring.push(Float.NaN)
        ring.push(Float.POSITIVE_INFINITY)
        ring.push(2.5f)
        ring.pump(active = true, dtMs = 16f)
        assertEquals(0f, ring.targetAt(0), 0f)
        assertEquals(0f, ring.targetAt(1), 0f)
        assertEquals(1f, ring.targetAt(2), 0f)
    }

    @Test
    fun `clear resets the window and the read cursor`() {
        val ring = WaveformRing(capacity = 8, barCount = 2)
        ring.push(0.8f); ring.pump(active = true, dtMs = 16f)
        ring.clear()
        assertEquals(0f, ring.barAt(0), 0f)
        assertEquals(0f, ring.targetAt(1), 0f)
        assertEquals(0, ring.pendingCount)
    }

    @Test
    fun `copyInto reuses the destination array`() {
        val ring = WaveformRing(capacity = 8, barCount = 3)
        ring.push(0.25f)
        settle(ring)
        val dst = FloatArray(3)
        ring.copyInto(dst)
        assertEquals(0f, dst[0], 0f)
        assertEquals(0.25f, dst[2], 1e-6f)
        val short = FloatArray(1)
        ring.copyInto(short)
        assertEquals(0f, short[0], 0f)
    }
}
