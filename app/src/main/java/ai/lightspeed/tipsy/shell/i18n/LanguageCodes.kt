package ai.lightspeed.tipsy.shell.i18n

/**
 * 语言码规范化（W1-P5，方案 §4.8）。
 *
 * ## ⚠️ 这里有**两条**规则，不是一条
 *
 * 方案 §4.8 与 W1 计划 §7.2 都只提了 `normalizeLanguageCode`，但
 * `src/i18n/i18n-index.ts` 里实际存在两个对同一输入给**不同答案**的函数：
 *
 * | 场景 | RN 出处 | `zh`（简体）的结果 |
 * | --- | --- | --- |
 * | 账号语言 / 任意语言码 | `normalizeLanguageCode`（`:64-75`） | **`zh-tw`** |
 * | 启动读设备 locale | `defaultLanguage`（`:118-135`） | **`en`** |
 *
 * 也就是说：账号 `language_code` 存 `zh` 的用户看繁体，而设备语言是简体中文
 * 的新用户看英文。**这不是 bug，是两个不同场景的产品决策**（iOS 的
 * `L10n.swift:56-79` 同样拆成两个函数）。
 *
 * 只实现一个的后果：简体设备用户会看到繁体中文。而且这种偏差在
 * 英文环境测试里**完全看不出来** —— 与方案 §4.8 记的 iOS 教训同类。
 *
 * ## 为什么不映射成 Android 资源限定符
 *
 * 方案 §4.8 明写「不强行映射成 Android resource name」。RN 的 key 就是英文原文
 * （含空格、标点、`{{}}` 插值），走 `strings.xml` 要为 1838 个 key 造合法资源名，
 * 且每次 RN 侧改文案都要重新映射。壳改为运行期查表（见 [L10n]）。
 */
object LanguageCodes {

    /**
     * `SUPPORTED_LANGUAGES`（`i18n-index.ts:31-58`，**26** 个）。
     *
     * ⚠️ 与另外三套集合都不同，四者不可混用（方案 §4.8）：
     * - 磁盘 locale JSON **28** 个（含 `ar.json`）
     * - `i18n-index.ts` 实际 import **27** 个（`ar` 未 import）
     * - 本集合 **26** 个（`zh` 有 import 但**不在**支持码内）
     * - 设置页可选列表：服务端 `/supported_languages`，**≠** 以上任何一个
     *
     * 顺序照抄 RN，便于 diff。
     */
    val SUPPORTED: Set<String> = linkedSetOf(
        "en", "de", "ko", "pt", "ru", "fr", "ja", "es", "it", "zh-tw",
        "nl", "cs", "fi", "fil", "pl", "hu", "ro", "no", "da", "sv",
        "th", "vi", "id", "ms", "pt-br", "tr",
    )

    /** 兜底语言。fallback 链的中间一环，见 [L10n.t]。 */
    const val FALLBACK = "en"

    /**
     * 规范化任意语言码（对齐 `normalizeLanguageCode`，`i18n-index.ts:64-75`）。
     *
     * 逐行对应：lowercase + `_`→`-` → 精确匹配 → 主语言码匹配（`es-CR`→`es`）
     * → `zh` 系一律 `zh-tw` → 兜底 `en`。
     *
     * **用于账号语言码与桥传入的值**，不要用于设备 locale（见 [fromDeviceLocale]）。
     */
    fun normalize(input: String?): String {
        if (input.isNullOrEmpty()) return FALLBACK
        val normalized = input.lowercase().replace('_', '-')

        // 精确匹配
        if (normalized in SUPPORTED) return normalized

        // 主语言码匹配（es-CR → es）
        val primary = normalized.substringBefore('-')
        if (primary in SUPPORTED) return primary

        // zh-Hant / zh-TW / zh-HK 等繁体变体 → zh-tw
        //
        // ⚠️ 注意这一支会**吞掉简体 zh**：`zh` 不在 SUPPORTED 里（只有 `zh-tw`），
        // 所以 `zh` 走到这里也返回 `zh-tw`。这是 RN 的既有行为，照抄。
        // 设备 locale 那条路径需要不同结果，见 [fromDeviceLocale]。
        if (primary == "zh") return "zh-tw"

        return FALLBACK
    }

    /**
     * 启动时按**设备 locale** 决定初始语言（对齐 `defaultLanguage`，
     * `i18n-index.ts:118-135`）。
     *
     * 与 [normalize] 的唯一差别：**简体中文设备用英文**（产品决策）。
     * 判定方式照抄 RN —— 以 `zh` 开头且不含 `zh-hant`/`zh-tw`/`zh-hk` 即视为简体。
     *
     * @param languageTag 设备 locale 的 BCP-47 tag（如 `zh-Hans-CN`）
     */
    fun fromDeviceLocale(languageTag: String?): String {
        if (languageTag.isNullOrEmpty()) return FALLBACK
        val normalized = languageTag.lowercase().replace('_', '-')

        if (normalized.startsWith("zh") &&
            !normalized.contains("zh-hant") &&
            !normalized.contains("zh-tw") &&
            !normalized.contains("zh-hk")
        ) {
            return FALLBACK
        }
        return normalize(languageTag)
    }
}
