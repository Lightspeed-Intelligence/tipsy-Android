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
     */
    data class ChatDetail(val characterId: String? = null) : AppRoute {
        override val requiresAuth = true
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

    // ── Profile 的出口（方案 §8.1 记的 5 个，W3 起陆续启用）──────────
    //
    // ⚠️ 这些类型**现在都不在** `ProductionRoutePolicy.enabledRouteTypes` 里，
    // 点击会走 `navigator.rejectNotEnabled` 给出明确错误 —— 这是 §8.3 要求的
    // 「路由到未启用的 Surface 必须给出明确错误或安全 fallback，不做 silent no-op」。
    //
    // 定义它们而不是在 UI 里留 TODO：出口一旦有了类型，启用时只改
    // `ProductionRoutePolicy` 一处并更新矩阵测试，不必回头找散落的调用点。

    /**
     * 设置页。方案 §8.1 记为「设置→**原生列表**」（不是 Surface），
     * 但 Settings 列表本体属 W3 后续包（§1.3 归属表：Native / W3）。
     * 语言页仍在 `SettingsSurface`（刻意不迁，§8.1）。
     */
    data object Settings : AppRoute {
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
}
