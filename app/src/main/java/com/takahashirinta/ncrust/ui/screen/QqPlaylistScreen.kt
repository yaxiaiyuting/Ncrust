/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.2.0：QQ 音乐用户歌单列表（**按源隔离展示**，只读，离线可看）。
 */

package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.playlist.PlaylistHardFailure
import com.takahashirinta.ncrust.playlist.PlaylistDegradation
import com.takahashirinta.ncrust.playlist.PlaylistLoadCoordinator
import com.takahashirinta.ncrust.playlist.PlaylistResult
import com.takahashirinta.ncrust.qq.QqPlaylistRepository
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.Playlist
import com.takahashirinta.ncrust.source.PlaylistKey
import com.takahashirinta.ncrust.source.groupPlaylistsBySource
import com.takahashirinta.ncrust.ui.components.DetailScaffold
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.controls.MetroIconButton
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.launch

/**
 * QQ 音乐用户歌单列表（v2.2.0）。
 *
 * ## 按源隔离（任务书第 12 条）
 *
 * 本页**只显示 QQ 音乐的歌单**，且标题栏明确标出音源。列表数据经
 * [groupPlaylistsBySource] 分组后再取 QQ 那一组渲染 —— 也就是说「不跨源合并」
 * 不是靠这一页「碰巧只请求了 QQ」，而是**结构上经过了一个会分组的函数**，
 * 将来若有人把网易云歌单也塞进来，它们会落进另一个分组、而不是混进这个列表。
 *
 * ## 状态机
 *
 * 状态由 [PlaylistLoadCoordinator] 管理（generation + ownerId + key 三重判据）。
 * 三种「有数据但有话要说」的降级（离线 / 登录过期 / 被截断）走**横幅**而不是错误页 ——
 * 错误页会把已经能看的数据藏起来，「离线可看」就不成立了。
 *
 * ## 刷新
 *
 * **不做自动刷新**（任务书第 4.1 条）：只有 ① 进入页面时缓存过期、② 用户点右上角刷新、
 * ③ 下拉越过阈值，这三种情况才会发请求。
 */
