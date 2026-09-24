/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · B：QQ 音乐业务接口（搜索 / 取链 / 歌词 / 资料）。
 */

package com.takahashirinta.ncrust.qq

import android.util.Log
import com.takahashirinta.ncrust.BuildConfig
import com.takahashirinta.ncrust.cache.OfflineKeys
import com.takahashirinta.ncrust.lyric.LrcLine
import com.takahashirinta.ncrust.lyric.LrcParser
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.player.SongUrlResult
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import org.json.JSONArray
import org.json.JSONObject

/**
 * QQ 音乐的业务接口（v2.1.0 · B）。
 *
 * 全部端点的请求形状都是**实测**得到的（2026-09，`musicu.fcg` 客户端协议），
 * 不是从文档推断：搜索用 web 身份、取链与歌词用客户端身份，两套身份各自在自己的
 * 场景下验证过（见 [QqClient.musicu] 的 `appIdentity` 参数）。
 *
 * ## 一条硬纪律：取不到 URL 就返回 null，**绝不返回坏链接**
 *
 * 这是从网易云侧继承的铁律（见 `SongUrlFetcher` 的 KDoc）：拿一个 HTML 错误页或
 * 空流给 ExoPlayer，表现是**无限缓冲**（用户看到的是「卡住」而不是「跳过」）。
 * QQ 侧还有一个类似的坑：无权限时服务端会给 30 秒试听片段 ——
 * 本实现**只接受服务端明确返回的 purl**，不做任何「猜一个 URL 试试」的兜底。
 */
object QqApi {

    private const val TAG = "QqApi"

    /** 取链失败时服务端给的业务码（实测匿名态）。 */
    const val RESULT_NEED_LOGIN_OR_VIP = 104003

    /** 取链失败的原因（只用于日志与 UI 提示，**不参与播放决策**）。 */
    data class UrlFailure(val resultCode: Int, val tips: String, val fileType: QqFileType?)

    @Volatile
    var lastUrlFailure: UrlFailure? = null
        private set

    // ---------------- 搜索 ----------------

    /**
     * 搜索歌曲。**旧版 GET 优先，`musicu.fcg` 兜底。**
     *
     * 顺序是有实测依据的（2026-09）：新版 `DoSearchForQQMusicMobile` 连续 6 次只成功 1 次
     * （其余 `code:2001`，疑似按出口 IP 限流），旧版 `client_search_cp` 3/3 稳定。
     * 两条通道的字段名在 `new_json=1` 下完全一致（`mid`/`file.media_mid`/`pay.pay_play`），
     * 所以共用同一套映射；哪条先成功用哪条的结果，只有第一条拿不到东西时才试第二条。
     */
    suspend fun searchSongs(keyword: String, limit: Int, page: Int = 1): List<SongItem> {
        if (keyword.isBlank()) return emptyList()
        val n = limit.coerceIn(1, 60)
        val p = page.coerceAtLeast(1)

        val legacy = runCatching {
            QqClient.legacyGet(QqRequests.legacySearchUrl(keyword, n, p))
        }.getOrNull()
        legacy?.let { json ->
            val songs = QqSongMapper.songsFromLegacySearch(json)
            if (songs.isNotEmpty()) {
                if (BuildConfig.DEBUG) Log.d(TAG, "search(legacy) '$keyword' -> ${songs.size}")
                return songs
            }
        }

        val songs = searchViaMusicu(keyword, n, p)
        if (BuildConfig.DEBUG) Log.d(TAG, "search(musicu) '$keyword' -> ${songs.size}")
        return songs
    }

    /**
     * `musicu.fcg` 通道。
     *
     * **信封 key 用 module 名、且不带 `comm`** —— 这是实测出来的配方：
     * 带 Web comm 会被判成通道不匹配而返回 0 条（`code:2001`）。
     * 这与本文件其它接口（vkey/歌词）必须带 comm 正好相反，别顺手统一。
     */
    private suspend fun searchViaMusicu(keyword: String, n: Int, p: Int): List<SongItem> {
        val envelope = QqRequests.searchEnvelope(keyword, n, p, newSearchId())
        val response = QqClient.musicuEnvelope(envelope, appIdentity = false) ?: return emptyList()
        return QqSongMapper.songsFromSearchResponse(JSONObject().put("req", response))
    }

