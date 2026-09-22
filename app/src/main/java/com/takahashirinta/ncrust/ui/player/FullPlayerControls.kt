/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明（Bug1「音质切换」/ A3「降级语义」）：
 *   - 新增 qualityStatusFlow：音质标签按**实际文件参数**（br/type）追加角标 ——
 *     「已降级」/「无权限」/「该曲无此档位」，不再把"其实在播 Hi-Res"误报成降级。
 */

package com.takahashirinta.ncrust.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.formatDuration
import com.takahashirinta.ncrust.player.QualityStatus
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.flow.StateFlow

@Composable
fun FullPlayerControls(
    isPlaying: Boolean,
    showLyrics: Boolean,
    showQueue: Boolean,
    progressFlow: StateFlow<Float>,
    positionFlow: StateFlow<Long>,
    durationFlow: StateFlow<Long>,
    qualityIndexFlow: StateFlow<Int>,
    qualityStatusFlow: StateFlow<QualityStatus>,
    qualityOptions: List<String>,
    onPlayPause: () -> Unit,
    onPlayPrevious: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onAddToLibrary: () -> Unit = {},
    // 当前歌是否已在收藏库: true 显示对号, 按下走"移出库"分支
    isInLibrary: Boolean = false,
    isBufferingFlow: StateFlow<Boolean>,
    onSeek: (Float) -> Unit = {},
    onNavigateToUser: () -> Unit = {},
    lyricsUnavailable: Boolean = false,
    previousEnabled: Boolean = true,
    // 宽屏左栏用紧凑尺寸：缩小按钮/间距，把纵向空间让给封面区。
    compact: Boolean = false,
    // 宽屏横向布局：进度行 + 操作行扁平铺开，替代手机的竖向大按钮堆叠。
    landscape: Boolean = false,
    // P1：大屏模式把音质控件搬到左栏（就地选择器）后，控制条右端不再重复画一个
    // 「点一下跳设置页」的音质角标 —— 同一屏里两个音质入口会互相打架。
    showQuality: Boolean = true,
    // P1：大屏幕模式（横屏桌面播放器布局）。true 时按钮图标换成"退出大屏"。
    bigScreen: Boolean = false,
    /**
     * P1：大屏幕模式入口/出口（同一个按钮、同一个槽位）。
     *
     * 为什么放在这条操作行里而不是屏幕右上角：
     *  - 这一行在展开态子树里（折叠态整棵不挂载，符合 B-1 的命中区契约），
     *    并且整块控制条共用 progress>0.7 的淡入，入口天然"只在展开且有歌时出现"；
     *  - 右上角在横屏大屏下是歌词面板的 A-/A+ 字号按钮，放那里会互相抢命中区。
     */
    onToggleBigScreen: () -> Unit = {},
    /**
     * 横向控制条右端的替代槽位（仅 [showQuality] = false 时使用）。
     *
     * 大屏模式用它放「退出大屏」：**不能**放进左端那组按钮 —— 横向控制条是
     * 「左组贴左 + 传输组居中 + 右组贴右」的三段式，左组加到 4 个按钮后（4×40dp）
     * 在窄的右栏里会顶到居中的传输组，实测 PCL110 横屏下与「上一首」重叠。
     * 右端正好是音质角标让出来的空位。
     */
    trailing: (@Composable () -> Unit)? = null
) {
    val strings = LocalStrings.current
    // 触觉反馈:播放/暂停/切歌/开关面板给一个轻振,补足无 ripple 时代的确认感
    val haptic = LocalHapticFeedback.current
    fun tick() = haptic.performHapticFeedback(HapticFeedbackType.LongPress)

    val hPad = if (compact) 16.dp else 24.dp
    val bottomPad = if (compact) 8.dp else 32.dp
    val gapTight = if (compact) 4.dp else 8.dp
    val gapLoose = if (compact) 8.dp else 16.dp
    val ctrlRowPad = if (compact) 2.dp else 16.dp
    val sideBtn = if (compact) 44.dp else 60.dp
    val sideIcon = if (compact) 30.dp else 40.dp
    val playBtn = if (compact) 58.dp else 84.dp
    val playIcon = if (compact) 38.dp else 56.dp
    val sideGap = if (compact) 20.dp else 32.dp
    val toggleBtn = if (compact) 44.dp else 56.dp
    val toggleIcon = if (compact) 26.dp else 32.dp

    if (landscape) {
        // 宽屏横向控件条：进度行（位置 · 进度 · 时长）+ 操作行（面板开关 · 音质 · 传输），
        // 扁平铺开，不再照搬手机的竖向大按钮堆叠。
        val lPad = if (compact) 12.dp else 20.dp
        val lTop = if (compact) 2.dp else 4.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = lPad, vertical = lTop)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PositionText(positionFlow = positionFlow, modifier = Modifier)
                Spacer(Modifier.width(8.dp))
                SlimProgressBar(
                    progressFlow = progressFlow,
                    durationFlow = durationFlow,
                    isBufferingFlow = isBufferingFlow,
                    onSeek = onSeek,
                    modifier = Modifier.weight(1f),
                    horizontalPadding = 0.dp,
                )
                Spacer(Modifier.width(8.dp))
                DurationText(durationFlow = durationFlow, modifier = Modifier)
            }
            Spacer(Modifier.height(6.dp))
            // 对称三段：左=面板开关，中=传输（主控居中），右=音质。
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.align(Alignment.CenterStart),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(enabled = !lyricsUnavailable) {
                                tick()
                                onToggleLyrics()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        MetroIcon(
                            imageVector = Icons.Default.Lyrics,
                            contentDescription = strings.lyricsButton,
                            tint = if (lyricsUnavailable) LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.5f)
                            else if (showLyrics) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                            sizeDp = 24.dp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable {
                                tick()
                                onToggleQueue()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        MetroIcon(
                            imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                            contentDescription = strings.queueButton,
                            tint = if (showQueue) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                            sizeDp = 24.dp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable {
                                tick()
                                onAddToLibrary()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        MetroIcon(
                            imageVector = if (isInLibrary) Icons.Default.Check else Icons.Default.Add,
                            contentDescription = strings.addToLibraryButton,
                            tint = if (isInLibrary) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                            sizeDp = 24.dp
                        )
                    }
                }
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clickable(enabled = previousEnabled) {
                                tick()
                                onPlayPrevious()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        MetroIcon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = strings.prevButton,
                            tint = if (previousEnabled) LocalMetroColors.current.onBackground else LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.5f),
                            sizeDp = 28.dp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clickable {
                                tick()
                                onPlayPause()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        MetroIcon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) strings.pauseButton else strings.playButton,
                            tint = LocalMetroColors.current.onBackground,
                            sizeDp = 36.dp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clickable {
                                tick()
                                onPlayNext()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        MetroIcon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = strings.nextButton,
                            tint = LocalMetroColors.current.onBackground,
                            sizeDp = 28.dp
                        )
                    }
                }
                if (showQuality) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .background(LocalMetroColors.current.surfaceVariant)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onNavigateToUser() }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        PlayerQualityLabel(
                            qualityIndexFlow = qualityIndexFlow,
                            qualityStatusFlow = qualityStatusFlow,
                            options = qualityOptions
                        )
                    }
                } else if (trailing != null) {
                    Box(modifier = Modifier.align(Alignment.CenterEnd)) { trailing() }
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = bottomPad)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = hPad)
        ) {
            // 位置文本抽出为叶子 Composable：仅它随 250ms 位置更新重组，
            // 不牵连按钮/进度条区域
            PositionText(
                positionFlow = positionFlow,
                modifier = Modifier.align(Alignment.CenterStart)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(LocalMetroColors.current.surfaceVariant)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onNavigateToUser() }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                PlayerQualityLabel(
                    qualityIndexFlow = qualityIndexFlow,
                    qualityStatusFlow = qualityStatusFlow,
                    options = qualityOptions
                )
            }
            DurationText(
                durationFlow = durationFlow,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
        Spacer(Modifier.height(gapTight))
        SlimProgressBar(
            progressFlow = progressFlow,
            durationFlow = durationFlow,
            isBufferingFlow = isBufferingFlow,
            onSeek = onSeek
        )
        Spacer(Modifier.height(gapLoose))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = ctrlRowPad),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(sideBtn)
                    .clickable(enabled = previousEnabled) {
                        tick()
                        onPlayPrevious()
                    },
                contentAlignment = Alignment.Center
            ) {
                MetroIcon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = strings.prevButton,
                    tint = if (previousEnabled) LocalMetroColors.current.onBackground else LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.5f),
                    sizeDp = sideIcon
                )
            }
            Spacer(Modifier.width(sideGap))
            Box(
                modifier = Modifier
                    .size(playBtn)
                    .clickable {
                        tick()
                        onPlayPause()
                    },
                contentAlignment = Alignment.Center
            ) {
                MetroIcon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) strings.pauseButton else strings.playButton,
                    tint = LocalMetroColors.current.onBackground,
                    sizeDp = playIcon
                )
            }
            Spacer(Modifier.width(sideGap))
            Box(
                modifier = Modifier
                    .size(sideBtn)
                    .clickable {
                        tick()
                        onPlayNext()
                    },
                contentAlignment = Alignment.Center
            ) {
                MetroIcon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = strings.nextButton,
                    tint = LocalMetroColors.current.onBackground,
                    sizeDp = sideIcon
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Box(
                modifier = Modifier
                    .size(toggleBtn)
                    .clickable(enabled = !lyricsUnavailable) {
                        tick()
                        onToggleLyrics()
                    },
                contentAlignment = Alignment.Center
            ) {
                MetroIcon(
                    imageVector = Icons.Default.Lyrics,
                    contentDescription = strings.lyricsButton,
                    // 无歌词/未加载时置灰, 语义是该歌不可展开歌词
                    tint = if (lyricsUnavailable) LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.5f)
                    else if (showLyrics) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                    sizeDp = toggleIcon
                )
            }
            Box(
                modifier = Modifier
                    .size(toggleBtn)
                    .clickable {
                        tick()
                        onToggleQueue()
                    },
                contentAlignment = Alignment.Center
            ) {
                MetroIcon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                    contentDescription = strings.queueButton,
                    tint = if (showQueue) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                    sizeDp = toggleIcon
                )
            }
            Box(
                modifier = Modifier
                    .size(toggleBtn)
                    .clickable {
                        tick()
                        onAddToLibrary()
                    },
                contentAlignment = Alignment.Center
            ) {
                // 已在库 → 对号(按动移出); 不在库 → 加号(按动收藏)
                MetroIcon(
                    imageVector = if (isInLibrary) Icons.Default.Check else Icons.Default.Add,
                    contentDescription = strings.addToLibraryButton,
                    tint = if (isInLibrary) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                    sizeDp = toggleIcon
                )
            }
            // P1：大屏幕模式入口。同一槽位在横屏大屏下变成"退出大屏"。
            Box(
                modifier = Modifier
                    .size(toggleBtn)
                    .clickable {
                        tick()
                        onToggleBigScreen()
                    },
                contentAlignment = Alignment.Center
            ) {
                MetroIcon(
                    imageVector = if (bigScreen) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                    contentDescription = if (bigScreen) strings.bigScreenExit else strings.bigScreenEnter,
                    tint = if (bigScreen) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                    sizeDp = toggleIcon
                )
            }
        }
    }
}

