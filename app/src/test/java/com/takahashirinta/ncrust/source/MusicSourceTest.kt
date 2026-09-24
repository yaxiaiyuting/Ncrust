package com.takahashirinta.ncrust.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1.0 · A：音源枚举与标识符编解码的纯逻辑单测。
 *
 * 这些用例的重点**不是**「新功能能用」，而是「旧数据不会被读错」：
 * v2.1.0 之前持久化的队列 JSON 没有 source 字段、离线缓存 key 是 `song:<id>:<level>`，
 * 一旦解析成别的音源，老用户升级后就是「歌单还在、一首都放不出来」。
 */
class MusicSourceTest {

    // ---------- MusicSource.fromKey：向后兼容是主诉求 ----------

    @Test
    fun `null 与空串回落网易云`() {
        assertEquals(MusicSource.NETEASE, MusicSource.fromKey(null))
        assertEquals(MusicSource.NETEASE, MusicSource.fromKey(""))
    }

    @Test
    fun `不认识的值回落网易云而不是抛异常`() {
        assertEquals(MusicSource.NETEASE, MusicSource.fromKey("spotify"))
        assertEquals(MusicSource.NETEASE, MusicSource.fromKey("NETEASE")) // 大小写敏感是有意的
        assertEquals(MusicSource.NETEASE, MusicSource.fromKey(" qqmusic"))
    }

    @Test
    fun `已知 key 正确解析`() {
        assertEquals(MusicSource.NETEASE, MusicSource.fromKey("netease"))
        assertEquals(MusicSource.QQMUSIC, MusicSource.fromKey("qqmusic"))
    }

    @Test
    fun `key 与 ordinal 解耦——key 是写进持久化数据的稳定值`() {
        assertEquals("netease", MusicSource.NETEASE.key)
        assertEquals("qqmusic", MusicSource.QQMUSIC.key)
        // 断言字面量而不是内部一致性：改动这两个字符串等于让所有历史数据指错音源。
    }

    @Test
    fun `只有 QQ 音乐需要 sourceId`() {
        assertTrue(MusicSource.QQMUSIC.requiresSourceId)
        assertFalse(MusicSource.NETEASE.requiresSourceId)
    }

    @Test
    fun `selectable 覆盖全部取值`() {
        assertEquals(MusicSource.values().toList(), MusicSource.selectable)
    }

    // ---------- trackKey ----------

    @Test
    fun `trackKey 往返`() {
        assertEquals(MusicSource.NETEASE to 123L, SourceIds.parseTrackKey(SourceIds.trackKey(MusicSource.NETEASE, 123L)))
        assertEquals(MusicSource.QQMUSIC to 456L, SourceIds.parseTrackKey(SourceIds.trackKey(MusicSource.QQMUSIC, 456L)))
    }

    @Test
    fun `parseTrackKey 拒绝坏输入`() {
        assertNull(SourceIds.parseTrackKey(null))
        assertNull(SourceIds.parseTrackKey(""))
        assertNull(SourceIds.parseTrackKey("netease"))
        assertNull(SourceIds.parseTrackKey("netease:"))
        assertNull(SourceIds.parseTrackKey("netease:abc"))
        assertNull(SourceIds.parseTrackKey("netease:0"))
        assertNull(SourceIds.parseTrackKey("netease:-5"))
        assertNull(SourceIds.parseTrackKey("spotify:5")) // 未知音源不猜
        assertNull(SourceIds.parseTrackKey(":5"))
    }

    // ---------- mediaId ----------

    @Test
    fun `网易云的 mediaId 与 v2_0_2 逐字节相同`() {
        // 这条断言是「老用户升级后当前播放项/通知栏不失效」的守门人。
        assertEquals("song:123", SourceIds.mediaId(MusicSource.NETEASE, 123L))
    }

    @Test
    fun `QQ 音乐的 mediaId 带音源段`() {
        assertEquals("song:qqmusic:456", SourceIds.mediaId(MusicSource.QQMUSIC, 456L))
    }

    @Test
    fun `mediaId 对非正 id 返回 null`() {
        assertNull(SourceIds.mediaId(MusicSource.NETEASE, 0L))
        assertNull(SourceIds.mediaId(MusicSource.QQMUSIC, -1L))
    }

    @Test
    fun `mediaId 往返`() {
        for (source in MusicSource.values()) {
            val id = 987654321L
            assertEquals(source to id, SourceIds.parseMediaId(SourceIds.mediaId(source, id)))
        }
    }

    @Test
    fun `parseMediaId 认历史形状 song_id 为网易云`() {
        // v2.1.0 之前 ExoPlayer 里挂的就是这个形状，升级后正在播的歌不能变成「无法解析」。
        assertEquals(MusicSource.NETEASE to 42L, SourceIds.parseMediaId("song:42"))
    }

    @Test
    fun `parseMediaId 拒绝坏输入而不是猜成网易云`() {
        assertNull(SourceIds.parseMediaId(null))
        assertNull(SourceIds.parseMediaId(""))
        assertNull(SourceIds.parseMediaId("album:42"))
        assertNull(SourceIds.parseMediaId("song:"))
        assertNull(SourceIds.parseMediaId("song:abc"))
        assertNull(SourceIds.parseMediaId("song:0"))
        assertNull(SourceIds.parseMediaId("song:-3"))
        assertNull(SourceIds.parseMediaId("song:spotify:3"))
        assertNull(SourceIds.parseMediaId("song:qqmusic:"))
        assertNull(SourceIds.parseMediaId("song:qqmusic:xyz"))
    }

    // ---------- QQ 兜底 id ----------

    @Test
    fun `兜底 id 恒为负且非零`() {
        for (mid in listOf("0039MnYb0qxYhV", "a", "0039MnYb0qxYhW", "")) {
            val id = SourceIds.fallbackIdFromSourceId(mid)
            assertTrue("mid=$mid id=$id 应为负", id < 0L)
        }
    }

    @Test
    fun `兜底 id 是确定性的——队列持久化与离线缓存都拿它当 key`() {
        val mid = "0039MnYb0qxYhV"
        assertEquals(SourceIds.fallbackIdFromSourceId(mid), SourceIds.fallbackIdFromSourceId(mid))
    }

    @Test
    fun `相邻 mid 不产生相邻 id`() {
        assertNotEquals(
            SourceIds.fallbackIdFromSourceId("0039MnYb0qxYhV"),
            SourceIds.fallbackIdFromSourceId("0039MnYb0qxYhW"),
        )
    }

    @Test
    fun `兜底 id 不会与真实 songid 撞号`() {
        // 服务端 songid 恒为正；兜底 id 恒为负 —— 这是两侧唯一的隔离保证。
        val realSongId = 102065756L
        assertNotEquals(realSongId, SourceIds.fallbackIdFromSourceId("0039MnYb0qxYhV"))
    }

    @Test
    fun `兜底 id 在改写后依然是负数而不溢出`() {
        // 覆盖散列高位为 1 的输入：如果忘了屏蔽符号位，取负会溢出成 Long.MIN_VALUE 附近的正数。
        val ids = listOf("zzzz", "0039MnYb0qxYhV", "\u00ff\u00ff\u00ff", "9".repeat(64))
            .map { SourceIds.fallbackIdFromSourceId(it) }
        for (id in ids) {
            assertTrue("id=$id 必须为负且在 Long 范围内", id < 0L && id != Long.MIN_VALUE)
        }
    }
}
