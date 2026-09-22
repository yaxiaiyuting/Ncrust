/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明（Bug1「音质切换」）：
 *   - A：音质下拉 onSelect 后调用 onQualityPreferenceChanged()，正在播放时立即生效。
 *   - C：API < 27 无系统 FLAC 解码器时，对 lossless/hires/jyeffect 档位给出
 *        「本机不支持该档位，将自动降级」提示（MetroDropdownRow 新增 hint 参数）。
 */

package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.takahashirinta.kanesumi.anim.sokuou.rememberMetroFlingBehavior
import io.github.takahashirinta.kanesumi.controls.MetroSelectorFlyout
import com.takahashirinta.ncrust.cache.OfflineAudioCache
import com.takahashirinta.ncrust.reco.ArtistReco
import io.github.takahashirinta.kanesumi.controls.MetroSwitch
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import coil.compose.AsyncImage
import androidx.lifecycle.viewmodel.compose.viewModel
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.cache.ContentCache
import android.content.Context
import android.widget.Toast
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.player.SongUrlFetcher
import com.takahashirinta.ncrust.power.BackgroundActivity
import com.takahashirinta.ncrust.ui.BottomOverlayInsetDp
import com.takahashirinta.ncrust.ui.components.QrAuthorizeScreen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.takahashirinta.ncrust.ui.components.QrLoginDialog
import com.takahashirinta.ncrust.ui.theme.BackgroundImageManager
import com.takahashirinta.ncrust.lyric.LyricsDisplayPrefs
import com.takahashirinta.ncrust.lyric.LyricsSweepQuality
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.i18n.LanguagePreset
import com.takahashirinta.ncrust.ui.i18n.getSavedLanguageCode
import com.takahashirinta.ncrust.ui.i18n.languagePresets
import com.takahashirinta.ncrust.ui.theme.AccentSource
import com.takahashirinta.ncrust.ui.theme.AccentSourceSelector
import com.takahashirinta.ncrust.ui.theme.ThemeColorSelector
import com.takahashirinta.ncrust.ui.theme.systemAccentSupported
import com.takahashirinta.ncrust.ui.theme.ThemeMode
import com.takahashirinta.ncrust.ui.theme.themeColorPresets
import com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UserScreen(
    onOpenAbout: () -> Unit = {},
    themeIndex: Int = 0,
    onThemeChange: (Int) -> Unit = {},
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    accentSource: AccentSource = AccentSource.PRESET,
    onAccentSourceChange: (AccentSource) -> Unit = {},
    onRefreshSystemAccent: () -> Unit = {},
    onShowWebLogin: () -> Unit = {},
    refreshTrigger: Int = 0,
    onLanguageChange: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val coroutineScope = rememberCoroutineScope()
    var showAccountDialog by remember { mutableStateOf(false) }
    var showQrLogin by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableStateOf(0L) }
    // 缓存占用要递归遍历 cacheDir，放 IO 线程算，避免组合期主线程卡顿。
    LaunchedEffect(Unit) {
        cacheSize = withContext(Dispatchers.IO) { currentCacheSize(context) }
    }
    var hasCookie by remember { mutableStateOf(CookieManager.hasCookie(context)) }
    // 扫码登录为平板 / 大屏独占：手机端未登录点头像仍直接进 WebView 官方登录页。
    val isWideLayout = LocalConfiguration.current.screenWidthDp >= 600

    var userProfile by remember { mutableStateOf<PlaylistApi.UserProfile?>(null) }
    var isLoadingProfile by remember { mutableStateOf(false) }

    val prefs = remember { context.getSharedPreferences("ncrust_settings", 0) }
    val playerViewModel: PlayerViewModel = viewModel()
    var wifiQuality by remember { mutableIntStateOf(prefs.getInt("wifi_quality", 3)) }
    var mobileQuality by remember { mutableIntStateOf(prefs.getInt("mobile_quality", 1)) }
    var gaplessEnabled by remember { mutableStateOf(prefs.getBoolean("gapless_playback", true)) }
    var lyricsTranslation by remember { mutableStateOf(prefs.getBoolean("lyrics_translation", true)) }
    // v1.4.0 · 音乐人推荐开关
    var artistRecoEnabled by remember { mutableStateOf(ArtistReco.isEnabled(context)) }
    // v1.5.0 · B / v1.5.1 · A 逐字动画模式（0 渐变扫过 / 1 逐字硬切 / 2 关闭逐字）。
    // 读的时候顺带完成 v1.5.0 布尔开关 lyrics_word_by_word 的一次性迁移。
    var lyricsWordAnimation by remember { mutableIntStateOf(LyricsDisplayPrefs.readWordAnimation(prefs)) }
    // v1.5.1 · D 媒体面板歌词（默认关：开启后 ARTIST 会被改写成「艺人 · 歌词行」）。
    var lyricsInMediaSession by remember {
        mutableStateOf(prefs.getBoolean("lyrics_in_media_session", false))
    }
    // v1.5.1 · E 歌词字号倍率（0.7x ~ 1.5x，默认 1.0x）。
    var lyricsFontScale by remember { mutableStateOf(LyricsDisplayPrefs.readFontScale(prefs)) }
    // v1.5.2 逐字扫过绘制质量（0 自动 / 1 高级软边 / 2 兼容硬边，默认自动）。
    var lyricsSweepQuality by remember { mutableIntStateOf(LyricsDisplayPrefs.readSweepQuality(prefs)) }

    var selectedLanguageCode by remember { mutableStateOf(getSavedLanguageCode(context)) }

    fun loadProfile() {
        if (!CookieManager.hasCookie(context)) {
            userProfile = null
            hasCookie = false
            return
        }
        coroutineScope.launch {
            isLoadingProfile = true
            try {
                val profile = PlaylistApi.getUserProfile()
                // 服务端对失效 cookie 会返回空 account/profile → userId=0。
                // 此时判定为已过期，主动清除本地 cookie，避免 UI 卡在 "UID: 0"。
                if (profile.userId == 0L) {
                    CookieManager.clearCookie(context)
                    RetrofitClient.updateCookie(null)
                    hasCookie = false
                    userProfile = null
                } else {
                    userProfile = profile
                    hasCookie = true
                }
            } catch (_: Exception) {
                userProfile = null
            } finally {
                isLoadingProfile = false
            }
        }
    }

    LaunchedEffect(Unit) { loadProfile() }
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) {
            hasCookie = CookieManager.hasCookie(context)
            loadProfile()
        }
    }

    if (showAccountDialog) AccountDialog(
        userProfile = userProfile,
        onDismiss = { showAccountDialog = false },
        onScanAuthorize = {
            showAccountDialog = false
            showScanner = true
        },
        onLogout = {
            CookieManager.clearCookie(context)
            RetrofitClient.updateCookie(null)
            hasCookie = false
            userProfile = null
            showAccountDialog = false
        }
    )

    // 手机端扫码授权：扫平板登录二维码 → 解析 unikey → 局域网回传本机 cookie。
    // 连接/成功/失败状态都在扫码页内呈现，不再扫到即关闭。
    if (showScanner) QrAuthorizeScreen(
        onAuthorized = { showScanner = false },
        onClose = { showScanner = false }
    )

    if (showQrLogin) QrLoginDialog(
        onLoginSuccess = { cookie ->
            CookieManager.saveCookie(context, cookie)
            RetrofitClient.updateCookie(cookie)
            hasCookie = true
            showQrLogin = false
            loadProfile()
        },
        onGenericLogin = {
            showQrLogin = false
            onShowWebLogin()
        },
        onDismiss = { showQrLogin = false }
    )

    if (showClearCacheConfirm) ClearCacheConfirmDialog(
        onConfirm = {
            showClearCacheConfirm = false
            coroutineScope.launch {
                val size = withContext(Dispatchers.IO) {
                    ContentCache.clearAll()
                    runCatching { coil.Coil.imageLoader(context).memoryCache?.clear() }
                    runCatching { coil.Coil.imageLoader(context).diskCache?.clear() }
                    runCatching {
                        context.cacheDir?.let { dir ->
                            dir.listFiles()
                                ?.filter { it.name == "WebView" || it.name == "http" }
                                ?.forEach { it.deleteRecursively() }
                        }
                    }
                    // v1.6.0 · D1：离线音频缓存在 filesDir/offline/audio（不随系统清缓存消失），
                    // 用户点「清除缓存」时一并清掉，并作废离线 URL 清单，避免留下死条目。
                    runCatching { OfflineAudioCache.clear(context) }
                    currentCacheSize(context)
                }
                cacheSize = size
                Toast.makeText(context, strings.cacheCleared, Toast.LENGTH_SHORT).show()
            }
        },
        onDismiss = { showClearCacheConfirm = false }
    )

    // 宽屏设置/资料内容居中限宽（上限 720dp），避免设置行横跨平板。
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxHeight(),
        contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
        flingBehavior = rememberMetroFlingBehavior()
    ) {
        // Groove 大字页头。
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
            ) {
                MetroText(
                    strings.tabUser,
                    color = LocalMetroColors.current.onBackground,
                    style = LocalMetroTypography.current.pageHeading,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        // Profile 块。整块可点：已登录 → 账户管理；未登录 → 直接进 WebView 登录。
        item {
            ProfileBlock(
                isLoading = isLoadingProfile,
                profile = userProfile,
                notLoggedInText = strings.notLoggedIn,
                loginHintText = strings.loginHint,
                uidLabel = strings.uidLabel(userProfile?.userId?.toString() ?: ""),
                onClick = {
                    when {
                        hasCookie -> showAccountDialog = true
                        // 平板/大屏：原生扫码（官方 App 扫），下面再给「通用登录」退回 WebView
                        isWideLayout -> showQrLogin = true
                        else -> onShowWebLogin()
                    }
                }
            )
            Spacer(Modifier.height(24.dp))
        }

        // 音质
        item {
            SectionTitle(strings.qualitySectionTitle)
            // API < 27 没有系统 FLAC 解码器：选中 lossless / hires / jyeffect 会在取链阶段
            // 被跳过、实际拿到 mp3。这里显式提示，避免"选了无损却没无损"（Bug1-C）。
            val flacUnsupported = !SongUrlFetcher.deviceSupportsFlac
            MetroDropdownRow(
                label = strings.wifiQualityLabel,
                selectedIndex = wifiQuality,
                options = strings.qualityOptions,
                hint = if (flacUnsupported && isFlacTierIndex(wifiQuality)) {
                    strings.qualityFlacUnsupportedHint
                } else null,
                onSelect = {
                    wifiQuality = it
                    prefs.edit().putInt("wifi_quality", it).apply()
                    // Bug1-A：设置立即生效——正在播放时按新档位重新取链，而不是等下一首。
                    playerViewModel.onQualityPreferenceChanged()
                }
            )
            MetroDropdownRow(
                label = strings.mobileQualityLabel,
                selectedIndex = mobileQuality,
                options = strings.qualityOptions,
                hint = if (flacUnsupported && isFlacTierIndex(mobileQuality)) {
                    strings.qualityFlacUnsupportedHint
                } else null,
                onSelect = {
                    mobileQuality = it
                    prefs.edit().putInt("mobile_quality", it).apply()
                    playerViewModel.onQualityPreferenceChanged()
                }
            )
            Spacer(Modifier.height(24.dp))
        }

        // 播放
        item {
            SectionTitle(strings.playbackSectionTitle)
            // 标题与 Switch 同行居中，描述另起一行——描述不参与对齐，否则 Switch
            // 会被顶到与描述顶部对齐，看起来像挂在描述上。
            SettingSwitchRow(
                title = strings.gaplessSectionTitle,
                description = strings.gaplessDescription,
                checked = gaplessEnabled,
                onCheckedChange = {
                    gaplessEnabled = it
                    prefs.edit().putBoolean("gapless_playback", it).apply()
                    // 即时生效: VM 缓存的 gaplessEnabled 不刷新的话,
                    // 本首歌的预载状态与开关不一致, 要等下一首歌才对上
                    playerViewModel.refreshGaplessSetting()
                }
            )
            // v1.4.0 · 音乐人推荐卡片开关。默认关；目标艺人与锚点配置存在 prefs 且默认空，
            // 因此其他用户安装后既看不到卡片、也不会为它发任何请求。
            SettingSwitchRow(
                title = strings.artistRecoTitle,
                checked = artistRecoEnabled,
                onCheckedChange = {
                    artistRecoEnabled = it
                    ArtistReco.setEnabled(context, it)
                }
            )
            // 歌词翻译开关(Spotify 式双语:原句下方小号译文)。切了立即生效,播放器常挂载无需重进。
            SettingSwitchRow(
                title = strings.lyricsTranslationLabel,
                checked = lyricsTranslation,
                onCheckedChange = {
                    lyricsTranslation = it
                    prefs.edit().putBoolean("lyrics_translation", it).apply()
                    playerViewModel.setLyricsTranslation(it)
                }
            )
            // v1.5.0 · B / v1.5.1 · A 逐字动画三选一。默认「渐变扫过」；没有 yrc 逐字数据的
            // 歌不受影响（整行渲染与 v1.4.1 一致）。
            MetroDropdownRow(
                label = strings.lyricsWordAnimationLabel,
                selectedIndex = lyricsWordAnimation,
                options = strings.lyricsWordAnimationOptions,
                onSelect = {
                    lyricsWordAnimation = it
                    playerViewModel.setLyricsWordAnimation(it)
                }
            )
            // v1.5.2 逐字扫过质量三选一。只影响渐变带磨不磨圆 —— 两种档位的光标位置算法
            // 完全相同（都走 SweepTrack），所以切到「兼容」也不会退回按词跳变。
            MetroDropdownRow(
                label = strings.lyricsSweepQualityLabel,
                selectedIndex = lyricsSweepQuality,
                options = strings.lyricsSweepQualityOptions,
                onSelect = {
                    lyricsSweepQuality = it
                    playerViewModel.setLyricsSweepQuality(it)
                }
            )
            // v1.5.1 · E 歌词字号（0.7x~1.5x）。改完立即生效，无需重进播放器。
            MetroDropdownRow(
                label = strings.lyricsFontScaleLabel,
                selectedIndex = LyricsDisplayPrefs.fontScaleStepIndex(lyricsFontScale),
                options = LyricsDisplayPrefs.FONT_SCALE_LABELS,
                onSelect = {
                    val scale = LyricsDisplayPrefs.FONT_SCALE_STEPS[it]
                    lyricsFontScale = scale
                    playerViewModel.setLyricsFontScale(scale)
                }
            )
            // v1.5.1 · D 媒体面板歌词。默认关 —— 开启后系统媒体卡片的第二行会从
            // 「歌手」变成「歌手 · 当前歌词行」（Android 13+ 的面板第二行只认 ARTIST）。
            SettingSwitchRow(
                title = strings.lyricsInMediaSessionLabel,
                description = strings.lyricsInMediaSessionHint,
                checked = lyricsInMediaSession,
                onCheckedChange = {
                    lyricsInMediaSession = it
                    playerViewModel.setLyricsInMediaSession(it)
                }
            )
            Spacer(Modifier.height(24.dp))
        }

        // 外观：主题模式 + 主题色 + 语言
        item {
            SectionTitle(strings.themeModeSectionTitle)
            ThemeModeSelector(
                selected = themeMode,
                labels = Triple(
                    strings.themeModeSystem,
                    strings.themeModeDark,
                    strings.themeModeLight
                ),
                onSelect = onThemeModeChange
            )
            Spacer(Modifier.height(24.dp))

            SectionTitle(strings.themeSectionTitle)
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                ThemeColorSelector(
                    selectedIndex = themeIndex,
                    presets = themeColorPresets,
                    onSelect = onThemeChange
                )
            }
            Spacer(Modifier.height(24.dp))

            // B2-C：主题色来源三选一。跟随封面/跟随系统都是"动态来源"，拿不到就回落预设色。
            SectionTitle(strings.accentSourceSectionTitle)
            AccentSourceSelector(
                selected = accentSource,
                systemEnabled = systemAccentSupported,
                labels = Triple(
                    strings.accentSourcePreset,
                    strings.accentSourceCover,
                    strings.accentSourceSystem
                ),
                systemHint = strings.accentSourceSystemHint,
                onSelect = onAccentSourceChange
            )
            // B2-D：部分 ROM 换壁纸后不发配置变更，给一个手动重读入口。
            if (accentSource == AccentSource.SYSTEM && systemAccentSupported) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .border(1.dp, LocalMetroColors.current.divider)
                        .clickable { onRefreshSystemAccent() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    MetroText(
                        strings.accentSystemRefresh,
                        color = LocalMetroColors.current.primary,
                        style = TextStyle(fontSize = 12.sp)
                    )
                }
            }
            Spacer(Modifier.height(24.dp))

            SectionTitle(strings.languageSectionTitle)
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                MetroLanguageDropdown(
                    selectedCode = selectedLanguageCode,
                    presets = languagePresets,
                    onSelect = { code ->
                        if (code != selectedLanguageCode) {
                            selectedLanguageCode = code
                            onLanguageChange(code)
                        }
                    }
                )
            }
            Spacer(Modifier.height(32.dp))
        }

        // 后台运行：跳转系统"允许后台活动 / 忽略电池优化"设置（与首次启动弹窗同一入口）。
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        runCatching {
                            context.startActivity(BackgroundActivity.requestIntent(context))
                        }.onFailure {
                            runCatching {
                                context.startActivity(BackgroundActivity.appDetailsIntent(context))
                            }
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetroText(
                    strings.batteryTitle,
                    color = LocalMetroColors.current.onBackground,
                    style = TextStyle(fontSize = 15.sp),
                    modifier = Modifier.weight(1f)
                )
                MetroIcon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = LocalMetroColors.current.onSurfaceVariant,
                    sizeDp = 20.dp,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        // 自定义背景图（v1.2.0 · B3）。
        // 选图走 SAF 只读打开，取到后立刻降采样拷进私有目录 —— 因为不再需要回读原文件，
        // 所以**不**申请 persistable URI 权限（那会让应用长期持有用户文件的访问权）。
        item {
            SectionTitle(strings.bgSectionTitle)
            val bgRevision by BackgroundImageManager.revision.collectAsState()
            val hasCustomBg = remember(bgRevision) { BackgroundImageManager.isActive(context) }
            val imagePicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    coroutineScope.launch {
                        if (!BackgroundImageManager.importFromUri(context, uri)) {
                            Toast.makeText(context, strings.bgImportFailed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { imagePicker.launch(arrayOf("image/*")) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetroText(
                    if (hasCustomBg) strings.bgChange else strings.bgPick,
                    color = LocalMetroColors.current.onBackground,
                    style = TextStyle(fontSize = 15.sp),
                    modifier = Modifier.weight(1f)
                )
            }
            if (hasCustomBg) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { BackgroundImageManager.clear(context) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MetroText(
                        strings.bgRemove,
                        color = LocalMetroColors.current.primary,
                        style = TextStyle(fontSize = 15.sp),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }

        // 存储与缓存：显示当前占用，点击后弹窗确认再清除。
        item {
            SectionTitle(strings.storageSectionTitle)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showClearCacheConfirm = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetroText(
                    strings.cacheSizeLabel(cacheSize),
                    color = LocalMetroColors.current.onBackground,
                    style = TextStyle(fontSize = 15.sp),
                    modifier = Modifier.weight(1f)
                )
                MetroText(
                    strings.clearCache,
                    color = LocalMetroColors.current.primary,
                    style = TextStyle(fontSize = 15.sp)
                )
            }
            Spacer(Modifier.height(32.dp))
        }

        // 关于：单行条目，右侧带箭头。
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenAbout)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetroText(
                    strings.aboutButton,
                    color = LocalMetroColors.current.onBackground,
                    style = TextStyle(fontSize = 15.sp),
                    modifier = Modifier.weight(1f)
                )
                MetroIcon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = LocalMetroColors.current.onSurfaceVariant,
                    sizeDp = 20.dp,
                )
            }
        }
    }
    }
}