@Composable
private fun PositionText(positionFlow: StateFlow<Long>, modifier: Modifier) {
    val position by positionFlow.collectAsState()
    MetroText(
        formatDuration(position),
        color = LocalMetroColors.current.onSurfaceVariant,
        style = TextStyle(fontSize = 12.sp),
        modifier = modifier
    )
}

@Composable
private fun DurationText(durationFlow: StateFlow<Long>, modifier: Modifier) {
    val duration by durationFlow.collectAsState()
    MetroText(
        formatDuration(duration),
        color = LocalMetroColors.current.onSurfaceVariant,
        style = TextStyle(fontSize = 12.sp),
        modifier = modifier
    )
}

/**
 * 音质档位 + 状态角标。唯一实现，两处复用：
 *  - 控制条（窄屏底部居中 / 宽屏横向右端）：点一下跳设置页；
 *  - P1 大屏模式左栏：包一层可点盒子 + MetroSelectorFlyout，就地切换档位。
 * 拆成公开 Composable 而不是复制一份，是为了让「实际档位 vs 偏好档位」的角标语义
 * （已降级 / 无权限 / 该曲无此档位）只有一处真相。
 */
@Composable
fun PlayerQualityLabel(
    qualityIndexFlow: StateFlow<Int>,
    qualityStatusFlow: StateFlow<QualityStatus>,
    options: List<String>
) {
    val qualityIndex by qualityIndexFlow.collectAsState()
    val status by qualityStatusFlow.collectAsState()
    val label = options.getOrElse(qualityIndex) { options.getOrElse(3) { "" } }
    // A3：状态按实际文件参数（br/type）判定，不再按 level 序号 —— 明确区分
    // 「无权限」「该曲无此档位」「真实降级」，避免把"其实在播 Hi-Res"或
    // "沉浸声换格式"误报成「已降级」。
    val badge = when (status) {
        QualityStatus.NORMAL -> null
        QualityStatus.DOWNGRADED -> LocalStrings.current.qualityDowngradedBadge
        QualityStatus.NO_ENTITLEMENT -> LocalStrings.current.qualityNoEntitlementBadge
        QualityStatus.SONG_LACKS_TIER -> LocalStrings.current.qualitySongLacksTierBadge
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        MetroText(
            label,
            color = LocalMetroColors.current.primary,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
        // 状态非 NORMAL 时才加后缀，否则用户改了设置却听不出变化，会以为"切换音质失败"。
        if (badge != null) {
            Spacer(Modifier.width(4.dp))
            MetroText(
                badge,
                color = LocalMetroColors.current.onSurfaceVariant,
                style = TextStyle(fontSize = 10.sp),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
