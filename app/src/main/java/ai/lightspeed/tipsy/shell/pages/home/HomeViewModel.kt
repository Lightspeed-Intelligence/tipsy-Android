package ai.lightspeed.tipsy.shell.pages.home

import ai.lightspeed.tipsy.shell.analytics.Analytics
import ai.lightspeed.tipsy.shell.network.ApiException
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.UUID

/**
 * Home 的编排器（方案 §8.1 Home 行）。
 *
 * ## 职责边界
 *
 * 拉数据、维护每系列游标与 session、去重、翻页、埋点。**不碰 UI**，
 * 也不做导航 —— 导航一律经 Router（方案 §4.7 单一入口）。
 *
 * ## 本包做到哪（明确边界，避免当成漏实现）
 *
 * 已做：6 个系列的列表 + 分页 + 下拉刷新 + 性别筛选 + session 语义 + 去重续拉
 * + 5 个页面级埋点。
 *
 * **未做**（下一包）：冷启动缓存（`useForYouListCache` 的信封 + authScope 门禁
 * + 7 天 TTL）、标签筛选抽屉（`HomeFilterDrawer` 382 行）、banner（`CardBanner`
 * 946 行，方案 §8.1 评估留 RN Surface）、每日彩蛋弹窗、`character_page_exposure`
 * 的可见性去重上报。
 */
