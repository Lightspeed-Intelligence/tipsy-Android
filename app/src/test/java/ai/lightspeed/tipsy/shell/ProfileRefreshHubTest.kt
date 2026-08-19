package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.profile.ProfileRefreshCoordinator
import ai.lightspeed.tipsy.shell.pages.profile.ProfileRefreshHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ProfileRefreshHub] 的账号隔离、成功 ack、close retry 与生命周期时序。 */
class ProfileRefreshHubTest {

    @Test
    fun `dirty 只有当前账号的 user info 成功才能 ack`() {
        val hub = ProfileRefreshHub()
        hub.notifyProfileChanged(ACCOUNT_A)
        val attempt = requireNotNull(hub.pendingAttempt(ACCOUNT_A))

        assertNull("B 不能领取 A 的刷新", hub.pendingAttempt(ACCOUNT_B))
        assertFalse(
            "token 已换号时旧响应不能清 dirty",
            hub.acknowledge(attempt, currentUserId = ACCOUNT_B, refreshedUserId = ACCOUNT_A),
        )
        assertFalse(
            "响应账号不匹配时不能清 dirty",
            hub.acknowledge(attempt, currentUserId = ACCOUNT_A, refreshedUserId = ACCOUNT_B),
        )
        assertNotNull("失败或错号后 dirty 必须保留", hub.pendingAttempt(ACCOUNT_A))

        assertTrue(
            hub.acknowledge(attempt, currentUserId = ACCOUNT_A, refreshedUserId = ACCOUNT_A),
        )
        assertNull("成功 ack 后才清 dirty", hub.pendingAttempt(ACCOUNT_A))
    }

    @Test
    fun `Surface 内请求期间再次 mutation 合并 wake 但更新 revision 且旧响应不能误清`() {
        val hub = ProfileRefreshHub()
        var wakeups = 0
        hub.addObserver { wakeups++ }
        hub.onEditProfileSurfaceVisibilityChanged(isVisible = true, currentUserId = ACCOUNT_A)
        hub.notifyProfileChanged(ACCOUNT_A)
        val first = requireNotNull(hub.pendingAttempt(ACCOUNT_A))

        hub.notifyProfileChanged(ACCOUNT_A)
        val latest = requireNotNull(hub.pendingAttempt(ACCOUNT_A))

        assertNotEquals(first, latest)
        assertEquals("dirty 期间连续 mutation 只唤醒一次", 1, wakeups)
        assertFalse(hub.acknowledge(first, ACCOUNT_A, ACCOUNT_A))
        assertEquals("只保留最新 dirty，不排 mutation 队列", latest, hub.pendingAttempt(ACCOUNT_A))
        assertTrue(hub.acknowledge(latest, ACCOUNT_A, ACCOUNT_A))
    }

    @Test
    fun `空账号不产生无归属 dirty`() {
        val hub = ProfileRefreshHub()
        var wakeups = 0
        hub.addObserver { wakeups++ }

        hub.notifyProfileChanged(null)
        hub.notifyProfileChanged("")

        assertEquals(0, wakeups)
        assertNull(hub.pendingAttempt(ACCOUNT_A))
    }

    @Test
    fun `登录换号或登出清掉旧账号状态`() {
        val hub = ProfileRefreshHub()
        hub.onEditProfileSurfaceVisibilityChanged(isVisible = true, currentUserId = ACCOUNT_A)
        hub.notifyProfileChanged(ACCOUNT_A)

        hub.onDidLogin(ACCOUNT_B)

        assertNull(hub.pendingAttempt(ACCOUNT_A))
        assertNull(hub.pendingAttempt(ACCOUNT_B))

        hub.notifyProfileChanged(ACCOUNT_B)
        hub.onDidLogout()

        assertNull(hub.pendingAttempt(ACCOUNT_B))
    }

    @Test
    fun `Surface 真正 close 即使早期已成功也生成最终刷新`() {
        val hub = ProfileRefreshHub()
        var wakeups = 0
        hub.addObserver { wakeups++ }
        hub.onEditProfileSurfaceVisibilityChanged(isVisible = true, currentUserId = ACCOUNT_A)
        hub.notifyProfileChanged(ACCOUNT_A)
        val early = requireNotNull(hub.pendingAttempt(ACCOUNT_A))
        assertTrue(hub.acknowledge(early, ACCOUNT_A, ACCOUNT_A))
        assertNull(hub.pendingAttempt(ACCOUNT_A))

        hub.onEditProfileSurfaceVisibilityChanged(isVisible = false, currentUserId = ACCOUNT_A)
        val closeAttempt = hub.pendingAttempt(ACCOUNT_A)

        assertNotNull("退栈必须再生成一次最终校准", closeAttempt)
        assertNotEquals(early, closeAttempt)
        assertEquals("mutation 与 close 各唤醒一次", 2, wakeups)

        hub.onEditProfileSurfaceVisibilityChanged(isVisible = false, currentUserId = ACCOUNT_A)
        assertEquals("重复 false 不是新的 close 沿", 2, wakeups)
    }

