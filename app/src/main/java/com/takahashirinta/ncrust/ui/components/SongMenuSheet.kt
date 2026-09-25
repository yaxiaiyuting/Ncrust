package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.ui.theme.AppShapes
import io.github.takahashirinta.kanesumi.controls.MetroBottomSheet
import io.github.takahashirinta.kanesumi.controls.MetroDivider
import io.github.takahashirinta.kanesumi.core.insets.metroNavigationBarsPadding
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration

data class SongMenuAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit
)

@Composable
fun SongMenuSheet(
    song: SongItem,
    actions: List<SongMenuAction>,
    onDismiss: () -> Unit
) {
    MetroBottomSheet(
        onDismiss = onDismiss,
        // 信息区作为 dragHandle：MetroBottomSheet 只在 handle 区挂纵向拖拽手势，
        // 语义与旧实现（整块信息区可下滑收起）一致。
        dragHandle = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // v2.5.0 · B：封面改为圆角 + 1dp 描边（此前是直角——旧正典「图片不裁圆角」）。
                // 形状按渲染边长选：112dp < 160dp ⇒ AppShapes.small。仍然贴屏左边缘。
                AsyncImage(
                    model = CoverUrls.small(song.album?.picUrl),
                    contentDescription = null,
                    modifier = Modifier
                        .size(112.dp)
                        .appCoverFrame(shape = AppShapes.small),
                    contentScale = ContentScale.Crop
                )
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    MetroText(
                        song.name,
                        color = LocalMetroColors.current.onBackground,
                        style = LocalMetroTypography.current.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    MetroText(
                        song.artists?.joinToString("/") { it.name } ?: "",
                        color = LocalMetroColors.current.primary,
                        style = LocalMetroTypography.current.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val albumName = song.album?.name
                    if (!albumName.isNullOrEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        MetroText(
                            albumName,
                            color = LocalMetroColors.current.onSurfaceVariant,
                            style = LocalMetroTypography.current.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
    ) {
        MetroDivider()

        // ── v2.5.0 · D：操作列表**必须有界且可滚动** ────────────────────────────
        //
        // 真机实测（S6/G9209 · Android 7.0 · 1440×2560 = 411×731dp）：操作条目加到
        // **10 条**时，弹层总高 112dp(信息区) + 10×64dp + 安全区 ≈ 752dp > 731dp，
        // 最后一条「单曲信息」被挤出屏幕；而 `MetroBottomSheet` 的内容是一个**不滚动**的
        // Column，实测在其上做上滑手势坐标完全不变 —— 也就是说那一条**点不到**。
        //
        // 这是本版新增「添加到下一首播放」时**真实引入的回归**（9 条时刚好放得下），
        // 也是弹层自身的隐患：条目数只会继续增长。修法有两处可选：
        //  ① 改 Kanesumi 的 `MetroBottomSheet` 让它自己可滚动 —— **不做**：
        //     本版按用户裁定不改那个仓库（会让发布产物依赖另一个仓库的未发布提交，
        //     破坏「git clone 即可复现」，同 v1.5.0 搬 MetroLyricsPanel 的理由）；
        //  ② 在**调用点**给操作列表一个最大高度 + 垂直滚动 —— **本实现**。
        //
        // 最大高度取「屏幕高 − 176dp」：176 = 信息区 112 + 上下留白 64，
        // 保证「信息区 + 列表 + 安全区」一定放得下。用 `heightIn(max=)` 而不是固定高度，
        // 所以内容本来就放得下时（平板 / 横屏 / 条目少）**行为与改动前逐字节一致**，
        // 不会平白多出一个滚动容器。
        //
        // v2.5.1：这段算术搬进了纯逻辑对象 [SongMenuSheetLayout]，并由
        // `SongMenuSheetLayoutTest` 在 JVM 上钉住「S6 竖屏 10 条必须可滚」与
        // 「1000dp 高 10 条不许平白多出滚动容器」两条 —— 本版补的是**回归单测**，
        // 修法本身一字未动（v2.5.0 的真机验证已经证明修法有效）。
        val maxActionsHeight = SongMenuSheetLayout.maxActionsHeightDp(
            LocalConfiguration.current.screenHeightDp
        ).dp
        Column(
            modifier = Modifier
                .heightIn(max = maxActionsHeight)
                .verticalScroll(rememberScrollState())
        ) {
            // 可扩展操作列表
            actions.forEach { action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            action.onClick()
                            onDismiss()
                        }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MetroIcon(
                        imageVector = action.icon,
                        contentDescription = null,
                        tint = LocalMetroColors.current.onBackground,
                        sizeDp = 24.dp
                    )
                    Spacer(Modifier.width(16.dp))
                    MetroText(
                        action.label,
                        color = LocalMetroColors.current.onBackground,
                        style = LocalMetroTypography.current.bodyLarge
                    )
                }
            }
        }

        Spacer(Modifier.metroNavigationBarsPadding())
    }
}
