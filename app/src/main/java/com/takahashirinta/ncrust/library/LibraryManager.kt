/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明（Bug2「我喜欢的歌无法全部加载 / 加入播放列表」）：
 *   - ① 分页游标改为独立的 likedIdCursor（已消费 id 前缀长度），不再用 cachedSongs.size，
 *        避免有歌取不到详情时游标错位、尾部歌曲永远加载不出来。
 *   - ② refreshFromCloud 由「整体替换」改为「按 likedIds 顺序合并去重」，
 *        不再把用户已翻页加载的歌曲打回前 50 首。
 *   - ⑤ 新增 loadAllLikedSongs：为「播放全部」一次性补齐全部详情，分批 + 限流。
 */

package com.takahashirinta.ncrust.library

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 云同步收藏库。
 *
 * 语义（与网易云官方一致）：
 *  - 「收藏单曲」 = 网易云「收藏/我喜欢」（weapi /api/radio/like）；
 *  - 「收藏专辑」 = 网易云「我收藏的专辑」（weapi /api/album/sub），与单曲解耦。
 *
 * 本地用 SharedPreferences 缓存云端状态（收藏单曲 + 收藏专辑），保证：
 *   - 进收藏页/登录时后台拉取（refreshFromCloud）刷新，云端为真源；
 *   - 未拉取/未登录/失败时直接显示本地缓存，绝不出现空白+加载动画；
 *   - 收藏/取消动作先落本地缓存即时生效，再异步推到云端。
 *
 * 该对象保持公开 API 不变，所有既有调用点（收藏按钮、各详情页「加入收藏」）无需改动。
 */
object LibraryManager {
    private const val PREFS_NAME = "ncrust_library"
    private const val KEY_SONGS = "saved_songs"
    private const val KEY_ALBUMS = "saved_albums"
    private const val KEY_LIKED_IDS = "liked_ids"

    // 收藏单曲分页加载：进页先拉首屏 BATCH 首详情渲染，滚动到底再补下一批。
    const val LIKED_BATCH_SIZE = 50

    private val gson = Gson()
    private val songListType = object : TypeToken<List<SongItem>>() {}.type
    private val idListType = object : TypeToken<List<Long>>() {}.type

    @Volatile private var cachedSongs: MutableList<SongItem>? = null
    private val songsLock = Any()
    @Volatile private var cachedAlbums: MutableList<AlbumInfo>? = null
    private val albumsLock = Any()
    // 红心歌单全部单曲 id（有序），作为分页加载的底表。
    @Volatile private var cachedLikedIds: List<Long>? = null
    private val idsLock = Any()

    /**
     * 分页游标：cachedLikedIds 中**已被消费（已请求过详情）的前缀长度**（Bug2-①）。
     *
     * 绝不能用 cachedSongs.size 当游标——某些 id 取不到详情（下架 / 无版权）时，
     * 「已解析歌曲数」会小于「已消费 id 数」，游标随之错位：同一段 id 被反复请求，
     * 且「已解析数 >= id 总数」可能永远不成立，尾部歌曲再也加载不出来。
     *
     * 只在 songsLock 或 IO 协程内写；@Volatile 保证可见性即可，无需嵌套加锁。
     */
    @Volatile private var likedIdCursor = 0

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flushJob: Job? = null
    private var pendingAppContext: Context? = null
    private val flushLock = Any()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun ensureSongsLoaded(context: Context): MutableList<SongItem> {
        val current = cachedSongs
        if (current != null) return current
        return synchronized(songsLock) {
            cachedSongs ?: run {
                val parsed = runCatching {
                    val json = prefs(context).getString(KEY_SONGS, null)
                    if (json.isNullOrEmpty()) emptyList<SongItem>()
                    else (gson.fromJson<List<SongItem>>(json, songListType) ?: emptyList())
                }.getOrDefault(emptyList())
                cachedSongs = parsed.toMutableList()
                parsed.toMutableList()
            }
        }
    }

    private fun ensureAlbumsLoaded(context: Context): MutableList<AlbumInfo> {
        val current = cachedAlbums
        if (current != null) return current
        return synchronized(albumsLock) {
            cachedAlbums ?: run {
                // v2.5.5 · A：读路径走 SavedAlbumCodec（认稳定名 / 旧单字母 / 声明顺序），
                // 且**逐条**容错 —— 旧写法 `runCatching { gson.fromJson(...) }` 是
                // 「一个字节坏了，用户收藏的整张专辑表消失」。
                val parsed = SavedAlbumCodec.decode(prefs(context).getString(KEY_ALBUMS, null))
                cachedAlbums = parsed.toMutableList()
                parsed.toMutableList()
            }
        }
    }

