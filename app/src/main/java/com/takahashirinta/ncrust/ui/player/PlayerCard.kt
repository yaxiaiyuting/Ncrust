/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明（Bug1「音质切换」）：
 *   - 向 FullPlayerControls 传入 qualityStatus（偏好档位 vs 实际文件质量不一致）。
 */

package com.takahashirinta.ncrust.ui.player

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.Coil
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.QueueModes
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import androidx.compose.foundation.systemGestureExclusion
import com.takahashirinta.ncrust.lyric.LyricsWordAnimationMode
import com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel
import io.github.takahashirinta.kanesumi.anim.sokuou.SokuouTweens
import io.github.takahashirinta.kanesumi.controls.MetroDivider
import io.github.takahashirinta.kanesumi.controls.MetroIconButton
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import android.widget.Toast

@Composable
fun PlayerCard(
    song: SongItem?,
    isPlaying: Boolean,
    screenHeightPx: Float,
    progress: Animatable<Float, AnimationVector1D>,
    totalDragDistancePx: Float = 0f,
    playbackQueue: List<SongItem> = emptyList(),
    currentQueueIndex: Int = -1,
    playMode: Int = 0,
    onPlayPause: () -> Unit,
    onDismiss: () -> Unit,
    onPlayPrevious: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    onRemoveFromQueue: (Int) -> Unit = {},
    onPlayFromQueue: (Int) -> Unit = {},
    onMoveInQueue: (Int, Int) -> Unit = { _, _ -> },
    onTogglePlayMode: () -> Unit = {},
    onPlayNothing: () -> Unit = {},
    onSongInfoClick: () -> Unit = {},
    onClearQueue: () -> Unit = {},
    onSavePlaylist: () -> Unit = {},
    onNavigateToUser: () -> Unit = {}
) {
    val hasSong = song != null
    // 初始落大封面: 歌词未就绪时(加载中/确无), 全屏默认看封面而非空歌词面板;
    // lyricsReady 到位后由下方的 LaunchedEffect 自动切回歌词视图。
    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // v1.5.1 · B：手势导航机型给控制栏把手留出的上抬量（见把手处的注释）。
    // 只在 navigation_mode == 2（Android 10+ 手势导航）时非零；三键导航与 API < 29 为 0。
    val gestureNavLiftDp = remember(context) {
        if (isGestureNavigation(context)) GESTURE_NAV_HANDLE_LIFT_DP else 0.dp
    }

    val strings = LocalStrings.current
    val playerViewModel: PlayerViewModel = viewModel()
    val lyrics by playerViewModel.lyrics.collectAsState()
    val translatedLyrics by playerViewModel.translatedLyrics.collectAsState()
    val lyricsLoading by playerViewModel.lyricsLoading.collectAsState()
    val lyricsSongId by playerViewModel.lyricsSongId.collectAsState()
    val lyricsNoContentSongId by playerViewModel.lyricsNoContentSongId.collectAsState()
    val showLyricsTranslation by playerViewModel.showLyricsTranslation.collectAsState()
    // v1.5.1 · A：逐字动画模式（0 渐变扫过 / 1 逐字硬切 / 2 关闭逐字）。
    // 模式 2 时 karaokeEnabled=false，行内渲染退回 v1.4.1 的整行路径。
    val lyricsWordAnimation by playerViewModel.lyricsWordAnimation.collectAsState()
    // v1.5.1 · E：歌词字号倍率（设置页与歌词界面的 A-/A+ 都改它）。
    val lyricsFontScale by playerViewModel.lyricsFontScale.collectAsState()
    // 收藏库状态: 当前歌是否已收藏(右下角 加号/对号 切换用)。切歌或操作后刷新。
    var libraryTick by remember { mutableIntStateOf(0) }
    val isSongSaved = remember(song?.id, libraryTick) {
        song?.let { LibraryManager.isSongSaved(context, it.id) } ?: false
    }
    // currentPosition / progress 是 4Hz 更新的 StateFlow，直接传引用给需要的子组件，
    // 让它们在最小作用域（graphicsLayer / Canvas draw / derivedStateOf / 叶子 Text）内订阅，
    // 避免 PlayerCard 本身随位置更新 4Hz 重组
    // duration / qualityIndex / isBuffering downgraded to leaf collect inside FullPlayerControls;
    // subscribing at this scope would force the whole PlayerCard subtree to recompose on song
    // change / buffer flap, dragging in AsyncImage + Column layout for no reason.

    // 宽屏分栏进度：0 = 单栏（封面居中、控件铺满居中），1 = 两栏（左封面+控件 / 右歌词·队列）。
    // 由是否显示歌词/队列驱动；窄屏不读取该值（不触发额外重组）。
    val wideSplit by animateFloatAsState(
        targetValue = if (showLyrics || showQueue) 1f else 0f,
        animationSpec = tween(280, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
        label = "widePlayerSplit"
    )

    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val screenWidthPx = with(density) { screenWidthDp.toPx() }
    val dp24px = with(density) { 24.dp.toPx() }
    // 宽屏播放器两栏（Apple Music 式）：左封面 / 右歌词·队列。
    val isWidePlayer = LocalConfiguration.current.screenWidthDp >= 600
    // 宽屏左栏占整宽的比例：随 wideSplit 在 100%(单栏) 与 44%(两栏) 间过渡。窄屏恒为 1。
    val wideLeftFraction = if (isWidePlayer) 1f - 0.56f * wideSplit else 1f
    // 迷你条与顶栏按钮的触觉反馈
    val haptic = LocalHapticFeedback.current

    // 唯一封面 overlay：全屏 ↔ miniBar 始终是同一个 cover，只平滑移动/缩放，绝不消失。
    // 宽屏大图落点由左栏"封面区"实测得到（区域化，分辨率无关）。
    var cardRootOrigin by remember { mutableStateOf(Offset.Zero) }
    var wideCoverCenter by remember { mutableStateOf(Offset.Zero) }
    var wideCoverSizePx by remember { mutableStateOf(0f) }
    val coverSizePx = if (isWidePlayer) {
        // 实测前用兜底尺寸，避免首帧 1px 让 Coil 按 1px 解码成纯色（重进时尤为明显）。
        if (wideCoverSizePx > 0f) wideCoverSizePx
        else minOf(screenWidthPx * 0.4f, screenHeightPx * 0.5f)
    } else screenWidthPx
    val coverSizeDp = with(density) { coverSizePx.toDp() }
    val miniCoverHalfPx = with(density) { 28.dp.toPx() }
    val miniScale = miniCoverHalfPx * 2f / coverSizePx
    val statusBarPx = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        .let { with(density) { it.toPx() } }
    val miniCoverCenterX = miniCoverHalfPx
    val miniCoverCenterY = statusBarPx + miniCoverHalfPx
    val largeCoverCenterX = if (isWidePlayer) wideCoverCenter.x else screenWidthPx / 2f
    val largeCoverCenterY = if (isWidePlayer) wideCoverCenter.y else screenHeightPx * 0.3f + dp24px
    val boundsCenter = coverSizePx / 2f

    // 完全收起时才激活迷你播放栏；derivedStateOf 将重组限制在阈值穿越处
    val miniBarEnabled by remember { derivedStateOf { progress.value < 0.01f } }
    val miniBarInteractionSource = remember { MutableInteractionSource() }
    // 完全展开时才激活收起按钮
    val dismissEnabled by remember { derivedStateOf { progress.value > 0.99f } }
    // ---- v1.3.0 · B-1：折叠态只挂载 mini bar，其余展开态子树一律条件挂载 ----
    // 展开态子树 = 顶部标题栏 / 歌词·队列双面板 / 底部播放控件 / 大封面模式下的曲名信息。
    // 历史：它们一度常挂载（无 gate），只切 graphicsLayer alpha，不再走 mount/dispose ——
    // 那是"Apple Music 手感"的关键（原生 View 系统里 player subview 是 app 启动就构建好、
    // 隐藏用 visibility=GONE 的），也把首次展开的构造成本从"跨阈值那一帧"挪走了。
    // 但常挂载的代价是**命中区**：Compose 的命中测试与 alpha 无关，折叠态这些 alpha≈0 的
    // 子树仍留在卡片里（卡片 = fillMaxSize + translationY(collapsedOffsetY)，整块压在屏幕
    // 下半部），详情页底部落在这一带的交互元素因此完全收不到事件（死带）。
    // 现在的取舍：折叠态不挂载 → 死带消失。代价是展开时首次挂载展开态子树要做一次
    // composition + layout + draw（冷启动/切歌后第一次展开最明显，之后有 Compose 的
    // slot table 复用，成本低于首次），压不进单帧时会看到一次轻微掉帧；冷启动那一次由
    // Splash + AppWarmup 兜底吸收。阈值 0.01 与 miniBarEnabled 同一处：拖动或点按动画
    // 一开始（约第 1 帧）就挂载，给后面 400ms 动画留出吸收时间。
    // mini bar 本身**永远挂载**（否则无法点击/上拉展开），是折叠态唯一保留的子树。
    val expandedMounted by remember { derivedStateOf { progress.value > 0.01f } }
    // 折叠态命中区让位开关（见下方根 Box 的 padding/offset 注释）。
    // 同样用 derivedStateOf 离散化：只在跨阈值那一帧重组一次，动画帧零成本。
    val hitGateInsetDp = with(density) { statusBarPx.toDp() }
    val collapsedHitGate by remember { derivedStateOf { progress.value <= 0.01f } }

    // lyricAnimProgress：0 = 大封面，1 = 小封面；驱动封面缩放 + 内容淡入淡出
    // queueSlideProgress：0 = 歌词位置，1 = 列表位置；仅 b↔c 时动画，其他时 snap
    // 两个 Animatable 均只在 graphicsLayer { } draw 阶段读取，动画帧内零 recompose
    // 初始 0（大封面）：冷启动/splash 后若当前歌无歌词，首帧即大封面，不会"停"在歌词位
    val lyricAnimProgress = remember { Animatable(0f) }
    val queueSlideProgress = remember { Animatable(0f) }
    // 仅在歌词模式下歌词可交互；阈值穿越处各触发一次重组，其余帧零重组
    val lyricsEnabled by remember { derivedStateOf { lyricAnimProgress.value > 0.5f && queueSlideProgress.value < 0.5f } }
    // 卡片基本展开(>90%)时播放器内容区才可交互。面板常挂载、graphicsLayer 只调 alpha,
    // 折叠态下歌词行/队列行在屏幕底部(迷你条与导航栏之间的缝隙)依然命中测试——
    // 用户"在导航栏底部乱按"会点到不可见的歌词行/队列行, 触发 seek/切歌。
    // 展开阈值和 alpha 淡入阈值(0.7)错开, 保证交互只在内容真正可见后开启。
    val cardExpandedForInput by remember { derivedStateOf { progress.value > 0.9f } }

    // ---- v1.4.0 · A：底部控制栏可收起（仅窄屏全屏态）----
    // controlsCollapse: 0 = 展开（每次进入全屏的默认值），1 = 完全收起。
    // 收起后控制栏整体滑出屏幕，面板（歌词/队列）顺势长高到全屏；右下角留一个极简播放键。
    // 手势：控制栏区域向上拖 = 收起；收起后右下角悬浮键向下拖 = 恢复（点 = 播放/暂停）。
    // 零重组：滑动走 graphicsLayer 平移，高度收缩在 layout 阶段读 Animatable（不触发 recompose）。
    // 只在窄屏生效：宽屏是左右两栏，右栏本来就是整轴高度，没有可让出的空间。
    val controlsCollapse = remember { Animatable(0f) }
    var controlsHeightPx by remember { mutableFloatStateOf(0f) }
    // 控制栏在**卡片根 Box 局部坐标**里的上沿，用于让根节点的整卡拖拽给"收起控制栏"让路。
    var controlsTopInCardPx by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val controlsCollapsedForInput by remember { derivedStateOf { controlsCollapse.value > 0.5f } }

    // 收起状态不持久化：离开全屏（卡片回到折叠态）即复位为展开。
    LaunchedEffect(Unit) {
        snapshotFlow { progress.value <= 0.01f }
            .distinctUntilChanged()
            .collect { collapsed -> if (collapsed) controlsCollapse.snapTo(0f) }
    }

    // 收起/恢复共用的竖直拖拽检测器：拖动期间 snapTo 跟手，松手做方向敏感吸附。
    // key 带 isWidePlayer：宽屏不启用（见上）。
    // 注意：必须每次调用都**新建** Modifier 实例。SuspendPointerInputElement 内部持有
    // previousKeys 这类可变状态，同一个实例挂到两个节点上会互相踩，表现为其中一个节点
    // 的 pointer 处理器起不来（实测：控制栏能收起、悬浮键的恢复手势收不到事件）。
    fun controlsCollapseDrag(): Modifier = Modifier.pointerInput(hasSong, isWidePlayer) {
        if (!hasSong || isWidePlayer) return@pointerInput
        // current 必须自己累加：onVerticalDrag 的 dragAmount 是**每帧增量**，
        // 写成 startValue - dragAmount/h 只会得到最后一帧的位移，拖动基本不动。
        var current = 0f
        var from = 0f
        detectVerticalDragGestures(
            onDragStart = {
                from = controlsCollapse.value
                current = from
            },
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                val h = if (controlsHeightPx > 1f) controlsHeightPx
                        else with(density) { 200.dp.toPx() }
                current = (current - dragAmount / h).coerceIn(0f, 1f)
                coroutineScope.launch { controlsCollapse.snapTo(current) }
            },
            onDragEnd = {
                coroutineScope.launch {
                    // 方向敏感吸附：向上推（收起）要 25% 行程；向下拉（恢复）只要 5%。
                    // 恢复方向阈值刻意很小：收起会挡住内容、需要"故意"，而恢复只是把控制栏
                    // 放回来、没有任何副作用，阈值大了反而会让"划不回来"（S6 真机实测：
                    // 把手向下可拖的总行程本来就短，控制栏越高越够不到比例阈值）。
                    // 微动（两个方向都没到阈值）按出发点归位。
                    val delta = current - from
                    val target = when {
                        delta <= -0.05f -> 0f
                        delta >= 0.25f -> 1f
                        else -> if (from >= 0.5f) 1f else 0f
                    }
                    controlsCollapse.animateTo(target, tween(260, easing = FastOutSlowInEasing))
                }
            },
            onDragCancel = {
                coroutineScope.launch {
                    controlsCollapse.animateTo(0f, tween(200, easing = FastOutSlowInEasing))
                }
            }
        )
    }

    // 控制栏收起的显式切换（把手上点一下即可），与拖拽共用同一条动画轴。
    fun toggleControlsCollapse() {
        coroutineScope.launch {
            val target = if (controlsCollapse.value > 0.5f) 0f else 1f
            controlsCollapse.animateTo(target, tween(260, easing = FastOutSlowInEasing))
        }
    }

    // ---- 歌词可达性驱动的「大封面 ↔ 歌词视图」自动切换 ----
    // - 切歌瞬间: 旧歌词已被 ViewModel 清空, 直接落大封面, 绝不残留上一首歌词。
    // - 歌词就绪(lyricsReady): 自动切回歌词视图（Apple Music 语义）。
    // - 歌词未就绪(仍在加载/确无): 保持大封面 + 灰按钮。
    // - 用户在队列视图时不打扰。
    // lyricsReady：歌词**属于当前歌**且非加载中。旧歌词残留(切歌过渡)不算就绪——
    // 由 lyricsSongId == song?.id 保证, 否则无歌词的新歌会被误判就绪显示旧歌词。
    val lyricsReady = lyricsSongId == song?.id && !lyricsLoading && lyrics.isNotEmpty()
    // 只有服务端明确答复"确无歌词"时才置灰歌词按钮；加载中/请求失败都保持可点，
    // 失败时点一下即触发重试，而不是一次网络抖动就把按钮永久禁用（issue: 后台被杀重进后按钮灰掉）。
    val lyricsNoContent = lyricsNoContentSongId == song?.id && lyrics.isEmpty()
    val lyricsUnavailable = lyricsNoContent && !lyricsLoading

    // 每首歌只自动切到歌词视图一次；用户手动关掉歌词后，不再被自动打开
    // （否则关歌词时 showLyrics 被 effect 立刻改回 true，wideSplit 回不到 0，
    //  表现为"封面/控件都不动"）。
    var autoSwitchedForSong by remember(song?.id) { mutableStateOf(false) }

    LaunchedEffect(song?.id, lyricsReady, lyricsLoading, showQueue) {
        when {
            showQueue -> {
                // 用户在队列：不打扰, 放弃自动回切
            }
            // 歌词就绪 → 首次自动切到歌词视图（封面缩小/左移）
            lyricsReady -> {
                if (!autoSwitchedForSong) {
                    autoSwitchedForSong = true
                    if (!showLyrics) showLyrics = true
                }
            }
            // 加载中：保持当前视图，不清空也不切回大封面 —— 切歌时封面完全不动。
            lyricsLoading -> {
            }
            // 确无歌词（加载完成且为空）→ 回大封面
            else -> if (showLyrics) showLyrics = false
        }
    }

    LaunchedEffect(showLyrics, showQueue) {
        when {
            showLyrics -> {
                // 与封面缩小并行
                launch { lyricAnimProgress.animateTo(1f, tween(190, easing = FastOutSlowInEasing)) }
                // 若当前 queueSlideProgress > 0（来自列表模式），横滑回歌词位置
                if (queueSlideProgress.value > 0.01f) {
                    queueSlideProgress.animateTo(
                        0f, tween(260, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
                    )
                }
            }
            showQueue -> {
                launch { lyricAnimProgress.animateTo(1f, tween(190, easing = FastOutSlowInEasing)) }
                when {
                    // 已在列表位置（或正在返回），无需再动
                    queueSlideProgress.value > 0.99f -> {}
                    // 来自稳定歌词模式（lyricAnimProgress 已是 1）→ 横滑
                    lyricAnimProgress.value > 0.95f -> {
                        queueSlideProgress.animateTo(
                            1f, tween(260, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
                        )
                    }
                    // 来自大封面模式 → 不横滑，仅随封面缩小淡入
                    else -> queueSlideProgress.snapTo(1f)
                }
            }
            else -> {
                // 切回大封面：等封面展开完成，内容随 lyricAnimProgress 自然淡出
                lyricAnimProgress.animateTo(0f, tween(300, easing = LinearOutSlowInEasing))
                // 封面展开后重置滑动位置，为下次 b→c 准备
                queueSlideProgress.snapTo(0f)
            }
        }
    }

    // 展开动作触发一次歌词定位: 面板常挂载, isVisible 不会翻转, 若不做强制定位,
    // 从 mini bar 拉起后歌词停在旧位置(或用户上次手动滚动的位置)
    var lyricLocateTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        snapshotFlow { progress.value }
            .distinctUntilChanged { a, b -> (a > 0.9f) == (b > 0.9f) }
            .collect { p ->
                if (p > 0.9f) lyricLocateTrigger++
            }
    }

    // 歌词/队列面板可交互(展开 + 面板在前台)时,面板区域内的纵向手势归内部列表滚动。
    // 根节点的整卡拖拽与兜底消费器都不得抢手势——否则在面板上一滑,整卡被拖走、
    // 列表几乎滚不动(issue #23)。面板外的封面/顶栏/大封面模式仍驱动整卡。
    val topBarBottomPx = statusBarPx + with(density) { 56.dp.toPx() }
    val isPanelInteractive by remember {
        derivedStateOf {
            (lyricsEnabled || queueSlideProgress.value > 0.5f) && progress.value > 0.7f
        }
    }
    // 注意区分两个不同职责的判断，不要把语义混在一起：
    //
    // ① isOverPanel —— **面板语义**：点是否落在歌词/列表这类可滚动面板上。
    //    只给下面的拖拽检测器用：落在面板内时根节点必须让路，
    //    否则内部 LazyColumn 滚不动（历史 issue #23）。它依赖 isPanelInteractive 是正确的。
    fun isOverPanel(y: Float, x: Float) = isPanelInteractive && y > topBarBottomPx &&
        (!isWidePlayer || x > screenWidthPx / 2f)

    // ② isOverCardVisibleArea —— **几何语义**：点是否落在卡片自己的可见矩形内。
    //    只给下面「展开态吞事件」的消费者用。
    //
    //    旧实现这里用的是 isOverPanel，于是豁免区被绑在 isPanelInteractive 上、
    //    进而绑在 lyricsEnabled 上：**关闭歌词后豁免区整个消失**，根节点在展开态
    //    把事件全部吞掉，底部播放控制栏与底部导航栏一起失效（歌词开着反而正常）。
    //    消费者要挡的只是**下层兄弟**，与自己内部显示哪个面板无关，
    //    所以这里必须只依赖几何。
    // ③ isOverCollapsibleControls —— **控制栏语义**：点是否落在"可收起的底部控制栏"上。
    //    只给根节点的整卡拖拽让路用：窄屏全屏态下，这一区域内向上拖是"收起控制栏"，
    //    而不是把整张卡片拖走。controlsTopInCardPx 由控制栏 onGloballyPositioned 实测，
    //    分辨率无关；未实测到（MAX_VALUE）时判断恒为 false，即退化成旧行为。
    // 注意这里**不**按"控制栏当前是展开还是收起"分段：控制栏收起后，它让出的那块区域
    // 属于面板（歌词的点击/滚动），而把手仍然在最底部；两种状态下这块都应该归它们，
    // 而不是让整卡拖拽来抢（S6 真机实测：收起后向下拖把手恢复，会被整卡拖拽抢走，
    // 结果是"控制栏没回来、整卡反而被拖下去"）。
    fun isOverCollapsibleControls(y: Float) =
        !isWidePlayer && cardExpandedForInput && y >= controlsTopInCardPx

    fun isOverCardVisibleArea(y: Float, x: Float): Boolean {
        // 宽屏左右分栏，左半是封面区，触摸落在左半时不属于卡片内容区。
        if (isWidePlayer && x <= screenWidthPx / 2f) return false
        // 卡片可见上沿：收起时整体下移到 collapsedOffsetY，展开时回到 0。
        // 用 progress 插值而不是直接读 cardRootOrigin.y —— graphicsLayer 的平移
        // 不会重新触发布局，onGloballyPositioned 写入的坐标在动画期间会滞后。
        val cardTopPx = cardRootOrigin.y * (1f - progress.value)
        return y >= cardTopPx
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { cardRootOrigin = it.boundsInRoot().topLeft }
            // ---- 死带修复（B-1 的必要补充）：折叠态把**命中区**上沿让出 statusBar 高 ----
            // 卡片是 fillMaxSize + translationY(collapsedOffsetY)，下面两个 pointerInput（上拉
            // 手势、展开态吞事件）挂在根 Box 上，命中区 = 整个根 Box；而 mini bar 的内容被
            // .statusBarsPadding() 下推了 statusBar 高、卡背背景也被 graphicsLayer 下推了同样
            // 高度。于是 collapsedOffsetY .. collapsedOffsetY+statusBar 这一段（S6 实测
            // 1920..2016，24dp）视觉上"卡片透明、下层详情页透出来"，事件却被卡片自己吃掉
            // ——详情页 y≈1938 的原 ⋮ 点不动就是这个，只凭不挂载 Column 子树修不掉（那一段
            // 里根本没有子节点，吃事件的正是这两个 pointerInput 自己）。
            // 做法：折叠态给根 Box 加一个 statusBar 高的 top padding（只收窄两个 pointerInput
            // 的命中区），再用等量 offset 把子节点放回原位 —— 视觉与子节点布局零变化。
            // 展开态与动画中段不加 padding：整屏吞事件的行为原样保留。
            .then(if (collapsedHitGate) Modifier.padding(top = hitGateInsetDp) else Modifier)
            // Outer modifier → runs last within this node in Main pass (after drag detector below).
            // Consumes remaining events when fully expanded so Scaffold siblings never receive them.
            // 面板区域不吞事件:内部列表需要先拿到未消费的 MOVE 才能滚动。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (progress.value > 0.99f) {
                            val pos = event.changes.firstOrNull()?.position
                            if (pos == null || !isOverCardVisibleArea(pos.y, pos.x)) {
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            }
            // Inner modifier → runs first within this node in Main pass.
            // Handles drag-to-expand/collapse; runs before the outer consumer so it sees unconsumed MOVE.
            // 仅在有歌（!hasSong = 暂无播放）时可拖拽；用 hasSong 作 key，来了歌后手势重新激活。
            .pointerInput(hasSong) {
                if (!hasSong) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 面板内纵向手势完全交给内部 LazyColumn/进度条,根节点不消费任何事件。
                    if (isOverPanel(down.position.y, down.position.x)) return@awaitEachGesture
                    // 底部控制栏区域同理让路：向上拖 = 收起控制栏（v1.4.0 · A）。
                    if (isOverCollapsibleControls(down.position.y)) return@awaitEachGesture
                    val startProgress = progress.value
                    val pointer = down.id
                    val slop = viewConfiguration.touchSlop
                    // 自定义竖直 slop 检测：**忽略消费标志**累积位移。标准
                    // awaitVerticalTouchSlopOrCancellation 一旦看到事件被消费就返回 null——
                    // miniBar 的 clickable 会消费 down/事件, 导致拖拽永远起不来
                    // ("滑动拉起"失效)。这里越界后再 consume, 之后正常 drag。
                    var acc = 0f
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointer } ?: break
                        if (!change.pressed) break
                        acc += change.position.y - change.previousPosition.y
                        if (abs(acc) > slop) {
                            dragging = true
                            change.consume()
                            break
                        }
                    }
                    if (!dragging) return@awaitEachGesture
                    // 手动拖动循环：同样忽略消费标志读位移, 边拖边 consume
                    // （压制 miniBar clickable 的按压, 让它不会在抬手时误触发展开）。
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointer } ?: break
                        if (!change.pressed) break
                        change.consume()
                        val dragAmount = change.position.y - change.previousPosition.y
                        if (dragAmount != 0f) {
                            coroutineScope.launch {
                                progress.snapTo(
                                    (progress.value - dragAmount / totalDragDistancePx).coerceIn(0f, 1f)
                                )
                            }
                        }
                    }
                    coroutineScope.launch {
                        val target = if (startProgress < 0.5f) {
                            if (progress.value >= 0.5f) 1f else 0f
                        } else {
                            if (progress.value >= 0.75f) 1f else 0f
                        }
                        progress.animateTo(
                            target,
                            if (target == 1f)
                                tween(durationMillis = 400, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
                            else
                                tween(durationMillis = 260, easing = FastOutSlowInEasing)
                        )
                    }
                }
            }
            // 把上面 padding 让出的高度补回去：子节点仍从根 Box 顶开始（视觉/布局不变），
            // 只有两个 pointerInput 留在下移后的命中区里。
            .then(if (collapsedHitGate) Modifier.offset(y = -hitGateInsetDp) else Modifier)
    ) {
        // 全屏纯黑背景。展开时 translationY=0，卡片顶与封面顶/内容区顶等高；
        // 折叠时整体下移一个 statusBar 高，使顶边对齐 miniBar 内容。miniBar 自带
        // statusBarsPadding（内容被下推 statusBar 高），背景若不跟着下移，miniBar
        // 上方就会多出一条 statusBar 高的 surface 色块——即"手机 miniBar 变高一片"。
        // statusBarPx 在车机(WindowInsets=0)为 0，天然不影响车机。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = statusBarPx * (1f - progress.value)
                }
                .background(LocalMetroColors.current.background)
        )
        // 折叠态卡背：卡片整体下移后，miniBar 下方露出的是这张黑底（原底部导航/系统栏
        // 位置）。折叠时用 surface 盖住，与 miniBar 同色，避免底部黑块；展开时透明。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = (1f - progress.value * 5f).coerceIn(0f, 1f)
                    translationY = statusBarPx * (1f - progress.value)
                }
                .background(LocalMetroColors.current.surface)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            // B-1：折叠态不挂载展开态子树（mini bar 在下面、永远挂载）。见 expandedMounted 注释。
            if (hasSong && expandedMounted) {
                val s = song!!

                // 底部播放控件（窄屏整宽 / 宽屏左栏共用）。常挂载，alpha 只在 draw 阶段调。
                val playerControls: @Composable () -> Unit = {
                    FullPlayerControls(
                        isPlaying = isPlaying,
                        showLyrics = showLyrics,
                        showQueue = showQueue,
                        progressFlow = playerViewModel.progress,
                        positionFlow = playerViewModel.currentPosition,
                        durationFlow = playerViewModel.duration,
                        qualityIndexFlow = playerViewModel.currentQualityIndex,
                        qualityStatusFlow = playerViewModel.qualityStatus,
                        qualityOptions = strings.qualityOptions,
                        onPlayPause = onPlayPause,
                        onPlayPrevious = onPlayPrevious,
                        onPlayNext = onPlayNext,
                        onToggleLyrics = {
                            val nowReady = lyricsReady
                            showLyrics = !showLyrics
                            showQueue = false
                            // 未就绪(失败/尚未成功)时点击 = 触发一次重新加载，
                            // 不必切歌才能恢复歌词。
                            if (!nowReady && !lyricsLoading) playerViewModel.retryLyrics()
                        },
                        onToggleQueue = {
                            showQueue = !showQueue
                            showLyrics = false
                        },
                        onAddToLibrary = {
                            val cur = song
                            if (cur != null) {
                                // 已在库 → 移出; 不在库 → 收藏。本地即时生效, 云端异步同步。
                                if (LibraryManager.isSongSaved(context, cur.id)) {
                                    LibraryManager.removeSong(context, cur.id)
                                    Toast.makeText(context, strings.removedFromLibrary, Toast.LENGTH_SHORT).show()
                                } else {
                                    LibraryManager.saveSong(context, cur)
                                    Toast.makeText(context, strings.addedToLibrary, Toast.LENGTH_SHORT).show()
                                }
                                libraryTick++
                            }
                        },
                        isInLibrary = isSongSaved,
                        isBufferingFlow = playerViewModel.isBuffering,
                        onSeek = { fraction ->
                            val dur = playerViewModel.duration.value
                            if (dur > 0) {
                                playerViewModel.seekTo((fraction * dur).toLong())
                            }
                        },
                        onNavigateToUser = onNavigateToUser,
                        lyricsUnavailable = lyricsUnavailable,
                        previousEnabled = playMode != QueueModes.INFINITY,
                        landscape = isWidePlayer
                    )
                }

                // 歌词 / 队列双面板（窄屏整宽 / 宽屏右栏共用）。
                val playerPanels: @Composable (Modifier) -> Unit = { panelModifier ->
                    Box(modifier = panelModifier) {
                        // 歌词面板：translationX 从 0 滑至 -screenWidthPx，确保非歌词模式下完全移出屏幕，
                        // 彻底消除与列表面板的命中测试重叠（combinedClickable 忽略 isConsumed 标志）
                        // alpha 用阶梯而非交叉淡化: 切换时源面板瞬时隐藏、目标面板单层全宽滑入,
                        // 每帧只合成一个面板——原先 260ms 内两个全屏面板同时 alpha 混合是
                        // 低端机上左右切换动作的主要 GPU 成本。
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val q = queueSlideProgress.value
                                    alpha = lyricAnimProgress.value * if (q < 0.01f) 1f else 0f
                                    translationX = -q * screenWidthPx
                                }
                        ) {
                            // 切歌时旧歌词渐隐、新歌词渐显（Sokuou UWP 缓动）。
                            // key = song?.id：同一首歌的歌词更新不触发 Crossfade, 只换内容。
                            Crossfade(
                                targetState = song?.id,
                                animationSpec = SokuouTweens.CoverFade,
                                label = "LyricsCrossfade"
                            ) { _ ->
                                LyricsView(
                                    lyrics = lyrics,
                                    translatedLyrics = translatedLyrics,
                                    showTranslation = showLyricsTranslation,
                                    positionFlow = playerViewModel.currentPosition,
                                    isPlaying = isPlaying,
                                    isVisible = showLyrics,
                                    forcedLocateTrigger = lyricLocateTrigger,
                                    onSeekToMs = { ms -> playerViewModel.seekTo(ms) },
                                    enabled = lyricsEnabled && cardExpandedForInput,
                                    onUserScrolled = {},
                                    isLoading = lyricsLoading,
                                    wordByWordEnabled = lyricsWordAnimation != LyricsWordAnimationMode.OFF,
                                    wordAnimationMode = lyricsWordAnimation,
                                    fontScale = lyricsFontScale,
                                    onFontScaleStep = { delta -> playerViewModel.stepLyricsFontScale(delta) },
                                )
                            }
                        }

                        // 列表面板：translationX 从 +screenWidthPx 滑至 0，稳定态时完全在屏幕外
                        // alpha 阶梯同上: q < 0.01 时完全透明, 切换只合成单个面板
                        // 面板标题行高度: 队列自动定位要按"整个队列区域"(含标题行)居中
                        var queueHeaderHeightPx by remember { mutableFloatStateOf(0f) }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val q = queueSlideProgress.value
                                    alpha = lyricAnimProgress.value * if (q > 0.01f) 1f else 0f
                                    translationX = (1f - q) * screenWidthPx
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .onGloballyPositioned {
                                        queueHeaderHeightPx = it.size.height.toFloat()
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MetroText(
                                    strings.queueTitle,
                                    color = LocalMetroColors.current.onBackground,
                                    style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                                    modifier = Modifier.weight(1f)
                                )
                                MetroIconButton(onClick = onTogglePlayMode) {
                                    MetroIcon(
                                        imageVector = when (playMode) {
                                            QueueModes.SINGLE -> Icons.Default.RepeatOne
                                            QueueModes.SHUFFLE -> Icons.Default.Shuffle
                                            QueueModes.LINE -> Icons.Default.PlaylistPlay
                                            QueueModes.INFINITY -> Icons.Default.AllInclusive
                                            else -> Icons.Default.Repeat
                                        },
                                        contentDescription = strings.playModeButton,
                                        tint = if (playMode != QueueModes.CYCLE) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                                        sizeDp = 24.dp
                                    )
                                }
                                MetroIconButton(onClick = onSavePlaylist) {
                                    MetroIcon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = strings.saveAsPlaylist,
                                        tint = LocalMetroColors.current.onBackground,
                                        sizeDp = 24.dp
                                    )
                                }
                                // 清空队列: 停播并回到暂无播放态
                                MetroIconButton(onClick = onClearQueue) {
                                    MetroIcon(
                                        imageVector = Icons.Default.DeleteSweep,
                                        contentDescription = strings.clearQueue,
                                        tint = LocalMetroColors.current.onBackground,
                                        sizeDp = 24.dp
                                    )
                                }
                            }
                            MetroDivider(color = LocalMetroColors.current.divider)
                            QueueView(
                                queue = playbackQueue,
                                currentIndex = currentQueueIndex,
                                playMode = playMode,
                                isActive = showQueue,
                                interactive = cardExpandedForInput,
                                queueHeaderHeightPx = queueHeaderHeightPx,
                                onPlayIndex = onPlayFromQueue,
                                onRemoveIndex = onRemoveFromQueue,
                                onMove = onMoveInQueue
                            )
                        }
                    }
                }

                if (isWidePlayer) {
                    // 宽屏：Row 两栏，区域化布局（weight 分区，无绝对定位 / 魔法数字）。
                    // 左栏 = 封面区(weight 撑满剩余) + 歌名 + 控件；右栏 = 歌词·队列占满整轴。
                    Row(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .weight(wideLeftFraction)
                                .fillMaxHeight()
                        ) {
                            // 封面区：只作为"唯一封面 overlay"在宽屏的落点参考——实测其中心与
                            // 尺寸，交给 overlay 封面定位。这里不再放第二个封面，保证全屏 ↔
                            // miniBar 始终是同一个 cover。
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .onGloballyPositioned { coords ->
                                        val b = coords.boundsInRoot()
                                        // 封面在左栏封面区内居中（不贴边，视觉更和谐）。
                                        wideCoverCenter = b.center - cardRootOrigin
                                        wideCoverSizePx = minOf(b.width, b.height)
                                    }
                            )
                            // 歌名 / 歌手 + 控件：限宽居中，与封面成组（单栏时不再铺满整宽显得散）。
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Column(
                                    modifier = Modifier
                                        .widthIn(max = 560.dp)
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp)
                                        .graphicsLayer { alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }
                                        .clickable { onSongInfoClick() }
                                ) {
                                    MetroText(
                                        s.name,
                                        color = LocalMetroColors.current.onBackground,
                                        style = LocalMetroTypography.current.titleLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    MetroText(
                                        s.artists?.joinToString("/") { it.name } ?: "",
                                        color = LocalMetroColors.current.primary,
                                        style = LocalMetroTypography.current.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Box(
                                    modifier = Modifier
                                        .widthIn(max = 560.dp)
                                        .fillMaxWidth()
                                        .graphicsLayer { alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }
                                ) {
                                    playerControls()
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        // 右栏常挂载（weight 给极小下限，wideSplit=0 时宽度趋 0 但不卸载）：
                        // 避免开关歌词时面板反复 mount/unmount 导致歌词状态丢失/不再重绘。
                        Box(
                            modifier = Modifier
                                .weight((1f - wideLeftFraction).coerceAtLeast(0.0001f))
                                .fillMaxHeight()
                        ) {
                            playerPanels(
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }
                            )
                        }
                    }
                } else {
                    // 窄屏：顶部标题栏 + 整宽面板 + 底部控件（保持原行为）。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .graphicsLayer { alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }
                            .padding(start = 68.dp, end = 56.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer { alpha = lyricAnimProgress.value }
                                // 歌名区域可点: 上拉"转到歌手/转到专辑"菜单(类 Apple Music)
                                .clickable { onSongInfoClick() }
                        ) {
                            MetroText(
                                s.name,
                                color = LocalMetroColors.current.onBackground,
                                style = LocalMetroTypography.current.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                modifier = Modifier.basicMarquee(
                                    iterations = Int.MAX_VALUE,
                                    animationMode = MarqueeAnimationMode.Immediately,
                                    initialDelayMillis = 2000,
                                    repeatDelayMillis = 2500,
                                    velocity = 48.dp
                                )
                            )
                            MetroText(
                                s.artists?.joinToString("/") { it.name } ?: "",
                                color = LocalMetroColors.current.onSurfaceVariant,
                                style = LocalMetroTypography.current.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .graphicsLayer { alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }
                    ) {
                        playerPanels(Modifier.fillMaxSize())
                        // 收起态悬浮播放键（v1.4.0 · A）：只在控制栏真的收起后挂载 ——
                        // alpha=0 却常挂载的按钮会变成一块"摸不着的命中区"（见 B-1 的教训）。
                        // 点 = 播放/暂停；向下拖 = 把控制栏拉回来（复用同一条收起进度轴）。
                        if (controlsCollapsedForInput) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 16.dp, bottom = 16.dp)
                                    .size(56.dp)
                                    .background(LocalMetroColors.current.surfaceVariant)
                                    .then(controlsCollapseDrag())
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onPlayPause()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                MetroIcon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = LocalMetroColors.current.onBackground,
                                    sizeDp = 30.dp
                                )
                            }
                        }
                        // 大封面模式下的曲名/歌手信息：overlay 在内容区底部，不占高度。
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 8.dp)
                                .graphicsLayer {
                                    alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) *
                                            (1f - lyricAnimProgress.value)
                                }
                                // 歌名区域可点: 上拉"转到歌手/转到专辑"菜单(类 Apple Music)
                                .clickable { onSongInfoClick() }
                        ) {
                            MetroText(
                                s.name,
                                color = LocalMetroColors.current.onBackground,
                                style = LocalMetroTypography.current.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            MetroText(
                                s.artists?.joinToString("/") { it.name } ?: "",
                                color = LocalMetroColors.current.primary,
                                style = LocalMetroTypography.current.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { c ->
                                // 只在展开态实测（收起后高度会被收成 0，别把度量值也带偏）：
                                // 高度决定"从布局里收走多少"，上沿决定整卡拖拽在哪让路。
                                if (controlsCollapse.value < 0.01f) {
                                    controlsHeightPx = c.size.height.toFloat()
                                    controlsTopInCardPx = c.boundsInRoot().top - cardRootOrigin.y
                                }
                            }
                            // 收起：layout 阶段把高度收走（面板顺势长高），内容用 graphicsLayer
                            // 平移出屏幕 —— 全程零 recomposition，只有这一棵子树重新布局。
                            .collapsibleHeight(controlsCollapse)
                            .then(controlsCollapseDrag())
                            .graphicsLayer {
                                alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f)
                                translationY = controlsCollapse.value * size.height
                            }
                    ) {
                        playerControls()
                    }
                    // 控制栏把手（v1.4.2）：常驻在内容区最底部，**收起态也可见**。
                    // 用户反馈：「划下去就划不上来了」—— 收起后歌词面板占满全屏，歌词自己的
                    // 点击/滚动都在抢手势，只靠"右下角悬浮键下滑"恢复既难发现也难命中。
                    // 这里给一个明确的小横条：向上拖=收起、向下拖=恢复、点一下=切换。
                    // 它挂在 Column 里（在最底部、系统栏之上），控制栏收起时不会被一起带走。
                    // 触摸契约（v1.5.0 · C2）：视觉 40×3dp 不变；
                    //  - 拖拽带 = 全宽 × 24dp（外层）—— 保持 v1.4.2 的手感，宽度**故意不缩到 48dp**：
                    //    1440px 宽的 S6 上 48dp 只有 192px，用户抱怨过「划下去就划不上来」；
                    //  - 点按命中盒 = 居中 48×24dp（内层显式声明）—— 满足 48dp 最小触摸目标，
                    //    并承载无障碍语义。两者取并集，命中区只增不减。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 手势导航机型要把整条拖拽带抬离系统「回到桌面」手势带：
                            // 实测（PCL110 / Android 16）从屏幕底部往上 0~13dp 起手的上滑
                            // 100% 被判成系统手势，28dp 起手才会交给应用；而这一段**无法**用
                            // systemGestureExclusion 排除（排除矩形确实注册进了
                            // mSystemGestureExclusion，系统照样吞掉）。所以按导航模式把
                            // 拖拽带整体上抬 32dp —— 这样带内任意一点起手都在安全区。
                            // 三键导航 / API < 29（无 navigation_mode 设置项）不抬，行为不变。
                            .padding(bottom = gestureNavLiftDp)
                            .height(24.dp)
                            // v1.5.1 · B —— 全面屏手势冲突：把手在屏幕**最底边**（卡片是
                            // fillMaxSize，覆盖到系统手势区），Android 10+ 的手势导航会把从
                            // 这里起手的上滑当成"回到桌面"抢走，把手于是只能点按、拖不动。
                            //
                            // 这里声明系统手势排除区（API 29+ 生效、低版本自动忽略）。
                            // 实测（PCL110 / Android 16）：排除矩形确实注册进了 dumpsys 的
                            // mSystemGestureExclusion，**但系统照样吞掉底部 0~13dp 起手的上滑**
                            // —— 真正解决问题的是上面那个「按导航模式上抬 32dp」，这一句是配合
                            // （对左右边缘与部分 ROM 仍然有效）。
                            //
                            // 只排除**这一条**：系统在同一屏幕边缘只认最靠上的那一个排除矩形，
                            // 若把收起态那个悬浮播放键也一起排除，两个矩形里只有更靠上的悬浮键
                            // 会被采信，把手反而失效。宽 × 24dp 远小于系统允许的上限（200dp）。
                            .systemGestureExclusion()
                            .then(controlsCollapseDrag()),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 48.dp, height = 24.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { toggleControlsCollapse() }
                                // 此前把手对无障碍服务完全不可见：TalkBack 用户把控制栏收起后
                                // 没有任何办法把它拿回来。
                                .semantics { contentDescription = strings.controlsHandleLabel },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 40.dp, height = 3.dp)
                                    .background(
                                        LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.55f)
                                    )
                            )
                        }
                    }
                }
            }
        }

        // 迷你播放栏叠加层：始终在 Composition 中，透明度仅在绘制阶段控制，避免动画期间触发重组
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .then(
                    // 暂无播放（hasSong=false）时不可点击展开，避免空白播放器被拉起。
                    if (miniBarEnabled && hasSong) Modifier.clickable(
                        interactionSource = miniBarInteractionSource,
                        indication = null,
                        onClick = {
                            coroutineScope.launch {
                                progress.animateTo(
                                    1f,
                                    tween(durationMillis = 400, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
                                )
                            }
                        }
                    ) else Modifier
                )
                .graphicsLayer {
                    alpha = (1f - progress.value * 5f).coerceIn(0f, 1f)
                }
                .background(LocalMetroColors.current.surface)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasSong) {
                    val s = song!!
                    // 唯一封面 overlay 会落到这个方形占位处（窄屏与宽屏一致）。
                    Spacer(modifier = Modifier.fillMaxHeight().aspectRatio(1f))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    ) {
                        MetroText(
                            s.name,
                            color = LocalMetroColors.current.onBackground,
                            style = LocalMetroTypography.current.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        MetroText(
                            s.artists?.joinToString("/") { it.name } ?: "",
                            color = LocalMetroColors.current.onSurfaceVariant,
                            style = LocalMetroTypography.current.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (miniBarEnabled) {
                        MetroIconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPlayPause()
                        }) {
                            MetroIcon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = LocalMetroColors.current.onBackground
                            )
                        }
                        MetroIconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPlayNext()
                        }) {
                            MetroIcon(Icons.Default.SkipNext, null, tint = LocalMetroColors.current.onBackground)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(96.dp))
                    }
                } else {
                    // 暂无播放: 卡片仍不可拉起(保持既有约束), 但给一个播放键
                    // 直接开始 Infinity——取每日推荐开播, 无需先有队列
                    MetroText(
                        strings.noSongPlaying,
                        color = LocalMetroColors.current.onSurfaceVariant,
                        style = LocalMetroTypography.current.bodyMedium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    )
                    if (miniBarEnabled) {
                        MetroIconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPlayNothing()
                        }) {
                            MetroIcon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = LocalStrings.current.playButton,
                                tint = LocalMetroColors.current.onBackground
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                }
            }
        }

        // 唯一封面叠加层：全屏 ↔ miniBar 共用这一个 cover，随 progress 平滑位移/缩放。
        // 窄屏大图=整屏宽居中；宽屏大图=左栏封面区实测中心与尺寸。
        if (hasSong) {
            val s = song!!
            StableCover(
                model = CoverUrls.large(s.album?.picUrl),
                contentDescription = null,
                placeholderColor = LocalMetroColors.current.surfaceVariant,
                modifier = Modifier
                    .then(
                        if (isWidePlayer) Modifier.size(coverSizeDp)
                        else Modifier.fillMaxWidth().aspectRatio(1f)
                    )
                    .graphicsLayer {
                        val p = progress.value
                        val normalizedP = ((p - 0.2f) / 0.8f).coerceIn(0f, 1f)
                        // 宽屏封面恒为大图（缩到 mini 是窄屏"大封面↔歌词"切换的语义）；
                        // 宽屏下它随分栏进度在左栏与居中之间平滑移动。
                        val lyricAnimValue = if (isWidePlayer) 0f else lyricAnimProgress.value

                        val targetCenterX = largeCoverCenterX + lyricAnimValue * (miniCoverCenterX - largeCoverCenterX)
                        val targetCenterY = largeCoverCenterY + lyricAnimValue * (miniCoverCenterY - largeCoverCenterY)
                        val targetScale = miniScale + (1f - lyricAnimValue) * (1f - miniScale)

                        val currentCenterX = miniCoverCenterX + normalizedP * (targetCenterX - miniCoverCenterX)
                        val currentCenterY = miniCoverCenterY + normalizedP * (targetCenterY - miniCoverCenterY)
                        val currentBaseScale = miniScale + normalizedP * (targetScale - miniScale)

                        scaleX = currentBaseScale
                        scaleY = currentBaseScale
                        translationX = currentCenterX - boundsCenter
                        translationY = currentCenterY - boundsCenter
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    },
                contentScale = ContentScale.Crop
            )
        }

        // 收起按钮叠加层：z 序最高，保证触摸事件不被任何下层元素拦截
        if (hasSong) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .graphicsLayer { alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }
                    .padding(end = 8.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (dismissEnabled) {
                    MetroIconButton(onClick = onDismiss) {
                        MetroIcon(Icons.Default.KeyboardArrowDown, strings.collapsePlayer, tint = LocalMetroColors.current.onBackground)
                    }
                }
            }
        }
    }
}

