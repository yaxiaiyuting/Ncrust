package com.takahashirinta.ncrust.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌单写操作的回归测试。
 *
 * 全部断言来自 2026-09 登录态真机实测（仓库外 tools/probe-quality.py 同款 eapi 通道）：
 * 写端点 HTTP 状态码恒为 200，成败只看 body.code —— 所以「业务码 → 结果」的映射
 * 必须单测覆盖，否则 405/502/403 会被当成成功。
 */
class PlaylistEditApiTest {

    // ==================== 业务码映射 ====================

    @Test
    fun `code 200 is success`() {
        val r = PlaylistEditApi.parseWriteResponse(200, """{"code":200,"count":3}""")
        assertTrue(r is PlaylistWriteResult.Success)
        assertEquals(3, (r as PlaylistWriteResult.Success).raw.optInt("count"))
    }

    /** 502「歌单(内)歌曲重复」= 已经在歌单里，必须当幂等成功，不能当失败。 */
    @Test
    fun `code 502 duplicate is not a failure`() {
        val r = PlaylistEditApi.parseWriteResponse(200, """{"message":"歌单内歌曲重复","code":502}""")
        assertTrue(r is PlaylistWriteResult.Duplicate)
    }

    /** 405 限流：必须能被上层识别出来做「操作过于频繁」文案，而不是笼统失败。 */
    @Test
    fun `code 405 is rate limited`() {
        val r = PlaylistEditApi.parseWriteResponse(200, """{"code":405,"message":"操作过于频繁，请稍后再试"}""")
        assertTrue(r is PlaylistWriteResult.RateLimited)
    }

    /** 403 illegal request!：身份 cookie 与 csrf 都没带全，属于客户端实现错误。 */
    @Test
    fun `code 403 is illegal request`() {
        val r = PlaylistEditApi.parseWriteResponse(200, """{"code":403,"message":"illegal request!"}""")
        assertTrue(r is PlaylistWriteResult.IllegalRequest)
    }

    /** 301 未登录写操作（服务端文案是「系统错误」，别被它误导）。 */
    @Test
    fun `code 301 unauthenticated is a failure`() {
        val r = PlaylistEditApi.parseWriteResponse(200, """{"code":301,"message":"系统错误"}""")
        assertTrue(r is PlaylistWriteResult.Failure)
        assertEquals(301, (r as PlaylistWriteResult.Failure).code)
    }

    @Test
    fun `code 401 delete by non-owner is a failure`() {
        val r = PlaylistEditApi.parseWriteResponse(200, """{"code":401,"message":"无权限操作歌单"}""")
        assertTrue(r is PlaylistWriteResult.Failure)
        assertEquals(401, (r as PlaylistWriteResult.Failure).code)
    }

    /** msg 与 message 两种键名服务端都用过，都要能取到文案。 */
    @Test
    fun `failure message falls back to msg key`() {
        val r = PlaylistEditApi.parseWriteResponse(200, """{"code":400,"msg":"参数错误"}""")
        assertEquals("参数错误", (r as PlaylistWriteResult.Failure).message)
    }

    /**
     * 空 body 是 eapi 加密出错的典型症状（HTTP 200 且没有任何报错信息），
     * 必须落成失败，绝不能当成功。
     */
    @Test
    fun `empty body is a failure not success`() {
        listOf(null, "", "   ").forEach { body ->
            val r = PlaylistEditApi.parseWriteResponse(200, body)
            assertTrue("body=" + body, r is PlaylistWriteResult.Failure)
        }
    }

    @Test
    fun `non json body is a failure`() {
        val r = PlaylistEditApi.parseWriteResponse(200, "<html>502 Bad Gateway</html>")
        assertTrue(r is PlaylistWriteResult.Failure)
    }

    // ==================== 请求体构造 ====================

    /**
     * op 白名单是最高危的一条：实测**任何非 add 的值（含空串、未知值）都会被服务端
     * 当成删除执行**。所以加歌请求体里的 op 必须是字面量 add。
     */
    @Test
    fun `add payload pins op to add`() {
        val p = PlaylistEditApi.addSongsPayload(123L, listOf(1L, 2L, 3L))
        assertEquals("add", p["op"])
        assertEquals("123", p["pid"])
        assertEquals("[1,2,3]", p["trackIds"])
        assertEquals("true", p["imme"])
    }

    @Test
    fun `remove payload pins op to del`() {
        val p = PlaylistEditApi.removeSongsPayload(456L, listOf(9L))
        assertEquals("del", p["op"])
        assertEquals("456", p["pid"])
        assertEquals("[9]", p["trackIds"])
    }

    /** 隐私只有 0（公开）/ 10（私密）合法，服务端对 5 之类返回 400 错误的歌单隐私类型。 */
    @Test
    fun `privacy payload uses id and privacy only`() {
        val p = PlaylistEditApi.privacyPayload(7L, PlaylistEditApi.PRIVACY_PRIVATE)
        assertEquals("7", p["id"])
        assertEquals("10", p["privacy"])
        assertEquals(2, p.size)
        assertFalse(p.containsKey("pid"))
    }

    // ==================== 归属判定 ====================

    /**
     * 收藏的歌单里 userId / creator.userId 都是**原作者**，所以判自建必须
     * creator.userId == 我 且 未订阅；否则会把别人的歌单显示成可编辑。
     */
    @Test
    fun `owned check requires creator match and not subscribed`() {
        val mine = PlaylistApi.PlaylistInfo(
            id = 1L, name = "mine", coverImgUrl = "", trackCount = 3,
            creatorUserId = 42L, specialType = 0, privacy = 0, subscribed = false,
        )
        val subscribed = mine.copy(id = 2L, subscribed = true)
        val others = mine.copy(id = 3L, creatorUserId = 99L)

        assertTrue(mine.isOwnedBy(42L))
        assertFalse(subscribed.isOwnedBy(42L))
        assertFalse(others.isOwnedBy(42L))
    }
}
