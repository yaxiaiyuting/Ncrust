/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明（B4 进度↔歌词对齐）：
 *   - 新增「每首歌曲的播放进度记忆」：中途退出可从断点续播；
 *     正常播完则清除记录，使重播从 0 分 0 秒开始，避免进度条与歌词错位。
 */

package com.takahashirinta.ncrust.player

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.takahashirinta.ncrust.network.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object PlaybackStateManager {
    private const val PREFS_NAME = "ncrust_playback_state"
    private const val KEY_SONG_ID = "song_id"
    private const val KEY_SONG_NAME = "song_name"
    private const val KEY_SONG_ARTIST = "song_artist"
    private const val KEY_SONG_ARTWORK = "song_artwork"
    private const val KEY_IS_PLAYING = "is_playing"
    private const val KEY_HAS_STATE = "has_state"

    // 队列持久化 key
    private const val KEY_QUEUE = "queue"
    private const val KEY_QUEUE_INDEX = "queue_index"

    // 每首歌曲进度记忆 key（B4）
    private const val KEY_SONG_POSITIONS = "song_positions"
    // 上限：超出后按保存时间淘汰最旧的，避免 SharedPreferences 无限膨胀。
    private const val MAX_SONG_POSITIONS = 300

    // 复用一个 Gson 实例：new Gson() 会构建反射映射表，几百首歌频繁调用时反射初始化非常热。
    // Gson 本身线程安全。
    private val gson = Gson()
    private val songListType = object : TypeToken<List<SongItem>>() {}.type
    private val positionMapType = object : TypeToken<MutableMap<Long, PositionEntry>>() {}.type

    // 队列写盘 debounce：连续 addToQueue / insertNext / removeFromQueue 会累计触发。
    // 200 ms 合并一次能把连续 20 首歌的加入压成 1 次 IO，避免主线程 Gson.toJson 抖动。
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingQueue: List<SongItem>? = null
    private var pendingIndex: Int = 0
    private var pendingAppContext: Context? = null
    private var flushJob: Job? = null
    private val flushLock = Any()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ---------- 单曲状态 ----------
    fun saveState(context: Context, songId: Long, title: String, artist: String, artwork: String, isPlaying: Boolean) {
        getPrefs(context).edit()
            .putBoolean(KEY_HAS_STATE, true)
            .putLong(KEY_SONG_ID, songId)
            .putString(KEY_SONG_NAME, title)
            .putString(KEY_SONG_ARTIST, artist)
            .putString(KEY_SONG_ARTWORK, artwork)
            .putBoolean(KEY_IS_PLAYING, isPlaying)
            .apply()
    }

    fun updatePlayingState(context: Context, isPlaying: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_IS_PLAYING, isPlaying).apply()
    }

    fun clearState(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    fun hasState(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_HAS_STATE, false)
    }

    data class SavedState(
        val songId: Long,
        val songName: String,
        val songArtist: String,
        val songArtwork: String,
        val isPlaying: Boolean
    )

    fun getState(context: Context): SavedState? {
        val prefs = getPrefs(context)
        if (!prefs.getBoolean(KEY_HAS_STATE, false)) return null
        return SavedState(
            songId = prefs.getLong(KEY_SONG_ID, 0),
            songName = prefs.getString(KEY_SONG_NAME, "") ?: "",
            songArtist = prefs.getString(KEY_SONG_ARTIST, "") ?: "",
            songArtwork = prefs.getString(KEY_SONG_ARTWORK, "") ?: "",
            isPlaying = prefs.getBoolean(KEY_IS_PLAYING, false)
        )
    }

    // ---------- 每首歌曲的播放进度记忆（B4） ----------
    //
    // 语义（对应「每首歌自动从 0 分 0 秒播放对齐，有播放记录的就从退出点续播」）：
    //   - 中途切歌 / 暂停 / 进程被杀 → 记下当前进度，下次再播这首歌从断点续播；
    //   - 正常播完 → **清除**记录，重播必定从 0:00 开始。
    // 清除这一步很关键：若播完仍留着旧进度，重播时播放器从 0 开始而歌词面板/
    // 进度条可能停在旧位置，就会出现「歌词对不上歌」。
    data class PositionEntry(val posMs: Long, val savedAtMs: Long)

    private val positionLock = Any()
    @Volatile private var positionCache: MutableMap<Long, PositionEntry>? = null
    private var posFlushJob: Job? = null

    private fun ensurePositionsLoaded(context: Context): MutableMap<Long, PositionEntry> {
        positionCache?.let { return it }
        return synchronized(positionLock) {
            positionCache ?: run {
                val parsed = runCatching {
                    val json = getPrefs(context).getString(KEY_SONG_POSITIONS, null)
                    if (json.isNullOrEmpty()) mutableMapOf<Long, PositionEntry>()
                    else (gson.fromJson<MutableMap<Long, PositionEntry>>(json, positionMapType)
                        ?: mutableMapOf())
                }.getOrDefault(mutableMapOf())
                parsed.also { positionCache = it }
            }
        }
    }

    /**
     * 读取某首歌的续播位置（毫秒）。
     *
     * 返回 0 表示「没有可用记录」——包括从未播过、已播完被清除、
     * 以及记录位置过于接近开头（< 3s，续播没有意义）或结尾（> 95%，等于已听完）。
     */
    fun getSongPosition(context: Context, songId: Long): Long {
        if (songId <= 0) return 0
        val map = ensurePositionsLoaded(context)
        val entry = synchronized(positionLock) { map[songId] } ?: return 0L
        if (entry.posMs < MIN_RESUMABLE_MS) return 0L
        return entry.posMs
    }

    /** 记录某首歌的播放位置（1s debounce 写盘）。 */
    fun saveSongPosition(context: Context, songId: Long, positionMs: Long) {
        if (songId <= 0 || positionMs < MIN_RESUMABLE_MS) return
        val map = ensurePositionsLoaded(context)
        synchronized(positionLock) {
            map[songId] = PositionEntry(positionMs, System.currentTimeMillis())
            if (map.size > MAX_SONG_POSITIONS) {
                map.entries
                    .sortedBy { it.value.savedAtMs }
                    .take(map.size - MAX_SONG_POSITIONS)
                    .forEach { map.remove(it.key) }
            }
        }
        scheduleFlushPositions(context)
    }

    /** 歌曲正常播完时清除记录，使下次重播从 0:00 开始。 */
    fun clearSongPosition(context: Context, songId: Long) {
        if (songId <= 0) return
        val map = ensurePositionsLoaded(context)
        val removed = synchronized(positionLock) { map.remove(songId) != null }
        if (removed) scheduleFlushPositions(context)
    }

    private fun scheduleFlushPositions(context: Context) {
        val appContext = context.applicationContext
        synchronized(flushLock) {
            posFlushJob?.cancel()
            posFlushJob = ioScope.launch {
                delay(1_000L)
                flushPositions(appContext)
            }
        }
    }

    private suspend fun flushPositions(context: Context) {
        val map = positionCache ?: return
        val snapshot = synchronized(positionLock) { map.toMap() }
        try {
            // 与队列一样：Gson 反射序列化放 Default，IO 只做写盘。
            val json = withContext(Dispatchers.Default) { gson.toJson(snapshot) }
            withContext(Dispatchers.IO) {
                getPrefs(context).edit().putString(KEY_SONG_POSITIONS, json).apply()
            }
        } catch (_: Exception) {
            // 写失败不影响播放，下次变更再试。
        }
    }

    /** 小于此值的位置不值得续播（直接从头播），也避免把开头几秒当成有效记录。 */
    private const val MIN_RESUMABLE_MS = 3_000L

    // ---------- 队列持久化 ----------
    fun saveQueue(context: Context, queue: List<SongItem>, currentIndex: Int) {
        synchronized(flushLock) {
            pendingQueue = queue
            pendingIndex = currentIndex
            pendingAppContext = context.applicationContext
            flushJob?.cancel()
            flushJob = ioScope.launch {
                delay(200L)
                flushPendingQueue()
            }
        }
    }

    private suspend fun flushPendingQueue() {
        val queue: List<SongItem>
        val index: Int
        val ctx: Context
        synchronized(flushLock) {
            queue = pendingQueue ?: return
            index = pendingIndex
            ctx = pendingAppContext ?: return
            pendingQueue = null
            pendingAppContext = null
        }
        try {
            // Gson 反射序列化是 CPU 密集，切 Default 避免 IO 线程池被占。
            val json = withContext(Dispatchers.Default) { gson.toJson(queue) }
            withContext(Dispatchers.IO) {
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(KEY_QUEUE, json)
                    .putInt(KEY_QUEUE_INDEX, index)
                    .apply()
            }
        } catch (_: Exception) {
            clearQueue(ctx)
        }
    }

    suspend fun getQueue(context: Context): Pair<List<SongItem>, Int>? {
        val prefs = getPrefs(context)
        val json = prefs.getString(KEY_QUEUE, null) ?: return null
        if (json.isEmpty() || json == "[]") return null
        return try {
            // Gson 反射反序列化是 CPU 密集，切 Default 避免主线程卡顿（队列几百首时 50ms+）。
            val queue: List<SongItem> = withContext(Dispatchers.Default) {
                gson.fromJson(json, songListType)
            }
            val index = prefs.getInt(KEY_QUEUE_INDEX, 0)
            Pair(queue, index)
        } catch (e: Exception) {
            clearQueue(context)
            null
        }
    }

    fun clearQueue(context: Context) {
        // 也取消任何飞行中的 debounce 写，避免 clearQueue 之后又被延迟写覆盖回去
        synchronized(flushLock) {
            flushJob?.cancel()
            pendingQueue = null
            pendingAppContext = null
        }
        getPrefs(context).edit()
            .remove(KEY_QUEUE)
            .remove(KEY_QUEUE_INDEX)
            .apply()
    }
}
