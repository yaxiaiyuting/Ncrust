/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明：
 *   - Bug1：公开 deviceSupportsFlac / isFlacTier，供设置页对 FLAC 档位给出
 *     「本机不支持该档位，将自动降级」提示，不再静默降档。
 *   - B2：FLAC 门控由「仅平台解码器」改为「平台解码器 或 随包的 FFmpeg 扩展」，
 *     使 API 24–26（Android 7.0/7.1）也能真正播放无损。 */

package com.takahashirinta.ncrust.player

import android.media.MediaCodecList
import android.os.Build
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import android.util.Log
import com.takahashirinta.ncrust.BuildConfig
import com.takahashirinta.ncrust.network.ClientIdentity
import com.takahashirinta.ncrust.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * [actualLevel] 是服务端返回的 **level 字符串**，[br] / [type] 才是实际文件参数。
 * A1 起两者一起带出来：实测存在 granted=lossless 但 br 仍是 Hi-Res 值（1,685,762）的情况 ——
 * 只看 level 字符串会把"其实在播 Hi-Res"误判成降级（A3 据此重写降级判定）。
 */
data class SongUrlResult(
    val url: String,
    val actualLevel: String,
    val br: Long = 0L,
    val type: String = "",
)

object SongUrlFetcher {
    private const val TAG = "SongUrlFetcher"
    private const val SONG_URL_PATH = "/eapi/song/enhance/player/url/v1"

    private val FLAC_TIERS = setOf("lossless", "hires", "jyeffect", "jymaster")

