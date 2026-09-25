/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// 期望值取自官方 typescript/hct/hct_test.ts（java/hct/HctTest.java 在 main 分支已不存在）：
//   describe('CAM to ARGB') 与 describe('viewing conditions') 两节。

package com.takahashirinta.ncrust.ui.theme.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Cam16Test {

    @Test
    fun redMatchesUpstreamTestFile() {
        val cam = Cam16.fromInt(0xFFFF0000.toInt())
        assertEquals(27.408, cam.hue, 0.001)
        assertEquals(113.358, cam.chroma, 0.001)
        assertEquals(46.445, cam.j, 0.001)
        assertEquals(89.494, cam.m, 0.001)
        assertEquals(91.890, cam.s, 0.001)
        assertEquals(105.989, cam.q, 0.001)
    }

    @Test
    fun greenMatchesUpstreamTestFile() {
        val cam = Cam16.fromInt(0xFF00FF00.toInt())
        assertEquals(142.140, cam.hue, 0.001)
        assertEquals(108.410, cam.chroma, 0.001)
        assertEquals(79.332, cam.j, 0.001)
        assertEquals(85.588, cam.m, 0.001)
        assertEquals(78.605, cam.s, 0.001)
        assertEquals(138.520, cam.q, 0.001)
    }

    @Test
    fun blueMatchesUpstreamTestFile() {
        val cam = Cam16.fromInt(0xFF0000FF.toInt())
        assertEquals(282.788, cam.hue, 0.001)
        assertEquals(87.231, cam.chroma, 0.001)
        assertEquals(25.466, cam.j, 0.001)
        assertEquals(68.867, cam.m, 0.001)
        assertEquals(93.675, cam.s, 0.001)
        assertEquals(78.481, cam.q, 0.001)
    }

    /** 白色在 CAM16 下是「带一点点蓝」的：hue 209.492、chroma 2.869（官方测试值）。 */
    @Test
    fun whiteMatchesUpstreamTestFile() {
        val cam = Cam16.fromInt(0xFFFFFFFF.toInt())
        assertEquals(209.492, cam.hue, 0.001)
        assertEquals(2.869, cam.chroma, 0.001)
        assertEquals(100.0, cam.j, 1e-6)
        assertEquals(2.265, cam.m, 0.001)
        assertEquals(12.068, cam.s, 0.001)
        assertEquals(155.521, cam.q, 0.001)
    }

    @Test
    fun blackIsAllZero() {
        val cam = Cam16.fromInt(0xFF000000.toInt())
        assertEquals(0.0, cam.hue, 1e-9)
        assertEquals(0.0, cam.chroma, 1e-9)
        assertEquals(0.0, cam.j, 1e-9)
        assertEquals(0.0, cam.m, 1e-9)
        assertEquals(0.0, cam.s, 1e-9)
        assertEquals(0.0, cam.q, 1e-9)
    }

    /** 官方 typescript/hct/hct_test.ts: "CAM to ARGB to CAM" 要求红/绿/蓝往返完全相等。 */
    @Test
    fun toIntRoundTripsExactly() {
        for (argb in intArrayOf(
            0xFFFF0000.toInt(),
            0xFF00FF00.toInt(),
            0xFF0000FF.toInt(),
            0xFF4285F4.toInt(),
            0xFF1DB954.toInt(),
            0xFFF6F2E9.toInt(),
            0xFF123456.toInt(),
            0xFF808080.toInt(),
        )) {
            val back = Cam16.fromInt(argb).toInt()
            assertEquals("0x${Integer.toHexString(argb)} 往返不相等", argb, back)
        }
    }

    /** 逐通道容差 ±1 的弱化版本：覆盖更多随机色，允许极少数色域边界上的 1/255 误差。 */
    @Test
    fun toIntRoundTripsWithinOnePerChannel() {
        var argb = 0xFF000000.toInt()
        var i = 0
        while (i < 512) {
            val back = Cam16.fromInt(argb).toInt()
            assertEquals(ColorUtils.redFromArgb(argb).toDouble(), ColorUtils.redFromArgb(back).toDouble(), 1.0)
            assertEquals(ColorUtils.greenFromArgb(argb).toDouble(), ColorUtils.greenFromArgb(back).toDouble(), 1.0)
            assertEquals(ColorUtils.blueFromArgb(argb).toDouble(), ColorUtils.blueFromArgb(back).toDouble(), 1.0)
            // 固定步长遍历整个 RGB 立方体（0x010203 步进），保证确定性。
            argb = argb + 0x00010203
            if (argb > 0xFFFFFF) {
                argb = 0xFF000000.toInt() + (argb and 0xFFFFFF)
            }
            i++
        }
    }

    /** 中性灰的 CAM16 chroma 必须接近 0（取色器据此判定"封面是灰度"）。 */
    @Test
    fun graysHaveNearZeroChroma() {
        for (argb in intArrayOf(0xFF000000.toInt(), 0xFF404040.toInt(), 0xFF808080.toInt(), 0xFFC0C0C0.toInt(), 0xFFFFFFFF.toInt())) {
            val cam = Cam16.fromInt(argb)
            assertTrue("0x${Integer.toHexString(argb)} chroma=${cam.chroma}", cam.chroma < 3.0)
        }
    }

    /** 官方 typescript/hct/hct_test.ts: describe('viewing conditions') / 'default'。 */
    @Test
    fun defaultViewingConditionsMatchUpstreamTestFile() {
        val vc = ViewingConditions.DEFAULT
        assertEquals(0.184, vc.n, 0.001)
        assertEquals(29.981, vc.aw, 0.001)
        assertEquals(1.017, vc.nbb, 0.001)
        assertEquals(1.017, vc.ncb, 0.001)
        assertEquals(0.69, vc.c, 0.001)
        assertEquals(1.0, vc.nc, 0.001)
        assertEquals(1.021, vc.rgbD[0], 0.001)
        assertEquals(0.986, vc.rgbD[1], 0.001)
        assertEquals(0.934, vc.rgbD[2], 0.001)
        assertEquals(0.388, vc.fl, 0.001)
        assertEquals(0.789, vc.flRoot, 0.001)
        assertEquals(1.909, vc.z, 0.001)
    }

    /** make() 的自变量语义：背景 L* 越低，中性度 n 越小、z 越小。 */
    @Test
    fun makeRespectsBackgroundLstar() {
        val bright = ViewingConditions.defaultWithBackgroundLstar(100.0)
        val mid = ViewingConditions.defaultWithBackgroundLstar(50.0)
        val dark = ViewingConditions.defaultWithBackgroundLstar(0.0)
        assertTrue(bright.n > mid.n)
        assertTrue(mid.n > dark.n)
        assertTrue(bright.z > mid.z)
        assertTrue(mid.z > dark.z)
        // 纯黑背景是非物理的，上游把下限钳在 0.1，因此 n 永远为正。
        assertTrue(dark.n > 0.0)
    }

    /** fromXyz / xyzInViewingConditions 必须与 fromInt / toInt 走同一条路径。 */
    @Test
    fun fromXyzAgreesWithFromInt() {
        val argb = 0xFF4285F4.toInt()
        val xyz = ColorUtils.xyzFromArgb(argb)
        val viaXyz = Cam16.fromXyz(xyz[0], xyz[1], xyz[2])
        val viaInt = Cam16.fromInt(argb)
        assertEquals(viaInt.hue, viaXyz.hue, 1e-9)
        assertEquals(viaInt.chroma, viaXyz.chroma, 1e-9)
        assertEquals(viaInt.j, viaXyz.j, 1e-9)
        // XYZ -> CAM16 -> XYZ 不是位级可逆（pow/log 的往返误差约 1e-6），容差取 1e-4。
        assertEquals(0.0, viaXyz.toXyz()[0] - xyz[0], 1e-4)
        assertEquals(0.0, viaXyz.toXyz()[1] - xyz[1], 1e-4)
        assertEquals(0.0, viaXyz.toXyz()[2] - xyz[2], 1e-4)
    }

    /** xyzInViewingConditions 复用传入数组时必须返回同一个实例（上游的 tempArray 优化）。 */
    @Test
    fun xyzInViewingConditionsReusesReturnArray() {
        val cam = Cam16.fromInt(0xFF4285F4.toInt())
        val buffer = DoubleArray(3)
        val returned = cam.xyzInViewingConditions(ViewingConditions.DEFAULT, buffer)
        assertTrue(returned === buffer)
        val fresh = cam.xyzInViewingConditions(ViewingConditions.DEFAULT)
        assertEquals(fresh[0], buffer[0], 1e-12)
        assertEquals(fresh[1], buffer[1], 1e-12)
        assertEquals(fresh[2], buffer[2], 1e-12)
    }

    /** fromJch / fromUcs 与 fromXyz 三条构造路径必须自洽。 */
    @Test
    fun jchAndUcsConstructorsAreConsistent() {
        val cam = Cam16.fromInt(0xFF1DB954.toInt())
        val fromJch = Cam16.fromJch(cam.j, cam.chroma, cam.hue)
        assertEquals(cam.hue, fromJch.hue, 1e-9)
        assertEquals(cam.chroma, fromJch.chroma, 1e-9)
        assertEquals(cam.j, fromJch.j, 1e-9)
        assertEquals(cam.jstar, fromJch.jstar, 1e-9)

        val fromUcs = Cam16.fromUcs(cam.jstar, cam.astar, cam.bstar)
        assertEquals(cam.hue, fromUcs.hue, 1e-6)
        assertEquals(cam.chroma, fromUcs.chroma, 1e-6)
        assertEquals(cam.j, fromUcs.j, 1e-6)
    }

    /** distance() 是 CAM16-UCS 的 delta E：同色为 0，差别越大越大。 */
    @Test
    fun distanceIsZeroForIdenticalColors() {
        val a = Cam16.fromInt(0xFF1DB954.toInt())
        val b = Cam16.fromInt(0xFF1DB954.toInt())
        val c = Cam16.fromInt(0xFFEF4444.toInt())
        assertEquals(0.0, a.distance(b), 1e-12)
        assertTrue(a.distance(c) > 0.0)
    }

    /** chromaticAdaptation 与上游 HctSolver 的同名函数同式：奇函数、在 0 处为 0、单调。 */
    @Test
    fun chromaticAdaptationIsOddAndMonotonic() {
        assertEquals(0.0, Cam16.chromaticAdaptation(0.0), 1e-12)
        assertEquals(-Cam16.chromaticAdaptation(30.0), Cam16.chromaticAdaptation(-30.0), 1e-12)
        assertTrue(Cam16.chromaticAdaptation(30.0) > Cam16.chromaticAdaptation(10.0))
        assertTrue(Cam16.chromaticAdaptation(30.0) < 400.0)
    }
}
