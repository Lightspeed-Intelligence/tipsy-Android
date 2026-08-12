package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.network.ScalarCoercion
import org.json.JSONObject

/**
 * 创作列表的一条（`/user/created/list` 的 `list` 元素）。
 *
 * ## 三种 item 共用一个模型
 *
 * `item_type` 取 `character` / `story` / `game`（`useCreatedList.ts:81` 的判别）。
 * 方案 §8.1 要求「5 个 Tab 共用一个分页壳，差异只在 endpoint 与 item 类型」——
 * 这里同理：**不为三种类型建三个 data class**，UI 按 [type] 分流渲染。
 *
 * 后续包接入记忆/收藏/点赞/角色卡时会再有三种类型（共 6 种，方案 §8.1 记的
 * 「item 类型有 6 种，这是 12.6k 的主要来源」），届时按同一模式扩 [type]。
 */
data class ProfileCreatedItem(
    val type: ProfileItemType,
    /** 去重与点击都用它，见 [dedupeKey]。 */
    val itemId: String?,
    /** game 独有；非 game 为 null。 */
    val gameId: String?,
    /**
     * 显示名 —— ⚠️ **三种类型的字段名不同**（实测，别统一成一个）：
     * - character → `nickname`（`CharacterGridItem.tsx:608`，**不是 `name`**）
     * - story → `title`（`StoryItem.tsx:645`）
     * - game → `title`（`GameGridItem.tsx`）
     */
    val name: String?,
    /** 封面 —— 三种类型都是 `image_url`（game 还有 `game.assets` 回落，见 [parse]）。 */
    val coverUrl: String?,
    /**
     * 审核阶段（`review_stage`）。UI 靠它显示待审/驳回角标
     * （RN 侧 `ReviewStatusBadge.tsx` 78 行，本刀先只解析不渲染）。
     */
    val reviewStage: String?,
    /** 该条原始 JSON。**编辑入口必须原封透传它**，见下。 */
    val rawJson: String,
) : ProfileListEntry {

    /**
     * 去重键 —— ⚠️ **按类型分流，不能统一用 `item_id`**。
     *
     * RN 侧 `useCreatedList.ts:79-85`：game 用 `game_${game_id}`，其余用 `item_id`。
     * 统一用 `item_id` 会让 game 与 character 在 id 相同时互相顶掉
     * （两套 id 空间独立，撞号完全可能），表现是"某个游戏卡片莫名消失"。
     */
    override val dedupeKey: String
        get() = when (type) {
            ProfileItemType.GAME -> "game_${gameId.orEmpty()}"
            else -> itemId.orEmpty()
        }

    companion object {

        /**
         * 解析一条；`item_type` 不认识时返回 null（调用方过滤掉）。
         *
         * ⚠️ **[rawJson] 必须是原始 JSON 原文**，不是从本模型反序列化回去的。
         * 方案 §8.1 Profile 行记的坑：「创作列表的原始 JSON 必须原封透传给
         * `CreateSurface`。**by-id 重拉会导致保存时字段重置（= 数据损坏）**」。
         * 本模型只取 UI 用得到的几个字段，其余几十个字段（`custom_prompt` /
         * `world_books` / `voice_param` …）只存在于 [rawJson] 里 ——
         * 丢了它，编辑入口就只能 by-id 重拉，正好踩进那个坑。
         */
        fun parse(json: JSONObject): ProfileCreatedItem? {
            val type = ProfileItemType.fromWire(
                ScalarCoercion.optString(json, FIELD_ITEM_TYPE),
            ) ?: return null
            // ⚠️ 展示字段在**嵌套对象**里，不在顶层 —— 见 [nestedPayload]
            val nested = nestedPayload(json)
            return ProfileCreatedItem(
                type = type,
                itemId = ScalarCoercion.optString(json, FIELD_ITEM_ID)?.takeIf { it.isNotBlank() },
                gameId = ScalarCoercion.optString(json, FIELD_GAME_ID)?.takeIf { it.isNotBlank() },
                name = parseName(json, nested, type),
                coverUrl = parseCoverUrl(json, nested),
                reviewStage = ScalarCoercion.optString(json, FIELD_REVIEW_STAGE)
                    ?.takeIf { it.isNotBlank() },
                rawJson = json.toString(),
            )
        }

        /**
         * 取嵌套的业务对象（`character` / `story` / `game`）。
         *
         * ## ⚠️ 展示字段在这一层，顶层的同名字段**不能用**
         *
         * 实测响应（`/user/created/list`，character item）：
         * ```
         * { "item_type":"character", "item_id":"...",
         *   "title":"同学",                                    // 顶层有 title
         *   "image_url":"create_media/image/xxx.jpg",          // ⚠️ 相对路径
         *   "character":{ "nickname":"同学",                    // nickname 只在这层
         *                 "image_url":"https://img2.tipsy.chat/create_media/..." } }
         * ```
         *
         * 两处陷阱：
         * 1. **顶层没有 `nickname`** —— 只有嵌套层有。取顶层得到 null，卡片名字空白
         * 2. **顶层 `image_url` 是相对路径**，嵌套层才是完整 URL。
         *    拿相对路径喂 Coil 会静默加载失败 —— 卡片只剩占位色，不报错
         *
         * RN 侧把整个嵌套对象作为 prop 传下去（`CharacterGrid.tsx:574`
         * `character={cellItem.story}`），组件内一律访问 `character.xxx`
         * （`CharacterGridItem.tsx:604,608`），所以天然取的是嵌套层。
         *
         * ⚠️ 字段名与 item 类型**不完全对应**：character item 的嵌套键是
         * `character`，但 RN 在 `CharacterGrid` 里是从 `cellItem.story` 取的
         * （那个变量名是 RN 侧的历史遗留，见 `CharacterGrid.tsx:570`
         * `const story = cellItem as StoryGetRes`）。所以这里三个键都试一遍，
         * 而不是按类型硬映射一个键名。
         */
        private fun nestedPayload(json: JSONObject): JSONObject? =
            json.optJSONObject(FIELD_NESTED_CHARACTER)
                ?: json.optJSONObject(FIELD_NESTED_STORY)
                ?: json.optJSONObject(FIELD_NESTED_GAME)

        /**
         * 取显示名：character 用 `nickname`，story / game 用 `title`。
         *
         * ⚠️ 实测差异（`CharacterGridItem.tsx:608` vs `StoryItem.tsx:645`）。
         * character 的字段是 `nickname` 而**不是** `name` —— 写成 `name` 时
         * 接口里恰好也有个 `name` 字段（角色内部名），能取到值但**显示的不是
         * 用户看到的那个名字**，本地对着自己的角色不一定看得出来。
         *
         * 先嵌套层后顶层：character 的 `nickname` 只有嵌套层有（见 [nestedPayload]），
         * 而顶层 `title` 是所有类型都有的兜底。
         */
        private fun parseName(
            json: JSONObject,
            nested: JSONObject?,
            type: ProfileItemType,
        ): String? {
            val field = when (type) {
                ProfileItemType.CHARACTER -> FIELD_NICKNAME
                ProfileItemType.STORY, ProfileItemType.GAME -> FIELD_TITLE
            }
            val fromNested = nested?.let {
                ScalarCoercion.optString(it, field)?.takeIf { s -> s.isNotBlank() }
            }
            // 顶层 title 兜底：嵌套对象缺失时（响应形状变化）仍能显示个名字，
            // 而不是白卡片
            return fromNested
                ?: ScalarCoercion.optString(json, FIELD_TITLE)?.takeIf { it.isNotBlank() }
        }

        /**
         * 取封面。
         *
         * **只认完整 URL** —— 顶层 `image_url` 是相对路径（见 [nestedPayload]），
         * 直接喂给 Coil 会静默失败。嵌套层已经是带域名的完整地址。
         *
         * 兜底顺序：嵌套 `image_url` → 嵌套 `face_url`（头像图，比空白好）。
         * 都拿不到就返回 null，UI 显示占位色。
         *
         * ⚠️ 刻意**不**用顶层相对路径手工拼域名：域名在响应里是
         * `img2.tipsy.chat`，但那是后端可换的 CDN 主机，硬编码会在换主机时全站图裂。
         */
        private fun parseCoverUrl(json: JSONObject, nested: JSONObject?): String? {
            val candidates = listOfNotNull(
                nested?.let { ScalarCoercion.optString(it, FIELD_IMAGE_URL) },
                nested?.let { ScalarCoercion.optString(it, FIELD_FACE_URL) },
                // 顶层只在它恰好是完整 URL 时才用（防响应形状变化）
                ScalarCoercion.optString(json, FIELD_IMAGE_URL),
            )
            return candidates.firstOrNull {
                it.isNotBlank() && (it.startsWith("http://") || it.startsWith("https://"))
            }
        }

        private const val FIELD_ITEM_TYPE = "item_type"
        private const val FIELD_ITEM_ID = "item_id"
        private const val FIELD_GAME_ID = "game_id"
        private const val FIELD_NICKNAME = "nickname"
        private const val FIELD_TITLE = "title"
        private const val FIELD_FACE_URL = "face_url"

        // 嵌套业务对象的键。三个都试（见 nestedPayload 注释：
        // 键名与 item_type 不严格对应）
        private const val FIELD_NESTED_CHARACTER = "character"
        private const val FIELD_NESTED_STORY = "story"
        private const val FIELD_NESTED_GAME = "game"
        private const val FIELD_IMAGE_URL = "image_url"
        private const val FIELD_REVIEW_STAGE = "review_stage"
    }
}

/** 创作列表的 item 类型（本刀三种；后续扩到 6 种，见 [ProfileCreatedItem] 注释）。 */
enum class ProfileItemType(val wire: String) {
    CHARACTER("character"),
    STORY("story"),
    GAME("game"),
    ;

    companion object {
        /** 按接口值反查；不认识返回 null —— 新类型上线时宁可不显示，也不要崩。 */
        fun fromWire(value: String?): ProfileItemType? =
            entries.firstOrNull { it.wire == value }
    }
}
