package ai.lightspeed.tipsy.shell.surface

import ai.lightspeed.tipsy.shell.router.AppRoute

/**
 * 把 [AppRoute] 映射成目标 Surface 的**业务 props**（W1-CLOSEOUT-2）。
 *
 * ## 为什么要单独一层
 *
 * 壳侧的 route 是类型化的 sealed class，RN 侧的 props 是一组约定好的 key。
 * 中间这层映射是**唯一容易静默出错的地方** —— key 拼错、漏传必填参数，
 * 两边都不报错：
 * - RN 侧 props 是 TS 类型，但 initial props 来自原生，**运行期不校验**
 * - Kotlin 侧只知道自己放了什么，不知道 JS 要什么
 *
 * 所以映射集中在这里并配单测，而不是散在各导航调用点。
 *
 * ## props 名称必须逐字对齐 RN
 *
 * 每个常量都标了实测出处。**改名前先去 RN 侧确认** —— 这些是跨仓契约，
 * 壳单方面改名的表现是「参数没生效」而不是编译失败。
 *
 * ## ⚠️ 返回 `Map` 而不是 `Bundle`
 *
 * `android.os.Bundle` 在 JVM 单测里是**抛异常的 stub**（与 `Base64`/`Log`/`Uri` 同类，
 * `SurfaceContractTest` 已记过这条）。映射逻辑正是最该被单测覆盖的部分 ——
 * 返回纯 Kotlin `Map` 让它可测，转 `Bundle` 留在 [SurfaceContract] 里做。
 *
 * 用 `returnDefaultValues = true` 绕是方案 §5.4 点名的假绿色，本仓已三次拒绝。
 */
object SurfaceProps {

    // ── ChatDetailSurface（`src/surfaces/ChatDetailSurface.tsx:75-140`）──

    /** **必填**（`:75` 声明为 `characterId: string`，非可选）。 */
    const val CHAT_CHARACTER_ID = "characterId"

    /** 初始子屏。缺省 = `ChatDetailPage`（`:120-140` 的联合类型）。 */
    const val CHAT_INITIAL_SCREEN = "initialScreen"

    /** mini phone 聊天页的初始屏值（`:118` 注释：对齐 `toChatPage` 的 mini_phone 分支）。 */
    const val CHAT_SCREEN_MINI_PHONE = "MiniPhoneChat"

    /*
     * ── ChatDetail 的判定素材（P9）─────────────────────────
     *
     * ⚠️ **两个平铺 + 两个必须走 `preload`** —— 这不是风格选择，是实测的
     * 消费方差异（`ChatDetailSurface.tsx`，2026-08-17 逐行核实）：
     *
     * | 素材 | 消费方 | 形状 |
     * | --- | --- | --- |
     * | `chatEnterSource` | `props.chatEnterSource`（`:356`） | 平铺 |
     * | `isStory` | `props.isStory`（`:378`、`:424`） | 平铺 |
     * | `characterType` | **`preloadState.characterType`**（`:377`） | 嵌套 preload |
     * | `contentType` | **`preloadState.contentType`**（`:378`） | 嵌套 preload |
     *
     * `resolveInitialParams` 里 `props.characterType` / `props.contentType`
     * **全仓零命中** —— 它读的是 `getChatPreloadCache(id).getState()`，
     * 而那份 state 由 `seedChatPreloadFromShell(props)`（`:496`，在
     * `resolveInitialParams` 之前跑）从 **`props.preload`** 灌进去。
     *
     * 所以把 characterType 平铺在顶层的表现是：**html 富文本角色与多角色影院
     * 一律落到普通聊天页**，且两端都不报错 —— 正是本类注释警告的那类漂移。
     *
     * ⚠️ 壳**不传 `initialScreen`**（mini phone 除外）：`resolveInitialParams`
     * 会用这些素材自决，壳再传目标屏等于把分流复刻成两份（§2.30 纪律）。
     */

