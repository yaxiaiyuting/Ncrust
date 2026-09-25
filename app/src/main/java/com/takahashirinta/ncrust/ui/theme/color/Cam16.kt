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
//   https://github.com/material-foundation/material-color-utilities/blob/main/java/hct/Cam16.java
//   https://github.com/material-foundation/material-color-utilities/blob/main/java/hct/ViewingConditions.java
// 上游早期版本位于 java/cam16/ 包，main 分支已迁到 java/hct/，两者实现相同。
// 移植原则：常量、公式、运算顺序与上游逐行一致；ViewingConditions 并入本文件（上游是独立文件），
// 以满足"颜色算法只用固定几个文件"的约定。不得引入 android.* / androidx.*。

package com.takahashirinta.ncrust.ui.theme.color

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * CAM16, a color appearance model.
 *
 * CAM16 的颜色不仅取决于十六进制值，还取决于观察条件（ViewingConditions）。
 * HCT 用 CAM16 的 hue/chroma + L*a*b* 的 tone 组合而成。
 */
class Cam16 private constructor(
    /** Hue in CAM16 */
    val hue: Double,
    /** Chroma in CAM16 */
    val chroma: Double,
    /** Lightness in CAM16 */
    val j: Double,
    /** Brightness in CAM16（绝对值，通常用 j 更合适） */
    val q: Double,
    /** Colorfulness in CAM16（绝对值，通常用 chroma 更合适） */
    val m: Double,
    /** Saturation in CAM16 */
    val s: Double,
    /** Lightness coordinate in CAM16-UCS */
    val jstar: Double,
    /** a* coordinate in CAM16-UCS */
    val astar: Double,
    /** b* coordinate in CAM16-UCS */
    val bstar: Double,
) {

    /** CAM16-UCS 空间中的色差（delta E 的 CAM16 版本）。 */
    fun distance(other: Cam16): Double {
        val dJ = jstar - other.jstar
        val dA = astar - other.astar
        val dB = bstar - other.bstar
        val dEPrime = Math.sqrt(dJ * dJ + dA * dA + dB * dB)
        return 1.41 * Math.pow(dEPrime, 0.63)
    }

    /**
     * ARGB representation of the color. Assumes the color was viewed in default viewing conditions.
     */
    fun toInt(): Int = viewed(ViewingConditions.DEFAULT)

    /** ARGB representation of the color, in defined viewing conditions. */
    fun viewed(viewingConditions: ViewingConditions): Int {
        val xyz = xyzInViewingConditions(viewingConditions)
        return ColorUtils.argbFromXyz(xyz[0], xyz[1], xyz[2])
    }

    /**
     * 默认观察条件下的 XYZ（上游 `Cam16.viewed` / `xyzInViewingConditions` 的便捷入口）。
     */
    fun toXyz(viewingConditions: ViewingConditions = ViewingConditions.DEFAULT): DoubleArray =
        xyzInViewingConditions(viewingConditions)

    /**
     * 把 CAM16 反解回 XYZ。
     *
     * @param returnArray 便于复用缓冲区的可选数组；语义与上游一致（非空时写回并返回同一个数组）。
     */
    fun xyzInViewingConditions(
        viewingConditions: ViewingConditions,
        returnArray: DoubleArray? = null,
    ): DoubleArray {
        // chroma 或 j 为 0 时 alpha 取 0，避免 0/0；这是上游的显式分支，不能简化成除法。
        val alpha = if (chroma == 0.0 || j == 0.0) 0.0 else chroma / Math.sqrt(j / 100.0)

        val t = Math.pow(
            alpha / Math.pow(1.64 - Math.pow(0.29, viewingConditions.n), 0.73),
            1.0 / 0.9,
        )
        val hRad = Math.toRadians(hue)

        val eHue = 0.25 * (cos(hRad + 2.0) + 3.8)
        val ac = viewingConditions.aw *
            Math.pow(j / 100.0, 1.0 / viewingConditions.c / viewingConditions.z)
        val p1 = eHue * (50000.0 / 13.0) * viewingConditions.nc * viewingConditions.ncb
        val p2 = ac / viewingConditions.nbb

        val hSin = sin(hRad)
        val hCos = cos(hRad)

        val gamma = 23.0 * (p2 + 0.305) * t / (23.0 * p1 + 11.0 * t * hCos + 108.0 * t * hSin)
        val a = gamma * hCos
        val b = gamma * hSin
        val rA = (460.0 * p2 + 451.0 * a + 288.0 * b) / 1403.0
        val gA = (460.0 * p2 - 891.0 * a - 261.0 * b) / 1403.0
        val bA = (460.0 * p2 - 220.0 * a - 6300.0 * b) / 1403.0

        val rCBase = maxOf(0.0, (27.13 * abs(rA)) / (400.0 - abs(rA)))
        val rC = Math.signum(rA) * (100.0 / viewingConditions.fl) * Math.pow(rCBase, 1.0 / 0.42)
        val gCBase = maxOf(0.0, (27.13 * abs(gA)) / (400.0 - abs(gA)))
        val gC = Math.signum(gA) * (100.0 / viewingConditions.fl) * Math.pow(gCBase, 1.0 / 0.42)
        val bCBase = maxOf(0.0, (27.13 * abs(bA)) / (400.0 - abs(bA)))
        val bC = Math.signum(bA) * (100.0 / viewingConditions.fl) * Math.pow(bCBase, 1.0 / 0.42)
        val rF = rC / viewingConditions.rgbD[0]
        val gF = gC / viewingConditions.rgbD[1]
        val bF = bC / viewingConditions.rgbD[2]

        val matrix = CAM16RGB_TO_XYZ
        val x = (rF * matrix[0][0]) + (gF * matrix[0][1]) + (bF * matrix[0][2])
        val y = (rF * matrix[1][0]) + (gF * matrix[1][1]) + (bF * matrix[1][2])
        val z = (rF * matrix[2][0]) + (gF * matrix[2][1]) + (bF * matrix[2][2])

        if (returnArray != null) {
            returnArray[0] = x
            returnArray[1] = y
            returnArray[2] = z
            return returnArray
        }
        return doubleArrayOf(x, y, z)
    }

    companion object {
        /** XYZ -> CAM16 'cone' 响应矩阵（上游常量，来自 CAM16 规范）。 */
        val XYZ_TO_CAM16RGB: Array<DoubleArray> = arrayOf(
            doubleArrayOf(0.401288, 0.650173, -0.051461),
            doubleArrayOf(-0.250268, 1.204414, 0.045854),
            doubleArrayOf(-0.002079, 0.048952, 0.953127),
        )

        /** CAM16 'cone' 响应 -> XYZ 矩阵（上游常量）。 */
        val CAM16RGB_TO_XYZ: Array<DoubleArray> = arrayOf(
            doubleArrayOf(1.8620678, -1.0112547, 0.14918678),
            doubleArrayOf(0.38752654, 0.62144744, -0.00897398),
            doubleArrayOf(-0.01584150, -0.03412294, 1.0499644),
        )

        /**
         * 锥体响应的非线性压缩（chromatic adaptation 的逆/正向共用）。
         *
         * 上游把它声明在 `HctSolver.chromaticAdaptation`（java/hct/HctSolver.java:342），
         * 属于 CAM16 的通用辅助函数，这里收敛到一处实现，供 HctSolver 调用，避免两份公式漂移。
         */
        fun chromaticAdaptation(component: Double): Double {
            val af = Math.pow(abs(component), 0.42)
            return Math.signum(component) * 400.0 * af / (af + 27.13)
        }

        /**
         * Create a CAM16 color from a color, assuming the color was viewed in default viewing
         * conditions.
         */
        fun fromInt(argb: Int): Cam16 = fromIntInViewingConditions(argb, ViewingConditions.DEFAULT)

        /** Create a CAM16 color from a color in defined viewing conditions. */
        fun fromIntInViewingConditions(argb: Int, viewingConditions: ViewingConditions): Cam16 {
            // 直接展开 sRGB -> XYZ，避免多一次数组分配；数值与 ColorUtils.xyzFromArgb 完全一致。
            val red = (argb and 0x00ff0000) shr 16
            val green = (argb and 0x0000ff00) shr 8
            val blue = argb and 0x000000ff
            val redL = ColorUtils.linearized(red)
            val greenL = ColorUtils.linearized(green)
            val blueL = ColorUtils.linearized(blue)
            val x = 0.41233895 * redL + 0.35762064 * greenL + 0.18051042 * blueL
            val y = 0.2126 * redL + 0.7152 * greenL + 0.0722 * blueL
            val z = 0.01932141 * redL + 0.11916382 * greenL + 0.95034478 * blueL
            return fromXyzInViewingConditions(x, y, z, viewingConditions)
        }

        /** 默认观察条件下的 [fromXyzInViewingConditions]，对应上游的 XYZ 入口。 */
        fun fromXyz(
            x: Double,
            y: Double,
            z: Double,
            viewingConditions: ViewingConditions = ViewingConditions.DEFAULT,
        ): Cam16 = fromXyzInViewingConditions(x, y, z, viewingConditions)

        fun fromXyzInViewingConditions(
            x: Double,
            y: Double,
            z: Double,
            viewingConditions: ViewingConditions,
        ): Cam16 {
            // Transform XYZ to 'cone'/'rgb' responses
            val matrix = XYZ_TO_CAM16RGB
            val rT = (x * matrix[0][0]) + (y * matrix[0][1]) + (z * matrix[0][2])
            val gT = (x * matrix[1][0]) + (y * matrix[1][1]) + (z * matrix[1][2])
            val bT = (x * matrix[2][0]) + (y * matrix[2][1]) + (z * matrix[2][2])

            // Discount illuminant
            val rD = viewingConditions.rgbD[0] * rT
            val gD = viewingConditions.rgbD[1] * gT
            val bD = viewingConditions.rgbD[2] * bT

            // Chromatic adaptation
            val rAF = Math.pow(viewingConditions.fl * abs(rD) / 100.0, 0.42)
            val gAF = Math.pow(viewingConditions.fl * abs(gD) / 100.0, 0.42)
            val bAF = Math.pow(viewingConditions.fl * abs(bD) / 100.0, 0.42)
            val rA = Math.signum(rD) * 400.0 * rAF / (rAF + 27.13)
            val gA = Math.signum(gD) * 400.0 * gAF / (gAF + 27.13)
            val bA = Math.signum(bD) * 400.0 * bAF / (bAF + 27.13)

            // redness-greenness
            val a = (11.0 * rA + -12.0 * gA + bA) / 11.0
            // yellowness-blueness
            val b = (rA + gA - 2.0 * bA) / 9.0

            // auxiliary components
            val u = (20.0 * rA + 20.0 * gA + 21.0 * bA) / 20.0
            val p2 = (40.0 * rA + 20.0 * gA + bA) / 20.0

            // hue
            val atan2 = Math.atan2(b, a)
            val atanDegrees = Math.toDegrees(atan2)
            val hue = MathUtils.sanitizeDegreesDouble(atanDegrees)
            val hueRadians = Math.toRadians(hue)

            // achromatic response to color
            val ac = p2 * viewingConditions.nbb

            // CAM16 lightness and brightness
            val j = 100.0 * Math.pow(ac / viewingConditions.aw, viewingConditions.c * viewingConditions.z)
            val q = 4.0 / viewingConditions.c * Math.sqrt(j / 100.0) *
                (viewingConditions.aw + 4.0) * viewingConditions.flRoot

            // CAM16 chroma, colorfulness, and saturation.
            // hue < 20.14 时 +360 是 CAM16 规范里的 eHue 修正（hue' 的定义域），不要动。
            val huePrime = if (hue < 20.14) hue + 360 else hue
            val eHue = 0.25 * (cos(Math.toRadians(huePrime) + 2.0) + 3.8)
            val p1 = 50000.0 / 13.0 * eHue * viewingConditions.nc * viewingConditions.ncb
            val t = p1 * hypot(a, b) / (u + 0.305)
            val alpha = Math.pow(1.64 - Math.pow(0.29, viewingConditions.n), 0.73) * Math.pow(t, 0.9)
            // CAM16 chroma, colorfulness, saturation
            val c = alpha * Math.sqrt(j / 100.0)
            val m = c * viewingConditions.flRoot
            val s = 50.0 * Math.sqrt((alpha * viewingConditions.c) / (viewingConditions.aw + 4.0))

            // CAM16-UCS components
            val jstar = (1.0 + 100.0 * 0.007) * j / (1.0 + 0.007 * j)
            val mstar = 1.0 / 0.0228 * Math.log1p(0.0228 * m)
            val astar = mstar * cos(hueRadians)
            val bstar = mstar * sin(hueRadians)

            return Cam16(hue, c, j, q, m, s, jstar, astar, bstar)
        }

        /** 由 CAM16 的 j / c / h 构造（默认观察条件）。 */
        fun fromJch(j: Double, c: Double, h: Double): Cam16 =
            fromJchInViewingConditions(j, c, h, ViewingConditions.DEFAULT)

        fun fromJchInViewingConditions(
            j: Double,
            c: Double,
            h: Double,
            viewingConditions: ViewingConditions,
        ): Cam16 {
            val q = 4.0 / viewingConditions.c * Math.sqrt(j / 100.0) *
                (viewingConditions.aw + 4.0) * viewingConditions.flRoot
            val m = c * viewingConditions.flRoot
            val alpha = c / Math.sqrt(j / 100.0)
            val s = 50.0 * Math.sqrt((alpha * viewingConditions.c) / (viewingConditions.aw + 4.0))

            val hueRadians = Math.toRadians(h)
            val jstar = (1.0 + 100.0 * 0.007) * j / (1.0 + 0.007 * j)
            val mstar = 1.0 / 0.0228 * Math.log1p(0.0228 * m)
            val astar = mstar * cos(hueRadians)
            val bstar = mstar * sin(hueRadians)
            return Cam16(h, c, j, q, m, s, jstar, astar, bstar)
        }

        /** Create a CAM16 color from CAM16-UCS coordinates. */
        fun fromUcs(jstar: Double, astar: Double, bstar: Double): Cam16 =
            fromUcsInViewingConditions(jstar, astar, bstar, ViewingConditions.DEFAULT)

        fun fromUcsInViewingConditions(
            jstar: Double,
            astar: Double,
            bstar: Double,
            viewingConditions: ViewingConditions,
        ): Cam16 {
            val m = hypot(astar, bstar)
            val m2 = Math.expm1(m * 0.0228) / 0.0228
            val c = m2 / viewingConditions.flRoot
            var h = Math.atan2(bstar, astar) * (180.0 / Math.PI)
            if (h < 0.0) {
                h += 360.0
            }
            // 上游写作 `1.` / `100.`；Kotlin 不接受省略小数位的字面量，等价改写为 1.0 / 100.0。
            val j = jstar / (1.0 - (jstar - 100.0) * 0.007)
            return fromJchInViewingConditions(j, c, h, viewingConditions)
        }
    }
}