    /**
     * 平台自带的 FLAC **解码**器（API 27+ 才有）。
     *
     * 注意 API 24–26（Android 7.0/7.1）的系统里 audio/flac 只有
     * OMX.google.flac.encoder —— 只有编码器，没有解码器，因此这些系统上
     * 拿到 flac URL 也播不出声（表现为「有进度没声音」）。
     */
    private val platformFlacDecoder: Boolean by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            false
        } else {
            runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                    !info.isEncoder && info.supportedTypes.any { it.equals("audio/flac", ignoreCase = true) }
                }
            }.getOrDefault(false)
        }
    }

    /**
     * 随包分发的 FFmpeg 软件解码扩展（org.jellyfin.media3:media3-ffmpeg-decoder，GPL-3.0）。
     * isAvailable() 只在对应 ABI 的 libffmpegJNI.so 真正加载成功时返回 true。
     */
    private val ffmpegExtensionAvailable: Boolean by lazy {
        runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)
    }

    /**
     * 本机能否真正解码 FLAC：**平台解码器或 FFmpeg 扩展，有其一即可**。
     * 因此 API 24–26 也能走无损档位，不再被静默跳过。
     */
    private val deviceCanDecodeFlac: Boolean by lazy {
        platformFlacDecoder || ffmpegExtensionAvailable
    }

    init {
        // 实测无损是否真的可解时，这一行是唯一权威依据（尤其 API 24–26 与各种 ROM）。
        Log.i(
            TAG,
            "FLAC capability: platformDecoder=$platformFlacDecoder " +
                "ffmpegExtension=$ffmpegExtensionAvailable " +
                "ffmpegVersion=" + runCatching { FfmpegLibrary.getVersion() }.getOrDefault("n/a") +
                " => canDecodeFlac=$deviceCanDecodeFlac"
        )
    }

    /**
     * 本机是否具备系统 FLAC 解码器（API < 27 没有）。
     * 设置页据此在用户选中 FLAC 档位时提示「本机不支持该档位，将自动降级」（Bug1-C）。
     */
    val deviceSupportsFlac: Boolean get() = deviceCanDecodeFlac

    /** [level] 是否属于必须依赖 FLAC 解码器的档位。 */
    fun isFlacTier(level: String): Boolean = level in FLAC_TIERS

    // Returns null when no level yields a playable URL (e.g. VIP-only song without a
    // subscription, or no valid session). Callers must skip the song instead of playing.
    suspend fun fetch(songId: Long, level: String = "lossless"): SongUrlResult? = withContext(Dispatchers.IO) {
        // Try the requested level first, then fall back down the quality ladder.
        val fallbackLevels = when (level) {
            "dolby"    -> listOf("dolby", "hires", "lossless", "exhigh", "higher", "standard")
            "jymaster" -> listOf("jymaster", "hires", "lossless", "exhigh", "higher", "standard")
            "jyeffect" -> listOf("jyeffect", "lossless", "exhigh", "higher", "standard")
            "hires"    -> listOf("hires", "lossless", "exhigh", "higher", "standard")
            "lossless" -> listOf("lossless", "exhigh", "higher", "standard")
            "exhigh"   -> listOf("exhigh", "higher", "standard")
            "higher"   -> listOf("higher", "standard")
            "standard" -> listOf("standard")
            else       -> listOf(level, "lossless", "exhigh", "higher", "standard")
        }

        for (tryLevel in fallbackLevels) {
            // 设备解不了 FLAC 时,flac 档位取来也是无声,直接跳到 mp3 档。
            if (tryLevel in FLAC_TIERS && !deviceCanDecodeFlac) continue
            try {
                val payload = buildPayload(songId, tryLevel)
                // A1：服务端按 **Cookie 里的客户端身份** 判定音质上限。只带 MUSIC_U/__csrf 时，
                // 请求 hires 会被静默封顶成 lossless（code 仍是 200）；追加 os/appver 后同一请求
                // 才真正返回 hires。实测见仓库外 tools/probe-quality.py。
                val response = RetrofitClient.eapiPost(
                    SONG_URL_PATH,
                    payload,
                    useInterface = true,
                    extraCookie = ClientIdentity.extraCookieFor(RetrofitClient.getCookie())
                )
                val body = response.body?.string() ?: continue
                Log.d(TAG, "eapi response ($tryLevel): $body")
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: continue
                if (data.length() > 0) {
                    val obj = data.getJSONObject(0)
                    val code = obj.optInt("code", 200)
                    val url = obj.optString("url")
                    val actualLevel = obj.optString("level", tryLevel)
                    // type/br 用于诊断:type 是实际容器(mp3/flac/mp4),br 是码率,
                    // 出现"有进度没声音"时靠这两项判断拿到的到底是不是预期的文件。
                    val type = obj.optString("type", "")
                    val br = obj.optLong("br", 0L)
                    // code != 200 means the level is unavailable (404 = no resource / not entitled).
                    // There is no point accepting such an entry, so advance down the ladder.
                    if (code != 200 || url.isNullOrEmpty()) {
                        logAttempt(tryLevel, actualLevel, br, type, code, accepted = false)
                        continue
                    }
                    logAttempt(tryLevel, actualLevel, br, type, code, accepted = true)
                    return@withContext SongUrlResult(url, actualLevel, br, type)
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetch failed for level=$tryLevel", e)
            }
        }

        // 所有档位都取不到可播放的 URL。绝不要用 https://music.163.com/song/media/outer/url?id=X.mp3
        // 兜底 —— 那个旧端点对无版权/需会员的歌曲返回 302→404 的 HTML 页面，ExoPlayer 拿到非音频流
        // 会无限缓冲（"卡住"）。这里直接返回 null，让上层跳歌而不是播放坏链接。
        Log.e(TAG, "no playable url for songId=$songId at any level")
        null
    }

    /**
     * A1：把「请求档位 / 服务端给的 level 字符串 / 实际文件参数」分开打出来，供真机实测比对
     * 与 A3 的降级判定使用 —— 实测存在 granted=lossless 但 br 仍是 Hi-Res 值的情况。
     * debug 额外带 deviceId 前 4 位（确认身份是否真的生效），release 只留必要字段。
     */
    private fun logAttempt(
        requested: String,
        granted: String,
        br: Long,
        type: String,
        code: Int,
        accepted: Boolean,
    ) {
        val detail = "quality attempt: requested=$requested granted=$granted br=$br type=$type code=$code accepted=$accepted"
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "$detail deviceId=${ClientIdentity.deviceId().take(4)}…")
        } else {
            Log.i(TAG, detail)
        }
    }

    private fun buildPayload(songId: Long, level: String): Map<String, String> {
        // 官方客户端 header 字段,声明 PC 端并携带随机 requestId,保证杜比等音质返回正常码率。
        // A1：body header 与 Cookie 里的身份同源。实测解锁只看 **Cookie**，body 这份对音质
        // 无用；但两处不一致会形成自相矛盾的客户端指纹（旧值 appver="" / deviceId="pyncm!"），
        // 因此一并填成同一组值。
        val config = JSONObject()
            .put("os", ClientIdentity.OS)
            .put("appver", ClientIdentity.APPVER)
            .put("osver", ClientIdentity.OSVER)
            .put("deviceId", ClientIdentity.deviceId())
            .put("requestId", (20_000_000..30_000_000).random().toString())

        val base = mutableMapOf(
            "ids" to JSONArray().put(songId).toString(),
            "level" to level,
            "header" to config.toString(),
        )
        // 杜比全景声必须以 mp4 容器输出(EAC3),其余音质用 FLAC。
        base["encodeType"] = if (level == "dolby") "mp4" else "flac"
        return base
    }
}
