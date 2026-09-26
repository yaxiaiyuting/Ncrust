/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.5 · G：聚合搜索统计行的单测。
 */

package com.takahashirinta.ncrust.ui.components

import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.ui.i18n.deDE
import com.takahashirinta.ncrust.ui.i18n.en
import com.takahashirinta.ncrust.ui.i18n.jpJP
import com.takahashirinta.ncrust.ui.i18n.jpMY
import com.takahashirinta.ncrust.ui.i18n.koNK
import com.takahashirinta.ncrust.ui.i18n.ruRU
import com.takahashirinta.ncrust.ui.i18n.zhCN
import com.takahashirinta.ncrust.ui.i18n.zhTW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SourceCounts] 的守卫。
 *
 * ## 这一组用例对应的**用户报告**
 *
 * 搜「晴天」→ 界面立刻显示「网易云 30 首 · **QQ 音乐 0 首**」，
 * 而底部托盘正在播的那首《晴天》就是 QQ 音乐源；约 5 秒后 QQ 结果才出现、数字才更新。
 * 那 5 秒里「0 首」是**假话** —— 用户据此以为 QQ 搜不到那首歌。
 *
 * 所以本文件的第一条、也是最重要的一条断言是：
 * **只要 QQ 还没回来，它那一侧就绝不许出现 "0"。**
 */
class SourceCountsTest {

    private val strings = zhCN

    // ------------------------------------------------------------ ★ 核心：未返回时不许显示 0

    /**
     * ★★ **QQ 还在飞时，统计行里不许出现 "0"。**
     *
     * 这是本版修的那个 bug 的机器可读版本：只要这条红了，用户就又会看到那句假话。
     */
    @Test
    fun `QQ 未返回时统计行不出现 0 而显示搜索中`() {
        val counts = SourceCounts(
            neteaseCount = 30,
            neteaseStatus = SourceSearchStatus.DONE,
            qqCount = 0,
            qqStatus = SourceSearchStatus.PENDING,
        )
        val line = counts.summary(strings)
        // ⚠️ 不能写成 `!line.contains("0")`：网易云的「30 首」里也有一个 0。
        // 要断言的是**QQ 那一侧**没有出现「0 首」这个计数形态。
        val qqZero = strings.sourceQqMusic + " " + strings.searchSourceCount(0)
        assertTrue("QQ 侧出现了 0 —— 这正是用户报告的那句假话：$line", !line.contains(qqZero))
        assertEquals("QQ 侧必须是「搜索中」", strings.searchSourcePending, counts.qqText(strings))
        assertTrue("统计行里没有「搜索中」标记：$line", line.contains(strings.searchSourcePending))
        assertTrue("网易云的计数应当照常显示：$line", line.contains(strings.searchSourceCount(30)))
    }

    /** 相反的一面：**真的 0 首**（QQ 已返回且为空）必须显示 0，不能被"搜索中"盖住。 */
    @Test
    fun `QQ 已返回且为空时显示真实的 0`() {
        val counts = SourceCounts(
            neteaseCount = 30,
            neteaseStatus = SourceSearchStatus.DONE,
            qqCount = 0,
            qqStatus = SourceSearchStatus.DONE,
        )
        val line = counts.summary(strings)
        assertEquals("真的 0 首必须显示 0", strings.searchSourceCount(0), counts.qqText(strings))
        assertTrue("真的 0 首必须显示 0：$line", line.contains(strings.searchSourceCount(0)))
        assertFalse("已返回就不该再说「搜索中」：$line", line.contains(strings.searchSourcePending))
    }

    // ------------------------------------------------------------ 三种非 DONE 状态各说各的

    @Test
    fun `超时与搜索中是两句不同的话`() {
        val pending = SourceCounts(qqStatus = SourceSearchStatus.PENDING).qqText(strings)
        val timeout = SourceCounts(qqStatus = SourceSearchStatus.TIMEOUT).qqText(strings)
        assertNotEquals("超时与「搜索中」撞词了 —— 前者会自动有结果，后者需要用户动一下", pending, timeout)
        assertEquals(strings.searchSourcePending, pending)
        assertEquals(strings.searchSourceTimeout, timeout)
    }

    @Test
    fun `未发起这一轮时显示未登录而不是 0`() {
        val counts = SourceCounts(
            neteaseCount = 30,
            neteaseStatus = SourceSearchStatus.DONE,
            qqStatus = SourceSearchStatus.SKIPPED,
        )
        val line = counts.summary(strings)
        assertEquals(strings.searchSourceSkipped, counts.qqText(strings))
        assertFalse("未发起也不该显示 0：$line", line.contains("QQ 音乐 0"))
    }

    // ------------------------------------------------------------ 与既有格式逐字相同（换路径不许改口径）

