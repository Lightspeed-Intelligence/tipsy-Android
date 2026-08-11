package ai.lightspeed.tipsy.shell.i18n

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
 * ## ⚠️ 为什么壳只读不写这个 key
 *
 * **语言设置页刻意不迁移**（方案 §8.1，与 iOS 同边界）—— 它留在
 * `SettingsSurface` 里，仍由 RN 发 `/user/set_language`。壳不需要写这条链。
 *
 * 而且 `user-storage` 是 Zustand persist 信封（`{state, version}`），
 * 方案 §4.6 要求「原生写入必须 merge，不得整体覆盖破坏信封」。
 * 本类**只读**，把写入的风险留给真正需要它的步骤（P2）。
 *
 * ## 已知缺口（写下来避免当成 bug）
 *
 * RN 设置页改完语言后，壳这边**不会自动收到通知** —— 桥契约里只有壳→JS 的
 * `onLanguageChanged`，没有反向的方法（已核实 `modules/tipsy-auth/src/index.ts`）。
 * 当前处理：壳在 Surface 容器关闭时重读本 key（见 `MainActivity`）。
 * 这不需要改 `tipsy-app`。若将来发现该时机不够（例如设置页不关就切 Tab），
 * 再考虑给桥加一个可选方法 —— 别为了「更干净」提前改跨仓契约。
 */
object AccountLanguageReader {

    /** RN 侧 `persist` 的 name（`store/user.ts:287`）。 */
    const val USER_STORAGE_KEY = "user-storage"

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
