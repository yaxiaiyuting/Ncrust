/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · B：QQ 音乐请求体的**纯构造**（无 IO、无 Android 依赖、JVM 可单测）。
 */

package com.takahashirinta.ncrust.qq

import org.json.JSONArray
import org.json.JSONObject

/**
 * `musicu.fcg` 请求体的构造（v2.1.0 · B）。
 *
 * 单独抽出来的理由不是「分层好看」，而是**这些字段名是实测出来的、写错了不会报错**：
 * - 取链的 `filename` / `songmid` / `songtype` 必须**等长并行数组**，少一个元素服务端就
 *   返回空 purl，看起来像「无权限」；
 * - 搜索信封的 key 必须是 **module 名**且**不能带 `comm`**，带了会被判通道不匹配、返回 0 条。
 *
 * 抽成纯函数之后，这两条都能在 JVM 单测里钉住，也能被「打真实服务端」的探针测试复用
 * （见 `QqLiveProbeTest`）。
 */
internal object QqRequests {

    const val VKEY_MODULE = "music.vkey.GetVkey"
    const val VKEY_METHOD = "UrlGetVkey"
    const val SEARCH_MODULE = "music.search.SearchCgiService"
    const val SEARCH_METHOD = "DoSearchForQQMusicMobile"
    const val LYRIC_MODULE = "music.musichallSong.PlayLyricInfo"
    const val LYRIC_METHOD = "GetPlayLyricInfo"
    const val VIP_MODULE = "VipLogin.VipLoginInter"
    const val VIP_METHOD = "vip_login_base"

    /**
     * 批量取链的请求体。**`filename` / `songmid` / `songtype` 三个数组必须等长**，
     * 第 i 个文件名对应第 i 个 songmid。
     *
     * @param mediaMid 拼文件名用的 mid —— **必须传 `file.media_mid`**，不是 `song.mid`。
     */
    fun vkey(
        songMid: String,
        mediaMid: String,
        types: List<QqFileType>,
        uin: String,
        guid: String,
    ): JSONObject {
        val filenames = JSONArray()
        val songmids = JSONArray()
        val songtypes = JSONArray()
        for (t in types) {
            filenames.put(QqQuality.fileNameFor(t, mediaMid))
            songmids.put(songMid)
            songtypes.put(0)
        }
        return JSONObject()
            .put("module", VKEY_MODULE)
            .put("method", VKEY_METHOD)
            .put(
                "param",
                JSONObject()
                    .put("uin", uin)
                    .put("filename", filenames)
                    .put("guid", guid)
                    .put("songmid", songmids)
                    .put("songtype", songtypes)
                    .put("ctx", 0),
            )
    }

    /** 搜索的 `param`。`searchid` 由调用方生成（它含时间与计数器，不该是纯函数）。 */
    fun searchParam(keyword: String, limit: Int, page: Int, searchId: String): JSONObject = JSONObject()
        .put("searchid", searchId)
        .put("query", keyword)
        .put("search_type", 0)
        .put("num_per_page", limit.coerceIn(1, 60))
        .put("page_num", page.coerceAtLeast(1))
        .put("highlight", true)
        .put("grp", true)
        .put("selectors", JSONObject())
        .put("vec_selectors", JSONArray())

    /**
     * 搜索的**完整信封**：key 是 module 名（不是 `"req"`），且**不含 `comm`** ——
     * 实测带 Web comm 会被判通道不匹配、返回 0 条。
     */
    fun searchEnvelope(keyword: String, limit: Int, page: Int, searchId: String): JSONObject =
        JSONObject().put(
            SEARCH_MODULE,
            JSONObject()
                .put("module", SEARCH_MODULE)
                .put("method", SEARCH_METHOD)
                .put("param", searchParam(keyword, limit, page, searchId)),
        )

    /** 旧版 GET 搜索的 URL（主通道，实测比 musicu 稳定）。 */
    fun legacySearchUrl(keyword: String, limit: Int, page: Int): String =
        "https://c.y.qq.com/soso/fcgi-bin/client_search_cp" +
            "?p=" + page.coerceAtLeast(1) +
            "&n=" + limit.coerceIn(1, 60) +
            "&w=" + java.net.URLEncoder.encode(keyword, "UTF-8") +
            "&format=json&cr=1&new_json=1"

    /** 歌词请求体。`songId` 是服务端数字 songid（不是我们合成的 id）。 */
    fun lyric(songMid: String, rawSongId: Long): JSONObject = JSONObject()
        .put("module", LYRIC_MODULE)
        .put("method", LYRIC_METHOD)
        .put(
            "param",
            JSONObject()
                .put("crypt", 1)
                .put("lrc_t", 0)
                .put("qrc", 1)
                .put("qrc_t", 0)
                .put("roma", 1)
                .put("roma_t", 0)
                .put("trans", 1)
                .put("trans_t", 0)
                .put("needSingingAnnotations", false)
                .put("type", 1)
                .put("songMid", songMid)
                .put("songId", rawSongId),
        )

    /** 会员状态请求体（匿名也可调，实测 `code=0`）。 */
    fun vip(): JSONObject = JSONObject()
        .put("module", VIP_MODULE)
        .put("method", VIP_METHOD)
        .put("param", JSONObject())

    // ---------------- 手机号验证码登录（v2.1.1） ----------------

    const val LOGIN_MODULE = "music.login.LoginServer"
    const val SEND_PHONE_CODE_METHOD = "SendPhoneAuthCode"
    const val LOGIN_METHOD = "Login"

    /**
     * 发短信验证码。实测（2026-09）：
     * - **`areaCode` 必须是字符串**：传数字 `86` 会被判成畸形请求（`req.code=10006`），
     *   传 `"86"` 才走到正常的参数校验路径（`104400` = 号码非法）；
     * - `tmeAppid` 会被服务端校验（传别的值回 `bad request: unknown tmeAppID`）；
     * - `comm` 需要 `tmeLoginMethod = 3`（见 [QqClient.musicuLogin]）。
     */
    fun sendPhoneAuthCode(phoneNo: String, areaCode: String = QqPhoneLogin.AREA_CODE_CN): JSONObject =
        JSONObject()
            .put("module", LOGIN_MODULE)
            .put("method", SEND_PHONE_CODE_METHOD)
            .put(
                "param",
                JSONObject()
                    .put("tmeAppid", "qqmusic")
                    .put("areaCode", areaCode)
                    .put("phoneNo", phoneNo),
            )

    /**
     * 用短信验证码换凭证。`loginMode = 1` 就是「手机验证码登录」这一种模式
     * （`2` 是 refresh_token 续期，见 PHASE0-QQMUSIC-API.md §4.5.2）。
     */
    fun phoneLogin(phoneNo: String, code: String): JSONObject = JSONObject()
        .put("module", LOGIN_MODULE)
        .put("method", LOGIN_METHOD)
        .put(
            "param",
            JSONObject()
                .put("code", code)
                .put("loginMode", 1)
                .put("phoneNo", phoneNo),
        )
}
