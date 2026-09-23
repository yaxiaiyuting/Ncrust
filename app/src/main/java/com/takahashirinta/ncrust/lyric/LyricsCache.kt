/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一条缓存的歌词原文、译文与逐字时间轴。
 *
 * [yrc] 是 v1.5.0 · B 新增的；老缓存里没有这个字段，Gson 走 Unsafe 反序列化不会填默认值，
 * 所以声明成可空并在读取处 `orEmpty()` —— 老缓存命中时退化成「没有逐字数据」，不会崩。
 *
 * [ttml] / [ttmlAt] 是 v1.9.0 新增的 AMLL TTML 原文与写入时刻，同样必须**可空 + 有默认值**：
 * 老缓存的 JSON 里没有这两个 key，Gson 同样走 Unsafe，缺字段时 [ttml] 是 null、[ttmlAt] 是 0，
 * 正好等于「没有 TTML」—— 既不崩，也不会被误判成新鲜数据。
 */
data class CachedLyrics(
    val lrc: String,
    val tlyric: String,
    val timestamp: Long,
    val yrc: String? = null,
    val ttml: String? = null,
    val ttmlAt: Long = 0
)

/**
 * 歌词本地缓存。
 *
 * 目的：进程被杀后重进时，无需再等网络即可立即恢复当前歌歌词，消除
 * 「冷启动歌词按钮灰掉 / 歌词消失、必须切歌才回来」的问题。
 *
 * 存储：SharedPreferences 内一个 JSON map（songId -> CachedLyrics）。
 * 内存镜像避免重复读盘；容量上限 [MAX_ENTRIES]，超出按 timestamp 淘汰最旧。
 * 只缓存服务端 code==200 的权威结果（含"确无歌词"的空串），失败不写，
 * 这样下次重试仍会打网络而不是命中一个假的空结果。
 *
 * v1.9.0 起同一张表还承载 AMLL TTML（见 [TTML_KEY_PREFIX] / [getTtml] / [putTtml]），
 * 与 LRC 共用容量上限与淘汰策略；TTML 另有 7 天 TTL（[TTML_TTL_MS]）。
 */
object LyricsCache {
    private const val PREFS = "ncrust_lyrics_cache"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 200

    /**
     * 「只有 TTML、还没有 LRC 条目」时的暂存 key 前缀（v1.9.0）。
     *
     * 为什么不把 TTML 直接写进歌曲自己的条目：条目里 `lrc == ""` 是**「确无歌词」的权威标记**
     * （[put] 会写、PlayerViewModel 命中它就把歌词按钮置灰且不再打网络），而预取 TTML 的下一首
     * 往往还没有 LRC 条目。为了存 TTML 就建一条 lrc="" 的正式条目，等于把那首歌判成「确无歌词」，
     * 一进播放歌词就没了 —— 预取反而把功能弄坏。所以没有正式条目时先落在 `ttml:<songId>` 下，
     * 等 [put] 写正式条目时再并进去（见 [put]），同一首歌最终只留一条。
     */
    private const val TTML_KEY_PREFIX = "ttml:"

    /** TTML 的本地 TTL：7 天。AMLL 投稿修订很慢，7 天足够省掉绝大多数重复拉取。 */
    const val TTML_TTL_MS = 7L * 24 * 60 * 60 * 1000

    private val gson = Gson()
    private val type = object : TypeToken<MutableMap<String, CachedLyrics>>() {}.type
    private val lock = Any()

    @Volatile
    private var memory: MutableMap<String, CachedLyrics>? = null

