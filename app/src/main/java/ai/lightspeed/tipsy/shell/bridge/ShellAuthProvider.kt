package ai.lightspeed.tipsy.shell.bridge

import ai.lightspeed.tipsy.shell.auth.ShellTokenStore
import ai.lightspeed.tipsy.shell.network.ApiErrorGate
import ai.lightspeed.tipsy.shell.router.AppRoute
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
 * ## 当前边界
 *
 * auth 生命周期（取 token / 刷新 / 登出 / 401 归属判定）已委派 [ShellTokenStore]。
 * P5 已由壳的 L10n 提供语言真值。**登录 UI**（W2，§2.20）与**他人主页**
 * （W3，§2.32）已接通。402 已进共享 gate；宝石页未启用时由 Router 明确拒绝。
 * 仍未实现的只有 [notifyOnboardingCompleted]（W4）。
 *
 * ⚠️ **能力落地后必须回来回填这里的 override**。2026-08-17 查出三个
 * `notImplemented` 的波次标签早已过期（`requestLogin` 标 W2、两个
 * `openUserProfile` 标 W1-P4），而对应能力分别在 §2.20 / §2.32 就落地了 ——
 * 桩留在原地，debug 下变成「点了就崩」。根因是**桥能力回填此前零单测覆盖**，
 * 现由 `ShellAuthProviderBridgeWiringTest` 逐个钉死。
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
    /** 语言真值来源；生产装配注入壳的 `L10n.current`，测试可替换。 */
    private val languageCodeProvider: () -> String?,
    /**
     * API 根地址（W1-P6）。壳内**优先于**构建期 `EXPO_PUBLIC_API_URL`，
     * 保证原生页与 RN Surface 命中同一后端。
     */
    private val apiBaseUrlProvider: () -> String? = { null },
    /** 关当前 Surface 容器。由 [ai.lightspeed.tipsy.shell.MainActivity] 注入。 */
    private val onPopSurface: (surfaceInstanceId: String?) -> Unit,
    /**
     * 拉起原生登录页（W2 已落地，§2.20）。
     *
     * ⚠️ **不能留 notImplemented**：`axios.ts:160` 在壳宿主下取不到有效 token
     * 就调它，即**每个 axiosAuth 请求的未登录路径都会打到这里**，
     * 而 [notImplemented] 在 debug 会抛。
     *
     * 之所以不走 [onRequestRoute]：登录不是"导航到 Login 目标"，而是
     * `AppRouter.requestLogin` 的排队语义（登录后要 flush pendingRoute）。
     * 混成一条会让「登录后恰好执行一次」那套逻辑绕过 Router。
     */
    private val onRequestLogin: (reason: String?) -> Unit = {},
    /**
     * 桥发起的导航请求。**统一经 Router**（`Source.BRIDGE`）而不是各给一个
     * 专用回调 —— auth gate、白名单判定、去重都在 Router 一处，
     * 桥不该有第二套判定（§4.7 单一入口）。
     *
     * ⚠️ 因此 `openUserProfile` 这类方法**不能留 notImplemented**：
     * ChatDetail 深栈有三个调用点（`comments.tsx:2012`、
     * `CharacterProfile.tsx:1291,1294`），且最后那处**没接 `.catch`** ——
     * debug 抛会变成未处理的 promise rejection，表现是「聊天页点头像没反应」。
     */
    private val onRequestRoute: (route: AppRoute) -> Unit = {},
    /**
     * 导航到宝石购买页。402 兜底与桥的 `openGemsPurchase` **共用同一出口** ——
     * 两处各写一份会让「未启用」的判定漂移。
     * 默认 no-op 仅为测试便利；壳侧注入接 Router 的实现。
     */
    private val onNavigateGemsPurchase: (params: Map<String, String>) -> Unit = {},
    /**
     * EditProfileSurface 成功修改后，向原生 Profile 发账号归属明确的刷新信号。
     * userId 由本 provider 从 [tokenStore] 解析，桥方法不接收 JS userId。
     */
    private val onProfileRefreshRequested: (ownerUserId: String) -> Unit = {},
    /**
     * CreateSurface 创建/编辑角色成功后的创作列表失效信号
     * （`create/profileDetail.tsx:1574`，iOS 先行）。无账号 payload ——
     * 本 provider 确认当前确有登录账号后才转发（iOS `guard userId` 同义），
     * 消费方（Profile）自守 tab 边界与登出复位。
     */
    private val onCreatedCharactersChanged: () -> Unit = {},
    /**
     * RN Surface 建群/群成员变更后的会话列表刷新信号
     * （`ChatGroupSettingsPanel.tsx:118`、`chat-group-member-picker.tsx:600`）。
     * iOS 同义实现是无守卫即时广播（NotificationCenter → silentRefreshFirstPage），
     * 这里同样不加账号守卫 —— 消费方的 hasLoadedOnce/登出复位已构成边界。
     */
    private val onChattedListChanged: () -> Unit = {},
    /** token 真值（W1-P1）。 */
    private val tokenStore: ShellTokenStore,
    /**
     * 进程级 401/402 唯一漏斗。Native [ai.lightspeed.tipsy.shell.network.ApiClient]
     * 与 RN 桥入口必须注入**同一实例**，否则两边会各自防抖。
     */
    private val apiErrorGate: ApiErrorGate,
    /**
     * 承载非 suspend 桥方法里的 fire-and-forget 工作（当前是 paymentRequired
     * 进入 suspend 漏斗）。
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

    /**
     * 拉起原生登录页（W2 已落地，§2.20）。
     *
     * ⚠️ 曾经标 `notImplemented(wave = "W2")` 并**在原生登录页落地后忘了回填**
     * （2026-08-17 查出，P9 前置）。它是 `axiosAuth` 未登录路径的终点，
     * debug 下那个 throw 会让每个未登录请求崩一次。
     * 幂等在 Router/Activity 一侧（登录页可能被 401、深链、点击三路并发触发）。
     */
    override fun requestLogin(reason: String?) = onRequestLogin(reason)

    /**
     * 当前语义：失效 auth generation / 废弃 refresh / 清 token / 广播一次 / 收栈。
     * 返回栈收敛由 [onPopSurface] 承担（W1 只有单层 Surface 容器）。
     *
     * W1 计划 §3.5 的最终顺序写的是 `clear → pop → emit`；当前唯一 listener 在 clear
     * 临界区同步 emit，所以调用顺序仍是 `clear → emit → pop`。现有 listener 只做有界
     * 状态分发，且整段在主线程，不构成本包跨账号 blocker；精确顺序仍需后续收口。
     */
    override suspend fun logout() {
        // ⚠️ `logout()` 在契约里**不是** @MainThread（它主要做存储清理），但收栈动的是
        // FragmentManager —— 必须自己切主线程。桥的 onMain 只覆盖标了 @MainThread
        // 的方法，不会替这里做。
        // 漏掉的表现：从 appScope（Dispatchers.Default）触发的登出抛
        // CalledFromWrongThreadException，而 JS 直接调 logout() 的路径又恰好没事 ——
        // 典型的"只有某条路径崩"。
        withContext(mainDispatcher) {
            // 默认 notifyListener=true。TipsyApplication 的唯一 listener 同时通知
            // RN Registry 与壳内 AuthStateHub；这里不再另发一次。
            // 清理与收栈在同一主线程顺序段，不给 UI 登录动作留夹入窗口。
            tokenStore.clearToken()
            onPopSurface(null)
        }
    }

    /**
     * 仅清 token（删号等场景），**不收栈、不发 loggedOut** ——
     * 与 [logout] 的区别在这里：调用方（如 DeleteAccountSurface）自己控制后续导航。
     */
    override suspend fun clearToken() = tokenStore.clearToken(notifyListener = false)

    /** 无参 401 无法证明会话归属，必须像 Native 的 `401(null)` 一样可诊断地忽略。 */
    override fun notifyServerAuthRejected() {
        logger.log(Logger.Level.WARN, "收到无参 authRejected —— 无法校验 token 归属，已忽略")
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
        apiErrorGate.onUnauthorized(authToken)
    }

    /**
     * [ApiErrorGate] 通过防抖后的终端处理。与上面的桥入口分开，
     * 是为了避免“provider → gate → provider 入口”递归。
     */
    internal suspend fun handleServerAuthRejectedForToken(authToken: String): Boolean =
        withContext(mainDispatcher) {
            // 归属校验与清理在 tokenStore 内原子完成，且与收栈共处主线程顺序段。
            // 若等 token 锁时 B 已登录，原子比较会返回 false；若清 A 成功，
            // 从返回 true 到同步 pop 之间，主线程的 B 登录动作无法夹入。
            if (!tokenStore.clearTokenIfCurrent(authToken)) {
                logger.log(Logger.Level.INFO, "忽略过期会话的 authRejected（被拒 token 已非当前 token）")
                return@withContext false
            }
            onPopSurface(null)
            true
        }

    // ── SurfaceNavigationContract ───────────────────────────────

    /**
     * 这是 P0 唯一**真正实现**的导航能力 —— W0 已有 FragmentManager 返回栈，
     * 且 `ChatDetailSurface` gate（P9）依赖它。
     */
    override fun popSurface(surfaceInstanceId: String?) = onPopSurface(surfaceInstanceId)

    /**
     * 打开他人主页（W3 已落地，§2.32；`AppRoute.UserProfile` 在生产白名单里）。
     *
     * ⚠️ 曾经标 `notImplemented(wave = "W1-P4")` 并**在他人主页落地后忘了回填**
     * （2026-08-17 查出，P9 前置）。ChatDetail 深栈三个调用点会打到这里，
     * 其中 `CharacterProfile.tsx:1294` 未接 `.catch`。
     *
     * 空 userId 的拦截在 Router（单一入口，挡住所有调用方），这里不重复判。
     */
    override fun openUserProfile(userId: String) =
        onRequestRoute(AppRoute.UserProfile(userId))

    /**
     * 带推荐归因的他人主页（`CharacterProfile.tsx:1287`）。
     *
     * JS 侧对这个方法的失败**有 `.catch` 回落到 [openUserProfile]**，所以即使
     * 归因链路出问题也还能开页 —— 但那条回落过去同样落在 throw 上，
     * 两个都得实现才有意义。
     */
    override fun openUserProfileWithRecommendation(userId: String, contextJSON: String) =
        onRequestRoute(
            AppRoute.UserProfile(userId, recommendationContextJSON = contextJSON),
        )

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
        scope.launch { apiErrorGate.onPaymentRequired() }
    }

    /** [ApiErrorGate] 通过防抖后的 402 终端处理。 */
    internal suspend fun handleServerPaymentRequired() {
        // Native ApiClient 在 Dispatchers.IO 解析响应，因此 gate 的回调不能
        // 假设自己来自桥的主线程。Router/FragmentManager 统一在这里切主线程。
        withContext(mainDispatcher) { onNavigateGemsPurchase(emptyMap()) }
    }

    /**
     * 互动通知评论卡 → 评论页（W4 批次 4）。
     *
     * ⚠️ 键是 **snake_case**（对齐 RN Comments 路由参数，`LetterItem.tsx:299`），
     * 与 [openChatDetail] 的 camelCase **不同轴** —— 两个方法各照各的 RN 真值，
     * 别"统一风格"。target 缺失不跳（iOS 同义：guard + 日志）。
     */
    override fun openComments(params: Map<String, String>) {
        val targetType = params["target_type"]?.toIntOrNull()
        val targetId = params["target_id"]?.takeIf { it.isNotBlank() }
        if (targetType == null || targetId == null) {
            logger.log(Logger.Level.WARN, "openComments 缺 target 参数，已忽略：$params")
            return
        }
        onRequestRoute(
            AppRoute.Comments(
                targetType = targetType,
                targetId = targetId,
                // 该入口不传 creatorId（iOS 注释：删除权限走 RN 兜底请求）
                creatorId = params["creator_id"].orEmpty(),
                commentId = params["comment_id"],
                rootId = params["root_id"],
            ),
        )
    }

    /**
     * 互动通知作品图 → 聊天详情（W4 批次 4）。
     *
     * 键是 **camelCase**（`LetterItem.tsx:253-262`）。分流素材只透传：
     * `resolveInitialParams` 自决初始屏（§2.30/§2.35 纪律，与卡片点击同链）。
     * 空 characterId 不跳（iOS guard 同义；Router 对 ChatDetail 不再重复判空）。
     */
    override fun openChatDetail(characterId: String, params: Map<String, String>) {
        if (characterId.isBlank()) {
            logger.log(Logger.Level.WARN, "openChatDetail 空 characterId，已忽略")
            return
        }
        onRequestRoute(
            AppRoute.ChatDetail(
                characterId = characterId,
                chatEnterSource = params["chatEnterSource"]?.takeIf { it.isNotBlank() },
                isStory = params["isStory"] == "true",
                characterType = params["characterType"]?.toIntOrNull(),
                contentType = params["contentType"]?.toIntOrNull(),
            ),
        )
    }

    /**
     * Surface 内「回馈」入口 → SettingsSurface 直达 Feedback 屏（W4 批次 4）。
     * iOS `openFeedback` → `.feedback` 路由同义。
     */
    override fun openFeedback() =
        onRequestRoute(
            AppRoute.SettingsSubScreen(AppRoute.SettingsSubScreen.Screen.FEEDBACK),
        )

    // ── SurfaceLifecycleContract ────────────────────────────────

    override fun notifyOnboardingCompleted() =
        notImplemented("notifyOnboardingCompleted", wave = "W4")

    // ── SurfaceProfileContract ──────────────────────────────────

    /**
     * EditProfileSurface 成功 mutation 后刷新原生 Profile 的资料与统计。
     *
     * 账号必须从 Native token 真值现取：`user-storage` 可能未 hydrate，也可能仍是
     * 上一账号；若让 JS 传 userId，会把一个本应无参数的成功回执变成可伪造归属。
     */
    override fun notifyProfileChanged() {
        val ownerUserId = tokenStore.currentUserId()
        if (ownerUserId.isNullOrBlank()) {
            logger.log(Logger.Level.WARN, "收到 profileChanged 但当前无有效 Native userId，已忽略")
            return
        }
        onProfileRefreshRequested(ownerUserId)
    }

    /**
     * 创作列表失效信号。守卫语义对齐 iOS（`guard let userId … else return`）：
     * 登出瞬间迟到的成功回执不该触发任何刷新（消费方随后也会被登出复位兜底）。
     */
    override fun notifyCreatedCharactersChanged() {
        val ownerUserId = tokenStore.currentUserId()
        if (ownerUserId.isNullOrBlank()) {
            logger.log(
                Logger.Level.WARN,
                "收到 createdCharactersChanged 但当前无有效 Native userId，已忽略",
            )
            return
        }
        onCreatedCharactersChanged()
    }

    // ── SurfaceChatListContract ─────────────────────────────────

    override fun notifyChattedListChanged() {
        onChattedListChanged()
    }

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
