/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明（Bug1「音质切换」）：
 *   - A：新增 onQualityPreferenceChanged()，设置页改音质后若正在播放且生效档位确实变化，
 *        立即按新档位对当前歌重新取链播放（不再等到下一首才生效）。
 *   - B：新增 preferredQualityIndex（偏好档位）与 qualityDowngraded（降级标记），
 *        与 currentQualityIndex（服务端实际返回档位）区分，供播放器 UI 提示"已降级"。
 *   - 抽出 effectivePreferredLevel()，统一设置页/播放路径的档位读取。 */

package com.takahashirinta.ncrust.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.takahashirinta.ncrust.cache.OfflineAudioCache
import com.takahashirinta.ncrust.cache.OfflineKeys
import com.takahashirinta.ncrust.cache.OfflineUrlStore
import com.takahashirinta.ncrust.player.QualityAssessment
import com.takahashirinta.ncrust.player.QualityLadder
import com.takahashirinta.ncrust.player.QualityStatus
import com.takahashirinta.ncrust.lyric.AmllTtmlClient
import com.takahashirinta.ncrust.lyric.LrcLine
import com.takahashirinta.ncrust.lyric.LrcParser
import com.takahashirinta.ncrust.lyric.YrcParser
import com.takahashirinta.ncrust.lyric.LyricCandidate
import com.takahashirinta.ncrust.lyric.LyricRequestGate
import com.takahashirinta.ncrust.lyric.LyricSourceChain
import com.takahashirinta.ncrust.lyric.LyricSourceKind
import com.takahashirinta.ncrust.lyric.LyricSourcePrefs
import com.takahashirinta.ncrust.lyric.LyricTrack
import com.takahashirinta.ncrust.lyric.LyricTrackMerge
import com.takahashirinta.ncrust.lyric.LyricTrackSource
import com.takahashirinta.ncrust.lyric.cacheTag
import com.takahashirinta.ncrust.lyric.LyricsCache
import com.takahashirinta.ncrust.lyric.LyricsDisplayPrefs
import com.takahashirinta.ncrust.lyric.LyricsSweepQuality
import com.takahashirinta.ncrust.lyric.LyricsWordAnimationMode
import com.takahashirinta.ncrust.lyric.TtmlDoc
import com.takahashirinta.ncrust.lyric.TtmlParser
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.player.PlaybackService
import com.takahashirinta.ncrust.player.PlaybackStateManager
import com.takahashirinta.ncrust.player.PlayReporter
import com.takahashirinta.ncrust.player.SongUrlFetcher
import com.takahashirinta.ncrust.player.SongUrlResult
import com.takahashirinta.ncrust.formatDuration
import com.takahashirinta.ncrust.ui.i18n.getSavedLanguageCode
import com.takahashirinta.ncrust.ui.i18n.stringsForCode
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    val isPlaying = MutableStateFlow(false)
    val currentPosition = MutableStateFlow(0L)
    val duration = MutableStateFlow(0L)
    val progress = MutableStateFlow(0f)
    val lyrics = MutableStateFlow<List<LrcLine>>(emptyList())
    // 当前 lyrics 内容所属的歌曲 id：-1 = 尚无(切歌后旧歌词只是渐隐过渡, 不算就绪)。
    // 让 UI 的 "lyricsReady" 判断基于"歌词属于当前歌", 而非"有没有歌词数组"——
    // 否则切歌后旧歌词残留会被误判为就绪, 把无歌词的新歌切回歌词视图显示旧歌词。
    val lyricsSongId = MutableStateFlow(-1L)
    // 外文歌词的译文(tlyric),Spotify 式渲染在原句下方。
    val translatedLyrics = MutableStateFlow<List<LrcLine>>(emptyList())
    /**
     * v1.9.2：音译轨（罗马音 / 粤拼），与 [translatedLyrics] 同构的行级轨道，来源可以是 TTML 的
     * `x-roman`，也可以是网易云的 `romalrc`，缺口按文本逐行回退（见 [LyricTrackMerge]）。
     *
     * **本版没有 UI 消费者**：v1.9.0/v1.9.1 从来没有渲染过音译（TtmlDoc.romans 无引用、
     * 网易云 romalrc 也没解析），要显示它必须给 LyricsView / NcrustLyricsPanel 加一个副文本槽，
     * 而「渲染层 diff 必须为空」是本版的硬约束。所以这里先把**数据层**做完整并单测覆盖，
     * 渲染接线留给解禁渲染层的版本（届时 LyricsView 只需多收一个参数）。
     */
    val romanizedLyrics = MutableStateFlow<List<LrcLine>>(emptyList())
    // 歌词是否仍在加载中(网络往返未返回)。UI 用它区分「真的没歌词」与「还没加载出来」:
    // 加载中或成功为空 → 全屏默认大封面、歌词按钮置灰;加载出非空 → 自动切回歌词视图。
    val lyricsLoading = MutableStateFlow(false)
    // 服务端明确答复"这首歌没有歌词"(code==200 且 lrc 为空)的歌曲 id。
    // 与「请求失败 / 尚未加载」严格区分：失败时保持 -1，歌词按钮仍可点（触发重试），
    // 避免一次网络抖动就把按钮永久置灰、必须切歌才能恢复。
    val lyricsNoContentSongId = MutableStateFlow(-1L)
    // 设置页开关:是否显示歌词翻译。默认开——外文歌直接看到双语,中文歌 tlyric 为空不受影响。
    val showLyricsTranslation = MutableStateFlow(true)
    // v1.5.1 · D：上一次推给媒体面板的歌词行（去重用，避免 2Hz 采样反复写同一个值）。
    private var lastMediaLyricLine: String? = null

    // v1.5.1 · E：字号落盘防抖窗口 —— A-/A+ 连点时只写最后一次。
    private val FONT_SCALE_WRITE_DEBOUNCE_MS = 150L
    // 设置页开关:逐字歌词(v1.5.0 · B，v1.5.1 · A 起是三模式)。默认「渐变扫过」——
    // 只在歌曲真的带 yrc 逐字数据时才有区别，没有逐字数据的歌行为与关掉完全一致。
    // 取值见 LyricsWordAnimationMode（0 渐变扫过 / 1 逐字硬切 / 2 关闭逐字）。
    val lyricsWordAnimation = MutableStateFlow(LyricsWordAnimationMode.GRADIENT_SWEEP)

    // v1.5.2：逐字扫过的绘制质量（0 自动 / 1 高级软边 / 2 兼容硬边）。默认「自动」——
    // 按设备是否 low-RAM 决定；两种画法的**光标位置算法完全相同**，所以切到兼容档只是
    // 少了渐变带的柔化，不会退回 v1.5.1 那种按词跳变。取值见 LyricsSweepQuality。
    val lyricsSweepQuality = MutableStateFlow(LyricsSweepQuality.AUTO)

    /**
     * v1.5.1 · D：是否把当前歌词行送进系统媒体面板（通知栏 / 锁屏 / 车机）。
     *
     * **默认关**：Android 13+ 的媒体面板第二行只认 ARTIST，开启后 ARTIST 会变成
     * 「艺人 · 歌词行」，这是对既有语义的改写 —— 只有用户明确打开才做。
     */
    val lyricsInMediaSession = MutableStateFlow(false)

    /**
     * v1.9.0：AMLL TTML 歌词源总开关（默认开）。关掉 = 一个 TTML 请求都不发，
     * 既有 LRC / yrc 路径原样保留，等价于 v1.8.1 的行为。
     */
    val lyricsTtmlEnabled = MutableStateFlow(true)

    /**
     * v1.9.0：TTML 与网易云歌词都可用时是否优先用 TTML（默认开）。
     * 只在 [lyricsTtmlEnabled] 开着时有意义 —— 关掉 TTML 时它不影响任何结果。
     */
    val lyricsTtmlFirst = MutableStateFlow(true)

    /**
     * v1.5.1 · E：歌词字号倍率（0.7~1.5，默认 1.0）。设置页与歌词界面的 A- / A+ 共用它，
     * 变更立即生效（StateFlow → 面板重组），落盘做 150ms 防抖（连续点按只写一次磁盘）。
     */
    val lyricsFontScale = MutableStateFlow(LyricsDisplayPrefs.FONT_SCALE_DEFAULT)
    private var fontScaleWriteJob: kotlinx.coroutines.Job? = null

    val isBuffering = MutableStateFlow(false)
    // Emits true when the current song enters the preload window (last 20 s).
    val needsPreload = MutableStateFlow(false)

    val currentSongId = MutableStateFlow<Long?>(null)
    val currentSongName = MutableStateFlow<String?>(null)
    val currentSongArtist = MutableStateFlow<String?>(null)
    val currentSongArtwork = MutableStateFlow<String?>(null)

    private var onSongEndedCallback: (() -> Unit)? = null
    private var onSongPreviousCallback: (() -> Unit)? = null
    private var onSongTransitionedCallback: (() -> Unit)? = null
    private var onUnplayableCallback: (() -> Unit)? = null
    private var playJob: Job? = null
    private var preloadJob: Job? = null

    // 防止同一首歌重复上报播放行为。
    private var lastReportedSongId = -1L

    /** B4：本歌最近一次已落盘的进度，避免每 500ms 的采样都写一次 SharedPreferences。 */
    private var lastSavedPositionMs = -1L

    /** B4：本次起播的续播位置（0 = 从 0 分 0 秒开始）。 */
    val resumedFromMs = MutableStateFlow(0L)

    // Incremented on every explicit playSong call; lets preloadNextSong detect staleness.
    private var songPlayVersion = 0

    companion object {
        /**
         * 音质档位（低 → 高）。索引与 i18n qualityOptions 顺序、以及 eapi level 取值一一对应；
         * 索引越大音质越高，因此「实际索引 < 偏好索引」表示可能被降级（A3 起改用实际文件参数判定）。
         * A2 起档位表由 QualityLadder 提供，并新增 jymaster（超清母带）。
         */
        val QUALITY_LEVELS = QualityLadder.LEVELS

        /** B4：进度落盘间隔。2Hz 采样下最多每 5s 写一次，兼顾续播精度与 IO 开销。 */
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L
    }

    /** 服务端**实际**返回的档位索引（可能因设备解码能力 / 会员权限 / 版权低于偏好）。 */
    val currentQualityIndex = MutableStateFlow(3)

    /** 用户**偏好**档位索引（由设置页 wifi / mobile 偏好 + 当前网络决定）。 */
    val preferredQualityIndex = MutableStateFlow(3)

    /**
     * 音质状态（A3）。不再用「level 字符串的档位序号」判断降级 —— 实测存在标签写 lossless、
     * 实际文件是 Hi-Res 的情况，也存在请求杜比拿到 jyeffect（不同容器）的情况。
     * 判定统一走 QualityAssessment：按实际文件参数（br/type）+ 该曲档位上限给结论。
     */
    val qualityStatus = MutableStateFlow(QualityStatus.NORMAL)

    /**
     * B2-C：跟随封面时的原始主题色（ARGB）。由 PlaybackService 的 Palette 结果静态回调推送，
     * null = 无封面 / 提取失败 → UI 回落预设色。饱和度压制与亮度锚定在 UI 侧按当前明暗处理
     * （同一张封面在深色/浅色下锚定区间不同，不能只按 URL 缓存处理结果）。
     */
    val coverAccentRgb = MutableStateFlow<Int?>(null)

    private val qualityApiLevels = QUALITY_LEVELS

    /** 本次播放**请求**的档位；与 lastPlayedLevel（实际拿到）区分，用于判断偏好是否真的变了。 */
    private var lastRequestedLevel = ""

    // 播放出错(如设备解码不了 24-bit FLAC / 高采样率)时自动降档重试的阶梯。
    // 每出错一次降一档,到 standard 仍失败才跳歌:保证「有声音,或跳歌」,绝不静默卡住。
    private val qualityRetryLadder = listOf(
        "jymaster", "dolby", "jyeffect", "hires", "lossless", "exhigh", "higher", "standard",
    )
    // 上一次交给 PlaybackService 的 URL 的实际档位(fetch 内部可能已降级)。
    private var lastPlayedLevel = ""
    // A3：最近一次取链的输入，供偏好变化后重算状态（不重新取链）。
    private var lastVerdictRequested = ""
    private var lastVerdictResult: SongUrlResult? = null
    // 已处理过的出错点 (songId@level),配合时间窗防止同一错误反复触发重试。
    private var lastErrorKey = ""
    private var lastErrorHandledAt = 0L

    private var gaplessEnabled = false
    // 进入当前歌最后 60 秒即触发下一首预载: 取链(网络往返)+ ExoPlayer 准备/buffer
    // 需要充足时间, 20s 碰到慢网络/冷缓存会来不及, 无缝退化成硬切。
    private val PRELOAD_THRESHOLD_MS = 60_000L

    // Metadata for the in-flight preload; applied when ExoPlayer auto-transitions.
    // All reads/writes happen on the main thread.
    private var preloadedSongId = -1L
    private var preloadedTitle = ""
    private var preloadedArtist = ""
    private var preloadedArtwork = ""
    // A3：预载结果整体留存 —— 自动切歌时要据此重算音质状态（br/type/songMaxLevel 都要用）。
    private var preloadedResult: SongUrlResult? = null
    private var preloadedRequestedLevel = ""
    // Cached stream URL from the last completed preload; used by playSong fast-path to skip fetch.
    private var preloadedUrl = ""
    // Song ID most recently requested by playSong; lets a concurrent preload detect a same-song race.
    private var latestPlaySongId = -1L

    private data class PreloadCacheEntry(
        val url: String,
        val actualLevel: String,
        // 取链时用的请求档位:播放失败降档重试时,只有档位一致才允许命中缓存,
        // 避免把上一档(可能播不出声)的 URL 原样放回播放器。
        val requestedLevel: String,
        // A3：缓存也要带上实际文件参数与档位上限，否则走缓存开播时算不出音质状态。
        val br: Long = 0L,
        val type: String = "",
        val songMaxLevel: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )
    private val preloadCache = mutableMapOf<Long, PreloadCacheEntry>()
    private val CACHE_TTL_MS = 5 * 60 * 1_000L
    // Prevents duplicate preload launches for the same song while one is in flight.
    private var currentlyPreloadingSongId = -1L

    // ⚠️ 下面这三个**必须声明在 init 块之前**（v1.9.0 · S1 hotfix）。
    //
    // Kotlin 的属性初始化与 init 块**按声明顺序**执行，而 init 块里的
    // `viewModelScope.launch { fetchLyrics(...) }` 在 Main.immediate 下可能**在构造期就同步跑起来**
    // （构造本身就在主线程）—— 于是 fetchLyrics 会在构造函数返回之前执行。
    // 声明在 init 之后的属性此刻还是默认值：引用类型是 null，基本类型是 0。
    //
    // 实测崩溃（v1.9.0 首次真机安装，PCL110）：
    // `NullPointerException: Attempt to invoke virtual method 'long ...LyricRequestGate.begin()'
    //  on a null object reference` at PlayerViewModel.<init> → fetchLyrics。
    // 老代码里 `lyricsFetchingSongId` 是 Long（读成 0 只是判等失真，不崩），所以这个坑一直潜伏；
    // v1.9.0 新增的 [lyricReqGate] 与 [STALE_NETEASE_LYRICS] 是**对象引用**，一读就炸。
    //
    // 单测抓不到：项目里没有 Robolectric，构造 AndroidViewModel 需要真 Application，
    // 所以只有「装到真机冷启」能暴露。**新增供 fetchLyrics 使用的字段时，一律放这一段。**

    // 同一首歌的歌词请求只允许一个在途(playSong / onSongTransitioned / 冷启动恢复
    // 会并发发起, 不打去重会瞬间打 3×n 个请求, 触发服务端限流反而更拉胯)。
    private var lyricsFetchingSongId = -1L

    /**
     * v1.9.0：歌词请求序列号闸门（见 [LyricRequestGate]）。
     *
     * 去重（[lyricsFetchingSongId]）挡的是「同一首歌的重复请求」，闸门挡的是「旧歌的响应盖掉
     * 新歌」—— 两件事必须分开：A 歌请求还在飞时切到 B，去重放行 B，闸门作废 A。
     */
    private val lyricReqGate = LyricRequestGate()

    /** 闸门判定「这次请求已经被新歌取代」时的返回：调用方拿到它只会原样丢弃，不会写任何状态。 */
    private val STALE_NETEASE_LYRICS = NeteaseLyrics("", "", "", "", authoritative = false)

    init {
        refreshGaplessSetting()
        // 从设置读歌词开关(默认开);设置页切换时经 setLyricsTranslation /
        // setLyricsWordAnimation 实时生效。readWordAnimation 顺带完成 v1.5.0 布尔开关的迁移。
        lyricsWordAnimation.value = LyricsDisplayPrefs.readWordAnimation(
            getApplication<Application>()
                .getSharedPreferences(LyricsDisplayPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        )
        showLyricsTranslation.value = getApplication<Application>()
            .getSharedPreferences("ncrust_settings", 0)
            .getBoolean("lyrics_translation", true)
        // v1.5.1 · E：歌词字号倍率（默认 1.0x —— 与 v1.5.0 的视觉完全一致）。
        lyricsFontScale.value = LyricsDisplayPrefs.readFontScale(
            getApplication<Application>()
                .getSharedPreferences(LyricsDisplayPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        )
        // v1.5.2：逐字扫过质量（默认自动）。
        lyricsSweepQuality.value = LyricsDisplayPrefs.readSweepQuality(
            getApplication<Application>()
                .getSharedPreferences(LyricsDisplayPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        )
        // v1.5.1 · D：媒体面板歌词开关（默认关，见字段注释）。
        lyricsInMediaSession.value = getApplication<Application>()
            .getSharedPreferences("ncrust_settings", 0)
            .getBoolean("lyrics_in_media_session", false)
        // v1.9.0：TTML 歌词源两个开关（默认都开）。read* 自身会把「键被写坏」回落成默认值。
        val lyricPrefs = getApplication<Application>()
            .getSharedPreferences(LyricsDisplayPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        lyricsTtmlEnabled.value = LyricsDisplayPrefs.readTtmlEnabled(lyricPrefs)
        lyricsTtmlFirst.value = LyricsDisplayPrefs.readTtmlFirst(lyricPrefs)

        // v1.5.1 · D：把「当前行」推给 PlaybackService —— 只在**跨行**时写一次
        // （currentPosition 是 2Hz 采样，这里每次采样只做一次 O(行数) 的二分/线性比较，
        // 值没变就不写），通知栏因此不会逐帧重绘。关掉开关或无歌词时推 null。
        viewModelScope.launch {
            combine(lyrics, currentPosition, lyricsInMediaSession) { lines, pos, on ->
                Triple(lines, pos, on)
            }.collect { (lines, pos, on) ->
                val line = if (!on || lines.isEmpty()) null else lines.lastOrNull { it.timeMs <= pos }?.text
                if (line != lastMediaLyricLine) {
                    lastMediaLyricLine = line
                    PlaybackService.mediaLyricLine = line
                }
            }
        }

        PlaybackService.onProgressUpdate = { pos, dur ->
            currentPosition.value = pos
            duration.value = dur
            progress.value = if (dur > 0) pos.toFloat() / dur.toFloat() else 0f

            // 播放行为上报: 进度达 80% 视为"听完",每首歌只上报一次。
            val sid = currentSongId.value ?: -1L
            if (sid > 0 && sid != lastReportedSongId && PlayReporter.reachedCompletion(pos, dur)) {
                lastReportedSongId = sid
                PlayReporter.reportPlay(sid, pos, dur, end = "playend", isWifi = isOnWifi())
            }

            // B4：每 5s 落盘一次本歌进度，供断点续播；播完会由 onPlaybackEnded 清除。
            if (sid > 0 && kotlin.math.abs(pos - lastSavedPositionMs) >= POSITION_SAVE_INTERVAL_MS) {
                lastSavedPositionMs = pos
                PlaybackStateManager.saveSongPosition(getApplication(), sid, pos)
            }

            // Signal the preload window once per song (guarded by !needsPreload.value).
            if (gaplessEnabled && dur > 0 && pos > 1_000L && !needsPreload.value) {
                val remaining = dur - pos
                if (remaining in 1L..PRELOAD_THRESHOLD_MS) {
                    needsPreload.value = true
                }
            }
        }
        PlaybackService.onPlaybackEnded = {
            // 自然播放结束时,若尚未上报则补一条 playend。
            val sid = currentSongId.value ?: -1L
            // B4：正常播完 = 这首歌已「听完」，清除进度记录，重播时从 0 分 0 秒对齐。
            if (sid > 0) PlaybackStateManager.clearSongPosition(getApplication(), sid)
            if (sid > 0 && sid != lastReportedSongId) {
                lastReportedSongId = sid
                PlayReporter.reportPlay(sid, duration.value, duration.value, end = "playend", isWifi = isOnWifi())
            }
            onSongEndedCallback?.invoke()
        }
        PlaybackService.onPlaybackPrevious = { onSongPreviousCallback?.invoke() }
        PlaybackService.onIsPlayingChanged = { playing -> isPlaying.value = playing }
        PlaybackService.onBufferingChanged = { buffering -> isBuffering.value = buffering }
        // B2-C：封面 Palette 提取出的主题色（null = 无封面 / 提取失败 → UI 回落预设色）。
        PlaybackService.onCoverAccent = { rgb -> coverAccentRgb.value = rgb }
        // ExoPlayer 主线程回调。播放失败 → 降档重试,而不是无声地停在 IDLE。
        PlaybackService.onPlaybackError = { sid -> handlePlaybackError(sid) }

        // Called on the main thread by ExoPlayer's onMediaItemTransition (AUTO reason).
        PlaybackService.onSongTransitioned = {
            // B4：无缝切换说明上一首已自然播完，清除其进度记录（重播从 0:00 对齐）。
            val finishedId = currentSongId.value ?: -1L
            if (finishedId > 0) PlaybackStateManager.clearSongPosition(getApplication(), finishedId)
            if (preloadedSongId > 0) {
                resetLyricsForNewSong()
                currentSongId.value = preloadedSongId
                currentSongName.value = preloadedTitle
                currentSongArtist.value = preloadedArtist
                currentSongArtwork.value = preloadedArtwork
                preloadedResult?.let { applyQualityVerdict(preloadedRequestedLevel, it) }
                PlaybackStateManager.saveState(
                    getApplication(), preloadedSongId,
                    preloadedTitle, preloadedArtist, preloadedArtwork, true
                )
                viewModelScope.launch { fetchLyrics(preloadedSongId) }
                clearPreloadedState()
                needsPreload.value = false
            }
            onSongTransitionedCallback?.invoke()
        }

        val savedState = PlaybackStateManager.getState(getApplication())
        if (savedState != null) {
            resetLyricsForNewSong()
            currentSongId.value = savedState.songId
            currentSongName.value = savedState.songName
            currentSongArtist.value = savedState.songArtist
            currentSongArtwork.value = savedState.songArtwork

            // Activity 冷重建但前台 Service 还活着的场景：不能盲写 isPlaying=false，
            // 否则 UI 显示暂停但音频还在响，用户要点多次按钮才能让状态与音频对齐。
            // 直接从 live service 拉真值，同时把 duration/position 一并同步——
            // 否则 500ms 心跳到来前 duration=0，togglePlayPause 会误走全量 playSong 分支导致重取 URL。
            val svc = PlaybackService.instance
            if (svc != null) {
                runCatching {
                    val p = svc.player
                    isPlaying.value = p.isPlaying
                    val livePos = p.currentPosition
                    val liveDur = p.duration
                    if (liveDur > 0) {
                        currentPosition.value = livePos
                        duration.value = liveDur
                        progress.value = livePos.toFloat() / liveDur.toFloat()
                    }
                }
            } else {
                isPlaying.value = false
            }

            if (savedState.songId > 0) {
                viewModelScope.launch { fetchLyrics(savedState.songId) }
            }
        }
    }

    fun setOnSongEndedCallback(callback: () -> Unit) { onSongEndedCallback = callback }
    fun setOnSongPreviousCallback(callback: () -> Unit) { onSongPreviousCallback = callback }
    fun setOnSongTransitionedCallback(callback: () -> Unit) { onSongTransitionedCallback = callback }
    fun setOnUnplayableCallback(callback: () -> Unit) { onUnplayableCallback = callback }

    /**
     * 切歌时立即清空旧歌词并置加载态。
     *
     * 上一版为了 Crossfade 渐隐而保留旧歌词, 切歌后最多 3 秒还挂着上一首的歌词,
     * 用户观感是"还停在旧歌"或"无歌词状态前闪一下旧歌词"——prog 长专整张连播时
     * 非常明显。这里直接清空: 旧歌词不再显示, 新歌词由 fetchLyrics 就绪后
     * PlayerCard 自动切回歌词视图; 确无歌词则由大封面盖住。
     */
    private fun resetLyricsForNewSong() {
        lyrics.value = emptyList()
        translatedLyrics.value = emptyList()
        romanizedLyrics.value = emptyList()
        lyricsLoading.value = true
        // 旧歌词是渐隐过渡素材, 不属于新歌; 在"当前歌歌词就绪"判定里立即失效
        lyricsSongId.value = -1L
        lyricsNoContentSongId.value = -1L
        // v1.5.2：位置也必须跟着一首歌一起翻篇。
        // 位置流是 2Hz 广播的，切歌那一两帧里 currentPosition 还是**上一首的末尾**；
        // 歌词面板（LyricsView）是拿 positionState.value 给外推做种子的，于是新歌词一到位就会
        // 用上一首的位置去二分找行 —— 落在新歌的最后一行，面板从第一行快速滚到最后一行、
        // 等真实位置（0）到了再滚回来。用户看到的就是「切歌时把歌词从头到尾过了一遍」。
        // 这里在**所有切歌路径共用的地方**归零，服务端随后会用 startPositionMs 广播真实值。
        currentPosition.value = 0L
        progress.value = 0f
    }

    fun resetPreloadFlag() { needsPreload.value = false }

    fun refreshGaplessSetting() {
        val prefs = getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
        // 默认开启: 无缝预载是播放体验的一部分, 不该让大多数用户默默用着硬切换
        gaplessEnabled = prefs.getBoolean("gapless_playback", true)
    }

    /**
     * v1.5.0 · B / v1.5.1 · A：逐字动画模式。只影响行内渲染，不重取歌词
     * （词时间轴一直在 LrcLine 上），所以切换是即时的、不需要重新请求。
     */
    fun setLyricsWordAnimation(mode: Int) {
        val normalized = LyricsWordAnimationMode.normalize(mode)
        lyricsWordAnimation.value = normalized
        LyricsDisplayPrefs.writeWordAnimation(
            getApplication<Application>()
                .getSharedPreferences(LyricsDisplayPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE),
            normalized
        )
    }

    /**
     * v1.5.2：逐字扫过的绘制质量（自动 / 高级软边 / 兼容硬边）。
     *
     * 只影响**怎么画**，不重取歌词、不重建轨道，所以切换是即时的：
     * `softEdge` 只在 draw 阶段被读，改完下一帧就生效。
     */
    fun setLyricsSweepQuality(quality: Int) {
        val normalized = LyricsSweepQuality.normalize(quality)
        lyricsSweepQuality.value = normalized
        LyricsDisplayPrefs.writeSweepQuality(
            getApplication<Application>()
                .getSharedPreferences(LyricsDisplayPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE),
            normalized
        )
    }

    /** 设置页开关:歌词翻译开/关。写 SharedPreferences + 更新 StateFlow,播放器立即可见。 */

    /**
     * v1.5.1 · D：媒体面板歌词开关。关闭时立刻清掉已经写进 ARTIST 的歌词行，
     * 不等下一次跨行 —— 否则用户关了开关、通知栏还挂着上一句歌词。
     */
    fun setLyricsInMediaSession(enabled: Boolean) {
        lyricsInMediaSession.value = enabled
        getApplication<Application>()
            .getSharedPreferences("ncrust_settings", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("lyrics_in_media_session", enabled).apply()
        if (!enabled) {
            lastMediaLyricLine = null
            PlaybackService.mediaLyricLine = null
        }
    }

    /**
     * v1.5.1 · E：设置歌词字号倍率。立即生效；落盘防抖（3GB 设备上连续点 A-/A+
     * 不该每次都同步写一遍 SharedPreferences）。
     */
    fun setLyricsFontScale(scale: Float) {
        val clamped = scale.coerceIn(LyricsDisplayPrefs.FONT_SCALE_MIN, LyricsDisplayPrefs.FONT_SCALE_MAX)
        lyricsFontScale.value = clamped
        fontScaleWriteJob?.cancel()
        fontScaleWriteJob = viewModelScope.launch {
            delay(FONT_SCALE_WRITE_DEBOUNCE_MS)
            LyricsDisplayPrefs.writeFontScale(
                getApplication<Application>()
                    .getSharedPreferences(LyricsDisplayPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE),
                clamped
            )
        }
    }

    /** v5.1 · E：歌词界面里的 A- / A+ —— 在档位表上走一格。 */
    fun stepLyricsFontScale(delta: Int) {
        setLyricsFontScale(LyricsDisplayPrefs.steppedFontScale(lyricsFontScale.value, delta))
    }

    fun setLyricsTranslation(enabled: Boolean) {
        showLyricsTranslation.value = enabled
        getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
            .edit().putBoolean("lyrics_translation", enabled).apply()
    }

    /**
     * v1.9.0：AMLL TTML 歌词源开关。写盘 + **立即对当前歌重拉一次** ——
     * 「用哪一份歌词」是源选择的结果，本地缓存里存的是上一次选择的结果，
     * 不重拉的话用户会看到开关拨了却什么都没变。
     */
    fun setLyricsTtmlEnabled(enabled: Boolean) {
        lyricsTtmlEnabled.value = enabled
        LyricsDisplayPrefs.writeTtmlEnabled(prefs(), enabled)
        refetchLyricsForSourceChange()
    }

    /**
     * v1.9.0：「TTML 优先」开关。语义同样是源选择，因此与总开关一样需要重拉当前歌。
     * 总开关关着时这个值不影响任何结果（UI 侧此时也不挂载这一行）。
     */
    fun setLyricsTtmlFirst(first: Boolean) {
        lyricsTtmlFirst.value = first
        LyricsDisplayPrefs.writeTtmlFirst(prefs(), first)
        refetchLyricsForSourceChange()
    }

    private fun prefs(): SharedPreferences = getApplication<Application>()
        .getSharedPreferences(LyricsDisplayPrefs.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 歌词源设置变化后重拉当前歌。复用 [retryLyrics]：它会清掉去重与「确无歌词」标记，
     * 这两件事都必须做 —— 否则同一首歌的第二次请求会被去重挡掉、脏标记还会让 UI 置灰。
     */
    private fun refetchLyricsForSourceChange() = retryLyrics()

    /**
     * 读取当前**生效的偏好档位**：设置页 wifi_quality / mobile_quality 按当前网络二选一。
     * 统一入口，避免设置页、播放路径、预载路径各写一份（Bug1 根因之一）。
     */
    private fun effectivePreferredLevel(prefs: SharedPreferences): String =
        if (isOnWifi()) qualityApiLevels.getOrElse(prefs.getInt("wifi_quality", 3)) { "lossless" }
        else qualityApiLevels.getOrElse(prefs.getInt("mobile_quality", 1)) { "higher" }

    /**
     * A3：记录一次取链结果并刷新音质状态。判定依据是**实际文件参数**（br/type）与该曲
     * 档位上限，而不是服务端返回的 level 字符串 —— 后者只是标签，可能写低（实测
     * granted=lossless 而 br=1,685,762，其实是 Hi-Res 文件）。
     */
    private fun applyQualityVerdict(requested: String, result: SongUrlResult) {
        lastPlayedLevel = result.actualLevel
        lastVerdictRequested = requested
        lastVerdictResult = result
        val verdict = QualityAssessment.assess(
            requested = requested,
            granted = result.actualLevel,
            br = result.br,
            type = result.type,
            songMaxLevel = result.songMaxLevel,
        )
        currentQualityIndex.value = verdict.displayIndex
        qualityStatus.value = verdict.status
    }

    /** 偏好档位变化后重算状态（不重新取链，避免每次改设置都重播当前歌）。 */
    private fun refreshQualityVerdict() {
        val result = lastVerdictResult ?: return
        applyQualityVerdict(lastVerdictRequested, result)
    }

    /**
     * 设置页改动音质偏好后调用（Bug1-A）。
     *
     * 1. 先刷新 preferredQualityIndex / qualityStatus，让播放器标签立刻反映偏好变化；
     * 2. 若当前有歌在播、**且生效档位确实变化**，按新档位对当前歌重新取链播放。
     *    生效档位没变时直接返回——否则每次改设置都会把当前歌从头重播一次。
     */
    fun onQualityPreferenceChanged() {
        val prefs = getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
        val newLevel = effectivePreferredLevel(prefs)
        preferredQualityIndex.value = qualityApiLevels.indexOf(newLevel).coerceAtLeast(0)
        refreshQualityVerdict()

        val sid = currentSongId.value ?: return
        if (sid <= 0L) return
        if (newLevel == lastRequestedLevel) return
        playSong(
            sid,
            title = currentSongName.value ?: "",
            artist = currentSongArtist.value ?: "",
            artworkUrl = currentSongArtwork.value ?: "",
            quality = newLevel
        )
    }

    /**
     * P1：**大屏模式就地切换音质**（不再"跳设置页"）。
     *
     * 复用设置页那条现成通道，一处逻辑两处入口：
     *  1. 写**当前网络对应**的那一项偏好（Wi-Fi → `wifi_quality`，移动网络 → `mobile_quality`），
     *     与 UserScreen 的两个下拉完全一致 —— 否则在 Wi-Fi 下改的档位会污染移动网络的偏好；
     *  2. 复用 [onQualityPreferenceChanged]：只改偏好 + **档位真变了才**重新取链，
     *     不会因为点一次音质就把当前歌从头重播。
     *
     * 调用方（播放器里的音质选择器）因此不需要自己读/写 prefs，也不需要判断网络类型。
     */
    /**
     * P1：读**当前生效**的偏好档位（按当前网络在 `wifi_quality` / `mobile_quality` 里二选一），
     * 供大屏模式的就地选择器回显。
     *
     * 必须现读，不能直接用 [preferredQualityIndex]：那个 StateFlow 是"最近一次已知值"，
     * 只在播放歌曲 / 改过设置时刷新，冷启动后仍是默认档 3（无损）——
     * 实测 PCL110：偏好其实是「超清母带」，选择器却把「无损」高亮成当前项。
     * 顺带把读到的值写回 StateFlow，让别处读到的也是一致的。
     */
    fun currentQualityPreferenceIndex(): Int {
        val prefs = getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
        val index = qualityApiLevels.indexOf(effectivePreferredLevel(prefs)).coerceAtLeast(0)
        preferredQualityIndex.value = index
        return index
    }

    fun setQualityPreference(index: Int) {
        if (index !in qualityApiLevels.indices) return
        val prefs = getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
        prefs.edit()
            .putInt(if (isOnWifi()) "wifi_quality" else "mobile_quality", index)
            .apply()
        onQualityPreferenceChanged()
    }

    private fun isOnWifi(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    fun playSong(
        songId: Long,
        title: String = "",
        artist: String = "",
        artworkUrl: String = "",
        quality: String = "",
        // B4 续播：-1 = 自动（读这首歌的进度记录）；>= 0 = 显式指定起播位置。
        // 显式传值的唯一场景是「播放失败降档重试」—— 那时必须沿用**当前**进度，
        // 不能退回记录里的旧位置，否则听感上会倒退一截。
        startPositionMs: Long = -1L,
    ) {
        songPlayVersion++
        val fetchVersion = songPlayVersion
        latestPlaySongId = songId
        needsPreload.value = false
        refreshGaplessSetting()

        val prefs = getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
        val selectedQuality = if (quality.isNotEmpty()) quality else effectivePreferredLevel(prefs)
        val qIdx = qualityApiLevels.indexOf(selectedQuality).coerceAtLeast(0)
        lastRequestedLevel = selectedQuality

        // B4：决定这首歌的起播位置。没有记录（或已播完被清除）就是 0 —— 即
        // 「每首歌自动从 0 分 0 秒播放对齐」；有记录则从上次退出点续播。
        val resumeMs = if (startPositionMs >= 0) startPositionMs
        else PlaybackStateManager.getSongPosition(getApplication(), songId)
        lastSavedPositionMs = resumeMs
        resumedFromMs.value = resumeMs
        if (resumeMs > 0) {
            // GUI 提示：明确告诉用户这是「从上次退出的时间点续播」，
            // 而不是进度条/歌词出了错（B4）。
            val app = getApplication<Application>()
            val text = stringsForCode(getSavedLanguageCode(app)).resumeFromFormat(formatDuration(resumeMs))
            Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
        }
        // 显式传入 quality 的调用来自「播放失败降档重试」，那不是用户偏好，不能覆盖偏好档位。
        if (quality.isEmpty()) preferredQualityIndex.value = qIdx
        currentQualityIndex.value = qIdx
        qualityStatus.value = QualityStatus.NORMAL

        // Fast path: URL was preloaded and cached for THIS requested level — skip network round-trip.
        // 缓存条目带档位:播放失败降档重试时,绝不会把上一档(可能已证明播不出声)的 URL 原样喂回。
        val cachedEntry = preloadCache[songId]?.takeIf {
            it.requestedLevel == selectedQuality && System.currentTimeMillis() - it.timestamp <= CACHE_TTL_MS
        }
        if (cachedEntry != null) {
            clearPreloadedState()
            applyQualityVerdict(
                selectedQuality,
                SongUrlResult(
                    cachedEntry.url, cachedEntry.actualLevel,
                    cachedEntry.br, cachedEntry.type, cachedEntry.songMaxLevel,
                ),
            )
            resetLyricsForNewSong()
            currentSongId.value = songId
            currentSongName.value = title
            currentSongArtist.value = artist
            currentSongArtwork.value = artworkUrl
            val intent = Intent(getApplication(), PlaybackService::class.java).apply {
                putExtra("url", cachedEntry.url)
                putExtra("title", title)
                putExtra("artist", artist)
                putExtra("artwork", artworkUrl)
                putExtra("songId", songId)
                putExtra("startPositionMs", resumeMs)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                getApplication<Application>().startForegroundService(intent)
            else
                getApplication<Application>().startService(intent)
            isPlaying.value = true
            viewModelScope.launch { fetchLyrics(songId) }
            PlaybackStateManager.saveState(getApplication(), songId, title, artist, artworkUrl, true)
            return
        }

        playJob?.cancel()
        playJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                var result = SongUrlFetcher.fetch(songId, selectedQuality)
                if (result == null) {
                    // v1.6.0 · D1：取链失败（典型是断网）时先看离线缓存 —— 只要这台设备**真的
                    // 播过**这首歌，就用「最后一次成功播放的 URL + 本地音频片段」起播，
                    // 全程不需要网络。缓存里没有就什么都不做，绝不用旧 URL 去赌。
                    result = recallOfflineCache(songId, selectedQuality)
                }
                if (result == null) {
                    // 该歌在所有音质档位都取不到可播放的 URL（无版权 / 需会员且当前无订阅）。
                    // 前一个版本会兜底喂给 ExoPlayer 一个 404 的 HTML 链接导致无限缓冲"卡住"，
                    // 现在改成交由 MainScreen 跳下一首，绝不播放坏链接。
                    Log.w("PlayerViewModel", "no playable url for songId=$songId, skipping")
                    withContext(Dispatchers.Main) { onUnplayableCallback?.invoke() }
                    return@launch
                }
                // 取链期间若有更新的 playSong / 预载接管发生(版本号已前进),
                // 本次结果作废: 再发一次 "url" intent 会让 ExoPlayer setMediaItem
                // 把同一首歌重播一遍 —— 就是"听起来像拖带"的卡顿。
                if (fetchVersion != songPlayVersion) return@launch
                // setMediaItem 会替换整个播放列表，槽位随之作废。
                clearPreloadedState()
                resetLyricsForNewSong()
                // 歌词请求异步化: 旧实现在这里顺序等待(失败退避最坏 3s+),
                // 开播被歌词请求拖住, 慢网络/风控下"点了没反应"。切到 launch 后
                // 播放立即开始, 歌词就绪了再自动切回歌词视图。
                viewModelScope.launch { fetchLyrics(songId) }
                withContext(Dispatchers.Main) {
                    applyQualityVerdict(selectedQuality, result)
                    currentSongId.value = songId
                    currentSongName.value = title
                    currentSongArtist.value = artist
                    currentSongArtwork.value = artworkUrl

                    val intent = Intent(getApplication(), PlaybackService::class.java).apply {
                        putExtra("url", result.url)
                        putExtra("title", title)
                        putExtra("artist", artist)
                        putExtra("artwork", artworkUrl)
                        putExtra("songId", songId)
                        putExtra("startPositionMs", resumeMs)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        getApplication<Application>().startForegroundService(intent)
                    else
                        getApplication<Application>().startService(intent)
                    isPlaying.value = true
                }
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "fetchUrl failed", e)
            }
        }
    }

    /**
     * v1.6.0 · D1：离线兜底取链。
     *
     * 只有「离线 URL 清单里有这首歌」**且**「本地音频缓存里确实有对应片段」时才返回结果 ——
     * 两个条件缺一不可：只满足前者会拿一个指向空缓存的过期 URL 去起播，退化成网络
     * 403/404 的无限缓冲（正是 v1.3.0 明确禁止的那类坏链接）。
     *
     * 档位不严格要求一致（[OfflineUrlStore.recall] 会退化成「这首歌的任意档位」）：
     * 离线时能把这歌放出来，比死守用户选的档位重要得多。
     */
    private fun recallOfflineCache(songId: Long, level: String): SongUrlResult? {
        val app = getApplication<Application>()
        val hit = OfflineUrlStore.recall(app, songId, listOf(level)) ?: return null
        if (!OfflineAudioCache.contains(app, hit.first)) return null
        Log.i("PlayerViewModel", "offline cache hit songId=$songId key=" + hit.first)
        return SongUrlResult(hit.second, OfflineKeys.levelOf(hit.first) ?: level, 0L, "", null)
    }

    /**
     * 播放出错(如设备解码器不认 24-bit FLAC / 高采样率,或拿到坏链接)时的降档重试。
     * ExoPlayer 主线程回调;每出错一次沿 qualityRetryLadder 降一档重新取链播放,
     * 到 standard 仍失败才交由 MainScreen 跳歌。同一 (songId@level) 3 秒内只处理一次,
     * 防止解码器反复报错触发重试风暴。
     */
    private fun handlePlaybackError(songId: Long) {
        if (songId <= 0 || songId != currentSongId.value) return
        val level = lastPlayedLevel.ifEmpty {
            qualityApiLevels.getOrElse(currentQualityIndex.value) { "lossless" }
        }
        val key = "${songId}@$level"
        val now = System.currentTimeMillis()
        if (key == lastErrorKey && now - lastErrorHandledAt < 3_000L) return
        lastErrorKey = key
        lastErrorHandledAt = now

        val idx = qualityRetryLadder.indexOf(level)
        val nextLevel = when {
            idx in 0 until qualityRetryLadder.size - 1 -> qualityRetryLadder[idx + 1]
            // 服务端可能返回阶梯之外的档位(如 sky/jymaster),从无损起往下试,不能直接跳歌。
            idx < 0 -> "lossless"
            else -> null // 已是 standard,无档可降
        }
        if (nextLevel == null) {
            Log.w("PlayerViewModel", "lowest tier also failed for songId=$songId, skipping")
            onUnplayableCallback?.invoke()
            return
        }
        Log.w("PlayerViewModel", "playback error at level=$level for songId=$songId, retrying at $nextLevel")
        playSong(
            songId,
            title = currentSongName.value ?: "",
            artist = currentSongArtist.value ?: "",
            artworkUrl = currentSongArtwork.value ?: "",
            quality = nextLevel,
            // 降档重试必须从**当前**进度接着播，不能读进度记录（那是上一次退出的位置）。
            startPositionMs = currentPosition.value
        )
    }

    /**
     * 清空「待播槽位」的本地镜像（preloaded*），与 PlaybackService.clearPendingNext 成对。
     *
     * 任何一次**替换 ExoPlayer 播放列表**的开播动作都必须调用它（playSong 两条路径、
     * 预载接管、stopService）。否则残留的 preloadedSongId 会同时造成两个后果：
     *  1. preloadNextSong 误判「这首已经在槽位里」而不再预载 → 无缝播放静默失效；
     *  2. onSongTransitioned 把一首并不在播放列表里的歌当成已切歌 → 串台。
     */
    private fun clearPreloadedState() {
        preloadedSongId = -1L
        preloadedTitle = ""
        preloadedArtist = ""
        preloadedArtwork = ""
        preloadedResult = null
        preloadedRequestedLevel = ""
        preloadedUrl = ""
    }

    fun preloadNextSong(songId: Long, title: String, artist: String, artworkUrl: String, allowCurrent: Boolean = false) {
        // Dedup: skip only if the SAME song is already being fetched/in queue.
        // 不能因 URL 已缓存而整体跳过——缓存意味着"省的再取链", 但下一首仍需
        // addMediaItem 入 ExoPlayer 队列才能无缝切换; 否则缓存命中时直接 return,
        // ExoPlayer 队列永远只有当前一首, 播完必然走 songEnded→playNext 硬切(= 无缝失效)。
        if (currentlyPreloadingSongId == songId) return
        // 把当前正在播的歌再入队(除单曲循环由 MainScreen 显式 allowCurrent 外)——
        // 队尾回绕/单曲队列等边界会让 [A,A] 自动过渡成"假单曲循环"。
        if (!allowCurrent && songId == currentSongId.value && songId > 0) return
        // v1.5.2 串台修复：这一首已经在待播槽位里 → 幂等跳过，绝不再发一次 preload_next。
        // preloadedSongId 是槽位的本地镜像，被消费（onSongTransitioned）或播放列表被替换
        // （clearPreloadedState）时清空。19f2969 拆掉上游「URL 已缓存就整体 return」之后，
        // 同一首下一曲会在「切歌瞬间」和「进入最后 60s」各预载一次，第二次会把同一个
        // media item 再 addMediaItem 一遍，播放列表变成 [当前, 下一首, 下一首']。
        if (songId > 0 && songId == preloadedSongId) return

        val capturedVersion = songPlayVersion
        preloadJob?.cancel()
        currentlyPreloadingSongId = songId
        preloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
                if (!prefs.getBoolean("gapless_playback", true)) {
                    currentlyPreloadingSongId = -1L
                    return@launch
                }
                // v1.9.0：顺手预取**下一首**的 TTML 歌词。这里是「下一首是谁」唯一的确定入口
                // （MainScreen 在切歌瞬间与进入最后 60s 各调一次，重复调用已被上面的槽位判断
                // 挡掉），所以挂在这里最准。单独 launch：预取是为了省掉切歌时的等待，
                // 绝不能反过来挤占取链、把播放拖慢；拿不到下一首时根本不会走到这里。
                if (lyricsTtmlEnabled.value) {
                    launch { AmllTtmlClient.prefetch(getApplication(), songId) }
                }
                val quality = if (isOnWifi())
                    qualityApiLevels.getOrElse(prefs.getInt("wifi_quality", 3)) { "lossless" }
                else
                    qualityApiLevels.getOrElse(prefs.getInt("mobile_quality", 1)) { "higher" }
                // 缓存命中(同档 + TTL 内)则跳过网络, 但仍走下方入队路径。
                val cacheHit = preloadCache[songId]?.takeIf {
                    it.requestedLevel == quality &&
                        System.currentTimeMillis() - it.timestamp <= CACHE_TTL_MS
                }
                val result = cacheHit?.let {
                    SongUrlResult(it.url, it.actualLevel, it.br, it.type, it.songMaxLevel)
                } ?: SongUrlFetcher.fetch(songId, quality)
                if (result == null) {
                    // 预加载失败：可能无版权/无订阅，忽略即可，等当前歌结束时由 songEnded 跳歌。
                    currentlyPreloadingSongId = -1L
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    currentlyPreloadingSongId = -1L
                    // Store in cache regardless of staleness — URL is valid even if a new song started.
                    preloadCache[songId] = PreloadCacheEntry(
                        result.url, result.actualLevel, quality, result.br, result.type, result.songMaxLevel,
                    )
                    if (capturedVersion != songPlayVersion) {
                        // playSong was called while this fetch was in flight.
                        // If it was for THIS same song and hasn't completed its own fetch, take over.
                        if (songId == latestPlaySongId && currentSongId.value != songId) {
                            // 接管 = 一次新的开播动作: 版本号前进, 让并发 playSong 的
                            // 取链结果在 fetchVersion 检查处作废, 杜绝二次 setMediaItem 重播。
                            songPlayVersion++
                            playJob?.cancel()
                            lastRequestedLevel = quality
                            applyQualityVerdict(quality, result)
                            // 接管同样是一次替换播放列表的开播，槽位镜像必须一并作废。
                            clearPreloadedState()
                            resetLyricsForNewSong()
                            currentSongId.value = songId
                            currentSongName.value = title
                            currentSongArtist.value = artist
                            currentSongArtwork.value = artworkUrl
                            val intent = Intent(getApplication(), PlaybackService::class.java).apply {
                                putExtra("url", result.url)
                                putExtra("title", title)
                                putExtra("artist", artist)
                                putExtra("artwork", artworkUrl)
                                putExtra("songId", songId)
                                // 预载接管同样遵守续播语义（这次接管就是一次开播动作）。
                                putExtra(
                                    "startPositionMs",
                                    PlaybackStateManager.getSongPosition(getApplication(), songId)
                                )
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                getApplication<Application>().startForegroundService(intent)
                            else
                                getApplication<Application>().startService(intent)
                            isPlaying.value = true
                            viewModelScope.launch { fetchLyrics(songId) }
                            PlaybackStateManager.saveState(getApplication(), songId, title, artist, artworkUrl, true)
                        }
                        return@withContext
                    }
                    // Normal path: add to ExoPlayer queue for gapless auto-transition.
                    preloadedSongId = songId
                    preloadedTitle = title
                    preloadedArtist = artist
                    preloadedArtwork = artworkUrl
                    preloadedResult = result
                    preloadedRequestedLevel = quality
                    preloadedUrl = result.url
                    val intent = Intent(getApplication(), PlaybackService::class.java).apply {
                        putExtra("action", "preload_next")
                        putExtra("url", result.url)
                        putExtra("title", title)
                        putExtra("artist", artist)
                        putExtra("artwork", artworkUrl)
                        putExtra("songId", songId)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        getApplication<Application>().startForegroundService(intent)
                    else
                        getApplication<Application>().startService(intent)
                    Log.d("PlayerViewModel", "Preload enqueued: $title")
                }
            } catch (e: Exception) {
                currentlyPreloadingSongId = -1L
                Log.w("PlayerViewModel", "Preload failed for songId=$songId", e)
            }
        }
    }

    fun fetchLyricsForSong(songId: Long) {
        viewModelScope.launch { fetchLyrics(songId) }
    }

    /** v1.9.0：一次网易云歌词取数的结果。抽出来是为了让「缓存命中」与「网络返回」共用同一条源选择路径。 */
    private data class NeteaseLyrics(
        val lrc: String,
        val tlyric: String,
        val yrc: String,
        /** v1.9.2：音译轨原文（romalrc）。与 rv 参数无关，实测 rv=0 也会返回，缺 key 时为空串。 */
        val romalrc: String,
        /** 服务端 code==200 的**权威**答复（有歌词或确无歌词）；false = 瞬时失败，不能判成「确无歌词」。 */
        val authoritative: Boolean,
    )

    /** 网易云一侧解析好的三条轨（主轨 + 译文 + 音译），一次解析、两相共用。 */
    private data class NeteaseTracks(
        val lines: List<LrcLine> = emptyList(),
        val tlyric: List<LrcLine> = emptyList(),
        val romalrc: List<LrcLine> = emptyList(),
    )

    /** TTML 胜出时算好的两条副文本轨，连同它们的来源标记一起交给落地函数。 */
    private data class LyricTracks(
        val translation: LyricTrack = LyricTrack(),
        val roman: LyricTrack = LyricTrack(),
    )

    /** 日志用：「来源/行数」。 */
    private fun trackText(track: LyricTrack): String =
        (track.source?.cacheTag ?: "none") + "/" + track.lines.size

    private suspend fun fetchLyrics(songId: Long) {
        // 去重必须排在 begin() 之前：同歌并发时第二次直接返回；若先 begin()，第二次会把第一次
        // 的号作废 —— 第一次的结果整包丢弃、第二次又不干活，歌词反而永远出不来。
        if (lyricsFetchingSongId == songId) return
        // v1.9.0：领号。此后每个挂起点之后都要问一次「我还是当前号吗」，不是就整包丢弃；
        // 这是「A 歌请求还在飞、用户已切到 B 歌」时不把 A 的歌词盖到 B 上的唯一保证。
        val seq = lyricReqGate.begin()
        lyricsFetchingSongId = songId
        lyricsLoading.value = true
        try {
            val netease = loadNeteaseLyrics(songId, seq)
            if (!lyricReqGate.isCurrent(seq)) return
            // 瞬时失败（风控 / 需登录 / 断网）：保持既有行为 —— 什么都不写、按钮保持可点可重试，
            // 也**不**去拉 TTML（拉 TTML 的前提是「已经拿到网易云的权威答复」）。
            if (!netease.authoritative) return
            applyBestLyricSource(songId, seq, netease)
        } finally {
            // 只有本次请求仍是"当前在途"时才清加载态。旧歌请求的 finally 若在
            // 新歌请求在途时无条件清 lyricsLoading=false, 会让 UI 误判新歌"加载已结束
            // 且无歌词"→ 关歌词回封面, 表现为"听几首后歌词莫名不见"。
            if (lyricsFetchingSongId == songId && lyricReqGate.isCurrent(seq)) {
                lyricsLoading.value = false
                lyricsFetchingSongId = -1L
            }
        }
    }

    /**
     * 取网易云那份歌词：先缓存、后网络（含既有 4 次退避重试）。
     *
     * 缓存命中也不在这里直接落地：TTML 开关是**播放期**策略，缓存里只有 LRC 原文，
     * 最终显示哪一份统一交给 [applyBestLyricSource] 用 [LyricSourceChain] 决定。
     */
    private suspend fun loadNeteaseLyrics(songId: Long, seq: Long): NeteaseLyrics {
        // 先查本地缓存：命中则不再打网络（歌词几乎不变）。
        // 这是进程被杀重进时歌词能秒回、且不受冷启动风控/限流影响的关键。
        val cached = LyricsCache.get(getApplication(), songId)
        if (!lyricReqGate.isCurrent(seq)) return STALE_NETEASE_LYRICS
        if (cached != null) {
            Log.d("PlayerViewModel", "fetchLyrics cache hit id=$songId lrc=${cached.lrc.length}")
            return NeteaseLyrics(
                cached.lrc, cached.tlyric, cached.yrc.orEmpty(), cached.romalrc.orEmpty(),
                authoritative = true
            )
        }
        // 失败重试(最多 4 次, 递增退避): 冷启动时 AppWarmup 与恢复请求同时在
        // 打网络, 歌词请求的瞬时超时/限流不该让歌词永久消失。关键是**响应层面的
        // 失败也要重试** —— 服务端风控(-460/-462)或需登录(301)返回的 code!=200
        // 响应里 lrc 为空, 旧实现当成"这首歌没歌词"直接结束, 用户必须切歌才能
        // 重新触发加载; 这些失败码是瞬时的, 退避重试大概率能拿到真歌词。
        // code==200 是服务端的权威答复(有歌词或无歌词), 不再重试。
        repeat(4) { attempt ->
            try {
                val lyricResponse = RetrofitClient.api.getLyric(id = songId)
                // 已经切歌了就别再解析/重试：这一整轮请求的结果对当前歌没有任何意义。
                if (!lyricReqGate.isCurrent(seq)) return STALE_NETEASE_LYRICS
                val code = lyricResponse.code
                val lrcText = lyricResponse.lrc?.lyric ?: ""
                val tlyricText = lyricResponse.tlyric?.lyric ?: ""
                // v1.5.0 · B：逐字时间轴。实测该曲没挂逐字资产时响应里连 yrc 这个 key
                // 都没有(不是 null)，所以这里为空是常态，不是错误。
                val yrcText = lyricResponse.yrc?.lyric ?: ""
                // v1.9.2：音译轨。同一个响应里本来就有这个字段（实测与 rv 取值无关），
                // 此前一直没有解析，于是「TTML 胜出」时音译轨只能是 TTML 那一份（多半为空）。
                val romalrcText = lyricResponse.romalrc?.lyric ?: ""
                if (code != 200 && attempt < 3) {
                    Log.w("PlayerViewModel", "fetchLyrics unsettled songId=$songId code=$code, retry ${attempt + 1}")
                    delay(700L + attempt * 400L)
                    if (!lyricReqGate.isCurrent(seq)) return STALE_NETEASE_LYRICS
                    return@repeat
                }
                // 只缓存 code==200 的权威结果(有歌词/确无歌词)。code!=200 是瞬时
                // 失败(风控/需登录), 不写缓存也不置"确无歌词", 按钮保持可点可重试。
                if (code == 200) {
                    LyricsCache.put(
                        getApplication(), songId, lrcText, tlyricText, yrcText, romalrcText
                    )
                }
                Log.d(
                    "PlayerViewModel",
                    "fetchLyrics id=$songId code=$code lrc=${lrcText.length} " +
                        "tlyric=${tlyricText.length} yrc=${yrcText.length} " +
                        "romalrc=${romalrcText.length} current=${currentSongId.value}"
                )
                return NeteaseLyrics(
                    lrcText, tlyricText, yrcText, romalrcText, authoritative = code == 200
                )
            } catch (e: Exception) {
                // 失败不清空已有歌词(网络抖动不该把 UI 变空白), 重试后仍失败才退出
                if (attempt == 3) {
                    Log.e("PlayerViewModel", "fetchLyrics failed for songId=$songId", e)
                    return STALE_NETEASE_LYRICS
                }
                delay(700L + attempt * 400L)
                if (!lyricReqGate.isCurrent(seq)) return STALE_NETEASE_LYRICS
            }
        }
        return STALE_NETEASE_LYRICS
    }

    /**
     * v1.9.0：按 [LyricSourceChain] 选源并落地。分两相，为的是**不让 TTML 拖住既有 LRC 的显示**：
     *
     *  1. 第一相只用网易云的候选（YRC / LRC）决策，选中立刻显示 —— 显示时机与 v1.8.1 完全一致；
     *  2. 第二相在「用户开了 TTML」时拉一次 TTML，连同第一相的候选重新 [LyricSourceChain.pick]
     *     一次，TTML 赢了才替换。TTML 是第三方镜像（最坏要跑 4 面镜子、每面 connect 5s），
     *     把它放在第一相之前，会把「本来就有歌词」变成「一直转圈」，那是对既有行为的倒退。
     *
     * 失败一律静默：拉不到 / 解析不出 / 没有逐字 span，都保留第一相的结果，不弹错、不新增 Toast。
     */
    private suspend fun applyBestLyricSource(songId: Long, seq: Long, netease: NeteaseLyrics) {
        val sourcePrefs = LyricSourcePrefs(
            ttmlEnabled = lyricsTtmlEnabled.value,
            ttmlFirst = lyricsTtmlFirst.value,
        )
        val order = LyricSourceChain.order(sourcePrefs)
        // 解析是 CPU 活(逐行正则), 放 Default 上跑, 别让主线程在切歌瞬间一边处理重组一边解 LRC。
        // v1.9.2：网易云三条轨（主轨 + 译文 + 音译）在**同一次** withContext 里解析完，
        // 第一相与第二相共用同一份结果 —— 合并只发生在一次 fetch 内部，不会把两次请求的数据拼起来。
        val parsed = if (netease.lrc.isNotEmpty()) {
            withContext(Dispatchers.Default) {
                NeteaseTracks(
                    lines = parseLyrics(netease.lrc, netease.yrc),
                    tlyric = if (netease.tlyric.isEmpty()) emptyList() else LrcParser.parse(netease.tlyric),
                    romalrc = if (netease.romalrc.isEmpty()) emptyList() else LrcParser.parse(netease.romalrc),
                )
            }
        } else {
            NeteaseTracks()
        }
        if (!lyricReqGate.isCurrent(seq)) return
        val neteaseHasWords = parsed.lines.any { it.words.isNotEmpty() }

        fun candidate(kind: LyricSourceKind, ttml: TtmlDoc?): LyricCandidate? = when (kind) {
            // YRC 与 LRC 都来自同一份网易云响应：行文本是同一份，差别只在有没有逐字时间轴。
            LyricSourceKind.YRC -> parsed.lines.takeIf { it.isNotEmpty() }
                ?.let { LyricCandidate(kind, it.size, neteaseHasWords) }
            LyricSourceKind.LRC -> parsed.lines.takeIf { it.isNotEmpty() }
                ?.let { LyricCandidate(kind, it.size, false) }
            LyricSourceKind.TTML -> ttml?.let {
                // hasWordLevel 必须由解析器判「有没有词」——只有整句的 TTML 投稿
                // 用它替换 LRC 只会白白丢掉网易云的行级数据。
                LyricCandidate(kind, it.lines.size, TtmlParser.hasWordLevel(it))
            }
        }

        // 第一相：网易云的候选按 order 排好（TTML 先缺席）交给 pick，选中就落地显示。
        val neteasePick = LyricSourceChain.pick(
            order.filter { it != LyricSourceKind.TTML }.mapNotNull { candidate(it, null) }
        )
        if (neteasePick != null) {
            applyNeteaseLyrics(songId, seq, parsed.lines, parsed.tlyric, parsed.romalrc)
            // v1.9.1：把「最终用了哪个源」显式打出来。此前只能从网络请求侧反推，
            // 独立验证者因此把「优先级开关是否真的改变选中源」列为未验证项（U3）——
            // 两相结构下「有没有发 TTML 请求」推不出「最后显示的是谁」。
            Log.i(
                "PlayerViewModel",
                "歌词源 songId=$songId phase=1 picked=${neteasePick.kind} " +
                    "lines=${neteasePick.lineCount} words=${neteasePick.hasWordLevel}"
            )
            // 歌词已经落到 StateFlow：先把加载态收掉，UI 才能立刻显示它（lyricsReady 要求
            // !lyricsLoading）。第二相只是「再挑一次、择优升级」，不该让它继续转圈。
            if (lyricReqGate.isCurrent(seq) && currentSongId.value == songId) {
                lyricsLoading.value = false
            }
        }

        // 第二相：只有用户开了 TTML 才发请求；关掉就一个字节都不拉（隐私开关不能形同虚设）。
        var ttmlWon = false
        if (sourcePrefs.ttmlEnabled && currentSongId.value == songId) {
            // AmllTtmlClient 自己吞掉网络异常并返回 null；这里的 runCatching 只是兜底
            // 「绝不让补充源把播放路径搞崩」—— 失败静默，走上面那一相的结果。
            val raw = runCatching { AmllTtmlClient.load(getApplication(), songId) }.getOrNull()
            if (!lyricReqGate.isCurrent(seq)) return
            val doc = if (raw.isNullOrBlank()) {
                null
            } else {
                withContext(Dispatchers.Default) { TtmlParser.parse(raw) }
            }
            if (!lyricReqGate.isCurrent(seq)) return
            if (doc != null) {
                val picked = LyricSourceChain.pick(order.mapNotNull { candidate(it, doc) })
                if (picked?.kind == LyricSourceKind.TTML) {
                    // v1.9.2：两条副文本轨在这里一次算完（纯函数，放 Default 上跑 —— 里面有一次
                    // O(n·m) 的 LCS）。withContext 是挂起点，回来必须复查闸门与当前歌。
                    val tracks = withContext(Dispatchers.Default) {
                        LyricTracks(
                            translation = LyricTrackMerge.merge(
                                doc.lines, doc.translations, parsed.lines, parsed.tlyric
                            ),
                            roman = LyricTrackMerge.merge(
                                doc.lines, doc.romans, parsed.lines, parsed.romalrc
                            ),
                        )
                    }
                    if (!lyricReqGate.isCurrent(seq) || currentSongId.value != songId) return
                    applyTtmlLyrics(songId, doc, tracks)
                    ttmlWon = true
                    Log.i(
                        "PlayerViewModel",
                        "歌词源 songId=$songId phase=2 picked=TTML " +
                            "lines=${doc.lines.size} words=${TtmlParser.hasWordLevel(doc)} " +
                            "轨道 translation=${trackText(tracks.translation)} " +
                            "roman=${trackText(tracks.roman)} (覆盖了 phase=1)"
                    )
                } else {
                    // 拉了 TTML 但没赢（没有逐字 span / 排序后被 YRC 压过）—— 显式记一行，
                    // 否则「用户开了 TTML 却仍是整行」在线上无法归因。
                    Log.i(
                        "PlayerViewModel",
                        "歌词源 songId=$songId phase=2 picked=${picked?.kind} " +
                            "(TTML 未胜出，保留 phase=1)"
                    )
                }
            }
        }

        // 两个源都没得用，才是「确无歌词」：与既有语义一致，置灰歌词按钮。
        if (!ttmlWon && neteasePick == null &&
            lyricReqGate.isCurrent(seq) && currentSongId.value == songId
        ) {
            lyricsNoContentSongId.value = songId
            translatedLyrics.value = emptyList()
            romanizedLyrics.value = emptyList()
        }
    }

    /**
     * 把网易云那份落地：行来自 lrc（有 yrc 时已挂上逐字），译文来自 tlyric，音译来自 romalrc。
     *
     * 三条轨的解析已经在 [applyBestLyricSource] 里（Default 线程上）做完，这里只写状态。
     * 网易云源的两条副文本轨**按时间戳与原行配对**是既有语义（tlyric/romalrc 与 lrc 是同一份资产、
     * 时间戳同刻），v1.5.0 起就是这么显示的，本版一行不改 —— 分轨合并只在 TTML 胜出时介入。
     */
    private suspend fun applyNeteaseLyrics(
        songId: Long,
        seq: Long,
        lines: List<LrcLine>,
        translations: List<LrcLine>,
        romans: List<LrcLine>,
    ) {
        // 状态写入前必须重新确认「仍是当前号 + 仍是当前歌」。
        if (!lyricReqGate.isCurrent(seq) || currentSongId.value != songId) return
        if (lines.isNotEmpty()) {
            lyrics.value = lines
            // 有歌词：标记为"当前歌的歌词就绪"（供 UI 自动回切歌词视图）
            lyricsSongId.value = songId
            lyricsNoContentSongId.value = -1L
        }
        translatedLyrics.value = translations
        romanizedLyrics.value = romans
        // v1.9.2：记下这两条轨实际来自谁（观测字段；值未变则不落盘）。
        LyricsCache.putTrackSources(
            getApplication(),
            songId,
            LyricTrackSource.NETEASE.takeIf { translations.isNotEmpty() },
            LyricTrackSource.NETEASE.takeIf { romans.isNotEmpty() },
        )
    }

    /**
     * 把 TTML 那份落地（v1.9.2：分轨合并）。
     *
     * 主轨是 TTML 的行；译文轨与音译轨各自独立决定（[LyricTrackMerge]）：
     * TTML 那一轨能对上主轨时间戳的行原样用，**缺的行**按文本/行序回退到网易云的 tlyric / romalrc，
     * 对不上的逐行丢弃。v1.9.0 是直接把 `doc.translations.filter { ... }` 覆盖上去 ——
     * TTML 没有译文时那是个空表，会把刚显示的网易云译文**整轨清空**（实测 22704409）。
     *
     * 渲染层按 timeMs 精确配对（translatedLyrics.associateBy { timeMs }），而合并产出的每一行
     * 时间戳都取自 [TtmlDoc.lines]，所以这里不需要动渲染层一行。
     */
    private suspend fun applyTtmlLyrics(songId: Long, doc: TtmlDoc, tracks: LyricTracks) {
        if (currentSongId.value != songId) return
        lyrics.value = doc.lines
        translatedLyrics.value = tracks.translation.lines
        romanizedLyrics.value = tracks.roman.lines
        lyricsSongId.value = songId
        lyricsNoContentSongId.value = -1L
        // v1.9.2：记下两条轨实际来自谁（观测字段；值未变则不落盘）。
        LyricsCache.putTrackSources(
            getApplication(), songId, tracks.translation.source, tracks.roman.source
        )
    }

    /**
     * v1.5.0 · B：统一的歌词解析入口。有 yrc 就把逐词时间轴挂到 LRC 行上，没有就只解 LRC。
     * [YrcParser.attachWords] 不会增删行、也不改行文本与时间戳 —— 关掉逐字开关后渲染结果
     * 与 v1.4.1 逐字节一致。
     */
    private fun parseLyrics(lrcText: String, yrcText: String): List<LrcLine> {
        val lines = LrcParser.parse(lrcText)
        return if (yrcText.isEmpty()) lines else YrcParser.attachWords(lines, yrcText)
    }

    /**
     * 手动重试当前歌歌词。请求失败后用户点歌词按钮可再次触发加载；
     * 清掉去重与"确无歌词"标记，强制重新请求（失败结果本就不入缓存）。
     */
    fun retryLyrics() {
        val songId = currentSongId.value ?: return
        if (songId <= 0) return
        lyricsNoContentSongId.value = -1L
        lyricsLoading.value = true
        lyricsFetchingSongId = -1L
        viewModelScope.launch { fetchLyrics(songId) }
    }

    /**
     * 只载入歌曲元数据, 不取链不播放(剪贴板分享的单曲场景)。
     * 之后用户按下播放键 → togglePlayPause 检测到 duration==0 且 songId>0,
     * 自动走全量 playSong 路径开播。
     */
    fun prepareSongWithoutPlay(songId: Long, title: String, artist: String, artworkUrl: String) {
        latestPlaySongId = songId
        resetLyricsForNewSong()
        currentSongId.value = songId
        currentSongName.value = title
        currentSongArtist.value = artist
        currentSongArtwork.value = artworkUrl
        currentPosition.value = 0L
        duration.value = 0L
        progress.value = 0f
        isPlaying.value = false
        viewModelScope.launch { fetchLyrics(songId) }
        PlaybackStateManager.saveState(
            getApplication(), songId, title, artist, artworkUrl, false
        )
    }

    fun togglePlayPause() {
        val songId = currentSongId.value
        // 只有"已载入但从未开播"的场景才走全量 playSong(剪贴板单曲/冷启动恢复态)。
        // duration==0 且当前曲正在播(如无缝过渡后心跳还没把 duration 同步回来)时
        // 若走 playSong 会重取 URL + setMediaItem, 把正在播的歌从 0 重播一遍=拖带感。
        if (duration.value == 0L && songId != null && songId > 0 && !isPlaying.value) {
            playSong(
                songId,
                title = currentSongName.value ?: "",
                artist = currentSongArtist.value ?: "",
                artworkUrl = currentSongArtwork.value ?: ""
            )
            return
        }
        isPlaying.value = !isPlaying.value
        val intent = Intent(getApplication(), PlaybackService::class.java).apply {
            putExtra("action", if (isPlaying.value) "resume" else "pause")
        }
        getApplication<Application>().startService(intent)
    }

    /** 仅暂停播放,保留当前歌曲与队列(LINE 模式队尾停用)。 */
    fun pausePlayback() {
        isPlaying.value = false
        val intent = Intent(getApplication(), PlaybackService::class.java).apply {
            putExtra("action", "pause")
        }
        getApplication<Application>().startService(intent)
    }

    fun seekTo(position: Long) {
        // 乐观更新：startService 是异步的，等 PlaybackService 回调会有可感知延迟；
        // 暂停态下 ticker 又不广播，不在这里更新就会"拖了没反应"。
        // 服务端 seek 完成后会再广播一次权威值，覆盖这里的估算。
        val dur = duration.value
        currentPosition.value = position
        if (dur > 0) progress.value = (position.toFloat() / dur.toFloat()).coerceIn(0f, 1f)

        val intent = Intent(getApplication(), PlaybackService::class.java).apply {
            putExtra("action", "seek")
            putExtra("position", position)
        }
        getApplication<Application>().startService(intent)
    }

    fun stopService() {
        val app = getApplication<Application>()
        PlaybackStateManager.clearState(app)
        PlaybackStateManager.clearQueue(app)
        // v1.9.0：退出播放器时作废所有在途的歌词请求，别让它们的响应落回已经清空的播放器状态。
        lyricReqGate.invalidate()

        val intent = Intent(app, PlaybackService::class.java).apply {
            putExtra("action", "stop")
        }
        app.startService(intent)
        isPlaying.value = false
        clearPreloadedState()
        resetLyricsForNewSong()
        currentSongId.value = null
        currentSongName.value = null
        currentSongArtist.value = null
        currentSongArtwork.value = null
    }

    override fun onCleared() {
        PlaybackService.onProgressUpdate = null
        PlaybackService.onPlaybackEnded = null
        PlaybackService.onPlaybackPrevious = null
        PlaybackService.onIsPlayingChanged = null
        PlaybackService.onSongTransitioned = null
        PlaybackService.onBufferingChanged = null
        PlaybackService.onCoverAccent = null
        PlaybackService.onPlaybackError = null
        super.onCleared()
    }
}
