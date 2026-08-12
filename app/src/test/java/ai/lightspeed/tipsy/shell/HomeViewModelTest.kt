package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.network.ApiException
import ai.lightspeed.tipsy.shell.pages.home.HomeFeedItem
import ai.lightspeed.tipsy.shell.pages.home.HomeFeedPage
import ai.lightspeed.tipsy.shell.pages.home.HomeFeedSource
import ai.lightspeed.tipsy.shell.pages.home.HomeFilters
import ai.lightspeed.tipsy.shell.pages.home.HomeGender
import ai.lightspeed.tipsy.shell.pages.home.HomeSeries
import ai.lightspeed.tipsy.shell.pages.home.HomeTag
import ai.lightspeed.tipsy.shell.pages.home.HomeViewModel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `HomeViewModel` 的编排语义（W2）。
 *
 * 这里测的三件事都是「错了不报错，只是行为不对」：
 * session 语义（方案 §8.1）、翻页去重续拉（§8.4 第 3 条）、失败时不清列表。
 */
class HomeViewModelTest {

    // ── session 语义（方案 §8.1，最容易写错的一处）──────────

    @Test
    fun `翻页不换 session`() = runTest {
        // 换了的话后端每页给一个新推荐池 → **重复内容刷屏**
        val api = RecordingApi()
        // 必须给有数据的页：空页会被判为"到底"，onLoadMore 正确地不再发请求
        api.pages = listOf(page(character("a")), page(character("b")))
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()
        vm.onLoadMore()
        advanceUntilIdle()

        assertEquals(2, api.calls.size)
        assertEquals("两页的 session 必须相同", api.calls[0].sessionId, api.calls[1].sessionId)
        assertEquals(0, api.calls[0].page)
        assertEquals(1, api.calls[1].page)
    }

    @Test
    fun `切性别换 session`() = runTest {
        // 不换的话池被锁在旧筛选上 → **切了性别列表不变**（home.tsx:535-539 的真实 bug）
        val api = RecordingApi()
        api.pages = listOf(page(character("a")))
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()
        val first = api.calls.last().sessionId

        vm.onGenderSelected(HomeGender.MALE)
        advanceUntilIdle()

        assertNotEquals("切性别必须换 session", first, api.calls.last().sessionId)
        assertEquals("female 之外的映射值", "male", api.calls.last().gender.apiValue)
    }

    @Test
    fun `下拉刷新换 session 且从第 0 页重来`() = runTest {
        val api = RecordingApi()
        api.pages = listOf(page(character("a")), page(character("b")), page(character("c")))
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()
        vm.onLoadMore()
        advanceUntilIdle()
        val beforeRefresh = api.calls.last().sessionId

        vm.onRefresh()
        advanceUntilIdle()

        assertNotEquals(beforeRefresh, api.calls.last().sessionId)
        assertEquals("刷新必须回到第 0 页", 0, api.calls.last().page)
    }

    @Test
    fun `语言变化换 session`() = runTest {
        // 方案 §8.4 第 2 条：账号语言 ≠ 设备语言时冷启动数秒后 settle，要强拉
        var language = "en"
        val api = RecordingApi()
        api.pages = listOf(page(character("a")), page(character("b")))
        val vm = viewModel(api, languageProvider = { language })
        vm.onFirstAppear()
        advanceUntilIdle()
        val before = api.calls.last().sessionId

        language = "zh-tw"
        vm.onLanguageSettled()
        advanceUntilIdle()

        assertNotEquals(before, api.calls.last().sessionId)
        assertEquals("zh-tw", api.calls.last().languageCode)
    }

    @Test
    fun `切系列各自独立 session`() = runTest {
        val api = RecordingApi()
        api.pages = listOf(page(character("a")), page(character("b")))
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()
        val forYouSession = api.calls.last().sessionId

        vm.onSeriesSelected(HomeSeries.WEEKLY_PICKS)
        advanceUntilIdle()

        assertNotEquals(forYouSession, api.calls.last().sessionId)
        assertEquals(HomeSeries.WEEKLY_PICKS, api.calls.last().series)
    }

    // ── 翻页去重（方案 §8.4 第 3 条）───────────────────────

    @Test
    fun `翻页去重按 stableKey`() = runTest {
        // For You 翻页实测每页 1~3 条重复。不去重会让同一角色出现两次，
        // 且 Compose 的 key 撞了会直接抛
        val api = RecordingApi()
        api.pages = listOf(
            page(character("a"), character("b")),
            // 第二页含一条重复
            page(character("b"), character("c")),
        )
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()
        vm.onLoadMore()
        advanceUntilIdle()

        val keys = vm.state.value.items.map { it.stableKey }
        assertEquals(listOf("a", "b", "c"), keys)
    }

