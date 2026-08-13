package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.auth.AuthStateHub
import ai.lightspeed.tipsy.shell.router.AppRoute
import ai.lightspeed.tipsy.shell.router.AppRouter
import ai.lightspeed.tipsy.shell.router.ProductionRoutePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppRouter] 的 auth gate / 排队 / 去重测试（W1-P4，计划 §6.6 的测试矩阵）。
 *
 * 这里覆盖的失败模式都**不会报错**，只会让用户困惑：
 * 「点了没反应」、「莫名跳到上次想去的页面」、「返回要按两次」。
 */
class AppRouterTest {

    // ── 已登录：直接导航 ───────────────────────────────────────

    @Test
    fun `已登录时直接导航`() {
        val f = fixture(loggedIn = true, enabled = listOf(AppRoute.ChatDetail::class.java))
        f.router.handle(AppRoute.ChatDetail("c1"))

        assertEquals(listOf<AppRoute>(AppRoute.ChatDetail("c1")), f.navigated)
        assertFalse("不该排队", f.router.hasPendingRoute())
    }

    /** 不要求登录的路由，未登录也能直接走（游客可看他人主页）。 */
    @Test
    fun `不要求登录的路由未登录也可导航`() {
        val f = fixture(loggedIn = false, enabled = listOf(AppRoute.UserProfile::class.java))
        f.router.handle(AppRoute.UserProfile("u1"))

        assertEquals(1, f.navigated.size)
        assertEquals(0, f.loginRequests.size)
    }

    // ── 未登录排队：登录后恰好一次 ─────────────────────────────

    /**
     * 不排队的症状：用户从 push 进来 → 被登录页拦下 → 登录完停在首页，
     * **原本要去的页面没打开**，看起来就是「点了没反应」。
     */
    @Test
    fun `未登录时排队并请求登录`() {
        val f = fixture(loggedIn = false, enabled = listOf(AppRoute.ChatDetail::class.java))
        f.router.handle(AppRoute.ChatDetail("c1"), AppRouter.Source.PUSH)

        assertEquals("此刻不该导航", 0, f.navigated.size)
        assertEquals("必须请求登录", 1, f.loginRequests.size)
        assertTrue("必须排队", f.router.hasPendingRoute())
    }

    @Test
    fun `登录后执行排队路由 且只执行一次`() {
        val f = fixture(loggedIn = false, enabled = listOf(AppRoute.ChatDetail::class.java))
        f.router.handle(AppRoute.ChatDetail("c1"), AppRouter.Source.PUSH)

        f.loggedIn = true
        f.hub.notifyDidLogin("u1")

        assertEquals(listOf<AppRoute>(AppRoute.ChatDetail("c1")), f.navigated)
        assertFalse("执行后必须清空", f.router.hasPendingRoute())
    }

    /**
     * **换号场景**：连续两次 didLogin 不得把同一路由执行两次。
     * 症状是同一页面被打开两次，返回时要按两下。
     */
    @Test
    fun `连续两次登录事件不重复执行排队路由`() {
        val f = fixture(loggedIn = false, enabled = listOf(AppRoute.ChatDetail::class.java))
        f.router.handle(AppRoute.ChatDetail("c1"), AppRouter.Source.PUSH)

        f.loggedIn = true
        f.hub.notifyDidLogin("u1")
        f.hub.notifyDidLogin("u2")

        assertEquals("必须恰好一次", 1, f.navigated.size)
    }

    /**
     * **登出必须丢弃排队路由** —— 它属于上一个账号的意图。
     * 不丢的症状：登出再登录后**莫名跳到上个账号想去的页面**。
     */
    @Test
    fun `登出丢弃排队路由`() {
        val f = fixture(loggedIn = false, enabled = listOf(AppRoute.ChatDetail::class.java))
        f.router.handle(AppRoute.ChatDetail("c1"), AppRouter.Source.PUSH)
        assertTrue(f.router.hasPendingRoute())

        f.hub.notifyDidLogout()
        assertFalse("登出后排队必须清空", f.router.hasPendingRoute())

        f.loggedIn = true
        f.hub.notifyDidLogin("u2")
        assertEquals("新账号登录不该执行旧账号的路由", 0, f.navigated.size)
    }

