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

// 本文件调用 material-color-utilities（Apache-2.0）的量化/评分算法，来源：
//   https://github.com/material-foundation/material-color-utilities （QuantizerCelebi / Score / Hct）
// 流程与 Android 版 Material You 的 DynamicColors 一致：量化 -> 打分 -> 取种子色 -> 生成色板。
//
// 【为什么把算法 vendored 进本仓库，而不是加一条 Gradle 依赖】
// 1. 发布产物必须能从源码 checkout 复现：本项目是 GPLv3 fork，release APK 由 `git clone` +
//    `./gradlew assembleRelease` 产出，构建输入必须全部在版本控制内。material-color-utilities
//    没有稳定的 Maven Central 坐标（历史上只有 com.google.android.material 内部 shade 的一份），
//    引入 jitpack 之类的第三方仓库会让「同一个 commit 在今天和明年构建出不同产物」。
// 2. GPLv3 fork 不能引入未经审查的依赖：每加一个依赖都要走一遍许可证与供应链审查
//    （见仓库根 THIRD-PARTY-LICENSES.md），而这份算法只有 ~1500 行、纯数学、无传递依赖，
//    vendored 的成本远低于新增依赖的长期维护成本。Apache-2.0 与 GPLv3 兼容，保留许可证头即可。
// 3. 需要纯 JVM 可测：本文件不 import 任何 android.* / androidx.*，可以直接在
//    `:app:testDebugUnitTest` 里跑，不需要 Robolectric 或真机。

package com.takahashirinta.ncrust.ui.theme.color

/**
 * 封面取色的**唯一**入口。
 *
 * 输入是调用方已经降采样到至多 50x50 的 ARGB 像素数组（调用方可以用 [downsample] 从 Bitmap 得到），
 * 输出是 [CoverPalette]；**任何降级情况都返回 null，绝不抛异常**，由 App 回落到预设主题。
 *
 * 全流程是**输入的纯函数**：没有随机数（量化器的随机种子固定为上游常量）、没有时间/环境依赖、
 * 不读全局状态，同样的输入必然得到同样的输出。
 */
object CoverPaletteExtractor {

    /** 量化目标颜色数（上游 / Android Palette 的常见取值，远大于最终需要的角色数）。 */
    const val MAX_QUANTIZE_COLORS = 128

    /** 只需要最优的一个种子色。 */
    const val SCORE_DESIRED = 1

    /**
     * 灰度阈值：种子色 HCT chroma 低于此值时判定「封面没有可用色彩」，返回 null。
     *
     * 取值与依据见 [Hct.HCT_CHROMA_THRESHOLD]（= 4.0，来自上游 CorePalette 的中性色板 chroma）。
     */
    const val GRAYSCALE_CHROMA_THRESHOLD: Double = Hct.HCT_CHROMA_THRESHOLD

    /**
     * 从一屏像素里提取配色。
     *
     * @param pixels ARGB 像素；调用方应先用 [downsample] 降到 <= 50x50（2500 像素 ~ 数毫秒）
     * @param isDark 取深色变体还是浅色变体
     * @return 配色；以下情况返回 **null**（不抛异常）：
     *  - [pixels] 为空；
     *  - 量化结果为空；
     *  - 最优种子色的 HCT chroma < [GRAYSCALE_CHROMA_THRESHOLD]（纯灰度封面）；
     *  - 任何内部异常（用 runCatching 兜底）。
     */
    fun extract(pixels: IntArray, isDark: Boolean): CoverPalette? =
        runCatching { extractOrNull(pixels, isDark) }.getOrNull()

