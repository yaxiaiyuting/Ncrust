package com.takahashirinta.ncrust.search

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v2.1.4 聚合搜索排序的回归测试。
 *
 * 规则来自用户原话：「如果用户有 vip，如果有网易云就优先给出网易云的会员专享歌曲，
 * qq 绿钻就给 qq 的曲库，如果两个都有，就同时给出来」。
 *
 * 这里的用例把三件事钉住：
 * 1. 四种会员组合各自的结果顺序（含「都没有 ⇒ 一个字节都不改」）；
 * 2. 交错（拉链式）的精确形状 —— 它决定了首屏同时看到两家的会员曲；
 * 3. `fee` 语义，特别是最容易误判的 `fee == 8`（免费播放、高音质需会员）。
 */
class SearchRankingTest {

    private fun n(id: String, access: TrackAccess) = RankedSong("n$id", access)
    private fun q(id: String, access: TrackAccess) = RankedSong("q$id", access)

    private fun <T> List<RankedSong<T>>.keys() = map { it.value }

    // ---------- 都没有会员：保持历史行为 ----------

    @Test
    fun `两家都没会员时顺序完全不变——网易云在前`() {
        val netease = listOf(n("1", TrackAccess.MEMBER_ONLY), n("2", TrackAccess.FREE))
        val qq = listOf(q("1", TrackAccess.MEMBER_ONLY), q("2", TrackAccess.FREE))
        val out = SearchRanking.rank(netease, qq, neteaseVip = false, qqVip = false)
        assertEquals(listOf("n1", "n2", "q1", "q2"), out.keys())
    }

    @Test
    fun `都没有会员时不得重排任何一家内部顺序`() {
        val netease = listOf(n("1", TrackAccess.FREE), n("2", TrackAccess.MEMBER_ONLY), n("3", TrackAccess.FREE))
        val out = SearchRanking.rank(netease, emptyList(), neteaseVip = false, qqVip = false)
        assertEquals(listOf("n1", "n2", "n3"), out.keys())
    }

    // ---------- 只有一家有会员 ----------

    @Test
    fun `只有网易云有会员——网易云的会员专享排最前，其余按原序，QQ 整块在后`() {
        val netease = listOf(
            n("a", TrackAccess.FREE),
            n("b", TrackAccess.MEMBER_ONLY),
            n("c", TrackAccess.UNKNOWN),
            n("d", TrackAccess.MEMBER_ONLY),
        )
        val qq = listOf(q("x", TrackAccess.MEMBER_ONLY), q("y", TrackAccess.FREE))
        val out = SearchRanking.rank(netease, qq, neteaseVip = true, qqVip = false)
        assertEquals(listOf("nb", "nd", "na", "nc", "qx", "qy"), out.keys())
    }

    @Test
    fun `只有 QQ 有会员——QQ 的会员专享排最前，网易云整块在后`() {
        val netease = listOf(n("a", TrackAccess.MEMBER_ONLY), n("b", TrackAccess.FREE))
        val qq = listOf(q("x", TrackAccess.FREE), q("y", TrackAccess.MEMBER_ONLY), q("z", TrackAccess.MEMBER_ONLY))
        val out = SearchRanking.rank(netease, qq, neteaseVip = false, qqVip = true)
        assertEquals(listOf("qy", "qz", "qx", "na", "nb"), out.keys())
    }

    /**
     * 另一家的会员专享**不得**被挑出来。
     *
     * 用户没有那家的会员，把它的会员曲推到前面等于推一首点开就跳歌的结果 ——
     * 这与「按会员排序」的初衷正好相反。
     */
    @Test
    fun `没有会员的那一家的会员专享不被提前`() {
        val netease = listOf(n("a", TrackAccess.FREE), n("b", TrackAccess.MEMBER_ONLY))
        val qq = listOf(q("x", TrackAccess.MEMBER_ONLY), q("y", TrackAccess.FREE))
        val out = SearchRanking.rank(netease, qq, neteaseVip = false, qqVip = true)
        // QQ 的 x 被提前，网易云的 b 仍然留在它原来的位置（第 3 位）
        assertEquals(listOf("qx", "qy", "na", "nb"), out.keys())
    }

    // ---------- 两家都有：交错 ----------