    private fun loadLocked(context: Context): MutableMap<String, CachedLyrics> {
        memory?.let { return it }
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null)
        val map = if (raw.isNullOrEmpty()) {
            mutableMapOf()
        } else {
            runCatching { gson.fromJson<MutableMap<String, CachedLyrics>>(raw, type) }
                .getOrNull() ?: mutableMapOf()
        }
        memory = map
        return map
    }

    /** 超出上限时按 timestamp 淘汰最旧。LRC 条目与 TTML 暂存条目共用这一个名额池。 */
    private fun trimLocked(map: MutableMap<String, CachedLyrics>) {
        if (map.size > MAX_ENTRIES) {
            map.entries
                .sortedBy { it.value.timestamp }
                .take(map.size - MAX_ENTRIES)
                .forEach { map.remove(it.key) }
        }
    }

    private fun persistLocked(context: Context, map: Map<String, CachedLyrics>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENTRIES, gson.toJson(map))
            .apply()
    }

    suspend fun get(context: Context, songId: Long): CachedLyrics? = withContext(Dispatchers.IO) {
        synchronized(lock) { loadLocked(context)[songId.toString()] }
    }

    /**
     * 写入 LRC / 译文 / 逐字。
     *
     * [yrc] 保持默认参数，v1.5.0 的调用点（PlayerViewModel）签名不变。
     * 若这首歌之前只有 TTML 暂存条目，这里把它并进正式条目并删掉暂存，避免同一首歌留两条。
     */
    suspend fun put(context: Context, songId: Long, lrc: String, tlyric: String, yrc: String = "") {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val map = loadLocked(context)
                val key = songId.toString()
                val now = System.currentTimeMillis()
                val old = map[key]
                val staged = map.remove(ttmlKey(songId))
                // 正式条目已有 TTML 就用它（putTtml 会就地更新它），否则取暂存的那份。
                val ttmlSource = old?.takeIf { it.ttml != null } ?: staged
                map[key] = CachedLyrics(
                    lrc, tlyric, now, yrc,
                    ttmlSource?.ttml, ttmlSource?.ttmlAt ?: 0
                )
                trimLocked(map)
                persistLocked(context, map)
            }
        }
    }

    /**
     * 取 TTL 内的 TTML 原文（[isTtmlFresh]）；过期、没有、内容为空都返回 null，
     * 调用方据此决定要不要打网络。
     */
    suspend fun getTtml(context: Context, songId: Long): String? =
        readTtml(context, songId, requireFresh = true)

    /**
     * 忽略 TTL 取 TTML 原文，**给离线兜底用**：只在 [getTtml] 返回 null（过期或没拉过）
     * 且当前拿不到网络时，用这份旧数据先显示，别让用户看到空白。
     */
    suspend fun getTtmlStale(context: Context, songId: Long): String? =
        readTtml(context, songId, requireFresh = false)

    private suspend fun readTtml(context: Context, songId: Long, requireFresh: Boolean): String? =
        withContext(Dispatchers.IO) {
            val entry = synchronized(lock) {
                val map = loadLocked(context)
                map[songId.toString()] ?: map[ttmlKey(songId)]
            }
            ttmlOf(entry, System.currentTimeMillis(), requireFresh)
        }

    /**
     * 写入 TTML 原文并打上 [System.currentTimeMillis] 的时间戳。
     *
     * 空内容直接丢弃：宁可下次再拉一次，也不把「这次没拿到」缓存成「这首歌没有 TTML」——
     * 后者会让这首歌在 TTL 内都不再尝试。
     */
    suspend fun putTtml(context: Context, songId: Long, ttml: String) {
        if (ttml.isBlank()) return
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val map = loadLocked(context)
                val key = songId.toString()
                val now = System.currentTimeMillis()
                val existing = map[key]
                if (existing != null) {
                    map[key] = existing.copy(ttml = ttml, ttmlAt = now)
                } else {
                    // 还没有正式条目：暂存，绝不建 lrc="" 的正式条目（见 TTML_KEY_PREFIX）。
                    map[ttmlKey(songId)] = CachedLyrics("", "", now, null, ttml, now)
                }
                trimLocked(map)
                persistLocked(context, map)
            }
        }
    }

    private fun ttmlKey(songId: Long): String = TTML_KEY_PREFIX + songId

    /**
     * 纯函数：这份 TTML 是否还在 TTL 内。JVM 可单测（不碰 Android）。
     *
     * [ttmlAt] <= 0（老缓存没这个字段）一律算过期，所以不需要额外判断条目存在与否。
     * 边界：`now - ttmlAt == ttlMs` 算**过期**（TTL 是左闭右开区间），这样「正好第 7 天」
     * 会去重新拉一次，而不是把一个卡在边界上的值当成永远新鲜。
     */
    fun isTtmlFresh(ttmlAt: Long, now: Long, ttlMs: Long = TTML_TTL_MS): Boolean =
        ttmlAt > 0 && now - ttmlAt < ttlMs

    /**
     * 纯函数：从缓存条目里挑出 TTML 原文。
     *
     * [requireFresh] = true 走在线路径（[isTtmlFresh] 过滤）；false 走离线兜底（忽略 TTL）。
     * 空白内容一律不算命中 —— 正常写不进缓存，但半截写入/人工改坏的老数据不能当成歌词返回。
     */
    internal fun ttmlOf(entry: CachedLyrics?, now: Long, requireFresh: Boolean = true): String? {
        if (entry == null) return null
        val raw = entry.ttml ?: return null
        if (raw.isBlank()) return null
        if (requireFresh && !isTtmlFresh(entry.ttmlAt, now)) return null
        return raw
    }
}
