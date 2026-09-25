/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.3.0 · A/B：库页「歌单」tab —— 本地歌单 / 网易云 / QQ 音乐 **三个按源分区的区块**。
 */

package com.takahashirinta.ncrust.ui.screen

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.local.LocalPlaylist
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.playlist.PlaylistDegradation
import com.takahashirinta.ncrust.playlist.PlaylistHardFailure
import com.takahashirinta.ncrust.playlist.PlaylistLoadCoordinator
import com.takahashirinta.ncrust.playlist.PlaylistResult
import com.takahashirinta.ncrust.qq.QqPlaylistRepository
import com.takahashirinta.ncrust.source.Playlist
import com.takahashirinta.ncrust.ui.BottomOverlayInsetDp
import com.takahashirinta.ncrust.ui.components.appCoverFrame
import com.takahashirinta.ncrust.ui.components.appPressScale
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.theme.AppShapes
import io.github.takahashirinta.kanesumi.anim.sokuou.MetroDefault
import io.github.takahashirinta.kanesumi.anim.sokuou.rememberMetroFlingBehavior
import io.github.takahashirinta.kanesumi.controls.MetroDialog
import io.github.takahashirinta.kanesumi.controls.MetroProgressIndicator
import io.github.takahashirinta.kanesumi.controls.MetroTextField
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import io.github.takahashirinta.kanesumi.core.theme.MetroTypography

/**
 * 库页「歌单」tab（v2.3.0 · A/B）。
 *
 * ## 信息架构（任务书 3.1 / 3.2）
 *
 * **按音源分区，三块，各自独立、不合并**：
 *
 * | 区块 | 内容 | 来源 |
 * |---|---|---|
 * | 本地歌单 | 可编辑、可混装音源、只加不减 + tombstone | 本地 prefs |
 * | 网易云 | 新建入口 + 云端歌单网格 | `PlaylistApi.getUserPlaylists` |
 * | QQ 音乐 | **歌单列表直接平铺** | `QqPlaylistRepository.loadList` |
 *
 * ## 「QQ 歌单一步可达」是怎么做到的（与 v2.2.0 的差别）
 *
 * v2.2.0 的库页里 QQ 歌单是一个**入口行**（`QqPlaylistEntryRow`，v2.3.0 已删），点进去才是列表 ——
 * 「看 QQ 歌单」要两步。本版把**列表本身**放进这一页：`QqPlaylistInlineRow` 直接渲染，
 * 点击才进入（只读的）详情。入口行因此退役（本文件不再引用它）。
 *
 * ## 为什么整页是**一个** `LazyVerticalGrid`
 *
 * 三个区块的高度都不知道（本地歌单数量本地可变、两个远程源各自可能为空/加载中/报错），
 * 而「`LazyColumn` 里嵌 `LazyVerticalGrid`」在 Compose 里是非法的（同向嵌套滚动 +
 * 无限高度约束）。所以用**一个**网格：区块标题与 QQ 歌单行都用 `GridItemSpan(maxLineSpan)`
 * 占满整行 —— 三种不同宽度的内容共存在同一个滚动容器里，滚动 / fling / `contentPadding`
 * 都只有一份。
 */
