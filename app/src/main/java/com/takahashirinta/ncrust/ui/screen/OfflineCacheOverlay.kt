/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.cache.OfflineAudioCache
import com.takahashirinta.ncrust.cache.OfflineLibrary
import com.takahashirinta.ncrust.cache.OfflineTrack
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.i18n.Strings
import com.takahashirinta.ncrust.ui.i18n.formatCacheBytes
import com.takahashirinta.ncrust.ui.theme.LocalNcrustColors
import com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel
import io.github.takahashirinta.kanesumi.controls.MetroSelectorFlyout
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 离线缓存管理（v2.0.0 · T3 · 离线缓存 Phase 2）。
 *
 * ## 这是什么 / 不是什么
 *
 * 列的**只有「这台设备真的播过」的歌**（[OfflineLibrary] 的写入点在 PlaybackService 的
 * 播放心跳里），删的是**本地缓存片段**。全程没有「下载按钮 / 下载队列 / 进度百分比」，
 * 文案也一律用「已缓存 / 缓存」—— 本 fork 的定位是「已播放音频流的本地缓存」，
 * 不是官方下载（见 AGENTS.md 的合规契约）。列表底部常驻一句
 * [LocalStrings] 的 offlineCacheFragmentNotice：只保证有片段，不保证整曲完整。
 *
 * ## 为什么用全屏 Dialog 而不是接一个导航目的地
 *
 * 1. 设置页在三个图层里的最下层，播放器卡片是盖在所有页面之上的独立图层。Dialog 是**独立窗口**，
 *    天然在卡片之上，不存在「管理页底部按钮被播放器死带吃掉」那类命中测试问题（触摸陷阱第 2 条）；
 * 2. 返回键 / 返回手势由 Dialog 自己的 dismissOnBackPress 处理，行为与系统一致，
 *    比在 NavGraph 上接一页再自己兜返回键更不容易漏（也不用动 MainActivity / 导航图）。
 *
 * ## 打开即对账
 *
 * [OfflineAudioCache.reconcileLibrary] 会丢掉「缓存里已经没有任何片段」的索引条目
 * （例如音频 LRU 淘汰了 span）。列表里的每一行都必须真的能离线播，宁可少列也不谎报。
 *
 * ## 上限为什么提示「下次启动生效」
 *
 * SimpleCache 的淘汰器（[androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor]）
 * 在**构造时固化**，改上限只能重建 SimpleCache 实例；而播放中的 ExoPlayer 正握着它，
 * 重建会中断播放。所以这里只写 prefs，并如实提示下次启动生效 —— 不为了「看起来即时」
 * 去动正在播的缓存。
 */
