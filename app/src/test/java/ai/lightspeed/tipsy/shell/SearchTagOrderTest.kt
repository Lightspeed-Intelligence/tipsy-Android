package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.home.HomeTag
import ai.lightspeed.tipsy.shell.pages.search.SearchTagOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 标签栏排序（`SearchTagOrder`）—— **逐条对拍 RN 的
 * `searchTagOrder.test.ts`**（144 行，方案 §8.2「现成 fixture 来源」）。
 *
 * 六个用例的名字与断言都照抄那份，只把 fixture 换成 [HomeTag]。
 * 四层优先级里任一层写错都会被其中至少一条抓到。
 *
 * ⚠️ `configIndex` 在测试里 = **传入列表的下标**（对齐 RN
 * `configuredTags.forEach((tag, index))`）。生产侧由 `HomeTagParser` 在
 * 按 `sort_order` 排序**之后**赋值 —— 因为 RN 那个数组本身已排过序。
 */
class SearchTagOrderTest {

    @Test
    fun `选中项按选择顺序置前 其余按配置顺序`() {
        val result = SearchTagOrder.derive(
            tagIds = listOf("regular-late", "event", "selected", "regular-early"),
            selectedTagIds = listOf("selected"),
            configuredTags = catalog(
                tag("regular-late", 30),
                tag("event", 1),
                tag("selected", 20),
                tag("regular-early", 10),
            ),
        )
        assertEquals(
            listOf("selected", "event", "regular-early", "regular-late"),
            result,
        )
    }

    /**
     * legacy 活动标签即使 `is_event = false` 也要排在普通标签前。
     *
     * ⚠️ 这条锁的是 `hasSpecialPresentation` 那一层 —— 漏掉 legacy 名称表
     * （Halloween 2025 等 6 条）会让历史活动标签排到后面，两端都不报错。
     */
    @Test
    fun `legacy 活动标签即使 isEvent 为 false 也优先`() {
        val result = SearchTagOrder.derive(
            tagIds = listOf("regular-first", "valentine", "regular-second"),
            selectedTagIds = emptyList(),
            configuredTags = catalog(
                tag("regular-first", -10),
                // isEvent=false 但命中 legacy 名称表 → hasSpecialPresentation
                tag("valentine", 1, special = true),
                tag("regular-second", 0),
            ),
        )
        assertEquals(listOf("valentine", "regular-first", "regular-second"), result)
    }

    @Test
    fun `event 标签无视配置顺序优先`() {
        val result = SearchTagOrder.derive(
            tagIds = listOf("regular", "event"),
            selectedTagIds = emptyList(),
            configuredTags = catalog(
                tag("regular", 1),
                tag("event", 100, special = true),
            ),
        )
        assertEquals(listOf("event", "regular"), result)
    }

    /**
     * 目录里没有的 id **全部丢弃**，选中的也不例外。
     *
     * `hidden` 在 RN 侧是 `show_in_filter: false` —— 壳侧那一层在
     * `HomeTagParser` 就过滤掉了，所以这里等价于「不在目录里」。
     */
    @Test
    fun `过滤掉隐藏与目录外的标签 含已选中的`() {
        val result = SearchTagOrder.derive(
            tagIds = listOf("hidden", "unknown", "visible"),
            selectedTagIds = listOf("hidden", "unknown"),
            // hidden 已被 show_in_filter 过滤，故不进目录
            configuredTags = catalog(tag("visible", 2)),
        )
        assertEquals(listOf("visible"), result)
    }

    /**
     * 同序或缺序时按 **`tag_aggs` 聚合顺序**兜底（第四层）。
     *
     * ⚠️ 单边带 `sort_order` 时**不比聚合序**，直接定序
     * （对齐 RN 的 `return -1 / 1`）—— 所以 unknown 两项排在所有带序项之后。
     */
    @Test
    fun `同序或缺序时保持聚合顺序`() {
        val result = SearchTagOrder.derive(
            tagIds = listOf("unknown-b", "same-b", "unknown-a", "same-a", "first"),
            selectedTagIds = emptyList(),
            configuredTags = catalog(
                tag("unknown-a", null),
                tag("same-a", 10),
                tag("first", 1),
                tag("unknown-b", null),
                tag("same-b", 10),
            ),
        )
        assertEquals(
            listOf("first", "same-b", "same-a", "unknown-b", "unknown-a"),
            result,
        )
    }

    /**
     * 全都没有 `sort_order` 时按**配置数组顺序**。
     *
     * ⚠️ `hasKnownSortOrder` 是**集合级**判定 —— 所以 `sortOrder` 必须能
     * 区分 null 与 0。存成 0 会让这条走进 sort_order 轨，
     * 得到按聚合序（late、early）而不是配置序（early、late）。
     */
    @Test
    fun `全无 sort_order 时按配置数组顺序`() {
        val result = SearchTagOrder.derive(
            tagIds = listOf("late", "early"),
            selectedTagIds = emptyList(),
            configuredTags = catalog(tag("early", null), tag("late", null)),
        )
        assertEquals(listOf("early", "late"), result)
    }

    // ── 壳侧补充用例 ────────────────────────────────

    @Test
    fun `空聚合列表返回空`() {
        assertEquals(
            emptyList<String>(),
            SearchTagOrder.derive(emptyList(), emptyList(), catalog(tag("a", 1))),
        )
    }

    @Test
    fun `选中顺序即显示顺序 后选的排后面`() {
        // 用户先点 b 后点 a → 显示 b、a（不是按目录顺序）
        val result = SearchTagOrder.derive(
            tagIds = listOf("a", "b", "c"),
            selectedTagIds = listOf("b", "a"),
            configuredTags = catalog(tag("a", 1), tag("b", 2), tag("c", 3)),
        )
        assertEquals(listOf("b", "a", "c"), result)
    }

    /** `configIndex` = 传入列表下标（生产侧是排序后的下标）。 */
    private fun catalog(vararg tags: HomeTag): List<HomeTag> =
        tags.mapIndexed { index, tag -> tag.copy(configIndex = index) }

    private fun tag(id: String, sortOrder: Long?, special: Boolean = false) = HomeTag(
        id = id,
        label = id,
        sortOrder = sortOrder,
        hasSpecialPresentation = special,
    )

    private fun tag(id: String, sortOrder: Int, special: Boolean = false) =
        tag(id, sortOrder.toLong(), special)
}
