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
        // sort_order 只用于排序，不进模型 —— 所以先配对再排，最后丢掉
        val withOrder = ArrayList<Pair<HomeTag, Long>>(list.length())
        for (i in 0 until list.length()) {
            val raw = list.optJSONObject(i) ?: continue
            // ⚠️ 「不等于 false」而非「等于 true」—— 见类注释第 2 条
            if (ScalarCoercion.optBoolean(raw, "show_in_filter") == false) continue
            val id = ScalarCoercion.optString(raw, "tag_id").orEmpty()
            val label = ScalarCoercion.optString(raw, "desc")
                ?.takeIf { it.isNotBlank() }
                ?: ScalarCoercion.optString(raw, "alias").orEmpty()
            if (id.isBlank() || label.isBlank()) continue
            val tag = HomeTag(
                id = id,
                label = label,
                isNew = ScalarCoercion.optBoolean(raw, "is_new") ?: false,
                isEvent = ScalarCoercion.optBoolean(raw, "is_event") ?: false,
            )
            withOrder.add(tag to (ScalarCoercion.optLong(raw, "sort_order") ?: 0L))
        }
        // sortedBy 是**稳定排序**：sort_order 相同的项保持后端返回序
        return withOrder.sortedBy { it.second }.map { it.first }
    }
}