/** Profile 块：96dp 方形头像 + 昵称 titleLarge + UID/登录状态 bodySmall。整块可点。 */
@Composable
private fun ProfileBlock(
    isLoading: Boolean,
    profile: PlaylistApi.UserProfile?,
    notLoggedInText: String,
    loginHintText: String,
    uidLabel: String,
    onClick: () -> Unit
) {
    val strings = LocalStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(LocalMetroColors.current.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (profile?.avatarUrl?.isNotEmpty() == true) {
                AsyncImage(
                    model = profile.avatarUrl,
                    contentDescription = strings.userAvatarDesc,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                MetroIcon(
                    Icons.Default.Person,
                    strings.userIconDesc,
                    tint = LocalMetroColors.current.onSurfaceVariant,
                    sizeDp = 52.dp,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        // 文字列 weight(1f)：任何语言的昵称/提示都能换行不撑破。
        Column(modifier = Modifier.weight(1f)) {
            when {
                isLoading -> MetroText(
                    strings.loading,
                    color = LocalMetroColors.current.onSurfaceVariant,
                    style = TextStyle(fontSize = 20.sp),
                )
                profile != null -> {
                    MetroText(
                        profile.nickname,
                        color = LocalMetroColors.current.onBackground,
                        style = LocalMetroTypography.current.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    MetroText(
                        uidLabel,
                        color = LocalMetroColors.current.onSurfaceVariant,
                        style = LocalMetroTypography.current.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                else -> {
                    MetroText(
                        notLoggedInText,
                        color = LocalMetroColors.current.onSurfaceVariant,
                        style = LocalMetroTypography.current.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    MetroText(
                        loginHintText,
                        color = LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.6f),
                        style = LocalMetroTypography.current.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Groove 风分区标题：16sp semi-bold、上留白 4dp、左 16dp。 */
@Composable
private fun SectionTitle(text: String) {
    MetroText(
        text,
        color = LocalMetroColors.current.onBackground,
        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
    )
}

/**
 * 开关设置行：标题与 Switch 同一行垂直居中，描述（可选）另起一行。
 * 描述不参与对齐，避免 Switch 被顶到与描述顶部对齐。
 */
@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetroText(
            title,
            color = LocalMetroColors.current.onBackground,
            style = LocalMetroTypography.current.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(16.dp))
        MetroSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
    if (description != null) {
        MetroText(
            description,
            color = LocalMetroColors.current.onSurfaceVariant,
            style = LocalMetroTypography.current.caption,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
        )
    }
}

/** 主题模式三选一：跟随系统 / 深色 / 浅色。 */
@Composable
private fun ThemeModeSelector(
    selected: ThemeMode,
    labels: Triple<String, String, String>,
    onSelect: (ThemeMode) -> Unit
) {
    val options = listOf(
        ThemeMode.SYSTEM to labels.first,
        ThemeMode.DARK to labels.second,
        ThemeMode.LIGHT to labels.third
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (mode, label) ->
            val active = mode == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .border(
                        1.dp,
                        if (active) LocalMetroColors.current.primary
                        else LocalMetroColors.current.divider
                    )
                    .background(
                        if (active) LocalMetroColors.current.primary.copy(alpha = 0.14f)
                        else Color.Transparent
                    )
                    .clickable { onSelect(mode) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                MetroText(
                    label,
                    color = if (active) LocalMetroColors.current.primary
                    else LocalMetroColors.current.onSurfaceVariant,
                    style = TextStyle(fontSize = 14.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 清除缓存确认弹窗。 */
@Composable
private fun ClearCacheConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalStrings.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(LocalMetroColors.current.surface)
                .padding(24.dp)
        ) {
            MetroText(
                strings.clearCache,
                color = LocalMetroColors.current.onBackground,
                style = LocalMetroTypography.current.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(12.dp))
            MetroText(
                strings.clearCacheConfirm,
                color = LocalMetroColors.current.onSurfaceVariant,
                style = LocalMetroTypography.current.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                DialogButton(text = strings.cancel, accent = false, onClick = onDismiss)
                Spacer(Modifier.width(12.dp))
                DialogButton(text = strings.clearCache, accent = true, onClick = onConfirm)
            }
        }
    }
}

/**
 * 单行下拉：左侧 label + 右侧「选中值 + ▼」，点击整行弹出垂直菜单。
 *
 * 多语言鲁棒：
 *  - label 用 weight(1f)，任何语言都能换行，不会挤到右侧值。
 *  - 选中值用 maxLines=1 + Ellipsis + widthIn(max=160dp)，极端长文会截断但不会撑破布局。
 *  - 下拉展开的菜单里每项独占一行，完整显示，用户始终能看到完整名字。
 */
@Composable
private fun MetroDropdownRow(
    label: String,
    selectedIndex: Int,
    options: List<String>,
    hint: String? = null,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    // Box 只包住下拉行本身：MetroSelectorFlyout 需要锚在这一行上，
    // 提示文案放在 Box 之外，避免把弹出菜单的锚点推下去。
    Column {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetroText(
                    label,
                    color = LocalMetroColors.current.onBackground,
                    style = TextStyle(fontSize = 15.sp),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                MetroText(
                    options.getOrElse(selectedIndex) { "" },
                    color = LocalMetroColors.current.primary,
                    style = TextStyle(fontSize = 15.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp)
                )
                MetroIcon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = LocalMetroColors.current.onSurfaceVariant,
                    sizeDp = 20.dp,
                )
            }
            // UWP ComboBox 移植:选中项落回锚点原位,菜单从锚点双向展开。
            MetroSelectorFlyout(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                options = options,
                selectedIndex = selectedIndex,
                onSelect = onSelect,
            )
        }
    if (hint != null) {
        MetroText(
            hint,
            color = LocalMetroColors.current.onSurfaceVariant,
            style = TextStyle(fontSize = 12.sp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
        )
    }
    }
}

/** 档位索引是否落在"依赖 FLAC 解码器"的档位（与 SongUrlFetcher 的判定同源）。 */
private fun isFlacTierIndex(index: Int): Boolean {
    val level = PlayerViewModel.QUALITY_LEVELS.getOrNull(index) ?: return false
    return SongUrlFetcher.isFlacTier(level)
}

@Composable
private fun AccountDialog(
    userProfile: PlaylistApi.UserProfile?,
    onDismiss: () -> Unit,
    onScanAuthorize: () -> Unit,
    onLogout: () -> Unit
) {
    val strings = LocalStrings.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(LocalMetroColors.current.surface)
                .padding(24.dp)
        ) {
            MetroText(
                strings.accountDialogTitle,
                color = LocalMetroColors.current.onBackground,
                style = LocalMetroTypography.current.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(16.dp))

            if (userProfile != null) {
                MetroText(
                    strings.nicknameLabel(userProfile.nickname),
                    color = LocalMetroColors.current.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                MetroText(
                    strings.uidLabel(userProfile.userId.toString()),
                    color = LocalMetroColors.current.onSurfaceVariant,
                    style = TextStyle(fontSize = 13.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(20.dp))
            }

            // 扫码授权其他设备：扫平板的登录二维码，经局域网把本机账号授权过去。
            FullWidthDialogButton(
                text = strings.scanEntryTitle,
                accent = false,
                borderColor = LocalMetroColors.current.primary,
                textColor = LocalMetroColors.current.primary,
                onClick = onScanAuthorize
            )
            Spacer(Modifier.height(12.dp))

            // 全宽按钮：容器 fillMaxWidth，文字 Center + 换行——极长翻译最多多占一行，不会撑破对话框。
            FullWidthDialogButton(
                text = strings.logoutButton,
                accent = false,
                borderColor = Color.Red.copy(alpha = 0.5f),
                textColor = Color.Red,
                onClick = onLogout
            )

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                DialogButton(
                    text = strings.close,
                    accent = false,
                    onClick = onDismiss
                )
            }
        }
    }
}

/** 短按钮：内容包裹式（wrap content）。用于对话框右下"取消/关闭/保存"。 */
@Composable
private fun DialogButton(
    text: String,
    accent: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .then(
                if (accent) Modifier.background(LocalMetroColors.current.primary)
                else Modifier.border(1.dp, LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.4f))
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        MetroText(
            text,
            color = if (accent) LocalMetroColors.current.onPrimary else LocalMetroColors.current.onSurfaceVariant,
            style = TextStyle(fontSize = 14.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 全宽按钮：填满可用宽度，文字居中，多行安全。用于对话框内的"更新 Cookie/退出登录"。 */
@Composable
private fun FullWidthDialogButton(
    text: String,
    accent: Boolean,
    borderColor: Color = LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.4f),
    textColor: Color = LocalMetroColors.current.onBackground,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (accent) Modifier.background(LocalMetroColors.current.primary)
                else Modifier.border(1.dp, borderColor)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        MetroText(
            text,
            color = if (accent) LocalMetroColors.current.onPrimary else textColor,
            style = TextStyle(fontSize = 14.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 语言下拉。闭合态显示当前选中语言名 + 箭头；点击弹出所有语言。
 *
 * 多语言鲁棒：闭合态 Text 设 maxLines=1 + Ellipsis + weight(1f)。极长名如
 * "Советский русский"/"Middle English" 最多截断，绝不换行撑破箭头位置。
 */
@Composable
fun MetroLanguageDropdown(
    selectedCode: String,
    presets: List<LanguagePreset>,
    onSelect: (String) -> Unit
) {
    val selected = presets.find { it.code == selectedCode } ?: presets.first()
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.4f))
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MetroText(
                selected.displayName,
                color = LocalMetroColors.current.onBackground,
                style = TextStyle(fontSize = 14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            MetroIcon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = LocalMetroColors.current.onSurfaceVariant,
                sizeDp = 20.dp,
            )
        }

        // UWP ComboBox 移植:选中项落回锚点原位,内部滚动到选中语言。
        val selectedIndex = presets.indexOfFirst { it.code == selectedCode }.coerceAtLeast(0)
        MetroSelectorFlyout(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            options = presets.map { it.displayName },
            selectedIndex = selectedIndex,
            onSelect = { index ->
                onSelect(presets[index].code)
                expanded = false
            },
        )
    }
}


/**
 * 统计应用缓存占用：图片磁盘缓存 + 缓存目录（含 WebView 缓存）+ 离线音频缓存。
 *
 * v1.6.0 · D1：离线音频放在 filesDir/offline/audio（不随系统清 cacheDir 消失），但它同样由
 * 用户可见的「清除缓存」清掉，所以计入同一个数字 —— 统计口径与可清理范围必须一致，
 * 否则用户会看到「占用 300MB、清完还剩 300MB」。
 */
private fun currentCacheSize(context: Context): Long {
    var total = 0L
    runCatching { coil.Coil.imageLoader(context).diskCache?.size?.let { total += it } }
    runCatching { context.cacheDir?.let { total += folderSize(it) } }
    runCatching { total += OfflineAudioCache.sizeBytes(context) }
    return total
}

private fun folderSize(dir: java.io.File): Long {
    var size = 0L
    dir.listFiles()?.forEach { f ->
        size += if (f.isDirectory) folderSize(f) else f.length()
    }
    return size
}
