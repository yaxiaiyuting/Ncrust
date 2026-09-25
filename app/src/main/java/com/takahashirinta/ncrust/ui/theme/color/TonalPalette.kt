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

// 本文件派生自 material-foundation/material-color-utilities（Apache-2.0）。
// 上游源文件：
//   https://github.com/material-foundation/material-color-utilities/blob/main/java/palettes/TonalPalette.java
// 包括 T50 起、按 maxChroma 二分查找的 KeyColor 逻辑与「黄色 palette 的 tone 99 取 98/100 平均值」
// 这一特例，全部照抄。不得引入 android.* / androidx.*。

package com.takahashirinta.ncrust.ui.theme.color

/**
 * A convenience class for retrieving colors that are constant in hue and chroma, but vary in tone.
 *
 * 注意：与上游一样，本类**不是线程安全**的（tone() 内部有缓存）。每次取色都新建实例即可。
 */
class TonalPalette private constructor(
    /** 调色板色相（HCT），0..360。 */
    val hue: Double,
    /** 调色板彩度（HCT），0..约 130（sRGB 色域上限）。 */
    val chroma: Double,
    /** 关键色：从 T50 出发、第一个能达到请求彩度的 tone。 */
    val keyColor: Hct,
) {

    private val cache = HashMap<Int, Int>()

    /**
     * Create an ARGB color with HCT hue and chroma of this Tones instance, and the provided HCT tone.
     *
     * @param tone HCT tone, 0..100
     */
    fun tone(tone: Int): Int {
        val cached = cache[tone]
        if (cached != null) {
            return cached
        }
        val color = if (tone == 99 && Hct.isYellow(hue)) {
            // 黄色在 tone 99 处会因色域边界抖动，上游改用 98 与 100 的平均值让它平滑。
            averageArgb(tone(98), tone(100))
        } else {
            Hct.from(hue, chroma, tone.toDouble()).toInt()
        }
        cache[tone] = color
        return color
    }

    /** Given a tone, use hue and chroma of palette to create a color, and return it as HCT. */
    fun hct(tone: Double): Hct =
        if (tone == 99.0 && Hct.isYellow(hue)) {
            Hct.fromInt(tone(99))
        } else {
            Hct.from(hue, chroma, tone)
        }

    private fun averageArgb(argb1: Int, argb2: Int): Int {
        val red1 = (argb1 ushr 16) and 0xff
        val green1 = (argb1 ushr 8) and 0xff
        val blue1 = argb1 and 0xff
        val red2 = (argb2 ushr 16) and 0xff
        val green2 = (argb2 ushr 8) and 0xff
        val blue2 = argb2 and 0xff
        val red = Math.round((red1 + red2) / 2f)
        val green = Math.round((green1 + green2) / 2f)
        val blue = Math.round((blue1 + blue2) / 2f)
        // 上游写作 `(255 << 24 | (red & 255) << 16 | ...) >>> 0`；`>>> 0` 对 int 是恒等变换，
        // 这里显式加括号，避免 Kotlin 中缀优先级带来的歧义。
        return (255 shl 24) or ((red and 255) shl 16) or ((green and 255) shl 8) or (blue and 255)
    }

    companion object {
        /**
         * Create tones using the HCT hue and chroma from a color.
         */
        fun fromInt(argb: Int): TonalPalette = fromHct(Hct.fromInt(argb))

        /**
         * Create tones using a HCT color.
         */
        fun fromHct(hct: Hct): TonalPalette = TonalPalette(hct.hue, hct.chroma, hct)

        /**
         * Create tones from a defined HCT hue and chroma.
         */
        fun fromHueAndChroma(hue: Double, chroma: Double): TonalPalette {
            val keyColor = KeyColor(hue, chroma).create()
            return TonalPalette(hue, chroma, keyColor)
        }
    }

    /** Key color is a color that represents the hue and chroma of a tonal palette. */
    private class KeyColor(private val hue: Double, private val requestedChroma: Double) {

        /** Cache that maps tone to max chroma to avoid duplicated HCT calculation. */
        private val chromaCache = HashMap<Int, Double>()

        /**
         * Creates a key color from a hue and a chroma. The key color is the first tone, starting
         * from T50, matching the given hue and chroma.
         */
        fun create(): Hct {
            // Pivot around T50 because T50 has the most chroma available, on average. Thus it is
            // most likely to have a direct answer.
            val pivotTone = 50
            val toneStepSize = 1
            // Epsilon to accept values slightly higher than the requested chroma.
            val epsilon = 0.01

            // Binary search to find the tone that can provide a chroma that is closest to the
            // requested chroma.
            var lowerTone = 0
            var upperTone = 100
            while (lowerTone < upperTone) {
                val midTone = (lowerTone + upperTone) / 2
                val isAscending = maxChroma(midTone) < maxChroma(midTone + toneStepSize)
                val sufficientChroma = maxChroma(midTone) >= requestedChroma - epsilon

                if (sufficientChroma) {
                    // Either range [lowerTone, midTone] or [midTone, upperTone] has the answer, so
                    // search in the range that is closer the pivot tone.
                    if (Math.abs(lowerTone - pivotTone) < Math.abs(upperTone - pivotTone)) {
                        upperTone = midTone
                    } else {
                        if (lowerTone == midTone) {
                            return Hct.from(hue, requestedChroma, lowerTone.toDouble())
                        }
                        lowerTone = midTone
                    }
                } else {
                    // As there is no sufficient chroma in the midTone, follow the direction to the
                    // chroma peak.
                    if (isAscending) {
                        lowerTone = midTone + toneStepSize
                    } else {
                        // Keep midTone for potential chroma peak.
                        upperTone = midTone
                    }
                }
            }

            return Hct.from(hue, requestedChroma, lowerTone.toDouble())
        }

        /** Find the maximum chroma for a given tone. */
        private fun maxChroma(tone: Int): Double {
            val cached = chromaCache[tone]
            if (cached == null) {
                // MAX_CHROMA_VALUE = 200：远超 sRGB 色域上限，用来探测"该 hue/tone 到底能有多彩"。
                val newChroma = Hct.from(hue, MAX_CHROMA_VALUE, tone.toDouble()).chroma
                chromaCache[tone] = newChroma
                return newChroma
            }
            return cached
        }

        private companion object {
            private const val MAX_CHROMA_VALUE = 200.0
        }
    }
}