    @Test
    fun `两家都有会员——会员专享交错在前，其余交错在后`() {
        val netease = listOf(n("a", TrackAccess.MEMBER_ONLY), n("b", TrackAccess.FREE), n("c", TrackAccess.MEMBER_ONLY))
        val qq = listOf(q("x", TrackAccess.MEMBER_ONLY), q("y", TrackAccess.MEMBER_ONLY), q("z", TrackAccess.FREE))
        val out = SearchRanking.rank(netease, qq, neteaseVip = true, qqVip = true)
        // 会员组交错：na, qx, nc, qy   |  其余组交错：nb, qz
        assertEquals(listOf("na", "qx", "nc", "qy", "nb", "qz"), out.keys())
    }

    @Test
    fun `交错在长度不等时把多出来的一截原样接在尾部`() {
        val netease = listOf(n("a", TrackAccess.MEMBER_ONLY), n("b", TrackAccess.MEMBER_ONLY), n("c", TrackAccess.MEMBER_ONLY))
        val qq = listOf(q("x", TrackAccess.MEMBER_ONLY))
        val out = SearchRanking.rank(netease, qq, neteaseVip = true, qqVip = true)
        assertEquals(listOf("na", "qx", "nb", "nc"), out.keys())
    }

    @Test
    fun `两家都有会员但一家没有会员专享曲——退化成另一家在前`() {
        val netease = listOf(n("a", TrackAccess.FREE))
        val qq = listOf(q("x", TrackAccess.MEMBER_ONLY))
        val out = SearchRanking.rank(netease, qq, neteaseVip = true, qqVip = true)
        assertEquals(listOf("qx", "na"), out.keys())
    }

    // ---------- 不丢结果 ----------

    @Test
    fun `任何会员组合下都不丢也不重复任何一条结果`() {
        val netease = listOf(
            n("a", TrackAccess.MEMBER_ONLY), n("b", TrackAccess.FREE), n("c", TrackAccess.UNKNOWN),
        )
        val qq = listOf(
            q("x", TrackAccess.MEMBER_ONLY), q("y", TrackAccess.FREE), q("z", TrackAccess.UNKNOWN),
        )
        for (nv in listOf(true, false)) {
            for (qv in listOf(true, false)) {
                val out = SearchRanking.rank(netease, qq, nv, qv)
                assertEquals("neteaseVip=$nv qqVip=$qv 结果数不对", 6, out.size)
                assertEquals("neteaseVip=$nv qqVip=$qv 有重复", 6, out.keys().toSet().size)
            }
        }
    }

    @Test
    fun `空输入不崩`() {
        assertEquals(emptyList<String>(), SearchRanking.rank<Any>(emptyList(), emptyList(), true, true).keys())
        assertEquals(
            listOf("n1"),
            SearchRanking.rank(listOf(n("1", TrackAccess.MEMBER_ONLY)), emptyList(), true, true).keys(),
        )
    }

    // ---------- fee 语义 ----------

    @Test
    fun `网易云 fee 的映射——8 是免费播放而不是会员专享`() {
        assertEquals(TrackAccess.FREE, TrackAccess.ofNeteaseFee(0))
        // 最容易误判的一条：fee=8 带「付费」字样，实际是「播放免费 + 高音质需会员」。
        // 实测《稻香(深情版)》就是 fee=8。把它当会员专享会让排序去推一首谁都能放的歌。
        assertEquals(TrackAccess.FREE, TrackAccess.ofNeteaseFee(8))
        assertEquals(TrackAccess.MEMBER_ONLY, TrackAccess.ofNeteaseFee(1))
        assertEquals(TrackAccess.MEMBER_ONLY, TrackAccess.ofNeteaseFee(4))
        assertEquals(TrackAccess.UNKNOWN, TrackAccess.ofNeteaseFee(null))
        assertEquals(TrackAccess.UNKNOWN, TrackAccess.ofNeteaseFee(999))
    }

    @Test
    fun `QQ 的 pay_play 布尔映射——null 是不知道而不是免费`() {
        assertEquals(TrackAccess.MEMBER_ONLY, TrackAccess.ofQqMemberOnly(true))
        assertEquals(TrackAccess.FREE, TrackAccess.ofQqMemberOnly(false))
        assertEquals(TrackAccess.UNKNOWN, TrackAccess.ofQqMemberOnly(null))
    }

    /** `UNKNOWN` 绝不能算作 gated —— 它只是「不知道」，往前推会推出点不开的歌。 */
    @Test
    fun `UNKNOWN 不是会员专享`() {
        assertEquals(false, TrackAccess.UNKNOWN.isGated)
        assertEquals(true, TrackAccess.MEMBER_ONLY.isGated)
        assertEquals(false, TrackAccess.FREE.isGated)
    }
}
