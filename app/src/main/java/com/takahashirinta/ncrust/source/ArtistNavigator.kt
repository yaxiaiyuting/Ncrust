/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.6.1 · P0：艺人跳转的**唯一**身份判定落点。**纯逻辑，无 Android 依赖，JVM 可单测。**
 */

package com.takahashirinta.ncrust.source

import com.takahashirinta.ncrust.crosssource.MatchConfidence
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.ArtistItem

/**
 * 一次「转到歌手」的**决策结果**（v2.6.1）。
 *
 * 它把「点了之后到底去哪」从一个散在三处的副作用（谁拼路由、谁补 id、谁兜底）
 * 收敛成一个可以单测的值。三种形态互斥且穷尽：
 *
 * | 形态 | 含义 | UI 动作 |
 * |---|---|---|
 * | [Direct] | 拿到了**本源内**合法值域的身份 | 进该源艺人页 |
 * | [Search] | 拿不到可信身份，但知道艺人名 | 进搜索页并预填艺人名 |
 * | [Unavailable] | 连名字都没有 | 什么都不做（不跳错页） |
 *
 * **不存在第四种形态**（「跳到另一个源的某个艺人」）是**有意**的：
 * 本应用没有任何一条「跨源艺人跳转」的产品需求，而 v2.6.1 的 P0 正是
 * 「把一个源的数字 id 拿去另一个源查」造成的。把它排除在类型之外，
 * 比在实现里小心不提更可靠。
 */
sealed interface ArtistNav {

    /**
     * 进**本源**艺人页。
     *
     * @property source 目标音源。**构造它的唯一入口是 [ArtistNavigator]**，
     *   且恒等于歌曲自己的音源（见 [ArtistNavigator.resolve] 的不变量）。
     * @property id 该音源内的**字符串**身份：网易云是十进制 id，QQ 音乐是 `singerMID`。
     *   恒满足 [ArtistNavigator.idDomainMatches]。
     * @property name 艺人名的快照，**只作展示与召回**（艺人页拿它当搜索关键词去拉对端热门曲）。
     *   **绝不参与身份判定** —— 名字可以空、可以错、可以重名，而 [id] 不行。
     *   之所以要把它带在决策里：QQ 的 `singerMID` 没有"按 mid 取名字"的接口，
     *   这个名字只有调用方手上那首歌才有；不带走，艺人页就只能显示「未知艺人」
     *   并且**静默地**召不回任何对端数据。
     */
    data class Direct(val source: MusicSource, val id: String, val name: String = "") : ArtistNav

    /**
     * 进搜索页并预填 [keyword]。
     *
     * 这条路的语义是「**找不到，但用户能理解**」：他会在搜索结果里看到正确的艺人，
     * 而不是被带到一个毫不相干的人那里。见 AGENTS.md 铁律 21。
     */
    data class Search(val keyword: String, val reason: ArtistNavReason) : ArtistNav

    /** 连一个能当关键词的艺人名都没有 —— 不做任何跳转。 */
    data object Unavailable : ArtistNav
}

/** 为什么要跳搜索。写进日志，让线上能区分「旧数据」与「服务端没给」。 */
enum class ArtistNavReason {
    /** 本源内有合法的字符串身份，直接跳。**不是**降级。 */
    SAME_SOURCE_ID,

    /** 本源内既没有数字 id 也没有字符串身份（冷启动恢复的曲目、只存名字的历史数据）。 */
    MISSING_ID,

    /**
     * 只有**数字**身份、没有该源可用的字符串身份 —— 本 P0 的确切形状。
     *
     * QQ 曲目只有 `singer.id`（QQ 域数字）时，唯一能"用"它的方式是把它当网易云 id 查，
     * 而实测那样会跳到**另一个歌手**（周杰伦 `4558` → 马洪波）。宁可跳搜索。
     */
    AMBIGUOUS_NUMERIC_ID,
}