/**
 * v1.4.0 · A：按 [collapse]（0..1）把本节点**在布局里占的高度**收走。
 *
 * 与 graphicsLayer 平移配合使用：布局高度收走 → 兄弟节点（面板，weight 1f）顺势长高；
 * 内容本身由外层 graphicsLayer 平移出屏幕。这里在 layout 阶段直接读 Animatable，
 * **不触发 recomposition** —— 动画帧只重排这一棵子树。
 */
private fun Modifier.collapsibleHeight(collapse: Animatable<Float, AnimationVector1D>): Modifier =
    this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val shrink = (collapse.value.coerceIn(0f, 1f) * placeable.height).roundToInt()
        layout(placeable.width, (placeable.height - shrink).coerceAtLeast(0)) {
            placeable.place(0, 0)
        }
    }

// 新封面超过该阈值仍未就绪，才退化为纯色占位（"实在不出来再禁用"）。
private const val COVER_HOLD_MS = 400L

/**
 * 切歌不闪的封面。Coil 的 [AsyncImagePainter] 在 model 变化时先进入 loading 态、
 * 画 placeholder（纯色），新图没秒出就会闪一下占位色。这里改为：
 *  - 记住最近一次成功加载的封面，切歌换图期间先沿用旧图（视觉无缝）；
 *  - 新图在 [COVER_HOLD_MS] 内就绪 → 直接换上新图；
 *  - 超过阈值仍未就绪 → 才退化为占位色。
 */
