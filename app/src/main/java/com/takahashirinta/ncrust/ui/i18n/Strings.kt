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
    /** API < 27 无系统 FLAC 解码器、且选中 FLAC 档位时的设置页提示（Bug1-C）。 */
    val qualityFlacUnsupportedHint: String,
    val playbackSectionTitle: String,
    val gaplessSectionTitle: String,
    val gaplessDescription: String,
    val lyricsTranslationLabel: String,
    val themeSectionTitle: String,
    val themeModeSectionTitle: String,
    val themeModeSystem: String,
    val themeModeDark: String,
    val themeModeLight: String,
    val themeColorNames: List<String>,
    val languageSectionTitle: String,
    val aboutButton: String,
    val storageSectionTitle: String,
    val cacheSizeLabel: (Long) -> String,
    val clearCache: String,
    val clearCacheConfirm: String,
    val cacheCleared: String,

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
)

/** 字节数格式化为人类可读的 B/KB/MB/GB，供 cacheSizeLabel 复用。 */
fun formatCacheBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
