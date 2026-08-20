package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.auth.Generations
import ai.lightspeed.tipsy.shell.pages.screen.ScreenCardEvent
import ai.lightspeed.tipsy.shell.pages.screen.ScreenEndpoint
import ai.lightspeed.tipsy.shell.pages.screen.ScreenFeedItem
import ai.lightspeed.tipsy.shell.pages.screen.ScreenFirstScreenCache
import ai.lightspeed.tipsy.shell.pages.screen.ScreenMediaSourceType
import ai.lightspeed.tipsy.shell.pages.screen.ScreenPage
import ai.lightspeed.tipsy.shell.pages.screen.ScreenSessionTracker
import ai.lightspeed.tipsy.shell.pages.screen.ScreenSource
import ai.lightspeed.tipsy.shell.pages.screen.ScreenViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 大屏页编排（W4-P1，§2.35）。
 *
 * 重点是「错了不报错」的四件事：AB 三个前置条件、session 只在翻页复用、
 * 首屏缓存的读写顺序、埋点会话的双轴起止。
 */
class ScreenViewModelTest {

    // ── AB 端点分流 ────────────────────────────────

    @Test
    fun `游客恒走 distribution 即使 flag 为真`() = runTest {
        // ⚠️ 最容易漏的一条：ownerUserId 为空时 RN 的 resolveConfigs 直接
        // 返回空 map，flag 根本读不到 → distribution
        val api = FakeApi()
        val vm = viewModel(api, owner = null)
        vm.onEndpointResolved(flagEnabled = true)
        advanceUntilIdle()

        assertEquals(ScreenEndpoint.DISTRIBUTION, vm.state.value.endpoint)
        assertEquals(ScreenEndpoint.DISTRIBUTION, api.calls.first().endpoint)
    }

    @Test
    fun `已登录且 flag 开走 recommendation`() = runTest {
        val api = FakeApi()
        val vm = viewModel(api, owner = "u1")
        vm.onEndpointResolved(flagEnabled = true)
        advanceUntilIdle()

        assertEquals(ScreenEndpoint.RECOMMENDATION, vm.state.value.endpoint)
    }

    @Test
    fun `已登录但 flag 关走 distribution`() = runTest {
        val api = FakeApi()
        val vm = viewModel(api, owner = "u1")
        vm.onEndpointResolved(flagEnabled = false)
        advanceUntilIdle()

        assertEquals(ScreenEndpoint.DISTRIBUTION, vm.state.value.endpoint)
    }

    // ── session 复用规则 ───────────────────────────

    /**
     * ⚠️ 首屏**不带** session；翻页才带上一次响应的。
     *
     * 写成「一直复用」会让切筛选/刷新后仍在旧推荐池里翻页，内容不更新。
     */
    @Test
    fun `首屏不带 session 翻页才带`() = runTest {
        val api = FakeApi()
        api.pages = mapOf(
            0 to page(listOf(item("a"), item("b"), item("c")), sessionId = "sess-1"),
            1 to page(listOf(item("d")), sessionId = "sess-2"),
        )
        val vm = viewModel(api, owner = "u1")
        vm.onEndpointResolved(flagEnabled = true)
        advanceUntilIdle()

        assertNull("首屏不该带 session", api.calls[0].sessionId)

        // 翻到接近尾部触发预拉
        vm.onPageChanged(1)
        advanceUntilIdle()
        assertEquals("翻页要带上一次响应的 session", "sess-1", api.calls[1].sessionId)
    }

    @Test
    fun `下拉刷新清掉 session`() = runTest {
        val api = FakeApi()
        api.pages = mapOf(0 to page(listOf(item("a"), item("b")), sessionId = "sess-1"))
        val vm = viewModel(api, owner = "u1")
        vm.onEndpointResolved(flagEnabled = true)
        advanceUntilIdle()

        vm.onRefresh()
        advanceUntilIdle()
        assertNull("刷新必须清 session", api.calls.last().sessionId)
    }

    // ── 首屏缓存的读写顺序 ─────────────────────────

