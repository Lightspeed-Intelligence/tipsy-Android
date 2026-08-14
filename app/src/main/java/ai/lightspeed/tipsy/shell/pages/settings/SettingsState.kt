package ai.lightspeed.tipsy.shell.pages.settings

/**
 * Settings 列表 + 语言页的状态（单个 data class 原子替换，同 `HomeState` 的理由）。
 *
 * 语言页是同一个 ViewModel 的一部分而不是独立 VM：两者共享
 * [supportedLanguages] 与「当前账号语言」这两份数据，拆开会让语言页每次
 * 打开都重拉一次列表（RN 侧那个列表在 store 里，跨页复用）。
 *
 * @property accountSecurityExpanded 「账号与安全」是否展开 —— **本地状态**，
 *   不是导航（`page.tsx:247`）。折叠时三个子项不渲染
 * @property nsfwEnabled 分级开关当前值。⚠️ 真值在后端 `user.nsfw`，
 *   这里是镜像；写入见 [SettingsViewModel.onNsfwToggle]
 * @property nsfwPending 分级开关请求在飞 —— 期间禁用，防连点产生反复写
 * @property languageError 语言列表拉取失败的文案。⚠️ **必须有** ——
 *   RN 拉不到时是永久 loading（`isLoading = languages.length === 0`），
 *   照抄那个会让用户对着转圈无从判断
 */
data class SettingsState(
    val accountSecurityExpanded: Boolean = false,
    val nsfwEnabled: Boolean = false,
    val nsfwPending: Boolean = false,

    // ── 语言页 ──────────────────────────────────────

    /** 服务端可选语言；空 = 还没拉到或拉取失败（看 [languageError] 区分）。 */
    val supportedLanguages: List<SupportedLanguage> = emptyList(),
    /** 已提交的语言（= 当前账号语言）。 */
    val selectedLanguage: String? = null,
    /**
     * 待提交的语言（点行只改这个，Done 才提交）。
     *
     * RN 的两段选择态（`useChangeLanguage.ts:16-18`）：点行改
     * `tempSelectedLanguage`，Done 时才写 `selectedLanguage` 并打接口。
     */
    val pendingLanguage: String? = null,
    val isLanguageLoading: Boolean = false,
    val languageError: String? = null,
) {
    /**
     * 语言页的 Done 是否可点（`language.tsx:24-27` `isDoneActive`）。
     *
     * 两者相等时**不可点** —— 不是点了无效果，是按钮本身不响应
     * （RN 的 `onDone` 开头 `if (!isDoneActive) return`）。
     */
    val isLanguageDoneEnabled: Boolean
        get() = pendingLanguage != null && pendingLanguage != selectedLanguage

    /** 当前渠道下应显示的行（含展开态过滤）。 */
    val visibleRows: List<SettingsRow>
        get() = SettingsRow.visibleRows(accountSecurityExpanded = accountSecurityExpanded)
}
