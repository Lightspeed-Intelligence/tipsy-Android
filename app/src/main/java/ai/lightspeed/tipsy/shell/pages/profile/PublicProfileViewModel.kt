package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.analytics.Analytics
import ai.lightspeed.tipsy.shell.auth.Generations
import ai.lightspeed.tipsy.shell.network.ApiException
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
 * 他人主页的编排（W3，进度文档 §2.32）。
 *
 * ## 本刀的范围
 *
 * **已做**：公开资料头部（`/user/get/public`）、四统计（`/user/stats_info` 的
 * OPPORTUNISTIC 那条）、单列表 v2→v1 回落、关注 toggle。
 *
 * **刻意不做**（与 RN 对等，不是漏实现）：
 * - **翻页** —— 他人主页在 RN 侧翻不了页（§2.32 第 5 条，见
 *   [PublicProfileApi.FIRST_PAGE]）
 * - **记忆 tab** —— `/plot/list/creator` 在现网从未被调用（§2.32 第 6 条：
 *   唯一调用点不传 `isPersonal`，SWR key 恒 `undefined`）
 * - **钱包卡** —— `isSelf && UserProfileGems`（`CharacterGrid.tsx:1431`）
 * - **五图标 tab 栏** —— `renderTabBar` 开头就 `if (!isSelf) return null`
 *
 * ## 两条并发链，分开取消
 *
 * 资料+统计是一条（[loadJob]），关注 toggle 是另一条（[followJob]）。
 * 不合并的理由：关注请求成功后要**重拉**资料与统计，若两者共用一个 job
 * 变量，重拉会把自己取消掉。
 *
 * ## ⚠️ 列表 v2 空 → 回落 v1
 *
 * `CharacterGrid.tsx:980-983` 的三元：`creatorCreatedList?.length > 0 ? v2 : v1`。
 * RN 是两个请求**都发**（两个 `useSWRInfinite`），壳改成**串行**：v2 有货就不发
 * v1。理由是省一个必然被丢弃的请求，且 v1 的结果在 v2 非空时**永远不上屏** ——
 * 并发发它只是为了 SWR 的缓存形状。行为对等（用户看到的列表相同），
 * 代价是 v2 空时多一个串行 RTT。
 */
