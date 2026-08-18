package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.auth.Generations
import ai.lightspeed.tipsy.shell.auth.ShellTokenStore
import ai.lightspeed.tipsy.shell.network.ApiClient
import ai.lightspeed.tipsy.shell.network.ApiErrorGate
import ai.lightspeed.tipsy.shell.pages.settings.SettingsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Settings 接口的请求契约。
 *
 * **用真实 HTTP 往返而非 mock**（同 `HomeApiContractTest`）：ViewModel 层的
 * fake API 验不到真实路径 —— `/user/nsfw` 少了 `/update` 时全部单测照过，
 * 真机上却 404，且失败自动回滚把它伪装成「开关点了没反应」。
 */
class SettingsApiContractTest {

    private lateinit var server: MockWebServer
    private lateinit var api: SettingsApi
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
        api = SettingsApi(
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
    fun `分级开关命中 user nsfw update 且只发 nsfw 字段`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"code":0,"msg":"ok","data":{"nsfw":true}}"""),
        )
        api.setNsfw(true)
        val request = server.takeRequest()
        // ⚠️ 路径带 `/update`（`apis/user.ts:133`）；写成 `/user/nsfw` 会 404
        assertEquals("/api/v1/user/nsfw/update", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals(true, body.getBoolean("nsfw"))
        assertEquals(1, body.length())
    }

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
