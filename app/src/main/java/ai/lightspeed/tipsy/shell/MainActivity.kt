package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.analytics.Analytics
import ai.lightspeed.tipsy.shell.i18n.L10n
import ai.lightspeed.tipsy.shell.pages.login.LoginFragment
import ai.lightspeed.tipsy.shell.pages.profile.PublicProfileFragment
import ai.lightspeed.tipsy.shell.pages.search.SearchFragment
import ai.lightspeed.tipsy.shell.pages.settings.SettingsFragment
import ai.lightspeed.tipsy.shell.router.AppRoute
import ai.lightspeed.tipsy.shell.router.AppRouter
import ai.lightspeed.tipsy.shell.surface.CommentsSurfaceContract
import ai.lightspeed.tipsy.shell.surface.CreateSurfaceContract
import ai.lightspeed.tipsy.shell.surface.EditProfileSurfaceContract
import ai.lightspeed.tipsy.shell.surface.GemsSubscriptionSurfaceContract
import ai.lightspeed.tipsy.shell.surface.NotificationSurfaceContract
import ai.lightspeed.tipsy.shell.surface.RoleCardSurfaceContract
import ai.lightspeed.tipsy.shell.surface.SettingsSurfaceContract
import ai.lightspeed.tipsy.shell.surface.SurfaceProps
import ai.lightspeed.tipsy.shell.surface.UserCoinsSurfaceContract
import ai.lightspeed.tipsy.shell.tabs.TabHostFragment
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.commit
import com.facebook.react.modules.core.DefaultHardwareBackBtnHandler

/**
 * 壳的宿主 Activity。
 *
 * 方案 ADR-002：`AppCompatActivity` + FragmentManager 承载两类页面 ——
 * 原生页是 Fragment 内挂 [ComposeView]，RN 页是 [RNSurfaceFragment]。
 * FragmentManager 统一处理返回栈、saved state、predictive back 与进程重建。
 *
 * ## 层次（W2 起）
 *
 * ```
 * native_root_container ── TabHostFragment（五 Tab，常驻）
 * surface_container ───── 盖在其上：LoginFragment / RNSurfaceFragment
 * ```
 *
 * 两个容器叠放：Tab 是根，登录页与 RN Surface 盖在上面并进返回栈。
 * W0 的自检根（挂 DebugSurface 的两个按钮）已由真实首页替掉；
 * [openDebugSurface] 保留但只剩深链/调试路径可达。
 */
class MainActivity : AppCompatActivity(), DefaultHardwareBackBtnHandler {

    /** 壳的单一导航入口（W1-P4）。 */
    private lateinit var router: AppRouter

