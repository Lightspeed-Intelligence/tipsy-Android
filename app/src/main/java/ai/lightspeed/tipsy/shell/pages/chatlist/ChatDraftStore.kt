package ai.lightspeed.tipsy.shell.pages.chatlist

import ai.lightspeed.tipsy.shell.auth.LegacyMmkvStore
import ai.lightspeed.tipsy.shell.network.ScalarCoercion
import org.json.JSONArray

/**
 * 聊天草稿读取（RN `store/chat_draft_lru.ts`，方案 §8.1 ChatList「草稿」行）。
 *
 * ## 壳**只读**
 *
 * 写方是 RN 的 ChatDetail（输入框离开时存草稿）。壳读它做两件事
 * （`ChatGrid.tsx:99-121` + `ChatListItem.tsx:460-473`）：
 * 1. 行内展示 `[Draft]` 前缀 + 草稿文本（无文本时显示 `Image`）+ 草稿时间
 * 2. 参与排序：有草稿的会话按 `updatedAt` 与其它会话的 `latest_time*1000` 混排
 *
 * 壳写它会与 RN 的 LRU 语义打架（容量淘汰顺序由 lru-cache 维护，
 * 壳没有对应实现），且 §4.1 没有给壳这个 owner 位。
 *
 * ## 存储格式：`lru-cache` 的 `dump()` 转储，不是普通对象
 *
 * MMKV key `chat_draft_lru`（默认实例），值是
 * `[[characterId, {value: ChatDraft}], ...]` 的 JSON（`PersistLRU` 直接
 * `JSON.stringify(lruCache.dump())`）。两层包装都要剥：
 * 外层 entry 是 `[key, wrapper]` 二元组，内层 wrapper 的 `value` 才是草稿。
 *
 * ## legacy 纯字符串条目
 *
 * 早期版本直接存字符串（`getChatDraft` 的 `typeof raw === 'string'` 迁移分支）。
 * RN 读到时会**写回**迁移后的对象，壳只读 —— 所以这里必须兼容两种形状，
 * 且 legacy 条目没有 `updatedAt`（排序回落到会话自身时间，展示不出草稿时间）。
 *
 * ## mini_phone 不查草稿
 *
 * 草稿键是 `item_id`（角色 id），小手机与普通会话同角色同键 ——
 * RN 对 mini_phone 条目**跳过**草稿（`ChatGrid.tsx:104`、`ChatListItem.tsx:391`），
 * 否则普通会话的草稿会串显到小手机行上。调用方（ViewModel）负责这条过滤。
 */
/** 草稿读取的接缝（同 `HomeFilters` 的理由：让 ViewModel 可单测）。 */
interface ChatDraftStoreLike {
    fun readAll(): Map<String, ChatDraft>
}

class ChatDraftStore(private val store: LegacyMmkvStore) : ChatDraftStoreLike {

    /**
     * 读全部草稿，`characterId → 草稿`。
     *
     * 一次性整表读而不是按 id 单查：MMKV 里是一整个 JSON 字符串，
     * 按 id 查也得整个 parse，缓存整表让一帧内 N 行只 parse 一次。
     * 解析失败返回空表 —— 草稿是锦上添花，坏数据不该让列表页挂掉。
     */
    override fun readAll(): Map<String, ChatDraft> =
        parseDump(store.getString(STORAGE_KEY))

    companion object {
        /** `chatDraftLru = new PersistLRU('chat_draft_lru', 100)`。 */
        const val STORAGE_KEY = "chat_draft_lru"

        /** 解析 lru dump；抽成纯函数为了单测（绕开 MMKV native 依赖）。 */
        internal fun parseDump(raw: String?): Map<String, ChatDraft> {
            if (raw.isNullOrBlank()) return emptyMap()
            val entries = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyMap()
            val result = HashMap<String, ChatDraft>()
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONArray(i) ?: continue
                val characterId = entry.optString(0).takeIf { it.isNotEmpty() } ?: continue
                val draft = parseEntryValue(entry) ?: continue
                result[characterId] = draft
            }
            return result
        }

        /** 测试入口（同 `mergeGenderIntoEnvelopeForTest` 的理由）。 */
        internal fun parseDumpForTest(raw: String?): Map<String, ChatDraft> = parseDump(raw)

        /**
         * 剥两层包装。entry[1] 是 lru-cache 的 wrapper `{value: ...}`；
         * `value` 可能是对象（现行 ChatDraft）或字符串（legacy）。
         */
        private fun parseEntryValue(entry: JSONArray): ChatDraft? {
            val wrapper = entry.optJSONObject(1) ?: return null
            // legacy：value 直接是字符串
            wrapper.opt("value")?.let { value ->
                if (value is String) {
                    return if (value.isBlank()) null else ChatDraft(
                        text = value,
                        imageCount = 0,
                        updatedAt = null,
                    )
                }
            }
            val obj = wrapper.optJSONObject("value") ?: return null
            val text = ScalarCoercion.optString(obj, "text").orEmpty()
            val imageCount = obj.optJSONArray("imageAttachments")?.length() ?: 0
            // 空草稿（无文本且无图）不进表 —— RN 的 normalize 在写入时已挡，
            // 这里再挡一次防脏数据（空草稿会让行显示一个孤零零的 [Draft] 前缀）
            if (text.isBlank() && imageCount == 0) return null
            return ChatDraft(
                text = text,
                imageCount = imageCount,
                updatedAt = ScalarCoercion.optLong(obj, "updatedAt"),
            )
        }
    }
}
