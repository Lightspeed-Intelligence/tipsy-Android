package ai.lightspeed.tipsy.shell

import android.app.Application
import android.content.res.Configuration
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.ReactNativeHost
import com.facebook.react.ReactPackage
import com.facebook.react.common.ReleaseLevel
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint
import com.facebook.react.defaults.DefaultReactNativeHost
import ai.lightspeed.tipsy.shell.auth.AuthStateHub
import ai.lightspeed.tipsy.shell.auth.Generations
import ai.lightspeed.tipsy.shell.auth.MmkvTokenPersistence
import ai.lightspeed.tipsy.shell.auth.RefreshTokenApi
import ai.lightspeed.tipsy.shell.auth.ShellTokenStore
import ai.lightspeed.tipsy.shell.bridge.ShellAuthProvider
import ai.lightspeed.tipsy.shell.network.ApiClient
import ai.lightspeed.tipsy.shell.network.ApiErrorGate
import android.util.Log
import expo.modules.ApplicationLifecycleDispatcher
import okhttp3.OkHttpClient
import expo.modules.ReactNativeHostWrapper
import expo.modules.tipsyauth.TipsyAuthRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 壳 Application。
 *
 * 方案 ADR-003：**一个进程一个 React Runtime**。这里只建一个 ReactHost，
 * 所有 RN 页面都是它之上的 Surface —— 不得为页面新建 Runtime。
 *
 * 实现 [ReactApplication] 的作用不只是"提供 host"：`ReactFragment` 默认经
 * `activity.application as ReactApplication` 取 reactHost，实现了它就能复用
 * 官方的生命周期转发，不必自己实现 onHostResume/Pause/Destroy（方案 ADR-002）。
 *
 * W1-P0 起这里注册 auth 桥 provider（见 [registerAuthBridge]）。
 * 埋点 / 营销 SDK / 推送仍刻意不做 —— 那些属方案 §4.2 的 root side-effect 清单，
 * 各由所属波次按"单一 owner"契约接入；提前初始化会与 RN 侧双写（iOS 真实踩过）。
 */
class TipsyApplication : Application(), ReactApplication {

    override val reactNativeHost: ReactNativeHost = ReactNativeHostWrapper(
        this,
        object : DefaultReactNativeHost(this) {
            override fun getPackages(): List<ReactPackage> = PackageList(this).packages

            // Debug 直连 Metro 时的入口模块。W0 严格隔离期指向零业务依赖的
            // 自检入口，与 app/build.gradle 的 react.entryFile 保持一致 ——
            // 两处不一致会出现"Metro 加载业务包、离线包却是自检包"的错配。
            override fun getJSMainModuleName(): String = "index.surfaces.debug"

            override fun getUseDeveloperSupport(): Boolean = BuildConfig.DEBUG

            override val isNewArchEnabled: Boolean = true
        }
    )

    override val reactHost: ReactHost
        // ReactNativeHostWrapper.createReactHost → ExpoReactHostFactory 内部有
        // `if (reactHost == null)` 缓存，所以这个 getter 每次返回同一实例，
        // 单 Runtime 不变量成立（已核实 ExpoReactHostFactory.kt:85）。
        get() = ReactNativeHostWrapper.createReactHost(applicationContext, reactNativeHost)

    /**
     * 持 provider 的强引用 —— registry 侧是弱引用，这里不持就会被回收。
     */
    private var authProvider: ShellAuthProvider? = null

    /**
     * 进程级作用域。`SupervisorJob` 让单个失败的子任务不连带取消其他 ——
     * 桥的 fire-and-forget（当前为 402 导航）不能因为别处的异常被取消。
     *
     * 刻意**不**在 provider 内部新建 scope：那样登出会随调用方作用域被取消，
     * 表现为"401 了但没登出"，且只在特定时序下发生。
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * token refresh 的进程级作用域。refresh HTTP 自身会在 [RefreshTokenApi] 内切到 IO；
     * 回到这里后必须在 Main.immediate 完成 token 状态迁移与 loggedOut 分发，因为
     * [authStateHub] 的 Router/未来常驻 UI observer 都是主线程状态。若复用上面的
     * Default scope，refresh 自动失效会从后台线程直接改 Router，形成数据竞争。
     */
    private val tokenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 壳内登录态广播（W1-P1，§3.4）。W2 的五 Tab 直接订阅它 ——
     * iOS 因为常驻 Tab 只广播给 RN 桥，踩过"登录后无人重拉""登出串账号数据"。
     */
    val authStateHub = AuthStateHub()

