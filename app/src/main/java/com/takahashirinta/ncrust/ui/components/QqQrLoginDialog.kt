/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · C（hotfix 4）：QQ 音乐自绘二维码登录。
 */

package com.takahashirinta.ncrust.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.qq.QqQrClient
import com.takahashirinta.ncrust.qq.QqQrLogin
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.controls.MetroButton
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.delay

/**
 * QQ 音乐登录：**自绘二维码**（v2.1.0 · C）。
 *
 * ## 流程（两步纯 HTTP + 一步复用已验证的 WebView）
 *
 * 1. `ptqrshow` 取二维码 PNG 与 `qrsig`（[QqQrClient.requestQr]）；
 * 2. `ptqrlogin` 长轮询扫码状态（[QqQrClient.poll]），直到 `ptuiCB` 给出 `0` + 跳转地址；
 * 3. 把那个跳转地址交给 WebView 打开 —— `check_sig` 会下发 `.qq.com` 的登录 cookie，
 *    随后 `y.qq.com` 自己的脚本完成**音乐票据**（`qqmusic_key`）的交换。
 *
 * **为什么第 3 步不自己用 HTTP 做完**：换音乐票据那一步（`QQConnectLogin.LoginServer.QQLogin`
 * 之类）需要扫码方的 OAuth code，本仓库**没有账号可以实测**，参数形状属于「有依据的推测」。
 * 而「在 WebView 里打开站点让它自己换」这条路**已被真机验证可登录**（用户实测反馈）。
 * 与其赌一个没验证过的请求形状，不如把最后一步交给已经证明能用的机制 ——
 * 用户的体验是一样的（扫完码自动完成），但我们不引入未验证的假设。
 *
 * ## 失败时的降级
 *
 * 轮询可能因为网络/风控拿不到结果（`ptqrlogin` 在部分出口 IP 上会被 WAF 拦）。
 * 界面因此常驻一个「改用网页登录」按钮，直接切到已经验证可用的 WebView 登录。
 * 二维码区域也不会一直转——失败会明确显示原因。
 */
@Composable
fun QqQrLoginDialog(
    /** 扫码确认成功：把跳转地址与已拿到的 cookie 交给调用方去完成登录。 */
    onConfirmed: (url: String, cookies: String) -> Unit,
    /** 用户选择「改用网页登录」。 */
    onUseWebLogin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current

    var qr by remember { mutableStateOf<QqQrClient.QrCode?>(null) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var status by remember { mutableStateOf(QqQrLogin.QrStatus.WAITING) }
    var loadFailed by remember { mutableStateOf(false) }
    // 换一张二维码时 +1，用来重启下面的轮询协程。
    var generation by remember { mutableStateOf(0) }
    // 轮询拿不到响应（网络抖动/被拦）的连续次数：只用于把提示写清楚，不终止流程。
    var misses by remember { mutableStateOf(0) }

    // 取二维码
    LaunchedEffect(generation) {
        qr = null
        bitmap = null
        loadFailed = false
        status = QqQrLogin.QrStatus.WAITING
        misses = 0
        val code = QqQrClient.requestQr()
        if (code == null) {
            loadFailed = true
            return@LaunchedEffect
        }
        qr = code
        bitmap = BitmapFactory.decodeByteArray(code.png, 0, code.png.size)
        if (bitmap == null) loadFailed = true
    }

    // 轮询
    LaunchedEffect(qr, generation) {
        val code = qr ?: return@LaunchedEffect
        val deadline = System.currentTimeMillis() + QqQrLogin.QR_TTL_SECONDS * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            val cb = QqQrClient.poll(code.qrsig)
            if (cb == null) {
                misses++
                continue
            }
            misses = 0
            status = cb.status
            when (cb.status) {
                QqQrLogin.QrStatus.CONFIRMED -> {
                    val url = cb.url
                    if (url.isNullOrEmpty()) {
                        // 确认成功却没给跳转地址：这在协议里不该发生，按失败处理而不是静默卡住
                        status = QqQrLogin.QrStatus.FAILED
                    } else {
                        onConfirmed(url, QqQrClient.cookies())
                    }
                    return@LaunchedEffect
                }
                QqQrLogin.QrStatus.EXPIRED, QqQrLogin.QrStatus.FAILED -> return@LaunchedEffect
                else -> Unit // WAITING / SCANNED 继续轮询
            }
        }
        // 超时：让用户能刷新，而不是永远停在「等待扫码」
        status = QqQrLogin.QrStatus.EXPIRED
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .background(colors.surface)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            MetroText(
                text = strings.sourceQqAccount,
                style = androidx.compose.ui.text.TextStyle(fontSize = 18.sp),
                color = colors.onSurface,
            )
            Spacer(Modifier.height(16.dp))

            // 二维码。222×222 的源图放到 220dp：**必须关掉平滑缩放**（FilterQuality.None），
            // 否则插值会把二维码的模块边缘糊掉，扫描识别率明显下降。
            Box(
                modifier = Modifier.size(220.dp).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = bitmap
                if (bmp != null && status != QqQrLogin.QrStatus.EXPIRED) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = strings.sourceQqLoginAction,
                        modifier = Modifier.size(220.dp),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
                    )
                } else {
                    MetroText(
                        text = strings.sourceQrRefresh,
                        style = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                        color = colors.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            MetroText(
                text = when {
                    loadFailed -> strings.sourceQrLoadFailed
                    status == QqQrLogin.QrStatus.SCANNED -> strings.sourceQrScanned
                    status == QqQrLogin.QrStatus.EXPIRED -> strings.sourceQrExpired
                    status == QqQrLogin.QrStatus.FAILED -> strings.sourceQrFailed
                    misses >= 3 -> strings.sourceQrNetworkHint
                    else -> strings.sourceQrWaiting
                },
                style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            MetroButton(text = strings.sourceQrRefresh, onClick = { generation++ })
            Spacer(Modifier.height(8.dp))
            // 常驻的降级入口：轮询在部分出口 IP 上会被 WAF 拦（本机就是），
            // 用户不该因为这条路不通就完全登不上。
            MetroButton(text = strings.sourceWebLogin, onClick = onUseWebLogin)
            Spacer(Modifier.height(8.dp))
            MetroButton(text = strings.close, onClick = onDismiss)
        }
    }
}

/** 轮询间隔。2 秒：与人扫码确认的节奏相称，又不会把服务端打得太勤。 */
private const val POLL_INTERVAL_MS = 2_000L
