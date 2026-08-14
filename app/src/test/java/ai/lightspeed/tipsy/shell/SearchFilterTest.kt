package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.home.HomeTag
import ai.lightspeed.tipsy.shell.pages.home.HomeTagParser
import ai.lightspeed.tipsy.shell.pages.search.SearchContentRating
import ai.lightspeed.tipsy.shell.pages.search.SearchFilter
import ai.lightspeed.tipsy.shell.pages.search.SearchGender
import ai.lightspeed.tipsy.shell.pages.search.SearchSorting
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索筛选的**值映射**（§2.34）。
 *
 * 三组值都是前后端契约，写错的共同表现是「选了筛选但结果没变」——
 * 后端认不出会静默回落，两端都不报错。
 */
class SearchFilterTest {

    // ── 性别 ────────────────────────────────────────

    @Test
    fun `性别 UI 文案与后端值的映射`() {
        assertEquals("female", SearchGender.FEMALE.wire)
        assertEquals("male", SearchGender.MALE.wire)
        // ⚠️ Non-binary → other（不是 nonbinary / non_binary）
        assertEquals("other", SearchGender.NON_BINARY.wire)
    }

    /**
     * ⚠️ `All` 映射成 **null = 整键不发**，不是发 `"all"`。
     *
     * RN 的 `default: genderOption.gender = undefined` 加
     * `delete params.gender`（`useSearch.ts:118-136`）。发 `all` 后端可能
     * 当成一个真实取值，返回的结果集与现网不同。
     */
    @Test
    fun `性别 All 是整键不发`() {
        assertNull(SearchGender.ALL.wire)
    }

    /**
     * ⚠️ 第四项文案是 **`Non-binary`（带连字符）**。
     *
     * Home 侧的枚举是 `NonBinary`（无连字符）—— 两套写法不可复用。
     * 拿 Home 那个当 UI 文案会让 i18n 查不到词条（key 就是英文原文），
     * 显示成 key 本身。
     */
    @Test
    fun `性别文案对齐 SexList 含连字符`() {
        assertEquals(
            listOf("All", "Female", "Male", "Non-binary"),
            SearchGender.ALL_OPTIONS.map { it.label },
        )
    }

    // ── 排序 ────────────────────────────────────────

    @Test
    fun `排序 UI 文案与后端枚举不同`() {
        // SearchSortingValueMap（constants/common.ts:111-117）
        assertEquals("MostInteracted", SearchSorting.MOST_INTERACTED.wire)
        assertEquals("MostLiked", SearchSorting.MOST_LIKED.wire)
        assertEquals("MostFavorited", SearchSorting.MOST_FAVORITED.wire)
        // 这两个两端同名
        assertEquals("Recommended", SearchSorting.RECOMMENDED.wire)
        assertEquals("Latest", SearchSorting.LATEST.wire)
    }

    @Test
    fun `排序选项顺序对齐 SearchSortingList`() {
        assertEquals(
            listOf("Recommended", "Most Interacted", "Most Liked", "Most Favorited", "Latest"),
            SearchSorting.ALL_OPTIONS.map { it.label },
        )
    }

    @Test
    fun `认不出的排序值回落 Recommended`() {
        // 对齐 RN 的 `?? 'Recommended'`
        assertEquals(SearchSorting.RECOMMENDED, SearchSorting.fromWire("Nonsense"))
        assertEquals(SearchSorting.RECOMMENDED, SearchSorting.fromWire(null))
        assertEquals(SearchSorting.MOST_LIKED, SearchSorting.fromWire("MostLiked"))
    }

    // ── 分级 ────────────────────────────────────────

    @Test
    fun `分级三值 值即契约`() {
        assertEquals(
            listOf("All", "SFW", "NSFW"),
            SearchContentRating.ALL_OPTIONS.map { it.label },
        )
    }

    /**
     * ⚠️ 不显示分级的渠道**固定提交 `All`**，不是不发这个键。
     *
     * `FilterDrawer.tsx:75-79` 注释原文：「不展示 Content Rating 的渠道
     * （iOS/GooglePlay）固定提交 All，与线上一致」。
     */
    @Test
    fun `不可选分级时固定提交 All`() {
        val filter = SearchFilter(contentRating = SearchContentRating.NSFW)
        assertEquals("All", filter.wireContentRating(canPickContentRating = false))
        assertEquals("NSFW", filter.wireContentRating(canPickContentRating = true))
    }

    /**
     * 三重 gating 的后两条（第一条 `Platform.OS === 'android'` 壳天然满足）。
     *
     * ⚠️ 这里是「**非 GooglePlay**」—— 与 Settings 的 Limitless 开关用的
     * `isAndroidAPK`（只有 directApk）**不同**：RuStore 在这里**算**可选。
     */
    @Test
    fun `分级可选性 非 GooglePlay 且 nsfw 开`() {
        // GooglePlay：无论 nsfw 都不可选
        assertFalse(SearchFilter.canPickContentRating(nsfwEnabled = true, isGooglePlay = true))
        // 侧载 + nsfw 关：不可选
        assertFalse(SearchFilter.canPickContentRating(nsfwEnabled = false, isGooglePlay = false))
        // 侧载 + nsfw 开：可选（RuStore 也走这条）
        assertTrue(SearchFilter.canPickContentRating(nsfwEnabled = true, isGooglePlay = false))
    }

