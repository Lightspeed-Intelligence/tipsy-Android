package ai.lightspeed.tipsy.shell.pages.settings

import ai.lightspeed.tipsy.shell.auth.Generations
import ai.lightspeed.tipsy.shell.i18n.L10n
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Settings 列表 + 语言页的编排（W3，进度文档 §2.33）。
 *
 * ## 语言页为什么必须是原生的
 *
 * 曾以为它在 `SettingsSurface` 里，**错**（§2.33 订正，三处证据）：
 * `KNOWN_SCREENS` 刻意不含 `Language`，`index.surfaces.js` 刻意不调
 * `hydrateSupportedLanguages`（注释：「消费页壳内为原生」），
 * iOS 侧也是原生 `LanguageViewController`。
 *
 * 直接后果：壳内 `config-persist.supportedLanguages` **恒为空**，
 * 列表必须壳自己拉（[loadSupportedLanguages]）。
 *
 * ## ⚠️ 语言与 nsfw 是**两种相反**的写入流，别抄混
 *
 * | | 语言 | nsfw（Limitless） |
 * | --- | --- | --- |
 * | 顺序 | **先本地切、再打接口** | **接口成功才写本地** |
 * | 失败 | 只 Toast，**不回滚** | 不写本地（等于自动回滚） |
 * | 出处 | `useChangeLanguage.ts:60-72` | `settings/page.tsx:76-81` |
 *
 * 语言写成「等接口成功再切」会有明显卡顿（RN 是点 Done 立即 goBack）；
 * nsfw 写成乐观更新会让失败后本地与后端不一致，而那是内容分级 —— 合规风险。
 */
