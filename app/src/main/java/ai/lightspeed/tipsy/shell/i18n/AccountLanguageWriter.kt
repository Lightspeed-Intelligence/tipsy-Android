package ai.lightspeed.tipsy.shell.i18n

import org.json.JSONObject

/**
 * 把账号语言**回写**进 RN 的 `user-storage` 信封（进度文档 §2.37 的 FAIL 项）。
 *
 * ## 为什么必须有这个写入口（不是「为了对称」）
 *
 * 原生语言页（§2.33）只把语言存到服务端（`SettingsViewModel` 的
 * `api.setLanguage`），**从不回写 `user-storage`**。而壳自己会从那个信封读回
 * 语言并覆盖当前值（[AccountLanguageReader] ← `TipsyApplication
 * .refreshAccountLanguage()`）。于是：
 *
 * ```
 * 语言页选繁中 → 壳 UI 正确切换 → 开一次 RN Surface 再返回
 *   → MainActivity 的 back stack listener 调 refreshAccountLanguage()
 *   → 从信封读到**旧值** en → L10n.setLanguage("en") → 静默回退英文
 * ```
 *
 * 症状是「改了、看起来生效了、过一会儿又回去了」—— **用户不会报**。
 * §9.1 的「语言切换」列就是因为这条 FAIL（不是 ChatDetail 的问题）。
 *
 * 根因是读写方向不成对：壳读得对，但改动只落到服务端，没回写共享信封，
 * 于是被 RN 的旧值倒灌。回写这一侧后，读写闭合，倒灌源头消失。
 *
 * ## ⚠️ 为什么这里推翻了 [AccountLanguageReader] 写的「壳只读不写」
 *
 * 那条注释的前提是「语言设置页刻意不迁移，留在 `SettingsSurface` 里，
 * 仍由 RN 发 `/user/set_language`」。**该前提在 §2.33 语言页原生化之后
 * 已不成立** —— 现在壳是语言的唯一写入者，那么信封镜像也必须由壳维护。
 * 前提变了，结论跟着变；不是当初记错了。
 *
 * ## 只写 `languageCode` 一个字段
 *
 * RN 侧 `languageCode` 没有独立 setter，唯一写方是 `updateUserInfo()` 的
 * `languageCode: user.language_code || null`（`store/user.ts:187`）。
 * 壳镜像的就是这一个字段，**不顺手写别的** —— 同一信封里 `nsfw` 那类字段
 * 各有所有权（见 `HomeFilterStore` 的表）。
 *
 * ## 信封语义已核实（不是推测）
 *
 * `user-storage` 的 persist 配置**只有 `name` + `storage`**
 * （`store/user.ts:286-289`）：无 `version`、无 `migrate`、无 `partialize`、
 * 无 `merge`。三个直接结论：
 *
 * 1. **写 `version: 0` 是对的** —— zustand 默认 `version: 0`
 *    （`zustand@5` `middleware.js:333`）。
 * 2. **不会触发 migrate 分支** —— 那个分支要求 `version` 不等且配了
 *    `migrate`（`middleware.js:389`）。这正是 §2.23.1 性别筛选那条至今
 *    没定修法的顾虑，**在这个 key 上不存在**（那边 `config-persist-storage`
 *    确实配了自定义 `merge`）。
 * 3. **可以造只含一个字段的最小信封** —— 默认 merge 是浅展开
 *    `{...currentState, ...persistedState}`（`middleware.js:334-337`），
 *    缺的字段自然回落 store 默认值，不会变成 undefined。
 *
 * 故本类与 [mergeGenderIntoEnvelope] 有一处**刻意的行为差异**：那边信封
 * 缺失时 `return null` 不写（怕触发 migrate），**这里缺失时造信封**。
 * 对齐 iOS 的 `SharedMMKV.mergePersistState`（默认值就是
 * `["state": [:], "version": 0]`）。不造的后果是全新安装用户在 RN 初始化
 * 信封之前改语言永不生效 —— 与性别筛选那条一样的静默失效。
 *
 * ## 写完必须发 `onUserStoreChanged`
 *
 * 常驻 JS runtime 已经 hydrate 过这个 store，直接改 MMKV **它不会知道**。
 * `index.surfaces.js:72-76` 已有该监听（注释写明「壳每次 merge user-storage
 * 后通知长驻 JS runtime 重读」），桥方法 `notifyUserStoreChanged` 两端都在 ——
 * 只是 Android 壳此前从没调过。**这条链是现成的，不需要改 `tipsy-app`。**
 */
object AccountLanguageWriter {

    /**
     * 把语言码 merge 进信封，返回要写回的字符串。
     *
     * 抽成纯函数是为了单测（同 [mergeGenderIntoEnvelope] 的理由）：这是
     * 破坏性最大的一类写入 —— 整体覆盖会静默清掉用户昵称/头像/引导状态等
     * 二十多个字段，且**不报错**。必须能对着 JSON 逐条断言。
     *
     * @param raw 现有信封原文；null / 空 / 非 JSON 都当作「信封不存在」→ 造新的
     * @return 永不为 null（与 gender 那条不同，见类注释）
     */
    fun merge(raw: String?, languageCode: String): String {
        val envelope = parseEnvelope(raw) ?: JSONObject().put(ENVELOPE_VERSION, DEFAULT_VERSION)
        // state 缺失或类型不对都重建：宁可让 RN 的 merge 用默认值补齐，
        // 也不要写一个没有 state 层的信封（那样 rehydrate 直接拿不到东西）
        val state = envelope.optJSONObject(ENVELOPE_STATE) ?: JSONObject()
        // ⚠️ 只 put 这一个 key，见类注释
        state.put(FIELD_LANGUAGE_CODE, languageCode)
        envelope.put(ENVELOPE_STATE, state)
        // 造新信封时补 version；已有信封**保留它原来的值**不要改成 0 ——
        // 若将来 RN 给这个 store 加了 version，覆盖成 0 会反向触发 migrate
        if (!envelope.has(ENVELOPE_VERSION)) {
            envelope.put(ENVELOPE_VERSION, DEFAULT_VERSION)
        }
        return envelope.toString()
    }

    private fun parseEnvelope(raw: String?): JSONObject? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        return runCatching { JSONObject(trimmed) }.getOrNull()
    }

    private const val ENVELOPE_STATE = "state"
    private const val ENVELOPE_VERSION = "version"

    /** zustand 默认值（`middleware.js:333`），`user-storage` 未声明 version。 */
    private const val DEFAULT_VERSION = 0

    /** 同 [AccountLanguageReader] 读的字段：camelCase，不是接口的 snake_case。 */
    private const val FIELD_LANGUAGE_CODE = "languageCode"
}
