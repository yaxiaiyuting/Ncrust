/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.6.1 · P0：艺人跳转身份判定 + 值域闸门 + 置信度闸门的单测。
 */

package com.takahashirinta.ncrust.source

import com.takahashirinta.ncrust.crosssource.MatchConfidence
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.1 · P0 的回归单测。
 *
 * ## 钉住的是哪两件事（两台真机、同一条菜单项、两种错法）
 *
 * | 场景 | 修复前的真实表现 | 本文件的用例 |
 * |---|---|---|
 * | QQ 曲目**新鲜**加载（`singer.id=4558`，无 mid） | 跳到网易云的**马洪波** | [QQ 曲目只有数字 singerID 时跳搜索而不是猜一个艺人] |
 * | QQ 曲目**冷启动恢复**（`id=null`，只有名字） | **毫无反应**（静默失败） | [冷启动恢复的 QQ 曲目只有名字时跳搜索] |
 *
 * 夹具里的数字全部来自 2026-09 的真实接口响应（匿名可复现）：
 * QQ `singer` = `{id:4558, mid:"0025NhlN2yWrP4", name:"周杰伦"}`；
 * 网易云 `api/artist/4558` = 马洪波、`api/artist/6452` = 周杰伦。
 */
class ArtistNavigatorTest {

    // ---------------------------------------------------------------- 夹具

    private fun song(
        source: MusicSource,
        artist: ArtistItem?,
        id: Long = if (source == MusicSource.QQMUSIC) 0x4000_0000_0001_79ADL else 186016L,
    ) = SongItem(
        id = id,
        name = "晴天",
        artists = artist?.let { listOf(it) },
        album = AlbumItem(id = 8220L, name = "叶惠美", picUrl = null),
        duration = 269_000L,
        source = if (source == MusicSource.NETEASE) null else source.key,
        sourceId = if (source == MusicSource.QQMUSIC) "0039MnYb0qxYhV" else null,
    )

    /** 真实形状：QQ 同时给了数字 id 与 singerMID。 */
    private val qqArtistFull = ArtistItem(id = 4558L, name = "周杰伦", mid = "0025NhlN2yWrP4")

    /** 旧版本 / 丢失路径的形状：只剩数字 id。 */
    private val qqArtistNumericOnly = ArtistItem(id = 4558L, name = "周杰伦", mid = null)

    /** 冷启动恢复的形状：`PlaybackStateManager` 只存了艺人**名字**。 */
    private val restoredArtist = ArtistItem(id = null, name = "周杰伦", mid = null)

    private val neArtist = ArtistItem(id = 6452L, name = "周杰伦")

    // ------------------------------------------------ 1. 主路径：本源内身份

    @Test
    fun `QQ 曲目带 singerMID 时直接进 QQ 艺人页`() {
        val nav = ArtistNavigator.resolve(song(MusicSource.QQMUSIC, qqArtistFull))
        assertEquals(ArtistNav.Direct(MusicSource.QQMUSIC, "0025NhlN2yWrP4"), nav)
    }

    @Test
    fun `网易云曲目带十进制 id 时直接进网易云艺人页`() {
        val nav = ArtistNavigator.resolve(song(MusicSource.NETEASE, neArtist))
        assertEquals(ArtistNav.Direct(MusicSource.NETEASE, "6452"), nav)
    }

    // ------------------------------------ 2. P0 本体：绝不把数字 QQ id 当身份

    @Test
    fun `QQ 曲目只有数字 singerID 时跳搜索而不是猜一个艺人`() {
        val nav = ArtistNavigator.resolve(song(MusicSource.QQMUSIC, qqArtistNumericOnly))
        assertEquals(
            "只有 QQ 的数字 singerID（4558）时必须降级为搜索 —— "
                + "拿它当网易云 id 查会得到马洪波，这正是 v2.6.1 的 P0",
            ArtistNav.Search("周杰伦", ArtistNavReason.AMBIGUOUS_NUMERIC_ID),
            nav,
        )
    }

    @Test
    fun `数字 4558 永远不会出现在任何 Direct 里`() {
        // 反向钉住：把所有入口扫一遍，断言 "4558" 这个串谁都不要。
        // （只断言 `!= Search` 是不够的 —— 错误实现会产出 Direct(NETEASE,"4558")。）
        val cases = listOf(
            song(MusicSource.QQMUSIC, qqArtistNumericOnly),
            song(MusicSource.QQMUSIC, qqArtistFull),
            song(MusicSource.NETEASE, neArtist),
            song(MusicSource.QQMUSIC, restoredArtist),
        )
        for (s in cases) {
            val nav = ArtistNavigator.resolve(s)
            if (nav is ArtistNav.Direct) {
                assertNotEquals("Direct 的 id 不许是 QQ 的数字 singerID", "4558", nav.id)
            }
        }
    }

