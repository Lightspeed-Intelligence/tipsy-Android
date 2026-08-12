package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.user.CurrentUser

/**
 * Profile 的页面状态（同 `HomeState` 的理由：单个 data class 原子替换，
 * 不要 N 个 StateFlow —— 那会让同时变化的字段分帧到达 UI）。
 *
 * ## 分页状态按 tab 分表存
 *
 * [paging] 是 `tab → 该 tab 的分页状态`（参考 `HomeViewModel` 的
 * `loaded: MutableMap<HomeSeries, ...>` 形状），**不是给每个 tab 加一组字段**。
 * 五个 tab 各自独立翻页，切 tab 不重置对方进度 —— 理由见 [ProfileTabPaging]。
 *
 * 当前 tab 的派生属性（[items] / [isInitialLoading] …）只是读 [paging] 的糖，
 * 让 UI 不必自己查表。
 */
data class ProfileState(
    /** 当前用户；null 表示还没拉到（首次冷启动进页面时的瞬态）。 */
    val user: CurrentUser? = null,
    /** 四个统计数字。⚠️ 字段与标签交叉，见 [ProfileStats] 类注释。 */
    val stats: ProfileStats = ProfileStats.EMPTY,
    /** 钱包三栏卡（宝石/免费/金币 + 订阅档位）。拉取失败保留旧值，同 stats。 */
    val wallet: ProfileWallet = ProfileWallet.EMPTY,
    /** 当前选中的 tab。⚠️ 顺序即埋点的 `active_tab_index`，见 [ProfileTab]。 */
    val selectedTab: ProfileTab = ProfileTab.CREATED,
    /** `tab → 分页状态`。缺键视为初始态（见 [pagingOf]）。 */
    val paging: Map<ProfileTab, ProfileTabPaging> = emptyMap(),
    /** 下拉刷新是整页级的（用户信息 + 统计 + 当前 tab 列表），故不进 [paging]。 */
    val isRefreshing: Boolean = false,
) {
    fun pagingOf(tab: ProfileTab): ProfileTabPaging = paging[tab] ?: ProfileTabPaging()

    private val current: ProfileTabPaging get() = pagingOf(selectedTab)

    // ── 当前 tab 的派生属性（UI 直接读，不必查表）──────────────

    val items: List<ProfileListEntry> get() = current.items

    /** 当前 tab 的创作条目；非创作 tab 时为空。 */
    val createdItems: List<ProfileCreatedItem>
        get() = current.items.filterIsInstance<ProfileCreatedItem>()

    /** 当前 tab 的记忆条目；非记忆 tab 时为空。 */
    val memoryItems: List<ProfileMemoryItem>
        get() = current.items.filterIsInstance<ProfileMemoryItem>()

    /**
     * 当前 tab 的角色卡条目，**默认卡置顶**（`sortRoleCardsWithDefaultFirst`，
     * 排序在派生层做 —— 分页累计保持接口顺序，置顶只是显示规则）。
     * `sortedBy` 是稳定排序：非默认卡之间保持原始相对顺序。
     */
    val roleCardItems: List<ProfileRoleCardItem>
        get() = current.items.filterIsInstance<ProfileRoleCardItem>()
            .sortedBy { if (it.makeDefault) 0 else 1 }

    /** 当前 tab 的收藏/点赞条目（两 tab 同模型）；其它 tab 时为空。 */
    val favoriteItems: List<ProfileFavoriteItem>
        get() = current.items.filterIsInstance<ProfileFavoriteItem>()

    /** 首屏加载中（列表为空且在请求）。 */
    val isInitialLoading: Boolean get() = current.isInitialLoading
    val isLoadingMore: Boolean get() = current.isLoadingMore

    /** 已到底：不再触发翻页。判据是 `累计数 >= total`，见 [ProfileTabPaging.reachedEnd]。 */
    val hasReachedEnd: Boolean get() = current.hasReachedEnd

    /**
     * 当前 tab 的首屏错误文案。
     *
     * ⚠️ **只在 [items] 为空时展示** —— 已有数据时翻页失败不清列表
     * （方案 §8.4，与 `HomeState.errorMessage` 同一条纪律）。
     */
    val errorMessage: String? get() = current.errorMessage
}
