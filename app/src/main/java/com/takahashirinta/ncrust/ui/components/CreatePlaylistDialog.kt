package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import kotlinx.coroutines.launch
import io.github.takahashirinta.kanesumi.controls.MetroButton
import io.github.takahashirinta.kanesumi.controls.MetroDialog
import io.github.takahashirinta.kanesumi.controls.MetroDivider
import io.github.takahashirinta.kanesumi.controls.MetroDropdownMenu
import io.github.takahashirinta.kanesumi.controls.MetroDropdownMenuItem
import io.github.takahashirinta.kanesumi.controls.MetroTextField
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/**
 * 「保存为歌单」结果。创建成功后若还要往里加歌，加歌结果单独带出来 ——
 * 只报「创建成功」而把加歌失败吞掉，用户会以为歌已经在歌单里。
 */
enum class PlaylistCreateOutcome {
    /** 歌单创建成功且歌（若有）全部加入。 */
    SUCCESS,

    /** 歌单创建成功，但加歌失败。 */
    CREATED_SONGS_FAILED,

    /** 服务端 405 限流（冷却可能超过 10 分钟）。 */
    RATE_LIMITED,

    FAILED,
}

/**
 * v1.3.0 · B2：创建歌单对话框（保存当前播放队列 / 从收藏页建空歌单）。
 *
 * 隐私只有公开(0)/私密(10)两种取值 —— 服务端对其它值返回 400 错误的歌单隐私类型，
 * 所以这里用固定两项的下拉而不是自由输入。
 *
 * 创建过程**不自动关闭**：等 [onCreate] 回来再决定关闭还是留在原地显示错误，
 * 避免"点了创建但什么都没发生"。
 */
@Composable
fun CreatePlaylistDialog(
    defaultName: String,
    songCount: Int = 0,
    onDismiss: () -> Unit,
    onCreate: suspend (name: String, privacy: Int) -> PlaylistCreateOutcome,
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(defaultName) }
    var privacy by remember { mutableIntStateOf(0) }
    var privacyMenuOpen by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val canCreate = name.isNotBlank() && !creating

    MetroDialog(onDismissRequest = { if (!creating) onDismiss() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            MetroText(
                strings.playlistCreateTitle,
                color = colors.onBackground,
                style = typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            if (songCount > 0) {
                Spacer(Modifier.height(4.dp))
                MetroText(
                    strings.trackCountSongs(songCount),
                    color = colors.onSurfaceVariant,
                    style = typography.bodySmall,
                )
            }
        }
        MetroDivider()

        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            MetroTextField(
                value = name,
                onValueChange = {
                    // 名称上限 40 字：服务端未实测上限，取一个不会惹麻烦的保守值。
                    if (it.length <= 40) {
                        name = it
                        errorText = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !creating,
                placeholder = strings.playlistNameHint,
                singleLine = true,
            )

            Spacer(Modifier.height(12.dp))

            // 隐私：只读展示 + 点击弹两项菜单（0 公开 / 10 私密）。
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !creating) { privacyMenuOpen = true }
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
                        if (privacy == 10) strings.playlistPrivacyPrivate else strings.playlistPrivacyPublic,
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
        Row(Modifier.fillMaxWidth()) {
            MetroButton(
                text = strings.cancel,
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                enabled = !creating,
                containerColor = Color.Transparent,
                contentColor = colors.onSurfaceVariant,
            )
            MetroButton(
                text = strings.playlistCreateConfirm,
                onClick = {
                    creating = true
                    errorText = null
                    scope.launch {
                        val outcome = onCreate(name.trim(), privacy)
                        creating = false
                        when (outcome) {
                            // 成功由调用方关闭对话框并弹提示（它才知道队列/歌单上下文）。
                            PlaylistCreateOutcome.SUCCESS,
                            PlaylistCreateOutcome.CREATED_SONGS_FAILED -> onDismiss()
                            PlaylistCreateOutcome.RATE_LIMITED ->
                                errorText = strings.playlistOpTooFrequent
                            PlaylistCreateOutcome.FAILED ->
                                errorText = strings.playlistCreateFailed
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = canCreate,
            )
        }
    }
}
