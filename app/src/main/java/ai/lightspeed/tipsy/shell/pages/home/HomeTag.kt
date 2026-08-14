package ai.lightspeed.tipsy.shell.pages.home

import ai.lightspeed.tipsy.shell.network.ScalarCoercion
import org.json.JSONObject

/**
 * 筛选抽屉里的一个标签（`POST /character/tags` 的一项，`types/tag.ts:26-44`）。
 *
 * ## 只留筛选真正用得到的字段
 *
 * RN 的 `TagType` 有 17 个字段，但抽屉只解构 `{ id, tag, isNew, isEvent }`
 * （`HomeFilterDrawer.tsx:202`）。图标 / 水印 / 文字色那些是角色创建页与
 * 卡片角标用的，本包不搬 —— 搬了就得跟着搬渲染逻辑，而那些位置还不存在。
 *
 * ⚠️ **`tag` 取的是 `desc`，回落 `alias`**（`config_persist.ts:301`
 * `tag: tag.desc || tag.alias`）。反过来取会让部分标签显示成内部别名。
 */
data class HomeTag(
    /** `tag_id` —— 发给列表接口的 `tag_ids` 用这个值。 */
    val id: String,
    /** 展示文案：`desc` 优先，空则 `alias`。 */
    val label: String,
    /** `is_new`：显示 NEW 角标。 */
    val isNew: Boolean = false,
    /** `is_event`：显示活动角标。 */
    val isEvent: Boolean = false,

    // ── 以下三个字段由 Search P2 的标签栏排序需要（§2.34）────────
    //
    // Home 的抽屉**不用**它们（那边只按 sort_order 排一次就够）。
    // 加在这里而不是给 Search 建第二个标签模型：两处的标签目录来自同一个
    // `/character/tags`，两套模型必然漂移。

    /**
     * `sort_order` **原始值**（Home 侧排完即弃，但 Search 需要判「有没有」）。
     *
     * ⚠️ `deriveResultTagOrder` 的第三层优先级是「**目录里存在任一带
     * `sort_order` 的标签**时按它排，否则按配置顺序排」
     * （`searchTagOrder.ts:39-41` `hasKnownSortOrder`）—— 那是集合级判定，
     * 所以必须保留 null 与 0 的区别：字段缺失是 null，不是 0。
     */
    val sortOrder: Long? = null,

    /**
     * 该标签在**目录里的原始下标**（`configurationIndex`，
     * `searchTagOrder.ts:33-36`）。
     *
     * ⚠️ 是**过滤 `show_in_filter` 之后、排序之前**的下标 —— RN 那边
     * `configuredTags.forEach((tag, index))` 遍历的是已 hydrate 的目录数组，
     * 而那个数组本身已按 `sort_order` 排过（`config_persist.ts:297`）。
     * 所以壳侧取「排序后的下标」才对等，见 [HomeTagParser.parse]。
     */
    val configIndex: Int = 0,

    /**
     * 「特殊呈现」标签 —— `deriveResultTagOrder` 的**第二层优先级**
     * （`hasSpecialPresentation`，`searchTagOrder.ts:15-23`）。
     *
     * 判据是 `isEvent || iconRenderKind !== 'none' ||
     * watermarkRenderKind !== 'none' || textColor` 任一为真，落到 API 字段是
     * `is_event` / `icon_type`+`icon_value` / `watermark_url` / `text_color`，
     * **外加一张 6 条的 legacy 名称回落表**（见 [HomeTagParser.LEGACY_SPECIAL_TAGS]）。
     *
     * ⚠️ 壳**不迁** `resolveTagDisplay` 本体（441 行的图标/水印呈现配置）——
     * 标签行还没有 lottie 与水印渲染。这里只要那个布尔判定。
     */
    val hasSpecialPresentation: Boolean = false,
)

/**
 * 标签目录解析。
 *
 * ## 三件容易漏的事
 *
 * 1. **按 `sort_order` 升序排**（`config_persist.ts:297-299`）。后端返回顺序
 *    不保证，不排会让标签行顺序在每次冷启动后变化。
 * 2. **`show_in_filter !== false` 才进筛选**（`config_persist.ts:313`）——
 *    注意是「不等于 false」而非「等于 true」：字段缺失时**要显示**。
 *    写成 `== true` 会让所有没带该字段的标签消失。
 * 3. **`id` / `label` 任一为空的项直接丢弃**（对齐 `isTagType` 守卫，
 *    `config_persist.ts:32-35` 要求 `id` 与 `tag` 都是 string）。
 */
