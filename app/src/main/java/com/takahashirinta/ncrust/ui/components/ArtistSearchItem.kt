package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.network.ArtistSearchItem
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.theme.AppShapes
import io.github.takahashirinta.kanesumi.controls.MetroDropdownMenu
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistSearchItem(
    artist: ArtistSearchItem,
    onClick: () -> Unit,
    menuContent: (@Composable ColumnScope.(onDismiss: () -> Unit) -> Unit)? = null
) {
    val strings = LocalStrings.current
    val aliasStr = artist.alias?.joinToString(" / ") ?: ""
    val transStr = artist.trans ?: ""

    var showMenu by remember { mutableStateOf(false) }

    Box {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { if (menuContent != null) showMenu = true }
            )
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(72.dp)) {
            // v2.5.0 · B：艺人头像画的是**人像**而不是专辑封面，所以取圆形（AppShapes.full），
            // 不套用「≥160dp → large / <160dp → small」那条方封面尺寸规则；顺带带上 1dp 描边。
            // appCoverFrame 必须排在 background **之前**：圆形裁切要罩住这块占位底色，
            // 否则方形底色会从圆形的四角露出来（描边在内容之后绘制，不会被底色盖掉）。
            AsyncImage(
                model = CoverUrls.small(artist.picUrl),
                contentDescription = strings.artistAvatarDesc,
                modifier = Modifier
                    .fillMaxSize()
                    .appCoverFrame(shape = AppShapes.full)
                    .background(LocalMetroColors.current.surfaceVariant),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MetroText(
                    artist.name,
                    color = LocalMetroColors.current.onBackground,
                    style = LocalMetroTypography.current.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (transStr.isNotEmpty()) {
                    MetroText(
                        " · $transStr",
                        color = LocalMetroColors.current.onSurfaceVariant,
                        style = LocalMetroTypography.current.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (aliasStr.isNotEmpty()) {
                MetroText(
                    aliasStr,
                    color = LocalMetroColors.current.onSurfaceVariant,
                    style = LocalMetroTypography.current.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            MetroText(
                strings.artistStats(artist.albumSize ?: 0, artist.musicSize ?: 0),
                color = LocalMetroColors.current.onSurfaceVariant,
                style = LocalMetroTypography.current.bodySmall
            )
        }
    }
    if (menuContent != null) {
        MetroDropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            containerColor = LocalMetroColors.current.surface
        ) {
            menuContent { showMenu = false }
        }
    }
    }
}
