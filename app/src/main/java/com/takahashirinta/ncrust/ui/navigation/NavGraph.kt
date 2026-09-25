package com.takahashirinta.ncrust.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.screen.*
import com.takahashirinta.ncrust.ui.theme.AppMotion
import com.takahashirinta.ncrust.ui.theme.PageTransitionSetting
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object NavRoutes {
    const val HOME = "home"
    const val ALBUM = "album/{albumId}"
    const val ARTIST = "artist/{artistId}"
    const val PLAYLIST = "playlist/{playlistId}/{playlistName}/{playlistCoverUrl}"
    const val SONG_DETAIL = "song/{songId}"

    /**
     * v2.2.0：QQ 音乐歌单详情。
     *
     * 详情路由把**身份三元组**编进路径：`playlistId`(tid) + `ownerId`(uin) + `dirId`。
     * ownerId 必须进路由 —— 否则「A 账号点进歌单 → 返回 → 切到 B 账号 → 系统恢复同一条
     * 路由」会拿 A 的 playlistId 去 B 的账号下查，运气好是 10004，运气不好是另一个歌单。
     *
     * v2.3.0 · A：**`QQ_PLAYLISTS` 列表路由与 `QqPlaylistScreen` 已删除** ——
     * 歌单列表现在平铺在库页的「歌单」tab 里（`LibraryPlaylistsTab`），
     * 「一步可达」之后那个二级列表页就没有存在意义了（留着它等于留一条没有入口的死路由）。
     */
    const val QQ_PLAYLIST_DETAIL = "qqplaylist/{playlistId}/{ownerId}/{dirId}/{playlistName}"

    /**
     * v2.3.0 · B：**本地歌单**详情 / 编辑页。
     *
     * 路由把 [PlaylistKey] 的三元组原样编进路径（与 QQ 详情同一条纪律：
     * 归属账号必须进路由，否则「A 账号点进去 → 返回 → 切到 B 账号 → 系统恢复同一条路由」
     * 会拿 A 的歌单 id 去 B 的账号下查）。
     *
     * 这里**没有** `dirId`：本地歌单的 `dirId` 是载荷、存在本地记录里
     * （`LocalPlaylist.dirId`），请求详情时由仓库读出来 —— 路由里再带一份就成了第二处真相。
     */
    const val LOCAL_PLAYLIST_DETAIL = "localplaylist/{source}/{ownerId}/{playlistId}"

    /**
     * v2.4.0 · E：**带音源的**详情路由（艺人 / 专辑 / 单曲）。
     *
     * ## 为什么必须新增一套而不是改旧的
     *
     * 旧路由的 id 参数是 [NavType.LongType]，而 **QQ 音乐的身份是字符串**：
     * 艺人是 `singerMID`、专辑是 albumMid、单曲是 songmid（形状 `0025NhlN2yWrP4`）。
     * 用 Long 装它只有两条路，两条都是错的：要么给 QQ 编一个假数字 id
     * （那正是「给错专辑 / 错单曲」的温床），要么把它当网易云的 id 用。
     *
     * 所以新的三段路由里 `source` 与 id **一律是 `StringType`**（QQ 的 id 天然是字符串，
     * 网易云的十进制 id 写成字符串也不丢信息），旧的单段 `Long` 路由**原样保留**：
     * 剪贴板识别、`resolveAndNavigate`、首页/库页那些「只有网易云 id」的老调用点
     * 一个都不用改（它们构造出的就是网易云身份，见下面的 composable）。
     *
     * 注册顺序上两套路由互不冲突：`album/{albumId}` 只吃一段路径，
     * `album/{source}/{albumId}` 吃两段，导航库的深链正则无法互相匹配。
     */
    const val ARTIST_SRC = "artist/{source}/{artistId}"
    const val ALBUM_SRC = "album/{source}/{albumId}"
    const val SONG_SRC = "song/{source}/{songId}"

    fun album(albumId: Long) = "album/$albumId"
    fun artist(artistId: Long) = "artist/$artistId"
    fun playlist(id: Long, name: String = "", coverUrl: String = "") =
        "playlist/$id/${URLEncoder.encode(name, StandardCharsets.UTF_8.toString())}/${URLEncoder.encode(coverUrl, StandardCharsets.UTF_8.toString())}"
    fun song(songId: Long) = "song/$songId"

    /** v2.4.0 · E：带音源的艺人路由（QQ 的 id 是 `singerMID`）。 */
    fun artist(source: MusicSource, id: String) = "artist/${source.key}/$id"

    /** v2.4.0 · E：带音源的专辑路由。 */
    fun album(source: MusicSource, id: String) = "album/${source.key}/$id"

    /** v2.4.0 · E：带音源的单曲路由。 */
    fun song(source: MusicSource, id: String) = "song/${source.key}/$id"

    fun qqPlaylistDetail(id: String, ownerId: String, dirId: Long, name: String) =
        "qqplaylist/$id/$ownerId/$dirId/" + URLEncoder.encode(name, StandardCharsets.UTF_8.toString())

    /** v2.3.0 · B：本地歌单路由。三段都做 URL 编码 —— `PlaylistKey.id` 可能是 `local:...`。 */
    fun localPlaylist(sourceKey: String, ownerId: String, playlistId: String): String =
        "localplaylist/" + URLEncoder.encode(sourceKey, StandardCharsets.UTF_8.toString()) +
            "/" + URLEncoder.encode(ownerId, StandardCharsets.UTF_8.toString()) +
            "/" + URLEncoder.encode(playlistId, StandardCharsets.UTF_8.toString())
}

