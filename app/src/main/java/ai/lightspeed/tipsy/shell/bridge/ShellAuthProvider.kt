package ai.lightspeed.tipsy.shell.bridge

import android.util.Log
import expo.modules.tipsyauth.TipsyAuthProvider

/**
 * 壳侧 [TipsyAuthProvider] 实现。**注册它就等于让 RN 侧 `isShellHost()` 返回 true**，
 * 从而激活 RN 仓里那 55 个文件里已存在的壳适配分支（方案 §7.2）。
 *
 * ## W1-P0 的边界（重要）
 *
 * 这一步只建**骨架**。auth 真值（token 读取/刷新/迁移）属 P1/P2，语言真值属 P5，
 * 导航属 P4。所以本类现在有大量未实现项。
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
    /** 关当前 Surface 容器。由 [ai.lightspeed.tipsy.shell.MainActivity] 注入。 */
    private val onPopSurface: (surfaceInstanceId: String?) -> Unit,
) : TipsyAuthProvider {

    // ── SurfaceEnvContract ──────────────────────────────────────

    override fun currentLanguageCode(): String? = languageCodeProvider()

    /**
     * 返回 null = 壳不覆盖，JS 用构建期 `EXPO_PUBLIC_API_URL`。
     * 这是**当前正确的行为**：壳还没有自己的环境配置（P6 接入），
     * 此时谎报一个地址会让原生页与 Surface 命中不同后端。
     */
    override fun apiBaseURL(): String? = null

    /**
     * 返回 null（而非空串）= 壳无意见，JS 沿用自己的持久值。
     * ⚠️ 空串在契约里有特殊含义：**用户显式停用**，调用方不得再回退 —— 不要混用。
     */
    override fun boeLane(): String? = null

    // ── SurfaceAuthContract ─────────────────────────────────────

    /**
     * P1/P2 未完成前恒返回 null，语义是**「当前未登录」**。
     *
     * 这是安全的默认：JS 侧会走未登录分支，不会拿到假 token 去发请求。
     * ⚠️ P2 完成前**不要**让它返回任何占位值。
     */
    override suspend fun getValidToken(): String? = null

    override fun requestLogin(reason: String?) =
        notImplemented("requestLogin(reason=$reason)", wave = "W2（原生 Login 页）")

    override suspend fun logout() =
        notImplemented("logout", wave = "W1-P1")

    override suspend fun clearToken() =
        notImplemented("clearToken", wave = "W1-P1")

    override fun notifyServerAuthRejected() =
        notImplemented("notifyServerAuthRejected", wave = "W1-P1")

    override suspend fun notifyServerAuthRejectedForToken(authToken: String) =
        // ⚠️ 实现时：只有 authToken 仍是当前 token 才允许登出。
        // 绝不回退到无参版本 —— 旧账号迟到的 401 会误登出新账号。
        // 这里**刻意不打印 authToken**（token 不进 log）。
        notImplemented("notifyServerAuthRejectedForToken", wave = "W1-P1")

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

    override fun openGemsPurchase(params: Map<String, String>) =
        notImplemented("openGemsPurchase(keys=${params.keys})", wave = "W4")

    override fun notifyServerPaymentRequired() =
        notImplemented("notifyServerPaymentRequired", wave = "W1-P6")

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
        Log.e(TAG, msg)
    }

    private companion object {
        const val TAG = "ShellAuthProvider"
    }
}
