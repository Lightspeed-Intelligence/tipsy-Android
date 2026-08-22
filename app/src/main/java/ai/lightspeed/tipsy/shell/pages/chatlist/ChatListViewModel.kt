package ai.lightspeed.tipsy.shell.pages.chatlist

import ai.lightspeed.tipsy.shell.analytics.Analytics
import ai.lightspeed.tipsy.shell.auth.Generations
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
 * ChatList（Tab4「時光長廊」）的编排（方案 §8.1 ChatList 行，W3 P1）。
 *
 * ## 本刀做了什么 / 没做什么
 *
 * **已做**：`/user/chatted/list` 单在飞分页链 + 冷启动种子缓存、LV 徽章批拉、
 * 草稿只读混排、左滑 pin/unpin（成功后本地重排）与删除（乐观 + mutation 闸门 +
 * convEpoch）、推送红点消除、铃铛未读、Grid/Map 偏好持久化、埋点。
 *
 * **未做**（后续刀）：Map「時光長廊」视图（P2，重视觉自绘）、
 * 启动后台预取 page 0、`firstInteractive` 性能埋点族、点击进会话的真实导航
 * （P9 前被 Router 明确拒绝，Fragment 层已接 `AppRoute.ChatDetail` 请求）。
 *
 * ## 双 generation 闸门（§4.4，**本页是 mutation 轨的第一个实战用例**）
 *
 * 每条分页/刷新响应回写前校验 [Generations.isValid]（auth + mutation 两轨）：
 * - auth 轨：登出/换号后，在飞的旧账号列表不得写进新账号的屏幕
 * - mutation 轨：删除/置顶的乐观变更后，在飞的旧列表响应**不得复活已删行**
 *   （iOS 整月列表修复里最阴的一类：删了的会话过两秒自己回来了）
 *
 * 本地乐观变更（[confirmDelete] / [togglePin]）成功后 `bumpMutation()`，
 * 让所有更早出发的在飞响应作废，然后**主动重拉第 0 页**收敛到服务端真值。
 *
 * ## 单在飞分页链（同 `HomeViewModel`/`ProfileViewModel` 的并发模型）
 *
 * 任一时刻至多一条分页链在飞；刷新 / 语言 settle / 登录态变化都先 [cancelInFlight]。
 * 徽章批拉与铃铛未读是**独立小任务**（不属于分页链）—— 它们晚到只更新自己的
 * 状态位，不触碰列表（§8.4「晚到的 banner」同型防御）。
 *
 * ## 展示排序与数据分层
 *
 * [ChatListState.threads] 保持接口顺序，草稿混排 / pinned 置顶在
 * [ChatListState.sortedThreads] 派生（理由见 `ChatListState` 类注释）。
 */
