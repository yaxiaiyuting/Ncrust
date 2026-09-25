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

// 降级规则是本测试类的重点：灰度 / 空输入必须返回 null（App 回落到预设主题），
// 绝不抛异常；彩色封面必须给出浅色与深色两套不同 primary 的配色。
// 期望的 11 个角色色由官方 Python 实现（PyPI material-color-utilities 0.2.6）逐值复核过：
//   种子 0xFFD32F2F -> light 0xFFBA1A20 0xFFFFFFFF 0xFFFFDAD6 0xFF775653 0xFF725B2E
//                            0xFFFFFBFF 0xFFF5DDDB 0xFF201A19 0xFF534342 0xFF857371 0xFFD8C2BF
//   种子 0xFFD32F2F -> dark  0xFFFFB3AC 0xFF680008 0xFF930010 0xFFE7BDB8 0xFFE1C38C
//                            0xFF201A19 0xFF534342 0xFFEDE0DE 0xFFD8C2BF 0xFFA08C8A 0xFF534342

package com.takahashirinta.ncrust.ui.theme.color

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CoverPaletteExtractorTest {

    private val red = 0xFFD32F2F.toInt()

    private fun solid(argb: Int, count: Int): IntArray = IntArray(count) { argb }

    /** (a) 纯灰度封面：必须返回 null，交给 App 的预设主题。 */
    @Test
    fun grayscaleImageReturnsNull() {
        val gray = IntArray(300) {
            when (it % 3) {
                0 -> 0xFF000000.toInt()
                1 -> 0xFF808080.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
        }
        assertNull(CoverPaletteExtractor.extract(gray, false))
        assertNull(CoverPaletteExtractor.extract(gray, true))
    }

    /** (a') 灰度渐变同样必须被判定为无色彩。 */
    @Test
    fun grayRampReturnsNull() {
        val ramp = IntArray(256) { ColorUtils.argbFromRgb(it, it, it) }
        assertNull(CoverPaletteExtractor.extract(ramp, false))
    }

    /** (b) 空输入：返回 null（且不抛异常）。 */
    @Test
    fun emptyInputReturnsNull() {
        assertNull(CoverPaletteExtractor.extract(IntArray(0), false))
        assertNull(CoverPaletteExtractor.extract(IntArray(0), true))
    }

    /** 全透明像素（ARGB=0）等价于无色彩：返回 null。 */
    @Test
    fun transparentBlackReturnsNull() {
        assertNull(CoverPaletteExtractor.extract(IntArray(64), false))
    }

    /** (c) 纯饱和红封面：非 null，浅色与深色的 primary 必须不同。 */
    @Test
    fun solidRedImageProducesPaletteWhoseLightAndDarkPrimariesDiffer() {
        val pixels = solid(red, 400)
        val light = CoverPaletteExtractor.extract(pixels, false)
        val dark = CoverPaletteExtractor.extract(pixels, true)
        assertNotNull(light)
        assertNotNull(dark)
        assertNotNull(light!!)
        assertNotNull(dark!!)
        assertNotEquals(light.primary, dark.primary)
        assertEquals(0xFFBA1A20.toInt(), light.primary)
        assertEquals(0xFFFFB3AC.toInt(), dark.primary)
        // 种子色被完整保留（纯色图量化后仍是原色）。
        assertEquals(red, light.seedArgb)
        assertEquals(red, dark.seedArgb)
        assertEquals(false, light.isDark)
        assertEquals(true, dark.isDark)
    }

    /** 浅色/深色两套全部 11 个角色与官方实现逐值一致。 */
    @Test
    fun paletteValuesMatchUpstreamImplementation() {
        val pixels = solid(red, 400)
        val light = CoverPaletteExtractor.extract(pixels, false)!!
        assertEquals(0xFFBA1A20.toInt(), light.primary)
        assertEquals(0xFFFFFFFF.toInt(), light.onPrimary)
        assertEquals(0xFFFFDAD6.toInt(), light.primaryContainer)
        assertEquals(0xFF775653.toInt(), light.secondary)
        assertEquals(0xFF725B2E.toInt(), light.tertiary)
        assertEquals(0xFFFFFBFF.toInt(), light.neutral)
        assertEquals(0xFFF5DDDB.toInt(), light.neutralVariant)
        assertEquals(0xFF201A19.toInt(), light.onSurface)
        assertEquals(0xFF534342.toInt(), light.onSurfaceVariant)
        assertEquals(0xFF857371.toInt(), light.outline)
        assertEquals(0xFFD8C2BF.toInt(), light.outlineVariant)

        val dark = CoverPaletteExtractor.extract(pixels, true)!!
        assertEquals(0xFFFFB3AC.toInt(), dark.primary)
        assertEquals(0xFF680008.toInt(), dark.onPrimary)
        assertEquals(0xFF930010.toInt(), dark.primaryContainer)
        assertEquals(0xFFE7BDB8.toInt(), dark.secondary)
        assertEquals(0xFFE1C38C.toInt(), dark.tertiary)
        assertEquals(0xFF201A19.toInt(), dark.neutral)
        assertEquals(0xFF534342.toInt(), dark.neutralVariant)
        assertEquals(0xFFEDE0DE.toInt(), dark.onSurface)
        assertEquals(0xFFD8C2BF.toInt(), dark.onSurfaceVariant)
        assertEquals(0xFFA08C8A.toInt(), dark.outline)
        assertEquals(0xFF534342.toInt(), dark.outlineVariant)
    }

    /** 直接调用 CoverPalette 的两个模式入口，结果必须与 extract 一致。 */
    @Test
    fun coverPaletteModeAccessorsAgree() {
        val light = CoverPalette.forMode(red, false)
        val dark = CoverPalette.forMode(red, true)
        assertEquals(CoverPalette.light(red), light)
        assertEquals(CoverPalette.dark(red), dark)
        assertEquals(CoverPalette.fromSeed(red, false), light)
        assertEquals(light, CoverPaletteExtractor.extract(solid(red, 16), false))
    }

    /** Compose Color 只是 Int 的包装（本包唯一允许 import androidx 的地方）。 */
    @Test
    fun composeColorAccessorsMirrorArgb() {
        val palette = CoverPalette.light(red)
        assertEquals(palette.primary, palette.primaryColor.toArgb())
        assertEquals(palette.onSurface, palette.onSurfaceColor.toArgb())
        assertEquals(palette.neutral, palette.neutralColor.toArgb())
        assertEquals(palette.outline, palette.outlineColor.toArgb())
    }

    /** (d) 同一输入两次必须给出完全相同的输出（纯函数 / 确定性）。 */
    @Test
    fun extractionIsDeterministic() {
        val pixels = IntArray(200) {
            when (it % 4) {
                0 -> 0xFFE53935.toInt()
                1 -> 0xFF43A047.toInt()
                2 -> 0xFF1E88E5.toInt()
                else -> 0xFFFDD835.toInt()
            }
        }
        val first = CoverPaletteExtractor.extract(pixels, false)
        val second = CoverPaletteExtractor.extract(pixels, false)
        assertEquals(first, second)
        assertNotNull(first)
        // 输入数组不被修改（纯函数：不写出参数）。
        val snapshot = pixels.copyOf()
        CoverPaletteExtractor.extract(pixels, true)
        org.junit.Assert.assertArrayEquals(snapshot, pixels)
    }

    /** 多种颜色混合时选出的种子色 = 人口最多且彩度合适的那一个。 */
    @Test
    fun multiColorImagePicksTopScoredSeed() {
        val pixels = ArrayList<Int>(200)
        repeat(100) { pixels.add(0xFFE53935.toInt()) }
        repeat(60) { pixels.add(0xFF43A047.toInt()) }
        repeat(40) { pixels.add(0xFF1E88E5.toInt()) }
        val palette = CoverPaletteExtractor.extract(pixels.toIntArray(), false)
        assertNotNull(palette)
        assertEquals(0xFFE53935.toInt(), palette!!.seedArgb)
    }

    /** (e) 单像素图像不得抛异常；彩色返回配色、灰度返回 null。 */
    @Test
    fun singlePixelImageDoesNotThrow() {
        assertNotNull(CoverPaletteExtractor.extract(intArrayOf(red), true))
        assertNotNull(CoverPaletteExtractor.extract(intArrayOf(red), false))
        assertNull(CoverPaletteExtractor.extract(intArrayOf(0xFF808080.toInt()), true))
    }

    /** 阈值必须是 4.0，且 Hct 与 Extractor 暴露的是同一个常量。 */
    @Test
    fun grayscaleThresholdIsDocumentedConstant() {
        assertEquals(4.0, CoverPaletteExtractor.GRAYSCALE_CHROMA_THRESHOLD, 1e-12)
        assertEquals(4.0, Hct.HCT_CHROMA_THRESHOLD, 1e-12)
    }

    /** 阈值边界行为：chroma 恰好 >= 4 的颜色应能生成配色（用 #E0E0E0=2.65 与 #808080=1.90 对照）。 */
    @Test
    fun colorsJustBelowThresholdAreRejected() {
        assertNull(CoverPaletteExtractor.extract(solid(0xFFE0E0E0.toInt(), 64), false))
        assertNull(CoverPaletteExtractor.extract(solid(0xFF9E9E9E.toInt(), 64), false))
        assertNotNull(CoverPaletteExtractor.extract(solid(0xFFFF0000.toInt(), 64), false))
    }

    /** 常量：量化上限与打分数量必须与文档一致。 */
    @Test
    fun extractorConstantsAreStable() {
        assertEquals(128, CoverPaletteExtractor.MAX_QUANTIZE_COLORS)
        assertEquals(1, CoverPaletteExtractor.SCORE_DESIRED)
    }
}