    @Test
    fun `去重后空页主动续拉`() = runTest {
        // 全量去重后若无新 item，不主动续拉会让列表停在半屏且不再触发加载
        val api = RecordingApi()
        api.pages = listOf(
            page(character("a")),
            page(character("a")), // 全重复 → 应自动再拉一页
            page(character("b")),
        )
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()
        vm.onLoadMore()
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), vm.state.value.items.map { it.stableKey })
        assertEquals("应发出 3 次请求（第 2 页全重复触发续拉）", 3, api.calls.size)
    }

    @Test
    fun `续拉限次 —— 不形成无限循环`() = runTest {
        // 异常数据（后端一直返回同一页）不限次会打爆请求
        val api = RecordingApi()
        api.repeatingPage = page(character("a"))
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()

        // 首次那页 + 最多 MAX_EMPTY_DEDUPE_STREAK 次续拉
        assertTrue(
            "请求次数应受限，实际 ${api.calls.size}",
            api.calls.size <= HomeViewModel.MAX_EMPTY_DEDUPE_STREAK + 1,
        )
    }

    @Test
    fun `streak 累计 —— 每次下滑最多再发一个请求，不再自动续拉`() = runTest {
        // ⚠️ 这条钉住一个真实的设计边界，写这个测试时我先写错了一版断言。
        //
        // 要限的是**自动续拉链**（一次触发里连拉 N 页），不是"彻底不再请求"：
        // - streak 存在 cursor 里跨 onLoadMore 累计 → 达上限后不再自动连拉
        // - 但用户**显式下滑**仍应发一个请求 —— 否则后端恢复正常后
        //   列表永远卡住、再也加载不出新内容
        //
        // 每次手势 1 个请求受人手速度自然限制，不构成请求风暴；
        // 而"一次触发连拉 N 页"不限次才是真会打爆后端的那种。
        val api = RecordingApi()
        api.repeatingPage = page(character("a"))
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()

        // 首屏：第 0 页有新数据即停（1 次请求）
        assertEquals(1, api.calls.size)

        // 第一次下滑：全重复 → 自动续拉，直到 streak 到上限
        vm.onLoadMore()
        advanceUntilIdle()
        val afterFirstScroll = api.calls.size
        assertEquals(
            "首次下滑应连拉到上限即停",
            1 + HomeViewModel.MAX_EMPTY_DEDUPE_STREAK,
            afterFirstScroll,
        )

        // 后续每次下滑只再发 1 个（streak 已达上限，不再自动连拉）
        repeat(3) { i ->
            vm.onLoadMore()
            advanceUntilIdle()
            assertEquals(
                "第 ${i + 2} 次下滑只应再发 1 个请求",
                afterFirstScroll + i + 1,
                api.calls.size,
            )
        }
    }

    @Test
    fun `拿到新数据后 streak 重置 —— 自动续拉能力恢复`() = runTest {
        // streak 只在"去重后无新增"时累加，拿到新 item 必须归零 ——
        // 不归零会让一次偶发的重复页永久关掉自动续拉
        val api = RecordingApi()
        api.pages = listOf(
            page(character("a")),
            page(character("a")), // 重复 → streak=1，续拉
            page(character("b")), // 有新增 → streak 归零并停
            page(character("b")), // 重复 → streak=1（若未归零则应直接停）
            page(character("c")),
        )
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()
        vm.onLoadMore()
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), vm.state.value.items.map { it.stableKey })

        vm.onLoadMore()
        advanceUntilIdle()
        // 能继续自动续拉到拿出 c，说明 streak 已归零
        assertEquals(listOf("a", "b", "c"), vm.state.value.items.map { it.stableKey })
    }

    // ── 到底判定 ──────────────────────────────────────────

    @Test
    fun `过滤后为空但原始条数非零时不算到底`() = runTest {
        // For You 的一页可能全是暂不支持的类型。用 items.isEmpty() 判到底
        // 会让列表提前停在半屏
        val api = RecordingApi()
        api.pages = listOf(
            HomeFeedPage(items = emptyList(), rawItemCount = 21, hasMore = null),
            page(character("a")),
        )
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()

        assertTrue("不应标记到底", !vm.state.value.hasReachedEnd)
    }

    @Test
    fun `原始条数为零算到底`() = runTest {
        val api = RecordingApi()
        api.pages = listOf(HomeFeedPage(emptyList(), rawItemCount = 0, hasMore = null))
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()
        assertTrue(vm.state.value.hasReachedEnd)
    }

    @Test
    fun `World 用 has_more 判到底`() = runTest {
        val api = RecordingApi()
        api.pages = listOf(
            HomeFeedPage(
                items = listOf(world("w1")),
                rawItemCount = 1,
                hasMore = false,
            ),
        )
        val vm = viewModel(api)
        vm.onSeriesSelected(HomeSeries.WORLD)
        advanceUntilIdle()
        assertTrue(vm.state.value.hasReachedEnd)
    }

    @Test
    fun `到底后不再翻页`() = runTest {
        val api = RecordingApi()
        api.pages = listOf(HomeFeedPage(emptyList(), rawItemCount = 0, hasMore = null))
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()
        val callsAfterFirst = api.calls.size

        vm.onLoadMore()
        advanceUntilIdle()

        assertEquals(callsAfterFirst, api.calls.size)
    }

    // ── 失败处理 ──────────────────────────────────────────

    @Test
    fun `首屏失败给出兜底文案`() = runTest {
        val api = RecordingApi()
        api.error = ApiException.Transport(java.io.IOException("boom"))
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()

        // ⚠️ 兜底 key 必须是 26 个 locale 都有的那条。用 RN 的
        // `Something went wrong` 会让所有语言显示英文（进度文档 §2.20）
        assertEquals(HomeViewModel.FALLBACK_ERROR_KEY, vm.state.value.errorMessage)
        assertTrue(!vm.state.value.isInitialLoading)
    }

    @Test
    fun `业务错误优先用后端 msg`() = runTest {
        val api = RecordingApi()
        api.error = ApiException.Business(code = 2, serverMessage = "参数非法")
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()
        assertEquals("参数非法", vm.state.value.errorMessage)
    }

    @Test
    fun `业务错误但 msg 为空时回落兜底`() = runTest {
        // 置 null 等于什么都不显示（进度文档 §2.20 记的登录页真实 bug）
        val api = RecordingApi()
        api.error = ApiException.Business(code = 2, serverMessage = null)
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()
        assertEquals(HomeViewModel.FALLBACK_ERROR_KEY, vm.state.value.errorMessage)
    }

    @Test
    fun `已有数据时翻页失败不清列表也不弹错误`() = runTest {
        // 把用户正在看的内容抹掉，比"翻页没成功"严重得多
        val api = RecordingApi()
        api.pages = listOf(page(character("a")))
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()

        api.error = ApiException.Transport(java.io.IOException("boom"))
        vm.onLoadMore()
        advanceUntilIdle()

        assertEquals(listOf("a"), vm.state.value.items.map { it.stableKey })
        assertNull("已有数据时不应展示错误", vm.state.value.errorMessage)
    }

    // ── 切 Tab 保留状态 ───────────────────────────────────

    @Test
    fun `切回已加载过的系列直接回显且不重发请求`() = runTest {
        // 对齐 SWR 的 keepPreviousData —— 每次切都重拉会让切 Tab 变卡
        val api = RecordingApi()
        api.pages = listOf(page(character("a")), page(character("b")))
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()

        vm.onSeriesSelected(HomeSeries.WEEKLY_PICKS)
        advanceUntilIdle()
        val callsAfterSwitch = api.calls.size

        vm.onSeriesSelected(HomeSeries.FOR_YOU)
        advanceUntilIdle()

        assertEquals("切回不应重发请求", callsAfterSwitch, api.calls.size)
        assertEquals(listOf("a"), vm.state.value.items.map { it.stableKey })
    }

    // ── 标签筛选 ──────────────────────────────────────────

    @Test
    fun `勾选标签后请求带上 tag_ids 且换 session`() = runTest {
        val api = RecordingApi()
        api.tags = listOf(HomeTag("t1", "浪漫"), HomeTag("t2", "校园"))
        api.pages = listOf(page(character("a")), page(character("b")))
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()
        val before = api.calls.last().sessionId

        vm.onFilterDrawerOpen()
        advanceUntilIdle()
        vm.onTagsApplied(listOf("t1"))
        advanceUntilIdle()

        assertEquals(listOf("t1"), api.calls.last().tagIds)
        // 不换 session 的话新筛选复用旧推荐池 → 「筛了但结果没怎么变」
        assertTrue("勾选标签必须换 session", api.calls.last().sessionId != before)
    }

    @Test
    fun `Following 不带标签`() = runTest {
        // `useHomeCharacterLists.ts:89` 的 isFollowing ? [] : tags。
        // 带上会把关注列表筛掉大半，而该系列 UI 上没有筛选入口
        val api = RecordingApi()
        api.tags = listOf(HomeTag("t1", "浪漫"))
        api.pages = listOf(page(character("a")), page(character("b")))
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()
        vm.onFilterDrawerOpen()
        advanceUntilIdle()
        vm.onTagsApplied(listOf("t1"))
        advanceUntilIdle()

        vm.onSeriesSelected(HomeSeries.FOLLOWING)
        advanceUntilIdle()

        val followingCall = api.calls.last { it.series == HomeSeries.FOLLOWING }
        assertEquals(emptyList<String>(), followingCall.tagIds)
    }

    @Test
    fun `World 不带标签`() = runTest {
        val api = RecordingApi()
        api.tags = listOf(HomeTag("t1", "浪漫"))
        api.pages = listOf(page(character("a")), page(character("b")))
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()
        vm.onFilterDrawerOpen()
        advanceUntilIdle()
        vm.onTagsApplied(listOf("t1"))
        advanceUntilIdle()

        vm.onSeriesSelected(HomeSeries.WORLD)
        advanceUntilIdle()

        assertEquals(emptyList<String>(), api.calls.last { it.series == HomeSeries.WORLD }.tagIds)
    }

    @Test
    fun `改标签不作废 World 已缓存的列表`() = runTest {
        // filterKey 按系列算：World 不发标签，指纹里也不能含标签。
        // 含了会让「改标签」白白清掉 World 的游标，切回去要重新加载且结果相同
        val api = RecordingApi()
        api.tags = listOf(HomeTag("t1", "浪漫"))
        api.pages = listOf(page(character("a")), page(character("w")), page(character("b")))
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()
        vm.onSeriesSelected(HomeSeries.WORLD)
        advanceUntilIdle()
        val worldSession = api.calls.last { it.series == HomeSeries.WORLD }.sessionId

        vm.onSeriesSelected(HomeSeries.FOR_YOU)
        advanceUntilIdle()
        vm.onFilterDrawerOpen()
        advanceUntilIdle()
        vm.onTagsApplied(listOf("t1"))
        advanceUntilIdle()
        vm.onSeriesSelected(HomeSeries.WORLD)
        advanceUntilIdle()

        assertEquals(
            "World 的 session 不应因改标签而变",
            worldSession,
            api.calls.last { it.series == HomeSeries.WORLD }.sessionId,
        )
    }

    @Test
    fun `不在目录里的勾选 id 被丢弃`() = runTest {
        // 目录随 nsfw 变化；留着不存在的 id 会让请求带上后端不认识的标签，
        // 静默返回空列表（对齐 HomeFilterDrawer.tsx:80 的 visibleTagIds 过滤）
        val api = RecordingApi()
        api.tags = listOf(HomeTag("t1", "浪漫"))
        api.pages = listOf(page(character("a")), page(character("b")))
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()
        vm.onFilterDrawerOpen()
        advanceUntilIdle()

        vm.onTagsApplied(listOf("t1", "已下线的标签"))
        advanceUntilIdle()

        assertEquals(listOf("t1"), vm.state.value.selectedTagIds)
        assertEquals(listOf("t1"), api.calls.last().tagIds)
    }

    @Test
    fun `重复应用同一勾选不重发请求`() = runTest {
        val api = RecordingApi()
        api.tags = listOf(HomeTag("t1", "浪漫"))
        api.pages = listOf(page(character("a")), page(character("b")))
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()
        vm.onFilterDrawerOpen()
        advanceUntilIdle()
        vm.onTagsApplied(listOf("t1"))
        advanceUntilIdle()
        val calls = api.calls.size

        vm.onFilterDrawerOpen()
        advanceUntilIdle()
        vm.onTagsApplied(listOf("t1"))
        advanceUntilIdle()

        assertEquals("同一勾选不应重拉", calls, api.calls.size)
        assertTrue("但抽屉要关上", !vm.state.value.isFilterDrawerOpen)
    }

    @Test
    fun `标签目录只拉一次`() = runTest {
        val api = RecordingApi()
        api.tags = listOf(HomeTag("t1", "浪漫"))
        api.pages = listOf(page(character("a")))
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()

        vm.onFilterDrawerOpen()
        advanceUntilIdle()
        vm.onFilterDrawerDismiss()
        vm.onFilterDrawerOpen()
        advanceUntilIdle()

        assertEquals(1, api.tagFetchCount)
    }

    @Test
    fun `标签目录拉取失败不阻塞抽屉打开`() = runTest {
        // RN 的 hydrateTags 也是 console.warn 后咽掉（config_persist.ts:321）
        val api = RecordingApi()
        api.tagError = ApiException.Transport(java.io.IOException("boom"))
        api.pages = listOf(page(character("a")))
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()

        vm.onFilterDrawerOpen()
        advanceUntilIdle()

        assertTrue("抽屉仍应打开", vm.state.value.isFilterDrawerOpen)
        assertTrue(vm.state.value.tagCatalog.isEmpty())
        assertNull("不应把标签失败当成列表错误", vm.state.value.errorMessage)
    }

    @Test
    fun `埋点的 selectedTags 是用户勾选原样`() = runTest {
        // home.tsx:1398 直接传 selectedTags.tags —— 即使 Following 请求不带标签，
        // 埋点里仍记着用户当时勾了什么。按系列清空会让归因失真
        val api = RecordingApi()
        api.tags = listOf(HomeTag("t1", "浪漫"))
        api.pages = listOf(page(character("a")), page(character("b")))
        val vm = viewModel(api)
        vm.onFirstAppear()
        advanceUntilIdle()
        vm.onFilterDrawerOpen()
        advanceUntilIdle()
        vm.onTagsApplied(listOf("t1"))
        advanceUntilIdle()

        assertEquals(listOf("t1"), vm.state.value.selectedTagIds)
    }

    // ── fixture ───────────────────────────────────────────

    private fun TestScope.viewModel(
        api: RecordingApi,
        languageProvider: () -> String = { "en" },
    ) = HomeViewModel(
        api = api,
        filters = FakeFilters(),
        languageProvider = languageProvider,
        scope = this,
        // 单测里不碰 android.util.Log（那是抛 "not mocked" 的桩）
        logWarn = { _, _ -> },
    )

    private fun page(vararg items: HomeFeedItem) =
        HomeFeedPage(items = items.toList(), rawItemCount = items.size, hasMore = null)

    private fun character(id: String) = HomeFeedItem.Character(
        characterId = id,
        nickname = "n$id",
        introduction = "",
        imageUrl = "",
        animatedImageUrl = null,
        creatorId = "c",
        creatorNickname = null,
        totalMessages = 0,
        voiceSupported = false,
        isTranslated = false,
        lang = null,
        characterType = null,
        contentType = null,
        nsfw = false,
        isChatted = false,
        recommendation = null,
    )

    private fun world(id: String) = HomeFeedItem.World(
        projectId = id,
        name = "w",
        introduction = "",
        coverUrl = "",
        creatorId = "",
        creatorNickname = null,
        interactionCount = 0,
        versionChange = false,
        nsfw = false,
    )

    /** 记录每次请求的参数，便于断言 session 与分页语义。 */
    private class RecordingApi : HomeFeedSource {
        data class Call(
            val series: HomeSeries,
            val page: Int,
            val gender: HomeGender,
            val languageCode: String,
            val sessionId: String,
            val tagIds: List<String>,
        )

        val calls = mutableListOf<Call>()

        /** 按调用序返回；用尽后返回 [repeatingPage] 或空到底页。 */
        var pages: List<HomeFeedPage> = emptyList()

        /** 每次都返回同一页（测续拉限次）。 */
        var repeatingPage: HomeFeedPage? = null

        /** 非空则抛（测失败路径）。 */
        var error: Throwable? = null

        override suspend fun fetchPage(
            series: HomeSeries,
            page: Int,
            gender: HomeGender,
            nsfw: Boolean,
            languageCode: String,
            tagIds: List<String>,
            contentType: Int?,
            sessionId: String,
        ): HomeFeedPage {
            calls.add(Call(series, page, gender, languageCode, sessionId, tagIds))
            error?.let { throw it }
            repeatingPage?.let { return it }
            // 每个系列各自从头取：切系列时页码从 0 重新开始
            val indexForSeries = calls.count { it.series == series } - 1
            return pages.getOrNull(indexForSeries)
                ?: HomeFeedPage(emptyList(), rawItemCount = 0, hasMore = null)
        }

        /** 标签目录：默认空表。测标签筛选的用例自己塞值。 */
        var tags: List<HomeTag> = emptyList()
        var tagFetchCount: Int = 0
        var tagError: Throwable? = null

        override suspend fun fetchTags(nsfw: Boolean): List<HomeTag> {
            tagFetchCount++
            tagError?.let { throw it }
            return tags
        }
    }

    /** 内存版筛选存储。 */
    private class FakeFilters(
        private var gender: HomeGender = HomeGender.ALL,
        private var nsfw: Boolean = false,
    ) : HomeFilters {
        override fun readGender(): HomeGender = gender
        override fun readNsfw(): Boolean = nsfw
        override fun writeGender(gender: HomeGender): Boolean {
            this.gender = gender
            return true
        }
    }
}
