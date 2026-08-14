package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.auth.Generations
import ai.lightspeed.tipsy.shell.pages.profile.CreatorListPage
import ai.lightspeed.tipsy.shell.pages.profile.ProfileCreatedItem
import ai.lightspeed.tipsy.shell.pages.profile.ProfileItemType
import ai.lightspeed.tipsy.shell.pages.profile.ProfileStats
import ai.lightspeed.tipsy.shell.pages.profile.PublicProfileSource
import ai.lightspeed.tipsy.shell.pages.profile.PublicProfileViewModel
import ai.lightspeed.tipsy.shell.pages.profile.PublicUserProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 他人主页的编排语义（§2.32）。
 *
 * 测的是「错了不报错」的几件事：v2→v1 回落（含 v2 失败也要回落）、
 * 关注成功后必须重拉而不是本地翻转、登出只清关注态不清页面、
 * 单页不翻页。
 */
class PublicProfileViewModelTest {

    // ── 首屏 ────────────────────────────────────────

    @Test
    fun `bind 拉资料 统计与列表`() = runTest {
        val api = FakeApi(v2 = listOf(item("a"), item("b")))
        val vm = viewModel(api)
        vm.bind("u1")
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("u1", s.userId)
        assertEquals("Alice", s.profile?.nickname)
        assertEquals(2, s.items.size)
        assertEquals(1L, s.stats.followersLabelCount)
        assertFalse(s.isLoading)
    }

    @Test
    fun `bind 空 userId 不发任何请求`() = runTest {
        val api = FakeApi()
        val vm = viewModel(api)
        vm.bind("")
        advanceUntilIdle()

        assertTrue(api.profileCalls.isEmpty())
        assertTrue(api.v2Calls.isEmpty())
    }

    @Test
    fun `bind 幂等 同一 userId 已有资料不重拉`() = runTest {
        val api = FakeApi(v2 = listOf(item("a")))
        val vm = viewModel(api)
        vm.bind("u1")
        advanceUntilIdle()
        vm.bind("u1")
        advanceUntilIdle()

        assertEquals(1, api.profileCalls.size)
    }

    @Test
    fun `bind 换 userId 会重置并重拉`() = runTest {
        val api = FakeApi(v2 = listOf(item("a")))
        val vm = viewModel(api)
        vm.bind("u1")
        advanceUntilIdle()
        vm.bind("u2")
        advanceUntilIdle()

        assertEquals(listOf("u1", "u2"), api.profileCalls)
        assertEquals("u2", vm.state.value.userId)
    }

    // ── v2 → v1 回落 ────────────────────────────────

    @Test
    fun `v2 有数据时不发 v1`() = runTest {
        val api = FakeApi(v2 = listOf(item("a")))
        val vm = viewModel(api)
        vm.bind("u1")
        advanceUntilIdle()

        assertEquals(1, api.v2Calls.size)
        assertTrue("v2 非空时 v1 的结果永不上屏，不该发", api.v1Calls.isEmpty())
    }

    @Test
    fun `v2 为空回落 v1`() = runTest {
        // CharacterGrid.tsx:980-983 的三元
        val api = FakeApi(v2 = emptyList(), v1 = listOf(item("z")))
        val vm = viewModel(api)
        vm.bind("u1")
        advanceUntilIdle()

        assertEquals(1, api.v1Calls.size)
        assertEquals(listOf("z"), vm.state.value.items.map { it.itemId })
    }

    @Test
    fun `v2 失败也要回落 v1 而不是整页空白`() = runTest {
        // 只在「空」时回落会让 v2 端点故障时壳空白，而 RN 那边照样有内容
        // （RN 两个请求独立，v2 挂了 SWR 给 undefined，三元同样落 v1）
        val api = FakeApi(v1 = listOf(item("z")), failV2 = true)
        val vm = viewModel(api)
        vm.bind("u1")
        advanceUntilIdle()

        assertEquals(listOf("z"), vm.state.value.items.map { it.itemId })
        assertNull("有内容就不该显示错误", vm.state.value.errorMessage)
    }