    @Test
    fun `QQ 的 mid 被塞进网易云值域时同样不跳`() {
        // 损坏数据的形状：source 说网易云，artist 却是 base62 的 QQ mid。
        val broken = SongItem(
            id = 186016L,
            name = "晴天",
            artists = listOf(ArtistItem(id = null, name = "周杰伦", mid = "0025NhlN2yWrP4")),
            album = null,
            duration = null,
            source = MusicSource.NETEASE.key,
        )
        assertEquals(
            ArtistNav.Search("周杰伦", ArtistNavReason.MISSING_ID),
            ArtistNavigator.resolve(broken),
        )
    }

    // ------------------------------------------ 3. 冷启动恢复：静默失败那一半

    @Test
    fun `冷启动恢复的 QQ 曲目只有名字时跳搜索`() {
        val nav = ArtistNavigator.resolve(song(MusicSource.QQMUSIC, restoredArtist))
        assertEquals(ArtistNav.Search("周杰伦", ArtistNavReason.MISSING_ID), nav)
    }

    @Test
    fun `冷启动恢复的网易云曲目只有名字时跳搜索`() {
        val nav = ArtistNavigator.resolve(song(MusicSource.NETEASE, ArtistItem(name = "周杰伦")))
        assertEquals(ArtistNav.Search("周杰伦", ArtistNavReason.MISSING_ID), nav)
    }

    @Test
    fun `连名字都没有时什么都不做`() {
        assertEquals(ArtistNav.Unavailable, ArtistNavigator.resolve(song(MusicSource.QQMUSIC, null)))
        assertEquals(
            ArtistNav.Unavailable,
            ArtistNavigator.resolve(song(MusicSource.QQMUSIC, ArtistItem(id = 4558L, name = "   "))),
        )
    }

    // -------------------------------------------------- 4. 不变量：绝不跨源跳

    @Test
    fun `任何情况下 Direct 的 source 都等于歌曲自己的音源`() {
        val candidates = listOf(
            qqArtistFull, qqArtistNumericOnly, restoredArtist, neArtist,
            ArtistItem(id = 999L, name = "某人", mid = "001BLpXF2DyJe2"),
            ArtistItem(id = 6452L, name = "周杰伦", mid = "0025NhlN2yWrP4"), // 两域都填
        )
        val brokenSources = listOf(
            MusicSource.QQMUSIC to SongItem(
                id = 1L, name = "x", artists = listOf(neArtist), album = null, duration = null,
                source = MusicSource.QQMUSIC.key,
            ),
            MusicSource.NETEASE to SongItem(
                id = 1L, name = "x", artists = listOf(qqArtistFull), album = null, duration = null,
                source = MusicSource.NETEASE.key,
            ),
        )

        for (s in listOf(MusicSource.NETEASE, MusicSource.QQMUSIC)) {
            for (a in candidates) {
                val nav = ArtistNavigator.resolve(song(s, a))
                if (nav is ArtistNav.Direct) {
                    assertEquals(
                        "跳转必须留在歌曲自己的音源里（跨源跳转不是本应用的产品行为）",
                        s, nav.source,
                    )
                }
            }
        }
        for ((declared, s) in brokenSources) {
            val nav = ArtistNavigator.resolve(s)
            if (nav is ArtistNav.Direct) assertEquals("Direct 不许跨源", declared, nav.source)
        }
    }

    // -------------------------------------------------- 5. 值域闸门（纯函数）

    @Test
    fun `网易云值域只吃十进制且小于 2 的 40 次方`() {
        assertTrue(ArtistNavigator.idDomainMatches(MusicSource.NETEASE, "6452"))
        assertTrue(ArtistNavigator.idDomainMatches(MusicSource.NETEASE, "1"))
        assertTrue(ArtistNavigator.idDomainMatches(MusicSource.NETEASE, " 6452 "))
        assertFalse("0 不是合法艺人 id", ArtistNavigator.idDomainMatches(MusicSource.NETEASE, "0"))
        assertFalse("负数", ArtistNavigator.idDomainMatches(MusicSource.NETEASE, "-1"))
        assertFalse("QQ 的 singerMID 不是网易云 id", ArtistNavigator.idDomainMatches(MusicSource.NETEASE, "0025NhlN2yWrP4"))
        assertFalse("空", ArtistNavigator.idDomainMatches(MusicSource.NETEASE, ""))
        assertFalse("null", ArtistNavigator.idDomainMatches(MusicSource.NETEASE, null))
    }

