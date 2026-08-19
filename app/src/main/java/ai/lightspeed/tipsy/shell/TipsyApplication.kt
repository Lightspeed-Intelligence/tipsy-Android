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
import ai.lightspeed.tipsy.shell.analytics.Analytics
import ai.lightspeed.tipsy.shell.auth.AuthStateHub
import ai.lightspeed.tipsy.shell.auth.Generations
import ai.lightspeed.tipsy.shell.auth.LegacyMmkvStore
import ai.lightspeed.tipsy.shell.auth.MmkvTokenPersistence
import ai.lightspeed.tipsy.shell.auth.RefreshTokenApi
import ai.lightspeed.tipsy.shell.auth.ShellTokenStore
import ai.lightspeed.tipsy.shell.bridge.ShellAuthProvider
import ai.lightspeed.tipsy.shell.i18n.AccountLanguageReader
import ai.lightspeed.tipsy.shell.i18n.AssetLocaleLoader
import ai.lightspeed.tipsy.shell.i18n.L10n
import ai.lightspeed.tipsy.shell.i18n.LanguageCodes
import ai.lightspeed.tipsy.shell.network.ApiClient
import ai.lightspeed.tipsy.shell.network.ApiErrorGate
import ai.lightspeed.tipsy.shell.pages.profile.ProfileRefreshHub
import ai.lightspeed.tipsy.shell.router.AppRoute
import ai.lightspeed.tipsy.shell.router.AppRouter
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

            // Debug 直连 Metro 时的入口模块。**必须与 app/build.gradle 的
            // react.entryFile 保持一致** —— 两处不一致会出现「Metro 加载业务包、
            // 离线包却是自检包」的错配，且 debug 下看不出来（Metro 那份是对的），
            // 只有 release 或关掉 Metro 时才暴露。
            //
            // W1-CLOSEOUT-2 起切到业务入口：`index.surfaces.debug` 只注册
            // DebugSurface，任何业务 Surface 路由都会挂到一个不存在的组件上。
            override fun getJSMainModuleName(): String = "index.surfaces"

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

    /**
     * EditProfileSurface → 原生 Profile 的账号级刷新接力。
     * 注册为 [authStateHub] observer，登录/登出/换号会同步清掉旧账号 pending。
     */
    val profileRefreshHub = ProfileRefreshHub()

    /** token 真值。W2 原生 Login 页登录成功后调 [ShellTokenStore.onLoggedIn]。 */
    lateinit var tokenStore: ShellTokenStore
        private set

    /** 壳侧 API 客户端（W1-P6）。W2 的 Home / Login 用它发请求。 */
    lateinit var apiClient: ApiClient
        private set

    /**
     * 双 generation 闸门（§4.4）。auth 轨由 [tokenStore] 在 login/logout 时 bump；
     * mutation 轨由列表页的乐观变更 bump（W3 ChatList 的删除/置顶是第一个用例）。
     * 页面 ViewModel 经此引用共享**同一实例** —— 各建一个会让闸门互相看不见。
     */
    lateinit var generations: Generations
        private set

    /**
     * 401/402 的进程级唯一漏斗。[apiClient] 与 RN 桥 provider 共用此实例，
     * 因此 Native/RN 同时报错也只消费一个防抖窗口。
     */
    private lateinit var apiErrorGate: ApiErrorGate

    /**
     * RN 侧 MMKV 的入口（W1-P5 读账号语言；W2 起 `HomeFilterStore` 读写筛选）。
     *
     * `lazy` 而非 `lateinit`：`bootstrapI18n` 在 `registerAuthBridge` 之前跑，
     * 而 MMKV 的初始化在两处都要用 —— 用 lazy 让谁先用谁触发，不依赖调用顺序。
     *
     * ⚠️ 这个 lazy 把实例缓存到**进程结束**，所以 `LegacyMmkvStore.open` 必须
     * 在目录不存在时建目录而不是返回不可用实例（那个缺陷已修，见该类注释）。
     */
    val sharedMmkvStore by lazy { LegacyMmkvStore.open(this) }

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

    /**
     * 壳内页面发起导航的入口（W2）。
     *
     * ⚠️ **不要让业务页直接持 Router** —— 方案 §4.7 要求单一入口，而 Router 归
     * 当前 Activity（它才有 FragmentManager）。业务 Fragment 经这里转接，
     * Activity 在 onCreate/onDestroy 设置与清除。
     *
     * 为 null 时（无 Activity 在前台）**安全跳过并记日志**，同
     * [onNavigateGemsPurchaseRequested] 的理由。
     */
    var onRouteRequested: ((AppRoute, AppRouter.Source) -> Unit)? = null

    /**
     * 拉起原生登录页（W2，§2.20）。同 [onPopSurfaceRequested] 的理由用回调转接。
     *
     * 单列一个而不是复用 [onRouteRequested]：登录不是导航到某个目标，而是
     * `AppRouter.requestLogin` 的排队语义 —— 登录成功后要 flush pendingRoute。
     *
     * ⚠️ 为 null 时（无前台 Activity）**安全跳过并记日志**。桥的未登录路径
     * 可能来自后台请求，那时没有 UI 可拉起登录页。
     */
    var onRequestLoginRequested: ((String?) -> Unit)? = null

    /** 业务页调这个而不是直接碰 Router。 */
    fun requestRoute(route: AppRoute, source: AppRouter.Source) {
        val handler = onRouteRequested
        if (handler == null) {
            Log.w(TAG, "导航请求到达但无前台 Activity，已跳过：${route.javaClass.simpleName}")
            return
        }
        handler(route, source)
    }

    override fun onCreate() {
        super.onCreate()
        DefaultNewArchitectureEntryPoint.releaseLevel = ReleaseLevel.STABLE
        loadReactNative(this)
        // i18n 必须早于 registerAuthBridge：桥的 getCurrentLanguageCode 会读
        // L10n.current，而 index.surfaces.js 在 runtime 启动时就调它对齐 i18n
        bootstrapI18n()
        installAnalytics()
        authStateHub.addObserver(profileRefreshHub)
        registerAuthBridge()
        // Expo 模块的 Application 生命周期分发；autolinked 模块依赖它
        ApplicationLifecycleDispatcher.onApplicationCreate(this)
    }

    /**
     * 装配埋点 facade（进度文档 §2.17）。
     *
     * **Qt 尚未接线**（owner 已决策推迟到业务迁移后），所以当前 sink 只落日志。
     * 接 Qt 时只改这一处，业务页一行不动 —— 那正是先建 facade 的目的。
     *
     * ⚠️ release 下也保留日志？**不**：埋点参数里可能有 uid 等标识，
     * 且量很大（每张卡片曝光一条）。只在 debug 打。
     */
    private fun installAnalytics() {
        Analytics.install { eventId, params, pageName ->
            if (BuildConfig.DEBUG) {
                Log.d(TAG_ANALYTICS, "$eventId page=$pageName params=$params")
            }
            // Qt 的真实出口在这里接（QtConfigure.preInit + onEventWithParams）。
            // ⚠️ 现状是 Qt 的 preInit 在壳里**一次都不会调**（进度文档 §2.17
            // 实测：QtPackage 只实现 ReactActivityLifecycleListener，而壳没有
            // ReactActivity）—— 接线时必须先解决初始化，不能只加调用
        }
    }

    /**
     * 装配 i18n（W1-P5，方案 §4.8）。
     *
     * **两阶段初始化**（对齐 RN，方案 §4.6）：
     * 1. 这里先按**设备 locale** 起步 —— 冷启动时还没有 user
     * 2. 拿到账号后按 `user.language_code` 覆盖（见 [refreshAccountLanguage]）
     *
     * ⚠️ 两阶段的必然结果是**首屏读到的可能是过渡语言**。方案 §4.6 与 W1
     * 计划 §7.6 都明确：**不要拿语言当缓存闸** —— iOS 那样做导致「第二次启动
     * 永远没有种子」。当前壳还没有缓存层（W2/W3 才有），此处只留约束说明。
     */
    private fun bootstrapI18n() {
        L10n.bootstrap(
            loader = AssetLocaleLoader(this),
            // ⚠️ 设备 locale 走 fromDeviceLocale 而**不是** normalize ——
            // 两条规则对简体 zh 给不同答案（en vs zh-tw），见 LanguageCodes 注释
            initialLanguage = LanguageCodes.fromDeviceLocale(deviceLanguageTag()),
            listener = { code ->
                // 桥广播收口在 L10n.setLanguage 里，所以这里是**唯一**发射点。
                // 运行中的 Surface 靠它同步 i18next（index.surfaces.js:102-107）
                TipsyAuthRegistry.notifyLanguageChanged(code)
            },
            logger = { Log.w(TAG, it) },
        )
        refreshAccountLanguage()
    }

    /**
     * 按账号语言覆盖（两阶段的第 2 步）。
     *
     * 也用于「RN 设置页改完语言后壳需要重读」—— 桥契约没有 JS→壳 的语言
     * 通知方法（已核实），所以由 [MainActivity] 在 Surface 容器关闭时调这里。
     * 账号无语言意见时**不覆盖**，保留设备默认。
     */
    fun refreshAccountLanguage() {
        val raw = sharedMmkvStore.getString(AccountLanguageReader.USER_STORAGE_KEY)
        val accountLanguage = AccountLanguageReader.parse(raw) ?: return
        L10n.setLanguage(accountLanguage)
    }

    /** 设备 locale 的 BCP-47 tag。 */
    private fun deviceLanguageTag(): String =
        resources.configuration.locales.takeIf { it.size() > 0 }?.get(0)?.toLanguageTag()
            ?: java.util.Locale.getDefault().toLanguageTag()

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
        generations = Generations()
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
            // W1-P5：壳成为语言的唯一 writer。`index.surfaces.js:45-49` 在 runtime
            // 启动时读它对齐 i18next，运行中的变化经 onLanguageChanged 事件接力。
            languageCodeProvider = { L10n.current },
            onPopSurface = { instanceId -> onPopSurfaceRequested?.invoke(instanceId) },
            // W2 §2.20：原生登录页。桥的 requestLogin 与 401 兜底走同一出口。
            onRequestLogin = { reason -> onRequestLoginRequested?.invoke(reason) },
            // 桥发起的导航统一经 Router（Source.BRIDGE）——白名单、auth gate、
            // 去重只在 Router 判一次。为 null（无前台 Activity）时安全跳过。
            onRequestRoute = { route -> requestRoute(route, AppRouter.Source.BRIDGE) },
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
            onProfileRefreshRequested = { ownerUserId ->
                profileRefreshHub.notifyProfileChanged(ownerUserId)
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

        /** 埋点日志单独一个 tag，便于 `logcat -s` 过滤（量大）。 */
        const val TAG_ANALYTICS = "TipsyAnalytics"
    }
}
