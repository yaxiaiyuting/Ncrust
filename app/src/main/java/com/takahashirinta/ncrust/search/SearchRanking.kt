/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.4 · 聚合搜索的排序 + 会员专享判定。**纯逻辑，JVM 可单测。**
 */

package com.takahashirinta.ncrust.search

/**
 * 一首歌的「播放是否被会员墙挡住」（v2.1.4）。
 *
 * ## 为什么需要它
 *
 * 聚合搜索把两个音源的结果拼在一张表里，而**拼的顺序此前是硬编码的**：
 * 网易云在前、QQ 接在后。用户明明有 QQ 绿钻，搜出来的歌却要往下翻半天才看到 QQ 的 ——
 * 会员买在哪一家，哪一家的结果就该先被看见。
 *
 * ## 与「音质是否需要会员」是两件事
 *
 * 这里只回答「**能不能播**」。一首免费曲的无损档位需要会员属于**音质**权益
 * （网易云实测 `fee=8`：播放免费、无损要会员），不算 [MEMBER_ONLY] ——
 * 把那种歌归成会员专享会让排序去推一首本来谁都能放的歌。
 */
enum class TrackAccess {
    /**
     * 播放**需要会员**（会员专享）。
     *
     * - 网易云：`fee == 1`（VIP 专享）或 `fee == 4`（数字专辑，买了才能听）。
     *   `fee == 8` **不在此列**：那是「免费播放 + 高音质需会员」。
     * - QQ 音乐：`pay.pay_play == 1`。
     */
    MEMBER_ONLY,

    /** 免费可播（网易云 `fee == 0` / `fee == 8`）。 */
    FREE,

    /**
     * 服务端没给判据 / 我们读不懂。
     *
     * **必须与 [FREE] 分开**：把「不知道」当「免费」会让排序把一批可能被墙的歌排到前面，
     * 用户点下去只会跳歌。不知道就与 [FREE] 同组靠后，这是唯一不撒谎的排法。
     */
    UNKNOWN,
    ;

    /** 播放是否被会员墙挡住。 */
    val isGated: Boolean get() = this == MEMBER_ONLY

    companion object {
        /**
         * 网易云的 `fee` → 播放是否被墙（v2.1.4）。**判据只此一处。**
         *
         * 实测语义（2026-09，登录态，`api/cloudsearch/pc`）：
         * - `0` 免费、`8` 免费播放但高音质需会员 ⇒ 都**不是**会员专享；
         * - `1` VIP 专享、`4` 数字专辑 ⇒ 是。
         *
         * 其它值（腾讯/网易以后可能加档）返回 [UNKNOWN] 而不是猜 ——
         * 猜错的方向是把免费曲标成专享（用户白翻一屏），或者反过来（推一首点不开的歌）。
         * 注意 `fee == 8` 是最容易被误判的一个：它带「付费」字样却是**免费可播**的，
         * 实测《稻香(深情版)》就是 `fee=8`。
         */
        fun ofNeteaseFee(fee: Int?): TrackAccess = when (fee) {
            0, 8 -> FREE
            1, 4 -> MEMBER_ONLY
            else -> UNKNOWN
        }

        /** QQ 音乐的 `pay.pay_play == 1` → 会员专享（映射时已算成布尔，这里只翻译）。 */
        fun ofQqMemberOnly(memberOnly: Boolean?): TrackAccess = when (memberOnly) {
            true -> MEMBER_ONLY
            false -> FREE
            null -> UNKNOWN
        }
    }
}

/**
 * 排序用的最小封装：把一个**任意类型**的结果与它的会员判定绑在一起。
 *
 * 用泛型而不是直接吃 `SongItem`，是为了让排序规则可以脱离 Retrofit/Gson 注解单测 ——
 * 否则测「排序」要先构造十几个无关字段，测试会写成「构造数据」而不是「验证规则」。
 * 调用方（`SearchViewModel`）用一行 `map { RankedSong(it, accessOf(it)) }` 适配。
 */
data class RankedSong<T>(val value: T, val access: TrackAccess)

/**
 * 聚合搜索的排序（v2.1.4）。
 *
 * ## 规则（按用户给的语义直接落地）
 *
 * | 用户有会员的音源 | 结果顺序 |
 * |---|---|
 * | 只有 QQ | QQ 的会员专享 → QQ 的其余 → 网易云的会员专享 → 网易云的其余 |
 * | 只有网易云 | 网易云的会员专享 → 网易云的其余 → QQ 的会员专享 → QQ 的其余 |
 * | 两家都有 | **交错（拉链式）**：两家的会员专享轮流在前，然后是两家的其余 |
 * | 都没有 / 都未登录 | **保持既有顺序不变**（网易云在前）—— 这个功能不该改变免费用户看到的顺序 |
 *
 * ## 为什么「两家都有」是交错而不是「都堆在前面」
 *
 * 用户原话是「如果两个都有，就同时给出来」。堆成「网易云会员曲 → QQ 会员曲 → 其余」
 * 会让排在最前的那一家永远占满首屏；交错才能让两家的会员曲**同时**出现在前面，
 * 而且交错是稳定、可预测的（同一次搜索的两次发布不会出现不同顺序）。
 *
 * ## 两个刻意的不变量
 *
 * 1. **只在「同一档」内部重排**（会员专享一组、其余一组），组内顺序原样保留 ——
 *    服务端算出来的相关性排序不会被我们重新洗一遍。
 * 2. **两家都没会员时一个字节都不改**。本功能是给会员的便利，不是给所有人的改动；
 *    免费用户的搜索结果顺序必须与 v2.1.3 完全一致。
 */
object SearchRanking {

    fun <T> rank(
        netease: List<RankedSong<T>>,
        qq: List<RankedSong<T>>,
        neteaseVip: Boolean,
        qqVip: Boolean,
    ): List<RankedSong<T>> {
        if (!neteaseVip && !qqVip) return netease + qq

        // 只有「有会员的那一家」的会员专享会被挑出来；另一家即使有会员专享也不动它
        // （用户没有那家的会员，推它的会员曲等于推一首点不开的歌）。
        val nGated = if (neteaseVip) netease.filter { it.access.isGated } else emptyList()
        val qGated = if (qqVip) qq.filter { it.access.isGated } else emptyList()
        val nRest = netease - nGated.toSet()
        val qRest = qq - qGated.toSet()

        if (neteaseVip && !qqVip) return nGated + nRest + qq
        if (qqVip && !neteaseVip) return qGated + qRest + netease
        return interleave(nGated, qGated) + interleave(nRest, qRest)
    }

    /**
     * 拉链式交错：`[a1, b1, a2, b2, …]`，多出来的一截原样接在尾部。
     *
     * 长度不等时不做特殊处理 —— 谁长谁把尾巴接上，顺序仍然稳定可预测。
     */
    private fun <T> interleave(a: List<T>, b: List<T>): List<T> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val out = ArrayList<T>(a.size + b.size)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            if (i < a.size) out.add(a[i])
            if (i < b.size) out.add(b[i])
        }
        return out
    }
}
