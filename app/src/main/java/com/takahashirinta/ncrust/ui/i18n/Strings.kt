package com.takahashirinta.ncrust.ui.i18n

data class Strings(
    // Navigation tabs
    val tabHome: String,
    val tabLibrary: String,
    val tabSearch: String,
    val tabUser: String,

    // Common
    val cancel: String,
    val close: String,
    val retry: String,
    val loading: String,
    val back: String,

    // Auth / Account
    val accountDialogTitle: String,
    val nicknameLabel: (String) -> String,
    val uidLabel: (String) -> String,
    val logoutButton: String,
    val notLoggedIn: String,
    val loginHint: String,

    // User screen — sections
    val qualitySectionTitle: String,
    val wifiQualityLabel: String,
    val mobileQualityLabel: String,
    val qualityOptions: List<String>,
    /** 实际档位低于偏好档位时，播放器音质标签后的角标（Bug1-B）。 */
    val qualityDowngradedBadge: String,

    /** A3：实际文件低于请求档位，但该曲有这个档位 —— 账号/版权没给到。 */
    val qualityNoEntitlementBadge: String,

    /** A3：该曲本身就没有请求的档位。 */
    val qualitySongLacksTierBadge: String,

    // B2-C：主题色来源三选一
    val accentSourceSectionTitle: String,
    val accentSourcePreset: String,
    val accentSourceCover: String,
    val accentSourceSystem: String,
    val accentSourceSystemHint: String,

    /** B2-D：手动重新读取系统强调色。 */
    val accentSystemRefresh: String,

    /** E：榜单区块标题。 */
    val toplistSectionTitle: String,
    // v1.4.0 · 音乐人推荐卡片（首页分节标题 + 卡片副标题）
    val artistRecoTitle: String,
    val artistRecoDesc: String,
    /** API < 27 无系统 FLAC 解码器、且选中 FLAC 档位时的设置页提示（Bug1-C）。 */
    val qualityFlacUnsupportedHint: String,
    val playbackSectionTitle: String,
    val gaplessSectionTitle: String,
    val gaplessDescription: String,
    val lyricsTranslationLabel: String,
    /**
     * v1.5.0 · B 的逐字歌词布尔开关文案。v1.5.1 · A 起设置页改成三选一
     * （[lyricsWordAnimationLabel]），这一项只为迁移路径保留，已无 UI 入口。
     */
    val lyricsWordByWordLabel: String,
    // v1.5.1 · A：逐字动画三选一（0 渐变扫过 / 1 逐字硬切 / 2 关闭逐字）。顺序必须与
    // LyricsWordAnimationMode 的常量一一对应。
    val lyricsWordAnimationLabel: String,
    val lyricsWordAnimationOptions: List<String>,
    // v1.5.1 · D：媒体面板歌词开关（默认关）。开启后 ARTIST 变成「艺人 · 当前歌词行」。
    val lyricsInMediaSessionLabel: String,
    val lyricsInMediaSessionHint: String,
    // v1.5.2：逐字扫过质量三选一（0 自动 / 1 高级软边 / 2 兼容硬边）。顺序必须与
    // LyricsSweepQuality 的常量一一对应。
    val lyricsSweepQualityLabel: String,
    val lyricsSweepQualityOptions: List<String>,
    // v1.9.0：AMLL TTML 歌词源总开关（默认开）+ 与网易云歌词同时可用时是否优先用 TTML（默认开）。
    // 两者都只在「用户开了 TTML」时才有意义；关掉后行为与 v1.8.1 完全一致（一个 TTML 请求都不发）。
    val lyricsTtmlEnabledLabel: String,
    val lyricsTtmlFirstLabel: String,
    // v1.9.3：音译（罗马音 / 粤拼）显示开关，**默认关**。只影响显示——
    // 没有音译数据的歌打开后也没有任何变化（不会多出空行）。
    val lyricsRomanizationLabel: String,
    val lyricsRomanizationHint: String,
    // v2.0.0 · T2：播放时禁止熄屏（默认开）。
    val keepScreenOnLabel: String,
    val keepScreenOnHint: String,
    // v2.0.0 · T4：动态字号（实验性，默认关）。
    val dynamicFontLabel: String,
    val dynamicFontHint: String,
    // v1.5.1 · E：歌词字号（倍率档位文案是纯数字，与语言无关，不进 i18n）。
    val lyricsFontScaleLabel: String,
    /** 歌词界面 A- / A+ 的无障碍描述。 */
    val lyricsFontSmaller: String,
    val lyricsFontLarger: String,
    // v1.5.0 · C2：控制栏把手（全屏播放器底部那条 40×3dp 小横条）的无障碍描述。
    // 它此前对 TalkBack 完全不可见 —— 视力障碍用户收起控制栏后再也拿不回来。
    val controlsHandleLabel: String,
    /** P1：大屏幕模式入口按钮（横屏桌面播放器布局）。 */
    val bigScreenEnter: String,
    /** P1：大屏幕模式出口按钮。与入口是同一个按钮，横屏大屏下图标与描述切换。 */
    val bigScreenExit: String,
    // v1.8.0 · T4：应用内「自动旋转」。⚠️ 它只控制本应用是否跟随传感器，
    // 与系统设置里的"自动旋转"互相独立（应用既不读也不改系统设置）。
    /** 开启态（跟随传感器旋转；在播放器里转横屏会自动进入大屏幕模式）。 */
    val autoRotateOn: String,
    /** 关闭态（锁定竖屏；进出大屏幕模式只走 ⤢ 按钮）。 */
    val autoRotateOff: String,
    /** 设置页整行开关的标题。 */
    val autoRotateLabel: String,
    /** 设置页整行开关的说明。 */
    val autoRotateDescription: String,
    // v1.8.0 · T3：音频可视化（大屏幕模式左栏、封面下方的波形条）。
    /** 设置页整行开关的标题。 */
    val audioVisualizerLabel: String,
    /** 设置页整行开关的说明。 */
    val audioVisualizerDescription: String,
    val themeSectionTitle: String,
    val themeModeSectionTitle: String,
    val themeModeSystem: String,
    val themeModeDark: String,
    val themeModeLight: String,
    val themeColorNames: List<String>,
    val languageSectionTitle: String,
    val aboutButton: String,
    val storageSectionTitle: String,
    val clearCache: String,
    val clearCacheConfirm: String,
    /**
     * v2.0.0 · HF1：离线 / 缓存相关的 19 条文案**收进一个嵌套组**，不再是 [Strings] 的构造参数。
     *
     * 为什么必须这么做（真机启动崩溃的根因）：dex 的 `invoke-*` 指令寄存器数是 8 位 ⇒
     * **单个方法最多 255 个参数寄存器**。本文件是「一个 data class 装全部 UI 文案」的结构，
     * v1.9.3 时已有 **240** 个构造参数；v2.0.0 一次性加了 21 条（T2 两条、T4 两条、T3 十七条）
     * 后变成 **261** 个 ⇒ `zh_CN.kt` 顶层 `val zhCN = Strings(...)` 的 `<clinit>` 里那条 invoke
     * 越过上限，ART 校验**直接拒绝整个类**：PCL110（API 36 / release）与 S6（API 24 / debug）
     * 都表现为**启动即崩**：
     * `VerifyError: Verifier rejected class …Zh_CNKt: <clinit>() … expected 6 argument registers,
     * method signature has 7 or more`。
     *
     * ⚠️ **JVM 单测与编译都发现不了它** —— 那是 dex/ART 层面的限制，只有真机（或模拟器）能暴露。
     *
     * 拆分口径：只挪「离线 / 缓存」这一组（同一个功能面、内聚），其余属性一个都没动；
     * [Strings] 类体里保留 19 个**成员转发属性**（`val xxx get() = offline.xxx`），
     * 所以全仓库 `strings.offlineCacheXxx` / `strings.cacheSizeLabel` 的调用点一个字都不用改。
     *
     * ⚠️ 给后来者：`Strings` 的构造参数现在是 **243** 个；dex 单个方法最多 254 个参数寄存器，
     * 而带默认参数的类还会生成一个「参数 + 2」的合成构造函数 ⇒ **实际余量只剩 ~9 个**。
     * 再加字段请继续拆组（嵌套 data class + 转发属性），不要硬加到构造参数上。
     */
    val offline: OfflineStrings,

    /**
     * v2.1.0 · C：多音源（QQ 音乐）那一组。
     *
     * 为什么又拆一组：上面那条注释说得清楚 —— `Strings` 的构造参数已经贴着 dex 255 上限，
     * 再加字段**必须**继续拆组。这一组只有 3 条，但同样走分组，避免下次有人顺手往
     * 构造参数上直接加而踩上限（v2.0.0 · HF1 就是这么崩的）。
     */
    val source: SourceStrings,

    // v1.5.1 · C：无网络时的首页降级空态（标题 / 提示）。有缓存时会直接显示缓存，
    // 只有"一条都没有"时才轮到它。
    val networkOfflineTitle: String,
    val networkOfflineHint: String,

    // Home screen
    val dailySongsTitle: String,
    val recommendPlaylistTitle: String,
    val newSongsTitle: String,
    val refreshLabel: String,
    val noMoreContent: String,
    val trackCountSongs: (Int) -> String,
    // 主页推荐歌单里首位的私人 FM 电台卡（标题用用户昵称, 不是 uid）
    val fmRadioTitle: (String) -> String,
    // 拿不到用户资料时电台卡仍常驻的通用标题
    val fmRadioTitleGeneric: String,
    val fmRadioSubtitle: String,

    // Library screen
    val categoryTracks: String,
    val categoryAlbums: String,
    val categoryPlaylists: String,
    val noSavedSongs: String,
    val noSavedAlbums: String,
    val noPlaylists: String,
    val notLoggedInForPlaylists: String,
    val loadFailed: (String?) -> String,
    val trackCount: (Int) -> String,
    val albumArtistAndCount: (String, Int) -> String,

    // Search screen
    val searchCategoryTracks: String,
    val searchCategoryAlbums: String,
    val searchCategoryArtists: String,
    val searchPlaceholder: String,
    val searchSongsEmpty: String,
    val searchAlbumsEmpty: String,
    val searchArtistsEmpty: String,

    // Player controls
    val prevButton: String,
    val playButton: String,
    val pauseButton: String,
    val nextButton: String,
    val lyricsButton: String,
    val queueButton: String,
    val addToLibraryButton: String,

    // Song / queue actions
    val actionAddToLibrary: String,
    val actionInsertNext: String,
    val actionAppendToQueue: String,
    val actionGoToArtist: String,
    val actionGoToAlbum: String,

    // Background activity permission (battery optimization whitelist)
    val batteryTitle: String,
    val batteryMessage: String,
    val batteryAllow: String,
    val batteryLater: String,

    // Native QR login (tablet / large screen)
    val qrLoginTitle: String,
    val qrScanHint: String,
    val qrScannedHint: String,
    val qrExpiredHint: String,
    val qrLoadFailed: String,
    val qrGenericLogin: String,

    // Phone-side QR scan to authorize another device (LAN cookie handoff)
    val scanEntryTitle: String,
    val scanPrompt: String,
    val scanPermissionNeeded: String,
    val scanNoCookie: String,
    val scanSuccess: String,
    val scanFailed: String,
    val scanConnecting: String,

    val clearQueue: String,
    val actionAddToPlaylist: String,
    val actionRemoveFromLibrary: String,
    val actionSaveAlbum: String,
    // 专辑已收藏时的按钮文案(取消收藏)
    val actionUnsaveAlbum: String,
    val unknownArtist: String,
    val playAllButton: String,

    // Play-all dialog
    val songCountFormat: (Int) -> String,
    /** 断点续播提示，参数是已格式化的时间点（如 "1:23"）。 */
    val resumeFromFormat: (String) -> String,

    // User screen — 自定义背景（v1.2.0 · B3）
    val bgSectionTitle: String,
    val bgPick: String,
    val bgChange: String,
    val bgRemove: String,
    val bgImportFailed: String,
    val playNowTitle: String,
    val playNowDesc: String,
    val insertNextTitle: String,
    val insertNextDesc: String,

    // About
    val aboutTitle: String,
    val aboutAppSubtitle: String,
    val aboutSectionProject: String,
    val aboutVersion: String,
    val aboutDeveloperOriginal: String,
    val aboutDeveloperFork: String,
    val aboutLicense: String,
    val aboutLicenseGplWithMit: String,
    val aboutRepositoryFork: String,
    val aboutRepositoryOriginal: String,
    val aboutSectionTechStack: String,
    val aboutLangLabel: String,
    val aboutUIFrameworkLabel: String,
    val aboutDesignSystemLabel: String = "Design System",
    val aboutAudioEngineLabel: String,
    val aboutNetworkLabel: String,
    val aboutImageLabel: String,
    val aboutSectionTeam: String,
    val aboutRoleDev: String,
    val aboutRoleTester: String,
    val aboutRoleForkMaintainer: String,
    val aboutSectionCredits: String,
    val aboutCreditCli: String,
    val aboutCreditAnim: String,
    val aboutCreditDesign: String,

    // Player UI
    val noLyrics: String,
    val emptyQueue: String,
    val collapsePlayer: String,

    // Detail page titles and content
    val artistDetailTitle: String,
    val albumDetailTitle: String,
    val playlistDetailTitle: String,
    val unknownArtistName: String,
    val noAlbums: String,
    val noHotSongs: String,
    val artistAlbumCount: (Int) -> String,
    val artistSongCount: (Int) -> String,
    val albumReleaseDate: (String) -> String,
    val albumLabel: (String) -> String,
    val artistDataLoadFailed: (Int) -> String,
    val artistStats: (Int, Int) -> String,

    // Accessibility content descriptions
    val coverDesc: String,
    val albumCoverDesc: String,
    val artistAvatarDesc: String,
    val playlistCoverDesc: String,
    val userAvatarDesc: String,

    // Search
    val clearSearchButton: String,

    // Song detail screen
    val songDetailTitle: String,
    val unknownAlbum: String,
    val lyricsLabel: String,

    // Player queue panel
    val queueTitle: String,
    val playModeButton: String,
    val saveAsPlaylist: String,
    val noSongPlaying: String,
    val queueSectionPast: String,
    val queueSectionNow: String,
    val queueSectionUpcoming: String,
    val queueInfinityPlaceholder: String,
    val queueClearAll: String,

    // User screen
    val userIconDesc: String,

    // Search history
    val searchHistoryClear: String,
    val searchHistoryEmpty: String,
    val searchHistoryDelete: String,

    // Feedback toasts
    val addedToLibrary: String,
    val removedFromLibrary: String,

    // Playlist management (v1.3.0 · B2)
    val playlistCreateTitle: String,
    val playlistNameHint: String,
    val playlistPrivacy: String,
    val playlistPrivacyPublic: String,
    val playlistPrivacyPrivate: String,
    val playlistCreateConfirm: String,
    /** 含歌单名，如「已创建「我的歌单」」。 */
    val playlistCreated: (String) -> String,
    /** 创建成功后往歌单里塞歌的结果，count = 实际提交的曲目数。 */
    val playlistSongsAdded: (Int) -> String,
    val playlistCreateFailed: String,
    /** 服务端 405 限流，冷却可能超过 10 分钟。 */
    val playlistOpTooFrequent: String,

    // Add-to-playlist sheet (v1.3.0 · B3)
    val addToPlaylistTitle: String,
    val playlistNew: String,
    val playlistNoOwned: String,
    val addToPlaylistSuccess: String,
    /** 502 = 歌曲已在歌单里，按幂等成功提示。 */
    val addToPlaylistDuplicate: String,
    val addToPlaylistFailed: String,
    /** 歌曲长按菜单里的入口名（v1.3.0 · B3 起是真正的功能，此前只被当成队列按钮的 contentDescription）。 */
    val actionAddToPlaylistSheet: String,

    // Playlist edit / delete (v1.3.0 · B4)
    val playlistEditTitle: String,
    val playlistDescHint: String,
    val playlistSave: String,
    val playlistUpdated: String,
    /** 含歌单名，如「确定删除「我的歌单」？」 */
    val playlistDeleteConfirm: (String) -> String,
    val playlistDeleteWarning: String,
    val playlistDeleted: String,
    val playlistDeleteFailed: String,
    val playlistUnsubscribe: String,
    /** 从歌单里移除这首歌（仅自建歌单显示）。 */
    val removeFromPlaylist: String,
    /** 移除成功的提示（与 removeFromPlaylist 这个动作名分开，避免出现「从歌单移除」当反馈）。 */
    val removedFromPlaylist: String,
    val playlistDelete: String,
) {
    // ---------- 转发属性（v2.0.0 · HF1）----------
    // 离线 / 缓存那一组（19 条）的构造参数已经挪进 [OfflineStrings]，这里用**成员**转发属性把
    // 调用点（strings.offlineCacheXxx / strings.cacheSizeLabel / strings.cacheCleared）原样保住。
    // 刻意不用顶层扩展属性：扩展属性在别的包里要逐条 import，而成员属性对 `LocalStrings.current.x`
    // 天然可见。成员属性不进构造函数，所以不会再撑大那个已经贴着 dex 255 上限的参数表。
    val offlineCacheUsage: (String, String, String) -> String get() = offline.offlineCacheUsage
    val offlineCacheListTitle: (Int) -> String get() = offline.offlineCacheListTitle
    val offlineCachePartial: String get() = offline.offlineCachePartial
    val offlineCacheLimitHint: String get() = offline.offlineCacheLimitHint
    val offlineCachePlayingLocked: String get() = offline.offlineCachePlayingLocked
    val offlineCacheFragmentNotice: String get() = offline.offlineCacheFragmentNotice
    val offlineCacheDeleteConfirm: (String) -> String get() = offline.offlineCacheDeleteConfirm
    val cacheSizeLabel: (Long) -> String get() = offline.cacheSizeLabel
    val offlineCacheManageLabel: String get() = offline.offlineCacheManageLabel
    val offlineCacheTitle: String get() = offline.offlineCacheTitle
    val offlineCacheLimitLabel: String get() = offline.offlineCacheLimitLabel
    val offlineCacheEmpty: String get() = offline.offlineCacheEmpty
    val offlineCacheDeleteTrack: String get() = offline.offlineCacheDeleteTrack
    val offlineCacheDeleteTitle: String get() = offline.offlineCacheDeleteTitle
    val offlineCacheDeleted: String get() = offline.offlineCacheDeleted

    // ---------- v2.1.0 · C：多音源那一组的转发属性 ----------
    val sourceQqMusic: String get() = source.sourceQqMusic
    val sourceQqAccount: String get() = source.sourceQqAccount
    val sourceQqLoginAction: String get() = source.sourceQqLoginAction
    val sourceSummary: (Int, Int) -> String get() = source.sourceSummary
    val sourceQrWaiting: String get() = source.sourceQrWaiting
    val sourceQrScanned: String get() = source.sourceQrScanned
    val sourceQrExpired: String get() = source.sourceQrExpired
    val sourceQrFailed: String get() = source.sourceQrFailed
    val sourceQrLoadFailed: String get() = source.sourceQrLoadFailed
    val sourceQrNetworkHint: String get() = source.sourceQrNetworkHint
    val sourceQrServiceUnavailable: String get() = source.sourceQrServiceUnavailable
    val sourceQrSwitchingToWeb: String get() = source.sourceQrSwitchingToWeb
    val sourceQrAvailabilityNote: String get() = source.sourceQrAvailabilityNote
    val sourceQrRefresh: String get() = source.sourceQrRefresh
    val sourceWebLogin: String get() = source.sourceWebLogin

    // v2.1.1：播放页音源角标 + 手机号验证码登录（都在 `source` 分组里，理由见那边的注释）
    val sourceNetease: String get() = source.sourceNetease
    val sourceQqPhoneTitle: String get() = source.sourceQqPhoneTitle
    val sourceQqPhoneLabel: String get() = source.sourceQqPhoneLabel
    val sourceQqPhoneHint: String get() = source.sourceQqPhoneHint
    val sourceQqSendCode: String get() = source.sourceQqSendCode
    val sourceQqResendCode: String get() = source.sourceQqResendCode
    val sourceQqCodeLabel: String get() = source.sourceQqCodeLabel
    val sourceQqCodeHint: String get() = source.sourceQqCodeHint
    val sourceQqPhoneSubmit: String get() = source.sourceQqPhoneSubmit
    val sourceQqCodeSent: String get() = source.sourceQqCodeSent
    val sourceQqPhoneBadNumber: String get() = source.sourceQqPhoneBadNumber
    val sourceQqCodeWrong: String get() = source.sourceQqCodeWrong
    val sourceQqPhoneTooFrequent: String get() = source.sourceQqPhoneTooFrequent
    val sourceQqPhoneNeedCaptcha: String get() = source.sourceQqPhoneNeedCaptcha
    val sourceQqPhoneNote: String get() = source.sourceQqPhoneNote
    val sourceQqCaptchaHint: String get() = source.sourceQqCaptchaHint
    val sourceQqCaptchaRetry: String get() = source.sourceQqCaptchaRetry
    val sourceQqCodeSendFailed: String get() = source.sourceQqCodeSendFailed
    val sourceQqLoginFailed: String get() = source.sourceQqLoginFailed
    val sourceQqAccountRestricted: String get() = source.sourceQqAccountRestricted
    val sourceQqDeviceLimit: String get() = source.sourceQqDeviceLimit
    val sourceQqLoginRateLimited: String get() = source.sourceQqLoginRateLimited
    val cacheUsageAudio: String get() = offline.cacheUsageAudio
    val cacheUsageImage: String get() = offline.cacheUsageImage
    val cacheUsageOther: String get() = offline.cacheUsageOther
    val cacheCleared: String get() = offline.cacheCleared
}

