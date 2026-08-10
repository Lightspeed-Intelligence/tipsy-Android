package ai.lightspeed.tipsy.shell.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 壳侧 token 真值（W1-P1）。**壳是 token 的唯一刷新者与唯一持久化者。**
 *
 * `isShellHost()` 为 true 后 JS 不再读写 token，全部经 `getValidToken()` 向这里取 ——
 * 所以这个类是 auth 的单点。它的行为逐条对齐 RN `src/lib/auth/jwt.ts`，
 * 偏差会表现为"壳内与现网 RN 包行为不一致"，而这类差异只在特定时间窗口出现。
 *
 * ## 刷新语义（照搬 RN，不是重新设计）
 *
 * 1. 无 token → null（未登录，合法业务态）
 * 2. token 未临过期 → 直接返回
 * 3. token **临过期**（剩余 0~5 分钟）→ single-flight 刷新
 * 4. 刷新失败但**旧 token 仍未过期** → 返回旧 token（RN `jwt.ts:127-129`）
 * 5. 刷新失败且旧 token 已过期 → 清 token，返回 null
 *
 * ⚠️ **已过期的 token 不走刷新路径** —— [Jwt.isExpiringSoon] 对已过期返回 false
 * （RN 的 `exp - now > 0` 条件）。这类 token 在第 2 步被"未临过期"放过，
 * 拿去发请求会得到 401，再由 `notifyServerAuthRejectedForToken` 处理。
 * 这是 RN 的现有行为，壳照搬；**不要在这里"顺手修正"**成主动刷新或直接清除 ——
 * 那会改变现网已验证的行为路径。
 *
 * ## single-flight
 *
 * 并发调用只发一次刷新请求，其余等同一个结果。RN 用模块级 `refreshPromise`
 * 变量实现（`jwt.ts:111`），壳用 [Mutex] + [Deferred]。
 *
 * 不做的事：**不重试**。RN 侧没有重试，加了会让登录态在网络抖动时行为分叉。
 */
