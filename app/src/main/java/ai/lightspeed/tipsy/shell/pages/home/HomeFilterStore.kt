package ai.lightspeed.tipsy.shell.pages.home

import ai.lightspeed.tipsy.shell.auth.LegacyMmkvStore
import org.json.JSONObject

/**
 * Home 筛选条件的读写（方案 §8.1「筛选持久化」行 + §4.6 存储契约）。
 *
 * ## ⚠️ `nsfw` 只读，`gender` 可写 —— 两个字段的所有权不同
 *
 * 同一个 `config-persist-storage` 信封里：
 *
 * | 字段 | 真值在哪 | 壳能写吗 |
 * | --- | --- | --- |
 * | `gender` | 本地设备偏好 | ✅ 能写 |
 * | `nsfw` | **后端 `user.nsfw`** | ❌ 只读 |
 *
 * `nsfw` 由 RN 的 store 底部订阅从 `user.nsfw` **单向镜像**过来，
 * App 不回写后端（`config_persist.ts:225` 注释 + 文件末尾的 `useUserStore.subscribe`）。
 * 壳写 `nsfw` 会破坏这个单向流：下次 RN Surface 起来时又被 user.nsfw 覆盖回去，
 * 表现为「关了 NSFW 过一会儿自己开回来」。
 *
 * ## 写入必须 merge（方案 §4.6）
 *
 * 这是 Zustand persist 信封 `{state: {...}, version: n}`。整体覆盖会丢掉
 * 同一信封里其余二十多个字段（模型选择、上下文长度、已点击标签…），
 * 表现为**用户的一堆设置被重置**，且不报错。
 *
 * 所以写 gender 的流程是：读原始 JSON → 只改 `state.gender` → 写回。
 * 读不出信封时**不写**（宁可丢一次筛选偏好，也不要造一个残缺信封覆盖旧的）。
 */
/**
 * 筛选读写的接缝（同 [HomeFeedSource] 的理由：让 ViewModel 可单测）。
 *
 * ⚠️ 三个方法的所有权不同，见 [HomeFilterStore] 类注释 —— **没有 `writeNsfw`
 * 是刻意的**，不要"为了对称"补一个。
 */
interface HomeFilters {
    fun readGender(): HomeGender
    fun readNsfw(): Boolean
    fun writeGender(gender: HomeGender): Boolean
}

class HomeFilterStore(private val store: LegacyMmkvStore) : HomeFilters {

    /** 性别筛选。读不到时回落 [HomeGender.ALL]（RN 初始值是空串，同样回落 All）。 */
    override fun readGender(): HomeGender =
        HomeGender.fromStored(readStateField(FIELD_GENDER))

    /**
     * NSFW 镜像。**只读**，见类注释。
     *
     * 默认 false（`config_persist.ts:223` `nsfw: false`）—— 读不到时按最保守值走，
     * 不能默认 true。
     */
    override fun readNsfw(): Boolean {
        val raw = readState() ?: return false
        if (!raw.has(FIELD_NSFW) || raw.isNull(FIELD_NSFW)) return false
        return raw.optBoolean(FIELD_NSFW, false)
    }

    /**
     * 写性别（merge，见类注释）。
     *
     * @return 是否真的写进去了。false 表示信封不可读/不可写 —— 调用方**不要**
     *   因此回滚 UI 状态：内存里的筛选仍应生效，只是这次没持久化。
     *   （回滚会让用户看到「点了性别又跳回去」，比丢持久化更糟。）
     */
    override fun writeGender(gender: HomeGender): Boolean {
        val merged = mergeGenderIntoEnvelope(store.getString(CONFIG_PERSIST_KEY), gender)
            ?: return false
        return store.putString(CONFIG_PERSIST_KEY, merged)
    }

    private fun readEnvelope(): JSONObject? = parseEnvelope(store.getString(CONFIG_PERSIST_KEY))

    private fun readState(): JSONObject? = readEnvelope()?.optJSONObject(ENVELOPE_STATE)

    /** 读一个字符串字段。null 与字面量 `"null"` 都当作缺失（同 [AccountLanguageReader] 的坑）。 */
    private fun readStateField(field: String): String? {
        val state = readState() ?: return null
        if (!state.has(field) || state.isNull(field)) return null
        return state.optString(field).takeIf { it.isNotBlank() && it != "null" }
    }

    companion object {
        /** RN `persist` 的 name（`config_persist.ts:445`）。 */
        const val CONFIG_PERSIST_KEY = "config-persist-storage"
    }
}

private const val ENVELOPE_STATE = "state"
private const val FIELD_GENDER = "gender"
private const val FIELD_NSFW = "nsfw"

/** 解析信封；非 JSON 对象 / 空值都返回 null。 */
private fun parseEnvelope(raw: String?): JSONObject? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
    return runCatching { JSONObject(trimmed) }.getOrNull()
}

/**
 * 把 gender **merge 进**信封，返回要写回的字符串；不可用时 null（调用方不写）。
 *
 * 抽成纯函数是为了单测 —— 这是本包破坏性最大的一处写入（整体覆盖会重置用户
 * 二十多项设置且不报错），必须能对着 JSON 逐条断言。
 */
internal fun mergeGenderIntoEnvelope(raw: String?, gender: HomeGender): String? {
    val envelope = parseEnvelope(raw) ?: return null
    // 缺 state 子对象说明不是 Zustand 信封 —— 不认，宁可不写
    val state = envelope.optJSONObject(ENVELOPE_STATE) ?: return null
    // ⚠️ 只 put 这一个 key。**不要顺手写 nsfw** —— 它的真值在后端，
    // 由 RN 的 user store 订阅单向镜像过来（见类注释）
    state.put(FIELD_GENDER, gender.storedValue)
    return envelope.toString()
}

/** 测试入口（同 `parseForYouForTest` 的理由）。 */
internal fun mergeGenderIntoEnvelopeForTest(raw: String?, gender: HomeGender): String? =
    mergeGenderIntoEnvelope(raw, gender)
