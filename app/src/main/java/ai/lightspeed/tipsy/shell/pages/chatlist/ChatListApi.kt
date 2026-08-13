package ai.lightspeed.tipsy.shell.pages.chatlist

import ai.lightspeed.tipsy.shell.network.ApiClient
import ai.lightspeed.tipsy.shell.network.AuthMode
import ai.lightspeed.tipsy.shell.network.ScalarCoercion
import org.json.JSONArray
import org.json.JSONObject

/**
 * ChatList 的接口层（方案 §8.1 ChatList 行，接口清单 2026-08-12 逐个核实）。
 *
 * ## 全部 REQUIRED
 *
 * RN 侧八个端点全走 `axiosAuth`（`apis/chat.ts` / `apis/relationship.ts` /
 * `apis/letter.ts` 已逐个核对）—— 会话列表没有游客形态，未登录时 ViewModel
 * 根本不发（对齐 Profile 的「登出只清不拉」纪律）。
 *
 * ## pin/unpin/delete 的请求体按 item_type 分流
 *
 * game 用 `{item_type, game_id}`，其余用 `{item_id, item_type, chat_mode,
 * conversation_id}`（`apis/chat.ts:566-583` 的三元组条件）。
 * `conversation_id` 是小手机对话级定位 —— 漏传时后端回落到「该角色全部小手机」，
 * 会误删/误 pin 同角色的其它入口。
 */
interface ChatListSource {
    suspend fun fetchPage(page: Int, languageCode: String): ChatThreadPage
    suspend fun fetchRelationshipStats(characterIds: List<String>): List<RelationshipStat>
    suspend fun pin(thread: ChatThread)
    suspend fun unpin(thread: ChatThread)
    suspend fun delete(thread: ChatThread)
    suspend fun markPushMessageViewed(characterId: String)
    suspend fun fetchUnreadStatus(): Boolean
}

