package ai.lightspeed.tipsy.shell.network

/**
 * 401 / 402 的**唯一**汇聚点（W1-P6，方案 §4.5）。
 *
 * ## 为什么必须唯一
 *
 * 这两类错误有**两个入口**：
 * - 原生页发的请求（本网络层）
 * - RN Surface 发的请求（经桥的 `notifyServerAuthRejectedForToken` / `notifyServerPaymentRequired`）
 *
 * 方案要求两者**汇聚到同一 handler**。分开处理的后果是防抖各算一套 ——
 * 用户在 Surface 里触发 401 的同时原生页也触发，会弹两次登录页。
 *
 * ## 401 必须带上被拒绝的 token
 *
 * ⚠️ **不带 token 的 401 不得触发登出**（W1 计划 §3.2）：旧账号迟到的 401
 * 会把刚登录的新账号踢下线。RN 侧已经守着这条 —— `axios.ts:32` 明写
 * 「只有能绑定到实际请求 token 的拒绝才允许触发全局登出」，
 * 拿不到就 `return` 什么都不做。壳侧同构。
 *
 * ## 防抖与防自触发环
 *
 * 登出本身会取消在飞请求，那些请求可能又报 401 → 再触发登出。
 * 用 [debounceWindowMs] 内只处理一次来断开这个环。
 */
class ApiErrorGate(
    private val onAuthRejected: suspend (authToken: String) -> Unit,
    private val onPaymentRequired: suspend () -> Unit,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val debounceWindowMs: Long = DEFAULT_DEBOUNCE_MS,
    private val logger: (String) -> Unit = {},
) {

    /** 防抖轨。**两类各自独立** —— 合用一个窗口会让 401 之后紧跟的 402 被吞掉。 */
    private enum class Kind { AUTH_REJECTED, PAYMENT_REQUIRED }

    private val lastHandledAt = mutableMapOf<Kind, Long>()
    private val lock = Any()

    /**
     * 处理一次 401。
     *
     * @param authToken 该请求**实际使用**的 token。为 null 表示请求没带 token
     *   （[AuthMode.OPPORTUNISTIC] 且当时未登录）—— 此时**什么都不做**，
     *   因为无法判断这个 401 属于哪个会话。
     */
    suspend fun onUnauthorized(authToken: String?) {
        if (authToken == null) {
            // 对齐 RN axios.ts:32-33：拿不到实际 token 就不触发全局登出。
            // 这不是"漏处理"，是刻意的安全选择 —— 见类注释。
            logger("收到 401 但请求未带 token，忽略（无法判断会话归属）")
            return
        }
        if (!shouldProceed(Kind.AUTH_REJECTED)) return
        // 刻意不打印 token
        logger("处理 401：交由壳的 auth 兜底")
        onAuthRejected(authToken)
    }

    /** 处理一次 402（付费墙）。 */
    suspend fun onPaymentRequired() {
        if (!shouldProceed(Kind.PAYMENT_REQUIRED)) return
        logger("处理 402：导航宝石购买页")
        onPaymentRequired.invoke()
    }

    private fun shouldProceed(kind: Kind): Boolean = synchronized(lock) {
        val now = nowMillis()
        val last = lastHandledAt[kind] ?: 0L
        if (last != 0L && now - last < debounceWindowMs) {
            logger("$kind 在防抖窗口内（${now - last}ms < $debounceWindowMs ms），跳过")
            return false
        }
        lastHandledAt[kind] = now
        return true
    }

    private companion object {
        /**
         * 3 秒。取值依据：足够覆盖「登出取消在飞请求 → 那批请求报 401」这一波，
         * 又不会长到让用户第二次真实的 401 被吞掉。
         */
        const val DEFAULT_DEBOUNCE_MS = 3_000L
    }
}
