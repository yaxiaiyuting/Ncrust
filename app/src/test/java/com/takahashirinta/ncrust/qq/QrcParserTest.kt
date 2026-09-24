package com.takahashirinta.ncrust.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1.0 · D：QRC 解析单测。
 *
 * 样本是**真实的《晴天》QRC**（2026-09 从 `music.musichallSong.PlayLyricInfo` 取回后解密），
 * 不是编造的 —— QRC 的文法有几处反直觉（词文本写在时间元组**之前**、词时间是绝对毫秒、
 * 空格本身也是一个「词」），只有真实样本才盖得住。
 */
class QrcParserTest {

    /** 真实样本：`[ti:…]` 等元数据行 + 逐字行混排。 */
    private val realQrc = """
        [ti:晴天]
        [ar:周杰伦]
        [al:叶惠美]
        [by:]
        [offset:0]
        [0,2250]晴(0,160)天(160,160) (320,160)-(480,160) (640,160)周(800,160)杰(960,160)伦(1120,160) (1280,160)((1440,160)Jay(1600,160) (1760,160)Chou(1920,160))(2080,160)
        [2250,2250]词(2250,450)：(2700,450)周(3150,450)杰(3600,450)伦(4050,450)
        [4000,0]
    """.trimIndent()

    @Test
    fun `元数据行被跳过，只留内容行`() {
        val lines = QrcParser.parseContent(realQrc)
        assertEquals(2, lines.size)
        assertEquals(0L, lines[0].timeMs)
        assertEquals(2250L, lines[1].timeMs)
    }

    @Test
    fun `行文本由各词拼接而成——QRC 自带文本，不需要对齐 LRC`() {
        val line = QrcParser.parseContent(realQrc)[0]
        // 每一段（含只含空格的段）都要拼进去，否则文本会与官方显示不一致
        assertEquals("晴天 - 周杰伦 (Jay Chou)", line.text)
    }

    @Test
    fun `词时间是绝对毫秒——不是相对行首`() {
        val line = QrcParser.parseContent(realQrc)[0]
        val words = line.words
        // 第一个词从 0 开始
        assertEquals(0L, words[0].startMs)
        assertEquals("晴", words[0].text)
        // 第二个词 160ms（同一行内），说明是绝对而非相对行首的 0/160
        assertEquals(160L, words[1].startMs)
        // 跨行核对：第二行的第一个词应当是 2250（= 该行行首），不是 0
        val second = QrcParser.parseContent(realQrc)[1]
        assertEquals(2250L, second.words[0].startMs)
        assertEquals("词", second.words[0].text)
    }

    @Test
    fun `词的字符区间与行文本严格对应`() {
        val line = QrcParser.parseContent(realQrc)[0]
        for (w in line.words) {
            assertTrue("区间必须在文本内: $w", w.charStart in 0..line.text.length)
            assertTrue("区间必须递增: $w", w.charEndExclusive in w.charStart..line.text.length)
            // 区间里的内容必须就是词的文本 —— 逐字高亮直接按这个区间取版面路径，
            // 错一位就是「唱到别字」，而且只在真机上看得出来。
            assertEquals(
                "字符区间与词文本不一致: $w",
                w.text,
                line.text.substring(w.charStart, w.charEndExclusive),
            )
        }
    }

    @Test
    fun `行尾时间来自行时长`() {
        val lines = QrcParser.parseContent(realQrc)
        assertEquals(0L + 2250L, lines[0].endMs)
        assertEquals(2250L + 2250L, lines[1].endMs)
    }

    @Test
    fun `空行与纯空白行被跳过——它们没有可显示文本`() {
        // [4000,0] 是真实的间奏空行；保留它只会让渲染层多一次无意义布局。
        val lines = QrcParser.parseContent("[4000,0]\n[5000,1000]只(5000,1000)有(6000,0)")
        assertEquals(1, lines.size)
        assertEquals("只有", lines[0].text)
        assertEquals(5000L, lines[0].timeMs)
    }

