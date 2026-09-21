/*
 * Ncrust -- 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 本文件派生自 Kanesumi（Apache-2.0）的 kanesumi-controls/MetroLyricsPanel.kt：
 * 逐字高亮需要控制「单行内部怎么画」，而 Kanesumi 的面板只接受纯文本、无法从外部注入
 * 渲染；把面板整体搬进本仓库，是为了让 v1.5.0 的构建**只依赖 Ncrust 一个 git 仓库**
 * （Kanesumi 走组合构建、不在本仓库版本控制内，改它会让发布产物不可复现）。
 * 除单行渲染外，滚动/定位/缩放/渐入/a11y 等行为与原版逐行一致，改动处均标「v1.5.0 · B」。
 * 原 Apache-2.0 代码与本 GPLv3 项目的兼容性见 THIRD-PARTY-LICENSES.md。
 */

package com.takahashirinta.ncrust.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import io.github.takahashirinta.kanesumi.anim.sokuou.SokuouTweens
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import com.takahashirinta.ncrust.lyric.LrcWord
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.abs

/**
 * Metro 歌词面板 -- 左对齐大字,直接复刻 Ncrust 接入前的 LazyColumn 实现,
 * 叠加 Apple Music 式渐入加载。
 *
 * 静态 / 强调(复刻原版):
 *  - 左对齐 32sp 粗体;lineHeight 42sp;行间同字号。
 *  - 颜色离散:当前行 primary、过去行 White@0.6、未来行 Gray@0.4(alpha 烤进颜色,
 *    不走 graphicsLayer alpha,与原版一致)。
 *  - 缩放连续:单个 `smoothCurrentIndex: Animatable<Float>` 在跨行时用 QuickSwitch
 *    (180ms FastOutSlowIn,等同原版 tween(180, FastOutSlowInEasing))从旧行号滑到
 *    新行号;每行 graphicsLayer 按 `dist = abs(index - smoothCurrentIndex.value)` 连续
 *    算 `scale = lerp(1.0, 0.82, (dist/1.8).coerceIn(0,1))`,锚点左侧 TransformOrigin(0, 0.5)。
 *    行 i 退场与行 i+1 进场是同一时刻的连续交接,无翻转瞬间。
 *
 * 渐入加载(新增):歌词从空 -> 非空(或换歌)时,整面板 alpha 0 -> 1 用 CoverFade
 * (400ms CubicBezier(0.2,0,0,1))渐入;未完成时是空的,符合 Apple "没加载完是空的,
 * 然后渐入"。无弹簧、无过冲、无颜色动画。
 *
 * 性能:`smoothCurrentIndex.value` 与 `fadeIn.value` 只在 graphicsLayer lambda 里读,
 * 动画帧内零重组;面板只在离散当前行索引变化时重组(LazyColumn 懒渲染,离屏行不组合)。
 *
 * 滚动交互沿用原版:当前行自动滚到视口 36% 高处(animateScrollToItem,有动画);
 * 用户手动滚动暂停自动跟随 5 秒后恢复;面板显现瞬间 snap 到当前行;点某行回调
 * [onLineClick](tap-to-seek)。
 *
 * @param isVisible 首次变为 true 时瞬间跳到当前行,不等自动滚动慢慢滚过去。
 * @param currentPositionMillis draw phase / derived 阶段读取的播放位置(ms),
 *   内部必须读 snapshot state;面板据此二分定位当前行,只在跨行时重组。
 */
@Immutable
data class NcrustLyricLine(
    val timestampMillis: Long,
    val text: String,
    // 可选翻译(Spotify 式双语):非空时渲染在原句下方,小号降透明度。
    val translation: String = "",
    // v1.5.0 · B：逐字时间轴（来自 yrc）。空 = 该行没有逐字数据，按普通整行渲染。
    val words: List<LrcWord> = emptyList(),
    // v1.5.0 · B：整行结束时刻（yrc 的行时长）。null = 未知。
    val endMs: Long? = null,
)

