package ai.lightspeed.tipsy.shell.pages.search

import ai.lightspeed.tipsy.shell.analytics.Analytics
import ai.lightspeed.tipsy.shell.auth.Generations
import ai.lightspeed.tipsy.shell.pages.home.HomeFeedItem
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 搜索页的数据层（对应 `hooks/search/useSearch.ts` 495 行 + 页面层的 tab 状态）。
 *
 * ## 500ms 防抖只管「查」，不管「清」
 *
 * `handleChange` 立刻清空结果并置 loading，真正的请求延后 500ms
 * （`useSearch.ts:284-333`）。这个顺序很关键：先清再延后查，用户连打字时
 * 看到的是空列表 + spinner，而不是上一个词的结果。
 *
 * ## 两个查询的并发形态不同
 *
 * 创作者查询 **fire-and-forget**，角色查询 **await**（`useSearch.ts:298-299`）。
 * 因为 loading 态由角色查询关闭 —— 创作者结果晚到只影响 Creators tab 的内容，
 * 不该让 Characters tab 一直转圈。照抄这个不对称。
 *
 * ## 翻页三重守卫
 *
 * `loadingMore` 在途、`refreshing` 在途、当前列表为空 —— 三种情况都不翻页
 * （`useSearch.ts:219-234`）。第三条是防「空列表也触发 onEndReached」导致
 * page1/page2 并发；第二条是防筛选重查期间基于旧数据错页。
 *
 * ## auth generation + 搜索序号双闸门
 *
 * 每个响应回写前同时校验 [Generations.isAuthValid] 与本地搜索序号（快照均在
 * **请求出发前**取）。auth 轨拦下「搜索在途时登出」；搜索序号拦下 A/B 两次
 * 搜索乱序回包。Search 不拥有乐观列表 mutation，因此不能被 ChatList 等模块的
 * 全局 mutation generation 误伤。
 */
