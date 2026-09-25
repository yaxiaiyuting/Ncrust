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
//   https://github.com/material-foundation/material-color-utilities/blob/main/java/utils/ColorUtils.java
//   https://github.com/material-foundation/material-color-utilities/blob/main/java/utils/MathUtils.java
// 算法逻辑逐行移植：矩阵常量、分支条件、运算顺序均与上游保持一致，不做任何数学改动；
// 仅把 Java 静态方法改写为 Kotlin object（上游 MathUtils 也并入本文件，避免多出一个文件）。
// 本文件不得引入 android.* / androidx.*，以便在纯 JVM 单元测试中运行。

package com.takahashirinta.ncrust.ui.theme.color

import kotlin.math.abs

/**
 * Utility methods for mathematical operations.
 *
 * 对应上游 `utils/MathUtils.java`。
 */
object MathUtils {

    /** The signum function. @return 1 if num > 0, -1 if num < 0, and 0 if num = 0 */
    fun signum(num: Double): Int = when {
        num < 0 -> -1
        num == 0.0 -> 0
        else -> 1
    }

    /** The linear interpolation function. @return start if amount = 0 and stop if amount = 1 */
    fun lerp(start: Double, stop: Double, amount: Double): Double =
        (1.0 - amount) * start + amount * stop

    /** Clamps an integer between two integers. */
    fun clampInt(min: Int, max: Int, input: Int): Int = when {
        input < min -> min
        input > max -> max
        else -> input
    }

    /** Clamps a double between two doubles. */
    fun clampDouble(min: Double, max: Double, input: Double): Double = when {
        input < min -> min
        input > max -> max
        else -> input
    }

    /** Sanitizes a degree measure as an integer: 0 (inclusive) .. 360 (exclusive). */
    fun sanitizeDegreesInt(degrees: Int): Int {
        var d = degrees % 360
        if (d < 0) {
            d += 360
        }
        return d
    }

    /** Sanitizes a degree measure as a double: 0.0 (inclusive) .. 360.0 (exclusive). */
    fun sanitizeDegreesDouble(degrees: Double): Double {
        var d = degrees % 360.0
        if (d < 0) {
            d += 360.0
        }
        return d
    }

    /**
     * Sign of direction change needed to travel from one angle to another.
     *
     * 相差正好 180 度时两个方向等距，上游约定返回 1.0。
     */
    fun rotationDirection(from: Double, to: Double): Double {
        val increasingDifference = sanitizeDegreesDouble(to - from)
        return if (increasingDifference <= 180.0) 1.0 else -1.0
    }

    /** Distance of two points on a circle, represented using degrees. */
    fun differenceDegrees(a: Double, b: Double): Double = 180.0 - abs(abs(a - b) - 180.0)

    /** Multiplies a 1x3 row vector with a 3x3 matrix. */
    fun matrixMultiply(row: DoubleArray, matrix: Array<DoubleArray>): DoubleArray {
        val a = row[0] * matrix[0][0] + row[1] * matrix[0][1] + row[2] * matrix[0][2]
        val b = row[0] * matrix[1][0] + row[1] * matrix[1][1] + row[2] * matrix[1][2]
        val c = row[0] * matrix[2][0] + row[1] * matrix[2][1] + row[2] * matrix[2][2]
        return doubleArrayOf(a, b, c)
    }
}

/**
 * Color science utilities.
 *
 * 对应上游 `utils/ColorUtils.java`。注意上游把 RGB 通道线性化后放大到 0..100，
 * 因此这里的 linear RGB / XYZ 的量纲都是 0..100（不是 0..1），移植时不能"顺手归一化"。
 */
object ColorUtils {

    /** sRGB 线性 RGB -> XYZ 的 D65 矩阵（上游 ColorUtils.SRGB_TO_XYZ）。 */
    val SRGB_TO_XYZ: Array<DoubleArray> = arrayOf(
        doubleArrayOf(0.41233895, 0.35762064, 0.18051042),
        doubleArrayOf(0.2126, 0.7152, 0.0722),
        doubleArrayOf(0.01932141, 0.11916382, 0.95034478),
    )

