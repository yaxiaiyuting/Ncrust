/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · A：多音源架构的地基。**纯逻辑，无 Android 依赖，JVM 可单测。**
 */

package com.takahashirinta.ncrust.source

/**
 * 音乐音源（v2.1.0 · A）。
 *
 * 现在的取值只有网易云与 QQ 音乐两个，但架构上这里是**唯一**「音源」概念的定义处：
 * 队列里的每一首歌、每一个播放 URL 缓存条目、每一次歌词请求都必须能回答「你属于哪个音源」。
 *
 * ## 为什么 key 是字符串而不是 ordinal
 *
 * [key] 会被写进 SharedPreferences（队列持久化）、离线缓存的 URL query 参数、
 * 以及 MediaItem 的 mediaId。用 ordinal 的话，「以后在中间插一个音源」会让所有历史数据
 * 静默指错音源；字符串 key 是稳定且可读的，也让 JSON 里能直接看出来源。
 *
 * ## 未知 key 一律回落 [NETEASE]（不是抛异常）
 *
 * 这是**向后兼容的硬要求**：v2.1.0 之前持久化的队列 JSON 里没有 `source` 字段，
 * 离线缓存的 key 形状是 `song:<id>:<level>`（没有音源段）。读到一个不认识的 key 时
 * 唯一正确的解释就是「这是旧数据、来自网易云」，抛异常等于让老用户开不了机。
 */
enum class MusicSource(val key: String) {
    NETEASE("netease"),
    QQMUSIC("qqmusic"),
    ;

    /** 该音源的歌曲是否需要 `sourceId`（QQ 音乐取链必须带 songmid，网易云不需要）。 */
    val requiresSourceId: Boolean get() = this == QQMUSIC

    companion object {
        /** 旧数据、未知 key 的归属。 */
        val DEFAULT = NETEASE

        /** 所有可登录、可在 UI 上切换的音源（顺序即 UI 顺序）。 */
        val selectable: List<MusicSource> = listOf(NETEASE, QQMUSIC)

        /** 解析字符串 key；null / 空串 / 不认识的值一律回落 [DEFAULT]。 */
        fun fromKey(key: String?): MusicSource =
            values().firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * 音源感知的标识符编解码（v2.1.0 · A）。**纯逻辑，JVM 可单测。**
 *
 * 三套标识符在本应用里各有用途，且**都必须能把「音源」带在字符串里**，
 * 否则两个平台的同名 id 会互相串台（QQ 音乐的数字 songid 与网易云的 songId 完全可能撞号）：
 *
 * | 用途 | 形状 | 谁在用 |
 * |---|---|---|
 * | [trackKey] | `netease:123` / `qqmusic:456` | 内部缓存 key、日志、诊断 |
 * | [mediaId] | `song:123`（网易云，与 v2.0.2 逐字节相同）/ `song:qqmusic:456` | media3 的 MediaItem、Android Auto browse tree |
 * | 离线缓存 key | `song:123:lossless`（网易云，形状不变）/ `song:q:456:lossless` | `OfflineKeys` |
 *
 * **网易云一侧的形状一律保持 v2.0.2 原样**：已有用户的队列 JSON、离线缓存清单、
 * offload 缓存目录里的 URL 全部指向这些字符串，改形状 = 让老用户的离线缓存全部失效。
 */
object SourceIds {

    /** media3 `MediaItem.mediaId` 的前缀，v1.3.0 起就是它，不要改。 */
    const val MEDIA_ID_PREFIX = "song:"

    /** 离线缓存 key 里 QQ 音源的短标记（比 `qqmusic` 短，且不会与 `toLongOrNull()` 混淆）。 */
    const val OFFLINE_QQ_TAG = "q"

    /** `netease:123`。 */
    fun trackKey(source: MusicSource, id: Long): String = source.key + ":" + id

    /**
     * 解析 [trackKey]。**只接受「已知音源 key + 正整数 id」**，其余返回 null ——
     * 这个函数用来读外部输入（日志解析、诊断），宁可返回 null 也不要猜。
     */
    fun parseTrackKey(text: String?): Pair<MusicSource, Long>? {
        if (text.isNullOrEmpty()) return null
        val sep = text.indexOf(':')
        if (sep <= 0) return null
        val source = MusicSource.values().firstOrNull { it.key == text.substring(0, sep) } ?: return null
        val id = text.substring(sep + 1).toLongOrNull() ?: return null
        if (id <= 0L) return null
        return source to id
    }

    /**
     * 网易云：`song:123`（**与 v2.0.2 逐字节相同**）；QQ 音乐：`song:qqmusic:456`。
     * [songId] <= 0 时返回 null，让调用方自己决定怎么办（不要造一个假的 mediaId 出来）。
     */
    fun mediaId(source: MusicSource, id: Long): String? {
        if (id <= 0L) return null
        return if (source == MusicSource.NETEASE) {
            MEDIA_ID_PREFIX + id
        } else {
            MEDIA_ID_PREFIX + source.key + ":" + id
        }
    }

    /**
     * 解析 mediaId。兼容三种历史/新形状：
     * - `song:123` → (NETEASE, 123)：v2.1.0 之前写入 ExoPlayer 与通知栏的形状，**必须继续认**；
     * - `song:qqmusic:456` → (QQMUSIC, 456)；
     * - `song:<未知音源>:456` / `song:abc` → null（调用方按「无法解析」处理，不要猜成网易云，
     *   因为用错音源取链会拿到 404 或别人的歌）。
     */
    fun parseMediaId(mediaId: String?): Pair<MusicSource, Long>? {
        if (mediaId.isNullOrEmpty() || !mediaId.startsWith(MEDIA_ID_PREFIX)) return null
        val rest = mediaId.removePrefix(MEDIA_ID_PREFIX)
        val sep = rest.indexOf(':')
        if (sep < 0) {
            val id = rest.toLongOrNull() ?: return null
            return if (id > 0L) MusicSource.NETEASE to id else null
        }
        val sourceKey = rest.substring(0, sep)
        val source = MusicSource.values().firstOrNull { it.key == sourceKey } ?: return null
        val id = rest.substring(sep + 1).toLongOrNull() ?: return null
        return if (id > 0L) source to id else null
    }

    /**
     * QQ 音乐歌曲的**兜底数字 id**：当某个接口只给了 songmid、没给数字 songid 时用。
     *
     * 返回 **负数**，这样它与服务端下发的真实 songid（恒为正）永远不会撞号；
     * 同一个 mid 必须每次得到同一个值（队列持久化、离线缓存、进度记忆都拿它当 key），
     * 所以用确定性的 FNV-1a 64 位散列，而不是 `hashCode()`（JVM 实现虽稳定但只有 32 位，
     * 而且字符串 hashCode 的碰撞在这里没有任何兜底手段）。
     */
    fun fallbackIdFromSourceId(sourceId: String): Long {
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
        for (ch in sourceId) {
            hash = hash xor (ch.code.toLong() and 0xffL)
            hash *= 0x100000001b3L // FNV prime
        }
        // 取 62 位再取负，保证结果恒为负、非 0，且不会溢出 Long。
        val magnitude = (hash and 0x3fffffffffffffffL).let { if (it == 0L) 1L else it }
        return -magnitude
    }
}
