package ai.lightspeed.tipsy.shell.bridge

import ai.lightspeed.tipsy.shell.auth.AuthStateHub
import ai.lightspeed.tipsy.shell.auth.ShellTokenStore
import android.util.Log
import expo.modules.tipsyauth.TipsyAuthProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 壳侧 [TipsyAuthProvider] 实现。**注册它就等于让 RN 侧 `isShellHost()` 返回 true**，
 * 从而激活 RN 仓里那 55 个文件里已存在的壳适配分支（方案 §7.2）。
 *
 * ## 当前边界（W1-P1 已接通）
 *
 * auth 生命周期（取 token / 刷新 / 登出 / 401 归属判定）已委派 [ShellTokenStore]。
 * 仍未实现的：**登录 UI**（W2 原生 Login 页）、**导航**（P4 Router）、
 * **语言真值**（P5）、**402 付费墙**（P6）。
 *
 * ⚠️ token 的**历史数据迁移**（MMKV 三形态兼容读已就位，SecureStore 兜底未做）
 * 属 P2 —— 覆盖升级设备上 SecureStore 里的 token 目前读不出来，
 * 那批用户会被当作未登录。这是已知缺口，不是 bug。
 *
 * **未实现项一律走 [notImplemented]，绝不静默 no-op。** 理由是方案 §8.3 的纪律：
 * 「路由到尚未启用的目标必须给明确错误或安全兜底，**绝不静默 no-op**」。
 * 静默 no-op 在这个架构里的典型症状是「点了没反应」—— 不报错、不崩溃，
 * 只能靠用户反馈发现（iOS 在 ChatDetail 与 Comments 真实踩过）。
 *
 * 返回 null 与「未实现」是**两件事**，本类严格区分：
 * - `getValidToken()` 返回 null = **「当前未登录」**，是合法业务态，JS 会走未登录分支
 * - `requestLogin()` 未实现 = 能力缺失，必须可见
 */