    @Test
    fun `v2 与 v1 都空时走空态而不是错误态`() = runTest {
        val api = FakeApi(v2 = emptyList(), v1 = emptyList())
        val vm = viewModel(api)
        vm.bind("u1")
        advanceUntilIdle()

        assertTrue(vm.state.value.items.isEmpty())
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun `列表按 dedupeKey 去重`() = runTest {
        val api = FakeApi(v2 = listOf(item("a"), item("a"), item("b")))
        val vm = viewModel(api)
        vm.bind("u1")
        advanceUntilIdle()

        // 重复 key 进 LazyGrid 会直接崩
        assertEquals(2, vm.state.value.items.size)
    }

    // ── 不翻页（与 RN 对等）──────────────────────────

    @Test
    fun `列表只拉一次 没有翻页`() = runTest {
        // §2.32 第 5 条：RN 侧他人主页触底调的是自己那条列表，
        // 两条 creator 列表都无 setSize 出口 —— 壳按单页实现即对等
        val api = FakeApi(v2 = List(200) { item("i$it") })
        val vm = viewModel(api)
        vm.bind("u1")
        advanceUntilIdle()

        assertEquals(1, api.v2Calls.size)
        assertEquals(200, vm.state.value.items.size)
    }

    // ── 统计失败降级 ────────────────────────────────

    @Test
    fun `统计失败不影响资料与列表`() = runTest {
        val api = FakeApi(v2 = listOf(item("a")), failStats = true)
        val vm = viewModel(api)
        vm.bind("u1")
        advanceUntilIdle()

        val s = vm.state.value
        assertNotNull("统计失败不该让整页失败", s.profile)
        assertEquals(1, s.items.size)
        assertEquals(ProfileStats.EMPTY, s.stats)
        assertNull(s.errorMessage)
    }

    @Test
    fun `资料失败且列表为空时显示错误`() = runTest {
        val api = FakeApi(failProfile = true)
        val vm = viewModel(api)
        vm.bind("u1")
        advanceUntilIdle()

        assertNotNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isLoading)
    }

    // ── 关注 toggle ─────────────────────────────────

    @Test
    fun `关注成功后重拉资料与统计 而不是本地翻转`() = runTest {
        // useProfile.tsx:241-243 两个 mutate。只翻转本地的表现是
        // 「按钮变了但粉丝数不动」
        val api = FakeApi(v2 = listOf(item("a")), followed = false)
        val vm = viewModel(api)
        vm.bind("u1")
        advanceUntilIdle()
        assertEquals(1, api.profileCalls.size)

        api.followed = true
        api.statsToReturn = ProfileStats(9, 2, 3, 4)
        vm.onFollowClick()
        advanceUntilIdle()

        assertEquals(1, api.followCalls.size)
        assertEquals("必须重拉 /user/get/public", 2, api.profileCalls.size)
        assertEquals("必须重拉 stats", 2, api.statsCalls.size)
        assertTrue(vm.state.value.isFollowed)
        assertEquals(9L, vm.state.value.stats.followersLabelCount)
    }

    @Test
    fun `关注在飞期间连点不重复发请求`() = runTest {
        val api = FakeApi(v2 = listOf(item("a")))
        val gate = CompletableDeferred<Unit>()
        api.followGate = gate
        val vm = viewModel(api)
        vm.bind("u1")
        advanceUntilIdle()

        vm.onFollowClick()
        advanceUntilIdle()
        assertTrue(vm.state.value.isFollowPending)
        vm.onFollowClick()
        vm.onFollowClick()
        advanceUntilIdle()

        assertEquals("toggle 连点会让计数闪两下", 1, api.followCalls.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.isFollowPending)
    }

