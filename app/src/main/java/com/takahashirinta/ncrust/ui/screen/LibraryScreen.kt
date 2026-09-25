/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明（Bug2「我喜欢的歌无法全部加入播放列表」）：
 *   - ④ 收藏单曲列表新增「播放全部」入口（复用 PlayAllDialog 二次确认）。
 *     回调由上层补齐**整个**红心歌单，而不是当前已分页加载的部分。
 */

package com.takahashirinta.ncrust.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.defaultPlaylistName
import com.takahashirinta.ncrust.library.AlbumInfo
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.local.LocalPlaylistRepository
import com.takahashirinta.ncrust.local.LocalPlaylistStore
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.PlaylistEditApi
import com.takahashirinta.ncrust.network.PlaylistWriteResult
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.ui.BottomOverlayInsetDp
import com.takahashirinta.ncrust.ui.ResponsiveContent
import io.github.takahashirinta.kanesumi.anim.sokuou.MetroDefault
import io.github.takahashirinta.kanesumi.anim.sokuou.rememberMetroFlingBehavior
import io.github.takahashirinta.kanesumi.controls.MetroProgressIndicator
import io.github.takahashirinta.kanesumi.controls.MetroTabItem
import io.github.takahashirinta.kanesumi.controls.MetroTabRow
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import com.takahashirinta.ncrust.ui.components.CreatePlaylistDialog
import com.takahashirinta.ncrust.ui.components.PlayAllButton
import com.takahashirinta.ncrust.ui.components.PlaylistCreateOutcome
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import kotlinx.coroutines.launch
import android.widget.Toast