    // ── 激活态 ──────────────────────────────────────

    @Test
    fun `默认筛选不算激活`() {
        assertFalse(SearchFilter().hasActiveFilter)
    }

    @Test
    fun `三项任一非默认即激活`() {
        assertTrue(SearchFilter(gender = SearchGender.MALE).hasActiveFilter)
        assertTrue(SearchFilter(sorting = SearchSorting.LATEST).hasActiveFilter)
        assertTrue(SearchFilter(contentRating = SearchContentRating.SFW).hasActiveFilter)
    }

    /** ⚠️ 标签**不算**激活 —— 它是独立的二级栏，不影响筛选按钮外观。 */
    @Test
    fun `只选标签不算筛选激活`() {
        assertFalse(SearchFilter(tagIds = listOf("a", "b")).hasActiveFilter)
    }

    // ── 标签目录解析扩展（HomeTag 新增三字段）───────

    @Test
    fun `sort_order 缺失存 null 而不是 0`() {
        // hasKnownSortOrder 是集合级判定，必须能区分 null 与 0
        val tags = parseTags(
            tagJson("a", sortOrder = null),
            tagJson("b", sortOrder = 0),
        )
        assertNull(tags.first { it.id == "a" }.sortOrder)
        assertEquals(0L, tags.first { it.id == "b" }.sortOrder)
    }

    @Test
    fun `configIndex 是排序后的下标`() {
        // RN 遍历的目录数组本身已按 sort_order 排过，故对等的是排序后序
        val tags = parseTags(
            tagJson("late", sortOrder = 30),
            tagJson("early", sortOrder = 10),
        )
        assertEquals(listOf("early", "late"), tags.map { it.id })
        assertEquals(0, tags[0].configIndex)
        assertEquals(1, tags[1].configIndex)
    }

    @Test
    fun `is_event 命中特殊呈现`() {
        val tags = parseTags(tagJson("e", sortOrder = 1, extra = { it.put("is_event", true) }))
        assertTrue(tags.single().hasSpecialPresentation)
    }

    @Test
    fun `text_color 命中特殊呈现`() {
        val tags = parseTags(
            tagJson("t", sortOrder = 1, extra = { it.put("text_color", "#D7D234") }),
        )
        assertTrue(tags.single().hasSpecialPresentation)
    }

    @Test
    fun `watermark_url 命中特殊呈现`() {
        val tags = parseTags(
            tagJson("w", sortOrder = 1, extra = { it.put("watermark_url", "https://x/y.png") }),
        )
        assertTrue(tags.single().hasSpecialPresentation)
    }

    @Test
    fun `icon_type 与 icon_value 都有才命中`() {
        val onlyType = parseTags(
            tagJson("i", sortOrder = 1, extra = { it.put("icon_type", "lottie") }),
        )
        assertFalse("只有 type 没有 value 不算", onlyType.single().hasSpecialPresentation)

        val both = parseTags(
            tagJson("i", sortOrder = 1, extra = {
                it.put("icon_type", "lottie").put("icon_value", "x.json")
            }),
        )
        assertTrue(both.single().hasSpecialPresentation)
    }

    /**
     * ⚠️ legacy 名称表 —— 这些历史活动标签在接口里**不带**任何呈现字段，
     * 呈现是前端按名字硬映射的。漏了它们会让万圣节这类标签的排序位置
     * 与现网不同，而两端都不报错。
     */
    @Test
    fun `legacy 活动名命中特殊呈现`() {
        HomeTagParser.LEGACY_SPECIAL_TAGS.forEach { name ->
            val byId = parseTags(tagJson(name, sortOrder = 1))
            assertTrue("按 id 匹配失败：$name", byId.single().hasSpecialPresentation)
        }
    }

    @Test
    fun `legacy 名匹配忽略空格与大小写`() {
        // RN 的 normalizeTagMatcher 去空格 + 小写
        val tags = parseTags(
            tagJson("plain", sortOrder = 1, extra = { it.put("alias", "halloween2025") }),
        )
        assertTrue(tags.single().hasSpecialPresentation)
    }

    @Test
    fun `普通标签不命中特殊呈现`() {
        val tags = parseTags(tagJson("Romance", sortOrder = 1))
        assertFalse(tags.single().hasSpecialPresentation)
    }

    // ── 脚手架 ──────────────────────────────────────

    private fun parseTags(vararg items: JSONObject): List<HomeTag> {
        val array = JSONArray()
        items.forEach { array.put(it) }
        return HomeTagParser.parse(JSONObject().put("tags", array))
    }

    private fun tagJson(
        id: String,
        sortOrder: Int?,
        extra: (JSONObject) -> JSONObject = { it },
    ): JSONObject {
        val obj = JSONObject().put("tag_id", id).put("desc", id)
        if (sortOrder != null) obj.put("sort_order", sortOrder)
        return extra(obj)
    }
}