@Composable
fun NcrustLyricsPanel(
    lines: List<NcrustLyricLine>,
    currentPositionMillis: () -> Long,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
    // 外部强制定位信号(递增即可): 面板常挂载时 isVisible 不会翻转,
    // 播放器从折叠态展开/重新唤起需要主动把滚动锚回当前行
    forcedScrollTrigger: Int = 0,
    currentLineColor: Color = LocalMetroColors.current.primary,
    pastLineColor: Color = Color.White.copy(alpha = 0.6f),
    futureLineColor: Color = Color.Gray.copy(alpha = 0.4f),
    fontSize: TextUnit = 32.sp,
    lineHeight: TextUnit = 42.sp,
    translationFontSize: TextUnit = 20.sp,
    translationLineHeight: TextUnit = 26.sp,
    inactiveScale: Float = 0.82f,
    enabled: Boolean = true,
    // v1.5.0 · B：逐字高亮开关。false（或该行没有逐字数据）时行内渲染与 v1.4.1 相同。
    karaokeEnabled: Boolean = true,
    onLineClick: (Long) -> Unit = {},
    onUserScrolled: () -> Unit = {},
) {
    val currentPosition by rememberUpdatedState(currentPositionMillis)
    val timestamps = remember(lines) { LongArray(lines.size) { lines[it].timestampMillis } }
    // 离散当前行:二分定位,只在跨行时变 -> 面板只在跨行时重组。
    val currentIndex by remember(lines) {
        derivedStateOf { currentLineIndex(currentPosition(), timestamps) }
    }
    // 连续当前行:跨行时 QuickSwitch 滑过去,驱动每行 graphicsLayer 缩放。
    val smoothCurrentIndex = remember { Animatable(currentIndex.coerceAtLeast(0).toFloat()) }
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            smoothCurrentIndex.animateTo(currentIndex.toFloat(), SokuouTweens.QuickSwitch)
        }
    }

    // 渐入加载:空 -> 非空(或换歌)时整面板 alpha 0 -> 1。由 lines 直接派生,
    // 不用手动 Animatable —— 否则一旦那个 LaunchedEffect 没跑完/没重启,
    // 面板会永远停在 alpha=0, 表现为"歌词明明有却一片空白"。
    val fadeIn by animateFloatAsState(
        targetValue = if (lines.isNotEmpty()) 1f else 0f,
        animationSpec = SokuouTweens.CoverFade,
        label = "lyricsFadeIn",
    )

    // a11y:liveRegion 播报当前行文本,map + distinctUntilChanged 压掉帧级位置流。
    val a11yText = remember { mutableStateOf("") }
    LaunchedEffect(lines) {
        snapshotFlow { currentPosition() }
            .map { currentLineIndex(it, timestamps) }
            .distinctUntilChanged()
            .collect { idx ->
                val line = if (idx >= 0 && idx < lines.size) lines[idx] else null
                val text = line?.let {
                    if (it.translation.isEmpty()) it.text else "${it.text}\n${it.translation}"
                } ?: ""
                if (text != a11yText.value) a11yText.value = text
            }
    }

    val listState = rememberLazyListState()
    var userScrolling by remember { mutableStateOf(false) }
    var programmaticScrolling by remember { mutableStateOf(false) }
    var lastAutoScrolledIndex by remember { mutableIntStateOf(-1) }

    // 换歌:回顶 + 清状态 + 连续索引复位。（渐入由上面的 fadeIn 自动派生）
    LaunchedEffect(lines) {
        userScrolling = false
        lastAutoScrolledIndex = -1
        smoothCurrentIndex.snapTo(0f)
        listState.scrollToItem(0)
    }

    // 面板显现瞬间直接跳到当前行,不等自动滚动逐行滚过去。
    LaunchedEffect(isVisible) {
        if (!isVisible || lines.isEmpty()) return@LaunchedEffect
        var vh = listState.layoutInfo.viewportSize.height
        if (vh == 0) {
            delay(16)
            vh = listState.layoutInfo.viewportSize.height
        }
        val idx = currentIndex.coerceAtLeast(0)
        val offset = if (vh > 0) -(vh * 0.36f).toInt() else 0
        smoothCurrentIndex.snapTo(idx.toFloat())
        listState.scrollToItem((idx + 1).coerceIn(1, lines.size), offset)
        lastAutoScrolledIndex = idx
    }

    // 外部强制定位(如播放器展开): 语义同 isVisible 定位, 但由调用方递增触发。
    // 顺带解除用户手动滚动暂停——展开瞬间用户预期歌词就在当前行。
    LaunchedEffect(forcedScrollTrigger) {
        if (forcedScrollTrigger == 0 || !isVisible || lines.isEmpty()) return@LaunchedEffect
        userScrolling = false
        lastAutoScrolledIndex = -1
        var vh = listState.layoutInfo.viewportSize.height
        if (vh == 0) {
            delay(16)
            vh = listState.layoutInfo.viewportSize.height
        }
        val idx = currentIndex.coerceAtLeast(0)
        val offset = if (vh > 0) -(vh * 0.36f).toInt() else 0
        smoothCurrentIndex.snapTo(idx.toFloat())
        listState.scrollToItem((idx + 1).coerceIn(1, lines.size), offset)
        lastAutoScrolledIndex = idx
    }

    // 跨行自动滚动:当前行滚到视口 36% 高处(有动画)。
    LaunchedEffect(currentIndex) {
        if (userScrolling || currentIndex < 0 || currentIndex == lastAutoScrolledIndex) return@LaunchedEffect
        lastAutoScrolledIndex = currentIndex
        val vh = listState.layoutInfo.viewportSize.height
        val offset = if (vh > 0) -(vh * 0.36f).toInt() else 0
        programmaticScrolling = true
        try {
            listState.animateScrollToItem((currentIndex + 1).coerceIn(1, lines.size), offset)
        } finally {
            programmaticScrolling = false
        }
    }

    // 用户手动滚动 -> 暂停自动跟随 5s。
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && !programmaticScrolling) {
            if (!userScrolling) {
                userScrolling = true
                onUserScrolled()
            }
        } else if (!listState.isScrollInProgress && userScrolling) {
            delay(5000)
            userScrolling = false
            lastAutoScrolledIndex = -1
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fadeIn }
            .semantics {
                text = AnnotatedString(a11yText.value)
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        LazyColumn(
            state = listState,
            userScrollEnabled = enabled,
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "top_spacer") { Spacer(Modifier.height(200.dp)) }

            itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                // 颜色离散:基于整数 currentIndex,跨行时翻转;连续 scale 会盖住这一瞬。
                val color = when {
                    currentIndex < 0 -> futureLineColor
                    index < currentIndex -> pastLineColor
                    index == currentIndex -> currentLineColor
                    else -> futureLineColor
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(enabled, line.timestampMillis) {
                            if (enabled) detectTapGestures { onLineClick(line.timestampMillis) }
                        }
                        .padding(vertical = 10.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                // 连续距离驱动缩放:无翻转瞬间,行间渐变交接。
                                // 缩放放在整行(原句+翻译)外层,双语同时放大/缩小。
                                val dist = abs(index - smoothCurrentIndex.value)
                                val scale = lerp(1f, inactiveScale, (dist / 1.8f).coerceIn(0f, 1f))
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(0f, 0.5f)
                            },
                    ) {
                        // v1.5.0 · B：有逐字数据且开关打开时走卡拉 OK 渲染，否则与 v1.4.1 完全一致。
                        LyricLineBody(
                            text = line.text,
                            words = line.words,
                            endMs = line.endMs,
                            color = color,
                            highlightColor = currentLineColor,
                            // 只有「当前行」才做逐字高亮：已唱完的行会切成 pastLineColor，
                            // 若在那些行上再叠高亮色，整行会被重新点亮成 primary，破坏过去行的弱化。
                            enabled = karaokeEnabled && index == currentIndex,
                            currentPositionMillis = currentPosition,
                            style = TextStyle(
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        if (line.translation.isNotEmpty()) {
                            // Spotify 式双语:原句下方小号、降透明度渲染翻译。
                            MetroText(
                                text = line.translation,
                                style = TextStyle(
                                    fontSize = translationFontSize,
                                    lineHeight = translationLineHeight,
                                    fontWeight = FontWeight.Normal,
                                ),
                                softWrap = true,
                                color = color.copy(alpha = color.alpha * 0.6f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp),
                            )
                        }
                    }
                }
            }

            item(key = "bottom_spacer") { Spacer(Modifier.height(200.dp)) }
        }
    }
}