class ChatListApi(
    private val apiClient: ApiClient,
    /** 铃铛未读接口的 `platform` 参数：`google_play` 或 `apk`（见 [fetchUnreadStatus]）。 */
    private val downloadChannel: String,
) : ChatListSource {

    /**
     * 会话列表一页。
     *
     * 请求体照 `useUserChattedList.ts:38-44`：`page`/`size=50`/`language_code`/
     * `need_total: true`。page 从 **0** 开始（SWR infinite 的 pageIndex）。
     */
    override suspend fun fetchPage(page: Int, languageCode: String): ChatThreadPage {
        val body = JSONObject()
            .put("page", page)
            .put("size", PAGE_SIZE)
            .put("language_code", languageCode)
            .put("need_total", true)
        val envelope = apiClient.post(
            path = PATH_CHATTED_LIST,
            jsonBody = body.toString(),
            authMode = AuthMode.REQUIRED,
        )
        return ChatThreadPage.parse(envelope.data)
    }

    /**
     * LV 徽章批拉（`/user/character/relationship/batch_get`）。
     *
     * RN 对 id 列表先去重再**排序**（`ChatGrid.tsx:125` 的
     * `[...new Set(...)].sort()`）—— 排序是为了 SWR key 稳定，这里照做，
     * 让同一批 id 的请求体字节一致（后端缓存/日志可对拍）。
     */
    override suspend fun fetchRelationshipStats(
        characterIds: List<String>,
    ): List<RelationshipStat> {
        if (characterIds.isEmpty()) return emptyList()
        val body = JSONObject()
            .put("character_ids", JSONArray(characterIds.distinct().sorted()))
        val envelope = apiClient.post(
            path = PATH_RELATIONSHIP_BATCH,
            jsonBody = body.toString(),
            authMode = AuthMode.REQUIRED,
        )
        val items = envelope.data?.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
                RelationshipStat.parse(obj)?.let(::add)
            }
        }
    }

    override suspend fun pin(thread: ChatThread) {
        apiClient.post(
            path = PATH_PIN,
            jsonBody = pinBody(thread).toString(),
            authMode = AuthMode.REQUIRED,
        )
    }

    override suspend fun unpin(thread: ChatThread) {
        apiClient.post(
            path = PATH_UNPIN,
            jsonBody = pinBody(thread).toString(),
            authMode = AuthMode.REQUIRED,
        )
    }

    /**
     * 删除，按 item_type 走三个端点（`index.tsx:181-190`）。
     *
     * ⚠️ `plot` 类型 RN 走的是 character 端点（else 分支）—— 不要给它单开端点。
     */
    override suspend fun delete(thread: ChatThread) {
        when (thread.itemType) {
            ChatThread.TYPE_STORY -> apiClient.post(
                path = PATH_DELETE_STORY,
                jsonBody = JSONObject().put("story_id", thread.itemId).toString(),
                authMode = AuthMode.REQUIRED,
            )

            ChatThread.TYPE_GAME -> apiClient.post(
                path = PATH_DELETE_GAME,
                jsonBody = JSONObject().put("game_id", thread.gameId).toString(),
                authMode = AuthMode.REQUIRED,
            )

            else -> apiClient.post(
                path = PATH_DELETE_CHARACTER,
                jsonBody = JSONObject().apply {
                    put("character_id", thread.itemId)
                    // 小手机对话级软删的两个定位参数；普通会话都是 null，
                    // JSONObject.put(String, null) 会移除键 —— 与 RN 的
                    // undefined 字段被 JSON.stringify 省略同义
                    thread.chatMode?.let { put("chat_mode", it) }
                    thread.conversationId?.let { put("conversation_id", it) }
                }.toString(),
                authMode = AuthMode.REQUIRED,
            )
        }
    }

    /** 消推送红点（`updatePushMessageViewed`，点击 `is_push_message` 条目时）。 */
    override suspend fun markPushMessageViewed(characterId: String) {
        apiClient.post(
            path = PATH_PUSH_VIEWED,
            jsonBody = JSONObject().put("character_id", characterId).toString(),
            authMode = AuthMode.REQUIRED,
        )
    }

    /**
     * pin/unpin 共用的请求体（`apis/chat.ts:566-583` 的三元组条件）：
     * game → `{item_type, game_id}`；其余 → `{item_id, item_type, chat_mode,
     * conversation_id}`（null 字段不出现在 JSON 里，同 RN 的 undefined 省略）。
     */
    private fun pinBody(thread: ChatThread): JSONObject = if (thread.itemType == ChatThread.TYPE_GAME) {
        JSONObject()
            .put("item_type", thread.itemType)
            .put("game_id", thread.gameId)
    } else {
        JSONObject().apply {
            put("item_id", thread.itemId)
            put("item_type", thread.itemType)
            thread.chatMode?.let { put("chat_mode", it) }
            thread.conversationId?.let { put("conversation_id", it) }
        }
    }

    /**
     * 铃铛未读状态。
     *
     * ⚠️ 端点是 `/message/notification/get_unread_status`（`apis/letter.ts:18-25`），
     * **不是** RN SWR key 里的 `/system_message_notification/read_status` ——
     * 那是缓存键。`platform` 按渠道分流：GooglePlay → `google_play`，其余 → `apk`
     * （`isGooglePlay ? 'google_play' : 'apk'`；壳没有 iOS 分支）。
     *
     * @return `unread_messages` —— 铃铛红点只看这一个字段（`index.tsx:320`）
     */
    override suspend fun fetchUnreadStatus(): Boolean {
        val platform = if (downloadChannel == CHANNEL_GOOGLE_PLAY) "google_play" else "apk"
        val envelope = apiClient.post(
            path = PATH_UNREAD_STATUS,
            jsonBody = JSONObject().put("platform", platform).toString(),
            authMode = AuthMode.REQUIRED,
        )
        val data = envelope.data ?: return false
        return ScalarCoercion.optBoolean(data, "unread_messages") ?: false
    }

    companion object {
        /** `constants/chat.ts:33` 的 `CHATTED_PAGE_SIZE`。固定值，不要「优化」。 */
        const val PAGE_SIZE = 50

        private const val PATH_CHATTED_LIST = "/user/chatted/list"
        private const val PATH_RELATIONSHIP_BATCH = "/user/character/relationship/batch_get"
        private const val PATH_PIN = "/user/chatted/pin"
        private const val PATH_UNPIN = "/user/chatted/unpin"
        private const val PATH_DELETE_CHARACTER = "/user/chatted/character/delete"
        private const val PATH_DELETE_STORY = "/user/chatted/story/delete"
        private const val PATH_DELETE_GAME = "/user/chatted/game/delete"
        private const val PATH_PUSH_VIEWED = "/user/chatted/update_push_message_view_time"
        private const val PATH_UNREAD_STATUS = "/message/notification/get_unread_status"

        /** `BuildConfig.DOWNLOAD_CHANNEL` 的 GooglePlay 值（app/build.gradle:103）。 */
        private const val CHANNEL_GOOGLE_PLAY = "GooglePlay"
    }
}