    /**
     * ⚠️⚠️ 缓存必须在**发请求前**读。
     *
     * 若先写后读，冷启动时会把网络第 0 条写进去再读回来当缓存头，
     * 而 merge 的 drop(1) 又把它从 rest 去掉 —— 等价于没有缓存，
     * 且首屏顺序与现网不同（现网首次从第 2 条开始）。**不报错**。
     */
    @Test
    fun `冷启动无缓存时首屏从第二条开始并把第一条写进缓存`() = runTest {
        val api = FakeApi()
        api.pages = mapOf(0 to page(listOf(item("a"), item("b"), item("c"))))
        val cache = FakeCache()
        val vm = viewModel(api, owner = "u1", cache = cache)
        vm.onEndpointResolved(flagEnabled = true)
        advanceUntilIdle()

        assertEquals(listOf("b", "c"), vm.state.value.items.map { it.characterId })
        assertEquals("第一条要进缓存供下次冷启动", "a", cache.stored?.characterId)
    }

    @Test
    fun `有缓存时缓存卡顶到列表头`() = runTest {
        val api = FakeApi()
        api.pages = mapOf(0 to page(listOf(item("a"), item("b"), item("c"))))
        val cache = FakeCache()
        cache.preset = item("z")
        val vm = viewModel(api, owner = "u1", cache = cache)
        vm.onEndpointResolved(flagEnabled = true)
        advanceUntilIdle()

        assertEquals(listOf("z", "b", "c"), vm.state.value.items.map { it.characterId })
    }

    @Test
    fun `下拉刷新不走缓存合并 用全量网络列表`() = runTest {
        val api = FakeApi()
        api.pages = mapOf(0 to page(listOf(item("a"), item("b"))))
        val cache = FakeCache()
        cache.preset = item("z")
        val vm = viewModel(api, owner = "u1", cache = cache)
        vm.onEndpointResolved(flagEnabled = true)
        advanceUntilIdle()

        vm.onRefresh()
        advanceUntilIdle()
        // 刷新后头就是网络第一条 —— 这是"被 drop 的那条"能上屏的唯一途径
        assertEquals(listOf("a", "b"), vm.state.value.items.map { it.characterId })
    }

    // ── 归因诊断 ────────────────────────────────────

    @Test
    fun `recommendation 缺归因字段要报诊断事件`() = runTest {
        val api = FakeApi()
        // sessionId 为空 → 缺 session_id
        api.pages = mapOf(0 to page(listOf(item("a"), item("b")), sessionId = null, requestId = "r"))
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val vm = viewModel(api, owner = "u1", events = events)
        vm.onEndpointResolved(flagEnabled = true)
        advanceUntilIdle()

        val missing = events.filter { it.first == ScreenSessionTracker.EVENT_ATTRIBUTION_MISSING }
        assertEquals(1, missing.size)
        assertEquals("session_id", missing[0].second["missing_fields"])
        assertEquals("/recommend/home/list", missing[0].second["endpoint"])
    }

    @Test
    fun `distribution 不报归因诊断`() = runTest {
        val api = FakeApi()
        api.pages = mapOf(0 to page(listOf(item("a"), item("b"))))
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val vm = viewModel(api, owner = null, events = events)
        vm.onEndpointResolved(flagEnabled = false)
        advanceUntilIdle()

        assertTrue(
            events.none { it.first == ScreenSessionTracker.EVENT_ATTRIBUTION_MISSING },
        )
    }

    // ── 会话埋点的双轴起止 ─────────────────────────

    @Test
    fun `失焦结束会话 聚焦重开`() = runTest {
        val api = FakeApi()
        api.pages = mapOf(0 to page(listOf(item("a"), item("b"))))
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val vm = viewModel(api, owner = "u1", events = events)
        vm.onEndpointResolved(flagEnabled = true)
        advanceUntilIdle()

        vm.onFocusChanged(true)
        vm.onFocusChanged(false)
        vm.onFocusChanged(true)
        advanceUntilIdle()

        val names = events.map { it.first }
        assertEquals(2, names.count { it == ScreenSessionTracker.EVENT_SESSION_START })
        assertEquals(1, names.count { it == ScreenSessionTracker.EVENT_SESSION_END })
    }

