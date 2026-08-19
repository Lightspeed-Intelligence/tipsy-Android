package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.auth.Generations
import ai.lightspeed.tipsy.shell.auth.ShellTokenStore
import ai.lightspeed.tipsy.shell.network.ApiClient
import ai.lightspeed.tipsy.shell.network.ApiErrorGate
import ai.lightspeed.tipsy.shell.pages.profile.AvatarDecorationApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 头像框目录接口的请求契约（P7）。
 *
 * **用真实 HTTP 往返而非 mock**（同 `SettingsApiContractTest`）。这里最重要的
 * 断言是鉴权模式：RN 走 `axiosPublic`（`apis/avatarDecoration.ts:16`），
 * 壳必须映射成 OPPORTUNISTIC —— **有 token 带上，没有也照发**。写成 NONE
 * （从不带 token）在客户端看不出任何差别，正是 `AuthMode` 类注释里 iOS
 * 「搜索历史恒空」那个 bug 的形状；本 PR 之前的首版就踩了这条。
 */
class AvatarDecorationApiContractTest {

    private lateinit var server: MockWebServer
    private lateinit var api: AvatarDecorationApi
    private var storedToken: String? = VALID_TOKEN

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val tokenStore = ShellTokenStore(
            persistence = object : ShellTokenStore.TokenPersistence {
                override fun read(): String? = storedToken
                override fun write(token: String?) { storedToken = token }
            },
            refreshApi = ShellTokenStore.RefreshApi { error("本测试不应触发刷新") },
            generations = Generations(),
            scope = CoroutineScope(Dispatchers.Unconfined),
            nowSeconds = { NOW },
        )
        api = AvatarDecorationApi(
            ApiClient(
                client = OkHttpClient(),
                baseUrl = server.url("/api/v1").toString().trimEnd('/'),
                tokenStore = tokenStore,
                errorGate = ApiErrorGate(
                    onAuthRejected = { false },
                    onPaymentRequired = {},
                    logger = {},
                ),
                appVersion = "1.0.0",
                downloadChannel = "APK",
                nowSeconds = { NOW },
            ),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `已登录时带 token 请求目录`() = runTest {
        server.enqueue(catalogue())
        val url = api.fetchImageUrl("weekly_champion")

        val request = server.takeRequest()
        assertEquals("/api/v1/avatar_decoration/config/list", request.path)
        // ⚠️ OPPORTUNISTIC 的前半：有 token 必须带。回退成 NONE 时这里失败
        assertEquals(VALID_TOKEN, request.getHeader("token"))
        assertEquals("https://cdn.example/frame_champion.png", url)
    }

    @Test
    fun `未登录时不带 token 也照发`() = runTest {
        storedToken = null
        server.enqueue(catalogue())
        val url = api.fetchImageUrl("weekly_champion")

        val request = server.takeRequest()
        // OPPORTUNISTIC 的后半：无 token 不拦截请求（区别于 REQUIRED）
        assertNull(request.getHeader("token"))
        assertEquals("https://cdn.example/frame_champion.png", url)
    }

    @Test
    fun `code 查无此项返回 null`() = runTest {
        server.enqueue(catalogue())
        assertNull(api.fetchImageUrl("retired_code"))
    }

    @Test
    fun `目录里 image_url 为空串按无框处理`() = runTest {
        server.enqueue(catalogue())
        // blank_frame 条目的 image_url 是空串 —— 不能把空串交给 Coil
        assertNull(api.fetchImageUrl("blank_frame"))
    }

    @Test
    fun `code 为空不发请求`() = runTest {
        assertNull(api.fetchImageUrl(null))
        assertNull(api.fetchImageUrl("  "))
        assertEquals(0, server.requestCount)
    }

    private fun catalogue(): MockResponse = MockResponse()
        .setResponseCode(200)
        .setBody(
            """
            {"code":0,"msg":"ok","data":{"list":[
              {"code":"weekly_champion","image_url":"https://cdn.example/frame_champion.png"},
              {"code":"blank_frame","image_url":""}
            ]}}
            """.trimIndent(),
        )

    private companion object {
        const val NOW = 1_700_000_000L

        /** exp 远在未来的最小 JWT（payload 只需 exp/sub）。 */
        val VALID_TOKEN: String = run {
            fun b64(json: String) = java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.toByteArray())
            "${b64("""{"alg":"HS256"}""")}." +
                "${b64("""{"exp":${NOW + 86_400},"sub":"u1"}""")}.sig"
        }
    }
}
