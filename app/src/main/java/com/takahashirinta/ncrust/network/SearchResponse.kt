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
    /**
     * 平台侧**媒体文件** ID（v2.1.0 · B）：QQ 音乐的 `file.media_mid`。
     *
     * 为什么它与 [sourceId] 必须分开：QQ 的播放 URL 文件名按 **media_mid** 拼
     * （`<音质前缀><media_mid>.<扩展名>`），而实测同一首歌这两个值经常不同
     * （《晴天》`mid=0039MnYb0qxYhV` 而 `media_mid=003Qui1q2u1Zho`）。
     * 用错一个就会静默拿不到 URL（服务端只回空 purl，不报错）。
     * 网易云一侧恒为 null。可空 + 默认值，理由同 [source]。
     */
    @SerializedName("media_id") val mediaId: String? = null,
    /**
     * 网易云的付费类型原始值（v2.1.4）。**只用于派生「播放是否需要会员」，不参与取链。**
     *
     * 实测语义（2026-09，登录态，cloudsearch/pc）：
     * - `0` = 免费；`8` = 免费播放但**高音质**需会员（播放本身不受限）；
     * - `1` = VIP 专享；`4` = 数字专辑（买了才能听）。
     *
     * 与 [memberOnly] 一样可空 + 有默认值：它会跟着 Gson 进队列持久化，
     * 而 Gson 走 Unsafe 反序列化、不调用构造函数，老队列 JSON 里没有这个 key。
     * 判定本身在 [com.takahashirinta.ncrust.search.TrackAccess] 一处收敛，这里只存原始值。
     */
    @SerializedName("fee") val fee: Int? = null,
    /**
     * **播放是否需要会员**（v2.1.4）。`true` = 会员专享，`false` = 免费可播，
     * `null` = 服务端没给判据或读不懂（见 [com.takahashirinta.ncrust.search.TrackAccess]）。
     *
     * 与 [fee] 的分工：QQ 音乐的判据是布尔（`pay.pay_play == 1`），映射时就能确定，
     * 直接写在这里；网易云的判据是 [fee] 那个整数，由搜索聚合层派生。
     * **null 的语义是「不知道」**，不是「免费」—— 排序把「不知道」与「免费」同组靠后，
     * 绝不把它当会员专享往前推。
     */
    @SerializedName("member_only") val memberOnly: Boolean? = null,
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