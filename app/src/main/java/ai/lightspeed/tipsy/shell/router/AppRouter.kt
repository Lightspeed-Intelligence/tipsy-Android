package ai.lightspeed.tipsy.shell.router

import ai.lightspeed.tipsy.shell.auth.AuthStateHub

/** 当前 binary 的生产路由白名单；未过验收矩阵的目标一律不进入。 */
object ProductionRoutePolicy {
    /**
     * 启用的目标必须集中改这里并更新矩阵测试（`AppRouterTest`）。
     *
     * ## 为什么 [AppRoute.Search] 与 [AppRoute.UserProfile] 可以进，而其它目标还不行
     *
     * §9.1 的验收矩阵管的是 **RN Surface** —— 那些项检查的是 Surface 生命周期
     * （挂载/卸载/返回键/桥事件迟到），风险来自 RN 与原生的边界。
     * 这两个都是**纯原生 Fragment**，不开 Surface、不走桥，那套矩阵对它们不适用；
     * 验收是壳自己的单测 + 冒烟（同 W2 的原生 Login —— Login 走的是
     * `Navigator.requestLogin` 专用口，所以没出现在这个集合里）。
     *
     * `UserProfile` = 他人主页（W3，进度文档 §2.32）。它是**第一个被真实打通的
     * 卡片出口** —— Search 的创作者点击此前恒被拒绝。
     *
     * `Settings` = 设置列表（W3，§2.33）。⚠️ 放开的是**壳的原生列表本体**，
     * 不是它的 7 个子屏 —— 那些走 `SettingsSurface`，未过 §9.1，
     * 点击仍会被拒绝（列表内部再走一次 Router）。
     * 语言页由 Settings 压栈打开，**不是独立路由目标**（RN 侧也没有深链到它）。
     *
     * ## `ChatDetail` / `MiniPhoneChat` 是第一批进来的 **RN Surface**（P9）
     *
     * 它们与上面三个不同轴 —— 走的是 `ChatDetailSurface`，所以确实要过 §9.1。
     * 放开的依据（进度文档 §2.36）：
     * - 微根 18 项已逐行核对 `ChatDetailSurface.tsx:546-631`，顺序一致
     * - 5 个微栈目标已枚举，均在栈内（不存在 `RoleCardSurface` 那种死链）
     * - 三个过期桥桩已回填（`openUserProfile` 系在深栈有三个调用点，
     *   留 `notImplemented` 会让「点头像」debug 崩）
     * - 判定素材经 route 透传且形状有单测（`preload` 嵌套那条尤其容易写错）
     *
     * ⚠️ §12 **实例关闭链仍是已接受偏差**：TS 侧 `popSurface()` 无参，
     * Android 桥固定传 `null`，所以 `MainActivity.popSurface` 的实例比对
     * 恒短路成「栈里有就 pop」。当前只有单层 Surface 容器，弹不错；
     * 真出现多层（如 ChatDetail 内再开 Comments）之前必须根治。
     *
     * ⚠️ 别据此推论「原生页都能随便加」：加任何目标都要先有对应的单测与冒烟，
     * 且这里与 `ShellNavigator.navigate` 的分支必须同时更新 ——
     * 只加白名单不加分支会走到 `error()`（刻意不做 silent no-op）。
     */
    val enabledRouteTypes: Set<Class<out AppRoute>> = setOf(
        AppRoute.Search::class.java,
        AppRoute.UserProfile::class.java,
        AppRoute.Settings::class.java,
        AppRoute.ChatDetail::class.java,
        AppRoute.MiniPhoneChat::class.java,
        // Tab3 创建入口（W4）。CreateSurface 在 RN 侧已注册
        //（`index.surfaces.js:136`），且 §9.1 的单层容器/popSurface 收口
        // 与 ChatDetail 同一条链
        AppRoute.Create::class.java,
        // P5 编辑入口：**同一个 CreateSurface 容器**的编辑态（props 带
        // `editCharacter` 全量对象）。容器 gate 复用 Create 那行；props 形状
        // 由 SurfacePropsTest + SurfaceContractTest 钉住。编辑保真（原始 JSON
        // 原封透传）是它单独成 route 的全部理由 —— 见 AppRoute.EditCharacter
        AppRoute.EditCharacter::class.java,
        // W4 批次 3：Settings 的 7 个直达子屏（§2.41 静态 gate 已预接：
        // 强类型 Screen enum、微根/微栈机器断言、退栈按类型解除去重）。
        // 单层容器纪律与 ChatDetail/Create 同一条链 —— SettingsSurface
        // 不在自身内再开 Surface（12 个微栈目标都是页内导航）
        AppRoute.SettingsSubScreen::class.java,
        // W4 批次 3：EditProfile（§2.43 预接的全部机制随本行生效 ——
        // auth-scoped bootstrap、精确 token + JWT sub 账号闸、进程级
        // mutation 串行、notifyProfileChanged → ProfileRefreshHub 刷新接力、
        // 无参路由退栈解除）。§9.1 模拟器矩阵见 §2.49；真机项继续累积
        AppRoute.EditProfile::class.java,
        // W4 批次 3：Comments（Screen 评论按钮 / 互动通知评论卡的落点）。
        // ⚠️ ChatDetail 内点评论**不经这里**（Comments 屏在 ChatDetail 微栈内，
        // §12.1 核实 2026-08-20）—— 本 route 只从原生页进入，单层容器纪律不变
        AppRoute.Comments::class.java,
        // W4 批次 4：站内信（ChatList 铃铛 → NotificationSurface）。
        // Engagement tab 的跨栈出口经桥（openComments/openChatDetail/
        // openFeedback 本刀新增；openUserProfile 早有）—— 目标容器
        // replace + addToBackStack 叠栈（同 ChatDetail → 他人主页的既有
        // 模式），返回逐层弹；popSurface 弹栈顶，单显示层语义不变
        AppRoute.Letter::class.java,
        // W4 批次 4：宝石购买/订阅 + 金币兑换（Profile 钱包卡三出口 +
        // 402 付费墙兜底 + 桥 openGemsPurchase，三入口早已汇到同一 route）。
        // ⚠️ 真实购买在模拟器不可测（无 Play Billing），矩阵只验渲染与
        // 渠道分流 —— 支付闭环是真机冒烟置顶项（§2.52）
        AppRoute.GemsPurchase::class.java,
        AppRoute.UserCoins::class.java,
        // W4 批次 5：角色卡新增/编辑（Profile 角色卡 tab 的 Add New +
        // 卡行点击）。微栈含 CreateStack —— 换头像死链是 iOS 原始事故，
        // 静态 gate 已锁（RoleCardSurfaceContractTest）
        AppRoute.RoleCard::class.java,
    )
}

