package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.auth.Generations
import ai.lightspeed.tipsy.shell.auth.ShellTokenStore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
     * 对已过期返回 false，照搬 RN）。它会被原样返回，拿去发请求得 401。
     * 这是现网既有行为，测试在此固定它，防止有人"顺手改成主动刷新"。
     */
    @Test
    fun `已过期 token 原样返回 不触发刷新`() = runTest {
        val expired = tokenWithExp(now - 10)
        val api = CountingApi { "should-not-be-called" }
        val store = store(persisted = expired, api = api)

        assertEquals(expired, store.getValidToken())
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

    /** 解析不了的 token：`isExpiringSoon=false` → 按"未临过期"原样返回（RN 同）。 */
    @Test
    fun `无法解析的 token 原样返回 不触发刷新`() = runTest {
        val api = CountingApi { error("should not refresh") }
        val store = store(persisted = "not-a-jwt", api = api)
        assertEquals("not-a-jwt", store.getValidToken())
        assertEquals(0, api.callCount.get())
    }

    @Test
    fun `刷新返回空 token 时保留旧 token`() = runTest {
        val old = tokenWithExp(now + 120)
        val store = store(persisted = old, api = CountingApi { "" })
        assertEquals(old, store.getValidToken())
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
        gate.complete(Unit)
        val results = jobs.awaitAll()

        assertEquals("10 个并发调用必须只发一次刷新", 1, api.callCount.get())
        assertTrue("所有调用方都应拿到新 token", results.all { it == newToken })
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
     * 刷新期间发生登出 → 新 token **丢弃**，不写进 store。
     *
     * 不做这个校验的后果：用户登出后，一个在飞的刷新把旧账号的新 token 写回，
     * store 里凭空出现一个"已登录"状态 —— 下次启动直接进入旧账号。
     */
    @Test
    fun `刷新期间登出 新 token 不得写入`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val generations = Generations()
        val persistence = FakePersistence(tokenWithExp(now + 120))
        val store = store(
            persistence = persistence,
            api = CountingApi {
                gate.await()
                tokenWithExp(now + 3600)
            },
            generations = generations,
        )

        val job = async { store.getValidToken() }
        store.clearToken() // 登出：自增 auth generation
        gate.complete(Unit)

        assertNull("generation 已变，刷新结果必须丢弃", job.await())
        assertNull("登出后 store 必须是空的", persistence.stored)
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

    // ── listener ──────────────────────────────────────────────

    @Test
    fun `清 token 时通知 listener`() = runTest {
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

    // ── helpers ───────────────────────────────────────────────

    private fun kotlinx.coroutines.test.TestScope.store(
        persisted: String? = null,
        persistence: FakePersistence = FakePersistence(persisted),
        api: ShellTokenStore.RefreshApi = CountingApi { error("unexpected refresh") },
        generations: Generations = Generations(),
        listener: ShellTokenStore.Listener = ShellTokenStore.Listener.NOOP,
    ) = ShellTokenStore(
        persistence = persistence,
        refreshApi = api,
        generations = generations,
        scope = this,
        listener = listener,
        // 固定时钟：token 判定全是时间相关的，用真实时钟会让这些测试
        // 随运行日期变化（构造的 exp 相对"今天"早已过期）
        nowSeconds = { now },
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

    private fun tokenWithExp(exp: Long): String {
        val payload = JSONObject().put("exp", exp).put("sub", "u1")
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
