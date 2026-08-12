package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.analytics.Analytics
import ai.lightspeed.tipsy.shell.network.ApiException
import ai.lightspeed.tipsy.shell.user.CurrentUserStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Profile（自己视角）的编排（方案 §8.1 Profile 行，W3 第一刀）。
 *
 * ## 本刀做了什么 / 没做什么
 *
 * **已做**：资料头部（`/user/info`）、四个统计数字（`/user/stats_info`）、
 * 创作 tab 三列网格分页（`/user/created/list`）、记忆 tab 单列大卡分页
 * （`/plot/list/self`）、五图标 tab 栏（未接数据源的 tab 走占位）。
 *
 * **未做**（后续包）：角色卡/收藏/点赞三个 tab、钱包区、
 * 创作任务弹窗那条五个 `useEffect` 协调的状态链、所有编辑/删除/置顶动作、
 * 他人主页（`isSelf = false` 分支，注意它的 stats 走 `OPPORTUNISTIC`）、
 * 创作列表首屏缓存（`profileCreatedListCache`，见下）、
 * `onFirstTabDataReady` 一族页面性能参数（`user-profile.tsx:137`，属性能埋点包）。
 *
 * ## 并发模型：单在飞分页链（同 `HomeViewModel` 的 `inFlight`）
 *
 * 同一时刻**至多一条**分页链在飞，且必然属于当前选中 tab：
 * 切 tab / 刷新 / 语言 settle / 登录态变化都先 [cancelInFlight]。
 * 被打断的 tab 若还没成功拉过首屏，分页状态整体复位 —— 不复位会让
 * `isInitialLoading` 永远卡 `true`，[loadFirstPageIfNeeded] 从此跳过它。
 *
 * 备选是「每 tab 一条链并行」（更贴近 RN 每 tab 独立 `useSWRInfinite`），
 * 但那需要为页级 `isRefreshing` 与跨 tab 响应竞态各加一套归属判定；
 * 单链把这两类竞态整个消掉，代价只是切 tab 时偶尔废弃一个在飞请求。
 *
 * ## 首屏缓存刻意留到后续包
 *
 * RN 有 `profileCreatedListCache`（24h TTL），但它的设计与 `HomeForYouCache`
 * **不同**：`userId` + `languageCode` 编进 **cache key** 而非信封字段，
 * 还带 `apiBaseUrl` 且剥掉 20 个私有/编辑态字段。
 * 值得单独一刀照它的形状做（顺带印证 §2.23.1 那个性别缺陷该怎么修 ——
 * RN 自己的新代码就是"key 带维度"而不是"信封做门禁"）。
 *
 * ## ⚠️ loading 语义不能照抄 RN
 *
 * RN 的整页 `isLoading` 在 `isSelf` 分支取的是 `selfCharacterIsLoading`
 * （`useProfile.tsx:185-186`），也就是 `/character/list/self` 的 loading ——
 * 而那个请求**根本不上屏**（`useProfile.tsx:165-167` 注释确认）。
 * 壳不发那个死请求，所以 loading 直接接各 tab 自己的列表请求。
 * 照抄会得到一个"永远不消失或永不出现"的骨架屏。
 */
