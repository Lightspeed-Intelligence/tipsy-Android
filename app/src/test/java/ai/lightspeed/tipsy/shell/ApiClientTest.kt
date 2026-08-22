package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.auth.Generations
import ai.lightspeed.tipsy.shell.auth.ShellTokenStore
import ai.lightspeed.tipsy.shell.network.ApiClient
import ai.lightspeed.tipsy.shell.network.ApiErrorGate
import ai.lightspeed.tipsy.shell.network.ApiException
import ai.lightspeed.tipsy.shell.network.AuthMode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ApiClient] 的三鉴权模式与错误处理测试（W1-P6）。
 *
 * **用真实 HTTP 往返（MockWebServer）而不是 mock OkHttp 接口**：
 * 本测试要验的是「实际发出了什么 header」，mock 接口只会验到我自己写的 stub。
 */
class ApiClientTest {

    private lateinit var server: MockWebServer
    private val now = 1_700_000_000L
    private val validToken = tokenWithExp(now + 3_600)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── 三鉴权模式：token 到底带没带 ──────────────────────────

    @Test
    fun `REQUIRED 模式带 token`() = runTest {
        server.enqueue(ok())
        val f = fixture(token = validToken)

        f.client.get("user/info", authMode = AuthMode.REQUIRED)

        assertEquals(validToken, server.takeRequest().getHeader("token"))
    }

    /**
     * ⚠️ **本文件最重要的一条。** `OPPORTUNISTIC` 有 token 时**必须带上**。
     *
     * iOS 把 `/search/character_search` 实现成「永不带 token」，结果
     * **最近搜索历史永久为空** —— 那个接口带 token 才会记录搜索词。
     * 不报错、不崩溃，只是功能静默失效。
     */
    @Test
    fun `OPPORTUNISTIC 模式有 token 时必须带上`() = runTest {
        server.enqueue(ok())
        val f = fixture(token = validToken)

        f.client.get("search/character_search", authMode = AuthMode.OPPORTUNISTIC)

        assertEquals(
            "OPPORTUNISTIC 有 token 必须带 —— iOS 漏了这条导致搜索历史恒空",
            validToken,
            server.takeRequest().getHeader("token"),
        )
    }

    @Test
    fun `OPPORTUNISTIC 模式无 token 时照样发请求`() = runTest {
        server.enqueue(ok())
        val f = fixture(token = null)

        f.client.get("search/character_search", authMode = AuthMode.OPPORTUNISTIC)

        val req = server.takeRequest()
        assertNull("无 token 时不带该 header", req.getHeader("token"))
        assertEquals("但请求必须照发", "/search/character_search", req.path)
    }

    @Test
    fun `NONE 模式即使有 token 也不带`() = runTest {
        server.enqueue(ok())
        val f = fixture(token = validToken)

        f.client.get("public/config", authMode = AuthMode.NONE)

        assertNull(server.takeRequest().getHeader("token"))
    }

    /**
     * `REQUIRED` 且无 token 时**根本不发请求**（对齐 RN `axiosAuth`）。
     * 发一个必然 401 的请求毫无意义，还会触发 auth 兜底造成误登出路径。
     */
    @Test
    fun `REQUIRED 无 token 时不发请求直接失败`() = runTest {
        val f = fixture(token = null)

        assertThrows(ApiException.Unauthenticated::class.java) {
            kotlinx.coroutines.runBlocking { f.client.get("user/info", authMode = AuthMode.REQUIRED) }
        }
        assertEquals("请求根本不该发出", 0, server.requestCount)
    }

    @Test
    fun `REQUIRED 过期或不可解析 token 都不发请求`() = runTest {
        val unusableTokens = listOf(tokenWithExp(now - 1), "not-a-jwt")

        unusableTokens.forEach { token ->
            val f = fixture(token = token)
            assertThrows(ApiException.Unauthenticated::class.java) {
                kotlinx.coroutines.runBlocking {
                    f.client.get("user/info", authMode = AuthMode.REQUIRED)
                }
            }
        }

        assertEquals("不可用 token 绝不得上网", 0, server.requestCount)
    }

