package ai.lightspeed.tipsy.shell.network

import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    /**
     * 返回 true 表示该 token 仍属当前会话且已执行全局处理；
     * false 表示迟到的旧会话事件，不得占用当前会话的防抖窗口。
     */
    private val onAuthRejected: suspend (authToken: String) -> Boolean,
    private val onPaymentRequired: suspend () -> Unit,
    /** 单调时钟；墙钟回拨不能把防抖窗口意外拉长数小时。 */
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val debounceWindowMs: Long = DEFAULT_DEBOUNCE_MS,
    private val logger: (String) -> Unit = {},
) {

    /**
     * 两类各自独立的锁与时钟。合用一轨会让 401 之后紧跟的 402 被吞；
     * auth 回调是 suspend，不能在 JVM `synchronized` 块里等它完成。
     */
    private val authMutex = Mutex()
    private val paymentMutex = Mutex()
    private var lastAuthWindow: AuthWindow? = null
    private var lastPaymentHandledAt: Long? = null

    /**
     * 401 窗口按 token 区分：A 的已处理事件不得挡住紧接着登录的 B。
     * 只保留 SHA-256 指纹，不在 gate 中延长原始 token 的生命周期。
     */
    private data class AuthWindow(
        val tokenFingerprint: ByteArray,
        val handledAt: Long,
    )

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
        authMutex.withLock {
            val now = nowMillis()
            val fingerprint = fingerprint(authToken)
            if (isAuthDebounced(lastAuthWindow, fingerprint, now)) return

            // 刻意不打印 token
            logger("处理 401：交由壳的 auth 兜底")
            if (onAuthRejected(authToken)) {
                // 窗口从副作用完成后开始；主线程若恰好卡顿，不能在处理完成前就过期。
                lastAuthWindow = AuthWindow(fingerprint, nowMillis())
            } else {
                // 旧 token 迟到的 401 不能把新账号的真实 401 挡在窗口外。
                logger("忽略未归属当前会话的 401，不启动防抖窗口")
            }
        }
    }

    /** 处理一次 402（付费墙）。 */
    suspend fun onPaymentRequired() {
        paymentMutex.withLock {
            val now = nowMillis()
            if (isDebounced(lastPaymentHandledAt, now, "PAYMENT_REQUIRED")) return
            logger("处理 402：导航宝石购买页")
            onPaymentRequired.invoke()
            lastPaymentHandledAt = nowMillis()
        }
    }

    private fun isDebounced(last: Long?, now: Long, kind: String): Boolean {
        if (last != null && now - last < debounceWindowMs) {
            logger("$kind 在防抖窗口内（${now - last}ms < $debounceWindowMs ms），跳过")
            return true
        }
        return false
    }

    private fun isAuthDebounced(
        last: AuthWindow?,
        fingerprint: ByteArray,
        now: Long,
    ): Boolean {
        if (last == null || now - last.handledAt >= debounceWindowMs) return false

        // 只去重同一会话，不让 A 的窗口吞掉 B。
        val sameSession = last.tokenFingerprint.contentEquals(fingerprint)
        if (!sameSession) return false

        logger(
            "AUTH_REJECTED 在防抖窗口内（${now - last.handledAt}ms < " +
                "$debounceWindowMs ms），跳过",
        )
        return true
    }

    private fun fingerprint(token: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))

    private companion object {
        /**
         * 3 秒。取值依据：足够覆盖「登出取消在飞请求 → 那批请求报 401」这一波，
         * 又不会长到让用户第二次真实的 401 被吞掉。
         */
        const val DEFAULT_DEBOUNCE_MS = 3_000L
    }
}
