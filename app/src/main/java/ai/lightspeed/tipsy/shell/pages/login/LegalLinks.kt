package ai.lightspeed.tipsy.shell.pages.login

/**
 * 登录页条款链接（对齐 RN `LoginScreen.tsx:567-618`）。
 *
 * ## ⚠️ 域名按渠道分流
 *
 * RN 侧用 `isGooglePlay` 二分（`constants/common.ts:18-19`：
 * `Platform.OS === 'android' && !isAndroidAPK && !isRuStore`）：
 *
 * | 渠道 | 域名 |
 * | --- | --- |
 * | GooglePlay | `chaterai.xyz` |
 * | APK / RuStore | `tipsy.chat` |
 *
 * 壳侧用 `BuildConfig.DOWNLOAD_CHANNEL` 判定 —— 三个 flavor 的值分别是
 * `GooglePlay` / `APK` / `RuStore`（`app/build.gradle:81,87,93`），
 * 与 RN 的三分支一一对应。
 *
 * **搞错域名不会报错**，只是用户点开看到另一个品牌站点的条款 ——
 * 这在合规上是实质问题（Google Play 审核会看条款链接是否指向申报的主体）。
 */
object LegalLinks {

    private const val HOST_GOOGLE_PLAY = "https://chaterai.xyz"
    private const val HOST_OTHER = "https://tipsy.chat"

    /** `DOWNLOAD_CHANNEL` 的 GooglePlay 值（`app/build.gradle:81`）。 */
    private const val CHANNEL_GOOGLE_PLAY = "GooglePlay"

    data class Urls(
        val communityGuidelines: String,
        val termsOfService: String,
        val privacyPolicy: String,
    )

    /**
     * @param downloadChannel 传 `BuildConfig.DOWNLOAD_CHANNEL`。
     *   注入而非直接读 BuildConfig —— 否则三渠道的分流逻辑没法单测
     *   （`BuildConfig` 在单测里是编译进去的固定值）。
     */
    fun forChannel(downloadChannel: String): Urls {
        val host = if (downloadChannel == CHANNEL_GOOGLE_PLAY) HOST_GOOGLE_PLAY else HOST_OTHER
        return Urls(
            communityGuidelines = "$host/community-guidelines",
            termsOfService = "$host/terms-of-service",
            privacyPolicy = "$host/privacy-policy",
        )
    }

    // ── i18n key（= 英文原文，与 RN 的 t() 参数逐字一致）───────
    //
    // ⚠️ 这些 key 必须在 `SHELL_KEYS` 白名单里且已导出，否则非英文用户
    // 看到英文（方案 §4.8 记的 iOS 教训）。已确认全部在 assets/locales 里。

    const val KEY_TERMS_PREFIX =
        "By logging in, you confirm that you are at least 18 years old and agree to our"
    const val KEY_COMMUNITY_GUIDELINES = "Community Guidelines"
    const val KEY_TERMS_OF_SERVICE = "Terms of Service"
    const val KEY_PRIVACY_POLICY = "Privacy Policy"
    const val KEY_AND = "and"
}
