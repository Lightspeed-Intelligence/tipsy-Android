package ai.lightspeed.tipsy.shell.pages.screen

import ai.lightspeed.tipsy.shell.auth.Generations
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
 * 大屏页的数据编排（W4-P1，进度文档 §2.35）。
 *
 * ## P1 负责数据，P2 视频已在 UI/播放层接入
 *
 * 本类仍只编排 AB 端点分流、分页 + session 复用、归因、首屏缓存与
 * 会话埋点。Media3 有界池、±1 窗口、buffer/cache 与播放可见性由
 * `ScreenPlayerPool` / `ScreenVideoHost` / `ScreenFragment` 负责（§2.42）。
 * 真实 showcase 视频与 OOM/解码器验收仍 NOT RUN，不得因 P2 代码已接线
 * 就宣称 production-ready。
 *
 * ## ⚠️ session_id 的复用规则：**只有翻页复用**
 *
 * `screen.tsx:773-781`：
 * ```
 * shouldReuseSession = !isRefresh && pageNum > 0
 * ```
 * 也就是：**首屏（page 0）与下拉刷新都传 null**，且首屏/刷新时还要主动
 * **清掉**已存的 session（`:779-781`）。翻页才带上一次响应的 session。
 *
 * 写成「一直复用」的后果：切筛选/刷新后仍在旧推荐池里翻页，
 * 用户看到的内容不更新 —— 与 Home 的「翻页不换 session、筛选换」是**同一条
 * 纪律的镜像**（方案 §8.4 第 4 条）。
 *
 * ## 单在飞链 + auth 轨闸门
 *
 * 同 Home/Profile：任一时刻至多一条分页链，切换维度先取消。
 * 回写前校验 auth 快照（§4.4）—— 取消只在挂起点生效，
 * 不校验会让登出瞬间的响应写进已清空的状态（§2.32 记的同型缺陷）。
 */
