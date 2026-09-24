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

    private val _currentType = MutableStateFlow(1)
    val currentType: StateFlow<Int> = _currentType

    private var searchJob: Job? = null

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
                    // v2.1.0 · E：**聚合搜索** —— 网易云结果在前、QQ 音乐结果接在后。
                    //
                    // 为什么聚合做在这一层（而不是各 Screen）：这里是「一次搜索」的唯一入口，
                    // UI 层只消费 `_songs` 列表，聚合放这里等于 UI 零改动就能搜到两个平台的歌。
                    //
                    // 为什么不做跨平台去重：同一首歌在两个平台上**不是同一份资产**
                    // （码率、版权、能否播放都可能不同），合并成一条会让用户点到一个
                    // 他其实没权限播放的来源；而「同名不同版」的误判又会把能放的版本藏起来。
                    // 两条并排显示 + 音源标识，是更诚实也更少猜的做法。
                    val netease = runCatching {
                        RetrofitClient.api.search(keyword = _query.value, type = 1).result?.songs
                    }.getOrNull().orEmpty()
                    val qq = if (QqClient.isLoggedIn() || QqAccountAvailability.allowAnonymousSearch) {
                        runCatching { SourceRouter.searchSongs(MusicSource.QQMUSIC, _query.value, 30) }
                            .getOrNull().orEmpty()
                    } else {
                        emptyList()
                    }
                    // 某一侧失败不能把另一侧的结果也吞掉：网易云挂了也应该能看到 QQ 的歌。
                    _songs.value = (netease + qq).distinctBy { it.trackKey }
                    _albums.value = emptyList()
                    _artists.value = emptyList()
                    android.util.Log.i(
                        "SearchViewModel",
                        "aggregate query='${_query.value}' netease=${netease.size} qq=${qq.size}",
                    )
                }
                10 -> {
                    val response = RetrofitClient.api.searchAlbum(keyword = _query.value, type = 10)
                    _albums.value = response.result?.albums ?: emptyList()
                    _songs.value = emptyList()
                    _artists.value = emptyList()
                }
                100 -> {
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
    }
}