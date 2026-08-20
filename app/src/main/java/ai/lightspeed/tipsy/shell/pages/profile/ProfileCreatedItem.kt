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
     * 审核阶段（嵌套层 `review_stage`，`un_reviewed/pass/failed`）。
     * ⚠️ P4 订正：RN 从**嵌套对象**取（`character.review_stage`），
     * 第一刀解析的顶层同名字段在实测响应里并不总在。
     */
    val reviewStage: String?,
    /** 未成年审核状态（`minor_review_status`：approved/rejected/pending/final_rejected）。 */
    val minorReviewStatus: String?,
    /** 置顶（`is_pinned`）→ 右上 Pin 角标。 */
    val isPinned: Boolean,
    /** 公开（`is_public`）。自己主页上非公开显示锁角标。 */
    val isPublic: Boolean,
    /** 18+（`nsfw`）→ 封面模糊 + 审核通过时的 18+ 标签。 */
    val nsfw: Boolean,
    /** `character_type`，2 = 多角色 story（显示 story 标签）。 */
    val characterType: Int?,
    /**
     * `final_hit` 位标记。已核实两处消费（`CharacterGridItem.tsx:98,576`）：
     * `< 2` → 整卡遮罩不可用；`& 8` → 封面模糊。缺失按 null（不遮不糊）。
     */
    val finalHit: Int?,
    /** 消息数（`stats.total_messages ?? total_messages`，嵌套 stats 优先）。 */
    val messageCount: Long,
    /** 曝光数（`stats.exposure_count`）。仅 character 卡 `is_public` 时显示。 */
    val exposureCount: Long?,
    /**
     * 删除动作用的业务 id（P5）。⚠️ **取嵌套层，不是顶层 `item_id`** ——
     * RN 传给卡片的是嵌套对象（`cellItem.character || cellItem`），删除调的是
     * `deleteCharacter(character.character_id)` / `deleteStory(character.story_id)`
     * （`CharacterGridItem.tsx:830` / `StoryItem.tsx:839`）。嵌套缺失回落顶层
     * `item_id`（两者实测同值，回落只为响应形状变化时不至于发空 id）。
     * game 无删除，恒 null。**带默认值**同 `CurrentUser.bio` 的理由
     * （既有构造点不受字段追加影响），[Companion.parse] 永远显式传。
     */
    val deleteId: String? = null,
    /**
     * 置顶动作用的 id（`/character/toggle_pin` 的 `item_id`）。
     * character = 嵌套 `character_id`；story = 嵌套 `item_id || story_id`
     * （`StoryItem.tsx:463` 的原样兜底链）；game = `game_id`
     * （`GameGridItem.tsx:265`）。
     */
    val pinId: String? = null,
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

    /**
     * 左上审核角标（`CharacterGridItem.tsx:355-374` 的判定，三种卡同一函数形状）。
     *
     * rejected 优先于 pending；都不中返回 null（approved **不渲染角标**，
     * RN 的 `getReviewStatusBadge` 对通过态返回 null）。
     */
    val reviewBadge: ProfileReviewBadge?
        get() = when {
            minorReviewStatus == MINOR_REJECTED ||
                minorReviewStatus == MINOR_FINAL_REJECTED ||
                reviewStage == REVIEW_FAILED -> ProfileReviewBadge.REJECTED

            minorReviewStatus == MINOR_PENDING ||
                reviewStage == REVIEW_UNREVIEWED -> ProfileReviewBadge.PENDING

            else -> null
        }

    /** 未成年审核拦截（`CharacterGridItem.tsx:126-129`）—— 参与模糊判定。 */
    private val isMinorReviewBlocked: Boolean
        get() = minorReviewStatus == MINOR_REJECTED ||
            minorReviewStatus == MINOR_PENDING ||
            minorReviewStatus == MINOR_FINAL_REJECTED

    /**
     * 封面模糊，三条任一（`CharacterGridItem.tsx:571-577` 注释原文照录）：
     * ① `!nsfw偏好 && item.nsfw` —— 壳内 nsfw 偏好恒 false（后端权威单向镜像），
     *    故**所有 18+ 封面一律模糊**；② `final_hit & 8`；③ 未成年审核拦截。
     */
    val shouldBlurCover: Boolean
        get() = nsfw || ((finalHit ?: 0) and FINAL_HIT_BLUR_BIT) != 0 || isMinorReviewBlocked

    /**
     * 整卡不可用遮罩（`CharacterGridItem.tsx:98`：`final_hit != null && < 2`）。
     * 遮罩优先于一切内容与角标（RN 的 `isMasked ? maskCover : 正常内容`）。
     */
    val isMaskedUnavailable: Boolean
        get() = finalHit != null && finalHit < FINAL_HIT_VISIBLE_MIN

    /** story 标签（`character_type === 2`，`CharacterGridItem.tsx:527`）。 */
    val showStoryTag: Boolean get() = characterType == CHARACTER_TYPE_STORY

    /**
     * 编辑入口的透传载荷（P5，仅 character；story 编辑按方案 §8.1 不做，
     * game 无编辑）。
     *
     * ## ⚠️ 取**嵌套对象**，与 [rawJson]（顶层元素）不同层
     *
     * RN 的 `CharacterGridItem.handleEdit` 调 `initCharStateUpdate(character)`，
     * 而 `character` prop 是 `cellItem.character || cellItem`（iOS 契约文档
     * `create-rn-surface-contract.md` §3 同此对齐）。传顶层元素会让 create
     * store 预填出一层错误包装 —— 保存时字段错位，正是「by-id 重拉导致
     * 字段重置」同级别的数据损坏。
     *
     * 返回原始 JSON **原文**（不从本模型反序列化），理由见 [Companion.parse]。
     */
    fun editPayloadJson(): String? {
        if (type != ProfileItemType.CHARACTER) return null
        val top = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        return (top.optJSONObject(FIELD_NESTED_CHARACTER) ?: top).toString()
    }

    /**
     * 18+ 标签：nsfw 且**审核已通过**（`CharacterGridItem.tsx:528-531` ——
     * 待审/驳回时左上位置让给审核角标，18+ 不再重复出现）。
     */
    val showNsfwTag: Boolean
        get() = nsfw && reviewStage != REVIEW_FAILED && reviewStage != REVIEW_UNREVIEWED

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
            val stats = nested?.optJSONObject(FIELD_STATS)
            return ProfileCreatedItem(
                type = type,
                itemId = ScalarCoercion.optString(json, FIELD_ITEM_ID)?.takeIf { it.isNotBlank() },
                gameId = ScalarCoercion.optString(json, FIELD_GAME_ID)?.takeIf { it.isNotBlank() },
                name = parseName(json, nested, type),
                coverUrl = parseCoverUrl(json, nested),
                reviewStage = nestedThenTop(json, nested, FIELD_REVIEW_STAGE),
                minorReviewStatus = nestedThenTop(json, nested, FIELD_MINOR_REVIEW_STATUS),
                isPinned = nested?.optBoolean(FIELD_IS_PINNED, false) ?: false,
                // 缺失按 true：把公开内容错标成私密（多画一把锁）比反过来
                //（私密内容不标锁）更显眼、更容易被发现修掉
                isPublic = nested?.optBoolean(FIELD_IS_PUBLIC, true) ?: true,
                nsfw = nested?.optBoolean(FIELD_NSFW, false) ?: false,
                characterType = nested?.let { ScalarCoercion.optInt(it, FIELD_CHARACTER_TYPE) },
                finalHit = nested?.let { ScalarCoercion.optInt(it, FIELD_FINAL_HIT) },
                messageCount = stats?.let { ScalarCoercion.optLong(it, FIELD_TOTAL_MESSAGES) }
                    ?: nested?.let { ScalarCoercion.optLong(it, FIELD_TOTAL_MESSAGES) }
                    ?: 0L,
                exposureCount = stats?.let { ScalarCoercion.optLong(it, FIELD_EXPOSURE_COUNT) },
                deleteId = parseDeleteId(json, nested, type),
                pinId = parsePinId(json, nested, type),
                rawJson = json.toString(),
            )
        }

        /** 删除 id：见属性注释。game 无删除动作（RN 菜单只有置顶）。 */
        private fun parseDeleteId(
            json: JSONObject,
            nested: JSONObject?,
            type: ProfileItemType,
        ): String? {
            val field = when (type) {
                ProfileItemType.CHARACTER -> FIELD_CHARACTER_ID
                ProfileItemType.STORY -> FIELD_STORY_ID
                ProfileItemType.GAME -> return null
            }
            return nested?.let { ScalarCoercion.optString(it, field)?.takeIf { s -> s.isNotBlank() } }
                ?: ScalarCoercion.optString(json, FIELD_ITEM_ID)?.takeIf { it.isNotBlank() }
        }

        /** 置顶 id：三类各有取法，见属性注释。 */
        private fun parsePinId(
            json: JSONObject,
            nested: JSONObject?,
            type: ProfileItemType,
        ): String? = when (type) {
            ProfileItemType.CHARACTER ->
                nested?.let { ScalarCoercion.optString(it, FIELD_CHARACTER_ID) }
                    ?.takeIf { it.isNotBlank() }
                    ?: ScalarCoercion.optString(json, FIELD_ITEM_ID)?.takeIf { it.isNotBlank() }

            // StoryItem.tsx:463：`character.item_id || character.story_id`。
            // 嵌套对象通常无 item_id，实际命中 story_id；顶层 item_id 作最后兜底
            ProfileItemType.STORY ->
                nested?.let { ScalarCoercion.optString(it, FIELD_ITEM_ID) }
                    ?.takeIf { it.isNotBlank() }
                    ?: nested?.let { ScalarCoercion.optString(it, FIELD_STORY_ID) }
                        ?.takeIf { it.isNotBlank() }
                    ?: ScalarCoercion.optString(json, FIELD_ITEM_ID)?.takeIf { it.isNotBlank() }

            ProfileItemType.GAME ->
                ScalarCoercion.optString(json, FIELD_GAME_ID)?.takeIf { it.isNotBlank() }
                    ?: nested?.let { ScalarCoercion.optString(it, FIELD_GAME_ID) }
                        ?.takeIf { it.isNotBlank() }
        }

        /** 嵌套层优先、顶层兜底（响应形状变化时不至于全空）。 */
        private fun nestedThenTop(
            json: JSONObject,
            nested: JSONObject?,
            field: String,
        ): String? =
            nested?.let { ScalarCoercion.optString(it, field)?.takeIf { s -> s.isNotBlank() } }
                ?: ScalarCoercion.optString(json, field)?.takeIf { it.isNotBlank() }

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
        private const val FIELD_CHARACTER_ID = "character_id"
        private const val FIELD_STORY_ID = "story_id"
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
        private const val FIELD_MINOR_REVIEW_STATUS = "minor_review_status"
        private const val FIELD_IS_PINNED = "is_pinned"
        private const val FIELD_IS_PUBLIC = "is_public"
        private const val FIELD_NSFW = "nsfw"
        private const val FIELD_CHARACTER_TYPE = "character_type"
        private const val FIELD_FINAL_HIT = "final_hit"
        private const val FIELD_STATS = "stats"
        private const val FIELD_TOTAL_MESSAGES = "total_messages"
        private const val FIELD_EXPOSURE_COUNT = "exposure_count"

        private const val REVIEW_FAILED = "failed"
        private const val REVIEW_UNREVIEWED = "un_reviewed"
        private const val MINOR_REJECTED = "rejected"
        private const val MINOR_PENDING = "pending"
        private const val MINOR_FINAL_REJECTED = "final_rejected"
        private const val CHARACTER_TYPE_STORY = 2

        /** `final_hit` 第 4 位（&8）= 需媒体特殊处理 → 模糊。 */
        private const val FINAL_HIT_BLUR_BIT = 8

        /** `final_hit < 2` = 整卡不可用。 */
        private const val FINAL_HIT_VISIBLE_MIN = 2
    }
}

/** 左上审核角标的两种可见态（approved 不渲染，`ReviewStatusBadge.tsx`）。 */
enum class ProfileReviewBadge { PENDING, REJECTED }

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
