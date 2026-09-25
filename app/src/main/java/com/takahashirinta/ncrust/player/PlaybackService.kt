/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明（B2 FFmpeg 集成）：
 *   - 挂载 DefaultRenderersFactory，扩展渲染器模式设为 ON：有平台解码器时仍走平台，
 *     没有时（API < 27 的 FLAC）才回退到随包分发的 FFmpeg 软件解码器。
 *   - B3-1：缓冲策略按物理内存分档，≤3.5GB 机型峰值缓冲减半。
 *   - B3-2：禁用流内嵌 ID3 元数据（封面等）解析，显示用的元数据全部来自 API。
 *   - B3-3：加入音频 offload 能力探测日志（刻意不启用，依据见 logAudioOffloadCapability）。
 *   - B4：playUrl 支持 startPositionMs，配合「每首歌进度记忆」实现断点续播；
 *     用 setMediaItem(item, pos) 而非 prepare 后 seekTo，保证歌词首帧即对齐。
 *   - v1.5.2 串台修复：待播槽位（PreloadSlot）至多一首预载项；预载项自带
 *     mediaId 与 MediaMetadata；onMediaItemTransition 加槽位守卫，宁可不更新 UI
 *     也不显示与耳朵不符的歌。 */

package com.takahashirinta.ncrust.player

