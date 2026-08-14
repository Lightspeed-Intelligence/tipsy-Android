package ai.lightspeed.tipsy.shell.pages.search

import ai.lightspeed.tipsy.shell.pages.home.HomeTag

/**
 * 搜索结果标签栏的排序（`deriveResultTagOrder`，`searchTagOrder.ts` 81 行）。
 *
 * RN 侧有 **144 行现成单测**（`searchTagOrder.test.ts`），本实现照它逐条对拍
 * （方案 §8.2「现成 fixture 来源」的用法）。
 *
 * ## 四层优先级，顺序不能调
 *
 * 1. **选中项按「选择顺序」置前** —— 不是按目录顺序。用户先点 B 后点 A，
 *    显示就是 B、A（`selectedTagIds` 本身即选择顺序）。
 * 2. **「特殊呈现」标签优先**（活动标签等，见 [HomeTag.hasSpecialPresentation]）。
 * 3. **目录里存在任一带 `sort_order` 的标签**时按 `sort_order` 排，
 *    否则按**配置顺序**排。⚠️ 这是**集合级**判定
 *    （`searchTagOrder.ts:39-41` `hasKnownSortOrder`），不是逐项判定 ——
 *    所以 `sortOrder` 必须能区分 null 与 0。
 * 4. 同序时按 **`tag_aggs` 的聚合顺序**兜底（即 [tagIds] 的原始下标）。
 *
 * ## 两个输入的语义完全不同
 *
 * - [tagIds] 来自**搜索响应的 `tag_aggs`** = 「本次命中结果里有哪些标签」
 * - [configuredTags] 来自**标签目录**（`/character/tags`）= 「这个标签长什么样」
 *
 * ⚠️ **目录里没有的 id 直接丢弃**（`visibleTagMap.has(id)` 守卫）——
 * 后端聚合可能给出已下线的标签，渲染它会得到一个没有文案的空胶囊。
 */
object SearchTagOrder {

    /**
     * @param tagIds `tag_aggs` 的 id 列表（有序）
     * @param selectedTagIds 已选中的 id，**按选择顺序**
     * @param configuredTags 标签目录（已过 `show_in_filter`，见 `HomeTagParser`）
     * @return 展示顺序的 id 列表
     */
    fun derive(
        tagIds: List<String>,
        selectedTagIds: List<String>,
        configuredTags: List<HomeTag>,
    ): List<String> {
        val visible = configuredTags.associateBy { it.id }
        // 集合级判定：目录里**任一**标签带 sort_order 就走 sort_order 轨
        val hasKnownSortOrder = configuredTags.any { it.sortOrder != null }

        // ① 选中项按选择顺序置前（目录里没有的丢掉）
        val selectedInOrder = selectedTagIds.filter { visible.containsKey(it) }
        val selectedSet = selectedInOrder.toSet()

        // 其余按 tag_aggs 顺序取，记下聚合下标做最终兜底（第 ④ 层）
        val rest = tagIds
            .filter { visible.containsKey(it) && it !in selectedSet }
            .mapIndexed { aggregationIndex, id -> id to aggregationIndex }

        val sorted = rest.sortedWith { left, right ->
            val leftTag = visible.getValue(left.first)
            val rightTag = visible.getValue(right.first)

            // ② 特殊呈现优先（RN 是 Number(right) - Number(left)，即 true 在前）
            val presentation = rightTag.hasSpecialPresentation.compareTo(
                leftTag.hasSpecialPresentation,
            )
            if (presentation != 0) return@sortedWith presentation

            if (!hasKnownSortOrder) {
                // ③b 无人带 sort_order → 按配置顺序，同序按聚合序
                val byConfig = leftTag.configIndex.compareTo(rightTag.configIndex)
                return@sortedWith if (byConfig != 0) {
                    byConfig
                } else {
                    left.second.compareTo(right.second)
                }
            }

            // ③a 有人带 sort_order → 带的排前面，两边都带时比值
            val leftOrder = leftTag.sortOrder
            val rightOrder = rightTag.sortOrder
            when {
                leftOrder != null && rightOrder != null -> {
                    val byOrder = leftOrder.compareTo(rightOrder)
                    if (byOrder != 0) byOrder else left.second.compareTo(right.second)
                }
                // ⚠️ 单边带值时**不比聚合序**，直接定序（对齐 RN 的 return -1 / 1）
                leftOrder != null -> -1
                rightOrder != null -> 1
                else -> left.second.compareTo(right.second)
            }
        }

        return selectedInOrder + sorted.map { it.first }
    }
}