/**
 * v1.5.0 · B —— 单行歌词渲染。
 *
 * 有逐字数据时画两层：底色整行画一遍（弱化），再在 `drawWithContent` 里把「已唱到」
 * 的那段字符用高亮色重画一遍。
 *
 * 三个关键选择：
 *  1. **零重组**：播放位置只在 draw 阶段读 —— 逐字推进只让这一行重绘，不触发任何重组，
 *     与面板既有的 graphicsLayer 缩放动画、以及播放器整体的 GPU 零重组原则一致；
 *  2. **不自己排版**：直接复用 BasicText 量出来的 [TextLayoutResult]，换行 / 断字 / CJK
 *     避头尾全部交给 Compose，逐字高亮天然跟着版面走；
 *  3. **一次画完**：`getPathForRange` 会把跨行的一段字符合成一条 Path，所以无论唱到第几个词，
 *     都只有一次 clipPath + 一次 drawText，开销不随词数增长。
 *
 * 判定是「词的起始时间 <= 当前播放位置」→ 逐词整块点亮（不做扫描线渐变，与 Kanesumi 的
 * 离散取色风格一致）。没有逐字数据时退回与 v1.4.1 完全相同的整行 MetroText。
 */
@Composable
private fun LyricLineBody(
    text: String,
    words: List<LrcWord>,
    endMs: Long?,
    color: Color,
    highlightColor: Color,
    enabled: Boolean,
    currentPositionMillis: () -> Long,
    style: TextStyle,
) {
    if (!enabled || words.isEmpty()) {
        MetroText(
            text = text,
            style = style,
            softWrap = true,
            color = color,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    // 未唱部分弱化到 45%：当前行原来的整行 primary 变成「已唱的实心 + 未唱的淡影」，
    // 与 Kanesumi 的离散取色一致；唱完的部分才回到满色 primary。
    val unsungColor = color.copy(alpha = color.alpha * 0.45f)
    // 注意：BasicText 的 color 参数是 ColorProducer 而不是 Color，颜色走 style 传，
    // 免得为一个参数多 import 一个函数式接口。
    BasicText(
        text = text,
        style = style.copy(color = unsungColor),
        softWrap = true,
        modifier = Modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                val lr = layout ?: return@drawWithContent
                val pos = currentPositionMillis()
                // 越过 yrc 的行结束时刻就整行满色 —— 兜住「词的字符区间没铺满整行」的情况
                // （LRC 的美化文本可能比词拼接多出结尾标点）。
                val sungEnd = if (endMs != null && pos >= endMs) {
                    text.length
                } else {
                    sungCharEnd(words, pos, text.length)
                }
                if (sungEnd <= 0) return@drawWithContent
                val path = runCatching { lr.getPathForRange(0, sungEnd) }.getOrNull()
                    ?: return@drawWithContent
                clipPath(path) {
                    drawText(lr, color = highlightColor, topLeft = Offset.Zero)
                }
            },
        onTextLayout = { layout = it },
    )
}

/** 已唱到的字符末尾下标（不含）：取所有 startMs <= positionMillis 的词的 charEndExclusive 最大值。 */
private fun sungCharEnd(words: List<LrcWord>, positionMillis: Long, textLength: Int): Int {
    var end = 0
    for (w in words) {
        if (w.startMs > positionMillis) continue
        if (w.charEndExclusive > end) end = w.charEndExclusive
    }
    return end.coerceIn(0, textLength)
}

// 播放位置(ms) -> 当前行索引。-1 表示还没到第一行(全部未来行)。
private fun currentLineIndex(positionMillis: Long, timestamps: LongArray): Int {
    if (timestamps.isEmpty()) return -1
    if (positionMillis < timestamps[0]) return -1
    var lo = 0
    var hi = timestamps.size - 1
    if (positionMillis >= timestamps[hi]) return hi
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (timestamps[mid] <= positionMillis) lo = mid else hi = mid - 1
    }
    return lo
}
