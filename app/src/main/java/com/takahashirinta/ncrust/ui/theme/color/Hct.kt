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
//   https://github.com/material-foundation/material-color-utilities/blob/main/java/hct/Hct.java
//   https://github.com/material-foundation/material-color-utilities/blob/main/java/hct/HctSolver.java
// HctSolver 并入本文件（上游是独立文件），公式与常量逐行照抄：CRITICAL_PLANES 的 255 个临界平面值
// 由上游 Java 源文件直接机械提取，未经手抄，避免任何一位数字出错。
// 不得引入 android.* / androidx.*。

package com.takahashirinta.ncrust.ui.theme.color

/**
 * HCT：hue、chroma、tone。
 *
 * 用 CAM16 的 hue/chroma + L*a*b* 的 L*（tone）组成，tone 与相对亮度 Y 直接挂钩，
 * 因此 tone 差 40 就保证对比度 >= 3.0，差 50 保证 >= 4.5 —— 这是 Material 3 全部色板都按
 * tone 取值的原因。
 *
 * 对应上游 `hct/Hct.java`。上游的 setHue/setChroma/setTone 也一并保留（同样的语义：
 * 重投影后 chroma 可能因色域边界而下降）。
 */
class Hct private constructor(argb: Int) {

    /** Hue in CAM16, 0..360 */
    var hue: Double = 0.0
        private set

    /** Chroma in CAM16（色域内最大 chroma 随 hue/tone 变化，可能低于请求值） */
    var chroma: Double = 0.0
        private set

    /** Tone，即 L*a*b* 的 L*，0..100 */
    var tone: Double = 0.0
        private set

    private var argb: Int = argb

    init {
        setInternalState(argb)
    }

    fun toInt(): Int = argb

    /**
     * Set the hue of this color. Chroma may decrease because chroma has a different maximum for any
     * given hue and tone.
     */
    fun setHue(newHue: Double) {
        setInternalState(HctSolver.solveToInt(newHue, chroma, tone))
    }

    /** Set the chroma of this color. Chroma may decrease（色域上限）。 */
    fun setChroma(newChroma: Double) {
        setInternalState(HctSolver.solveToInt(hue, newChroma, tone))
    }

    /** Set the tone of this color. Chroma may decrease（色域上限）。 */
    fun setTone(newTone: Double) {
        setInternalState(HctSolver.solveToInt(hue, chroma, newTone))
    }

    /**
     * Translate a color into different ViewingConditions.
     *
     * 同一个 hex 在开灯/关灯、白底/黑底下的观感不同（Josef Albers 的 color relativity）。
     * 步骤与上游一致：① 用 CAM16 求出该色在目标观察条件下的 XYZ；② 把这些 XYZ 当作默认观察
     * 条件下的颜色重新解析成 CAM16；③ 用「目标条件下的 Y」换算出 tone。
     */
    fun inViewingConditions(vc: ViewingConditions): Hct {
        // 1. Use CAM16 to find XYZ coordinates of color in specified VC.
        val cam16 = Cam16.fromInt(toInt())
        val viewedInVc = cam16.xyzInViewingConditions(vc)

        // 2. Create CAM16 of those XYZ coordinates in default VC.
        val recastInVc = Cam16.fromXyzInViewingConditions(
            viewedInVc[0],
            viewedInVc[1],
            viewedInVc[2],
            ViewingConditions.DEFAULT,
        )

        // 3. Create HCT from:
        // - CAM16 using default VC with XYZ coordinates in specified VC.
        // - L* converted from Y in XYZ coordinates in specified VC.
        return from(recastInVc.hue, recastInVc.chroma, ColorUtils.lstarFromY(viewedInVc[1]))
    }

    override fun toString(): String =
        "HCT(${Math.round(hue)}, ${Math.round(chroma)}, ${Math.round(tone)})"

    private fun setInternalState(argb: Int) {
        this.argb = argb
        val cam = Cam16.fromInt(argb)
        hue = cam.hue
        chroma = cam.chroma
        tone = ColorUtils.lstarFromArgb(argb)
    }