    /** 后来的路由覆盖先前排队的 —— 用户最新意图优先。 */
    @Test
    fun `新路由覆盖旧的排队路由`() {
        val f = fixture(
            loggedIn = false,
            enabled = listOf(AppRoute.ChatDetail::class.java, AppRoute.Subscribe::class.java),
        )
        f.router.handle(AppRoute.ChatDetail("c1"))
        f.router.handle(AppRoute.Subscribe)

        f.loggedIn = true
        f.hub.notifyDidLogin("u1")

        assertEquals(listOf<AppRoute>(AppRoute.Subscribe), f.navigated)
    }

    // ── 去重（同 Intent 投递两次）───────────────────────────────

    @Test
    fun `同一路由同一来源去重`() {
        val f = fixture(loggedIn = true, enabled = listOf(AppRoute.ChatDetail::class.java))
        f.router.handle(AppRoute.ChatDetail("c1"), AppRouter.Source.DEEP_LINK)
        f.router.handle(AppRoute.ChatDetail("c1"), AppRouter.Source.DEEP_LINK)

        assertEquals("同 Intent 被投递两次时只处理一次", 1, f.navigated.size)
    }

    /** 不同来源不去重 —— 用户可能真的先点深链、再从应用内点一次。 */
    @Test
    fun `不同来源不去重`() {
        val f = fixture(loggedIn = true, enabled = listOf(AppRoute.ChatDetail::class.java))
        f.router.handle(AppRoute.ChatDetail("c1"), AppRouter.Source.DEEP_LINK)
        f.router.handle(AppRoute.ChatDetail("c1"), AppRouter.Source.IN_APP)

        assertEquals(2, f.navigated.size)
    }

    @Test
    fun `不同参数不去重`() {
        val f = fixture(loggedIn = true, enabled = listOf(AppRoute.ChatDetail::class.java))
        f.router.handle(AppRoute.ChatDetail("c1"))
        f.router.handle(AppRoute.ChatDetail("c2"))

        assertEquals("不同角色是不同目标", 2, f.navigated.size)
    }

    /**
     * 去重只覆盖目标仍在栈里的时段。退出后再点是新的用户意图，必须能重开。
     *
     * Search 是首个进生产白名单的普通目标；没有这条，返回 Home 后第二次点击
     * 搜索框会被 [AppRouter] 永久吞掉，而 Fragment 自己的幂等保护无法补救。
     */
    @Test
    fun `目标关闭后同一路由可再次打开`() {
        val f = fixture(loggedIn = true, enabled = listOf(AppRoute.Search::class.java))

        f.router.handle(AppRoute.Search)
        f.router.handle(AppRoute.Search)
        assertEquals("目标仍在栈里时重复点击只导航一次", 1, f.navigated.size)

        f.router.onDestinationClosed(AppRoute.Search)
        f.router.handle(AppRoute.Search)

        assertEquals("退出后再次点击是新意图", 2, f.navigated.size)
    }

    // ── 未启用目标：明确拒绝，不静默 ────────────────────────────

    /**
     * §8.3 纪律：路由到未启用目标必须给明确错误或安全兜底，**绝不 silent no-op**。
     * 静默的症状正是「点了没反应」。
     */
    @Test
    fun `P9 前 ChatDetail 被明确拒绝且不会导航`() {
        assertFalse(
            "生产白名单本身必须锁住 ChatDetail，不能只靠 Activity 记得不启用",
            AppRoute.ChatDetail::class.java in ProductionRoutePolicy.enabledRouteTypes,
        )
        val f = fixture(
            loggedIn = true,
            enabled = ProductionRoutePolicy.enabledRouteTypes.toList(),
        )
        val route = AppRoute.ChatDetail("c1")
        f.router.handle(route)

        assertEquals("不该导航", 0, f.navigated.size)
        assertEquals("必须明确拒绝一次", 1, f.rejections.size)
        assertEquals(route, f.rejections.single())
    }