    @Test
    fun `底层仍 STARTED 首刷失败且 pop 不走 onStart 时 close 信号重试成功`() {
        val hub = ProfileRefreshHub()
        var underlayStarted = false
        var onStartCalls = 0
        val successCallbacks = mutableListOf<(String) -> Unit>()
        val failureCallbacks = mutableListOf<() -> Unit>()
        val coordinator = ProfileRefreshCoordinator(
            hub = hub,
            currentUserIdProvider = { ACCOUNT_A },
            isStarted = { underlayStarted },
            refresh = { onUserInfoRefreshed, onUserInfoRefreshFailed ->
                successCallbacks += onUserInfoRefreshed
                failureCallbacks += onUserInfoRefreshFailed
            },
            scheduleRetry = { retry -> retry() },
        )
        hub.addObserver(coordinator)

        // Profile 先正常启动一次；此时没有 dirty。
        underlayStarted = true
        onStartCalls++
        assertFalse(coordinator.onStart())
        hub.onEditProfileSurfaceVisibilityChanged(isVisible = true, currentUserId = ACCOUNT_A)

        hub.notifyProfileChanged(ACCOUNT_A)
        assertEquals("STARTED 的底层页立即发起首刷", 1, successCallbacks.size)
        // 模拟第一次 /user/info 失败。Surface 仍在，等待 close 做最终校准而不原地重试。
        failureCallbacks[0]()
        assertEquals(1, successCallbacks.size)
        assertNotNull("首刷失败后 dirty 不能提前清", hub.pendingAttempt(ACCOUNT_A))

        // sibling Surface pop：底层 Fragment 仍 STARTED，系统不会触发 onStart。
        hub.onEditProfileSurfaceVisibilityChanged(isVisible = false, currentUserId = ACCOUNT_A)

        assertTrue(underlayStarted)
        assertEquals("Surface pop 不依赖 Profile onStart", 1, onStartCalls)
        assertEquals("close 沿应直接再发一次", 2, successCallbacks.size)

        successCallbacks[1](ACCOUNT_A)
        assertNull("close-trigger retry 成功后才清 dirty", hub.pendingAttempt(ACCOUNT_A))

        // 迟到的首刷成功回调绑定旧 revision，不能改变已确认的新状态。
        successCallbacks[0](ACCOUNT_A)
        assertNull(hub.pendingAttempt(ACCOUNT_A))
    }

    @Test
    fun `先 close 后晚到通知首刷失败会有界重试且不依赖 onStart`() {
        val hub = ProfileRefreshHub()
        var started = false
        var onStartCalls = 0
        val successCallbacks = mutableListOf<(String) -> Unit>()
        val failureCallbacks = mutableListOf<() -> Unit>()
        val coordinator = ProfileRefreshCoordinator(
            hub = hub,
            currentUserIdProvider = { ACCOUNT_A },
            isStarted = { started },
            refresh = { success, failure ->
                successCallbacks += success
                failureCallbacks += failure
            },
            scheduleRetry = { retry -> retry() },
        )
        hub.addObserver(coordinator)
        started = true
        onStartCalls++
        assertFalse(coordinator.onStart())
        hub.onEditProfileSurfaceVisibilityChanged(isVisible = true, currentUserId = ACCOUNT_A)
        hub.onEditProfileSurfaceVisibilityChanged(isVisible = false, currentUserId = ACCOUNT_A)

        // 保存完成晚于退栈：此时已经没有下一次 close/onStart 可依赖。
        hub.notifyProfileChanged(ACCOUNT_A)
        assertEquals(1, successCallbacks.size)
        failureCallbacks[0]()

        assertEquals("失败 completion 应排一次 retry", 2, successCallbacks.size)
        assertEquals("retry 不是靠生命周期重入", 1, onStartCalls)
        successCallbacks[1](ACCOUNT_A)
        assertNull(hub.pendingAttempt(ACCOUNT_A))
    }