class SearchViewModel(
    private val api: SearchSource,
    private val generations: Generations,
    private val languageProvider: () -> String,
    private val nsfwProvider: () -> Boolean,
    /** 当前 userId；null = 未登录（最近搜索是 REQUIRED，未登录不发）。 */
    private val userIdProvider: () -> String?,
    private val scope: CoroutineScope? = null,
    /** 防抖窗口，测试注入 0 以免等待真实 500ms。 */
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
    private val logWarn: (String, Throwable?) -> Unit = { msg, t -> Log.w(TAG, msg, t) },
) : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private val coroutineScope: CoroutineScope get() = scope ?: viewModelScope

    /** 防抖任务：新输入取消旧的（`clearTimeout` 的对应物）。 */
    private var debounceJob: Job? = null

    /** 创作者首查是 fire-and-forget，必须单独持有，换词时才能取消。 */
    private var creatorSearchJob: Job? = null

    /** 建议词任务：输入变化即重发，旧的取消（RN 用 SWR key 变化实现）。 */
    private var suggestJob: Job? = null

    private var loadMoreJob: Job? = null

    private var recentHistoryJob: Job? = null
    private var popularTermsJob: Job? = null
    private var clearHistoryJob: Job? = null

    /** 每次输入变化 / 提交都递增；旧请求即使不响应取消，也不能覆盖新词。 */
    private var searchSeq = 0L

    /** 翻页游标。两个 tab 各自一份（`dataPages` 的对应物）。 */
    private var characterPage = 1
    private var creatorPage = 1
    private var characterEmptyAfterDedupeStreak = 0
    private var creatorEmptyAfterDedupeStreak = 0

    /** 曝光去重集合。**新搜索时清空** —— 否则换词后同一角色不再上报。 */
    private val exposedCharacterIds = mutableSetOf<String>()
    private val exposedCreatorIds = mutableSetOf<String>()

    // ── 生命周期 ────────────────────────────────

    /** 进页面：拉最近搜索 + 热门搜索。 */
    fun onAppear() {
        refreshRecentHistory()
        refreshPopularTerms()
    }

    /**
     * 登录态变化：取消所有旧账号工作并清空可关联到账号的查询/历史/session。
     * 登出只保留公开热门词且不发请求；登录才重拉最近与热门。
     */
    fun onAuthChanged(loggedIn: Boolean) {
        searchSeq += 1
        debounceJob?.cancel()
        creatorSearchJob?.cancel()
        suggestJob?.cancel()
        loadMoreJob?.cancel()
        recentHistoryJob?.cancel()
        popularTermsJob?.cancel()
        clearHistoryJob?.cancel()
        resetPaging()
        exposedCharacterIds.clear()
        exposedCreatorIds.clear()
        _state.value = SearchState(popularTerms = _state.value.popularTerms)
        if (loggedIn) onAppear()
    }

    /** 首次可见曝光。RN 搜索页无独立 page_exposure，只有搜索触发时的埋点。 */

    // ── 输入 ────────────────────────────────

    /**
     * 输入框内容变化（`page.tsx:62-67` 的 `onQueryChange`）。
     *
     * ⚠️ 只改输入值 + 清结果 + 回到未搜索态，**不发搜索请求** ——
     * 搜索由 [submitQuery]（回车/点建议词/点最近/点热门）触发。
     * 这里只顺带拉建议词。
     *
     * RN 的 `SearchBar.onQueryChange` 里 `searchWay.current = 'search'` ——
     * 用户手打字后来源就回到普通搜索（哪怕之前点的是热门词）。
     *
     * ## 与 RN 的一处**有意分歧**：建议词的拉取方式
     *
     * RN 用 SWR（`SuggestTags.tsx:22-34`）：key 随 query 变化即重发，带
     * `dedupingInterval: 10s` 去重、`keepPreviousData` 保留旧值。壳没有 SWR，
     * 这里用「取消旧任务 + 回写前比对 query」等效实现前两者；
     * **没有实现 10s 去重缓存** —— 逐字输入时壳会比 RN 多发几个 suggest 请求。
     * 该接口是轻量 `axiosPublic` 且无副作用，暂按可接受处理；若真机观察到
     * 请求量问题，再补一个按 query 归一化的短 TTL 缓存。
     */
    fun onQueryChange(query: String) {
        searchSeq += 1
        val previous = _state.value
        _state.value = previous.copy(
            query = query,
            tab = SearchTab.NONE,
            searchWay = SearchWay.SEARCH,
            // 清结果（`initState()`）
            characterResults = emptyList(),
            characterTotal = 0,
            characterOutcome = CharacterSearchOutcome.IDLE,
            characterSessionId = "",
            tagAggIds = emptyList(),
            creatorResults = emptyList(),
            creatorTotal = 0,
            creatorSessionId = "",
            isLoading = false,
            isRefreshing = false,
            isLoadingMore = false,
            suggestions = if (query.isBlank()) emptyList() else previous.suggestions,
        )
        resetPaging()
        exposedCharacterIds.clear()
        exposedCreatorIds.clear()
        debounceJob?.cancel()
        creatorSearchJob?.cancel()
        loadMoreJob?.cancel()
        fetchSuggestions(query)
        if (query.isEmpty()) refreshRecentHistory()
    }

    /**
     * 提交搜索（回车 / 点建议词 / 点最近搜索 / 点热门搜索）。
     *
     * [way] 决定埋点的 `search_type`：手动回车是 `search`，点最近是
     * `recent_search`，点热门是 `popular_search`（`page.tsx:133/140`）。
     */
    fun submitQuery(query: String, way: SearchWay = SearchWay.SEARCH) {
        if (query.isBlank()) return
        val seq = ++searchSeq

        // 立刻清结果 + 置 loading + 切到角色 tab（RN 的 handleChange + setTab）
        _state.value = _state.value.copy(
            query = query,
            searchWay = way,
            tab = SearchTab.CHARACTERS,
            characterResults = emptyList(),
            characterTotal = 0,
            characterOutcome = CharacterSearchOutcome.IDLE,
            characterSessionId = "",
            tagAggIds = emptyList(),
            creatorResults = emptyList(),
            creatorTotal = 0,
            creatorSessionId = "",
            suggestions = emptyList(),
            isLoading = true,
            isRefreshing = false,
            isLoadingMore = false,
        )
        resetPaging()
        exposedCharacterIds.clear()
        exposedCreatorIds.clear()

        // ⚠️ `session_id` 恒为空串，**不是**上一次搜索的 id ——
        // RN 的 `handleChange` 先 `initState()`（把 sessionIdRef 清成 ''）
        // 再发这个事件（`useSearch.ts:322-331`）。看起来像「带上了 session」
        // 其实永远是空的。照抄，不要「顺手修正」成上一次的 id
        Analytics.track(
            "search_trigger_page_exposure",
            mapOf(
                "search_type" to way.trackingValue,
                "query" to query,
                "session_id" to "",
                "platform" to "app",
            ),
        )

        debounceJob?.cancel()
        creatorSearchJob?.cancel()
        loadMoreJob?.cancel()
        suggestJob?.cancel()
        debounceJob = coroutineScope.launch {
            delay(debounceMillis)
            // RN 把原始 query 同时传给 API 和两类埋点；只用 trim 判空。
            // 保留首尾空格，避免 trigger/result funnel 的 query 不一致。
            runSearch(query, way, seq)
        }
    }

    /** 清空输入框（点 X）。 */
    fun clearQuery() = onQueryChange("")

    /** IME action 直接读 StateFlow 当前值，避免提交到重组前的 composing text。 */
    fun submitCurrentQuery() = submitQuery(_state.value.query)

    fun onTabChange(tab: SearchTab) {
        _state.value = _state.value.copy(tab = tab)
    }

    // ── 查询 ────────────────────────────────

    /**
     * 首查：创作者 fire-and-forget，角色 await（见类注释的不对称说明）。
     */
    private suspend fun runSearch(searchTerm: String, way: SearchWay, seq: Long) {
        val snapshot = generations.snapshot()

        // 创作者查询不 await —— 它晚到不该拖住 Characters tab 的 loading
        creatorSearchJob = coroutineScope.launch {
            queryCreators(searchTerm, page = 1, snapshot = snapshot, way = way, seq = seq)
        }
        try {
            queryCharacters(searchTerm, page = 1, snapshot = snapshot, way = way, seq = seq)
        } finally {
            // auth 失效时结果要丢，但当前查询的 spinner 仍必须收掉。
            if (searchSeq == seq) {
                _state.value = _state.value.copy(isLoading = false)
            }
        }
    }

    private suspend fun queryCharacters(
        searchTerm: String,
        page: Int,
        snapshot: Generations.Snapshot,
        way: SearchWay,
        seq: Long,
    ): Boolean {
        try {
            val result = api.searchCharacters(
                SearchCharacterQuery(
                    searchTerm = searchTerm,
                    page = page,
                    tagIds = emptyList(),
                    nsfw = nsfwProvider(),
                    languageCode = languageProvider(),
                    // P1 无筛选器：性别不发、排序恒 Recommended、分级恒 All
                    gender = null,
                    sorting = "Recommended",
                    contentRating = "All",
                ),
            )
            // 回写前校验：期间登出或已经提交新词都丢弃。
            if (!canWrite(snapshot, seq)) return false

            val state = _state.value
            if (page == 1) {
                _state.value = state.copy(
                    characterResults = result.hits.distinctBy { it.stableKey },
                    characterTotal = result.total,
                    characterOutcome = result.outcome,
                    characterSessionId = result.searchSessionId,
                    tagAggIds = result.tagAggIds,
                )
                Analytics.track(
                    "search_result_page_exposure",
                    mapOf(
                        "search_type" to way.trackingValue,
                        "query" to searchTerm,
                        "session_id" to result.searchSessionId,
                        "search_tab" to "character",
                        "character_sort_filter" to "Recommended",
                        // P1 无筛选器：RN 在无性别筛选时发的是 undefined（键存在值为空）
                        "character_gender" to null,
                        "tags" to "",
                        "platform" to "app",
                    ),
                )
            } else {
                val seen = state.characterResults.mapTo(HashSet()) { it.stableKey }
                _state.value = state.copy(
                    characterResults = state.characterResults +
                        result.hits.filter { seen.add(it.stableKey) },
                    characterTotal = result.total,
                    characterSessionId = result.searchSessionId.ifEmpty {
                        state.characterSessionId
                    },
                )
            }
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            if (!canWrite(snapshot, seq)) return false
            logWarn("角色搜索失败：page=$page", t)
            // 首页失败要回到 IDLE —— 否则空态会把「请求失败」当成「搜到 0 条」
            // 并诱导用户去创建角色（`useSearch.ts:169-177`）
            _state.value = _state.value.copy(
                characterOutcome = if (page == 1) {
                    CharacterSearchOutcome.IDLE
                } else {
                    _state.value.characterOutcome
                },
                toastKey = "Failed",
            )
            return false
        }
    }

    private suspend fun queryCreators(
        searchTerm: String,
        page: Int,
        snapshot: Generations.Snapshot,
        way: SearchWay,
        seq: Long,
    ): Boolean {
        try {
            val result = api.searchCreators(searchTerm, page)
            if (!canWrite(snapshot, seq)) return false

            val state = _state.value
            if (page == 1) {
                _state.value = state.copy(
                    creatorResults = result.hits.distinctBy { it.userId },
                    creatorTotal = result.total,
                    creatorSessionId = result.searchSessionId,
                )
                Analytics.track(
                    "search_result_page_exposure",
                    mapOf(
                        "search_type" to way.trackingValue,
                        "query" to searchTerm,
                        "session_id" to result.searchSessionId,
                        "search_tab" to "creator",
                        // 创作者 tab 无筛选维度，RN 发空串（不是 null）
                        "character_sort_filter" to "",
                        "character_gender" to "",
                        "tags" to "",
                        "platform" to "app",
                    ),
                )
            } else {
                val seen = state.creatorResults.mapTo(HashSet()) { it.userId }
                _state.value = state.copy(
                    creatorResults = state.creatorResults +
                        result.hits.filter { seen.add(it.userId) },
                    creatorTotal = result.total,
                    creatorSessionId = result.searchSessionId.ifEmpty {
                        state.creatorSessionId
                    },
                )
            }
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            if (!canWrite(snapshot, seq)) return false
            logWarn("创作者搜索失败：page=$page", t)
            _state.value = _state.value.copy(toastKey = "Failed")
            return false
        }
    }

    /**
     * 翻页（列表滚到底）。三重守卫见类注释。
     */
    fun loadMore() {
        val s = _state.value
        if (s.query.isBlank()) return
        if (s.isLoadingMore || s.isRefreshing) return
        if (loadMoreJob?.isActive == true) return

        val currentCount = when (s.tab) {
            SearchTab.CHARACTERS -> s.characterResults.size
            SearchTab.CREATORS -> s.creatorResults.size
            SearchTab.NONE -> return
        }
        // 首查还没返回（列表空）时不翻页 —— 否则空列表触发的 onEndReached
        // 会与首查并发，出现 page1/page2 同时在飞
        if (currentCount == 0) return

        val canLoadMore = when (s.tab) {
            SearchTab.CHARACTERS ->
                s.canLoadMoreCharacters &&
                    characterEmptyAfterDedupeStreak < MAX_EMPTY_DEDUPE_STREAK
            SearchTab.CREATORS ->
                s.canLoadMoreCreators && creatorEmptyAfterDedupeStreak < MAX_EMPTY_DEDUPE_STREAK
            SearchTab.NONE -> false
        }
        if (!canLoadMore) return

        // 与 page1 保持同一原始 query，不能从 page2 开始悄然 trim。
        val searchTerm = s.query
        val way = s.searchWay
        val tab = s.tab
        val seq = searchSeq
        loadMoreJob = coroutineScope.launch {
            val snapshot = generations.snapshot()
            _state.value = _state.value.copy(isLoadingMore = true, isLoading = true)
            try {
                if (tab == SearchTab.CHARACTERS) {
                    while (true) {
                        val before = _state.value.characterResults.size
                        val nextPage = characterPage + 1
                        if (!queryCharacters(searchTerm, nextPage, snapshot, way, seq)) break
                        characterPage = nextPage
                        val after = _state.value.characterResults.size
                        characterEmptyAfterDedupeStreak = if (after == before) {
                            characterEmptyAfterDedupeStreak + 1
                        } else {
                            0
                        }
                        if (after > before || !_state.value.canLoadMoreCharacters) break
                        if (characterEmptyAfterDedupeStreak >= MAX_EMPTY_DEDUPE_STREAK) break
                    }
                } else {
                    while (true) {
                        val before = _state.value.creatorResults.size
                        val nextPage = creatorPage + 1
                        if (!queryCreators(searchTerm, nextPage, snapshot, way, seq)) break
                        creatorPage = nextPage
                        val after = _state.value.creatorResults.size
                        creatorEmptyAfterDedupeStreak = if (after == before) {
                            creatorEmptyAfterDedupeStreak + 1
                        } else {
                            0
                        }
                        if (after > before || !_state.value.canLoadMoreCreators) break
                        if (creatorEmptyAfterDedupeStreak >= MAX_EMPTY_DEDUPE_STREAK) break
                    }
                }
            } finally {
                if (searchSeq == seq) {
                    _state.value = _state.value.copy(isLoadingMore = false, isLoading = false)
                }
            }
        }
    }

    // ── 建议词 / 最近 / 热门 ────────────────────────────────

    private fun fetchSuggestions(query: String) {
        suggestJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val snapshot = generations.snapshot()
        suggestJob = coroutineScope.launch {
            try {
                val list = api.fetchSuggestions(trimmed)
                if (!generations.isAuthValid(snapshot)) return@launch
                // 输入已经变了就丢弃（旧词的建议不该盖新词的）
                if (_state.value.query.trim() != trimmed) return@launch
                _state.value = _state.value.copy(suggestions = list)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                // 建议词是增强功能，失败**静默** —— RN 侧 SWR 也不弹 Toast
                logWarn("建议词拉取失败", t)
            }
        }
    }

    /**
     * 最近搜索。**未登录不发** —— 该接口是 `REQUIRED`，未登录时
     * ApiClient 会直接拒绝，发了只是白拿一条错误日志（对齐 RN 的
     * `if (!userId) return`，`RecentSearch.tsx:32-35`）。
     */
    private fun refreshRecentHistory() {
        recentHistoryJob?.cancel()
        if (userIdProvider() == null) {
            _state.value = _state.value.copy(recentSearches = emptyList())
            return
        }
        val snapshot = generations.snapshot()
        recentHistoryJob = coroutineScope.launch {
            try {
                val list = api.fetchRecentHistory()
                if (!generations.isAuthValid(snapshot)) return@launch
                _state.value = _state.value.copy(recentSearches = list)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logWarn("最近搜索拉取失败", t)
            }
        }
    }

    private fun refreshPopularTerms() {
        popularTermsJob?.cancel()
        val snapshot = generations.snapshot()
        popularTermsJob = coroutineScope.launch {
            try {
                val list = api.fetchPopularTerms()
                if (!generations.isAuthValid(snapshot)) return@launch
                _state.value = _state.value.copy(popularTerms = list)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logWarn("热门搜索拉取失败", t)
            }
        }
    }

    fun onClearHistoryRequest() {
        _state.value = _state.value.copy(showClearHistoryDialog = true)
    }

    fun onClearHistoryDismiss() {
        _state.value = _state.value.copy(showClearHistoryDialog = false)
    }

    /** 确认清空最近搜索。成功后本地也清空（RN 同序：先 await 再清）。 */
    fun onClearHistoryConfirm() {
        clearHistoryJob?.cancel()
        val snapshot = generations.snapshot()
        clearHistoryJob = coroutineScope.launch {
            try {
                api.clearRecentHistory()
                if (!generations.isAuthValid(snapshot)) return@launch
                _state.value = _state.value.copy(
                    recentSearches = emptyList(),
                    showClearHistoryDialog = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (!generations.isAuthValid(snapshot)) return@launch
                logWarn("清空最近搜索失败", t)
                // 失败要关弹窗（否则用户卡在弹窗里反复点），但列表**不清**
                _state.value = _state.value.copy(
                    showClearHistoryDialog = false,
                    toastKey = "Failed",
                )
            }
        }
    }

    // ── 埋点 ────────────────────────────────

    /**
     * 角色卡曝光（列表可见回调）。**集合去重** —— 同一张卡滚进滚出只报一次。
     *
     * ⚠️ 筛选重查在途（[SearchState.isRefreshing]）时不报：此刻列表展示的还是
     * 旧结果，而去重集合已按新筛选清空，会把旧卡当新卡重报（`useSearch.ts:337`）。
     */
    fun onCharacterExposed(item: HomeFeedItem.Character) {
        if (_state.value.isRefreshing) return
        if (!exposedCharacterIds.add(item.characterId)) return
        Analytics.track(
            "search_content_exposure",
            mapOf(
                "content_id" to item.characterId,
                "session_id" to _state.value.characterSessionId,
                "search_tab" to "character",
            ),
        )
    }

    /**
     * 角色卡点击埋点。HomeCard 在搜索结果里由页面接管点击，因此这里显式补齐
     * HomeCard 原本会发送的通用点击与有 session 时的搜索归因事件。
     */
    fun onCharacterClick(item: HomeFeedItem.Character, itemPosition: Int) {
        Analytics.track(
            "character_page_click",
            mapOf(
                "scene" to "searchCharacter",
                "type" to "character",
                "characterId" to item.characterId,
                "creatorId" to item.creatorId,
                "nsfw" to item.nsfw,
                "isStory" to false,
                "filter" to P1_FILTER_JSON,
                "gender" to "All",
                "selectedTags" to "[]",
                "banner" to "",
            ),
        )
        val sessionId = _state.value.characterSessionId
        if (sessionId.isNotEmpty()) {
            Analytics.track(
                "search_content_click",
                mapOf(
                    "content_id" to item.characterId,
                    "session_id" to sessionId,
                    "item_position" to itemPosition,
                    "search_tab" to "character",
                ),
            )
        }
    }

    /**
     * 创作者行曝光。发**两个**事件（`useSearch.ts:348-362`）：
     * `character_page_exposure`（每次可见都发，不去重）与
     * `search_content_exposure`（去重）。别合并 —— 前者是通用的主页曝光口径。
     */
    fun onCreatorExposed(creator: CreatorResult) {
        Analytics.track(
            "character_page_exposure",
            mapOf("scene" to "searchCreator", "creator_id" to creator.userId),
        )
        if (creator.userId.isBlank()) return
        if (!exposedCreatorIds.add(creator.userId)) return
        Analytics.track(
            "search_content_exposure",
            mapOf(
                "content_id" to creator.userId,
                "session_id" to _state.value.creatorSessionId,
                "search_tab" to "creator",
            ),
        )
    }

    /** 创作者行点击埋点（`CreatorResultItem.tsx:52-65`，两个事件）。 */
    fun onCreatorClick(creator: CreatorResult, itemPosition: Int) {
        Analytics.track(
            "character_page_click",
            mapOf("scene" to "searchCreator", "creator_id" to creator.userId),
        )
        val sessionId = _state.value.creatorSessionId
        if (sessionId.isNotEmpty()) {
            Analytics.track(
                "search_content_click",
                mapOf(
                    "content_id" to creator.userId,
                    "session_id" to sessionId,
                    "item_position" to itemPosition,
                    "search_tab" to "creator",
                ),
            )
        }
    }

    fun consumeToast() {
        _state.value = _state.value.copy(toastKey = null)
    }

    private fun resetPaging() {
        characterPage = 1
        creatorPage = 1
        characterEmptyAfterDedupeStreak = 0
        creatorEmptyAfterDedupeStreak = 0
    }

    private fun canWrite(snapshot: Generations.Snapshot, seq: Long): Boolean =
        searchSeq == seq && generations.isAuthValid(snapshot)

    companion object {
        private const val TAG = "SearchViewModel"
        private const val P1_FILTER_JSON =
            "{\"sorting\":\"Recommended\",\"gender\":\"All\",\"selectedTags\":[]}"
        private const val MAX_EMPTY_DEDUPE_STREAK = 3

        /** 防抖窗口（`useSearch.ts:308`）。 */
        const val DEBOUNCE_MILLIS = 500L
    }
}