@Composable
fun MainNavGraph(
    navController: NavHostController,
    onSongClick: (SongItem) -> Unit,
    onReplaceAndPlay: (List<SongItem>) -> Unit = {},
    onInsertNext: (List<SongItem>) -> Unit = {},
    onSongInsertNext: (SongItem) -> Unit = {},
    onSongAppendToQueue: (SongItem) -> Unit = {},
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> },
    /**
     * v2.5.1 · F：页面转场开关（「页面切换动效」，默认启用）。
     *
     * 由 `MainScreen` 持有状态（设置页改一次 → 这里立刻拿到新值 → 下一次导航即生效，
     * **不需要重启**）。默认参数取 [PageTransitionSetting.DEFAULT_ENABLED] 而不是写死 `true`，
     * 是为了让「默认值只有一处真相」。
     */
    pageTransitionEnabled: Boolean = PageTransitionSetting.DEFAULT_ENABLED,
    startDestination: String = NavRoutes.HOME
) {
    // v2.5.1 · F：页面切换动效**用户可配，默认启用**。
    //
    // v2.5.0 的注释（「页面转场期间新旧两页同帧渲染, slide/fade 每帧都要全屏合成,
    // 低端机上是切换动作的主要掉帧源, 所以显式关掉」）描述的**代价依然成立**，
    // 本版没有推翻它 —— 推翻的是它的**处置方式**：
    // 从「一刀切不做」改成「默认启用 + 用户可关」，关掉时逐字节回到 v2.5.0 的行为。
    // 用户拍板见 TASK / v2.5.1 release notes；量化数据（release 包、两台真机、
    // dumpsys gfxinfo framestats）见 docs/verification/v2.5.1/。
    //
    // 四个转场都在**导航发生的那一刻**求值（lambda 体内），所以
    // 「设置页关掉 → 下一次点进详情页就已经没有动画」，无需重启、无需重建 NavHost。
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { pageEnterTransition(pageTransitionEnabled) },
        exitTransition = { pageExitTransition(pageTransitionEnabled) },
        popEnterTransition = { pagePopEnterTransition(pageTransitionEnabled) },
        popExitTransition = { pagePopExitTransition(pageTransitionEnabled) }
    ) {
        composable(NavRoutes.HOME) {
            // 不渲染任何内容，由 MainScreen 的 Scaffold 内容填充
        }

        composable(
            route = NavRoutes.ALBUM,
            arguments = listOf(navArgument("albumId") { type = NavType.LongType })
        ) { backStackEntry ->
            val albumId = backStackEntry.arguments?.getLong("albumId") ?: return@composable
            // 旧路由只有网易云的十进制 id ⇒ 它构造出来的**就是网易云身份**。
            // 这里不猜、不查表：能走到这条路由的调用点（剪贴板识别、resolveAndNavigate、
            // 首页/库页）本来就只认识网易云的 id。
            AlbumDetailScreen(
                sourceKey = MusicSource.NETEASE.key,
                albumId = albumId.toString(),
                onBack = { navController.popBackStack() },
                onSongClick = onSongClick,
                onArtistClick = { source, artistId ->
                    navController.navigate(NavRoutes.artist(source, artistId))
                },
                onReplaceAndPlay = onReplaceAndPlay,
                onInsertNext = onInsertNext,
                onSongInsertNext = onSongInsertNext,
                onSongAppendToQueue = onSongAppendToQueue,
                onShowSongMenu = onShowSongMenu
            )
        }

        composable(
            route = NavRoutes.ALBUM_SRC,
            arguments = listOf(
                navArgument("source") { type = NavType.StringType },
                navArgument("albumId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val sourceKey = backStackEntry.arguments?.getString("source") ?: return@composable
            val albumId = backStackEntry.arguments?.getString("albumId") ?: return@composable
            AlbumDetailScreen(
                sourceKey = sourceKey,
                albumId = albumId,
                onBack = { navController.popBackStack() },
                onSongClick = onSongClick,
                onArtistClick = { source, artistId ->
                    navController.navigate(NavRoutes.artist(source, artistId))
                },
                onReplaceAndPlay = onReplaceAndPlay,
                onInsertNext = onInsertNext,
                onSongInsertNext = onSongInsertNext,
                onSongAppendToQueue = onSongAppendToQueue,
                onShowSongMenu = onShowSongMenu
            )
        }

        composable(
            route = NavRoutes.ARTIST,
            arguments = listOf(navArgument("artistId") { type = NavType.LongType })
        ) { backStackEntry ->
            val artistId = backStackEntry.arguments?.getLong("artistId") ?: return@composable
            ArtistDetailScreen(
                sourceKey = MusicSource.NETEASE.key,
                artistId = artistId.toString(),
                onBack = { navController.popBackStack() },
                onSongClick = onSongClick,
                onAlbumClick = { source, id -> navController.navigate(NavRoutes.album(source, id)) },
                onSongInsertNext = onSongInsertNext,
                onSongAppendToQueue = onSongAppendToQueue,
                onShowSongMenu = onShowSongMenu
            )
        }

        composable(
            route = NavRoutes.ARTIST_SRC,
            arguments = listOf(
                navArgument("source") { type = NavType.StringType },
                navArgument("artistId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val sourceKey = backStackEntry.arguments?.getString("source") ?: return@composable
            val artistId = backStackEntry.arguments?.getString("artistId") ?: return@composable
            ArtistDetailScreen(
                sourceKey = sourceKey,
                artistId = artistId,
                onBack = { navController.popBackStack() },
                onSongClick = onSongClick,
                onAlbumClick = { source, id -> navController.navigate(NavRoutes.album(source, id)) },
                onSongInsertNext = onSongInsertNext,
                onSongAppendToQueue = onSongAppendToQueue,
                onShowSongMenu = onShowSongMenu
            )
        }

        composable(
            route = NavRoutes.PLAYLIST,
            arguments = listOf(
                navArgument("playlistId") { type = NavType.LongType },
                navArgument("playlistName") { type = NavType.StringType; defaultValue = "" },
                navArgument("playlistCoverUrl") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val playlistId = backStackEntry.arguments?.getLong("playlistId") ?: return@composable
            // ← 解码
            val name = URLDecoder.decode(
                backStackEntry.arguments?.getString("playlistName") ?: "",
                StandardCharsets.UTF_8.toString()
            )
            val cover = URLDecoder.decode(
                backStackEntry.arguments?.getString("playlistCoverUrl") ?: "",
                StandardCharsets.UTF_8.toString()
            )
            PlaylistDetailScreen(
                playlistId = playlistId,
                playlistName = name,
                playlistCoverUrl = cover,
                onBack = { navController.popBackStack() },
                onSongClick = onSongClick,
                onReplaceAndPlay = onReplaceAndPlay,
                onInsertNext = onInsertNext,
                onSongInsertNext = onSongInsertNext,
                onSongAppendToQueue = onSongAppendToQueue,
                onShowSongMenu = onShowSongMenu
            )
        }

        composable(
            route = NavRoutes.QQ_PLAYLIST_DETAIL,
            arguments = listOf(
                navArgument("playlistId") { type = NavType.StringType },
                navArgument("ownerId") { type = NavType.StringType },
                navArgument("dirId") { type = NavType.LongType },
                navArgument("playlistName") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val pid = backStackEntry.arguments?.getString("playlistId") ?: return@composable
            val owner = backStackEntry.arguments?.getString("ownerId") ?: return@composable
            val dirId = backStackEntry.arguments?.getLong("dirId") ?: 0L
            val name = URLDecoder.decode(
                backStackEntry.arguments?.getString("playlistName") ?: "",
                StandardCharsets.UTF_8.toString()
            )
            QqPlaylistDetailScreen(
                playlistId = pid,
                ownerId = owner,
                dirId = dirId,
                playlistName = name,
                onBack = { navController.popBackStack() },
                onSongClick = onSongClick,
                onReplaceAndPlay = onReplaceAndPlay,
                onSongInsertNext = onSongInsertNext,
                onSongAppendToQueue = onSongAppendToQueue,
                onShowSongMenu = onShowSongMenu
            )
        }

        composable(
            route = NavRoutes.LOCAL_PLAYLIST_DETAIL,
            arguments = listOf(
                navArgument("source") { type = NavType.StringType },
                navArgument("ownerId") { type = NavType.StringType },
                navArgument("playlistId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val sourceKey = URLDecoder.decode(
                backStackEntry.arguments?.getString("source") ?: "",
                StandardCharsets.UTF_8.toString()
            )
            val owner = URLDecoder.decode(
                backStackEntry.arguments?.getString("ownerId") ?: "",
                StandardCharsets.UTF_8.toString()
            )
            val pid = URLDecoder.decode(
                backStackEntry.arguments?.getString("playlistId") ?: "",
                StandardCharsets.UTF_8.toString()
            )
            LocalPlaylistDetailScreen(
                playlistKey = com.takahashirinta.ncrust.source.PlaylistKey(
                    source = com.takahashirinta.ncrust.source.MusicSource.fromKey(sourceKey),
                    id = pid,
                    ownerId = owner,
                ),
                onBack = { navController.popBackStack() },
                onSongClick = onSongClick,
                onReplaceAndPlay = onReplaceAndPlay,
                onSongInsertNext = onSongInsertNext,
                onSongAppendToQueue = onSongAppendToQueue,
                onShowSongMenu = onShowSongMenu
            )
        }

        composable(
            route = NavRoutes.SONG_DETAIL,
            arguments = listOf(navArgument("songId") { type = NavType.LongType })
        ) { backStackEntry ->
            val songId = backStackEntry.arguments?.getLong("songId") ?: return@composable
            // 旧路由 = 网易云身份（理由同 ALBUM 那一段）。
            SongDetailScreen(
                sourceKey = MusicSource.NETEASE.key,
                songId = songId.toString(),
                onBack = { navController.popBackStack() },
                onPlay = onSongClick
            )
        }

        composable(
            route = NavRoutes.SONG_SRC,
            arguments = listOf(
                navArgument("source") { type = NavType.StringType },
                navArgument("songId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val sourceKey = backStackEntry.arguments?.getString("source") ?: return@composable
            val songId = backStackEntry.arguments?.getString("songId") ?: return@composable
            SongDetailScreen(
                sourceKey = sourceKey,
                songId = songId,
                onBack = { navController.popBackStack() },
                onPlay = onSongClick
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// v2.5.1 · F：页面转场的四个方向
//
// 分散在四个 private 函数里，是为了让「关掉时到底挂的是什么」一眼可查：
// 四个函数的第一行**都是**同一个短路 `if (!enabled) …None`。
// 这样「关闭时不能有残留动画计算」不是靠调用点自觉，而是这四条路径的唯一形状。
//
// 位移取屏宽的 1/12（`PAGE_SLIDE_DIVISOR`）：足够读出方向，又不至于让新旧两页
// 各滑过半个屏幕（滑得越远，转场期间需要重绘的像素带越宽）。这个「小位移 + 淡入淡出」
// 的组合是低端机的折中：方向感来自位移，遮盖感来自 alpha，两者都不需要额外测量。
//
// 时长与曲线**只有一处真相**：`AppMotion.pageTransitionSpec()`（260ms + FastOutSlowInEasing）。
// 关掉时 `PageTransitionSetting.durationMs(false) == 0`，且这里连 spec 都不会构造。
// ─────────────────────────────────────────────────────────────────────────

/** 转场位移 = 屏宽 / 该除数。12 ⇒ 360dp 屏上约 30dp。 */
private const val PAGE_SLIDE_DIVISOR = 12

/** 前进（push）：新页从右侧滑入并淡入。 */
private fun pageEnterTransition(enabled: Boolean): EnterTransition =
    if (!enabled) {
        EnterTransition.None
    } else {
        slideInHorizontally(
            animationSpec = AppMotion.pageTransitionSpec(),
            initialOffsetX = { it / PAGE_SLIDE_DIVISOR }
        ) + fadeIn(animationSpec = AppMotion.pageTransitionSpec())
    }

/** 前进时的旧页：向左让位并淡出。 */
private fun pageExitTransition(enabled: Boolean): ExitTransition =
    if (!enabled) {
        ExitTransition.None
    } else {
        slideOutHorizontally(
            animationSpec = AppMotion.pageTransitionSpec(),
            targetOffsetX = { -it / PAGE_SLIDE_DIVISOR }
        ) + fadeOut(animationSpec = AppMotion.pageTransitionSpec())
    }

/** 返回（pop）：上一页从左侧滑回并淡入。 */
private fun pagePopEnterTransition(enabled: Boolean): EnterTransition =
    if (!enabled) {
        EnterTransition.None
    } else {
        slideInHorizontally(
            animationSpec = AppMotion.pageTransitionSpec(),
            initialOffsetX = { -it / PAGE_SLIDE_DIVISOR }
        ) + fadeIn(animationSpec = AppMotion.pageTransitionSpec())
    }

/** 返回时被关掉的那一页：向右退出并淡出。 */
private fun pagePopExitTransition(enabled: Boolean): ExitTransition =
    if (!enabled) {
        ExitTransition.None
    } else {
        slideOutHorizontally(
            animationSpec = AppMotion.pageTransitionSpec(),
            targetOffsetX = { it / PAGE_SLIDE_DIVISOR }
        ) + fadeOut(animationSpec = AppMotion.pageTransitionSpec())
    }