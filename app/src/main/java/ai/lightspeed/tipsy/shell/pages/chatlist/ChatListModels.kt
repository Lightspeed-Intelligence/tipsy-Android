package ai.lightspeed.tipsy.shell.pages.chatlist

import ai.lightspeed.tipsy.shell.network.ScalarCoercion
import org.json.JSONObject

/**
 * 会话列表的一条（RN `types/chat.ts` 的 `ChattedCharacterListRes`，W3 ChatList P1）。
 *
 * ## 四种 item_type 的 id 归属
 *
 * `character`/`story`/`plot` 用 [itemId]；`game` 用 [gameId]（`item_id` 可能缺失）。
 * pin/unpin/delete 的请求体按此分流（`apis/chat.ts:571-628`），
 * 搞混的症状是「删 A 游戏删掉了 B 角色」级别的数据事故。
 *
 * ## 小手机（mini_phone）是**对话级**条目
 *
 * 同一角色可能有多个小手机入口，靠 [conversationId] 区分（`types/chat.ts:305` 注释）。
 * 所以匹配一条 mini_phone 必须比 `item_id + chat_mode + conversation_id` 三元组 ——
 * 只比 item_id 会把同角色其它小手机入口一起删掉（RN `index.tsx:158-163` 特意写了这条）。
 */
