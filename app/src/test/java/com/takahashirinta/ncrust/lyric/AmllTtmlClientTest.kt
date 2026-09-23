/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.9.0：AMLL TTML 拉取客户端的单测。
 *
 * 全部通过假 [TtmlFetcher]（[FakeFetcher]）驱动，**一个真实网络请求都不发**。
 * 覆盖的每条行为都来自 2026-09 curl 实测的镜像语义（见 [AmllTtmlClient] 的 KDoc），不是文档推断。
 */
class AmllTtmlClientTest {

    /** 记录调用顺序的假取数器：既能断言「试了哪几面镜像」，也能断言「有没有多试」。 */
    private class FakeFetcher(private val handler: (String) -> TtmlHttpResult) : TtmlFetcher {
        val calls = mutableListOf<String>()
        override suspend fun get(url: String): TtmlHttpResult {
            calls += url
            return handler(url)
        }
    }

    private val songId = 5257138L
    private val urls = AmllTtmlClient.urlsFor(songId)
    private val tt = "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body/></tt>"

    // ---------- URL 构造 ----------

    @Test
    fun `URL 构造——四个镜像模板都按 songId 拼对`() {
        assertEquals(4, urls.size)
        assertEquals("https://amlldb.bikonoo.com/ncm-lyrics/5257138.ttml", urls[0])
        assertEquals(
            "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/ncm-lyrics/5257138.ttml",
            urls[1]
        )
        assertEquals(
            "https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/ncm-lyrics/5257138.ttml",
            urls[2]
        )
        assertEquals("https://amlldb.bikonoo.com/lyrics/ncm-lyrics/5257138.ttml", urls[3])
        // 每面镜像都必须带上 id，且都是 .ttml
        assertTrue(urls.all { it.contains("5257138") && it.endsWith(".ttml") })
    }

    @Test
    fun `URL 构造——镜像名固定且主镜像排第一（回退顺序 = 表顺序）`() {
        assertEquals(listOf("amlldb", "github-raw", "jsdelivr", "amlldb-alt"), AmllTtmlClient.mirrors.map { it.name })
        assertTrue(AmllTtmlClient.mirrors.first().template.startsWith("https://amlldb.bikonoo.com/ncm-lyrics/"))
    }

    @Test
    fun `URL 构造——不同 songId 各自成 URL（不串号）`() {
        assertEquals(
            "https://amlldb.bikonoo.com/ncm-lyrics/287035.ttml",
            AmllTtmlClient.urlsFor(287035L)[0]
        )
    }

    // ---------- 命中 ----------

    @Test
    fun `命中——主镜像 200 且 body 以 tt 开头时返回原文，且只打一面镜像`() = runBlocking {
        val fake = FakeFetcher { TtmlHttpResult(200, tt) }
        assertEquals(tt, AmllTtmlClient.fetch(songId, fake))
        assertEquals(listOf(urls[0]), fake.calls)
    }

    @Test
    fun `命中——body 前面有换行或空白也算 TTML`() = runBlocking {
        val padded = "\n\n  " + tt
        val fake = FakeFetcher { TtmlHttpResult(200, padded) }
        assertEquals(padded, AmllTtmlClient.fetch(songId, fake))
        assertEquals(1, fake.calls.size)
    }

    // ---------- 「这首歌没有 TTML」的两种形态：都不回退 ----------

    @Test
    fun `404——判定为无此歌词且不再试下一个镜像（主镜像 302 跟随后就是 404）`() = runBlocking {
        // 备用镜像此刻是「有」的：如果实现仍然回退，就会返回 tt —— 用它证明回退真的被省掉了。
        val fake = FakeFetcher { url ->
            if (url == urls[0]) TtmlHttpResult(404, "歌词不存在") else TtmlHttpResult(200, tt)
        }
        assertNull(AmllTtmlClient.fetch(songId, fake))
        assertEquals("404 后必须立刻收工，不能白试备用镜像", listOf(urls[0]), fake.calls)
    }