/** 字节数格式化为人类可读的 B/KB/MB/GB，供 cacheSizeLabel 复用。 */
fun formatCacheBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

/**
 * v2.0.0 · HF1：离线 / 缓存文案组。存在的唯一理由是 dex 单方法 255 参数寄存器上限，
 * 详见 [Strings.offline] 的 KDoc —— 语义上它们本来就是同一个功能面（离线缓存 + 存储占用）。
 *
 * 文案契约（v2.0.0 · T3，合规相关）：一律用「已缓存 / 缓存」，**不承诺「整曲完整」** ——
 * media3 SimpleCache 只保证「播放过的片段在本地」，不是「下载了一整首」。
 */
data class OfflineStrings(
    /** (已用, 上限, 剩余)，三个都已由 formatCacheBytes 格式化。 */
    val offlineCacheUsage: (String, String, String) -> String,
    /** 已缓存曲目列表标题。 */
    val offlineCacheListTitle: (Int) -> String,
    /** 量不到该曲占用时的占位文案（「已缓存片段」）。 */
    val offlineCachePartial: String,
    /** 上限「下次启动生效」的如实说明（淘汰器在启动时固化）。 */
    val offlineCacheLimitHint: String,
    /** 当前播放曲目禁用删除时的说明。 */
    val offlineCachePlayingLocked: String,
    /** 「不保证整曲完整」的常驻提示。 */
    val offlineCacheFragmentNotice: String,
    val offlineCacheDeleteConfirm: (String) -> String,
    val cacheSizeLabel: (Long) -> String,
    val offlineCacheManageLabel: String,
    val offlineCacheTitle: String,
    val offlineCacheLimitLabel: String,
    val offlineCacheEmpty: String,
    val offlineCacheDeleteTrack: String,
    val offlineCacheDeleteTitle: String,
    val offlineCacheDeleted: String,
    val cacheUsageAudio: String,
    val cacheUsageImage: String,
    val cacheUsageOther: String,
    val cacheCleared: String,
)