    private fun ensureLikedIdsLoaded(context: Context): List<Long> {
        val current = cachedLikedIds
        if (current != null) return current
        return synchronized(idsLock) {
            cachedLikedIds ?: run {
                val parsed = runCatching {
                    val json = prefs(context).getString(KEY_LIKED_IDS, null)
                    if (json.isNullOrEmpty()) emptyList<Long>()
                    else (gson.fromJson<List<Long>>(json, idListType) ?: emptyList())
                }.getOrDefault(emptyList())
                cachedLikedIds = parsed
                parsed
            }
        }
    }

    private fun scheduleFlush(context: Context) {
        synchronized(flushLock) {
            pendingAppContext = context.applicationContext
            flushJob?.cancel()
            flushJob = ioScope.launch {
                delay(300L)
                flushToDisk()
            }
        }
    }

    private fun flushToDisk() {
        val ctx: Context
        val songsSnapshot: List<SongItem>
        val albumsSnapshot: List<AlbumInfo>
        val likedIdsSnapshot: List<Long>
        synchronized(flushLock) {
            ctx = pendingAppContext ?: return
            pendingAppContext = null
        }

        synchronized(songsLock) { songsSnapshot = cachedSongs?.toList() ?: emptyList() }
        synchronized(albumsLock) { albumsSnapshot = cachedAlbums?.toList() ?: emptyList() }
        synchronized(idsLock) { likedIdsSnapshot = cachedLikedIds?.toList() ?: emptyList() }
        runCatching {
            prefs(ctx).edit()
                .putString(KEY_SONGS, gson.toJson(songsSnapshot))
                // v2.5.5 · A：写路径走 codec（显式字段名），不再依赖 R8 后的类结构。
                .putString(KEY_ALBUMS, SavedAlbumCodec.encode(albumsSnapshot))
                .putString(KEY_LIKED_IDS, gson.toJson(likedIdsSnapshot))
                .apply()
        }
    }

    /**
     * 预热内存缓存：在 IO 线程把收藏单曲/专辑/红心 id 从 SharedPreferences 解析进内存。
     * 避免首次在组合期（主线程）调用 [getSavedSongs] / [isSongSaved] 时同步读盘 + Gson 卡顿。
     */
    fun preload(context: Context) {
        val app = context.applicationContext
        ioScope.launch {
            ensureSongsLoaded(app)
            ensureAlbumsLoaded(app)
            ensureLikedIdsLoaded(app)
        }
    }

    private fun isLoggedIn(context: Context) = CookieManager.hasCookie(context)

    private fun pushLike(songId: Long, like: Boolean) {
        ioScope.launch { runCatching { PlaylistApi.likeSong(songId, like) } }
    }

    private fun pushSubAlbum(albumId: Long, sub: Boolean) {
        ioScope.launch { runCatching { PlaylistApi.subAlbum(albumId, sub) } }
    }

    // ==================== 单曲（收藏） ====================

    /** 收藏（收藏）单曲：先落本地缓存，再异步同步到云端。 */
    fun saveSong(context: Context, song: SongItem) {
        val songs = ensureSongsLoaded(context)
        val added: Boolean
        synchronized(songsLock) {
            added = songs.none { it.id == song.id }
            if (added) songs.add(0, song)
        }
        if (added) {
            scheduleFlush(context)
            if (isLoggedIn(context)) pushLike(song.id, true)
        }
    }

    fun saveSongs(context: Context, newSongs: List<SongItem>) {
        val songs = ensureSongsLoaded(context)
        val added = mutableListOf<SongItem>()
        synchronized(songsLock) {
            for (song in newSongs) {
                if (songs.none { it.id == song.id }) {
                    songs.add(0, song)
                    added.add(song)
                }
            }
        }
        if (added.isNotEmpty()) {
            scheduleFlush(context)
            if (isLoggedIn(context)) added.forEach { pushLike(it.id, true) }
        }
    }

    /** 取消收藏（或为取消收藏）单曲：移除本地缓存，异步同步云端。 */
    fun removeSong(context: Context, songId: Long) {
        val songs = ensureSongsLoaded(context)
        val removed: Boolean
        synchronized(songsLock) {
            removed = songs.removeAll { it.id == songId }
        }
        if (removed) {
            scheduleFlush(context)
            if (isLoggedIn(context)) pushLike(songId, false)
        }
    }

    fun getSavedSongs(context: Context): List<SongItem> {
        val songs = ensureSongsLoaded(context)
        return synchronized(songsLock) { songs.toList() }
    }

