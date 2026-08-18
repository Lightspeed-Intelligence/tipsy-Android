package ai.lightspeed.tipsy.shell.router

/**
 * 壳内所有可导航目标的**类型化**表示（W1-P4，方案 §4.7）。
 *
 * ## 为什么用 sealed class 而不是 String
 *
 * 方案要求「单一入口 + typed parser」：所有来源（Intent / Push / Widget /
 * Compose 点击 / RN 桥）都先解析成 [AppRoute]，再交给 Router 统一处理
 * auth gate、去重、source attribution。
 *
 * 用字符串会让「路由拼错」变成运行期静默失败 —— 而 §8.3 的纪律是
 * **路由到未启用目标必须给明确错误或安全兜底，绝不静默 no-op**。
 * sealed class 让新增目标时编译器强制处理所有分支。
 *
 * ## 七条外部深链（实测 `src/App.tsx:445-465`）
 *
 * RN 侧 `linking.config.screens` 声明的路径就是这七条，壳必须逐条对齐 ——
 * 少一条那个入口就打不开，多一条则壳能进而 RN 进不去，两侧行为分叉。
 */
sealed interface AppRoute {

    /** 该路由是否要求已登录。未登录时 Router 会先排队、登录后**恰好执行一次**。 */
    val requiresAuth: Boolean

    // ── 七条外部深链（tipsy:// 与 push / widget 共用）─────────────

    /** `profile/daily-gem-entry` —— 每日宝石入口。 */
    data object DailyGemEntry : AppRoute {
        override val requiresAuth = true
    }

    /** `profile/user-balance` —— 钱包余额。 */
    data object UserBalance : AppRoute {
        override val requiresAuth = true
    }

    /** `subscribe/page` —— 订阅页。 */
    data object Subscribe : AppRoute {
        override val requiresAuth = true
    }

    /**
     * `chat/detail` —— 聊天详情。W1-P9 的 gate 对象。
     *
     * `characterId` 可空：RN 侧该路由的参数是可选的（进去后恢复上次会话）。
     *
     * ## 其余三个参数是**判定素材**，不是目标屏（P9）
     *
     * 壳**刻意不复刻** `resolveChatEntryMode` / `resolveChatEntryScreen` 的分流：
     * 只把素材透传给 `ChatDetailSurface`，由它挂载时 `resolveInitialParams`
     * 自决初始屏（§2.30 定的纪律，Screen 那刀也照此办）。
     *
     * ⚠️ 这与 Screen 的 RN 代码是**有意偏差**：`screen.tsx:655-704` 是在
     * 页面内做四路分流的。壳侧仍按 ChatList 那条做 —— 分流逻辑存在两份
     * 就一定会漂移，而漂移的表现是「同一个角色从不同入口进去落在不同屏」。
     *
     * 参数形状对齐 `useChatNavigation.ts:91-105` 壳分支的 `bridgeParams`
     * （那是 RN 侧自己经桥转发时用的形状，跟着它走天然对等）。
     *
     * @param chatEnterSource 入口来源。影响影院 `sourceType` 与入口模式判定
     *   （`big_screen` → `first_tab`，其余 → `chat_list`）。
     * @param isStory story 恒普通聊天页，优先级高于影院分流。
     * @param characterType `2` = 多角色。**缺失不等于 1** —— RN 按
     *   `resolvedCharacterType !== 2` 判，缺失时靠 `interactive.tsx`
     *   on-mount 兜底纠偏，所以壳传 null 是安全的。
     * @param contentType 与 `characterType == 1` 合起来判 html 富文本
     *   （`1 + 2` → `ChatDetailHtml`）。
     */
    data class ChatDetail(
        val characterId: String? = null,
        val chatEnterSource: String? = null,
        val isStory: Boolean = false,
        val characterType: Int? = null,
        val contentType: Int? = null,
    ) : AppRoute {
        override val requiresAuth = true
    }

    /**
     * `chatEnterSource` 的取值（`types/chat.ts` 的 `ChatEnterSource`）。
     *
     * 壳侧只用得到这几个 —— 每个原生列表页各对应一个入口。
     * ⚠️ **值是跨仓契约**，RN 侧按字符串比对（`useChatNavigation.ts:59`
     * 用 `=== 'big_screen'` 决定影院 sourceType），拼错不报错、只是走错分支。
     */
    object ChatEnterSource {
        /** Screen 大屏页 CTA（`screen.tsx`）—— 影院 sourceType 走 `first_tab`。 */
        const val BIG_SCREEN = "big_screen"