class ProfileViewModel(
    private val api: ProfileSource,
    private val userStore: CurrentUserStore,
    private val languageProvider: () -> String,
    /** 注入是为了测试；生产用 viewModelScope。 */
    private val scope: CoroutineScope? = null,
    /** 注入而非直接调用：JVM 单测里 `android.util.Log` 是抛 "not mocked" 的桩。 */
    private val logWarn: (String, Throwable?) -> Unit = { msg, t -> Log.w(TAG, msg, t) },
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    private val coroutineScope: CoroutineScope get() = scope ?: viewModelScope

    /** 在飞的分页链与它属于哪个 tab。见类注释「并发模型」。 */
    private var inFlight: Job? = null
    private var inFlightTab: ProfileTab? = null

    /** 用户信息 + 统计的在飞任务；登出时必须取消（见 [onAuthChanged]）。 */
    private var userStatsJob: Job? = null

    /**
     * 进页面时调。
     *
     * 幂等：当前 tab 已有数据时不重复拉首屏（对齐 `HomeViewModel.loadIfNeeded`）。
     * 用户信息与统计每次都刷 —— RN 侧 `FollowInfo` 是 `isFocused` 时 `mutate`
     * （`FollowInfo.tsx:41-45`），头像昵称改完回到页面要立刻更新。
     */
    fun onAppear() {
        refreshUserAndStats()
        loadFirstPageIfNeeded(_state.value.selectedTab)
    }

    /**
     * 切 tab。
     *
     * 新 tab 没数据才拉首屏；已有数据直接复用（RN 每个 tab 是独立
     * `useSWRInfinite`，切回来不重拉）。未接数据源的 tab 只切选中态，不发请求。
     *
     * 切走会打断上一个 tab 的在飞链（见类注释「并发模型」）。
     */
    fun onTabSelected(tab: ProfileTab) {
        if (_state.value.selectedTab == tab) return
        cancelInFlight()
        _state.value = _state.value.copy(selectedTab = tab)
        loadFirstPageIfNeeded(tab)
    }

    /**
     * 首次可见时上报页面曝光。
     *
     * 参数照 `user-profile.tsx:313`：`page_exposure` + `page_name: profile`。
     * ⚠️ RN 侧**自己和他人主页都发这一个事件**（方案 §8.1 Profile 行的埋点列），
     * 不按视角分流 —— 后续接他人主页时不要新造一个事件名。
     *
     * 由 Fragment 在首次 STARTED 时调一次（同 `HomeFragment.hasReportedFirstAppear`），
     * 不放在 [onAppear] 里：那个每次回到页面都会跑。
     */
    fun onFirstExposure() {
        Analytics.track(
            "page_exposure",
            mapOf("page_name" to "profile", "platform" to "app"),
        )
    }

    /**
     * 登录态变化。
     *
     * ## ⚠️ 登出时**只清不拉**
     *
     * `AuthStateHub` 的硬约束：登出后 authorized 请求必然被前置拒绝，
     * 发了只会产生噪音。Profile 的接口**都是 `REQUIRED`**
     * （与 Home 那三个 `OPPORTUNISTIC` 不同 —— Home 登出后照发能拿到游客内容，
     * Profile 登出后根本没有"当前用户"可查）。
     *
     * 所以这里登出走清空、登录才重拉。照抄 `HomeViewModel.onAuthChanged`
     * 的"无条件重拉"会在每次登出时打必然失败的请求。
     *
     * ## ⚠️ 必须取消 [userStatsJob]
     *
     * 登出瞬间可能有 `/user/info` 响应在飞。不取消的话它会在清空之后 resume,
     * 把上一账号的头像昵称写回来（`CurrentUserStore.refresh` 对
     * CancellationException 的分流就是为这里准备的）。
     */
    fun onAuthChanged(loggedIn: Boolean) {
        userStatsJob?.cancel()
        userStatsJob = null
        cancelInFlight()
        userStore.clear()
        _state.value = ProfileState()
        if (loggedIn) onAppear()
    }

    /**
     * 下拉刷新。
     *
     * RN 的 `handleRefresh` 把五个 tab **全部** mutate（`CharacterGrid.tsx:252-262`
     * 的 `Promise.allSettled`）。单在飞链的对应物：当前 tab 立即重拉第 0 页，
     * 其它 tab 复位成初始态、下次切换过去时重拉 —— 不并发五个请求，最终一致。
     *
     * 当前 tab 保留旧内容直到新数据到达（`isRefreshing` 而非清空）——
     * 清空会让下拉时整屏闪白，且失败后用户什么都看不到（同 `HomeViewModel.onRefresh`）。
     *
     * 占位 tab（未接数据源）上也能下拉：只刷用户信息与统计，完成即收圈。
     */
    fun onRefresh() {
        if (_state.value.isRefreshing) return
        val tab = _state.value.selectedTab
        cancelInFlight()
        val current = _state.value
        _state.value = current.copy(
            isRefreshing = true,
            paging = current.paging.filterKeys { it == tab },
        )
        val statsJob = refreshUserAndStats()
        if (tab.isImplemented) {
            updatePaging(tab) { it.copy(errorMessage = null) }
            loadPages(tab, fromPage = 0)
        } else {
            coroutineScope.launch {
                statsJob.join()
                _state.value = _state.value.copy(isRefreshing = false)
            }
        }
    }

    /**
     * 语言 settle 后重拉（方案 §8.4 第 2 条）。
     *
     * 全部 tab 复位：创作列表的请求体带 `language_code`，其余 tab 在 RN 侧的
     * SWR key 同样含语言。用户信息与统计**不动** —— 昵称头像与四个数字都
     * 与语言无关，白拉两个请求。
     *
     * 由 Fragment 订阅 `L10n.languageFlow` 调用（同 `HomeViewModel.onLanguageSettled`）。
     */
    fun onLanguageSettled() {
        cancelInFlight()
        _state.value = _state.value.copy(paging = emptyMap(), isRefreshing = false)
        loadFirstPageIfNeeded(_state.value.selectedTab)
    }

    /**
     * 滑到底触发翻页。幂等 —— 门禁任一命中直接返回。
     *
     * `emptyAfterDedupeStreak` 的门禁比 Home 严：Home 的 `onLoadMore` 不查
     * streak（每次手动触发仍会发一页），这里查 —— 达到上限后连那一页也不发，
     * 等下拉刷新把 streak 归零。
     */
    fun onLoadMore() {
        val s = _state.value
        val tab = s.selectedTab
        if (!tab.isImplemented || s.isRefreshing) return
        val p = s.pagingOf(tab)
        if (!p.hasLoadedOnce || p.hasReachedEnd || p.isInitialLoading || p.isLoadingMore) return
        if (p.emptyAfterDedupeStreak >= ProfileTabPaging.MAX_EMPTY_DEDUPE_STREAK) return
        if (inFlight?.isActive == true) return
        updatePaging(tab) { it.copy(isLoadingMore = true) }
        loadPages(tab, fromPage = p.nextPage)
    }

    // ── 内部 ────────────────────────────────────────

    private fun loadFirstPageIfNeeded(tab: ProfileTab) {
        if (!tab.isImplemented) return
        val p = _state.value.pagingOf(tab)
        // hasLoadedOnce 而不是 items.isEmpty()：空列表（total=0）也算已加载，
        // 否则每次切回来都会为一个必然空的 tab 重发请求
        if (p.hasLoadedOnce || p.isInitialLoading) return
        updatePaging(tab) { it.copy(isInitialLoading = true, errorMessage = null) }
        loadPages(tab, fromPage = 0)
    }

    /**
     * 取消在飞分页链，并清掉被打断 tab 的瞬态标志。
     *
     * 还没成功拉过首屏的 tab 整体复位（下次进入重拉）；已有数据的只清
     * loading 标志、**不动 items**。页级 `isRefreshing` 一并收掉 ——
     * 在飞链必然是当前活动，链没了圈也该停。
     */
    private fun cancelInFlight() {
        val job = inFlight
        val tab = inFlightTab
        inFlight = null
        inFlightTab = null
        job?.cancel()
        if (tab != null) {
            updatePaging(tab) { p ->
                if (!p.hasLoadedOnce) ProfileTabPaging()
                else p.copy(isInitialLoading = false, isLoadingMore = false)
            }
        }
        if (_state.value.isRefreshing) {
            _state.value = _state.value.copy(isRefreshing = false)
        }
    }

    private fun loadPages(tab: ProfileTab, fromPage: Int) {
        inFlightTab = tab
        inFlight = coroutineScope.launch {
            runCatching { loadPageChain(tab, fromPage) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    onPageFailed(tab, error)
                }
        }
    }

    /**
     * 拉一页，并在「去重后无新增」时**主动续拉**（方案 §8.4 第 3 条），
     * 受 [ProfileTabPaging.MAX_EMPTY_DEDUPE_STREAK] 限次 —— 不限次时异常数据
     * （后端一直返回同一页）会形成无限循环。整条链在一个协程里
     * （同 `HomeViewModel.loadPageChain`），取消即整链停。
     */
    private suspend fun loadPageChain(tab: ProfileTab, fromPage: Int) {
        var pageIndex = fromPage
        while (true) {
            val page = fetchPage(tab, pageIndex)
            val prev = _state.value.pagingOf(tab)
            // 第 0 页（含下拉刷新）从空列表重新累计；翻页在已有列表上追加。
            // ⚠️ 刷新时**替换**而不是先清空再填 —— 清空会让可见卡片先闪一下空白
            val existing = if (pageIndex == 0) emptyList() else prev.items
            val seen = existing.mapTo(HashSet()) { it.dedupeKey }
            // 去重键按类型分流（game 用 game_<id>），见 ProfileCreatedItem.dedupeKey
            val fresh = page.items.filter { seen.add(it.dedupeKey) }
            val merged = existing + fresh
            // ⚠️ streak 存在 paging 里、跨 onLoadMore 调用累计（HomeViewModel
            // 里那条注释的同一教训：本地计数器挡不住请求风暴）
            val streak = if (fresh.isEmpty() && pageIndex > 0) prev.emptyAfterDedupeStreak + 1 else 0

            // 整个构造新 ProfileTabPaging 而不是 copy：一次性把 loading /
            // error 等瞬态全复位，漏 copy 某个标志的 bug 从构造上排除
            val updated = ProfileTabPaging(
                items = merged,
                nextPage = pageIndex + 1,
                total = page.total,
                emptyAfterDedupeStreak = streak,
                hasLoadedOnce = true,
            )
            val done = updated.reachedEnd(merged.size)
            updatePaging(tab) { updated.copy(hasReachedEnd = done) }
            if (_state.value.isRefreshing) {
                _state.value = _state.value.copy(isRefreshing = false)
            }

            // 有新增、或已到底 → 停
            if (fresh.isNotEmpty() || done) return
            if (streak >= ProfileTabPaging.MAX_EMPTY_DEDUPE_STREAK) return
            pageIndex++
        }
    }

    /**
     * 归一化：不同 tab 的响应形状不同（创作是内联嵌套对象、记忆是关系型 join），
     * 但翻页壳只需要 `items + total`。
     */
    private suspend fun fetchPage(tab: ProfileTab, page: Int): TabPage = when (tab) {
        ProfileTab.CREATED ->
            api.fetchCreatedPage(page = page, languageCode = languageProvider())
                .let { TabPage(it.items, it.total) }

        ProfileTab.MEMORY ->
            api.fetchMemoryPage(page = page)
                .let { TabPage(it.items, it.total) }

        // 走到这里说明 isImplemented 的判断被绕过了 —— 显式炸掉让实现错误可见
        //（同 Router 对未接线分支的处理），不要静默返回空页
        ProfileTab.ROLE_CARD, ProfileTab.FAVORITES, ProfileTab.LIKED ->
            error("${tab.name} 未接数据源却发起了分页请求")
    }

    private fun refreshUserAndStats(): Job {
        userStatsJob?.cancel()
        val job = coroutineScope.launch {
            // 先刷用户信息：statsInfo 需要 userId
            userStore.refresh()
            val user = userStore.current.value
            _state.value = _state.value.copy(user = user)
            val userId = user?.userId ?: return@launch
            val stats = runCatching { api.fetchSelfStats(userId) }
                .onFailure {
                    if (it is CancellationException) throw it
                    logWarn("拉取 /user/stats_info 失败，保留已有统计", it)
                }
                .getOrNull() ?: return@launch
            _state.value = _state.value.copy(stats = stats)
        }
        userStatsJob = job
        return job
    }

    private fun onPageFailed(tab: ProfileTab, error: Throwable) {
        logWarn("拉取 ${tab.name} 列表页失败", error)
        // ⚠️ 已有数据时不清列表、不显错误 —— 方案 §8.4。
        // 只有首屏空列表失败才把错误摆到页面上
        updatePaging(tab) { p ->
            p.copy(
                isInitialLoading = false,
                isLoadingMore = false,
                errorMessage = if (p.items.isEmpty()) errorMessageOf(error) else p.errorMessage,
            )
        }
        if (_state.value.isRefreshing) {
            _state.value = _state.value.copy(isRefreshing = false)
        }
    }

    /**
     * 文案取后端 `msg`，为空回落 [FALLBACK_ERROR_KEY] —— 与 Home / 登录页
     * 同一处理（进度文档 §2.20 记的坑：置 null 等于什么都不弹）。
     */
    private fun errorMessageOf(error: Throwable): String = when (error) {
        is ApiException.Business ->
            error.serverMessage?.takeIf { it.isNotBlank() } ?: FALLBACK_ERROR_KEY
        else -> FALLBACK_ERROR_KEY
    }

    private inline fun updatePaging(
        tab: ProfileTab,
        transform: (ProfileTabPaging) -> ProfileTabPaging,
    ) {
        val s = _state.value
        _state.value = s.copy(paging = s.paging + (tab to transform(s.pagingOf(tab))))
    }

    /** 一页的归一化形状，[fetchPage] 的返回值。 */
    private data class TabPage(val items: List<ProfileListEntry>, val total: Long)

    companion object {
        private const val TAG = "ProfileViewModel"

        /** i18n key（key = 英文原文）。UI 层判等后走 LocalizedText，同 Home。 */
        const val FALLBACK_ERROR_KEY = "Please try again later"
    }
}
