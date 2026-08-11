package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.login.Base64Codec
import ai.lightspeed.tipsy.shell.pages.login.EmailLoginApi
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [EmailLoginApi] 的单测。用真实 [MockWebServer] 而非 mock 接口 ——
 * 要验证的正是「**实际发出了什么 header 和 body**」，mock 接口只会验到自己的 stub。
 * 范式照 `ApiClientTest.kt`。
 */
class EmailLoginApiTest {

    private lateinit var server: MockWebServer

    // 16 字节密钥的 base64，让 ClientIdCipher 能真的加密出东西
    private val aesKey = java.util.Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api(
        deviceId: String = "test-device-id",
        key: String = aesKey,
        lane: String? = null,
    ) = EmailLoginApi(
        baseUrl = server.url("/").toString().trimEnd('/'),
        appVersion = "1.4.4",
        downloadChannel = "APK",
        deviceIdProvider = { deviceId },
        aesKey = key,
        laneProvider = { lane },
        base64 = jvmBase64,
    )

    /** 单测跑在 JVM 上，可用 API 26+ 的 java.util.Base64 绕开 android.util.Base64 空壳。 */
    private val jvmBase64 = object : Base64Codec {
        override fun encodeToString(bytes: ByteArray): String =
            java.util.Base64.getEncoder().encodeToString(bytes)

        override fun decode(value: String): ByteArray =
            java.util.Base64.getDecoder().decode(value)
    }

    private fun enqueue(code: Int, body: String, status: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
    }

    // ── 发码 ────────────────────────────────────────────────

    @Test
    fun `发码请求的路径 body 与 header 全部对齐 RN`() {
        enqueue(200, """{"code":0,"msg":"ok"}""")

        runBlocking { api().sendCode("user@example.com") }

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/login/email/send_code", req.path)
        assertEquals("""{"email":"user@example.com"}""", req.body.readUtf8())

        // header 名大小写照抄 RN：platform 小写，其余大写驼峰
        assertEquals("android", req.getHeader("platform"))
        assertEquals("1.4.4", req.getHeader("X-App-Version"))
        assertEquals("APK", req.getHeader("X-Download-Channel"))
        assertEquals("application/json", req.getHeader("Content-Type"))
    }

    @Test
    fun `发码带 X-Client-ID 风控头且值可解码`() {
        enqueue(200, """{"code":0}""")

        runBlocking { api().sendCode("a@b.com") }

        val clientId = server.takeRequest().getHeader(EmailLoginApi.CLIENT_ID_HEADER)
        assertTrue("X-Client-ID 不能缺失", !clientId.isNullOrEmpty())
        // iv(12) + 密文(13) + tag(16) = 41 字节
        val raw = java.util.Base64.getDecoder().decode(clientId)
        assertEquals(12 + "test-device-id".length + 16, raw.size)
    }

    @Test
    fun `匿名请求不得带 token 头`() {
        enqueue(200, """{"code":0}""")

        runBlocking { api().sendCode("a@b.com") }

        // 字面上没有这个 header —— 不是空串、不是匿名 token
        assertNull(server.takeRequest().getHeader("token"))
    }

    @Test
    fun `加密失败时 X-Client-ID 发空串而不阻断请求`() {
        enqueue(200, """{"code":0}""")

        // 密钥缺失 → ClientIdCipher 返回空串
        runBlocking { api(key = "").sendCode("a@b.com") }

        val req = server.takeRequest()
        assertEquals("", req.getHeader(EmailLoginApi.CLIENT_ID_HEADER) ?: "")
    }

    @Test
    fun `生产域不带 lane 头`() {
        enqueue(200, """{"code":0}""")
        // MockWebServer 是 http+localhost，本就不在 lane 允许名单内
        runBlocking { api(lane = "boe_test").sendCode("a@b.com") }
        assertNull(server.takeRequest().getHeader("X-Tipsy-Lane"))
    }

    // ── 登录 ────────────────────────────────────────────────