data class ChatThread(
    /** `character` / `story` / `game` / `plot`。未知值在解析层丢弃。 */
    val itemType: String,
    val itemId: String,
    val itemName: String,
    /** 仅 game 类型有值。 */
    val gameId: String?,
    val faceUrl: String,
    /** 删除确认弹窗的头像兜底（`face_url || image_url`，`index.tsx:348`）。 */
    val imageUrl: String,
    val introduction: String,
    val greeting: String?,
    val lastMessageContent: String?,
    /** Unix **秒**（展示时 ×1000，`ChatListItem.tsx:473`）。 */
    val latestTimeSeconds: Long,
    val isPinned: Boolean,
    val isPushMessage: Boolean,
    val isPushMessageViewed: Boolean,
    val currentStreakDays: Int,
    /** `"mini_phone"` 或 null（普通会话）。 */
    val chatMode: String?,
    val conversationId: String?,
    /** 小手机绑定的归属普通对话，点击进入时用（P9 接 ChatDetail 时透传）。 */
    val parentConversationId: String?,
    /** 2 = story 型角色，头像右下角标 story 标（`ChatListItem.tsx:400`）。 */
    val characterType: Int?,
    /** html 型分流素材（P9 经桥透传给 ChatDetailSurface）。 */
    val contentType: Int?,
    /** simulator 埋点的 creator（`creator_id ?? user_id ?? creator.user_id`）。 */
    val creatorId: String?,
    /** game 点击导航素材（P9）。 */
    val versionChange: Boolean,
) {

    /**
     * 列表 stable key（LazyColumn 的 `key` + 分页去重键）。
     *
     * ⚠️ **不掺 index / latest_time**。RN 的 `getChattedListItemKey` 掺了这两个，
     * 那是 FlatList 对 key 冲突宽容的历史妥协；LazyColumn 遇重复 key 直接崩，
     * 且掺 `latest_time` 会让「收到新消息」变成「删一行加一行」（整行重配、
     * 滑动位置跳）。业务四元组已足够唯一，翻页窗口内的重复由解析后
     * ViewModel 的去重防御兜住（方案 §8.4 列表纪律）。
     */
    val stableKey: String
        get() = listOf(
            itemType,
            if (itemType == TYPE_GAME) gameId.orEmpty() else itemId,
            chatMode.orEmpty(),
            conversationId.orEmpty(),
        ).joinToString(":")

    val isMiniPhone: Boolean get() = chatMode == CHAT_MODE_MINI_PHONE

    /** 头像右下 story 角标（`item_type === 'story' || character_type === 2`）。 */
    val showStoryTag: Boolean
        get() = itemType == TYPE_STORY || characterType == CHARACTER_TYPE_STORY

    /**
     * 进聊天时透传给 `ChatDetailSurface` 的 `isStory`（P9）。
     *
     * ⚠️ **与 [showStoryTag] 不是同一个判定**，别复用那个 —— 它多带一条
     * `characterType == 2`（角标把多角色也画成 story 样式），而
     * `ChatListItem.tsx:286` 写入 preload 的 `isStory` **只看
     * `item_type === 'story'`**。
     *
     * 差别在多角色角色上会体现：`isStory` 为真时
     * `resolveChatEntryScreen` 恒落普通聊天页（优先级最高，
     * `chat_mode_lru.ts:74`），而 `characterType == 2` 本该进 **MultiCinema**。
     * 用角标那个判定的表现是「多角色角色从聊天列表进去看不到影院」，
     * 且不报错。
     */
    val isStoryEntry: Boolean get() = itemType == TYPE_STORY

    /**
     * 与另一条是否指同一业务实体（pin/delete 的匹配判定，
     * `index.tsx:156-163` / `ChatListItem.tsx:178-183` 的比对条件）。
     */
    fun matches(other: ChatThread): Boolean = if (itemType == TYPE_GAME) {
        other.itemType == TYPE_GAME && other.gameId == gameId
    } else {
        other.itemId == itemId &&
            other.chatMode == chatMode &&
            other.conversationId == conversationId
    }

    companion object {
        const val TYPE_CHARACTER = "character"
        const val TYPE_STORY = "story"
        const val TYPE_GAME = "game"
        const val TYPE_PLOT = "plot"
        const val CHAT_MODE_MINI_PHONE = "mini_phone"
        const val CHARACTER_TYPE_STORY = 2

        private val KNOWN_TYPES = setOf(TYPE_CHARACTER, TYPE_STORY, TYPE_GAME, TYPE_PLOT)

        /**
         * 解析一条；`item_type` 不认识或缺业务 id 时返回 null（调用方过滤）。
         *
         * 全部数字字段走 [ScalarCoercion] —— dev/prod 会把 TS 标 `string` 的字段
         * 返成 number（反之亦然），列表路径静默吞错的教训见方案 §4.5。
         */
        fun parse(json: JSONObject): ChatThread? {
            val itemType = ScalarCoercion.optString(json, "item_type") ?: return null
            if (itemType !in KNOWN_TYPES) return null
            val itemId = ScalarCoercion.optString(json, "item_id").orEmpty()
            val gameId = ScalarCoercion.optString(json, "game_id")
            // game 缺 game_id、其余缺 item_id 都是脏数据 —— RN 的删除守卫
            // （`index.tsx:149-150`）也把这两种当不可操作
            if (itemType == TYPE_GAME) {
                if (gameId.isNullOrEmpty()) return null
            } else if (itemId.isEmpty()) {
                return null
            }
            return ChatThread(
                itemType = itemType,
                itemId = itemId,
                itemName = ScalarCoercion.optString(json, "item_name").orEmpty(),
                gameId = gameId,
                faceUrl = ScalarCoercion.optString(json, "face_url").orEmpty(),
                imageUrl = ScalarCoercion.optString(json, "image_url").orEmpty(),
                introduction = ScalarCoercion.optString(json, "introduction").orEmpty(),
                greeting = ScalarCoercion.optString(json, "greeting"),
                lastMessageContent = ScalarCoercion.optString(json, "last_message_content"),
                latestTimeSeconds = ScalarCoercion.optLong(json, "latest_time") ?: 0L,
                isPinned = ScalarCoercion.optBoolean(json, "is_pinned") ?: false,
                isPushMessage = ScalarCoercion.optBoolean(json, "is_push_message") ?: false,
                isPushMessageViewed = ScalarCoercion.optBoolean(json, "is_push_message_viewed")
                    ?: false,
                currentStreakDays = ScalarCoercion.optInt(json, "current_streak_days") ?: 0,
                chatMode = ScalarCoercion.optString(json, "chat_mode"),
                conversationId = ScalarCoercion.optString(json, "conversation_id"),
                parentConversationId = ScalarCoercion.optString(json, "parent_conversation_id"),
                characterType = ScalarCoercion.optInt(json, "character_type"),
                contentType = ScalarCoercion.optInt(json, "content_type"),
                creatorId = parseCreatorId(json),
                versionChange = ScalarCoercion.optBoolean(json, "version_change") ?: false,
            )
        }

        /**
         * simulator 埋点的 creator 三级兜底
         * （`simulatorGameTracking.ts:176`：`creator_id ?? user_id ?? creator.user_id`）。
         * `creator.user_id` 的 TS 类型是 `string | number` —— 又一个标量漂移点。
         */
        private fun parseCreatorId(json: JSONObject): String? {
            ScalarCoercion.optString(json, "creator_id")?.let { return it }
            ScalarCoercion.optString(json, "user_id")?.let { return it }
            val creator = json.optJSONObject("creator") ?: return null
            return ScalarCoercion.optString(creator, "user_id")
        }
    }
}

