/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1：大屏模式方向策略的两个纯判定。
 *
 * 为什么单独测：设备平放在桌面上时无法在真机上验证「物理横过来 → 放宽成 SENSOR」与
 * 「转回竖屏 → 退出大屏」这两条路径（需要真的把手机转过去），所以判据必须由单测兜住。
 */
class BigScreenOrientationTest {

    // ---------- 物理朝向 ----------

    @Test
    fun `portrait holds are not landscape`() {
        assertFalse(BigScreenOrientation.isPhysicallyLandscape(0))   // 正持竖屏
        assertFalse(BigScreenOrientation.isPhysicallyLandscape(180)) // 倒持竖屏
        assertFalse(BigScreenOrientation.isPhysicallyLandscape(44))
        assertFalse(BigScreenOrientation.isPhysicallyLandscape(136))
    }

    @Test
    fun `landscape holds are landscape`() {
        assertTrue(BigScreenOrientation.isPhysicallyLandscape(90))
        assertTrue(BigScreenOrientation.isPhysicallyLandscape(270))
        assertTrue(BigScreenOrientation.isPhysicallyLandscape(45))
        assertTrue(BigScreenOrientation.isPhysicallyLandscape(315))
    }

    @Test
    fun `unknown and out of range degrees are not landscape`() {
        // 平放 / 传感器不可用时平台回 -1，必须当作"不是横向"（否则会立刻放宽、窗口弹回竖屏）
        assertFalse(BigScreenOrientation.isPhysicallyLandscape(BigScreenOrientation.ORIENTATION_UNKNOWN))
        assertFalse(BigScreenOrientation.isPhysicallyLandscape(360))
        assertFalse(BigScreenOrientation.isPhysicallyLandscape(1440)) // 多圈之后仍是 0°
        // 负角度先归一化再判定：-90° ≡ 270°（左横持）
        assertTrue(BigScreenOrientation.isPhysicallyLandscape(-90))
    }

    // ---------- 放宽判定 ----------

    @Test
    fun `relax only after the device is physically landscape`() {
        assertFalse(BigScreenOrientation.shouldRelaxToSensor(bigScreen = true, alreadyRelaxed = false, degrees = 0))
        assertFalse(BigScreenOrientation.shouldRelaxToSensor(bigScreen = true, alreadyRelaxed = false, degrees = -1))
        assertTrue(BigScreenOrientation.shouldRelaxToSensor(bigScreen = true, alreadyRelaxed = false, degrees = 90))
    }

    @Test
    fun `relax is idempotent and never fires outside big screen`() {
        assertFalse(BigScreenOrientation.shouldRelaxToSensor(bigScreen = true, alreadyRelaxed = true, degrees = 90))
        assertFalse(BigScreenOrientation.shouldRelaxToSensor(bigScreen = false, alreadyRelaxed = false, degrees = 90))
    }

    // ---------- 退出判定 ----------

    @Test
    fun `rotating back to portrait exits big screen`() {
        assertTrue(BigScreenOrientation.shouldExitOnConfiguration(bigScreen = true, orientationLandscape = false))
        assertFalse(BigScreenOrientation.shouldExitOnConfiguration(bigScreen = true, orientationLandscape = true))
        assertFalse(BigScreenOrientation.shouldExitOnConfiguration(bigScreen = false, orientationLandscape = false))
    }
}
