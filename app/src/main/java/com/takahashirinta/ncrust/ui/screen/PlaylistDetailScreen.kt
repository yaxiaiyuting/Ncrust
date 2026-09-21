package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.takahashirinta.ncrust.cache.ContentCache
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.PlaylistEditApi
import com.takahashirinta.ncrust.network.PlaylistWriteResult
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.ui.components.DetailHeader
import com.takahashirinta.ncrust.ui.components.DetailScaffold
import com.takahashirinta.ncrust.ui.components.EditPlaylistDialog
import com.takahashirinta.ncrust.ui.components.EditPlaylistOutcome
import com.takahashirinta.ncrust.ui.components.PlayAllDialog
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.controls.MetroBottomSheet
import io.github.takahashirinta.kanesumi.controls.MetroDialog
import io.github.takahashirinta.kanesumi.controls.MetroDivider
import io.github.takahashirinta.kanesumi.controls.MetroIconButton
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.launch

@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    playlistName: String = "",
    playlistCoverUrl: String = "",
    onBack: () -> Unit,
    onSongClick: (SongItem) -> Unit,
    onReplaceAndPlay: (List<SongItem>) -> Unit = {},
    onInsertNext: (List<SongItem>) -> Unit = {},
    onSongInsertNext: (SongItem) -> Unit = {},
    onSongAppendToQueue: (SongItem) -> Unit = {},
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> }
) {
    val cached = remember(playlistId) { ContentCache.getPlaylistSongs(playlistId) }
    var songs by remember(playlistId) { mutableStateOf(cached ?: emptyList()) }
    var isLoading by remember(playlistId) { mutableStateOf(cached == null) }
    var error by remember(playlistId) { mutableStateOf<String?>(null) }
    var showPlayAllDialog by remember { mutableStateOf(false) }
    // v1.3.0 · B4：编辑/删除入口只在**本人自建**歌单上出现。
    var info by remember(playlistId) { mutableStateOf<PlaylistApi.PlaylistInfo?>(null) }
    var ownerUid by remember { mutableLongStateOf(0L) }
    var showActions by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // 顶部标题优先用路由带进来的名字；改名成功后要立刻反映，故用信息里的名字覆盖。
    var displayName by remember(playlistId) { mutableStateOf(playlistName) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val strings = LocalStrings.current
    val isOwned = info?.isOwnedBy(ownerUid) == true

    fun loadSongs() {
        coroutineScope.launch {
            if (songs.isEmpty()) isLoading = true
            error = null
            try {
                val fresh = PlaylistApi.getPlaylistDetail(playlistId)
                songs = fresh
                ContentCache.putPlaylistSongs(playlistId, fresh)
            } catch (e: Exception) {
                if (songs.isEmpty()) error = strings.loadFailed(e.message)
            } finally {
                isLoading = false
            }
        }
    }

    // 元信息单独拉一次（n=0 的轻量请求）：详情页要判断"是不是我的歌单"才能决定是否给编辑入口。
    fun loadInfo() {
        coroutineScope.launch {
            runCatching {
                val uid = PlaylistApi.getCurrentUserId()
                ownerUid = uid
                val fetched = PlaylistApi.fetchPlaylistInfo(playlistId)
                info = fetched
                fetched?.name?.takeIf { it.isNotBlank() }?.let { displayName = it }
            }
        }
    }

    LaunchedEffect(playlistId) {
        loadSongs()
        loadInfo()
    }

    val coverUrl = playlistCoverUrl.ifEmpty { songs.firstOrNull()?.album?.picUrl }

    if (showPlayAllDialog) {
        PlayAllDialog(
            songCount = songs.size,
            onDismiss = { showPlayAllDialog = false },
            onReplaceAndPlay = { onReplaceAndPlay(songs) },
            onInsertNext = { onInsertNext(songs) }
        )
    }

    if (showEdit && info != null) {
        val current = info!!
        EditPlaylistDialog(
            initialName = current.name,
            initialDesc = current.description,
            initialPrivacy = current.privacy,
            onDismiss = { showEdit = false },
            onSave = { name, desc, privacy ->
                var changed = false
                var ok = true
                // 三个字段三个端点：只发改动过的，少一次写请求就少 2s 闸门 + 少一次撞 405 的机会。
                if (name.isNotBlank() && name != current.name) {
                    changed = true
                    ok = PlaylistEditApi.renamePlaylist(playlistId, name) && ok
                }
                if (desc != current.description) {
                    changed = true
                    ok = PlaylistEditApi.updatePlaylistDesc(playlistId, desc) && ok
                }
                if (privacy != current.privacy) {
                    changed = true
                    ok = PlaylistEditApi.updatePlaylistPrivacy(playlistId, privacy) && ok
                }
                when {
                    !ok && PlaylistEditApi.lastError is PlaylistWriteResult.RateLimited ->
                        EditPlaylistOutcome.RATE_LIMITED
                    !ok -> EditPlaylistOutcome.FAILED
                    !changed -> EditPlaylistOutcome.SUCCESS
                    else -> {
                        info = current.copy(name = name, description = desc, privacy = privacy)
                        displayName = name
                        // 服务端 detail 本身有陈旧缓存，本地这份不丢就会出现"改了名进详情还是旧名"。
                        ContentCache.invalidatePlaylist(playlistId)
                        EditPlaylistOutcome.SUCCESS
                    }
                }
            }
        )
    }

    if (showDeleteConfirm) {
        MetroDialog(onDismissRequest = { showDeleteConfirm = false }) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                MetroText(
                    strings.playlistDeleteConfirm(displayName.ifEmpty { strings.categoryPlaylists }),
                    color = LocalMetroColors.current.onBackground,
                    style = LocalMetroTypography.current.bodyLarge,
                )
                Spacer(Modifier.height(6.dp))
                MetroText(
                    strings.playlistDeleteWarning,
                    color = LocalMetroColors.current.onSurfaceVariant,
                    style = LocalMetroTypography.current.bodySmall,
                )
            }
            MetroDivider()
            Row(Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showDeleteConfirm = false }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    MetroText(
                        strings.cancel,
                        color = LocalMetroColors.current.onSurfaceVariant,
                        style = LocalMetroTypography.current.bodyLarge,
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            showDeleteConfirm = false
                            coroutineScope.launch {
                                if (PlaylistEditApi.deletePlaylist(playlistId)) {
                                    ContentCache.invalidatePlaylist(playlistId)
                                    Toast.makeText(context, strings.playlistDeleted, Toast.LENGTH_SHORT).show()
                                    onBack()
                                } else {
                                    Toast.makeText(context, strings.playlistDeleteFailed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    MetroText(
                        strings.playlistDelete,
                        color = LocalMetroColors.current.primary,
                        style = LocalMetroTypography.current.bodyLarge,
                    )
                }
            }
        }
    }

    if (showActions) {
        MetroBottomSheet(
            onDismiss = { showActions = false },
            // 首行当 dragHandle，与 SongMenuSheet 一致：只有 handle 区挂纵向拖拽手势。
            dragHandle = {
                PlaylistActionRow(strings.playlistEditTitle) { showEdit = true; showActions = false }
            },
        ) {
            MetroDivider()
            PlaylistActionRow(strings.playlistEditTitle) {
                showActions = false
                showEdit = true
            }
            PlaylistActionRow(
                if (info?.privacy == PlaylistEditApi.PRIVACY_PRIVATE) {
                    strings.playlistPrivacyPublic
                } else {
                    strings.playlistPrivacyPrivate
                }
            ) {
                showActions = false
                val target = if (info?.privacy == PlaylistEditApi.PRIVACY_PRIVATE) {
                    PlaylistEditApi.PRIVACY_PUBLIC
                } else {
                    PlaylistEditApi.PRIVACY_PRIVATE
                }
                coroutineScope.launch {
                    if (PlaylistEditApi.updatePlaylistPrivacy(playlistId, target)) {
                        info = info?.copy(privacy = target)
                        ContentCache.invalidatePlaylist(playlistId)
                        Toast.makeText(context, strings.playlistUpdated, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, strings.playlistCreateFailed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            PlaylistActionRow(strings.playlistDelete) {
                showActions = false
                showDeleteConfirm = true
            }
        }
    }

    DetailScaffold(
        title = strings.playlistDetailTitle,
        onBack = onBack,
        isLoading = isLoading,
        hasCachedContent = songs.isNotEmpty(),
        error = error,
        onRetry = { loadSongs() },
        header = {
            DetailHeader(
                coverUrl = coverUrl,
                title = displayName.ifEmpty { strings.categoryPlaylists },
                subtitle = null,
                infoLines = listOf(strings.trackCountSongs(songs.size)),
                onPlayAll = if (songs.isNotEmpty()) ({ showPlayAllDialog = true }) else null,
                headerActions = {
                    if (isOwned) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MetroIconButton(onClick = { showActions = true }) {
                                MetroIcon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = strings.playlistEditTitle,
                                    tint = LocalMetroColors.current.onBackground,
                                )
                            }
                        }
                    }
                }
            )
        },
        content = {
            items(songs, key = { it.id }) { song ->
                SongCard(
                    song = song,
                    style = SongCardStyle.COMPACT,
                    onClick = { onSongClick(song) },
                    onShowMenu = {
                        onShowSongMenu(song, buildList {
                            add(SongMenuAction(Icons.Default.LibraryAdd, strings.actionAddToLibrary) {
                                LibraryManager.saveSong(context, song)
                                Toast.makeText(context, strings.addedToLibrary, Toast.LENGTH_SHORT).show()
                            })
                            add(SongMenuAction(Icons.Default.PlaylistPlay, strings.actionInsertNext) {
                                onSongInsertNext(song)
                            })
                            add(SongMenuAction(Icons.Default.PlaylistAdd, strings.actionAppendToQueue) {
                                onSongAppendToQueue(song)
                            })
                            // v1.3.0 · B5：只有本人自建歌单能移除曲目（他人歌单服务端 404）。
                            if (isOwned) {
                                add(SongMenuAction(Icons.Default.Delete, strings.removeFromPlaylist) {
                                    coroutineScope.launch {
                                        if (PlaylistEditApi.removeSongs(playlistId, listOf(song.id)) != null) {
                                            songs = songs.filterNot { it.id == song.id }
                                            ContentCache.invalidatePlaylist(playlistId)
                                            Toast.makeText(context, strings.removedFromPlaylist, Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, strings.playlistCreateFailed, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                })
                            }
                        })
                    }
                )
            }
        }
    )
}

/** 歌单操作菜单的行（直角、无圆角，与 SongMenuSheet 同款排版）。 */
@Composable
private fun PlaylistActionRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetroText(
            label,
            color = LocalMetroColors.current.onBackground,
            style = LocalMetroTypography.current.bodyLarge,
        )
    }
}
