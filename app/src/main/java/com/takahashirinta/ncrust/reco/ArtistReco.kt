/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.reco

import android.content.Context
import android.util.Log
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.PlaylistApi

/**
 * 首页「音乐人推荐」卡片的触发配置与判定（v1.4.0 · 任务 B）。
 *
 * ## 触发设计（B0 实测后定稿，见 commit 说明）
 * 原方案「对收藏艺人的 simiArtist 列表做匹配」**不可行**：B0 实测
 *  - 任务里写的 `/eapi/simi/artist` 根本不存在（匿名与登录态都是 404）；
 *  - 真实端点是 `/eapi/discovery/simiArtist`（参数名 `artistid`，匿名 301、必须登录）；
 *  - 但目标艺人 122618229 自己的相似列表返回空，本账号 top20 收藏艺人的相似列表里
 *    也 0 次命中它 —— 这条路对目标艺人永远不触发。
 *
 * 因此采用任务里预先约定的**降级方案：风格锚点艺人**——
 * 「用户收藏单曲的艺人 ∩ 锚点 ≠ ∅」即认为口味命中，在首页插入卡片。
 * 锚点有两个来源（并集）：
 *  1. 手动配置：prefs `artist_reco_anchor_ids`（CSV，默认空）；
 *  2. 自动推导：目标艺人的热门曲 → simiSong → 结果里的其他艺人（7 天 TTL 缓存）。
 *
 * ## 不影响其他用户
 * 所有配置默认空：`artist_reco_enabled=false`、`artist_reco_target_id=0`，
 * 此时 [shouldShow] 恒为 false，卡片不显示、也不发任何请求。
 */
object ArtistReco {
    private const val TAG = "ArtistReco"
    private const val PREFS = "ncrust_settings"

    const val KEY_ENABLED = "artist_reco_enabled"
    const val KEY_TARGET = "artist_reco_target_id"
    const val KEY_ANCHORS = "artist_reco_anchor_ids"
    private const val KEY_AUTO_ANCHORS = "artist_reco_auto_anchor_ids"
    private const val KEY_AUTO_AT = "artist_reco_auto_anchor_at"

    /** 自动锚点缓存有效期：7 天。 */
    private const val AUTO_TTL_MS = 7L * 24 * 60 * 60 * 1000
    /** 推导锚点时取样几首目标艺人的热门曲（每首一次 simiSong 请求）。 */
    private const val AUTO_SEED_SONGS = 3

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun targetId(ctx: Context): Long = prefs(ctx).getLong(KEY_TARGET, 0L)

    fun setTarget(ctx: Context, artistId: Long) {
        prefs(ctx).edit().putLong(KEY_TARGET, artistId).apply()
    }

    fun manualAnchors(ctx: Context): Set<Long> = parseIds(prefs(ctx).getString(KEY_ANCHORS, "").orEmpty())

    fun setManualAnchors(ctx: Context, ids: Collection<Long>) {
        prefs(ctx).edit().putString(KEY_ANCHORS, ids.joinToString(",")).apply()
    }

    fun autoAnchors(ctx: Context): Set<Long> =
        parseIds(prefs(ctx).getString(KEY_AUTO_ANCHORS, "").orEmpty())

    private fun autoAnchorsFresh(ctx: Context): Boolean {
        val at = prefs(ctx).getLong(KEY_AUTO_AT, 0L)
        return at > 0L && System.currentTimeMillis() - at < AUTO_TTL_MS
    }

    private fun parseIds(raw: String): Set<Long> =
        raw.split(',', ';', ' ').mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toLongOrNull() }.toSet()

    /** 配置是否完整：开启 + 配了目标艺人 + 至少有一个可用锚点。 */
    fun isConfigured(ctx: Context): Boolean =
        isEnabled(ctx) && targetId(ctx) != 0L && (manualAnchors(ctx) + autoAnchors(ctx)).isNotEmpty()

    /**
     * 口味命中判定：收藏单曲（本地已加载部分）里的艺人只要有一个落在锚点集合里，
     * 就认为"与目标艺人风格相近"，首页显示卡片。纯本地计算，不发请求。
     */
    fun shouldShow(ctx: Context): Boolean {
        if (!isConfigured(ctx)) return false
        val anchors = manualAnchors(ctx) + autoAnchors(ctx)
        val mine = LibraryManager.getSavedSongs(ctx)
            .flatMap { it.artists.orEmpty() }
            .mapNotNull { it.id }
            .toHashSet()
        return mine.any { it in anchors }
    }

    /**
     * 自动推导锚点：目标艺人的热门曲 → [PlaylistApi.getSimilarSongs] → 结果里除目标之外的艺人。
     * 带 7 天 TTL；force=true 时无视 TTL。返回推导后的锚点集合（失败时返回既有缓存）。
     */
    suspend fun refreshAutoAnchors(ctx: Context, force: Boolean = false): Set<Long> {
        val target = targetId(ctx)
        if (target == 0L) return emptySet()
        if (!force && autoAnchorsFresh(ctx)) return autoAnchors(ctx)
        return try {
            val seeds = PlaylistApi.getArtistTopSongIds(target, AUTO_SEED_SONGS)
            val found = linkedSetOf<Long>()
            for (seed in seeds) {
                val songs = runCatching { PlaylistApi.getSimilarSongs(seed) }.getOrDefault(emptyList())
                for (s in songs) {
                    for (a in s.artists.orEmpty()) {
                        val aid = a.id
                        if (aid != null && aid != target) found += aid
                    }
                }
            }
            // 手动锚点永远不写回自动缓存；两者在读取处取并集。
            prefs(ctx).edit()
                .putString(KEY_AUTO_ANCHORS, found.joinToString(","))
                .putLong(KEY_AUTO_AT, System.currentTimeMillis())
                .apply()
            found
        } catch (e: Exception) {
            Log.e(TAG, "refreshAutoAnchors failed: ${e.message}")
            autoAnchors(ctx)
        }
    }
}