/**
 * 「转到歌手」的唯一身份判定（v2.6.1 P0 修复）。
 *
 * ## 它修的到底是什么
 *
 * 真机实测（PCL110 / WGR-W09，v2.6.0-gpl）同一条菜单项有两种错法，**根因是同一个**：
 *
 * | 当前歌曲的数据来源 | `artists[0]` 的形状 | 点「转到歌手」的结果 |
 * |---|---|---|
 * | 搜索结果里**新鲜**加载的 QQ 曲目 | `id=4558`（QQ 数字）、无 mid | 跳到**马洪波**（网易云 4558） |
 * | **冷启动恢复**的 QQ 曲目 | `id=null`、只有名字 | **毫无反应**（静默失败） |
 *
 * 第一种错在「拿 QQ 的数字 id 当网易云 id 走老路由」；第二种错在
 * `resolveAndNavigate` 的补 id 回落是**网易云**的 `song/detail`，而 QQ 曲目的 id 带
 * bit62 标志位（[SourceIds.QQ_ID_FLAG]），问网易云必然查不到 ⇒ `artistId` 仍是 null
 * ⇒ `when` 一个分支都不匹配 ⇒ 什么都不发生。
 *
 * ## 三条不变量（改这个文件之前先读）
 *
 * 1. **绝不产出与歌曲音源不同的 [ArtistNav.Direct]**。跨源跳转不在本应用的
 *    产品范围内；把它排除在类型之外，比在实现里小心不提更可靠。
 * 2. **身份必须过值域闸门**（[idDomainMatches]）。QQ 的艺人字符串身份是 base62 的
 *    `singerMID`，网易云是十进制 id —— 形状不同，所以「拿数字 QQ id 当 mid 用」
 *    这种错法在**闸门**上就被拦下，不需要调用方自觉。
 * 3. **置信度不足一律跳搜索**（[crossSourceJump]）。即便将来真要做跨源跳转，
 *    也必须拿到 `MatchConfidence.mergeable`（>= MEDIUM，全应用唯一阈值）
 *    **且**目标源内有合法值域的身份；任一不满足 ⇒ [ArtistNav.Search]。
 *
 * ## 为什么名字不能当身份
 *
 * v2.4.0 已经用实测钉过这条：网易云上存在与 `邓紫棋` 同名的仿冒号（62017015，1 张专辑），
 * 按名字锚定会把真身 `G.E.M.邓紫棋`（7763）判成 `NONE`。所以 [ArtistItem.name] 在这里
 * **只**用来生成搜索关键词，**从不**参与 [ArtistNav.Direct] 的构造。
 */
object ArtistNavigator {

    /**
     * 网易云艺人 id 的值域上界（不含）。
     *
     * 取 `2^40`：网易云的 id 是十进制百万~十亿量级，远小于它；而
     * [SourceIds.QQ_ID_FLAG]（`2^62`）造的合成 id 远大于它。
     * 于是「这个 id 到底属于哪个值域」是一个**结构性**判据，不是启发式。
     */
    const val NETEASE_ID_MAX: Long = 1L shl 40

    /** `singerMID` 的合法字符集（base62）。 */
    private val BASE62 = Regex("^[0-9A-Za-z]+$")

    /**
     * 这个 id 是不是 [source] 域内的合法**字符串**身份。纯函数，JVM 可单测。
     *
     * - **网易云**：纯十进制、`1 ..< ` [NETEASE_ID_MAX]。这就是 `api/artist/{id}` 吃的形状。
     * - **QQ 音乐**：base62 的 `singerMID`（`0025NhlN2yWrP4`），长度 >= 5，
     *   **且不能是一个网易云值域内的纯数字** —— 后者正是「QQ 数字 `singerID` 被当成
     *   mid 用」的形状。真实 `singerMID` 是 14 位 base62，恰好落在
     *   [NETEASE_ID_MAX] 以内的纯数字概率约 `62^-14`，可以忽略；
     *   而把这条写死成判据，是为了让「传错域」**必然**在闸门上失败。
     */
    fun idDomainMatches(source: MusicSource, id: String?): Boolean {
        val v = id?.trim().orEmpty()
        if (v.isEmpty()) return false
        return when (source) {
            MusicSource.NETEASE ->
                v.all { it in '0'..'9' } && v.toLongOrNull()?.let { it in 1 until NETEASE_ID_MAX } == true

            MusicSource.QQMUSIC -> {
                if (v.length < 5 || v.length > 64) return false
                if (!BASE62.matches(v)) return false
                // 纯数字且落在网易云值域 ⇒ 这是 QQ 的 singerID，不是 singerMID。
                val asLong = v.toLongOrNull()
                !(asLong != null && asLong in 1 until NETEASE_ID_MAX)
            }
        }
    }

