package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.analytics.Analytics
import ai.lightspeed.tipsy.shell.auth.Generations
import ai.lightspeed.tipsy.shell.network.ApiException
import ai.lightspeed.tipsy.shell.pages.home.HomeFeedItem
import ai.lightspeed.tipsy.shell.pages.home.HomeTag
import ai.lightspeed.tipsy.shell.pages.search.CharacterSearchOutcome
import ai.lightspeed.tipsy.shell.pages.search.CharacterSearchPage
import ai.lightspeed.tipsy.shell.pages.search.CreatorResult
import ai.lightspeed.tipsy.shell.pages.search.CreatorSearchPage
import ai.lightspeed.tipsy.shell.pages.search.LoadMoreRequestGate
import ai.lightspeed.tipsy.shell.pages.search.SearchCharacterQuery
import ai.lightspeed.tipsy.shell.pages.search.SearchContentRating
import ai.lightspeed.tipsy.shell.pages.search.SearchGender
import ai.lightspeed.tipsy.shell.pages.search.SearchSorting
import ai.lightspeed.tipsy.shell.pages.search.SearchSource
import ai.lightspeed.tipsy.shell.pages.search.SearchTab
import ai.lightspeed.tipsy.shell.pages.search.SearchViewModel
import ai.lightspeed.tipsy.shell.pages.search.SearchWay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SearchViewModel` 的编排语义。
 *
 * 重点是四类**本地难复现**的时序问题：
 * - 防抖：连打字只发最后一次
 * - 查询隔离：A 的晚响应不得覆盖已完成的 B
 * - 翻页三重守卫：空列表 / 在途 / 已到底都不该发请求
 * - generation 闸门：auth 变化作废响应，不相关的 mutation 不作废
 */
class SearchViewModelTest {

    // ── 防抖 ────────────────────────────────

    @Test
    fun `连续提交只发最后一次搜索`() = runTest {
        val api = RecordingApi()
        val vm = viewModel(api, debounceMillis = 500)

        vm.submitQuery("a")
        vm.submitQuery("ab")
        vm.submitQuery("abc")
        advanceUntilIdle()

        assertEquals("防抖窗口内只该留最后一次", listOf("abc"), api.characterQueries.map { it.searchTerm })
    }

    @Test
    fun `输入变化不发搜索只拉建议词`() = runTest {
        val api = RecordingApi()
        val vm = viewModel(api)

        vm.onQueryChange("ela")
        advanceUntilIdle()

        assertTrue("输入阶段不该发搜索请求", api.characterQueries.isEmpty())
        assertEquals(listOf("ela"), api.suggestQueries)
        assertEquals("仍停在未搜索态", SearchTab.NONE, vm.state.value.tab)
    }

    @Test
    fun `空白查询不触发搜索`() = runTest {
        val api = RecordingApi()
        val vm = viewModel(api)

        vm.submitQuery("   ")
        advanceUntilIdle()

        assertTrue(api.characterQueries.isEmpty())
    }

    @Test
    fun `API 与搜索埋点保留同一原始查询`() = runTest {
        val sent = mutableListOf<Pair<String, Map<String, Any?>>>()
        Analytics.resetForTest()
        try {
            Analytics.install { eventId, params, _ -> sent += eventId to params }
            val api = RecordingApi()
            val vm = viewModel(api)

            vm.submitQuery("  elaine ")
            advanceUntilIdle()

            assertEquals("  elaine ", vm.state.value.query)
            assertEquals("  elaine ", api.characterQueries.single().searchTerm)
            assertEquals("  elaine ", api.creatorQueries.single().first)
            assertEquals(
                listOf("  elaine ", "  elaine ", "  elaine "),
                sent.filter {
                    it.first == "search_trigger_page_exposure" ||
                        it.first == "search_result_page_exposure"
                }.map { it.second["query"] },
            )
        } finally {
            Analytics.resetForTest()
        }
    }

    // ── 双查询 ────────────────────────────────

    @Test
    fun `提交搜索会同时查角色与创作者并切到角色 tab`() = runTest {
        val api = RecordingApi()
        api.characterPage = page(hits = listOf(character("c1")), total = 1, sessionId = "cs")
        api.creatorPage = creatorPage(hits = listOf(creator("u1")), total = 1, sessionId = "us")
        val vm = viewModel(api)

        vm.submitQuery("elaine")
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals(SearchTab.CHARACTERS, s.tab)
        assertEquals(listOf("c1"), s.characterResults.map { it.characterId })
        assertEquals(listOf("u1"), s.creatorResults.map { it.userId })
        assertEquals("cs", s.characterSessionId)
        assertEquals("us", s.creatorSessionId)
        assertFalse("查询完成后不该还在 loading", s.isLoading)
    }

    /**
     * 创作者查询是 fire-and-forget：它慢的时候**不该**拖住 Characters tab 的
     * loading 关闭（`useSearch.ts:298-299` 的不对称 await）。
     */
    @Test
    fun `创作者查询未返回也不阻塞角色结果展示`() = runTest {
        val api = RecordingApi()
        api.characterPage = page(hits = listOf(character("c1")), total = 1)
        val gate = CompletableDeferred<Unit>()
        api.creatorGate = gate
        val vm = viewModel(api)

        vm.submitQuery("elaine")
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals(listOf("c1"), s.characterResults.map { it.characterId })
        assertFalse("创作者还没回，角色 tab 不该转圈", s.isLoading)
        gate.complete(Unit)
    }

    // ── 跨查询隔离 ───────────────

    /**
     * 角色请求随 debounce job 取消，但 HTTP 层仍可能在取消与回写之间
     * 完成。fake 故意不响应协程取消，强制覆盖这个窗口。
     */
    @Test
    fun `A 的角色晚响应不覆盖 B`() = runTest {
        val api = RecordingApi()
        val oldGate = CompletableDeferred<Unit>()
        api.characterGatesByQuery = mapOf("old" to oldGate)
        api.characterPagesByQuery = mapOf(
            "old" to page(listOf(character("old-character")), total = 1, sessionId = "old-cs"),
            "new" to page(listOf(character("new-character")), total = 1, sessionId = "new-cs"),
        )
        val vm = viewModel(api)

        vm.submitQuery("old")
        advanceUntilIdle()
        assertEquals(listOf("old"), api.characterQueries.map { it.searchTerm })

        vm.submitQuery("new")
        advanceUntilIdle()
        assertEquals(listOf("new-character"), vm.state.value.characterResults.map { it.characterId })

        oldGate.complete(Unit)
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("new", s.query)
        assertEquals(
            "A 的角色响应晚到时不得改写 B 的列表",
            listOf("new-character"),
            s.characterResults.map { it.characterId },
        )
        assertEquals("new-cs", s.characterSessionId)
        assertFalse(s.isLoading)
    }

    /** 创作者查询是独立 launch，提交 B 并不会取消 A，必须靠查询身份隔离。 */
    @Test
    fun `A 的创作者晚响应不覆盖 B`() = runTest {
        val api = RecordingApi()
        val oldGate = CompletableDeferred<Unit>()
        api.creatorGatesByQuery = mapOf("old" to oldGate)
        api.creatorPagesByQuery = mapOf(
            "old" to creatorPage(listOf(creator("old-creator")), total = 1, sessionId = "old-us"),
            "new" to creatorPage(listOf(creator("new-creator")), total = 1, sessionId = "new-us"),
        )
        val vm = viewModel(api)

        vm.submitQuery("old")
        advanceUntilIdle()
        assertEquals(listOf("old"), api.creatorQueries.map { it.first })

        vm.submitQuery("new")
        advanceUntilIdle()
        assertEquals(listOf("new-creator"), vm.state.value.creatorResults.map { it.userId })

        oldGate.complete(Unit)
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("new", s.query)
        assertEquals(
            "A 的创作者响应晚到时不得改写 B 的列表",
            listOf("new-creator"),
            s.creatorResults.map { it.userId },
        )
        assertEquals("new-us", s.creatorSessionId)
        assertFalse(s.isLoading)
    }

    // ── 失败处理 ────────────────────────────────

    /**
     * 首查失败要回到 IDLE —— 否则空态会把「请求失败」当成「搜到 0 条」
     * 并诱导用户去创建角色。
     */
    @Test
    fun `首查失败回到 IDLE 且不显示创建按钮`() = runTest {
        val api = RecordingApi()
        api.failCharacter = true
        val vm = viewModel(api)

        vm.submitQuery("elaine")
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals(CharacterSearchOutcome.IDLE, s.characterOutcome)
        assertFalse(
            "请求失败时显示 Create Now 会把网络错误误导成「没这个角色」",
            s.showsCreateCharacterButton,
        )
        assertEquals("Failed", s.toastKey)
    }

    @Test
    fun `搜到零条且响应正常时显示创建按钮`() = runTest {
        val api = RecordingApi()
        api.characterPage = page(hits = emptyList(), total = 0, outcome = CharacterSearchOutcome.SAFE)
        val vm = viewModel(api)

        vm.submitQuery("nonexistent")
        advanceUntilIdle()

        assertTrue(vm.state.value.showsCreateCharacterButton)
    }

    // ── 翻页三重守卫 ────────────────────────────────

    @Test
    fun `列表为空时不翻页`() = runTest {
        val api = RecordingApi()
        api.characterPage = page(hits = emptyList(), total = 100)
        val vm = viewModel(api)
        vm.submitQuery("x")
        advanceUntilIdle()
        val before = api.characterQueries.size

        vm.loadMore()
        advanceUntilIdle()

        assertEquals(
            "空列表也会触发 onEndReached —— 不挡住会与首查并发",
            before,
            api.characterQueries.size,
        )
    }

    @Test
    fun `已加载全部时不翻页`() = runTest {
        val api = RecordingApi()
        api.characterPage = page(hits = listOf(character("c1")), total = 1)
        val vm = viewModel(api)
        vm.submitQuery("x")
        advanceUntilIdle()
        val before = api.characterQueries.size

        vm.loadMore()
        advanceUntilIdle()

        assertEquals(before, api.characterQueries.size)
    }

    @Test
    fun `翻页请求在途时重复触发不会并发`() = runTest {
        val api = RecordingApi()
        api.characterPage = page(hits = listOf(character("c1")), total = 100)
        val vm = viewModel(api)
        vm.submitQuery("x")
        advanceUntilIdle()
        val afterFirst = api.characterQueries.size

        // 闸住第 2 页，让它停在「已发出、未返回」
        val gate = CompletableDeferred<Unit>()
        api.characterGate = gate
        vm.loadMore()
        // ⚠️ 必须先跑调度器：loadMore 是 launch，同步断言时请求还没发出
        advanceUntilIdle()
        val inFlight = api.characterQueries.size
        assertEquals("第一次触发该发出一个请求", afterFirst + 1, inFlight)

        // 请求在飞时再触发两次
        vm.loadMore()
        vm.loadMore()
        advanceUntilIdle()

        assertEquals(
            "在途时的重复触发必须被守卫挡住，否则同一页会被拉两次",
            inFlight,
            api.characterQueries.size,
        )
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `翻页在途时重新提交也会重置 loadingMore`() = runTest {
        val api = RecordingApi()
        api.characterPage = page(hits = listOf(character("old")), total = 100)
        val vm = viewModel(api)
        vm.submitQuery("old")
        advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        api.characterGate = gate
        vm.loadMore()
        advanceUntilIdle()
        assertTrue(vm.state.value.isLoadingMore)

        // 新请求不走旧闸门；旧翻页 job 被取消，但其 finally 因 seq 已变不会改新状态。
        api.characterGate = null
        api.characterPagesByQuery = mapOf(
            "new" to page(hits = listOf(character("new")), total = 1),
        )
        vm.submitQuery("new")
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf("new"), state.characterResults.map { it.characterId })
        assertFalse("旧翻页的 loadingMore 不得泄漏进新搜索", state.isLoadingMore)
        assertFalse(state.isLoading)
    }

    @Test
    fun `翻页页码递增且结果追加`() = runTest {
        val api = RecordingApi()
        api.characterPage = page(hits = listOf(character("c1")), total = 100, sessionId = "page-1")
        val vm = viewModel(api)
        vm.submitQuery("x")
        advanceUntilIdle()

        api.characterPage = page(hits = listOf(character("c2")), total = 100, sessionId = "page-2")
        vm.loadMore()
        advanceUntilIdle()

        assertEquals(listOf(1, 2), api.characterQueries.map { it.page })
        assertEquals(listOf("c1", "c2"), vm.state.value.characterResults.map { it.characterId })
        assertEquals("翻页返回新 session 时点击归因要跟随更新", "page-2", vm.state.value.characterSessionId)
    }

    @Test
    fun `翻页重复项按稳定 id 去重避免 Compose key 冲突`() = runTest {
        val api = RecordingApi()
        api.characterPage = page(hits = listOf(character("c1"), character("c1")), total = 3)
        api.creatorPage = creatorPage(hits = listOf(creator("u1"), creator("u1")), total = 3)
        val vm = viewModel(api)
        vm.submitQuery("x")
        advanceUntilIdle()

        api.characterPage = page(hits = listOf(character("c1"), character("c2")), total = 3)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("c1", "c2"), vm.state.value.characterResults.map { it.characterId })

        vm.onTabChange(SearchTab.CREATORS)
        api.creatorPage = creatorPage(hits = listOf(creator("u1"), creator("u2")), total = 3)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("u1", "u2"), vm.state.value.creatorResults.map { it.userId })
    }

    @Test
    fun `去重后空页会主动续拉到有新结果`() = runTest {
        val api = RecordingApi().apply {
            characterPagesByNumber = mapOf(
                1 to page(listOf(character("c1")), total = 3),
                2 to page(listOf(character("c1")), total = 3),
                3 to page(listOf(character("c2")), total = 3),
            )
            creatorPagesByNumber = mapOf(
                1 to creatorPage(listOf(creator("u1")), total = 3),
                2 to creatorPage(listOf(creator("u1")), total = 3),
                3 to creatorPage(listOf(creator("u2")), total = 3),
            )
        }
        val vm = viewModel(api)
        vm.submitQuery("x")
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3), api.characterQueries.map { it.page })
        assertEquals(listOf("c1", "c2"), vm.state.value.characterResults.map { it.characterId })

        vm.onTabChange(SearchTab.CREATORS)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3), api.creatorPages)
        assertEquals(listOf("u1", "u2"), vm.state.value.creatorResults.map { it.userId })
    }

    @Test
    fun `连续空页续拉最多三页`() = runTest {
        val api = RecordingApi()
        api.characterPage = page(listOf(character("c1")), total = 100)
        val vm = viewModel(api)
        vm.submitQuery("x")
        advanceUntilIdle()

        // 后续每页都只有已展示的 c1；一次 loadMore 最多续拉 page2..page4。
        vm.loadMore()
        advanceUntilIdle()

        assertEquals(listOf(1, 2, 3, 4), api.characterQueries.map { it.page })
        assertEquals(listOf("c1"), vm.state.value.characterResults.map { it.characterId })
        assertFalse(vm.state.value.isLoadingMore)

        vm.loadMore()
        advanceUntilIdle()
        assertEquals(
            "达到上限后 UI 仍在触底阈值内，也不能无限重试",
            listOf(1, 2, 3, 4),
            api.characterQueries.map { it.page },
        )
    }

    @Test
    fun `触底失败时同一列表不无限自动重试`() {
        val gate = LoadMoreRequestGate()

        assertTrue(gate.shouldRequest(nearEnd = true, isBlocked = false, canLoadMore = true, itemCount = 10))
        assertFalse(gate.shouldRequest(nearEnd = true, isBlocked = true, canLoadMore = true, itemCount = 10))
        assertFalse(
            "请求失败后 loading 回落，itemCount 未变时不得立即重试",
            gate.shouldRequest(nearEnd = true, isBlocked = false, canLoadMore = true, itemCount = 10),
        )
        assertTrue(
            "成功增加条目后可继续填满视口",
            gate.shouldRequest(nearEnd = true, isBlocked = false, canLoadMore = true, itemCount = 12),
        )
        assertFalse(gate.shouldRequest(nearEnd = false, isBlocked = false, canLoadMore = true, itemCount = 12))
        assertTrue(
            "滚出再进入阈值后允许用户重试",
            gate.shouldRequest(nearEnd = true, isBlocked = false, canLoadMore = true, itemCount = 12),
        )
    }

    @Test
    fun `创作者 tab 翻页走创作者接口`() = runTest {
        val api = RecordingApi()
        api.characterPage = page(hits = listOf(character("c1")), total = 1)
        api.creatorPage = creatorPage(hits = listOf(creator("u1")), total = 50, sessionId = "page-1")
        val vm = viewModel(api)
        vm.submitQuery("x")
        advanceUntilIdle()
        vm.onTabChange(SearchTab.CREATORS)

        api.creatorPage = creatorPage(hits = listOf(creator("u2")), total = 50, sessionId = "page-2")
        vm.loadMore()
        advanceUntilIdle()

        assertEquals("创作者 tab 该翻创作者的页", listOf(1, 2), api.creatorPages)
        assertEquals("page-2", vm.state.value.creatorSessionId)
    }

    // ── 新搜索重置 ────────────────────────────────

    @Test
    fun `新搜索把页码重置回第一页`() = runTest {
        val api = RecordingApi()
        api.characterPage = page(hits = listOf(character("c1")), total = 100)
        val vm = viewModel(api)
        vm.submitQuery("first")
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()

        vm.submitQuery("second")
        advanceUntilIdle()

        assertEquals(
            "换词后必须从第 1 页开始，否则新词的结果从第 2 页拿",
            1,
            api.characterQueries.last().page,
        )
    }

    // ── auth 闸门 ────────────────────────────────

    /**
     * 搜索在途时登出：旧结果**不得**写进状态。
     * 这类 bug 本地必现不了（要精确时序），只能靠测试钉住。
     */
    @Test
    fun `auth 失效时丢弃在飞结果并关闭 loading`() = runTest {
        val api = RecordingApi()
        val generations = Generations()
        api.characterPage = page(hits = listOf(character("stale-character")), total = 1)
        api.creatorPage = creatorPage(hits = listOf(creator("stale-creator")), total = 1)
        val characterGate = CompletableDeferred<Unit>()
        val creatorGate = CompletableDeferred<Unit>()
        api.characterGate = characterGate
        api.creatorGate = creatorGate
        val vm = viewModel(api, generations = generations)

        vm.submitQuery("x")
        advanceUntilIdle()
        assertTrue(vm.state.value.isLoading)
        // 请求在飞时登出
        generations.bumpAuth()
        characterGate.complete(Unit)
        creatorGate.complete(Unit)
        advanceUntilIdle()

        val s = vm.state.value
        assertTrue("登出后旧账号的角色结果不得出现", s.characterResults.isEmpty())
        assertTrue("登出后旧账号的创作者结果不得出现", s.creatorResults.isEmpty())
        assertFalse("auth 作废响应也必须收掉 loading", s.isLoading)
    }

    @Test
    fun `mutation bump 不作废 Search 在飞结果`() = runTest {
        val api = RecordingApi()
        val generations = Generations()
        api.characterPage = page(
            hits = listOf(character("character")),
            total = 1,
            sessionId = "character-session",
        )
        api.creatorPage = creatorPage(
            hits = listOf(creator("creator")),
            total = 1,
            sessionId = "creator-session",
        )
        val characterGate = CompletableDeferred<Unit>()
        val creatorGate = CompletableDeferred<Unit>()
        api.characterGate = characterGate
        api.creatorGate = creatorGate
        val vm = viewModel(api, generations = generations)

        vm.submitQuery("x")
        advanceUntilIdle()
        generations.bumpMutation()
        characterGate.complete(Unit)
        creatorGate.complete(Unit)
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals(listOf("character"), s.characterResults.map { it.characterId })
        assertEquals(listOf("creator"), s.creatorResults.map { it.userId })
        assertEquals("character-session", s.characterSessionId)
        assertEquals("creator-session", s.creatorSessionId)
        assertFalse(s.isLoading)
    }

    // ── 最近搜索 ────────────────────────────────

    @Test
    fun `未登录不拉最近搜索`() = runTest {
        val api = RecordingApi()
        val vm = viewModel(api, userId = null)

        vm.onAppear()
        advanceUntilIdle()

        assertEquals(
            "recent_history 是 REQUIRED，未登录发了只是白拿一条错误日志",
            0,
            api.recentHistoryCalls,
        )
        assertEquals("热门搜索是公开接口，仍要拉", 1, api.popularCalls)
    }

    @Test
    fun `已登录进页面拉最近与热门`() = runTest {
        val api = RecordingApi()
        api.recentHistory = listOf("elaine", "emi")
        api.popularTerms = listOf("anime")
        val vm = viewModel(api, userId = "u1")

        vm.onAppear()
        advanceUntilIdle()

        assertEquals(listOf("elaine", "emi"), vm.state.value.recentSearches)
        assertEquals(listOf("anime"), vm.state.value.popularTerms)
    }

    @Test
    fun `清空查询会重拉最近搜索`() = runTest {
        val api = RecordingApi().apply { recentHistory = listOf("before") }
        val vm = viewModel(api, userId = "u1")
        vm.onAppear()
        advanceUntilIdle()

        vm.onQueryChange("new query")
        api.recentHistory = listOf("new query", "before")
        val callsBeforeClear = api.recentHistoryCalls
        vm.clearQuery()
        advanceUntilIdle()

        assertEquals(callsBeforeClear + 1, api.recentHistoryCalls)
        assertEquals(listOf("new query", "before"), vm.state.value.recentSearches)
    }

    @Test
    fun `登出清私有搜索且不发请求登录再重拉`() = runTest {
        val api = RecordingApi().apply {
            recentHistory = listOf("old-private-query")
            popularTerms = listOf("public-term")
            characterPage = page(listOf(character("old-result")), total = 1, sessionId = "old-session")
        }
        val vm = viewModel(api, userId = "u1")
        vm.onAppear()
        vm.submitQuery("old-private-query")
        advanceUntilIdle()
        val recentCallsBeforeLogout = api.recentHistoryCalls
        val popularCallsBeforeLogout = api.popularCalls

        vm.onAuthChanged(loggedIn = false)
        advanceUntilIdle()

        val loggedOut = vm.state.value
        assertEquals("", loggedOut.query)
        assertTrue(loggedOut.recentSearches.isEmpty())
        assertTrue(loggedOut.characterResults.isEmpty())
        assertTrue(loggedOut.creatorResults.isEmpty())
        assertEquals("", loggedOut.characterSessionId)
        assertEquals(listOf("public-term"), loggedOut.popularTerms)
        assertEquals("登出不得发 REQUIRED recent_history", recentCallsBeforeLogout, api.recentHistoryCalls)
        assertEquals("登出不主动发任何请求", popularCallsBeforeLogout, api.popularCalls)

        api.recentHistory = listOf("new-account-query")
        api.popularTerms = listOf("new-public-term")
        vm.onAuthChanged(loggedIn = true)
        advanceUntilIdle()

        assertEquals(listOf("new-account-query"), vm.state.value.recentSearches)
        assertEquals(listOf("new-public-term"), vm.state.value.popularTerms)
        assertEquals(recentCallsBeforeLogout + 1, api.recentHistoryCalls)
        assertEquals(popularCallsBeforeLogout + 1, api.popularCalls)
    }

    @Test
    fun `清空最近搜索成功后本地也清空`() = runTest {
        val api = RecordingApi()
        api.recentHistory = listOf("elaine")
        val vm = viewModel(api, userId = "u1")
        vm.onAppear()
        advanceUntilIdle()

        vm.onClearHistoryRequest()
        assertTrue(vm.state.value.showClearHistoryDialog)
        vm.onClearHistoryConfirm()
        advanceUntilIdle()

        assertTrue(vm.state.value.recentSearches.isEmpty())
        assertFalse(vm.state.value.showClearHistoryDialog)
        assertEquals(1, api.clearHistoryCalls)
    }

    @Test
    fun `清空失败时关弹窗但保留列表`() = runTest {
        val api = RecordingApi()
        api.recentHistory = listOf("elaine")
        api.failClear = true
        val vm = viewModel(api, userId = "u1")
        vm.onAppear()
        advanceUntilIdle()

        vm.onClearHistoryRequest()
        vm.onClearHistoryConfirm()
        advanceUntilIdle()

        assertEquals(
            "清空失败不该假装清空了",
            listOf("elaine"),
            vm.state.value.recentSearches,
        )
        assertFalse("必须关弹窗，否则用户卡在里面反复点", vm.state.value.showClearHistoryDialog)
    }

    // ── 建议词 ────────────────────────────────

    @Test
    fun `建议词把原始输入放在第一条且去重`() = runTest {
        val api = RecordingApi()
        api.suggestions = listOf("Elaine", "elaine cosplay", "ELF", "elf")
        val vm = viewModel(api)

        vm.onQueryChange("elaine")
        advanceUntilIdle()

        assertEquals(
            "原始输入恒第一条，重复建议保留 API 首个大小写形式",
            listOf("elaine", "elaine cosplay", "ELF"),
            vm.state.value.displaySuggestions,
        )
    }

    @Test
    fun `建议词失败静默不弹 Toast`() = runTest {
        val api = RecordingApi()
        api.failSuggest = true
        val vm = viewModel(api)

        vm.onQueryChange("ela")
        advanceUntilIdle()

        assertEquals(
            "建议词是增强功能，失败不该打扰用户",
            null,
            vm.state.value.toastKey,
        )
    }

    /**
     * 旧建议词响应晚到时不得覆盖新输入的建议。
     *
     * fake 按查询词返回不同结果 —— 否则「新旧结果长得一样」，测试即使
     * 在实现有 bug 时也会通过（第一版就踩了这个：两次请求返回同一个 list，
     * 断言看到的 "stale" 其实来自新请求）。
     */
    @Test
    fun `旧建议词响应不覆盖新输入`() = runTest {
        val api = RecordingApi()
        api.suggestionsByQuery = mapOf(
            "old" to listOf("old-suggestion"),
            "new" to listOf("new-suggestion"),
        )
        val gate = CompletableDeferred<Unit>()
        api.suggestGate = gate
        val vm = viewModel(api)

        vm.onQueryChange("old")
        advanceUntilIdle() // 旧请求已发出并卡在闸门
        // 闸门放开前改输入：新请求不受闸门限制
        api.suggestGate = null
        vm.onQueryChange("new")
        advanceUntilIdle()
        assertEquals(listOf("new-suggestion"), vm.state.value.suggestions)

        // 旧请求现在才返回
        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(
            "旧词的建议晚到时不该盖掉新词的",
            vm.state.value.suggestions.contains("old-suggestion"),
        )
        assertEquals(listOf("new-suggestion"), vm.state.value.suggestions)
    }

    // ── 查询参数 ────────────────────────────────

    @Test
    fun `P1 无筛选器时不发性别键且排序恒 Recommended`() = runTest {
        val api = RecordingApi()
        val vm = viewModel(api)

        vm.submitQuery("x")
        advanceUntilIdle()

        val q = api.characterQueries.single()
        assertEquals("P1 无筛选：gender 必须为 null（不发这个键）", null, q.gender)
        assertEquals("Recommended", q.sorting)
        assertEquals("All", q.contentRating)
        assertEquals("en", q.languageCode)
        assertTrue(q.tagIds.isEmpty())
    }

    // ── 点击归因 ────────────────────────────────

    @Test
    fun `角色点击补齐 HomeCard 通用事件与搜索归因`() = runTest {
        val sent = mutableListOf<Pair<String, Map<String, Any?>>>()
        Analytics.resetForTest()
        try {
            Analytics.install { eventId, params, _ -> sent += eventId to params }
            val item = character("c1").copy(creatorId = "u1", nsfw = true)
            val api = RecordingApi().apply {
                characterPage = page(listOf(item), total = 1, sessionId = "search-session")
            }
            val vm = viewModel(api)
            vm.submitQuery("elaine")
            advanceUntilIdle()
            sent.clear()

            vm.onCharacterClick(item, itemPosition = 3)

            assertEquals(listOf("character_page_click", "search_content_click"), sent.map { it.first })
            assertEquals("searchCharacter", sent[0].second["scene"])
            assertEquals("c1", sent[0].second["characterId"])
            assertEquals("u1", sent[0].second["creatorId"])
            assertEquals(true, sent[0].second["nsfw"])
            assertEquals("search-session", sent[1].second["session_id"])
            assertEquals(3, sent[1].second["item_position"])
            assertEquals("character", sent[1].second["search_tab"])
        } finally {
            Analytics.resetForTest()
        }
    }

    // ── 筛选（P2，§2.34）────────────────────────

    @Test
    fun `打开抽屉复制已生效筛选 关闭不提交`() = runTest {
        val api = RecordingApi()
        val vm = viewModel(api)
        vm.onFilterDrawerOpen()
        vm.onFilterGenderSelect(SearchGender.MALE)
        advanceUntilIdle()
        assertEquals(SearchGender.MALE, vm.state.value.pendingFilter?.gender)
        // 已生效值不该被改
        assertEquals(SearchGender.ALL, vm.state.value.filter.gender)

        // 关抽屉（点 X / 遮罩）—— RN 的 handleClose 只 setOpen(false)
        vm.onFilterDrawerDismiss()
        advanceUntilIdle()
        assertNull(vm.state.value.pendingFilter)
        assertEquals("关闭不提交", SearchGender.ALL, vm.state.value.filter.gender)
    }

    @Test
    fun `Reset 只回默认三项 不关抽屉不清标签`() = runTest {
        val api = RecordingApi()
        val vm = viewModel(api)
        vm.onFilterDrawerOpen()
        vm.onFilterGenderSelect(SearchGender.MALE)
        vm.onFilterSortingSelect(SearchSorting.LATEST)
        vm.onFilterReset()
        advanceUntilIdle()

        val pending = vm.state.value.pendingFilter
        assertNotNull("Reset 不该关抽屉", pending)
        assertEquals(SearchGender.ALL, pending?.gender)
        assertEquals(SearchSorting.RECOMMENDED, pending?.sorting)
    }

    @Test
    fun `Done 提交筛选并把真值发进请求`() = runTest {
        val api = RecordingApi()
        api.characterPage = page(listOf(character("a")), total = 1)
        val vm = viewModel(api)
        vm.submitQuery("cat")
        advanceUntilIdle()
        val before = api.characterQueries.size

        vm.onFilterDrawerOpen()
        vm.onFilterGenderSelect(SearchGender.NON_BINARY)
        vm.onFilterSortingSelect(SearchSorting.MOST_LIKED)
        vm.onFilterDone()
        advanceUntilIdle()

        assertNull(vm.state.value.pendingFilter)
        val q = api.characterQueries.last()
        // ⚠️ 发的是后端枚举值，不是 UI 文案
        assertEquals("other", q.gender)
        assertEquals("MostLiked", q.sorting)
        assertTrue("Done 必须重查", api.characterQueries.size > before)
    }

    /**
     * ⚠️ 筛选重查走 `isRefreshing` 而不是 `isLoading` —— **保留旧列表**。
     * 清空会让筛选时列表闪空一下（RN 专门为此拆了 refreshing）。
     */
    @Test
    fun `筛选重查保留旧结果且不切 tab`() = runTest {
        val api = RecordingApi()
        api.characterPage = page(listOf(character("a")), total = 1)
        val vm = viewModel(api)
        vm.submitQuery("cat")
        advanceUntilIdle()
        vm.onTabChange(SearchTab.CREATORS)

        val gate = CompletableDeferred<Unit>()
        api.characterGate = gate
        vm.onFilterDrawerOpen()
        vm.onFilterSortingSelect(SearchSorting.LATEST)
        vm.onFilterDone()
        advanceUntilIdle()

        assertTrue(vm.state.value.isRefreshing)
        assertEquals("旧结果要留着", 1, vm.state.value.characterResults.size)
        assertEquals("不该被弹回 Characters", SearchTab.CREATORS, vm.state.value.tab)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.isRefreshing)
    }

    @Test
    fun `未搜索时改筛选不发请求`() = runTest {
        val api = RecordingApi()
        val vm = viewModel(api)
        vm.onFilterDrawerOpen()
        vm.onFilterSortingSelect(SearchSorting.LATEST)
        vm.onFilterDone()
        advanceUntilIdle()

        assertTrue(api.characterQueries.isEmpty())
    }

    @Test
    fun `性别 All 时请求不带 gender 键`() = runTest {
        val api = RecordingApi()
        api.characterPage = page(listOf(character("a")), total = 1)
        val vm = viewModel(api)
        vm.submitQuery("cat")
        advanceUntilIdle()

        // 默认就是 All —— gender 应为 null（API 层据此整键不发）
        assertNull(api.characterQueries.first().gender)
    }

    @Test
    fun `不可选分级时请求恒发 All`() = runTest {
        val api = RecordingApi()
        api.characterPage = page(listOf(character("a")), total = 1)
        val vm = viewModel(api)
        // 未调 onContentRatingAvailability → canPick 为 false
        vm.onFilterDrawerOpen()
        vm.onFilterContentRatingSelect(SearchContentRating.NSFW)
        vm.onFilterDone()
        vm.submitQuery("cat")
        advanceUntilIdle()

        assertEquals("All", api.characterQueries.last().contentRating)
    }

    @Test
    fun `可选分级时请求发选中值`() = runTest {
        val api = RecordingApi()
        api.characterPage = page(listOf(character("a")), total = 1)
        val vm = viewModel(api)
        vm.onContentRatingAvailability(true)
        vm.onFilterDrawerOpen()
        vm.onFilterContentRatingSelect(SearchContentRating.SFW)
        vm.onFilterDone()
        vm.submitQuery("cat")
        advanceUntilIdle()

        assertEquals("SFW", api.characterQueries.last().contentRating)
    }

    // ── 标签栏 ──────────────────────────────────────

    @Test
    fun `点标签 toggle 并立即重查`() = runTest {
        val api = RecordingApi()
        api.characterPage = page(listOf(character("a")), total = 1)
        val vm = viewModel(api)
        vm.submitQuery("cat")
        advanceUntilIdle()
        val before = api.characterQueries.size

        vm.onTagToggle("t1")
        advanceUntilIdle()
        assertEquals(listOf("t1"), vm.state.value.filter.tagIds)
        assertEquals(listOf("t1"), api.characterQueries.last().tagIds)
        assertTrue(api.characterQueries.size > before)

        // 再点一次取消
        vm.onTagToggle("t1")
        advanceUntilIdle()
        assertTrue(vm.state.value.filter.tagIds.isEmpty())
    }

    @Test
    fun `标签选中顺序保留`() = runTest {
        val api = RecordingApi()
        api.characterPage = page(listOf(character("a")), total = 1)
        val vm = viewModel(api)
        vm.submitQuery("cat")
        advanceUntilIdle()

        vm.onTagToggle("b")
        advanceUntilIdle()
        vm.onTagToggle("a")
        advanceUntilIdle()
        // 选中顺序即显示顺序（SearchTagOrder 第一层优先级依赖它）
        assertEquals(listOf("b", "a"), vm.state.value.filter.tagIds)
    }

    @Test
    fun `标签目录拉取后进状态`() = runTest {
        val api = RecordingApi()
        val vm = viewModel(
            api,
            tags = listOf(
                HomeTag(id = "t1", label = "Romance"),
            ),
        )
        vm.onAppear()
        advanceUntilIdle()

        assertEquals(1, vm.state.value.tagCatalog.size)
        assertEquals(mapOf("t1" to "Romance"), vm.state.value.tagLabels)
    }

    @Test
    fun `目录外的聚合标签不进展示顺序`() = runTest {
        // 后端聚合可能给出已下线标签，渲染它是个没文案的空胶囊
        val api = RecordingApi()
        api.characterPage = CharacterSearchPage(
            total = 1,
            searchSessionId = "s",
            outcome = CharacterSearchOutcome.SAFE,
            hits = listOf(character("a")),
            tagAggIds = listOf("t1", "gone"),
        )
        val vm = viewModel(
            api,
            tags = listOf(
                HomeTag(id = "t1", label = "Romance"),
            ),
        )
        vm.onAppear()
        vm.submitQuery("cat")
        advanceUntilIdle()

        assertEquals(listOf("t1"), vm.state.value.orderedTagIds)
    }

    // ── fixture ────────────────────────────────

    private fun kotlinx.coroutines.test.TestScope.viewModel(
        api: RecordingApi,
        generations: Generations = Generations(),
        userId: String? = "u1",
        debounceMillis: Long = 0,
        nsfw: Boolean = false,
        tags: List<HomeTag>? = null,
    ): SearchViewModel = SearchViewModel(
        api = api,
        generations = generations,
        languageProvider = { "en" },
        nsfwProvider = { nsfw },
        userIdProvider = { userId },
        tagSource = tags?.let { list -> { list } },
        scope = this as CoroutineScope,
        debounceMillis = debounceMillis,
        logWarn = { _, _ -> },
    )

    private fun page(
        hits: List<HomeFeedItem.Character>,
        total: Int,
        sessionId: String = "sess",
        outcome: CharacterSearchOutcome = CharacterSearchOutcome.SAFE,
    ) = CharacterSearchPage(
        total = total,
        searchSessionId = sessionId,
        outcome = outcome,
        hits = hits,
        tagAggIds = emptyList(),
    )

    private fun creatorPage(
        hits: List<CreatorResult>,
        total: Int,
        sessionId: String = "csess",
    ) = CreatorSearchPage(total = total, searchSessionId = sessionId, hits = hits)

    private fun character(id: String) = HomeFeedItem.Character(
        characterId = id,
        nickname = id,
        introduction = "",
        imageUrl = "",
        animatedImageUrl = null,
        creatorId = "",
        creatorNickname = null,
        totalMessages = 0,
        voiceSupported = false,
        isTranslated = false,
        lang = null,
        characterType = null,
        contentType = null,
        nsfw = false,
        isChatted = false,
    )

    private fun creator(id: String) = CreatorResult(
        userId = id,
        nickname = id,
        avatar = "",
        avatarDecorationCode = null,
        bio = "",
        followeesCount = 0,
        totalInteractions = 0,
        createdCharactersCount = 0,
    )

    private class RecordingApi : SearchSource {
        var characterPage = CharacterSearchPage(
            0, "sess", CharacterSearchOutcome.SAFE, emptyList(), emptyList(),
        )
        var creatorPage = CreatorSearchPage(0, "csess", emptyList())
        var suggestions: List<String> = emptyList()

        /** 按查询词分别闸住/返回，用来稳定构造 A 比 B 晚回的时序。 */
        var characterGatesByQuery: Map<String, CompletableDeferred<Unit>> = emptyMap()
        var creatorGatesByQuery: Map<String, CompletableDeferred<Unit>> = emptyMap()
        var characterPagesByQuery: Map<String, CharacterSearchPage> = emptyMap()
        var creatorPagesByQuery: Map<String, CreatorSearchPage> = emptyMap()
        var characterPagesByNumber: Map<Int, CharacterSearchPage> = emptyMap()
        var creatorPagesByNumber: Map<Int, CreatorSearchPage> = emptyMap()

        /** 按查询词分别返回（验证「旧响应不覆盖新输入」必须用这个）。 */
        var suggestionsByQuery: Map<String, List<String>> = emptyMap()
        var popularTerms: List<String> = emptyList()
        var recentHistory: List<String> = emptyList()

        var failCharacter = false
        var failSuggest = false
        var failClear = false
        var characterGate: CompletableDeferred<Unit>? = null
        var creatorGate: CompletableDeferred<Unit>? = null
        var suggestGate: CompletableDeferred<Unit>? = null

        val characterQueries = mutableListOf<SearchCharacterQuery>()
        val creatorQueries = mutableListOf<Pair<String, Int>>()
        val creatorPages = mutableListOf<Int>()
        val suggestQueries = mutableListOf<String>()
        var popularCalls = 0
        var recentHistoryCalls = 0
        var clearHistoryCalls = 0

        override suspend fun searchCharacters(query: SearchCharacterQuery): CharacterSearchPage {
            characterQueries += query
            val keyedGate = characterGatesByQuery[query.searchTerm]
            if (keyedGate != null) {
                // 模拟已进入 HTTP 回调的响应：取消 debounce job 也不能假定它不回写。
                withContext(NonCancellable) { keyedGate.await() }
            } else {
                characterGate?.await()
            }
            if (failCharacter) throw ApiException.Business(1, "boom")
            return characterPagesByQuery[query.searchTerm]
                ?: characterPagesByNumber[query.page]
                ?: characterPage
        }

        override suspend fun searchCreators(searchTerm: String, page: Int): CreatorSearchPage {
            creatorQueries += searchTerm to page
            creatorPages += page
            val keyedGate = creatorGatesByQuery[searchTerm]
            if (keyedGate != null) {
                // 与角色请求相同，模拟取消后仍会到达业务回调的 HTTP 响应。
                withContext(NonCancellable) { keyedGate.await() }
            } else {
                creatorGate?.await()
            }
            return creatorPagesByQuery[searchTerm]
                ?: creatorPagesByNumber[page]
                ?: creatorPage
        }

        override suspend fun fetchSuggestions(searchTerm: String): List<String> {
            suggestQueries += searchTerm
            // 闸门快照在 await 前取：之后置 null 不影响已进入的请求
            val gate = suggestGate
            gate?.await()
            if (failSuggest) throw ApiException.Business(1, "boom")
            return suggestionsByQuery[searchTerm] ?: suggestions
        }

        override suspend fun fetchPopularTerms(): List<String> {
            popularCalls++
            return popularTerms
        }

        override suspend fun fetchRecentHistory(): List<String> {
            recentHistoryCalls++
            return recentHistory
        }

        override suspend fun clearRecentHistory() {
            clearHistoryCalls++
            if (failClear) throw ApiException.Business(1, "boom")
        }
    }
}