    fun isSongSaved(context: Context, songId: Long): Boolean {
        val songs = ensureSongsLoaded(context)
        return synchronized(songsLock) { songs.any { it.id == songId } }
    }

    fun getSongsByAlbumId(context: Context, albumId: Long): List<SongItem> {
        val songs = ensureSongsLoaded(context)
        return synchronized(songsLock) { songs.filter { it.album?.id == albumId } }
    }

    // ==================== 专辑（云端收藏） ====================

    /** 收藏页「专辑」栏：返回云端「收藏的专辑」（album_sublist 缓存）。纯云端，不派生。 */
    fun getSavedAlbums(context: Context): List<AlbumInfo> {
        val albums = ensureAlbumsLoaded(context)
        return synchronized(albumsLock) { albums.toList() }
    }

    /** 收藏专辑（订阅云端）：先落本地缓存，再异步同步云端。 */
    fun subscribeAlbum(context: Context, album: AlbumInfo) {
        val albums = ensureAlbumsLoaded(context)
        synchronized(albumsLock) {
            albums.removeAll { it.albumId == album.albumId }
            albums.add(0, album)
        }
        scheduleFlush(context)
        if (isLoggedIn(context)) pushSubAlbum(album.albumId, true)
    }

    /** 取消收藏专辑：仅移除专辑订阅（不影响收藏单曲，二者解耦）。 */
    fun removeAlbum(context: Context, albumId: Long) {
        val albums = ensureAlbumsLoaded(context)
        val removed: Boolean
        synchronized(albumsLock) {
            removed = albums.removeAll { it.albumId == albumId }
        }
        if (removed) {
            scheduleFlush(context)
            if (isLoggedIn(context)) pushSubAlbum(albumId, false)
        }
    }

    // ==================== 云端同步 ====================

    /**
     * 从云端拉取收藏单曲 + 收藏专辑刷新本地缓存（云端为真源）。
     * 未登录返回 false。返回是否成功拉取（供调用方判断是否需要提示）。
     */
    suspend fun refreshFromCloud(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!CookieManager.hasCookie(context)) {
            Log.w(TAG, "refreshFromCloud: no cookie, skip")
            return@withContext false
        }
        val uid: Long = try {
            PlaylistApi.getCurrentUserId()
        } catch (e: Exception) {
            Log.e(TAG, "refreshFromCloud: getCurrentUserId failed: ${e.message}")
            return@withContext false
        }

        // 单曲与专辑各自独立尝试、独立提交：任一步失败不致整体放弃。
        var anySuccess = false

        // 单曲：先拉全量有序 trackIds 作底表，只需首屏分批详情即可渲染(懒加载)。
        try {
            val likedIds = PlaylistApi.getLikedTrackIds(uid)
            Log.i(TAG, "refreshFromCloud: likedIds=${likedIds.size}")
            synchronized(idsLock) { cachedLikedIds = likedIds }
            val firstBatch = if (likedIds.size > LIKED_BATCH_SIZE) likedIds.take(LIKED_BATCH_SIZE) else likedIds
            val firstSongs = if (firstBatch.isNotEmpty()) PlaylistApi.getSongsByIds(firstBatch) else emptyList()
            // Bug2-②：合并而不是整体替换。旧实现每次只写回首屏 50 首，而收藏页每次进入都会
            // 调用本方法，导致用户已翻页加载的歌曲被反复丢弃。
            // 以云端 likedIds 顺序为准重建：新详情优先、缺失的用本地已加载项补齐；
            // 用 Map 天然去重，同时剔除已取消收藏的歌（云端为真源）。
            synchronized(songsLock) {
                val oldById = (cachedSongs ?: mutableListOf()).associateBy { it.id }
                val freshById = firstSongs.associateBy { it.id }
                val merged = LinkedHashMap<Long, SongItem>(likedIds.size)
                for (id in likedIds) {
                    if (merged.containsKey(id)) continue
                    val song = freshById[id] ?: oldById[id] ?: continue
                    merged[id] = song
                }
                cachedSongs = merged.values.toMutableList()
                // 游标 = 已连续覆盖的 id 前缀长度：刷新后既不重复请求也不漏请求（Bug2-①）。
                likedIdCursor = likedIds.takeWhile { merged.containsKey(it) }.size
            }
            anySuccess = true
        } catch (e: Exception) {
            Log.e(TAG, "refreshFromCloud: liked songs failed: ${e.message}")
        }

