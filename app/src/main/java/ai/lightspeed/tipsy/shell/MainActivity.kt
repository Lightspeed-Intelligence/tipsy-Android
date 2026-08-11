package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.i18n.LocalizedText
import ai.lightspeed.tipsy.shell.i18n.rememberCurrentLanguage
import ai.lightspeed.tipsy.shell.pages.login.LoginFragment
import androidx.activity.enableEdgeToEdge
import ai.lightspeed.tipsy.shell.router.AppRoute
import ai.lightspeed.tipsy.shell.router.AppRouter
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.fragment.app.commit
import com.facebook.react.modules.core.DefaultHardwareBackBtnHandler

/**
 * 壳的宿主 Activity。
 *
 * 方案 ADR-002：`AppCompatActivity` + FragmentManager 承载两类页面 ——
 * 原生页是 Fragment 内挂 [ComposeView]，RN 页是 [RNSurfaceFragment]。
 * FragmentManager 统一处理返回栈、saved state、predictive back 与进程重建。
 *
 * W0 边界：这里还没有五 Tab 与 Router（W1/W2 的事），只提供一个能验证
 * 「原生根可显示 + 能挂载/卸载 RN Surface」的最小宿主。
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

        // 语言可能在 RN Surface 里被改（语言设置页刻意留在 RN，方案 §8.1），
        // 而桥契约**没有 JS→壳 的语言通知方法**（已核实 tipsy-auth 只有壳→JS 的
        // onLanguageChanged）—— 所以壳在 Surface 容器出栈后自己重读。
        //
        // 用 back stack listener 而不是在 popSurface() 里调：返回键有**两条**路径
        // （桥的 popSurface / 系统返回键直接走 FragmentManager），只挂前者会让
        // 「按系统返回键退出设置页」这条路漏掉语言更新。listener 覆盖两者。
        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                app.refreshAccountLanguage()
            }
        }

        router = AppRouter(
            navigator = ShellNavigator(),
            isLoggedIn = { app.tokenStore.hasToken() },
            authStateHub = app.authStateHub,
            logger = { Log.i(TAG, it) },
        )
        // W1 只启用 ChatDetail（P9 的 gate 对象）。其余目标随波次开 ——
        // §8.3：未过 §9.1 矩阵的 Surface 不得接生产入口。
        router.markEnabled(AppRoute.ChatDetail::class.java)

        if (savedInstanceState == null) {
            // 原生根：证明壳自己能先渲染，不依赖 RN
            findViewById<ComposeView>(R.id.native_root).setContent {
                MaterialTheme {
                    ShellHomeScreen(
                        onOpenSurface = { openDebugSurface() },
                        onOpenLogin = { openLogin() },
                    )
                }
            }
            // 冷启动的深链：Intent 已带 data
            router.handleUri(intent?.data?.toString(), AppRouter.Source.DEEP_LINK)
        }
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
                is AppRoute.ChatDetail -> openSurface("ChatDetailSurface")
                // 其余目标尚未启用，Router 的 enabledRoutes 会先拦下 ——
                // 走到这里说明有人 markEnabled 了却没加分支，属实现错误，必须可见。
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

    private fun openSurface(componentName: String) {
        supportFragmentManager.commit {
            replace(R.id.surface_container, RNSurfaceFragment.newInstance(componentName))
            addToBackStack(componentName)
        }
    }

    private companion object {
        const val TAG = "MainActivity"

        /** 登录页的 Fragment tag —— [openLogin] 靠它做幂等判定。 */
        const val TAG_LOGIN = "login"
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

@Composable
private fun ShellHomeScreen(onOpenSurface: () -> Unit, onOpenLogin: () -> Unit) {
    val language by rememberCurrentLanguage()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Tipsy Android Shell",
                style = MaterialTheme.typography.headlineSmall,
            )
            // W1-P5 起原生页文案走 LocalizedText（自订阅语言变化）。
            // **不要写 Text(L10n.t(key))** —— 那样 Compose 不知道读了可变状态，
            // 语言切换后已组合的文本不重组，表现为「切了语言当前页没变」。
            LocalizedText(
                key = "Loading",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "lang=$language",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Button(onClick = onOpenSurface, modifier = Modifier.padding(top = 24.dp)) {
                Text("挂载 DebugSurface")
            }
            // 原生登录页入口。W2 的五 Tab shell 到位后这个自检根会整体替掉，
            // 在那之前它是唯一能手工进登录页的路径（桥的 requestLogin 也进同一页）
            Button(onClick = onOpenLogin, modifier = Modifier.padding(top = 12.dp)) {
                Text("打开原生登录页")
            }
        }
    }
}
