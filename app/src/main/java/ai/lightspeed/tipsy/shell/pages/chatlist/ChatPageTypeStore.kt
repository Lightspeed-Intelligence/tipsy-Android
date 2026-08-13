package ai.lightspeed.tipsy.shell.pages.chatlist

import ai.lightspeed.tipsy.shell.auth.LegacyMmkvStore
import android.util.Log
import org.json.JSONObject

/**
 * Grid/Map 视图偏好（`config-persist-storage` 信封的 `chatPageType` 字段）。
 *
 * ## 与 `HomeFilterStore.gender` 同一所有权形态：本地设备偏好，壳可写
 *
 * RN 侧 `setChatPageType` 只 set 本地 store（`config_persist.ts:364-365`），
 * 不涉及后端 —— 与 `nsfw`（后端权威、壳只读）不同。
 *
 * ## 写入必须 merge（方案 §4.6，同 `mergeGenderIntoEnvelope`）
 *
 * Zustand persist 信封 `{state: {...}, version: n}` 里有二十多个字段，
 * 整体覆盖会静默重置用户的模型选择等设置。读原始 JSON → 只改
 * `state.chatPageType` → 写回。信封不可读时**不写**（继承进度文档 §2.23.1
 * 的已知问题：全新安装且 RN 未初始化过信封时偏好不持久化，内存态仍生效）。
 */
/** 读写的接缝（让 ViewModel 可单测，同 `HomeFilters`）。 */
interface ChatPageTypeStoreLike {
    fun read(): ChatPageType
    fun write(type: ChatPageType): Boolean
}

class ChatPageTypeStore(private val store: LegacyMmkvStore) : ChatPageTypeStoreLike {

    /** 读视图偏好。读不到回落 GRID（RN 初始值 `ChatPageType.GRID`）。 */
    override fun read(): ChatPageType {
        val raw = store.getString(CONFIG_PERSIST_KEY) ?: return ChatPageType.GRID
        val envelope = parseEnvelope(raw) ?: return ChatPageType.GRID
        val state = envelope.optJSONObject(ENVELOPE_STATE) ?: return ChatPageType.GRID
        val value = if (state.isNull(FIELD_CHAT_PAGE_TYPE)) {
            null
        } else {
            state.optString(FIELD_CHAT_PAGE_TYPE)
        }
        return ChatPageType.fromStored(value)
    }

    /**
     * 写视图偏好（merge，见类注释）。
     *
     * @return 是否真的写进去了。false 时调用方**不要**回滚 UI —— 内存态仍生效，
     *   只是这次没持久化（同 `HomeFilterStore.writeGender` 的约定）。
     */
    override fun write(type: ChatPageType): Boolean {
        val merged = mergeChatPageTypeIntoEnvelope(store.getString(CONFIG_PERSIST_KEY), type)
            ?: run {
                Log.w(TAG, "config-persist 信封不可读，chatPageType 本次不持久化")
                return false
            }
        return store.putString(CONFIG_PERSIST_KEY, merged)
    }

    companion object {
        private const val TAG = "ChatPageTypeStore"

        /** RN `persist` 的 name（`config_persist.ts:445`），与 Home 的 gender 同信封。 */
        const val CONFIG_PERSIST_KEY = "config-persist-storage"
    }
}

/** `types/chat.ts:244-247` 的 `ChatPageType` 枚举。 */
enum class ChatPageType(val storedValue: String) {
    GRID("grid"),
    MAP("map"),
    ;

    companion object {
        /** 未知值回落 GRID —— 信封可能被更新版本的 RN 写入新值。 */
        fun fromStored(value: String?): ChatPageType =
            entries.firstOrNull { it.storedValue == value } ?: GRID
    }
}

private const val ENVELOPE_STATE = "state"
private const val FIELD_CHAT_PAGE_TYPE = "chatPageType"

private fun parseEnvelope(raw: String?): JSONObject? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
    return runCatching { JSONObject(trimmed) }.getOrNull()
}

/**
 * 把 chatPageType merge 进信封；信封不可用时 null（调用方不写）。
 * 抽成纯函数为了单测（同 `mergeGenderIntoEnvelope` 的理由）。
 */
internal fun mergeChatPageTypeIntoEnvelope(raw: String?, type: ChatPageType): String? {
    val envelope = parseEnvelope(raw) ?: return null
    val state = envelope.optJSONObject(ENVELOPE_STATE) ?: return null
    state.put(FIELD_CHAT_PAGE_TYPE, type.storedValue)
    return envelope.toString()
}
