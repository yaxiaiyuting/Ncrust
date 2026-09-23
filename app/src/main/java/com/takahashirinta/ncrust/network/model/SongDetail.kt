package com.takahashirinta.ncrust.network.model

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

@Immutable
data class ArtistItem(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("name") val name: String
)

@Immutable
data class AlbumItem(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("name") val name: String?,
    @SerializedName("picUrl") val picUrl: String?
)

data class SongDetailResponse(
    @SerializedName("songs") val songs: List<SongDetail>
)

data class SongDetail(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("ar") val artists: List<ArtistItem>,
    @SerializedName("al") val album: AlbumItem,
    @SerializedName("dt") val duration: Long,
    @SerializedName("no") val trackNumber: Int?,
    @SerializedName("mv") val mvId: Long?
)

data class LyricResponse(
    @SerializedName("code") val code: Int = 200,
    @SerializedName("lrc") val lrc: LyricContent?,
    @SerializedName("tlyric") val tlyric: LyricContent?,
    // v1.5.0 · B：逐字歌词（只在请求带 yv=-1 且该曲挂了逐字资产时才有这个 key）。
    @SerializedName("yrc") val yrc: LyricContent? = null,
    // v1.9.2：音译轨（罗马音/粤拼）。实测它与 rv 参数无关 —— rv=0 与 rv=-1 对同一首歌返回
    // **逐字节相同**的 body（4/4 首实测，含中/韩/英各一首），所以本版不动请求参数，
    // 只把响应里本来就有、此前一直没解析的字段接上。可空 + 默认值，缺 key 时等于「没有音译轨」。
    @SerializedName("romalrc") val romalrc: LyricContent? = null
)

data class LyricContent(
    @SerializedName("lyric") val lyric: String?
)