@Composable
fun LibraryPlaylistsTab(
    localPlaylists: List<LocalPlaylist>,
    neteasePlaylists: List<PlaylistApi.PlaylistInfo>,
    isLoadingNetease: Boolean,
    neteaseError: String?,
    onRetryNetease: () -> Unit,
    onCreateNetease: () -> Unit,
    onNeteaseClick: (PlaylistApi.PlaylistInfo) -> Unit,
    onPlayNetease: (Long) -> Unit,
    onLocalClick: (LocalPlaylist) -> Unit,
    onCreateLocal: () -> Unit,
    onQqClick: (Playlist) -> Unit,
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
    val context = LocalContext.current

    // ---- QQ 歌单：本页自己加载（与 v2.2.0 删除的 QQ 歌单列表页同一套闸门与仓库，不另写一条数据路径）----
    val coordinator = remember { PlaylistLoadCoordinator() }
    var qqPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var qqLoading by remember { mutableStateOf(false) }
    var qqFailure by remember { mutableStateOf<PlaylistHardFailure?>(null) }
    var qqDegradation by remember { mutableStateOf<PlaylistDegradation?>(null) }
    var qqHasLoadedOnce by remember { mutableStateOf(false) }
    var qqReloadTick by remember { mutableIntStateOf(0) }
    val ownerId = remember { QqPlaylistRepository.currentOwnerId(context) }

    LaunchedEffect(ownerId, qqReloadTick) {
        if (qqLoading) return@LaunchedEffect
        qqLoading = true
        coordinator.onOwnerChanged(ownerId)
        val load = coordinator.beginList(ownerId)
        val result = QqPlaylistRepository.loadList(forceRefresh = qqReloadTick > 0)
        // 账号在请求飞行期间变过 ⇒ 整包丢弃（v2.2.0 的世代闸门，原样复用）。
        if (coordinator.isCurrent(load)) {
            when (result) {
                is PlaylistResult.Data -> {
                    coordinator.accept(load, result.outcome.data.size)
                    qqPlaylists = result.outcome.data
                    qqDegradation = result.outcome.degradation
                    qqFailure = null
                }
                is PlaylistResult.Failed -> {
                    coordinator.fail(load, result.reason.toReason())
                    qqFailure = result.reason
                    qqDegradation = null
                }
            }
            qqHasLoadedOnce = true
        }
        qqLoading = false
    }

    val qqOrdered = remember(qqPlaylists) {
        qqPlaylists.filter { it.isFavorite } +
            qqPlaylists.filter { it.isOwned && !it.isFavorite } +
            qqPlaylists.filter { !it.isOwned }
    }
    val qqNeedLogin = qqFailure == PlaylistHardFailure.NeedLogin

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
        flingBehavior = rememberMetroFlingBehavior(),
    ) {
        // ============================================================ 本地歌单
        item(key = "hdr-local", span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(strings.localPlaylistSectionTitle, colors, typography)
        }
        if (localPlaylists.isEmpty()) {
            item(key = "local-empty", span = { GridItemSpan(maxLineSpan) }) {
                SectionHint(strings.localPlaylistEmpty, colors, typography)
            }
        }
        item(key = "local-create") {
            NewPlaylistGridItem(
                modifier = Modifier.fillMaxWidth().animateItem(
                    fadeInSpec = tween(150, easing = MetroDefault),
                    placementSpec = tween(220, easing = MetroDefault),
                    fadeOutSpec = tween(120, easing = MetroDefault),
                ),
                onClick = onCreateLocal,
                label = strings.localPlaylistNew,
            )
        }
        items(localPlaylists, key = { "local-" + it.key.tag }) { pl ->
            LocalPlaylistGridItem(
                playlist = pl,
                modifier = Modifier.fillMaxWidth().animateItem(
                    fadeInSpec = tween(150, easing = MetroDefault),
                    placementSpec = tween(220, easing = MetroDefault),
                    fadeOutSpec = tween(120, easing = MetroDefault),
                ),
                onClick = { onLocalClick(pl) },
            )
        }

        // ============================================================== 网易云
        item(key = "hdr-netease", span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(strings.sourceNetease, colors, typography)
        }
        when {
            isLoadingNetease && neteasePlaylists.isEmpty() -> {
                item(key = "ne-loading", span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                        MetroProgressIndicator(color = colors.primary)
                    }
                }
            }
            neteaseError != null && neteasePlaylists.isEmpty() -> {
                item(key = "ne-error", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        MetroText(neteaseError, color = colors.onSurfaceVariant, style = typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.clickable(onClick = onRetryNetease).padding(vertical = 6.dp)) {
                            MetroText(strings.retry, color = colors.primary, style = typography.bodyMedium)
                        }
                    }
                }
            }
            neteasePlaylists.isEmpty() -> {
                item(key = "ne-empty", span = { GridItemSpan(maxLineSpan) }) {
                    SectionHint(strings.noPlaylists, colors, typography)
                }
            }
            else -> {
                item(key = "ne-create") {
                    NewPlaylistGridItem(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onCreateNetease,
                    )
                }
                items(neteasePlaylists, key = { "ne-" + it.id }) { pl ->
                    PlaylistGridItem(
                        playlist = pl,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onNeteaseClick(pl) },
                        onPlayAll = { onPlayNetease(pl.id) },
                    )
                }
            }
        }

        // ============================================================ QQ 音乐
        item(key = "hdr-qq", span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetroText(strings.sourceQqMusic, color = colors.onSurfaceVariant, style = typography.caption)
                Spacer(Modifier.weight(1f))
                // 手动刷新：与 QQ 歌单独立页面同一个入口形状（v2.2.0 的约定）。
                Box(
                    modifier = Modifier
                        .clickable(enabled = !qqLoading) { qqReloadTick++ }
                        .padding(6.dp),
                ) {
                    MetroIcon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = strings.playlistRefresh,
                        tint = colors.primary,
                        sizeDp = 18.dp,
                    )
                }
            }
        }
        qqDegradation?.let { deg ->
            item(key = "qq-degraded", span = { GridItemSpan(maxLineSpan) }) {
                SectionHint(
                    text = when (deg) {
                        PlaylistDegradation.OFFLINE -> strings.playlistOffline
                        PlaylistDegradation.NEED_LOGIN -> strings.playlistLoginExpired
                        PlaylistDegradation.TRUNCATED -> strings.playlistTruncated
                    },
                    colors = colors,
                    typography = typography,
                )
            }
        }
        when {
            !qqHasLoadedOnce && qqLoading -> {
                item(key = "qq-loading", span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                        MetroProgressIndicator(color = colors.primary)
                    }
                }
            }
            // 空状态必须**分两种**（任务书 3.1）：「未登录」与「暂无歌单」是两件事 ——
            // 后者是已登录但这个账号确实没有歌单，前者是还没登录，出口完全不同。
            qqNeedLogin -> {
                item(key = "qq-need-login", span = { GridItemSpan(maxLineSpan) }) {
                    SectionHint(strings.playlistLoginRequired, colors, typography)
                }
            }
            qqOrdered.isEmpty() -> {
                item(key = "qq-empty", span = { GridItemSpan(maxLineSpan) }) {
                    SectionHint(
                        if (qqFailure != null) strings.playlistLoadFailed else strings.playlistEmpty,
                        colors,
                        typography,
                    )
                }
            }
            else -> {
                items(qqOrdered, key = { "qq-" + it.key.tag }, span = { GridItemSpan(maxLineSpan) }) { pl ->
                    QqPlaylistInlineRow(playlist = pl, onClick = { onQqClick(pl) })
                }
            }
        }
    }
}

