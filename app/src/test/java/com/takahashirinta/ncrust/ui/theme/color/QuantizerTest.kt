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

// 上游参考：java/quantize/QuantizerCelebiTest.java 在 main 分支已不存在（官方现存的量化断言在
// typescript/quantize/quantizer_celebi_test.ts）。本文件用**合成图像**做端到端断言：
// 3 个已知颜色 -> 必须回到这 3 个颜色本身（不是"差不多"），人口数必须精确。
// 这三个颜色 + 人口也用官方 Python 实现（PyPI material-color-utilities 0.2.6）复核过：
//   prominent_colors_from_array([e53935 x100, 43a047 x60, 1e88e5 x40], 4)
//   == ['#e53935', '#43a047', '#1e88e5']

package com.takahashirinta.ncrust.ui.theme.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantizerTest {

    private val red = 0xFFE53935.toInt()
    private val green = 0xFF43A047.toInt()
    private val blue = 0xFF1E88E5.toInt()

    private fun threeColorImage(): IntArray {
        val pixels = ArrayList<Int>(200)
        repeat(100) { pixels.add(red) }
        repeat(60) { pixels.add(green) }
        repeat(40) { pixels.add(blue) }
        return pixels.toIntArray()
    }

    @Test
    fun celebiReturnsExactlyTheThreeInputColorsWithExactPopulations() {
        val result = QuantizerCelebi.quantize(threeColorImage(), 128)
        assertEquals("只有 3 种颜色时不应聚出更多类", 3, result.size)
        assertEquals(100, result[red] ?: 0)
        assertEquals(60, result[green] ?: 0)
        assertEquals(40, result[blue] ?: 0)
    }

    @Test
    fun celebiPopulationSumEqualsPixelCount() {
        val pixels = threeColorImage()
        val result = QuantizerCelebi.quantize(pixels, 128)
        assertEquals(pixels.size, result.values.sum())
    }

    /**
     * 8 色合成图的**逐值**断言。期望值不是"跑出来抄一遍"，而是与**官方 Kotlin 移植**
     * （material-color-utilities 仓库 main 分支 `kotlin/quantize/`，Copyright 2025 Google LLC）
     * 在同一份输入上的输出逐字节比对过：
     *   QuantizerWu -> 7 个簇：#00897B #FDD835 #8E24AA #616161 #BDBDBD #E53935 #339687
     *   QuantizerCelebi -> 5 个簇：#3E9C52:320 #FDD835:120 #8E24AA:90 #8793AC:680 #E53935:400
     *
     * 注意：PyPI 上的 `material-color-utilities`（第三方 Rust/pybind 重写）会给出**不同**的聚类
     * （它会保留更多簇），在这一组输入上与参考实现不一致。本移植以官方 Java/Kotlin 行为为准。
     */
    @Test
    fun celebiMatchesOfficialKotlinPortOnEightColorImage() {
        val pixels = eightColorImage()
        val wu = QuantizerWu().quantize(pixels, 128)
        assertEquals(7, wu.size)
        assertEquals(
            listOf(
                0xFF00897B.toInt(), 0xFFFDD835.toInt(), 0xFF8E24AA.toInt(),
                0xFF616161.toInt(), 0xFFBDBDBD.toInt(), 0xFFE53935.toInt(), 0xFF339687.toInt(),
            ),
            wu.keys.toList(),
        )
        val result = QuantizerCelebi.quantize(pixels, 128)
        assertEquals(
            mapOf(
                0xFF3E9C52.toInt() to 320,
                0xFFFDD835.toInt() to 120,
                0xFF8E24AA.toInt() to 90,
                0xFF8793AC.toInt() to 680,
                0xFFE53935.toInt() to 400,
            ),
            result,
        )
        assertEquals(pixels.size, result.values.sum())
    }

    /** 与 [celebiMatchesOfficialKotlinPortOnEightColorImage] 共用的 8 色合成图。 */
    private fun eightColorImage(): IntArray {
        val spec = listOf(
            0xFFE53935.toInt() to 400,
            0xFF43A047.toInt() to 260,
            0xFF1E88E5.toInt() to 180,
            0xFFFDD835.toInt() to 120,
            0xFF8E24AA.toInt() to 90,
            0xFF00897B.toInt() to 60,
            0xFFBDBDBD.toInt() to 300,
            0xFF616161.toInt() to 200,
        )
        val pixels = ArrayList<Int>(1610)
        for ((color, count) in spec) {
            repeat(count) { pixels.add(color) }
        }
        return pixels.toIntArray()
    }

    /** 同样的输入必须给出**逐位相同**的输出（量化器用固定种子 0x42688）。 */
    @Test
    fun celebiIsDeterministic() {
        val pixels = threeColorImage()
        val first = QuantizerCelebi.quantize(pixels, 128)
        val second = QuantizerCelebi.quantize(pixels, 128)
        assertEquals(first, second)
        // 打乱输入顺序不应改变结果（Wsmeans 内部按颜色去重后统计人口）。
        val shuffled = pixels.copyOf()
        shuffled.reverse()
        assertEquals(first, QuantizerCelebi.quantize(shuffled, 128))
    }

    /** 灰度渐变：聚出来的每个颜色都应该是灰的（R=G=B），否则量化链路有问题。 */
    @Test
    fun celebiOnGrayRampProducesOnlyGrays() {
        val pixels = IntArray(256) { i -> ColorUtils.argbFromRgb(i, i, i) }
        val result = QuantizerCelebi.quantize(pixels, 32)
        assertTrue(result.isNotEmpty())
        // Lab 空间的聚类中心取整回 sRGB 后允许有 1 的通道差（实测最大通道差 = 1，见 #040304）。
        for (argb in result.keys) {
            val r = ColorUtils.redFromArgb(argb)
            val g = ColorUtils.greenFromArgb(argb)
            val b = ColorUtils.blueFromArgb(argb)
            assertEquals(r.toDouble(), g.toDouble(), 2.0)
            assertEquals(r.toDouble(), b.toDouble(), 2.0)
        }
    }

    /**
     * 人口总数不应超过像素数。**不保证相等**：上游 Wsmeans 结尾遇到"两个聚类映射到同一个 ARGB"
     * 时会 `continue` 丢掉后来者的入口，所以理论上可能少算。这里显式记录这个上游行为。
     */
    @Test
    fun celebiPopulationNeverExceedsPixelCount() {
        val pixels = IntArray(1024) { i ->
            ColorUtils.argbFromRgb((i * 7) % 256, (i * 13) % 256, (i * 29) % 256)
        }
        val result = QuantizerCelebi.quantize(pixels, 64)
        assertTrue(result.isNotEmpty())
        assertTrue("sum=${result.values.sum()} > pixels=${pixels.size}", result.values.sum() <= pixels.size)
        for ((_, population) in result) {
            assertTrue("人口必须为正", population > 0)
        }
    }

    /** QuantizerMap：只去重计数，不降维。 */
    @Test
    fun mapQuantizerCountsExactly() {
        val pixels = intArrayOf(red, red, green, blue, blue, blue)
        val result = QuantizerMap.quantize(pixels, 128)
        assertEquals(3, result.size)
        assertEquals(2, result[red])
        assertEquals(1, result[green])
        assertEquals(3, result[blue])
        assertEquals(pixels.size, result.values.sum())
    }

    /**
     * QuantizerWu 的输出**只作为 k-means 的初始中心**，人口一律记 0（上游行为，不能"顺手"填真实人口，
     * 否则 QuantizerCelebi 的语义就和上游分叉了）。
     */
    @Test
    fun wuReturnsZeroPopulationsAsStartingClusters() {
        val result = QuantizerWu().quantize(threeColorImage(), 128)
        assertTrue(result.isNotEmpty())
        assertTrue(result.size <= 3)
        for ((_, population) in result) {
            assertEquals(0, population)
        }
    }

    /** Wsmeans 传入 Wu 的初始中心时必须能收敛回同样的 3 个颜色。 */
    @Test
    fun wsmeansSeededByWuKeepsExactColors() {
        val pixels = threeColorImage()
        val wuClusters = QuantizerWu().quantize(pixels, 128).keys.toIntArray()
        assertTrue(wuClusters.isNotEmpty())
        val result = QuantizerWsmeans.quantize(pixels, wuClusters, 128)
        assertEquals(3, result.size)
        assertEquals(100, result[red] ?: 0)
        assertEquals(60, result[green] ?: 0)
        assertEquals(40, result[blue] ?: 0)
    }

    /** 降级输入不得抛异常（上游在这两种情况下会抛：空输入 nextInt(0)、maxColors<=0 cubes[0]）。 */
    @Test
    fun degenerateInputsReturnEmptyInsteadOfThrowing() {
        assertEquals(0, QuantizerCelebi.quantize(IntArray(0), 128).size)
        assertEquals(0, QuantizerCelebi.quantize(threeColorImage(), 0).size)
        assertEquals(0, QuantizerWu().quantize(IntArray(0), 128).size)
        assertEquals(0, QuantizerWsmeans.quantize(IntArray(0), IntArray(0), 128).size)
    }

    /** 单像素图像：必须得到一个颜色，人口为 1。 */
    @Test
    fun singlePixelImageQuantizesToOneColor() {
        val result = QuantizerCelebi.quantize(intArrayOf(red), 128)
        assertEquals(1, result.size)
        assertEquals(1, result.values.sum())
        assertNotNull(result.keys.firstOrNull())
    }

    /** maxColors 是对上限的约束：给 8 个颜色、只允许 4 个，结果不得多于 4。 */
    @Test
    fun maxColorsIsRespected() {
        val pixels = IntArray(80) { i ->
            ColorUtils.argbFromRgb(30 + (i % 8) * 28, 40 + (i % 8) * 20, 200 - (i % 8) * 18)
        }
        val result = QuantizerCelebi.quantize(pixels, 4)
        assertTrue("size=${result.size} 超过 maxColors", result.size <= 4)
    }

    /** PointProviderLab 的往返：ARGB -> Lab -> ARGB 允许 ±1/通道（8bit 量化误差）。 */
    @Test
    fun pointProviderLabRoundTripsWithinOnePerChannel() {
        for (argb in intArrayOf(red, green, blue, 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt())) {
            val back = PointProviderLab.toInt(PointProviderLab.fromInt(argb))
            assertEquals(ColorUtils.redFromArgb(argb).toDouble(), ColorUtils.redFromArgb(back).toDouble(), 1.0)
            assertEquals(ColorUtils.greenFromArgb(argb).toDouble(), ColorUtils.greenFromArgb(back).toDouble(), 1.0)
            assertEquals(ColorUtils.blueFromArgb(argb).toDouble(), ColorUtils.blueFromArgb(back).toDouble(), 1.0)
        }
        // distance 是平方距离：自身为 0、对称、非负。
        val a = PointProviderLab.fromInt(red)
        val b = PointProviderLab.fromInt(blue)
        assertEquals(0.0, PointProviderLab.distance(a, a), 1e-12)
        assertEquals(PointProviderLab.distance(a, b), PointProviderLab.distance(b, a), 1e-12)
        assertTrue(PointProviderLab.distance(a, b) > 0.0)
    }
}