    @Test
    fun `200 但 body 不是 TTML——同样判无此歌词且不回退`() = runBlocking {
        // 实测风险：CDN/代理层用 200 返回 HTML 错误页。判据是「去掉前导空白后以 <tt 开头」。
        val fake = FakeFetcher { url ->
            if (url == urls[0]) TtmlHttpResult(200, "<!DOCTYPE html><html>Not Found</html>")
            else TtmlHttpResult(200, tt)
        }
        assertNull(AmllTtmlClient.fetch(songId, fake))
        assertEquals(listOf(urls[0]), fake.calls)
    }

    @Test
    fun `200 但 body 为空——判无此歌词`() = runBlocking {
        val fake = FakeFetcher { TtmlHttpResult(200, "") }
        assertNull(AmllTtmlClient.fetch(songId, fake))
        assertEquals(1, fake.calls.size)
    }

    @Test
    fun `网络异常后遇到 404——同样立即收工`() = runBlocking {
        val fake = FakeFetcher { url ->
            when (url) {
                urls[0] -> TtmlHttpResult(AmllTtmlClient.NETWORK_ERROR, null)
                urls[1] -> TtmlHttpResult(404, "歌词不存在")
                else -> TtmlHttpResult(200, tt)
            }
        }
        assertNull(AmllTtmlClient.fetch(songId, fake))
        assertEquals(listOf(urls[0], urls[1]), fake.calls)
    }

    // ---------- 回退 ----------

    @Test
    fun `网络异常——回退到下一面镜像并返回命中结果`() = runBlocking {
        val fake = FakeFetcher { url ->
            if (url == urls[0]) TtmlHttpResult(AmllTtmlClient.NETWORK_ERROR, null) else TtmlHttpResult(200, tt)
        }
        assertEquals(tt, AmllTtmlClient.fetch(songId, fake))
        assertEquals("第一面失败后应立即试第二面", listOf(urls[0], urls[1]), fake.calls)
    }

    @Test
    fun `非 200（500）——回退到备用镜像`() = runBlocking {
        val fake = FakeFetcher { url ->
            if (url == urls[0]) TtmlHttpResult(500, "Internal Server Error") else TtmlHttpResult(200, tt)
        }
        assertEquals(tt, AmllTtmlClient.fetch(songId, fake))
        assertEquals(listOf(urls[0], urls[1]), fake.calls)
    }

    @Test
    fun `取数实现抛异常——按网络异常处理并回退，不把异常抛给调用方`() = runBlocking {
        // 生产实现自己吞异常，但接口是公开的：任何 TtmlFetcher 实现抛出都不该炸到播放路径。
        val fake = FakeFetcher { url ->
            if (url == urls[0]) throw java.io.IOException("boom") else TtmlHttpResult(200, tt)
        }
        assertEquals(tt, AmllTtmlClient.fetch(songId, fake))
        assertEquals(listOf(urls[0], urls[1]), fake.calls)
    }

    // ---------- 全部失败 ----------

    @Test
    fun `全部镜像网络异常——返回 null，每面只试一次（不重试）`() = runBlocking {
        val fake = FakeFetcher { TtmlHttpResult(AmllTtmlClient.NETWORK_ERROR, null) }
        assertNull(AmllTtmlClient.fetch(songId, fake))
        assertEquals("四面镜像各试一次，顺序固定，不重复", urls, fake.calls)
    }

    @Test
    fun `全部镜像 500——返回 null`() = runBlocking {
        val fake = FakeFetcher { TtmlHttpResult(503, "unavailable") }
        assertNull(AmllTtmlClient.fetch(songId, fake))
        assertEquals(urls, fake.calls)
    }

    @Test
    fun `网络异常码约定——TtmlHttpResult 负 code 表示没拿到响应`() {
        assertEquals(-1, AmllTtmlClient.NETWORK_ERROR)
        assertNull(TtmlHttpResult(AmllTtmlClient.NETWORK_ERROR, null).body)
    }
}
