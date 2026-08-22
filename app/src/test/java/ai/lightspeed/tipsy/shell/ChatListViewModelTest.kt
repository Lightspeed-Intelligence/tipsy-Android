package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.auth.Generations
import ai.lightspeed.tipsy.shell.network.ApiException
import ai.lightspeed.tipsy.shell.pages.chatlist.ChatDraftStore
import ai.lightspeed.tipsy.shell.pages.chatlist.ChatListSource
import ai.lightspeed.tipsy.shell.pages.chatlist.ChatListViewModel
import ai.lightspeed.tipsy.shell.pages.chatlist.ChatPageType
import ai.lightspeed.tipsy.shell.pages.chatlist.ChatPageTypeStore
import ai.lightspeed.tipsy.shell.pages.chatlist.ChatThread
import ai.lightspeed.tipsy.shell.pages.chatlist.ChatThreadPage
import ai.lightspeed.tipsy.shell.pages.chatlist.RelationshipStat
import ai.lightspeed.tipsy.shell.user.CurrentUser
import ai.lightspeed.tipsy.shell.user.CurrentUserStore
import ai.lightspeed.tipsy.shell.user.UserInfoSource
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
 * `ChatListViewModel` 的编排语义（W3 P1）。
 *
 * 重点是 §4.4 双 generation 的**mutation 轨第一个实战用例**：
 * 「删除后在飞的旧列表响应不得复活已删行」—— iOS 列表整月修复里最阴的一类，
 * 本地必现不了（需要精确的响应时序），只能靠测试钉住。
 */
class ChatListViewModelTest {

    // ── 分页链 ──────────────────────────────────────────────

    @Test
    fun `首屏加载后翻页从第 1 页继续`() = runTest {
        val api = RecordingApi()
        api.pages = listOf(
            pageOf(thread("a"), thread("b")),
            pageOf(thread("c")),
        )
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        vm.onLoadMore()
        advanceUntilIdle()

        assertEquals(listOf(0, 1), api.listCalls)
        assertEquals(listOf("a", "b", "c"), vm.state.value.threads.map { it.itemId })
    }

    @Test
    fun `翻页去重后空页主动续拉且限 3 次`() = runTest {
        val api = RecordingApi()
        // 第 0 页 a；后续页全是重复的 a（服务端异常数据），has_more 恒 true
        api.pages = listOf(
            pageOf(thread("a")),
            pageOf(thread("a")),
            pageOf(thread("a")),
            pageOf(thread("a")),
            pageOf(thread("a")),
            pageOf(thread("a")),
        )
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        vm.onLoadMore()
        advanceUntilIdle()

        // 首屏 1 次 + 翻页链最多 3 次空页续拉后停（不形成请求循环）
        assertTrue("实际请求数 ${api.listCalls.size}", api.listCalls.size <= 5)
        // streak 达上限后 onLoadMore 直接被门禁挡住
        val before = api.listCalls.size
        vm.onLoadMore()
        advanceUntilIdle()
        assertEquals(before, api.listCalls.size)
    }

    @Test
    fun `首屏失败显示错误而有数据时失败不清列表`() = runTest {
        val api = RecordingApi()
        api.pages = listOf(pageOf(thread("a")))
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.threads.size)