class ShellTokenStore(
    private val persistence: TokenPersistence,
    private val refreshApi: RefreshApi,
    private val generations: Generations,
    private val scope: CoroutineScope,
    private val listener: Listener = Listener.NOOP,
    /**
     * 当前时间（秒）。**可注入是必需的，不是为了好看**：token 的所有判定都是
     * 时间相关的，写死 `System.currentTimeMillis()` 会让"临过期""刷新中过期"
     * 这些关键分支根本无法测 —— 而它们正是最容易写错的部分。
     */
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    /** 持久化后端。P2 会换成带 legacy 迁移的实现；现在由调用方注入便于测试。 */
    interface TokenPersistence {
        fun read(): String?
        fun write(token: String?)
    }

    /** `/auth/refresh_token`。返回新 token；失败抛异常。 */
    fun interface RefreshApi {
        suspend fun refresh(currentToken: String): String
    }

    /** 登录态变化通知（→ `TipsyAuthRegistry` 广播 + 壳内常驻页订阅）。 */
    interface Listener {
        fun onTokenCleared()

        companion object {
            val NOOP = object : Listener {
                override fun onTokenCleared() = Unit
            }
        }
    }

    private val mutex = Mutex()

    /** 在飞的刷新。非 null 表示已有刷新在跑，后来者等它。 */
    private var inFlight: Deferred<String?>? = null

    /**
     * 内存缓存，避免每次调用都读 MMKV。
     *
     * `getValidToken()` 在 RN 侧每个请求前都会调（`utils/axios.ts` 拦截器），
     * 频率等于请求数。
     */
    @Volatile
    private var cached: String? = null

    @Volatile
    private var cacheLoaded = false

    /**
     * 取有效 token。契约：拿到的永远是可直接发请求的 token，或 null（未登录）。
     *
     * **token 绝不写 log / Sentry breadcrumb / analytics** —— 本方法及其调用链
     * 不含任何打印 token 的语句。
     */
    suspend fun getValidToken(): String? {
        val current = currentToken() ?: return null

        if (!Jwt.isExpiringSoon(current, nowSeconds())) {
            // 含"已过期"的情况，见类注释第 3 条说明
            return current
        }

        return refreshSingleFlight(current)
    }

    /**
     * single-flight 刷新。并发调用共享同一次网络请求。
     *
     * 捕获 auth generation：刷新期间若发生 logout / 换号，**结果直接丢弃**，
     * 不写进 store —— 否则旧账号的新 token 会覆盖新账号的。
     */
    private suspend fun refreshSingleFlight(currentToken: String): String? {
        val existing = mutex.withLock { inFlight }
        if (existing != null) return runCatching { existing.await() }.getOrNull()

        val job = mutex.withLock {
            // 双检：等锁期间可能已有人发起
            inFlight ?: scope.async { doRefresh(currentToken) }.also { inFlight = it }
        }

        return try {
            job.await()
        } catch (_: Throwable) {
            null
        } finally {
            mutex.withLock { if (inFlight === job) inFlight = null }
        }
    }

    private suspend fun doRefresh(currentToken: String): String? {
        val snapshot = generations.snapshot()

        val newToken = try {
            refreshApi.refresh(currentToken)
        } catch (_: Throwable) {
            // RN `jwt.ts:126-131`：刷新失败时若旧 token 仍未过期就继续用它，
            // 否则清掉。**不打印异常内容** —— 异常 message 可能含请求详情。
            return if (Jwt.hasNotExpired(currentToken, nowSeconds())) {
                currentToken
            } else {
                clearInternal()
                null
            }
        }

        if (!generations.isAuthValid(snapshot)) {
            // 刷新期间换过号/登出：这个 token 属于旧账号，丢弃。
            // 不清当前 token —— 当前 token 属于新账号，与本次刷新无关。
            return null
        }

        if (newToken.isBlank()) {
            return if (Jwt.hasNotExpired(currentToken, nowSeconds())) currentToken else null
        }

        persist(newToken)
        return newToken
    }

    /** 登录成功后由壳调用（W2 的原生 Login 页）。会自增 auth generation。 */
    fun onLoggedIn(token: String) {
        generations.bumpAuth()
        persist(token)
    }

    /**
     * 仅清 token（删号等场景，对应桥的 `clearToken()`）。
     * 自增 auth generation 使在飞响应失效。
     */
    suspend fun clearToken() {
        mutex.withLock { inFlight = null }
        clearInternal()
    }

    /**
     * 被服务端拒绝的 token 是否仍是当前 token。
     *
     * ⚠️ 这是 `notifyServerAuthRejectedForToken` 的判定依据（W1 计划 §3.2）：
     * **只有仍是当前 token 才允许登出**，否则旧账号迟到的 401 会误登出新账号。
     */
    fun isCurrentToken(token: String): Boolean = currentToken() == token

    /**
     * 是否**存在** token（不判断有效性、不触发刷新）。
     *
     * 给 Router 的 auth gate 用：它只需要知道「要不要先弹登录」，
     * 不该为了这个判断去发一次网络刷新 —— 那会让每条深链都可能卡在网络上。
     *
     * ⚠️ **刻意不暴露 token 本身**。Router 不需要它，多一个出口就多一条泄漏路径。
     */
    fun hasToken(): Boolean = currentToken() != null

    private fun currentToken(): String? {
        if (!cacheLoaded) {
            synchronized(this) {
                if (!cacheLoaded) {
                    cached = persistence.read()?.takeIf { it.isNotBlank() }
                    cacheLoaded = true
                }
            }
        }
        return cached
    }

    private fun persist(token: String) {
        cached = token
        cacheLoaded = true
        persistence.write(token)
    }

    private fun clearInternal() {
        generations.bumpAuth()
        cached = null
        cacheLoaded = true
        persistence.write(null)
        listener.onTokenCleared()
    }
}