        /** 聊天列表会话行。 */
        const val CHAT_LIST = "chat_list"

        /**
         * 发现页卡片 —— RN 侧固定 normal 模式，不读角色 LRU。
         *
         * ⚠️ **搜索结果也用这个值**，不是 `"search"`：搜索页复用
         * `HomeCard`（`CharacterResultList.tsx:88`），而它硬编码传 `'home'`
         * （`HomeCard.tsx:178`）。`ChatEnterSource` 联合类型里根本没有
         * `search`（`navigation/type.ts:21-26` 只有 home / big_screen /
         * chat_list / profile / unknown）—— 传个不存在的值不报错，
         * 只是入口模式判定会落到 else 分支。
         */
        const val HOME = "home"

        /** 他人主页的角色卡（W3 已实现，但那里的卡片点击尚未接 ChatDetail）。 */
        const val PROFILE = "profile"
    }

    /** `chat/mini-phone` —— mini phone 聊天。 */
    data class MiniPhoneChat(val characterId: String? = null) : AppRoute {
        override val requiresAuth = true
    }

    /** `chat/letter` —— 站内信（落在 `NotificationSurface`，W4 启用）。 */
    data object Letter : AppRoute {
        override val requiresAuth = true
    }

    /** `create/profile-detail` —— 创建流程里的角色详情。 */
    data class CreateProfileDetail(val characterId: String? = null) : AppRoute {
        override val requiresAuth = true
    }

    // ── 壳内目标（桥调用触达，非深链）────────────────────────────

    /**
     * 用户主页。**self / others 的分流由 Router 集中判定**（§6.5）——
     * 不在各调用点各判一次，iOS 在这里踩过「关注自己」。
     *
     * @param recommendationContextJSON 推荐归因上下文，来自
     *   `openUserProfileWithRecommendation`；null 表示无归因。
     */
    data class UserProfile(
        val userId: String,
        val recommendationContextJSON: String? = null,
    ) : AppRoute {
        // 看他人主页不要求登录（游客可浏览），与 RN 侧一致
        override val requiresAuth = false
    }

    /** 宝石购买 / 订阅（`GemsSubscriptionSurface`，W4 启用）。 */
    data class GemsPurchase(val params: Map<String, String> = emptyMap()) : AppRoute {
        override val requiresAuth = true
    }

    /**
     * 角色创建流程 → `CreateSurface`（Tab3 伪 Tab 的目标，W4）。
     *
     * ## 壳**只传入口来源**，不传目标屏与 triggerSource
     *
     * RN 侧 `TabNavigator.tsx:425-430` 的 tabPress 里带了四个参数
     * （`screen: 'ProfileDetail'` + `from` / `triggerSource` / `operationType`），
     * 但那是**完整 App 内**跳 `CreateTabStack` 用的形状。壳侧不复刻：
     * `CreateSurface` 自己就是那层微容器，它按 `isEdit` 自决落地页与参数
     * （`CreateSurface.tsx:113-135` 的 `initialParams`），并把
     * `createEnterSource` 过一遍 `normalizeCharacterTriggerSource` 得出
     * `triggerSource`。
     *
     * 壳再传一份的表现是「同一个入口在两处各判一次」——§2.30 已定的纪律
     * （ChatDetail 那刀同理：只传素材，分流留给 Surface）。
     *
     * @param enterSource 进入来源，进 `createEnterSource` prop。
     *   ⚠️ 取值必须落在 `normalizeCharacterTriggerSource`
     *   （`characterCreateAnalytics.ts:106-122`）认识的集合里 ——
     *   不认识的值返回 `null`，Surface 会兜底成 `tab_bar_plus`，
     *   表现是**埋点归因串到 Tab 入口**而不报错。见 [CreateEnterSource]。
     */
    data class Create(
        val enterSource: String = CreateEnterSource.TAB_BAR_PLUS,
    ) : AppRoute {
        override val requiresAuth = true
    }

    /**
     * `createEnterSource` 的取值（`normalizeCharacterTriggerSource` 认识的那些）。
     *
     * ⚠️ **跨仓契约**，RN 侧按字符串比对后归一到三个埋点值:
     * `tab_bar_plus` / `draft_box` / `cha_edit`。拼错不报错,只是归一失败
     * 落到 Surface 的 `|| 'tab_bar_plus'` 兜底。
     */
    object CreateEnterSource {
        /** Tab3 的 ➕ —— 对齐 RN tabPress 传的 `triggerSource: 'tab_bar_plus'`。 */
        const val TAB_BAR_PLUS = "tab_bar_plus"

