package ai.lightspeed.tipsy.shell.pages.settings

import ai.lightspeed.tipsy.shell.BuildConfig
import ai.lightspeed.tipsy.shell.router.AppRoute

/**
 * Settings 列表的行定义与**渠道 gating**（`settings/page.tsx` 逐行核实）。
 *
 * ## 为什么 gating 收在这里而不是散在 Compose 里
 *
 * RN 侧有 **9 处 `!isGooglePlay`** 加两个独立条件，散在 430 行 JSX 中。
 * 抄到 Compose 里会变成 9 个 `if`，而漏掉任何一个的表现是
 * **「GooglePlay 版多出一行不该有的入口」** —— 那是会被商店审核抓的合规问题，
 * 且本地跑 directApk 完全看不出来（那个渠道所有行都显示）。
 *
 * 所以行是数据（[ALL] 里带 [visibleIn] 谓词），UI 只 `filter` 一次。
 * 单测因此能对三个渠道各断言一遍完整行序（`SettingsRowTest`）。
 *
 * ## ⚠️ `isAndroidAPK` 是**一个**渠道，不是「所有 Android」
 *
 * RN 的 `isAndroidAPK = Application.applicationId === PACKAGE_NAME_APK`
 * （`constants/common.ts:16`）—— 只有 directApk（`ai.lightspeed.tipsy`）。
 * **RuStore 也不满足**。Limitless 开关用的是这个条件
 * （`shouldShowNsfwSetting(isAndroidAPK)` = 恒等函数，`nsfwPolicy.ts:12`），
 * 所以三渠道里**只有 directApk 显示分级开关**。
 *
 * 写成「Android 就显示」会让 RuStore 版出现一个不该有的成人内容开关。
 */
enum class SettingsRow(
    /** i18n key（key = 英文原文，走 `L10n`）。 */
    val titleKey: String,
    /** testTag；RN 的 `testID` 有值时照抄（自动化脚本双端共用）。 */
    val testTag: String,
    /** 该行做什么，见 [SettingsAction]。 */
    val action: SettingsAction,
) {
    /** 语言页 —— **原生**（§2.33：RN 的 `KNOWN_SCREENS` 刻意不含 `Language`）。 */
    LANGUAGE("Language", "settings_language_click", SettingsAction.OpenLanguage),

    /** 订阅。⚠️ 非 GooglePlay 才有（`page.tsx:217`）。 */
    SUBSCRIPTION("Subscription", "settings_subscription_click", SettingsAction.Subscription),

    /**
     * 添加小组件。RN 条件是 `isAndroidWidgetSupported`
     * （`utils/widgetSupport.ts`），壳侧目标是 `SettingsSurface` 的 `Widget` 屏。
     *
     * ⚠️ 壳当前**恒显示**：RN 那个判定读的是原生模块的能力查询，壳侧对应物
     * （Widget 支持探测）属 W4 Widget 包。显示但点击被拒 ≠ 不显示，
     * 视觉差异记在验收里，比静默隐藏一个功能安全。
     */
    ADD_WIDGET(
        "Add Widget",
        "settings_add_widget_click",
        SettingsAction.SurfaceScreen(AppRoute.SettingsSubScreen.Screen.WIDGET),
    ),

    /**
     * 账号与安全 —— **本地展开/收起，不是导航**（`page.tsx:247`
     * `setAccountSecurityExpanded`）。展开后才出现下面三行。
     */
    ACCOUNT_SECURITY(
        "Account & Security",
        "settings_account_security_click",
        SettingsAction.ToggleAccountSecurity,
    ),

    /** 安全设置（展开项）。⚠️ 非 GooglePlay 才有（`page.tsx:251`）。 */
    SECURITY(
        "Security",
        "settings_security_click",
        SettingsAction.SurfaceScreen(AppRoute.SettingsSubScreen.Screen.SECURITY),
    ),

    /** 黑名单（展开项）。文案 key 是 **`Blocked`** 不是 `Blacklist`（屏名才是后者）。 */
    BLOCKED(
        "Blocked",
        "settings_blocked_click",
        SettingsAction.SurfaceScreen(AppRoute.SettingsSubScreen.Screen.BLACKLIST),
    ),

    /** 删除账号（展开项）。 */
    DELETE_ACCOUNT(
        "Delete Account",
        "settings_delete_account_click",
        SettingsAction.SurfaceScreen(AppRoute.SettingsSubScreen.Screen.DELETE),
    ),

    /** 反馈。三渠道都有。 */
    FEEDBACK(
        "Feedback",
        "settings_feedback_click",
        SettingsAction.SurfaceScreen(AppRoute.SettingsSubScreen.Screen.FEEDBACK),
    ),

    /** 社区规范。⚠️ 非 GooglePlay 才有；**外部链接**不经 Surface。 */
    COMMUNITY_GUIDELINES(
        "Community Guidelines",
        "settings_community_guidelines_click",
        SettingsAction.OpenUrl(URL_COMMUNITY_GUIDELINES),
    ),

    /** 服务条款。⚠️ 非 GooglePlay 才有；外部链接。 */
    TERMS_OF_SERVICE(
        "Terms of Service",
        "settings_terms_click",
        SettingsAction.OpenUrl(URL_TERMS),
    ),

    /** 官网。⚠️ 非 GooglePlay 才有；外部链接。 */
    OFFICIAL_WEBSITE(
        "Official Website",
        "settings_website_click",
        SettingsAction.OpenUrl(URL_HOME),
    ),

    /** 关于。三渠道都有。 */
    ABOUT(
        "About Tipsy",
        "settings_about_click",
        SettingsAction.SurfaceScreen(AppRoute.SettingsSubScreen.Screen.ABOUT),
    ),

    /** 联系我们。三渠道都有。 */
    CONTACT_US(
        "Contact Us",
        "settings_contact_click",
        SettingsAction.SurfaceScreen(AppRoute.SettingsSubScreen.Screen.CONTACT_US),
    ),

    /**
     * 分级开关（Limitless）。⚠️ **只有 directApk**，见类注释。
     *
     * 它是 `nsfw` 的唯一写方（§2.33 订正了方案 §8.1 的「App 不回写后端」）。
     */
    LIMITLESS("Limitless", "settings_limitless_switch", SettingsAction.ToggleNsfw),
    ;

    /** 是否属于「账号与安全」的展开子项（折叠时不显示）。 */
    val isAccountSecurityChild: Boolean
        get() = this == SECURITY || this == BLOCKED || this == DELETE_ACCOUNT

    /**
     * 该行在当前渠道是否可见。
     *
     * @param isGooglePlay `BuildConfig.DOWNLOAD_CHANNEL == "GooglePlay"`
     * @param isDirectApk `== "APK"`（RN 的 `isAndroidAPK`）
     */
    fun visibleIn(isGooglePlay: Boolean, isDirectApk: Boolean): Boolean = when (this) {
        // 9 处 !isGooglePlay 中属于本表的这些（逐行对齐 page.tsx）
        SUBSCRIPTION, SECURITY, COMMUNITY_GUIDELINES, TERMS_OF_SERVICE, OFFICIAL_WEBSITE ->
            !isGooglePlay

        // shouldShowNsfwSetting(isAndroidAPK) —— 只有 directApk
        LIMITLESS -> isDirectApk

        else -> true
    }

    companion object {
        /** 顺序 = 现网列表行序，**不能调**（用户肌肉记忆 + 自动化脚本按序断言）。 */
        val ALL: List<SettingsRow> = entries.toList()

        /**
         * 按当前渠道与展开态给出实际行序。
         *
         * 折叠时**不渲染**三个子项（RN 是 `{accountSecurityExpanded && ...}`），
         * 不是渲染成禁用态。
         */
        fun visibleRows(
            isGooglePlay: Boolean = BuildConfig.DOWNLOAD_CHANNEL == CHANNEL_GOOGLE_PLAY,
            isDirectApk: Boolean = BuildConfig.DOWNLOAD_CHANNEL == CHANNEL_APK,
            accountSecurityExpanded: Boolean,
        ): List<SettingsRow> = ALL.filter { row ->
            row.visibleIn(isGooglePlay, isDirectApk) &&
                (accountSecurityExpanded || !row.isAccountSecurityChild)
        }

        /** `app/build.gradle` 的 googlePlay flavor 值。 */
        const val CHANNEL_GOOGLE_PLAY = "GooglePlay"

        /** directApk flavor 值 —— 对应 RN 的 `isAndroidAPK`。 */
        const val CHANNEL_APK = "APK"

    }
}