@Composable
private fun StableCover(
    model: Any?,
    contentDescription: String?,
    placeholderColor: Color,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val painter = rememberAsyncImagePainter(
        model = model,
        imageLoader = Coil.imageLoader(context),
    )
    val state = painter.state
    // 最近一次成功加载的封面位图（跨切歌保留）。
    var lastBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // 新图超过阈值仍未就绪 → 退化占位色。
    var timedOut by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        val s = state
        if (s is AsyncImagePainter.State.Success) {
            (s.result.drawable as? BitmapDrawable)?.bitmap?.let { lastBitmap = it }
            timedOut = false
        }
    }
    LaunchedEffect(model) {
        timedOut = false
        delay(COVER_HOLD_MS)
        if (painter.state !is AsyncImagePainter.State.Success) timedOut = true
    }

    Box(modifier = modifier) {
        // 底层：切歌后旧图垫底（超阈值退化占位色）。
        val bg = if (timedOut) null else lastBitmap
        if (bg != null) {
            Image(
                painter = remember(bg) { BitmapPainter(bg.asImageBitmap()) },
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = contentScale,
            )
        } else {
            Box(Modifier.matchParentSize().background(placeholderColor))
        }
        // 上层：当前请求的 painter。必须真正绘制它，Coil 才会在 onRemembered 里发起
        // 请求；loading 时它不画东西，露出底层旧图/占位色。
        Image(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier.matchParentSize(),
            contentScale = contentScale,
        )
    }
}