        // 下拉刷新失败：列表保留，无错误位（§8.4）
        api.failNext = true
        vm.onRefresh()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.threads.size)
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isRefreshing)
    }

    // ── mutation 闸门（本刀的核心新增）───────────────────────

    @Test
    fun `删除期间在飞的旧响应不得复活已删行`() = runTest {
        val api = RecordingApi()
        api.pages = listOf(pageOf(thread("a"), thread("b")))
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()

        // 挂起一个在飞刷新（gate 卡住响应）
        api.gate = CompletableDeferred()
        api.pages = listOf(pageOf(thread("a"), thread("b"))) // 旧世界的响应：a 还在
        vm.onRefresh()
        // 响应未回时用户删除 a（乐观移除 + bumpMutation）
        vm.requestDelete(vm.state.value.threads.first { it.itemId == "a" })
        vm.confirmDelete()
        advanceUntilIdle()
        // 放行旧响应
        api.gate?.complete(Unit)
        api.gate = null
        advanceUntilIdle()

        // 旧响应必须被闸门丢弃：a 不得复活。
        // （confirmDelete 成功后自己会发对账重拉，那次拿到的是 delete 后的
        // pages —— RecordingApi 的 pages 在 delete 成功后换成不含 a 的）
        assertFalse(
            "已删行被在飞旧响应复活了",
            vm.state.value.threads.any { it.itemId == "a" },
        )
    }

    @Test
    fun `删除成功后写 convEpoch 且失败时不写`() = runTest {
        val api = RecordingApi()
        api.pages = listOf(pageOf(thread("a")))
        val epochBumps = mutableListOf<String>()
        val vm = viewModel(api, onEpochBump = { epochBumps += it })
        vm.onAppear()
        advanceUntilIdle()

        vm.requestDelete(vm.state.value.threads[0])
        vm.confirmDelete()
        advanceUntilIdle()
        assertEquals(listOf("a"), epochBumps)

        // 失败路径不写
        api.pages = listOf(pageOf(thread("b")))
        vm.onRefresh()
        advanceUntilIdle()
        api.failNextDelete = true
        vm.requestDelete(vm.state.value.threads[0])
        vm.confirmDelete()
        advanceUntilIdle()
        assertEquals(listOf("a"), epochBumps)
    }

    @Test
    fun `game 删除不写 convEpoch`() = runTest {
        val api = RecordingApi()
        api.pages = listOf(pageOf(gameThread("g1")))
        val epochBumps = mutableListOf<String>()
        val vm = viewModel(api, onEpochBump = { epochBumps += it })
        vm.onAppear()
        advanceUntilIdle()
        vm.requestDelete(vm.state.value.threads[0])
        vm.confirmDelete()
        advanceUntilIdle()
        assertTrue(epochBumps.isEmpty())
    }

    @Test
    fun `删除失败重拉恢复且 Toast 优先服务端 msg`() = runTest {
        val api = RecordingApi()
        api.pages = listOf(pageOf(thread("a")))
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()

        api.failNextDelete = true
        vm.requestDelete(vm.state.value.threads[0])
        vm.confirmDelete()
        advanceUntilIdle()

        // 失败后重拉恢复（RN 失败路径的 mutate()）
        assertEquals(1, vm.state.value.threads.size)
        // RN `index.tsx:196-207`：优先 err.message，只有取不到才 t('Delete failed')
        assertEquals("delete boom", vm.state.value.toastKey)
    }

    // ── pin 重排（ChatListItem.tsx:175-226 逐行对齐）────────

    @Test
    fun `pin 后插进 pinned 组按时间序`() {
        // 初始：p2(pinned,t=200) p1(pinned,t=100) c(t=50) target(t=150)
        val threads = listOf(
            thread("p2", time = 200, pinned = true),
            thread("p1", time = 100, pinned = true),
            thread("c", time = 50),
            thread("target", time = 150),
        )
        val result = ChatListViewModel.reorderAfterPinToggle(threads, threads[3])
        // target(150) 应插在 p2(200) 之后、p1(100) 之前
        assertEquals(listOf("p2", "target", "p1", "c"), result.map { it.itemId })
        assertTrue(result[1].isPinned)
    }

    @Test
    fun `unpin 后按时间插回非 pinned 区`() {
        val threads = listOf(
            thread("target", time = 150, pinned = true),
            thread("a", time = 200),
            thread("b", time = 100),
        )
        val result = ChatListViewModel.reorderAfterPinToggle(threads, threads[0])
        // target(150) 应插在 a(200) 之后、b(100) 之前
        assertEquals(listOf("a", "target", "b"), result.map { it.itemId })
        assertFalse(result[1].isPinned)
    }

    @Test
    fun `pin 成功弹成功 Toast 失败弹失败且不动列表`() = runTest {
        val api = RecordingApi()
        api.pages = listOf(pageOf(thread("a"), thread("b")))
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()

        vm.togglePin(vm.state.value.threads[1])
        advanceUntilIdle()
        assertEquals(ChatListViewModel.KEY_PIN_OK, vm.state.value.toastKey)
        assertTrue(vm.state.value.threads[0].itemId == "b" && vm.state.value.threads[0].isPinned)

        vm.consumeToast()
        api.failNextPin = true
        val orderBefore = vm.state.value.threads.map { it.itemId }
        vm.togglePin(vm.state.value.threads[1])
        advanceUntilIdle()
        assertEquals(ChatListViewModel.KEY_PIN_FAILED, vm.state.value.toastKey)
        assertEquals(orderBefore, vm.state.value.threads.map { it.itemId })
    }

    // ── 登录态 ──────────────────────────────────────────────

    @Test
    fun `换号后在飞响应被 auth 闸门丢弃且登出清空`() = runTest {
        val api = RecordingApi()
        api.pages = listOf(pageOf(thread("a")))
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        assertEquals(listOf("a"), vm.state.value.threads.map { it.itemId })

        // 让一次刷新真正起飞并挂起在 gate（runCurrent 推进到第一个挂起点）
        api.gate = CompletableDeferred()
        api.pages = listOf(pageOf(thread("b"))) // 旧世界的响应内容
        vm.onRefresh()
        testScheduler.runCurrent()
        assertEquals(2, api.listCalls.size)

        // 响应在飞期间换号（ShellTokenStore 登出/换号时 bumpAuth）
        generations.bumpAuth()
        api.gate?.complete(Unit)
        api.gate = null
        advanceUntilIdle()

        // 旧响应必须被 auth 闸门丢弃：b 不得上屏
        assertEquals(listOf("a"), vm.state.value.threads.map { it.itemId })

        // 登出只清不拉（全 REQUIRED）
        val callsBefore = api.listCalls.size
        vm.onAuthChanged(loggedIn = false)
        advanceUntilIdle()
        assertTrue(vm.state.value.threads.isEmpty())
        assertEquals(callsBefore, api.listCalls.size)
    }

    @Test
    fun `未登录时首屏不发请求`() = runTest {
        val api = RecordingApi()
        api.pages = listOf(pageOf(thread("a")))
        val vm = viewModel(api, userId = null)
        vm.onAppear()
        advanceUntilIdle()
        assertTrue(api.listCalls.isEmpty())
    }

    // ── 徽章旁路 ────────────────────────────────────────────

    @Test
    fun `首页落地后批拉徽章且只含非小手机 character`() = runTest {
        val api = RecordingApi()
        api.pages = listOf(
            pageOf(
                thread("c1"),
                thread("c2", miniPhone = true),
                gameThread("g1"),
                thread("s1", type = "story"),
            ),
        )
        api.stats = listOf(RelationshipStat("c1", 3, 2, true))
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()

        assertEquals(listOf(listOf("c1")), api.statsCalls)
        assertEquals(2, vm.state.value.relationshipStats["c1"]!!.level)
    }

    // ── 视图偏好 ────────────────────────────────────────────

    @Test
    fun `切视图写偏好且重复选择不写`() = runTest {
        val api = RecordingApi()
        api.pages = listOf(pageOf(thread("a")))
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()

        vm.onPageTypeSelected(ChatPageType.MAP)
        assertEquals(ChatPageType.MAP, vm.state.value.pageType)
        assertEquals(listOf(ChatPageType.MAP), pageTypeWrites)

        vm.onPageTypeSelected(ChatPageType.MAP)
        assertEquals(1, pageTypeWrites.size)
    }

    // ── 桥信号：notifyChattedListChanged ─────────────────────

    /** 建群信号 = 即时静默重拉第 0 页（iOS silentRefreshFirstPage 同义）。 */
    @Test
    fun `chattedList 信号后静默重拉且列表收敛到新数据`() = runTest {
        val api = RecordingApi()
        api.pages = listOf(pageOf(thread("a")))
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        assertEquals(listOf("a"), vm.state.value.threads.map { it.itemId })

        // 服务端多了新建的群会话 g
        api.pages = listOf(pageOf(thread("g"), thread("a")))
        vm.onChattedListChangedSignal()
        // 静默：不转圈（isRefreshing 是下拉刷新的 UI 位）
        assertTrue(!vm.state.value.isRefreshing)
        advanceUntilIdle()

        assertEquals(listOf(0, 0), api.listCalls)
        assertEquals(listOf("g", "a"), vm.state.value.threads.map { it.itemId })
    }

    /** 未拉过首屏 / 未登录时信号 no-op —— 首次进入本来就拉全新数据。 */
    @Test
    fun `chattedList 信号在首屏前与未登录时不发请求`() = runTest {
        val api = RecordingApi()
        api.pages = listOf(pageOf(thread("a")))
        val notLoaded = viewModel(api)
        notLoaded.onChattedListChangedSignal()
        advanceUntilIdle()
        assertTrue(api.listCalls.isEmpty())

        val guest = viewModel(api, userId = null)
        guest.onChattedListChangedSignal()
        advanceUntilIdle()
        assertTrue(api.listCalls.isEmpty())
    }

    // ── fixtures ────────────────────────────────────────────

    private lateinit var generations: Generations
    private val pageTypeWrites = mutableListOf<ChatPageType>()

    private fun TestScope.viewModel(
        api: RecordingApi,
        userId: String? = "u1",
        onEpochBump: (String) -> Unit = {},
    ): ChatListViewModel {
        generations = Generations()
        return ChatListViewModel(
            api = api,
            drafts = FakeDraftStore(),
            pageTypeStore = FakePageTypeStore(pageTypeWrites),
            cache = null, // 种子缓存走 MMKV，单测不接（有独立的信封测试）
            convEpoch = FakeConvEpoch(onEpochBump),
            generations = generations,
            languageProvider = { "en" },
            userIdProvider = { userId },
            userStore = CurrentUserStore(FakeUserSource(), logWarn = { _, _ -> }),
            scope = this,
            logWarn = { _, _ -> },
        )
    }

    private class FakeDraftStore : ai.lightspeed.tipsy.shell.pages.chatlist.ChatDraftStoreLike {
        override fun readAll() = emptyMap<String, ai.lightspeed.tipsy.shell.pages.chatlist.ChatDraft>()
    }

    private class FakePageTypeStore(
        private val writes: MutableList<ChatPageType>,
    ) : ai.lightspeed.tipsy.shell.pages.chatlist.ChatPageTypeStoreLike {
        override fun read() = ChatPageType.GRID
        override fun write(type: ChatPageType): Boolean {
            writes += type
            return true
        }
    }

    private class FakeConvEpoch(
        private val onBump: (String) -> Unit,
    ) : ai.lightspeed.tipsy.shell.pages.chatlist.ConvEpochLike {
        override fun bump(characterId: String) = onBump(characterId)
    }

    private class FakeUserSource : UserInfoSource {
        override suspend fun fetchCurrentUser(): CurrentUser =
            CurrentUser("u1", "nick", null, null, relationshipSwitch = true)
    }

    private class RecordingApi : ChatListSource {
        var pages: List<ChatThreadPage> = emptyList()
        var stats: List<RelationshipStat> = emptyList()
        var failNext = false
        var failNextDelete = false
        var failNextPin = false
        var gate: CompletableDeferred<Unit>? = null

        val listCalls = mutableListOf<Int>()
        val statsCalls = mutableListOf<List<String>>()
        val deleted = mutableListOf<String>()

        override suspend fun fetchPage(page: Int, languageCode: String): ChatThreadPage {
            listCalls += page
            gate?.await()
            if (failNext) {
                failNext = false
                throw ApiException.Business(1, "boom")
            }
            return pages.getOrNull(page) ?: ChatThreadPage(emptyList(), 0, hasMore = false)
        }

        override suspend fun fetchRelationshipStats(
            characterIds: List<String>,
        ): List<RelationshipStat> {
            statsCalls += characterIds
            return stats
        }

        override suspend fun pin(thread: ChatThread) {
            if (failNextPin) {
                failNextPin = false
                throw ApiException.Business(1, "pin boom")
            }
        }

        override suspend fun unpin(thread: ChatThread) {
            if (failNextPin) {
                failNextPin = false
                throw ApiException.Business(1, "unpin boom")
            }
        }

        override suspend fun delete(thread: ChatThread) {
            if (failNextDelete) {
                failNextDelete = false
                throw ApiException.Business(1, "delete boom")
            }
            deleted += thread.itemId
            // 服务端删除后，后续列表不再含该行
            pages = pages.map { p ->
                ChatThreadPage(
                    items = p.items.filterNot { it.matches(thread) },
                    total = (p.total - 1).coerceAtLeast(0),
                    hasMore = p.hasMore,
                )
            }
        }

        override suspend fun markPushMessageViewed(characterId: String) = Unit

        override suspend fun fetchUnreadStatus(): Boolean = false
    }

    private fun pageOf(vararg items: ChatThread) = ChatThreadPage(
        items = items.toList(),
        total = items.size.toLong(),
        hasMore = true,
    )

    private fun thread(
        id: String,
        type: String = "character",
        time: Long = 1L,
        pinned: Boolean = false,
        miniPhone: Boolean = false,
    ) = ChatThread(
        itemType = type,
        itemId = id,
        itemName = "n_$id",
        gameId = null,
        faceUrl = "",
        imageUrl = "",
        introduction = "",
        greeting = null,
        lastMessageContent = null,
        latestTimeSeconds = time,
        isPinned = pinned,
        isPushMessage = false,
        isPushMessageViewed = false,
        currentStreakDays = 0,
        chatMode = if (miniPhone) "mini_phone" else null,
        conversationId = if (miniPhone) "conv_$id" else null,
        parentConversationId = null,
        characterType = null,
        contentType = null,
        creatorId = null,
        versionChange = false,
    )

    private fun gameThread(gameId: String) = ChatThread(
        itemType = "game",
        itemId = "",
        itemName = "game",
        gameId = gameId,
        faceUrl = "",
        imageUrl = "",
        introduction = "",
        greeting = null,
        lastMessageContent = null,
        latestTimeSeconds = 1L,
        isPinned = false,
        isPushMessage = false,
        isPushMessageViewed = false,
        currentStreakDays = 0,
        chatMode = null,
        conversationId = null,
        parentConversationId = null,
        characterType = null,
        contentType = null,
        creatorId = null,
        versionChange = false,
    )
}
