package com.takahashirinta.ncrust.network

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.network.model.AlbumItem

data class SearchResponse(
    @SerializedName("result") val result: SearchResult?
)

data class SearchResult(
    @SerializedName("songs") val songs: List<SongItem>?,
    @SerializedName("albums") val albums: List<AlbumSearchItem>?,
    @SerializedName("artists") val artists: List<ArtistSearchItem>?
)

// @Immutable：Compose 视 List<T> 为 unstable 参数，会强制每次重组重新比较；
// 显式标注后 Compose 在参数不变时可跳过整个 SongCard 子树的重组。
// 前提是所有字段全 val + 值语义——本 data class 已满足。
@Immutable
data class SongItem(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("ar") val artists: List<ArtistItem>?,
    @SerializedName("al") val album: AlbumItem?,
    @SerializedName("dt") val duration: Long?,
    /**
     * 音源 key（v2.1.0 · A）：`"netease"` / `"qqmusic"`。**可空且默认 null 是硬要求** ——
     * 队列是 Gson 持久化在 `ncrust_playback_state` 里的，而 Gson 走 Unsafe 反序列化、
     * 不调用构造函数：v2.1.0 之前写入的队列 JSON 里没有这个 key，读到就是 null。
     * null 的语义是「v2.1.0 之前的数据 ⇒ 网易云」（见 MusicSource.fromKey）。
     *
     * 判定请用 source 包里的扩展属性 `SongItem.musicSource`，不要直接读这个字符串。
     */
    @SerializedName("source") val source: String? = null,
    /**
     * 平台侧字符串 ID（v2.1.0 · A）：QQ 音乐的 `songmid`（形如 `0039MnYb0qxYhV`）。
     *
     * 为什么必须有它：QQ 音乐的取链接口要按 **mid** 拼文件名（`<音质前缀><mid>.<扩展名>`），
     * 数字 songid 只能定位歌曲、不能取链。网易云一侧恒为 null（它的 [id] 就够用）。
     * 同样可空 + 默认值，理由与 [source] 相同。
     */
    @SerializedName("mid") val sourceId: String? = null,
)

@Immutable
data class AlbumSearchItem(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("picUrl") val picUrl: String?,
    @SerializedName("artist") val artist: ArtistItem?,
    @SerializedName("publishTime") val publishTime: Long?,
    @SerializedName("size") val size: Int?,
    @SerializedName("company") val company: String?
)

@Immutable
data class ArtistSearchItem(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("picUrl") val picUrl: String?,
    @SerializedName("picId") val picId: Long?,
    @SerializedName("albumSize") val albumSize: Int?,
    @SerializedName("musicSize") val musicSize: Int?,
    @SerializedName("alias") val alias: List<String>?,
    @SerializedName("trans") val trans: String?
)