/**
 * v1.5.1 · B：控制栏把手在手势导航机型上要抬离系统手势带的高度。
 *
 * 实测（PCL110 / Android 16 / navigation_mode=2）：从屏幕底部往上 0~13dp 起手的上滑
 * 100% 被判成「回到桌面」，28dp 起手才会交给应用；把这段声明成
 * `systemGestureExclusion` 也**没用** —— 排除矩形确实注册进了
 * `dumpsys window` 的 `mSystemGestureExclusion`，系统照样把上滑当自家手势。
 * 32dp 是实测安全阈值（28dp 可用）之上的保守取值：
 * 拖拽带整体上抬后，带内任意一点起手都不再落在系统手势带里。
 */
private val GESTURE_NAV_HANDLE_LIFT_DP = 32.dp

/**
 * 是否处于 Android 10+ 的手势导航（`navigation_mode == 2`）。
 *
 * `navigation_mode` 是 API 29 引入的 secure setting；读取 secure setting 不需要权限，
 * 设置项不存在（API < 29 或三键导航写的是 0）时 `getInt` 返回默认值 0 ⇒ 不上抬，
 * 老设备与三键导航机型的视觉/行为完全不变。
 */
private fun isGestureNavigation(context: android.content.Context): Boolean = runCatching {
    android.provider.Settings.Secure.getInt(context.contentResolver, "navigation_mode", 0) == 2
}.getOrDefault(false)
