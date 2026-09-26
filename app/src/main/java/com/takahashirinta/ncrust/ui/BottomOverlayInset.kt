package com.takahashirinta.ncrust.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.ui.player.TrayLayout

/**
 * 屏幕底部被浮层遮挡的总高度（滚动内容需预留，避免最后一项被盖住）。
 *
 * - 窄屏（<600dp）：底部导航 80dp + 托盘 80dp + 视觉缓冲 8dp = **168dp**。
 * - 宽屏（>=600dp）：无底部导航（改用左侧 sidebar），只剩托盘 80dp + 缓冲 8dp = **88dp**。
 *
 * 系统导航栏（手势条 / 三按钮）不算在内——由外层 systemBars padding 补偿。
 *
 * ⚠️ **v2.5.5 起这里不再写字面量。** 托盘从 56dp 加到 80dp 时，本文件是三个消费者之一
 * （另两个是 `PlayerCard` 的 `.height(...)` 与 `MainActivity` 的 `miniBarHeightPx`）——
 * 只改前者会让**列表最后一项被托盘盖住 24dp**，而那个症状与「托盘」在代码上毫无关联，
 * 是最难归因的一类回归。算式全部搬进 [TrayLayout.bottomOverlayInsetDp]。
 *
 * 用法（须在 composable 内）：
 * ```
 * LazyColumn(contentPadding = PaddingValues(bottom = BottomOverlayInsetDp)) { ... }
 * ```
 */
val BottomOverlayInsetDp: Dp
    @Composable get() = TrayLayout
        .bottomOverlayInsetDp(LocalConfiguration.current.screenWidthDp >= 600)
        .dp
