/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · C（hotfix 1）：QQ 音乐登录浮层。
 */

package com.takahashirinta.ncrust.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.takahashirinta.ncrust.qq.QqAuthStore
import com.takahashirinta.ncrust.qq.QqCookie
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.delay

/**
 * QQ 音乐登录浮层（v2.1.0 · C）。
 *
 * ## 为什么必须是**桌面 UA**（v2.1.0 真机反馈修复）
 *
 * WebView 默认发 Android UA，`y.qq.com` 判定为手机端后会自动跳到移动版 H5 ——
 * 那个页面上**没有可用的登录入口**（它引导你去装 App）。真机反馈原话：
 * 「网页版会自动从 pc 跳到手机，没有对应登录方式」。
 *
 * 所以这里把 UA 覆写成桌面 Chrome。桌面版首页右上角有明确的「登录」按钮，
 * 点开后是 QQ 互联的标准登录弹窗（默认展示二维码，也可切账号密码 / 手机号）。
 *
 * ## 为什么必须处理 `window.open`（否则点了「登录」毫无反应）
 *
 * QQ 互联的登录是一个**弹窗**（`window.open` 到 `xui.ptlogin2.qq.com`）。
 * WebView 默认不支持多窗口，弹窗请求会被直接丢掉 —— 表现就是「点了登录没反应」，
 * 而这与 UA 问题叠加在一起时，用户完全无法判断是哪里坏了。
 *
 * ## 为什么用**轮询 cookie** 而不是等 `onPageFinished`
 *
 * 登录过程会在 `xui.ptlogin2.qq.com` → `ptlogin2.qq.com` → `y.qq.com` 之间多次跳转，
 * 弹窗还可能被我们接管到同一个 WebView 里（见 [WebChromeClient.onCreateWindow]），
 * 于是「最后一个加载完成的页面是哪个」并不确定。**登录成功的唯一权威事实是 cookie**，
 * 所以这里按固定间隔直接读 cookie，不依赖任何页面回调。
 *
 * 另外补一步自愈：QQ 互联登录完成后先拿到的是 `p_skey`/`uin`（`.qq.com` 域），
 * 音乐侧的 `qqmusic_key` 要由 `y.qq.com` 自己的脚本去换。所以一旦检测到「互联已登录
 * 但还没有音乐票据」，就主动把 WebView 导航回 `y.qq.com` 一次，让那一步发生。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun QqLoginOverlay(
    onLoggedIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    // 「互联已登录但缺音乐票据」时只补一次导航，避免与用户的操作打架。
    var nudged by remember { mutableStateOf(false) }

    // 轮询 cookie：登录成功的权威判据（见 KDoc）。
    LaunchedEffect(webView) {
        val view = webView ?: return@LaunchedEffect
        while (true) {
            delay(COOKIE_POLL_MS)
            val cookieManager = CookieManager.getInstance()
            val cookie = cookieManager.getCookie(COOKIE_PROBE_URL)
            if (!cookie.isNullOrEmpty()) {
                if (QqCookie.isLoggedIn(cookie)) {
                    // 用 merge 语义落盘（QqAuthStore.saveCookie 内部就是 merge）：
                    // 登录流程回传的往往只是增量字段集，覆盖会把 uin 丢掉。
                    QqAuthStore.saveCookie(context, cookie)
                    onLoggedIn()
                    return@LaunchedEffect
                }
                if (!nudged && QqCookie.uinOf(cookie) != null) {
                    // 互联已登录、音乐票据还没换到 —— 回首页让 y.qq.com 的脚本完成交换。
                    nudged = true
                    view.loadUrl(HOME_URL)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    // 桌面 UA：不加这一行会被跳到没有登录入口的移动版 H5。
                    settings.userAgentString = DESKTOP_UA
                    // QQ 互联登录是弹窗（window.open），必须允许并接管，否则点「登录」没反应。
                    settings.setSupportMultipleWindows(true)
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            // 每次页面开始加载都重新读一次 cookie。
                            // 这一步是「快路径」：轮询最坏要等一个间隔，而登录成功那一刻
                            // 往往紧接着一次跳转，这里能立刻收工。
                            val cookie = CookieManager.getInstance().getCookie(COOKIE_PROBE_URL)
                            if (!cookie.isNullOrEmpty() && QqCookie.isLoggedIn(cookie)) {
                                QqAuthStore.saveCookie(ctx, cookie)
                                onLoggedIn()
                            }
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        /**
                         * 把弹窗接管到**同一个 WebView**里渲染。
                         *
                         * 为什么复用而不是新建：新建一个 WebView 需要额外的窗口层级与生命周期管理，
                         * 而这里的目标只有一个 —— 让用户能完成登录。登录成功的判据是 cookie，
                         * 不依赖弹窗与父页面的 JS 通信（那条通路在同 WebView 复用下本来也会断，
                         * 但我们不需要它）。
                         */
                        override fun onCreateWindow(
                            view: WebView,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message,
                        ): Boolean {
                            val transport = resultMsg.obj as WebView.WebViewTransport
                            transport.webView = view
                            resultMsg.sendToTarget()
                            return true
                        }
                    }
                    CookieManager.getInstance().removeAllCookies(null)
                    loadUrl(HOME_URL)
                    webView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 直达登录页的入口条。**放在底部而不是顶部**：真机截图复核发现，
        // 顶部条会正好盖住桌面版站点的 header（而「登录」按钮就在那里）——
        // 一个为了「找不到入口时兜底」而加的提示，反而把入口挡住了，这是第一次改版踩到的。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .background(LocalMetroColors.current.surface)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp)
                .clickable { webView?.loadUrl(QQ_CONNECT_LOGIN_URL) },
        ) {
            MetroText(
                text = strings.sourceQqLoginHint,
                style = LocalMetroTypography.current.bodySmall,
                color = LocalMetroColors.current.primary,
            )
        }

        TopScrimIconButton(
            icon = Icons.Default.Close,
            contentDescription = strings.close,
            onClick = onDismiss,
            alignment = Alignment.TopEnd,
        )
    }
}

private const val HOME_URL = "https://y.qq.com/"

/**
 * 探测 cookie 用的固定 URL。
 *
 * 不能传「当前页面 URL」：登录会在 `qq.com` 各子域间跳转，而我们要的是**音乐侧**的票据，
 * 它挂在 `y.qq.com`（或其父域 `.qq.com`）上。
 */
private const val COOKIE_PROBE_URL = "https://y.qq.com/"

/** 桌面 Chrome UA。桌面版首页才有登录入口（移动版会引导去装 App）。 */
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36"

/**
 * QQ 互联标准登录页（`appid`/`daid`/`pt_3rd_aid` 是 QQ 音乐 Web 端的固定值，
 * 与扫码轮询链路上用的是同一组）。默认展示二维码，可切账号密码 / 手机号。
 */
private const val QQ_CONNECT_LOGIN_URL =
    "https://xui.ptlogin2.qq.com/cgi-bin/xlogin" +
        "?appid=716027609&daid=383&style=33&theme=2" +
        "&s_url=https%3A%2F%2Fy.qq.com%2Fportal%2Fprofile.html" +
        "&pt_3rd_aid=100497308"

/** cookie 轮询间隔。1.5 s：比人手动完成一次登录的粒度细得多，又不至于空转。 */
private const val COOKIE_POLL_MS = 1500L
