/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · A：网易云音源的 Provider 实现。
 */

package com.takahashirinta.ncrust.source

import android.util.Log
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.player.SongUrlFetcher
import com.takahashirinta.ncrust.player.SongUrlResult

/**
 * 网易云音源（v2.1.0 · A）。
 *
 * **本类只做转发，不含任何新逻辑** —— 这是 v2.1.0 最重要的一条纪律：
 * 抽象层的引入不允许改变既有网易云功能的行为。取链仍走 [SongUrlFetcher.fetch]
 * （8 档降级阶梯、FLAC 设备门控、离线缓存 key 挂载、降级原因判定全在里面），
 * 搜索仍走 [RetrofitClient.api] 的 `cloudsearch/pc`，详情仍走 [PlaylistApi.getSongsByIds]。
 *
 * 这么薄的转发看起来「多了一层」，但它买到的是：调用方不必再知道
 * 「网易云的取链函数在 player 包里、搜索在 network 包里」这种位置知识，
 * 也让 QQ 音乐的实现有一个必须对齐的契约（见 [MusicSourceProvider]）。
 */
object NeteaseSourceProvider : MusicSourceProvider {

    private const val TAG = "NeteaseSource"

    override val source: MusicSource = MusicSource.NETEASE

    /** cookie 非空即视为已登录 —— 与 v2.0.2 的判据一致（不做真实校验请求）。 */
    override val isLoggedIn: Boolean
        get() = !RetrofitClient.getCookie().isNullOrBlank()

    override suspend fun searchSongs(keyword: String, limit: Int): List<SongItem> =
        runCatching {
            RetrofitClient.api.search(keyword = keyword, type = 1, limit = limit)
                .result?.songs.orEmpty()
        }.onFailure { Log.w(TAG, "search failed", it) }
            .getOrDefault(emptyList())

    override suspend fun resolveUrl(song: SongItem, level: String): SongUrlResult? =
        SongUrlFetcher.fetch(song.id, level)

    /**
     * 元数据补全。`getSongsByIds` 内部走 `/eapi/v3/song/detail`，
     * 返回的 [SongItem] 天然没有 source 字段（= 网易云），与入参一致。
     */
    override suspend fun songDetail(song: SongItem): SongItem? =
        runCatching { PlaylistApi.getSongsByIds(listOf(song.id)).firstOrNull() }
            .onFailure { Log.w(TAG, "songDetail failed for id=${song.id}", it) }
            .getOrNull()
}
