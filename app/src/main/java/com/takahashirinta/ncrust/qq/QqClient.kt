/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · B：QQ 音乐的 HTTP 通道（独立于网易云那一套）。
 */

package com.takahashirinta.ncrust.qq

import android.content.Context
import android.util.Log
import com.takahashirinta.ncrust.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * QQ 音乐的 HTTP 通道（v2.1.0 · B）。
 *
 * ## 为什么不复用 [com.takahashirinta.ncrust.network.RetrofitClient]
 *
 * 网易云那条链路里塞满了只对网易云有意义的东西：eapi 的 AES 签名与
 * `/eapi/`→`/api/` 路径重写、weapi 的双层 AES + RSA、CSRF token、PC 身份 Cookie。
 * 把那条链路「参数化」成一个通用客户端，等于让两套互不相干的协议互相污染 ——
 * 任何一边改签名逻辑都可能悄悄改到另一边的行为。所以 QQ 自己一条 OkHttp 通道，
 * **只共享 OkHttp 这个库，不共享它的配置**。
 *
 * ## 协议：`musicu.fcg` 客户端协议（**免签**）
 *
 * 所有业务都在一个端点：
 *
 * ```
 * POST https://u.y.qq.com/cgi-bin/musicu.fcg
 * {"comm": {…客户端身份…}, "req": {"module": "…", "method": "…", "param": {…}}}
 * ```
 *
 * 实测（2026-09）这个协议**不需要任何签名**（社区里流传的 `zzc_sign` 是给另一套
 * 需要签名的 web 接口用的），只要 `comm` 里的身份字段齐全即可 —— 匿名也能搜到歌、
 * 拿到歌词，只是取不到播放 URL（服务端返回 `purl: ""` + `result: 104003`，即
 * 「需要登录/会员」）。
 *
 * ## 与网易云 cookie 的关系：**零关系**
 *
 * QQ 的 cookie 存在 [QqAuthStore]（`ncrust_qq_prefs`），与网易云的 `ncrust_prefs`
 * 完全隔离。登出一家不影响另一家。
 */
object QqClient {

    private const val TAG = "QqClient"

    private const val MUSICU_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"

    /** 与实测一致的客户端 UA。服务端会按它决定返回哪套响应形状。 */
    private const val UA_APP = "QQMusic 14090008(android 10)"

    /** 搜索用 web 身份（实测这套身份 + 浏览器 UA 能拿到完整搜索结果）。 */
    private const val UA_WEB =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private var appContext: Context? = null

    /**
     * 搜索专用通道（v2.1.0 · hotfix 3）：**超时收紧到秒级**。
     *
     * 原因：搜索是交互式的，而取链/歌词不是。共用那条 15/20 秒的客户端时，
     * 一旦 QQ 侧慢或不可达，搜索界面就要陪着等十几秒（真机反馈「一直转圈」）。
     * 另外 `withTimeoutOrNull` **取消不了** 阻塞中的 `execute()` ——
     * 协程层面的超时只能让 UI 不再等，真正的连接要靠 OkHttp 自己的超时收掉。
     */
    private val searchHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 与 `RetrofitClient.init(this)` 同样的接入方式：进程启动时给一次 application context。
     * 没 init 时 [musicu] 会直接返回 null（而不是崩）—— 后台组件可能在 init 之前被拉起。
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 当前是否已登录（只读本地 cookie，不发请求）。 */
    fun isLoggedIn(): Boolean = appContext?.let { QqAuthStore.isLoggedIn(it) } ?: false

    /** `param.uin`：未登录时用 `"0"`（实测匿名可用）。 */
    fun uinForRequest(): String = appContext?.let { uinOf(it) } ?: "0"

    /** `param.guid`：本机自生成的设备标识派生值。 */
    fun guidForRequest(): String = appContext?.let { QqIdentity.guid(it) } ?: "10000"

