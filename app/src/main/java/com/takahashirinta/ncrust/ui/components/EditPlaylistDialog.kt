package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.network.PlaylistEditApi
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.controls.MetroButton
import io.github.takahashirinta.kanesumi.controls.MetroDialog
import io.github.takahashirinta.kanesumi.controls.MetroDivider
import io.github.takahashirinta.kanesumi.controls.MetroDropdownMenu
import io.github.takahashirinta.kanesumi.controls.MetroDropdownMenuItem
import io.github.takahashirinta.kanesumi.controls.MetroTextField
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.launch

/** 编辑歌单的结果；RATE_LIMITED 单独出来是因为服务端 405 的冷却可能超过 10 分钟。 */
enum class EditPlaylistOutcome { SUCCESS, RATE_LIMITED, FAILED }

/**
 * v1.3.0 · B4：编辑歌单（名称 / 简介 / 隐私）。
 *
 * 三个字段对应服务端三个**独立**端点（update/name、desc/update、update/privacy），
 * 客户端按需只发改动过的那个 —— 每多一次写请求就多 2s 写闸门间隔，也会更快撞 405。
 */
@Composable
fun EditPlaylistDialog(
    initialName: String,
    initialDesc: String,
    initialPrivacy: Int,
    onDismiss: () -> Unit,
    onSave: suspend (name: String, desc: String, privacy: Int) -> EditPlaylistOutcome,
    /** 可选：对话框底部追加「删除歌单」（仅自建歌单传）。点击后由调用方开二次确认。 */
    onDelete: (() -> Unit)? = null,
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(initialName) }
    var desc by remember { mutableStateOf(initialDesc) }
    var privacy by remember { mutableIntStateOf(initialPrivacy) }
    var privacyMenuOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    MetroDialog(onDismissRequest = { if (!saving) onDismiss() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            MetroText(
                strings.playlistEditTitle,
                color = colors.onBackground,
                style = typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }
        MetroDivider()

        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            MetroTextField(
                value = name,
                onValueChange = { if (it.length <= 40) { name = it; errorText = null } },
                modifier = Modifier.fillMaxWidth(),
                enabled = !saving,
                placeholder = strings.playlistNameHint,
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            MetroTextField(
                value = desc,
                onValueChange = { if (it.length <= 200) { desc = it; errorText = null } },
                modifier = Modifier.fillMaxWidth(),
                enabled = !saving,
                placeholder = strings.playlistDescHint,
                maxLines = 3,
            )
            Spacer(Modifier.height(12.dp))
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !saving) { privacyMenuOpen = true }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetroText(
                        strings.playlistPrivacy,
                        color = colors.onSurfaceVariant,
                        style = typography.bodyMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    MetroText(
                        if (privacy == PlaylistEditApi.PRIVACY_PRIVATE) {
                            strings.playlistPrivacyPrivate
                        } else {
                            strings.playlistPrivacyPublic
                        },
                        color = colors.primary,
                        style = typography.bodyMedium,
                    )
                }
                MetroDropdownMenu(
                    expanded = privacyMenuOpen,
                    onDismissRequest = { privacyMenuOpen = false },
                ) {
                    MetroDropdownMenuItem(
                        text = strings.playlistPrivacyPublic,
                        onClick = { privacy = 0; privacyMenuOpen = false },
                    )
                    MetroDropdownMenuItem(
                        text = strings.playlistPrivacyPrivate,
                        onClick = { privacy = 10; privacyMenuOpen = false },
                    )
                }
            }

            errorText?.let { message ->
                Spacer(Modifier.height(8.dp))
                MetroText(message, color = Color.Red, style = typography.bodySmall)
            }
        }

        MetroDivider()
        // v1.3.0：编辑入口放在顶部 scrim 后，原来「⋮ → 底部菜单 → 编辑」的两跳收敛成
        // 「⋮ → 本对话框」一跳；删除作为次要动作直接挂在底部，仍走调用方的二次确认。
        if (onDelete != null) {
            MetroButton(
                text = strings.playlistDelete,
                onClick = {
                    onDismiss()
                    onDelete()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !saving,
                containerColor = Color.Transparent,
                contentColor = LocalMetroColors.current.primary,
            )
            MetroDivider()
        }
        Row(Modifier.fillMaxWidth()) {
            MetroButton(
                text = strings.cancel,
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                enabled = !saving,
                containerColor = Color.Transparent,
                contentColor = colors.onSurfaceVariant,
            )
            MetroButton(
                text = strings.playlistSave,
                onClick = {
                    saving = true
                    errorText = null
                    scope.launch {
                        when (val outcome = onSave(name.trim(), desc, privacy)) {
                            EditPlaylistOutcome.SUCCESS -> onDismiss()
                            EditPlaylistOutcome.RATE_LIMITED -> {
                                saving = false
                                errorText = strings.playlistOpTooFrequent
                            }
                            EditPlaylistOutcome.FAILED -> {
                                saving = false
                                errorText = strings.playlistCreateFailed
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = name.isNotBlank() && !saving,
            )
        }
    }
}