@Composable
fun QqPlaylistScreen(
    onBack: () -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onRelogin: () -> Unit = {},
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 世代/账号闸门。`remember` 而非全局单例：离开页面即随组合销毁，
    // 不会把上一页的在途请求带回来（这也让「旧响应后到」在页面级就不可能落地）。
    val coordinator = remember { PlaylistLoadCoordinator() }

    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var loadedKey by remember { mutableStateOf<PlaylistKey?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var degradation by remember { mutableStateOf<PlaylistDegradation?>(null) }
    var failure by remember { mutableStateOf<PlaylistHardFailure?>(null) }
    var hasLoadedOnce by remember { mutableStateOf(false) }

    val ownerId = remember { QqPlaylistRepository.currentOwnerId(context) }

    fun load(force: Boolean) {
        if (isLoading) return
        isLoading = true
        // 领号：把「这次请求属于哪个账号 / 哪个世代」记下来，响应回来时用它判等。
        val load = coordinator.beginList(ownerId)
        scope.launch {
            val result = QqPlaylistRepository.loadList(forceRefresh = force)
            // ★ 闸门：账号在请求飞行期间变过 ⇒ 整包丢弃，绝不写进 UI。
            if (!coordinator.isCurrent(load)) return@launch
            when (result) {
                is PlaylistResult.Data -> {
                    coordinator.accept(load, result.outcome.data.size)
                    playlists = result.outcome.data
                    loadedKey = null // 列表级没有单个 key；用 ownerId 判等
                    degradation = result.outcome.degradation
                    failure = null
                }
                is PlaylistResult.Failed -> {
                    coordinator.fail(load, result.reason.toReason())
                    failure = result.reason
                    degradation = null
                }
            }
            hasLoadedOnce = true
            isLoading = false
        }
    }

    LaunchedEffect(ownerId) {
        // 账号变化 ⇒ 作废旧世代，然后按「非强制」加载（缓存新鲜就不发请求）。
        coordinator.onOwnerChanged(ownerId)
        load(force = false)
    }

    // 「按源分组」的落点：即使数据源将来混入其它音源，也只取 QQ 这一组渲染。
    val groups = remember(playlists) { groupPlaylistsBySource(playlists) }
    val qqGroup = groups.firstOrNull { it.source == MusicSource.QQMUSIC }?.playlists.orEmpty()
    val owned = qqGroup.filter { it.isOwned && !it.isFavorite }
    val favorites = qqGroup.filter { it.isFavorite }
    val collected = qqGroup.filter { !it.isOwned }

    DetailScaffold(
        title = strings.qqPlaylistsTitle,
        onBack = onBack,
        isLoading = isLoading && !hasLoadedOnce,
        hasCachedContent = hasLoadedOnce && playlists.isNotEmpty(),
        error = failure?.let { it.message(strings.playlistLoadFailed, strings.playlistNotFound, strings.playlistLoginExpired) },
        onRetry = { load(force = true) },
        onTopEndAction = { load(force = true) },
        topEndIcon = Icons.Default.Refresh,
        topEndContentDescription = strings.playlistRefresh,
        header = {
            InfoBanner(
                sourceLabel = strings.sourceQqMusic,
                degradation = degradation,
                strings = strings,
                onRelogin = onRelogin,
            )
        },
    ) {
        if (qqGroup.isEmpty() && !isLoading) {
            item(key = "empty") {
                EmptyState(
                    text = if (failure == PlaylistHardFailure.NeedLogin) {
                        strings.playlistLoginRequired
                    } else {
                        strings.playlistEmpty
                    },
                    colors = colors,
                    typography = typography,
                )
            }
        }
        if (favorites.isNotEmpty()) {
            item(key = "hdr-fav") { SectionHeader(strings.playlistFavorite, colors, typography) }
            items(favorites, key = { "fav-" + it.key.tag }) { pl ->
                PlaylistRow(pl, strings, colors, typography) { onPlaylistClick(pl) }
            }
        }
        if (owned.isNotEmpty()) {
            item(key = "hdr-owned") { SectionHeader(strings.playlistsSectionOwned, colors, typography) }
            items(owned, key = { "own-" + it.key.tag }) { pl ->
                PlaylistRow(pl, strings, colors, typography) { onPlaylistClick(pl) }
            }
        }
        if (collected.isNotEmpty()) {
            item(key = "hdr-collected") { SectionHeader(strings.playlistsSectionFav, colors, typography) }
            items(collected, key = { "col-" + it.key.tag }) { pl ->
                PlaylistRow(pl, strings, colors, typography) { onPlaylistClick(pl) }
            }
        }
    }
}

/** 硬失败 → 协调器的原因枚举（两套类型各司其职：前者是 UI 契约，后者是状态机契约）。 */
private fun PlaylistHardFailure.toReason(): PlaylistLoadCoordinator.Reason = when (this) {
    PlaylistHardFailure.NeedLogin -> PlaylistLoadCoordinator.Reason.NEED_LOGIN
    PlaylistHardFailure.Network -> PlaylistLoadCoordinator.Reason.NETWORK
    PlaylistHardFailure.NotFound -> PlaylistLoadCoordinator.Reason.NOT_FOUND
    is PlaylistHardFailure.Other -> PlaylistLoadCoordinator.Reason.NETWORK
}

private fun PlaylistHardFailure.message(fallback: String, notFound: String, needLogin: String): String =
    when (this) {
        PlaylistHardFailure.NeedLogin -> needLogin
        PlaylistHardFailure.NotFound -> notFound
        PlaylistHardFailure.Network -> fallback
        is PlaylistHardFailure.Other -> fallback
    }

/**
 * 顶部信息条：音源标识 + 降级横幅。
 *
 * 音源标识**一定**显示（QQ 是本应用第二个音源，用户需要知道这一页的数据来自哪里）；
 * 降级横幅只在真的降级时出现，且**每一种都给出路**（登录过期给「重新登录」）。
 */
@Composable
private fun InfoBanner(
    sourceLabel: String,
    degradation: PlaylistDegradation?,
    strings: com.takahashirinta.ncrust.ui.i18n.Strings,
    onRelogin: () -> Unit,
) {
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        MetroText(sourceLabel, color = colors.primary, style = typography.titleMedium)
        if (degradation != null) {
            Spacer(Modifier.height(6.dp))
            val text = when (degradation) {
                PlaylistDegradation.OFFLINE -> strings.playlistOffline
                PlaylistDegradation.NEED_LOGIN -> strings.playlistLoginExpired
                PlaylistDegradation.TRUNCATED -> strings.playlistTruncated
            }
            MetroText(text, color = colors.onSurfaceVariant, style = typography.bodySmall)
            if (degradation == PlaylistDegradation.NEED_LOGIN) {
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .clickable(onClick = onRelogin)
                        .padding(vertical = 6.dp, horizontal = 2.dp),
                ) {
                    MetroText(strings.playlistRelogin, color = colors.primary, style = typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    text: String,
    colors: io.github.takahashirinta.kanesumi.core.theme.MetroColors,
    typography: io.github.takahashirinta.kanesumi.core.theme.MetroTypography,
) {
    Box(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
        MetroText(text, color = colors.onSurfaceVariant, style = typography.caption)
    }
}

/** 单行歌单。封面 + 名称 + 曲目数（+「我喜欢」标记）。 */
@Composable
private fun PlaylistRow(
    playlist: Playlist,
    strings: com.takahashirinta.ncrust.ui.i18n.Strings,
    colors: io.github.takahashirinta.kanesumi.core.theme.MetroColors,
    typography: io.github.takahashirinta.kanesumi.core.theme.MetroTypography,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RectangleShape) // Kanesumi Design：直角，无圆角
                .background(colors.surfaceVariant),
        ) {
            val cover = playlist.coverUrl
            if (!cover.isNullOrBlank()) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            MetroText(
                playlist.name,
                color = colors.onSurface,
                style = typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            val subtitle = buildString {
                append(strings.playlistTrackCount(playlist.trackCount))
                if (playlist.isFavorite) {
                    append(" · ")
                    append(strings.playlistFavorite)
                }
            }
            MetroText(subtitle, color = colors.onSurfaceVariant, style = typography.bodySmall)
        }
    }
}

@Composable
private fun EmptyState(
    text: String,
    colors: io.github.takahashirinta.kanesumi.core.theme.MetroColors,
    typography: io.github.takahashirinta.kanesumi.core.theme.MetroTypography,
) {
    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        MetroText(text, color = colors.onSurfaceVariant, style = typography.bodyLarge)
    }
}

/**
 * 库页「歌单」tab 里的入口行（v2.2.0）。
 *
 * 单独成行、并明确写「QQ 音乐」，是为了**不与网易云歌单网格混在一起** ——
 * 点进去是只含 QQ 歌单的独立页面。这是「按源隔离展示」在入口处的体现。
 */
@Composable
fun QqPlaylistEntryRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RectangleShape)
                .background(colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            MetroIcon(Icons.Default.Refresh, contentDescription = null, tint = colors.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            MetroText(strings.qqPlaylistsTitle, color = colors.onSurface, style = typography.bodyLarge)
            MetroText(strings.playlistsEntryHint, color = colors.onSurfaceVariant, style = typography.bodySmall)
        }
    }
}
