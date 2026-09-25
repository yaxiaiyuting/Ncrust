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
//   https://github.com/material-foundation/material-color-utilities/blob/main/java/score/Score.java
// 权重、目标 chroma、过滤阈值、色相邻域（hue-14 .. hue+15）与去重步长（90 -> 15 度）逐行照抄。
// 不得引入 android.* / androidx.*。

package com.takahashirinta.ncrust.ui.theme.color

/**
 * Given a large set of colors, remove colors that are unsuitable for a UI theme, and rank the rest
 * based on suitability.
 *
 * 有了它，量化阶段就可以放心用很高的聚类数（颜色不会被"和稀泥"），再在这里收敛到少数几个
 * 真正适合当主题色的候选。
 */
object Score {

    /** A1 Chroma：Material 的 A1 色板目标彩度，偏离它越远扣分越多。 */
    private const val TARGET_CHROMA = 48.0

    /** 人口占比的权重（比彩度更重要）。 */
    private const val WEIGHT_PROPORTION = 0.7

    /** 彩度高于目标时的权重：高彩度更"有个性"，惩罚较轻。 */
    private const val WEIGHT_CHROMA_ABOVE = 0.3

    /** 彩度低于目标时的权重。 */
    private const val WEIGHT_CHROMA_BELOW = 0.1

    /** 低于此彩度直接判定"不适合当主题色"。 */
    private const val CUTOFF_CHROMA = 5.0

    /** 30 度邻域内的人口占比低于此值也不适合当主题色。 */
    private const val CUTOFF_EXCITED_PROPORTION = 0.01

    /** Fallback color is Google Blue. */
    private val FALLBACK_COLOR_ARGB = 0xff4285f4.toInt()

    /** @return 按适合度排序的颜色，最合适的在第一位；至少返回一个颜色。 */
    fun score(colorsToPopulation: Map<Int, Int>): List<Int> =
        score(colorsToPopulation, 4, FALLBACK_COLOR_ARGB, true)

    fun score(colorsToPopulation: Map<Int, Int>, desired: Int): List<Int> =
        score(colorsToPopulation, desired, FALLBACK_COLOR_ARGB, true)

    fun score(colorsToPopulation: Map<Int, Int>, desired: Int, fallbackColorArgb: Int): List<Int> =
        score(colorsToPopulation, desired, fallbackColorArgb, true)

    /**
     * @param colorsToPopulation 颜色 -> 出现次数（一般来自图像量化）
     * @param desired 最多返回多少个候选
     * @param fallbackColorArgb 一个都选不出来时返回的颜色
     * @param filter 是否过滤掉彩度/占比不达标的颜色
     * @return 按适合度降序排列的颜色；一个都没有时返回 [fallbackColorArgb]
     */
    fun score(
        colorsToPopulation: Map<Int, Int>,
        desired: Int,
        fallbackColorArgb: Int,
        filter: Boolean,
    ): List<Int> {
        // Get the HCT color for each Argb value, while finding the per hue count and total count.
        val colorsHct = ArrayList<Hct>()
        val huePopulation = IntArray(360)
        var populationSum = 0.0
        for ((argb, population) in colorsToPopulation) {
            val hct = Hct.fromInt(argb)
            colorsHct.add(hct)
            val hue = Math.floor(hct.hue).toInt()
            huePopulation[hue] += population
            populationSum += population
        }

        // Hues with more usage in neighboring 30 degree slice get a larger number.
        // 邻域取 [hue-14, hue+15)，正好 30 度，且左右各覆盖 15 度 —— 这是上游的取法，不要"顺手"改成 ±15。
        val hueExcitedProportions = DoubleArray(360)
        for (hue in 0 until 360) {
            val proportion = huePopulation[hue] / populationSum
            for (i in hue - 14 until hue + 16) {
                val neighborHue = MathUtils.sanitizeDegreesInt(i)
                hueExcitedProportions[neighborHue] += proportion
            }
        }

        // Scores each HCT color based on usage and chroma, while optionally filtering out values
        // that do not have enough chroma or usage.
        val scoredHcts = ArrayList<ScoredHCT>()
        for (hct in colorsHct) {
            val hue = MathUtils.sanitizeDegreesInt(Math.round(hct.hue).toInt())
            val proportion = hueExcitedProportions[hue]
            if (filter && (hct.chroma < CUTOFF_CHROMA || proportion <= CUTOFF_EXCITED_PROPORTION)) {
                continue
            }

            val proportionScore = proportion * 100.0 * WEIGHT_PROPORTION
            val chromaWeight = if (hct.chroma < TARGET_CHROMA) WEIGHT_CHROMA_BELOW else WEIGHT_CHROMA_ABOVE
            val chromaScore = (hct.chroma - TARGET_CHROMA) * chromaWeight
            val score = proportionScore + chromaScore
            scoredHcts.add(ScoredHCT(hct, score))
        }
        // Sorted so that colors with higher scores come first. Kotlin 的 sortedWith 与 Java 的
        // Collections.sort 同样是稳定排序，同分时保持输入顺序。
        scoredHcts.sortWith(compareByDescending { it.score })

        // Iterates through potential hue differences in degrees in order to select the colors with
        // the largest distribution of hues possible. Starting at 90 degrees (maximum difference for
        // 4 colors) then decreasing down to a 15 degree minimum.
        val chosenColors = ArrayList<Hct>()
        for (differenceDegrees in 90 downTo 15) {
            chosenColors.clear()
            for (entry in scoredHcts) {
                val hct = entry.hct
                var hasDuplicateHue = false
                for (chosenHct in chosenColors) {
                    if (MathUtils.differenceDegrees(hct.hue, chosenHct.hue) < differenceDegrees) {
                        hasDuplicateHue = true
                        break
                    }
                }
                if (!hasDuplicateHue) {
                    chosenColors.add(hct)
                }
                if (chosenColors.size >= desired) {
                    break
                }
            }
            if (chosenColors.size >= desired) {
                break
            }
        }
        val colors = ArrayList<Int>()
        if (chosenColors.isEmpty()) {
            colors.add(fallbackColorArgb)
        }
        for (chosenHct in chosenColors) {
            colors.add(chosenHct.toInt())
        }
        return colors
    }

    private class ScoredHCT(val hct: Hct, val score: Double)
}