    private fun extractOrNull(pixels: IntArray, isDark: Boolean): CoverPalette? {
        if (pixels.isEmpty()) {
            return null
        }
        val quantized = QuantizerCelebi.quantize(pixels, MAX_QUANTIZE_COLORS)
        if (quantized.isEmpty()) {
            return null
        }
        // 兜底色取「人口最多的量化色」，而不是上游 Score 默认的 Google Blue：
        // 否则一张纯灰度封面会被 Score 的 filter 全过滤掉、回落到 Google Blue（chroma 68），
        // 于是灰度判定形同虚设。用输入自身的颜色兜底，后面的 chroma 检查才有效。
        var fallback = 0
        var fallbackPopulation = -1
        for ((argb, population) in quantized) {
            if (population > fallbackPopulation) {
                fallbackPopulation = population
                fallback = argb
            }
        }
        // filter = false：是否"够彩"由下面的 chroma 阈值显式判断，不交给 Score 隐式过滤。
        val seeds = Score.score(quantized, SCORE_DESIRED, fallback, false)
        val seed = seeds.firstOrNull() ?: return null
        val seedHct = Hct.fromInt(seed)
        if (seedHct.chroma < GRAYSCALE_CHROMA_THRESHOLD) {
            return null
        }
        return CoverPalette.fromSeed(seed, isDark)
    }

    /**
     * 把 [srcW] x [srcH] 的 ARGB 像素降采样到最长边不超过 [dstMax]，返回新的紧凑数组。
     *
     * 采样方式：**均值下采样（box average）**，每个目标像素取其覆盖的源矩形内所有像素的
     * RGB（含 A）算术平均。不是最近邻 —— 上游 AndroidX Palette 的 `resizeBitmap` 用的是带滤波的
     * 缩放（等价于均值/双线性），最近邻会漏掉细线条里的饱和色，让封面主色偏离。
     *
     * - 源图最长边已经 <= [dstMax]：**原样返回**（同一个数组实例，不做拷贝）；
     * - [srcW] / [srcH] / [dstMax] 非正，或 [pixels] 长度不足 [srcW]*[srcH]：返回空数组（不抛异常）；
     * - 200x100 且 dstMax=50 -> 50x25（每边等比缩小，长边正好等于 dstMax）。
     */
    fun downsample(pixels: IntArray, srcW: Int, srcH: Int, dstMax: Int): IntArray {
        if (srcW <= 0 || srcH <= 0 || dstMax <= 0) {
            return IntArray(0)
        }
        if (pixels.size < srcW * srcH) {
            return IntArray(0)
        }
        if (srcW <= dstMax && srcH <= dstMax) {
            return pixels
        }
        val scale = maxOf(srcW, srcH).toDouble() / dstMax
        val dstW = maxOf(1, Math.floor(srcW / scale).toInt())
        val dstH = maxOf(1, Math.floor(srcH / scale).toInt())

        val out = IntArray(dstW * dstH)
        for (dy in 0 until dstH) {
            val y0 = (dy * srcH) / dstH
            val y1 = maxOf(y0 + 1, ((dy + 1) * srcH) / dstH)
            for (dx in 0 until dstW) {
                val x0 = (dx * srcW) / dstW
                val x1 = maxOf(x0 + 1, ((dx + 1) * srcW) / dstW)
                var a = 0L
                var r = 0L
                var g = 0L
                var b = 0L
                var n = 0L
                for (y in y0 until minOf(y1, srcH)) {
                    val row = y * srcW
                    for (x in x0 until minOf(x1, srcW)) {
                        val argb = pixels[row + x]
                        a += (argb ushr 24) and 0xff
                        r += (argb ushr 16) and 0xff
                        g += (argb ushr 8) and 0xff
                        b += argb and 0xff
                        n++
                    }
                }
                if (n == 0L) {
                    out[dy * dstW + dx] = 0
                } else {
                    out[dy * dstW + dx] = (((a / n).toInt() and 0xff) shl 24) or
                        (((r / n).toInt() and 0xff) shl 16) or
                        (((g / n).toInt() and 0xff) shl 8) or
                        ((b / n).toInt() and 0xff)
                }
            }
        }
        return out
    }
}
