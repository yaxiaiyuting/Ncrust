package com.takahashirinta.ncrust.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/**
 * 主题色来源三选一（B2-C）。形态对齐同页的 ThemeModeSelector：三等分方块 + 选中高亮。
 * 系统取色在 API < 31 上不可用：该项置灰、不可点，并在下方给出说明。
 */
@Composable
fun AccentSourceSelector(
    selected: AccentSource,
    systemEnabled: Boolean,
    labels: Triple<String, String, String>,
    systemHint: String,
    onSelect: (AccentSource) -> Unit,
) {
    val options = listOf(
        AccentSource.PRESET to labels.first,
        AccentSource.COVER to labels.second,
        AccentSource.SYSTEM to labels.third,
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (source, label) ->
                val enabled = source != AccentSource.SYSTEM || systemEnabled
                val active = source == selected && enabled
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .border(
                            1.dp,
                            when {
                                !enabled -> LocalMetroColors.current.divider.copy(alpha = 0.4f)
                                active -> LocalMetroColors.current.primary
                                else -> LocalMetroColors.current.divider
                            }
                        )
                        .background(
                            if (active) LocalMetroColors.current.primary.copy(alpha = 0.14f)
                            else Color.Transparent
                        )
                        .clickable(enabled = enabled) { onSelect(source) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MetroText(
                        label,
                        color = when {
                            !enabled -> LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.45f)
                            active -> LocalMetroColors.current.primary
                            else -> LocalMetroColors.current.onSurfaceVariant
                        },
                        style = TextStyle(fontSize = 12.sp),
                        maxLines = 1
                    )
                }
            }
        }
        if (!systemEnabled) {
            Spacer(Modifier.height(6.dp))
            MetroText(
                systemHint,
                color = LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.7f),
                style = TextStyle(fontSize = 11.sp)
            )
        }
    }
}