class HomeViewModel(
    private val api: HomeFeedSource,
    private val filters: HomeFilters,
    private val languageProvider: () -> String,
    /** 注入是为了测试；生产用 viewModelScope。 */
    private val scope: CoroutineScope? = null,
    /**
     * For You 冷启动种子缓存。null = 不启用（测试里多数用例不关心种子）。
     */
    private val cache: HomeForYouCache? = null,
    /** 当前 userId，用于 authScope 门禁。未登录返回 null。 */
    private val userIdProvider: () -> String? = { null },
    /**
     * 失败诊断日志。默认走 `android.util.Log`。
     *
     * 注入而非直接调用：JVM 单测里 `android.util.Log` 是抛 "not mocked" 的桩。
     * 同 `EmailLoginViewModel.logWarn` 的做法。
     */
    private val logWarn: (String, Throwable?) -> Unit = { msg, t -> Log.w(TAG, msg, t) },
) : ViewModel() {

    private val _state = MutableStateFlow(
        HomeState(
            gender = filters.readGender(),
            nsfw = filters.readNsfw(),
        ),
    )
    val state: StateFlow<HomeState> = _state.asStateFlow()

    /** 每个系列独立的游标：切回来时保留已加载内容与页码（对齐 RN 的 SWR 缓存）。 */
    private val cursors = mutableMapOf<HomeSeries, SeriesCursor>()

    /** 每个系列已加载完成的数据。切 Tab 不丢。 */
    private val loaded = mutableMapOf<HomeSeries, List<HomeFeedItem>>()

    /**
     * 冷启动种子的「锁定头」（For You 专用）。
     *
     * 真实第 0 页到达时把它放在最前、再 union 真实数据（对齐 RN 的
     * `unionBy(displayCachedList, flatList, key)`，`home.tsx:711`）——
     * **不是直接丢弃种子**：那会让首屏内容在真实数据到达瞬间整屏跳变。
     * 首页之后（翻页 / 刷新）不再参与，置 null。
     */
    private var lockedHead: List<HomeFeedItem> = emptyList()

    /**
     * 当前在飞的请求。切系列/刷新时取消 ——
     * 不取消的表现是「切到 B 系列后 A 的响应到达并覆盖了 B 的列表」。
     */
    private var inFlight: Job? = null

    private val workScope: CoroutineScope get() = scope ?: viewModelScope

    /** 页面首次可见时调（对齐 `home.tsx:1787-1789` 的挂载 effect）。 */
    fun onFirstAppear() {
        Analytics.track("page_exposure", mapOf("page_name" to "discover", "platform" to "app"))
        trackSubpageExposure(_state.value.selectedSeries)
        seedFromCache()
        loadIfNeeded(_state.value.selectedSeries)
    }

    /**
     * 冷启动种子：先显示上次的前 5 条，避免白屏（方案 §4.6）。
     *
     * ⚠️ **只在 For You 且列表为空时**。种子是按 For You 拉的，显示在其他系列
     * 下就是错数据。三个门禁（version / authScope / TTL）在 [HomeForYouCache] 里。
     *
     * 种子进 `items` 但**不建游标** —— 真实第一页到达后走 [mergeSeed] 覆盖，
     * 不能让种子把 `nextPage` 顶成 1，否则首屏永远从第 2 页开始拉。
     */
    private fun seedFromCache() {
        val cache = cache ?: return
        if (_state.value.selectedSeries != HomeSeries.FOR_YOU) return
        if (_state.value.items.isNotEmpty()) return
        val seed = cache.read(
            authScope = HomeForYouCache.authScopeOf(userIdProvider()),
            gender = _state.value.gender,
        )
        if (seed.isEmpty()) return
        // ⚠️ 种子**不写进 `loaded`** —— `loadIfNeeded` 用
        // `loaded[series]?.isNotEmpty()` 判「已有数据就不拉」，写进去会让
        // 首屏永远停在种子上、真实数据一次都不拉。存 [lockedHead] 里，
        // 第 0 页到达时作为锁定头参与合并
        lockedHead = seed
        // isInitialLoading 置 false：有种子就不该显示全屏 spinner
        _state.value = _state.value.copy(items = seed, isInitialLoading = false)
    }

    /** Tab 重新获得焦点（切走再切回）。RN 的 `isFocused` effect 会重报子页曝光。 */
    fun onReappear() {
        trackSubpageExposure(_state.value.selectedSeries)
    }

    fun onSeriesSelected(series: HomeSeries) {
        val current = _state.value
        if (series == current.selectedSeries) return

        Analytics.track(
            "discover_page_tab_click",
            mapOf("platform" to "Android", "tab_type" to series.tabType),
        )
        // 切系列立刻换显示内容（已加载过的直接回显，对齐 SWR keepPreviousData）
        inFlight?.cancel()
        val cached = loaded[series].orEmpty()
        _state.value = current.copy(
            selectedSeries = series,
            items = cached,
            isInitialLoading = false,
            isRefreshing = false,
            isLoadingMore = false,
            hasReachedEnd = cursors[series]?.hasReachedEnd ?: false,
            errorMessage = null,
        )
        trackSubpageExposure(series)
        loadIfNeeded(series)
    }

    /**
     * 切性别。
     *
     * 写回 `config-persist-storage` 并**清掉所有系列的游标** ——
     * 筛选是全局的，不是当前系列独有（RN 侧 gender 进每个系列的 SWR key）。
     * 只清当前系列会让「切性别 → 切到别的系列」显示上一个性别的结果。
     */
    fun onGenderSelected(gender: HomeGender) {
        if (gender == _state.value.gender) return
        filters.writeGender(gender)
        inFlight?.cancel()
        cursors.clear()
        loaded.clear()
        _state.value = _state.value.copy(
            gender = gender,
            items = emptyList(),
            hasReachedEnd = false,
            errorMessage = null,
        )
        loadIfNeeded(_state.value.selectedSeries)
    }

    /**
     * 打开筛选抽屉。
     *
     * 顺手拉标签目录 —— **失败不阻塞抽屉打开**（抽屉里还有性别与重置可用）。
     * RN 的 `hydrateTags` 也是 `console.warn` 后咽掉（`config_persist.ts:321`）。
     */
    fun onFilterDrawerOpen() {
        _state.value = _state.value.copy(isFilterDrawerOpen = true)
        if (_state.value.tagCatalog.isNotEmpty()) return
        workScope.launch {
            val tags = runCatching { api.fetchTags(_state.value.nsfw) }
                .onFailure { logWarn("标签目录拉取失败，抽屉只显示性别筛选", it) }
                .getOrNull()
                ?: return@launch
            _state.value = _state.value.copy(tagCatalog = tags)
        }
    }

    fun onFilterDrawerDismiss() {
        _state.value = _state.value.copy(isFilterDrawerOpen = false)
    }

    /**
     * 应用标签勾选。
     *
     * 语义与 [onGenderSelected] 一致：**清所有系列的游标与已加载数据**，
     * 因为标签是跨系列的筛选条件。相同勾选直接返回，避免无谓重拉。
     *
     * ⚠️ 只保留仍在目录里的 id（对齐 `HomeFilterDrawer.tsx:80` 的
     * `filter((id) => visibleTagIds.has(id))`）—— 目录随 nsfw 变化，
     * 留着不存在的 id 会让请求带上后端不认识的标签，静默返回空列表。
     */
    fun onTagsApplied(tagIds: List<String>) {
        val visible = _state.value.tagCatalog.mapTo(HashSet()) { it.id }
        val next = tagIds.filter { it in visible }
        if (next == _state.value.selectedTagIds) {
            _state.value = _state.value.copy(isFilterDrawerOpen = false)
            return
        }
        inFlight?.cancel()
        // ⚠️ **只清受标签影响的系列**，不是 cursors.clear()。
        // Following / World 不发标签，它们的结果不会因改标签而变 —— 一起清掉
        // 会让用户切回去时白等一次加载，而拿到的内容完全一样（RN 侧靠 SWR key
        // 不含 tags 自然保留，这里要显式做到同一效果）
        cursors.keys.retainAll { !it.supportsTagFilter }
        loaded.keys.retainAll { !it.supportsTagFilter }
        val current = _state.value.selectedSeries
        _state.value = _state.value.copy(
            selectedTagIds = next,
            isFilterDrawerOpen = false,
            // 当前系列不受标签影响时（理论上抽屉不该开着）保留其列表
            items = if (current.supportsTagFilter) emptyList() else _state.value.items,
            hasReachedEnd = if (current.supportsTagFilter) false else _state.value.hasReachedEnd,
            errorMessage = null,
        )
        loadIfNeeded(current)
    }

    /**
     * 下拉刷新：**换 session**、从第 0 页重来。
     *
     * 保留旧内容直到新数据到达（`isRefreshing` 而非清空）—— 清空会让下拉时
     * 整屏闪白，且失败后用户什么都看不到。
     */
    fun onRefresh() {
        val series = _state.value.selectedSeries
        inFlight?.cancel()
        cursors.remove(series)
        _state.value = _state.value.copy(isRefreshing = true, errorMessage = null)
        load(series, isRefresh = true)
    }

    /** 滚到底部附近时调。幂等 —— 重复调用不会重复发请求。 */
    fun onLoadMore() {
        val current = _state.value
        if (current.hasReachedEnd) return
        if (current.isInitialLoading || current.isRefreshing || current.isLoadingMore) return
        if (inFlight?.isActive == true) return
        val cursor = cursors[current.selectedSeries] ?: return
        if (cursor.nextPage == 0) return // 首页还没成功，交给 loadIfNeeded
        _state.value = current.copy(isLoadingMore = true)
        load(current.selectedSeries, isRefresh = false)
    }

    /**
     * 语言变化后重拉（方案 §8.4 第 2 条：账号语言 ≠ 设备语言时冷启动数秒后
     * settle，触发换 session 强拉）。
     *
     * 由 Fragment 订阅 `L10n.languageFlow` 调用。语言进 filterKey，所以这里
     * 只需清游标即可换 session。
     */
    fun onLanguageSettled() {
        inFlight?.cancel()
        cursors.clear()
        loaded.clear()
        _state.value = _state.value.copy(items = emptyList(), hasReachedEnd = false)
        loadIfNeeded(_state.value.selectedSeries)
    }

    /** nsfw 镜像变化（登录后 `user.nsfw` 翻转）。同语言处理。 */
    fun onNsfwChanged(nsfw: Boolean) {
        if (nsfw == _state.value.nsfw) return
        inFlight?.cancel()
        cursors.clear()
        loaded.clear()
        _state.value = _state.value.copy(nsfw = nsfw, items = emptyList(), hasReachedEnd = false)
        loadIfNeeded(_state.value.selectedSeries)
    }

    /** 登录/登出：账号相关数据必须重拉（For You 与 Following 都按 token 个性化）。 */
    fun onAuthChanged() {
        inFlight?.cancel()
        cursors.clear()
        loaded.clear()
        _state.value = _state.value.copy(
            nsfw = filters.readNsfw(),
            items = emptyList(),
            hasReachedEnd = false,
            errorMessage = null,
        )
        loadIfNeeded(_state.value.selectedSeries)
    }

    /** 卡片曝光。uid 排队由 [Analytics] 统一处理（见该类注释）。 */
    fun onItemExposed(item: HomeFeedItem) {
        val series = _state.value.selectedSeries
        Analytics.track("character_page_exposure", exposureParams(item, series))
    }

    fun onItemClicked(item: HomeFeedItem) {
        val series = _state.value.selectedSeries
        Analytics.track("character_page_click", exposureParams(item, series))
    }

    private fun exposureParams(item: HomeFeedItem, series: HomeSeries): Map<String, Any?> {
        // filter 字段是 RN 侧 `JSON.stringify(logparams)` 的产物。三个字段的
        // 顺序照 `HomeCard.tsx` 的对象字面量序 —— 后端按字符串存，顺序变化会
        // 让同一筛选在报表里被当成两种
        val gender = _state.value.gender.storedValue
        // ⚠️ 埋点里的 selectedTags 是**用户的勾选原样**，不按系列过滤
        // （`home.tsx:1398` 直接传 `selectedTags.tags`）—— 即使 Following
        // 请求时不带标签，埋点里仍记着用户当时勾了什么。按系列清空会让归因失真
        val tagsJson = JSONArray(_state.value.selectedTagIds).toString()
        val filterJson = """{"gender":"$gender","selectedTags":$tagsJson}"""
        val common = mapOf(
            "scene" to series.key,
            "filter" to filterJson,
            "gender" to gender,
            "selectedTags" to tagsJson,
            "banner" to "",
        )
        return when (item) {
            is HomeFeedItem.Character -> common + mapOf(
                "type" to "character",
                "characterId" to item.characterId,
                "creatorId" to item.creatorId,
                "nsfw" to item.nsfw,
                "isStory" to false,
            )
            // ⚠️ story 的 characterId 字段传的是 **story_id**，isStory=true
            // （`HomeStoryCard.tsx:150-162`）—— 不是另起一个字段名
            is HomeFeedItem.Story -> common + mapOf(
                "type" to "character",
                "characterId" to item.storyId,
                "creatorId" to item.creatorId,
                "nsfw" to item.nsfw,
                "isStory" to true,
            )
            // World 卡片在 RN 侧 `disableExposureTracking`，走独立的
            // trackSimulatorCardExposure —— 那套属 W4，这里不发错事件
            is HomeFeedItem.World -> emptyMap()
        }
    }

    private fun trackSubpageExposure(series: HomeSeries) {
        Analytics.track(
            "discover_subpage_exposure",
            mapOf("platform" to "Android", "tab_type" to series.tabType),
        )
    }

    /** 没有数据且没在飞就拉第一页；已有数据直接返回（对齐 SWR 的缓存命中）。 */
    private fun loadIfNeeded(series: HomeSeries) {
        if (loaded[series]?.isNotEmpty() == true) return
        _state.value = _state.value.copy(
            // ⚠️ 已有种子（或任何已显示内容）时**不置** isInitialLoading ——
            // 那会在种子之上再盖一个全屏 spinner，等于种子白读了。
            // 这条被 `有种子时先显示种子且不显示 spinner` 逼出来：
            // 第一版无条件置 true，种子刚设好就被覆盖
            isInitialLoading = _state.value.items.isEmpty(),
            errorMessage = null,
        )
        load(series, isRefresh = false)
    }

    private fun load(series: HomeSeries, isRefresh: Boolean) {
        inFlight = workScope.launch {
            runCatching { loadPageChain(series, isRefresh) }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    onLoadFailed(series, error)
                }
        }
    }

    /**
     * 拉一页，并在「去重后无新增」时**主动续拉**（方案 §8.4 第 3 条）。
     *
     * 限 [MAX_EMPTY_DEDUPE_STREAK] 次连续续拉 —— 不限次时异常数据会形成无限循环。
     */
    private suspend fun loadPageChain(series: HomeSeries, isRefresh: Boolean) {
        while (true) {
            val filterKey = currentFilterKey(series)
            val cursor = cursors[series]
                ?.takeIf { it.filterKey == filterKey }
                ?: SeriesCursor(sessionId = newSessionId(), filterKey = filterKey)

            val page = api.fetchPage(
                series = series,
                page = cursor.nextPage,
                gender = _state.value.gender,
                nsfw = _state.value.nsfw,
                languageCode = languageProvider(),
                // ⚠️ Following / World **不带标签**（`useHomeCharacterLists.ts:89`
                // 的 `isFollowing ? [] : tags`）。带上会把关注列表筛掉大半，
                // 而这两个系列 UI 上没有筛选入口，用户无从发现自己被筛了
                tagIds = if (series.supportsTagFilter) _state.value.selectedTagIds else emptyList(),
                contentType = null,
                sessionId = cursor.sessionId,
            )

            // 到底判定：World 用 has_more，其余用**过滤前**的原始条数
            // （items 为空但 rawItemCount > 0 说明这页全是暂不支持的类型，还有下一页）
            val reachedEnd = page.hasMore?.let { !it } ?: (page.rawItemCount == 0)

            val existing = when {
                // 下拉刷新的第 0 页：清空重来，**种子也不留**
                // （用户主动刷新就是要新内容，RN 的 setShowForYouCache(false) 同义）
                isRefresh && cursor.nextPage == 0 -> {
                    lockedHead = emptyList()
                    emptyList()
                }
                // 冷启动第 0 页：种子作为锁定头在前（`home.tsx:711` 的 unionBy 顺序），
                // 真实数据去重后追加。种子里已有的角色不会重复出现
                cursor.nextPage == 0 -> lockedHead
                else -> loaded[series].orEmpty()
            }
            // 去重按 stableKey（含 requestId 的那个）。For You 翻页实测每页
            // 1~3 条重复，全量替换会让可见卡片重配（方案 §8.4 第 1 条）
            val seen = existing.mapTo(HashSet()) { it.stableKey }
            val fresh = page.items.filter { seen.add(it.stableKey) }
            val merged = existing + fresh

            // ⚠️ streak 必须存在 cursor 里、**跨 onLoadMore 调用累计**。
            // 用一个本地 `guard` 变量限次是不够的：那个计数每次 onLoadMore 都从 0
            // 重来，用户持续下滑就能反复触发「拉 3 页全重复」，仍然形成请求风暴
            // （只是被滚动手势节流，不是被限次挡住）。
            val streak = if (fresh.isEmpty()) cursor.emptyAfterDedupeStreak + 1 else 0
            loaded[series] = merged
            cursors[series] = cursor.copy(
                nextPage = cursor.nextPage + 1,
                hasReachedEnd = reachedEnd,
                emptyAfterDedupeStreak = streak,
            )

            // 写种子：只有 For You 的第 0 页（对齐 `useHomeCharacterLists.ts:163-169`
            // 的 `forYouFirstPage` effect）。用**原始响应片段**而不是模型，见
            // HomeForYouCache 类注释
            if (series == HomeSeries.FOR_YOU && cursor.nextPage == 0) {
                page.rawList?.let { raw ->
                    cache?.write(
                        authScope = HomeForYouCache.authScopeOf(userIdProvider()),
                        gender = _state.value.gender,
                        rawItems = raw,
                    )
                }
                // 第 0 页已落地，锁定头的使命结束 —— 留着会让后续刷新把
                // 旧种子又插回列表头
                lockedHead = emptyList()
            }

            if (series == _state.value.selectedSeries) {
                _state.value = _state.value.copy(
                    items = merged,
                    isInitialLoading = false,
                    isRefreshing = false,
                    isLoadingMore = false,
                    hasReachedEnd = reachedEnd,
                    errorMessage = null,
                )
            }

            // 有新增、或已到底 → 停。
            if (fresh.isNotEmpty() || reachedEnd) return

            // 去重后空页 → 主动续拉，但**受累计 streak 限制**（方案 §8.4 第 3 条
            // 的「限连续 3 页」）。达到上限就停下，等用户下一次手动下滑或刷新 ——
            // 不限次时异常数据（后端一直返回同一页）会打爆请求
            if (streak >= MAX_EMPTY_DEDUPE_STREAK) {
                if (series == _state.value.selectedSeries) {
                    _state.value = _state.value.copy(
                        isInitialLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                    )
                }
                return
            }
        }
    }

    /**
     * 失败处理。
     *
     * ⚠️ **已有数据时不清列表、不展示错误** —— 翻页失败把用户正在看的内容抹掉
     * 是比"翻页没成功"严重得多的问题。只有首屏（列表空）才给出错误文案。
     *
     * 文案取后端 `msg`，为空回落 [FALLBACK_ERROR_KEY] —— 与登录页同一处理
     * （进度文档 §2.20 记的坑：置 null 等于什么都不弹）。
     */
    private fun onLoadFailed(series: HomeSeries, error: Throwable) {
        if (series != _state.value.selectedSeries) return
        val hasData = _state.value.items.isNotEmpty()
        val message = when {
            hasData -> null
            error is ApiException.Business -> error.serverMessage?.takeIf { it.isNotBlank() }
                ?: FALLBACK_ERROR_KEY
            else -> FALLBACK_ERROR_KEY
        }
        _state.value = _state.value.copy(
            isInitialLoading = false,
            isRefreshing = false,
            isLoadingMore = false,
            errorMessage = message,
        )
    }

    /**
     * 当前筛选指纹。
     *
     * 含 gender / nsfw / 语言 —— 任一变化都要换 session。RN 的 For You filterKey
     * 只含 gender+tags+contentTypes，nsfw 与语言靠"离开首页再回来"重置；
     * 壳内 Home 是常驻 Fragment，没有那个挂载周期可依赖（见 [SeriesCursor] 注释）。
     */
    private fun currentFilterKey(series: HomeSeries): String {
        val s = _state.value
        // 标签进指纹：勾选变化必须换 session，否则新筛选会复用旧推荐池，
        // 表现是「筛了标签但结果没怎么变」。
        //
        // ⚠️ **按系列算**：Following / World 不发标签（见 loadPageChain 的说明），
        // 所以它们的指纹里也不能含标签 —— 含了会让「改标签」白白作废这两个系列
        // 已缓存的列表与页码，用户切回去要重新加载，且结果完全一样
        val tags = if (series.supportsTagFilter) s.selectedTagIds.joinToString(",") else ""
        return "${s.gender.storedValue}|${s.nsfw}|${languageProvider()}|$tags"
    }

    /** 对齐 RN 的 `client_${uuid()}` 前缀 —— 后端按前缀区分客户端生成的 session。 */
    private fun newSessionId(): String = "client_${UUID.randomUUID()}"

    companion object {
        /**
         * 「去重后空页」的连续续拉上限。
         *
         * RN 限连续 3 页（方案 §8.4 第 3 条）。这里的 3 是**续拉次数**，
         * 与首次那页合计最多请求 4 页。
         */
        const val MAX_EMPTY_DEDUPE_STREAK = 3

        /**
         * 兜底错误文案 key。
         *
         * ⚠️ 用 `Please try again later` 而**不是** RN 的 `Something went wrong` ——
         * 后者不在 26 个 locale 文件的任何一个里，`L10n.t` 找不到会回落到 key 本身，
         * 结果所有语言都显示英文（进度文档 §2.20 已为登录页记过同一条）。
         */
        const val FALLBACK_ERROR_KEY = "Please try again later"

        private const val TAG = "HomeViewModel"
    }
}