/**
 * v2.1.0 · C：多音源相关的文案。
 *
 * 只有品牌名与账号区块标题：其余交互（登录 / 登出 / 未登录）直接复用既有的
 * `loginHint` / `logoutButton` / `notLoggedIn`，不重复造一遍同义文案 ——
 * 8 种语言各多一条同义句，维护成本是实打实的。
 */
data class SourceStrings(
    /** 品牌名。**各语言保持一致**（专有名词不翻译）。 */
    val sourceQqMusic: String,
    /** 账号区块标题，例如「QQ 音乐账号」。 */
    val sourceQqAccount: String,
    /** 登录动作，例如「登录 QQ 音乐」。 */
    val sourceQqLoginAction: String,
    /**
     * 搜索结果顶部那行小字：`(网易云条数, QQ 音乐条数) -> 文案`。
     *
     * 为什么需要它：`SongCard` 只在**非网易云**的行上标音源，于是纯网易云的结果
     * 在界面上看不出「来自哪里」——真机反馈正是「现在有了稻香，似乎是网易云的搜索结果？」。
     */
    val sourceSummary: (Int, Int) -> String,
    /** 二维码等待扫码。 */
    val sourceQrWaiting: String,
    /** 已扫码、等手机端确认。 */
    val sourceQrScanned: String,
    /** 二维码已过期。 */
    val sourceQrExpired: String,
    /** 扫码登录失败。 */
    val sourceQrFailed: String,
    /** 二维码获取失败。 */
    val sourceQrLoadFailed: String,
    /** 轮询拿不到响应时的提示（网络/风控）。 */
    val sourceQrNetworkHint: String,
    /** v2.1.1：服务端明确回绝（403/空 body）时的提示 —— 与「网络不稳定」是两回事。 */
    val sourceQrServiceUnavailable: String,
    /** v2.1.1：连续被拒、准备自动切到网页登录时的提示。 */
    val sourceQrSwitchingToWeb: String,
    /** v2.1.1：设置页 QQ 账号卡片上的可用性说明。 */
    val sourceQrAvailabilityNote: String,
    /** 刷新二维码。 */
    val sourceQrRefresh: String,
    /** 改用网页登录。 */
    val sourceWebLogin: String,

    // ---------- v2.1.1：播放页音源角标 + QQ 音乐手机号验证码登录 ----------
    //
    // ⚠️ 为什么这些**必须**放在本分组里，而不是加到外层 `Strings` 的构造参数上：
    // 外层构造函数已经有 **244 个参数 = 245 个 dex 寄存器**（含 this），上限是 255。
    // v2.0.0 · HF1 就是因为往它上面直接加字段而崩的（见外层那段注释）。
    // 本分组只有 15 个参数，随便加；外层一个都不加 —— 于是本版对那个上限的占用是 0。

    /** 网易云（音源名，与 [sourceQqMusic] 对称）。只用在播放页的音源角标上。 */
    val sourceNetease: String,
    /** 手机号登录浮层标题。 */
    val sourceQqPhoneTitle: String,
    /** 手机号输入框标签。 */
    val sourceQqPhoneLabel: String,
    /** 手机号输入框占位。 */
    val sourceQqPhoneHint: String,
    /** 发送验证码按钮。 */
    val sourceQqSendCode: String,
    /** 重新发送验证码按钮（倒计时结束后）。 */
    val sourceQqResendCode: String,
    /** 验证码输入框标签。 */
    val sourceQqCodeLabel: String,
    /** 验证码输入框占位。 */
    val sourceQqCodeHint: String,
    /** 提交登录按钮。 */
    val sourceQqPhoneSubmit: String,
    /** 验证码已发送。 */
    val sourceQqCodeSent: String,
    /** 手机号格式不对（本地校验就拦下，不发请求）。 */
    val sourceQqPhoneBadNumber: String,
    /** 验证码不对或已过期。 */
    val sourceQqCodeWrong: String,
    /** 发得太频繁。 */
    val sourceQqPhoneTooFrequent: String,
    /** 服务端要图形验证码 —— 手机号这条路走不通，引导去网页登录。 */
    val sourceQqPhoneNeedCaptcha: String,
    /** 合规说明：验证码由腾讯下发、本应用不读短信也不存手机号。 */
    val sourceQqPhoneNote: String,
    /** v2.1.1：风控要求图形验证码时，验证页顶部的说明。 */
    val sourceQqCaptchaHint: String,
    /** v2.1.1：验证页的手动兜底按钮（cookie 变化检测没触发时用）。 */
    val sourceQqCaptchaRetry: String,

    // ---------- v2.1.3：手机号登录自己的失败文案 ----------
    //
    // ⚠️ 这一组是**用户报出来的 bug 的直接修复**：v2.1.2 的手机号登录在失败分支里
    // 复用了 `sourceQrFailed`（「扫码登录失败」），于是短信登录失败时界面显示
    // 「扫码登录失败」—— 用户原话「我短信验证码登陆为什么会显示扫码登录失败」。
    // 那句话在任何情况下都是错的：短信登录与扫码登录是两条完全不同的链路。
    // **教训：跨功能的文案不要复用**，哪怕字面上只是「登录失败」四个字。

    /** 验证码发送失败（认不出的服务端码）。 */
    val sourceQqCodeSendFailed: String,
    /** 登录失败（认不出的服务端码）。 */
    val sourceQqLoginFailed: String,
    /** 账号受限/封禁（20277/20278/20450）。 */
    val sourceQqAccountRestricted: String,
    /** 登录设备数超限（20279）。 */
    val sourceQqDeviceLimit: String,
    /** 登录过于频繁（104604）。 */
    val sourceQqLoginRateLimited: String,
)
