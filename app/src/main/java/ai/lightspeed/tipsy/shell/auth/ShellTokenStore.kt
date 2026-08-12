package ai.lightspeed.tipsy.shell.auth

import kotlinx.coroutines.CancellationException
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
 * 2. token 有效且未临过期 → 直接返回；expired/malformed → null（不主动刷新）
 * 3. token **临过期**（剩余 0~5 分钟）→ single-flight 刷新
 * 4. 刷新失败但**旧 token 仍未过期** → 返回旧 token（RN `jwt.ts:127-129`）
 * 5. 刷新失败且旧 token 已过期 → 清 token，返回 null
 *
 * ⚠️ **已过期的 token 不走刷新路径** —— [Jwt.isExpiringSoon] 对已过期返回 false
 * （RN 的 `exp - now > 0` 条件）。持久层会保留原值，但 [getValidToken] 必须按壳桥
 * 契约返回 null，不能把 expired/malformed token 交给 WebView/SSE 等不经过 axios
 * 二次过滤的消费者。不要改成“主动刷新已过期 token”，那仍会与 live RN 分叉。
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
    /**
     * refresh job 与其完成后的状态通知所在作用域。生产装配必须使用 Main.immediate，
     * 因为 [Listener] 会同步触达 Router/UI；真正的 refresh HTTP 由实现自行切到 IO。
     */
    private val scope: CoroutineScope,
    private val listener: Listener = Listener.NOOP,
    /**
     * 当前时间（秒）。**可注入是必需的，不是为了好看**：token 的所有判定都是
     * 时间相关的，写死 `System.currentTimeMillis()` 会让"临过期""刷新中过期"
     * 这些关键分支根本无法测 —— 而它们正是最容易写错的部分。
     */
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    /** 可注入仅用于确定性覆盖“捕获 snapshot 后等待 single-flight 锁”的竞态。 */
    private val refreshMutex: Mutex = Mutex(),
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
        /**
         * 在 token 状态迁移的临界区内同步调用，以保证 `loggedOut` 不会落到随后登录的
         * 新账号上。实现只能做有界、非阻塞且不抛异常的进程内事件分发；不得做 I/O
         * 或等待其他线程。
         */
        fun onTokenCleared()

        companion object {
            val NOOP = object : Listener {
                override fun onTokenCleared() = Unit
            }
        }
    }

    /**
     * token cache、persistence 与 auth generation 的共同临界区。
     *
     * 只做“先看 generation、再写/清 token”仍有 TOCTOU：登录 B 可以夹在检查与清除
     * 之间，随后旧账号 A 的失败 refresh 会把 B 清掉。所有状态迁移必须在这把锁内完成。
     * Java monitor 是刻意选择：登录入口 [onLoggedIn] 是同步 API，不能获取
     * suspend [refreshMutex]。
     */
    private val stateLock = Any()

    /** 在飞 refresh 与所属会话绑定；换号后新会话不得等待旧账号的 job。 */
    private data class InFlightRefresh(
        val authGeneration: Long,
        val token: String,
        val deferred: Deferred<String?>,
    )

    private var inFlight: InFlightRefresh? = null

    private data class TokenSnapshot(
        val token: String?,
        val generations: Generations.Snapshot,
    )

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
     * 取可直接发送的有效 token，并仅在它**尚未过期但临近过期**时尝试刷新。
     *
     * 已过期或无法解析时返回 null，但不主动刷新、也不在这里强制清持久值。这个返回
     * 契约与 `SurfaceAuthContract` / iOS `AuthTokenStore.validToken()` 一致；否则 WebView
     * 这类直接消费桥 token 的路径会发送失效值。HTTP 层仍应在真正起飞前二次校验，
     * 以覆盖 await 后换号或恰好过期的窗口。
     *
     * **token 绝不写 log / Sentry breadcrumb / analytics** —— 本方法及其调用链
     * 不含任何打印 token 的语句。
     */
    suspend fun getValidToken(): String? {
        val snapshot = currentTokenSnapshot()
        val current = snapshot.token ?: return null
        val currentTime = nowSeconds()

        if (!Jwt.isExpiringSoon(current, currentTime)) {
            // 已过期/无法解析不触发 refresh，但也绝不能桥给直接消费者。
            return current.takeIf { Jwt.hasNotExpired(it, currentTime) }
        }

        val refreshedOrFallback = refreshSingleFlight(current, snapshot.generations) ?: return null
        // refresh API 也可能违反契约返回 malformed/已过期值；桥出口最后再守一次。
        return refreshedOrFallback.takeIf { Jwt.hasNotExpired(it, nowSeconds()) }
    }

    /**
     * single-flight 刷新。并发调用共享同一次网络请求。
     *
     * 捕获 auth generation：刷新期间若发生 logout / 换号，**结果直接丢弃**，
     * 不写进 store —— 否则旧账号的新 token 会覆盖新账号的。
     */
    private suspend fun refreshSingleFlight(
        currentToken: String,
        snapshot: Generations.Snapshot,
    ): String? {
        val flight = refreshMutex.withLock {
            // 调用方可能在捕获 A snapshot 后被抢占，等它拿到 mutex 时账号已经切到 B。
            // 这里必须在替换 inFlight 之前重验；否则迟到的 A 会覆盖 B 的 flight，随后
            // 另一个 B 调用方再发第二次 refresh，破坏 single-flight。
            val snapshotStillCurrent = synchronized(stateLock) {
                ensureCacheLoadedLocked()
                generations.isAuthValid(snapshot) && cached == currentToken
            }
            if (!snapshotStillCurrent) return@withLock null

            val existing = inFlight
            if (existing != null &&
                !existing.deferred.isCompleted &&
                existing.authGeneration == snapshot.auth &&
                existing.token == currentToken
            ) {
                existing
            } else {
                InFlightRefresh(
                    authGeneration = snapshot.auth,
                    token = currentToken,
                    deferred = scope.async { doRefresh(currentToken, snapshot) },
                ).also { inFlight = it }
            }
        } ?: return null

        return try {
            flight.deferred.await()
        } catch (cancelled: CancellationException) {
            // caller 取消不能被伪装成“未登录”；共享 refresh 属进程级 scope，会继续供
            // 其他 waiter 使用，因此这里只传播 caller cancellation。
            throw cancelled
        } catch (_: Throwable) {
            null
        } finally {
            // 不能由一个提前取消的 waiter 清掉仍在飞的共享 slot，否则下一请求会再发
            // 第二次 refresh。若无人等到完成，下一调用会因 isCompleted=true 直接替换。
            if (flight.deferred.isCompleted) {
                refreshMutex.withLock { if (inFlight === flight) inFlight = null }
            }
        }
    }

    private suspend fun doRefresh(
        currentToken: String,
        snapshot: Generations.Snapshot,
    ): String? {
        val refreshedCandidate = try {
            refreshApi.refresh(currentToken).takeIf { it.isNotBlank() }
        } catch (cancelled: CancellationException) {
            // scope 取消是生命周期信号，不是“刷新失败”；不得据此回退或清 token。
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        val refreshed = refreshedCandidate?.takeIf { Jwt.hasNotExpired(it, nowSeconds()) }

        return synchronized(stateLock) {
            // 校验与写/清是同一个原子步骤；否则 B 可夹在两者之间被 A 的结果覆盖。
            if (!generations.isAuthValid(snapshot)) {
                return@synchronized null
            }

            if (refreshed != null) {
                persistLocked(refreshed)
                return@synchronized refreshed
            }

            // 异常、空值与无效 refresh token 都走同一失败回退。后两者是服务端契约
            // 违例，不能覆盖仍可用的旧 token，更不能经 bridge 交给直接消费者。
            // 不打印异常内容，异常 message 可能含请求详情。
            if (Jwt.hasNotExpired(currentToken, nowSeconds())) {
                currentToken
            } else {
                clearInternalLocked(notifyListener = true)
                null
            }
        }
    }

    /** 登录成功后由壳调用（W2 的原生 Login 页）。会自增 auth generation。 */
    fun onLoggedIn(token: String) = synchronized(stateLock) {
        generations.bumpAuth()
        persistLocked(token)
    }

    /**
     * 清 token 并自增 auth generation，使在飞响应失效。
     *
     * [notifyListener] 默认为 true，供完整 logout 路径广播一次 `loggedOut`；桥的
     * `clearToken()`（删号等中间步骤）必须显式传 false，只清值、不广播也不收栈。
     */
    suspend fun clearToken(notifyListener: Boolean = true) {
        refreshMutex.withLock {
            inFlight = null
            synchronized(stateLock) {
                clearInternalLocked(notifyListener = notifyListener)
            }
        }
    }

    /**
     * 仅当 [expectedToken] 仍属于当前会话时清除。
     *
     * 401 终端不能写成 `isCurrentToken()` 后再 `clearToken()`：登录 B 可以夹在两次调用
     * 之间，旧账号 A 的迟到 401 会清掉 B。比较、generation bump、持久化清除与通知
     * 必须是同一个原子状态迁移。
     */
    suspend fun clearTokenIfCurrent(
        expectedToken: String,
        notifyListener: Boolean = true,
    ): Boolean = refreshMutex.withLock {
        synchronized(stateLock) {
            ensureCacheLoadedLocked()
            if (cached != expectedToken) {
                false
            } else {
                inFlight = null
                clearInternalLocked(notifyListener = notifyListener)
                true
            }
        }
    }

    /**
     * token 是否仍属于当前会话，仅供“发送前是否还能使用”之类的只读判定。
     *
     * ⚠️ 销毁性操作不得先调本方法再另行清除：两步之间可以换号。401 必须直接用
     * [clearTokenIfCurrent] 原子 compare-and-clear，否则旧账号迟到响应会误登出新账号。
     */
    fun isCurrentToken(token: String): Boolean = currentToken() == token

    /**
     * 是否存在**当前仍可用**的 token（只做本地判定，不触发刷新）。
     *
     * 给 Router 的 auth gate 用：expired/malformed 不能算“已登录”，否则路由会直接进入
     * 受保护目标，随后才被 API 拒绝；但这里也不该发 refresh，让每条深链卡在网络上。
     *
     * ⚠️ **刻意不暴露 token 本身**。Router 不需要它，多一个出口就多一条泄漏路径。
     */
    fun hasToken(): Boolean = currentToken()?.let {
        Jwt.hasNotExpired(it, nowSeconds())
    } == true

    /**
     * 当前 token 的 `sub`（用户 id），无有效 token 时 null。
     *
     * ⚠️ **只返回 userId，不返回 token**（同 [hasToken] 的理由：多一个 token
     * 出口就多一条泄漏路径）。给埋点绑定 uid 用 —— `Analytics` 的四个
     * uid-required 事件在绑定前会排队，冷启动已登录时必须尽早绑上，
     * 否则首屏卡片曝光全部积压到用户下一次登录才发出。
     *
     * 不触发刷新，与 [hasToken] 一致。
     */
    fun currentUserId(): String? = currentToken()
        ?.takeIf { Jwt.hasNotExpired(it, nowSeconds()) }
        ?.let { Jwt.subject(it) }

    private fun currentTokenSnapshot(): TokenSnapshot = synchronized(stateLock) {
        ensureCacheLoadedLocked()
        TokenSnapshot(cached, generations.snapshot())
    }

    private fun currentToken(): String? {
        if (!cacheLoaded) {
            synchronized(stateLock) {
                if (!cacheLoaded) {
                    ensureCacheLoadedLocked()
                }
            }
        }
        return cached
    }

    /** [stateLock] 内调用。 */
    private fun ensureCacheLoadedLocked() {
        if (!cacheLoaded) {
            cached = persistence.read()?.takeIf { it.isNotBlank() }
            cacheLoaded = true
        }
    }

    /** [stateLock] 内调用。 */
    private fun persistLocked(token: String) {
        cached = token
        cacheLoaded = true
        persistence.write(token)
    }

    /**
     * [stateLock] 内调用。listener 同样留在临界区内，保证 loggedOut 事件先于随后登录 B；
     * 否则 B 可夹在持久化清除与通知之间，收到一条属于 A 的迟到 loggedOut。Listener
     * 契约限定为同步、非阻塞事件分发；若未来要做 I/O，必须先引入按 generation 串行的事件队列。
     */
    private fun clearInternalLocked(notifyListener: Boolean) {
        generations.bumpAuth()
        cached = null
        cacheLoaded = true
        persistence.write(null)
        if (notifyListener) listener.onTokenCleared()
    }
}
