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
 * P1：大屏模式第三谓词 + 分栏语义边界（44%~50% 窄带回归）+ 封面正方形边长。
 *
 * 数值来自 PCL110（1272×2800 / density 560）实测：
 *  - 竖屏 363dp 宽 ⇒ 宽屏谓词 false；
 *  - 横屏 800×363dp（窗口 2800×1272px）⇒ 宽屏谓词 true，但大屏模式看的是第三个谓词；
 *  - 真实分栏边界 = 0.44×2800 = 1232px，而旧实现用 1400px 当边界。
 */
class PlayerLayoutTest {

    // ---------- 大屏模式第三谓词 ----------

    @Test
    fun `big screen requires both intent and landscape window`() {
        assertTrue(PlayerLayout.isBigScreenActive(requested = true, orientationLandscape = true))
    }

    @Test
    fun `big screen stays off before the window actually rotates`() {
        // 点了按钮但系统还没转过去（旋转有延迟）→ 仍走竖屏布局，否则竖屏窗口里会塞一个两栏布局
        assertFalse(PlayerLayout.isBigScreenActive(requested = true, orientationLandscape = false))
    }

    @Test
    fun `big screen off when not requested`() {
        assertFalse(PlayerLayout.isBigScreenActive(requested = false, orientationLandscape = true))
        assertFalse(PlayerLayout.isBigScreenActive(requested = false, orientationLandscape = false))
    }

    // ---------- 分栏边界（命中测试语义） ----------

    @Test
    fun `narrow player has no split`() {
        assertEquals(1f, PlayerLayout.wideLeftFraction(isWidePlayer = false, wideSplit = 1f))
        // 窄屏恒为 1，不读 wideSplit（保证不因分栏动画触发额外重组）
        assertEquals(1f, PlayerLayout.wideLeftFraction(isWidePlayer = false, wideSplit = 0.3f))
    }

    @Test
    fun `wide single column is full width and split is 44 percent`() {
        assertEquals(1f, PlayerLayout.wideLeftFraction(isWidePlayer = true, wideSplit = 0f))
        assertEquals(0.44f, PlayerLayout.wideLeftFraction(isWidePlayer = true, wideSplit = 1f), 1e-6f)
    }

    @Test
    fun `wide split boundary follows the animation`() {
        // PCL110 横屏宽 2800px：单栏边界 = 整宽；两栏稳定态 = 0.44×2800 = 1232px
        assertEquals(2800f, PlayerLayout.splitBoundaryPx(2800f, isWidePlayer = true, wideSplit = 0f), 1e-3f)
        assertEquals(1232f, PlayerLayout.splitBoundaryPx(2800f, isWidePlayer = true, wideSplit = 1f), 1e-3f)
    }

    @Test
    fun `legacy half width boundary was wrong in the 44 to 50 percent band`() {
        // 回归：旧实现用 screenWidthPx/2 = 1400px 当边界，于是 1232..1400px 这条带子
        // （真实属于歌词面板）被判成封面区，带内拖歌词会被整卡拖拽抢走。
        val boundary = PlayerLayout.splitBoundaryPx(2800f, isWidePlayer = true, wideSplit = 1f)
        val legacy = 2800f / 2f
        val bandPoint = 1300f
        assertTrue("real boundary must be left of the legacy midpoint", boundary < legacy)
        assertTrue("band point is inside the panel", bandPoint > boundary)
        assertFalse("legacy predicate would have called it cover area", bandPoint > legacy)
    }

    @Test
    fun `big screen left boundary is fixed at 44 percent`() {
        assertEquals(1232f, PlayerLayout.bigScreenLeftBoundaryPx(2800f), 1e-3f)
    }

    @Test
    fun `big screen left fraction matches the wide split fraction`() {
        // 两套布局同一比例：视觉一致，也不再引入第二个魔法数字
        assertEquals(PlayerLayout.WIDE_RIGHT_FRACTION, 1f - PlayerLayout.BIG_SCREEN_LEFT_FRACTION, 1e-6f)
    }

    // ---------- 封面 ----------

    @Test
    fun `cover is square and height bound in big screen landscape`() {
        // PCL110 横屏：左栏宽 0.44×2800 = 1232px，左栏剩余高 636px（扣掉歌名/音质块）
        assertEquals(636f, PlayerLayout.squareCoverSizePx(1232f, 636f), 1e-3f)
        // 与旧宽屏竖屏（封面区高 170dp ≈ 595px）相比必须更大，这是 P1 的目标
        assertTrue(PlayerLayout.squareCoverSizePx(1232f, 636f) > 595f)
    }

