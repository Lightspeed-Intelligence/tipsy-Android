package ai.lightspeed.tipsy.shell.pages.screen

/**
 * 首屏缓存合并（`mergeShowcaseFirstScreenFeed`，`showcaseFirstScreenFeed.ts` 31 行）。
 *
 * RN 侧有 **53 行现成单测**（`showcaseFirstScreenFeed.test.ts`），照它对拍。
 *
 * ## ⚠️ `slice(1)` 与「写缓存」是一个原子步骤
 *
 * 调用点（`screen.tsx:826-838`）的顺序是：
 * ```
 * pageNum === 0 → setShowcaseFirstScreenCache(sig, nextMediaList[0])   ← 先存第 0 条
 *                → merge(cachedHeadItem, networkItems)                  ← 再 slice(1)
 * ```
 * 所以 [merge] 丢掉的 `networkItems[0]` **正是刚被写进缓存的那条**，
 * 由 [cachedHeadItem] 顶到列表头。**不丢数据**：
 * - 缓存命中 → 头是上次冷启动存的卡（秒开），网络第 2 条起接在后面
 * - 缓存未命中 → 头是网络第 2 条；第 1 条进了缓存，**下次冷启动**才上屏
 *
 * ⚠️ **只抄 `slice(1)` 不抄写缓存 → 首屏真的少一条**，而且是永久少一条。
 *
 * ## 下拉刷新走另一条路
 *
 * `isRefresh` 时**不合并**、直接用全量网络列表（`screen.tsx:831-836`
 * 的三元）。所以刷新后头就是网络第 1 条 —— 这也是"少的那条"能被用户看到的
 * 唯一途径。
 */
object ScreenFirstScreenFeed {

    /**
     * 合并缓存头与网络列表。
     *
     * @param cachedHeadItem 缓存的首屏卡；null = 未命中
     * @param networkItems 本次网络列表（**已附归因**）
     */
    fun merge(
        cachedHeadItem: ScreenFeedItem?,
        networkItems: List<ScreenFeedItem>,
    ): List<ScreenFeedItem> {
        // ⚠️ 无条件丢弃第 0 条 —— 它已被写进缓存，见类注释
        val rest = networkItems.drop(1)
        if (cachedHeadItem == null) return rest

        // 缓存卡的归因是**上次请求**的，必须换成本次响应里的同 id 归因；
        // 本次响应没有这张卡时**清空归因**（而不是留旧的）——
        // 两条 RN 单测分别锁这两种情形
        val matching = networkItems.firstOrNull { it.characterId == cachedHeadItem.characterId }
        val rebound = cachedHeadItem.copy(attribution = matching?.attribution)

        return buildList {
            add(rebound)
            // 去掉与缓存头重复的那条（缓存卡可能出现在 rest 里）
            rest.forEach { if (it.characterId != cachedHeadItem.characterId) add(it) }
        }
    }
}
