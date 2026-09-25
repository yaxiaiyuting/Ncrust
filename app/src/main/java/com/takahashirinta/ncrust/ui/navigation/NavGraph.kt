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
     * v2.2.0：QQ 音乐歌单（按源隔离的独立页面）。
     *
     * 详情路由把**身份三元组**编进路径：`playlistId`(tid) + `ownerId`(uin) + `dirId`。
     * ownerId 必须进路由 —— 否则「A 账号点进歌单 → 返回 → 切到 B 账号 → 系统恢复同一条
     * 路由」会拿 A 的 playlistId 去 B 的账号下查，运气好是 10004，运气不好是另一个歌单。
     */
    const val QQ_PLAYLISTS = "qqplaylists"
    const val QQ_PLAYLIST_DETAIL = "qqplaylist/{playlistId}/{ownerId}/{dirId}/{playlistName}"

    fun album(albumId: Long) = "album/$albumId"
    fun artist(artistId: Long) = "artist/$artistId"
    fun playlist(id: Long, name: String = "", coverUrl: String = "") =
        "playlist/$id/${URLEncoder.encode(name, StandardCharsets.UTF_8.toString())}/${URLEncoder.encode(coverUrl, StandardCharsets.UTF_8.toString())}"
    fun song(songId: Long) = "song/$songId"

    fun qqPlaylistDetail(id: String, ownerId: String, dirId: Long, name: String) =
        "qqplaylist/$id/$ownerId/$dirId/" + URLEncoder.encode(name, StandardCharsets.UTF_8.toString())
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
            AlbumDetailScreen(
                albumId = albumId,
                onBack = { navController.popBackStack() },
                onSongClick = onSongClick,
                onArtistClick = { artistId -> navController.navigate(NavRoutes.artist(artistId)) },
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
                artistId = artistId,
                onBack = { navController.popBackStack() },
                onSongClick = onSongClick,
                onAlbumClick = { id -> navController.navigate(NavRoutes.album(id)) },
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

        composable(NavRoutes.QQ_PLAYLISTS) {
            QqPlaylistScreen(
                onBack = { navController.popBackStack() },
                onPlaylistClick = { pl ->
                    navController.navigate(
                        NavRoutes.qqPlaylistDetail(pl.key.id, pl.key.ownerId, pl.dirId, pl.name)
                    )
                }
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
            route = NavRoutes.SONG_DETAIL,
            arguments = listOf(navArgument("songId") { type = NavType.LongType })
        ) { backStackEntry ->
            val songId = backStackEntry.arguments?.getLong("songId") ?: return@composable
            SongDetailScreen(
                songId = songId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}