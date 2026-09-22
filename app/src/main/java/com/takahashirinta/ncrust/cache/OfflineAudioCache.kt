/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.cache

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import java.io.File

/**
 * 已播放音频流的本地缓存（v1.6.0 · D1 · Phase 1）。
 *
 * ## 定位（合规前提）
 *
 * 这**不是**「官方下载」，也不碰任何 DRM：它只是把**用户已经点播过、已经播放过的音频流**
 * 按 HTTP 语义落一份到本地，等价于 ExoPlayer 的 CacheDataSource，用于弱网/断网时避免重下。
 * 未播放过的歌不会产生任何本地文件；没有任何绕过会员/版权校验的路径（URL 仍然每次向服务端要，
 * 服务端不给就是不给）；离线可播范围**严格等于**「这台设备上真正播过的歌」。
 *
 * ## 三个实现决定
 *
 * 1. **放在 filesDir/offline/audio 而不是 cacheDir**（v1.5.0 调研结论）：系统/清理类 App
 *    会清 cacheDir；而「清除缓存」按钮只删 cacheDir/{WebView,http}，不会误删这里 ——
 *    离线音频是可预期保留的数据，不该被系统的常规缓存回收清掉。设置页的「缓存占用」里
 *    会把它一并统计并允许用户清除（[clear]）。
 * 2. **自定义 cache key**（[OfflineKeys]）：网易的播放 URL 每次取都变，media3 默认按 URL
 *    做 key 会导致缓存永不命中 —— 一首歌放十遍存十份。
 * 3. **FLAG_IGNORE_CACHE_ON_ERROR**：写缓存失败（空间不足 / 目录被占）绝不能影响播放，
 *    出错就退化成纯网络播放。
 *
 * 上限默认 512 MiB，可在 prefs 里改（`ncrust_settings` 的 `offline_cache_mb`）。
 * LRU 淘汰由 media3 的 [LeastRecentlyUsedCacheEvictor] 负责。
 */
object OfflineAudioCache {

    /** 缓存目录名（相对 filesDir）。 */
    private const val DIR = "offline/audio"

    /** 默认上限：512 MiB。 */
    const val DEFAULT_MAX_BYTES = 512L * 1024 * 1024

    private const val PREFS = "ncrust_settings"
    private const val KEY_MAX_MB = "offline_cache_mb"

    @Volatile
    private var cache: SimpleCache? = null

    /** 读取用户设置的上限（MB）。非法值一律回落默认，避免 prefs 被写坏时把缓存设成 0。 */
    fun maxBytes(context: Context): Long {
        val mb = runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_MAX_MB, (DEFAULT_MAX_BYTES / 1024 / 1024).toInt())
        }.getOrDefault((DEFAULT_MAX_BYTES / 1024 / 1024).toInt())
        return if (mb in 64..8192) mb.toLong() * 1024 * 1024 else DEFAULT_MAX_BYTES
    }

    /** 进程内单例。SimpleCache 独占目录，必须只建一份。 */
    fun get(context: Context): SimpleCache {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val app = context.applicationContext
            val dir = File(app.filesDir, DIR)
            val built = SimpleCache(
                dir,
                LeastRecentlyUsedCacheEvictor(maxBytes(app)),
                StandaloneDatabaseProvider(app),
            )
            cache = built
            return built
        }
    }

    /** 给 ExoPlayer 用的数据源工厂：命中本地的片段不再走网络。 */
    fun dataSourceFactory(context: Context): DataSource.Factory {
        val app = context.applicationContext
        val c = get(app)
        return CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(app))
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory().setCache(c).setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)
            )
            .setCacheKeyFactory { spec -> OfflineKeys.keyOf(spec.uri.toString()) ?: spec.uri.toString() }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** 缓存里是否已有这个 key（离线可播的判据）。 */
    fun contains(context: Context, key: String): Boolean =
        runCatching { get(context).keys.contains(key) }.getOrDefault(false)

    /** 已用字节数。 */
    fun sizeBytes(context: Context): Long =
        runCatching { get(context).cacheSpace }.getOrDefault(0L)

    /**
     * 清空音频缓存。播放中的歌曲正在读的片段被删掉不会崩 —— CacheDataSource 会回源网络。
     * 同时清掉离线 URL 清单，否则会留下指向已删缓存的死条目。
     */
    fun clear(context: Context) {
        val app = context.applicationContext
        runCatching { get(app).keys.toList().forEach { get(app).removeResource(it) } }
        OfflineUrlStore.clear(app)
    }
}
