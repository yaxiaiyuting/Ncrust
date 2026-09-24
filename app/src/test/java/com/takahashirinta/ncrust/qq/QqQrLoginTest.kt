package com.takahashirinta.ncrust.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1.0 · C / v2.1.1：QQ 互联扫码登录的纯逻辑单测。
 *
 * 这一层的错误**全都表现为「扫了码但界面纹丝不动」**：token 算错 ⇒ 服务端判 token 错、
 * 状态永远是等待；`ptuiCB` 解析错位 ⇒ 把「已扫码」读成「已过期」；状态码映射错 ⇒
 * 停在等待或提前终止轮询。三类都不会抛异常，所以必须靠用例钉住。
 *
 * ## v2.1.1：上一版的用例本身是错的（记在这里，别再犯）
 *
 * v2.1.0 的三个用例**把实现的假设当成了协议**：
 * - `ptqrToken 空串退化成初值 5381——官方算法的已知性质` —— 断言了错的初值；
 * - `解析已扫码待确认` 用 `'65'`、`解析过期` 用 `'67'` —— 65/67 用反了。
 *
 * 它们全绿，而功能全坏。教训：**码表与算法要拿服务端/vendor 的证据来钉，不能拿
 * 自己实现里抄来的常量来钉**。v2.1.1 的证据是两份：
 * ① 腾讯生产 JS `ptlogin/js/c_login_2.js`（`hash33` 初值与 `ptuiCB` 的 case 分支）；
 * ② 真机对服务端的实测向量（token 值就是服务端接受的那个）。
 *
 * ## 仍然没有实测的部分（如实标注）
 *
 * 「扫码确认成功那一跳」（`ptuiCB('0',...)` → WebView 换票）需要一个真实 QQ 账号去扫，
 * 开发环境没有，所以它的**端到端**仍未验证 —— 但码表与解析规则已由 vendor JS 钉死。
 */
class QqQrLoginTest {

    // ---------- ptqrtoken ----------

    /**
     * 真实向量：2026-09 用 `tools/probe-qq-qr-token.py` 现取 `qrsig`、算出 token、
     * 再用它轮询 `ptqrlogin`，服务端回 `HTTP 200 + ptuiCB('66',...)` 的那三个值。
     *
     * 这是**全仓库唯一钉住「服务端确实接受这个 token」的地方** —— v2.1.0 的注释里写着
     * 「没有真实向量可钉」，现在有了。
     */
    @Test
    fun `ptqrToken 与实测被服务端接受的向量一致`() {
        val vectors = listOf(
            "bdc07c3bfb1645e839bf16914db5eac413da5a55185517285ec141ca2da2b19a0" +
                "4338f20a370cacab8f9da5c58512ab15d135c22f36b9aaf2fa1f9880904703c" to 1295411603L,
            "f47ccb66c856a2b52a6fd20209ad0268023a365066b0d088df34652f146e40b8" +
                "8ff6595e7a6359d56b7ea91856435be3db7a9e56465666affe6f03edbb8742b7" to 1574057480L,
            "17d972ab716e2ec54a7b83f0477beb11e4ae9d4494d812cb1314725f7717b6c7" +
                "41330ed9e4bba3bff11dda461fccf71a8bb83c53e7d05eb1816251652b1d65e1" to 732277153L,
        )
        for ((qrsig, expected) in vectors) {
            assertEquals("qrsig=$qrsig", expected, QqQrLogin.ptqrToken(qrsig))
        }
    }

    /**
     * 初值必须是 **0**，不是 g_tk 那个 5381。
     *
     * 这不是「口味」问题：带 5381 的 token 会被 WAF 判成畸形请求，`ptqrlogin` 直接 403
     * （实测 3/3），而 0 拿到 200。这个用例就是那条分界线。
     */
    @Test
    fun `ptqrToken 用官方 hash33 的初值 0 而不是 g_tk 的 5381`() {
        assertEquals(0L, QqQrLogin.ptqrToken(""))
        val sig = "3bde9427ac7109bf2c"
        assertNotEquals(
            "5381 是 g_tk 的初值（hash33(p_skey, 5381)），用到 ptqrtoken 上会让每次轮询都 403",
            5381L,
            QqQrLogin.ptqrToken(sig),
        )
    }