// ⚠️ 这三个常量必须在**文件作用域**，不能放 enum 的 companion object：
// enum 的 entries 先于 companion 初始化，entry 构造里引用 companion 的常量
// 会编译失败（"Companion object is uninitialized here"）。
// 三个 URL 逐字核实自 `settings/page.tsx:292,303,313`。
private const val URL_COMMUNITY_GUIDELINES = "https://tipsy.chat/community-guidelines"
private const val URL_TERMS = "https://tipsy.chat/terms-of-service"
private const val URL_HOME = "https://tipsy.chat/"

/**
 * 一行被点击时做什么。
 *
 * 分成这几类而不是都塞 lambda：**行是数据**（能被单测按渠道断言），
 * 行为的分派收在 Fragment 一处。
 */
sealed interface SettingsAction {
    /** 打开原生语言页。 */
    data object OpenLanguage : SettingsAction

    /** 展开/收起「账号与安全」—— 本地状态，不导航。 */
    data object ToggleAccountSecurity : SettingsAction

    /** 切换分级开关（`POST /user/nsfw`）。 */
    data object ToggleNsfw : SettingsAction

    /**
     * 订阅页。RN 走 `SubscriptionStack`，壳侧对应 `GemsSubscriptionSurface`
     * （未过 §9.1，点击被明确拒绝）。
     */
    data object Subscription : SettingsAction

    /**
     * `SettingsSurface` 的某个子屏。
     *
     * @param screen 必须是 `SettingsSurface.tsx:36-44` 的 `KNOWN_SCREENS` 之一 ——
     *   传别的值 RN 侧会**静默兜底到 `Feedback`**（`normalizeScreen`），
     *   表现为「点安全设置进了反馈页」。⚠️ `Language` **不在**那个白名单里
     *   （语言页原生，§2.33）
     */
    data class SurfaceScreen(val screen: AppRoute.SettingsSubScreen.Screen) : SettingsAction

    /** 外部链接（`WebBrowser.openBrowserAsync` / `Linking`）—— 不经 Surface。 */
    data class OpenUrl(val url: String) : SettingsAction
}