/**
 * In traditional color spaces, a color can be identified solely by the observer's measurement of
 * the color. Color appearance models such as CAM16 also use information about the environment where
 * the color was observed, known as the viewing conditions.
 *
 * 本类缓存只与观察条件有关、与具体颜色无关的中间量（CAM16 转换的加速）。
 * 对应上游 `hct/ViewingConditions.java`。
 */
class ViewingConditions private constructor(
    /** 背景中性度 n（Y_b / Y_w）。 */
    val n: Double,
    val aw: Double,
    val nbb: Double,
    val ncb: Double,
    val c: Double,
    val nc: Double,
    /** 每个通道的 discount illuminant 系数。 */
    val rgbD: DoubleArray,
    val fl: Double,
    val flRoot: Double,
    val z: Double,
) {

    companion object {
        /** sRGB-like viewing conditions. */
        @JvmField
        val DEFAULT: ViewingConditions = defaultWithBackgroundLstar(50.0)

        /**
         * Create ViewingConditions from a simple, physically relevant, set of parameters.
         *
         * @param whitePoint 白点（XYZ，量纲 0..100），默认 D65
         * @param adaptingLuminance 适应亮度，可用 lux * 0.0586 估算；默认 11.72（约 200 lux）
         * @param backgroundLstar 周围环境的 L*，默认 50.0
         * @param surround 环境光描述：0 全黑影院、1.0 昏暗房间、2.0 无差别；默认 2.0
         * @param discountingIlluminant 眼睛是否扣除环境光偏色；自发光屏幕默认 false
         */
        fun make(
            whitePoint: DoubleArray,
            adaptingLuminance: Double,
            backgroundLstar: Double,
            surround: Double,
            discountingIlluminant: Boolean,
        ): ViewingConditions {
            // 纯黑背景是非物理的，会让后续式子产生无穷大；上游把下限钳在 0.1，照抄。
            val backgroundLstarClamped = Math.max(0.1, backgroundLstar)
            // Transform white point XYZ to 'cone'/'rgb' responses
            val matrix = Cam16.XYZ_TO_CAM16RGB
            val xyz = whitePoint
            val rW = (xyz[0] * matrix[0][0]) + (xyz[1] * matrix[0][1]) + (xyz[2] * matrix[0][2])
            val gW = (xyz[0] * matrix[1][0]) + (xyz[1] * matrix[1][1]) + (xyz[2] * matrix[1][2])
            val bW = (xyz[0] * matrix[2][0]) + (xyz[1] * matrix[2][1]) + (xyz[2] * matrix[2][2])
            val f = 0.8 + (surround / 10.0)
            val c = if (f >= 0.9) {
                MathUtils.lerp(0.59, 0.69, (f - 0.9) * 10.0)
            } else {
                MathUtils.lerp(0.525, 0.59, (f - 0.8) * 10.0)
            }
            var d = if (discountingIlluminant) {
                1.0
            } else {
                f * (1.0 - ((1.0 / 3.6) * Math.exp((-adaptingLuminance - 42.0) / 92.0)))
            }
            d = MathUtils.clampDouble(0.0, 1.0, d)
            val nc = f
            val rgbD = doubleArrayOf(
                d * (100.0 / rW) + 1.0 - d,
                d * (100.0 / gW) + 1.0 - d,
                d * (100.0 / bW) + 1.0 - d,
            )
            val k = 1.0 / (5.0 * adaptingLuminance + 1.0)
            val k4 = k * k * k * k
            val k4F = 1.0 - k4
            val fl = (k4 * adaptingLuminance) + (0.1 * k4F * k4F * Math.cbrt(5.0 * adaptingLuminance))
            val n = (ColorUtils.yFromLstar(backgroundLstarClamped) / whitePoint[1])
            val z = 1.48 + Math.sqrt(n)
            val nbb = 0.725 / Math.pow(n, 0.2)
            val ncb = nbb
            val rgbAFactors = doubleArrayOf(
                Math.pow(fl * rgbD[0] * rW / 100.0, 0.42),
                Math.pow(fl * rgbD[1] * gW / 100.0, 0.42),
                Math.pow(fl * rgbD[2] * bW / 100.0, 0.42),
            )

            val rgbA = doubleArrayOf(
                (400.0 * rgbAFactors[0]) / (rgbAFactors[0] + 27.13),
                (400.0 * rgbAFactors[1]) / (rgbAFactors[1] + 27.13),
                (400.0 * rgbAFactors[2]) / (rgbAFactors[2] + 27.13),
            )

            val aw = ((2.0 * rgbA[0]) + rgbA[1] + (0.05 * rgbA[2])) * nbb
            return ViewingConditions(n, aw, nbb, ncb, c, nc, rgbD, fl, Math.pow(fl, 0.25), z)
        }

        /**
         * Create sRGB-like viewing conditions with a custom background lstar.
         *
         * 默认背景 L* = 50（中灰）。
         */
        fun defaultWithBackgroundLstar(lstar: Double): ViewingConditions = make(
            ColorUtils.whitePointD65(),
            (200.0 / Math.PI * ColorUtils.yFromLstar(50.0) / 100f),
            lstar,
            2.0,
            false,
        )
    }
}