    private const val SEARCH_MODULE = "music.search.SearchCgiService"

    private val searchCounter: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * 搜索 id：实测是 64 位量级的随机数（高 5 位 + 中段 + 当天毫秒）。
     * 服务端似乎不校验它，但照形状生成是零成本的保险；[searchCounter] 保证同一毫秒内
     * 连续调用也不会撞 id（撞了服务端可能返回上一页）。
     */
    private fun newSearchId(): String {
        val a = (1..20).random().toLong() * 18_014_398_509_481_984L
        val b = (0..4_194_304).random().toLong() * 4_294_967_296L
        val c = System.currentTimeMillis() % (24 * 60 * 60 * 1000L)
        return (a + b + c + searchCounter.incrementAndGet()).toString()
    }

    // ---------------- 取播放 URL ----------------

    /**
     * 取该曲在 [level] 档位下的可播放 URL。
     *
     * ## 两个实测踩出来的坑，直接决定了这里的写法
     *
     * 1. **服务端不会自动降级音质。** 请求 `M800` 而无权限时它返回空 `purl` +
     *    `result=104003`，**不会**顺手给你 128k。所以降级必须由客户端做，而且要把
     *    多个档位**一次性批量放进同一个 `filename[]`**（一次往返），再按优先级取
     *    第一个 `result==0 && purl` 非空的。逐个档位发请求的话，一首会员曲最坏要
     *    发 8 次往返才轮到能放的那一档。
     *
     * 2. **文件名只能用 `file.media_mid`，不能用 `song.mid`。** 两者实测经常不同，
     *    而**用错时服务端照样返回 purl 与 vkey、不报任何错** ——
     *    直到 CDN 下载才 `404 file not exist`，在播放器里表现为「缓冲一会儿然后报错」。
     *    正因如此，这里**不做「media_mid 失败就用 mid 再试一次」的兜底**：
     *    那个「兜底」拿到的 purl 是坏的，它只会把一次干净的失败换成一个诡异的播放错误。
     *    只有连 `media_id` 都没有（老数据、手工构造的条目）时才退回 `sourceId`。
     */
    suspend fun fetchPlayUrl(song: SongItem, level: String): SongUrlResult? {
        val songMid = song.sourceId ?: return null
        // 没有 media_id（v2.1.0 之前落盘的队列条目）时才退回 songmid
        val mediaMid = song.mediaId?.takeIf { it.isNotEmpty() } ?: songMid

        val types = QqQuality.attemptsFor(level)
        val info = requestVkeyBatch(songMid, mediaMid, types) ?: return null

        // 按**请求时的优先级**挑，而不是按响应顺序 —— 响应顺序是服务端的实现细节，
        // 依赖它等于把「用户选无损却拿到 128k」变成一个随机事件。
        for (fileType in types) {
            val entry = info[fileType] ?: continue
            val purl = entry.optString("purl").takeIf { it.isNotEmpty() }
            if (purl == null) {
                lastUrlFailure = UrlFailure(entry.optInt("result", 0), entry.optString("tips"), fileType)
                continue
            }
            val url = buildUrl(purl)
            val actualLevel = QqQuality.ncrustLevelOf(fileType)
            Log.i(TAG, "vkey ok: requested=$level actual=$actualLevel prefix=${fileType.prefix} mid=$mediaMid")
            lastUrlFailure = null
            // 与网易云侧同一个离线缓存 key 机制：挂上它，media3 的 SimpleCache
            // 才能把「同一首歌 + 同一档位」的轮换 URL 认成同一份缓存。
            return SongUrlResult(
                url = OfflineKeys.withKey(url, song.id, actualLevel),
                actualLevel = actualLevel,
                // QQ 不返回码率字段；留 0 表示「未知」，比编一个数字诚实。
                br = 0L,
                type = fileType.ext,
                songMaxLevel = null,
            )
        }
        Log.w(TAG, "no playable url for ${SourceIds.trackKey(MusicSource.QQMUSIC, song.id)} at level=$level")
        return null
    }