    @Test
    fun `ptqrToken 恒为非负——负数会被服务端判 token 错`() {
        for (sig in listOf("", "a", "0".repeat(200), "\u00ff\u00ff", "3bde9427ac7109bf2c")) {
            val t = QqQrLogin.ptqrToken(sig)
            assertTrue("sig=$sig 得到 $t，必须落在 [0, 2^31)", t in 0..0x7FFFFFFFL)
        }
    }

    @Test
    fun `ptqrToken 是确定性的——同一 qrsig 每次必须算出同一个 token`() {
        val sig = "59c60ab30a896b45b44d55923630d19d321e484f"
        assertEquals(QqQrLogin.ptqrToken(sig), QqQrLogin.ptqrToken(sig))
    }

    @Test
    fun `ptqrToken 对相邻 qrsig 给出不同结果`() {
        assertTrue(QqQrLogin.ptqrToken("aaa") != QqQrLogin.ptqrToken("aab"))
    }

    // ---------- 状态码映射（以腾讯生产 JS 的 case 分支为准） ----------

    @Test
    fun `66 是未扫描`() {
        assertEquals(QqQrLogin.QrStatus.WAITING, QqQrLogin.statusOf(66))
    }

    /** `case "67": ... go_qrlogin_step(2)` —— 手机端扫码成功，等待确认。 */
    @Test
    fun `67 是已扫描待确认——不是已过期`() {
        assertEquals(QqQrLogin.QrStatus.SCANNED, QqQrLogin.statusOf(67))
    }

    /** `case "65": ... set_qrlogin_invalid()` —— 二维码失效。 */
    @Test
    fun `65 是已过期——不是已扫描`() {
        assertEquals(QqQrLogin.QrStatus.EXPIRED, QqQrLogin.statusOf(65))
    }

    @Test
    fun `0 是确认成功`() {
        assertEquals(QqQrLogin.QrStatus.CONFIRMED, QqQrLogin.statusOf(0))
    }

    @Test
    fun `68 与 22005 是用户拒绝`() {
        assertEquals(QqQrLogin.QrStatus.FAILED, QqQrLogin.statusOf(68))
        assertEquals(QqQrLogin.QrStatus.FAILED, QqQrLogin.statusOf(22005))
    }

    @Test
    fun `未知码归为失败而不是当成等待`() {
        // 「当成等待」会让界面永远转下去；归为失败才能让用户看到「刷新」。
        assertEquals(QqQrLogin.QrStatus.FAILED, QqQrLogin.statusOf(7))
    }

    // ---------- ptuiCB 解析 ----------

    @Test
    fun `解析等待扫码——这是实测拿到的真实响应`() {
        val cb = QqQrLogin.parsePtuiCb("ptuiCB('66','0','','0','二维码未失效。', '')")!!
        assertEquals(66, cb.code)
        assertEquals(QqQrLogin.QrStatus.WAITING, cb.status)
        assertNull(cb.url)
        assertEquals("二维码未失效。", cb.message)
    }

    @Test
    fun `解析已扫码待确认`() {
        val cb = QqQrLogin.parsePtuiCb("ptuiCB('67','0','','0','二维码认证中。', '')")!!
        assertEquals(QqQrLogin.QrStatus.SCANNED, cb.status)
    }

    @Test
    fun `解析确认成功并取出跳转地址`() {
        val text = "ptuiCB('0','0','https://ptlogin2.qq.com/check_sig?uin=123&ptsigx=abc','0','登录成功！', '昵称');"
        val cb = QqQrLogin.parsePtuiCb(text)!!
        assertEquals(QqQrLogin.QrStatus.CONFIRMED, cb.status)
        assertEquals("https://ptlogin2.qq.com/check_sig?uin=123&ptsigx=abc", cb.url)
    }

    @Test
    fun `解析过期`() {
        val cb = QqQrLogin.parsePtuiCb("ptuiCB('65','0','','0','二维码已失效。', '');")!!
        assertEquals(QqQrLogin.QrStatus.EXPIRED, cb.status)
    }

    @Test
    fun `字段里的逗号与中文不会让解析错位`() {
        // 真实响应里 message 就是中文，且**可能出现逗号**；按逗号切分必然错位。
        val text = "ptuiCB('67','0','','0','二维码已扫描，请在手机上确认登录，谢谢。', 'x');"
        val cb = QqQrLogin.parsePtuiCb(text)!!
        assertEquals(67, cb.code)
        assertEquals("二维码已扫描，请在手机上确认登录，谢谢。", cb.message)
    }