internal object HomeTagParser {

    fun parse(data: JSONObject): List<HomeTag> {
        val list = data.optJSONArray("tags") ?: return emptyList()
        val withOrder = ArrayList<Pair<HomeTag, Long?>>(list.length())
        for (i in 0 until list.length()) {
            val raw = list.optJSONObject(i) ?: continue
            // ⚠️ 「不等于 false」而非「等于 true」—— 见类注释第 2 条
            if (ScalarCoercion.optBoolean(raw, "show_in_filter") == false) continue
            val id = ScalarCoercion.optString(raw, "tag_id").orEmpty()
            val label = ScalarCoercion.optString(raw, "desc")
                ?.takeIf { it.isNotBlank() }
                ?: ScalarCoercion.optString(raw, "alias").orEmpty()
            if (id.isBlank() || label.isBlank()) continue
            val sortOrder = ScalarCoercion.optLong(raw, "sort_order")
            val tag = HomeTag(
                id = id,
                label = label,
                isNew = ScalarCoercion.optBoolean(raw, "is_new") ?: false,
                isEvent = ScalarCoercion.optBoolean(raw, "is_event") ?: false,
                sortOrder = sortOrder,
                hasSpecialPresentation = isSpecialPresentation(raw, id),
            )
            withOrder.add(tag to sortOrder)
        }
        // sortedBy 是**稳定排序**：sort_order 相同的项保持后端返回序。
        // ⚠️ 缺失的 sort_order 参与排序时按 0 处理（保持既有行为），
        // 但**模型里存 null** —— Search 的 hasKnownSortOrder 要区分两者
        return withOrder
            .sortedBy { it.second ?: 0L }
            // configIndex = 排序**后**的下标：RN 遍历的目录数组本身已按
            // sort_order 排过（config_persist.ts:297），所以对等的是排序后序
            .mapIndexed { index, (tag, _) -> tag.copy(configIndex = index) }
    }

    /**
     * 「特殊呈现」判定 —— `hasSpecialPresentation` + `resolveTagDisplay`
     * 的等效实现（Search P2 的标签栏排序用，§2.34）。
     *
     * 四个 API 字段任一命中即为真，另加 legacy 名称表兜底。
     * **不迁** `resolveTagDisplay` 本体（441 行的图标/水印呈现配置）——
     * 只要这个布尔值。
     */
    private fun isSpecialPresentation(raw: JSONObject, id: String): Boolean {
        if (ScalarCoercion.optBoolean(raw, "is_event") == true) return true
        // iconRenderKind !== 'none' 的 API 来源（`tagDisplay.ts:283-290`）
        val iconType = ScalarCoercion.optString(raw, "icon_type")?.takeIf { it.isNotBlank() }
        val iconValue = ScalarCoercion.optString(raw, "icon_value")?.trim()?.takeIf { it.isNotEmpty() }
        if (iconType != null && iconValue != null) return true
        // watermarkRenderKind !== 'none'（`:324-327`）
        if (!ScalarCoercion.optString(raw, "watermark_url")?.trim().isNullOrEmpty()) return true
        // textColor 非空（`:359`）
        if (!ScalarCoercion.optString(raw, "text_color")?.trim().isNullOrEmpty()) return true
        // legacy 回落表：按 id 或 alias 匹配（`tagDisplay.ts:152-165`）
        val alias = ScalarCoercion.optString(raw, "alias").orEmpty()
        return matchesLegacySpecial(id) || matchesLegacySpecial(alias)
    }

    private fun matchesLegacySpecial(key: String): Boolean {
        if (key.isBlank()) return false
        // RN 的 normalizeTagMatcher 去空格 + 小写后比对
        val normalized = key.replace(" ", "").lowercase()
        return LEGACY_SPECIAL_TAGS.any { it.replace(" ", "").lowercase() == normalized }
    }

    /**
     * legacy 活动标签名（`tagDisplay.ts:73` `legacyTagFallbackMap` 的 6 条）。
     *
     * 这些历史活动标签在接口里**不带** `icon_type` / `text_color` 等字段，
     * 呈现是前端按名字硬映射的。漏了它们会让万圣节这类标签在搜索结果
     * 标签栏里的**排序位置与现网不同**（本该优先，实际排到后面），
     * 而两端都不报错。
     */
    val LEGACY_SPECIAL_TAGS = listOf(
        "Halloween 2025",
        "Christmas 2025",
        "Valentines2026",
        "Under The Mask",
        "Brewing & Coding",
        "NewStart",
    )
}
