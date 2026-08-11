package ai.lightspeed.tipsy.shell.i18n

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 原生页 i18n（W1-P5，方案 §4.8）。**壳是语言的唯一 writer。**
 *
 * - key = **英文原文**，与 RN i18next 的 key 体系完全一致
 * - 译文来自 `assets/locales/<code>.json`（由 `tipsy-app` 的
 *   `export-shell-locales.mjs` 抽取，**双壳共用同一份脚本与 keys 清单**）
 * - fallback 链：**当前语言 → en → key**（方案 §4.8）
 * - 语言解析规则见 [LanguageCodes]，**两条规则**，别只实现一条
 *
 * ## 线程模型
 *
 * `t()` / [current] 任意线程可读 —— 调用方包括 UI 主线程、桥线程
 * （`getCurrentLanguageCode`）、后台请求线程（取 `language_code` 参数）。
 * 用 `@Volatile` 持不可变快照而不是加锁：读远多于写（每帧每个文案一次），
 * 写只发生在语言切换。iOS 用 `NSLock`，Android 这里用不可变引用交换更省。
 *
 * [setLanguage] 无线程要求 —— 状态更新是原子的，UI 刷新由 [languageFlow] 驱动。
 *
 * ## ⚠️ 语言变化的广播必须收口在这里
 *
 * 所有改变语言的路径（设置页切换 / 登录后按 `user.language_code` 覆盖 /
 * 启动恢复）都要经 [setLanguage]，它统一发桥事件。散落在各调用点会让
 * 「运行中的 Surface 语言不同步」只在某些路径下发生 —— iOS 把桥事件收在
 * `L10n.setLanguage` 里正是这个理由（`L10n.swift:96-98`）。
 */
object L10n {

    /** 加载某语言的词条表。缺失返回 null（调用方回退，不抛）。 */
    fun interface TableLoader {
        fun load(languageCode: String): LocaleTable?
    }

    /** 语言变化时通知外部（桥广播 / 埋点）。**不要在这里直接依赖 registry** —— 那样无法单测。 */
    fun interface ChangeListener {
        fun onLanguageChanged(languageCode: String)
    }

    private val _languageFlow = MutableStateFlow(LanguageCodes.FALLBACK)

    /**
     * 当前语言的可观察状态。**Compose 文案组件订阅它**（见 `LocalizedText`）——
     * 方案 §4.8 要求「提供自订阅语言变更的组件，不让每个页面手挂监听」。
     */
    val languageFlow: StateFlow<String> = _languageFlow.asStateFlow()

    /** 当前语言码（已 normalize）。任意线程可读。 */
    val current: String get() = _languageFlow.value

    @Volatile
    private var table: LocaleTable = LocaleTable.EMPTY

    /**
     * 英文表。**单独常驻**：它是 fallback 链的中间一环，
     * 切到任何语言都要能立刻回退到它，不该反复加载。
     */
    @Volatile
    private var enTable: LocaleTable = LocaleTable.EMPTY

    @Volatile
    private var loader: TableLoader? = null

    @Volatile
    private var listener: ChangeListener? = null

    @Volatile
    private var logger: ((String) -> Unit)? = null

    /**
     * 装配（由 `TipsyApplication.onCreate` 调用）。
     *
     * @param initialLanguage 启动语言。调用方按两阶段传：先设备 locale
     *   （[LanguageCodes.fromDeviceLocale]），拿到 user 后再按
     *   `user.language_code` 覆盖 —— 对齐 RN 的两段式初始化（方案 §4.6）。
     */
    fun bootstrap(
        loader: TableLoader,
        initialLanguage: String,
        listener: ChangeListener? = null,
        logger: ((String) -> Unit)? = null,
    ) {
        this.loader = loader
        this.listener = listener
        this.logger = logger
        enTable = loader.load(LanguageCodes.FALLBACK) ?: LocaleTable.EMPTY
        if (enTable.size == 0) {
            // 英文表缺失意味着 fallback 链只剩 key。可运行但所有 key≠value 的
            // 词条会显示错文案 —— 必须可见，否则只会表现为「部分文案怪怪的」
            logger?.invoke("[L10n] 英文词条表缺失或为空，fallback 链已退化到 key")
        }
        applyLanguage(initialLanguage, notify = false)
    }

    /**
     * 查词条。fallback 链：当前语言 → en → key。
     *
     * 返回 key 本身是**最后兜底**（1838 个 key 里 1744 个 key==value，
     * 所以兜底通常看起来正常 —— 这也是漏词条难被发现的原因）。
     */
    fun t(key: String): String = table[key] ?: enTable[key] ?: key

    /**
     * 带插值的查词条。RN 侧用 `{{name}}` 语法（实测 66 个 key 含插值）。
     *
     * 只做字面量替换，不实现复数/格式化 —— RN 侧那 66 个 key 也只用到替换。
     * 需要更多能力时再加，别提前造抽象。
     */
    fun t(key: String, args: Map<String, String>): String {
        var result = t(key)
        for ((name, value) in args) {
            result = result.replace("{{$name}}", value)
        }
        return result
    }

    /**
     * 切换语言。**所有语言变化路径都要经这里**（见类注释）。
     *
     * @param rawCode 未规范化的码（账号 `language_code` 或桥传入值）；
     *   内部走 [LanguageCodes.normalize]。设备 locale 请先自行走
     *   [LanguageCodes.fromDeviceLocale]，两条规则不同。
     */
    fun setLanguage(rawCode: String?) {
        applyLanguage(LanguageCodes.normalize(rawCode), notify = true)
    }

    private fun applyLanguage(normalizedCode: String, notify: Boolean) {
        // 已是该语言且表非空 → 不重复加载、不重复广播。
        // 重复广播会让 RN 侧 i18next.changeLanguage 被无谓调用，
        // 且订阅方（Compose 组件）无谓重组。
        if (normalizedCode == current && table.size > 0) return

        table = if (normalizedCode == LanguageCodes.FALLBACK) {
            enTable
        } else {
            loader?.load(normalizedCode) ?: LocaleTable.EMPTY
        }
        if (table.size == 0 && normalizedCode != LanguageCodes.FALLBACK) {
            // 该语言无资源 → 静默回退英文是**正确行为**（方案 §4.8「未知/无资源
            // code 安全 fallback」），但必须留诊断：否则新增语言忘了跑导出脚本时
            // 表现为「选了语言但还是英文」，无从判断
            logger?.invoke("[L10n] 词条表缺失：$normalizedCode（已回退英文表）")
        }
        _languageFlow.value = normalizedCode
        if (notify) listener?.onLanguageChanged(normalizedCode)
    }

    /** 仅供测试重置全局状态。 */
    internal fun resetForTest() {
        loader = null
        listener = null
        logger = null
        table = LocaleTable.EMPTY
        enTable = LocaleTable.EMPTY
        _languageFlow.value = LanguageCodes.FALLBACK
    }
}