    @Test
    fun `关注失败收掉 pending 且不重拉`() = runTest {
        val api = FakeApi(v2 = listOf(item("a")), failFollow = true)
        val vm = viewModel(api)
        vm.bind("u1")
        advanceUntilIdle()
        val profileCallsBefore = api.profileCalls.size

        vm.onFollowClick()
        advanceUntilIdle()

        assertFalse("失败后必须能再点", vm.state.value.isFollowPending)
        assertEquals(profileCallsBefore, api.profileCalls.size)
    }

    @Test
    fun `注销用户不发关注请求`() = runTest {
        // useProfile.tsx:238-239 显式 throw；壳侧按钮本就不渲染，这是第二道闸
        val api = FakeApi(v2 = listOf(item("a")), deleted = true)
        val vm = viewModel(api)
        vm.bind("u1")
        advanceUntilIdle()

        vm.onFollowClick()
        advanceUntilIdle()

        assertTrue(api.followCalls.isEmpty())
        assertFalse(vm.state.value.showFollowButton)
    }

    @Test
    fun `资料未拉到时点关注是空操作`() = runTest {
        val api = FakeApi(failProfile = true)
        val vm = viewModel(api)
        vm.bind("u1")
        advanceUntilIdle()

        vm.onFollowClick()
        advanceUntilIdle()

        assertTrue(api.followCalls.isEmpty())
    }

    // ── 登录态 ──────────────────────────────────────

    @Test
    fun `登出只清关注态 不清页面`() = runTest {
        // 他人的公开资料在登出后仍可展示（列表走 OPPORTUNISTIC）；
        // 但 is_followed 是账号私有的，不清会显示上一个账号的 Following
        val api = FakeApi(v2 = listOf(item("a")), followed = true)
        val vm = viewModel(api)
        vm.bind("u1")
        advanceUntilIdle()
        assertTrue(vm.state.value.isFollowed)

        vm.onAuthChanged(loggedIn = false)
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse("关注态属上一个账号，必须清", s.isFollowed)
        assertEquals("列表不该被清", 1, s.items.size)
        assertNotNull("资料不该被清", s.profile)
    }

    @Test
    fun `登录后重拉 因为新账号的关注关系不同`() = runTest {
        val api = FakeApi(v2 = listOf(item("a")))
        val vm = viewModel(api)
        vm.bind("u1")
        advanceUntilIdle()
        val before = api.profileCalls.size

        vm.onAuthChanged(loggedIn = true)
        advanceUntilIdle()

        assertEquals(before + 1, api.profileCalls.size)
    }

    @Test
    fun `未 bind 时登录态变化不发请求`() = runTest {
        val api = FakeApi()
        val vm = viewModel(api)
        vm.onAuthChanged(loggedIn = true)
        advanceUntilIdle()

        assertTrue(api.profileCalls.isEmpty())
    }

    // ── 刷新与语言 ──────────────────────────────────

    @Test
    fun `刷新保留旧列表直到新数据到达`() = runTest {
        val api = FakeApi(v2 = listOf(item("a")))
        val vm = viewModel(api)
        vm.bind("u1")
        advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        api.profileGate = gate
        vm.onRefresh()
        advanceUntilIdle()

        assertTrue(vm.state.value.isRefreshing)
        assertEquals("刷新期间清空会整屏闪白", 1, vm.state.value.items.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.isRefreshing)
    }

    @Test
    fun `注销用户不响应刷新`() = runTest {
        // RN 连 refreshControl 都不渲染（CharacterGrid.tsx:1455）
        val api = FakeApi(v2 = listOf(item("a")), deleted = true)
        val vm = viewModel(api)
        vm.bind("u1")
        advanceUntilIdle()
        val before = api.profileCalls.size

        vm.onRefresh()
        advanceUntilIdle()

        assertEquals(before, api.profileCalls.size)
        assertFalse(vm.state.value.isRefreshEnabled)
    }