    @Test
    fun `登录请求体含 email code avatar 与 lang_code 四个字段`() {
        enqueue(200, """{"code":0,"data":{"token":"jwt-abc","is_new_user":false,"linked_accounts":[]}}""")

        runBlocking { api().login("u@e.com", "123456", "zh-tw", "user/avatar/default/avatar3.png") }

        val body = server.takeRequest().body.readUtf8()
        assertTrue("缺 email", body.contains(""""email":"u@e.com""""))
        assertTrue("验证码字段名必须是 code", body.contains(""""code":"123456""""))
        assertTrue("缺 avatar", body.contains(""""avatar":"user/avatar/default/avatar3.png""""))
        assertTrue("缺 lang_code", body.contains(""""lang_code":"zh-tw""""))
    }

    @Test
    fun `登录成功解析 token 与 is_new_user`() {
        enqueue(
            200,
            """{"code":0,"data":{"token":"jwt-xyz","is_new_user":true,"linked_accounts":[{},{}]}}""",
        )

        val result = runBlocking { api().login("u@e.com", "123456", "en", "a.png") }

        assertEquals("jwt-xyz", result.token)
        assertTrue(result.isNewUser)
        assertEquals(2, result.linkedAccountCount)
    }

    // ── 错误语义：这是与 RN 刻意偏离的地方 ────────────────────

    @Test
    fun `HTTP 200 但 code 非 0 抛 BusinessException 并透传后端 msg`() {
        // 典型场景：发码被限流
        enqueue(200, """{"code":429,"msg":"发送过于频繁，请稍后再试"}""")

        val e = assertThrows(EmailLoginApi.BusinessException::class.java) {
            runBlocking { api().sendCode("a@b.com") }
        }
        assertEquals(429, e.code)
        // msg 要能直接给用户看 —— RN 侧这里是丢弃 msg 的
        assertEquals("发送过于频繁，请稍后再试", e.msg)
    }

    @Test
    fun `验证码错误透传后端 msg 而非硬编码英文`() {
        enqueue(200, """{"code":1001,"msg":"验证码错误"}""")

        val e = assertThrows(EmailLoginApi.BusinessException::class.java) {
            runBlocking { api().login("u@e.com", "000000", "zh-tw", "a.png") }
        }
        assertEquals("验证码错误", e.msg)
    }

    @Test
    fun `非 2xx 也能从 errorStream 读出业务 msg`() {
        enqueue(400, """{"code":2,"msg":"参数错误"}""", status = 400)

        val e = assertThrows(EmailLoginApi.BusinessException::class.java) {
            runBlocking { api().sendCode("bad") }
        }
        assertEquals(2, e.code)
        assertEquals("参数错误", e.msg)
    }

    @Test
    fun `响应非法 JSON 抛 IOException 而不是假成功`() {
        enqueue(200, "<html>502 Bad Gateway</html>")

        assertThrows(IOException::class.java) {
            runBlocking { api().sendCode("a@b.com") }
        }
    }

    @Test
    fun `code 为 0 但缺 token 时抛错而不是存空 token`() {
        enqueue(200, """{"code":0,"data":{"is_new_user":false}}""")

        assertThrows(IOException::class.java) {
            runBlocking { api().login("u@e.com", "123456", "en", "a.png") }
        }
    }

    @Test
    fun `token 是 JSON null 时抛错 —— 不能存成字面量 null 字符串`() {
        enqueue(200, """{"code":0,"data":{"token":null,"is_new_user":false}}""")

        assertThrows(IOException::class.java) {
            runBlocking { api().login("u@e.com", "123456", "en", "a.png") }
        }
    }

    @Test
    fun `随机默认头像路径格式对齐 RN`() {
        repeat(30) {
            val path = EmailLoginApi.randomDefaultAvatar()
            assertTrue(
                "头像路径格式错误：$path（拼错不报错，只会 404）",
                path.matches(Regex("""user/avatar/default/avatar([1-9]|1\d|20)\.png""")),
            )
        }
    }
}