    /** XYZ -> sRGB 线性 RGB 的逆矩阵（上游 ColorUtils.XYZ_TO_SRGB）。 */
    val XYZ_TO_SRGB: Array<DoubleArray> = arrayOf(
        doubleArrayOf(3.2413774792388685, -1.5376652402851851, -0.49885366846268053),
        doubleArrayOf(-0.9691452513005321, 1.8758853451067872, 0.04156585616912061),
        doubleArrayOf(0.05562093689691305, -0.20395524564742123, 1.0571799111220335),
    )

    /** D65 白点，量纲 0..100。 */
    val WHITE_POINT_D65: DoubleArray = doubleArrayOf(95.047, 100.0, 108.883)

    /** Converts a color from RGB components to ARGB format. */
    fun argbFromRgb(red: Int, green: Int, blue: Int): Int =
        (255 shl 24) or ((red and 255) shl 16) or ((green and 255) shl 8) or (blue and 255)

    /** Converts a color from linear RGB components (0..100) to ARGB format. */
    fun argbFromLinrgb(linrgb: DoubleArray): Int {
        val r = delinearized(linrgb[0])
        val g = delinearized(linrgb[1])
        val b = delinearized(linrgb[2])
        return argbFromRgb(r, g, b)
    }

    /** Returns the alpha component of a color in ARGB format. */
    fun alphaFromArgb(argb: Int): Int = (argb shr 24) and 255

    /** Returns the red component of a color in ARGB format. */
    fun redFromArgb(argb: Int): Int = (argb shr 16) and 255

    /** Returns the green component of a color in ARGB format. */
    fun greenFromArgb(argb: Int): Int = (argb shr 8) and 255

    /** Returns the blue component of a color in ARGB format. */
    fun blueFromArgb(argb: Int): Int = argb and 255

    /** Returns whether a color in ARGB format is opaque. */
    fun isOpaque(argb: Int): Boolean = alphaFromArgb(argb) >= 255

    /**
     * Converts a color from XYZ（0..100）to ARGB.
     *
     * 上游注释把方法名和文档写反了（方法叫 argbFromXyz，文档写 "from ARGB to XYZ"），此处按实现语义命名。
     */
    fun argbFromXyz(x: Double, y: Double, z: Double): Int {
        val matrix = XYZ_TO_SRGB
        val linearR = matrix[0][0] * x + matrix[0][1] * y + matrix[0][2] * z
        val linearG = matrix[1][0] * x + matrix[1][1] * y + matrix[1][2] * z
        val linearB = matrix[2][0] * x + matrix[2][1] * y + matrix[2][2] * z
        val r = delinearized(linearR)
        val g = delinearized(linearG)
        val b = delinearized(linearB)
        return argbFromRgb(r, g, b)
    }

    /** Converts a color from ARGB to XYZ（0..100）。 */
    fun xyzFromArgb(argb: Int): DoubleArray {
        val r = linearized(redFromArgb(argb))
        val g = linearized(greenFromArgb(argb))
        val b = linearized(blueFromArgb(argb))
        return MathUtils.matrixMultiply(doubleArrayOf(r, g, b), SRGB_TO_XYZ)
    }

    /** Converts a color represented in Lab color space into an ARGB integer. */
    fun argbFromLab(l: Double, a: Double, b: Double): Int {
        val whitePoint = WHITE_POINT_D65
        val fy = (l + 16.0) / 116.0
        val fx = a / 500.0 + fy
        val fz = fy - b / 200.0
        val xNormalized = labInvf(fx)
        val yNormalized = labInvf(fy)
        val zNormalized = labInvf(fz)
        val x = xNormalized * whitePoint[0]
        val y = yNormalized * whitePoint[1]
        val z = zNormalized * whitePoint[2]
        return argbFromXyz(x, y, z)
    }