        try {
            val cloudAlbums = PlaylistApi.getSubscribedAlbums()
            Log.i(TAG, "refreshFromCloud: albums=${cloudAlbums.size}")
            synchronized(albumsLock) {
                cachedAlbums = cloudAlbums.map {
                    AlbumInfo(
                        albumId = it.albumId,
                        name = it.name,
                        picUrl = it.picUrl,
                        artist = it.artist,
                        songCount = it.songCount
                    )
                }.toMutableList()
            }
            anySuccess = true
        } catch (e: Exception) {
            Log.e(TAG, "refreshFromCloud: albums failed: ${e.message}")
        }

        if (anySuccess) scheduleFlush(context)
        true
    }

    /** 红心歌单全部单曲 id（有序），供收藏页做分页懒加载的底表。 */
    fun getLikedSongIds(context: Context): List<Long> = ensureLikedIdsLoaded(context)

    /**
     * 分页拉取下一批收藏单曲详情（滚动到底时调用）。会追加进本地缓存并返回新渲染项。
     */
    suspend fun loadMoreLikedSongs(context: Context): List<SongItem> = withContext(Dispatchers.IO) {
        val allIds = ensureLikedIdsLoaded(context)
        // Bug2-①：游标是「已消费的 id 数」，与 cachedSongs.size 解耦；
        // 因此个别 id 取不到详情也不会让加载停滞。
        val start = likedIdCursor
        if (allIds.isEmpty() || start >= allIds.size) return@withContext emptyList()
        val sliceEnd = minOf(start + LIKED_BATCH_SIZE, allIds.size)
        val slice = allIds.subList(start, sliceEnd)
        // 先推进游标再发请求：即使本批部分 id 取不到详情，游标也必须前进，
        // 否则下一轮会重复请求同一段，终止条件也永远不成立（死循环）。
        likedIdCursor = sliceEnd
        val fetched = try {
            PlaylistApi.getSongsByIds(slice)
        } catch (e: Exception) {
            // 只有整批失败（网络/风控）才回滚游标，允许用户再次滚动时重试这一段。
            Log.e(TAG, "loadMoreLikedSongs failed: ${e.message}")
            likedIdCursor = start
            return@withContext emptyList()
        }
        synchronized(songsLock) {
            val songs = cachedSongs ?: mutableListOf()
            val seen = songs.mapTo(mutableSetOf()) { it.id }
            // 去重：同一 id 只保留首次出现的项，合并不会引入重复项（Bug2-②要求）。
            for (s in fetched) if (s.id !in seen) { songs.add(s); seen.add(s.id) }
            songs.toList()
        }.also { scheduleFlush(context) }
    }

    /**
     * 「播放全部」用：按红心歌单顺序返回**全部**收藏单曲详情（Bug2-⑤）。
     *
     * 与 loadMoreLikedSongs 的分页懒加载不同，这里一次性补齐所有缺失详情，
     * 因此按 PLAY_ALL_BATCH_SIZE 分批、批间 delay(PLAY_ALL_BATCH_DELAY_MS) 限流，
     * 避免收藏量很大时触发服务端风控。已在缓存中的歌曲直接复用，不重复请求。
     * 返回结果按红心歌单顺序且已去重；服务端已删除 / 无版权的 id 自然被跳过。
     */
    suspend fun loadAllLikedSongs(context: Context): List<SongItem> = withContext(Dispatchers.IO) {
        val allIds = ensureLikedIdsLoaded(context).distinct()
        if (allIds.isEmpty()) return@withContext emptyList()
        val byId = LinkedHashMap<Long, SongItem>()
        synchronized(songsLock) { cachedSongs?.forEach { byId[it.id] = it } }
        val missing = allIds.filter { it !in byId }
        for (start in missing.indices step PLAY_ALL_BATCH_SIZE) {
            val batch = missing.subList(start, minOf(start + PLAY_ALL_BATCH_SIZE, missing.size))
            val fetched = try {
                PlaylistApi.getSongsByIds(batch)
            } catch (e: Exception) {
                Log.e(TAG, "loadAllLikedSongs batch failed: ${e.message}")
                emptyList()
            }
            // 去重：同 id 覆盖写入，不会产生重复项。
            for (s in fetched) byId[s.id] = s
            if (start + PLAY_ALL_BATCH_SIZE < missing.size) delay(PLAY_ALL_BATCH_DELAY_MS)
        }
        allIds.mapNotNull { byId[it] }
    }

    /** 「播放全部」补齐详情时的批大小与批间隔（限流，见 loadAllLikedSongs）。 */
    private const val PLAY_ALL_BATCH_SIZE = 500
    private const val PLAY_ALL_BATCH_DELAY_MS = 250L

    private const val TAG = "LibraryManager"
}

@Immutable
data class AlbumInfo(
    val albumId: Long,
    val name: String,
    val picUrl: String,
    val artist: String,
    val songCount: Int
)
