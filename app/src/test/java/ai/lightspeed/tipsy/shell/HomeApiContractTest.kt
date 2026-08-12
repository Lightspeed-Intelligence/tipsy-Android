package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.auth.Generations
import ai.lightspeed.tipsy.shell.auth.ShellTokenStore
import ai.lightspeed.tipsy.shell.network.ApiClient
import ai.lightspeed.tipsy.shell.network.ApiErrorGate
import ai.lightspeed.tipsy.shell.pages.home.HomeApi
import ai.lightspeed.tipsy.shell.pages.home.HomeGender
import ai.lightspeed.tipsy.shell.pages.home.HomeSeries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Home 接口的请求契约（W2）。
 *
 * **用真实 HTTP 往返而非 mock**（同 `ApiClientTest` 的理由）：这里要验的正是
 * 「实际发出了什么请求体与 header」，mock 只会验到自己写的 stub。
 */
class HomeApiContractTest {

    private lateinit var server: MockWebServer
    private lateinit var api: HomeApi
    private var storedToken: String? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun buildApi(token: String? = null) {
        storedToken = token
        val tokenStore = ShellTokenStore(
            persistence = object : ShellTokenStore.TokenPersistence {
                override fun read(): String? = storedToken
                override fun write(token: String?) { storedToken = token }
            },
            // 本测试的 token 都远未到刷新窗口（exp = now + 1 天），不该触发刷新。
            // 用 error 而不是返回假 token —— 真触发了要立刻炸出来，
            // 否则会掩盖"OPPORTUNISTIC 意外走了刷新路径"这类问题
            refreshApi = ShellTokenStore.RefreshApi { error("本测试不应触发刷新") },
            generations = Generations(),
            scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            nowSeconds = { NOW },
        )
        api = HomeApi(
            ApiClient(
                client = OkHttpClient(),
                baseUrl = server.url("/api/v1").toString().trimEnd('/'),
                tokenStore = tokenStore,
                errorGate = ApiErrorGate(
                    // 本测试只发 200，gate 不该被触达。返回 false 表示"非当前会话"，
                    // 是最保守的值（不占防抖窗口）
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

    private fun enqueue(dataJson: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"code":0,"msg":"ok","data":$dataJson}"""),
        )
    }

    // ── For You ───────────────────────────────────────────

    @Test
    fun `For You 命中推荐接口且带 session 与 size 21`() = runTest {
        buildApi()
        enqueue("""{"list":[]}""")
        api.fetchPage(
            series = HomeSeries.FOR_YOU,
            page = 0,
            gender = HomeGender.FEMALE,
            nsfw = false,
            languageCode = "zh-tw",
            tagIds = listOf("t1", "t2"),
            contentType = 1,
            sessionId = "client_abc",
        )
        val request = server.takeRequest()
        assertEquals("/api/v1/recommend/recommend_feed/list", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals(0, body.getInt("page"))
        assertEquals(HomeApi.PAGE_SIZE, body.getInt("size"))
        assertEquals(21, body.getInt("size")) // 固定值，方案 §8.1「不要优化」
        assertEquals("client_abc", body.getString("session_id"))
        assertEquals("female", body.getString("gender"))
        assertEquals("zh-tw", body.getString("language_code"))
        assertEquals(1, body.getInt("content_type"))
        assertEquals(2, body.getJSONArray("tag_ids").length())
    }

    @Test
    fun `gender 为 All 时省略字段而不是发 null`() = runTest {
        // RN 的 genderMap['All'] 是 undefined，JSON.stringify 会**省略**该键。
        // 发 null 与省略在后端不等价（null 可能被当作显式筛选）
        buildApi()
        enqueue("""{"list":[]}""")
        api.fetchPage(
            HomeSeries.FOR_YOU, 0, HomeGender.ALL, false, "en", emptyList(), null, "s",
        )
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse("gender 不应出现", body.has("gender"))
        assertFalse("content_type 不应出现", body.has("content_type"))
    }

    @Test
    fun `tag_ids 恒发空数组而非省略`() = runTest {
        // character.ts:44 显式 `tag_ids: req.tag_ids || []` —— 省略时后端行为未定义
        buildApi()
        enqueue("""{"list":[]}""")
        api.fetchPage(HomeSeries.FOR_YOU, 0, HomeGender.ALL, false, "en", emptyList(), null, "s")
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(body.has("tag_ids"))
        assertEquals(0, body.getJSONArray("tag_ids").length())
    }

    // ── public_list ───────────────────────────────────────

    @Test
    fun `其余系列命中 public_list 且 sorting 逐个对齐`() = runTest {
        val expected = mapOf(
            HomeSeries.WEEKLY_PICKS to "WeeklyPicks",
            HomeSeries.NEW_RELEASES to "New",
            HomeSeries.ALL_TIME_FAVES to "Popular",
            HomeSeries.FOLLOWING to "FollowersCharacterNew",
        )
        for ((series, sorting) in expected) {
            buildApi()
            enqueue("""{"list":[]}""")
            api.fetchPage(series, 0, HomeGender.ALL, false, "en", emptyList(), null, "s")
            val request = server.takeRequest()
            assertEquals("/api/v1/character/get/public_list", request.path)
            val body = JSONObject(request.body.readUtf8())
            // ⚠️ sorting 与系列名不是同一个值（All-Time Faves → Popular）
            assertEquals("${series.key} 的 sorting", sorting, body.getString("sorting"))
        }
    }

    @Test
    fun `Following 不带标签筛选`() = runTest {
        // useHomeCharacterLists.ts:89 的 `isFollowing ? [] : tags`。
        // 带上会让关注列表被标签过滤掉大半，而 UI 上 Following 没有筛选入口
        buildApi()
        enqueue("""{"list":[]}""")
        api.fetchPage(
            HomeSeries.FOLLOWING, 0, HomeGender.ALL, false, "en",
            tagIds = listOf("t1", "t2"), contentType = null, sessionId = "s",
        )
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(0, body.getJSONArray("tag_ids").length())
    }

    @Test
    fun `public_list 不发客户端专属的 tracking session 字段`() = runTest {
        // character.ts:59-61 把 recommend_tracking_session_id 解构掉才发出。
        // 发上去是后端不认识的字段
        buildApi()
        enqueue("""{"list":[]}""")
        api.fetchPage(HomeSeries.WEEKLY_PICKS, 0, HomeGender.ALL, false, "en", emptyList(), null, "s")
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(body.has("recommend_tracking_session_id"))
    }

    // ── World ─────────────────────────────────────────────

    @Test
    fun `World 命中 game 接口且每页 20`() = runTest {
        // ⚠️ 20 而不是 21（useHomeCharacterLists.ts:149）
        buildApi()
        enqueue("""{"items":[],"has_more":false}""")
        api.fetchPage(HomeSeries.WORLD, 0, HomeGender.ALL, false, "ja", emptyList(), null, "s")
        val request = server.takeRequest()
        assertEquals("/api/v1/game/public/projects", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals(HomeApi.WORLD_PAGE_SIZE, body.getInt("size"))
        assertEquals(20, body.getInt("size"))
        assertEquals("ja", body.getString("language_code"))
    }

    @Test
    fun `World 空语言回落 en —— 其他系列不回落`() = runTest {
        // useHomeCharacterLists.ts:147 `language_code: language || 'en'` 只在 World 有
        buildApi()
        enqueue("""{"items":[],"has_more":false}""")
        api.fetchPage(HomeSeries.WORLD, 0, HomeGender.ALL, false, "", emptyList(), null, "s")
        assertEquals("en", JSONObject(server.takeRequest().body.readUtf8()).getString("language_code"))

        buildApi()
        enqueue("""{"list":[]}""")
        api.fetchPage(HomeSeries.FOR_YOU, 0, HomeGender.ALL, false, "", emptyList(), null, "s")
        assertEquals("", JSONObject(server.takeRequest().body.readUtf8()).getString("language_code"))
    }

    // ── 鉴权模式 ──────────────────────────────────────────

    @Test
    fun `三个接口都是 OPPORTUNISTIC —— 有 token 就带`() = runTest {
        // 方案 §4.5 记的 iOS 事故：把 axiosPublic 实现成"永不带 token"会让
        // For You 拿不到个性化推荐、Following 返回空，且**不报错**
        buildApi(token = VALID_TOKEN)
        enqueue("""{"list":[]}""")
        api.fetchPage(HomeSeries.FOR_YOU, 0, HomeGender.ALL, false, "en", emptyList(), null, "s")
        // token 走 `token` header，不是 Authorization: Bearer
        assertEquals(VALID_TOKEN, server.takeRequest().getHeader("token"))
    }

    @Test
    fun `无 token 时照发请求且不带 token header`() = runTest {
        // OPPORTUNISTIC 与 REQUIRED 的关键差别：前者无 token 也要发
        // （游客能浏览首页）。实现成 REQUIRED 会让未登录用户看到空首页
        buildApi(token = null)
        enqueue("""{"list":[]}""")
        api.fetchPage(HomeSeries.FOR_YOU, 0, HomeGender.ALL, false, "en", emptyList(), null, "s")
        val request = server.takeRequest()
        assertNull(request.getHeader("token"))
        assertEquals(1, server.requestCount)
    }

    private companion object {
        const val NOW = 1_700_000_000L

        /** exp 远在未来的最小 JWT（payload 只需 exp/sub）。 */
        val VALID_TOKEN: String = buildJwt(exp = NOW + 86_400, sub = "u1")

        fun buildJwt(exp: Long, sub: String): String {
            fun b64(json: String) = java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.toByteArray())
            return "${b64("""{"alg":"HS256"}""")}.${b64("""{"exp":$exp,"sub":"$sub"}""")}.sig"
        }
    }
}