class SettingsViewModel(
    private val api: SettingsSource,
    /** 应用语言到壳（生产传 `L10n::setLanguage`；注入是为了单测可断言）。 */
    private val applyLanguage: (String) -> Unit = { L10n.setLanguage(it) },
    /** 当前账号语言（生产读 `L10n.current`）。 */
    private val currentLanguage: () -> String = { L10n.current },
    /** auth 轨闸门（§4.4）—— 只校验 auth 轨，同他人主页的推理。 */
    private val generations: Generations,
    /** 注入是为了测试；生产用 viewModelScope。 */
    private val scope: CoroutineScope? = null,
    /** 注入而非直接调用：JVM 单测里 `android.util.Log` 是抛 "not mocked" 的桩。 */
    private val logWarn: (String, Throwable?) -> Unit = { msg, t -> Log.w(TAG, msg, t) },
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val coroutineScope: CoroutineScope get() = scope ?: viewModelScope

    private var languageJob: Job? = null
    private var nsfwJob: Job? = null

    /**
     * 进设置页时调。
     *
     * @param nsfwEnabled 当前 nsfw 镜像值（来自 `CurrentUser`，壳不自己拉）
     */
    fun onAppear(nsfwEnabled: Boolean) {
        _state.value = _state.value.copy(nsfwEnabled = nsfwEnabled)
    }

    /** 展开/收起「账号与安全」。纯本地，不发请求。 */
    fun onToggleAccountSecurity() {
        val s = _state.value
        _state.value = s.copy(accountSecurityExpanded = !s.accountSecurityExpanded)
    }

    // ── 语言页 ──────────────────────────────────────

    /**
     * 打开语言页时拉可选列表。
     *
     * 幂等：已有列表就不重拉（同一次会话内列表不会变）。
     *
     * 选中态初始值 = 当前壳语言。⚠️ 这里**不复刻 RN 的设备 locale 兜底**
     * （`useChangeLanguage.ts:31-33` 把 `zh` 开头映射成 `en`）—— 壳的
     * `L10n.current` 在启动时已由 `bootstrapI18n` 按正确规则定好
     * （账号码走 `normalize`、设备 locale 走 `fromDeviceLocale`，两条规则不同，
     * 见 `LanguageCodes` 类注释）。在这里再兜一次会用错那条规则。
     */
    fun onLanguagePageAppear() {
        val s = _state.value
        val current = currentLanguage()
        if (s.supportedLanguages.isNotEmpty()) {
            // 列表已有：只把待选态复位到当前语言（用户上次没提交就退出的残留要清）
            _state.value = s.copy(selectedLanguage = current, pendingLanguage = current)
            return
        }
        _state.value = s.copy(
            isLanguageLoading = true,
            languageError = null,
            selectedLanguage = current,
            pendingLanguage = current,
        )
        loadSupportedLanguages()
    }

    /** 点某一行语言 —— 只改待选态，不提交（RN 的 `handleLanguageSelect`）。 */
    fun onLanguageSelect(languageCode: String) {
        _state.value = _state.value.copy(pendingLanguage = languageCode)
    }

    /**
     * 点 Done。
     *
     * ## ⚠️ 先本地切、再打接口，失败**不回滚**
     *
     * 逐行对齐 RN（`language.tsx:29-40` + `useChangeLanguage.ts:60-72`）：
     * 点 Done → 立即 `goBack()`（由 Fragment 做）→ 后台
     * `i18n.changeLanguage` 本地切 → `POST /user/set_language` →
     * `updateUserInfo()` 重拉。失败只弹 `Save failed`，**本地语言已经切了不还原**。
     *
     * 写成「等接口成功再切」会让用户感到明显卡顿 —— 与现网体感不同。
     *
     * @return 是否真的提交了（false = Done 不可点，调用方不该关页面）
     */
    fun onLanguageDone(): Boolean {
        val s = _state.value
        val target = s.pendingLanguage
        if (!s.isLanguageDoneEnabled || target == null) return false

        // 1) 先本地切 —— 壳是语言唯一写入者，`setLanguage` 内部会广播给 RN
        //    （`onLanguageChanged`），Surface 侧 i18next 随之切
        _state.value = s.copy(selectedLanguage = target)
        applyLanguage(target)

        // 2) 后台保存
        val snapshot = generations.snapshot()
        languageJob?.cancel()
        languageJob = coroutineScope.launch {
            runCatching { api.setLanguage(target) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    // ⚠️ 换号后不写错误态：这次保存属于上一个账号
                    if (!generations.isAuthValid(snapshot)) return@launch
                    logWarn("保存语言失败（本地语言已切，不回滚）", error)
                    _state.value = _state.value.copy(languageError = SAVE_FAILED_KEY)
                }
        }
        return true
    }

    /** 清掉一次性的错误提示（Toast 弹过之后）。 */
    fun onLanguageErrorShown() {
        _state.value = _state.value.copy(languageError = null)
    }

    private fun loadSupportedLanguages() {
        val snapshot = generations.snapshot()
        coroutineScope.launch {
            val list = runCatching { api.fetchSupportedLanguages() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    logWarn("拉取 /supported_languages 失败", error)
                }
                .getOrNull()
            if (!generations.isAuthValid(snapshot)) return@launch
            _state.value = _state.value.copy(
                // ⚠️ 空列表也算失败：RN 那边表现是永久 loading，壳要给错误态
                //（列表为空时页面没有任何可点项，不给提示等于死页面）
                supportedLanguages = list.orEmpty(),
                isLanguageLoading = false,
                languageError = if (list.isNullOrEmpty()) LOAD_FAILED_KEY else null,
            )
        }
    }

    // ── 分级开关 ────────────────────────────────────

    /**
     * 切分级开关（Limitless）。
     *
     * ## ⚠️ 接口成功**才**写本地 —— 与语言页刻意相反
     *
     * RN 的顺序（`settings/page.tsx:76-81`）：`await updateUserNsfw(next)` →
     * `setNsfw(next)` → `hydrateTags()`。**不是乐观更新**：内容分级写错方向
     * 是合规风险，本地显示"已开"而后端仍关（或反之）都不可接受。
     *
     * `hydrateTags` 由 RN 侧持有（标签目录随分级变化），壳不复刻 ——
     * 壳的 Home 标签行走自己那条链。
     *
     * 连点防护：[SettingsState.nsfwPending] 期间直接返回。
     */
    fun onNsfwToggle() {
        val s = _state.value
        if (s.nsfwPending) return
        val next = !s.nsfwEnabled
        _state.value = s.copy(nsfwPending = true)
        val snapshot = generations.snapshot()
        nsfwJob?.cancel()
        nsfwJob = coroutineScope.launch {
            val ok = runCatching { api.setNsfw(next) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    logWarn("写 /user/nsfw 失败，不改本地值", error)
                }
                .isSuccess
            if (!generations.isAuthValid(snapshot)) return@launch
            _state.value = _state.value.copy(
                // 只有成功才写 —— 失败保持原值（等于自动回滚）
                nsfwEnabled = if (ok) next else _state.value.nsfwEnabled,
                nsfwPending = false,
                languageError = if (ok) _state.value.languageError else SAVE_FAILED_KEY,
            )
        }
    }

    /**
     * 登录态变化（登录与登出**同样处理**，故不带 `loggedIn` 参数）。
     *
     * 两边都是：取消在飞请求 + 把选中态复位到壳当前语言 + 清一次性错误。
     * 理由：
     * - **登出**时设置页会被 Activity 关掉（登录页盖上来），但 ViewModel 可能
     *   还活着；在飞的 `set_language` / `nsfw` 属上一个账号，必须取消。
     * - **换号**时新账号的语言可能不同，`L10n.current` 已由 auth 链更新，
     *   选中态要跟着走 —— 不复位会让语言页高亮在上一个账号的语言上。
     *
     * ⚠️ **不清 [SettingsState.supportedLanguages]**：那是**公共数据**
     * （OPPORTUNISTIC 端点，与账号无关），清了只是下次进来白拉一次。
     * ⚠️ **不清 [SettingsState.nsfwEnabled]**：它由 [onAppear] 从
     * `CurrentUser` 灌入，而那份数据的清理是 `CurrentUserStore` 的职责。
     */
    fun onAuthChanged() {
        languageJob?.cancel()
        nsfwJob?.cancel()
        val current = currentLanguage()
        _state.value = _state.value.copy(
            nsfwPending = false,
            selectedLanguage = current,
            pendingLanguage = current,
            languageError = null,
        )
    }

    companion object {
        private const val TAG = "SettingsViewModel"

        /** i18n key（key = 英文原文），对齐 `language.tsx:37` 的 Toast 文案。 */
        const val SAVE_FAILED_KEY = "Save failed"

        /**
         * 语言列表拉取失败的文案。
         *
         * 复用既有 key（已在 SHELL_KEYS，Profile / Home 都在用），
         * 避免为一个错误态新增词条。
         */
        const val LOAD_FAILED_KEY = "Please try again later"
    }
}
