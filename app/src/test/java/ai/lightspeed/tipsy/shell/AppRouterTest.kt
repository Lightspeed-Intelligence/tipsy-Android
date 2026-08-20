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
     *
     * ⚠️ 主体已换三次：`ChatDetail`（P9）→ `Letter`（批次 4）→
     * `GemsPurchase`（批次 4 第二刀）→ 现在是 `DailyGemEntry`（深链目标，
     * 无对应 Surface 启用计划前恒未启用）。样本耗尽时改为断言白名单 =
     * route 类型全集。
     */
    @Test
    fun `未启用的 Surface 目标被明确拒绝且不会导航`() {
        assertFalse(
            "生产白名单本身必须锁住未过矩阵的目标，不能只靠 Activity 记得不启用",
            AppRoute.DailyGemEntry::class.java in ProductionRoutePolicy.enabledRouteTypes,
        )
        val f = fixture(
            loggedIn = true,
            enabled = ProductionRoutePolicy.enabledRouteTypes.toList(),
        )
        val route = AppRoute.DailyGemEntry
        f.router.handle(route)

        assertEquals("不该导航", 0, f.navigated.size)
        assertEquals("必须明确拒绝一次", 1, f.rejections.size)
        assertEquals(route, f.rejections.single())
    }

    /**
     * W4 批次 4：Gems/UserCoins 双放行 —— Profile 钱包卡三出口 +
     * 402 付费墙兜底 + 桥 openGemsPurchase 全部有下一屏。
     */
    @Test
    fun `GemsPurchase 与 UserCoins 在生产白名单内且关闭后可重开`() {
        for (type in listOf(
            AppRoute.GemsPurchase::class.java,
            AppRoute.UserCoins::class.java,
        )) {
            assertTrue(
                "${type.simpleName} 必须在生产白名单里，否则钱包卡出口点了只会 reject",
                type in ProductionRoutePolicy.enabledRouteTypes,
            )
        }

        val f = fixture(
            loggedIn = true,
            enabled = listOf(AppRoute.GemsPurchase::class.java),
        )
        val route = AppRoute.GemsPurchase(mapOf("initialTab" to "buy_gems"))
        f.router.handle(route)
        f.router.handle(route)
        assertEquals("容器还在时重复点击应去重", 1, f.navigated.size)

        f.router.onDestinationClosed { it is AppRoute.GemsPurchase }
        f.router.handle(route)
        assertEquals("容器退栈后必须能重开", 2, f.navigated.size)
    }

    /**
     * W4 批次 5：`RoleCard` 进白名单 —— Profile 角色卡 tab 的
     * Add New 与卡行编辑有下一屏。
     */
    @Test
    fun `RoleCard 在生产白名单内且关闭后可重开`() {
        assertTrue(
            "RoleCard 必须在生产白名单里，否则 Add New 点了只会 reject",
            AppRoute.RoleCard::class.java in ProductionRoutePolicy.enabledRouteTypes,
        )

        val f = fixture(
            loggedIn = true,
            enabled = listOf(AppRoute.RoleCard::class.java),
        )
        // 无参（Add New）实例恒相等 —— Create 那次真机抓过的类别性缺陷，
        // 必须验证退栈解除
        val route = AppRoute.RoleCard()
        f.router.handle(route)
        f.router.handle(route)
        assertEquals("容器还在时重复点击应去重", 1, f.navigated.size)

        f.router.onDestinationClosed { it is AppRoute.RoleCard }
        f.router.handle(route)
        assertEquals("容器退栈后 Add New 必须能重开", 2, f.navigated.size)
    }

    /**
     * W4 批次 4：`Letter` 进白名单 —— ChatList 铃铛有下一屏
     * （NotificationSurface，三 tab 站内信）。
     */
    @Test
    fun `Letter 在生产白名单内且关闭后可重开`() {
        assertTrue(
            "Letter 必须在生产白名单里，否则铃铛点了只会 reject",
            AppRoute.Letter::class.java in ProductionRoutePolicy.enabledRouteTypes,
        )

        val f = fixture(
            loggedIn = true,
            enabled = listOf(AppRoute.Letter::class.java),
        )
        val route = AppRoute.Letter()
        f.router.handle(route)
        f.router.handle(route)
        assertEquals("容器还在时重复点击应去重", 1, f.navigated.size)

        f.router.onDestinationClosed { it is AppRoute.Letter }
        f.router.handle(route)
        assertEquals("容器退栈后必须能重开", 2, f.navigated.size)
    }

    /**
     * P9：`ChatDetail` 与 `MiniPhoneChat` 进白名单 —— 四个原生列表页的
     * 卡片点击至此**第一次真的有下一屏**。
     *
     * 这条锁的是「白名单与导航分支同时更新」：只加白名单不加
     * `ShellNavigator.navigate` 分支会走到 `error()`（那是刻意的，
     * 但要在这里先红）。
     */
    @Test
    fun `ChatDetail 与 MiniPhone 在生产白名单内`() {
        for (type in listOf(
            AppRoute.ChatDetail::class.java,
            AppRoute.MiniPhoneChat::class.java,
        )) {
            assertTrue(
                "${type.simpleName} 必须在生产白名单里，否则卡片点击仍然点了没反应",
                type in ProductionRoutePolicy.enabledRouteTypes,
            )
        }
    }

    /**
     * W4：`Create` 进白名单 —— Tab3 的 ➕ 至此真的有下一屏。
     *
     * 同上一条的理由：只加白名单不加 `ShellNavigator.navigate` 分支会走到
     * `error()`，这里先红。
     */
    @Test
    fun `Create 在生产白名单内`() {
        assertTrue(
            "Create 必须在生产白名单里，否则 Tab3 点击仍然点了没反应",
            AppRoute.Create::class.java in ProductionRoutePolicy.enabledRouteTypes,
        )
    }

    /**
     * P5：`EditCharacter` 进白名单 —— 创作卡 ⋮ 菜单的「编辑」有下一屏。
     * 同 Create 的理由：只加白名单不加 `ShellNavigator.navigate` 分支会
     * 走到 `error()`，这里先红。
     */
    @Test
    fun `EditCharacter 在生产白名单内`() {
        assertTrue(
            "EditCharacter 必须在生产白名单里，否则卡片菜单的编辑点了没反应",
            AppRoute.EditCharacter::class.java in ProductionRoutePolicy.enabledRouteTypes,
        )
    }

    /**
     * W4 批次 3：`SettingsSubScreen` 进白名单 —— Settings 列表的 7 个
     * Surface 子屏（Security/Blacklist/Feedback/About/ContactUs/Delete/Widget）
     * 有下一屏。§2.41 的静态 gate（微根/微栈/强类型 Screen/退栈解除）
     * 已先行，导航分支与去重解除在 MainActivity 早已预接。
     */
    @Test
    fun `SettingsSubScreen 在生产白名单内`() {
        assertTrue(
            "SettingsSubScreen 必须在生产白名单里，否则设置列表的七个子屏点了只会 reject",
            AppRoute.SettingsSubScreen::class.java in ProductionRoutePolicy.enabledRouteTypes,
        )
    }

    /**
     * W4 批次 3：`Comments` 进白名单 —— Screen 评论按钮有下一屏。
     * ⚠️ ChatDetail 内点评论不经此 route（Comments 屏在其微栈内），
     * 单层容器纪律不因本 route 改变。
     */
    @Test
    fun `Comments 在生产白名单内且关闭后可重开`() {
        assertTrue(
            "Comments 必须在生产白名单里，否则 Screen 评论按钮点了只会 reject",
            AppRoute.Comments::class.java in ProductionRoutePolicy.enabledRouteTypes,
        )

        val f = fixture(
            loggedIn = true,
            enabled = listOf(AppRoute.Comments::class.java),
        )
        val route = AppRoute.Comments(targetType = 1, targetId = "c1", creatorId = "u9")
        f.router.handle(route)
        f.router.handle(route)
        assertEquals("容器还在时重复点击应去重", 1, f.navigated.size)

        // 带参 route：容器退栈后按类型解除（MainActivity 的谓词版）
        f.router.onDestinationClosed { it is AppRoute.Comments }
        f.router.handle(route)
        assertEquals("容器退栈后同一作品评论页必须能重开", 2, f.navigated.size)
    }

    /**
     * Create 要求登录：未登录时**排队**而不是直接打开。
     *
     * 创建流程的每个接口都要 token，未登录直接挂 Surface 的表现是
     * 「进去了但一路 401」—— 比点了没反应更难归因。
     */
    @Test
    fun `未登录点 Create 先登录再恰好执行一次`() {
        val f = fixture(loggedIn = false, enabled = listOf(AppRoute.Create::class.java))
        f.router.handle(AppRoute.Create())

        assertEquals("不该直接导航", 0, f.navigated.size)
        assertEquals("必须请求登录一次", 1, f.loginRequests.size)

        f.loggedIn = true
        f.hub.notifyDidLogin("u1")

        assertEquals("登录后应恰好执行一次", listOf<AppRoute>(AppRoute.Create()), f.navigated)
        assertFalse("执行后必须清空", f.router.hasPendingRoute())
    }

    /**
     * ⚠️ **Router 的去重挡不住「点两张不同卡片」** —— 这条测试记录的是一个
     * 已知边界，不是缺陷：`lastHandled` 是 `route to source`，两个不同
     * characterId 是两个不同 route，两次都会 navigate。
     *
     * 幂等因此**必须由 Activity 侧补**（`openSurface` 用 componentName 作 tag
     * 判栈里是否已有），RN 侧对应物是 `useChatNavigation.ts:45` 的
     * `globalNavigating` 闸。不补的表现是叠两层 Surface 容器 ——
     * 而那正好会让 §12 那个「单层容器所以 popSurface 弹不错」的前提失效。
     *
     * 写成测试而不是注释：将来若有人想把幂等收进 Router，这条会告诉他
     * 当前语义是什么、以及为什么 Activity 那侧不能删。
     */
    @Test
    fun `Router 不挡不同角色的连续点击 —— 幂等归 Activity`() {
        val f = fixture(
            loggedIn = true,
            enabled = listOf(AppRoute.ChatDetail::class.java),
        )

        f.router.handle(AppRoute.ChatDetail("c1"))
        f.router.handle(AppRoute.ChatDetail("c2"))

        assertEquals(
            "两个不同 route 都会到 navigator —— 幂等必须由 openSurface 的 tag 判定兜住",
            2,
            f.navigated.size,
        )
    }

    /**
     * 带参路由的去重必须能解除，否则「退出聊天后再点同一个角色永远打不开」。
     *
     * `ChatDetail` 带 characterId + 判定素材，**相等判定拿不到那些值** ——
     * 所以 Activity 那侧用谓词版 `onDestinationClosed`。这条在 Router 层
     * 验证谓词确实能放开它（Activity 的接线由冒烟覆盖）。
     */
    @Test
    fun `ChatDetail 退出后可再次打开同一角色`() {
        val f = fixture(
            loggedIn = true,
            enabled = listOf(AppRoute.ChatDetail::class.java),
        )
        val route = AppRoute.ChatDetail("c1", chatEnterSource = "home", characterType = 1)

        f.router.handle(route)
        assertEquals(1, f.navigated.size)

        // 同一路由再来一次：在栈期间应被去重
        f.router.handle(route)
        assertEquals("在栈期间是重复投递", 1, f.navigated.size)

        // 容器关闭 —— 谓词版按类型放开（壳内不叠两层聊天页）
        f.router.onDestinationClosed { it is AppRoute.ChatDetail }

        f.router.handle(route)
        assertEquals("退出后再次点击是新意图", 2, f.navigated.size)
    }

    /**
     * **真机实测过的缺陷**（W4，2026-08-18）：关掉创建页后再点 ➕ 打不开。
     *
     * `AppRoute.Create` 的参数固定（`tab_bar_plus`），两次点击产出的实例
     * **完全相等** —— 所以 `lastHandled` 不解除时去重会**永久命中**，
     * 表现是「Tab3 只能用一次」。ChatDetail 因为每次带不同 characterId
     * 而侥幸不暴露这个洞，Create 是第一个无参 Surface 路由。
     *
     * Activity 侧的解除接线在 `onBackStackChanged`（按
     * `CreateSurfaceContract.COMPONENT_NAME` 判容器是否已出栈）；
     * 这条在 Router 层锁住语义。
     */
    @Test
    fun `Create 关闭后可再次打开`() {
        val f = fixture(loggedIn = true, enabled = listOf(AppRoute.Create::class.java))

        f.router.handle(AppRoute.Create())
        assertEquals(1, f.navigated.size)

        f.router.handle(AppRoute.Create())
        assertEquals("在栈期间是重复投递", 1, f.navigated.size)

        f.router.onDestinationClosed { it is AppRoute.Create }

        f.router.handle(AppRoute.Create())
        assertEquals("关掉创建页后再点 ➕ 必须能再开", 2, f.navigated.size)
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

    /**
     * W3：`UserProfile`（他人主页）是第二个进白名单的原生目标（§2.32）。
     *
     * 锁两件事：**在**白名单里（否则 Search 的创作者点击继续被拒绝，
     * 那是本刀要打通的出口），且 `requiresAuth = false` —— 游客不该在
     * Router 层被挡。
     *
     * ⚠️ 「游客点进去会看到登录页」是**接口层**的行为（`/user/get/public`
     * 走 axiosAuth，见 `PublicProfileApi` 类注释），不是 Router 层的 gate。
     * 两者别混：在 Router 写 requiresAuth=true 会连页面都不进，
     * 与 RN 的「进页面后被 axios 拦」时序不同。
     */
    @Test
    fun `UserProfile 在生产白名单内且 Router 层不拦游客`() {
        assertTrue(
            "UserProfile 必须在白名单里，否则 Search 的创作者点击继续被拒绝",
            AppRoute.UserProfile::class.java in ProductionRoutePolicy.enabledRouteTypes,
        )
        val f = fixture(
            loggedIn = false,
            enabled = ProductionRoutePolicy.enabledRouteTypes.toList(),
        )
        f.router.handle(AppRoute.UserProfile(userId = "u1"))

        assertEquals(1, f.navigated.size)
        assertEquals("不该在 Router 层要求登录", 0, f.loginRequests.size)
        assertEquals("不该被拒绝", 0, f.rejections.size)
    }

    /**
     * 谓词版 `onDestinationClosed`：带参路由无法用相等判定解除去重。
     *
     * `UserProfile` 有 `recommendationContextJSON` 第二字段，Activity 那侧
     * 拿不到当初那条路由的归因参数 —— 用相等判定永远匹配不上，
     * 表现为「从某人主页返回后，再点同一个人永远打不开」。
     */
    @Test
    fun `带归因参数的路由可用谓词解除去重`() {
        val f = fixture(
            loggedIn = true,
            enabled = listOf(AppRoute.UserProfile::class.java),
        )
        val route = AppRoute.UserProfile(userId = "u1", recommendationContextJSON = "{}")
        f.router.handle(route)
        assertEquals(1, f.navigated.size)

        // 相等判定匹配不上（少了归因参数）—— 这正是谓词版存在的理由
        f.router.onDestinationClosed(AppRoute.UserProfile(userId = "u1"))
        f.router.handle(route)
        assertEquals("相等判定不该解除去重", 1, f.navigated.size)

        // 谓词版按 userId 匹配
        f.router.onDestinationClosed { it is AppRoute.UserProfile && it.userId == "u1" }
        f.router.handle(route)
        assertEquals("谓词匹配后应能重开", 2, f.navigated.size)
    }

    /**
     * W3：`Settings` 是**列表本体**（原生页，§2.33）；W4 批次 3 起它的
     * 7 个子屏（`SettingsSubScreen` → `SettingsSurface`）也已放行。
     * 两个 route **类型不同**必须都能各自导航 —— 混为一谈的后果：
     * 点子页要么打开一层新的设置列表（传错 route），要么静默无反应（§8.3 禁止）。
     */
    @Test
    fun `Settings 列表与子屏都已启用且各自导航`() {
        assertTrue(
            "Settings 列表必须在白名单里，否则 Profile 的设置入口点了没反应",
            AppRoute.Settings::class.java in ProductionRoutePolicy.enabledRouteTypes,
        )

        val f = fixture(
            loggedIn = true,
            enabled = ProductionRoutePolicy.enabledRouteTypes.toList(),
        )
        f.router.handle(AppRoute.Settings)
        assertEquals(1, f.navigated.size)

        f.router.handle(
            AppRoute.SettingsSubScreen(AppRoute.SettingsSubScreen.Screen.SECURITY),
        )
        assertEquals("子屏是独立 route，必须导航而不是被吞", 2, f.navigated.size)
        assertEquals("不该有拒绝", 0, f.rejections.size)
    }

    /**
     * `SettingsSubScreen` 带 screen 参数，Activity 在容器退栈时拿不到原 route 实例；
     * 必须按 route 类型解除去重，否则关闭后再次点击同一行会永久命中 lastHandled。
     */
    @Test
    fun `SettingsSurface 关闭后同一子屏可以再次打开`() {
        val f = fixture(
            loggedIn = true,
            enabled = listOf(AppRoute.SettingsSubScreen::class.java),
        )
        val route = AppRoute.SettingsSubScreen(AppRoute.SettingsSubScreen.Screen.SECURITY)

        f.router.handle(route)
        f.router.handle(route)
        assertEquals("容器还在时重复点击应去重", 1, f.navigated.size)

        f.router.onDestinationClosed { it is AppRoute.SettingsSubScreen }
        f.router.handle(route)
        assertEquals("容器退栈后同一子屏必须能重开", 2, f.navigated.size)
    }

    /**
     * W4 批次 3：EditProfile 放行 —— §2.43 预接的 auth-scoped bootstrap /
     * 账号闸 / mutation 串行 / 刷新接力随白名单生效（§9.1 模拟器矩阵 §2.49）。
     */
    @Test
    fun `EditProfile 在生产白名单内且可导航`() {
        assertTrue(
            "EditProfile 必须在生产白名单里，否则 Profile 的编辑资料按钮点了只会 reject",
            AppRoute.EditProfile::class.java in ProductionRoutePolicy.enabledRouteTypes,
        )

        val f = fixture(
            loggedIn = true,
            enabled = ProductionRoutePolicy.enabledRouteTypes.toList(),
        )
        f.router.handle(AppRoute.EditProfile)

        assertEquals("必须导航", 1, f.navigated.size)
        assertEquals("不该有拒绝", 0, f.rejections.size)
    }

    /** 无参 Surface 关闭后必须解除 lastHandled，否则编辑资料只能打开一次。 */
    @Test
    fun `EditProfileSurface 关闭后可以再次打开`() {
        val f = fixture(
            loggedIn = true,
            enabled = listOf(AppRoute.EditProfile::class.java),
        )

        f.router.handle(AppRoute.EditProfile)
        f.router.handle(AppRoute.EditProfile)
        assertEquals("容器还在时重复点击应去重", 1, f.navigated.size)

        f.router.onDestinationClosed { it is AppRoute.EditProfile }
        f.router.handle(AppRoute.EditProfile)
        assertEquals("容器退栈后必须能重开", 2, f.navigated.size)
    }

    /** 谓词不匹配时不得误清 —— A → B 的合法叠栈里，B 出栈不该解除 A 的去重。 */
    @Test
    fun `谓词不匹配时不解除去重`() {
        val f = fixture(
            loggedIn = true,
            enabled = listOf(AppRoute.UserProfile::class.java),
        )
        val route = AppRoute.UserProfile(userId = "a")
        f.router.handle(route)

        f.router.onDestinationClosed { it is AppRoute.UserProfile && it.userId == "b" }
        f.router.handle(route)

        assertEquals(1, f.navigated.size)
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