    /**
     * ⚠️ 切后台要 end、回前台重开新会话 —— 不是暂停。
     * 只挂 Fragment 生命周期会漏掉这条，得到一个跨数小时的畸形长会话。
     */
    @Test
    fun `切后台结束会话 回前台重开`() = runTest {
        val api = FakeApi()
        api.pages = mapOf(0 to page(listOf(item("a"), item("b"))))
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val vm = viewModel(api, owner = "u1", events = events)
        vm.onEndpointResolved(flagEnabled = true)
        advanceUntilIdle()
        vm.onFocusChanged(true)
        events.clear()

        vm.onAppForegroundChanged(foreground = false, focused = true)
        vm.onAppForegroundChanged(foreground = true, focused = true)
        advanceUntilIdle()

        assertEquals(
            listOf(ScreenSessionTracker.EVENT_SESSION_END),
            events.map { it.first }.filter { it == ScreenSessionTracker.EVENT_SESSION_END },
        )
        assertTrue(events.any { it.first == ScreenSessionTracker.EVENT_SESSION_START })
    }

    @Test
    fun `页面不聚焦时前后台变化不动会话`() = runTest {
        val api = FakeApi()
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val vm = viewModel(api, owner = "u1", events = events)

        vm.onAppForegroundChanged(foreground = true, focused = false)
        vm.onAppForegroundChanged(foreground = false, focused = false)
        advanceUntilIdle()

        assertTrue(events.isEmpty())
    }

    @Test
    fun `CTA 前报输入点击并结束会话`() = runTest {
        val api = FakeApi()
        api.pages = mapOf(0 to page(listOf(item("a"), item("b"))))
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val vm = viewModel(api, owner = "u1", events = events)
        vm.onEndpointResolved(flagEnabled = true)
        advanceUntilIdle()
        vm.onFocusChanged(true)
        events.clear()

        vm.onStartChat()
        advanceUntilIdle()

        val names = events.map { it.first }
        assertTrue(ScreenSessionTracker.EVENT_INPUT_CLICK in names)
        assertTrue(ScreenSessionTracker.EVENT_SESSION_END in names)
        // 顺序：input_click 先于 session_end（RN screen.tsx:639-640）
        assertTrue(
            names.indexOf(ScreenSessionTracker.EVENT_INPUT_CLICK) <
                names.indexOf(ScreenSessionTracker.EVENT_SESSION_END),
        )
    }

    @Test
    fun `无会话时卡片事件静默丢弃`() = runTest {
        val api = FakeApi()
        api.pages = mapOf(0 to page(listOf(item("a"), item("b"))))
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val vm = viewModel(api, owner = "u1", events = events)
        vm.onEndpointResolved(flagEnabled = true)
        advanceUntilIdle()
        // 未 onFocusChanged(true) → 无会话
        events.clear()

        vm.onCardEvent(ScreenCardEvent.LIKE_CLICK)
        advanceUntilIdle()
        assertTrue(events.isEmpty())
    }

    // ── 分页与失败 ──────────────────────────────────

    @Test
    fun `空页判到底后不再翻页`() = runTest {
        val api = FakeApi()
        api.pages = mapOf(
            0 to page(listOf(item("a"), item("b"), item("c"))),
            1 to page(emptyList()),
        )
        val vm = viewModel(api, owner = "u1")
        vm.onEndpointResolved(flagEnabled = true)
        advanceUntilIdle()
        vm.onPageChanged(1)
        advanceUntilIdle()
        assertTrue(vm.state.value.hasReachedEnd)
        val before = api.calls.size

        vm.onPageChanged(0)
        vm.onPageChanged(1)
        advanceUntilIdle()
        assertEquals(before, api.calls.size)
    }

