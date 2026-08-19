package ai.lightspeed.tipsy.shell.i18n

import ai.lightspeed.tipsy.shell.user.UserStorageRepository
import org.json.JSONObject

/**
 * 从 RN 的 `user-storage` 信封里读账号语言码（W1-P5）。
 *
 * ## 语言真值链（实测）
 *
 * 真值在**后端**，不在本地：
 * ```
 * 设置页选语言 → POST /user/set_language → updateUserInfo() 重拉
 *   → user.language_code → user-storage 的 languageCode（本地镜像）
 * ```
 * （`useChangeLanguage.ts:57-72` + `store/user.ts:187`）
 *
 * 所以壳读 `user-storage.state.languageCode` 就拿到了账号语言。
 *
 * ## ⚠️ 本类只读，但这个 key 壳**是要写的** —— 写在 [AccountLanguageWriter]
 *
 * 原注释写着「壳只读不写这个 key」，理由是「语言设置页刻意不迁移，留在
 * `SettingsSurface` 里由 RN 发 `/user/set_language`」。
 * **那个前提在语言页原生化（§2.33）之后已不成立。**
 *
 * 现在壳是语言的唯一写入者，信封镜像也必须由壳维护 —— 只读会让本类读到
 * 自己造成的陈旧值，把用户刚选的语言覆盖回英文（§2.37 的 FAIL 项）。
 * 读写方向必须成对，详见 [AccountLanguageWriter] 类注释。
 *
 * 本类仍然只负责**读**（`user-storage` 是 Zustand persist 信封，方案 §4.6
 * 要求写入必须 merge），写入收在 [AccountLanguageWriter] / [AccountLanguageMirror]。
 *
 * ## 关于「RN 侧改语言」这条反向路径
 *
 * 桥契约里只有壳→JS 的 `onLanguageChanged`，没有 JS→壳 的语言通知
 * （已核实 `modules/tipsy-auth/src/index.ts`）。壳靠 `MainActivity` 在 Surface
 * 容器出栈时重读本 key 兜住这条路。
 *
 * ⚠️ 语言页原生化后，RN 侧已经没有改语言的入口了（`KNOWN_SCREENS` 不含
 * `Language`）—— 那个重读时机现在的主要作用是**兜 RN 侧其它写 user store
 * 的路径**（如 `updateUserInfo`）。它不再是语言的必需链路，但仍是共享信封
 * 的重读点，所以保留。
 */
object AccountLanguageReader {

    /** RN 侧 `persist` 的 name（`store/user.ts:287`）。 */
    const val USER_STORAGE_KEY = UserStorageRepository.USER_STORAGE_KEY

    /**
     * 解析出账号语言码（**未规范化**，调用方走 [LanguageCodes.normalize]）。
     *
     * @return 非空语言码；无信封 / 无该字段 / 为 null 时返回 null，
     *   表示「账号没有语言意见」，调用方应沿用设备默认。
     */
    fun parse(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || !value.startsWith("{")) return null

        val json = runCatching { JSONObject(value) }.getOrNull() ?: return null

        // Zustand persist 信封：{"state":{...},"version":n}。
        // 没有 partialize（已核实 store/user.ts:286-288），所以 languageCode
        // 确实会被持久化，不必担心它只在内存里
        val state = json.optJSONObject("state") ?: return null

        // optString 对 JSON null 返回字面量 "null" —— 不特判会把它当成一个
        // 叫 "null" 的语言码，normalize 后静默变成 en。与 LegacyTokenReader 同一坑
        if (!state.has(FIELD_LANGUAGE_CODE) || state.isNull(FIELD_LANGUAGE_CODE)) return null
        return state.optString(FIELD_LANGUAGE_CODE).takeIf { it.isNotBlank() && it != "null" }
    }

    /** `store/user.ts:35` 的字段名（注意是 camelCase —— 信封里存的是 JS 侧字段名，
     *  不是接口的 snake_case `language_code`）。 */
    private const val FIELD_LANGUAGE_CODE = "languageCode"
}