    /** 入口来源（`:77` `chatEnterSource?: ChatEnterSource`）。平铺。 */
    const val CHAT_ENTER_SOURCE = "chatEnterSource"

    /** story 标记（`:76` `isStory?: boolean`）。平铺。 */
    const val CHAT_IS_STORY = "isStory"

    /**
     * 壳侧列表数据子集（`:57` `ChatDetailSurfacePreload`）。**嵌套对象**。
     *
     * 声明了 14 个可选字段，壳当前只喂分流必需的两个 —— 其余
     * （nickname/imageUrl/imgPrimaryColor 等）是首帧背景优化，属独立包。
     * RN 侧对缺省字段有 `?? state` 逐字段保旧，少传不会抹掉已有值。
     */
    const val CHAT_PRELOAD = "preload"

    /** `preload.characterType`（`2` = 多角色）。 */
    const val PRELOAD_CHARACTER_TYPE = "characterType"

    /** `preload.contentType`（配合 `characterType == 1` 判 html 富文本）。 */
    const val PRELOAD_CONTENT_TYPE = "contentType"

    // ── 通用 ────────────────────────────────────────────────

    /** 用户 id。`openUserProfile` 的目标页属 W3，此处先留常量。 */
    const val USER_ID = "userId"

    /**
     * Follow 列表的类型：`followers` / `following`
     * （对齐 `FollowInfo.tsx:57,71` 传的 `type`）。
     */
    const val FOLLOW_TYPE = "type"

    /**
     * `SettingsSurface` 的初始子屏（`SettingsSurface.tsx:20` `initialScreen?`）。
     *
     * 取值范围是 `KNOWN_SCREENS` 那 7 个；**不含 `Language`**（语言页原生）。
     */
    const val SETTINGS_INITIAL_SCREEN = "initialScreen"

