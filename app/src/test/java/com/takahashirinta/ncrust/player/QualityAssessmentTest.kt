package com.takahashirinta.ncrust.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A3 音质状态判定的回归测试。
 *
 * 阈值与场景都来自真机/接口实测（2026-09，登录态 + PC 身份）：
 * 母带 4.7–5.8 Mbps、jyeffect 2.8–3.1 Mbps、Hi-Res 1.69 Mbps、无损 0.87–0.92 Mbps，
 * 均为 flac；320 kbps 为 mp3。
 */
class QualityAssessmentTest {

    private val levels = QualityLadder.LEVELS
    private fun idx(level: String) = levels.indexOf(level)

    /** 场景 1：标签写 lossless，实际文件是 Hi-Res —— 不能报降级，且要显示为高解析。 */
    @Test
    fun `lossless label with hires bitrate is normal and shown as hires`() {
        val v = QualityAssessment.assess(
            requested = "hires", granted = "lossless",
            br = 1_685_762, type = "flac", songMaxLevel = "hires",
        )
        assertEquals(QualityStatus.NORMAL, v.status)
        assertEquals(idx("hires"), v.displayIndex)
    }

    /** 场景 2：该曲有无损，但账号只拿到 320k —— 提示「无权限」。 */
    @Test
    fun `exhigh with lossless capable song is no entitlement`() {
        val v = QualityAssessment.assess(
            requested = "lossless", granted = "exhigh",
            br = 320_000, type = "mp3", songMaxLevel = "lossless",
        )
        assertEquals(QualityStatus.NO_ENTITLEMENT, v.status)
    }

    /** 场景 3：正常无损，不加任何角标。 */
    @Test
    fun `plain lossless is normal`() {
        val v = QualityAssessment.assess(
            requested = "lossless", granted = "lossless",
            br = 920_600, type = "flac", songMaxLevel = "lossless",
        )
        assertEquals(QualityStatus.NORMAL, v.status)
        assertEquals(idx("lossless"), v.displayIndex)
    }

    /** 场景 4：请求母带但该曲最高只有 Hi-Res —— 提示「该曲无此档位」。 */
    @Test
    fun `jymaster request on hires only song is song lacks tier`() {
        val v = QualityAssessment.assess(
            requested = "jymaster", granted = "hires",
            br = 1_685_762, type = "flac", songMaxLevel = "hires",
        )
        assertEquals(QualityStatus.SONG_LACKS_TIER, v.status)
    }

    /** 场景 5：请求杜比、实际给 jyeffect 的 2.8 Mbps FLAC —— 换格式不是降级。 */
    @Test
    fun `dolby request answered with immersive flac is normal`() {
        val v = QualityAssessment.assess(
            requested = "dolby", granted = "jyeffect",
            br = 2_798_469, type = "flac", songMaxLevel = "jymaster",
        )
        assertEquals(QualityStatus.NORMAL, v.status)
        assertEquals(idx("jyeffect"), v.displayIndex)
    }

    /** 回归：未知档位标签（如未来的 sky）不得退化成索引 0（旧逻辑会显示成「压缩」）。 */
    @Test
    fun `unknown granted label never falls back to index zero`() {
        val v = QualityAssessment.assess(
            requested = "hires", granted = "sky",
            br = 1_685_762, type = "flac", songMaxLevel = "hires",
        )
        assertEquals(QualityStatus.NORMAL, v.status)
        assertEquals(idx("hires"), v.displayIndex)
    }

    /** 拿不到 br/type 时保持安静，不误报。 */
    @Test
    fun `missing file parameters stay normal`() {
        val v = QualityAssessment.assess(
            requested = "hires", granted = "hires",
            br = 0, type = "", songMaxLevel = null,
        )
        assertEquals(QualityStatus.NORMAL, v.status)
    }

    /** 实际文件与请求档位相符：不需要为判定再去问一次该曲的档位上限。 */
    @Test
    fun `capability not fetched when measured matches request`() {
        assertFalse(QualityAssessment.needsSongCapability("hires", 1_685_762, "flac"))
    }

    /** 实际文件低于请求档位：要问一次档位上限，才能区分「无权限」与「该曲无此档位」。 */
    @Test
    fun `capability fetched when measured below request`() {
        assertTrue(QualityAssessment.needsSongCapability("hires", 920_600, "flac"))
    }

    /** 沉浸声换格式：判定必然 NORMAL，不必多花一次请求。 */
    @Test
    fun `capability skipped for immersive substitution`() {
        assertFalse(QualityAssessment.needsSongCapability("dolby", 2_798_469, "flac"))
    }

    /** 沉浸声却只给到 320k：这是真降级，要问档位上限。 */
    @Test
    fun `capability fetched when immersive degrades to mp3`() {
        assertTrue(QualityAssessment.needsSongCapability("dolby", 320_000, "mp3"))
    }

    /** 请求母带、实际给 320k（该曲其实有母带）—— 属于无权限。 */
    @Test
    fun `jymaster request answered with mp3 is no entitlement`() {
        val v = QualityAssessment.assess(
            requested = "jymaster", granted = "exhigh",
            br = 320_000, type = "mp3", songMaxLevel = "jymaster",
        )
        assertEquals(QualityStatus.NO_ENTITLEMENT, v.status)
    }
}