    @Test
    fun `刷新在飞时再次刷新是空操作`() = runTest {
        val api = FakeApi(v2 = listOf(item("a")))
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(api)
        vm.bind("u1")
        advanceUntilIdle()

        api.profileGate = gate
        vm.onRefresh()
        advanceUntilIdle()
        val during = api.profileCalls.size
        vm.onRefresh()
        advanceUntilIdle()

        assertEquals(during, api.profileCalls.size)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `语言 settle 后带新语言重拉 v2`() = runTest {
        // v2 请求体带 language_code（v1 不带）
        var lang = "en"
        val api = FakeApi(v2 = listOf(item("a")))
        val vm = viewModel(api, language = { lang })
        vm.bind("u1")
        advanceUntilIdle()

        lang = "ja"
        vm.onLanguageSettled()
        advanceUntilIdle()

        assertEquals(listOf("en", "ja"), api.v2Calls.map { it.languageCode })
    }

    // ── 派生态 ──────────────────────────────────────

    @Test
    fun `注销用户不渲染关注按钮也禁用刷新`() = runTest {
        val api = FakeApi(v2 = listOf(item("a")), deleted = true)
        val vm = viewModel(api)
        vm.bind("u1")
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.showFollowButton)
        assertFalse(s.isRefreshEnabled)
    }

    @Test
    fun `空白 bio 不占位`() = runTest {
        val api = FakeApi(v2 = listOf(item("a")), bio = "   ")
        val vm = viewModel(api)
        vm.bind("u1")
        advanceUntilIdle()

        assertNull(vm.state.value.bio)
    }

    // ── auth 轨闸门（§4.4）─────────────────────────

    /**
     * 登出瞬间在飞的资料响应**不得**把旧账号的关注态写回来。
     *
     * 这是「登出后仍显示 Following」的精确时序：取消只在挂起点生效，
     * 取消发生后、协程抛 CancellationException 之前，写 `_state` 的
     * 非挂起代码照常执行（§2.25 在 `CurrentUserStore.refresh` 上踩过同型）。
     * 用挂起的资料响应锁死。
     */
    @Test
    fun `登出瞬间在飞的资料响应不得写回旧账号关注态`() = runTest {
        val generations = Generations()
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi(v2 = listOf(item("a")), followed = true)
        api.profileGate = gate
        val vm = viewModel(api, generations = generations)
        vm.bind("u1")
        advanceUntilIdle()
        // 资料还挂着，页面尚无关注态
        assertFalse(vm.state.value.isFollowed)

        // 登出：hub 侧 bump auth 轨，页面清关注态
        generations.bumpAuth()
        vm.onAuthChanged(loggedIn = false)
        advanceUntilIdle()

        // 旧响应此刻才到
        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(
            "登出后旧账号的 is_followed 不得写回 —— 那正是本刀要避免的",
            vm.state.value.isFollowed,
        )
    }

    /** 换号后在飞的统计不得写进新账号的页面。 */
    @Test
    fun `换号后在飞响应不写状态`() = runTest {
        val generations = Generations()
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi(v2 = listOf(item("a")))
        api.profileGate = gate
        val vm = viewModel(api, generations = generations)
        vm.bind("u1")
        advanceUntilIdle()

        generations.bumpAuth()
        gate.complete(Unit)
        advanceUntilIdle()

        assertNull("换号后旧响应不该写资料", vm.state.value.profile)
    }

    /** 关注成功但期间换了号 → 不重拉、不写状态（这次关注属上一个账号）。 */
    @Test
    fun `关注期间换号不重拉`() = runTest {
        val generations = Generations()
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi(v2 = listOf(item("a")))
        val vm = viewModel(api, generations = generations)
        vm.bind("u1")
        advanceUntilIdle()
        val profileCallsBefore = api.profileCalls.size

        api.followGate = gate
        vm.onFollowClick()
        advanceUntilIdle()
        generations.bumpAuth()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("换号后不该重拉", profileCallsBefore, api.profileCalls.size)
    }