    /**
     * W3：`Search` 是第一个进白名单的目标（原生 Fragment，不是 RN Surface）。
     *
     * 这条测试锁两件事：**在**白名单里，且**游客可达** —— 搜索页六个端点里
     * 四个是 `OPPORTUNISTIC`，未登录能正常搜。写成 requiresAuth=true 会让
     * 游客点搜索框先弹登录页，与 RN 不一致。
     */
    @Test
    fun `Search 在生产白名单内且未登录也能直达`() {
        assertTrue(
            "Search 必须在生产白名单里，否则搜索入口点了没反应",
            AppRoute.Search::class.java in ProductionRoutePolicy.enabledRouteTypes,
        )
        val f = fixture(
            loggedIn = false,
            enabled = ProductionRoutePolicy.enabledRouteTypes.toList(),
        )
        f.router.handle(AppRoute.Search)

        assertEquals("游客也该直接进搜索页", 1, f.navigated.size)
        assertEquals(AppRoute.Search, f.navigated.single())
        assertEquals("不该要求登录", 0, f.loginRequests.size)
        assertEquals("不该被拒绝", 0, f.rejections.size)
    }

    // ── 顺序：auth gate 先于启用检查 ────────────────────────────

    /**
     * 未登录 + 目标未启用时，用户应先看到**登录页**而不是「功能未开放」。
     * 顺序颠倒会给出错的提示，比没有提示更让人困惑。
     */
    @Test
    fun `未登录且未启用时先请求登录`() {
        val f = fixture(loggedIn = false, enabled = emptyList())
        val route = AppRoute.ChatDetail("c1")
        f.router.handle(route)

        assertEquals("应请求登录", 1, f.loginRequests.size)
        assertEquals("此刻不该报「未启用」", 0, f.rejections.size)

        f.loggedIn = true
        f.hub.notifyDidLogin("u1")

        assertEquals("登录后仍不得打开未过 P9 的 Surface", 0, f.navigated.size)
        assertEquals(route, f.rejections.single())
    }

    // ── URI 入口 ──────────────────────────────────────────────

    @Test
    fun `handleUri 解析并导航`() {
        val f = fixture(loggedIn = true, enabled = listOf(AppRoute.ChatDetail::class.java))
        val handled = f.router.handleUri("tipsy://chat/detail?character_id=c9")

        assertTrue(handled)
        assertEquals(listOf<AppRoute>(AppRoute.ChatDetail("c9")), f.navigated)
    }

    @Test
    fun `handleUri 对无法识别的链接安全返回 false`() {
        val f = fixture(loggedIn = true, enabled = emptyList())
        assertFalse(f.router.handleUri("tiktok://chat/detail"))
        assertFalse(f.router.handleUri(null))
        assertEquals("不该导航也不该拒绝（压根没识别）", 0, f.navigated.size)
        assertEquals(0, f.rejections.size)
    }

    // ── dispose ───────────────────────────────────────────────

    @Test
    fun `dispose 后不再响应登录事件`() {
        val f = fixture(loggedIn = false, enabled = listOf(AppRoute.ChatDetail::class.java))
        f.router.handle(AppRoute.ChatDetail("c1"))
        f.router.dispose()

        f.loggedIn = true
        f.hub.notifyDidLogin("u1")

        assertEquals("dispose 后不得再导航（Activity 已销毁）", 0, f.navigated.size)
    }

    // ── helpers ───────────────────────────────────────────────

    private class Fixture(
        val router: AppRouter,
        val hub: AuthStateHub,
        val navigated: List<AppRoute>,
        val loginRequests: List<String?>,
        val rejections: List<AppRoute>,
    ) {
        var loggedIn: Boolean = false
    }

    private fun fixture(loggedIn: Boolean, enabled: List<Class<out AppRoute>>): Fixture {
        val navigated = mutableListOf<AppRoute>()
        val loginRequests = mutableListOf<String?>()
        val rejections = mutableListOf<AppRoute>()
        val hub = AuthStateHub()

        lateinit var f: Fixture
        val router = AppRouter(
            navigator = object : AppRouter.Navigator {
                override fun navigate(route: AppRoute, source: AppRouter.Source) {
                    navigated.add(route)
                }

                override fun requestLogin(reason: String?) {
                    loginRequests.add(reason)
                }

                override fun rejectNotEnabled(route: AppRoute, reason: String) {
                    rejections.add(route)
                }
            },
            isLoggedIn = { f.loggedIn },
            authStateHub = hub,
            enabledRouteTypes = enabled.toSet(),
        )
        f = Fixture(router, hub, navigated, loginRequests, rejections)
        f.loggedIn = loggedIn
        return f
    }
}
