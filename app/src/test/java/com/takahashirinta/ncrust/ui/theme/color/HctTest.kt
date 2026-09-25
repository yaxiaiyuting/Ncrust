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

// 期望值的来源（不是"跑出来多少就写多少"）：
// 1. material-color-utilities 官方测试 java/hct/HctTest.java 在 main 分支上**已不存在**
//    （全仓库 grep 无 *Test.java，见 https://github.com/material-foundation/material-color-utilities/tree/main/java/hct ），
//    官方现存的 HCT 断言在 TypeScript 版：typescript/hct/hct_test.ts。
//    本文件里 green / blue / from(282.788,87.230,90) 的期望值直接取自该文件。
// 2. 0x4285F4 的期望值用官方发布的 Python 实现（PyPI `material-color-utilities` 0.2.6，
//    Rust/pybind 实现）独立复核过，见 HctTest.googleBlue 的注释。

package com.takahashirinta.ncrust.ui.theme.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HctTest {

    /**
     * Google Blue #4285F4（也是 Score 的兜底色）。
     *
     * ⚠️ 任务书给的 ballpark 是 hue ≈ 220 / chroma 68–72 / tone 53，**与真实 HCT 不符**：
     * 220 是 #4285F4 的 **HSV/HSL** 色相（217.5），不是 HCT/CAM16 色相。实测：
     *   本移植：        hue=265.9794 chroma=62.2691 tone=56.5503
     *   官方 Python 0.2.6：hue=265.979403 chroma=62.269111 tone=56.550349
     * 两者一致到 1e-7（仅浮点末位差），所以这里断言真实值 266/62.27/56.55，
     * 而不是任务书里那个基于 HSV 的 ballpark。
     */
    @Test
    fun googleBlueMatchesUpstreamHct() {
        val hct = Hct.fromInt(0xFF4285F4.toInt())
        assertEquals(265.979, hct.hue, 0.05)
        assertEquals(62.269, hct.chroma, 0.05)
        assertEquals(56.550, hct.tone, 0.05)
    }

    /** 官方 typescript/hct/hct_test.ts: "ARGB to HCT / green"。 */
    @Test
    fun greenMatchesUpstreamTestFile() {
        val hct = Hct.fromInt(0xFF00FF00.toInt())
        assertEquals(142.139, hct.hue, 0.01)
        assertEquals(108.410, hct.chroma, 0.01)
        assertEquals(87.737, hct.tone, 0.01)
    }

    /** 官方 typescript/hct/hct_test.ts: "ARGB to HCT / blue"。 */
    @Test
    fun blueMatchesUpstreamTestFile() {
        val hct = Hct.fromInt(0xFF0000FF.toInt())
        assertEquals(282.788, hct.hue, 0.01)
        assertEquals(87.230, hct.chroma, 0.01)
        assertEquals(32.302, hct.tone, 0.01)
    }

    /**
     * 官方 typescript/hct/hct_test.ts: "ARGB to HCT / blue tone 90"。
     * 这条同时验证 HctSolver.findResultByJ 的牛顿迭代路径。
     */
    @Test
    fun solveToIntMatchesUpstreamTestFile() {
        val hct = Hct.from(282.788, 87.230, 90.0)
        assertEquals(282.239, hct.hue, 0.01)
        assertEquals(19.144, hct.chroma, 0.01)
        assertEquals(90.035, hct.tone, 0.01)
    }

    /**
     * 白色在 CAM16 里**不是**严格无彩色的：D65 白点在 CAM16 下被测量为略带蓝的色（hue≈209.5）。
     * 官方 typescript/hct/hct_test.ts 的 CAM16 白点断言就是 chroma 2.869（不是 0），
     * 本移植复现同样的值。它仍然远低于 HCT_CHROMA_THRESHOLD(4.0)，所以取色器会把纯白封面判成灰度。
     */
    @Test
    fun whiteIsNearlyAchromaticButNotExactlyZero() {
        val hct = Hct.fromInt(0xFFFFFFFF.toInt())
        assertEquals(2.869, hct.chroma, 0.01)
        assertTrue(
            "白色 chroma 必须低于灰度阈值，否则纯白封面不会被降级",
            hct.chroma < Hct.HCT_CHROMA_THRESHOLD,
        )
        assertEquals(209.492, hct.hue, 0.01)
        assertEquals(100.0, hct.tone, 1e-9)
    }

    @Test
    fun blackHasZeroToneAndZeroChroma() {
        val hct = Hct.fromInt(0xFF000000.toInt())
        assertEquals(0.0, hct.tone, 1e-9)
        assertEquals(0.0, hct.chroma, 1e-9)
        assertEquals(0xFF000000.toInt(), hct.toInt())
    }

    @Test
    fun grayIsAchromaticWithinThreshold() {
        for (argb in intArrayOf(0xFF000000.toInt(), 0xFF808080.toInt(), 0xFFEEEEEE.toInt(), 0xFFFFFFFF.toInt())) {
            val hct = Hct.fromInt(argb)
            assertTrue(
                "0x${Integer.toHexString(argb)} 的 chroma=${hct.chroma} 应低于灰度阈值",
                hct.chroma < Hct.HCT_CHROMA_THRESHOLD,
            )
        }
    }

    /** Hct.from(h,c,t) -> toInt() -> fromInt() -> toInt() 必须是不动点（同一个 ARGB）。 */
    @Test
    fun roundTripIsStable() {
        val cases = listOf(
            Triple(220.0, 60.0, 40.0),
            Triple(23.5, 83.3, 47.0),
            Triple(265.98, 62.27, 56.55),
            Triple(142.14, 108.41, 87.74),
            Triple(0.0, 0.0, 50.0),
        )
        for ((h, c, t) in cases) {
            val first = Hct.from(h, c, t).toInt()
            val second = Hct.fromInt(first).toInt()
            assertEquals("HCT($h,$c,$t) 往返不稳定", first, second)
        }
    }

    /**
     * 官方 typescript/hct/hct_test.ts 的 "CamSolver" 用例：色域内解应满足
     * tone 误差 <= 0.5、chroma 不超过请求值 + 2.5、hue 误差 <= 4 度（chroma > 0 时）。
     * 这里抽样取 12 x 5 x 4 = 240 组，覆盖全部色相区段。
     */
    @Test
    fun solverStaysWithinUpstreamTolerances() {
        var hue = 15.0
        while (hue < 360.0) {
            var chroma = 0.0
            while (chroma <= 100.0) {
                var tone = 20.0
                while (tone <= 80.0) {
                    val hct = Hct.from(hue, chroma, tone)
                    if (chroma > 0) {
                        assertTrue(
                            "hue=$hue chroma=$chroma tone=$tone -> hue=${hct.hue}",
                            Math.abs(hct.hue - hue) <= 4.0,
                        )
                    }
                    assertTrue(hct.chroma >= 0.0)
                    assertTrue(
                        "hue=$hue chroma=$chroma tone=$tone -> chroma=${hct.chroma}",
                        hct.chroma <= chroma + 2.5,
                    )
                    assertTrue(
                        "hue=$hue chroma=$chroma tone=$tone -> tone=${hct.tone}",
                        Math.abs(hct.tone - tone) <= 0.5,
                    )
                    tone += 20.0
                }
                chroma += 25.0
            }
            hue += 30.0
        }
    }

    /** 区间与上游 `Hct.java:131-141` 完全一致：isBlue [250,270)、isYellow [105,125)、isCyan [170,207)。 */
    @Test
    fun huePredicatesMatchUpstreamRanges() {
        assertTrue(Hct.isBlue(250.0))
        assertTrue(Hct.isBlue(269.9))
        assertTrue(!Hct.isBlue(270.0))
        assertTrue(!Hct.isBlue(249.9))
        assertTrue(Hct.isYellow(105.0))
        assertTrue(!Hct.isYellow(125.0))
        assertTrue(Hct.isCyan(170.0))
        assertTrue(!Hct.isCyan(207.0))
    }

    /** setHue / setChroma / setTone 与上游同样会因色域边界而降低 chroma，但必须落在合法范围。 */
    @Test
    fun settersKeepValuesInRange() {
        val hct = Hct.fromInt(0xFF4285F4.toInt())
        hct.setTone(95.0)
        assertEquals(95.0, hct.tone, 0.5)
        hct.setHue(120.0)
        assertEquals(120.0, hct.hue, 4.0)
        assertTrue(hct.chroma >= 0.0)
    }

    /** inViewingConditions：把颜色搬到别的观察条件下，tone 必须跟着变而 hue 大致保持。 */
    @Test
    fun inViewingConditionsKeepsHueAndChangesTone() {
        val original = Hct.fromInt(0xFF4285F4.toInt())
        val dark = ViewingConditions.defaultWithBackgroundLstar(5.0)
        val recast = original.inViewingConditions(dark)
        assertTrue(Math.abs(recast.hue - original.hue) <= 6.0)
        assertTrue(recast.tone < original.tone)
    }
}