    @Test
    fun `QQ 值域只吃 base62 且拒绝网易云值域内的纯数字`() {
        assertTrue(ArtistNavigator.idDomainMatches(MusicSource.QQMUSIC, "0025NhlN2yWrP4"))
        assertTrue(ArtistNavigator.idDomainMatches(MusicSource.QQMUSIC, "001BLpXF2DyJe2"))
        assertTrue(ArtistNavigator.idDomainMatches(MusicSource.QQMUSIC, "003Nz2So3XXYek"))
        // 本 P0 的关键一条：QQ 的数字 singerID **不是** QQ 域内的合法字符串身份。
        assertFalse("4558 是 singerID，不是 singerMID", ArtistNavigator.idDomainMatches(MusicSource.QQMUSIC, "4558"))
        assertFalse("143 同理", ArtistNavigator.idDomainMatches(MusicSource.QQMUSIC, "143"))
        assertFalse("0", ArtistNavigator.idDomainMatches(MusicSource.QQMUSIC, "0"))
        assertFalse("太短", ArtistNavigator.idDomainMatches(MusicSource.QQMUSIC, "ab1"))
        assertFalse("非 base62", ArtistNavigator.idDomainMatches(MusicSource.QQMUSIC, "0025-Nhl_2y"))
        assertFalse("空", ArtistNavigator.idDomainMatches(MusicSource.QQMUSIC, ""))
        assertFalse("null", ArtistNavigator.idDomainMatches(MusicSource.QQMUSIC, null))
    }

    @Test
    fun `QQ 合成 id 的十进制形态落在网易云值域之外`() {
        // SourceIds.qqId 造的 id 带 bit62；把它当艺人 id 用必须被值域闸门拦下。
        val synthetic = SourceIds.qqId(97773L, "0039MnYb0qxYhV").toString()
        assertFalse(ArtistNavigator.idDomainMatches(MusicSource.NETEASE, synthetic))
    }

    // -------------------------------------------------- 6. 跨源置信度闸门

    @Test
    fun `跨源跳转只有 mergeable 才放行`() {
        assertTrue(ArtistNavigator.crossSourceJumpAllowed(MatchConfidence.EXACT))
        assertTrue(ArtistNavigator.crossSourceJumpAllowed(MatchConfidence.HIGH))
        assertTrue(ArtistNavigator.crossSourceJumpAllowed(MatchConfidence.MEDIUM))
        assertFalse("LOW 绝不合并，也绝不允许跨源跳", ArtistNavigator.crossSourceJumpAllowed(MatchConfidence.LOW))
        assertFalse(ArtistNavigator.crossSourceJumpAllowed(MatchConfidence.NONE))
        assertFalse("没有结论 ≠ 可以跳", ArtistNavigator.crossSourceJumpAllowed(null))
    }

    @Test
    fun `跨源跳闸门两道都要过——置信度够但目标值域不对也不放行`() {
        assertNull(
            "MEDIUM 但给的是 QQ 的数字 id ⇒ 仍然不许跳",
            ArtistNavigator.crossSourceJump(MatchConfidence.MEDIUM, MusicSource.QQMUSIC, "4558"),
        )
        assertNull(
            ArtistNavigator.crossSourceJump(MatchConfidence.LOW, MusicSource.QQMUSIC, "0025NhlN2yWrP4"),
        )
        assertNull(
            ArtistNavigator.crossSourceJump(null, MusicSource.NETEASE, "6452"),
        )
        assertEquals(
            ArtistNav.Direct(MusicSource.QQMUSIC, "0025NhlN2yWrP4"),
            ArtistNavigator.crossSourceJump(MatchConfidence.EXACT, MusicSource.QQMUSIC, "0025NhlN2yWrP4"),
        )
    }

    // -------------------------------------------------- 7. 溯源信息

    @Test
    fun `降级为搜索时必须带得出原因`() {
        // 规则 19 的同一条纪律：跳转必须是**可追溯**的。reason 会进 logcat（TAG=ArtistNav）。
        val numericOnly = ArtistNavigator.resolve(song(MusicSource.QQMUSIC, qqArtistNumericOnly))
        val restored = ArtistNavigator.resolve(song(MusicSource.QQMUSIC, restoredArtist))
        assertTrue(numericOnly is ArtistNav.Search)
        assertTrue(restored is ArtistNav.Search)
        assertNotEquals(
            "「只有数字 id」与「连 id 都没有」必须能区分，否则线上排不出是哪种旧数据",
            (numericOnly as ArtistNav.Search).reason, (restored as ArtistNav.Search).reason,
        )
    }
}