    companion object {
        /**
         * 灰度判定阈值（HCT chroma）。
         *
         * ⚠️ 上游 material-color-utilities **没有**这个常量（已对全仓库 grep 确认）。它是本移植
         * 为「封面取色降级」引入的**应用层**阈值，取 4.0 的依据来自上游自身：
         * `java/palettes/CorePalette.java` 用 `TonalPalette.fromHueAndChroma(hue, 4.)` 构造 n1
         * 中性色板 —— 也就是说 chroma 4 正是上游心目中的「中性灰」。封面主色 chroma 低于 4 时，
         * 生成出来的主题与上游中性灰无异，不如让 App 回落到预设主题（见 CoverPaletteExtractor）。
         * 另可佐证：`java/score/Score.java` 的 CUTOFF_CHROMA = 5.，低于 5 的颜色被上游直接判定为
         * 「不适合做 UI 主题色」。
         */
        const val HCT_CHROMA_THRESHOLD: Double = 4.0

        /**
         * Create an HCT color from hue, chroma, and tone.
         *
         * @param hue 0 <= hue < 360；非法值会被归一化
         * @param chroma 0 <= chroma；返回值的 chroma 可能更低（色域边界）
         * @param tone 0 <= tone <= 100；非法值会被修正
         */
        fun from(hue: Double, chroma: Double, tone: Double): Hct =
            Hct(HctSolver.solveToInt(hue, chroma, tone))

        /** Create an HCT color from a color. */
        fun fromInt(argb: Int): Hct = Hct(argb)

        fun isBlue(hue: Double): Boolean = hue >= 250 && hue < 270

        fun isYellow(hue: Double): Boolean = hue >= 105 && hue < 125

        fun isCyan(hue: Double): Boolean = hue >= 170 && hue < 207
    }
}

/**
 * A class that solves the HCT equation.
 *
 * 对应上游 `hct/HctSolver.java`：先用牛顿迭代在 J 空间里试解（findResultByJ），
 * 失败则在 Y 平面上沿 RGB 立方体边界做二分（bisectToLimit）。
 */
object HctSolver {

    /** linrgb -> 缩放后的锥体响应（上游 SCALED_DISCOUNT_FROM_LINRGB，数值常量照抄）。 */
    val SCALED_DISCOUNT_FROM_LINRGB: Array<DoubleArray> = arrayOf(
        doubleArrayOf(0.001200833568784504, 0.002389694492170889, 0.0002795742885861124),
        doubleArrayOf(0.0005891086651375999, 0.0029785502573438758, 0.0003270666104008398),
        doubleArrayOf(0.00010146692491640572, 0.0005364214359186694, 0.0032979401770712076),
    )

    /** 缩放后的锥体响应 -> linrgb（上游 LINRGB_FROM_SCALED_DISCOUNT）。 */
    val LINRGB_FROM_SCALED_DISCOUNT: Array<DoubleArray> = arrayOf(
        doubleArrayOf(1373.2198709594231, -1100.4251190754821, -7.278681089101213),
        doubleArrayOf(-271.815969077903, 559.6580465940733, -32.46047482791194),
        doubleArrayOf(1.9622899599665666, -57.173814538844006, 308.7233197812385),
    )

    /** Y = kR*r + kG*g + kB*b（Rec.709 亮度系数）。 */
    val Y_FROM_LINRGB: DoubleArray = doubleArrayOf(0.2126, 0.7152, 0.0722)