        /** 草稿箱入口（壳内尚无该入口，值先备好）。 */
        const val DRAFT_BOX = "draft_box"
    }

    // ── Profile 的出口（方案 §8.1 记的 5 个，W3 起陆续启用）──────────
    //
    // ⚠️ 这些类型**现在都不在** `ProductionRoutePolicy.enabledRouteTypes` 里，
    // 点击会走 `navigator.rejectNotEnabled` 给出明确错误 —— 这是 §8.3 要求的
    // 「路由到未启用的 Surface 必须给出明确错误或安全 fallback，不做 silent no-op」。
    //
    // 定义它们而不是在 UI 里留 TODO：出口一旦有了类型，启用时只改
    // `ProductionRoutePolicy` 一处并更新矩阵测试，不必回头找散落的调用点。

    /**
     * 设置列表本体 —— **原生页**（方案 §8.1「设置→原生列表」、§1.3 归属表
     * `Native / W3`）。W3 已实现（进度文档 §2.33），在生产白名单里。
     *
     * ⚠️ 它的 **7 个子屏走 [SettingsSubScreen]**，那些未过 §9.1。
     * ⚠️ **语言页也是原生**，由 Settings 压栈打开、不是路由目标 ——
     * §2.33 订正了「语言页仍在 SettingsSurface」那句错话：
     * `SettingsSurface.tsx:34-44` 的 `KNOWN_SCREENS` 刻意不含 `Language`。
     */
    data object Settings : AppRoute {
        override val requiresAuth = true
    }

    /**
     * `SettingsSurface` 的某个子屏（W3 定义、**未启用**）。
     *
     * 7 个可达屏由 RN 的 `KNOWN_SCREENS` 定义（`SettingsSurface.tsx:36-44`）：
     * Security / Blacklist / Feedback / About / ContactUs / Delete / Widget。
     *
     * ⚠️ [screen] 传白名单外的值时 RN 会**静默兜底到 `Feedback`**
     * （`normalizeScreen`），表现为「点安全设置进了反馈页」。所以壳侧
     * 只允许传那 7 个值之一（`SettingsRow` 里已按行写死）。
     *
     * @param screen 子屏名，进 Surface 的平铺 prop `initialScreen`
     */
    data class SettingsSubScreen(val screen: String) : AppRoute {
        override val requiresAuth = true
    }

    /**
     * 编辑资料 → `EditProfileSurface`。
     *
     * ⚠️ RN 侧它**不是路由而是同页 Drawer**（`EditProfileDrawer` 654 行，
     * `setUserProfileOpen(true)`）。壳侧按 Surface 出口建模 —— §1.3 已定
     * 「EditProfile 是 RN Surface，iOS 迁移后回撤，直接继承」。
     *
     * ⚠️ 该 Surface 在 W3 还是 W4 过 §9.1 矩阵，**方案自相矛盾**：
     * §8.3 批次表列在 W3，§9.1 矩阵把「其余 10 个」标 W4。需 owner 定。
     */
    data object EditProfile : AppRoute {
        override val requiresAuth = true
    }

    /**
     * 粉丝 / 关注列表（`app/profile/follow.tsx` 445 行）。
     *
     * ⚠️ **RN 侧没有对应的 Surface**（已核实 `tipsy-app/src/surfaces/` 下无
     * FollowSurface）。方案 §8.1 把 `follow` 列进「不迁、走 Surface」的 5.2k 里，
     * 但那个 Surface 并不存在 —— 要么新建，要么原生实现。需 owner 定。
     *
     * @param type `followers` / `following`（对齐 `FollowInfo.tsx:57,71` 的参数）
     */
    data class Follow(val userId: String, val type: String) : AppRoute {
        override val requiresAuth = true
    }

    /** 金币页 → `UserCoinsSurface`（1304 行，§8.3 批次 4 = W4）。 */
    data object UserCoins : AppRoute {
        override val requiresAuth = true
    }

    /** 登录页（W2 的原生 Login）。**它自己当然不要求登录。** */
    data class Login(val reason: String? = null) : AppRoute {
        override val requiresAuth = false
    }

    /**
     * 搜索页（W3 原生，`app/search/page.tsx`）。
     *
     * **游客可用**：六个端点里四个走 `axiosPublic`（`OPPORTUNISTIC`），
     * 未登录能正常搜索，只是最近搜索为空（那两个是 `REQUIRED`）。
     * 写成 `requiresAuth = true` 会把游客搜索挡在登录页后面 —— RN 侧没有这个门。
     */
    data object Search : AppRoute {
        override val requiresAuth = false
    }
}