    @Test
    fun `OPPORTUNISTIC 过期或不可解析 token 省略 header 但照常发送`() = runTest {
        val unusableTokens = listOf(tokenWithExp(now - 1), "not-a-jwt")

        unusableTokens.forEach { token ->
            server.enqueue(ok())
            fixture(token = token).client.get("public/config", authMode = AuthMode.OPPORTUNISTIC)
            assertNull(server.takeRequest().getHeader("token"))
        }

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `取 token 后换号 REQUIRED 不得发送旧账号 token`() = runTest {
        val accountA = tokenWithExp(now + 3_600, sub = "account-a")
        val accountB = tokenWithExp(now + 3_600, sub = "account-b")
        val f = fixture(token = accountA, switchToTokenOnValidation = accountB)

        assertThrows(ApiException.Unauthenticated::class.java) {
            kotlinx.coroutines.runBlocking {
                f.client.get("user/info", authMode = AuthMode.REQUIRED)
            }
        }

        assertEquals("候选 token 已非当前会话，请求不得发出", 0, server.requestCount)
    }

    @Test
    fun `取 token 后换号 OPPORTUNISTIC 不得携带旧账号 token`() = runTest {
        server.enqueue(ok())
        val accountA = tokenWithExp(now + 3_600, sub = "account-a")
        val accountB = tokenWithExp(now + 3_600, sub = "account-b")
        val f = fixture(token = accountA, switchToTokenOnValidation = accountB)

        f.client.get("public/config", authMode = AuthMode.OPPORTUNISTIC)

        assertNull(server.takeRequest().getHeader("token"))
    }

    @Test
    fun `store 返回后恰好过期 REQUIRED 仍不得起飞`() = runTest {
        val f = fixture(
            token = tokenWithExp(now + 1),
            requestNow = now + 2,
        )

        // ⚠️ 这里**不能**用 `assertThrows { runBlocking { ... } }`（其余用例的写法）。
        //
        // 本用例的 token 落在 refresh 窗口内（exp = now+1，requestNow = now+2），所以
        // `getValidToken()` 会走到 `refreshSingleFlight`，那里 `scope.async` 把 refresh
        // 排到 **TestScope 的虚拟时间调度器**上（fixture 传的 `scope = this`），随后
        // `deferred.await()` 等它完成。而 `runBlocking` 已经占住唯一的 test 线程 ——
        // 调度器再也没有机会跑那个协程，于是**永久死锁**（不是变慢）。
        //
        // 症状：整个测试 task 挂到 CI job 60 分钟超时被 cancel，本地看起来像"卡住"，
        // 且**不产生失败报告**（旧报告还在，容易被误读成通过）。
        // 其余九处 `runBlocking` 侥幸不死锁，是因为它们的 token 无效/未进 refresh 窗口，
        // `getValidToken()` 在真正 suspend 之前就 return 了。
        //
        // 正确写法：直接在 runTest 的协程里 await 异常，不要嵌 runBlocking。
        // 用 try/catch 而非 assertThrows：后者的 lambda 不是 suspend，
        // 想在里面调 suspend 函数就只能嵌 runBlocking —— 正是死锁的来源。
        // （本仓未依赖 kotlin-test，所以不用 assertFailsWith。）
        var caught: ApiException.Unauthenticated? = null
        try {
            f.client.get("user/info", authMode = AuthMode.REQUIRED)
        } catch (e: ApiException.Unauthenticated) {
            caught = e
        }
        assertNotNull("REQUIRED + 已过期 token 必须抛 Unauthenticated", caught)

        assertEquals("真正建请求前必须二次检查过期窗口", 0, server.requestCount)
    }

    // ── 固定 header ───────────────────────────────────────────

    @Test
    fun `固定 header 齐全`() = runTest {
        server.enqueue(ok())
        val f = fixture(token = validToken)

        f.client.get("x", authMode = AuthMode.REQUIRED)

        val req = server.takeRequest()
        assertEquals("android", req.getHeader("Platform"))
        assertEquals("1.4.5", req.getHeader("X-App-Version"))
        assertEquals("APK", req.getHeader("X-Download-Channel"))
    }

    /** 非白名单 host（MockWebServer 是 http://localhost）不得带 lane。 */
    @Test
    fun `非白名单 host 不带 lane header`() = runTest {
        server.enqueue(ok())
        val f = fixture(token = validToken, lane = "my-lane")

        f.client.get("x", authMode = AuthMode.REQUIRED)

        assertNull(
            "localhost 不在白名单且是 http —— 不得泄漏 lane",
            server.takeRequest().getHeader("X-Tipsy-Lane"),
        )
    }

    // ── envelope ──────────────────────────────────────────────

    @Test
    fun `成功响应返回 envelope`() = runTest {
        server.enqueue(ok("""{"code":0,"data":{"id":7}}"""))
        val f = fixture(token = validToken)

        val e = f.client.get("x", authMode = AuthMode.REQUIRED)
        assertTrue(e.isSuccess)
        assertEquals(7, e.data!!.getInt("id"))
    }

    /** HTTP 200 + code != 0 → 业务异常，且**码可分辨**。 */
    @Test
    fun `HTTP 200 加业务错误码抛可分辨的业务异常`() = runTest {
        server.enqueue(ok("""{"code":6,"msg":"not enough gems"}"""))
        val f = fixture(token = validToken)

        val ex = assertThrows(ApiException.Business::class.java) {
            kotlinx.coroutines.runBlocking { f.client.get("x", authMode = AuthMode.REQUIRED) }
        }
        assertEquals(6, ex.code)
        assertTrue("UI 要能区分「宝石不足」和「网络错误」", ex.isNotEnoughGems)
    }

    @Test
    fun `业务异常保留结构化 data`() = runTest {
        server.enqueue(ok("""{"code":2,"msg":"blocked","data":{"ok":false}}"""))
        val f = fixture(token = validToken)

        val ex = assertThrows(ApiException.Business::class.java) {
            kotlinx.coroutines.runBlocking { f.client.get("x", authMode = AuthMode.REQUIRED) }
        }

        assertEquals(false, ex.data?.getBoolean("ok"))
    }

    // ── 401 / 402 汇聚 ────────────────────────────────────────

    @Test
    fun `401 交给 gate 且带上实际使用的 token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val f = fixture(token = validToken)

        assertThrows(ApiException.Http::class.java) {
            kotlinx.coroutines.runBlocking { f.client.get("x", authMode = AuthMode.REQUIRED) }
        }
        assertEquals("必须传实际用的 token，供归属判定", listOf(validToken), f.authRejected)
        assertEquals("对齐 live RN：401 只上报并抛错，不自动重试响应", 1, server.requestCount)
    }

    /**
     * OPPORTUNISTIC 未登录时的 401：**不带 token**，gate 必须忽略。
     * 否则旧账号迟到的 401 会踢掉新账号。
     */
    @Test
    fun `未带 token 的请求收到 401 时不触发登出`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val f = fixture(token = null)

        assertThrows(ApiException.Http::class.java) {
            kotlinx.coroutines.runBlocking { f.client.get("x", authMode = AuthMode.OPPORTUNISTIC) }
        }
        assertTrue("无法判断会话归属的 401 必须忽略", f.authRejected.isEmpty())
    }

    @Test
    fun `402 交给 gate`() = runTest {
        server.enqueue(MockResponse().setResponseCode(402))
        val f = fixture(token = validToken)

        assertThrows(ApiException.Http::class.java) {
            kotlinx.coroutines.runBlocking { f.client.get("x", authMode = AuthMode.REQUIRED) }
        }
        assertEquals(1, f.paymentRequired())
    }

    // ── 其他 HTTP 错误与畸形响应 ───────────────────────────────

    @Test
    fun `5xx 抛 Http 异常`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val f = fixture(token = validToken)

        val ex = assertThrows(ApiException.Http::class.java) {
            kotlinx.coroutines.runBlocking { f.client.get("x", authMode = AuthMode.REQUIRED) }
        }
        assertEquals(500, ex.status)
    }

    @Test
    fun `畸形响应抛 Malformed 而不是假成功`() = runTest {
        server.enqueue(ok("not json at all"))
        val f = fixture(token = validToken)

        assertThrows(ApiException.Malformed::class.java) {
            kotlinx.coroutines.runBlocking { f.client.get("x", authMode = AuthMode.REQUIRED) }
        }
    }

    // ── POST 与 query ─────────────────────────────────────────

    @Test
    fun `POST 发送 JSON body`() = runTest {
        server.enqueue(ok())
        val f = fixture(token = validToken)

        f.client.post("chat/send", """{"msg":"hi"}""", authMode = AuthMode.REQUIRED)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("""{"msg":"hi"}""", req.body.readUtf8())
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test
    fun `冻结 token POST 使用事件产生时的当前会话`() = runTest {
        server.enqueue(ok())
        val f = fixture(token = validToken)

        f.client.postWithFrozenToken(
            path = "screen/recommendation/batch",
            jsonBody = """{"events":[]}""",
            frozenToken = validToken,
        )

        val req = server.takeRequest()
        assertEquals(validToken, req.getHeader("token"))
        assertEquals("""{"events":[]}""", req.body.readUtf8())
    }

    @Test
    fun `冻结 token 已非当前账号时不发送`() = runTest {
        val accountA = tokenWithExp(now + 3_600, sub = "account-a")
        val accountB = tokenWithExp(now + 3_600, sub = "account-b")
        val f = fixture(token = accountB)

        assertThrows(ApiException.Unauthenticated::class.java) {
            kotlinx.coroutines.runBlocking {
                f.client.postWithFrozenToken(
                    path = "screen/recommendation/batch",
                    frozenToken = accountA,
                )
            }
        }

        assertEquals("账号 A 的排队事件不得使用账号 B 的会话发送", 0, server.requestCount)
    }

    @Test
    fun `GET query 参数被正确编码`() = runTest {
        server.enqueue(ok())
        val f = fixture(token = validToken)

        f.client.get("search", mapOf("q" to "a b&c"), authMode = AuthMode.REQUIRED)

        assertEquals("/search?q=a+b%26c", server.takeRequest().path)
    }

    // ── helpers ───────────────────────────────────────────────

    private fun ok(body: String = """{"code":0,"data":{}}""") =
        MockResponse().setResponseCode(200).setBody(body)

    private class Fixture(
        val client: ApiClient,
        val authRejected: List<String>,
        val paymentRequired: () -> Int,
    )

    private fun TestScope.fixture(
        token: String?,
        lane: String? = null,
        switchToTokenOnValidation: String? = null,
        requestNow: Long = now,
    ): Fixture {
        val rejected = mutableListOf<String>()
        var payment = 0
        var switchedAccount = false

        val store = ShellTokenStore(
            persistence = object : ShellTokenStore.TokenPersistence {
                override fun read(): String? = token
                override fun write(token: String?) = Unit
            },
            refreshApi = { error("本测试不触发刷新") },
            generations = Generations(),
            scope = this,
            // token store 使用固定时钟；各测试传真 JWT，避免结果随运行日期变化。
            nowSeconds = { now },
        )

        val gate = ApiErrorGate(
            onAuthRejected = { rejected.add(it) },
            onPaymentRequired = { payment++ },
        )

        return Fixture(
            ApiClient(
                client = OkHttpClient(),
                baseUrl = server.url("/").toString().trimEnd('/'),
                tokenStore = store,
                errorGate = gate,
                appVersion = "1.4.5",
                downloadChannel = "APK",
                laneProvider = { lane },
                nowSeconds = {
                    if (!switchedAccount) {
                        switchToTokenOnValidation?.let(store::onLoggedIn)
                        switchedAccount = true
                    }
                    requestNow
                },
            ),
            rejected,
        ) { payment }
    }

    private fun tokenWithExp(exp: Long, sub: String = "u1"): String {
        val payload = JSONObject().put("exp", exp).put("sub", sub)
        return "${encode("""{"alg":"HS256"}""")}.${encode(payload.toString())}.sig"
    }

    private fun encode(json: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val bytes = json.toByteArray(Charsets.UTF_8)
        val result = StringBuilder()
        var index = 0
        while (index < bytes.size) {
            val b0 = bytes[index].toInt() and 0xFF
            val b1 = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else -1
            val b2 = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xFF else -1
            result.append(alphabet[b0 shr 2])
            if (b1 < 0) {
                result.append(alphabet[(b0 and 0x03) shl 4])
            } else {
                result.append(alphabet[((b0 and 0x03) shl 4) or (b1 shr 4)])
                if (b2 < 0) {
                    result.append(alphabet[(b1 and 0x0F) shl 2])
                } else {
                    result.append(alphabet[((b1 and 0x0F) shl 2) or (b2 shr 6)])
                    result.append(alphabet[b2 and 0x3F])
                }
            }
            index += 3
        }
        return result.toString()
    }
}
