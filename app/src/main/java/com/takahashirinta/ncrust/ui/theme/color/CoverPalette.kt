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
// 取色算法与色板（TonalPalette）来自上游；角色 -> tone 的映射来自：
//   https://github.com/material-foundation/material-color-utilities/blob/main/java/scheme/Scheme.java
//     （lightFromCorePalette / darkFromCorePalette，main 分支 java/scheme/Scheme.java:150-214）
//   https://github.com/material-foundation/material-color-utilities/blob/main/java/palettes/CorePalette.java
//     （main 分支 java/palettes/CorePalette.java:79-88，a1/a2/a3/n1/n2 的 hue/chroma 取法）
// 本文件是本包内**唯一**允许 import androidx.compose.* 的文件；同时全部字段都是裸 ARGB Int，
// 单元测试只断言 Int，不依赖 Compose 运行时。

package com.takahashirinta.ncrust.ui.theme.color

import androidx.compose.ui.graphics.Color
import kotlin.math.max

/**
 * 由封面主色派生出来的一套角色色（浅色/深色各一套）。
 *
 * 每个字段都是 ARGB `Int`（与 Compose `Color(Int)` 构造参数同义），另外提供 `*Color` 只读属性
 * 方便在 Compose 里直接用。
 *
 * ## 角色 -> tone 映射（**逐条对应上游 java/scheme/Scheme.java:150-214**）
 *
 * 色板取法与上游 `java/palettes/CorePalette.java:79-88` 的 `CorePalette.of(argb)` 完全一致：
 * `a1 = (hue, max(48, chroma))`、`a2 = (hue, 16)`、`a3 = (hue + 60, 24)`、
 * `n1 = (hue, 4)`、`n2 = (hue, 8)`。
 *
 * | 角色 | 浅色 tone | 深色 tone | 色板 |
 * |---|---|---|---|
 * | primary | a1 40 | a1 80 | a1 |
 * | onPrimary | a1 100 | a1 20 | a1 |
 * | primaryContainer | a1 90 | a1 30 | a1 |
 * | secondary | a2 40 | a2 80 | a2 |
 * | tertiary | a3 40 | a3 80 | a3 |
 * | neutral（surface / background） | n1 99 | n1 10 | n1 |
 * | neutralVariant（surfaceVariant） | n2 90 | n2 30 | n2 |
 * | onSurface（onBackground 同值） | n1 10 | n1 90 | n1 |
 * | onSurfaceVariant | n2 30 | n2 80 | n2 |
 * | outline | n2 50 | n2 **60** | n2 |
 * | outlineVariant | n2 80 | n2 30 | n2 |
 *
 * 注意两个容易抄错的点：① 深色的 outline 是 **tone 60**（不是 50）；
 * ② 浅色的 neutral 是 **tone 99**（不是 100）。
 *
 * 说明：上游把 `Scheme` 标记为 legacy、推荐改用 `dynamiccolor.DynamicScheme`；但 DynamicScheme 的
 * 现代映射需要一整套 `ColorSpec` + `ContrastCurve` + `ToneDeltaPair` 才能算出来（数百行、且包含
 * 对比度动态调整），对一个「封面取色生成备用主题」的需求属于过度设计。这里采用仍然保留在上游
 * `java/scheme/Scheme.java` 里的经典映射，取值可逐条对照。
 */
data class CoverPalette(
    /** 生成这套色板的种子色（ARGB）。 */
    val seedArgb: Int,
    /** 这套色板属于深色还是浅色模式。 */
    val isDark: Boolean,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val secondary: Int,
    val tertiary: Int,
    /** surface / background（上游 n1 色板）。 */
    val neutral: Int,
    /** surfaceVariant / outline 所在的 n2 色板。 */
    val neutralVariant: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
    val outlineVariant: Int,
) {
    /** [primary] 的 Compose 版本。 */
    val primaryColor: Color get() = Color(primary)

    /** [onPrimary] 的 Compose 版本。 */
    val onPrimaryColor: Color get() = Color(onPrimary)

    /** [primaryContainer] 的 Compose 版本。 */
    val primaryContainerColor: Color get() = Color(primaryContainer)

    /** [secondary] 的 Compose 版本。 */
    val secondaryColor: Color get() = Color(secondary)

    /** [tertiary] 的 Compose 版本。 */
    val tertiaryColor: Color get() = Color(tertiary)

    /** [neutral] 的 Compose 版本。 */
    val neutralColor: Color get() = Color(neutral)

    /** [neutralVariant] 的 Compose 版本。 */
    val neutralVariantColor: Color get() = Color(neutralVariant)

    /** [onSurface] 的 Compose 版本。 */
    val onSurfaceColor: Color get() = Color(onSurface)

    /** [onSurfaceVariant] 的 Compose 版本。 */
    val onSurfaceVariantColor: Color get() = Color(onSurfaceVariant)

    /** [outline] 的 Compose 版本。 */
    val outlineColor: Color get() = Color(outline)

    /** [outlineVariant] 的 Compose 版本。 */
    val outlineVariantColor: Color get() = Color(outlineVariant)

    companion object {
        /** 按上游 `CorePalette.of` 的取法构造 5 条色板。 */
        fun fromSeed(seedArgb: Int, isDark: Boolean): CoverPalette {
            val hct = Hct.fromInt(seedArgb)
            val hue = hct.hue
            val chroma = hct.chroma

            val a1 = TonalPalette.fromHueAndChroma(hue, max(48.0, chroma))
            val a2 = TonalPalette.fromHueAndChroma(hue, 16.0)
            val a3 = TonalPalette.fromHueAndChroma(hue + 60.0, 24.0)
            val n1 = TonalPalette.fromHueAndChroma(hue, 4.0)
            val n2 = TonalPalette.fromHueAndChroma(hue, 8.0)

            return if (isDark) {
                CoverPalette(
                    seedArgb = seedArgb,
                    isDark = true,
                    primary = a1.tone(80),
                    onPrimary = a1.tone(20),
                    primaryContainer = a1.tone(30),
                    secondary = a2.tone(80),
                    tertiary = a3.tone(80),
                    neutral = n1.tone(10),
                    neutralVariant = n2.tone(30),
                    onSurface = n1.tone(90),
                    onSurfaceVariant = n2.tone(80),
                    outline = n2.tone(60),
                    outlineVariant = n2.tone(30),
                )
            } else {
                CoverPalette(
                    seedArgb = seedArgb,
                    isDark = false,
                    primary = a1.tone(40),
                    onPrimary = a1.tone(100),
                    primaryContainer = a1.tone(90),
                    secondary = a2.tone(40),
                    tertiary = a3.tone(40),
                    neutral = n1.tone(99),
                    neutralVariant = n2.tone(90),
                    onSurface = n1.tone(10),
                    onSurfaceVariant = n2.tone(30),
                    outline = n2.tone(50),
                    outlineVariant = n2.tone(80),
                )
            }
        }

        /** 浅色变体（等价于上游 `Scheme.light(argb)` 的 tone 取值）。 */
        fun light(seedArgb: Int): CoverPalette = fromSeed(seedArgb, false)

        /** 深色变体（等价于上游 `Scheme.dark(argb)` 的 tone 取值）。 */
        fun dark(seedArgb: Int): CoverPalette = fromSeed(seedArgb, true)

        /** 按模式取变体，给「跟随系统深浅色」的调用点用。 */
        fun forMode(seedArgb: Int, isDark: Boolean): CoverPalette = fromSeed(seedArgb, isDark)
    }
}