    /** token 真值。W2 原生 Login 页登录成功后调 [ShellTokenStore.onLoggedIn]。 */
    lateinit var tokenStore: ShellTokenStore
        private set

    /** 壳侧 API 客户端（W1-P6）。W2 的 Home / Login 用它发请求。 */
    lateinit var apiClient: ApiClient
        private set

    /**
     * 401/402 的进程级唯一漏斗。[apiClient] 与 RN 桥 provider 共用此实例，
     * 因此 Native/RN 同时报错也只消费一个防抖窗口。
     */
    private lateinit var apiErrorGate: ApiErrorGate

    /**
     * 当前承载 Surface 的容器提供的"关闭自己"回调。
     * 由 [MainActivity] 在 onCreate/onDestroy 设置与清除 ——
     * Application 不该直接持 Activity 引用（泄漏），所以用可空回调转接。
     */
    var onPopSurfaceRequested: ((String?) -> Unit)? = null

    /**
     * 当前 Activity 的 Router 入口（W1-P6）。同 [onPopSurfaceRequested] 的理由：
     * Application 不该持 Activity 引用，用可空回调转接，onDestroy 清除。
     *
     * ⚠️ 为 null 时（无 Activity 在前台）**必须安全跳过而不是抛** ——
     * 402 可能在后台请求里触发，那时没有 UI 可导航。
     */
    var onNavigateGemsPurchaseRequested: ((Map<String, String>) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        DefaultNewArchitectureEntryPoint.releaseLevel = ReleaseLevel.STABLE
        loadReactNative(this)
        registerAuthBridge()
        // Expo 模块的 Application 生命周期分发；autolinked 模块依赖它
        ApplicationLifecycleDispatcher.onApplicationCreate(this)
    }

