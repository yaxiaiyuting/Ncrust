package com.takahashirinta.ncrust.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.takahashirinta.ncrust.network.*
import com.takahashirinta.ncrust.qq.QqAccountAvailability
import com.takahashirinta.ncrust.qq.QqClient
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceRouter
import com.takahashirinta.ncrust.source.trackKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SearchViewModel : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _songs = MutableStateFlow<List<SongItem>>(emptyList())
    val songs: StateFlow<List<SongItem>> = _songs

    private val _albums = MutableStateFlow<List<AlbumSearchItem>>(emptyList())
    val albums: StateFlow<List<AlbumSearchItem>> = _albums

    private val _artists = MutableStateFlow<List<ArtistSearchItem>>(emptyList())
    val artists: StateFlow<List<ArtistSearchItem>> = _artists

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /**
     * 上一次单曲搜索里两个音源各出了多少条（v2.1.0 · E）。
     *
     * 存在的理由很直接：`SongCard` 只在**非网易云**的行上加音源标识，
     * 于是「网易云的行」和「QQ 的行」在列表里长得一样，用户无法判断结果来自哪里 ——
     * 真机反馈原话「现在有了稻香，似乎是网易云的搜索结果？」。
     * 一行小字把两个来源的条数都说清楚，比给每一行都挂标签更省地方。
     *
     * `null` = 还没搜过（不显示那一行）。
     */
    private val _sourceCounts = MutableStateFlow<Pair<Int, Int>?>(null)
    val sourceCounts: StateFlow<Pair<Int, Int>?> = _sourceCounts

    private val _currentType = MutableStateFlow(1)
    val currentType: StateFlow<Int> = _currentType

    private var searchJob: Job? = null

    /**
     * 补充源（QQ 音乐）的时间预算（v2.1.0 · hotfix 3）。
     *
     * 搜索是**交互式**功能，用户对「多久算慢」的容忍度是秒级。补充源晚到不如不到 ——
     * 主源的结果必须先让用户看见（见 [searchByType] 的顺序说明）。
     */
    private val QQ_SEARCH_BUDGET_MS = 5_000L

    fun onQueryChanged(newQuery: String) {
        _query.value = newQuery
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(500)
            if (newQuery.isBlank()) {
                clearResults()
                return@launch
            }
            searchByType(_currentType.value)
        }
    }

    fun onTypeChanged(type: Int) {
        _currentType.value = type
        if (_query.value.isNotBlank()) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch {
                searchByType(type)
            }
        }
    }

    private suspend fun searchByType(type: Int) {
        _isLoading.value = true
        _error.value = null
        try {
            when (type) {
                1 -> {
                    // v2.1.0 · E：**聚合搜索** —— 网易云在前、QQ 音乐接在后。
                    //
                    // ## hotfix 3：两个源不能串行等待（真机「搜索一直转圈」的根因）
                    //
                    // 第一版写成「先 await 网易云、再 await QQ，最后一起发布」。这在 QQ 那条
                    // 通道慢或不可达时是灾难：OkHttp 的 connect/read 超时是 15/20 秒，
                    // 网易云的结果明明已经到手，却要陪着一起等 —— 用户看到的就是**一直转圈**。
                    // 更糟的是同一时刻只看到「空结果 + 转圈」，连错误提示都没有：
                    // 因为网易云的异常被 `runCatching` 吞了，而原来那条路径会把 `_error` 交出去。
                    //
                    // 现在的顺序：
                    //  ① 网易云（主源）拿到就**立刻发布并停止转圈**；
                    //  ② QQ（补充源）带**硬预算**地追加，超时就放弃这一轮；
                    //  ③ 两个源都空且网易云报过错 ⇒ 把错误交出去，界面不留一块哑掉的空白。
                    val keyword = _query.value
                    val startedAt = System.currentTimeMillis()
                    var neteaseError: Throwable? = null
                    val netease = runCatching {
                        RetrofitClient.api.search(keyword = keyword, type = 1).result?.songs
                    }.onFailure {
                        neteaseError = it
                        android.util.Log.w("SearchViewModel", "netease search failed", it)
                    }.getOrNull().orEmpty()

                    // ① 主源到手即发布 —— 转圈到此结束，后面的 QQ 只是锦上添花。
                    _songs.value = netease.distinctBy { it.trackKey }
                    _albums.value = emptyList()
                    _artists.value = emptyList()
                    _sourceCounts.value = netease.size to 0
                    _isLoading.value = false

                    // ② 补充源：**硬预算**。超时/异常都只是「这一轮没有 QQ 结果」，
                    // 绝不能让它把已经可用的搜索结果拖住或清掉。
                    val qq = if (QqClient.isLoggedIn() || QqAccountAvailability.allowAnonymousSearch) {
                        // 注意：这里不能用 `runCatching { withTimeoutOrNull { ... } }` ——
                        // runCatching 的 lambda 不是 suspend 的，里面调不了挂起函数。
                        try {
                            withTimeoutOrNull(QQ_SEARCH_BUDGET_MS) {
                                SourceRouter.searchSongs(MusicSource.QQMUSIC, keyword, 30)
                            }.orEmpty()
                        } catch (e: Exception) {
                            android.util.Log.w("SearchViewModel", "qq search failed", e)
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }

                    // 只在「查询没变」时追加：用户已经改了关键词的话，这批结果已经过期，
                    // 写回去就是「搜 A 显示 B」。
                    if (qq.isNotEmpty() && _query.value == keyword) {
                        _songs.value = (netease + qq).distinctBy { it.trackKey }
                    }
                    _sourceCounts.value = netease.size to qq.size
                    // ③ 两个源都没结果，且主源确实报过错 ⇒ 让界面能显示错误/重试，
                    // 而不是一块什么都没有的空白。
                    if (netease.isEmpty() && qq.isEmpty() && neteaseError != null) {
                        _error.value = neteaseError?.message
                    }
                    android.util.Log.i(
                        "SearchViewModel",
                        "aggregate query='$keyword' netease=${netease.size} qq=${qq.size} " +
                            "elapsed=${System.currentTimeMillis() - startedAt}ms",
                    )
                }
                10 -> {
                    _sourceCounts.value = null
                    val response = RetrofitClient.api.searchAlbum(keyword = _query.value, type = 10)
                    _albums.value = response.result?.albums ?: emptyList()
                    _songs.value = emptyList()
                    _artists.value = emptyList()
                }
                100 -> {
                    _sourceCounts.value = null
                    val response = RetrofitClient.api.searchArtist(keyword = _query.value, type = 100)
                    _artists.value = response.result?.artists ?: emptyList()
                    _songs.value = emptyList()
                    _albums.value = emptyList()
                }
            }
        } catch (e: Exception) {
            _error.value = e.message
            // v2.1.0 · E：只有在**一条结果都没有**时才清空。聚合搜索下一侧失败很正常
            // （QQ 未登录、被限流），此时把另一侧已经拿到的结果清掉是纯损失。
            if (_songs.value.isEmpty() && _albums.value.isEmpty() && _artists.value.isEmpty()) {
                clearResults()
            }
        } finally {
            _isLoading.value = false
        }
    }

    fun clearQuery() {
        _query.value = ""
        clearResults()
    }

    private fun clearResults() {
        _songs.value = emptyList()
        _albums.value = emptyList()
        _artists.value = emptyList()
        // 计数必须跟着一起清，否则清空搜索框后还会留着一行
        // 「网易云 20 首 · QQ 音乐 6 首」挂在那儿（清空与清结果永远是同一件事）。
        _sourceCounts.value = null
    }
}