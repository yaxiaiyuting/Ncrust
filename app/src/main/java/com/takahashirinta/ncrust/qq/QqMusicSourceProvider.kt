/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · B/C：QQ 音乐音源的 Provider 实现。
 */

package com.takahashirinta.ncrust.qq

import android.content.Context
import android.util.Log
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.player.SongUrlResult
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.MusicSourceProvider
import com.takahashirinta.ncrust.source.SourceRouter

/**
 * QQ 音乐音源（v2.1.0 · B）。
 *
 * 与 [com.takahashirinta.ncrust.source.NeteaseSourceProvider] 的差别不只是换个 API：
 * QQ 这一侧**没有云歌单/收藏/播放上报**的对应物（本版也不做，见下），
 * 它提供的是「能搜、能放、能出歌词」这条最小可用链。
 *
 * ## 为什么 [isLoggedIn] 为 false 时**不**把自己从路由表里摘掉
 *
 * 摘掉的话，未登录用户搜索 QQ 音乐只能得到空结果，而**原因是「未登录」还是「搜不到」
 * 在 UI 上完全无法区分**。保留注册、让请求照发：
 * 实测匿名态搜索与歌词都能拿到数据（只有取链会被拒，`result=104003`），
 * 所以未登录用户至少能搜到歌、看到歌词，点播放时才提示需要登录 —— 这是更好的降级。
 *
 * ## 本版**不做**的事（避免误以为已支持）
 *
 * - 不把 QQ 歌曲加进网易云歌单/收藏（那需要「本地歌单」这个尚不存在的概念，
 *   而且网易云的歌单写接口会拒绝外部曲目）；
 * - 不把 QQ 的播放行为上报给任何一方（QQ 侧没有对应的 webLog 机制，也不该伪造）。
 */
object QqMusicSourceProvider : MusicSourceProvider {

    private const val TAG = "QqMusicSource"

    override val source: MusicSource = MusicSource.QQMUSIC

    override val isLoggedIn: Boolean get() = QqClient.isLoggedIn()

    override suspend fun searchSongs(keyword: String, limit: Int): List<SongItem> =
        runCatching { QqApi.searchSongs(keyword, limit) }
            .onFailure { Log.w(TAG, "search failed", it) }
            .getOrDefault(emptyList())

    override suspend fun resolveUrl(song: SongItem, level: String): SongUrlResult? =
        runCatching { QqApi.fetchPlayUrl(song, level) }
            .onFailure { Log.w(TAG, "resolveUrl failed for id=${song.id}", it) }
            .getOrNull()

    /**
     * 元数据补全：QQ 侧没有「按 id 批量取详情」的轻量端点（取详情要走完整曲库接口），
     * 而队列里的条目本来就已经带全了元数据。所以这里**返回原对象**而不是发一次请求 ——
     * 这个契约在接口里允许（「失败返回 null，调用方保留原对象即可」），
     * 但返回原对象更省一次往返，也不会让调用方误以为拿到了新数据。
     */
    override suspend fun songDetail(song: SongItem): SongItem? = song.takeIf { it.sourceId != null }

    /**
     * 进程启动时接线：注册 Provider（音源路由认得 QQ）并给 [QqClient] 一个 application context。
     *
     * 与 `RetrofitClient.init(this)` 并列调用。**幂等**，重复调用无副作用。
     */
    fun install(context: Context) {
        QqClient.init(context)
        SourceRouter.register(this)
    }
}