    /**
     * 注册 auth 桥 provider（W1-P0）。
     *
     * **时机很关键**：必须早于任何 Surface 的 JS 运行。RN 侧 `isShellAuthHost()`
     * 会**缓存首次结果**（它在高频 render 路径上被调用，如 ChatPage），
     * 注册晚了会让 JS 永久认为不在壳内 —— 这类 bug 只在冷启动竞态下出现，极难查。
     * 放在 `onCreate` 里、`ApplicationLifecycleDispatcher` 之前是最早的可用点。
     *
     * provider 在 registry 里是**弱引用**（对齐 iOS 的 `weak var`），
     * 所以这里必须持一个强引用字段，否则会被回收、`isShellHost()` 悄悄变回 false。
     */
    private fun registerAuthBridge() {
        // 手写装配（ADR-005：W1/W2 不引 DI 框架 —— 不把「引入 DI」与
        // 「首次 brownfield 集成」混在一起，两个都失败时无法二分定位）。
        val generations = Generations()
        tokenStore = ShellTokenStore(
            persistence = MmkvTokenPersistence.open(this),
            refreshApi = RefreshTokenApi(
                baseUrl = BuildConfig.API_BASE_URL,
                appVersion = BuildConfig.VERSION_NAME,
                downloadChannel = BuildConfig.DOWNLOAD_CHANNEL,
            ),
            generations = generations,
            scope = tokenScope,
            listener = object : ShellTokenStore.Listener {
                override fun onTokenCleared() {
                    // tokenStore 的唯一 clear 事件同时驱动两个消费端。
                    // provider.logout 不再单独 notify hub，否则主动登出会广播两次；
                    // refresh 失败自动清 token 也会经此到达壳内常驻页。生产 refresh
                    // 由 tokenScope 保证回到 Main.immediate，不能把 Router observer 发在 Default。
                    TipsyAuthRegistry.notifyAuthStateChanged("loggedOut")
                    authStateHub.notifyDidLogout()
                }
            },
        )

        // 先创建唯一 gate，再同时注入 provider 与 ApiClient。gate 的终端
        // 回调捕获 provider，但只会在完成赋值/注册后的真实网络事件中运行。
        lateinit var provider: ShellAuthProvider
        apiErrorGate = ApiErrorGate(
            onAuthRejected = { token -> provider.handleServerAuthRejectedForToken(token) },
            onPaymentRequired = { provider.handleServerPaymentRequired() },
            logger = { Log.i(TAG, it) },
        )

        provider = ShellAuthProvider(
            isDebugBuild = BuildConfig.DEBUG,
            // P5 会换成壳的 L10n store。现在返回 null = 壳无意见，
            // RN 侧沿用自己的语言判定，不至于被一个假值锁死。
            languageCodeProvider = { null },
            onPopSurface = { instanceId -> onPopSurfaceRequested?.invoke(instanceId) },
            onNavigateGemsPurchase = { params ->
                val handler = onNavigateGemsPurchaseRequested
                if (handler == null) {
                    // 后台请求触发 402 时没有 UI 可导航。记录而非静默丢弃 ——
                    // 否则排查「充值页没弹」时无从判断是没触发还是没 UI
                    Log.w(TAG, "402/宝石购买请求到达但无前台 Activity，已跳过")
                } else {
                    handler(params)
                }
            },
            tokenStore = tokenStore,
            apiErrorGate = apiErrorGate,
            scope = appScope,
            // W1-P6：壳成为 API 地址的真值。RN 侧 `getShellBaseAPIURL()` 会优先用它，
            // 保证**原生页与 RN Surface 命中同一后端** —— 不一致会让两边看到不同数据
            // 且都不报错（RN 侧 `constants/api.ts` 已备好这条通道）。
            apiBaseUrlProvider = { BuildConfig.API_BASE_URL },
        )
        authProvider = provider
        TipsyAuthRegistry.register(provider)

        // 网络层（W1-P6）。**共享 RN 的 OkHttpClient** —— 见 ApiClient 注释：
        // 各起一套会让连接池/DNS/TLS session 变成两份，且「同一后端两条链路」难查。
        apiClient = ApiClient(
            client = sharedOkHttpClient(),
            baseUrl = BuildConfig.API_BASE_URL,
            tokenStore = tokenStore,
            errorGate = apiErrorGate,
            appVersion = BuildConfig.VERSION_NAME,
            downloadChannel = BuildConfig.DOWNLOAD_CHANNEL,
            // P7 接壳的 lane store 后换成真值；现在 null = 壳无意见
            laneProvider = { null },
        )
    }

    /**
     * 取 RN 的 [OkHttpClient]，取不到则新建。
     *
     * RN 通过 `OkHttpClientProvider` 暴露它自己的 client。用反射是因为
     * 直接依赖 `com.facebook.react.modules.network.OkHttpClientProvider` 会把壳
     * 绑到 RN 的内部 API 上（它在 RN 版本间改过签名）。
     *
     * **失败时新建一个而不是抛** —— 共享是优化，不是正确性前提；
     * 拿不到就退化成两个 client，功能不受影响。
     */
    private fun sharedOkHttpClient(): OkHttpClient = runCatching {
        val cls = Class.forName("com.facebook.react.modules.network.OkHttpClientProvider")
        cls.getMethod("getOkHttpClient").invoke(null) as OkHttpClient
    }.getOrElse {
        Log.w(TAG, "未能取到 RN 的 OkHttpClient（${it.javaClass.simpleName}），壳自建一个")
        OkHttpClient()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ApplicationLifecycleDispatcher.onConfigurationChanged(this, newConfig)
    }

    private companion object {
        const val TAG = "TipsyApplication"
    }
}
