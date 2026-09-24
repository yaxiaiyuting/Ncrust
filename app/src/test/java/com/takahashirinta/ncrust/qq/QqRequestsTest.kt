package com.takahashirinta.ncrust.qq

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1.0 · B：QQ 请求体构造的纯逻辑单测。
 *
 * 这一层的错误**不会抛异常、也不会报错**，只会让服务端「什么都不给」：
 * - 三个并行数组不等长 ⇒ 空 `purl`，看起来像无权限；
 * - 搜索信封带了 `comm` ⇒ 返回 0 条，看起来像没有这首歌；
 * - 文件名用了 `song.mid` 而不是 `file.media_mid` ⇒ **服务端照样返回 purl**，
 *   直到 CDN 下载才 404。
 *
 * 所以这几条形状必须被单测钉住 —— 它们是「用起来不对但查不出来」的那一类 bug。
 */
class QqRequestsTest {

    private val types = QqQuality.attemptsFor("lossless")

    @Test
    fun `取链的三个并行数组必须等长且一一对应`() {
        val req = QqRequests.vkey("SONGMID", "MEDIAMID", types, uin = "123", guid = "g1")
        val param = req.getJSONObject("param")
        val filenames = param.getJSONArray("filename")
        val songmids = param.getJSONArray("songmid")
        val songtypes = param.getJSONArray("songtype")

        assertEquals(types.size, filenames.length())
        assertEquals("三个数组必须等长", filenames.length(), songmids.length())
        assertEquals(filenames.length(), songtypes.length())
        for (i in types.indices) {
            // 第 i 个文件名必须由第 i 个档位 + mediaMid 拼出
            assertEquals(QqQuality.fileNameFor(types[i], "MEDIAMID"), filenames.getString(i))
            assertEquals("SONGMID", songmids.getString(i))
        }
        // 文件名里不能出现 songmid（除非它恰好等于 mediaMid）—— 这是最容易踩的那个坑
        assertFalse(filenames.getString(0).contains("SONGMID"))
    }

    @Test
    fun `取链请求的模块与方法名与实测一致`() {
        val req = QqRequests.vkey("m", "m", types, "0", "g")
        assertEquals("music.vkey.GetVkey", req.getString("module"))
        assertEquals("UrlGetVkey", req.getString("method"))
        // ctx=0 必须存在（缺了服务端行为未验证）
        assertEquals(0, req.getJSONObject("param").getInt("ctx"))
    }

    @Test
    fun `搜索信封的 key 是 module 名且不含 comm`() {
        val env = QqRequests.searchEnvelope("Lemon", 30, 1, "999")
        assertTrue("信封 key 必须是 module 名", env.has(QqRequests.SEARCH_MODULE))
        assertFalse("不得带 req 键", env.has("req"))
        assertFalse("**绝不能带 comm** —— 实测带了会被判通道不匹配、返回 0 条", env.has("comm"))
        val inner = env.getJSONObject(QqRequests.SEARCH_MODULE)
        assertEquals(QqRequests.SEARCH_METHOD, inner.getString("method"))
        assertEquals("Lemon", inner.getJSONObject("param").getString("query"))
        assertEquals(30, inner.getJSONObject("param").getInt("num_per_page"))
    }

    @Test
    fun `搜索分页与条数被夹到合法区间`() {
        val env = QqRequests.searchEnvelope("x", 999, 0, "1")
        val param = env.getJSONObject(QqRequests.SEARCH_MODULE).getJSONObject("param")
        assertEquals(60, param.getInt("num_per_page"))
        assertEquals(1, param.getInt("page_num"))
    }

    @Test
    fun `旧版搜索 URL 带 new_json 与编码后的关键词`() {
        val url = QqRequests.legacySearchUrl("晴天 & 周杰伦", 10, 2)
        assertTrue(url.startsWith("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?"))
        assertTrue("必须带 new_json=1（字段名才与新版一致）", url.contains("new_json=1"))
        assertTrue(url.contains("cr=1"))
        assertTrue(url.contains("p=2"))
        assertTrue(url.contains("n=10"))
        // 关键词必须被 URL 编码（中文与 & 都不能裸露）
        assertFalse("裸 & 会把参数切断", url.substringAfter("w=").substringBefore("&format").contains("&"))
        assertTrue(url.contains("%E6%99%B4%E5%A4%A9"))
    }

    @Test
    fun `歌词请求带 mid 与数字 songId，且 crypt_qrc_trans_roma 全开`() {
        val req = QqRequests.lyric("0039MnYb0qxYhV", 97773L)
        assertEquals("music.musichallSong.PlayLyricInfo", req.getString("module"))
        assertEquals("GetPlayLyricInfo", req.getString("method"))
        val p = req.getJSONObject("param")
        assertEquals("0039MnYb0qxYhV", p.getString("songMid"))
        assertEquals(97773L, p.getLong("songId"))
        assertEquals(1, p.getInt("crypt"))
        assertEquals(1, p.getInt("qrc"))
        assertEquals(1, p.getInt("trans"))
        assertEquals(1, p.getInt("roma"))
    }

    @Test
    fun `VIP 请求用实测匿名可调的模块`() {
        val req = QqRequests.vip()
        assertEquals("VipLogin.VipLoginInter", req.getString("module"))
        assertEquals("vip_login_base", req.getString("method"))
    }

    @Test
    fun `取链请求可被序列化成合法 JSON（信封拼装不会崩）`() {
        val req = QqRequests.vkey("m", "mm", types, "0", "g")
        val envelope = JSONObject().put("comm", JSONObject()).put("req", req)
        val text = envelope.toString()
        assertTrue(text.isNotEmpty())
        assertEquals(types.size, JSONObject(text).getJSONObject("req").getJSONObject("param").getJSONArray("filename").length())
    }
}