    @Test
    fun `坏输入返回 null 而不是抛异常`() {
        assertNull(QqQrLogin.parsePtuiCb(null))
        assertNull(QqQrLogin.parsePtuiCb(""))
        assertNull(QqQrLogin.parsePtuiCb("这不是 ptuiCB"))
        assertNull(QqQrLogin.parsePtuiCb("ptuiCB()"))
        assertNull(QqQrLogin.parsePtuiCb("ptuiCB('abc','0','','0','x','y');"))
        // 字段不足时也返回 null（半截响应是网络抖动下的常态，按「这次没结果」处理）
        assertNull(QqQrLogin.parsePtuiCb("ptuiCB('66','0','','0'"))
    }

    // ---------- 轮询结果分类（v2.1.1：风控 ≠ 网络异常） ----------

    @Test
    fun `200 且能解析出 ptuiCB 归为 Status`() {
        val r = QqQrLogin.classifyPollResponse(200, "ptuiCB('66','0','','0','二维码未失效。', '')")
        assertTrue("$r", r is QqQrLogin.PollResult.Status)
        assertEquals(QqQrLogin.QrStatus.WAITING, (r as QqQrLogin.PollResult.Status).cb.status)
    }

    /**
     * 403 + 空 body 是**服务端明确回绝**（风控/拒绝），必须与网络异常分开 ——
     * v2.1.0 把两者都当「网络不稳定」，用户看到的提示因此是错的，而且会永远重试下去。
     */
    @Test
    fun `403 归为 Unavailable 而不是网络异常`() {
        val r = QqQrLogin.classifyPollResponse(403, "")
        assertEquals(QqQrLogin.PollResult.Unavailable(403), r)
    }

    @Test
    fun `其它非 2xx 也归为 Unavailable`() {
        for (code in listOf(400, 401, 404, 405, 412, 429, 500, 502, 503)) {
            assertTrue("http=$code", QqQrLogin.classifyPollResponse(code, "") is QqQrLogin.PollResult.Unavailable)
        }
    }

    @Test
    fun `200 但回的是一段解析不出的内容也归为 Unavailable`() {
        // WAF 拦下来时给的可能是 200 + 说明页；把它当「这次没结果、下轮继续」会白等到超时。
        assertTrue(
            QqQrLogin.classifyPollResponse(200, "<html>blocked</html>") is QqQrLogin.PollResult.Unavailable
        )
        assertTrue(QqQrLogin.classifyPollResponse(200, null) is QqQrLogin.PollResult.Unavailable)
        assertTrue(QqQrLogin.classifyPollResponse(200, "") is QqQrLogin.PollResult.Unavailable)
    }

    // ---------- URL 构造 ----------

    @Test
    fun `取二维码 URL 带上实测确定的参数`() {
        val url = QqQrLogin.qrShowUrl(0.5, 6)
        assertTrue(url.startsWith("https://ssl.ptlogin2.qq.com/ptqrshow?"))
        assertTrue(url.contains("appid=${QqQrLogin.APP_ID}"))
        assertTrue(url.contains("daid=${QqQrLogin.DAID}"))
        assertTrue(url.contains("pt_3rd_aid=${QqQrLogin.PT_3RD_AID}"))
        assertTrue("必须带 s 参数（实测 s=6 才给 222×222）", url.contains("s=6"))
        assertTrue("随机数防缓存", url.contains("t=0.5"))
        // u1 必须被编码（不编码会把参数切断）
        assertTrue(url.contains("u1=https%3A%2F%2Fgraph.qq.com%2Foauth2.0%2Flogin_jump"))
    }

    @Test
    fun `轮询 URL 带上 token 与时间戳`() {
        val url = QqQrLogin.qrLoginUrl(123456789L, 1790000000000L)
        assertTrue(url.startsWith("https://ssl.ptlogin2.qq.com/ptqrlogin?"))
        assertTrue(url.contains("ptqrtoken=123456789"))
        assertTrue(url.contains("action=0-0-1790000000000"))
        assertTrue(url.contains("ptredirect=0"))
        assertTrue(url.contains("aid=${QqQrLogin.APP_ID}"))
    }

    @Test
    fun `二维码有效期取值在服务端给的 900 秒之内`() {
        assertTrue(QqQrLogin.QR_TTL_SECONDS in 60..900)
    }
}
