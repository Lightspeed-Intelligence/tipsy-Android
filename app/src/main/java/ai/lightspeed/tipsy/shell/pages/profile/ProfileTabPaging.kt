package ai.lightspeed.tipsy.shell.pages.profile

/**
 * 单个 tab 的分页状态。
 *
 * ## 为什么每个 tab 一份，而不是 ViewModel 上几个裸字段
 *
 * 五个 tab 各自独立翻页：切到记忆再切回创作，创作已加载的 3 页和
 * `nextPage` 都要还在（RN 侧每个 tab 是自己的 `useSWRInfinite`，
 * 切 tab 不重置）。用 ViewModel 上的 `nextPage` / `total` 裸字段
 * 会让两个 tab 互相踩：切 tab 后 `nextPage` 还是上一个 tab 的页码，
 * 于是新 tab 从第 3 页开始拉，首屏直接缺前三页。
 *
 * ⚠️ [emptyAfterDedupeStreak] 也必须**按 tab 各存一份**，理由同
 * `ProfileViewModel` 里那条注释（跨调用累计才挡得住请求风暴）——
 * 但它同时不能跨 tab 共享，否则一个 tab 的空页续拉次数会误挡另一个 tab。
 *
 * @property items 已累计的条目；具体类型按 tab 不同，UI 层 `when` 分支渲染
 * @property nextPage 下一次要拉的页码（0-based）
 * @property total 服务端总数，到底判定用它
 */
data class ProfileTabPaging(
    val items: List<ProfileListEntry> = emptyList(),
    val nextPage: Int = 0,
    val total: Long = 0L,
    /**
     * [total] 的量纲：false = 条数（创作/记忆/角色卡），true = **页数**
     * （收藏/点赞的 `total_pages`，`useProfileFavorites.ts:63-66` 的判定是
     * `已拉页数 >= total_pages` —— 拿条数去比会在第一页就误判到底）。
     */
    val totalIsPages: Boolean = false,
    val isInitialLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasReachedEnd: Boolean = false,
    val emptyAfterDedupeStreak: Int = 0,
    /** 首屏错误文案；**只在 [items] 为空时**给 UI 展示（方案 §8.4）。 */
    val errorMessage: String? = null,
    /** 是否已经拉过首屏 —— 用于切 tab 时判断要不要触发首次加载。 */
    val hasLoadedOnce: Boolean = false,
) {
    /**
     * 到底判定，按 [totalIsPages] 分两轨：
     * - 条数轨：**已去重累计数 >= total**（`useCreatedList.ts:98-101`）
     * - 页数轨：**已拉页数 >= total_pages**（`useProfileFavorites.ts:63-66`）
     *
     * ⚠️ `total` 为 0 时两轨都算**已到底** —— RN 是 `if (!total) return true` /
     * `if (!total_pages) return true`。反过来写（0 当成"还没拿到总数、继续拉"）
     * 会让空列表无限翻页。
     */
    fun reachedEnd(loadedCount: Int, pagesLoaded: Int): Boolean {
        if (total <= 0L) return true
        return if (totalIsPages) pagesLoaded >= total else loadedCount >= total
    }

    companion object {
        /** 空页续拉的次数上限，同创作 tab（方案 §8.4）。 */
        const val MAX_EMPTY_DEDUPE_STREAK = 3
    }
}
