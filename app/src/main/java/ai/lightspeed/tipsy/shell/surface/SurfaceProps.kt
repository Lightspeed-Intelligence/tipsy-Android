package ai.lightspeed.tipsy.shell.surface

import ai.lightspeed.tipsy.shell.router.AppRoute
import ai.lightspeed.tipsy.shell.router.ChatDetailPreload
import org.json.JSONObject

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

    /** Screen 头像/角色名直达角色详情（`ChatDetailSurface.tsx:310-314`）。 */
    const val CHAT_SCREEN_CHARACTER_DETAIL = "CharacterDetail"

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

    /** 壳侧列表数据子集（`:57` `ChatDetailSurfacePreload`）。**嵌套对象**。 */
    const val CHAT_PRELOAD = "preload"

    const val PRELOAD_NICKNAME = "nickname"
    const val PRELOAD_GENDER = "gender"
    const val PRELOAD_IMAGE_URL = "imageUrl"
    const val PRELOAD_FACE_URL = "faceUrl"
    const val PRELOAD_IMG_PRIMARY_COLOR = "imgPrimaryColor"
    const val PRELOAD_NSFW = "nsfw"
    const val PRELOAD_GREETING = "greeting"
    const val PRELOAD_INTRODUCTION = "introduction"
    const val PRELOAD_IS_TRANSLATED = "isTranslated"
    const val PRELOAD_LANG = "lang"

    /** `preload.characterType`（`2` = 多角色）。 */
    const val PRELOAD_CHARACTER_TYPE = "characterType"

    /** `preload.contentType`（配合 `characterType == 1` 判 html 富文本）。 */
    const val PRELOAD_CONTENT_TYPE = "contentType"

    const val PRELOAD_GREETING_VIDEO_URL = "greetingVideoUrl"
    const val PRELOAD_GREETING_VIDEO_COVER_URL = "greetingVideoCoverUrl"

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
     * `CreateSurface` 的进入来源（`CreateSurface.tsx:25` `createEnterSource?`）。
     *
     * 值经 `normalizeCharacterTriggerSource` 归一成埋点用的 `triggerSource`；
     * 不认识的值归一为 `null` 后被 `|| 'tab_bar_plus'` 兜底 —— 拼错的表现是
     * **归因静默串到 Tab 入口**。取值见 `AppRoute.CreateEnterSource`。
     */
    const val CREATE_ENTER_SOURCE = "createEnterSource"

    /**
     * `CreateSurface.tsx:29-31` 的编辑态双 prop：`editCharacter`（完整对象，
     * 首选）与 `editCharacterId`（有损兜底）。`isEdit = !!editCharacter ||
     * !!editCharacterId`（`:79`）—— 两个都不放才会落创建态。
     */
    const val EDIT_CHARACTER = "editCharacter"
    const val EDIT_CHARACTER_ID = "editCharacterId"

    /** `CommentsSurface.tsx:16-24` 的五个 props（camelCase；targetType 是 Int）。 */
    const val COMMENTS_TARGET_TYPE = "targetType"
    const val COMMENTS_TARGET_ID = "targetId"
    const val COMMENTS_CREATOR_ID = "creatorId"
    const val COMMENTS_COMMENT_ID = "commentId"
    const val COMMENTS_ROOT_ID = "rootId"

    /** `NotificationSurface.tsx:16-19` 的初始 tab prop。 */
    const val NOTIFICATION_TAB = "tab"

    /**
     * `GemsSubscriptionSurface.tsx:21-28` 的六个可选 props（camelCase）。
     * `initialTab` 由 RN `normalizeTab` 归一（`buy_gems` 之外全落
     * `subscription`）；`planId` 收字符串（RN `normalizePlanId` 自转数字）；
     * `preferNextPlan` 是 **boolean**（壳按 iOS boolParam 语义转型）。
     */
    const val GEMS_INITIAL_TAB = "initialTab"
    const val GEMS_PLAN_ID = "planId"
    const val GEMS_PREFER_NEXT_PLAN = "preferNextPlan"
    const val GEMS_SCROLL_INFO = "scrollInfo"
    const val GEMS_SOURCE_TYPE = "sourceType"
    const val GEMS_SOURCE_PAGE = "sourcePage"

    /** `RoleCardSurface.tsx:25` 的可选编辑 id（空 = Add New 新增分支）。 */
    const val ROLE_CARD_PROFILE_CARD_ID = "profileCardId"

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

            // ⚠️ 分流与首帧素材都**必须进嵌套 preload**。Screen 的 imageUrl
            // 会被 MultiCinema 用作 useState 初值；漏传时详情请求虽然后到，
            // 首次挂载仍会先显示黑底。都没有就整个 preload 不放：空 preload 会让
            // seedChatPreloadFromShell 走到 `!preload` 提前返回，等价于不传，
            // 但少一个空对象更清楚
            val preload = buildChatPreload(
                preload = route.preload,
                fallbackCharacterType = route.characterType,
                fallbackContentType = route.contentType,
            )
            if (preload.isNotEmpty()) put(CHAT_PRELOAD, preload)
        }

        is AppRoute.CharacterDetail -> buildMap {
            route.characterId.takeIf { it.isNotBlank() }?.let {
                put(CHAT_CHARACTER_ID, it)
            }
            put(CHAT_INITIAL_SCREEN, CHAT_SCREEN_CHARACTER_DETAIL)
            val preload = buildChatPreload(preload = route.preload)
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
        is AppRoute.SettingsSubScreen -> mapOf(
            SETTINGS_INITIAL_SCREEN to route.screen.rnName,
        )

        /*
         * `CreateSurface`（Tab3，W4）。**只有一个 prop**。
         *
         * ⚠️ 壳刻意**不传** `screen` / `from` / `triggerSource` / `operationType` ——
         * RN 侧 `TabNavigator.tsx:425` 那四个参数是完整 App 内跳
         * `CreateTabStack` 的形状，而 `CreateSurface` 自己就是那层容器：
         * 它按 `isEdit` 自决 `initialParams`（`CreateSurface.tsx:113-135`），
         * 并从 `createEnterSource` 推出 `triggerSource`。
         * 壳再传一份就是把分流复刻成两份（§2.30）。
         *
         * ⚠️ 编辑模式的 `editCharacter` / `editCharacterId` 属 Profile 创作卡片
         * 菜单那条入口，**不是本 route** —— 那条要透传整个角色对象
         * （原封喂 `initCharStateUpdate` 才不丢字段），届时单开 route。
         */
        is AppRoute.Create -> mapOf(CREATE_ENTER_SOURCE to route.enterSource)

        /*
         * `CommentsSurface`（W4 批次 3）。props 形状照 iOS 容器
         * `CommentsSurfaceViewController.swift:56-62`：targetType 是 **Int**
         * （Surface root 里 `String(props.targetType)` 归一进 route param），
         * commentId/rootId 仅有值时下发（缺省与不传等价，可选 props）。
         */
        is AppRoute.Comments -> buildMap {
            put(COMMENTS_TARGET_TYPE, route.targetType)
            put(COMMENTS_TARGET_ID, route.targetId)
            put(COMMENTS_CREATOR_ID, route.creatorId)
            route.commentId?.takeIf { it.isNotBlank() }?.let { put(COMMENTS_COMMENT_ID, it) }
            route.rootId?.takeIf { it.isNotBlank() }?.let { put(COMMENTS_ROOT_ID, it) }
        }

        /*
         * `CreateSurface` 编辑态（P5，Profile 创作卡 ⋮ 菜单「编辑」）。
         *
         * `editCharacter` 是**完整角色对象**（嵌套层原文经 JsonRouteParams
         * 结构转换）——CreateSurface 用 `initCharStateUpdate(editCharacter)`
         * 全量预填（`CreateSurface.tsx:88-92`）。解析失败退化成只传
         * `editCharacterId`：RN 走 `getCharacterAuth` 有损兜底（可能丢
         * `conversation_style` 等字段，但仍是编辑态），比整个不传落进
         * **创建态**（用户以为在编辑、实际新建了一个角色）安全得多。
         */
        is AppRoute.EditCharacter -> buildMap {
            val payload = route.characterJson?.let { json ->
                runCatching { JsonRouteParams.toParams(JSONObject(json)) }.getOrNull()
            }
            if (payload != null) put(EDIT_CHARACTER, payload)
            route.characterId?.takeIf { it.isNotBlank() }?.let {
                put(EDIT_CHARACTER_ID, it)
            }
        }

        // 其余 route 的目标页尚未启用（Router 会先拦下）。
        // **不写 else -> null**：加新 route 时编译器要强制我来这里想一次
        // 「它需要什么 props」，而不是静默传空
        //
        // Settings / EditProfile / UserCoins 都是无参入口：
        // - Settings 是原生列表（§8.1），根本不经 Surface props
        // - EditProfile 在 RN 侧是同页 Drawer，无路由参数
        // - UserCoins 页自己从 user store 取当前用户，不需要壳传 id
        /*
         * `NotificationSurface`（W4 批次 4）。唯一 prop `tab`
         * （`NotificationSurface.tsx:16-19`，System/Personal/Engagement），
         * RN 侧 `props.tab ?? 'System'` —— 缺省即 System，铃铛入口不传
         */
        is AppRoute.Letter -> buildMap {
            route.tab?.takeIf { it.isNotBlank() }?.let { put(NOTIFICATION_TAB, it) }
        }

        /*
         * `GemsSubscriptionSurface`（W4 批次 4）。六个可选 props
         * （`GemsSubscriptionSurface.tsx:21-28`，camelCase）—— route 的
         * params 来自两个入口：Profile 钱包卡（camelCase 固定键）与
         * 桥 `openGemsPurchase`（RN 调用方传什么是什么）。iOS 容器只认
         * camelCase 并做 snake_case 别名归一（`TipsyRouter.swift:349-363`），
         * 壳照做 —— 不归一的表现是宝石页任务入口带 snake 键时初始 tab 静默
         * 回落 subscription。空值键不放（iOS 逐个 isEmpty 判断同义）。
         */
        is AppRoute.GemsPurchase -> buildMap {
            fun aliased(camel: String, snake: String): String? =
                (route.params[camel] ?: route.params[snake])?.takeIf { it.isNotBlank() }
            aliased("initialTab", "initial_tab")?.let { put(GEMS_INITIAL_TAB, it) }
            aliased("planId", "plan_id")?.let { put(GEMS_PLAN_ID, it) }
            aliased("preferNextPlan", "prefer_next_plan")?.let {
                // iOS boolParam：1/true/yes（大小写不敏感）→ true。
                // RN 侧 props 是 boolean —— 传字符串 "false" 会被真值判定当 true
                put(GEMS_PREFER_NEXT_PLAN, it.lowercase() in setOf("1", "true", "yes"))
            }
            aliased("scrollInfo", "scroll_info")?.let { put(GEMS_SCROLL_INFO, it) }
            aliased("sourceType", "source_type")?.let { put(GEMS_SOURCE_TYPE, it) }
            aliased("sourcePage", "source_page")?.let { put(GEMS_SOURCE_PAGE, it) }
        }

        /*
         * `RoleCardSurface`（W4 批次 5）。唯一可选 prop `profileCardId`
         * （`RoleCardSurface.tsx:25`）：空 = 新增分支（Add New），
         * 非空 = 编辑分支（拉 getProfileCard 预填）。iOS 容器同义。
         */
        is AppRoute.RoleCard -> buildMap {
            route.profileCardId?.takeIf { it.isNotBlank() }?.let {
                put(ROLE_CARD_PROFILE_CARD_ID, it)
            }
        }

        //
        // ⚠️ Search 是**纯原生页**（W3），和 Settings 一样根本不经 Surface ——
        // 它在这里返回空 map 纯粹是为了满足穷尽性；真有人给它传 props
        // 说明走错了路径（原生 Fragment 不读 Surface props）
        is AppRoute.Search,
        is AppRoute.DailyGemEntry,
        is AppRoute.UserBalance,
        is AppRoute.Subscribe,
        is AppRoute.CreateProfileDetail,
        is AppRoute.Login,
        is AppRoute.Settings,
        is AppRoute.EditProfile,
        is AppRoute.UserCoins,
        -> emptyMap()
    }

    private fun buildChatPreload(
        preload: ChatDetailPreload?,
        fallbackCharacterType: Int? = null,
        fallbackContentType: Int? = null,
    ): Map<String, Any> = buildMap {
        preload?.nickname?.let { put(PRELOAD_NICKNAME, it) }
        preload?.gender?.let { put(PRELOAD_GENDER, it) }
        preload?.imageUrl?.let { put(PRELOAD_IMAGE_URL, it) }
        preload?.faceUrl?.let { put(PRELOAD_FACE_URL, it) }
        preload?.imgPrimaryColor?.let { put(PRELOAD_IMG_PRIMARY_COLOR, it) }
        preload?.nsfw?.let { put(PRELOAD_NSFW, it) }
        preload?.greeting?.let { put(PRELOAD_GREETING, it) }
        preload?.introduction?.let { put(PRELOAD_INTRODUCTION, it) }
        preload?.isTranslated?.let { put(PRELOAD_IS_TRANSLATED, it) }
        preload?.lang?.let { put(PRELOAD_LANG, it) }
        (preload?.characterType ?: fallbackCharacterType)?.let {
            put(PRELOAD_CHARACTER_TYPE, it)
        }
        (preload?.contentType ?: fallbackContentType)?.let {
            put(PRELOAD_CONTENT_TYPE, it)
        }
        preload?.greetingVideoUrl?.let { put(PRELOAD_GREETING_VIDEO_URL, it) }
        preload?.greetingVideoCoverUrl?.let { put(PRELOAD_GREETING_VIDEO_COVER_URL, it) }
    }
}
