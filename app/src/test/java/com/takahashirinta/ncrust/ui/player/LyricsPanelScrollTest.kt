/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.0.1 hotfix：大屏歌首「歌词被拉到最顶端、第一句看不见」的滚动定位纯逻辑。
 *
 * 所有视口数值都来自 PCL110（2800×1272px / density 560 = 3.5）实测：
 *  - 竖屏全屏播放器的歌词面板约 700dp 高（2450px）；
 *  - 大屏模式右栏的歌词面板约 280dp 高（980px）—— 200dp 留白在里面占 71%，这就是 bug 的几何来源。
 */
class LyricsPanelScrollTest {

    /** PCL110 的 density。 */
    private val d = 3.5f
    private fun px(dp: Float) = dp * d

    // ---------- ① 顶部留白：竖屏逐像素不变 ----------

    @Test
    fun `portrait tall panel keeps the original 200dp spacer`() {
        // 700dp 视口：36% = 252dp > 200dp ⇒ 保持 200dp，与 v2.0.0 完全一致
        val vh = px(700f).toInt()          // 2450
        assertEquals(px(200f), LyricsPanelScroll.topSpacerHeightPx(vh, px(200f)), 0.01f)
    }

    @Test
    fun `boundary is 200dp divided by 036 dp`() {
        // 200 / 0.36 ≈ 555.6dp：之上的视口都不动，之下才开始收窄
        val above = px(556f).toInt()
        val below = px(555f).toInt()
        assertEquals(px(200f), LyricsPanelScroll.topSpacerHeightPx(above, px(200f)), 0.01f)
        assertTrue(LyricsPanelScroll.topSpacerHeightPx(below, px(200f)) < px(200f))
    }

    // ---------- ② 大屏短面板：留白必须收窄，否则第一句被推出可视区 ----------

    @Test
    fun `big screen short panel shrinks the spacer to the lead slot`() {
        // 280dp 面板（大屏右栏实测量级）：留白从 200dp 收到 100.8dp
        val vh = px(280f).toInt()          // 980
        val spacer = LyricsPanelScroll.topSpacerHeightPx(vh, px(200f))
        assertEquals(vh * LyricsPanelScroll.LEAD_FRACTION, spacer, 0.5f)
        // 第一句落在 36% 处 ⇒ 在渐隐带（min(100dp, 30%×视口) = 84dp）下方，肉眼可见
        assertTrue(spacer > vh * 0.30f)
    }

    @Test
    fun `spacer never exceeds the lead fraction of the viewport`() {
        // 不变量 1：任何视口下「列表在最顶端」都必须让第一句出现在 36% 以内。
        // 这条一旦破掉，「回顶」就会把第一句推出可视区 —— 正是本 bug。
        for (vhDp in intArrayOf(120, 200, 240, 279, 363, 420, 555, 556, 700, 900)) {
            val vh = px(vhDp.toFloat()).toInt()
            val spacer = LyricsPanelScroll.topSpacerHeightPx(vh, px(200f))
            assertTrue(
                "视口 ${vhDp}dp 时留白 $spacer 超过了 36% 视口",
                spacer <= vh * LyricsPanelScroll.LEAD_FRACTION + 0.01f,
            )
        }
    }

    @Test
    fun `unknown viewport falls back to the base spacer`() {
        // 首帧 / 无界约束：回落 200dp（回落 0 会让第一句贴顶）
        assertEquals(px(200f), LyricsPanelScroll.topSpacerHeightPx(0, px(200f)), 0.01f)
        assertEquals(px(200f), LyricsPanelScroll.topSpacerHeightPx(-1, px(200f)), 0.01f)
    }

    // ---------- ③ 定位 offset：与 v1.5.0 起的语义逐值一致 ----------

    @Test
    fun `lead offset keeps the original rounding`() {
        assertEquals(-(700 * 0.36f).toInt(), LyricsPanelScroll.leadOffsetPx(700))
        assertEquals(-252, LyricsPanelScroll.leadOffsetPx(700))
        assertEquals(-100, LyricsPanelScroll.leadOffsetPx(280))
        assertEquals(0, LyricsPanelScroll.leadOffsetPx(0))
    }

    // ---------- ④ 「回顶」与「首句落在 36%」必须是同一个位置 ----------

    @Test
    fun `scroll to top equals positioning line zero at the lead slot`() {
        // LazyColumn 的 scrollToItem(item, offset) 语义：目标 item 出现在 -offset 处；
        // item 1 之上只有 topSpacer，所以它要落到 36% 视口处需要的滚动量是 spacer - 36%×vh。
        // 不变量：这个量 <= 0（即被夹到 0）⇒ 两种定位方式给出同一个位置，不会来回跳。
        // 容差 1px：[leadOffsetPx] 用 .toInt() 向下取整（与 v1.5.0 起逐值一致），
        // 所以"回顶"与"定位"最多差不到 1px，肉眼与 LazyColumn 的整数滚动量都吃不出来。
        for (vhDp in intArrayOf(120, 240, 279, 363, 700)) {
            val vh = px(vhDp.toFloat()).toInt()
            val spacer = LyricsPanelScroll.topSpacerHeightPx(vh, px(200f))
            val neededScroll = spacer - (-LyricsPanelScroll.leadOffsetPx(vh)).toFloat()
            assertTrue(
                "视口 ${vhDp}dp：定位首句需要滚动 $neededScroll px（应为 <1px，即与回顶等价）",
                neededScroll < 1f,
            )
        }
    }

