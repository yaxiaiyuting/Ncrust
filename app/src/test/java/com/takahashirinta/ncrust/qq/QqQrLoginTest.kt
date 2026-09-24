package com.takahashirinta.ncrust.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1.0 · C：QQ 互联扫码登录的纯逻辑单测。
 *
 * 这一层的错误**全都表现为「扫了码但界面纹丝不动」**：token 算错 ⇒ 服务端判 token 错、
 * 状态永远是等待；`ptuiCB` 解析错位 ⇒ 把「已扫码」读成「已过期」；状态码映射错 ⇒
 * 停在等待或提前终止轮询。三类都不会抛异常，所以必须靠用例钉住。
 *
 * ## 验证边界（如实）
 *
 * `ptqrshow` 实测可用（HTTP 200 + 真实 PNG + `qrsig`），但 `ptqrlogin` 在**本机出口 IP
 * 上被 WAF 恒定 403**，所以「服务端是否接受我们算出的 token」这一步**没有实测过**。
 * 调研期抓到的那对 `qrsig`/`ptqrtoken` 经核验**不是同一次请求**（多轮探测混在一起），
 * 因此这里没有真实向量可钉 —— 只能钉算法自身的行为与解析的健壮性。
 * 这不是「测过了」，是「把能测的部分测了」。
 */
class QqQrLoginTest {

    // ---------- ptqrtoken ----------

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
    fun `ptqrToken 空串退化成初值 5381——官方算法的已知性质`() {
        assertEquals(5381L, QqQrLogin.ptqrToken(""))
    }

    @Test
    fun `ptqrToken 对相邻 qrsig 给出不同结果`() {
        assertTrue(QqQrLogin.ptqrToken("aaa") != QqQrLogin.ptqrToken("aab"))
    }

    // ---------- ptuiCB 解析 ----------

    @Test
    fun `解析等待扫码`() {
        val cb = QqQrLogin.parsePtuiCb("ptuiCB('66','0','','0','二维码未失效。', '');")!!
        assertEquals(66, cb.code)
        assertEquals(QqQrLogin.QrStatus.WAITING, cb.status)
        assertNull(cb.url)
        assertEquals("二维码未失效。", cb.message)
    }

    @Test
    fun `解析已扫码待确认`() {
        val cb = QqQrLogin.parsePtuiCb("ptuiCB('65','0','','0','二维码已扫描，请在手机上确认登录。', 'x');")!!
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
        val cb = QqQrLogin.parsePtuiCb("ptuiCB('67','0','','0','二维码已失效。', '');")!!
        assertEquals(QqQrLogin.QrStatus.EXPIRED, cb.status)
    }

    @Test
    fun `未知码归为失败而不是当成等待`() {
        // 「当成等待」会让界面永远转下去；归为失败才能让用户看到「刷新」。
        val cb = QqQrLogin.parsePtuiCb("ptuiCB('7','0','','0','未知状态。', '');")!!
        assertEquals(QqQrLogin.QrStatus.FAILED, cb.status)
    }

    @Test
    fun `字段里的逗号与中文不会让解析错位`() {
        // 真实响应里 message 就是中文，且**可能出现逗号**；按逗号切分必然错位。
        val text = "ptuiCB('65','0','','0','二维码已扫描，请在手机上确认登录，谢谢。', 'x');"
        val cb = QqQrLogin.parsePtuiCb(text)!!
        assertEquals(65, cb.code)
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
