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

// 期望值来源：TonalPalette 是 HctSolver + KeyColor 的组合，官方 Python 实现
// （PyPI material-color-utilities 0.2.6）对同一 hue/chroma 给出的 tone 值与本文件完全一致：
//   TonalPalette(220.0, 60.0).get_argb(t)  ->  0/10/40/50/80/90/99/100
//   = 0xFF000000 / 0xFF001F27 / 0xFF00677C / 0xFF00829C / 0xFF2CD8FF / 0xFFB1EBFF / 0xFFF9FDFF / 0xFFFFFFFF
//   key_color: hue=220.176219 chroma=55.552606 tone=78.893728

package com.takahashirinta.ncrust.ui.theme.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TonalPaletteTest {

    private val palette = TonalPalette.fromHueAndChroma(220.0, 60.0)

    /** 与官方实现逐值一致（见文件头注释）。 */
    @Test
    fun toneValuesMatchUpstreamImplementation() {
        assertEquals(0xFF000000.toInt(), palette.tone(0))
        assertEquals(0xFF001F27.toInt(), palette.tone(10))
        assertEquals(0xFF00677C.toInt(), palette.tone(40))
        assertEquals(0xFF00829C.toInt(), palette.tone(50))
        assertEquals(0xFF2CD8FF.toInt(), palette.tone(80))
        assertEquals(0xFFB1EBFF.toInt(), palette.tone(90))
        assertEquals(0xFFF9FDFF.toInt(), palette.tone(99))
        assertEquals(0xFFFFFFFF.toInt(), palette.tone(100))
    }

    @Test
    fun toneZeroIsBlackAndToneHundredIsWhite() {
        assertEquals(0xFF000000.toInt(), palette.tone(0))
        assertEquals(0.0, ColorUtils.lstarFromArgb(palette.tone(0)), 1e-9)
        assertEquals(0xFFFFFFFF.toInt(), palette.tone(100))
        assertEquals(100.0, ColorUtils.lstarFromArgb(palette.tone(100)), 1e-9)
    }

    @Test
    fun tone40IsDarkerThanTone80() {
        val dark = ColorUtils.lstarFromArgb(palette.tone(40))
        val light = ColorUtils.lstarFromArgb(palette.tone(80))
        assertTrue("tone40 lstar=$dark 应小于 tone80 lstar=$light", dark < light)
        assertEquals(40.0, dark, 1.0)
        assertEquals(80.0, light, 1.0)
    }

    @Test
    fun hueIsPreservedAcrossTones() {
        for (tone in intArrayOf(20, 30, 40, 50, 60, 70, 80)) {
            val hct = Hct.fromInt(palette.tone(tone))
            assertEquals("tone=$tone 的色相偏离", 220.0, hct.hue, 3.0)
        }
        assertEquals(220.0, Hct.fromInt(palette.tone(50)).hue, 3.0)
    }

    @Test
    fun tonalPaletteExposesRequestedHueAndChroma() {
        assertEquals(220.0, palette.hue, 1e-9)
        assertEquals(60.0, palette.chroma, 1e-9)
    }

    /**
     * keyColor：从 T50 起、第一个能达到请求彩度的 tone。
     * hue 220 / chroma 60 在 sRGB 内**不可达**（实测最大约 55.55），所以 keyColor 的 chroma 会低于请求值 ——
     * 这正是上游注释里说的 "The color returned may be lower than the requested chroma"。
     */
    @Test
    fun keyColorMatchesUpstreamAndIsGamutLimitedWhenNeeded() {
        assertEquals(220.176, palette.keyColor.hue, 0.5)
        assertEquals(55.553, palette.keyColor.chroma, 0.5)
        assertEquals(78.894, palette.keyColor.tone, 0.5)
        assertTrue(palette.keyColor.chroma < 60.0)
    }

    /**
     * chroma 可达时 keyColor 会命中请求值附近。实测（并与官方 Python 实现逐值一致）：
     *   TonalPalette(220.0, 20.0).key_color -> hue=219.078959 chroma=19.786358 tone=50.053751
     * 注意它并不精确等于 20：二分找到的 tone 处「最大可达 chroma」>= 19.99，但请求 20 在该 tone
     * 上仍可能被色域边界削掉一点，这正是上游文档说的 "may be lower than the requested chroma"。
     */
    @Test
    fun keyColorHitsRequestedChromaWhenAchievable() {
        val reachable = TonalPalette.fromHueAndChroma(220.0, 20.0)
        assertEquals(219.079, reachable.keyColor.hue, 0.01)
        assertEquals(19.786, reachable.keyColor.chroma, 0.01)
        assertEquals(50.054, reachable.keyColor.tone, 0.01)
        assertTrue(reachable.keyColor.chroma <= 20.0 + 0.01)
    }

    /** fromInt / fromHct：直接沿用该色的 hue 与 chroma；种子色本身应能被重新生成。 */
    @Test
    fun fromIntUsesColorHueAndChroma() {
        val seed = 0xFF4285F4.toInt()
        val fromInt = TonalPalette.fromInt(seed)
        assertEquals(Hct.fromInt(seed).hue, fromInt.hue, 1e-9)
        assertEquals(Hct.fromInt(seed).chroma, fromInt.chroma, 1e-9)
        assertEquals(seed, fromInt.keyColor.toInt())

        val fromHct = TonalPalette.fromHct(Hct.fromInt(seed))
        assertEquals(fromInt.hue, fromHct.hue, 1e-9)
        assertEquals(fromInt.chroma, fromHct.chroma, 1e-9)
    }

    /**
     * 黄色（HCT hue 105..125）的 tone 99 是特例：上游取 tone 98 与 tone 100 的 ARGB 平均值，
     * 因为黄色在 tone 99 处会因色域边界抖动。
     */
    @Test
    fun yellowTone99IsAverageOf98And100() {
        val yellow = TonalPalette.fromHueAndChroma(112.0, 60.0)
        assertTrue(Hct.isYellow(yellow.hue))
        val t98 = yellow.tone(98)
        val t100 = yellow.tone(100)
        val expected = ColorUtils.argbFromRgb(
            Math.round((ColorUtils.redFromArgb(t98) + ColorUtils.redFromArgb(t100)) / 2f),
            Math.round((ColorUtils.greenFromArgb(t98) + ColorUtils.greenFromArgb(t100)) / 2f),
            Math.round((ColorUtils.blueFromArgb(t98) + ColorUtils.blueFromArgb(t100)) / 2f),
        )
        assertEquals(expected, yellow.tone(99))
        assertEquals(0xFFFFFFCC.toInt(), yellow.tone(99))
        // hct(99) 走的是 tone(99) 反解，与 tone(99) 自洽。
        assertEquals(yellow.tone(99), yellow.hct(99.0).toInt())
    }

    /** 非黄色 palette 的 hct(tone) 等于 Hct.from(hue, chroma, tone)。 */
    @Test
    fun hctMatchesDirectConstructionForNonYellow() {
        val hct = palette.hct(40.0)
        assertEquals(220.0, hct.hue, 3.0)
        assertEquals(40.0, hct.tone, 1.0)
        assertEquals(palette.tone(40), hct.toInt())
    }

    /** tone() 有缓存：同一个 tone 重复取必须是同一个值（且不会因为缓存破坏前一次结果）。 */
    @Test
    fun toneIsCachedAndStable() {
        val first = palette.tone(45)
        val second = palette.tone(45)
        assertEquals(first, second)
        // 交错访问其他 tone 不得污染缓存。
        palette.tone(46)
        assertEquals(first, palette.tone(45))
    }

    /** 彩度越高 tone 40 越鲜艳（chroma 更大的 palette 在 tone 40 处彩度不应更低）。 */
    @Test
    fun higherRequestedChromaGivesHigherActualChroma() {
        val low = TonalPalette.fromHueAndChroma(220.0, 16.0)
        val high = TonalPalette.fromHueAndChroma(220.0, 60.0)
        assertTrue(Hct.fromInt(high.tone(40)).chroma > Hct.fromInt(low.tone(40)).chroma)
    }
}