    /**
     * 发一次 `musicu.fcg` 请求，返回**响应里 `req` 那个对象**（业务数据都在它里面）。
     *
     * 失败一律返回 null：调用方（Provider）的契约就是「拿不到就返回 null」，
     * 不在这里抛异常，也不做重试 —— 播放链路上的失败必须快速落到「跳歌」。
     *
     * @param appIdentity true 用客户端身份（vkey/歌词，实测有效）；
     *   false 用 web 身份（搜索，实测有效）。两套都在各自场景下实测过，
     *   不混用是因为服务端会按身份切换响应形状。
     */
    suspend fun musicu(
        request: JSONObject,
        appIdentity: Boolean,
    ): JSONObject? = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext null
        val body = JSONObject()
            .put("comm", if (appIdentity) appComm(ctx) else webComm(ctx))
            .put("req", request)
        execute(ctx, body, request.optString("module"), appIdentity)
    }

    /**
     * 自定义信封结构的调用（v2.1.0 · B）。
     *
     * 有些接口的信封 key **不是 `req` 而是 module 名**，而且**不能带 `comm`** ——
     * 搜索就是这种（带 comm 会被判通道不匹配、返回 0 条）。把这件事做成显式入口，
     * 而不是让调用方自己拼 body 再偷偷发请求。
     */
    suspend fun musicuEnvelope(envelope: JSONObject, appIdentity: Boolean): JSONObject? =
        withContext(Dispatchers.IO) {
            val ctx = appContext ?: return@withContext null
            if (appIdentity) envelope.put("comm", appComm(ctx))
            val module = envelope.keys().asSequence()
                .filter { it != "comm" }
                .mapNotNull { envelope.optJSONObject(it)?.optString("module") }
                .firstOrNull().orEmpty()
            execute(ctx, envelope, module, appIdentity = true)
        }

    /**
     * 登录专用通道（v2.1.1）：**客户端身份 + 登录方式标记 + 不带 cookie**。
     *
     * 三点都与上一条通道不同，每一点都有实测依据（`tools/probe-qq-phone-login.py`）：
     *
     * 1. `comm.tmeLoginMethod = 3`（手机验证码）。**这个是必需的**：`Login` 带着它回
     *    `req.code=1000`（走到业务层），去掉它直接回 `104400`（请求被拒）。
     * 2. `comm.tmeLoginType = 0`（手机号）。实测带不带都不影响 `Login` 的失败码，
     *    但它是官方信封的一部分，照带。
     * 3. **不发 Cookie**（除非显式传 [extraCookie]）。登录是在「还没有身份」的前提下发生的，
     *    把一个可能已经过期的旧 cookie 一起发过去，只会给服务端一个把这次登录绑到旧身份上的机会。
     *
     * @param extraCookie v2.1.1：图形验证码（`20276`）在 WebView 里完成后拿到的 cookie。
     *   风控要求的验证是**会话级**的，验证结果落在 cookie 上；不带回来等于白验证一次，
     *   下一次 `SendPhoneAuthCode` 会再次被要求验证 —— 用户看到的就是「验证完了还是要验证」。
     */
    suspend fun musicuLogin(request: JSONObject, extraCookie: String? = null): JSONObject? =
        withContext(Dispatchers.IO) {
            val ctx = appContext ?: return@withContext null
            val body = JSONObject()
                .put(
                    "comm",
                    appComm(ctx)
                        .put("tmeLoginMethod", LOGIN_METHOD_PHONE_CODE)
                        .put("tmeLoginType", LOGIN_TYPE_PHONE),
                )
                .put("req", request)
            execute(
                ctx,
                body,
                request.optString("module"),
                appIdentity = true,
                // 有验证 cookie 就带上；没有就发裸请求（实测过的形状）。
                sendCookie = !extraCookie.isNullOrEmpty(),
                overrideCookie = extraCookie,
            )
        }

    /** `comm.tmeLoginMethod`：3 = 手机短信验证码（实测 `Login` 必须要它）。 */
    private const val LOGIN_METHOD_PHONE_CODE = 3

    /** `comm.tmeLoginType`：0 = 手机号（1 = 微信，2 = QQ）。 */
    private const val LOGIN_TYPE_PHONE = 0

    private fun execute(
        ctx: Context,
        body: JSONObject,
        module: String,
        appIdentity: Boolean,
        sendCookie: Boolean = true,
        /** 非空时**只用它**作 Cookie（图形验证后回传的会话 cookie）。 */
        overrideCookie: String? = null,
    ): JSONObject? {
        return try {
            val httpRequest = Request.Builder()
                .url(MUSICU_URL)
                .header("User-Agent", if (appIdentity) UA_APP else UA_WEB)
                .header("Referer", "https://y.qq.com/")
                .header("Content-Type", "application/json")
                .apply {
                    // 只带认识的身份字段（见 QqCookie.requestCookie 的注释）。
                    // 登录请求例外：那时还没有身份，见 musicuLogin。
                    val cookie = if (!overrideCookie.isNullOrEmpty()) {
                        overrideCookie
                    } else if (sendCookie) {
                        QqCookie.requestCookie(QqAuthStore.getCookie(ctx))
                    } else {
                        ""
                    }
                    if (cookie.isNotEmpty()) header("Cookie", cookie)
                }
                .post(body.toString().toRequestBody(JSON))
                .build()

            http.newCall(httpRequest).execute().use { response ->
                val text = response.body?.string()
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "musicu $module http=${response.code} len=${text?.length ?: 0}")
                }
                if (!response.isSuccessful || text.isNullOrEmpty()) return@use null
                val json = JSONObject(text)
                // 信封 key 可能是 "req"，也可能是 module 名本身；两者都认。
                json.optJSONObject("req") ?: json.optJSONObject(module)
            }
        } catch (e: Exception) {
            Log.w(TAG, "musicu failed: $module", e)
            null
        }
    }

    /**
     * 旧版 `c.y.qq.com` 的 GET 通道（v2.1.0 · B）。
     *
     * 为什么搜索走它而不是走 `musicu.fcg`：实测 `DoSearchForQQMusicMobile`
     * **连续 6 次只有 1 次成功**（其余 `code:2001`，疑似按出口 IP 限流），
     * 而这条旧版 GET **3/3 稳定**。搜索是用户主动触发、可感知的功能，
     * 不能把成功率压在一条会随机失败的通道上。
     *
     * 好消息：加 `new_json=1` 后**字段名与新版完全一致**（`mid`/`name`/`interval`/
     * `file.media_mid`/`pay.pay_play`），所以 [QqSongMapper] 一套映射两边通用。
     */
    suspend fun legacyGet(url: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UA_WEB)
                .header("Referer", "https://y.qq.com/")
                .apply {
                    val cookie = appContext?.let { QqCookie.requestCookie(QqAuthStore.getCookie(it)) }
                    if (!cookie.isNullOrEmpty()) header("Cookie", cookie)
                }
                .get()
                .build()
            searchHttp.newCall(request).execute().use { response ->
                val text = response.body?.string()
                if (BuildConfig.DEBUG) Log.d(TAG, "legacyGet http=${response.code} len=${text?.length ?: 0}")
                if (!response.isSuccessful || text.isNullOrEmpty()) return@use null
                JSONObject(text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "legacyGet failed", e)
            null
        }
    }

    /**
     * web 身份：搜索用。字段与实测脚本逐项一致。
     * `g_tk` 恒为 5381（未登录时的固定值）；登录后腾讯侧的真实 g_tk 由 skey 派生，
     * 但实测搜索在带 cookie 时也不校验它。
     */
    private fun webComm(context: Context): JSONObject = JSONObject()
        .put("ct", 24)
        .put("cv", 4747474)
        .put("v", 4747474)
        .put("platform", "yqq.json")
        .put("chid", "0")
        .put("uin", uinOf(context))
        .put("format", "json")
        .put("inCharset", "utf-8")
        .put("outCharset", "utf-8")
        .put("notice", 0)
        .put("need_new_code", 1)
        .put("g_tk", 5381)
        .put("g_tk_new_20200303", 5381)

    /**
     * 客户端身份：取链与歌词用。设备指纹来自 [QqIdentity]（本机自生成，不是抄来的）。
     */
    private fun appComm(context: Context): JSONObject = JSONObject()
        .put("ct", 11)
        .put("cv", 14090008)
        .put("v", 14090008)
        .put("chid", "10003505")
        .put("tmeAppID", "qqmusic")
        .put("QIMEI36", QqIdentity.qimei36(context))
        .put("OpenUDID", QqIdentity.deviceId(context))
        .put("udid", QqIdentity.deviceId(context))
        .put("aid", QqIdentity.deviceId(context))
        .put("os_ver", "10")
        .put("phonetype", "MI 10")
        .put("uin", uinOf(context))
        .put("format", "json")

    /** 未登录时 `comm.uin` 用 `"0"`（实测匿名可用）。 */
    private fun uinOf(context: Context): String =
        QqAuthStore.uin(context)?.toString() ?: "0"
}