class ShellAuthProvider(
    private val isDebugBuild: Boolean,
    /** 语言真值来源。P5 会换成壳的 L10n store；现在由调用方注入以免这里先假设实现。 */
    private val languageCodeProvider: () -> String?,
    /**
     * API 根地址（W1-P6）。壳内**优先于**构建期 `EXPO_PUBLIC_API_URL`，
     * 保证原生页与 RN Surface 命中同一后端。
     */
    private val apiBaseUrlProvider: () -> String? = { null },
    /** 关当前 Surface 容器。由 [ai.lightspeed.tipsy.shell.MainActivity] 注入。 */
    private val onPopSurface: (surfaceInstanceId: String?) -> Unit,
    /**
     * 导航到宝石购买页。402 兜底与桥的 `openGemsPurchase` **共用同一出口** ——
     * 两处各写一份会让「未启用」的判定漂移。
     * 默认 no-op 仅为测试便利；壳侧注入接 Router 的实现。
     */
    private val onNavigateGemsPurchase: (params: Map<String, String>) -> Unit = {},
    /** token 真值（W1-P1）。 */
    private val tokenStore: ShellTokenStore,
    /** 登录态广播（W1-P1，§3.4）。 */
    private val authStateHub: AuthStateHub,
    /**
     * 承载非 suspend 桥方法里的 fire-and-forget 工作（如无参 authRejected 触发登出）。
     * 由壳注入 Application 级 scope —— **不要在这里新建**，否则登出会随
     * 调用方作用域被取消，表现为"401 了但没登出"。
     */
    private val scope: CoroutineScope,
    /**
     * 日志出口。**可注入的理由与假绿色有关**：`android.util.Log` 在 JVM 单测里是
     * 「调用即抛」的 stub，而本类的 401 归属判定、未实现项兜底都会记日志 ——
     * 用真 Log 会让这些分支根本测不了。
     *
     * 备选是 `testOptions.unitTests.returnDefaultValues = true`，但那会让**所有**
     * 未 mock 的 Android API 静默返回默认值，是方案 §5.4 点名的假绿色，已否决
     * （进度文档 §2.12 记过同一决定，当时是为 `org.json`）。
     */
    private val logger: Logger = Logger.ANDROID,
    /**
     * 主线程 dispatcher。可注入仅为可测 —— JVM 单测里没有 Android 主 Looper，
     * 用真的 [Dispatchers.Main] 会抛 `Module with the Main dispatcher is missing`。
     */
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : TipsyAuthProvider {

    /** 极小的日志抽象。只有 info/warn/error 三档，够本类用。 */
    fun interface Logger {
        fun log(level: Level, message: String)

        enum class Level { INFO, WARN, ERROR }

        companion object {
            val ANDROID = Logger { level, message ->
                when (level) {
                    Level.INFO -> Log.i(TAG, message)
                    Level.WARN -> Log.w(TAG, message)
                    Level.ERROR -> Log.e(TAG, message)
                }
            }
        }
    }

    // ── SurfaceEnvContract ──────────────────────────────────────

    override fun currentLanguageCode(): String? = languageCodeProvider()

    /**
     * 壳侧 API 根地址（W1-P6 起是真值）。
     *
     * RN 的 `constants/api.ts` 会**优先**用这个值（`resolveBaseAPIURL`），
     * 拿到 null 才回退构建期 `EXPO_PUBLIC_API_URL`。
     *
     * 为什么壳要当真值：原生页与 RN Surface **必须命中同一后端** ——
     * 不一致会让两边看到不同数据，而且两边都不报错。
     */
    override fun apiBaseURL(): String? = apiBaseUrlProvider()

    /**
     * 返回 null（而非空串）= 壳无意见，JS 沿用自己的持久值。
     * ⚠️ 空串在契约里有特殊含义：**用户显式停用**，调用方不得再回退 —— 不要混用。
     */
    override fun boeLane(): String? = null

    // ── SurfaceAuthContract ─────────────────────────────────────

    /**
     * 委派 [ShellTokenStore]：临过期 single-flight 刷新后返回，无法恢复返回 null。
     *
     * 返回 null 是**合法业务态**（当前未登录），JS 走未登录分支 —— 与「未实现」不同。
     */
    override suspend fun getValidToken(): String? = tokenStore.getValidToken()

    /** 原生 Login UI 属 W2；P1 只接通 token 生命周期，不含登录入口。 */
    override fun requestLogin(reason: String?) =
        notImplemented("requestLogin(reason=$reason)", wave = "W2（原生 Login 页）")

    /**
     * 完整语义（W1 计划 §3.5）：失效 auth generation → 废弃在飞 refresh →
     * 清 token → **收敛返回栈** → 发**一次** loggedOut。
     *
     * 返回栈收敛由 [onPopSurface] 承担（W1 只有单层 Surface 容器）；
     * P4 接 Router 后改为收敛整个栈。
     */
    override suspend fun logout() {
        tokenStore.clearToken()
        // ⚠️ `logout()` 在契约里**不是** @MainThread（它主要做存储清理），但收栈动的是
        // FragmentManager —— 必须自己切主线程。桥的 onMain 只覆盖标了 @MainThread
        // 的方法，不会替这里做。
        // 漏掉的表现：从 appScope（Dispatchers.Default）触发的登出抛
        // CalledFromWrongThreadException，而 JS 直接调 logout() 的路径又恰好没事 ——
        // 典型的"只有某条路径崩"。
        withContext(mainDispatcher) { onPopSurface(null) }
        authStateHub.notifyDidLogout()
    }

    /**
     * 仅清 token（删号等场景），**不收栈、不发 loggedOut** ——
     * 与 [logout] 的区别在这里：调用方（如 DeleteAccountSurface）自己控制后续导航。
     */
    override suspend fun clearToken() = tokenStore.clearToken()

    /**
     * 无参版本：**只在拿不到具体 token 时使用**。
     *
     * ⚠️ 它无法判断被拒 token 是否仍是当前 token，所以会**无条件登出** ——
     * 旧账号迟到的 401 会误登出新账号。RN 侧已优先调带 token 的版本，
     * 这里保留只为兼容老 bundle（OTA 把老 JS 推给新 binary 的情形）。
     */
    override fun notifyServerAuthRejected() {
        logger.log(Logger.Level.WARN, "收到无参 authRejected —— 无法校验 token 归属，按当前会话登出")
        scope.launch { logout() }
    }

    /**
     * 带 token 的版本（W1 计划 §3.2）。
     *
     * **只有被拒 token 仍是当前 token 才登出。** 不匹配说明这是旧账号迟到的 401，
     * 此刻登出会把刚登录的新账号踢掉 —— TS 契约注释明确写了这条。
     *
     * 这里**刻意不打印 authToken**（token 不进 log）。
     */
    override suspend fun notifyServerAuthRejectedForToken(authToken: String) {
        if (!tokenStore.isCurrentToken(authToken)) {
            logger.log(Logger.Level.INFO, "忽略过期会话的 authRejected（被拒 token 已非当前 token）")
            return
        }
        logout()
    }

    // ── SurfaceNavigationContract ───────────────────────────────

    /**
     * 这是 P0 唯一**真正实现**的导航能力 —— W0 已有 FragmentManager 返回栈，
     * 且 `ChatDetailSurface` gate（P9）依赖它。
     */
    override fun popSurface(surfaceInstanceId: String?) = onPopSurface(surfaceInstanceId)

    override fun openUserProfile(userId: String) =
        notImplemented("openUserProfile", wave = "W1-P4（Router）")

    override fun openUserProfileWithRecommendation(userId: String, contextJSON: String) =
        notImplemented("openUserProfileWithRecommendation", wave = "W1-P4")

    /**
     * 打开宝石购买页。
     *
     * 交给 Router 而非直接 `notImplemented`：目标 `GemsSubscriptionSurface` 属 W4，
     * Router 会**明确拒绝并记日志**（§8.3 不做 silent no-op）。
     * 走 Router 的好处是「未启用」这个状态只在一处判定，W4 启用时不用改这里。
     */
    override fun openGemsPurchase(params: Map<String, String>) {
        onNavigateGemsPurchase(params)
    }

    /**
     * 服务端付费墙（HTTP 402）→ 导航宝石购买页（W1-P6）。
     *
     * ⚠️ **不能留 notImplemented**：401/402 由 [ApiErrorGate] 汇聚后调到这里，
     * 而 `notImplemented` 在 debug 下**会抛** —— 那意味着每次 402 都让 App 崩。
     * 之前这里标的是「W1-P6」，正是本步。
     *
     * 防抖在 [ApiErrorGate]（两个入口共享同一个窗口），这里不再做第二层。
     *
     * 目标页 `GemsSubscriptionSurface` 属 W4，所以 Router 现在会**明确拒绝**
     * 并记日志 —— 不是静默 no-op。
     */
    override fun notifyServerPaymentRequired() {
        onNavigateGemsPurchase(emptyMap())
    }

    // ── SurfaceLifecycleContract ────────────────────────────────

    override fun notifyOnboardingCompleted() =
        notImplemented("notifyOnboardingCompleted", wave = "W4")

    // ── 未实现项的统一出口 ────────────────────────────────────────

    /**
     * **绝不静默**。debug 直接抛，让问题在开发期就暴露；release 记 error 日志
     * 并继续（不把用户卡死在一个尚未接线的入口上）。
     *
     * 注意这里只记方法名与波次，**不记参数值** —— 参数可能含 token / 用户标识。
     */
    private fun notImplemented(what: String, wave: String) {
        val msg = "[ShellAuthProvider] 桥方法尚未实现：$what（计划波次：$wave）"
        if (isDebugBuild) {
            throw NotImplementedError(msg)
        }
        logger.log(Logger.Level.ERROR, msg)
    }

    private companion object {
        const val TAG = "ShellAuthProvider"
    }
}