@Composable
fun LibraryScreen(
    onSongClick: (SongItem) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onPlayAlbum: (Long) -> Unit,
    onPlaylistClick: (PlaylistApi.PlaylistInfo) -> Unit = {},
    onPlayPlaylist: (Long) -> Unit = {},
    // Bug2-④：收藏单曲「播放全部」。由上层 loadAllLikedSongs 覆盖整个红心歌单。
    onPlayAllLiked: () -> Unit = {},
    onSongInsertNext: (SongItem) -> Unit = {},
    onSongAppendToQueue: (SongItem) -> Unit = {},
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> },
    // v2.3.0 · A/B：本地歌单的失效信号（见下面的 LaunchedEffect）。
    localPlaylistsTick: Int = 0,
    // v2.3.0 · A：QQ 歌单**不再走二级入口**，列表直接平铺在「歌单」tab 里。
    // 这个回调改成「点了某一张 QQ 歌单」。
    onQqPlaylistClick: (com.takahashirinta.ncrust.source.Playlist) -> Unit = {},
    // v2.3.0 · B：进入某个本地歌单（可编辑）。
    onLocalPlaylistClick: (com.takahashirinta.ncrust.source.PlaylistKey) -> Unit = {},
    refreshTrigger: Int = 0
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val strings = LocalStrings.current

    var savedSongs by remember { mutableStateOf(LibraryManager.getSavedSongs(context)) }
    var savedAlbums by remember { mutableStateOf(LibraryManager.getSavedAlbums(context)) }
    // 红心歌单总数（含尚未分页加载的部分），供「播放全部」入口显示规模。
    var likedTotal by remember { mutableIntStateOf(LibraryManager.getLikedSongIds(context).size) }
    var selectedCategory by remember { mutableIntStateOf(0) }
    // v2.3.0 · A：**分类顺序改成 单曲 / 歌单 / 专辑**（用户拍板，任务书 3.2）。
    // v2.2.1 及以前是 单曲 / 专辑 / 歌单。改顺序不是「换个 position」那么简单：
    // 下面所有 `selectedCategory == N` 的判定都必须跟着换（本版一次性改完，
    // 并在 LibraryPlaylistsTab 的 KDoc 里记下三个下标）。
    val categories = listOf(strings.categoryTracks, strings.categoryPlaylists, strings.categoryAlbums)

    var playlists by remember { mutableStateOf<List<PlaylistApi.PlaylistInfo>>(emptyList()) }
    var isLoadingPlaylists by remember { mutableStateOf(false) }
    var playlistError by remember { mutableStateOf<String?>(null) }
    // v2.3.0 · B：本地歌单（可编辑、只加不减 + tombstone）。它**不依赖任何网络状态** ——
    // 本地歌单存在的意义之一就是「网易云/QQ 都挂了也能看到自己的歌单」。
    var localPlaylists by remember { mutableStateOf(LocalPlaylistStore.readPlaylists(context)) }
    var showCreateLocal by remember { mutableStateOf(false) }
    // v1.3.0 · B4：收藏页的「新建歌单」入口（建空歌单），与全屏播放器的「保存队列为歌单」共用对话框。
    var showCreatePlaylist by remember { mutableStateOf(false) }
    fun loadPlaylists() {
        coroutineScope.launch {
            isLoadingPlaylists = true
            playlistError = null
            try {
                if (!CookieManager.hasCookie(context)) {
                    playlistError = strings.notLoggedInForPlaylists
                } else {
                    val userId = PlaylistApi.getCurrentUserId()
                    val result = PlaylistApi.getUserPlaylists(userId)
                    playlists = result.playlists
                }
            } catch (e: Exception) {
                playlistError = if (!CookieManager.hasCookie(context))
                    strings.notLoggedInForPlaylists
                else strings.loadFailed(e.message)
            } finally {
                isLoadingPlaylists = false
            }
        }
    }

    fun reloadLocal() {
        savedSongs = LibraryManager.getSavedSongs(context)
        savedAlbums = LibraryManager.getSavedAlbums(context)
        likedTotal = LibraryManager.getLikedSongIds(context).size
        localPlaylists = LocalPlaylistStore.readPlaylists(context)
    }

    LaunchedEffect(selectedCategory) {
        reloadLocal()
        // 歌单 tab 现在是下标 1（v2.3.0 · A 的顺序调整）。
        if (selectedCategory == 1 && playlists.isEmpty() && !isLoadingPlaylists) {
            loadPlaylists()
        }
    }

    // 进入收藏页时后台拉取云端收藏（收藏单曲 + 收藏专辑），刷新后平滑 diff 更新。
    // 失败/未登录不影响展示——本地缓存先渲染，绝不出现空白+加载动画。
    LaunchedEffect(Unit) {
        LibraryManager.refreshFromCloud(context)
        reloadLocal()
    }

    // 登录完成后（cookieRefreshTrigger 变化）再拉一次云端，保证重新安装/刚登录后库非空。
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) {
            LibraryManager.refreshFromCloud(context)
            reloadLocal()
        }
    }

    // v2.3.0 · B：本地歌单不是可观察数据源（SharedPreferences），所以由上层用一个
    // 递增的信号告诉本页「它变了，重读一次」。从歌曲菜单里加过歌之后必须重读，
    // 否则用户回到库页看到的还是加之前的样子。
    LaunchedEffect(localPlaylistsTick) {
        if (localPlaylistsTick > 0) localPlaylists = LocalPlaylistStore.readPlaylists(context)
    }

    // 收藏单曲分页懒加载：滚动到列表末尾时拉取下一批详情。
    val songListState = rememberLazyListState()
    LaunchedEffect(selectedCategory) {
        if (selectedCategory != 0) return@LaunchedEffect
        snapshotFlow { songListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastIndex ->
                val total = LibraryManager.getLikedSongIds(context).size
                if (lastIndex >= savedSongs.size - 5 && savedSongs.size < total) {
                    val more = LibraryManager.loadMoreLikedSongs(context)
                    if (more.isNotEmpty()) savedSongs = more
                }
            }
    }

    if (showCreatePlaylist) {
        val createStrings = strings
        CreatePlaylistDialog(
            defaultName = defaultPlaylistName(),
            songCount = 0,
            onDismiss = { showCreatePlaylist = false },
            onCreate = { name, privacy ->
                if (!CookieManager.hasCookie(context)) {
                    PlaylistCreateOutcome.FAILED
                } else {
                    val id = PlaylistEditApi.createPlaylist(name, privacy)
                    when {
                        id != null -> {
                            Toast.makeText(context, createStrings.playlistCreated(name), Toast.LENGTH_SHORT).show()
                            loadPlaylists()
                            PlaylistCreateOutcome.SUCCESS
                        }
                        PlaylistEditApi.lastError is PlaylistWriteResult.RateLimited ->
                            PlaylistCreateOutcome.RATE_LIMITED
                        else -> PlaylistCreateOutcome.FAILED
                    }
                }
            }
        )
    }

    // v2.3.0 · B：新建**本地**歌单。与「新建网易云歌单」是两个不同的对话框 ——
    // 前者只写本地 prefs，后者会发远程写请求（`PlaylistEditApi.createPlaylist`）。
    // 按钮文案与落点都不同，合成一个对话框会让用户以为是同一件事。
    if (showCreateLocal) {
        LocalPlaylistCreateDialog(
            onDismiss = { showCreateLocal = false },
            onCreate = { name ->
                LocalPlaylistRepository.createLocalOnly(context, name)
                localPlaylists = LocalPlaylistStore.readPlaylists(context)
                showCreateLocal = false
                Toast.makeText(context, strings.localPlaylistCreated(name), Toast.LENGTH_SHORT).show()
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
    ResponsiveContent {
        // 背景由 MainScreen 外层 Box 统一填充，此处不重复画一层
        Column(modifier = Modifier.fillMaxSize()) {
            // Groove 风大字页头。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
            ) {
                MetroText(
                    strings.tabLibrary,
                    color = LocalMetroColors.current.onBackground,
                    style = LocalMetroTypography.current.pageHeading,
                )
            }
            Spacer(Modifier.height(4.dp))
            MetroTabRow(
                selectedTabIndex = selectedCategory,
                items = categories.map { MetroTabItem(it) },
                onTabSelected = { index -> selectedCategory = index }
            )

            // Tab 切换用 AnimatedContent 做方向感知的横向滑入。
            // 距离 1/16 屏宽（比 NavGraph 的 1/8 更轻——同页内切 tab，不应有"翻页"的重量感）；
            // 200ms MetroDefault 曲线。向右切（index 增）新页从右入、旧页向左出；向左切反之。
            AnimatedContent(
                targetState = selectedCategory,
                transitionSpec = {
                    val goingRight = targetState > initialState
                    val enterSign = if (goingRight) 1 else -1
                    val exitSign = if (goingRight) -1 else 1
                    (slideInHorizontally(
                        animationSpec = tween(200, easing = MetroDefault),
                        initialOffsetX = { fullWidth -> enterSign * fullWidth / 16 }
                    ) + fadeIn(animationSpec = tween(160, easing = MetroDefault))) togetherWith
                    (slideOutHorizontally(
                        animationSpec = tween(200, easing = MetroDefault),
                        targetOffsetX = { fullWidth -> exitSign * fullWidth / 16 }
                    ) + fadeOut(animationSpec = tween(160, easing = MetroDefault)))
                },
                modifier = Modifier.fillMaxSize(),
                label = "LibraryCategoryContent"
            ) { category -> when (category) {
                0 -> {
                    if (savedSongs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            MetroText(strings.noSavedSongs, color = LocalMetroColors.current.onSurfaceVariant, style = LocalMetroTypography.current.bodyLarge)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = songListState,
                            contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
                            flingBehavior = rememberMetroFlingBehavior()
                        ) {
                            // Bug2-④：整张红心歌单的「播放全部」入口。
                            // 显示的是 total（云端数量）而非 savedSongs.size（已加载数量），
                            // 避免用户以为"我喜欢的歌"只有已翻页的那几十首。
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    MetroText(
                                        strings.trackCountSongs(likedTotal),
                                        color = LocalMetroColors.current.onSurfaceVariant,
                                        style = LocalMetroTypography.current.bodySmall
                                    )
                                    Spacer(Modifier.weight(1f))
                                    PlayAllButton(onClick = onPlayAllLiked)
                                }
                            }
                            items(savedSongs, key = { it.id }) { song ->
                                SongCard(
                                    song = song,
                                    style = SongCardStyle.LIST,
                                    onClick = { onSongClick(song) },
                                    onShowMenu = {
                                        onShowSongMenu(song, listOf(
                                            SongMenuAction(Icons.Default.LibraryAdd, strings.actionAddToLibrary) {
                                                LibraryManager.saveSong(context, song)
                                                Toast.makeText(context, strings.addedToLibrary, Toast.LENGTH_SHORT).show()
                                            },
                                            SongMenuAction(Icons.Default.PlaylistPlay, strings.actionInsertNext) {
                                                onSongInsertNext(song)
                                            },
                                            SongMenuAction(Icons.Default.PlaylistAdd, strings.actionAppendToQueue) {
                                                onSongAppendToQueue(song)
                                            },
                                            SongMenuAction(Icons.Default.Delete, strings.actionRemoveFromLibrary) {
                                                LibraryManager.removeSong(context, song.id)
                                                savedSongs = LibraryManager.getSavedSongs(context)
                                                savedAlbums = LibraryManager.getSavedAlbums(context)
                                            }
                                        ))
                                    }
                                )
                            }
                        }
                    }
                }

                1 -> {
                    // v2.3.0 · A：歌单 tab —— **单曲 / 歌单 / 专辑** 里的第二个。
                    // v2.2.1 及以前它在下标 2（顺序是 单曲/专辑/歌单）。
                    LibraryPlaylistsTab(
                        localPlaylists = localPlaylists,
                        neteasePlaylists = playlists,
                        isLoadingNetease = isLoadingPlaylists,
                        neteaseError = playlistError,
                        onRetryNetease = { loadPlaylists() },
                        onCreateNetease = { showCreatePlaylist = true },
                        onNeteaseClick = onPlaylistClick,
                        onPlayNetease = onPlayPlaylist,
                        onLocalClick = { pl -> onLocalPlaylistClick(pl.key) },
                        onCreateLocal = { showCreateLocal = true },
                        onQqClick = onQqPlaylistClick,
                    )
                }
                2 -> {
                    if (savedAlbums.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            MetroText(strings.noSavedAlbums, color = LocalMetroColors.current.onSurfaceVariant, style = LocalMetroTypography.current.bodyLarge)
                        }
                    } else {
                        // 自适应栅格：列数随内容栏宽度变化（宽屏多列），取代写死的 2 列。
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 160.dp),
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
                            flingBehavior = rememberMetroFlingBehavior()
                        ) {
                            items(savedAlbums, key = { it.albumId }) { album ->
                                LibraryAlbumGridItem(
                                    album = album,
                                    modifier = Modifier.fillMaxWidth().animateItem(
                                        fadeInSpec = tween(150, easing = MetroDefault),
                                        placementSpec = tween(220, easing = MetroDefault),
                                        fadeOutSpec = tween(120, easing = MetroDefault)
                                    ),
                                    onClick = { onAlbumClick(album.albumId) },
                                    onPlayAll = { onPlayAlbum(album.albumId) }
                                )
                            }
                        }
                    }
                }
            } }   // when (category) + AnimatedContent 的 lambda
        }
    }

    }
}

