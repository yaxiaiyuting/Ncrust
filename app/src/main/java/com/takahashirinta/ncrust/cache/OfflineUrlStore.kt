/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.cache

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 播放 URL 的有界索引（v1.6.0 · D1）。**纯逻辑**，JVM 可单测。
 *
 * 缓存了音频字节还不够 —— 断网时客户端**先要拿到一个 URL** 才会去问 CacheDataSource，
 * 而网易的 URL 20 分钟就过期、每次都变。所以必须把「最后一次成功播过的 URL」按
 * [OfflineKeys] 的 key 存下来：离线时直接拿它去播，CacheDataSource 命中缓存即无需网络。
 */
internal class OfflineUrlIndex(private val maxEntries: Int = MAX_ENTRIES) {
    // LinkedHashMap 的迭代顺序 = 插入顺序，put 前先 remove 即得到 LRU。
    private val map = LinkedHashMap<String, String>()

    fun put(key: String, url: String) {
        if (key.isEmpty() || url.isEmpty()) return
        map.remove(key)
        map[key] = url
        while (map.size > maxEntries) {
            val it = map.keys.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            } else {
                break
            }
        }
    }

    fun get(key: String): String? = map[key]

    /**
     * 离线兜底用：先按 [preferredKeys] 的顺序找，再退化成「这首歌的**任意**档位」。
     * 离线时能放出来比「档位严格一致」重要得多（缓存里是什么档就放什么档）。
     */
    fun recall(preferredKeys: List<String>, songId: Long): Pair<String, String>? {
        for (k in preferredKeys) map[k]?.let { return k to it }
        for ((k, v) in map) {
            if (OfflineKeys.songIdOf(k) == songId) return k to v
        }
        return null
    }

    fun size(): Int = map.size

    fun toJson(): String = Gson().toJson(map)

    companion object {
        const val MAX_ENTRIES = 300

        fun fromJson(json: String?): OfflineUrlIndex {
            val idx = OfflineUrlIndex()
            if (json.isNullOrEmpty()) return idx
            runCatching {
                val type = object : TypeToken<LinkedHashMap<String, String>>() {}.type
                val loaded: LinkedHashMap<String, String>? = Gson().fromJson(json, type)
                loaded?.forEach { (k, v) -> idx.put(k, v) }
            }
            return idx
        }
    }
}

/**
 * 离线 URL 落盘（v1.6.0 · D1）。
 *
 * 只存「key → 最后一次成功播放的 URL」，**不存 cookie、不存音频字节**（字节由
 * [OfflineAudioCache] 管）。清单有上限（[OfflineUrlIndex.MAX_ENTRIES]）并按 LRU 淘汰，
 * 所以它不会无限涨。
 */
object OfflineUrlStore {
    private const val PREFS = "ncrust_offline"
    private const val KEY_URLS = "urls"

    @Volatile
    private var index: OfflineUrlIndex? = null

    private fun index(context: Context): OfflineUrlIndex {
        index?.let { return it }
        synchronized(this) {
            index?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val loaded = OfflineUrlIndex.fromJson(prefs.getString(KEY_URLS, null))
            index = loaded
            return loaded
        }
    }

    private fun persist(context: Context, idx: OfflineUrlIndex) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_URLS, idx.toJson()).apply()
        }
    }

    /** 记下「这个 key 最后一次成功播放的 URL」。 */
    fun remember(context: Context, key: String, url: String) {
        synchronized(this) {
            val idx = index(context)
            idx.put(key, url)
            persist(context, idx)
        }
    }

    /** 从播放 URL 上取下 key 并记录（服务端收到 URL 时调用，URL 上已带 key）。 */
    fun rememberFromUrl(context: Context, url: String) {
        OfflineKeys.keyOf(url)?.let { remember(context, it, url) }
    }

    /** 离线兜底：找回这首歌可用的 (key, url)。 */
    fun recall(context: Context, songId: Long, preferredLevels: List<String>): Pair<String, String>? =
        synchronized(this) {
            index(context).recall(preferredLevels.map { OfflineKeys.key(songId, it) }, songId)
        }

    fun clear(context: Context) {
        synchronized(this) {
            index = OfflineUrlIndex()
            persist(context, index(context))
        }
    }
}