class ChatListViewModel(
    private val api: ChatListSource,
    private val drafts: ChatDraftStoreLike,
    private val pageTypeStore: ChatPageTypeStoreLike,
    private val cache: ChatListCache?,
    private val convEpoch: ConvEpochLike?,
    private val generations: Generations,
    private val languageProvider: () -> String,
    /** 当前 userId：种子缓存的 authScope 门禁输入。未登录 null。 */
    private val userIdProvider: () -> String?,
    /**
     * `/user/info` 的进程内持有者（LV 徽章的账号级开关 `relationship_switch`）。
     * RN 读的是启动时已 hydrate 的 user store；壳没有进程级镜像，
     * 与 Profile 同构：每次 [onAppear] 作为旁路任务刷新一次。
     */
    private val userStore: CurrentUserStore,
    private val scope: CoroutineScope? = null,
    private val logWarn: (String, Throwable?) -> Unit = { msg, t -> Log.w(TAG, msg, t) },
) : ViewModel() {

    private val _state = MutableStateFlow(ChatListState())
    val state: StateFlow<ChatListState> = _state.asStateFlow()

    private val coroutineScope: CoroutineScope get() = scope ?: viewModelScope

    private var inFlight: Job? = null

    /** 徽章 / 铃铛的独立小任务；登出取消（同 `ProfileViewModel.userStatsJob`）。 */
    private var sideJobs = mutableListOf<Job>()

    /** 分页游标。单列表页不需要按 tab 分表（对比 ProfileTabPaging）。 */
    private var nextPage = 0

    /** 空页续拉计数（§8.4 第 3 条，跨调用累计、限次防请求风暴）。 */
    private var emptyAfterDedupeStreak = 0

    /** Surface 返回 / 切 Tab 回来时是否需要重拉（CHATTED_LIST_REFRESH 的原生对应）。 */
    private var pendingRefreshOnAppear = false

    // ── 生命周期入口 ────────────────────────────────

    /**
     * 进页面 / 切回本 Tab 时调。
     *
     * 首次：读视图偏好 + 种子缓存 → 拉首屏。
     * 再次：铃铛未读每次都刷（`useFocusEffect` 里的 `mutateReadStatus()`）；
     * 列表只在被标脏时重拉（[markStale] 的消费点）。
     * 草稿每次都重读 —— RN 侧 ChatDetail 可能改了它（`draftTick` 的对应物）。
     * user（含 relationship_switch）作为旁路任务刷新。
     */
    fun onAppear() {
        refreshUnread()
        refreshUser()
        reloadDrafts()
        val s = _state.value
        if (!s.hasLoadedOnce && !s.isInitialLoading) {
            _state.value = s.copy(pageType = pageTypeStore.read())
            loadFirstScreen()
            return
        }
        if (pendingRefreshOnAppear) {
            pendingRefreshOnAppear = false
            onRefresh()
        }
    }

    /** 首次可见曝光（`index.tsx:280` 的 `page_exposure`，一次生命周期一次）。 */
    fun onFirstExposure() {
        Analytics.track(
            "page_exposure",
            mapOf("page_name" to "chat_list", "platform" to "app"),
        )
    }

    /**
     * 跨容器返回标脏（方案 §8.1 ChatList「跨容器刷新」行）。
     *
     * RN 的 `CHATTED_LIST_REFRESH` 事件发送方全在 ChatDetail 深栈（发消息 /
     * 重开会话后让列表重拉），JS eventEmitter 跨不过 Surface→原生页边界。
     * 原生对应：Surface 关闭返回 Tab 时由 Fragment 调这里，下次 [onAppear] 重拉。
     */
    fun markStale() {
        pendingRefreshOnAppear = true
    }

    /**
     * 桥 `notifyChattedListChanged` 的即时刷新（建群/群成员变更，工程日志 §2.56
     * 记的欠账）。iOS 对应 `silentRefreshFirstPage`：**立即**静默重拉，
     * 不是 [markStale] —— ChatList Tab 常驻且 Surface 是 sibling，「等下次
     * onAppear」在建群返回的路径上永远不触发（Fragment 未曾离开 STARTED）。
     *
     * 静默语义 = 不置 `isRefreshing`（不转圈）、保留旧列表直到新数据到达。
     * 复用 [reloadFromServer] 同款「打断旧链 + 从第 0 页重拉」：**已翻出的
     * 后续页被刻意丢弃**（触底会重新续拉）—— iOS 是只覆写第 0 页保留后续页、
     * RN 的 SWR mutate 是全部页重验，三端形态各异但都收敛到服务端真值；
     * 壳选与删除/置顶对账一致的既有模式，不为此包引入第三种刷新形态。
     * 未拉过首屏 / 未登录时 no-op（首次进入本来就拉全新数据）。
     */
    fun onChattedListChangedSignal() {
        val s = _state.value
        if (!s.hasLoadedOnce) return
        if (userIdProvider().isNullOrBlank()) return
        cancelInFlight()
        loadPages(fromPage = 0)
    }

    /**
     * 登录态变化。登出**只清不拉**（全接口 REQUIRED，同 Profile 的纪律）；
     * 登录重拉。清空包括种子缓存与用户信息 —— 会话列表全是账号私有数据。
     */
    fun onAuthChanged(loggedIn: Boolean) {
        cancelInFlight()
        cancelSideJobs()
        nextPage = 0
        emptyAfterDedupeStreak = 0
        pendingRefreshOnAppear = false
        if (!loggedIn) {
            cache?.clear()
            userStore.clear()
        }
        val pageType = _state.value.pageType
        _state.value = ChatListState(pageType = pageType)
        if (loggedIn) onAppear()
    }

    /** 语言 settle 后重拉（请求体带 `language_code`，§8.4 第 2 条）。 */
    fun onLanguageSettled() {
        cancelInFlight()
        nextPage = 0
        emptyAfterDedupeStreak = 0
        _state.value = _state.value.copy(
            isRefreshing = false,
            hasReachedEnd = false,
        )
        loadFirstScreen()
    }

    // ── 用户操作 ────────────────────────────────────

    /** 下拉刷新。保留旧内容直到新数据到达（清空会整屏闪白）。 */
    fun onRefresh() {
        if (_state.value.isRefreshing) return
        cancelInFlight()
        _state.value = _state.value.copy(isRefreshing = true, errorMessage = null)
        refreshUnread()
        reloadDrafts()
        loadPages(fromPage = 0)
    }

    /** 滑到底翻页。幂等门禁（同 Profile 的 onLoadMore）。 */
    fun onLoadMore() {
        val s = _state.value
        if (s.isRefreshing || s.isInitialLoading || s.isLoadingMore || s.hasReachedEnd) return
        if (!s.hasLoadedOnce) return
        if (emptyAfterDedupeStreak >= MAX_EMPTY_DEDUPE_STREAK) return
        if (inFlight?.isActive == true) return
        _state.value = s.copy(isLoadingMore = true)
        loadPages(fromPage = nextPage)
    }

    /** Grid/Map 切换。写入失败不回滚 UI（`ChatPageTypeStore.write` 的约定）。 */
    fun onPageTypeSelected(type: ChatPageType) {
        if (_state.value.pageType == type) return
        _state.value = _state.value.copy(pageType = type)
        pageTypeStore.write(type)
    }

    /** 左滑删除按钮 → 弹确认（`setSelectedItem` + `setDeleteModalShow`）。 */
    fun requestDelete(thread: ChatThread) {
        _state.value = _state.value.copy(pendingDelete = thread)
    }

    fun dismissDelete() {
        if (_state.value.isDeleting) return
        _state.value = _state.value.copy(pendingDelete = null)
    }

    /**
     * 确认删除（`index.tsx:148-212` 的 `handleDeleteItem`）。
     *
     * 顺序照 RN：**先乐观移除 → 再调 API → 成功后重拉对账 / 失败恢复重拉**。
     * 加两样 RN 没有的：
     * 1. `bumpMutation()` —— 乐观移除瞬间作废所有在飞旧响应（复活已删行的洞）
     * 2. character 删除成功后写 convEpoch（RN 影院缓存失效契约，见 [ConvEpochWriter]）
     */
    fun confirmDelete() {
        val target = _state.value.pendingDelete ?: return
        if (_state.value.isDeleting) return
        _state.value = _state.value.copy(isDeleting = true)
        coroutineScope.launch {
            // 乐观移除 + 作废在飞（bump 必须在移除前后皆可，关键是先于 API 返回；
            // 放在移除同帧最不容易漏）
            generations.bumpMutation()
            cancelInFlight()
            val before = _state.value
            _state.value = before.copy(
                threads = before.threads.filterNot { it.matches(target) },
                total = (before.total - 1).coerceAtLeast(0),
                pendingDelete = null,
            )
            val result = runCatching { api.delete(target) }
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                if (error is CancellationException) throw error
                logWarn("删除会话失败，重拉对账", error)
                _state.value = _state.value.copy(
                    isDeleting = false,
                    toastKey = toastKeyOf(error, fallback = KEY_DELETE_FAILED),
                )
                // RN 失败路径也 mutate() 重拉恢复
                reloadFromServer()
                return@launch
            }
            // 影院缓存失效：仅 character 会话（story/game 无多角色影院）
            if (target.itemType == ChatThread.TYPE_CHARACTER) {
                convEpoch?.bump(target.itemId)
            }
            _state.value = _state.value.copy(isDeleting = false)
            // RN 成功路径 mutate() 与服务端对账
            reloadFromServer()
        }
    }

    /**
     * 置顶/取消置顶（`ChatListItem.tsx:150-242` 的左滑第二键）。
     *
     * RN 是**成功后**本地重排（非乐观）：先 API，成功才动列表 + Toast。
     * 重排规则照抄：unpin → 摘出，插到「第一个非 pinned 且时间更早」的行前；
     * pin → 摘出，插到 pinned 组内按时间序的位置。
     * 本地重排后 `bumpMutation()` 作废在飞旧响应（重排也是本地变更，
     * 晚到的旧页会把行排回原位）。
     */
    fun togglePin(thread: ChatThread) {
        coroutineScope.launch {
            val result = runCatching {
                if (thread.isPinned) api.unpin(thread) else api.pin(thread)
            }
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                if (error is CancellationException) throw error
                logWarn("置顶操作失败", error)
                _state.value = _state.value.copy(
                    toastKey = if (thread.isPinned) KEY_UNPIN_FAILED else KEY_PIN_FAILED,
                )
                return@launch
            }
            generations.bumpMutation()
            cancelInFlight()
            val s = _state.value
            _state.value = s.copy(
                threads = reorderAfterPinToggle(s.threads, thread),
                toastKey = if (thread.isPinned) KEY_UNPIN_OK else KEY_PIN_OK,
            )
        }
    }

    /**
     * 点会话行。返回**要透传给 Router 的判定素材**；导航本身在 Fragment
     * （业务页不碰 FragmentManager，§4.7 单一导航入口）。
     *
     * 顺带做两件事（`ChatListItem.tsx:268-324` 的 `handlePress`）：
     * 推送红点消除（本地立即 + API 异步）与 simulator 点击埋点。
     */
    fun onThreadClicked(thread: ChatThread) {
        if (thread.itemType == ChatThread.TYPE_GAME) {
            trackSimulatorCard("simulator_card_click", thread)
            return
        }
        if (thread.isPushMessage && !thread.isPushMessageViewed) {
            // 本地立即消红点（RN 靠 mutate() 重拉，壳直接改内存态更快且省一拉）
            _state.value = _state.value.copy(
                threads = _state.value.threads.map {
                    if (it.matches(thread)) it.copy(isPushMessageViewed = true) else it
                },
            )
            coroutineScope.launch {
                runCatching { api.markPushMessageViewed(thread.itemId) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        logWarn("消推送红点失败（下次列表刷新自愈）", it)
                    }
            }
        }
    }

    /** simulator 卡曝光（95% 可见 + 1s 停留的判定在 UI 层，这里只发）。 */
    fun onGameCardExposed(thread: ChatThread) {
        if (thread.itemType != ChatThread.TYPE_GAME) return
        trackSimulatorCard("simulator_card_exposure", thread)
    }

    /** Toast 已展示，清一次性状态。 */
    fun consumeToast() {
        _state.value = _state.value.copy(toastKey = null)
    }

    // ── 内部：加载链 ─────────────────────────────────

    /** 首屏：种子缓存先上屏（有则），随后在线首页覆盖。 */
    private fun loadFirstScreen() {
        val userId = userIdProvider()
        if (userId.isNullOrBlank()) {
            // 未登录不发（REQUIRED 必被前置拒绝）。保持初始态，
            // 登录事件会经 onAuthChanged 重新进来
            return
        }
        val seed = cache?.read(ChatListCache.authScopeOf(userId))
        if (seed != null && seed.items.isNotEmpty()) {
            _state.value = _state.value.copy(
                threads = seed.items,
                total = seed.total,
                hasLoadedOnce = true,
                isInitialLoading = false,
            )
        } else {
            _state.value = _state.value.copy(isInitialLoading = true, errorMessage = null)
        }
        loadPages(fromPage = 0)
    }

    private fun loadPages(fromPage: Int) {
        inFlight = coroutineScope.launch {
            runCatching { loadPageChain(fromPage) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    onPageFailed(error)
                }
        }
    }

    /**
     * 拉一页并在「去重后无新增且未到底」时主动续拉（§8.4 第 3 条，限次）。
     *
     * 每页回写前过 [Generations.isValid] 双闸门 —— 快照在**请求出发前**取。
     */
    private suspend fun loadPageChain(fromPage: Int) {
        var pageIndex = fromPage
        while (true) {
            val snapshot = generations.snapshot()
            val page = api.fetchPage(page = pageIndex, languageCode = languageProvider())
            if (!generations.isValid(snapshot)) {
                // 期间换过号或本地删/置顶过 —— 这页数据基于旧世界，整体丢弃。
                // 不重试：触发 bump 的那一方负责发起新的加载
                return
            }
            val s = _state.value
            val existing = if (pageIndex == 0) emptyList() else s.threads
            val seen = existing.mapTo(HashSet()) { it.stableKey }
            val fresh = page.items.filter { seen.add(it.stableKey) }
            val merged = existing + fresh
            emptyAfterDedupeStreak =
                if (fresh.isEmpty() && pageIndex > 0) emptyAfterDedupeStreak + 1 else 0
            nextPage = pageIndex + 1

            _state.value = s.copy(
                threads = merged,
                total = page.total,
                hasReachedEnd = !page.hasMore,
                hasLoadedOnce = true,
                isInitialLoading = false,
                isLoadingMore = false,
                isRefreshing = false,
                errorMessage = null,
            )

            if (pageIndex == 0) {
                // 首页落地：写种子 + 徽章批拉。种子存原始片段（見 ChatListCache）
                page.rawList?.let { raw ->
                    cache?.write(
                        authScope = ChatListCache.authScopeOf(userIdProvider()),
                        rawListJson = raw,
                        total = page.total,
                    )
                }
                refreshRelationshipStats(merged)
            }

            if (fresh.isNotEmpty() || !page.hasMore) return
            if (emptyAfterDedupeStreak >= MAX_EMPTY_DEDUPE_STREAK) return
            pageIndex++
        }
    }

    /** 删除/置顶后的服务端对账重拉（RN 的 `mutate()` 对应物）。 */
    private fun reloadFromServer() {
        cancelInFlight()
        loadPages(fromPage = 0)
    }

    private fun cancelInFlight() {
        inFlight?.cancel()
        inFlight = null
        val s = _state.value
        if (s.isInitialLoading || s.isLoadingMore || s.isRefreshing) {
            _state.value = s.copy(
                // 未拉到过首屏的初始加载被打断 → 复位标志让下次 onAppear 重进
                isInitialLoading = false,
                isLoadingMore = false,
                isRefreshing = false,
            )
        }
    }

    private fun cancelSideJobs() {
        sideJobs.forEach { it.cancel() }
        sideJobs.clear()
    }

    private fun onPageFailed(error: Throwable) {
        logWarn("拉取会话列表页失败", error)
        val s = _state.value
        _state.value = s.copy(
            isInitialLoading = false,
            isLoadingMore = false,
            isRefreshing = false,
            // 已有数据不清列表不显错（§8.4）；只有首屏空列表失败才上错误位
            errorMessage = if (s.threads.isEmpty()) errorMessageOf(error) else s.errorMessage,
        )
    }

    // ── 内部：旁路数据 ───────────────────────────────

    /**
     * LV 徽章批拉。独立小任务：晚到只更新徽章表，不触列表（§8.4）。
     * 失败保留旧值 —— 徽章是装饰，一次网络抖动不该把已显示的徽章抹掉。
     */
    private fun refreshRelationshipStats(threads: List<ChatThread>) {
        val ids = threads
            .filter { it.itemType == ChatThread.TYPE_CHARACTER && !it.isMiniPhone }
            .map { it.itemId }
        if (ids.isEmpty()) return
        val snapshot = generations.snapshot()
        sideJobs += coroutineScope.launch {
            val stats = runCatching { api.fetchRelationshipStats(ids) }
                .onFailure {
                    if (it is CancellationException) throw it
                    logWarn("拉取关系等级失败，保留已有徽章", it)
                }
                .getOrNull() ?: return@launch
            // auth 轨即可：徽章表不受本地列表乐观变更影响（mutation 轨会误废）
            if (!generations.isAuthValid(snapshot)) return@launch
            _state.value = _state.value.copy(
                relationshipStats = stats.associateBy { it.characterId },
            )
        }
    }

    /**
     * 刷新用户信息（LV 徽章的账号级开关来源）。失败保留旧值 ——
     * [CurrentUserStore.refresh] 内部已带该语义，这里只负责把结果写进页面状态。
     */
    private fun refreshUser() {
        val snapshot = generations.snapshot()
        sideJobs += coroutineScope.launch {
            userStore.refresh()
            if (!generations.isAuthValid(snapshot)) return@launch
            _state.value = _state.value.copy(
                relationshipSwitch = userStore.current.value?.relationshipSwitch ?: false,
            )
        }
    }

    /** 铃铛未读。失败保留旧值。 */
    private fun refreshUnread() {
        val snapshot = generations.snapshot()
        sideJobs += coroutineScope.launch {
            val unread = runCatching { api.fetchUnreadStatus() }
                .onFailure {
                    if (it is CancellationException) throw it
                    logWarn("拉取铃铛未读失败，保留旧值", it)
                }
                .getOrNull() ?: return@launch
            if (!generations.isAuthValid(snapshot)) return@launch
            _state.value = _state.value.copy(hasUnreadLetters = unread)
        }
    }

    /** 重读草稿表（进页面 / 下拉刷新时；RN 的 `draftTick` 焦点重读对应物）。 */
    private fun reloadDrafts() {
        _state.value = _state.value.copy(drafts = drafts.readAll())
    }

    private fun trackSimulatorCard(event: String, thread: ChatThread) {
        // 2s 节流照 RN（`simulatorGameTracking.ts:59-70`）——
        // key 是 事件:页面:卡片，Compose 重组可能高频触发曝光回调
        val key = "$event:$PAGE_NAME_TIME_CORRIDOR:${thread.gameId}"
        val now = System.currentTimeMillis()
        val last = simulatorThrottle[key]
        if (last != null && now - last < SIMULATOR_THROTTLE_MS) return
        simulatorThrottle[key] = now
        Analytics.track(
            event,
            mapOf(
                "simulator_id" to thread.gameId.orEmpty(),
                "creator_id" to thread.creatorId.orEmpty(),
                "page_name" to PAGE_NAME_TIME_CORRIDOR,
            ),
        )
    }

    private val simulatorThrottle = HashMap<String, Long>()

    private fun errorMessageOf(error: Throwable): String = when (error) {
        is ApiException.Business ->
            error.serverMessage?.takeIf { it.isNotBlank() } ?: FALLBACK_ERROR_KEY
        else -> FALLBACK_ERROR_KEY
    }

    /** 业务错误带可展示 msg 时用它做 Toast，否则用兜底 key。 */
    private fun toastKeyOf(error: Throwable?, fallback: String): String = when (error) {
        is ApiException.Business ->
            error.serverMessage?.takeIf { it.isNotBlank() } ?: fallback
        else -> fallback
    }

    companion object {
        private const val TAG = "ChatListViewModel"

        /** 空页续拉上限（§8.4，iOS 限连续 3 页）。 */
        const val MAX_EMPTY_DEDUPE_STREAK = 3

        /** `simulatorGameTracking.ts:59` 的 `TRACKING_THROTTLE_MS`。 */
        private const val SIMULATOR_THROTTLE_MS = 2000L

        /** `pageName: 'time_corridor'`（`index.tsx` / `ChatListItem.tsx` 的调用点）。 */
        private const val PAGE_NAME_TIME_CORRIDOR = "time_corridor"

        // i18n key = 英文原文（全部已在 SHELL_KEYS 并已导出，2026-08-12 核实）
        const val FALLBACK_ERROR_KEY = "Please try again later"
        const val KEY_DELETE_FAILED = "Delete failed"
        const val KEY_PIN_OK = "Pinned successfully"
        const val KEY_PIN_FAILED = "Pinned failed"
        const val KEY_UNPIN_OK = "Unpinned successfully"
        const val KEY_UNPIN_FAILED = "Unpinned failed"

        /**
         * pin/unpin 成功后的本地重排（`ChatListItem.tsx:175-226` 逐行对齐）。
         *
         * 抽成静态纯函数是为了单测 —— 插入位置的循环边界（`i + 1` 还是 `i`、
         * `break` 的时机）RN 写得很绕，错一位就是「pin 后排到组尾」级的怪序。
         */
        internal fun reorderAfterPinToggle(
            threads: List<ChatThread>,
            target: ChatThread,
        ): List<ChatThread> {
            val index = threads.indexOfFirst { it.matches(target) }
            if (index == -1) return threads
            val list = threads.toMutableList()
            val item = list.removeAt(index)
            if (target.isPinned) {
                // Unpinning：插到第一个「非 pinned 且时间更早」的行处
                val updated = item.copy(isPinned = false)
                var insertIndex = list.size
                for (i in list.indices) {
                    if (!list[i].isPinned && list[i].latestTimeSeconds < updated.latestTimeSeconds) {
                        insertIndex = i
                        break
                    }
                }
                list.add(insertIndex, updated)
            } else {
                // Pinning：pinned 组内，插到最后一个「时间更晚的 pinned」之后
                val updated = item.copy(isPinned = true)
                var insertIndex = 0
                for (i in list.indices) {
                    if (list[i].isPinned && list[i].latestTimeSeconds > updated.latestTimeSeconds) {
                        insertIndex = i + 1
                    } else if (!list[i].isPinned) {
                        break
                    }
                }
                list.add(insertIndex, updated)
            }
            return list
        }
    }
}
