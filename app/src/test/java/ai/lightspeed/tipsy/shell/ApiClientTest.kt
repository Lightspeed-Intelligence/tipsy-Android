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
        val f = fixture(token = "tok-abc")

        f.client.get("user/info", authMode = AuthMode.REQUIRED)

        assertEquals("tok-abc", server.takeRequest().getHeader("token"))
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
        val f = fixture(token = "tok-abc")

        f.client.get("search/character_search", authMode = AuthMode.OPPORTUNISTIC)

        assertEquals(
            "OPPORTUNISTIC 有 token 必须带 —— iOS 漏了这条导致搜索历史恒空",
            "tok-abc",
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
        val f = fixture(token = "tok-abc")

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

    // ── 固定 header ───────────────────────────────────────────

    @Test
    fun `固定 header 齐全`() = runTest {
        server.enqueue(ok())
        val f = fixture(token = "t")

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
        val f = fixture(token = "t", lane = "my-lane")

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
        val f = fixture(token = "t")

        val e = f.client.get("x", authMode = AuthMode.REQUIRED)
        assertTrue(e.isSuccess)
        assertEquals(7, e.data!!.getInt("id"))
    }

    /** HTTP 200 + code != 0 → 业务异常，且**码可分辨**。 */
    @Test
    fun `HTTP 200 加业务错误码抛可分辨的业务异常`() = runTest {
        server.enqueue(ok("""{"code":6,"msg":"not enough gems"}"""))
        val f = fixture(token = "t")

        val ex = assertThrows(ApiException.Business::class.java) {
            kotlinx.coroutines.runBlocking { f.client.get("x", authMode = AuthMode.REQUIRED) }
        }
        assertEquals(6, ex.code)
        assertTrue("UI 要能区分「宝石不足」和「网络错误」", ex.isNotEnoughGems)
    }

    // ── 401 / 402 汇聚 ────────────────────────────────────────

    @Test
    fun `401 交给 gate 且带上实际使用的 token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val f = fixture(token = "tok-xyz")

        assertThrows(ApiException.Http::class.java) {
            kotlinx.coroutines.runBlocking { f.client.get("x", authMode = AuthMode.REQUIRED) }
        }
        assertEquals("必须传实际用的 token，供归属判定", listOf("tok-xyz"), f.authRejected)
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
        val f = fixture(token = "t")

        assertThrows(ApiException.Http::class.java) {
            kotlinx.coroutines.runBlocking { f.client.get("x", authMode = AuthMode.REQUIRED) }
        }
        assertEquals(1, f.paymentRequired())
    }

    // ── 其他 HTTP 错误与畸形响应 ───────────────────────────────

    @Test
    fun `5xx 抛 Http 异常`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val f = fixture(token = "t")

        val ex = assertThrows(ApiException.Http::class.java) {
            kotlinx.coroutines.runBlocking { f.client.get("x", authMode = AuthMode.REQUIRED) }
        }
        assertEquals(500, ex.status)
    }

    @Test
    fun `畸形响应抛 Malformed 而不是假成功`() = runTest {
        server.enqueue(ok("not json at all"))
        val f = fixture(token = "t")

        assertThrows(ApiException.Malformed::class.java) {
            kotlinx.coroutines.runBlocking { f.client.get("x", authMode = AuthMode.REQUIRED) }
        }
    }

    // ── POST 与 query ─────────────────────────────────────────

    @Test
    fun `POST 发送 JSON body`() = runTest {
        server.enqueue(ok())
        val f = fixture(token = "t")

        f.client.post("chat/send", """{"msg":"hi"}""", authMode = AuthMode.REQUIRED)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("""{"msg":"hi"}""", req.body.readUtf8())
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test
    fun `GET query 参数被正确编码`() = runTest {
        server.enqueue(ok())
        val f = fixture(token = "t")

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

    private fun TestScope.fixture(token: String?, lane: String? = null): Fixture {
        val rejected = mutableListOf<String>()
        var payment = 0

        val store = ShellTokenStore(
            persistence = object : ShellTokenStore.TokenPersistence {
                override fun read(): String? = token
                override fun write(token: String?) = Unit
            },
            refreshApi = { error("本测试不触发刷新") },
            generations = Generations(),
            scope = this,
            // 固定时钟 + 无 exp 的 token：让 isExpiringSoon 走「无 exp → true」分支会触发刷新，
            // 所以这里用一个远期 exp 的真 JWT 形态，确保不走刷新
            nowSeconds = { 1_700_000_000L },
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
            ),
            rejected,
        ) { payment }
    }
}