    @Test
    fun `首尾空白被裁掉且词区间同步平移`() {
        val line = QrcParser.parseContent("[0,1000] (0,100)你(100,900) (900,100)")[0]
        assertEquals("你", line.text)
        for (w in line.words) {
            assertEquals(w.text, line.text.substring(w.charStart, w.charEndExclusive))
        }
        // 落在被裁掉区域里的词不该留下越界区间
        assertTrue(line.words.all { it.charEndExclusive <= line.text.length })
    }

    @Test
    fun `坏输入返回空列表而不是抛异常`() {
        assertTrue(QrcParser.parseContent(null).isEmpty())
        assertTrue(QrcParser.parseContent("").isEmpty())
        assertTrue(QrcParser.parseContent("这不是歌词").isEmpty())
        assertTrue(QrcParser.parseContent("[ti:只有元数据]").isEmpty())
        assertTrue(QrcParser.parseContent("[0,1000]").isEmpty())
        // 时间元组缺一个数字：整行丢弃，不要让畸形数据进渲染层
        assertTrue(QrcParser.parseContent("[0]没(0,1)有(1,1)").isEmpty())
        // 负数/非法数字不崩
        assertTrue(QrcParser.parseContent("[-1,1000]负(0,1)数(1,1)").isEmpty())
    }

    @Test
    fun `没有时间元组的尾文本仍然进文本但不高亮`() {
        // 真实样本里没出现过，但让它整段丢失比让它不高亮糟得多。
        val line = QrcParser.parseContent("[0,1000]唱(0,900)完剩下的字")[0]
        assertEquals("唱完剩下的字", line.text)
        assertTrue(line.words.isNotEmpty())
        assertEquals("唱", line.words[0].text)
    }

    @Test
    fun `解析不排序——保持服务端给的顺序`() {
        // 重排会掩盖服务端偶发乱序；渲染层按列表顺序扫，解析器不擅自修数据。
        val lines = QrcParser.parseContent("[2000,100]后(2000,100)\n[1000,100]前(1000,100)")
        assertEquals(listOf(2000L, 1000L), lines.map { it.timeMs })
    }

    // ---------- XML 属性抽取与实体反转义 ----------

    @Test
    fun `从 XML 属性里取出 LyricContent`() {
        val xml = "<?xml version=\"1.0\"?><QrcInfos><LyricInfo><Lyric_1 LyricType=\"1\" " +
            "LyricContent=\"[0,1000]你(0,1000)\"/></LyricInfo></QrcInfos>"
        assertEquals("[0,1000]你(0,1000)", QrcParser.extractContent(xml))
        val lines = QrcParser.parseXml(xml)
        assertEquals(1, lines.size)
        assertEquals("你", lines[0].text)
    }

    @Test
    fun `实体反转义——& 必须最后替换，避免双重解码`() {
        assertEquals("A & B", QrcParser.unescapeXml("A &amp; B"))
        assertEquals("<b>", QrcParser.unescapeXml("&lt;b&gt;"))
        assertEquals("\"引号\"", QrcParser.unescapeXml("&quot;引号&quot;"))
        assertEquals("'单引号'", QrcParser.unescapeXml("&apos;单引号&apos;"))
        // 双重解码反例：原文里的字面量 `&lt;` 必须保持原样
        assertEquals("&lt;", QrcParser.unescapeXml("&amp;lt;"))
        // 数字实体（含代理对）
        assertEquals("A", QrcParser.unescapeXml("&#65;"))
        assertEquals("中", QrcParser.unescapeXml("&#x4E2D;"))
        assertEquals("😀", QrcParser.unescapeXml("&#x1F600;"))
    }

    @Test
    fun `实体反转义对坏输入保持原样`() {
        assertEquals("&", QrcParser.unescapeXml("&"))
        assertEquals("&amp", QrcParser.unescapeXml("&amp"))
        assertEquals("&unknown;", QrcParser.unescapeXml("&unknown;"))
        assertEquals("&#99999999999;", QrcParser.unescapeXml("&#99999999999;"))
        assertNull(QrcParser.extractContent(null))
        assertNull(QrcParser.extractContent("没有这个属性"))
        assertNull(QrcParser.extractContent("LyricContent=\"没有收尾引号"))
    }
}
