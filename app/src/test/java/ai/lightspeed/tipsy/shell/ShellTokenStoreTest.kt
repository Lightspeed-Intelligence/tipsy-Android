package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.auth.Generations
import ai.lightspeed.tipsy.shell.auth.ShellTokenStore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ShellTokenStore] 的刷新语义测试（W1-P1）。
 *
 * 覆盖的是**跨账号污染**与**并发刷新**这两类问题 —— 它们的共同点是
 * 不会报错、只会产生错的数据：token 写进错的账号、用户看到别人的内容。
 * iOS 侧的 generation 机制就是为此存在（W1 计划 §3.3）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellTokenStoreTest {

    private val now = 1_700_000_000L

    // ── 基本路径 ────────────────────────────────────────────────

    @Test
    fun `无 token 返回 null`() = runTest {
        val store = store(persisted = null)
        assertNull(store.getValidToken())
    }

    @Test
    fun `token 充裕时直接返回 不发刷新请求`() = runTest {
        val api = CountingApi { "new-token" }
        val fresh = tokenWithExp(now + 3600)
        val store = store(persisted = fresh, api = api)

        assertEquals(fresh, store.getValidToken())
        assertEquals("不该发刷新请求", 0, api.callCount.get())
    }

    @Test
    fun `临过期时刷新并持久化`() = runTest {
        val newToken = tokenWithExp(now + 3600)
        val persistence = FakePersistence(tokenWithExp(now + 120))
        val store = store(persistence = persistence, api = CountingApi { newToken })

        assertEquals(newToken, store.getValidToken())
        assertEquals("新 token 必须落盘", newToken, persistence.stored)
    }

    /**
     * 已过期 token **不走刷新**（[ai.lightspeed.tipsy.shell.auth.Jwt.isExpiringSoon]
     * 对已过期返回 false，照搬 RN）。但壳桥承诺返回可直接发送的值，所以这里返回
     * null；持久值保留，且不主动 refresh。
     */
    @Test
    fun `已过期 token 返回 null 保留持久值且不触发刷新`() = runTest {
        val expired = tokenWithExp(now - 10)
        val api = CountingApi { "should-not-be-called" }
        val persistence = FakePersistence(expired)
        val store = store(persistence = persistence, api = api)

        assertNull(store.getValidToken())
        assertEquals("读取失败不等于显式登出，不在这里强制清持久值", expired, persistence.stored)
        assertEquals("已过期不走刷新路径（RN 同）", 0, api.callCount.get())
    }

    // ── 刷新失败 ────────────────────────────────────────────────

    /** RN `jwt.ts:127-129`：刷新失败但旧 token 未过期 → 继续用旧的。 */
    @Test
    fun `刷新失败但旧 token 未过期 返回旧 token`() = runTest {
        val old = tokenWithExp(now + 120) // 临过期但未过期
        val persistence = FakePersistence(old)
        val store = store(persistence = persistence, api = CountingApi { error("network down") })

        assertEquals(old, store.getValidToken())
        assertEquals("不该清掉仍可用的 token", old, persistence.stored)
    }

    /**
     * RN `jwt.ts:130-131`：刷新失败且旧 token 已过期 → 清掉，返回 null。
     *
     * **要触发这条分支，token 必须同时满足** `isExpiringSoon=true`（才走刷新）
     * 与 `hasNotExpired=false`（才清掉）。exp 已过的 token 做不到 ——
     * 它的 `isExpiringSoon` 是 false（`exp - now > 0` 不成立），压根不走刷新。
     *
     * 唯一同时满足两者的形态是**刷新期间过期**：进入时还有几秒，
     * 刷新失败时已经过了。这也是现实里真会发生的时序。
     */
    @Test
    fun `刷新期间过期且刷新失败 清掉并返回 null`() = runTest {
        val old = tokenWithExp(now + 5) // 剩 5 秒：isExpiringSoon=true
        val persistence = FakePersistence(old)
        var clock = now
        val store = ShellTokenStore(
            persistence = persistence,
            refreshApi = CountingApi {
                clock = now + 10 // 刷新期间越过 exp
                error("network down")
            },
            generations = Generations(),
            scope = this,
            nowSeconds = { clock },
        )

        assertNull("旧 token 已过期且刷新失败 → 必须清掉", store.getValidToken())
        assertNull("已失效的 token 不能留在存储里", persistence.stored)
    }

    /** 解析不了的 token 不 refresh，也不得通过“有效 token”桥契约返回。 */
    @Test
    fun `无法解析的 token 返回 null 不触发刷新`() = runTest {
        val api = CountingApi { error("should not refresh") }
        val store = store(persisted = "not-a-jwt", api = api)
        assertNull(store.getValidToken())
        assertEquals(0, api.callCount.get())
    }

    @Test
    fun `Router 本地登录判定只接受未过期且可解析 token`() = runTest {
        assertTrue(store(persisted = tokenWithExp(now + 3600)).hasToken())
        assertTrue(!store(persisted = tokenWithExp(now - 1)).hasToken())
        assertTrue(!store(persisted = "not-a-jwt").hasToken())
    }

    @Test
    fun `刷新返回空 token 时保留旧 token`() = runTest {
        val old = tokenWithExp(now + 120)
        val store = store(persisted = old, api = CountingApi { "" })
        assertEquals(old, store.getValidToken())
    }

    @Test
    fun `刷新返回畸形或过期 token 时保留仍有效旧 token且不覆盖持久值`() = runTest {
        val old = tokenWithExp(now + 120)
        val invalidRefreshResults = listOf("not-a-jwt", tokenWithExp(now - 1))

        invalidRefreshResults.forEach { invalid ->
            val persistence = FakePersistence(old)
            val store = store(persistence = persistence, api = CountingApi { invalid })
            assertEquals(old, store.getValidToken())
            assertEquals("服务端无效新值不得覆盖可用旧 token", old, persistence.stored)
        }
    }

    @Test
    fun `刷新期间过期且返回空 token 清掉并通知一次`() = runTest {
        var clock = now
        var cleared = 0
        val persistence = FakePersistence(tokenWithExp(now + 5))
        val store = store(
            persistence = persistence,
            api = CountingApi {
                clock = now + 10
                ""
            },
            listener = object : ShellTokenStore.Listener {
                override fun onTokenCleared() {
                    cleared++
                }
            },
            nowSeconds = { clock },
        )

        assertNull("空 refresh token 必须走与异常相同的失败回退", store.getValidToken())
        assertNull(persistence.stored)
        assertEquals("失效只广播一次", 1, cleared)
    }

    // ── single-flight ─────────────────────────────────────────

    /**
     * 并发调用只发**一次**刷新请求。
     *
     * 不做去重的后果：一次冷启动可能同时有十几个请求要 token（列表、banner、
     * 用户信息…），每个都触发一次 refresh → 服务端可能只认最后一个，
     * 其余 token 立即失效 → **随机 401**。
     */
    @Test
    fun `并发取 token 只刷新一次`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val newToken = tokenWithExp(now + 3600)
        val api = CountingApi {
            gate.await() // 让所有调用方都进来后再放行
            newToken
        }
        val store = store(persisted = tokenWithExp(now + 120), api = api)

        val jobs = (1..10).map { async { store.getValidToken() } }
        runCurrent()
        assertEquals("放行前应已发出且仅发出一个 refresh", 1, api.callCount.get())
        gate.complete(Unit)
        val results = jobs.awaitAll()

        assertEquals("10 个并发调用必须只发一次刷新", 1, api.callCount.get())
        assertTrue("所有调用方都应拿到新 token", results.all { it == newToken })
    }

    @Test
    fun `一个 waiter 取消不得清掉仍在飞的共享 refresh`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val newToken = tokenWithExp(now + 3600)
        val api = CountingApi {
            started.complete(Unit)
            release.await()
            newToken
        }
        val store = store(persisted = tokenWithExp(now + 120), api = api)

        val cancelledWaiter = async { store.getValidToken() }
        started.await()
        val survivingWaiter = async { store.getValidToken() }
        runCurrent()

        cancelledWaiter.cancelAndJoin()
        val laterWaiter = async { store.getValidToken() }
        runCurrent()

        assertEquals("取消一个调用方后仍必须保持 single-flight", 1, api.callCount.get())
        release.complete(Unit)
        assertEquals(newToken, survivingWaiter.await())
        assertEquals(newToken, laterWaiter.await())
    }

    @Test
    fun `刷新完成后再次调用会发起新的刷新`() = runTest {
        val api = CountingApi { tokenWithExp(now + 120) } // 新 token 也临过期
        val store = store(persisted = tokenWithExp(now + 120), api = api)

        store.getValidToken()
        store.getValidToken()

        assertEquals("in-flight 必须在完成后清空，否则第二次会等一个已完成的旧结果", 2, api.callCount.get())
    }

    // ── generation 闸门 ───────────────────────────────────────

    /**
     * generation 必须在创建 refresh job **之前**与 A token 一起捕获。
     * 若放进异步 job 内部，job 尚未调度时登录 B，会错误地捕获 B generation，
     * 随后把 refresh 返回的 A token 写到 B 会话。
     */
    @Test
    fun `A refresh job 尚未调度时登录 B 结果不得覆盖 B`() = runTest {
        val tokenA = tokenWithExp(now + 120, subject = "A")
        val refreshedA = tokenWithExp(now + 3600, subject = "A")
        val tokenB = tokenWithExp(now + 3600, subject = "B")
        val persistence = FakePersistence(tokenA)
        val store = store(
            persistence = persistence,
            api = CountingApi { refreshedA },
        )

        val refreshA = async(start = CoroutineStart.UNDISPATCHED) { store.getValidToken() }
        store.onLoggedIn(tokenB)

        assertNull("A 的 job 必须携带创建前捕获的旧 generation", refreshA.await())
        assertEquals("异步启动时序不得让 A 覆盖 B", tokenB, persistence.stored)
    }

    @Test
    fun `迟到 A 等到锁后不得覆盖 B 的 single-flight slot`() = runTest {
        val refreshMutex = Mutex(locked = true)
        val tokenA = tokenWithExp(now + 120, subject = "A")
        val tokenB = tokenWithExp(now + 120, subject = "B")
        val refreshedB = tokenWithExp(now + 3600, subject = "B")
        val persistence = FakePersistence(tokenA)
        val api = CountingApi { refreshedB }
        val store = store(
            persistence = persistence,
            api = api,
            refreshMutex = refreshMutex,
        )

        val lateA = async { store.getValidToken() }
        runCurrent() // A 已捕获 snapshot，停在 refreshMutex
        store.onLoggedIn(tokenB)
        val currentB = async { store.getValidToken() }
        runCurrent() // B 排在同一把锁后

        refreshMutex.unlock()
        runCurrent()

        assertNull("A 取得锁后必须重验并退出，不能建立旧会话 flight", lateA.await())
        assertEquals(refreshedB, currentB.await())
        assertEquals("只允许 B 发一次 refresh", 1, api.callCount.get())
        assertEquals(refreshedB, persistence.stored)
    }

    /**
     * 刷新期间发生登出 → 新 token **丢弃**，不写进 store。
     *
     * 不做这个校验的后果：用户登出后，一个在飞的刷新把旧账号的新 token 写回，
     * store 里凭空出现一个"已登录"状态 —— 下次启动直接进入旧账号。
     */
    @Test
    fun `刷新期间登出 新 token 不得写入`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val generations = Generations()
        val persistence = FakePersistence(tokenWithExp(now + 120))
        val store = store(
            persistence = persistence,
            api = CountingApi {
                started.complete(Unit)
                release.await()
                tokenWithExp(now + 3600)
            },
            generations = generations,
        )

        val job = async { store.getValidToken() }
        started.await() // 确认 A 的 refresh 已真正发出，避免测试在异步任务启动前就登出
        store.clearToken() // 登出：自增 auth generation
        release.complete(Unit)

        assertNull("generation 已变，刷新结果必须丢弃", job.await())
        assertNull("登出后 store 必须是空的", persistence.stored)
    }

    @Test
    fun `A 刷新期间登录 B 成功结果不得覆盖 B`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val tokenA = tokenWithExp(now + 120, subject = "A")
        val refreshedA = tokenWithExp(now + 3600, subject = "A")
        val tokenB = tokenWithExp(now + 3600, subject = "B")
        val persistence = FakePersistence(tokenA)
        val store = store(
            persistence = persistence,
            api = CountingApi {
                started.complete(Unit)
                release.await()
                refreshedA
            },
        )

        val refreshA = async { store.getValidToken() }
        started.await()
        store.onLoggedIn(tokenB)
        release.complete(Unit)

        assertNull("A 的迟到 refresh 结果不得交给调用方", refreshA.await())
        assertEquals("A 的新 token 不得覆盖已登录的 B", tokenB, persistence.stored)
    }

    @Test
    fun `A 刷新异常但仍未过期 B 已登录时不得返回 A`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val tokenA = tokenWithExp(now + 120, subject = "A")
        val tokenB = tokenWithExp(now + 3600, subject = "B")
        val persistence = FakePersistence(tokenA)
        val store = store(
            persistence = persistence,
            api = CountingApi {
                started.complete(Unit)
                release.await()
                error("network down")
            },
        )

        val refreshA = async { store.getValidToken() }
        started.await()
        store.onLoggedIn(tokenB)
        release.complete(Unit)

        assertNull("generation 已变，失败回退也不得返回 A token", refreshA.await())
        assertEquals("失败回退不得改写 B", tokenB, persistence.stored)
    }

    @Test
    fun `A 刷新异常且已过期 B 已登录时不得清掉 B`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var clock = now
        var cleared = 0
        val tokenA = tokenWithExp(now + 5, subject = "A")
        val tokenB = tokenWithExp(now + 3600, subject = "B")
        val persistence = FakePersistence(tokenA)
        val store = store(
            persistence = persistence,
            api = CountingApi {
                started.complete(Unit)
                release.await()
                error("network down")
            },
            listener = object : ShellTokenStore.Listener {
                override fun onTokenCleared() {
                    cleared++
                }
            },
            nowSeconds = { clock },
        )

        val refreshA = async { store.getValidToken() }
        started.await()
        store.onLoggedIn(tokenB)
        clock = now + 10 // A 在 refresh 期间过期
        release.complete(Unit)

        assertNull("generation 已变，A 的失败结果必须完整丢弃", refreshA.await())
        assertEquals("A 过期不能触发 clearInternal 清掉 B", tokenB, persistence.stored)
        assertEquals("B 已登录时不得广播 token cleared", 0, cleared)
    }

    @Test
    fun `A 刷新返回空 token B 已登录时不得回退 A`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val tokenA = tokenWithExp(now + 120, subject = "A")
        val tokenB = tokenWithExp(now + 3600, subject = "B")
        val persistence = FakePersistence(tokenA)
        val store = store(
            persistence = persistence,
            api = CountingApi {
                started.complete(Unit)
                release.await()
                ""
            },
        )

        val refreshA = async { store.getValidToken() }
        started.await()
        store.onLoggedIn(tokenB)
        release.complete(Unit)

        assertNull("空 token 分支也必须先过 generation 闸门", refreshA.await())
        assertEquals("空响应不得影响 B", tokenB, persistence.stored)
    }

    @Test
    fun `A 刷新异常但仍未过期 登出后不得返回 A`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cleared = 0
        val persistence = FakePersistence(tokenWithExp(now + 120, subject = "A"))
        val store = store(
            persistence = persistence,
            api = CountingApi {
                started.complete(Unit)
                release.await()
                error("network down")
            },
            listener = object : ShellTokenStore.Listener {
                override fun onTokenCleared() {
                    cleared++
                }
            },
        )

        val refreshA = async { store.getValidToken() }
        started.await()
        store.clearToken()
        release.complete(Unit)

        assertNull("登出后失败回退不得复活 A token", refreshA.await())
        assertNull(persistence.stored)
        assertEquals("登出只应通知一次", 1, cleared)
    }

    @Test
    fun `A 刷新异常且已过期 登出后不得重复清除通知`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var clock = now
        var cleared = 0
        val persistence = FakePersistence(tokenWithExp(now + 5, subject = "A"))
        val store = store(
            persistence = persistence,
            api = CountingApi {
                started.complete(Unit)
                release.await()
                error("network down")
            },
            listener = object : ShellTokenStore.Listener {
                override fun onTokenCleared() {
                    cleared++
                }
            },
            nowSeconds = { clock },
        )

        val refreshA = async { store.getValidToken() }
        started.await()
        store.clearToken()
        clock = now + 10 // A 在 refresh 期间过期
        release.complete(Unit)

        assertNull(refreshA.await())
        assertNull(persistence.stored)
        assertEquals("旧 refresh 不得再次 clearInternal 并重复广播", 1, cleared)
    }

    @Test
    fun `clearToken 自增 auth generation`() = runTest {
        val generations = Generations()
        val before = generations.auth
        store(persisted = tokenWithExp(now + 3600), generations = generations).clearToken()
        assertTrue("清 token 必须失效在飞响应", generations.auth > before)
    }

    @Test
    fun `onLoggedIn 自增 auth generation 并写入`() = runTest {
        val generations = Generations()
        val persistence = FakePersistence(null)
        val token = tokenWithExp(now + 3600)
        val before = generations.auth

        store(persistence = persistence, generations = generations).onLoggedIn(token)

        assertEquals(token, persistence.stored)
        assertTrue("换号必须失效旧账号的在飞响应", generations.auth > before)
    }

    // ── isCurrentToken（401 归属判定）─────────────────────────

    /**
     * `notifyServerAuthRejectedForToken` 的依据。**这是防误登出的关键**：
     * 旧账号迟到的 401 不得把新账号踢掉（W1 计划 §3.2）。
     */
    @Test
    fun `isCurrentToken 区分当前与历史 token`() = runTest {
        val current = tokenWithExp(now + 3600)
        val store = store(persisted = current)

        assertTrue(store.isCurrentToken(current))
        assertTrue("旧账号的 token 必须判定为非当前", !store.isCurrentToken(tokenWithExp(now + 7200)))
    }

    @Test
    fun `清空后 isCurrentToken 恒为 false`() = runTest {
        val token = tokenWithExp(now + 3600)
        val store = store(persisted = token)
        store.clearToken()
        assertTrue(!store.isCurrentToken(token))
    }

    @Test
    fun `旧 token 的条件清除不得影响当前账号`() = runTest {
        val tokenA = tokenWithExp(now + 3600, subject = "A")
        val tokenB = tokenWithExp(now + 3600, subject = "B")
        var cleared = 0
        val persistence = FakePersistence(tokenB)
        val store = store(
            persistence = persistence,
            listener = object : ShellTokenStore.Listener {
                override fun onTokenCleared() {
                    cleared++
                }
            },
        )

        assertTrue("A 已不是当前会话，条件清除必须返回 false", !store.clearTokenIfCurrent(tokenA))
        assertEquals(tokenB, persistence.stored)
        assertEquals("旧 401 不得广播 loggedOut", 0, cleared)
    }

    @Test
    fun `当前 token 的条件清除原子完成并通知一次`() = runTest {
        val token = tokenWithExp(now + 3600)
        var cleared = 0
        val persistence = FakePersistence(token)
        val store = store(
            persistence = persistence,
            listener = object : ShellTokenStore.Listener {
                override fun onTokenCleared() {
                    cleared++
                }
            },
        )

        assertTrue(store.clearTokenIfCurrent(token))
        assertNull(persistence.stored)
        assertEquals(1, cleared)
    }

    // ── listener ──────────────────────────────────────────────

    @Test
    fun `clearToken 默认通知 listener`() = runTest {
        var cleared = 0
        val store = store(
            persisted = tokenWithExp(now + 3600),
            listener = object : ShellTokenStore.Listener {
                override fun onTokenCleared() {
                    cleared++
                }
            },
        )
        store.clearToken()
        assertEquals("登出必须**恰好**通知一次（§3.5）", 1, cleared)
    }

    @Test
    fun `clearToken 可只清值而不通知 listener`() = runTest {
        var cleared = 0
        var removed = 0
        val generations = Generations()
        val persistence = FakePersistence(tokenWithExp(now + 3600))
        val before = generations.auth
        val store = store(
            persistence = persistence,
            generations = generations,
            listener = object : ShellTokenStore.Listener {
                override fun onTokenRemoved() {
                    removed++
                }

                override fun onTokenCleared() {
                    cleared++
                }
            },
        )

        store.clearToken(notifyListener = false)

        assertNull("不通知仍必须清 token", persistence.stored)
        assertTrue("不通知仍必须失效 auth generation", generations.auth > before)
        assertEquals("静默 clear 也必须触发账号存储成对清理", 1, removed)
        assertEquals("桥 clearToken 不得广播 loggedOut", 0, cleared)
    }

    // ── helpers ───────────────────────────────────────────────

    private fun kotlinx.coroutines.test.TestScope.store(
        persisted: String? = null,
        persistence: FakePersistence = FakePersistence(persisted),
        api: ShellTokenStore.RefreshApi = CountingApi { error("unexpected refresh") },
        generations: Generations = Generations(),
        listener: ShellTokenStore.Listener = ShellTokenStore.Listener.NOOP,
        nowSeconds: () -> Long = { now },
        refreshMutex: Mutex = Mutex(),
    ) = ShellTokenStore(
        persistence = persistence,
        refreshApi = api,
        generations = generations,
        scope = this,
        listener = listener,
        // 固定时钟：token 判定全是时间相关的，用真实时钟会让这些测试
        // 随运行日期变化（构造的 exp 相对"今天"早已过期）
        nowSeconds = nowSeconds,
        refreshMutex = refreshMutex,
    )

    private class FakePersistence(var stored: String?) : ShellTokenStore.TokenPersistence {
        override fun read(): String? = stored
        override fun write(token: String?) {
            stored = token
        }
    }

    private class CountingApi(
        private val block: suspend () -> String,
    ) : ShellTokenStore.RefreshApi {
        val callCount = AtomicInteger(0)
        override suspend fun refresh(currentToken: String): String {
            callCount.incrementAndGet()
            return block()
        }
    }

    private fun tokenWithExp(exp: Long, subject: String = "u1"): String {
        val payload = JSONObject().put("exp", exp).put("sub", subject)
        return "${encode("""{"alg":"HS256"}""")}.${encode(payload.toString())}.sig"
    }

    private fun encode(json: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val bytes = json.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            sb.append(alphabet[b0 shr 2])
            if (b1 < 0) {
                sb.append(alphabet[(b0 and 0x03) shl 4])
            } else {
                sb.append(alphabet[((b0 and 0x03) shl 4) or (b1 shr 4)])
                if (b2 < 0) {
                    sb.append(alphabet[(b1 and 0x0F) shl 2])
                } else {
                    sb.append(alphabet[((b1 and 0x0F) shl 2) or (b2 shr 6)])
                    sb.append(alphabet[b2 and 0x3F])
                }
            }
            i += 3
        }
        return sb.toString()
    }
}