    @Test
    fun `首屏失败给重试态`() = runTest {
        val api = FakeApi()
        api.fail = true
        val vm = viewModel(api, owner = "u1")
        vm.onEndpointResolved(flagEnabled = true)
        advanceUntilIdle()

        assertTrue(vm.state.value.isRetryable)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `已有内容时翻页失败不摆错误`() = runTest {
        val api = FakeApi()
        api.pages = mapOf(0 to page(listOf(item("a"), item("b"), item("c"))))
        val vm = viewModel(api, owner = "u1")
        vm.onEndpointResolved(flagEnabled = true)
        advanceUntilIdle()

        api.fail = true
        vm.onPageChanged(1)
        advanceUntilIdle()

        assertFalse("已有内容不该显示错误", vm.state.value.isRetryable)
        assertEquals(2, vm.state.value.items.size)
    }

    // ── auth 轨 ─────────────────────────────────────

    @Test
    fun `换号后在飞响应不写状态`() = runTest {
        val generations = Generations()
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi()
        api.pages = mapOf(0 to page(listOf(item("a"), item("b"))))
        api.gate = gate
        val vm = viewModel(api, owner = "u1", generations = generations)
        vm.onEndpointResolved(flagEnabled = true)
        advanceUntilIdle()

        generations.bumpAuth()
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue("换号后旧响应不该上屏", vm.state.value.items.isEmpty())
    }

    @Test
    fun `登录态变化清空页面与会话`() = runTest {
        val api = FakeApi()
        api.pages = mapOf(0 to page(listOf(item("a"), item("b"))))
        val vm = viewModel(api, owner = "u1")
        vm.onEndpointResolved(flagEnabled = true)
        advanceUntilIdle()
        vm.onFocusChanged(true)

        vm.onAuthChanged()
        advanceUntilIdle()

        assertTrue(vm.state.value.items.isEmpty())
        assertNull("端点要重解析", vm.state.value.endpoint)
    }

    // ── 埋点参数 ────────────────────────────────────

    @Test
    fun `card_id 是下标加一 screen_bucket 前四张为一`() = runTest {
        assertEquals(1, ScreenSessionTracker.cardIdOf(0))
        assertEquals(1, ScreenSessionTracker.screenBucketOf(3))
        assertEquals(2, ScreenSessionTracker.screenBucketOf(4))
    }

    // ── 脚手架 ──────────────────────────────────────

    private fun TestScope.viewModel(
        api: FakeApi,
        owner: String?,
        cache: ScreenFirstScreenCache = FakeCache(),
        generations: Generations = Generations(),
        events: MutableList<Pair<String, Map<String, String>>> = mutableListOf(),
    ) = ScreenViewModel(
        api = api,
        tracker = ScreenSessionTracker(
            sessionIdFactory = { "sess-fixed" },
            track = { name, params -> events += name to params },
        ),
        cache = cache,
        generations = generations,
        languageProvider = { "en" },
        nsfwProvider = { false },
        ownerUserIdProvider = { owner },
        scope = this,
        logWarn = { _, _ -> },
    )

    private class FakeApi : ScreenSource {
        data class Call(val endpoint: ScreenEndpoint, val page: Int, val sessionId: String?)

        val calls = mutableListOf<Call>()
        var pages: Map<Int, ScreenPage> = emptyMap()
        var fail = false
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun fetchPage(
            endpoint: ScreenEndpoint,
            page: Int,
            nsfw: Boolean,
            gender: String?,
            languageCode: String,
            tagIds: List<String>,
            contentType: Int?,
            sessionId: String?,
        ): ScreenPage {
            calls += Call(endpoint, page, sessionId)
            gate?.await()
            if (fail) throw RuntimeException("screen boom")
            return pages[page] ?: ScreenPage(emptyList(), null, null)
        }
    }

    private class FakeCache : ScreenFirstScreenCache {
        var preset: ScreenFeedItem? = null
        var stored: ScreenFeedItem? = null

        override fun get(signature: String): ScreenFeedItem? = preset
        override fun put(signature: String, item: ScreenFeedItem) {
            stored = item
        }
    }

    private fun page(
        items: List<ScreenFeedItem>,
        requestId: String? = "req-1",
        sessionId: String? = "sess-1",
    ) = ScreenPage(items = items, requestId = requestId, sessionId = sessionId)

    private fun item(id: String) = ScreenFeedItem(
        characterId = id,
        mediaSourceType = ScreenMediaSourceType.STATIC_IMAGE,
        backgroundUrl = null,
        thumbnailUrl = null,
        imageUrl = null,
        tagline = "",
        greeting = "",
        nickname = id,
        creatorId = null,
        creatorNickname = null,
        creatorAvatarUrl = null,
        avatarUrl = null,
        likeCount = 0,
        commentCount = 0,
        totalMessages = 0,
        primaryColor = null,
        gender = null,
        nsfw = null,
        isTranslated = false,
        lang = null,
        characterType = null,
        contentType = null,
    )

}
