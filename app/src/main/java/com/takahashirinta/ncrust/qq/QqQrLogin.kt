/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · C（hotfix 4）：QQ 互联扫码登录的**纯逻辑**部分。
 */

package com.takahashirinta.ncrust.qq

/**
 * QQ 互联（`ssl.ptlogin2.qq.com`）扫码登录的纯逻辑：token 计算、`ptuiCB` 解析、状态映射。
 * **无 IO、无 Android 依赖、JVM 可单测。**
 *
 * ## 为什么走 QQ 互联而不是「QQ 音乐客户端扫码」
 *
 * 后者的接口（`CreateQRCode` / `GetQRCodeStatus`）实测匿名可用，但**扫码确认后的最后一跳
 * 在官方实现里只能是 MQTT 推送**（调研穷举 48 个候选方法后确认：HTTP 侧没有「把登录态
 * 交给发起方」的接口，`QRCodeLogin` 要的是**扫码方**的 OAuth code + 有效 Cookie）。
 * 要支持它就得自己实现 MQTT 5.0 over WSS 的握手与订阅，成本远超本版范围。
 *
 * QQ 互联这条是**纯 HTTP 长轮询**，官方 Web 端现役：二维码用**手机 QQ**扫，
 * `ptqrlogin` 直接返回下一步的跳转地址。
 *
 * ## 验证边界（如实标注）
 *
 * - `ptqrshow`（取二维码）：**实测可用**（HTTP 200 + 真实 PNG + `qrsig` cookie）。
 * - `ptqrlogin`（轮询）：**本机出口 IP 被 WAF 恒定 403**（调研期 8 种以上参数/Header/TLS
 *   变体全部失败），因此**这一步没有实测过**。你在国内移动网络下应当正常 ——
 *   若不通，界面会明确提示并给出「改用网页登录」的按钮（那条路已实测可登）。
 * - [ptqrToken] 的算法是官方 JS 的 `hash33`（`h = h*33 + ord(c)`，初值 5381，
 *   取低 31 位）。调研期抓到的那对 `qrsig`/`ptqrtoken` 经核验**不是同一次请求**
 *   （多轮探测混在一起），所以这里**没有真实向量可钉**，只能钉算法自身的行为。
 */
object QqQrLogin {

    /** 二维码 PNG 的边长级别。实测 `s=3` 是 111×111，`s=6` 是 222×222（显示时不做平滑缩放即可扫）。 */
    const val QR_SIZE_PARAM = 6

    /** 二维码有效期（服务端 `expiresIn` 的实测值是 900 秒，这里留出刷新余量）。 */
    const val QR_TTL_SECONDS = 840

    /**
     * `ptqrtoken = hash33(qrsig)`：官方 JS 的算法。
     *
     * `h = h * 33 + ord(c)`（即 `(h << 5) + h + c`），初值 5381，最后取低 31 位。
     * 结果恒为非负 —— 这一点在 Kotlin 里要显式做（`Int` 会溢出成负数，
     * 而请求参数里必须是正数，否则服务端判 token 错）。
     */
    fun ptqrToken(qrsig: String): Long {
        var h = 5381L
        for (c in qrsig) {
            h = (h shl 5) + h + c.code
            // 只保留低 32 位，避免长字符串把 h 推到天上去（官方 JS 是 32 位整数溢出语义）
            h = h and 0xFFFFFFFFL
        }
        return h and 0x7FFFFFFFL
    }

    /** 扫码状态。取值来自 `ptuiCB` 的第一个参数（QQ 互联的固定码表）。 */
    enum class QrStatus {
        /** `66`：二维码还没被扫。 */
        WAITING,

        /** `65`：已扫描，等手机端确认。 */
        SCANNED,

        /** `0`：确认成功，携带下一步跳转地址。 */
        CONFIRMED,

        /** `67`：二维码已过期，需要刷新。 */
        EXPIRED,

        /** `1`/`2`/…：被拒绝或其它失败。 */
        FAILED,
    }

    /** `ptuiCB(...)` 的解析结果。 */
    data class PtuiCb(
        val code: Int,
        val url: String?,
        val message: String,
    ) {
        val status: QrStatus get() = statusOf(code)
    }

    fun statusOf(code: Int): QrStatus = when (code) {
        0 -> QrStatus.CONFIRMED
        65 -> QrStatus.SCANNED
        66 -> QrStatus.WAITING
        67 -> QrStatus.EXPIRED
        else -> QrStatus.FAILED
    }

    /**
     * 解析 `ptuiCB('65','0','','0','二维码已扫描，请在手机上确认登录。', 'xxx');`
     *
     * **手写扫描而不是正则**：字段里会出现中文与转义引号，正则在这种输入上很容易
     * 贪婪吃掉后面的字段（一个字段读错，表现就是「永远停在等待扫码」）。
     * 解析失败返回 null，调用方按「这次轮询没结果」处理（下一轮继续），不要当失败终止。
     */
    fun parsePtuiCb(text: String?): PtuiCb? {
        if (text.isNullOrBlank()) return null
        val start = text.indexOf("ptuiCB(")
        if (start < 0) return null
        val fields = mutableListOf<String>()
        var i = start + "ptuiCB(".length
        val sb = StringBuilder()
        var inQuote = false
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '\'' -> {
                    inQuote = !inQuote
                    if (!inQuote) {
                        // 一个字段结束
                        fields.add(sb.toString())
                        sb.setLength(0)
                    }
                    i++
                }
                !inQuote && (ch == ',' || ch == ')') -> {
                    i++
                    if (fields.size >= 6) break
                }
                else -> {
                    sb.append(ch)
                    i++
                }
            }
        }
        if (fields.size < 5) return null
        val code = fields[0].trim().toIntOrNull() ?: return null
        val url = fields.getOrNull(2)?.takeIf { it.isNotBlank() }
        val message = fields.getOrNull(4).orEmpty()
        return PtuiCb(code, url, message)
    }

    /** `ptqrshow` 的 URL（取二维码图片）。`t` 是随机数，防缓存。 */
    fun qrShowUrl(random: Double, sizeParam: Int = QR_SIZE_PARAM): String =
        "https://ssl.ptlogin2.qq.com/ptqrshow" +
            "?appid=$APP_ID&e=2&l=M&s=$sizeParam&d=72&v=4&t=$random" +
            "&daid=$DAID&pt_3rd_aid=$PT_3RD_AID" +
            "&u1=" + encode(U1)

    /** `ptqrlogin` 的 URL（轮询扫码状态）。 */
    fun qrLoginUrl(token: Long, actionTs: Long): String =
        "https://ssl.ptlogin2.qq.com/ptqrlogin" +
            "?u1=" + encode(U1) +
            "&ptqrtoken=$token" +
            "&ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2052" +
            "&action=0-0-$actionTs&js_ver=20102616&js_type=1&pt_uistyle=40" +
            "&aid=$APP_ID&daid=$DAID&pt_3rd_aid=$PT_3RD_AID&"

    /** QQ 音乐 Web 端的固定 appid/daid（与扫码轮询链路上用的是同一组）。 */
    const val APP_ID = "716027609"
    const val DAID = "383"
    const val PT_3RD_AID = "100497308"

    /** 登录成功后的跳转目标（QQ 互联的登录跳板）。 */
    private const val U1 = "https://graph.qq.com/oauth2.0/login_jump"

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
