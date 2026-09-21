package com.takahashirinta.ncrust.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B2-C 颜色处理的回归测试：饱和度上限、亮度锚定、对比度下限、灰度兜底。 */
class AccentColorTest {

    private val samples = intArrayOf(
        0xFF1DB954.toInt(), 0xFF3B82F6.toInt(), 0xFFEF4444.toInt(), 0xFFF59E0B.toInt(),
        0xFF8B5CF6.toInt(), 0xFFFF00FF.toInt(), 0xFF00FF00.toInt(), 0xFFFFEB3B.toInt(),
        0xFF00E5FF.toInt(), 0xFF7F00FF.toInt(),
    )

    private fun hsvOf(argb: Int): FloatArray {
        val hsv = FloatArray(3)
        rgbToHsv((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, hsv)
        return hsv
    }

    @Test
    fun `onPrimary always reaches AA contrast on processed accents`() {
        for (isDark in listOf(true, false)) {
            for (argb in samples) {
                val accent = processAccentColor(argb, isDark) ?: continue
                val ratio = contrastRatio(onAccentColor(accent).toArgb(), accent.toArgb())
                assertTrue(
                    "contrast=%.2f argb=%08x dark=%s".format(ratio, argb, isDark),
                    ratio >= MIN_ACCENT_CONTRAST
                )
            }
        }
    }

    @Test
    fun `saturation is clamped to 0_6`() {
        for (isDark in listOf(true, false)) {
            for (argb in samples) {
                val accent = processAccentColor(argb, isDark) ?: continue
                // 容差留给 HSV<->RGB 的整数色阶往返（±1 色阶 → 饱和度误差 <0.5%）。
                assertTrue(hsvOf(accent.toArgb())[1] <= 0.605f)
            }
        }
    }

    @Test
    fun `lightness is anchored per theme mode`() {
        for (argb in samples) {
            processAccentColor(argb, isDark = true)?.let {
                val v = hsvOf(it.toArgb())[2]
                assertTrue("dark v=$v", v in 0.4f..0.6001f)
            }
            processAccentColor(argb, isDark = false)?.let {
                val v = hsvOf(it.toArgb())[2]
                assertTrue("light v=$v", v in 0.4f..0.5501f)
            }
        }
    }

    @Test
    fun `grayscale falls back to preset (returns null)`() {
        assertNull(processAccentColor(0xFF808080.toInt(), isDark = true))
        assertNull(processAccentColor(0xFFFFFFFF.toInt(), isDark = true))
        assertNull(processAccentColor(0xFF000000.toInt(), isDark = false))
    }

    @Test
    fun `hsv round trip keeps hue`() {
        val original = 0xFF1DB954.toInt()
        val hsv = hsvOf(original)
        val back = hsvToArgb(hsv[0], hsv[1], hsv[2])
        // 只要求每个色阶误差 ≤1：整数色阶往返不可能完全无损。
        for (shift in intArrayOf(16, 8, 0)) {
            val a = (original shr shift) and 0xFF
            val b = (back shr shift) and 0xFF
            assertTrue("channel shift=" + shift + " " + a + " vs " + b, kotlin.math.abs(a - b) <= 1)
        }
    }
}