@Composable
internal fun OfflineCacheManagerDialog(
    onDismiss: () -> Unit,
    currentSongId: Long,
) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val metro = LocalMetroColors.current
    val scope = rememberCoroutineScope()

    var reload by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var tracks by remember { mutableStateOf<List<OfflineTrack>>(emptyList()) }
    var bytesOf by remember { mutableStateOf<Map<Long, Long?>>(emptyMap()) }
    var usedBytes by remember { mutableStateOf(0L) }
    var limitMb by remember { mutableStateOf(OfflineAudioCache.maxMb(context)) }
    var limitExpanded by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<OfflineTrack?>(null) }

    // 读取与对账都要走 SimpleCache / 文件系统，放 IO 线程；组合期不阻塞。
    LaunchedEffect(reload) {
        val snapshot = withContext(Dispatchers.IO) {
            runCatching { OfflineAudioCache.reconcileLibrary(context) }
            val list = OfflineLibrary.list(context)
            Triple(
                list,
                list.associate { it.songId to OfflineAudioCache.bytesForSong(context, it.songId) },
                OfflineAudioCache.sizeBytes(context),
            )
        }
        tracks = snapshot.first
        bytesOf = snapshot.second
        usedBytes = snapshot.third
        loaded = true
    }

    val limitOptions = remember { OFFLINE_CACHE_LIMIT_MB.map { "$it MB" } }
    val selectedLimitIndex = OFFLINE_CACHE_LIMIT_MB.indexOf(limitMb).coerceAtLeast(0)
    val limitBytes = limitMb.toLong() * 1024L * 1024L
    // 上限调小后本次进程内仍是旧上限（淘汰器固化），剩余量按 0 兜底而不是显示负数。
    val remaining = (limitBytes - usedBytes).coerceAtLeast(0L)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LocalNcrustColors.current.background)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // 顶栏：Kanesumi 约定「顶栏是滚动列表的第一项」，这里是全屏 overlay，
            // 所以标题行固定在外层（与设置页的 SectionTitle 视觉一致）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetroText(
                    strings.offlineCacheTitle,
                    color = metro.onBackground,
                    style = LocalMetroTypography.current.pageHeading,
                    modifier = Modifier.weight(1f)
                )
                // 触摸契约（触摸陷阱第 7 条）：视觉 24dp，命中盒 48×48dp，且必须能被
                // 无障碍服务看见 —— 关闭按钮是这个全屏页唯一的出口。
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(onClick = onDismiss)
                        .semantics { contentDescription = strings.close },
                    contentAlignment = Alignment.Center
                ) {
                    MetroIcon(
                        Icons.Default.Close,
                        contentDescription = null,
                        tint = metro.onBackground,
                        sizeDp = 22.dp,
                    )
                }
            }

            // 用量：总量 / 上限 / 剩余。数字统一走 formatCacheBytes（与设置页同一个格式化）。
            MetroText(
                strings.offlineCacheUsage(
                    formatCacheBytes(usedBytes),
                    formatCacheBytes(limitBytes),
                    formatCacheBytes(remaining),
                ),
                color = metro.onSurfaceVariant,
                style = TextStyle(fontSize = 13.sp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // 上限选择器：合法区间 64..8192 MB（OfflineAudioCache.MIN_MB..MAX_MB）。
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { limitExpanded = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MetroText(
                        strings.offlineCacheLimitLabel,
                        color = metro.onBackground,
                        style = TextStyle(fontSize = 15.sp),
                        modifier = Modifier.weight(1f)
                    )
                    MetroText(
                        "$limitMb MB",
                        color = metro.primary,
                        style = TextStyle(fontSize = 15.sp)
                    )
                    MetroIcon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = metro.onSurfaceVariant,
                        sizeDp = 20.dp,
                    )
                }
                MetroSelectorFlyout(
                    expanded = limitExpanded,
                    onDismissRequest = { limitExpanded = false },
                    options = limitOptions,
                    selectedIndex = selectedLimitIndex,
                    onSelect = { index ->
                        val mb = OFFLINE_CACHE_LIMIT_MB.getOrNull(index) ?: return@MetroSelectorFlyout
                        limitMb = mb
                        OfflineAudioCache.setMaxMb(context, mb)
                        limitExpanded = false
                    },
                )
            }
            MetroText(
                strings.offlineCacheLimitHint,
                color = metro.onSurfaceVariant,
                style = TextStyle(fontSize = 12.sp),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            )

            MetroText(
                strings.offlineCacheListTitle(tracks.size),
                color = metro.onBackground,
                style = LocalMetroTypography.current.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
            )

            if (loaded && tracks.isEmpty()) {
                MetroText(
                    strings.offlineCacheEmpty,
                    color = metro.onSurfaceVariant,
                    style = TextStyle(fontSize = 13.sp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(tracks, key = { it.songId }) { track ->
                    OfflineTrackRow(
                        track = track,
                        bytes = bytesOf[track.songId],
                        playing = currentSongId > 0L && currentSongId == track.songId,
                        onDelete = { deleteTarget = track },
                    )
                }
                item {
                    MetroText(
                        strings.offlineCacheFragmentNotice,
                        color = metro.onSurfaceVariant,
                        style = TextStyle(fontSize = 12.sp),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)
                    )
                }
            }
        }
    }

    deleteTarget?.let { target ->
        DeleteOfflineTrackDialog(
            name = target.name?.takeIf { it.isNotBlank() } ?: strings.offlineCachePartial,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                // 删除要动 SimpleCache 与 prefs，放 IO；完成后重读列表与用量（reload 变化即重跑）。
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { OfflineAudioCache.removeSong(context, target.songId) }
                    }
                    Toast.makeText(context, strings.offlineCacheDeleted, Toast.LENGTH_SHORT).show()
                    reload++
                }
            },
        )
    }
}