    /**
     * 换目标后，上一个人的关注链不得把资料写进新页面。
     *
     * auth 轨挡不住这个（没换号），所以靠取消 + 回写前比对 userId 双保险。
     * 不挡的表现是「进 B 的主页却显示 A 的昵称头像」。
     */
    @Test
    fun `换目标后旧关注链不得写进新页面`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi(v2 = listOf(item("a")))
        val vm = viewModel(api)
        vm.bind("u1")
        advanceUntilIdle()

        api.followGate = gate
        vm.onFollowClick()
        advanceUntilIdle()

        // 还没回来就切到另一个人
        vm.bind("u2")
        advanceUntilIdle()
        val callsAfterBind = api.profileCalls.size

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("u2", vm.state.value.userId)
        assertEquals(
            "旧关注链不该为 u1 重拉资料",
            callsAfterBind,
            api.profileCalls.size,
        )
        assertEquals(listOf("u1", "u2"), api.profileCalls)
    }

    // ── 测试脚手架 ──────────────────────────────────

    private fun TestScope.viewModel(
        api: FakeApi,
        language: () -> String = { "en" },
        generations: Generations = Generations(),
    ) = PublicProfileViewModel(
        api = api,
        languageProvider = language,
        generations = generations,
        scope = this,
        logWarn = { _, _ -> },
    )

    private class FakeApi(
        private val v2: List<ProfileCreatedItem>? = null,
        private val v1: List<ProfileCreatedItem> = emptyList(),
        var followed: Boolean = false,
        private val deleted: Boolean = false,
        private val bio: String? = null,
        private val failProfile: Boolean = false,
        private val failStats: Boolean = false,
        private val failV2: Boolean = false,
        private val failFollow: Boolean = false,
    ) : PublicProfileSource {
        data class V2Call(val userId: String, val languageCode: String)

        val profileCalls = mutableListOf<String>()
        val statsCalls = mutableListOf<String>()
        val v2Calls = mutableListOf<V2Call>()
        val v1Calls = mutableListOf<String>()
        val followCalls = mutableListOf<String>()

        var statsToReturn = ProfileStats(1, 2, 3, 4)

        /** 未完成的 Deferred 可让对应请求挂起（测在飞态）。 */
        var profileGate: CompletableDeferred<Unit>? = null
        var followGate: CompletableDeferred<Unit>? = null

        override suspend fun fetchPublicUser(userId: String): PublicUserProfile? {
            profileCalls += userId
            profileGate?.await()
            if (failProfile) throw RuntimeException("profile boom")
            return PublicUserProfile(
                userId = userId,
                nickname = "Alice",
                avatarUrl = null,
                backgroundImgUrl = null,
                bio = bio,
                isFollowed = followed,
                isDeleted = deleted,
            )
        }

        override suspend fun fetchPublicStats(userId: String): ProfileStats {
            statsCalls += userId
            if (failStats) throw RuntimeException("stats boom")
            return statsToReturn
        }

        override suspend fun fetchCreatorListV2(
            userId: String,
            languageCode: String,
        ): CreatorListPage {
            v2Calls += V2Call(userId, languageCode)
            if (failV2) throw RuntimeException("v2 boom")
            return CreatorListPage(v2 ?: emptyList())
        }

        override suspend fun fetchCreatorListV1(userId: String): CreatorListPage {
            v1Calls += userId
            return CreatorListPage(v1)
        }

        override suspend fun toggleFollow(userId: String) {
            followCalls += userId
            followGate?.await()
            if (failFollow) throw RuntimeException("follow boom")
        }
    }

    private fun item(id: String): ProfileCreatedItem = ProfileCreatedItem(
        type = ProfileItemType.CHARACTER,
        itemId = id,
        gameId = null,
        name = id,
        coverUrl = null,
        reviewStage = "pass",
        minorReviewStatus = null,
        isPinned = false,
        isPublic = true,
        nsfw = false,
        characterType = null,
        finalHit = null,
        messageCount = 0,
        exposureCount = null,
        rawJson = "{}",
    )
}