    /**
     * 0..255 的「临界平面」：delinearize 后落在整数边界的 255 个 linrgb 坐标。
     * 二分时只在临界平面之间移动，保证结果可被 8bit 精确表示。
     */
    val CRITICAL_PLANES: DoubleArray = doubleArrayOf(
        0.015176349177441876, 0.045529047532325624, 0.07588174588720938, 0.10623444424209313,
        0.13658714259697685, 0.16693984095186062, 0.19729253930674434, 0.2276452376616281,
        0.2579979360165119, 0.28835063437139563, 0.3188300904430532, 0.350925934958123,
        0.3848314933096426, 0.42057480301049466, 0.458183274052838, 0.4976837250274023,
        0.5391024159806381, 0.5824650784040898, 0.6277969426914107, 0.6751227633498623,
        0.7244668422128921, 0.775853049866786, 0.829304845476233, 0.8848452951698498,
        0.942497089126609, 1.0022825574869039, 1.0642236851973577, 1.1283421258858297,
        1.1946592148522128, 1.2631959812511864, 1.3339731595349034, 1.407011200216447,
        1.4823302800086415, 1.5599503113873272, 1.6398909516233677, 1.7221716113234105,
        1.8068114625156377, 1.8938294463134073, 1.9832442801866852, 2.075074464868551,
        2.1693382909216234, 2.2660538449872063, 2.36523901573795, 2.4669114995532007,
        2.5710888059345764, 2.6777882626779785, 2.7870270208169257, 2.898822059350997,
        3.0131901897720907, 3.1301480604002863, 3.2497121605402226, 3.3718988244681087,
        3.4967242352587946, 3.624204428461639, 3.754355295633311, 3.887192587735158,
        4.022731918402185, 4.160988767090289, 4.301978482107941, 4.445716283538092,
        4.592217266055746, 4.741496401646282, 4.893568542229298, 5.048448422192488,
        5.20615066083972, 5.3666897647573375, 5.5300801301023865, 5.696336044816294,
        5.865471690767354, 6.037501145825082, 6.212438385869475, 6.390297286737924,
        6.571091626112461, 6.7548350853498045, 6.941541251256611, 7.131223617812143,
        7.323895587840543, 7.5195704746346665, 7.7182615035334345, 7.919981813454504,
        8.124744458384042, 8.332562408825165, 8.543448553206703, 8.757415699253682,
        8.974476575321063, 9.194643831691977, 9.417930041841839, 9.644347703669503,
        9.873909240696694, 10.106627003236781, 10.342513269534024, 10.58158024687427,
        10.8238400726681, 11.069304815507364, 11.317986476196008, 11.569896988756009,
        11.825048221409341, 12.083451977536606, 12.345119996613247, 12.610063955123938,
        12.878295467455942, 13.149826086772048, 13.42466730586372, 13.702830557985108,
        13.984327217668513, 14.269168601521828, 14.55736596900856, 14.848930523210871,
        15.143873411576273, 15.44220572664832, 15.743938506781891, 16.04908273684337,
        16.35764934889634, 16.66964922287304, 16.985093187232053, 17.30399201960269,
        17.62635644741625, 17.95219714852476, 18.281524751807332, 18.614349837764564,
        18.95068293910138, 19.290534541298456, 19.633915083172692, 19.98083495742689,
        20.331304511189067, 20.685334046541502, 21.042933821039977, 21.404114048223256,
        21.76888489811322, 22.137256497705877, 22.50923893145328, 22.884842241736916,
        23.264076429332462, 23.6469514538663, 24.033477234264016, 24.42366364919083,
        24.817520537484558, 25.21505769858089, 25.61628489293138, 26.021211842414342,
        26.429848230738664, 26.842203703840827, 27.258287870275353, 27.678110301598522,
        28.10168053274597, 28.529008062403893, 28.96010235337422, 29.39497283293396,
        29.83362889318845, 30.276079891419332, 30.722335150426627, 31.172403958865512,
        31.62629557157785, 32.08401920991837, 32.54558406207592, 33.010999283389665,
        33.4802739966603, 33.953417292456834, 34.430438229418264, 34.911345834551085,
        35.39614910352207, 35.88485700094671, 36.37747846067349, 36.87402238606382,
        37.37449765026789, 37.87891309649659, 38.38727753828926, 38.89959975977785,
        39.41588851594697, 39.93615253289054, 40.460400508064545, 40.98864111053629,
        41.520882981230194, 42.05713473317016, 42.597404951718396, 43.141702194811224,
        43.6900349931913, 44.24241185063697, 44.798841244188324, 45.35933162437017,
        45.92389141541209, 46.49252901546552, 47.065252796817916, 47.64207110610409,
        48.22299226451468, 48.808024568002054, 49.3971762874833, 49.9904556690408,
        50.587870934119984, 51.189430279724725, 51.79514187861014, 52.40501387947288,
        53.0190544071392, 53.637271562750364, 54.259673423945976, 54.88626804504493,
        55.517063457223934, 56.15206766869424, 56.79128866487574, 57.43473440856916,
        58.08241284012621, 58.734331877617365, 59.39049941699807, 60.05092333227251,
        60.715611475655585, 61.38457167773311, 62.057811747619894, 62.7353394731159,
        63.417162620860914, 64.10328893648692, 64.79372614476921, 65.48848194977529,
        66.18756403501224, 66.89098006357258, 67.59873767827808, 68.31084450182222,
        69.02730813691093, 69.74813616640164, 70.47333615344107, 71.20291564160104,
        71.93688215501312, 72.67524319850172, 73.41800625771542, 74.16517879925733,
        74.9167682708136, 75.67278210128072, 76.43322770089146, 77.1981124613393,
        77.96744375590167, 78.74122893956174, 79.51947534912904, 80.30219030335869,
        81.08938110306934, 81.88105503125999, 82.67721935322541, 83.4778813166706,
        84.28304815182372, 85.09272707154808, 85.90692527145302, 86.72564993000343,
        87.54890820862819, 88.3767072518277, 89.2090541872801, 90.04595612594655,
        90.88742016217518, 91.73345337380438, 92.58406282226491, 93.43925555268066,
        94.29903859396902, 95.16341895893969, 96.03240364439274, 96.9059996312159,
        97.78421388448044, 98.6670533535366, 99.55452497210776,
    )