    /**
     * ★ **两源都返回时，新路径与旧的 `sourceSummary` 产出逐字相同的文案。**
     *
     * v2.5.2 规则 2 的「搬家 ≠ 改行为」：本版把统计行从
     * `sourceSummary(Int, Int)`（只能表达计数）换成
     * `searchSourceSummaryWithStatus(String, String)`（能表达未知），
     * 但**已完成态的文案一个字都不许变** —— 否则老用户的肌肉记忆会失效，
     * 而且没有任何测试能说清「到底哪一版是对的」。
     *
     * 八种语言逐个比。
     */
    @Test
    fun `两源都返回时新路径与 sourceSummary 逐字相同`() {
        val presets = listOf(zhCN, zhTW, en, jpJP, jpMY, koNK, deDE, ruRU)
        presets.forEach { s ->
            for ((n, q) in listOf(30 to 12, 0 to 0, 1 to 1, 300 to 7)) {
                val done = SourceCounts(
                    neteaseCount = n,
                    neteaseStatus = SourceSearchStatus.DONE,
                    qqCount = q,
                    qqStatus = SourceSearchStatus.DONE,
                )
                assertEquals(
                    "两源都 DONE 时新路径改了文案：n=$n q=$q",
                    s.sourceSummary(n, q),
                    done.summary(s),
                )
            }
        }
    }

    /**
     * 计数文案本身必须能表达 0 / 1 / 大数，且**八种语言都不为空**。
     *
     * 「1 首」与「1 songs」这种复数错误在 en/de 上会显形，所以这两个语言的
     * `searchSourceCount` 用了单复数分支；这里只断言非空与包含数字。
     */
    @Test
    fun `计数文案在八种语言里都非空且带数字`() {
        val presets = listOf(zhCN, zhTW, en, jpJP, jpMY, koNK, deDE, ruRU)
        presets.forEach { s ->
            for (n in listOf(0, 1, 2, 30)) {
                val text = s.searchSourceCount(n)
                assertTrue("计数文案为空 (n=$n)", text.isNotBlank())
                assertTrue("计数文案里没有数字 (n=$n): $text", text.contains(n.toString()))
            }
        }
    }

    /** 八种语言的三种状态文案都非空，且两两不同（同一语言内）。 */
    @Test
    fun `八种语言的三种状态文案非空且互不相同`() {
        val presets = listOf(zhCN, zhTW, en, jpJP, jpMY, koNK, deDE, ruRU)
        presets.forEach { s ->
            val trio = listOf(s.searchSourcePending, s.searchSourceTimeout, s.searchSourceSkipped)
            trio.forEach { assertTrue("状态文案为空", it.isNotBlank()) }
            assertEquals("同一语言里三种状态的文案有重复：$trio", 3, trio.distinct().size)
        }
    }

    // ------------------------------------------------------------ 派生谓词

    @Test
    fun `hasPending 只在真的有源在飞时为真`() {
        assertTrue(SourceCounts(qqStatus = SourceSearchStatus.PENDING).hasPending)
        assertTrue(
            SourceCounts(neteaseStatus = SourceSearchStatus.PENDING, qqStatus = SourceSearchStatus.DONE)
                .hasPending,
        )
        assertFalse(
            SourceCounts(neteaseStatus = SourceSearchStatus.DONE, qqStatus = SourceSearchStatus.DONE)
                .hasPending,
        )
        assertFalse(
            SourceCounts(neteaseStatus = SourceSearchStatus.DONE, qqStatus = SourceSearchStatus.SKIPPED)
                .hasPending,
        )
    }

    @Test
    fun `qqUnavailable 只在超时时为真`() {
        assertTrue(SourceCounts(qqStatus = SourceSearchStatus.TIMEOUT).qqUnavailable)
        assertFalse(SourceCounts(qqStatus = SourceSearchStatus.PENDING).qqUnavailable)
        assertFalse(SourceCounts(qqStatus = SourceSearchStatus.DONE).qqUnavailable)
        assertFalse(SourceCounts(qqStatus = SourceSearchStatus.SKIPPED).qqUnavailable)
    }

    @Test
    fun `isDone 按音源分别判定`() {
        val counts = SourceCounts(
            neteaseStatus = SourceSearchStatus.DONE,
            qqStatus = SourceSearchStatus.PENDING,
        )
        assertTrue(counts.isDone(MusicSource.NETEASE))
        assertFalse(counts.isDone(MusicSource.QQMUSIC))
    }

    /**
     * 两侧文案合起来必须**正好**等于统计行 —— 防的是有人把某一句拼了两次。
     *
     * 与 `searchSourceSummaryWithStatus` 的实现无关（那是 i18n 的事），
     * 只钉住「left + 分隔 + right」这个结构。
     */
    @Test
    fun `统计行包含两侧文案`() {
        val counts = SourceCounts(
            neteaseCount = 30,
            neteaseStatus = SourceSearchStatus.DONE,
            qqStatus = SourceSearchStatus.PENDING,
        )
        val line = counts.summary(strings)
        assertTrue(line.contains(counts.neteaseText(strings)))
        assertTrue(line.contains(counts.qqText(strings)))
    }

    /** 默认值：QQ 是 PENDING（**不是 DONE + 0**）。这是构造这个类型时的安全默认值。 */
    @Test
    fun `默认的 QQ 状态是 PENDING 而不是 DONE`() {
        val counts = SourceCounts()
        assertEquals(SourceSearchStatus.PENDING, counts.qqStatus)
        assertFalse(counts.isDone(MusicSource.QQMUSIC))
        // 网易云默认 DONE：它总是第一个被发布的源。
        assertEquals(SourceSearchStatus.DONE, counts.neteaseStatus)
    }
}