class PublicProfileViewModel(
    private val api: PublicProfileSource,
    private val languageProvider: () -> String,
    /**
     * auth 轨闸门（§4.4）。**只校验 auth 轨**，不校验 mutation ——
     * 他人主页不拥有乐观列表变更（没有删除/置顶动作），拿全量 `isValid`
     * 会被 ChatList 的全局 mutation bump 误作废（Search 侧同一条推理，§2.31）。
     */
    private val generations: Generations,
    /** 注入是为了测试；生产用 viewModelScope。 */
    private val scope: CoroutineScope? = null,
    /** 注入而非直接调用：JVM 单测里 `android.util.Log` 是抛 "not mocked" 的桩。 */
    private val logWarn: (String, Throwable?) -> Unit = { msg, t -> Log.w(TAG, msg, t) },
) : ViewModel() {

    private val _state = MutableStateFlow(PublicProfileState())
    val state: StateFlow<PublicProfileState> = _state.asStateFlow()

    private val coroutineScope: CoroutineScope get() = scope ?: viewModelScope

    /** 资料 + 统计 + 列表这条链。 */
    private var loadJob: Job? = null

    /** 关注 toggle 那条链（与 [loadJob] 分开，见类注释）。 */
    private var followJob: Job? = null

    /**
     * 绑定目标用户并拉首屏。由 Fragment 在 `onViewCreated` 调一次。
     *
     * 幂等：同一 userId 且已有资料时不重拉（Fragment 重建走 saved state 时
     * 会再调一次）。换 userId 则整个重置 —— 同一个 Fragment 实例理论上不会
     * 换目标，但状态残留会让上一个人的卡片闪一下。
     */
    fun bind(userId: String) {
        if (userId.isBlank()) {
            logWarn("他人主页收到空 userId，不加载", null)
            return
        }
        val current = _state.value
        if (current.userId == userId && (current.profile != null || current.isLoading)) return
        loadJob?.cancel()
        // ⚠️ 换目标也要取消在飞的关注链：它成功后会重拉**上一个** userId 的资料，
        // 把 A 的昵称头像写进 B 的页面。auth 轨挡不住这个（没换号），
        // 所以除了取消，[onFollowClick] 的回写还额外校验 userId 未变。
        followJob?.cancel()
        _state.value = PublicProfileState(userId = userId, isLoading = true)
        load(userId)
    }

    /**
     * 下拉刷新。
     *
     * 保留旧内容直到新数据到达（`isRefreshing` 而非清空）—— 同
     * [ProfileViewModel.onRefresh] 的理由：清空会整屏闪白，失败后用户什么都看不到。
     *
     * ⚠️ 注销用户不响应刷新（RN 连刷新控件都不渲染，见
     * [PublicProfileState.isRefreshEnabled]）。这里再挡一次 —— UI 不渲染控件
     * 是视觉层，ViewModel 自己也该守住语义。
     */
    fun onRefresh() {
        val s = _state.value
        if (s.isRefreshing || s.userId.isBlank() || !s.isRefreshEnabled) return
        loadJob?.cancel()
        _state.value = s.copy(isRefreshing = true, errorMessage = null)
        load(s.userId)
    }

    /**
     * 点关注按钮。
     *
     * ## ⚠️ 成功后**重拉**，不本地翻转
     *
     * RN 的 `handleFollowUser` 成功后 `await publicUserMutate()` +
     * `await followerInfoMutate()`（`useProfile.tsx:241-243`）——
     * 因为关注会改变 followers 计数。只翻转本地 `isFollowed` 的表现是
     * 「按钮变了但粉丝数不动」，看着像数字没刷新，其实是没重拉。
     *
     * ## 连点防护
     *
     * toggle 端点连点两次 = 净零操作，但中间会让计数闪两下。
     * [PublicProfileState.isFollowPending] 期间直接返回。
     *
     * ## 注销用户不发请求
     *
     * RN 的 `handleFollowUser` 里显式 `throw new Error('Unable to follow
     * deleted user')`（`useProfile.tsx:238-239`）。壳侧按钮本就不渲染，
     * 这里是第二道闸。
     */
    fun onFollowClick() {
        val s = _state.value
        val profile = s.profile ?: return
        if (s.isFollowPending || profile.isDeleted || s.userId.isBlank()) return
        _state.value = s.copy(isFollowPending = true)
        followJob?.cancel()
        val snapshot = generations.snapshot()
        followJob = coroutineScope.launch {
            runCatching { api.toggleFollow(s.userId) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    logWarn("关注/取关失败", error)
                    // 换号后不写旧账号的 pending 态（新账号的页面已重置）
                    if (!generations.isAuthValid(snapshot)) return@launch
                    _state.value = _state.value.copy(isFollowPending = false)
                    return@launch
                }
            // ⚠️ 换号后不得重拉、不得写状态：这次关注属于上一个账号
            if (!generations.isAuthValid(snapshot)) return@launch
            // ⚠️ 换目标后同样不得回写：重拉的是**旧** userId 的资料，
            // 会把 A 的昵称头像写进 B 的页面（auth 轨挡不住 —— 没换号）
            if (_state.value.userId != s.userId) return@launch
            // 成功：重拉资料与统计（见方法注释「不本地翻转」）
            _state.value = _state.value.copy(isFollowPending = false)
            refreshProfileAndStats(s.userId, snapshot)
        }
    }

    /**
     * 首次可见时上报页面曝光。
     *
     * ⚠️ **事件名与自己视角相同**（`page_exposure` + `page_name: profile`）——
     * RN 两处都发这一个事件，不按视角分流（方案 §8.1 埋点列）。
     * 区分在参数：`entry_type` = `stack`（他人是 push 进来的）vs `tab`，
     * 以及 `is_self`（`user-profile.tsx:201-203`）。
     *
     * ⚠️ 别新造一个 `other_profile_exposure` —— 那会让同一漏斗在两端对不上。
     */
    fun onFirstExposure() {
        Analytics.track(
            "page_exposure",
            mapOf(
                "page_name" to "profile",
                "platform" to "app",
                // 他人主页是压栈进来的（自己视角是 tab），照 user-profile.tsx:201
                "entry_type" to "stack",
                "is_self" to "false",
            ),
        )
    }

    /**
     * 登录态变化。
     *
     * ## 登出：清关注态，但**不清页面**
     *
     * 与 [ProfileViewModel.onAuthChanged]（整页清空）不同 —— 他人主页看的是
     * **别人**的公开资料，登出后那些内容仍然是可展示的（列表走
     * OPPORTUNISTIC，游客也能拉）。清空会让用户在登出瞬间莫名看到空页。
     *
     * 但 [PublicUserProfile.isFollowed] 是**账号私有**的：它属于上一个账号的
     * 关注关系。不清的表现是「登出后仍显示 Following」，且点它会弹登录页。
     *
     * ⚠️ 登录/换号则重拉 —— 新账号的关注关系不同。
     */
    fun onAuthChanged(loggedIn: Boolean) {
        followJob?.cancel()
        followJob = null
        // ⚠️ 登出也必须取消在飞的 load —— 不取消的话，那条链的 `/user/get/public`
        // 响应会带着旧账号的 is_followed 回来。auth 快照校验已能挡住回写
        // （见 [load] 注释），取消是第一道闸：省掉一次注定被丢弃的续拉。
        loadJob?.cancel()
        val s = _state.value
        if (s.userId.isBlank()) return
        if (loggedIn) {
            _state.value = s.copy(isFollowPending = false)
            load(s.userId)
        } else {
            // 只清账号私有的关注态；列表与资料留着（见方法注释）
            _state.value = s.copy(
                profile = s.profile?.copy(isFollowed = false),
                isFollowPending = false,
                isLoading = false,
                isRefreshing = false,
            )
        }
    }

    /**
     * 语言 settle 后重拉列表（方案 §8.4 第 2 条）。
     *
     * v2 请求体带 `language_code`（v1 不带）。资料与统计与语言无关，
     * 但这里一并重拉 —— 单链实现简单，多两个轻请求换掉一类「只重拉一半」的 bug。
     */
    fun onLanguageSettled() {
        val s = _state.value
        if (s.userId.isBlank()) return
        loadJob?.cancel()
        load(s.userId)
    }

    // ── 内部 ────────────────────────────────────────

    /**
     * 拉一轮资料 + 统计 + 列表。
     *
     * ## ⚠️ auth 快照在**发请求前**捕获，回写前校验
     *
     * 取消不足以防住这类时序：`loadJob.cancel()` 只在**挂起点**生效，
     * 而取消发生后、协程真正抛 `CancellationException` 之前，
     * 非挂起代码（写 `_state`）照常执行。§2.25 已经在
     * `CurrentUserStore.refresh` 上踩过同一个坑（`runCatching` 吞掉取消，
     * 让登出瞬间在飞的 `/user/info` 把旧账号资料写回已清空的状态）。
     *
     * 这里的具体后果：登出瞬间在飞的 `/user/get/public` 响应带着
     * `is_followed = true` 回来，把 [onAuthChanged] 刚清掉的关注态写回去 ——
     * 表现正是「登出后仍显示 Following」，而那是本刀专门要避免的。
     */
    private fun load(userId: String) {
        val snapshot = generations.snapshot()
        loadJob = coroutineScope.launch {
            runCatching {
                refreshProfileAndStats(userId, snapshot)
                loadList(userId, snapshot)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                // 换号后的失败也不该把错误摆到新账号的页面上
                if (!generations.isAuthValid(snapshot)) return@launch
                onLoadFailed(error)
            }
            if (!generations.isAuthValid(snapshot)) return@launch
            // 成功路径统一收圈（失败路径在 onLoadFailed 里收）
            _state.value = _state.value.copy(isLoading = false, isRefreshing = false)
        }
    }

    /**
     * 资料 + 统计。
     *
     * 资料**失败要抛**（它是整页的前提：没有昵称头像的他人主页没有意义，
     * 且 REQUIRED 失败通常意味着未登录 —— 那时该让错误态显示出来）。
     * 统计**失败只记日志**（四个数字缺失不该让整页失败，同
     * [ProfileViewModel.refreshUserAndStats] 的纪律）。
     */
    private suspend fun refreshProfileAndStats(userId: String, snapshot: Generations.Snapshot) {
        val profile = api.fetchPublicUser(userId)
        // ⚠️ 逐步校验，不是只在链尾校一次：资料与统计之间还有一个挂起点，
        // 中途换号时资料已写、统计未写，会得到 A 的资料配 B 的数字
        if (!generations.isAuthValid(snapshot)) return
        if (profile != null) {
            _state.value = _state.value.copy(profile = profile)
        }
        val stats = runCatching { api.fetchPublicStats(userId) }
            .onFailure {
                if (it is CancellationException) throw it
                logWarn("拉取他人 /user/stats_info 失败，保留已有统计", it)
            }
            .getOrNull()
        if (!generations.isAuthValid(snapshot)) return
        if (stats != null) {
            _state.value = _state.value.copy(stats = stats)
        }
    }

    /**
     * 列表：v2 优先、空则回落 v1（见类注释）。
     *
     * ⚠️ **v2 失败也要回落 v1**，不只是空 —— RN 两个请求独立，v2 挂了 SWR 给
     * `undefined`，三元判定同样落到 v1。只在"空"时回落会让 v2 端点故障时
     * 整页空白，而 RN 那边照样有内容。
     */
    private suspend fun loadList(userId: String, snapshot: Generations.Snapshot) {
        val v2 = runCatching { api.fetchCreatorListV2(userId, languageProvider()) }
            .onFailure {
                if (it is CancellationException) throw it
                logWarn("拉取 creator 列表 v2 失败，回落 v1", it)
            }
            .getOrNull()
        val items = if (!v2?.items.isNullOrEmpty()) {
            v2.items
        } else {
            // 回落：v1 的扁平响应，解析时补 item_type（见 CreatorListPage.parseV1）
            api.fetchCreatorListV1(userId).items
        }
        if (!generations.isAuthValid(snapshot)) return
        // 去重后写入。**接口顺序即显示顺序** —— 他人主页无置顶排序规则
        // （那是自己视角创作卡的 is_pinned，他人列表由后端排好）
        val seen = HashSet<String>()
        _state.value = _state.value.copy(
            items = items.filter { seen.add(it.dedupeKey) },
            errorMessage = null,
        )
    }

    private fun onLoadFailed(error: Throwable) {
        logWarn("加载他人主页失败", error)
        val s = _state.value
        _state.value = s.copy(
            isLoading = false,
            isRefreshing = false,
            // ⚠️ 已有内容时不显错误（方案 §8.4）—— 刷新失败不该清掉正看着的列表
            errorMessage = if (s.items.isEmpty()) errorMessageOf(error) else s.errorMessage,
        )
    }

    /** 同 [ProfileViewModel.errorMessageOf]：后端 msg 优先，空则回落 i18n key。 */
    private fun errorMessageOf(error: Throwable): String = when (error) {
        is ApiException.Business ->
            error.serverMessage?.takeIf { it.isNotBlank() } ?: ProfileViewModel.FALLBACK_ERROR_KEY
        else -> ProfileViewModel.FALLBACK_ERROR_KEY
    }

    private companion object {
        const val TAG = "PublicProfileViewModel"
    }
}
