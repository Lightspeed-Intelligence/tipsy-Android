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
 * 钱包三栏卡（`/wallet/info` + `/subscription/get/active`）、
 * 创作 tab 三列网格分页（`/user/created/list`）、记忆 tab 单列大卡分页
 * （`/plot/list/self`）、五图标 tab 栏（未接数据源的 tab 走占位）。
 *
 * **未做**（后续包）：角色卡/收藏/点赞三个 tab、
 * 创作任务弹窗那条五个 `useEffect` 协调的状态链、所有编辑/删除/置顶动作、
 * 他人主页（`isSelf = false` 分支，注意它的 stats 走 `OPPORTUNISTIC`；
 * 他人主页**无钱包卡**，`CharacterGrid.tsx:1431` 的 `isSelf &&`）、
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
    private val walletApi: ProfileWalletSource,
    private val userStore: CurrentUserStore,
    private val languageProvider: () -> String,
    /** 注入是为了测试；生产用 viewModelScope。 */
    private val scope: CoroutineScope? = null,
    /** 注入而非直接调用：JVM 单测里 `android.util.Log` 是抛 "not mocked" 的桩。 */
    private val logWarn: (String, Throwable?) -> Unit = { msg, t -> Log.w(TAG, msg, t) },
    private val avatarDecorationSource: AvatarDecorationSource? = null,
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
     * [refreshProfile] 只由 Fragment 在同一 onStart 已发起 dirty 定向刷新时传 false；
     * 内容列表的按需加载仍照常执行。
     */
    fun onAppear(refreshProfile: Boolean = true) {
        if (refreshProfile) refreshUserAndStats()
        loadFirstPageIfNeeded(_state.value.selectedTab)
    }

    /**
     * EditProfileSurface 成功修改资料后的定向刷新。
     *
     * 复用 [refreshUserAndStats]，只更新用户资料、统计与同链路的钱包，不复位或重拉
     * 五个内容 tab。编辑昵称/头像不该让用户正在看的创作/收藏列表跳回第 0 页。
     * [onUserInfoRefreshed] 仅在本次 `/user/info` 确实成功时回调响应 userId；失败时
     * 即使 store 里仍有旧用户也不回调成功，而是在当前任务收尾后调用
     * [onUserInfoRefreshFailed]，让协调器做一次有界重试。
     */
    fun onProfileChanged(
        onUserInfoRefreshed: (String) -> Unit = {},
        onUserInfoRefreshFailed: () -> Unit = {},
    ) {
        refreshUserAndStats(onUserInfoRefreshed, onUserInfoRefreshFailed)
    }

    /**
     * 桥 `notifyCreatedCharactersChanged` 的创作列表失效信号（CreateSurface
     * 创建/编辑成功，`profileDetail.tsx:1574`；账号存在性已在 provider 守卫）。
     *
     * iOS 是「markPending + 可见时消费」；壳的单在飞链对应物按可见性分流：
     * - 创作 tab 正被选中 → 就地重拉（Surface 是 sibling，关闭时底下的
     *   Fragment 不重走 onStart —— 不立即拉就没有下一个触发点）。首屏失败
     *   停在错误态时同样重拉，创建成功是比「手动重试」更强的重试理由；
     * - 其它 tab 选中 → **不打断它的在飞链**，把创作 tab 作废成未加载态，
     *   切回时 `loadFirstPageIfNeeded` 重拉（invalidate 语义，同 RN mutate）；
     * - 其它 tab 选中且创作 tab 从未加载 → no-op（首次进入本来就拉全新数据）。
     */
    fun onCreatedCharactersChangedSignal() {
        if (_state.value.selectedTab == ProfileTab.CREATED) {
            reloadCreatedTab()
        } else if (_state.value.pagingOf(ProfileTab.CREATED).hasLoadedOnce) {
            updatePaging(ProfileTab.CREATED) { ProfileTabPaging() }
        }
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
        if (!loggedIn) userStore.clear()
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
        refreshUserAndStats()
        updatePaging(tab) { it.copy(errorMessage = null) }
        loadPages(tab, fromPage = 0)
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
        if (s.isRefreshing) return
        val p = s.pagingOf(tab)
        if (!p.hasLoadedOnce || p.hasReachedEnd || p.isInitialLoading || p.isLoadingMore) return
        if (p.emptyAfterDedupeStreak >= ProfileTabPaging.MAX_EMPTY_DEDUPE_STREAK) return
        if (inFlight?.isActive == true) return
        updatePaging(tab) { it.copy(isLoadingMore = true) }
        loadPages(tab, fromPage = p.nextPage)
    }

    /** 一次性 Toast 已展示，清状态（同 `ChatListViewModel.consumeToast`）。 */
    fun consumeToast() {
        _state.value = _state.value.copy(toastKey = null)
    }

    // ── P5 卡片 ⋮ 菜单（方案 §8.1：角色=编辑/删除/置顶、故事=删除/置顶、游戏=置顶）──

    /** 打开某张卡的菜单。单字段天然互斥 —— RN 那套 `closeOtherMenu` ref 表不需要。 */
    fun onMenuOpen(item: ProfileCreatedItem) {
        _state.value = _state.value.copy(openMenuKey = item.dedupeKey)
    }

    fun onMenuDismiss() {
        _state.value = _state.value.copy(openMenuKey = null)
    }

    /** 菜单「Delete」→ 关菜单、挂确认弹窗（真正删除在 [onDeleteConfirmed]）。 */
    fun onDeleteRequested(item: ProfileCreatedItem) {
        _state.value = _state.value.copy(openMenuKey = null, pendingDelete = item)
    }

    fun onDeleteDismissed() {
        _state.value = _state.value.copy(pendingDelete = null)
    }

    /**
     * 确认删除（`CharacterGridItem.tsx:825-840` / `StoryItem.tsx:832-846`）。
     *
     * 语义照 RN：**服务端先删、成功后整列表重拉对账**（不是 ChatList 那种
     * 乐观移除）—— 弹窗在发请求前就收掉，列表在重拉完成时更新。
     * 成功不弹 Toast（RN 如此）；仅 character 发 `delete_character` 埋点
     * （story 的 onConfirm 没有埋点，实测）。
     *
     * ⚠️ 失败提示是壳补的（`Delete failed`，ChatList 已有 key）：RN 的
     * onConfirm **没有** try/catch，失败表现为「点了删除、卡片还在、
     * 无任何提示」—— §2.39 那类静默失败，不继承。
     */
    fun onDeleteConfirmed() {
        val target = _state.value.pendingDelete ?: return
        _state.value = _state.value.copy(pendingDelete = null)
        val deleteId = target.deleteId
        if (deleteId.isNullOrBlank()) {
            // game 无删除动作，UI 不该给出这个入口；走到这里是装配错误
            logWarn("删除目标缺 id：type=${target.type} key=${target.dedupeKey}", null)
            _state.value = _state.value.copy(toastKey = KEY_DELETE_FAILED)
            return
        }
        coroutineScope.launch {
            val result = runCatching {
                when (target.type) {
                    ProfileItemType.CHARACTER -> api.deleteCharacter(deleteId)
                    ProfileItemType.STORY -> api.deleteStory(deleteId)
                    ProfileItemType.GAME -> error("game 卡无删除动作")
                }
            }
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                if (error is CancellationException) throw error
                logWarn("删除失败：${target.dedupeKey}", error)
                _state.value = _state.value.copy(toastKey = KEY_DELETE_FAILED)
                return@launch
            }
            if (target.type == ProfileItemType.CHARACTER) {
                // 参数名照 RN sendEvent（camelCase characterId）
                Analytics.track("delete_character", mapOf("characterId" to deleteId))
            }
            reloadCreatedTab()
        }
    }

    /**
     * 置顶/取消置顶（`CharacterGridItem.tsx:396-424` 的 `handleTogglePin`）。
     *
     * RN 语义逐条对齐：
     * - 单飞（`isPinning` 门）；点击即关菜单；
     * - **非乐观**：成功才重拉列表（置顶影响排序，本地重排不可靠，
     *   服务端才知道 pinned 组的最终顺序）；
     * - 成功 Toast 按**响应**的 `is_pinned` 分流（`res?.is_pinned` falsy →
     *   Unpinned，null 同 falsy）；
     * - 失败 Toast：服务端 msg 含 `up to 3 pins allowed` → 上限文案，
     *   否则统一 `Pinned failed`（RN 不分置顶/取消方向，照抄）。
     */
    fun onTogglePin(item: ProfileCreatedItem) {
        if (_state.value.pinningKey != null) return
        val pinId = item.pinId
        if (pinId.isNullOrBlank()) {
            logWarn("置顶目标缺 id：key=${item.dedupeKey}", null)
            _state.value = _state.value.copy(openMenuKey = null, toastKey = KEY_PIN_FAILED)
            return
        }
        _state.value = _state.value.copy(openMenuKey = null, pinningKey = item.dedupeKey)
        coroutineScope.launch {
            val result = runCatching { api.togglePin(pinId, item.type) }
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                if (error is CancellationException) throw error
                logWarn("置顶操作失败：${item.dedupeKey}", error)
                _state.value = _state.value.copy(
                    pinningKey = null,
                    toastKey = pinFailureKey(error),
                )
                return@launch
            }
            _state.value = _state.value.copy(
                pinningKey = null,
                toastKey = if (result.getOrNull() == true) KEY_PIN_OK else KEY_UNPIN_OK,
            )
            reloadCreatedTab()
        }
    }

    /**
     * 上限判定照 RN `error?.toString().includes('up to 3 pins allowed')` ——
     * 只认业务 envelope 的 msg（那是 RN 能 includes 到的来源）。
     */
    private fun pinFailureKey(error: Throwable?): String =
        if (error is ApiException.Business &&
            error.serverMessage?.contains(PIN_LIMIT_MSG_FRAGMENT) == true
        ) {
            KEY_PIN_LIMIT
        } else {
            KEY_PIN_FAILED
        }

    /**
     * 删除/置顶成功后的创作列表对账重拉（RN 的 `createdMutate`）。
     *
     * 只动创作 tab：pin/delete 不影响四个统计数字与其它 tab。保留旧列表直到
     * 新数据到达（清空会闪白，§8.4）。菜单动作只存在于创作 tab，此刻
     * [cancelInFlight] 打断的必然是自己 tab 的在飞链。
     */
    private fun reloadCreatedTab() {
        cancelInFlight()
        updatePaging(ProfileTab.CREATED) { it.copy(errorMessage = null) }
        loadPages(ProfileTab.CREATED, fromPage = 0)
    }

    // ── 内部 ────────────────────────────────────────

    private fun loadFirstPageIfNeeded(tab: ProfileTab) {
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
                totalIsPages = page.totalIsPages,
                emptyAfterDedupeStreak = streak,
                hasLoadedOnce = true,
            )
            // 页数轨的 pagesLoaded = 已完成的页数 = pageIndex + 1（0-based）
            val done = updated.reachedEnd(merged.size, pagesLoaded = pageIndex + 1)
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
     * 归一化：不同 tab 的响应形状不同（创作是内联嵌套对象、记忆是关系型 join、
     * 收藏/点赞的 total 是**页数**），但翻页壳只需要 `items + total (+量纲)`。
     */
    private suspend fun fetchPage(tab: ProfileTab, page: Int): TabPage = when (tab) {
        ProfileTab.CREATED ->
            api.fetchCreatedPage(page = page, languageCode = languageProvider())
                .let { TabPage(it.items, it.total) }

        ProfileTab.MEMORY ->
            api.fetchMemoryPage(page = page)
                .let { TabPage(it.items, it.total) }

        ProfileTab.ROLE_CARD ->
            api.fetchRoleCardPage(page = page)
                .let { TabPage(it.items, it.total) }

        // ⚠️ totalIsPages：这两个接口给的是 total_pages，
        // 判到底要用页数轨（ProfileTabPaging.reachedEnd）
        ProfileTab.FAVORITES ->
            api.fetchFavoritePage(page = page, liked = false)
                .let { TabPage(it.items, it.totalPages, totalIsPages = true) }

        ProfileTab.LIKED ->
            api.fetchFavoritePage(page = page, liked = true)
                .let { TabPage(it.items, it.totalPages, totalIsPages = true) }
    }

    private fun refreshUserAndStats(
        onUserInfoRefreshed: ((String) -> Unit)? = null,
        onUserInfoRefreshFailed: (() -> Unit)? = null,
    ): Job {
        userStatsJob?.cancel()
        var userInfoRefreshFailed = false
        val job = coroutineScope.launch {
            // 先刷用户信息：statsInfo 需要 userId
            val userInfoRefreshed = userStore.refresh()
            val user = userStore.current.value
            _state.value = _state.value.copy(user = user)
            // 头像框独立成子协程：随本 job 一起被取消（登出/新刷新不残留旧写回），
            // 但一个纯装饰目录不得阻塞头像昵称落地，也不得垫在 stats/钱包链前面
            launch { resolveAvatarDecoration(user?.avatarDecorationCode) }
            val userId = user?.userId
            // EditProfile 的 dirty 只能由一次真正成功的 /user/info 响应确认清除。
            // refresh 失败会保留 CurrentUserStore 的旧值；若只看 user != null，
            // 会把旧缓存误当成本次成功并吞掉后续重试。
            if (userInfoRefreshed && userId != null) {
                onUserInfoRefreshed?.invoke(userId)
            } else {
                userInfoRefreshFailed = true
            }
            if (userId == null) return@launch
            val stats = runCatching { api.fetchSelfStats(userId) }
                .onFailure {
                    if (it is CancellationException) throw it
                    logWarn("拉取 /user/stats_info 失败，保留已有统计", it)
                }
                .getOrNull()
            if (stats != null) {
                _state.value = _state.value.copy(stats = stats)
            }
            refreshWallet()
        }
        job.invokeOnCompletion { error ->
            // completion 后再报失败：协调器会排队补一次重试；若在协程体内同步回调，
            // 新 refresh 会从旧 userStatsJob 里取消自己。被新 mutation/登出取消的任务
            // 不算失败事件——前者已有新 revision，后者已清 dirty。
            if (error == null && userInfoRefreshFailed) onUserInfoRefreshFailed?.invoke()
        }
        userStatsJob = job
        return job
    }

    /**
     * 头像框 code → 图片 URL（P7，方案 §8.1 Profile 行）。
     *
     * 语义对齐 RN：`useAvatarDecorationConfig` 读的是 MMKV 持久化过的
     * `config-persist` 目录，**瞬时网络失败不掉框**。所以：
     * - code 为空 = 明确「未佩戴」，立即清空 —— EditProfile 取消佩戴后
     *   `notifyProfileChanged` 触发的刷新不能把旧框留在屏上；
     * - 目录返回但查无此 code / `image_url` 为空 → 清空（目录才是真值）；
     * - 拉取**失败**保留上次的 URL（同钱包/统计「一次网络抖动不清屏」的纪律）。
     *
     * 账号边界不靠这里：登出/换号走 [onAuthChanged] 整表复位 `ProfileState()`，
     * 且本函数总在 [userStatsJob] 的子协程里跑，旧账号的在飞解析会随 job 取消。
     */
    private suspend fun resolveAvatarDecoration(code: String?) {
        val source = avatarDecorationSource ?: return
        if (code.isNullOrBlank()) {
            _state.value = _state.value.copy(avatarDecorationImageUrl = null)
            return
        }
        runCatching { source.fetchImageUrl(code) }
            .onSuccess { url ->
                _state.value = _state.value.copy(avatarDecorationImageUrl = url)
            }
            .onFailure {
                if (it is CancellationException) throw it
                logWarn("拉取头像框目录失败，保留上次头像框", it)
            }
    }

    /**
     * 钱包 = 两个接口合成：`/wallet/info` 的余额 + `/subscription/get/active`
     * 的档位。**各自失败各自保留旧值**（同 stats 的纪律：一次网络抖动不该把
     * 用户正看着的余额清零），两个都失败则整块不动。
     */
    private suspend fun refreshWallet() {
        val current = _state.value.wallet
        val wallet = runCatching { walletApi.fetchWallet() }
            .onFailure {
                if (it is CancellationException) throw it
                logWarn("拉取 /wallet/info 失败，保留已有钱包", it)
            }
            .getOrNull()
        val planId = runCatching { walletApi.fetchSubscriptionPlanId() }
            .onFailure {
                if (it is CancellationException) throw it
                logWarn("拉取 /subscription/get/active 失败，保留已有档位", it)
            }
            .getOrNull()
        if (wallet == null && planId == null) return
        _state.value = _state.value.copy(
            wallet = (wallet ?: current).copy(planId = planId ?: current.planId),
        )
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

    /** 一页的归一化形状，[fetchPage] 的返回值。[totalIsPages] 见 [ProfileTabPaging]。 */
    private data class TabPage(
        val items: List<ProfileListEntry>,
        val total: Long,
        val totalIsPages: Boolean = false,
    )

    companion object {
        private const val TAG = "ProfileViewModel"

        /** i18n key（key = 英文原文）。UI 层判等后走 LocalizedText，同 Home。 */
        const val FALLBACK_ERROR_KEY = "Please try again later"

        // P5 菜单动作的 Toast keys（词条已全部在 SHELL_KEYS，勿改英文原文）
        const val KEY_PIN_OK = "Pinned successfully"
        const val KEY_UNPIN_OK = "Unpinned successfully"
        const val KEY_PIN_FAILED = "Pinned failed"
        const val KEY_PIN_LIMIT = "Up to 3 pins allowed. Unpin one to try again."
        const val KEY_DELETE_FAILED = "Delete failed"

        /** RN 判上限的子串（`CharacterGridItem.tsx:415`），大小写照原文。 */
        private const val PIN_LIMIT_MSG_FRAGMENT = "up to 3 pins allowed"
    }
}
