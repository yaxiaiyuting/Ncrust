/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明：
 *   - v1.2.0 · A1：取链请求的客户端身份。服务端按 **Cookie 里的客户端身份** 判定音质
 *     上限：只带 MUSIC_U/__csrf 的会话被封顶在 lossless，请求 hires / 母带会被静默回落
 *     成 lossless（code 仍是 200）；追加 os/appver 后同一请求才真正返回 hires。
 *     实测脚本见仓库外的 tools/probe-quality.py（不入 git）。
 */

package com.takahashirinta.ncrust.network

import android.content.Context
import android.util.Log
import java.security.SecureRandom

object ClientIdentity {
    private const val TAG = "ClientIdentity"
    private const val PREFS = "ncrust_device"
    private const val KEY_DEVICE_ID = "client_device_id"

    /** 客户端类型。实测 pc 与 android 都能解锁 Hi-Res，A1 先用风险更低的 pc。 */
    const val OS = "pc"

    // appver 取自 2026-09 网易云 PC 官方客户端，实测有效
    // 若未来服务端降权，参考 music.163.com/release/ 更新
    const val APPVER = "3.0.6"

    /** Win10 版本号。实测非必需（去掉仍返回 hires），保留只为客户端指纹自洽。 */
    const val OSVER = "10.0.19045"

    @Volatile
    private var cachedDeviceId: String? = null

    /**
     * 幂等初始化：先读一次 prefs，为空才生成并落盘；**生成后立刻写内存缓存**。
     * 缓存这步不能省 —— apply() 是异步的，若 init() 之后 deviceId() 又回头读 prefs，
     * 可能读到空串，同一进程内的指纹就前后不一致了。
     */
    fun init(context: Context) {
        if (cachedDeviceId != null) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_DEVICE_ID, null)
        val id = stored?.takeIf { it.isNotBlank() } ?: generateDeviceId().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
        cachedDeviceId = id
        Log.i(TAG, "client identity: os=$OS appver=$APPVER osver=$OSVER deviceId=${id.take(4)}… (len=${id.length})")
    }

    /** 32 位小写 hex。只做客户端指纹，不读 ANDROID_ID / 序列号，不需要任何权限。 */
    private fun generateDeviceId(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 未初始化时退回进程内临时值并告警：宁可指纹不稳定，也绝不抛异常打断播放。 */
    fun deviceId(): String = cachedDeviceId ?: generateDeviceId().also {
        cachedDeviceId = it
        Log.w(TAG, "deviceId() before init(); falling back to a process-local id")
    }

    /**
     * 需要追加到用户 cookie 之后的身份字段；用户 cookie 里已有的同名键跳过，不覆盖、不重复。
     * 顺序固定「用户字段在前、身份字段在后」，MUSIC_U / __csrf 永远原样保留。
     */
    fun extraCookieFor(userCookie: String?): String {
        val base = userCookie?.trim().orEmpty()
        val existing = base.split(';')
            .mapNotNull { it.substringBefore('=', "").trim().lowercase().takeIf { k -> k.isNotEmpty() } }
            .toSet()
        return listOf("os" to OS, "appver" to APPVER, "osver" to OSVER, "deviceId" to deviceId())
            .filterNot { (k, _) -> k.lowercase() in existing }
            .joinToString("; ") { (k, v) -> "$k=$v" }
    }
}