    override fun onCreate(savedInstanceState: Bundle?) {
        // 内容绘制到状态栏/导航栏之下，对齐 RN 侧的 `statusBarTranslucent`
        // （`LoginScreen.tsx:372` Modal 属性）。
        //
        // ⚠️ 不做的表现：状态栏是**不透明灰条**，而 RN 版那里是页面底色的延伸 ——
        // 与 RN 并排看第一眼就能看出来（首版实测如此）。
        //
        // targetSdk=36 在 Android 15+ 上本就强制 edge-to-edge，但 API<35 的设备
        // 需要显式调用。壳 minSdk=24，所以必须调 —— 否则新老设备表现不一致。
        // 各页自行用 inset 做避让（LoginFragment 已处理）。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val app = application as TipsyApplication

        // 桥的 popSurface 出口（W1-P0）。Application 不持 Activity 引用，
        // 用回调转接；onDestroy 必须清掉，否则泄漏本 Activity。
        app.onPopSurfaceRequested = { instanceId ->
            runOnUiThread { popSurface(instanceId) }
        }
        // 402 兜底与桥的 openGemsPurchase 都汇到 Router（W1-P6）
        app.onNavigateGemsPurchaseRequested = { params ->
            runOnUiThread {
                router.handle(AppRoute.GemsPurchase(params), AppRouter.Source.BRIDGE)
            }
        }
        // 壳内业务页的导航入口（W2）。同样经 Router —— 方案 §4.7 单一入口：
        // 业务页不得自己 commit Fragment 事务，否则 auth gate 与去重会被绕过
        app.onRouteRequested = { route, source ->
            runOnUiThread { router.handle(route, source) }
        }
        // 桥的 requestLogin 出口（W2 §2.20）。
        //
        // ⚠️ 这条此前**没接** —— ShellAuthProvider.requestLogin 一直是
        // notImplemented（debug 抛），而 axiosAuth 的每个未登录请求都会打到它。
        //
        // 走 navigator 的 requestLogin 而不是 router.handle(AppRoute.Login)：
        // 桥这条**不带目标路由**（JS 只说"要登录"，没说登录后去哪），
        // 而 handle() 会把 Login 本身当成待排队目标。openLogin() 的幂等
        // 保证在下方那个方法里，连续 401 不会叠登录页。
        app.onRequestLoginRequested = { reason ->
            runOnUiThread {
                Log.i(TAG, "桥请求登录：reason=$reason")
                openLogin()
            }
        }

        // 语言可能在 RN Surface 里被改（语言设置页刻意留在 RN，方案 §8.1），
        // 而桥契约**没有 JS→壳 的语言通知方法**（已核实 tipsy-auth 只有壳→JS 的
        // onLanguageChanged）—— 所以壳在 Surface 容器出栈后自己重读。
        //
        // 用 back stack listener 而不是在 popSurface() 里调：返回键有**两条**路径
        // （桥的 popSurface / 系统返回键直接走 FragmentManager），只挂前者会让
        // 「按系统返回键退出设置页」这条路漏掉语言更新。listener 覆盖两者。
        // 进程重建时 FragmentManager 可能已恢复 EditProfile 容器；先用真实 tag
        // 初始化可见沿，否则之后的 pop 只会被看成重复 false，丢掉最终刷新。
        app.profileRefreshHub.onEditProfileSurfaceVisibilityChanged(
            isVisible = supportFragmentManager.findFragmentByTag(
                EditProfileSurfaceContract.COMPONENT_NAME,
            ) != null,
            currentUserId = app.tokenStore.currentUserId(),
        )
        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                app.refreshAccountLanguage()
            }
            // Router 的重复投递去重只覆盖目标在栈期间。Search 退出后必须解除，
            // 否则返回 Home 再点同一个入口会被永久当成重复路由。
            if (supportFragmentManager.findFragmentByTag(TAG_SEARCH) == null) {
                router.onDestinationClosed(AppRoute.Search)
            }
            // 设置页同理（data object 无参，相等判定够用）
            if (supportFragmentManager.findFragmentByTag(TAG_SETTINGS) == null) {
                router.onDestinationClosed(AppRoute.Settings)
            }
            // 他人主页同理，但**不能用相等判定** —— 那条路由可能带归因参数
            // （recommendationContextJSON），这里拿不到，相等永远不成立。
            // 按 userId 匹配：栈里没有这个人的主页了才解除（A → B 的合法叠栈中，
            // B 出栈不该解除 A 的去重）。见 AppRouter.onDestinationClosed(谓词) 注释
            router.onDestinationClosed { route ->
                route is AppRoute.UserProfile &&
                    supportFragmentManager.findFragmentByTag(
                        tagForUserProfile(route.userId),
                    ) == null
            }
            // ChatDetail / CharacterDetail / mini phone 同样必须用**谓词版**（P9）：都带参
            // （characterId + 判定素材），相等判定拿不到那些值就永远不成立，
            // 表现是「退出聊天后再点同一个角色永远打不开」。
            //
            // 判据是「栈里已无 ChatDetailSurface 容器」而不是比对 characterId：
            // 两个 route 共用同一个 Surface 容器（同一个 componentName），
            // 且壳内不叠两层聊天页 —— 容器没了就说明那条路由已关闭。
            if (supportFragmentManager.findFragmentByTag(TAG_CHAT_DETAIL_SURFACE) == null) {
                router.onDestinationClosed { route ->
                    route is AppRoute.ChatDetail ||
                        route is AppRoute.CharacterDetail ||
                        route is AppRoute.MiniPhoneChat
                }
            }
            // Create 同理（W4）。⚠️ 这条**必须有**，而且它比 ChatDetail 更容易踩：
            // `AppRoute.Create` 的参数是固定的 `tab_bar_plus`，每次点 ➕ 产出的
            // 实例**完全相等**，所以 lastHandled 不解除的表现是「关掉创建页后
            // 再点 ➕ 永远打不开」—— 真机实测确认过（ChatDetail 因为每次带不同
            // characterId 而侥幸不暴露这个洞）。
            //
            // 用 data object 相等判定够用（无变参），但仍走谓词版保持与上面一致：
            // 将来若加 draft_box 等入口来源，相等判定会立刻失效而谓词版不会。
            if (supportFragmentManager.findFragmentByTag(CreateSurfaceContract.COMPONENT_NAME) == null) {
                router.onDestinationClosed { route ->
                    // EditCharacter 与 Create 共用容器：同一角色的编辑 route
                    // 实例相等（data class + 同一 rawJson），不解除同样会
                    //「编辑过一次的角色再也点不开编辑」
                    route is AppRoute.Create || route is AppRoute.EditCharacter
                }
            }
            // Settings 的 7 个直达屏共用同一个 SettingsSurface 容器。
            // route 自身带不同的 screen，不能构造某个固定值做相等判断；容器出栈后
            // 按类型解除，才能保证「关掉 Security 后还能再次打开 Security」，也不会
            // 把 Blacklist 等同容器入口留在 lastHandled 里。
            if (supportFragmentManager.findFragmentByTag(SettingsSurfaceContract.COMPONENT_NAME) == null) {
                router.onDestinationClosed { route -> route is AppRoute.SettingsSubScreen }
            }
            // Comments 带参（targetType/targetId），同 ChatDetail 用谓词版：
            // 容器出栈后按类型解除，否则同一作品的评论页只能打开一次
            if (supportFragmentManager.findFragmentByTag(CommentsSurfaceContract.COMPONENT_NAME) == null) {
                router.onDestinationClosed { route -> route is AppRoute.Comments }
            }
            // Letter 带可选 tab 参数，同样谓词版按类型解除
            if (supportFragmentManager.findFragmentByTag(NotificationSurfaceContract.COMPONENT_NAME) == null) {
                router.onDestinationClosed { route -> route is AppRoute.Letter }
            }
            // Gems（带 params map）与 UserCoins（无参 data object）同样谓词版
            if (supportFragmentManager.findFragmentByTag(GemsSubscriptionSurfaceContract.COMPONENT_NAME) == null) {
                router.onDestinationClosed { route -> route is AppRoute.GemsPurchase }
            }
            if (supportFragmentManager.findFragmentByTag(UserCoinsSurfaceContract.COMPONENT_NAME) == null) {
                router.onDestinationClosed { route -> route is AppRoute.UserCoins }
            }
            if (supportFragmentManager.findFragmentByTag(RoleCardSurfaceContract.COMPONENT_NAME) == null) {
                router.onDestinationClosed { route -> route is AppRoute.RoleCard }
            }
            // EditProfile 是无参 data object；若不在容器真正退栈后按类型解除，
            // 第二次点击会永久命中 Router 的 lastHandled 去重（与 Create 同型）。
            // 分支先预接，但 ProductionRoutePolicy 仍保持关闭：auth-scoped gate
            // 已静态落地，专属 §9.1 真机矩阵跑完前这段不会由生产路由抵达。
            val isEditProfileSurfaceVisible = supportFragmentManager.findFragmentByTag(
                EditProfileSurfaceContract.COMPONENT_NAME,
            ) != null
            if (!isEditProfileSurfaceVisible) {
                router.onDestinationClosed { route -> route is AppRoute.EditProfile }
            }
            // Surface 与 Profile 是 sibling：退栈时底层 Fragment 可能始终 STARTED，
            // 不会再触发 onStart。用真实 true→false 关闭沿强制一次最终校准，
            // 账号仍从 Native token store 读，不接受 JS userId。
            app.profileRefreshHub.onEditProfileSurfaceVisibilityChanged(
                isVisible = isEditProfileSurfaceVisible,
                currentUserId = app.tokenStore.currentUserId(),
            )
        }

        router = AppRouter(
            navigator = ShellNavigator(),
            isLoggedIn = { app.tokenStore.hasToken() },
            authStateHub = app.authStateHub,
            logger = { Log.i(TAG, it) },
        )
        // RN 入口已切到 index.surfaces.js（本包 §2.19），业务 Surface 组件**在包里**。
        // AppRouter 使用 ProductionRoutePolicy 的集中白名单：纯原生 Search 已按
        // W3-P1 放开；能挂 ≠ 已验收，ChatDetail 在完成 W1-P9 / §9.1 矩阵前
        // 仍刻意不进生产白名单，命中时走 rejectNotEnabled 记录明确拒绝。
        //
        // ⚠️ 白名单是编译期常量（ProductionRoutePolicy），不再有运行时 markEnabled。
        // 启用某个路由要集中改那里并同步矩阵测试。

        if (savedInstanceState == null) {
            // 五 Tab 是壳的根（W2）。原生根先渲染、不依赖 RN 这一点不变 ——
            // 只是内容从 W0 的自检页换成了真实首页
            supportFragmentManager.commit {
                replace(R.id.native_root_container, TabHostFragment.newInstance(), TAG_TABS)
            }
            // Bootstrap：无有效 token 直接弹登录页（对齐 RN 的 restoreSession，
            // 见 bootstrapSession 注释）
            bootstrapSession(app)
            // 冷启动的深链：Intent 已带 data
            router.handleUri(intent?.data?.toString(), AppRouter.Source.DEEP_LINK)
        }
    }

    /**
     * 会话恢复（对齐 RN `useUserActon.ts:270-290` 的 `restoreSession`）。
     *
     * ## 无 token / token 已过期 → **直接弹登录页**
     *
     * RN 的 restoreSession 在这两种情况下调 `requestLogin('restore-session-no-token')`
     * / `requestLogin('restore-session-expired')`。所以未登录冷启动看到的是登录页，
     * **不是游客态首页** —— 本包 owner 明确选择对齐现网行为。
     *
     * Tab 骨架仍然先建好（上面已 commit），登录页盖在它之上；登录成功后
     * `LoginFragment` 自己 popBackStack 回到首页，此时 `AuthStateHub.didLogin`
     * 触发 Home 重拉。
     *
     * ## 与 RN 的一处**已知差异**（不是漏实现）
     *
     * RN 在 token 有效时还会 `POST /user/info` 拉用户信息、按 `language_code`
     * 切语言、按 `onboardingStatus` 决定是否进引导流程。壳侧：
     * - 语言已由 `bootstrapI18n` 从 `user-storage` 读本地镜像（W1-P5）
     * - **`/user/info` 重拉与 onboarding 判定属下一包** —— 后者的权威判定在
     *   `tipsy-app/src/surfaces/onboardingStage.ts`（有单测），落 `OnboardingSurface`（W4）
     *
     * 所以老用户冷启动不会自动进引导流程。这是分期边界。
     */
    private fun bootstrapSession(app: TipsyApplication) {
        // hasToken() 是同步的本地判定（不发请求）。⚠️ 它只看"有没有"，
        // 过期判定在 getValidToken() 里 —— 这里用它避免在启动关键路径上
        // 触发一次可能的 refresh 网络请求
        if (!app.tokenStore.hasToken()) {
            Log.i(TAG, "会话恢复：无 token，拉起登录页")
            openLogin()
            return
        }
        Log.i(TAG, "会话恢复：本地有 token，进首页（/user/info 重拉属下一包）")
        // 已登录：绑定埋点 uid，让 uid-required 事件不必排队
        Analytics.bindUserId(app.tokenStore.currentUserId())
    }

    /**
     * 热启动的深链。`launchMode=singleTask` 下再次投递同一 Intent 会走这里，
     * 而**不是** onCreate —— 漏了它的表现是「App 在后台时点深链没反应」。
     *
     * 去重由 Router 负责（同一 (route, source) 只处理一次）。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        router.handleUri(intent.data?.toString(), AppRouter.Source.DEEP_LINK)
    }

    /**
     * RN 的返回键契约（**必须实现，否则 Surface 一挂就崩**）。
     *
     * `ReactFragment.onResume` → `reactDelegate.onHostResume()` 内部会把宿主
     * Activity 强转成 [DefaultHardwareBackBtnHandler]，不实现就抛
     * `ClassCastException: Host Activity does not implement DefaultHardwareBackBtnHandler`
     * —— 且崩在 onResume，构建期与静态检查都发现不了（W0 gate 实测捕获）。
     *
     * 语义：RN 侧不处理返回键时回调到这里，执行原生默认返回。
     * W1 起这里要接 Router：先给当前 RN 微栈，到栈底才 pop 原生（方案 §4.7）。
     */
    override fun invokeDefaultOnBackPressed() {
        // 到这里说明 RN 侧已经不处理了（微栈已到栈底），执行原生返回。
        // **返回栈的分层在这里体现**：RN 微栈 → 本回调 → FragmentManager 栈 → 退出。
        // 不要在这里再去 pop RN —— 那会跳过一层，表现为「按一次退两层」。
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        (application as TipsyApplication).let {
            it.onPopSurfaceRequested = null
            it.onNavigateGemsPurchaseRequested = null
            // 漏了这个会泄漏本 Activity（Application 是进程级，回调捕获了 this）
            it.onRouteRequested = null
            it.onRequestLoginRequested = null
        }
        // 必须 dispose：Router 订阅了 AuthStateHub（进程级），
        // 不解绑会让已销毁的 Activity 收到登录事件 → 往死掉的 FragmentManager 提交事务
        router.dispose()
        super.onDestroy()
    }

    /**
     * [AppRouter.Navigator] 的壳侧实现。
     *
     * 只做「把已决策的路由变成实际容器操作」—— auth gate、去重、排队都在 Router 里，
     * 这里不重复判断（否则两处逻辑会漂移）。
     */
    private inner class ShellNavigator : AppRouter.Navigator {

        override fun navigate(route: AppRoute, source: AppRouter.Source) {
            when (route) {
                // P9：ChatDetail 与 mini phone 是**同一个 Surface 的不同初始屏**，
                // 不是两个 Surface（对齐 useChatNavigation.toChatPage 的分支）
                is AppRoute.ChatDetail,
                is AppRoute.CharacterDetail,
                is AppRoute.MiniPhoneChat,
                -> openSurface("ChatDetailSurface", route)
                // W3：原生全屏页（不是 Surface）。白名单里为什么允许它们见
                // `ProductionRoutePolicy`
                is AppRoute.Search -> openSearch()
                // ⚠️ 空 userId 不开页 —— 目标页会去查一个不存在的用户。
                // 在这里挡而不是在页面里：Router 是单一入口，挡住所有调用方
                is AppRoute.UserProfile -> if (route.userId.isNotBlank()) {
                    openUserProfile(route.userId)
                } else {
                    Log.w(TAG, "拒绝导航：UserProfile 缺少 userId")
                }
                // W3：原生设置列表（§2.33）。它的 7 个子屏是 SettingsSubScreen，
                // 未过 §9.1 故不在白名单 —— Router 会先拦下
                is AppRoute.Settings -> openSettings()
                // W3：设置列表的 7 个子屏共用 SettingsSurface。分支先接好但生产
                // policy 仍保持关闭；完成 §9.1 后只需集中放开 route type。
                is AppRoute.SettingsSubScreen ->
                    openSurface(SettingsSurfaceContract.COMPONENT_NAME, route)
                // W4：Tab3 伪 Tab 的目标。走 openSurface 的通用链 ——
                // 幂等判定、平铺 props、popSurface 收口都与 ChatDetail 同一条
                is AppRoute.Create -> openSurface(CreateSurfaceContract.COMPONENT_NAME, route)
                // P5：创作卡 ⋮ 菜单「编辑」→ 同一个 CreateSurface 的编辑态。
                // 与 Create 共用容器与幂等判定（tag 是 componentName），
                // 所以创建页开着时编辑请求会被忽略 —— 这正是单层容器纪律要的
                is AppRoute.EditCharacter ->
                    openSurface(CreateSurfaceContract.COMPONENT_NAME, route)
                // W4 批次 3：评论页（Screen 评论按钮 / 互动通知评论卡）。
                // 通用链：幂等判定、平铺 props、popSurface 收口同 ChatDetail
                is AppRoute.Comments ->
                    openSurface(CommentsSurfaceContract.COMPONENT_NAME, route)
                // W4 批次 4：站内信（ChatList 铃铛）
                is AppRoute.Letter ->
                    openSurface(NotificationSurfaceContract.COMPONENT_NAME, route)
                // W4 批次 4：宝石购买/订阅（钱包卡 + 402 兜底 + 桥三入口同汇）
                is AppRoute.GemsPurchase ->
                    openSurface(GemsSubscriptionSurfaceContract.COMPONENT_NAME, route)
                // W4 批次 4：金币兑换（钱包卡 Coins →）
                is AppRoute.UserCoins ->
                    openSurface(UserCoinsSurfaceContract.COMPONENT_NAME, route)
                // W4 批次 5：角色卡新增/编辑（Add New / 卡行点击）
                is AppRoute.RoleCard ->
                    openSurface(RoleCardSurfaceContract.COMPONENT_NAME, route)
                // W3 预接：RN auth-scoped bootstrap 已落地，但专属 §9.1 尚未
                // 实机验收，生产 policy 仍关闭。组件身份、空 props、容器与关闭
                // 去重先钉死；后续放行只改集中 policy，不再临场补导航机制。
                is AppRoute.EditProfile ->
                    openSurface(EditProfileSurfaceContract.COMPONENT_NAME, route)
                // 其余目标尚未启用，Router 的 enabledRoutes 会先拦下 ——
                // 走到这里说明有人启用了路由却没加分支，属实现错误，必须可见。
                else -> error("路由已启用但缺少导航实现：${route.javaClass.simpleName}")
            }
        }

        override fun requestLogin(reason: String?) {
            Log.i(TAG, "打开原生登录页：reason=$reason")
            openLogin()
        }

        override fun rejectNotEnabled(route: AppRoute, reason: String) {
            Log.w(TAG, "拒绝导航：${route.javaClass.simpleName} —— $reason")
        }
    }

    /**
     * 打开原生登录页（W2）。
     *
     * ⚠️ **必须幂等** —— `requestLogin()` 可能被连续触发：401 兜底、深链 auth gate、
     * 用户主动点击三条路径都会调它，而 401 在并发请求下可能来好几个。
     * 不去重的表现是「登录页叠了好几层，返回要按多次」（iOS 的 402 防抖
     * 处理的是同类问题）。用 tag 判定栈里是否已有。
     */
    private fun openLogin() {
        if (supportFragmentManager.findFragmentByTag(TAG_LOGIN) != null) {
            Log.i(TAG, "登录页已在栈中，忽略重复请求")
            return
        }
        supportFragmentManager.commit {
            replace(R.id.surface_container, LoginFragment.newInstance(), TAG_LOGIN)
            addToBackStack(TAG_LOGIN)
        }
    }

    /**
     * 挂载一个业务 Surface。
     *
     * @param route 业务参数来源。**必须传** —— P9 前这里只传 componentName，
     *   `SurfaceProps.forRoute` 的产出根本没接到调用链上，等于所有业务参数
     *   都没送出去（`characterId` 恒 undefined，聊天页恢复上次会话）。
     *   那正是 `SurfaceProps` 类注释里警告的「参数没生效」型漂移。
     *
     * ⚠️ 语言必须传壳的真值（`L10n.current`）：壳是唯一 writer（§2.16），
     * 不传会让 Surface 用 JS 侧的陈旧值。
     */
    private fun openSurface(componentName: String, route: AppRoute) {
        // ⚠️ **必须幂等**，同 [openLogin] / [openSearch]。
        //
        // Router 的去重只挡**同一个** route（lastHandled 是 route to source），
        // 挡不住「快速点两张**不同**卡片」—— 那是两个不同 route，会叠两层
        // Surface 容器。RN 侧对此有专门的 `globalNavigating` 闸
        // （`useChatNavigation.ts:45`），壳侧对应物就是这里。
        //
        // 这条同时是 §12 实例关闭链「只有单层容器所以弹不错」那个前提的**保证**：
        // 没有它，两层同类型容器一出现，固定传 null 的 popSurface 就会弹错。
        if (supportFragmentManager.findFragmentByTag(componentName) != null) {
            Log.i(TAG, "$componentName 已在栈中，忽略重复请求")
            return
        }
        supportFragmentManager.commit {
            replace(
                R.id.surface_container,
                RNSurfaceFragment.newInstance(
                    componentName = componentName,
                    routeParams = SurfaceProps.forRoute(route),
                    languageCode = L10n.current,
                ),
                // tag 用 componentName：退栈后要靠它判「该 Surface 已关闭」
                // 从而解除 Router 的去重（见 onBackStackChanged 监听）
                componentName,
            )
            addToBackStack(componentName)
        }
    }

    /**
     * 打开原生搜索页（W3）。
     *
     * ⚠️ **必须幂等**，同 [openLogin] 的理由：搜索入口在 Home 顶栏，连点两次
     * 会叠两层，返回要按两次。用 tag 判定栈里是否已有。
     */
    private fun openSearch() {
        if (supportFragmentManager.findFragmentByTag(TAG_SEARCH) != null) {
            Log.i(TAG, "搜索页已在栈中，忽略重复请求")
            return
        }
        supportFragmentManager.commit {
            replace(R.id.surface_container, SearchFragment.newInstance(), TAG_SEARCH)
            addToBackStack(TAG_SEARCH)
        }
    }

    /**
     * 打开他人主页（W3，进度文档 §2.32）。
     *
     * ## ⚠️ 幂等判定按 **userId** 分，不是只按 tag
     *
     * 与 [openSearch] / [openLogin] 不同：搜索页与登录页各自只有一个实例，
     * 有 tag 就够；但他人主页**可以合法地叠栈** —— A 的主页 → A 创作的角色 →
     * 那个角色的其它创作者 B 的主页，是真实路径（RN 侧 ProfileStack 就是这样）。
     *
     * 所以判定条件是「栈顶已经是**同一个人**的主页」才忽略（防连点叠两层），
     * 不同人则照常压栈。只按 tag 判会让「从 A 的页面点进 B」被当成重复请求
     * 而静默丢弃 —— 那正是 §8.3 禁止的 silent no-op。
     *
     * tag 里带 userId 也让 [supportFragmentManager] 的查找天然按人区分。
     */
    private fun openUserProfile(userId: String) {
        val tag = tagForUserProfile(userId)
        if (supportFragmentManager.findFragmentByTag(tag) != null) {
            Log.i(TAG, "该用户主页已在栈中，忽略重复请求")
            return
        }
        supportFragmentManager.commit {
            replace(R.id.surface_container, PublicProfileFragment.newInstance(userId), tag)
            addToBackStack(tag)
        }
    }

    /**
     * 打开原生设置列表（W3，§2.33）。
     *
     * ⚠️ **必须幂等**，同 [openLogin] / [openSearch]：入口在 Profile 顶栏，
     * 连点两次会叠两层。用 tag 判定。
     */
    private fun openSettings() {
        if (supportFragmentManager.findFragmentByTag(TAG_SETTINGS) != null) {
            Log.i(TAG, "设置页已在栈中，忽略重复请求")
            return
        }
        supportFragmentManager.commit {
            replace(R.id.surface_container, SettingsFragment.newInstance(), TAG_SETTINGS)
            addToBackStack(TAG_SETTINGS)
        }
    }

    private companion object {
        const val TAG = "MainActivity"

        /** 登录页的 Fragment tag —— [openLogin] 靠它做幂等判定。 */
        const val TAG_LOGIN = "login"

        /** 搜索页的 Fragment tag —— [openSearch] 靠它做幂等判定。 */
        const val TAG_SEARCH = "search"

        /** 他人主页的 tag 前缀 —— 带 userId，见 [openUserProfile] 的幂等注释。 */
        const val TAG_USER_PROFILE_PREFIX = "user_profile:"

        /** 设置页的 Fragment tag —— [openSettings] 靠它做幂等判定。 */
        const val TAG_SETTINGS = "settings"

        /**
         * `ChatDetailSurface` 容器的 tag（P9）。
         *
         * 值等于 componentName —— [openSurface] 用 componentName 作 tag，
         * 退栈后靠它判「该 Surface 已关闭」从而解除 Router 去重。
         */
        const val TAG_CHAT_DETAIL_SURFACE = "ChatDetailSurface"

        fun tagForUserProfile(userId: String) = "$TAG_USER_PROFILE_PREFIX$userId"

        /** 五 Tab 根的 Fragment tag。 */
        const val TAG_TABS = "tabs"
    }

    /**
     * 关闭当前 RN Surface 容器（RN 栈底返回键经桥调到这里）。
     *
     * ⚠️ **必须幂等**（ADR-003）：迟到的 popSurface 不得关掉后来打开的容器。
     * W1-P0 先用「栈里有 Surface 才 pop」这个最小保证；P4 接 Router 时改为
     * 按 `surfaceInstanceId` 精确匹配当前容器。
     *
     * iOS 的闸是**类型判定**，迟到事件弹错了同类型页（后用 closingRef 补）——
     * Android 从一开始按实例判定，别重复那个 bug。
     */
    private fun popSurface(surfaceInstanceId: String?) {
        if (supportFragmentManager.backStackEntryCount == 0) return

        val current = supportFragmentManager.findFragmentById(R.id.surface_container)
                as? RNSurfaceFragment

        // ⚠️ **按实例判定，不是按类型**（ADR-003 / §12.1）。
        // iOS 的 popSurface 闸是类型判定，于是「迟到的旧实例事件弹掉了新打开的
        // 同类型页」—— 用户点返回后又被弹掉一层，后来靠 closingRef 补。
        // Android 从第一天按实例判定，别重复那个 bug。
        if (surfaceInstanceId != null && current != null &&
            current.surfaceInstanceId != surfaceInstanceId
        ) {
            Log.i(
                TAG,
                "忽略迟到的 popSurface：请求 id 与当前容器不符（当前=${current.surfaceInstanceId}）",
            )
            return
        }

        supportFragmentManager.popBackStack()
    }

    /**
     * 挂载 DebugSurface。W0 的核心 gate：
     * Metro 直连与离线内嵌 bundle 两种来源都必须能挂上、能返回、能反复开关。
     */
    private fun openDebugSurface() {
        supportFragmentManager.commit {
            replace(R.id.surface_container, RNSurfaceFragment.newInstance("DebugSurface"))
            addToBackStack("DebugSurface")
        }
    }
}