    // ---------- ⑤ 行下标 → item 下标：-1（还没到第一句）按第一句算 ----------

    @Test
    fun `before the first line targets line zero`() {
        // currentLineIndex 在第一句之前返回 -1；面板这时要摆的是"第一句"，不是"列表顶端"
        assertEquals(1, LyricsPanelScroll.targetItemIndex(-1, 66))
    }

    @Test
    fun `normal indices shift by the top spacer`() {
        assertEquals(1, LyricsPanelScroll.targetItemIndex(0, 66))
        assertEquals(6, LyricsPanelScroll.targetItemIndex(5, 66))
        assertEquals(66, LyricsPanelScroll.targetItemIndex(65, 66))
    }

    @Test
    fun `out of range indices are clamped into the list`() {
        // 切歌瞬间可能还带着上一首的行号：钳到列表内，绝不越界
        assertEquals(66, LyricsPanelScroll.targetItemIndex(999, 66))
        assertEquals(1, LyricsPanelScroll.targetItemIndex(-99, 66))
    }

    @Test
    fun `empty line list does not throw`() {
        assertEquals(0, LyricsPanelScroll.targetItemIndex(-1, 0))
        assertEquals(0, LyricsPanelScroll.targetItemIndex(3, 0))
    }

    // ================================================================ v2.3.0 · E
    // 横屏 / 大屏右栏的定位目标：视口**正中**（0.5），而不是竖屏的 0.36。
    // 探针（docs/verification/v2.3.0/probe-lyric-landscape.md）给的是几何事实：
    // 大屏右栏面板约 280dp 高 ⇒ 0.36 × 280 ≈ 100dp，当前行几乎贴着顶部渐隐带。

    @Test
    fun `横屏目标比例是视口正中`() {
        assertEquals(0.5f, LyricsPanelScroll.CENTER_FRACTION, 1e-6f)
    }

    @Test
    fun `竖屏目标比例仍是 0_36（一个像素都不动）`() {
        assertEquals(0.36f, LyricsPanelScroll.LEAD_FRACTION, 1e-6f)
    }

    @Test
    fun `横屏定位 offset 落在视口一半处`() {
        val vh = (280 * d).toInt()
        assertEquals(-(vh * 0.5f).toInt(), LyricsPanelScroll.leadOffsetPx(vh, LyricsPanelScroll.CENTER_FRACTION))
    }

    @Test
    fun `横屏顶部留白与竖屏一样被夹取 且等于定位点`() {
        val vh = (280 * d).toInt()
        val spacer = LyricsPanelScroll.topSpacerHeightPx(vh, 200f * d, LyricsPanelScroll.CENTER_FRACTION)
        // 不变量 1：留白 <= CENTER_FRACTION × 视口 ⇒「回顶」与「首句落在正中」是同一个位置
        assertTrue(spacer <= vh * LyricsPanelScroll.CENTER_FRACTION + 1f)
        assertEquals(vh * LyricsPanelScroll.CENTER_FRACTION, spacer, 1f)
    }

    @Test
    fun `横屏留白仍在顶部渐隐带之下（0_30 小于 0_5）`() {
        // LyricsView 的渐隐高 = min(100dp, 30% 视口)，所以首句必须落在 30% 以下。
        assertTrue(LyricsPanelScroll.CENTER_FRACTION > 0.30f)
        assertTrue(LyricsPanelScroll.LEAD_FRACTION > 0.30f)
    }

    @Test
    fun `默认参数等价于竖屏行为（既有调用点零改动）`() {
        val vh = (700 * d).toInt()
        assertEquals(
            LyricsPanelScroll.leadOffsetPx(vh, LyricsPanelScroll.LEAD_FRACTION),
            LyricsPanelScroll.leadOffsetPx(vh),
        )
        assertEquals(
            LyricsPanelScroll.topSpacerHeightPx(vh, 200f * d, LyricsPanelScroll.LEAD_FRACTION),
            LyricsPanelScroll.topSpacerHeightPx(vh, 200f * d),
        )
    }

    @Test
    fun `横屏短视口下正中比 36 百分比更靠下（这就是修的位置）`() {
        val vh = (280 * d).toInt()
        val center = -LyricsPanelScroll.leadOffsetPx(vh, LyricsPanelScroll.CENTER_FRACTION)
        val lead = -LyricsPanelScroll.leadOffsetPx(vh, LyricsPanelScroll.LEAD_FRACTION)
        assertTrue(center > lead)
        assertEquals((center - lead).toFloat(), vh * 0.14f, 2f)
    }
}
