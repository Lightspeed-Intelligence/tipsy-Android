package ai.lightspeed.tipsy.shell.router

import ai.lightspeed.tipsy.shell.auth.AuthStateHub

/** 当前 binary 的生产路由白名单；未过验收矩阵的目标一律不进入。 */
object ProductionRoutePolicy {
    /** P9 / §9.1 前没有业务路由可进生产；启用必须集中改这里并更新矩阵测试。 */
    val enabledRouteTypes: Set<Class<out AppRoute>> = emptySet()
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
