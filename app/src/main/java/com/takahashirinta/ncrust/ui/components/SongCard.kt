package com.takahashirinta.ncrust.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.CoverUrls
import io.github.takahashirinta.kanesumi.anim.sokuou.SokuouPresets
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import kotlinx.coroutines.launch

enum class SongCardStyle {
    LIST,
    COMPACT,
    GRID
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SongCard(
    song: SongItem,
    style: SongCardStyle = SongCardStyle.LIST,
    modifier: Modifier = Modifier,
    coverSize: Dp = Dp.Unspecified,
    onClick: () -> Unit = {},
    onShowMenu: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    isCurrentPlaying: Boolean = false,
    showCover: Boolean = true
) {
    val strings = LocalStrings.current
    val artistStr = song.artists?.joinToString("/") { it.name } ?: strings.unknownArtist
    val albumName = song.album?.name ?: ""
    val durationStr = song.duration?.let { formatDuration(it) } ?: ""
    // v2.3.0 · C/D：音源归属 + 版权可用性 + 原唱/翻唱。
    //
    // v2.1.0 · E 时这里只标 QQ（`else -> ""`）。改成两源都标的原因是聚合搜索会把两源的
    // 条目混进同一个列表，而两源的 id 完全独立 —— 实测同关键词下会出现**完全同名**的行
    // （《晴天》网易云 186016 / QQ 00083kc41YcFuR），不标音源用户判断不出哪行是哪个源。
    // 装配规则（含「什么时候什么都不显示」）全部在 SongTags 里，JVM 可单测。
    val tags = remember(song, strings) { SongTags.of(song, strings) }
    val sourceBadge = tags.firstOrNull { it.kind == SongTagKind.SOURCE }?.text.orEmpty()
    val extraBadges = tags.filter { it.kind != SongTagKind.SOURCE }
    val coverOriginLine = remember(song, strings) { SongTags.coverOriginLine(song, strings) }

    when (style) {
        SongCardStyle.LIST, SongCardStyle.COMPACT -> {
            val actualCoverSize = when {
                coverSize != Dp.Unspecified -> coverSize
                else -> 72.dp
            }

            // 无边框版式：封面贴屏幕左沿（无左 padding），文字与右侧 actions 保留 16dp 右 padding。
            // 长按触发菜单；右侧 MoreVert 按钮取消，避免与"无边框"视觉冲突。
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = { onShowMenu?.invoke() }
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showCover) {
                    AsyncImage(
                        model = CoverUrls.small(song.album?.picUrl),
                        contentDescription = strings.coverDesc,
                        // 纯色占位:避免低端机解码完成前出现"空方块"闪变(Metro 不做 crossfade,直接落图)
                        placeholder = ColorPainter(LocalMetroColors.current.surfaceVariant),
                        modifier = Modifier.size(actualCoverSize),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(14.dp))
                } else {
                    Spacer(Modifier.width(16.dp))
                }

                Column(Modifier.weight(1f)) {
                    // v2.3.0 · C/D：歌名 + 角标。**角标必须与歌名同一行**，不能接在副标题末尾 ——
                    // release 真机验证时发现：副标题是 maxLines=1 + Ellipsis，而它已经装着
                    // 「艺人 · 专辑  时长  · 音源」，长专辑名（如
                    // `The Life of a Showgirl: The Encore`）会把后面的「可播放 / 原唱」
                    // 整段省略掉。而那两个角标恰恰是**用户唯一需要一眼看到的信息**
                    // （任务书 5.4「用户能一眼看出哪首歌能播、用哪个源」）。
                    // 任务书 6.3 也要求「标签位置：歌曲名前面或后面，统一」。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MetroText(
                            song.name,
                            color = if (isCurrentPlaying) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                            style = LocalMetroTypography.current.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // weight 而不是 wrapContent：歌名过长时**让歌名自己省略**，
                            // 角标始终留在屏上。
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (extraBadges.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            SongTagChips(extraBadges)
                        }
                    }
                    MetroText(
                        buildString {
                            append(artistStr)
                            if (albumName.isNotEmpty()) append(" · $albumName")
                            if (durationStr.isNotEmpty()) append("  $durationStr")
                            if (sourceBadge.isNotEmpty()) append("  · $sourceBadge")
                        },
                        color = LocalMetroColors.current.onSurfaceVariant,
                        style = LocalMetroTypography.current.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // v2.3.0 · D：只有确证是翻唱**且**服务端给了原曲信息时才多出这一行。
                    // 实测翻唱里 51% 有 originSongSimpleData，其余只在上面的角标里显示「翻唱」。
                    if (coverOriginLine != null) {
                        MetroText(
                            coverOriginLine,
                            color = LocalMetroColors.current.onSurfaceVariant,
                            style = LocalMetroTypography.current.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (actions != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        actions.invoke(this)
                    }
                }
                Spacer(Modifier.width(16.dp))
            }
        }

        SongCardStyle.GRID -> {
            // 按压缩放动画：Animatable + graphicsLayer，动画帧仅在 draw 阶段消费。
            // 只给 GRID 创建——LIST 是首页高频路径，每项省一个 Animatable + 协程作用域分配。
            val scaleAnim = remember { Animatable(1f) }
            val scaleScope = rememberCoroutineScope()
            Column(
                modifier = modifier
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                scaleScope.launch { scaleAnim.animateTo(1.03f, SokuouPresets.QuickInteraction) }
                                tryAwaitRelease()
                                scaleScope.launch { scaleAnim.animateTo(1f, SokuouPresets.QuickInteraction) }
                            },
                            onTap = { onClick() },
                            onLongPress = { onShowMenu?.invoke() }
                        )
                    }
                    .graphicsLayer {
                        val s = scaleAnim.value
                        scaleX = s
                        scaleY = s
                    }
            ) {
                AsyncImage(
                    model = CoverUrls.small(song.album?.picUrl),
                    contentDescription = strings.coverDesc,
                    placeholder = ColorPainter(LocalMetroColors.current.surfaceVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.height(8.dp))
                // 网格格子里空间更紧，所以角标只显示**可用性**（能不能播），
                // 版本标签留给列表形态 —— 一格里塞三个角标会变成噪音。
                val gridBadges = extraBadges.filter { it.kind == SongTagKind.AVAILABILITY }
                MetroText(
                    if (gridBadges.isEmpty()) {
                        song.name
                    } else {
                        song.name + "  " + gridBadges.joinToString(" ") { it.text }
                    },
                    color = LocalMetroColors.current.onBackground,
                    style = LocalMetroTypography.current.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                MetroText(
                    if (sourceBadge.isEmpty()) "$artistStr · $albumName" else "$artistStr · $sourceBadge",
                    color = LocalMetroColors.current.onSurfaceVariant,
                    style = LocalMetroTypography.current.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * v2.3.0 · C/D：一行小角标（可播放 / 需会员 / 无版权 / 原唱 / 翻唱）。
 *
 * 直角、无圆角、小字号、低饱和底色 —— Kanesumi Design 的「信息优先、不抢主视觉」。
 * 颜色按 [SongTagKind] 分：可用性用主题色系（能不能播是要紧信息），版本用中性色
 * （原唱/翻唱是补充信息）。
 *
 * 尺寸刻意压到 [chipFontSp]：它要能与歌名同行而不把歌名挤没。
 */
private val chipFontSp = 10

@Composable
private fun SongTagChips(tags: List<SongTag>, modifier: Modifier = Modifier) {
    val colors = LocalMetroColors.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        tags.forEachIndexed { index, tag ->
            if (index > 0) Spacer(Modifier.width(4.dp))
            val tint = when (tag.kind) {
                SongTagKind.AVAILABILITY -> colors.primary
                SongTagKind.VERSION -> colors.onSurfaceVariant
                SongTagKind.SOURCE -> colors.onSurfaceVariant
            }
            Box(
                modifier = Modifier
                    .background(colors.surfaceVariant)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                MetroText(
                    tag.text,
                    color = tint,
                    style = TextStyle(fontSize = chipFontSp.sp, lineHeight = (chipFontSp + 2).sp),
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlayAllButton(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    onClick: () -> Unit
) {
    // 圆形外框(用户决策): 直径 = 原边长。clip 先于 background, 裁切不产生
    // 额外合成层, 与方形按钮同价。
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(LocalMetroColors.current.primary)
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        MetroIcon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = LocalStrings.current.playAllButton,
            tint = LocalMetroColors.current.onPrimary,
            sizeDp = size * 0.55f
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