    /**
     * Sanitizes a small enough angle in radians.
     *
     * @return A coterminal angle between 0 and 2pi.
     */
    fun sanitizeRadians(angle: Double): Double = (angle + Math.PI * 8) % (Math.PI * 2)

    /**
     * Delinearizes an RGB component, returning a floating-point number（不做 8bit 取整）。
     */
    fun trueDelinearized(rgbComponent: Double): Double {
        val normalized = rgbComponent / 100.0
        val delinearized = if (normalized <= 0.0031308) {
            normalized * 12.92
        } else {
            1.055 * Math.pow(normalized, 1.0 / 2.4) - 0.055
        }
        return delinearized * 255.0
    }

    /** 见 [Cam16.chromaticAdaptation]：上游声明在本类，公式是 CAM16 的通用辅助函数。 */
    fun chromaticAdaptation(component: Double): Double = Cam16.chromaticAdaptation(component)

    /**
     * Returns the hue of a linear RGB color in CAM16.
     *
     * @return The hue of the color in CAM16, in radians.
     */
    fun hueOf(linrgb: DoubleArray): Double {
        val scaledDiscount = MathUtils.matrixMultiply(linrgb, SCALED_DISCOUNT_FROM_LINRGB)
        val rA = chromaticAdaptation(scaledDiscount[0])
        val gA = chromaticAdaptation(scaledDiscount[1])
        val bA = chromaticAdaptation(scaledDiscount[2])
        // redness-greenness
        val a = (11.0 * rA + -12.0 * gA + bA) / 11.0
        // yellowness-blueness
        val b = (rA + gA - 2.0 * bA) / 9.0
        return Math.atan2(b, a)
    }

    fun areInCyclicOrder(a: Double, b: Double, c: Double): Boolean {
        val deltaAB = sanitizeRadians(b - a)
        val deltaAC = sanitizeRadians(c - a)
        return deltaAB < deltaAC
    }

    /**
     * Solves the lerp equation.
     *
     * @return A number t such that lerp(source, target, t) = mid.
     */
    fun intercept(source: Double, mid: Double, target: Double): Double =
        (mid - source) / (target - source)

    fun lerpPoint(source: DoubleArray, t: Double, target: DoubleArray): DoubleArray =
        doubleArrayOf(
            source[0] + (target[0] - source[0]) * t,
            source[1] + (target[1] - source[1]) * t,
            source[2] + (target[2] - source[2]) * t,
        )

    /**
     * Intersects a segment with a plane.
     *
     * @param axis The axis the plane is perpendicular with. (0: R, 1: G, 2: B)
     */
    fun setCoordinate(
        source: DoubleArray,
        coordinate: Double,
        target: DoubleArray,
        axis: Int,
    ): DoubleArray {
        val t = intercept(source[axis], coordinate, target[axis])
        return lerpPoint(source, t, target)
    }

    /** linrgb 的每个分量是否都在色域内（0..100）。 */
    fun isBounded(x: Double): Boolean = 0.0 <= x && x <= 100.0

    /**
     * Returns the nth possible vertex of the polygonal intersection.
     *
     * @param y The Y value of the plane.
     * @param n The zero-based index of the point. 0 <= n <= 11.
     * @return 该顶点（linrgb）；顶点不在立方体内时返回 [-1, -1, -1]。
     */
    fun nthVertex(y: Double, n: Int): DoubleArray {
        val kR = Y_FROM_LINRGB[0]
        val kG = Y_FROM_LINRGB[1]
        val kB = Y_FROM_LINRGB[2]
        val coordA = if (n % 4 <= 1) 0.0 else 100.0
        val coordB = if (n % 2 == 0) 0.0 else 100.0
        if (n < 4) {
            val g = coordA
            val b = coordB
            val r = (y - g * kG - b * kB) / kR
            return if (isBounded(r)) doubleArrayOf(r, g, b) else doubleArrayOf(-1.0, -1.0, -1.0)
        } else if (n < 8) {
            val b = coordA
            val r = coordB
            val g = (y - r * kR - b * kB) / kG
            return if (isBounded(g)) doubleArrayOf(r, g, b) else doubleArrayOf(-1.0, -1.0, -1.0)
        } else {
            val r = coordA
            val g = coordB
            val b = (y - r * kR - g * kG) / kB
            return if (isBounded(b)) doubleArrayOf(r, g, b) else doubleArrayOf(-1.0, -1.0, -1.0)
        }
    }