    /**
     * Converts a color from ARGB representation to L*a*b* representation.
     *
     * @return a Lab array of 3 elements
     */
    fun labFromArgb(argb: Int): DoubleArray {
        val linearR = linearized(redFromArgb(argb))
        val linearG = linearized(greenFromArgb(argb))
        val linearB = linearized(blueFromArgb(argb))
        val matrix = SRGB_TO_XYZ
        val x = matrix[0][0] * linearR + matrix[0][1] * linearG + matrix[0][2] * linearB
        val y = matrix[1][0] * linearR + matrix[1][1] * linearG + matrix[1][2] * linearB
        val z = matrix[2][0] * linearR + matrix[2][1] * linearG + matrix[2][2] * linearB
        val whitePoint = WHITE_POINT_D65
        val xNormalized = x / whitePoint[0]
        val yNormalized = y / whitePoint[1]
        val zNormalized = z / whitePoint[2]
        val fx = labF(xNormalized)
        val fy = labF(yNormalized)
        val fz = labF(zNormalized)
        val l = 116.0 * fy - 16
        val a = 500.0 * (fx - fy)
        val b = 200.0 * (fy - fz)
        return doubleArrayOf(l, a, b)
    }

    /**
     * Converts an L* value to an ARGB representation（灰度色）。
     */
    fun argbFromLstar(lstar: Double): Int {
        val y = yFromLstar(lstar)
        val component = delinearized(y)
        return argbFromRgb(component, component, component)
    }

    /**
     * Computes the L* value of a color in ARGB representation.
     */
    fun lstarFromArgb(argb: Int): Double {
        val y = xyzFromArgb(argb)[1]
        return 116.0 * labF(y / 100.0) - 16.0
    }

    /**
     * Converts an L* value to a Y value (相对亮度, 0..100)。
     */
    fun yFromLstar(lstar: Double): Double = 100.0 * labInvf((lstar + 16.0) / 116.0)

    /**
     * Converts a Y value (0..100) to an L* value.
     */
    fun lstarFromY(y: Double): Double = labF(y / 100.0) * 116.0 - 16.0

    /**
     * Linearizes an RGB component.
     *
     * 注意 0.040449936 是 sRGB 传递函数在 8bit 量化边界上的实测阈值（上游常量，直接照抄）。
     *
     * @param rgbComponent 0 <= rgbComponent <= 255
     * @return 0.0 <= output <= 100.0
     */
    fun linearized(rgbComponent: Int): Double {
        val normalized = rgbComponent / 255.0
        return if (normalized <= 0.040449936) {
            normalized / 12.92 * 100.0
        } else {
            Math.pow((normalized + 0.055) / 1.055, 2.4) * 100.0
        }
    }

    /**
     * Delinearizes an RGB component.
     *
     * @param rgbComponent 0.0 <= rgbComponent <= 100.0
     * @return 0 <= output <= 255
     */
    fun delinearized(rgbComponent: Double): Int {
        val normalized = rgbComponent / 100.0
        val delinearized = if (normalized <= 0.0031308) {
            normalized * 12.92
        } else {
            1.055 * Math.pow(normalized, 1.0 / 2.4) - 0.055
        }
        return MathUtils.clampInt(0, 255, Math.round(delinearized * 255.0).toInt())
    }

    /**
     * Returns the standard white point; white on a sunny day.
     */
    fun whitePointD65(): DoubleArray = WHITE_POINT_D65

    /** L*a*b* 的 f(t) 分段函数，常量 e/kappa 来自 CIE 标准，不可改。 */
    fun labF(t: Double): Double {
        val e = 216.0 / 24389.0
        val kappa = 24389.0 / 27.0
        return if (t > e) {
            Math.pow(t, 1.0 / 3.0)
        } else {
            (kappa * t + 16) / 116
        }
    }

    /** L*a*b* 的 f^-1(t)。 */
    fun labInvf(ft: Double): Double {
        val e = 216.0 / 24389.0
        val kappa = 24389.0 / 27.0
        val ft3 = ft * ft * ft
        return if (ft3 > e) {
            ft3
        } else {
            (116 * ft - 16) / kappa
        }
    }
}
