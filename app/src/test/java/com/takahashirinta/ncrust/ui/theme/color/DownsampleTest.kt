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

// CoverPaletteExtractor.downsample 的契约测试：**均值下采样（box average）**、等比缩到最长边 = dstMax、
// 源图不大于 dstMax 时原样返回（同一个数组实例）、非法输入返回空数组而不是抛异常。

package com.takahashirinta.ncrust.ui.theme.color

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DownsampleTest {

    private val red = 0xFFD32F2F.toInt()

    /** 200x100 -> 最长边 50 => 50x25（每边等比缩小 4 倍）。 */
    @Test
    fun wideImageScalesToFiftyByTwentyFive() {
        val src = IntArray(200 * 100) { red }
        val dst = CoverPaletteExtractor.downsample(src, 200, 100, 50)
        assertEquals(50 * 25, dst.size)
        assertTrue(dst.all { it == red })
    }

    /** 100x200 -> 25x50（长边是高度），说明不是"只缩宽度"。 */
    @Test
    fun tallImageScalesToTwentyFiveByFifty() {
        val src = IntArray(100 * 200) { red }
        val dst = CoverPaletteExtractor.downsample(src, 100, 200, 50)
        assertEquals(25 * 50, dst.size)
        assertTrue(dst.all { it == red })
    }

    /** 源图最长边 <= 上限：原样返回（同一个实例，不拷贝）。 */
    @Test
    fun smallImageIsReturnedUnchanged() {
        val src = IntArray(30 * 20) { red }
        val dst = CoverPaletteExtractor.downsample(src, 30, 20, 50)
        assertSame(src, dst)
        assertEquals(600, dst.size)

        val exact = IntArray(50 * 50) { red }
        assertSame(exact, CoverPaletteExtractor.downsample(exact, 50, 50, 50))
    }

    /** 均值下采样：2x1 的黑白 -> 一个 0xFF7F7F7F（127.5 截断为 127）。 */
    @Test
    fun averagingIsExactBoxMean() {
        val src = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        val dst = CoverPaletteExtractor.downsample(src, 2, 1, 1)
        assertEquals(1, dst.size)
        assertEquals(0xFF7F7F7F.toInt(), dst[0])
    }

    /** 4x4 -> 2x2：分块边界必须按 `x0 = dx*srcW/dstW` 取，每块恰好 2x2。 */
    @Test
    fun fourByFourSplitsIntoFourUniformBlocks() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val gray = 0xFF808080.toInt()
        val src = intArrayOf(
            black, black, white, white,
            black, black, white, white,
            gray, gray, gray, gray,
            gray, gray, gray, gray,
        )
        val dst = CoverPaletteExtractor.downsample(src, 4, 4, 2)
        assertEquals(4, dst.size)
        assertEquals(black, dst[0]) // 左上块
        assertEquals(white, dst[1]) // 右上块
        assertEquals(gray, dst[2]) // 左下块
        assertEquals(gray, dst[3]) // 右下块
    }

    /** 2x2 -> 1x1：四个不同像素的算术平均，(0+255+128+64)/4 = 111.75 -> 111。 */
    @Test
    fun twoByTwoAveragesAllFourPixels() {
        val src = intArrayOf(
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
            0xFF808080.toInt(), 0xFF404040.toInt(),
        )
        val dst = CoverPaletteExtractor.downsample(src, 2, 2, 1)
        assertEquals(1, dst.size)
        assertEquals(0xFF6F6F6F.toInt(), dst[0])
    }

    /** 非法输入：返回空数组，不抛异常。 */
    @Test
    fun invalidArgumentsReturnEmptyArray() {
        assertEquals(0, CoverPaletteExtractor.downsample(IntArray(0), 0, 0, 50).size)
        assertEquals(0, CoverPaletteExtractor.downsample(IntArray(10), 10, 1, 0).size)
        assertEquals(0, CoverPaletteExtractor.downsample(IntArray(10), -1, 1, 50).size)
        assertEquals(0, CoverPaletteExtractor.downsample(IntArray(10), 1, -1, 50).size)
        // 声明的尺寸大于实际数组长度：返回空数组而不是越界。
        assertEquals(0, CoverPaletteExtractor.downsample(IntArray(10), 100, 100, 50).size)
    }

    /** 空输入 + 合法尺寸：空进空出。 */
    @Test
    fun emptyInputWithValidSizeReturnsEmpty() {
        assertEquals(0, CoverPaletteExtractor.downsample(IntArray(0), 0, 0, 50).size)
    }

    /** 下采样结果必须保持"每个像素都是输入像素的混合"，不引入越界或未初始化数据。 */
    @Test
    fun downsampledOutputContainsNoUninitializedPixels() {
        val src = IntArray(101 * 37) { ColorUtils.argbFromRgb(it % 256, (it * 3) % 256, (it * 7) % 256) }
        val dst = CoverPaletteExtractor.downsample(src, 101, 37, 50)
        assertEquals(50 * 18, dst.size)
        for (argb in dst) {
            assertEquals("alpha 必须保持不透明", 255, ColorUtils.alphaFromArgb(argb))
        }
    }

    /** 下采样是纯函数：不修改输入数组。 */
    @Test
    fun downsampleDoesNotMutateInput() {
        val src = IntArray(200 * 100) { red }
        val snapshot = src.copyOf()
        CoverPaletteExtractor.downsample(src, 200, 100, 50)
        assertArrayEquals(snapshot, src)
    }

    /** 端到端：大图降采样后再取色，同样能得到配色（即调用方能按 50x50 喂进来）。 */
    @Test
    fun downsampledImageFeedsExtractor() {
        val src = IntArray(400 * 200) { red }
        val small = CoverPaletteExtractor.downsample(src, 400, 200, 50)
        assertEquals(50 * 25, small.size)
        val palette = CoverPaletteExtractor.extract(small, false)
        assertEquals(0xFFBA1A20.toInt(), palette!!.primary)
    }
}