    /**
     * 把 route 转成业务 props。
     *
     * @return 业务参数；无参数的 route 返回**空 map**。
     *   空 map 与 null 在这里没有语义差别，返回空 map 让调用方少一层判空。
     *
     * ## 值类型是 `Any`，不是 `String`
     *
     * P9 起有三种非字符串值：`Boolean`（`isStory`）、`Int`
     * （`preload.characterType`）、嵌套 `Map`（`preload` 本身）。
     *
     * **不能都塞成字符串**：RN 侧按 `characterType === 1` / `=== 2` 严格比较
     * （`chat_mode_lru.ts:77,83`），`"1" === 1` 在 JS 里是 `false` ——
     * 传字符串的表现是分流恒落普通聊天页，**不报错**。
     * `isStory` 同理走 `?? false` 而不是真值判定，`"false"` 会被当成真。
     *
     * 转 `Bundle` 时按值类型分派，见 `SurfaceContract.buildInitialProps`。
     */
    fun forRoute(route: AppRoute): Map<String, Any> = when (route) {
        is AppRoute.ChatDetail -> buildMap {
            // characterId 是必填 prop，但 route 里可空（RN 侧该深链参数可选，
            // 进去后恢复上次会话）。
            //
            // 空白就不放，而不是放空串：RN 侧主要用 falsy 判定
            // （`if (!characterId)`，`:225`），所以空串与缺省**行为等价**；
            // 但不放能让「壳到底传了什么」在 props 里一目了然，
            // 排查时不必区分「传了空」与「没传」。
            // ⚠️ 例外：世界（SimulatorGame）入口**刻意传空串**（`:109` 注释），
            // 那条路径走 projectId，属 W4，届时不要套用这里的规则。
            route.characterId?.takeIf { it.isNotBlank() }?.let {
                put(CHAT_CHARACTER_ID, it)
            }

            // 入口来源：不传时 RN 侧 `?? 'home'`（`:356`），与发现页同义。
            // 壳侧四个入口各有值，所以正常不会走那个兜底。
            route.chatEnterSource?.takeIf { it.isNotBlank() }?.let {
                put(CHAT_ENTER_SOURCE, it)
            }

            // isStory 只在为真时放。false 与缺省在 RN 侧等价（`?? false`），
            // 少一个键让 props 更能反映「壳到底判定了什么」
            if (route.isStory) put(CHAT_IS_STORY, true)

            // ⚠️ 这两个**必须进嵌套 preload** —— resolveInitialParams 读的是
            // preload store，不是顶层 props（见上方对照表）。
            // 都没有就整个 preload 不放：空 preload 会让
            // seedChatPreloadFromShell 走到 `!preload` 提前返回，等价于不传，
            // 但少一个空对象更清楚
            val preload = buildMap<String, Any> {
                route.characterType?.let { put(PRELOAD_CHARACTER_TYPE, it) }
                route.contentType?.let { put(PRELOAD_CONTENT_TYPE, it) }
            }
            if (preload.isNotEmpty()) put(CHAT_PRELOAD, preload)
        }

        is AppRoute.MiniPhoneChat -> buildMap {
            route.characterId?.takeIf { it.isNotBlank() }?.let {
                put(CHAT_CHARACTER_ID, it)
            }
            // mini phone 与 ChatDetail 是**同一个 Surface 的不同初始屏**，
            // 不是两个 Surface（对齐 useChatNavigation.toChatPage 的分支）
            put(CHAT_INITIAL_SCREEN, CHAT_SCREEN_MINI_PHONE)
        }

        is AppRoute.UserProfile -> mapOf(USER_ID to route.userId)

        /*
         * Follow 列表需要 userId + type。
         *
         * ⚠️ 这两个 prop 现在**没有消费方** —— RN 侧不存在 FollowSurface
         * （已核实 `tipsy-app/src/surfaces/` 下无该文件），`follow.tsx` 是
         * ProfileStack 里的普通页面。这里先按 `FollowInfo.tsx:57,71` 的导航参数
         * 形状备好，真正启用时要么建 Surface、要么改成原生页（需 owner 定）。
         */
        is AppRoute.Follow -> mapOf(
            USER_ID to route.userId,
            FOLLOW_TYPE to route.type,
        )

        /*
         * `SettingsSurface` 的子屏（W3，§2.33）。
         *
         * prop 名 **`initialScreen`**，平铺（§2.19：13 个 Surface 无一读
         * `props.route`）。⚠️ 值必须是 `SettingsSurface.tsx:36-44` 的
         * `KNOWN_SCREENS` 之一 —— 传别的值 RN 会**静默兜底 `Feedback`**
         * （`normalizeScreen`），表现为「点安全设置进了反馈页」。
         *
         * ⚠️ `Language` **不在**那个白名单里（语言页原生，§2.33 订正）——
         * 别把语言页做成这个 route。
         */
        is AppRoute.SettingsSubScreen -> mapOf(SETTINGS_INITIAL_SCREEN to route.screen)

        // 其余 route 的目标页尚未启用（Router 会先拦下）。
        // **不写 else -> null**：加新 route 时编译器要强制我来这里想一次
        // 「它需要什么 props」，而不是静默传空
        //
        // Settings / EditProfile / UserCoins 都是无参入口：
        // - Settings 是原生列表（§8.1），根本不经 Surface props
        // - EditProfile 在 RN 侧是同页 Drawer，无路由参数
        // - UserCoins 页自己从 user store 取当前用户，不需要壳传 id
        //
        // ⚠️ Search 是**纯原生页**（W3），和 Settings 一样根本不经 Surface ——
        // 它在这里返回空 map 纯粹是为了满足穷尽性；真有人给它传 props
        // 说明走错了路径（原生 Fragment 不读 Surface props）
        is AppRoute.Search,
        is AppRoute.DailyGemEntry,
        is AppRoute.UserBalance,
        is AppRoute.Subscribe,
        is AppRoute.Letter,
        is AppRoute.CreateProfileDetail,
        is AppRoute.GemsPurchase,
        is AppRoute.Login,
        is AppRoute.Settings,
        is AppRoute.EditProfile,
        is AppRoute.UserCoins,
        -> emptyMap()
    }
}
