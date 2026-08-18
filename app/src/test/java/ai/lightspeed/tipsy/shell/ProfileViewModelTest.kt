package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.profile.ProfileCreatedItem
import ai.lightspeed.tipsy.shell.pages.profile.ProfileCreatedPage
import ai.lightspeed.tipsy.shell.pages.profile.ProfileFavoriteItem
import ai.lightspeed.tipsy.shell.pages.profile.ProfileFavoritePage
import ai.lightspeed.tipsy.shell.pages.profile.ProfileMemoryItem
import ai.lightspeed.tipsy.shell.pages.profile.ProfileMemoryPage
import ai.lightspeed.tipsy.shell.pages.profile.ProfileRoleCardItem
import ai.lightspeed.tipsy.shell.pages.profile.ProfileRoleCardPage
import ai.lightspeed.tipsy.shell.pages.profile.ProfileSource
import ai.lightspeed.tipsy.shell.pages.profile.ProfileStats
import ai.lightspeed.tipsy.shell.pages.profile.ProfileTab
import ai.lightspeed.tipsy.shell.pages.profile.ProfileTabPaging
import ai.lightspeed.tipsy.shell.pages.profile.ProfileViewModel
import ai.lightspeed.tipsy.shell.pages.profile.ProfileWallet
import ai.lightspeed.tipsy.shell.pages.profile.ProfileWalletSource
import ai.lightspeed.tipsy.shell.user.CurrentUser
import ai.lightspeed.tipsy.shell.user.CurrentUserStore
import ai.lightspeed.tipsy.shell.user.UserInfoSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ProfileViewModel` 的编排语义（W3 第一刀）。
 *
 * 测的是「错了不报错」的四件事：到底判定（total 为 0 的反直觉分支）、
 * 翻页去重 + 空页续拉限次、失败不清列表、loading 不能照抄 RN 的死请求语义。
 */
class ProfileViewModelTest {

    // ── 到底判定 ────────────────────────────────────

    @Test
    fun `total 为 0 时算已到底`() = runTest {
        // RN 的 `if (!total) return true`。反过来写会让空列表无限翻页
        val api = FakeProfileApi(pages = listOf(page(items = emptyList(), total = 0)))
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()

        assertTrue("total=0 必须判为到底", vm.state.value.hasReachedEnd)
        assertEquals(1, api.createdCalls.size)
    }

    @Test
    fun `累计数达到 total 时到底`() = runTest {
        val api = FakeProfileApi(pages = listOf(page(items = listOf(item("a"), item("b")), total = 2)))
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()

        assertTrue(vm.state.value.hasReachedEnd)
    }

    @Test
    fun `累计数未达 total 时不到底`() = runTest {
        val api = FakeProfileApi(pages = listOf(page(items = listOf(item("a")), total = 5)))
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()

        assertFalse(vm.state.value.hasReachedEnd)
    }

    @Test
    fun `到底后不再翻页`() = runTest {
        val api = FakeProfileApi(pages = listOf(page(items = listOf(item("a")), total = 1)))
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        vm.onLoadMore()
        advanceUntilIdle()

        assertEquals("到底后不该再发请求", 1, api.createdCalls.size)
    }

    // ── 翻页与去重 ──────────────────────────────────

    @Test
    fun `翻页页码递增且第 0 页开始`() = runTest {
        val api = FakeProfileApi(
            pages = listOf(
                page(items = listOf(item("a")), total = 10),
                page(items = listOf(item("b")), total = 10),
            ),
        )
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        vm.onLoadMore()
        advanceUntilIdle()

        assertEquals(listOf(0, 1), api.createdCalls.map { it.page })
    }

    @Test
    fun `翻页重复条目被去重`() = runTest {
        val api = FakeProfileApi(
            pages = listOf(
                page(items = listOf(item("a"), item("b")), total = 10),
                // 第二页把 a 又返回一次
                page(items = listOf(item("a"), item("c")), total = 10),
            ),
        )
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        vm.onLoadMore()
        advanceUntilIdle()

        assertEquals(listOf("a", "b", "c"), vm.state.value.createdItems.map { it.itemId })
    }

    @Test
    fun `翻页不替换已有条目而是追加`() = runTest {
        // 方案 §8.4 禁止全量替换：替换会让可见卡片重配
        val api = FakeProfileApi(
            pages = listOf(
                page(items = listOf(item("a")), total = 10),
                page(items = listOf(item("b")), total = 10),
            ),
        )
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        vm.onLoadMore()
        advanceUntilIdle()

        assertEquals(2, vm.state.value.items.size)
        assertEquals("a", vm.state.value.createdItems.first().itemId)
    }

    @Test
    fun `空页续拉有限次不会无限循环`() = runTest {
        // 后端一直返回同一页时，不限次会打爆请求
        val repeated = page(items = listOf(item("a")), total = 100)
        val api = FakeProfileApi(pages = List(20) { repeated })
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        vm.onLoadMore()
        advanceUntilIdle()

        val calls = api.createdCalls.size
        assertTrue("续拉必须被限次，实际发了 $calls 次", calls <= 1 + ProfileTabPaging.MAX_EMPTY_DEDUPE_STREAK + 1)
    }

    // ── 失败处理 ────────────────────────────────────

    @Test
    fun `首屏失败显示错误且不卡在 loading`() = runTest {
        val api = FakeProfileApi(pages = emptyList(), failCreated = true)
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()

        assertNotNull("首屏空列表失败要给错误", vm.state.value.errorMessage)
        assertFalse(vm.state.value.isInitialLoading)
    }

    @Test
    fun `翻页失败不清已有列表也不显错误`() = runTest {
        // 方案 §8.4：把用户正在看的内容抹掉比翻页失败更糟
        val api = FakeProfileApi(pages = listOf(page(items = listOf(item("a")), total = 10)))
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        api.failCreated = true
        vm.onLoadMore()
        advanceUntilIdle()

        assertEquals("列表必须保留", 1, vm.state.value.items.size)
        assertNull("已有数据时翻页失败不该弹错误", vm.state.value.errorMessage)
        assertFalse(vm.state.value.isLoadingMore)
    }

    @Test
    fun `统计拉取失败不影响列表`() = runTest {
        val api = FakeProfileApi(
            pages = listOf(page(items = listOf(item("a")), total = 1)),
            failStats = true,
        )
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()

        assertEquals(1, vm.state.value.items.size)
        assertEquals("统计失败时走 EMPTY", ProfileStats.EMPTY, vm.state.value.stats)
    }

    @Test
    fun `用户信息拉取失败时不发统计请求`() = runTest {
        // 没有 userId 就发 stats 是无意义请求
        val api = FakeProfileApi(pages = listOf(page(items = emptyList(), total = 0)))
        val vm = viewModel(api, failUserInfo = true)
        vm.onAppear()
        advanceUntilIdle()

        assertEquals(0, api.statsCalls.size)
        assertNull(vm.state.value.user)
    }

    // ── loading 语义 ────────────────────────────────

    @Test
    fun `首屏成功后 loading 结束`() = runTest {
        // RN 的整页 loading 接的是不上屏的死请求 /character/list/self，
        // 壳接 /user/created/list —— 照抄会得到永不消失的骨架屏
        val api = FakeProfileApi(pages = listOf(page(items = listOf(item("a")), total = 1)))
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()

        assertFalse(vm.state.value.isInitialLoading)
        assertEquals(1, vm.state.value.items.size)
    }

    @Test
    fun `已有数据时再次 onAppear 不重复拉首屏`() = runTest {
        val api = FakeProfileApi(pages = listOf(page(items = listOf(item("a")), total = 10)))
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        vm.onAppear()
        advanceUntilIdle()

        assertEquals("列表已有数据就不该再拉第 0 页", 1, api.createdCalls.size)
    }

    @Test
    fun `onAppear 每次都刷用户信息与统计`() = runTest {
        // RN 侧 FollowInfo 是 isFocused 时 mutate：改完头像回到页面要立刻更新
        val api = FakeProfileApi(pages = listOf(page(items = listOf(item("a")), total = 10)))
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        vm.onAppear()
        advanceUntilIdle()

        assertEquals(2, api.statsCalls.size)
    }

    @Test
    fun `资料修改通知只刷新用户统计不重拉内容列表`() = runTest {
        val api = FakeProfileApi(pages = listOf(page(items = listOf(item("a")), total = 10)))
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()

        vm.onProfileChanged()
        advanceUntilIdle()

        assertEquals("用户资料链应再跑一次", 2, api.statsCalls.size)
        assertEquals("创作列表不得因资料修改重拉", listOf(0), api.createdCalls.map { it.page })
        assertEquals("现有内容必须原位保留", "a", vm.state.value.createdItems.single().itemId)
    }

    @Test
    fun `已有旧用户缓存时 user info 失败仍不能回调 ack 且随后可重试`() = runTest {
        val api = FakeProfileApi(pages = listOf(page(items = emptyList(), total = 0)))
        var failUserInfo = false
        val userSource = object : UserInfoSource {
            override suspend fun fetchCurrentUser(): CurrentUser? {
                if (failUserInfo) throw RuntimeException("/user/info failed")
                return CurrentUser(TEST_USER_ID, "新昵称", null, null)
            }
        }
        val vm = viewModel(api, userSource = userSource)
        val acknowledgedUserIds = mutableListOf<String>()

        // 先成功一次，让 CurrentUserStore 里有旧值；这是最容易把失败误判成功的情形。
        vm.onAppear()
        advanceUntilIdle()

        failUserInfo = true
        vm.onProfileChanged(onUserInfoRefreshed = { acknowledgedUserIds += it })
        advanceUntilIdle()

        assertTrue("旧缓存仍非空也不能把本次失败伪装成成功 ack", acknowledgedUserIds.isEmpty())
        assertEquals(TEST_USER_ID, vm.state.value.user?.userId)

        failUserInfo = false
        vm.onProfileChanged(onUserInfoRefreshed = { acknowledgedUserIds += it })
        advanceUntilIdle()

        assertEquals(listOf(TEST_USER_ID), acknowledgedUserIds)
        assertEquals("内容列表从始至终只拉首屏一次", listOf(0), api.createdCalls.map { it.page })
    }

    @Test
    fun `空 user store 的 user info 失败也回调失败并可随后成功`() = runTest {
        val api = FakeProfileApi(pages = listOf(page(items = emptyList(), total = 0)))
        var failUserInfo = true
        val userSource = object : UserInfoSource {
            override suspend fun fetchCurrentUser(): CurrentUser? {
                if (failUserInfo) throw RuntimeException("/user/info failed with empty store")
                return CurrentUser(TEST_USER_ID, "重试成功", null, null)
            }
        }
        val vm = viewModel(api, userSource = userSource)
        val acknowledgedUserIds = mutableListOf<String>()
        var failures = 0

        vm.onProfileChanged(
            onUserInfoRefreshed = { acknowledgedUserIds += it },
            onUserInfoRefreshFailed = { failures++ },
        )
        advanceUntilIdle()

        assertEquals(1, failures)
        assertTrue(acknowledgedUserIds.isEmpty())
        assertNull(vm.state.value.user)

        failUserInfo = false
        vm.onProfileChanged(
            onUserInfoRefreshed = { acknowledgedUserIds += it },
            onUserInfoRefreshFailed = { failures++ },
        )
        advanceUntilIdle()

        assertEquals("成功重试不再报失败", 1, failures)
        assertEquals(listOf(TEST_USER_ID), acknowledgedUserIds)
    }

    // ── 下拉刷新 ────────────────────────────────────

    @Test
    fun `下拉刷新从第 0 页重新累计`() = runTest {
        val api = FakeProfileApi(
            pages = listOf(
                page(items = listOf(item("a")), total = 10),
                page(items = listOf(item("b")), total = 10),
                page(items = listOf(item("c")), total = 10),
            ),
        )
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        vm.onLoadMore()
        advanceUntilIdle()
        assertEquals(2, vm.state.value.items.size)

        vm.onRefresh()
        advanceUntilIdle()

        assertEquals("刷新后只剩新的第 0 页", 1, vm.state.value.items.size)
        assertEquals("c", vm.state.value.createdItems.first().itemId)
        assertEquals(listOf(0, 1, 0), api.createdCalls.map { it.page })
    }

    @Test
    fun `刷新中重复触发被忽略`() = runTest {
        val api = FakeProfileApi(pages = List(5) { page(items = listOf(item("a")), total = 10) })
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        val before = api.createdCalls.size
        vm.onRefresh()
        vm.onRefresh()
        advanceUntilIdle()

        assertEquals("第二次 onRefresh 不该再发请求", before + 1, api.createdCalls.size)
    }

    // ── 登录态变化 ──────────────────────────────────

    @Test
    fun `登出时清空且不发 REQUIRED 请求`() = runTest {
        // AuthStateHub 硬约束：登出后 authorized 请求必然被前置拒绝。
        // Profile 两个接口都是 REQUIRED —— 与 Home 的 OPPORTUNISTIC 不同，
        // 照抄 HomeViewModel 的"无条件重拉"会打两个必然失败的请求
        val api = FakeProfileApi(pages = listOf(page(items = listOf(item("a")), total = 10)))
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        val callsBefore = api.createdCalls.size
        val statsBefore = api.statsCalls.size

        vm.onAuthChanged(loggedIn = false)
        advanceUntilIdle()

        assertEquals("登出后不该再发列表请求", callsBefore, api.createdCalls.size)
        assertEquals("登出后不该再发统计请求", statsBefore, api.statsCalls.size)
        assertTrue("列表要清空", vm.state.value.items.isEmpty())
        assertNull("用户信息要清空", vm.state.value.user)
    }

    @Test
    fun `登录后重新拉取`() = runTest {
        val api = FakeProfileApi(pages = List(3) { page(items = listOf(item("a")), total = 10) })
        val vm = viewModel(api)
        vm.onAuthChanged(loggedIn = true)
        advanceUntilIdle()

        assertTrue("登录后要拉列表", api.createdCalls.isNotEmpty())
        assertTrue("登录后要拉统计", api.statsCalls.isNotEmpty())
    }

    @Test
    fun `登出再登录不残留上一账号的列表`() = runTest {
        val api = FakeProfileApi(
            pages = listOf(
                page(items = listOf(item("old")), total = 10),
                page(items = listOf(item("new")), total = 10),
            ),
        )
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        assertEquals("old", vm.state.value.createdItems.single().itemId)

        vm.onAuthChanged(loggedIn = false)
        advanceUntilIdle()
        vm.onAuthChanged(loggedIn = true)
        advanceUntilIdle()

        assertEquals("必须是新账号的数据", "new", vm.state.value.createdItems.single().itemId)
    }

    // ── 请求参数契约 ────────────────────────────────

    @Test
    fun `创作列表带上当前语言`() = runTest {
        val api = FakeProfileApi(pages = listOf(page(items = emptyList(), total = 0)))
        val vm = viewModel(api, language = { "ja" })
        vm.onAppear()
        advanceUntilIdle()

        assertEquals("ja", api.createdCalls.first().languageCode)
    }

    @Test
    fun `统计请求带自己的 userId`() = runTest {
        val api = FakeProfileApi(pages = listOf(page(items = emptyList(), total = 0)))
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()

        assertEquals(TEST_USER_ID, api.statsCalls.first())
    }

    // ── 多 tab：游标隔离与在飞链 ────────────────────

    @Test
    fun `切 tab 后游标互不污染`() = runTest {
        // 这是分页状态按 tab 分表的存在理由：裸字段会让切回来的 tab
        // 从对方的页码继续拉，首屏缺前 N 页（ProfileTabPaging 类注释）
        val api = FakeProfileApi(
            pages = listOf(
                page(items = listOf(item("a")), total = 30),
                page(items = listOf(item("b")), total = 30),
                page(items = listOf(item("c")), total = 30),
            ),
            memoryPages = listOf(memoryPage(items = listOf(memoryItem("m1")), total = 30)),
        )
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        vm.onLoadMore()
        advanceUntilIdle()
        // 创作已到第 2 页（nextPage=2）
        vm.onTabSelected(ProfileTab.MEMORY)
        advanceUntilIdle()
        vm.onTabSelected(ProfileTab.CREATED)
        advanceUntilIdle()
        vm.onLoadMore()
        advanceUntilIdle()

        assertEquals("创作的翻页必须从自己的页码继续", listOf(0, 1, 2), api.createdCalls.map { it.page })
        assertEquals("记忆只拉过首屏", listOf(0), api.memoryCalls)
        assertEquals(listOf("a", "b", "c"), vm.state.value.createdItems.map { it.itemId })
    }

    @Test
    fun `切回已加载的 tab 不重拉`() = runTest {
        val api = FakeProfileApi(
            pages = listOf(page(items = listOf(item("a")), total = 1)),
            memoryPages = listOf(memoryPage(items = listOf(memoryItem("m1")), total = 1)),
        )
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        vm.onTabSelected(ProfileTab.MEMORY)
        advanceUntilIdle()
        vm.onTabSelected(ProfileTab.CREATED)
        advanceUntilIdle()
        vm.onTabSelected(ProfileTab.MEMORY)
        advanceUntilIdle()

        assertEquals("记忆首屏只该拉一次", listOf(0), api.memoryCalls)
        assertEquals("m1", vm.state.value.memoryItems.single().plotId)
    }

    @Test
    fun `记忆 total 为 0 时判到底且不续拉`() = runTest {
        val api = FakeProfileApi(
            pages = listOf(page(items = listOf(item("a")), total = 1)),
            memoryPages = listOf(memoryPage(items = emptyList(), total = 0)),
        )
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        vm.onTabSelected(ProfileTab.MEMORY)
        advanceUntilIdle()

        assertTrue(vm.state.value.hasReachedEnd)
        assertEquals(listOf(0), api.memoryCalls)
        vm.onLoadMore()
        advanceUntilIdle()
        assertEquals("到底后不再翻页", listOf(0), api.memoryCalls)
    }

    @Test
    fun `切到角色卡 tab 首拉走对应接口`() = runTest {
        // P6 前这里验的是「占位 tab 不发请求」；五 tab 全接后改验数据链正确分流
        val api = FakeProfileApi(
            pages = listOf(page(items = listOf(item("a")), total = 1)),
            roleCardPages = listOf(
                ProfileRoleCardPage(items = listOf(roleCardItem("rc1")), total = 1),
            ),
        )
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        val created = api.createdCalls.size

        vm.onTabSelected(ProfileTab.ROLE_CARD)
        advanceUntilIdle()

        assertEquals(ProfileTab.ROLE_CARD, vm.state.value.selectedTab)
        assertEquals(listOf(0), api.roleCardCalls)
        assertEquals("切 tab 不该重拉创作", created, api.createdCalls.size)
        assertEquals("rc1", vm.state.value.roleCardItems.single().profileCardId)
        assertTrue("total=1 已到底", vm.state.value.hasReachedEnd)
    }

    @Test
    fun `切走打断在飞首屏后切回能重拉`() = runTest {
        // 被取消的首屏若不复位，isInitialLoading 会永远卡 true，
        // loadFirstPageIfNeeded 从此跳过这个 tab（ViewModel.cancelInFlight 注释）
        val api = FakeProfileApi(
            pages = listOf(page(items = listOf(item("a")), total = 1)),
            memoryPages = listOf(memoryPage(items = listOf(memoryItem("m1")), total = 1)),
        )
        val gate = CompletableDeferred<Unit>()
        api.memoryGate = gate
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()

        vm.onTabSelected(ProfileTab.MEMORY)
        advanceUntilIdle() // 在飞链挂在 gate 上
        assertEquals(listOf(0), api.memoryCalls)

        vm.onTabSelected(ProfileTab.CREATED) // 打断
        gate.complete(Unit)
        advanceUntilIdle()

        val interrupted = vm.state.value.pagingOf(ProfileTab.MEMORY)
        assertFalse("被打断的首屏必须复位", interrupted.isInitialLoading)
        assertFalse(interrupted.hasLoadedOnce)
        assertTrue("取消的响应不得落数据", interrupted.items.isEmpty())

        api.memoryGate = null
        vm.onTabSelected(ProfileTab.MEMORY)
        advanceUntilIdle()

        assertEquals("切回要重拉", listOf(0, 0), api.memoryCalls)
        assertEquals("m1", vm.state.value.memoryItems.single().plotId)
    }

    @Test
    fun `下拉刷新复位其它 tab 待重拉`() = runTest {
        // RN 的 handleRefresh 把五个 tab 全 mutate（CharacterGrid.tsx:252-262）。
        // 单在飞链的对应物：当前 tab 立即重拉，其它 tab 复位、下次进入重拉
        val api = FakeProfileApi(
            pages = List(3) { page(items = listOf(item("a$it")), total = 1) },
            memoryPages = List(2) { memoryPage(items = listOf(memoryItem("m$it")), total = 1) },
        )
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        vm.onTabSelected(ProfileTab.MEMORY)
        advanceUntilIdle()
        vm.onTabSelected(ProfileTab.CREATED)
        advanceUntilIdle()
        assertEquals(listOf(0), api.memoryCalls)

        vm.onRefresh()
        advanceUntilIdle()

        assertFalse(vm.state.value.pagingOf(ProfileTab.MEMORY).hasLoadedOnce)
        vm.onTabSelected(ProfileTab.MEMORY)
        advanceUntilIdle()
        assertEquals("刷新后再进记忆要重拉", listOf(0, 0), api.memoryCalls)
        assertEquals("m1", vm.state.value.memoryItems.single().plotId)
    }

    @Test
    fun `语言 settle 后全 tab 复位且当前 tab 带新语言重拉`() = runTest {
        var lang = "en"
        val api = FakeProfileApi(
            pages = List(3) { page(items = listOf(item("a$it")), total = 1) },
            memoryPages = listOf(memoryPage(items = listOf(memoryItem("m1")), total = 1)),
        )
        val vm = viewModel(api, language = { lang })
        vm.onAppear()
        advanceUntilIdle()
        vm.onTabSelected(ProfileTab.MEMORY)
        advanceUntilIdle()
        vm.onTabSelected(ProfileTab.CREATED)
        advanceUntilIdle()

        lang = "ja"
        vm.onLanguageSettled()
        advanceUntilIdle()

        assertEquals("ja", api.createdCalls.last().languageCode)
        assertFalse("其它 tab 也要复位", vm.state.value.pagingOf(ProfileTab.MEMORY).hasLoadedOnce)
    }

    @Test
    fun `角色卡 tab 上下拉刷新走角色卡接口不动创作`() = runTest {
        val api = FakeProfileApi(
            pages = listOf(page(items = listOf(item("a")), total = 1)),
            roleCardPages = List(2) {
                ProfileRoleCardPage(items = listOf(roleCardItem("rc$it")), total = 1)
            },
        )
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        vm.onTabSelected(ProfileTab.ROLE_CARD)
        advanceUntilIdle()
        val created = api.createdCalls.size
        val stats = api.statsCalls.size

        vm.onRefresh()
        advanceUntilIdle()

        assertFalse("刷新圈必须收起", vm.state.value.isRefreshing)
        assertEquals("不发创作请求", created, api.createdCalls.size)
        assertEquals("角色卡重拉第 0 页", listOf(0, 0), api.roleCardCalls)
        assertEquals("统计要刷", stats + 1, api.statsCalls.size)
        assertEquals("rc1", vm.state.value.roleCardItems.single().profileCardId)
    }

    // ── P6：页数轨到底判定与角色卡排序 ────────────────

    @Test
    fun `收藏 tab 按 total_pages 判到底`() = runTest {
        // total_pages=2：拉完第 2 页才到底 —— 拿条数比会在第一页就误判
        val api = FakeProfileApi(
            pages = listOf(page(items = listOf(item("a")), total = 1)),
            favoritePages = listOf(
                ProfileFavoritePage(items = listOf(favoriteItem("f1")), totalPages = 2),
                ProfileFavoritePage(items = listOf(favoriteItem("f2")), totalPages = 2),
            ),
        )
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        vm.onTabSelected(ProfileTab.FAVORITES)
        advanceUntilIdle()

        assertFalse("第 1 页（共 2 页）不该到底", vm.state.value.hasReachedEnd)
        vm.onLoadMore()
        advanceUntilIdle()

        assertTrue("拉完第 2 页到底", vm.state.value.hasReachedEnd)
        assertEquals(listOf(0, 1), api.favoriteCalls)
        assertEquals(listOf("f1", "f2"), vm.state.value.favoriteItems.map { it.characterId })
    }

    @Test
    fun `收藏 total_pages 为 0 直接到底`() = runTest {
        val api = FakeProfileApi(
            pages = listOf(page(items = listOf(item("a")), total = 1)),
            favoritePages = listOf(ProfileFavoritePage(items = emptyList(), totalPages = 0)),
        )
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        vm.onTabSelected(ProfileTab.FAVORITES)
        advanceUntilIdle()

        assertTrue(vm.state.value.hasReachedEnd)
        assertEquals(listOf(0), api.favoriteCalls)
        vm.onLoadMore()
        advanceUntilIdle()
        assertEquals("到底后不再翻页", listOf(0), api.favoriteCalls)
    }

    @Test
    fun `点赞与收藏走不同接口`() = runTest {
        val api = FakeProfileApi(
            pages = listOf(page(items = listOf(item("a")), total = 1)),
            favoritePages = listOf(ProfileFavoritePage(listOf(favoriteItem("fav")), 1)),
            likedPages = listOf(ProfileFavoritePage(listOf(favoriteItem("like")), 1)),
        )
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        vm.onTabSelected(ProfileTab.FAVORITES)
        advanceUntilIdle()
        vm.onTabSelected(ProfileTab.LIKED)
        advanceUntilIdle()

        assertEquals(listOf(0), api.favoriteCalls)
        assertEquals(listOf(0), api.likedCalls)
        assertEquals("like", vm.state.value.favoriteItems.single().characterId)
    }

    @Test
    fun `角色卡默认卡置顶且稳定`() = runTest {
        val api = FakeProfileApi(
            pages = listOf(page(items = listOf(item("a")), total = 1)),
            roleCardPages = listOf(
                ProfileRoleCardPage(
                    items = listOf(
                        roleCardItem("rc1"),
                        roleCardItem("rc2", makeDefault = true),
                        roleCardItem("rc3"),
                    ),
                    total = 3,
                ),
            ),
        )
        val vm = viewModel(api)
        vm.onAppear()
        advanceUntilIdle()
        vm.onTabSelected(ProfileTab.ROLE_CARD)
        advanceUntilIdle()

        assertEquals(
            "默认卡置顶，其余保持接口顺序",
            listOf("rc2", "rc1", "rc3"),
            vm.state.value.roleCardItems.map { it.profileCardId },
        )
    }

    // ── 钱包 ────────────────────────────────────────

    @Test
    fun `钱包由两个接口合成`() = runTest {
        val api = FakeProfileApi(pages = listOf(page(items = emptyList(), total = 0)))
        val walletApi = FakeWalletApi(
            wallet = ProfileWallet(gemAmount = 12, leftFreeAmount = 3, coinAmount = 4.5),
            planId = ProfileWallet.PLAN_PREMIUM,
        )
        val vm = viewModel(api, walletApi = walletApi)
        vm.onAppear()
        advanceUntilIdle()

        val w = vm.state.value.wallet
        assertEquals(12L, w.gemAmount)
        assertEquals(ProfileWallet.PLAN_PREMIUM, w.planId)
        assertEquals("Premium", w.planNameKey)
    }

    @Test
    fun `钱包接口失败保留旧值`() = runTest {
        // 一次网络抖动不该把用户正看着的余额清零（同 stats 的纪律）
        val api = FakeProfileApi(pages = List(2) { page(items = emptyList(), total = 0) })
        val walletApi = FakeWalletApi(
            wallet = ProfileWallet(gemAmount = 12),
            planId = ProfileWallet.PLAN_STANDARD,
        )
        val vm = viewModel(api, walletApi = walletApi)
        vm.onAppear()
        advanceUntilIdle()
        assertEquals(12L, vm.state.value.wallet.gemAmount)

        walletApi.failWallet = true
        walletApi.failPlan = true
        vm.onRefresh()
        advanceUntilIdle()

        assertEquals("失败后余额要保留", 12L, vm.state.value.wallet.gemAmount)
        assertEquals(ProfileWallet.PLAN_STANDARD, vm.state.value.wallet.planId)
    }

    @Test
    fun `档位接口单独失败时余额仍更新`() = runTest {
        val api = FakeProfileApi(pages = List(2) { page(items = emptyList(), total = 0) })
        val walletApi = FakeWalletApi(
            wallet = ProfileWallet(gemAmount = 12),
            planId = ProfileWallet.PLAN_DELUXE,
        )
        val vm = viewModel(api, walletApi = walletApi)
        vm.onAppear()
        advanceUntilIdle()

        walletApi.wallet = ProfileWallet(gemAmount = 99)
        walletApi.failPlan = true
        vm.onRefresh()
        advanceUntilIdle()

        assertEquals(99L, vm.state.value.wallet.gemAmount)
        assertEquals("档位保留旧值", ProfileWallet.PLAN_DELUXE, vm.state.value.wallet.planId)
    }

    @Test
    fun `登出清空钱包`() = runTest {
        val api = FakeProfileApi(pages = listOf(page(items = emptyList(), total = 0)))
        val walletApi = FakeWalletApi(wallet = ProfileWallet(gemAmount = 12))
        val vm = viewModel(api, walletApi = walletApi)
        vm.onAppear()
        advanceUntilIdle()
        assertEquals(12L, vm.state.value.wallet.gemAmount)

        vm.onAuthChanged(loggedIn = false)
        advanceUntilIdle()

        assertEquals(ProfileWallet.EMPTY, vm.state.value.wallet)
    }

    // ── fixtures ────────────────────────────────────

    private fun TestScope.viewModel(
        api: FakeProfileApi,
        language: () -> String = { "en" },
        failUserInfo: Boolean = false,
        userSource: UserInfoSource? = null,
        walletApi: FakeWalletApi = FakeWalletApi(),
    ): ProfileViewModel {
        val resolvedUserSource = userSource ?: object : UserInfoSource {
            override suspend fun fetchCurrentUser(): CurrentUser? {
                if (failUserInfo) throw RuntimeException("boom")
                return CurrentUser(TEST_USER_ID, "昵称", null, null)
            }
        }
        return ProfileViewModel(
            api = api,
            walletApi = walletApi,
            userStore = CurrentUserStore(resolvedUserSource, logWarn = { _, _ -> }),
            languageProvider = language,
            scope = this,
            logWarn = { _, _ -> },
        )
    }

    private class FakeWalletApi(
        var wallet: ProfileWallet = ProfileWallet.EMPTY,
        var planId: Int = ProfileWallet.PLAN_FREE,
        var failWallet: Boolean = false,
        var failPlan: Boolean = false,
    ) : ProfileWalletSource {
        override suspend fun fetchWallet(): ProfileWallet {
            if (failWallet) throw RuntimeException("wallet boom")
            return wallet
        }

        override suspend fun fetchSubscriptionPlanId(): Int {
            if (failPlan) throw RuntimeException("plan boom")
            return planId
        }
    }

    private class FakeProfileApi(
        private val pages: List<ProfileCreatedPage> = emptyList(),
        private val memoryPages: List<ProfileMemoryPage> = emptyList(),
        private val roleCardPages: List<ProfileRoleCardPage> = emptyList(),
        private val favoritePages: List<ProfileFavoritePage> = emptyList(),
        private val likedPages: List<ProfileFavoritePage> = emptyList(),
        var failCreated: Boolean = false,
        private val failStats: Boolean = false,
    ) : ProfileSource {
        data class CreatedCall(val page: Int, val languageCode: String)

        val createdCalls = mutableListOf<CreatedCall>()
        val memoryCalls = mutableListOf<Int>()
        val roleCardCalls = mutableListOf<Int>()
        val favoriteCalls = mutableListOf<Int>()
        val likedCalls = mutableListOf<Int>()
        val statsCalls = mutableListOf<String>()

        /** 置一个未完成的 Deferred 可让记忆请求挂起（测「切走取消在飞链」用）。 */
        var memoryGate: CompletableDeferred<Unit>? = null

        private var cursor = 0
        private var memoryCursor = 0
        private var roleCardCursor = 0
        private var favoriteCursor = 0
        private var likedCursor = 0

        override suspend fun fetchSelfStats(userId: String): ProfileStats {
            statsCalls += userId
            if (failStats) throw RuntimeException("stats boom")
            return ProfileStats(1, 2, 3, 4)
        }

        override suspend fun fetchCreatedPage(page: Int, languageCode: String): ProfileCreatedPage {
            createdCalls += CreatedCall(page, languageCode)
            if (failCreated) throw RuntimeException("created boom")
            // 按调用顺序取，页码不作索引（刷新会回到 0 但要给新数据）
            val result = pages.getOrNull(cursor) ?: pages.lastOrNull()
            cursor++
            return result ?: ProfileCreatedPage(emptyList(), 0, null)
        }

        override suspend fun fetchMemoryPage(page: Int): ProfileMemoryPage {
            memoryCalls += page
            memoryGate?.await()
            val result = memoryPages.getOrNull(memoryCursor) ?: memoryPages.lastOrNull()
            memoryCursor++
            return result ?: ProfileMemoryPage(emptyList(), 0L)
        }

        override suspend fun fetchRoleCardPage(page: Int): ProfileRoleCardPage {
            roleCardCalls += page
            val result = roleCardPages.getOrNull(roleCardCursor) ?: roleCardPages.lastOrNull()
            roleCardCursor++
            return result ?: ProfileRoleCardPage(emptyList(), 0L)
        }

        override suspend fun fetchFavoritePage(page: Int, liked: Boolean): ProfileFavoritePage {
            return if (liked) {
                likedCalls += page
                val result = likedPages.getOrNull(likedCursor) ?: likedPages.lastOrNull()
                likedCursor++
                result ?: ProfileFavoritePage(emptyList(), 0L)
            } else {
                favoriteCalls += page
                val result = favoritePages.getOrNull(favoriteCursor) ?: favoritePages.lastOrNull()
                favoriteCursor++
                result ?: ProfileFavoritePage(emptyList(), 0L)
            }
        }
    }

    private fun page(items: List<ProfileCreatedItem>, total: Long) =
        ProfileCreatedPage(items = items, total = total, rawList = null)

    private fun memoryPage(items: List<ProfileMemoryItem>, total: Long) =
        ProfileMemoryPage(items = items, total = total)

    private fun item(id: String): ProfileCreatedItem =
        ProfileCreatedItem.parse(
            JSONObject().put("item_type", "character").put("item_id", id).put("nickname", id),
        )!!

    private fun memoryItem(id: String): ProfileMemoryItem =
        ProfileMemoryItem.parse(JSONObject().put("plot_id", id), null, null)!!

    private fun roleCardItem(id: String, makeDefault: Boolean = false): ProfileRoleCardItem =
        ProfileRoleCardItem.parse(
            JSONObject().put("profile_card_id", id).put("nickname", id)
                .put("make_default", makeDefault),
        )!!

    private fun favoriteItem(id: String): ProfileFavoriteItem =
        ProfileFavoriteItem.parse(
            JSONObject().put("character_id", id).put("nickname", id),
        )!!

    private companion object {
        const val TEST_USER_ID = "u-self"
    }
}
