package com.takahashirinta.ncrust.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.takahashirinta.ncrust.auth.NeteaseVipStore
import com.takahashirinta.ncrust.network.*
import com.takahashirinta.ncrust.qq.QqAccountAvailability
import com.takahashirinta.ncrust.qq.QqAuthStore
import com.takahashirinta.ncrust.qq.QqClient
import com.takahashirinta.ncrust.search.RankedSong
import com.takahashirinta.ncrust.search.SearchRanking
import com.takahashirinta.ncrust.search.TrackAccess
import com.takahashirinta.ncrust.search.TrackAvailability
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceRouter
import com.takahashirinta.ncrust.source.trackKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.takahashirinta.ncrust.ui.components.SourceCounts
import com.takahashirinta.ncrust.ui.components.SourceSearchStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SearchViewModel : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    /**
     * 两个平台的会员状态（v2.1.4），决定聚合结果怎么排（见 [SearchRanking]）。
     *
     * ## 为什么做成「注入的 lambda」而不是在这里读 SharedPreferences
     *
     * 这个 ViewModel 是纯 `ViewModel()`（没有 Application），而会员状态存在
     * SharedPreferences 里、需要 Context。为它换 `AndroidViewModel` 会牵动
     * `viewModel()` 的构造方式与既有的调用点；直接把 `Context` 传进来又会把
     * 一个 Application 级引用长期挂在这个 ViewModel 上。
     *
     * 做成 lambda 之后：① 每次搜索**现读**，登录/登出后立刻生效，不需要缓存失效逻辑；
     * ② 单测里可以直接换成一个返回固定值的 lambda，不必碰 Android。
     * 默认值 (`false to false`) 是保守的那一侧 —— 排序退化成 v2.1.3 的行为。
     */
    var vipFlagsProvider: () -> Pair<Boolean, Boolean> = { false to false }

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
    /**
     * v2.5.5 · G：逐源统计（含**状态**）。
     *
     * 旧类型是 `Pair<Int, Int>?` —— 它只能表达计数，于是「QQ 还没回来」被迫写成 0，
     * 界面上就是「QQ 音乐 0 首」那句假话。新类型见 [SourceCounts]。
     */
    private val _sourceCounts = MutableStateFlow<SourceCounts?>(null)
    val sourceCounts: StateFlow<SourceCounts?> = _sourceCounts

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

    /**
     * v2.5.5 · G：把两侧结果排序后发布。
     *
     * 从 `searchByType` 的 `1 ->` 分支里提出来只是因为那个分支现在多了一层
     * `coroutineScope { }`，内联的局部函数会横跨协程边界 —— 语义没变，
     * v2.1.4 的会员排序与 v2.3.0 的沉底规则**一个字没动**。
     */
    private fun publish(neteaseList: List<SongItem>, qqList: List<SongItem>) {
        val (neteaseVip, qqVip) = vipFlagsProvider()
        // v2.3.0 · C：`order` = v2.1.4 的 `rank`（会员买在哪家哪家先出）
        // + 把「服务端显式声明无版权」的行沉底。两者作用在不同的层，见其 KDoc。
        _songs.value = SearchRanking.order(
            netease = neteaseList.map {
                RankedSong(
                    it,
                    TrackAccess.ofNeteaseFee(it.fee),
                    TrackAvailability.of(it),
                )
            },
            qq = qqList.map {
                RankedSong(
                    it,
                    TrackAccess.ofQqMemberOnly(it.memberOnly),
                    TrackAvailability.of(it),
                )
            },
            neteaseVip = neteaseVip,
            qqVip = qqVip,
        ).map { it.value }.distinctBy { it.trackKey }
    }

    private suspend fun searchByType(type: Int) {
        _isLoading.value = true
        _error.value = null
        try {
            when (type) {
                1 -> {
                    // v2.1.0 · E：**聚合搜索** —— 网易云与 QQ 音乐。
                    //
                    // ## hotfix 3 留下的顺序契约（**本版没有改它**）
                    //
                    // 第一版写成「先 await 网易云、再 await QQ，最后一起发布」。这在 QQ 那条
                    // 通道慢或不可达时是灾难：OkHttp 的 connect/read 超时是 15/20 秒，
                    // 网易云的结果明明已经到手，却要陪着一起等 —— 用户看到的就是**一直转圈**。
                    // 现在的顺序：
                    //  ① 网易云（主源）拿到就**立刻发布并停止转圈**；
                    //  ② QQ（补充源）带**硬预算**地追加，超时就放弃这一轮；
                    //  ③ 两个源都空且网易云报过错 ⇒ 把错误交出去，界面不留一块哑掉的空白。
                    //
                    // ## v2.5.5 · G 改了两件事（都是「并发」与「状态」，不是「顺序」）
                    //
                    // **(1) 两个请求并发发起。** 旧实现是「await 网易云 → 再发 QQ」——
                    // 整体耗时是**和**而不是**最大值**。用户报告的「网易云秒出、QQ 5 秒后到」
                    // 里，那 5 秒中其实有一段是白白串行等出来的。
                    // `async`（默认 start = DEFAULT，立即开始）把两段重叠起来，
                    // 而**发布顺序一个字没改**：仍然是网易云一到就 publish。
                    //
                    // **(2) 统计量能表达「还没回来」。** 旧代码在网易云到手时写
                    // `_sourceCounts.value = netease.size to 0` —— 那个 0 在界面上是
                    // 「QQ 音乐 0 首」，而它的真实含义是「QQ 还没回来」。
                    // 用户据此以为 QQ 搜不到那首歌。现在写 [SourceSearchStatus.PENDING]，
                    // 界面显示「搜索中…」，QQ 回来后原地更新成计数。
                    val keyword = _query.value
                    val startedAt = System.currentTimeMillis()
                    val qqAllowed = QqClient.isLoggedIn() || QqAccountAvailability.allowAnonymousSearch

                    coroutineScope {
                        // 网易云：结果与异常一起回传，不用共享可变变量跨协程写。
                        // ⚠️ 只发**一次**请求：`runCatching` 包住调用，异常从 `exceptionOrNull()` 取，
                        // 绝不能为了拿异常而再调一次 `search(...)`（那会把一次搜索变成两次）。
                        val neteaseDeferred = async {
                            val outcome = runCatching {
                                RetrofitClient.api.search(keyword = keyword, type = 1).result?.songs
                            }
                            val failure = outcome.exceptionOrNull()
                            if (failure != null) {
                                android.util.Log.w("SearchViewModel", "netease search failed", failure)
                            }
                            outcome.getOrNull().orEmpty() to failure
                        }
                        // QQ：**硬预算**。超时/异常都只是「这一轮没有 QQ 结果」，
                        // 绝不能让它把已经可用的搜索结果拖住或清掉。
                        //
                        // 注意：这里不能用 `runCatching { withTimeoutOrNull { ... } }` ——
                        // runCatching 的 lambda 不是 suspend 的，里面调不了挂起函数。
                        val qqDeferred = if (qqAllowed) async {
                            try {
                                val r = withTimeoutOrNull(QQ_SEARCH_BUDGET_MS) {
                                    SourceRouter.searchSongs(MusicSource.QQMUSIC, keyword, 30)
                                }
                                // `withTimeoutOrNull` 返回 null = 预算用完 ⇒ 记成 TIMEOUT
                                // 而不是「0 首」。这两件事在界面上必须能区分。
                                r?.let { QqOutcome(it, timedOut = false) } ?: QqOutcome(emptyList(), timedOut = true)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                // 取消不是失败：用户改了关键词 / 离开页面时取消这一轮，
                                // 把它记成「失败」会让界面在下一次搜索开始前闪一条错误。
                                throw e
                            } catch (e: Exception) {
                                android.util.Log.w("SearchViewModel", "qq search failed", e)
                                // ★ 区分「预算用完」与「真的失败」：两者对用户的处置相同
                                //   （都要点重试），但把「连不上」说成「超时」是替他编原因。
                                QqOutcome(emptyList(), timedOut = false, failed = true)
                            }
                        } else {
                            null
                        }

                        // ① 主源到手即发布 —— 转圈到此结束，后面的 QQ 只是锦上添花。
                        //
                        // v2.1.4：发布前先按「用户有哪些平台的会员」排一次。此时 QQ 还没到，
                        // 但网易云自己的会员专享已经可以先排上去；等 QQ 到手会再排一次。
                        // 排序是纯函数且幂等，排两次不会抖。
                        val (netease, neteaseError) = neteaseDeferred.await()
                        publish(netease, emptyList())
                        _albums.value = emptyList()
                        _artists.value = emptyList()
                        _sourceCounts.value = SourceCounts(
                            neteaseCount = netease.size,
                            neteaseStatus = SourceSearchStatus.DONE,
                            qqCount = 0,
                            qqStatus = if (qqAllowed) SourceSearchStatus.PENDING else SourceSearchStatus.SKIPPED,
                        )
                        _isLoading.value = false

                        // ② 等补充源（预算已经在上面卡死，这里不会无限等）。
                        val qqOutcome = qqDeferred?.await() ?: QqOutcome(emptyList(), timedOut = false)
                        val qq = qqOutcome.songs

                        // 只在「查询没变」时追加：用户已经改了关键词的话，这批结果已经过期，
                        // 写回去就是「搜 A 显示 B」。
                        if (qq.isNotEmpty() && _query.value == keyword) {
                            publish(netease, qq)
                        }
                        if (_query.value == keyword) {
                            _sourceCounts.value = SourceCounts(
                                neteaseCount = netease.size,
                                neteaseStatus = SourceSearchStatus.DONE,
                                qqCount = qq.size,
                                qqStatus = when {
                                    !qqAllowed -> SourceSearchStatus.SKIPPED
                                    qqOutcome.timedOut -> SourceSearchStatus.TIMEOUT
                                    qqOutcome.failed -> SourceSearchStatus.ERROR
                                    else -> SourceSearchStatus.DONE
                                },
                            )
                        }
                        // ③ 两个源都没结果，且主源确实报过错 ⇒ 让界面能显示错误/重试，
                        // 而不是一块什么都没有的空白。
                        if (netease.isEmpty() && qq.isEmpty() && neteaseError != null) {
                            _error.value = neteaseError?.message
                        }
                        val (nVip, qVip) = vipFlagsProvider()
                        android.util.Log.i(
                            "SearchViewModel",
                            "aggregate query='$keyword' netease=${netease.size} qq=${qq.size} " +
                                "qqTimedOut=${qqOutcome.timedOut} qqAllowed=$qqAllowed " +
                                "vip(netease=$nVip qq=$qVip) " +
                                "elapsed=${System.currentTimeMillis() - startedAt}ms",
                        )
                    }
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            // ★ v2.5.5 · G：**取消必须原样抛出**，不能落进下面的 `catch (e: Exception)`。
            //
            // `searchJob.cancel()` 在用户每敲一个字（500ms debounce 之后）都会触发一次。
            // 旧写法把 `CancellationException` 当成普通异常：写 `_error`、并且在结果为空时
            // 调 `clearResults()` —— 表现是「正在打字时界面闪一下错误 / 上一轮结果被清空」。
            // 协程的取消是**控制流**不是错误，吞掉它还会破坏结构化并发
            // （父作用域无法感知子协程已取消）。
            throw e
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

/**
 * v2.5.5 · G：QQ 补充源这一轮的结果。
 *
 * 单独一个类型（而不是 `List<SongItem>`）是为了把「超时」这件事**带出来**：
 * 旧代码只有一个列表，超时与「确实 0 条」在类型上完全一样，
 * 于是界面只能显示「QQ 音乐 0 首」—— 而对超时来说那句话是错的。
 */
private data class QqOutcome(
    val songs: List<SongItem>,
    /** `withTimeoutOrNull` 返回 null ⇒ 预算用完。 */
    val timedOut: Boolean,
    /**
     * 抛了异常 ⇒ 真的失败（网络错误 / 服务端报错）。
     *
     * 与 [timedOut] 分开：两者对用户的处置相同（都要点重试），
     * 但把「连不上」显示成「超时」是替用户编原因。
     */
    val failed: Boolean = false,
)