    @Test
    fun `Surface 已关闭时两个晚到通知都唤醒且旧响应不能清最新 revision`() {
        val hub = ProfileRefreshHub()
        val successCallbacks = mutableListOf<(String) -> Unit>()
        val coordinator = ProfileRefreshCoordinator(
            hub = hub,
            currentUserIdProvider = { ACCOUNT_A },
            isStarted = { true },
            refresh = { success, _ -> successCallbacks += success },
            scheduleRetry = { retry -> retry() },
        )
        hub.addObserver(coordinator)
        hub.onEditProfileSurfaceVisibilityChanged(isVisible = true, currentUserId = ACCOUNT_A)
        hub.onEditProfileSurfaceVisibilityChanged(isVisible = false, currentUserId = ACCOUNT_A)

        hub.notifyProfileChanged(ACCOUNT_A)
        hub.notifyProfileChanged(ACCOUNT_A)

        assertEquals("关闭后不能合并新 revision 的唤醒", 2, successCallbacks.size)
        successCallbacks[0](ACCOUNT_A)
        assertNotNull("旧响应不能 ack 最新 mutation", hub.pendingAttempt(ACCOUNT_A))
        successCallbacks[1](ACCOUNT_A)
        assertNull("最新 revision 成功才清 dirty", hub.pendingAttempt(ACCOUNT_A))
    }

    @Test
    fun `closeAttempt 在飞时晚到 mutation 再唤醒且两个旧响应都不能 ack`() {
        val hub = ProfileRefreshHub()
        val successCallbacks = mutableListOf<(String) -> Unit>()
        val coordinator = ProfileRefreshCoordinator(
            hub = hub,
            currentUserIdProvider = { ACCOUNT_A },
            isStarted = { true },
            refresh = { success, _ -> successCallbacks += success },
            scheduleRetry = { retry -> retry() },
        )
        hub.addObserver(coordinator)
        hub.onEditProfileSurfaceVisibilityChanged(isVisible = true, currentUserId = ACCOUNT_A)

        hub.notifyProfileChanged(ACCOUNT_A) // Surface 内的即时刷新
        hub.onEditProfileSurfaceVisibilityChanged(false, ACCOUNT_A) // closeAttempt
        hub.notifyProfileChanged(ACCOUNT_A) // pop 后才完成的慢 mutation

        assertEquals("晚到 mutation 必须形成第三次 wake", 3, successCallbacks.size)
        successCallbacks[0](ACCOUNT_A)
        assertNotNull(hub.pendingAttempt(ACCOUNT_A))
        successCallbacks[1](ACCOUNT_A)
        assertNotNull("closeAttempt 也已被晚到 mutation 淘汰", hub.pendingAttempt(ACCOUNT_A))
        successCallbacks[2](ACCOUNT_A)
        assertNull("只有晚到 mutation 的最新响应能 ack", hub.pendingAttempt(ACCOUNT_A))
    }

    @Test
    fun `Surface 已关闭时同一 revision 自动重试最多一次`() {
        val hub = ProfileRefreshHub()
        val failureCallbacks = mutableListOf<() -> Unit>()
        var refreshes = 0
        val coordinator = ProfileRefreshCoordinator(
            hub = hub,
            currentUserIdProvider = { ACCOUNT_A },
            isStarted = { true },
            refresh = { _, failure ->
                refreshes++
                failureCallbacks += failure
            },
            scheduleRetry = { retry -> retry() },
        )
        hub.addObserver(coordinator)

        hub.notifyProfileChanged(ACCOUNT_A)
        failureCallbacks[0]()
        failureCallbacks[1]()

        assertEquals("第二次失败不能形成紧循环", 2, refreshes)
        assertNotNull("重试耗尽仍保留 dirty 等下一事件", hub.pendingAttempt(ACCOUNT_A))
    }

    @Test
    fun `未 STARTED 时 dirty 留到 onStart 再刷新`() {
        val hub = ProfileRefreshHub()
        var started = false
        var refreshes = 0
        val coordinator = ProfileRefreshCoordinator(
            hub = hub,
            currentUserIdProvider = { ACCOUNT_A },
            isStarted = { started },
            refresh = { callback, _ ->
                refreshes++
                callback(ACCOUNT_A)
            },
            scheduleRetry = { retry -> retry() },
        )
        hub.addObserver(coordinator)

        hub.notifyProfileChanged(ACCOUNT_A)
        assertEquals(0, refreshes)
        assertNotNull(hub.pendingAttempt(ACCOUNT_A))

        started = true
        assertTrue(coordinator.onStart())
        assertEquals(1, refreshes)
        assertNull(hub.pendingAttempt(ACCOUNT_A))
    }

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
    }
}