    /**
     * Finds the segment containing the desired color.
     *
     * @return 目标色所在线段的两端点（linrgb）。
     */
    fun bisectToSegment(y: Double, targetHue: Double): Array<DoubleArray> {
        var left = doubleArrayOf(-1.0, -1.0, -1.0)
        var right = left
        var leftHue = 0.0
        var rightHue = 0.0
        var initialized = false
        var uncut = true
        for (n in 0 until 12) {
            val mid = nthVertex(y, n)
            if (mid[0] < 0) {
                continue
            }
            val midHue = hueOf(mid)
            if (!initialized) {
                left = mid
                right = mid
                leftHue = midHue
                rightHue = midHue
                initialized = true
                continue
            }
            if (uncut || areInCyclicOrder(leftHue, midHue, rightHue)) {
                uncut = false
                if (areInCyclicOrder(leftHue, targetHue, midHue)) {
                    right = mid
                    rightHue = midHue
                } else {
                    left = mid
                    leftHue = midHue
                }
            }
        }
        return arrayOf(left, right)
    }

    fun midpoint(a: DoubleArray, b: DoubleArray): DoubleArray =
        doubleArrayOf((a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2)

    fun criticalPlaneBelow(x: Double): Int = Math.floor(x - 0.5).toInt()

    fun criticalPlaneAbove(x: Double): Int = Math.ceil(x - 0.5).toInt()

    /**
     * Finds a color with the given Y and hue on the boundary of the cube.
     *
     * @return The desired color, in linear RGB coordinates.
     */
    fun bisectToLimit(y: Double, targetHue: Double): DoubleArray {
        val segment = bisectToSegment(y, targetHue)
        var left = segment[0]
        var leftHue = hueOf(left)
        var right = segment[1]
        for (axis in 0 until 3) {
            if (left[axis] != right[axis]) {
                var lPlane: Int
                var rPlane: Int
                if (left[axis] < right[axis]) {
                    lPlane = criticalPlaneBelow(trueDelinearized(left[axis]))
                    rPlane = criticalPlaneAbove(trueDelinearized(right[axis]))
                } else {
                    lPlane = criticalPlaneAbove(trueDelinearized(left[axis]))
                    rPlane = criticalPlaneBelow(trueDelinearized(right[axis]))
                }
                for (i in 0 until 8) {
                    if (Math.abs(rPlane - lPlane) <= 1) {
                        break
                    } else {
                        val mPlane = Math.floor((lPlane + rPlane) / 2.0).toInt()
                        val midPlaneCoordinate = CRITICAL_PLANES[mPlane]
                        val mid = setCoordinate(left, midPlaneCoordinate, right, axis)
                        val midHue = hueOf(mid)
                        if (areInCyclicOrder(leftHue, targetHue, midHue)) {
                            right = mid
                            rPlane = mPlane
                        } else {
                            left = mid
                            leftHue = midHue
                            lPlane = mPlane
                        }
                    }
                }
            }
        }
        return midpoint(left, right)
    }

    fun inverseChromaticAdaptation(adapted: Double): Double {
        val adaptedAbs = Math.abs(adapted)
        val base = maxOf(0.0, 27.13 * adaptedAbs / (400.0 - adaptedAbs))
        return MathUtils.signum(adapted) * Math.pow(base, 1.0 / 0.42)
    }

    /**
     * Finds a color with the given hue, chroma, and Y.
     *
     * @return 命中时返回 ARGB；未命中返回 0（由调用方回落到二分法）。
     */
    fun findResultByJ(hueRadians: Double, chroma: Double, y: Double): Int {
        // Initial estimate of j.
        var j = Math.sqrt(y) * 11.0
        // ===========================================================
        // Operations inlined from Cam16 to avoid repeated calculation
        // ===========================================================
        val viewingConditions = ViewingConditions.DEFAULT
        val tInnerCoeff = 1 / Math.pow(1.64 - Math.pow(0.29, viewingConditions.n), 0.73)
        val eHue = 0.25 * (Math.cos(hueRadians + 2.0) + 3.8)
        val p1 = eHue * (50000.0 / 13.0) * viewingConditions.nc * viewingConditions.ncb
        val hSin = Math.sin(hueRadians)
        val hCos = Math.cos(hueRadians)
        for (iterationRound in 0 until 5) {
            // ===========================================================
            // Operations inlined from Cam16 to avoid repeated calculation
            // ===========================================================
            val jNormalized = j / 100.0
            val alpha = if (chroma == 0.0 || j == 0.0) 0.0 else chroma / Math.sqrt(jNormalized)
            val t = Math.pow(alpha * tInnerCoeff, 1.0 / 0.9)
            val ac = viewingConditions.aw *
                Math.pow(jNormalized, 1.0 / viewingConditions.c / viewingConditions.z)
            val p2 = ac / viewingConditions.nbb
            val gamma = 23.0 * (p2 + 0.305) * t / (23.0 * p1 + 11 * t * hCos + 108.0 * t * hSin)
            val a = gamma * hCos
            val b = gamma * hSin
            val rA = (460.0 * p2 + 451.0 * a + 288.0 * b) / 1403.0
            val gA = (460.0 * p2 - 891.0 * a - 261.0 * b) / 1403.0
            val bA = (460.0 * p2 - 220.0 * a - 6300.0 * b) / 1403.0
            val rCScaled = inverseChromaticAdaptation(rA)
            val gCScaled = inverseChromaticAdaptation(gA)
            val bCScaled = inverseChromaticAdaptation(bA)
            val linrgb = MathUtils.matrixMultiply(
                doubleArrayOf(rCScaled, gCScaled, bCScaled),
                LINRGB_FROM_SCALED_DISCOUNT,
            )
            // ===========================================================
            // Operations inlined from Cam16 to avoid repeated calculation
            // ===========================================================
            if (linrgb[0] < 0 || linrgb[1] < 0 || linrgb[2] < 0) {
                return 0
            }
            val kR = Y_FROM_LINRGB[0]
            val kG = Y_FROM_LINRGB[1]
            val kB = Y_FROM_LINRGB[2]
            val fnj = kR * linrgb[0] + kG * linrgb[1] + kB * linrgb[2]
            if (fnj <= 0) {
                return 0
            }
            if (iterationRound == 4 || Math.abs(fnj - y) < 0.002) {
                if (linrgb[0] > 100.01 || linrgb[1] > 100.01 || linrgb[2] > 100.01) {
                    return 0
                }
                return ColorUtils.argbFromLinrgb(linrgb)
            }
            // Iterates with Newton method,
            // Using 2 * fn(j) / j as the approximation of fn'(j)
            j = j - (fnj - y) * j / (2 * fnj)
        }
        return 0
    }

    /**
     * Finds an sRGB color with the given hue, chroma, and L*, if possible.
     *
     * @return ARGB。色域内无解时，hue 与 L* 仍然足够接近，chroma 取该 hue/tone 的最大值。
     */
    fun solveToInt(hueDegrees: Double, chroma: Double, lstar: Double): Int {
        // 极端 tone（纯黑/纯白附近）与零 chroma 直接返回灰度，避免后面的数值爆炸。
        if (chroma < 0.0001 || lstar < 0.0001 || lstar > 99.9999) {
            return ColorUtils.argbFromLstar(lstar)
        }
        val hueDegreesSafe = MathUtils.sanitizeDegreesDouble(hueDegrees)
        val hueRadians = hueDegreesSafe / 180 * Math.PI
        val y = ColorUtils.yFromLstar(lstar)
        val exactAnswer = findResultByJ(hueRadians, chroma, y)
        if (exactAnswer != 0) {
            return exactAnswer
        }
        val linrgb = bisectToLimit(y, hueRadians)
        return ColorUtils.argbFromLinrgb(linrgb)
    }

    /** 同 [solveToInt]，但返回 CAM16 对象。 */
    fun solveToCam(hueDegrees: Double, chroma: Double, lstar: Double): Cam16 =
        Cam16.fromInt(solveToInt(hueDegrees, chroma, lstar))
}
