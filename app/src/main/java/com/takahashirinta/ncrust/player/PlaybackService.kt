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
import com.takahashirinta.ncrust.cache.OfflineAudioCache
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
import com.takahashirinta.ncrust.ui.i18n.getSavedLanguageCode
import com.takahashirinta.ncrust.ui.i18n.stringsForCode
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
        var onPlaybackEnded: (() -> Unit)? = null
        var onPlaybackPrevious: (() -> Unit)? = null
        var onIsPlayingChanged: ((Boolean) -> Unit)? = null
        // Fired on the main thread when ExoPlayer reports a playback error (decode/source
        // failure). Carries the song id ExoPlayer was on; the ViewModel downgrades quality
        // and retries, so a device that can't decode e.g. 24-bit FLAC still gets sound.
        var onPlaybackError: ((Long) -> Unit)? = null
        // Fired on the main thread when ExoPlayer auto-transitions to a preloaded next item.
        var onSongTransitioned: (() -> Unit)? = null
        var onBufferingChanged: ((Boolean) -> Unit)? = null
        // B2-C：封面 Palette 提取出的主题色（ARGB）。null = 当前歌无封面，UI 回落预设色。
        // 由 PlaybackService 静态回调推给 PlayerViewModel.coverAccentRgb。
        var onCoverAccent: ((Int?) -> Unit)? = null
        var mediaTitle: String = "Ncrust"
        var mediaArtist: String = ""
        var mediaSongId: Long? = null
        var instance: PlaybackService? = null
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
        val renderersFactory = DefaultRenderersFactory(this)
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
                val itemSongId = PreloadSlot.songIdFromMediaId(mediaItem?.mediaId)
                val itemUrl = mediaItem?.localConfiguration?.uri?.toString()
                // v1.5.2 串台守卫：只有「ExoPlayer 真正起播的那一项」就是待播槽位里那一首，
                // 才允许把元数据写进通知栏 / 歌词 / ViewModel。重复入队的年代，这里会把
                // 「下下首」的标题写到正在播的「上一首」上 —— 就是用户听到的串台。
                // 现在宁可让 UI 停在原处（下一首自然会走硬切重新对齐），也绝不显示错歌。
                if (!PreloadSlot.transitionMatches(pendingNextSongId, pendingNextUrl, itemSongId, itemUrl)) {
                    Log.w(
                        "PlaybackService",
                        "AUTO transition outside the preload slot (itemId=" + mediaItem?.mediaId +
                            " url=" + itemUrl + ", slot songId=" + pendingNextSongId +
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
                // 预载位图就绪则在切换瞬间直接应用(任务栏立即是新图), 未就绪回退异步加载。
                val preloaded = pendingNextArtworkBitmap
                clearPendingNext()
                pendingNextArtworkBitmap = null
                (itemTitle ?: slotTitle)?.let { mediaTitle = it }
                mediaArtist = itemMetadata?.artist?.toString() ?: (slotArtist ?: "")
                mediaSongId = itemSongId ?: slotSongId.takeIf { it > 0 }
                Log.d(
                    "PlaybackService",
                    "gapless transition -> songId=" + mediaSongId + " title=" + mediaTitle +
                        " count=" + player.mediaItemCount
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
        startProgressUpdates()
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
        // B4：起播位置。0 = 从 0 分 0 秒开始；> 0 = 从上次退出的时间点续播。
        val startPositionMs = intent?.getLongExtra("startPositionMs", 0L) ?: 0L

        if (title != null) mediaTitle = title
        if (artist != null) mediaArtist = artist
        if (songId > 0) mediaSongId = songId

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
        .setMediaId("song:${song.id}")
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
        val songId = item.mediaId.removePrefix("song:").toLongOrNull() ?: return item
        val result = SongUrlFetcher.fetch(songId, currentQualityLevel()) ?: return item
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
        val mediaItem = androidx.media3.common.MediaItem.fromUri(url)
        player.setMediaItem(mediaItem, startPositionMs.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = true
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
            .setMediaId(PreloadSlot.mediaIdFor(songId, url))
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

    // setPlaybackState 去重：state 未变且距上次刷新 < STATE_MIN_INTERVAL_MS 时跳过
    // 位置精度对锁屏/通知条完全足够，跨进程 Binder 每次 1~3 ms，低端机 4Hz IPC 就吃满
    private var lastPlaybackStateInt: Int = -1
    private var lastPlaybackStateSentAt: Long = 0L
    private val STATE_MIN_INTERVAL_MS = 900L

    private fun updatePlaybackState() {
        val state = if (player.isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        val position = player.currentPosition
        val dur = if (player.duration > 0) player.duration else 0L

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
        val lyricLine = mediaLyricLine?.takeIf { it.isNotBlank() }
        val effectiveArtist = if (lyricLine == null) mediaArtist else "$mediaArtist · $lyricLine"
        if (mediaTitle != lastMetadataTitle || effectiveArtist != lastMetadataArtist ||
            dur != lastMetadataDuration || currentArtworkUrl != lastMetadataArtwork ||
            currentArtworkBitmap !== lastMetadataBitmap
        ) {
            val builder = android.support.v4.media.MediaMetadataCompat.Builder()
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, mediaTitle)
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST, effectiveArtist)
                .putLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION, dur)
            // 老车机 / 蓝牙 AVRCP 读的是 SUBTITLE 那一套，顺手写上；没歌词就写空串清掉。
            builder.putString(
                android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                lyricLine ?: ""
            )
            // 系统任务栏/锁屏的媒体卡优先读 MediaSession 的 ART 位图——不放进来的话
            // 系统退化用低清来源, 封面在任务栏上就是模糊的
            currentArtworkBitmap?.let {
                builder.putBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ART, it)
            }
            mediaSessionCompat?.setMetadata(builder.build())
            lastMetadataTitle = mediaTitle
            lastMetadataArtist = effectiveArtist
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

    private fun updateNotify() {
        // v1.6.0 · D3：API 36 的实时更新（Live Updates）。与媒体通知并行、互不依赖，
        // 失败只打日志（见 LiveUpdateNotifier）。
        runCatching {
            val songId = player.currentMediaItem?.mediaId
            if (songId != null) {
                LiveUpdateNotifier.update(
                    this,
                    title = mediaTitle,
                    artist = mediaArtist,
                    positionMs = player.currentPosition.coerceAtLeast(0L),
                    durationMs = player.duration.takeIf { it > 0L } ?: 0L,
                    isPlaying = player.isPlaying,
                )
            }
        }
        try {
            val n = buildNotification()
            if (!isServiceStarted) {
                startForeground(1, n)
                isServiceStarted = true
            } else {
                getSystemService(NotificationManager::class.java)?.notify(1, n)
            }
        } catch (e: Exception) {
            Log.e("PlaybackService", "Failed to update notification", e)
        }
    }

    private fun buildNotification(): Notification {
        val isPlaying = player.isPlaying

        val builder = NotificationCompat.Builder(this, "ncrust_playback")
            .setContentTitle(mediaTitle)
            .setContentText(mediaArtist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(false)
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

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    override fun onDestroy() {
        Log.d("PlaybackService", "onDestroy")
        LiveUpdateNotifier.cancel(this)
        instance = null
        isServiceStarted = false
        progressJob?.cancel()
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
