package com.takahashirinta.ncrust.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1.0 · D：**跨实现**验证 QRC 解密（最强的证据层）。
 *
 * 夹具是调研阶段独立抓取的**真实《晴天》QRC**：
 * - `qq/qrc-sample.hex` —— 服务端返回的加密十六进制（9808 字符）；
 * - `qq/qrc-sample-expected.xml` —— 由**另一份独立实现**（Python 参考实现
 *   `luren-dc/QQMusicApi`，以及调研期另写的一份 Java 移植）解出的黄金基准（9821 字节）。
 *
 * 本用例断言 Kotlin 的实现**逐字节**解出同一份 XML。这比「自己加密自己解密能过」强得多：
 * 它同时钉住了密钥、模式（ECB/NoPadding）、字节序、PC-2 偏移 bug、以及 zlib 解压行为。
 * 一旦有人把 `QqDes` 的 `- 27` 改回标准的 `- 28`，这里会立刻红。
 */
class QrcDecryptorGoldenTest {

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("qq/$name")!!
            .readBytes().toString(Charsets.UTF_8)

    @Test
    fun `真实晴天 QRC 解出与独立实现逐字节一致的 XML`() {
        val hex = resource("qrc-sample.hex").trim()
        val expected = resource("qrc-sample-expected.xml")

        val actual = QrcDecryptor.decrypt(hex)
        assertNotNull("解密不能返回 null", actual)
        assertEquals("解密结果长度", expected.length, actual!!.length)
        assertEquals("解密结果必须与独立实现逐字节一致", expected, actual)
    }

    @Test
    fun `解出的 XML 能被解析成完整的逐字歌词`() {
        val hex = resource("qrc-sample.hex").trim()
        val lines = QrcParser.parseXml(QrcDecryptor.decrypt(hex))
        // 真实《晴天》共 69 行 LyricContent，其中若干是空行/元数据；这里只断言量级与关键行，
        // 避免把「服务端这次多给了几行」变成测试失败。
        assertTrue("应解析出 50 行以上，实际 ${lines.size}", lines.size > 50)
        assertEquals("晴天 - 周杰伦 (Jay Chou)", lines[0].text)
        assertEquals(0L, lines[0].timeMs)
        // 逐字数据必须真的在：每个词都能在行文本里定位
        val first = lines[0]
        assertTrue("首行应有逐字", first.words.size >= 10)
        for (w in first.words) {
            assertEquals(w.text, first.text.substring(w.charStart, w.charEndExclusive))
        }
    }

    @Test
    fun `密文长度是 8 的整数倍且以 zlib 头开头`() {
        val hex = resource("qrc-sample.hex").trim()
        assertEquals(0, (hex.length / 2) % 8)
        // 解出来的明文必须以 zlib 头 (0x78) 开头 —— 这是「用对了算法」最快的自检
        val plain = QqDes.decrypt(
            ByteArray(hex.length / 2) {
                ((Character.digit(hex[it * 2], 16) shl 4) or Character.digit(hex[it * 2 + 1], 16)).toByte()
            }
        )
        assertEquals(0x78, plain[0].toInt() and 0xFF)
    }
}
