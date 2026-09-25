package com.takahashirinta.ncrust.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
    startDestination: String = NavRoutes.HOME
) {
    // 页面切换直接跳变, 不做转场动画(用户决策)。
    // 转场期间新旧两页同帧渲染, slide/fade 每帧都要全屏合成, 低端机上
    // 是切换动作的主要掉帧源; 详情页内容有 ContentCache/磁盘缓存兜底,
    // 跳变"瞬间出现完整内容"反而更利落, 且零中间帧。
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None }
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