/** `/user/chatted/list` 的一页（`UserChattedListRes`）。 */
data class ChatThreadPage(
    val items: List<ChatThread>,
    val total: Long,
    val hasMore: Boolean,
    /**
     * 响应 `list` 的原始 JSON —— 冷启动缓存存它而不是模型序列化
     * （同 `HomeFeedPage.rawList` 的理由：读写共用 [parse]，不造第二真值源）。
     */
    val rawList: org.json.JSONArray? = null,
) {
    companion object {
        /**
         * `data` 为 null 时对齐 RN 的兜底（`apis/chat.ts:512-518`）：
         * 空列表 + total 0 + has_more false，**不当错误**。
         */
        fun parse(data: JSONObject?): ChatThreadPage {
            if (data == null) return ChatThreadPage(emptyList(), 0L, hasMore = false)
            val listJson = data.optJSONArray("list")
            val items = buildList {
                if (listJson != null) {
                    for (i in 0 until listJson.length()) {
                        val obj = listJson.optJSONObject(i) ?: continue
                        ChatThread.parse(obj)?.let(::add)
                    }
                }
            }
            return ChatThreadPage(
                items = items,
                total = ScalarCoercion.optLong(data, "total") ?: 0L,
                hasMore = ScalarCoercion.optBoolean(data, "has_more") ?: false,
                rawList = listJson,
            )
        }
    }
}

/**
 * 一个角色的关系等级（`/user/character/relationship/batch_get` 的 items 元素）。
 *
 * 徽章渲染是**双开关**：`user.relationship_switch`（账号级）&&
 * [isRelationshipOpen]（角色级），且 `current_sub_level > 0`、非 mini_phone
 * （`ChatListItem.tsx:423-426` 的四条件）。
 */
data class RelationshipStat(
    val characterId: String,
    /** 徽章上的数字（LV{n}）。 */
    val subLevel: Int,
    /** 大等级 1..5，决定徽章图与颜色。 */
    val level: Int,
    val isRelationshipOpen: Boolean,
) {
    companion object {
        fun parse(json: JSONObject): RelationshipStat? {
            val id = ScalarCoercion.optString(json, "character_id") ?: return null
            return RelationshipStat(
                characterId = id,
                subLevel = ScalarCoercion.optInt(json, "current_sub_level") ?: 0,
                level = ScalarCoercion.optInt(json, "current_level") ?: 0,
                isRelationshipOpen = ScalarCoercion.optBoolean(json, "is_relationship_open")
                    ?: false,
            )
        }
    }
}

/**
 * 一条聊天草稿（RN `store/chat_draft_lru.ts` 的 `ChatDraft`，壳**只读**）。
 *
 * @property updatedAt 毫秒；legacy 纯字符串条目没有该值（见 [ChatDraftStore]），
 *   排序时回落到会话自身的 `latest_time`。
 */
data class ChatDraft(
    val text: String,
    val imageCount: Int,
    val updatedAt: Long?,
)