/** B4：「新建歌单」格子。与歌单格子同尺寸，直角、无圆角，只有一个居中的 +。 */
@Composable
fun NewPlaylistGridItem(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    // v2.3.0 · A/B：同一个「＋」格子被三处复用（新建网易云歌单 / 新建本地歌单），
    // 文案必须能改 —— 默认值保持既有调用点的行为不变。
    label: String? = null,
) {
    val strings = LocalStrings.current
    Column(modifier = modifier.clickable { onClick() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(LocalMetroColors.current.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            MetroIcon(
                imageVector = Icons.Default.Add,
                contentDescription = strings.playlistNew,
                tint = LocalMetroColors.current.primary,
                sizeDp = 40.dp,
            )
        }
        Spacer(Modifier.height(6.dp))
        MetroText(
            label ?: strings.playlistNew,
            color = LocalMetroColors.current.onBackground,
            style = LocalMetroTypography.current.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
fun PlaylistGridItem(
    playlist: PlaylistApi.PlaylistInfo,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onPlayAll: () -> Unit
) {
    val strings = LocalStrings.current
    Column(modifier = modifier.clickable { onClick() }) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            AsyncImage(
                model = CoverUrls.small(playlist.coverImgUrl),
                contentDescription = strings.playlistCoverDesc,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            PlayAllButton(
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                onClick = onPlayAll
            )
        }
        Spacer(Modifier.height(6.dp))
        MetroText(playlist.name, color = LocalMetroColors.current.onBackground, style = LocalMetroTypography.current.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
        MetroText(strings.trackCount(playlist.trackCount), color = LocalMetroColors.current.onSurfaceVariant, style = LocalMetroTypography.current.bodySmall, modifier = Modifier.padding(horizontal = 6.dp))
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
fun LibraryAlbumGridItem(
    album: AlbumInfo,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onPlayAll: () -> Unit
) {
    val strings = LocalStrings.current
    Column(modifier = modifier.clickable { onClick() }) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            AsyncImage(
                model = CoverUrls.small(album.picUrl),
                contentDescription = strings.albumCoverDesc,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            PlayAllButton(
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                onClick = onPlayAll
            )
        }
        Spacer(Modifier.height(6.dp))
        MetroText(album.name, color = LocalMetroColors.current.onBackground, style = LocalMetroTypography.current.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
        MetroText(strings.albumArtistAndCount(album.artist, album.songCount), color = LocalMetroColors.current.onSurfaceVariant, style = LocalMetroTypography.current.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
        Spacer(Modifier.height(6.dp))
    }
}