    /**
     * 一次请求把 [types] 里所有档位都问一遍，返回 `档位 → midurlinfo 条目`。
     *
     * 按响应条目自带的 `filename` 反查档位（[QqQuality.fileTypeOfFileName]），
     * 不依赖响应顺序与请求顺序一致。
     */
    private suspend fun requestVkeyBatch(
        songMid: String,
        mediaMid: String,
        types: List<QqFileType>,
    ): Map<QqFileType, JSONObject>? {
        if (types.isEmpty()) return null
        val request = QqRequests.vkey(
            songMid = songMid,
            mediaMid = mediaMid,
            types = types,
            uin = QqClient.uinForRequest(),
            guid = QqClient.guidForRequest(),
        )
        val response = QqClient.musicu(request, appIdentity = true) ?: return null
        val data = response.optJSONObject("data") ?: return null

        // CDN 前缀每次响应都可能不同（host 轮换），所以每次都更新。
        data.optJSONArray("sip")
            ?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.firstOrNull { it.isNotEmpty() } }
            ?.let { lastSip = it }

        val list = data.optJSONArray("midurlinfo") ?: return null
        val out = LinkedHashMap<QqFileType, JSONObject>()
        for (i in 0 until list.length()) {
            val entry = list.optJSONObject(i) ?: continue
            val type = QqQuality.fileTypeOfFileName(entry.optString("filename")) ?: continue
            out[type] = entry
        }
        return out
    }

    /**
     * 把 `purl` 拼成完整 URL：`<sip[0]><purl>`。
     *
     * ## `sip` 可能是空的（真机实测踩到，v2.1.0 hotfix 3）
     *
     * 实测三首免费曲目（《千与千寻》《城南花已开》《宫崎骏的夏天》）的 vkey 响应里
     * **`sip` 数组是空的**，但 `purl` 有值且 `result=0` —— 也就是「链是好的，只是没告诉你 CDN 域名」。
     * 早先的实现在这里直接返回 null，于是**本来能播的免费曲目被整个丢掉**
     * （表现是「点了没反应/跳歌」，而且因为它看起来像「无权限」，几乎无法排查）。
     *
     * 现在回落到固定的 CDN 域名。实测四个候选域名（http/https 各两个）都能返回
     * HTTP 200 + `audio/mpeg` + 真实 ID3 字节，所以统一用 `https`（不引 cleartext 问题）。
     */
    internal fun composeUrl(purl: String, sip: String?): String {
        if (purl.startsWith("http://") || purl.startsWith("https://")) return purl
        val host = sip?.takeIf { it.isNotEmpty() } ?: FALLBACK_CDN
        val prefix = if (host.endsWith("/")) host else "$host/"
        return prefix + purl.removePrefix("/")
    }

    private fun buildUrl(purl: String): String = composeUrl(purl, lastSip)

    /** `sip` 缺失时的兜底 CDN。实测 `http`/`https`、`ws`/`ws6`/`aqqmusic.tc` 四个域名都可用。 */
    private const val FALLBACK_CDN = "https://ws.stream.qqmusic.qq.com/"

    @Volatile
    private var lastSip: String? = null

    // ---------------- 歌词 ----------------

    /**
     * 一份 QQ 歌词包（v2.1.0 · D）。
     *
     * @property authoritative 服务端**明确**回答了这首歌的歌词情况（哪怕是「没有歌词」）。
     *   与「这次请求失败」必须分开：前者可以缓存、可以让 UI 显示「暂无歌词」，
     *   后者只能当作暂时拿不到、下次还要再试。这与网易云侧靠 `code == 200` 区分
     *   「权威空结果」是同一个道理（见 `PlayerViewModel` 里「只缓存 code==200 的结果」）。
     */
    data class LyricPack(
        val qrcLines: List<LrcLine>,
        val transLines: List<LrcLine>,
        val romaLines: List<LrcLine>,
        val authoritative: Boolean,
    ) {
        /** 有没有任何可用内容。 */
        val isEmpty: Boolean get() = qrcLines.isEmpty() && transLines.isEmpty() && romaLines.isEmpty()
    }

    suspend fun fetchLyric(song: SongItem): LyricPack? {
        val songMid = song.sourceId ?: return null
        val rawId = SourceIds.qqRawId(song.id) ?: 0L
        val request = QqRequests.lyric(songMid, rawId)
        val response = QqClient.musicu(request, appIdentity = true) ?: return null
        if (response.optInt("code", -1) != 0) {
            Log.w(TAG, "lyric code=${response.optInt("code", -1)} for mid=$songMid")
            return null
        }
        val data = response.optJSONObject("data") ?: return LyricPack(emptyList(), emptyList(), emptyList(), true)
        val crypt = data.optInt("crypt", 1) == 1

        // ⚠️ 实测：`qrc=0` 的歌**照样**加密，但解密出来是**行级 LRC**（不是 QRC 的 XML）。
        // 只按 QRC 解析的话这些歌会得到 0 行 —— 表现是「有歌词的歌显示暂无歌词」。
        val lyricText = decodeField(data.optString("lyric"), crypt)
        val qrc = QrcParser.parseXml(lyricText).ifEmpty { LrcParser.parse(lyricText.orEmpty()) }
        val roma = QrcParser.parseXml(decodeField(data.optString("roma"), crypt))
        val trans = LrcParser.parse(decodeField(data.optString("trans"), crypt).orEmpty())
            // 翻译轨里没有翻译的行会被服务端写成 `//`（实测），
            // 它不是歌词文本，留着只会在界面上多出一行斜杠。
            .filter { it.text.any { c -> c != '/' && !c.isWhitespace() } }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "lyric mid=$songMid qrc=${qrc.size} trans=${trans.size} roma=${roma.size}")
        }
        return LyricPack(qrc, trans, roma, authoritative = true)
    }

    /**
     * 一个歌词字段的解码：**先按密文解，解不开就当明文用**。
     *
     * 不依赖 `crypt` 标志位是因为实测它不可信：传 `crypt=0` 时服务端**照样**返回密文
     * （响应里 `crypt` 也回 0）。先用「能不能解出 zlib + UTF-8」来判，比信标志位稳。
     */
    private fun decodeField(value: String?, crypt: Boolean): String? {
        if (value.isNullOrEmpty()) return null
        QrcDecryptor.decrypt(value)?.let { return it }
        return if (crypt) null else value
    }

    /**
     * 账号的会员状态与音质权益。
     *
     * 走 `VipLogin.VipLoginInter` / `vip_login_base`：**实测匿名也能调用**（`code=0`），
     * 返回 `identity.vip`（0/1）、`identity.svip`、`identity.overdate`（到期日期字符串），
     * 以及 `music_lev_hq` / `music_lev_sq` / `music_lev_hires` / `music_lev_dolby`
     * 这几项**音质权益**。未登录时这些值都是 0/空 —— 这正是我们要的语义。
     *
     * ## 验证程度（如实标注）
     *
     * 匿名态的**结构**已实测；**登录态下 vip=1 的具体取值没有验证过**（本仓库没有
     * QQ 音乐账号）。因此本方法的结果**只用于界面显示与降级提示**，
     * 绝不参与「这首歌能不能放」的判断 —— 那个判断永远由服务端返回的 purl 决定
     * （见 [fetchPlayUrl]）。判断错了最坏是界面上的一个角标不对，不会凭空给或夺走播放权限。
     */
    suspend fun fetchProfile(): QqProfile? {
        val request = QqRequests.vip()
        val response = QqClient.musicu(request, appIdentity = true) ?: return null
        if (response.optInt("code", -1) != 0) return null
        val identity = response.optJSONObject("data")?.optJSONObject("identity") ?: return null
        val vip = identity.optInt("vip", 0)
        val svip = identity.optInt("svip", 0)
        return QqProfile(
            nick = null,
            uid = 0L,
            // 只区分「有没有会员」与原始档位，不把具体数字写死进业务判断
            // （见 QqProfile 的注释：腾讯加一档会员就会让写死的判断全错）。
            vipType = if (svip > 0) 2 else if (vip > 0) 1 else 0,
            vipExpireAt = parseOverdate(identity.optString("overdate")),
        )
    }

    /**
     * `overdate` 实测是**日期字符串**（形如 `2026-09-25`），不是时间戳。
     * 解析成秒级时间戳；解析不出返回 0（= 未知，[QqProfile.isVip] 会按「不过期」处理 ——
     * 服务端既然说了 vip=1，就不该因为我们看不懂日期而把它判成非会员）。
     */
    private fun parseOverdate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        // 已经是纯数字时间戳时直接用（腾讯侧字段格式历史上变过）
        text.toLongOrNull()?.let { return if (it > 100_000_000_000L) it / 1000L else it }
        return runCatching {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            fmt.parse(text)?.time?.div(1000L) ?: 0L
        }.getOrDefault(0L)
    }

    /**
     * 把副文本轨（翻译 / 音译）**按时间就近**贴到主轨的每一行上（v2.1.0 · D）。
     *
     * 为什么必须重写时间戳而不是原样交出：渲染层是按 `timeMs` **精确配对**副文本的
     * （`translatedLyrics.associateBy { timeMs }`）。QRC 主轨的时间戳与 `trans`（行级 LRC）
     * 的时间戳来自服务端的两份资产，实测并不逐行相同，原样交出会让译文一行都配不上。
     * 所以这里把选中的副文本行的 `timeMs` 改成**主轨那一行的时间戳**。
     *
     * 容差之外的副文本整行丢弃：宁可少一行译文，也不要把上一句的翻译贴到这一句上。
     */
    internal fun alignToMainLines(
        main: List<LrcLine>,
        sub: List<LrcLine>,
        toleranceMs: Long = 1500L,
    ): List<LrcLine> {
        if (main.isEmpty() || sub.isEmpty()) return emptyList()
        val sorted = sub.sortedBy { it.timeMs }
        val out = ArrayList<LrcLine>(main.size)
        var cursor = 0
        for (line in main) {
            // 单调游标：主轨与副轨都是按时间递增的，不需要每行都从头二分。
            while (cursor < sorted.size && sorted[cursor].timeMs < line.timeMs - toleranceMs) cursor++
            val candidate = sorted.getOrNull(cursor) ?: break
            if (kotlin.math.abs(candidate.timeMs - line.timeMs) <= toleranceMs) {
                out.add(candidate.copy(timeMs = line.timeMs))
            }
        }
        return out
    }

    private fun SongItem.trackKeyOrEmpty(): String =
        SourceIds.trackKey(MusicSource.QQMUSIC, id)

    // ---------------- 手机号验证码登录（v2.1.1） ----------------

    /**
     * 一次手机号登录尝试的结果。
     *
     * [cookie] 只在**成功且凭证完整**时非空；调用方负责落盘（[QqAuthStore.saveCookie]）——
     * 这一层刻意不碰存储，好让「登录协议」与「账号存储」各自可测、可替换。
     */
    data class PhoneLoginAttempt(
        val outcome: QqPhoneLogin.LoginOutcome,
        val cookie: String? = null,
    )

    /**
     * 一次「发短信验证码」尝试的结果。
     *
     * @property securityUrl `20276`（腾讯要求图形验证码）时**非空** —— 调用方要把它开在
     *   WebView 里让用户过验证。字段名 `securityURL` 来自实测响应骨架（[SendPhoneAuthCode]
     *   失败时它也在，只是空串），不是猜的。
     * @property errMsg 服务端的 `data.errMsg`，只用于诊断（界面不显示它，它是英文内部错误）。
     */
    data class SendCodeAttempt(
        val outcome: QqPhoneLogin.SendOutcome,
        val securityUrl: String? = null,
        val errMsg: String? = null,
    )

    /**
     * 发短信验证码。返回归类后的结局（见 [QqPhoneLogin.SendOutcome]）。
     *
     * 号码的**本地校验不在这里** —— 调用方应当先过 [QqPhoneLogin.normalizePhone]，
     * 格式不对就别发请求（省一次注定 `104400` 的往返）。
     *
     * @param captchaCookie 图形验证（`20276`）完成后从 WebView 拿回来的 cookie。
     *   风控的验证结果落在 cookie 上，不回传就等于没验证过 —— 下一次请求会**再次**被要求验证。
     */
    suspend fun sendPhoneAuthCode(phone: String, captchaCookie: String? = null): SendCodeAttempt {
        val response = QqClient.musicuLogin(QqRequests.sendPhoneAuthCode(phone), captchaCookie)
            ?: return SendCodeAttempt(QqPhoneLogin.SendOutcome.FAILED)
        val code = response.optInt("code", -1)
        val data = response.optJSONObject("data")
        val securityUrl = QqPhoneLogin.securityUrlOf(response)
        val errMsg = data?.optString("errMsg")?.takeIf { it.isNotEmpty() }
        // 20276 的现场只留**可诊断但不敏感**的部分：验证 URL 里可能带一次性 token，
        // 所以只记主机与长度，不把整条 URL 写进 logcat（与「不把凭证写进日志」同一条纪律）。
        val host = securityUrl?.let { runCatching { java.net.URL(it).host }.getOrNull() }
        Log.i(
            TAG,
            "SendPhoneAuthCode -> req.code=$code captchaHost=$host " +
                "captchaUrlLen=${securityUrl?.length ?: 0} errMsg=$errMsg",
        )
        return SendCodeAttempt(QqPhoneLogin.classifySend(code), securityUrl, errMsg)
    }

    /**
     * 用验证码换凭证。
     *
     * 成功（`req.code == 0`）时把 `req.data` 转成 cookie 串返回；**凭证不完整时按失败处理**
     * （见 [QqPhoneLogin.cookieFromCredential] 的注释：缺票据的 cookie 会让界面显示
     * 「已登录」而一取链就说没权限，比直接失败难排查得多）。
     */
    suspend fun loginWithPhoneCode(
        phone: String,
        code: String,
        captchaCookie: String? = null,
    ): PhoneLoginAttempt {
        val response = QqClient.musicuLogin(QqRequests.phoneLogin(phone, code), captchaCookie)
            ?: return PhoneLoginAttempt(QqPhoneLogin.LoginOutcome.FAILED)
        val reqCode = response.optInt("code", -1)
        val outcome = QqPhoneLogin.classifyLogin(reqCode)
        Log.i(TAG, "Login(phone) -> req.code=$reqCode outcome=$outcome")
        if (outcome != QqPhoneLogin.LoginOutcome.OK) return PhoneLoginAttempt(outcome)
        val cookie = QqPhoneLogin.cookieFromCredential(response.optJSONObject("data"))
        return if (cookie == null) {
            // 服务端说成功、凭证却不成形：不落盘，按失败报给用户（宁可不登，也不要半份登录态）
            Log.w(TAG, "Login(phone) 成功但凭证不完整，拒绝落盘")
            PhoneLoginAttempt(QqPhoneLogin.LoginOutcome.FAILED)
        } else {
            PhoneLoginAttempt(QqPhoneLogin.LoginOutcome.OK, cookie)
        }
    }
}