private fun PlaylistHardFailure.toReason(): PlaylistLoadCoordinator.Reason = when (this) {
    PlaylistHardFailure.NeedLogin -> PlaylistLoadCoordinator.Reason.NEED_LOGIN
    PlaylistHardFailure.Network -> PlaylistLoadCoordinator.Reason.NETWORK
    PlaylistHardFailure.NotFound -> PlaylistLoadCoordinator.Reason.NOT_FOUND
    is PlaylistHardFailure.Other -> PlaylistLoadCoordinator.Reason.NETWORK
}

/** 区块标题。整行 span，左对齐，与库页的 Groove 风格一致。 */
@Composable
private fun SectionHeader(text: String, colors: MetroColors, typography: MetroTypography) {
    Box(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
        MetroText(text, color = colors.onSurfaceVariant, style = typography.caption)
    }
}

/** 区块内的说明 / 空状态。**不是**居中大空态 —— 它只是这一块没有内容，别的块还有。 */
@Composable
private fun SectionHint(text: String, colors: MetroColors, typography: MetroTypography) {
    Box(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp)) {
        MetroText(text, color = colors.onSurfaceVariant, style = typography.bodySmall)
    }
}

/**
 * QQ 歌单的**整行**条目（平铺，不是格子）。
 *
 * 用整行而不是格子，是因为它长在「一页里同时有三个源」的上下文里 ——
 * 行式排布让「这是 QQ 那一段」与上下两段在视觉上一眼可分，
 * 也顺便把「一步可达」落成「列表就在这儿」而不是「再点一次」。
 */
@Composable
private fun QqPlaylistInlineRow(playlist: Playlist, onClick: () -> Unit) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
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
                // v2.5.0 · B：占位底板先裁圆，方形底才不会从圆角封面四角露出来。
                .clip(AppShapes.small)
                .background(colors.surfaceVariant),
        ) {
            val cover = playlist.coverUrl
            if (!cover.isNullOrBlank()) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    // 圆角 + 1dp 描边；形状按渲染边长：48dp < 160dp ⇒ AppShapes.small。
                    modifier = Modifier
                        .fillMaxSize()
                        .appCoverFrame(shape = AppShapes.small),
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

/**
 * 本地歌单格子。
 *
 * 曲目数**不在这里读 prefs** —— 列表渲染时对每个格子做一次 `SharedPreferences` 读
 * 会让滚动带上 IO。曲目数在点进详情时才读（那一页本来就要读）。
 */
@Composable
private fun LocalPlaylistGridItem(
    playlist: LocalPlaylist,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
    Column(modifier = modifier.appPressScale().clickable { onClick() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                // v2.5.0 · B：本地歌单格子没有封面图，占位色块与同格封面同形
                // （栅格 minSize=160dp ⇒ 单元格 ≥160dp ⇒ AppShapes.large）。
                .clip(AppShapes.large)
                .background(colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            MetroIcon(
                imageVector = Icons.Default.PlaylistPlay,
                contentDescription = null,
                tint = colors.primary.copy(alpha = 0.45f),
                sizeDp = 32.dp,
            )
        }
        Spacer(Modifier.height(6.dp))
        MetroText(
            playlist.name,
            color = colors.onBackground,
            style = typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        MetroText(
            if (playlist.hasRemoteSource) strings.localPlaylistSync else strings.localPlaylistLocalBadge,
            color = colors.onSurfaceVariant,
            style = typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Spacer(Modifier.height(6.dp))
    }
}

/** 「新建本地歌单」对话框。复用 Kanesumi 的 `MetroDialog` + `MetroTextField`。 */
@Composable
fun LocalPlaylistCreateDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
    var name by remember { mutableStateOf("") }
    MetroDialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            MetroText(
                strings.localPlaylistNew,
                color = colors.onBackground,
                style = typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(10.dp))
            MetroTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = strings.localPlaylistNameHint,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(Modifier.clickable(onClick = onDismiss).padding(10.dp)) {
                    MetroText(strings.cancel, color = colors.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clickable(enabled = name.isNotBlank()) { onCreate(name.trim()) }
                        .padding(10.dp),
                ) {
                    MetroText(
                        strings.localPlaylistCreate,
                        color = if (name.isNotBlank()) colors.primary else colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
