package ai.lightspeed.tipsy.shell.auth

import org.json.JSONObject

/**
 * 读 RN 时代遗留的 token（方案 §2.4 迁移算法第 1 步）。
 *
 * ## 为什么要兼容三种形态
 *
 * `src/store/auth.ts` 当前写入的是**裸字符串**（`storage.set(TOKEN_STORAGE_KEY, token)`），
 * 但同文件的 `parseLegacyPersistedToken` 说明历史版本还写过：
 * - Zustand persist 信封：`{"state":{"token":"..."}}`
 * - 半迁移形态：`{"token":"..."}`
 *
 * 覆盖升级设备上三种都可能存在（取决于用户上次用的是哪个版本），
 * **少兼容一种 = 那批用户升级后掉登录**。
 *
 * ## 刻意不做的事
 *
 * 不校验 JWT 结构/签名/过期 —— 那是调用方（`ShellTokenStore`）的职责。
 * 本类只负责「把存的东西如实取出来」，把「取出来」与「判断是否可用」分开，
 * 便于分别测试，也避免把解析失败与业务失效混成一个错误。
 */
object LegacyTokenReader {

    /** RN 侧的 key，见 `src/store/auth.ts` 的 `TOKEN_STORAGE_KEY`。 */
    const val TOKEN_STORAGE_KEY = "token-storage"

    /**
     * 从 MMKV 取出的原始字符串里解析 token。
     *
     * @return 非空 token；无法解析或为空白时返回 null。
     */
    fun parse(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null

        // 形态 1：裸字符串（当前 RN 版本写入的形态）。
        // 用「不像 JSON」而非「解析失败」判定 —— 裸 token 是 JWT，
        // 以 eyJ 开头且含两个点，绝不会以 { 开头。
        if (!value.startsWith("{")) {
            return value.takeIf { it.isNotBlank() }
        }

        val json = runCatching { JSONObject(value) }.getOrNull() ?: return null

        // 形态 2：Zustand persist 信封 {"state":{"token":...},"version":n}
        json.optJSONObject("state")?.let { state ->
            state.optNonBlankString("token")?.let { return it }
        }

        // 形态 3：{"token":"..."}
        json.optNonBlankString("token")?.let { return it }

        return null
    }

    /**
     * `optString` 对 JSON null 会返回字面量 "null" 字符串 —— 直接用会把
     * 「字段是 null」误当成一个叫 "null" 的 token，是个静默错值。
     */
    private fun JSONObject.optNonBlankString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() && it != "null" }
    }
}