import android.app.ActivityManager
import android.app.Notification
import android.media.AudioFormat
import android.media.AudioManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.takahashirinta.ncrust.BuildConfig
import com.takahashirinta.ncrust.cache.OfflineAudioCache
import com.takahashirinta.ncrust.cache.OfflineKeys
import com.takahashirinta.ncrust.cache.OfflineLibrary
import com.takahashirinta.ncrust.cache.OfflineUrlStore
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession as M3MediaSession
import androidx.palette.graphics.Palette
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.takahashirinta.ncrust.MainActivity
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.musicSource
import com.takahashirinta.ncrust.ui.i18n.getSavedLanguageCode
import com.takahashirinta.ncrust.ui.i18n.stringsForCode
import com.takahashirinta.ncrust.ui.player.VisualizerSetting
import kotlinx.coroutines.*

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    lateinit var player: ExoPlayer
    private var mediaSession: MediaLibrarySession? = null
    private var mediaSessionCompat: MediaSessionCompat? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressJob: Job? = null
    private var isServiceStarted = false
    private var currentArtworkUrl: String? = null
    /** v2.1.0 · C：当前媒体项的音源身份（车机/通知路径按它路由取链）。 */
    private var mediaSourceKey: String? = null
    private var mediaSourceId: String? = null
    private var mediaMediaId: String? = null
    private var currentArtworkBitmap: Bitmap? = null
    private var currentDominantColor: Int = 0xFF1DB954.toInt()
    // 封面加载代数: 每次 loadArtwork 自增, 完成时若代数已过期(期间又切了歌)
    // 则丢弃结果 —— 否则慢加载的上一首封面会覆盖新歌封面, 任务栏/锁屏
    // 显示上一首的图(切歌封面错位)。
    private var artworkGeneration = 0

    // v1.2.0 · B：暂停空闲后释放封面位图。3GB 机型上 currentArtworkBitmap +
    // pendingNextArtworkBitmap 两张全尺寸位图（各 1–4MB）叠加 B3 背景图后内存吃紧。
    // 系统已持有"已 post 出去的那条通知"里位图的独立副本，因此这里丢弃引用不会让
    // 已显示的通知变空；恢复播放时按 currentArtworkUrl 重新加载即可。
    private val artworkIdleHandler = Handler(Looper.getMainLooper())
    private var artworkReleaseRunnable: Runnable? = null
    private val ARTWORK_IDLE_RELEASE_MS = 45_000L

    /** 通知栏 / 锁屏封面只用到几百像素：统一降到这个边长，两张位图内存约为原来的 1/4。 */
    private val ARTWORK_MAX_PX = 512

    // B2-C：Palette 采样边长。取色只需判断"什么色"，112×112 足够，且与小图解码成本正相关。
    private val PALETTE_SAMPLE_PX = 112
    // 同一张封面不重复取色（URL 未变直接复用上次结果）。
    private var paletteUrl: String? = null
    private var paletteRgb: Int? = null
    private var paletteDominant: Int = 0xFF1DB954.toInt()

    companion object {
        // 车机浏览树节点 id。
        const val ROOT_ID = "ncrust_root"
        const val DAILY_ID = "ncrust_daily"
        const val FM_ID = "ncrust_fm"
        const val LIKED_ID = "ncrust_liked"

        var onProgressUpdate: ((Long, Long) -> Unit)? = null

        /**
         * v1.5.1 · D：当前**正在唱的那一行歌词**，由 PlayerViewModel 在「跨行」时写入
         * （null = 这首歌没歌词，或者用户没打开「媒体面板显示歌词」）。
         *
         * 为什么走 ARTIST：Android 13+ 的 SystemUI 媒体面板第二行**只读
         * METADATA_KEY_ARTIST**，`DISPLAY_SUBTITLE` 被完全忽略（AOSP 源码级结论，见 TASK.md
         * 的调研报告）；把歌词塞进 SUBTITLE 在现代系统上等于没写。所以开启后 ARTIST 变成
         * 「艺人 · 当前歌词行」，并**同时**写 SUBTITLE/DISPLAY_SUBTITLE 兜住老车机与蓝牙路径；
         * 关闭或没有歌词时这里保持 null，ARTIST 一字不变。
         */
        @Volatile var mediaLyricLine: String? = null
            set(value) {
                if (field == value) return
                field = value
                // v2.0.2：跨行时重 post 通知，**所有 API 版本都发**（v1.8.0 的 `SDK_INT < P`
                // 闸门已被真机推翻，见 LyricNotifyGate 的完整论证）。
                // 为什么必须挂在这里：媒体通知的正文是**应用 post 时**烘进去的，
                // 系统只在「通知被 post」的那一刻读一次会话 metadata，之后不轮询
                // —— 不重发就等于不刷新。字段是唯一的变化点，散在 ViewModel 里迟早会漏。
                instance?.onMediaLyricLineChanged(value)
            }
        var onPlaybackEnded: (() -> Unit)? = null
        var onPlaybackPrevious: (() -> Unit)? = null
        var onIsPlayingChanged: ((Boolean) -> Unit)? = null
        // Fired on the main thread when ExoPlayer reports a playback error (decode/source
        // failure). Carries the song id ExoPlayer was on; the ViewModel downgrades quality
        // and retries, so a device that can't decode e.g. 24-bit FLAC still gets sound.
        var onPlaybackError: ((Long) -> Unit)? = null
        // Fired on the main thread when ExoPlayer auto-transitions to a preloaded next item.
        var onSongTransitioned: (() -> Unit)? = null
        /**
         * v2.1.5：自动接续时把**真正起播那一项的身份**交给 ViewModel。
         *
         * 为什么单独一个回调而不是塞进 [onSongTransitioned]：那个回调是 v1.5.2 的既有契约
         * （`() -> Unit`），而身份必须带载荷（QQ 的 songmid / media_mid）。
         * 两者语义不同 —— 「切歌了」与「切到了哪一首」—— 分开后各自的调用方都读得懂。
         *
         * 参数：`(source, songId, sourceId, mediaId)`。
         * `songId <= 0` 表示这次 transition 没解析出可用身份（老形状 / 车机插入项），
         * 调用方应保持既有状态、不要拿它去取词。
         */
        var onTrackTransitioned: ((MusicSource, Long, String?, String?) -> Unit)? = null
        var onBufferingChanged: ((Boolean) -> Unit)? = null
        // B2-C：封面 Palette 提取出的主题色（ARGB）。null = 当前歌无封面，UI 回落预设色。
        // 由 PlaybackService 静态回调推给 PlayerViewModel.coverAccentRgb。
        var onCoverAccent: ((Int?) -> Unit)? = null
        var mediaTitle: String = "Ncrust"
        var mediaArtist: String = ""
        var mediaSongId: Long? = null
        var instance: PlaybackService? = null

        /**
         * v2.0.2：跨行重 post 的限流/去重判定已收敛进 [LyricNotifyGate]（纯逻辑 + JVM 单测）。
         *
         * v1.8.0 · T5 里那个 `LYRIC_NOTIFY_MIN_INTERVAL_MS = 250L` 挪到
         * [LyricNotifyGate.MIN_INTERVAL_MS]，语义也从「超过就丢弃」改成「超过就延后重试」。
         */

        /** v1.8.0 · T5：探针 tag（debug 包用来看"到底 post 了几次"）。 */
        private const val TAG_LYRIC_NOTIFY = "NcrustLyricNotify"

        /**
         * v2.1.5 · P1 探针 tag：媒体面板（MediaSession metadata）到底发布了什么。
         *
         * ## 为什么需要它
         *
         * 「控制中心媒体面板不显示歌词」这个问题在 adb 上原本**无法归因**：
         * `dumpsys media_session` 只打 `metadata:size=N, description=<title>,<artist>,<album>`，
         * 看不到 key 的**身份**，也看不到「最后更新时间 / 闸门计数 / 通知重建次数」。
         * 于是「应用从没发布过这一行」与「发布了但 ROM 不消费」在证据上长得一模一样。
         *
         * 本探针把这三件事一起打出来，判据就变成可执行的：
         * - `keys=` 里有没有我们写的 key ⇒ **应用侧发布是否发生**；
         * - `line=` / `title=` 是不是正在唱的那一行 ⇒ **内容是否正确**；
         * - `gate[...]` 的 post/defer/same 计数 ⇒ **更新策略有没有把它挡住**；
         * - 若以上全部正确而面板仍不显示，则结论只能是**ROM 侧不消费**（能力边界），
         *   而不是应用 bug —— 这正是 v2.1.5 对 P1 的结论所需要的证据形式。
         *
         * 只在 debug 包打印；release 里这些计数器只是几个 Int 自增，不进任何热路径循环。
         */
        private const val TAG_MEDIA_PANEL = "NcrustMediaPanel"
    }

    // Metadata staged for the next gapless transition.
    private var pendingNextTitle: String? = null
    private var pendingNextArtist: String? = null
    private var pendingNextArtwork: String? = null
    private var pendingNextSongId: Long = -1L
    /**
     * 待播槽位里那一项的 URL；**槽位是否被占用只看它**（见 [PreloadSlot]）。
     * preload 幂等判定与 transition 守卫都以「槽位里到底是哪一项」为准，
     * 不再靠 pendingNextTitle 是否为 null 这种间接信号。
     */
    private var pendingNextUrl: String? = null
    /**
     * v2.1.5：待播槽位的**音源身份**。
     *
     * 预载项的 mediaId 现在自带音源（见 [PreloadSlot.mediaIdFor]），所以 transition 时
     * 优先从 item 反解；这两项是**兜底**：老形状的 mediaId（`song:123` 且实际是 QQ 曲目）
     * 反解不出音源，那时只能信 ViewModel 随 preload_next 一起送来的这份。
     */
    private var pendingNextSource: MusicSource = MusicSource.DEFAULT
    private var pendingNextSourceId: String? = null
    private var pendingNextMediaId: String? = null
    // 无缝预载的下一首封面位图: preload_next 时提前加载, 切换瞬间直接应用,
    // 任务栏不会出现"新歌标题 + 上一首封面"的过渡窗口
    private var pendingNextArtworkBitmap: Bitmap? = null
    private var artworkPreloadGeneration = 0

    /** 低内存档阈值：≤3.5GB 视为低配（覆盖 2GB / 3GB 机型，4GB 及以上不降级）。 */
    private val LOW_RAM_TOTAL_BYTES = 3_500L * 1024 * 1024 * 1024

    /**
     * B3-3：音频 offload（硬件直通解码，低功耗路径）能力探测。
     *
     * **本版本刻意不启用**，依据：
     *  1) Media3 1.5.0 的 DefaultRenderersFactory **没有** setEnableAudioOffload
     *     （1.6+ 才提供）。要启用必须自建 DefaultAudioSink + AudioOffloadSupportProvider，
     *     改动面大且难以在无真机的情况下验证。
     *  2) offload 会绕过应用侧音频处理链，与本 App 的无缝预载 / 播放参数策略冲突，
     *     可能导致「无缝播放」退化 —— 那是本 App 的核心体验，不宜用它换省电。
     *  3) offload 只对平台原生支持的编码生效；本项目大量走 FFmpeg 软解（无损 FLAC），
     *     软解路径本来就不经过 offload。
     *
     * 因此这里只做能力探测并打日志，供在目标机型上评估后续是否需要单独开关。
     */
    private fun logAudioOffloadCapability() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.i("PlaybackService", "AudioOffload: unsupported (API < 29)")
            return
        }
        val supported = runCatching {
            // 注意：isOffloadedPlaybackSupported 是 AudioManager 的**静态**方法。
            AudioManager.isOffloadedPlaybackSupported(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_MP3)
                    .setSampleRate(44_100)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
                // 必须用 android.media.AudioAttributes —— 本文件已 import 了 media3 的同名类。
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
        }.getOrDefault(false)
        Log.i("PlaybackService", "AudioOffload: mp3 supported=" + supported + " (decision: disabled by design)")
    }

    /**
     * 按设备**物理内存**分档的缓冲策略（B3-1）。
     *
     * 背景：默认 LoadControl 在重缓冲后仅攒 5s 就续播，网络略慢于码率时会「播一点断一点」，
     * 所以此前把目标缓冲拉到了 30–60s。但峰值缓冲正是播放器常驻内存的主要来源：
     * 无损 FLAC 按 ~1000 kbps 估算约 125 KB/s，60s 峰值 ≈ 7.5 MB，30s ≈ 3.7 MB。
     *
     * 3 GB 机型（如三星 S6 G9209）在系统内存紧张时更早被回收，因此对低内存档把峰值缓冲
     * 减半；重缓冲续播阈值只从 15s 降到 12s —— 仍足以跨过弱网抖动，不至于退回
     * 「播一点断一点」。
     *
     * 判定用 totalMem 而非 isLowRamDevice：Android 只对 ≤1GB 机型置 low-ram 标志，
     * 3GB 机型不会被标记，达不到本次优化目标。
     */
    private fun buildLoadControl(): LoadControl {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val lowRamProfile = memInfo.totalMem in 1..LOW_RAM_TOTAL_BYTES
        Log.i(
            "PlaybackService",
            "LoadControl profile=" + (if (lowRamProfile) "low" else "default") +
                " totalMem=" + memInfo.totalMem / (1024 * 1024) + "MB"
        )
        val builder = DefaultLoadControl.Builder()
        return if (lowRamProfile) {
            // 低内存档：峰值缓冲 60s → 30s（常驻大致减半），续播阈值 15s → 12s。
            builder.setBufferDurationsMs(15_000, 30_000, 2_000, 12_000).build()
        } else {
            // 常规档：保持既有的弱网优化配置不变。
            builder.setBufferDurationsMs(30_000, 60_000, 2_500, 15_000).build()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d("PlaybackService", "onCreate")
        logAudioOffloadCapability()

        // 扩展渲染器模式 ON：优先用平台解码器（API 27+ 的 FLAC 走系统解码，省电），
        // 只有当平台没有任何解码器支持该格式时才回退到扩展里的 FFmpeg 软件解码器。
        // 这正是 API 24–26 播放无损 FLAC 所需要的路径。
        // v1.8.0 · T3：用子类替换裸的 DefaultRenderersFactory —— 唯一的差别是
        // override 了 buildAudioSink，在音频处理链最前面插一个 TeeAudioProcessor，
        // 把解码后的 PCM 旁路给可视化（见 VisualizerRenderersFactory 的 KDoc：
        // 任务书假设的 AudioListener.onAudioSamples 在 media3 里不存在，
        // 而 Visualizer 那条路要 RECORD_AUDIO，不能用）。
        // 开关关掉时音频线程侧只剩一次 volatile 读，等于零开销。
        VisualizerSetting.read(this)
        val renderersFactory = VisualizerRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        // 流内嵌的 ID3 元数据（尤其封面图，单张可达数百 KB）在本 App 里毫无用处：
        // 标题 / 歌手 / 封面一律由网易云 API 提供，并显式写入 MediaMetadata 与通知栏
        // （见 songItem() 与通知栏的 MediaMetadataCompat.Builder）。关掉解析可省下
        // 这部分解析 CPU 与内存 —— 对 3GB 机型是实打实的收益，且不影响任何显示。
        val extractorsFactory = DefaultExtractorsFactory()
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_DISABLE_ID3_METADATA)
            .setFlacExtractorFlags(FlacExtractor.FLAG_DISABLE_ID3_METADATA)
        // v1.6.0 · D1：ExoPlayer 走离线缓存数据源 —— 已播放过的音频片段直接从本地读，
        // 弱网/断网时不再重下（cache key 由 OfflineKeys 决定，见该文件 KDoc）。
        val mediaSourceFactory =
            DefaultMediaSourceFactory(OfflineAudioCache.dataSourceFactory(this), extractorsFactory)

        player = ExoPlayer.Builder(this, renderersFactory, mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            // 后台/熄屏播放时持有 partial wake lock，避免 CPU 休眠导致音频欠载
            // （听感是"炒豆子"爆鸣，严重时 AudioTrack 直接死掉、进度还在跑但没声）。
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(buildLoadControl())
            .build()

        // media3 MediaLibrarySession：对外暴露播放控制 + 浏览树，车机
        // （Android Automotive / Android Auto）据此发现应用并选歌。
        // 通知栏仍走 MediaSessionCompat，两者独立、互不干扰。
        mediaSession = MediaLibrarySession.Builder(this, player, libraryCallback()).build()

        mediaSessionCompat = MediaSessionCompat(this, "NcrustSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { player.play() }
                override fun onPause() { player.pause() }
                override fun onSkipToNext() { onPlaybackEnded?.invoke() }
                override fun onSkipToPrevious() { onPlaybackPrevious?.invoke() }
                override fun onSeekTo(pos: Long) {
                    player.seekTo(pos)
                    publishProgressNow()
                }
            })
            isActive = true
        }

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    onPlaybackEnded?.invoke()
                }
                onBufferingChanged?.invoke(state == Player.STATE_BUFFERING)
                updatePlaybackState()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // B：暂停即预约释放封面位图，恢复播放则取消释放并补回封面。
                if (isPlaying) onPlaybackActive() else scheduleArtworkIdleRelease()
                onIsPlayingChanged?.invoke(isPlaying)
                PlaybackStateManager.updatePlayingState(this@PlaybackService, isPlaying)
                updatePlaybackState()
                updateNotify()
            }
            override fun onPlayerError(error: PlaybackException) {
                // 之前完全没有错误处理:解码/取流失败后播放器静默停在 IDLE,
                // UI 还显示"在播",实际既没声音也不跳歌。现在上报给 ViewModel
                // 降档重试,最低档仍失败则由 ViewModel 跳歌。
                Log.e(
                    "PlaybackService",
                    "Playback error for songId=$mediaSongId code=${error.errorCodeName}: ${error.message}",
                    error
                )
                onPlaybackError?.invoke(mediaSongId ?: -1L)
            }
            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int
            ) {
                // Only handle automatic transitions triggered by ExoPlayer (gapless handoff).
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
                // v2.1.5：起播项的音源**从 item 自己身上读**。item 是 ExoPlayer 真正播的那一份，
                // 而 pendingNext* 是旁路（last-write-wins）—— 身份必须跟着 item 走，
                // 这正是跨源自动接续时会话/歌词留在上一首的接缝。
                val itemIdentity = PreloadSlot.identityFromMediaId(mediaItem?.mediaId)
                val itemSource = itemIdentity?.first
                val itemSongId = itemIdentity?.second
                val itemUrl = mediaItem?.localConfiguration?.uri?.toString()
                // v1.5.2 串台守卫：只有「ExoPlayer 真正起播的那一项」就是待播槽位里那一首，
                // 才允许把元数据写进通知栏 / 歌词 / ViewModel。重复入队的年代，这里会把
                // 「下下首」的标题写到正在播的「上一首」上 —— 就是用户听到的串台。
                // 现在宁可让 UI 停在原处（下一首自然会走硬切重新对齐），也绝不显示错歌。
                // v2.1.5：判定加上音源维度（网易云 123 与 QQ 123 是不同的歌）。
                if (!PreloadSlot.transitionMatches(
                        pendingNextSource, pendingNextSongId, pendingNextUrl,
                        itemSource, itemSongId, itemUrl,
                    )
                ) {
                    Log.w(
                        "PlaybackService",
                        "AUTO transition outside the preload slot (itemId=" + mediaItem?.mediaId +
                            " url=" + itemUrl + ", slot songId=" + pendingNextSongId +
                            " source=" + pendingNextSource.key +
                            ") — keep metadata in sync with audio, leave the slot for its own item"
                    )
                    // 刻意**不清**槽位：槽位项可能只是被排在了别的项之后（例如车机往播放列表里
                    // 插了内容），等它真的起播时元数据仍然正确。守卫只保证「绝不把槽位元数据
                    // 用在别的项上」——按 songId 比对，所以留着不会有谎报风险。
                    // 已播完的项照常清掉，列表长度不会因这次异常 transition 持续增长。
                    val playedItems = player.currentMediaItemIndex
                    if (playedItems > 0) player.removeMediaItems(0, playedItems)
                    return
                }
                // 元数据优先取 item 自带的（与音频同源），槽位只作兜底。
                val itemMetadata = mediaItem?.mediaMetadata
                val itemTitle = itemMetadata?.title?.toString()?.takeIf { it.isNotBlank() }
                val slotTitle = pendingNextTitle
                val slotArtist = pendingNextArtist
                val slotArtwork = pendingNextArtwork
                val slotSongId = pendingNextSongId
                // v2.1.5：音源身份同样「item 优先、槽位兜底」，并把解析出的载荷一并接管。
                val nextSource = itemSource ?: pendingNextSource
                val nextSourceId = pendingNextSourceId
                val nextMediaId = pendingNextMediaId
                // 预载位图就绪则在切换瞬间直接应用(任务栏立即是新图), 未就绪回退异步加载。
                val preloaded = pendingNextArtworkBitmap
                clearPendingNext()
                pendingNextArtworkBitmap = null
                mediaSourceKey = nextSource.key
                mediaSourceId = nextSourceId
                mediaMediaId = nextMediaId
                (itemTitle ?: slotTitle)?.let { mediaTitle = it }
                mediaArtist = itemMetadata?.artist?.toString() ?: (slotArtist ?: "")
                mediaSongId = itemSongId ?: slotSongId.takeIf { it > 0 }
                Log.d(
                    "PlaybackService",
                    "gapless transition -> songId=" + mediaSongId + " source=" + nextSource.key +
                        " title=" + mediaTitle + " count=" + player.mediaItemCount
                )
                if (!slotArtwork.isNullOrEmpty()) {
                    currentArtworkUrl = slotArtwork
                    // 使任何在途预载作废: 迟到的预载结果不能回填 pending,
                    // 否则会污染下一次切换
                    artworkPreloadGeneration++
                    if (preloaded != null) {
                        currentArtworkBitmap = preloaded
                        scope.launch(Dispatchers.Main) {
                            updatePlaybackState()
                            updateNotify()
                        }
                    } else {
                        // 回退异步加载; 旧位图保留到新封面加载完(用户决策),
                        // 加载完成由 metadata 位图引用比较触发换图
                        loadArtwork(slotArtwork)
                    }
                }
                // 播完的项统一清掉：列表收敛回「当前 + 至多一首待播」。
                val finishedItems = player.currentMediaItemIndex
                if (finishedItems > 0) player.removeMediaItems(0, finishedItems)
                updatePlaybackState()
                updateNotify()
                // v2.1.5：把**这次真正起播的曲目身份**交给 ViewModel。
                // 这是它唯一应该用来路由取词的依据 —— 原先它读的是自己那份
                // 「只有 playSong 才会更新」的音源字段，跨源自动接续时那是上一首的值。
                onTrackTransitioned?.invoke(
                    nextSource, mediaSongId ?: -1L, nextSourceId, nextMediaId,
                )
                onSongTransitioned?.invoke()
            }
        })
        // 音频输出层故障（听感"哒哒哒"爆鸣，严重时 AudioTrack 死掉、进度照跑但没声）
        // 不会走 onPlayerError, 这里单独接住并交给 ViewModel 的降档重试处理。
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioSinkError(
                eventTime: AnalyticsListener.EventTime,
                audioSinkError: Exception
            ) {
                Log.e("PlaybackService", "AudioSink error: ${audioSinkError.message}", audioSinkError)
                onPlaybackError?.invoke(mediaSongId ?: -1L)
            }
        })
        createNotificationChannel()
        cancelStaleMedia3Notification()
        startProgressUpdates()
    }

    /**
     * v2.0.2：清掉旧版 media3 自动 post 的那条媒体通知（id = 1001 / channel = `default_channel_id`）。
     *
     * 通知**不随进程死亡自动回收**：从 v2.0.1 升到本版时，用户会看到「新版已经不发第二条了，
     * 但旧的那条还挂在通知栏里」，直到它被别的路径覆盖或用户手动划掉。这里在服务创建时
     * 显式撤一次，升级后立刻收敛成一条。
     *
     * 只撤**本应用自己**的通知（`NotificationManager.cancel(id)` 的作用域就是本包），
     * 而且是幂等的：本版之后 media3 不会再发这个 id，`cancel` 一个不存在的 id 是空操作。
     *
     * 顺带说明为什么**不**删 `default_channel_id` 渠道：那个渠道是 media3 建的、本版起不再
     * 使用，但删渠道会让任何仍在往该渠道 post 的路径静默丢通知（API 26+ 往已删除渠道发通知
     * 不显示）。留一个空的渠道只是设置页里多一行，不会造成功能问题。
     */
    private fun cancelStaleMedia3Notification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.cancel(androidx.media3.session.DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
        }.onFailure { Log.w("PlaybackService", "cancel stale media3 notification failed", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d("PlaybackService", "onStartCommand action=${intent?.getStringExtra("action")}")

        when (intent?.getStringExtra("action")) {
            "preload_next" -> {
                val nextUrl = intent.getStringExtra("url") ?: return START_NOT_STICKY
                val nextSongId = intent.getLongExtra("songId", -1L)
                // v1.5.2 串台修复：同一首下一曲会被预载两次（切歌瞬间 + 进入最后 60s 窗口），
                // 旧实现无条件 addMediaItem，播放列表累积成 [当前, 下一首, 下一首']；
                // 播完当前项先播「下一首」, 再播那个重复项时 pendingNext* 已指向下下首 ⇒
                // 耳朵里是上一首、通知栏/歌词显示下一首。槽位至多一首是不变量本身。
                when (PreloadSlot.decide(pendingNextSongId, pendingNextUrl, nextSongId, nextUrl)) {
                    PreloadSlot.Decision.IGNORE -> {
                        Log.d(
                            "PlaybackService",
                            "preload_next ignored, slot already holds songId=$nextSongId"
                        )
                        return START_NOT_STICKY
                    }
                    PreloadSlot.Decision.REPLACE -> {
                        Log.d(
                            "PlaybackService",
                            "preload_next replaces stale slot songId=$pendingNextSongId -> $nextSongId"
                        )
                        removePendingItems()
                    }
                    PreloadSlot.Decision.APPEND -> Unit
                }
                pendingNextTitle = intent.getStringExtra("title")
                pendingNextArtist = intent.getStringExtra("artist")
                pendingNextArtwork = intent.getStringExtra("artwork")
                pendingNextSongId = nextSongId
                pendingNextUrl = nextUrl
                // v2.1.5：槽位必须连音源身份一起记。少了它，自动接续时这一项
                // 在 ViewModel 眼里仍然是「上一首的音源」，取词就会问错平台。
                pendingNextSource = MusicSource.fromKey(intent.getStringExtra("sourceKey"))
                pendingNextSourceId = intent.getStringExtra("sourceId")
                pendingNextMediaId = intent.getStringExtra("mediaId")
                // 提前加载下一首封面: 无缝切换瞬间任务栏直接是新图,
                // 不再出现"新歌标题 + 上一首封面"的过渡窗口
                if (!pendingNextArtwork.isNullOrEmpty()) {
                    preloadArtwork(pendingNextArtwork!!)
                }
                player.addMediaItem(buildPreloadMediaItem(nextUrl, nextSongId))
                Log.d(
                    "PlaybackService",
                    "Queued next: $pendingNextTitle url=$nextUrl count=" + player.mediaItemCount
                )
                return START_NOT_STICKY
            }
            "pause" -> { player.pause() }
            "resume" -> { player.play() }
            "stop" -> {
                PlaybackStateManager.clearState(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                // v1.6.0 · D3：撤掉实时更新通知，别在状态栏留一条不动的进度条。
                LiveUpdateNotifier.cancel(this)
                mediaSessionCompat?.isActive = false
                mediaSessionCompat?.release()
                mediaTitle = "Ncrust"
                mediaArtist = ""
                mediaSongId = null
                clearPendingNext()
                currentArtworkBitmap = null
                currentArtworkUrl = null
                stopSelf()
                return START_NOT_STICKY
            }
            "seek" -> {
                val pos = intent.getLongExtra("position", 0L)
                player.seekTo(pos)
                publishProgressNow()
            }
            "previous" -> onPlaybackPrevious?.invoke()
            "next" -> onPlaybackEnded?.invoke()
        }

        val url = intent?.getStringExtra("url")
        val title = intent?.getStringExtra("title")
        val artist = intent?.getStringExtra("artist")
        val artwork = intent?.getStringExtra("artwork")
        val songId = intent?.getLongExtra("songId", -1L) ?: -1L
        // v2.1.0 · C：音源身份。媒体通知与车机路径都要靠它把媒体项指回**正确的**音源 ——
        // 少了它，QQ 音乐的曲目在通知栏/车机上会被当成网易云的同号歌曲。
        val sourceKey = intent?.getStringExtra("sourceKey")
        val sourceId = intent?.getStringExtra("sourceId")
        val mediaId = intent?.getStringExtra("mediaId")
        // B4：起播位置。0 = 从 0 分 0 秒开始；> 0 = 从上次退出的时间点续播。
        val startPositionMs = intent?.getLongExtra("startPositionMs", 0L) ?: 0L

        if (title != null) mediaTitle = title
        if (artist != null) mediaArtist = artist
        if (songId > 0) mediaSongId = songId
        if (sourceKey != null) mediaSourceKey = sourceKey
        if (sourceId != null) mediaSourceId = sourceId
        if (mediaId != null) mediaMediaId = mediaId

        if (artwork != null && artwork != currentArtworkUrl) {
            currentArtworkUrl = artwork
            loadArtwork(artwork)
        } else if (artwork != null && artwork.isEmpty()) {
            // 这首歌没有封面：清掉主题色，UI 回落预设色（不然会沿用上一首的颜色）。
            paletteUrl = null
            paletteRgb = null
            onCoverAccent?.invoke(null)
        }

        if (url != null) {
            PlaybackStateManager.saveState(this, songId, mediaTitle, mediaArtist, currentArtworkUrl ?: "", true)
            playUrl(url, startPositionMs)
        } else if (!isServiceStarted && mediaTitle != "Ncrust") {
            updateNotify()
        }

        return START_NOT_STICKY
    }

    override fun onGetSession(controllerInfo: androidx.media3.session.MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    /**
     * 车机浏览树：根 → 每日推荐 / 私人 FM / 我的收藏；文件夹 → 歌曲列表。
     * 车机点播时经 [onAddMediaItems] 把 song:<id> 解析成可播放 URL。
     */
    private fun libraryCallback() = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: M3MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: M3MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            scope.launch {
                val children = runCatching { loadChildren(parentId) }.getOrDefault(emptyList())
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
            }
            return future
        }

        override fun onAddMediaItems(
            mediaSession: M3MediaSession,
            controller: M3MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val future = SettableFuture.create<MutableList<MediaItem>>()
            scope.launch {
                future.set(mediaItems.map { runCatching { resolveMediaItem(it) }.getOrDefault(it) }.toMutableList())
            }
            return future
        }
    }

    private fun rootItem(): MediaItem = folderItem(ROOT_ID, "Ncrust")

    private fun folderItem(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        )
        .build()

    private fun songItem(song: SongItem): MediaItem = MediaItem.Builder()
        // 网易云仍是 `song:123`（与 v2.0.2 逐字节相同，老媒体项继续能解析）；
        // QQ 音乐是 `song:qqmusic:456`。同号不同源在媒体层也必须分得开。
        .setMediaId(com.takahashirinta.ncrust.source.SourceIds.mediaId(song.musicSource, song.id) ?: "song:${song.id}")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(song.name)
                .setArtist(song.artists?.joinToString("/") { it.name })
                .setArtworkUri(song.album?.picUrl?.takeIf { it.isNotEmpty() }?.let { Uri.parse(CoverUrls.large(it)) })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        )
        .build()

    private suspend fun loadChildren(parentId: String): List<MediaItem> {
        val s = stringsForCode(getSavedLanguageCode(this))
        return when (parentId) {
            ROOT_ID -> listOf(
                folderItem(DAILY_ID, s.dailySongsTitle),
                folderItem(FM_ID, s.fmRadioTitleGeneric),
                folderItem(LIKED_ID, s.tabLibrary)
            )
            DAILY_ID -> PlaylistApi.getDailyRecommendSongs().map { songItem(it) }
            FM_ID -> PlaylistApi.getPersonalFm().map { songItem(it) }
            LIKED_ID -> LibraryManager.getSavedSongs(this).map { songItem(it) }
            else -> emptyList()
        }
    }

    /** 车机点播：把 song:<id> 解析成当前音质档的可播放 URL。 */
    private suspend fun resolveMediaItem(item: MediaItem): MediaItem {
        if (item.localConfiguration != null) return item
        // v2.1.0 · C：车机路径同样按音源路由。解析不出音源的 mediaId 直接放弃
        // （返回原 item 让上层跳歌），**不要**猜成网易云 —— 猜错就是放到别人的歌。
        val parsed = com.takahashirinta.ncrust.source.SourceIds.parseMediaId(item.mediaId) ?: return item
        val (source, songId) = parsed
        val ref = com.takahashirinta.ncrust.source.songRefOf(source, songId)
        val result = com.takahashirinta.ncrust.source.SourceRouter.resolveUrl(ref, currentQualityLevel())
            ?: return item
        return item.buildUpon().setUri(result.url).build()
    }

    private fun currentQualityLevel(): String {
        val prefs = getSharedPreferences("ncrust_settings", 0)
        // A2：档位表收敛到 QualityLadder —— 之前车机路径和手机路径各写一份，加档位时容易漂移。
        return QualityLadder.levelAt(prefs.getInt("wifi_quality", 3), "lossless")
    }

    /**
     * @param startPositionMs 起播位置（B4 续播）；0 表示从 0 分 0 秒开始。
     *
     * 用 setMediaItem(item, startPositionMs) 而不是「先 prepare 再 seekTo」：
     * 后者会先按位置 0 解码并回调一次进度，歌词面板可能闪一下第一行再跳走。
     * 直接带起播位置能让 position 从第一帧起就是正确值，歌词首帧即对齐。
     */
    private fun playUrl(url: String, startPositionMs: Long = 0L) {
        Log.d("PlaybackService", "Playing: $url startPositionMs=$startPositionMs")
        // v1.6.0 · D1：记下这首歌「最后一次成功播放的 URL」。断网时客户端必须先有一个 URL
        // 才会去问 CacheDataSource，而网易的 URL 20 分钟就过期 —— 这份清单就是离线回放的入口。
        runCatching { OfflineUrlStore.rememberFromUrl(this, url) }
        // Clear any stale preload metadata; setMediaItem replaces the entire playlist.
        clearPendingNext()
        // 手动切歌会顶掉无缝队列, 预载的下一首封面作废, 一并清掉
        pendingNextArtworkBitmap = null
        artworkPreloadGeneration++
        val mediaItem = buildCurrentMediaItem(url)
        player.setMediaItem(mediaItem, startPositionMs.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * 当前播放项的构造（v2.1.5 · 媒体面板修复）。
     *
     * ## 为什么原来的 `MediaItem.fromUri(url)` 是错的
     *
     * 这个应用有**两条**向系统暴露的媒体身份，而它们的信息来源不同：
     *
     * | 身份 | 标题来源 | 谁在用 |
     * |---|---|---|
     * | legacy `MediaSessionCompat`（`NcrustSession`） | 应用自己的 [mediaTitle] / [mediaArtist] 字段 | 通知栏、锁屏 |
     * | media3 `MediaLibrarySession`（`androidx.media3.session.id.`） | **ExoPlayer 当前 MediaItem 的 metadata** | 车机、以及**部分 ROM 的控制中心** |
     *
     * 旧实现用 `MediaItem.fromUri(url)` 建项 —— **没有 metadata**。于是第二条身份对系统讲的是
     * 「有一首歌，但它没有名字」。真机实测（华为 WGR-W09 / HarmonyOS 4.2）：
     *
     * ```
     * NcrustSession                metadata: size=5, description=闪闪星光, 紫荆花盛开 · 李荣浩/梁咏琪
     * androidx.media3.session.id.  metadata: size=3, description=null,  null, null      ← 空
     * ```
     *
     * 而华为控制中心的媒体卡对**官方网易云**（第三方应用）与自带华为音乐都正常显示，
     * 唯独 Ncrust 显示「未在播放」—— 说明这不是 ROM 能力边界，是应用讲了两套不一致的话。
     *
     * 顺带：预载项（[buildPreloadMediaItem]）一直是带 metadata 的，所以「无缝接续过来的那首歌」
     * 在 media3 会话里有名字、「手动点的这首歌」没有 —— 同一个应用内部都不自洽。
     * 现在两者共用同一套构造规则。
     *
     * @see buildPreloadMediaItem
     */
    private fun buildCurrentMediaItem(url: String): androidx.media3.common.MediaItem {
        val builder = androidx.media3.common.MediaItem.Builder().setUri(url)
        // mediaId 与预载项同形（`song:123` / `song:qqmusic:456`），车机与 ROM 才能把
        // 「当前项」和「待播项」认成同一套身份。取不到身份时不设，绝不编一个假的。
        mediaSongId?.takeIf { it > 0L }?.let { id ->
            com.takahashirinta.ncrust.source.SourceIds
                .mediaId(MusicSource.fromKey(mediaSourceKey), id)
                ?.let { builder.setMediaId(it) }
        }
        val meta = MediaMetadata.Builder()
        // mediaTitle 的哨兵值是 "Ncrust"（服务未起播时的默认值）—— 那不是歌名，不要写进去。
        mediaTitle.takeIf { it.isNotBlank() && it != "Ncrust" }?.let { meta.setTitle(it) }
        mediaArtist.takeIf { it.isNotBlank() }?.let { meta.setArtist(it) }
        currentArtworkUrl?.takeIf { it.isNotBlank() }?.let { art ->
            CoverUrls.large(art)?.let { meta.setArtworkUri(Uri.parse(it)) }
        }
        return builder.setMediaMetadata(meta.build()).build()
    }

    /**
     * 清空待播槽位的元数据。
     *
     * 凡是**替换播放列表**的路径（playUrl / stop / onTaskRemoved）都必须调用 ——
     * 残留的 pendingNext* 会在下一次 AUTO transition 时被当成新歌写进通知栏/歌词，
     * 那正是「UI 显示下一首、耳朵还是上一首」的串台。
     */
    private fun clearPendingNext() {
        pendingNextTitle = null
        pendingNextArtist = null
        pendingNextArtwork = null
        pendingNextSongId = -1L
        pendingNextUrl = null
        // v2.1.5：音源身份是槽位的一部分。漏清它比漏清标题更危险 ——
        // 残留的 pendingNextSource 会让下一次 transition 把**别的音源**的曲目
        // 认成槽位项，直接导致取词走错平台。
        pendingNextSource = MusicSource.DEFAULT
        pendingNextSourceId = null
        pendingNextMediaId = null
    }

    /**
     * 预载项的构造：元数据（标题/艺人/封面）与 mediaId 都写在 media item **自己身上**。
     *
     * 通知栏/歌词原先只靠 pendingNext* 这组旁路变量，而旁路是 last-write-wins：
     * 只要 ExoPlayer 起播的项不是槽位里那一首，写出来的元数据就是另一首歌。
     * 让元数据跟 item 走，UI 与音频就永远同源；mediaId=song:<id> 则让
     * onMediaItemTransition 能回答「这次起播的到底是哪一首」（见 PreloadSlot）。
     */
    private fun buildPreloadMediaItem(url: String, songId: Long): androidx.media3.common.MediaItem {
        val builder = androidx.media3.common.MediaItem.Builder()
            .setUri(url)
            // v2.1.5：mediaId 带音源（`song:123` / `song:qqmusic:456`）。
            // 这样 onMediaItemTransition 能**只凭 item 自己**回答「起播的是哪首歌」，
            // 不必信任 last-write-wins 的旁路变量 —— 跨源接续的串台就断在这里。
            .setMediaId(PreloadSlot.mediaIdFor(pendingNextSource, songId, url))
        val title = pendingNextTitle
        val artist = pendingNextArtist
        val artwork = pendingNextArtwork
        if (title != null || artist != null || !artwork.isNullOrEmpty()) {
            val meta = MediaMetadata.Builder()
            if (title != null) meta.setTitle(title)
            if (artist != null) meta.setArtist(artist)
            CoverUrls.large(artwork)?.let { meta.setArtworkUri(Uri.parse(it)) }
            builder.setMediaMetadata(meta.build())
        }
        return builder.build()
    }

    /**
     * 移除当前项之后的所有项，把播放列表收敛回「当前项 + 至多一首待播项」。
     *
     * ExoPlayer 的播放列表操作必须在应用主线程（本类所有调用点都在主线程）。
     * 之所以是「当前项之后」而不是固定的 index 1：历史版本留下过重复待播项，
     * 这里顺手把任何多余项一并清掉，恢复不变量。
     */
    private fun removePendingItems() {
        val from = player.currentMediaItemIndex + 1
        val count = player.mediaItemCount
        if (count > from) player.removeMediaItems(from, count)
    }

    /**
     * 提前加载下一首封面到 pendingNextArtworkBitmap(不入当前位图)。
     * 无缝 preload_next 时调用, 切换瞬间直接应用, 消除任务栏封面过渡窗口。
     * 用独立的 preload 代数做过期校验(不影响当前歌的 loadArtwork 代数)。
     */
    /** 把封面缩到通知栏够用的边长；原图不超过上限则原样返回（Palette 也吃这张）。 */
    private fun downscaleArtwork(source: Bitmap): Bitmap {
        val maxSide = maxOf(source.width, source.height)
        if (maxSide <= ARTWORK_MAX_PX) return source
        val ratio = ARTWORK_MAX_PX.toFloat() / maxSide
        val w = (source.width * ratio).toInt().coerceAtLeast(1)
        val h = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }

    /**
     * 暂停 N 秒后释放两张封面位图：预载位图此刻毫无价值，当前封面可按 URL 重载。
     * 刻意**不**重发通知 —— 系统那份副本还在，已显示的通知不会变空。
     */
    private fun scheduleArtworkIdleRelease() {
        cancelArtworkIdleRelease()
        val runnable = Runnable {
            artworkReleaseRunnable = null
            pendingNextArtworkBitmap = null
            currentArtworkBitmap = null
            // 位图引用比较用的快照一并清掉，否则重载回来的新实例会被当成"同一张图"而不重发。
            lastMetadataBitmap = null
            Log.i("PlaybackService", "idle release: dropped artwork bitmaps")
        }
        artworkReleaseRunnable = runnable
        artworkIdleHandler.postDelayed(runnable, ARTWORK_IDLE_RELEASE_MS)
    }

    private fun cancelArtworkIdleRelease() {
        artworkReleaseRunnable?.let { artworkIdleHandler.removeCallbacks(it) }
        artworkReleaseRunnable = null
    }

    /** 播放恢复：取消释放，并按 URL 补回已被释放的封面。 */
    private fun onPlaybackActive() {
        cancelArtworkIdleRelease()
        if (currentArtworkBitmap == null) currentArtworkUrl?.let { loadArtwork(it) }
    }

    /**
     * B2-C：从封面提取主题色。采样降到 112×112，createScaledBitmap + Palette 都在
     * Dispatchers.Default 上跑（都是 CPU 活，不能占主线程）；同一张封面（URL 未变，
     * 例如空闲释放后恢复播放触发的重载）直接复用上次结果，不重复取色。
     */
    private fun applyCoverAccent(bitmap: Bitmap, url: String, gen: Int) {
        if (url == paletteUrl) {
            paletteRgb?.let { onCoverAccent?.invoke(it) }
            return
        }
        scope.launch(Dispatchers.Default) {
            val palette = try {
                Palette.from(
                    Bitmap.createScaledBitmap(bitmap, PALETTE_SAMPLE_PX, PALETTE_SAMPLE_PX, true)
                ).generate()
            } catch (e: Exception) {
                Log.w("PlaybackService", "palette extraction failed", e)
                null
            }
            // 取色期间又切了歌：结果作废，别把上一首的颜色推给新歌。
            if (gen != artworkGeneration) return@launch
            if (palette == null) {
                onCoverAccent?.invoke(null)
                return@launch
            }
            val dominant = palette.getDominantColor(0xFF1DB954.toInt())
            // 主题色优先取 vibrant（更鲜亮，随后会被 processAccentColor 压饱和度）；
            // 通知栏着色沿用 dominant，保持原观感。
            val accent = palette.vibrantSwatch?.rgb
                ?: palette.dominantSwatch?.rgb
                ?: palette.mutedSwatch?.rgb
            paletteUrl = url
            paletteDominant = dominant
            paletteRgb = accent
            currentDominantColor = dominant
            onCoverAccent?.invoke(accent)
            scope.launch(Dispatchers.Main) { updateNotify() }
        }
    }

    private fun preloadArtwork(url: String) {
        val gen = ++artworkPreloadGeneration
        scope.launch(Dispatchers.IO) {
            try {
                val result = Coil.imageLoader(this@PlaybackService).execute(
                    ImageRequest.Builder(this@PlaybackService)
                        .data(CoverUrls.large(url))
                        .size(1024, 1024)
                        .build()
                )
                if (result is SuccessResult) {
                    if (gen != artworkPreloadGeneration) return@launch
                    pendingNextArtworkBitmap = downscaleArtwork(
                        (result.drawable as BitmapDrawable).bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    )
                }
            } catch (e: Exception) {
                Log.e("PlaybackService", "Preload artwork failed", e)
            }
        }
    }

    private fun loadArtwork(url: String) {
        // 旧位图**保留**到新封面加载完成再整体换掉(用户决策: 等它加载完再
        // 更换过去)——切歌瞬间不清图, 任务栏不会出现空图/系统保留旧图的
        // 不确定窗口; 加载完成后由 metadata 去重的位图引用比较触发重发。
        val gen = ++artworkGeneration
        scope.launch(Dispatchers.IO) {
            try {
                val imageLoader = Coil.imageLoader(this@PlaybackService)
                val request = ImageRequest.Builder(this@PlaybackService)
                    .data(CoverUrls.large(url))
                    // 锁屏/任务栏的媒体卡片是大尺寸位图(通常 1000px+), 512px 源会被
                    // 放大糊掉; 走图床 1080 缩略 + 1024 目标一起
                    .size(1024, 1024)
                    .build()
                val result = imageLoader.execute(request)
                // 加载期间又切了歌: 丢弃过期结果, 防止慢网下上一首封面覆盖新歌
                if (gen != artworkGeneration) return@launch
                if (result is SuccessResult) {
                    val srcBitmap = (result.drawable as BitmapDrawable).bitmap
                    // B：先拷成 ARGB_8888（Palette 读不了硬件位图）再降到通知栏够用的尺寸。
                    val bitmap = downscaleArtwork(srcBitmap.copy(Bitmap.Config.ARGB_8888, false))
                    currentArtworkBitmap = bitmap
                    // 立即重发 metadata(不等 500ms 心跳): 新封面尽快上任务栏
                    scope.launch(Dispatchers.Main) {
                        updatePlaybackState()
                        updateNotify()
                    }

                    // B2-C：取色统一走 applyCoverAccent —— 112×112 采样、Dispatchers.Default
                    // 计算、按封面 URL 去重。
                    applyCoverAccent(bitmap, url, gen)
                }
            } catch (e: Exception) {
                Log.e("PlaybackService", "Load artwork failed", e)
            }
        }
    }

    // 上一次 setMetadata 时的 title/artist/duration 快照，用于跳过等值重发
    private var lastMetadataTitle: String? = null
    private var lastMetadataArtist: String? = null
    private var lastMetadataDuration: Long = -1L
    private var lastMetadataArtwork: String? = null
    // 上一次 setMetadata 的 ART 位图引用。光比 URL 不够: 位图是异步换的,
    // URL 换新但位图还是旧的、或位图换新但 URL 已同步 —— 用引用比较,
    // 只要位图实例变了就重发, 保证任务栏封面最终切到新歌
    private var lastMetadataBitmap: Bitmap? = null

    /**
     * v1.8.0 · T5 / v2.0.2：上一次成功 post 通知的时刻（elapsedRealtime）。
     * 只作 [LyricNotifyGate] 的限流输入 —— 任何一次 post 都会刷新它（见 [updateNotify]）。
     */
    private var lastLyricNotifyAt = 0L

    // v2.1.5 · P1 探针计数（见 Companion.TAG_MEDIA_PANEL 的说明）。
    private var probeGatePost = 0
    private var probeGateDefer = 0
    private var probeGateSame = 0
    private var probeGateNotStarted = 0
    private var probeNotifyBuilds = 0
    // v2.1.5 · P1：**最后一次真正 setMetadata 出去的那一份**（键集与两行文字）。
    // 不读 MediaSessionCompat（androidx.media 1.7.0 的会话侧没有 metadata getter），
    // 而是记下应用发布的事实 —— 与 `dumpsys media_session` 里系统读到的那一份对照，
    // 「应用发布了什么 / 系统收到了什么」才是可归因的两条独立证据。
    private var lastPublishedPanelKeys = ""
    private var lastPublishedPanelTitle: String? = null
    private var lastPublishedPanelArtist: String? = null
    private var lastPublishedPanelDisplaySubtitle: String? = null

    /**
     * v2.0.2：**通知里当前那一行歌词**（不是「当前播放到的那一行」）。
     *
     * 去重的判据必须是「通知里已经是什么」，所以它只在 [updateNotify] 真的发出去之后才更新；
     * 初值是 [LyricNotifyGate.NEVER_POSTED] 而不是 `null` —— `null` 是有意义的行取值
     * （「当前没有歌词行」），混用会让第一首无歌词的歌永远不刷新。
     */
    private var lastPostedLyricLine: String? = LyricNotifyGate.NEVER_POSTED

    /** v2.0.2：被限流时用的延迟重试（保证密集段落的最后一行一定会到达，不丢内容）。 */
    private val lyricNotifyHandler = Handler(Looper.getMainLooper())
    private val lyricNotifyRetry = Runnable { onMediaLyricLineChanged(mediaLyricLine) }

    // setPlaybackState 去重：state 未变且距上次刷新 < STATE_MIN_INTERVAL_MS 时跳过
    // 位置精度对锁屏/通知条完全足够，跨进程 Binder 每次 1~3 ms，低端机 4Hz IPC 就吃满
    private var lastPlaybackStateInt: Int = -1
    private var lastPlaybackStateSentAt: Long = 0L
    private val STATE_MIN_INTERVAL_MS = 900L

    // v2.0.0 · T3：离线曲目索引的写入去重（每首歌只在首次起播 / 时长首次可知时落一次盘）。
    private var offlineRecordedSongId = -1L
    private var offlineRecordedKey: String? = null
    private var offlineRecordedDurationMs = -1L

    /**
     * v2.0.0 · T3：把「真的播起来了」的歌写进 [OfflineLibrary]（离线缓存管理页的清单来源）。
     *
     * 挂在 [updatePlaybackState] 而不是 `playUrl` 的原因是**覆盖面**：gapless 自动接续与
     * 车机点播都不经过 `playUrl`，而 2Hz 心跳只在 `player.isPlaying` 时跑 ——
     * 「心跳跑到了」本身就等于「这歌真的开始出声了」，比在 `playUrl` 里乐观写入更贴近
     * [OfflineLibrary] 的语义（列表 = 这台设备真的播过的歌）。
     *
     * 只认带 [OfflineKeys.QUERY_KEY] 的 URL：那是唯一会走 [OfflineAudioCache] 的路径，
     * 别的 URL 没有可命中的缓存条目，写进索引只会让 UI 说谎。
     */
    private fun maybeRecordOfflineLibrary(durationMs: Long) {
        // 只在真的在播时写：updatePlaybackState 还会被 BUFFERING / 封面加载等路径调用，
        // 那些时刻缓存里一个字节都还没有，写进去就是「列表说有、离线放不出来」。
        if (!player.isPlaying) return
        val songId = mediaSongId ?: return
        if (songId <= 0L) return
        val uri = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
        val key = OfflineKeys.keyOf(uri) ?: return
        if (songId == offlineRecordedSongId && key == offlineRecordedKey &&
            (durationMs <= 0L || durationMs == offlineRecordedDurationMs)
        ) {
            return
        }
        offlineRecordedSongId = songId
        offlineRecordedKey = key
        offlineRecordedDurationMs = durationMs
        runCatching {
            OfflineLibrary.record(
                context = this,
                songId = songId,
                name = mediaTitle,
                artist = mediaArtist,
                albumPicUrl = currentArtworkUrl,
                durationMs = durationMs,
                cacheKey = key,
                level = OfflineKeys.levelOf(key),
            )
        }
    }

    private fun updatePlaybackState() {
        val state = if (player.isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        val position = player.currentPosition
        val dur = if (player.duration > 0) player.duration else 0L
        // v2.0.0 · T3：起播后 500ms 内的第一次心跳就把这首歌写进离线曲目索引（幂等，见该函数）。
        runCatching { maybeRecordOfflineLibrary(dur) }

        val now = System.currentTimeMillis()
        val stateChanged = state != lastPlaybackStateInt
        if (stateChanged || now - lastPlaybackStateSentAt >= STATE_MIN_INTERVAL_MS) {
            mediaSessionCompat?.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(state, position, 1f)
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                                PlaybackStateCompat.ACTION_PAUSE or
                                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                                PlaybackStateCompat.ACTION_SEEK_TO or
                                PlaybackStateCompat.ACTION_PLAY_PAUSE
                    )
                    .setBufferedPosition(dur)
                    .build()
            )
            lastPlaybackStateInt = state
            lastPlaybackStateSentAt = now
        }

        // Metadata 只在 title/artist/duration/封面变化时重发——旧实现每 250ms 都要走一遍
        // MediaMetadataCompat.Builder + 跨进程 IPC 到系统 MediaSession，纯浪费。
        // 位图用**引用**比较: 实例变了(新封面加载完成)就重发, 同图不重发。
        // v1.5.1 · D：媒体面板歌词。只在"有歌词行"时改写 ARTIST（艺人前缀保留），
        // 没有就走原样 —— 关闭开关 / 无歌词的歌与 v1.5.0 逐字节一致。
        // v1.6.0（用户反馈修正）：有歌词行时 **第一行 = 当前歌词、第二行 = 「歌名 · 艺人」**；
        // 没歌词行时回到「歌名 / 艺人」。排布规则抽在 MediaDisplayLines（JVM 单测覆盖），
        // 这里只负责把结果写进 session 与通知。
        val display = MediaDisplayLines.of(mediaTitle, mediaArtist, mediaLyricLine)
        if (display.title != lastMetadataTitle || display.subtitle != lastMetadataArtist ||
            dur != lastMetadataDuration || currentArtworkUrl != lastMetadataArtwork ||
            currentArtworkBitmap !== lastMetadataBitmap
        ) {
            val builder = android.support.v4.media.MediaMetadataCompat.Builder()
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, display.title)
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST, display.subtitle)
                .putLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION, dur)
            // 老车机 / 蓝牙 AVRCP 读的是 SUBTITLE 那一套。v1.6.0 起歌词已经在 TITLE（第一行）了，
            // 再写一遍 SUBTITLE 会在支持三行的车机上重复显示，所以这里一律写空串清掉旧值。
            builder.putString(
                android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                ""
            )
            // 系统任务栏/锁屏的媒体卡优先读 MediaSession 的 ART 位图——不放进来的话
            // 系统退化用低清来源, 封面在任务栏上就是模糊的
            currentArtworkBitmap?.let {
                builder.putBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ART, it)
            }
            mediaSessionCompat?.setMetadata(builder.build())
            // v2.1.5 · P1：记下**这一份发布了什么**，供探针与 dumpsys 对照。
            lastPublishedPanelKeys = listOf(
                android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE,
                android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST,
                android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION,
                android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
            ).joinToString(",") { it.substringAfterLast('.') } +
                (if (currentArtworkBitmap != null) ",ART" else "")
            lastPublishedPanelTitle = display.title
            lastPublishedPanelArtist = display.subtitle
            lastPublishedPanelDisplaySubtitle = ""
            logMediaPanelSnapshot()
            lastMetadataTitle = display.title
            lastMetadataArtist = display.subtitle
            lastMetadataDuration = dur
            lastMetadataArtwork = currentArtworkUrl
            lastMetadataBitmap = currentArtworkBitmap
        }
    }

    /**
     * 立刻广播一次当前进度。
     *
     * 进度 ticker 只在 `player.isPlaying` 时广播（见 [startProgressUpdates]），
     * 于是**暂停态 seek 后 UI 永远收不到新位置**：进度条停在旧位置，用户要按一次
     * 播放键才看到跳转。seek 属于"用户显式改变位置"的事件，与播放状态无关，
     * 必须在 seek 后立刻广播一次。
     */
    private fun publishProgressNow() {
        val dur = player.duration
        if (dur > 0) onProgressUpdate?.invoke(player.currentPosition, dur)
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                if (player.isPlaying) {
                    onProgressUpdate?.invoke(player.currentPosition, player.duration)
                    updatePlaybackState()
                }
                // 500 ms tick：歌词滚动/进度条精度感知不到差异，但把 UI 层 4Hz
                // 广播降到 2Hz，PlayerViewModel 的三个 StateFlow / SlimProgressBar
                // 每秒重绘次数直接减半，低端机主线程 snapshot 广播压力显著下降
                delay(500)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "ncrust_playback",
                "Ncrust 音乐播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "正在播放的音乐"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    /**
     * v1.8.0 · T5 引入 / v2.0.2 重写：跨歌词行时重 post 媒体通知。
     *
     * ## 为什么「重 post」这件事本身是必需的
     *
     * 媒体通知的正文是**应用 post 的那一刻**烘进 extras 的；系统只在收到这条通知时读一次
     * 它引用的会话 metadata（AOSP `MediaDataManager.loadMediaDataInBg()` 就是在
     * `onNotificationPosted` 里 `create(token)` 再读一次），**之后不轮询**。media3 自己那条
     * 通知的刷新白名单也只有 playbackState / playWhenReady / metadata / timeline 四类事件，
     * **歌词行变化不在其中**。所以「不重发通知」== 「歌词永远停在最后一次 post 的那一行」，
     * 而「暂停/播放」恰好会走 `onIsPlayingChanged → updateNotify()` —— 这正是用户看到的
     * 「只有暂停再播放才刷新」。
     *
     * 歌词行本身一直是通的：`PlayerViewModel` 用 2Hz 位置流算出当前行，跨行时写
     * [mediaLyricLine]；`updatePlaybackState()`（2Hz）也一直在更新会话 metadata。
     * 断的只有「通知正文」这一环。
     *
     * ## v2.0.2 推翻了 v1.8.0 的版本闸门（真机实测，不是推理）
     *
     * v1.8.0 只在 `SDK_INT < P`（28）重 post，理由是「API 28+ 的 SystemUI 会自己从会话
     * metadata 重建媒体通知」。**那个前提不成立**：重建同样只发生在「通知被 post」那一刻。
     *
     * 实测（v2.0.1-gpl 原样，未改一行代码）：
     *  - 华为平板 WGR-W09 / HarmonyOS 4.2 / EMUI 14.2.0 / **Android 12（API 31）**：
     *    连续播放 47 秒，会话 metadata 推进了 **14 个不同的歌词行**，而通知正文
     *    `android.title` **47 秒一个字节没变**；
     *  - 荣耀 AGT-AN00 / MagicOS_9.0.0 / **Android 15（API 35）**：连续 38 秒同样冻结。
     * 两台设备 `SDK_INT` 都 ≥ 28 ⇒ 旧闸门让重 post 次数恒为 **0**。
     *
     * 所以现在**所有 API 版本都重 post**，不再按版本猜 ROM 会不会替应用重建。代价只是一次
     * 同 id 的 `notify()`（配合 `setOnlyAlertOnce(true)`，原地更新，不响不震不弹）；
     * 收益是「通知里那一行 == 正在唱的那一行」不再依赖任何 ROM 的实现细节。
     *
     * ## 限流与「不丢行」
     *
     * 判定全部收敛进 [LyricNotifyGate]（纯逻辑 + JVM 单测）：没进前台不发、与通知里当前
     * 那一行相同则不重发、距上次 post 不足 [LyricNotifyGate.MIN_INTERVAL_MS] 时
     * **延后重试而不是丢弃**。最后一条是本版新加的 —— 说唱段落里行变化可能快到 200ms 一次，
     * 旧实现直接丢弃，会让通知永远停在被丢掉的那一行上。
     */
    private fun onMediaLyricLineChanged(line: String?) {
        val decision = LyricNotifyGate.decide(
            line = line,
            lastPostedLine = lastPostedLyricLine,
            nowMs = SystemClock.elapsedRealtime(),
            lastPostAtMs = lastLyricNotifyAt,
            serviceStarted = isServiceStarted,
        )
        when (decision) {
            LyricNotifyGate.Decision.Post -> {
                probeGatePost++
                lyricNotifyHandler.removeCallbacks(lyricNotifyRetry)
                updateNotify()
                if (BuildConfig.DEBUG) {
                    Log.d(TAG_LYRIC_NOTIFY, "re-post on lyric line change: $line")
                }
            }

            is LyricNotifyGate.Decision.Defer -> {
                probeGateDefer++
                lyricNotifyHandler.removeCallbacks(lyricNotifyRetry)
                lyricNotifyHandler.postDelayed(lyricNotifyRetry, decision.retryInMs)
            }

            LyricNotifyGate.Decision.SkipNotStarted -> probeGateNotStarted++
            LyricNotifyGate.Decision.SkipSameLine -> probeGateSame++
        }
    }

    /**
     * v2.1.5 · P1 探针：把「媒体面板现在到底持有什么」整包打出来（仅 debug 包）。
     *
     * 只在**真的 setMetadata 之后**调用，所以它描述的是系统此刻读到的那一份，
     * 而不是应用打算写的那一份 —— 这个区别正是排查 ROM 兼容问题时最容易搞混的地方。
     */
    private fun logMediaPanelSnapshot() {
        if (!BuildConfig.DEBUG) return
        Log.d(
            TAG_MEDIA_PANEL,
            "keys=[" + lastPublishedPanelKeys + "] " +
                "title=" + lastPublishedPanelTitle +
                " artist=" + lastPublishedPanelArtist +
                " displaySubtitle='" + lastPublishedPanelDisplaySubtitle + "'" +
                " line=" + mediaLyricLine +
                " lastAt=" + lastLyricNotifyAt +
                " gate[post=" + probeGatePost + " defer=" + probeGateDefer +
                " same=" + probeGateSame + " notStarted=" + probeGateNotStarted + "] " +
                "notifyBuilds=" + probeNotifyBuilds
        )
    }

    private fun updateNotify() {
        // v1.6.0 · D3：API 36 的实时更新（Live Updates）。与媒体通知并行、互不依赖，
        // 失败只打日志（见 LiveUpdateNotifier）。
        runCatching {
            val songId = player.currentMediaItem?.mediaId
            if (songId != null) {
                val display = MediaDisplayLines.of(mediaTitle, mediaArtist, mediaLyricLine)
                LiveUpdateNotifier.update(
                    this,
                    title = display.title,
                    artist = display.subtitle,
                    positionMs = player.currentPosition.coerceAtLeast(0L),
                    durationMs = player.duration.takeIf { it > 0L } ?: 0L,
                    isPlaying = player.isPlaying,
                )
            }
        }
        try {
            probeNotifyBuilds++
            val n = buildNotification()
            if (!isServiceStarted) {
                startForeground(1, n)
                isServiceStarted = true
            } else {
                getSystemService(NotificationManager::class.java)?.notify(1, n)
            }
            // v2.0.2：记下「通知里现在到底是哪两行 / 什么时候发的」——这是
            // [LyricNotifyGate] 的两个输入。必须放在真的发出去之后（buildNotification
            // 抛异常时不能记账，否则会被误判成"已经显示了这一行"而永久跳过）。
            lastPostedLyricLine = mediaLyricLine
            lastLyricNotifyAt = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            Log.e("PlaybackService", "Failed to update notification", e)
        }
    }

    private fun buildNotification(): Notification {
        val isPlaying = player.isPlaying
        // v1.6.0（用户反馈修正）：通知与系统媒体面板共用同一份两行文案 —— 否则会出现
        // 「面板第一行是歌词、通知第一行是歌名」的不一致。规则见 MediaDisplayLines。
        val display = MediaDisplayLines.of(mediaTitle, mediaArtist, mediaLyricLine)

        val builder = NotificationCompat.Builder(this, "ncrust_playback")
            .setContentTitle(display.title)
            .setContentText(display.subtitle)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(false)
            // v1.8.0 · T5：逐行重 post 时不要再次提醒。媒体通知本身在 S6 上
            // defaults/sound/vibrate 全为空，但其它 ROM 未必 —— 显式声明，
            // 保证"刷新歌词"不会被用户感知成"又弹了一条通知"。
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(
                PendingIntent.getActivity(this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
            .addAction(android.R.drawable.ic_media_previous, "上一首", buildPI("previous"))
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "暂停" else "播放",
                buildPI(if (isPlaying) "pause" else "resume")
            )
            .addAction(android.R.drawable.ic_media_next, "下一首", buildPI("next"))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSessionCompat?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )            .setColor(currentDominantColor)
            .setColorized(true)

        if (currentArtworkBitmap != null) {
            builder.setLargeIcon(currentArtworkBitmap)
        }

        return builder.build()
    }

    private fun buildPI(action: String): PendingIntent {
        val i = Intent(this, PlaybackService::class.java).apply { putExtra("action", action) }
        return PendingIntent.getService(this, action.hashCode(), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * v2.0.2：**不再让 media3 自己发第二条媒体通知。**
     *
     * ## 为什么会有两条（真机取证）
     *
     * `PlaybackService` 是 media3 的 `MediaLibraryService`，`onCreate` 里建了一个
     * `MediaLibrarySession`。而 media3 `MediaSessionService` 的默认实现会在播放进行时
     * **自己 post 一条媒体通知**：`onUpdateNotification(session, startInForegroundRequired)`
     * → `MediaNotificationManager.updateNotification(...)`，用的是
     * [androidx.media3.session.DefaultMediaNotificationProvider] 的
     * **id = 1001 / channel = `default_channel_id`（渠道名 "Now playing"）/ groupKey = `media3_group_key`**。
     * 本类同时又在 [updateNotify] 里发自己的 **id = 1 / channel = `ncrust_playback`**。
     * 两个 id 不同，谁也覆盖不了谁 ⇒ **同一个包在通知栏里挂两条媒体通知**。
     *
     * 实测（v2.0.1-gpl 原样）：
     *  - 华为平板 WGR-W09（HarmonyOS 4.2 / API 31）`dumpsys notification --noredact`
     *    两条都在；下拉通知栏是**两张一模一样的媒体卡片**（用户报的「双通知栏」）；
     *  - 荣耀 AGT-AN00（MagicOS_9.0.0 / API 35）同样两条，且系统播控卡片挑中的是
     *    media3 那条 —— 它的正文是 `title=null / text=null`（见下），于是**歌词条根本不出现**。
     *
     * ## 为什么 media3 那条注定没有歌词（顺带修掉的第二个坑）
     *
     * media3 的通知正文取自 media3 会话的 metadata，而 media3 会话的 metadata 来自
     * **当前 `MediaItem` 自带的 `MediaMetadata`**：
     *  - 手动起播走的 [playUrl] 建的是裸 `MediaItem.fromUri(url)` —— **一个字段都没有**，
     *    于是那条通知 `android.title=null / android.text=null`；
     *  - 无缝预载走的 `buildPreloadMediaItem` 有 title/artist，AUTO 接续后那条通知能显示
     *    歌名/艺人，但**永远不会有歌词** —— 歌词只写进 `MediaSessionCompat`
     *    （见 [updatePlaybackState]），从来没进过 media3 会话。
     *
     * 换句话说：媒体通知这一份产物，多出来的那条要么是空的、要么是无歌词的重复项，
     * 没有任何场景是用户想要的。
     *
     * ## 修法：覆盖成空实现，通知由 [updateNotify] 单点管理
     *
     * media3 的判定链是（`media3-session-1.5.0` 字节码核实）：
     * ```
     * onUpdateNotification(session, startInForegroundRequired)   // 本方法
     *   ├─ onUpdateNotification(session)          // 单参版，只把 defaultMethodCalled 置 true
     *   └─ if (defaultMethodCalled) getMediaNotificationManager().updateNotification(...)
     * ```
     * 只要**不调用 super**，`defaultMethodCalled` 保持 false ⇒ media3 完全不再 post id=1001。
     * 这正是 media3 官方给「我要自己发通知」留的口子。
     *
     * 代价与对策（都已落地，不是待办）：
     *  - media3 不再替我们 `startForeground` —— [updateNotify] 本来就在自己
     *    `startForeground(1, n)` / `notify(1, n)`；
     *  - media3 不再管「播放结束后撤通知」—— 本类有自己的 `"stop"` 分支
     *    （`stopForeground(STOP_FOREGROUND_REMOVE)` + `stopSelf`）与 [onTaskRemoved]；
     *  - 生命周期其余部分不受影响：`MediaSessionService.onTaskRemoved` / `onDestroy` /
     *    `pauseAllPlayersAndStopSelf` 的字节码里都不碰通知管理器（已逐个核实）。
     *
     * ⚠️ 升级到本版时，旧版 media3 留下的那条 id=1001 通知**不会自己消失**
     * （通知不随进程死亡回收），所以 [onCreate] 里显式 `cancel` 一次。
     */
    override fun onUpdateNotification(
        session: androidx.media3.session.MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        // 刻意留空：不调用 super，media3 就不再 post 它自己那条通知。
        // 需要显式覆盖**双参**版本（单参版只置位 defaultMethodCalled，覆盖它挡不住）。
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    override fun onDestroy() {
        Log.d("PlaybackService", "onDestroy")
        LiveUpdateNotifier.cancel(this)
        instance = null
        isServiceStarted = false
        progressJob?.cancel()
        // v2.0.2：丢掉挂着的歌词重试，别让一个已销毁的服务再收到回调
        lyricNotifyHandler.removeCallbacks(lyricNotifyRetry)
        cancelArtworkIdleRelease()
        // Do NOT null the companion callbacks here — the ViewModel registers them once and
        // they must survive a service stop/restart cycle (e.g. stopSelf then play again).
        // ViewModel.onCleared() is responsible for clearing them when the ViewModel dies.
        currentArtworkBitmap = null
        mediaSession?.release()
        mediaSessionCompat?.isActive = false
        mediaSessionCompat?.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("PlaybackService", "onTaskRemoved")
        stopForeground(STOP_FOREGROUND_REMOVE)
        LiveUpdateNotifier.cancel(this)
        mediaSessionCompat?.isActive = false
        mediaSessionCompat?.release()
        mediaTitle = "Ncrust"
        mediaArtist = ""
        mediaSongId = null
        clearPendingNext()
        currentArtworkBitmap = null
        currentArtworkUrl = null
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}
