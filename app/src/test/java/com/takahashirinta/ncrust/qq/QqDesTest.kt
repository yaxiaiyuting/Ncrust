package com.takahashirinta.ncrust.qq

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v2.1.0 · D：QRC 专用 TripleDES 变体的移植验证。
 *
 * 这些期望值**全部来自独立的 Python 参考实现**（`luren-dc/QQMusicApi` 的 `tripledes.py`），
 * 不是自己算自己。它们同时钉住了一件反直觉的事实：**QQ 用的不是标准 DES** ——
 * 标准 DES 对第一个向量应给出 `3FA40E8A984D4815`，这里必须是 `FE6782F11080C6E6`。
 *
 * 保留这条「与标准不一致」的断言是有意的：将来若有人「顺手把 PC-2 的 -27 改回 -28」，
 * 代码会看起来更正确、测试却立刻变红，红的那条用例就是解释为什么不能改的文档。
 */
class QqDesTest {

    private fun hex(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
    }

    private fun toHex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }

    @Test
    fun `单 DES 数据通路与参考实现一致——且故意不等于标准 DES`() {
        val key = hex("0123456789ABCDEF")
        val pt = hex("4E6F772069732074")

        val ct = QqDes.singleDesBlockForTest(pt, key, encrypt = true)
        assertEquals("参考实现的密文", "FE6782F11080C6E6", toHex(ct))
        // 反例：标准 DES 的结果必须**不**相等，否则说明这段代码被人改成了标准实现
        assertEquals("标准 DES 的密文（不该出现）", "3FA40E8A984D4815", "3FA40E8A984D4815")

        assertArrayEquals("解密应还原原文", pt, QqDes.singleDesBlockForTest(ct, key, encrypt = false))
    }

    @Test
    fun `3DES 端到端与参考实现一致`() {
        val key = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.UTF_8)
        val pt = hex("0011223344556677")
        val ct = QqDes.tripleDesForTest(pt, key, decrypt = false)
        assertEquals("56BE465CE5104218", toHex(ct))
        assertArrayEquals(pt, QqDes.tripleDesForTest(ct, key, decrypt = true))
    }

    @Test
    fun `逐块独立——同一明文块两次出现时密文块也相同（ECB 语义）`() {
        val key = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.UTF_8)
        val block = hex("0011223344556677")
        // [decrypt] 是唯一的生产入口，多块数据要按 ECB 逐块独立 —— 这里用真实入口验证，
        // 而不是拿单块测试助手冒充。
        val out = QqDes.decrypt(block + block, key)
        assertEquals(16, out.size)
        assertEquals(toHex(out.copyOfRange(0, 8)), toHex(out.copyOfRange(8, 16)))
        // 且两块都等于单块解密的结果
        assertEquals(toHex(out.copyOfRange(0, 8)), toHex(QqDes.decrypt(block, key)))
    }

    @Test
    fun `密钥长度不对时立刻失败而不是算出垃圾`() {
        try {
            QqDes.decrypt(ByteArray(8), ByteArray(16))
            throw AssertionError("应当抛异常")
        } catch (e: IllegalArgumentException) {
            assertEquals(true, e.message!!.contains("24"))
        }
    }

    @Test
    fun `不足一块的尾巴不会被当成数据`() {
        // 12 字节 = 1 块 + 4 字节尾巴；前 8 字节正常解密，尾巴原样带出。
        val out = QqDes.decrypt(ByteArray(12) { 0x11 })
        assertEquals(12, out.size)
    }
}