class ScreenViewModel(
    private val api: ScreenSource,
    private val tracker: ScreenSessionTracker,
    /** 首屏缓存读写；注入是为了测试（生产是 MMKV 实现）。 */
    private val cache: ScreenFirstScreenCache,
    private val generations: Generations,
    private val languageProvider: () -> String,
    private val nsfwProvider: () -> Boolean,
    /** 当前登录 userId；null = 游客（**AB 恒 distribution**）。 */
    private val ownerUserIdProvider: () -> String?,
    private val scope: CoroutineScope? = null,
    private val logWarn: (String, Throwable?) -> Unit = { msg, t -> Log.w(TAG, msg, t) },
) : ViewModel() {

    private val _state = MutableStateFlow(ScreenState())
    val state: StateFlow<ScreenState> = _state.asStateFlow()

    private val coroutineScope: CoroutineScope get() = scope ?: viewModelScope

    private var inFlight: Job? = null

    /**
     * 上一次响应的 session_id —— **只在翻页时回传**。
     *
     * ⚠️ 首屏与刷新前必须清掉它，见类注释。
     */
    private var listSessionId: String? = null

    /** 当前维度签名（缓存 key + 请求去重）。 */
    private var signature: String = ""

    /**
     * 绑定 AB 端点并拉首屏。
     *
     * @param flagEnabled AB flag；**拉取失败传 false**（见
     *   [ScreenEndpointResolver] 类注释：失败静默走 distribution）
     */
    fun onEndpointResolved(flagEnabled: Boolean) {
        val owner = ownerUserIdProvider()
        val endpoint = ScreenEndpointResolver.resolve(owner, flagEnabled)
        val nextSignature = signatureOf(endpoint, owner)
        // 端点或维度没变且已有内容 → 不重拉（对齐 RN 的 signature 比较）
        if (endpoint == _state.value.endpoint &&
            nextSignature == signature &&
            _state.value.items.isNotEmpty()
        ) {
            return
        }
        signature = nextSignature
        _state.value = _state.value.copy(endpoint = endpoint)
        loadFirstPage()
    }

    /** 进页面时调（幂等：已有内容不重拉）。 */
    fun onAppear() {
        val endpoint = _state.value.endpoint ?: return
        if (_state.value.items.isNotEmpty() || _state.value.isLoading) return
        signature = signatureOf(endpoint, ownerUserIdProvider())
        loadFirstPage()
    }

    /**
     * 页面可见性变化（Fragment 焦点）。
     *
     * ⚠️ 会话的起止是**焦点 × 前台**两条轴的交集，见
     * [ScreenSessionTracker] 类注释。这里只管焦点轴。
     */
    fun onFocusChanged(focused: Boolean) {
        if (focused) {
            tracker.startSession()
            reportCurrentExposure()
        } else {
            tracker.endSession()
        }
    }

    /**
     * 前后台变化。
     *
     * ⚠️ **切后台要 end、回前台重开新会话** —— 不是暂停。
     * 只挂 Fragment 生命周期会漏掉「按 Home 键出去再回来」，
     * 表现为一个跨越数小时的畸形长会话。
     *
     * @param foreground 是否在前台
     * @param focused 页面是否仍聚焦（不聚焦时前后台变化与本页无关）
     */
    fun onAppForegroundChanged(foreground: Boolean, focused: Boolean) {
        if (!focused) return
        if (foreground) {
            tracker.startSession()
            reportCurrentExposure()
        } else {
            tracker.endSession()
        }
    }

    /** 竖向翻页到某一条。 */
    fun onPageChanged(index: Int) {
        val s = _state.value
        if (index == s.currentIndex || index !in s.items.indices) return
        _state.value = s.copy(currentIndex = index)
        reportCurrentExposure()
        maybeLoadMore(index)
    }

    /** 下拉刷新：**清 session**、全量替换、不走首屏缓存合并。 */
    fun onRefresh() {
        val s = _state.value
        if (s.isRefreshing || s.endpoint == null) return
        inFlight?.cancel()
        listSessionId = null
        _state.value = s.copy(isRefreshing = true, isRetryable = false)
        loadPage(page = 0, isRefresh = true)
    }

    /** 重试（5 秒超时或失败后的按钮）。 */
    fun onRetry() {
        if (_state.value.endpoint == null) return
        inFlight?.cancel()
        _state.value = _state.value.copy(isRetryable = false)
        loadFirstPage()
    }

    /** 点输入框 / CTA 前调 —— 一会话一次。 */
    fun onInputClick() {
        tracker.trackInputClick()
    }

    /**
     * 点 CTA 进聊天。
     *
     * ⚠️ RN 在这里 `endHomeSession()`（`screen.tsx:640`）—— 离开页面前
     * 主动结束会话，而**不是**等失焦。顺序是 `trackHomeInputClick` →
     * `endHomeSession` → 导航。
     */
    fun onStartChat() {
        tracker.trackInputClick()
        tracker.endSession()
    }

    fun onCardEvent(event: ScreenCardEvent) {
        tracker.trackCardEvent(event, _state.value.currentIndex, _state.value.currentItem)
    }

    /**
     * 登录态变化。
     *
     * ⚠️ **换号要重解析 AB** —— 配置按 owner 缓存（`service.ts:30-32`），
     * 且游客与登录用户的端点可能不同。所以这里清端点、等调用方重新
     * `onEndpointResolved`。
     */
    fun onAuthChanged() {
        inFlight?.cancel()
        tracker.endSession()
        listSessionId = null
        signature = ""
        _state.value = ScreenState()
    }

    /** 语言 settle 后重拉（请求体带 `language_code`）。 */
    fun onLanguageSettled() {
        val endpoint = _state.value.endpoint ?: return
        inFlight?.cancel()
        listSessionId = null
        signature = signatureOf(endpoint, ownerUserIdProvider())
        _state.value = _state.value.copy(items = emptyList(), currentIndex = 0)
        loadFirstPage()
    }

    // ── 内部 ────────────────────────────────────────

    private fun loadFirstPage() {
        _state.value = _state.value.copy(isLoading = true, isRetryable = false)
        // ⚠️ 首屏必须清 session（见类注释的复用规则）
        listSessionId = null
        loadPage(page = 0, isRefresh = false)
    }

    private fun maybeLoadMore(index: Int) {
        val s = _state.value
        if (s.hasReachedEnd || s.isLoadingMore || s.isLoading || s.isRefreshing) return
        if (inFlight?.isActive == true) return
        // 距列表尾 ≤2 张时预拉（竖向全屏翻页，提前量不能太小）
        if (index < s.items.size - LOAD_MORE_THRESHOLD) return
        _state.value = s.copy(isLoadingMore = true)
        loadPage(page = nextPage, isRefresh = false)
    }

    /** 下一页页码 = 已加载页数（0-based，`screen.tsx` 的 `setPage(pageNum)`）。 */
    private var nextPage: Int = 1

    private fun loadPage(page: Int, isRefresh: Boolean) {
        val endpoint = _state.value.endpoint ?: return
        val snapshot = generations.snapshot()
        val requestSignature = signature
        // ⚠️⚠️ **发请求前**读缓存，不能等响应回来再读。
        //
        // RN 侧 `cachedFirstScreenMedia` 是 `useMemo`（`screen.tsx:237-243`），
        // 在请求之前就求值了；而写缓存发生在响应之后（`:826`）。
        // 顺序反了的后果：冷启动无缓存时，先写入网络第 0 条、再读回来当
        // `cachedHeadItem` —— merge 里的 `drop(1)` 又把它从 rest 里去掉，
        // 于是第 0 条**既是头又被丢**，等价于什么都没变。
        //
        // 看着"结果正确"，但缓存的意义（下次冷启动秒开）没了，而且首屏
        // 顺序与现网不同（现网首次是从第 2 条开始）。**不报错**。
        val cachedHead = if (page == 0 && !isRefresh) cache.get(requestSignature) else null
        inFlight = coroutineScope.launch {
            try {
                // ⚠️ session 只在翻页时回传：!isRefresh && page > 0
                val reuse = !isRefresh && page > 0
                val response = api.fetchPage(
                    endpoint = endpoint,
                    page = page,
                    nsfw = nsfwProvider(),
                    gender = null,
                    languageCode = languageProvider(),
                    tagIds = emptyList(),
                    contentType = null,
                    sessionId = if (reuse) listSessionId else null,
                )
                // 回写前双校验：auth 未变 **且** 维度签名未变
                if (!generations.isAuthValid(snapshot) || requestSignature != signature) return@launch

                listSessionId = response.sessionId
                val attributed = ScreenAttribution.attribute(
                    items = response.items,
                    endpoint = endpoint,
                    requestId = response.requestId,
                    sessionId = response.sessionId,
                    ownerUserId = ownerUserIdProvider(),
                    page = page,
                    pageSize = ScreenApi.PAGE_SIZE,
                )
                // 归因缺失是**诊断事件**，必须报（方案 §8.1）
                tracker.trackAttributionMissing(page, endpoint, attributed.missingFields)

                applyPage(page, isRefresh, attributed.items, requestSignature, cachedHead)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!generations.isAuthValid(snapshot) || requestSignature != signature) return@launch
                logWarn("拉取大屏页失败", e)
                _state.value = _state.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    isLoadingMore = false,
                    // 已有内容时不摆错误（方案 §8.4）
                    isRetryable = _state.value.items.isEmpty(),
                )
            }
        }
    }

    /**
     * 写入一页。
     *
     * ⚠️ **首屏的「写缓存」与「合并」是原子步骤**（`screen.tsx:826-838`）：
     * 先把网络第 0 条写进缓存，再 merge（那里会 drop(1)）。
     * 只做后者会让首屏永久少一条 —— 见 [ScreenFirstScreenFeed] 类注释。
     */
    private fun applyPage(
        page: Int,
        isRefresh: Boolean,
        items: List<ScreenFeedItem>,
        requestSignature: String,
        /** **发请求前**读到的缓存头，见 [loadPage] 的 ⚠️。 */
        cachedHead: ScreenFeedItem?,
    ) {
        val s = _state.value
        val merged = when {
            // 下拉刷新：全量替换，**不合并缓存**（RN 的 isRefresh 分支）
            isRefresh -> items
            page == 0 -> {
                // ① 写缓存（网络第 0 条）—— 供**下次**冷启动用
                items.firstOrNull()?.let { cache.put(requestSignature, it) }
                // ② 用**请求前**读到的那份缓存合并（不是刚写的那条！）
                ScreenFirstScreenFeed.merge(cachedHead, items)
            }
            // 翻页：追加并去重
            else -> {
                val seen = s.items.mapTo(HashSet()) { it.characterId }
                s.items + items.filter { seen.add(it.characterId) }
            }
        }
        nextPage = page + 1
        _state.value = s.copy(
            items = merged,
            currentIndex = if (page == 0) 0 else s.currentIndex,
            isLoading = false,
            isRefreshing = false,
            isLoadingMore = false,
            isRetryable = false,
            // 空页即到底（这两个端点不给 total）
            hasReachedEnd = items.isEmpty(),
        )
        if (page == 0) reportCurrentExposure()
    }

    private fun reportCurrentExposure() {
        val s = _state.value
        val item = s.currentItem ?: return
        tracker.trackCardEvent(ScreenCardEvent.EXPOSURE, s.currentIndex, item)
    }

    private fun signatureOf(endpoint: ScreenEndpoint, owner: String?): String =
        ScreenCacheSignature.of(
            ownerUserId = owner,
            endpoint = endpoint,
            nsfw = nsfwProvider(),
            gender = null,
            languageCode = languageProvider(),
            tagIds = emptyList(),
            contentType = null,
        )

    companion object {
        private const val TAG = "ScreenViewModel"

        /** 距尾 ≤2 张时预拉（竖向全屏翻页，一屏一条）。 */
        const val LOAD_MORE_THRESHOLD = 2
    }
}

/** 首屏缓存接缝（生产是 MMKV，测试用内存实现）。 */
interface ScreenFirstScreenCache {
    /** 签名不匹配时返回 null（见 [ScreenCacheSignature]）。 */
    fun get(signature: String): ScreenFeedItem?
    fun put(signature: String, item: ScreenFeedItem)
}