/**
 * 壳的**单一导航入口**（W1-P4，方案 §4.7）。
 *
 * ```
 * Intent / Push / Widget / Compose 点击 / RN 桥
 *         ↓  AppRouteParser（typed）
 *         ↓  auth gate + 去重 + source attribution
 *    Native destination | RN Surface
 * ```
 *
 * ## 为什么必须集中
 *
 * iOS 把 self/others 分流散在各调用点，结果出现「关注自己」——
 * 每个新入口都要重新判一次，漏一处就是一个 bug。§6.5 要求集中判定。
 *
 * ## 未登录排队：登录后**恰好执行一次**
 *
 * 这是本类最容易写错的地方。三种错法各有症状：
 * - 不排队 → 点了没反应（用户从 push 进来，被登录页拦下，登录完就停在首页）
 * - 排队但不清 → 每次登录都重放一次旧路由（**下次登录莫名跳到上次的页面**）
 * - 排队且重复执行 → 同一目标被打开两次（返回时要按两次）
 */
class AppRouter(
    private val navigator: Navigator,
    private val isLoggedIn: () -> Boolean,
    private val authStateHub: AuthStateHub,
    private val logger: (String) -> Unit = {},
    enabledRouteTypes: Set<Class<out AppRoute>> = ProductionRoutePolicy.enabledRouteTypes,
) {

    /** 真正执行导航的一侧（由 Activity 实现）。Router 只做决策，不碰 UI。 */
    interface Navigator {
        /** 打开一个已解析且已过 auth gate 的路由。 */
        fun navigate(route: AppRoute, source: Source)

        /** 需要登录：展示登录入口。 */
        fun requestLogin(reason: String?)

        /** 路由已识别但目标尚未启用（Surface 未过 §9.1 矩阵）。 */
        fun rejectNotEnabled(route: AppRoute, reason: String)
    }

    /** source attribution（方案 §4.7）。埋点要区分入口来源。 */
    enum class Source { DEEP_LINK, PUSH, WIDGET, IN_APP, BRIDGE }

    /**
     * 已启用的目标白名单。
     *
     * §8.3 纪律：**未过 §9.1 矩阵的 Surface 不得接生产入口**，且路由到未启用目标
     * 必须给明确错误或安全兜底、**不做 silent no-op**。
     *
     * 生产默认来自 [ProductionRoutePolicy]。构造时复制成不可变快照，运行中不得
     * “临时放开”；新增目标必须随版本显式更新 policy 与验收矩阵。
     */
    private val enabledRoutes = enabledRouteTypes.toSet()

    /** 待登录后执行的路由。**最多一条** —— 后来的覆盖先前的（用户最新意图优先）。 */
    private var pendingRoute: PendingRoute? = null

    private data class PendingRoute(val route: AppRoute, val source: Source)

    /** 去重用：上一次处理的 (route, source)。防同一 Intent 被投递两次。 */
    private var lastHandled: Pair<AppRoute, Source>? = null

    private val loginObserver = object : AuthStateHub.Observer {
        override fun onDidLogin(userId: String?) {
            flushPending()
        }

        override fun onDidLogout() {
            // 登出时丢弃排队路由 —— 它属于上一个账号的意图。
            // 不清会导致「登出再登录后莫名跳到上个账号想去的页面」。
            if (pendingRoute != null) {
                logger("登出，丢弃排队路由 ${pendingRoute?.route?.javaClass?.simpleName}")
                pendingRoute = null
            }
            lastHandled = null
        }
    }

    init {
        authStateHub.addObserver(loginObserver)
    }

    /** 解析并处理一条外部 URI。返回是否识别（不代表已导航 —— 可能在排队）。 */
    fun handleUri(uriString: String?, source: Source = Source.DEEP_LINK): Boolean {
        val route = AppRouteParser.parse(uriString)
        if (route == null) {
            // **可诊断地忽略**：记下来但不崩、不猜。
            // 这里刻意不打印完整 URI —— 深链 query 可能含标识符。
            logger("无法识别的深链（scheme/path 不匹配），已忽略")
            return false
        }
        handle(route, source)
        return true
    }

    /**
     * 处理一条已解析的路由。
     *
     * 顺序**不能调**：去重 → auth gate → 启用检查 → 导航。
     * 把启用检查放到 auth gate 之前，会让未登录用户先看到「功能未开放」
     * 而不是登录页 —— 错的提示比没有提示更让人困惑。
     */
    fun handle(route: AppRoute, source: Source = Source.IN_APP) {
        if (lastHandled == (route to source)) {
            logger("重复路由，已去重：${route.javaClass.simpleName}")
            return
        }

        if (route.requiresAuth && !isLoggedIn()) {
            pendingRoute = PendingRoute(route, source)
            logger("需要登录，已排队：${route.javaClass.simpleName}")
            navigator.requestLogin(reason = route.javaClass.simpleName)
            return
        }

        dispatch(route, source)
    }

    private fun dispatch(route: AppRoute, source: Source) {
        if (route.javaClass !in enabledRoutes) {
            // 明确拒绝，不静默 —— §8.3
            logger("目标未启用：${route.javaClass.simpleName}")
            navigator.rejectNotEnabled(
                route,
                "该目标在当前波次尚未启用（未过验收矩阵）",
            )
            return
        }
        lastHandled = route to source
        navigator.navigate(route, source)
    }

    /**
     * 已打开的目标离开原生返回栈时解除去重。
     *
     * [lastHandled] 只该挡住同一次投递/连点造成的重入，不能永久封住一个路由。
     * Search 是第一个真正进入生产白名单的普通业务目标：如果退出时不清，用户返回
     * Home 后再次点搜索框会被当成“重复路由”，此后整个 Activity 生命周期里都打不开。
     *
     * 由实际持有返回栈的 Activity 在确认目标已移除后调用；传入其它 route 不会误清
     * 当前目标，避免迟到的关闭通知放开不相干的路由。
     */
    fun onDestinationClosed(route: AppRoute) {
        if (lastHandled?.first != route) return
        lastHandled = null
        logger("目标已关闭，解除路由去重：${route.javaClass.simpleName}")
    }

    /**
     * 同 [onDestinationClosed]，但按**谓词**匹配而不是整体相等。
     *
     * ## 为什么需要它：带参路由无法用相等判定
     *
     * [AppRoute.UserProfile] 有两个字段（`userId` + `recommendationContextJSON`）。
     * Activity 那侧只知道"某个用户的主页已出栈"，**拿不到当初那条路由的
     * 归因参数** —— 用相等判定就永远匹配不上，去重会一直挂着，
     * 表现为「从 A 的主页返回后，再点同一个 A 永远打不开」。
     *
     * 而按类型清（`javaClass ==`）又太粗：栈里可能还有别人的主页
     * （A → B 的合法叠栈），清掉会让迟到的关闭通知放开一个还开着的目标。
     *
     * 所以由调用方给谓词，自己判断"这条 lastHandled 是不是刚关掉那个"。
     *
     * @param predicate 对当前 [lastHandled] 的路由求值；true 才清
     */
    fun onDestinationClosed(predicate: (AppRoute) -> Boolean) {
        val current = lastHandled?.first ?: return
        if (!predicate(current)) return
        lastHandled = null
        logger("目标已关闭，解除路由去重：${current.javaClass.simpleName}")
    }

    /**
     * 登录完成后执行排队的路由。
     *
     * **先清 pendingRoute 再 dispatch** —— 否则 dispatch 过程中若再次触发
     * `onDidLogin`（换号场景），会把同一路由执行两次。
     */
    private fun flushPending() {
        val pending = pendingRoute ?: return
        pendingRoute = null
        logger("登录完成，执行排队路由：${pending.route.javaClass.simpleName}")
        dispatch(pending.route, pending.source)
    }

    /** Activity 销毁时调用，避免 hub 持有已死的 Router。 */
    fun dispose() {
        authStateHub.removeObserver(loginObserver)
        pendingRoute = null
        lastHandled = null
    }

    /** 仅测试用。 */
    internal fun hasPendingRoute(): Boolean = pendingRoute != null
}