    /** [MatchConfidence.mergeable] 的**唯一**消费口 —— 不要在调用方写 `>= HIGH` 这类比较。 */
    fun crossSourceJumpAllowed(confidence: MatchConfidence?): Boolean =
        confidence?.mergeable == true

    /**
     * 跨源跳转闸门（不变量 3）。**两道都要过**，否则返回 null（调用方据此跳搜索）。
     *
     * 探针结论：v2.6.1 的这条 P0 **没有**走 v2.4.0 的跨源匹配 ——
     * 它连匹配都没做，直接把 QQ 的数字 id 交给了网易云路由。所以本函数在当前代码里
     * 恒返回 null（没有可用的跨源结论）。保留它是因为**下一处**跨源跳转一定会用到它，
     * 而「阈值写在调用方」正是 v2.4.0 铁律 2 点名要避免的形状。
     *
     * @param confidence 缓存的匹配结论。`null` = 没有结论（不是「匹配失败」）。
     * @param targetSource 打算跳到哪个源。
     * @param targetId 打算跳过去的身份串。
     */
    fun crossSourceJump(
        confidence: MatchConfidence?,
        targetSource: MusicSource,
        targetId: String?,
    ): ArtistNav.Direct? {
        if (!crossSourceJumpAllowed(confidence)) return null
        if (!idDomainMatches(targetSource, targetId)) return null
        // 跨源跳转**不带名字**：那个名字来自另一个源，用它当搜索关键词会把
        // 目标源的召回带偏（而名字本来就不该参与身份判定）。
        return ArtistNav.Direct(targetSource, targetId!!.trim(), "")
    }

    /**
     * **「转到歌手」的唯一决策入口**（纯函数）。
     *
     * 不变量 1 由构造保证：两条 `Direct` 出口都写死成 `source = song.musicSource`，
     * 函数体内没有任何一处读「另一个源」的 id。
     */
    fun resolve(song: SongItem): ArtistNav {
        val source = song.musicSource
        val artist = song.artists?.firstOrNull()
        val keyword = artist?.name?.trim()?.takeIf { it.isNotEmpty() }

        // 本源内的字符串身份优先 → 直接跳。网易云看 id，QQ 看 mid。
        val sameSourceId: String? = when (source) {
            MusicSource.NETEASE -> artist?.id?.toString()
            MusicSource.QQMUSIC -> artist?.mid
        }
        if (idDomainMatches(source, sameSourceId)) {
            return ArtistNav.Direct(source, sameSourceId!!.trim(), keyword.orEmpty())
        }

        // 走到这里说明**本源内**没有可用身份。两条降级路都不许猜：
        //
        //  · QQ 曲目只剩 `singer.id`（QQ 域数字）时，唯一能"用"它的方式就是拿它去
        //    网易云查 —— 那正是本 P0（4558 → 马洪波）。它属于「只有跨源猜才能用」
        //    的身份，因此必须过 [crossSourceJump] 闸门；而探针结论是这条路上没有
        //    任何可用的匹配结论（`MatchCacheStore` 的键是 `(source, id)`，
        //    数字 singerID 不是任何一个源的有效键）⇒ 一律跳搜索。
        //  · 连数字 id 都没有（冷启动恢复）时同理，且连跨源猜都无从下手。
        val numericOnly = artist?.id?.takeIf { it > 0L }?.toString()
        val viaCrossSource = if (source == MusicSource.QQMUSIC && numericOnly != null) {
            // confidence=null：没有任何针对这个数字 id 的匹配结论可用（见 KDoc）。
            crossSourceJump(null, MusicSource.QQMUSIC, null)
        } else {
            null
        }
        viaCrossSource?.let { return it }

        if (keyword == null) return ArtistNav.Unavailable
        val reason = if (artist?.id != null && artist.id > 0L) {
            ArtistNavReason.AMBIGUOUS_NUMERIC_ID
        } else {
            ArtistNavReason.MISSING_ID
        }
        return ArtistNav.Search(keyword, reason)
    }
}