/** 单行：封面色块 + 标题 / 歌手 · 档位 · 占用 + 删除入口。 */
@Composable
private fun OfflineTrackRow(
    track: OfflineTrack,
    bytes: Long?,
    playing: Boolean,
    onDelete: () -> Unit,
) {
    val strings = LocalStrings.current
    val metro = LocalMetroColors.current
    val audioLabel = qualityLabel(strings, track.level)
    val sizeLabel = if (bytes != null && bytes > 0L) formatCacheBytes(bytes) else strings.offlineCachePartial

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面色块：有图就铺满（Kanesumi 铁律：图片不裁圆角），没有就用按 id 派生的
        // 稳定色块 —— 列表行不因为缺封面塌成空白。
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(coverPlaceholder(track.songId))
        ) {
            track.albumPicUrl?.takeIf { it.isNotBlank() }?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            MetroText(
                track.name?.takeIf { it.isNotBlank() } ?: strings.offlineCachePartial,
                color = metro.onBackground,
                style = TextStyle(fontSize = 15.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MetroText(
                listOfNotNull(
                    track.artist?.takeIf { it.isNotBlank() },
                    audioLabel,
                    sizeLabel,
                ).joinToString(" · "),
                color = metro.onSurfaceVariant,
                style = TextStyle(fontSize = 12.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (playing) {
            // 当前播放曲目：删它会让正在读的 span 消失 → CacheDataSource 回源网络
            // （断网即播放错误）。这里直接禁用并说明原因，不给「删了才发现」的机会。
            MetroText(
                strings.offlineCachePlayingLocked,
                color = metro.primary,
                style = TextStyle(fontSize = 11.sp),
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(onClick = onDelete)
                    .semantics { contentDescription = strings.offlineCacheDeleteTrack },
                contentAlignment = Alignment.Center
            ) {
                MetroIcon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = metro.onSurfaceVariant,
                    sizeDp = 20.dp,
                )
            }
        }
    }
}

/** 二次确认。与设置页的「清除缓存」确认框同一套视觉（直角 + surfaceContainerHigh + 右对齐按钮）。 */
@Composable
private fun DeleteOfflineTrackDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(LocalNcrustColors.current.surfaceContainerHigh)
                .padding(24.dp)
        ) {
            MetroText(
                strings.offlineCacheDeleteTitle,
                color = LocalMetroColors.current.onBackground,
                style = LocalMetroTypography.current.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(12.dp))
            MetroText(
                strings.offlineCacheDeleteConfirm(name),
                color = LocalMetroColors.current.onSurfaceVariant,
                style = LocalMetroTypography.current.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OverlayDialogButton(text = strings.cancel, accent = false, onClick = onDismiss)
                Spacer(Modifier.width(12.dp))
                OverlayDialogButton(text = strings.offlineCacheDeleteTitle, accent = true, onClick = onConfirm)
            }
        }
    }
}

/** 直角文本按钮，命中区 48dp 高（触摸陷阱第 7 条）。 */
@Composable
private fun OverlayDialogButton(text: String, accent: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        MetroText(
            text,
            color = if (accent) LocalMetroColors.current.primary else LocalMetroColors.current.onSurfaceVariant,
            style = TextStyle(fontSize = 14.sp),
        )
    }
}

/** 档位 API 名 → 当前语言的显示名（复用 i18n 的 qualityOptions，未知档位原样显示）。 */
private fun qualityLabel(strings: Strings, level: String?): String? {
    if (level.isNullOrBlank()) return null
    val index = PlayerViewModel.QUALITY_LEVELS.indexOf(level)
    return strings.qualityOptions.getOrNull(index) ?: level
}

/** 缺封面时的稳定色块：按歌曲 id 派生色相，同一首歌每次都是同一个颜色。 */
private fun coverPlaceholder(songId: Long): Color {
    val hue = ((songId * 47L) % 360L).toFloat()
    return Color.hsv(hue, 0.35f, 0.55f)
}

/** 上限候选（MB）。合法区间 64..8192，与 [OfflineAudioCache.maxBytes] 的夹取范围一致。 */
private val OFFLINE_CACHE_LIMIT_MB = listOf(64, 128, 256, 384, 512, 768, 1024, 2048, 4096, 8192)
