package com.takahashirinta.ncrust.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1.0 · D：QRC 解密单测。
 *
 * **夹具不是手写的**：它是把一份真实的 QRC XML 用**独立的 Python 参考实现**
 * （`luren-dc/QQMusicApi` 的 `tripledes.py` + `zlib`）加密出来的十六进制串，
 * 所以这条用例真正验证的是「Kotlin 侧的解密与参考实现逐字节一致」，
 * 而不只是「自己加密自己解密能过」。参考实现解密同一段密文得到同样的明文
 * （2026-09 实测）。
 *
 * 期望明文（见 [EXPECTED]）：属性值里刻意塞了 `&amp;` `&lt;` `&gt;` `&quot;` 四种实体
 * —— 真实歌词里 `&` 相当常见（`A & B`、`作词 & 作曲`），实体没反转义会直接显示成 `&amp;`。
 */
class QrcDecryptorTest {

    /** 参考实现生成的密文（hex）。 */
    private val cipherHex =
        "7b92bf132d9d690acff93ce34261d38f39bc763f8e108c2c326d5836dca36876989f811bc2b5ac8596ff532d6ecaaa3d" +
            "12fc64236e1b9d5583100104e3698aabf231b4704a59152554f3e70bd013b1cb1c9f9c3e59f8f9723a0ab8446bbf7fcd" +
            "fdffb2cd82555cf6a73305056abb9181adf993125110d158df5eac18fc19e9a7376c3b0bc6afbee711eac7a7fa48542" +
            "f1ee59e915ee169140acdfb171750f57f23d53afb82dd4dff5adb376c29c706cd893d5a91c24a6e5d37963d3fbf05029" +
            "6342aba321f76de374f8e71a1461a3658c0669afe4875c1d5637110c4d45be93b6fbc5dae478452ef82fbfb1994c8b1" +
            "af4f6b69fd691e6d8e61ef0ee6a84f5b3ed8d5c7d7cba060378f54de1299541b579aeeb9f0ddf92dc49d86555ad27dd" +
            "7c9e8713f7b62002dc2e4756fe4f1c0aad6"

    private val expected =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<QrcInfos>\n" +
            "<QrcHeadInfo SaveTime=\"1\" Version=\"100\"/>\n" +
            "<LyricInfo LyricCount=\"1\">\n" +
            "<Lyric_1 LyricType=\"1\" LyricContent=\"[ti:测试&amp;曲]\n" +
            "[ar:A &lt;B&gt;]\n" +
            "[offset:0]\n" +
            "[0,2000]晴(0,200)天(200,200) (400,200)&quot;引号&quot;(600,400)\n" +
            "[2000,1500]词(2000,500)：(2500,500)作(3000,500)\n" +
            "[4000,0]\n" +
            // 注意：参考实现生成的原文里，`"/>` 之后**直接**跟 `</LyricInfo>`（没有换行）。
            // 这里多写一个 \n 就会让「解密正确」被误判成失败 —— 曾经如此。
            "[5000,1000]结(5000,1000)尾(6000,0)\"/>" +
            "</LyricInfo>\n" +
            "</QrcInfos>\n"

    @Test
    fun `解密结果与参考实现逐字节一致`() {
        assertEquals(expected, QrcDecryptor.decrypt(cipherHex))
    }

    @Test
    fun `解密是确定性的`() {
        assertEquals(QrcDecryptor.decrypt(cipherHex), QrcDecryptor.decrypt(cipherHex))
    }

    @Test
    fun `坏输入返回 null 而不是抛异常`() {
        assertNull(QrcDecryptor.decrypt(null))
        assertNull(QrcDecryptor.decrypt(""))
        assertNull(QrcDecryptor.decrypt("zz")) // 非十六进制
        assertNull(QrcDecryptor.decrypt("abc")) // 奇数长度
        assertNull(QrcDecryptor.decrypt("00112233445566")) // 不是 8 字节整数倍
        // 长度合法但不是密文/不是 zlib 流
        assertNull(QrcDecryptor.decrypt("00".repeat(64)))
    }

    @Test
    fun `大写十六进制也认`() {
        assertEquals(expected, QrcDecryptor.decrypt(cipherHex.uppercase()))
    }

    @Test
    fun `空明文（服务端返回空串）不会误判成解密失败`() {
        // 真实响应里 trans/roma 经常就是空串 —— 空进空出，不要抛。
        assertNull(QrcDecryptor.decrypt(""))
    }

    @Test
    fun `密文能继续被解析成歌词行`() {
        val lines = QrcParser.parseXml(QrcDecryptor.decrypt(cipherHex))
        assertNotNull(lines)
        // 夹具里有 4 行内容，但 `[4000,0]` 是**空行**（间奏处真实存在），解析器按「无文本行」跳过，
        // 所以是 3 行 —— 这是有意的：给渲染层一行空文本只会多一次无意义的布局。
        assertEquals(3, lines.size)
        assertEquals("晴天 \"引号\"", lines[0].text)
        assertEquals("词：作", lines[1].text)
        assertEquals(2000L, lines[1].timeMs)
        assertEquals(3500L, lines[1].endMs)
    }

    @Test
    fun `解密后 XML 里的实体被正确反转义`() {
        val xml = QrcDecryptor.decrypt(cipherHex)!!
        // 属性值里的实体在 extractContent 阶段就被解掉
        val content = QrcParser.extractContent(xml)!!
        assertTrue(content.contains("[ti:测试&曲]"))
        assertTrue(content.contains("[ar:A <B>]"))
    }
}