    @Test
    fun `cover is width bound when the column is narrow`() {
        assertEquals(300f, PlayerLayout.squareCoverSizePx(300f, 636f), 1e-3f)
    }

    @Test
    fun `cover fallback never collapses to zero`() {
        assertEquals(1f, PlayerLayout.squareCoverSizePx(0f, 0f), 1e-3f)
        // 实测之前的兜底：横屏 2800×1272 → min(1120, 636) = 636px
        assertEquals(636f, PlayerLayout.coverFallbackSizePx(2800f, 1272f), 1e-3f)
    }
}

/**
 * v2.5.6 · P1：横向控制条的宽度预算（平板 ⤢ 在竖屏被盖住的回归防线）。
 *
 * 全部数值取自真机实测（WGR-W09 / EMUI 14.2.0 / Android 12 / API 31，
 * 见 `docs/verification/v2.5.6/probe-tablet-rotate.md`）：
 * 竖屏控制条容器 **352dp**（左栏 = 0.44 × 800dp），横屏 **563dp**。
 * 缺陷形状是「⤢ 被居中的传输组整个盖住」——
 * 那是**布局重叠**问题，纯函数只能钉住「谁该让位」，所以这里测的是判据本身，
 * 命中区本身由真机 uiautomator 断言（见验收脚本）。
 */
class ControlsBarBudgetTest {

    private val tabletPortraitDp = 352f
    private val tabletLandscapeDp = 563f
    private val phoneLandscapeDp = 640f

    @Test
    fun `真机平板竖屏——4 个左键时音质片必须让位`() {
        // 352dp 装不下 4×40 + 142 + 72 = 374dp ⇒ 不挂音质片，但左组与传输组都留得住。
        assertFalse(
            PlayerLayout.qualityChipFits(
                availableWidthDp = tabletPortraitDp,
                sideButtonCount = PlayerLayout.sideButtonCount(bigScreenEntryVisible = true),
            )
        )
    }

    @Test
    fun `真机平板竖屏——让位后剩下的两组确实放得下`() {
        // 把音质片摘掉之后，左组 + 传输组 = 160 + 142 = 302 ≤ 352 - 16 = 336 ⇒ 放得下。
        // 这条是上一条的**互补断言**：只说「音质片让位」不够，
        // 还要证明让位之后 ⤢ 那 40dp 真的在预算之内。
        val left = PlayerLayout.CONTROLS_SIDE_BUTTON_DP * 4
        val usable = tabletPortraitDp - PlayerLayout.CONTROLS_HORIZONTAL_PADDING_DP
        assertTrue(left + PlayerLayout.CONTROLS_TRANSPORT_DP <= usable)
    }

    @Test
    fun `真机平板横屏与手机横屏——音质片照旧挂载`() {
        listOf(tabletLandscapeDp, phoneLandscapeDp).forEach { width ->
            assertTrue(
                "width=$width 应该放得下",
                PlayerLayout.qualityChipFits(
                    availableWidthDp = width,
                    sideButtonCount = PlayerLayout.sideButtonCount(bigScreenEntryVisible = true),
                )
            )
        }
    }

    @Test
    fun `没有 ⤢ 时（手机）预算更宽裕——手机竖屏 360dp 也放得下`() {
        // 手机竖屏 360dp：3×40 + 142 + 72 = 334 > 344? 不 —— 344 是可用宽，
        // 334 ≤ 344 ⇒ 放得下。这条钉住「本版的让位规则**没有**顺手改掉手机的既有行为」。
        assertTrue(
            PlayerLayout.qualityChipFits(
                availableWidthDp = 360f,
                sideButtonCount = PlayerLayout.sideButtonCount(bigScreenEntryVisible = false),
            )
        )
    }

    @Test
    fun `边界——恰好放下与差 1dp 放不下`() {
        val exact = PlayerLayout.CONTROLS_HORIZONTAL_PADDING_DP +
            PlayerLayout.CONTROLS_SIDE_BUTTON_DP * 3 +
            PlayerLayout.CONTROLS_TRANSPORT_DP +
            PlayerLayout.CONTROLS_QUALITY_CHIP_DP
        assertTrue(PlayerLayout.qualityChipFits(exact, sideButtonCount = 3))
        assertFalse(PlayerLayout.qualityChipFits(exact - 1f, sideButtonCount = 3))
    }

    @Test
    fun `sideButtonCount 只有 3 与 4 两格`() {
        assertEquals(3, PlayerLayout.sideButtonCount(bigScreenEntryVisible = false))
        assertEquals(4, PlayerLayout.sideButtonCount(bigScreenEntryVisible = true))
    }
}
