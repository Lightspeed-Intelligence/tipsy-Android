package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.auth.Generations
import ai.lightspeed.tipsy.shell.user.CurrentUser
import ai.lightspeed.tipsy.shell.user.CurrentUserMirrorLike
import ai.lightspeed.tipsy.shell.user.CurrentUserStore
import ai.lightspeed.tipsy.shell.user.UserInfoSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `/user/info` 发布前的 auth generation / token subject 闸门。 */
class CurrentUserStoreTest {

    @Test
    fun `成功响应同时更新进程 store 发布共享快照并触发派生状态`() = runTest {
        val published = mutableListOf<String>()
        val updated = mutableListOf<String>()
        val store = CurrentUserStore(
            source = UserInfoSource { CurrentUser("u1", "Lee", null, null) },
            generations = Generations(),
            currentUserId = { "u1" },
            mirror = CurrentUserMirrorLike { user -> published += user.userId; true },
            onUserUpdated = { updated += it.userId },
            logWarn = { _, _ -> },
        )

        assertTrue(store.refresh())
        assertEquals("u1", store.current.value?.userId)
        assertEquals(listOf("u1"), published)
        assertEquals(listOf("u1"), updated)
    }

    @Test
    fun `请求期间换号时旧响应不进入内存 MMKV 或派生状态`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val generations = Generations()
        var currentUserId = "A"
        var mirrorWrites = 0
        var updates = 0
        val store = CurrentUserStore(
            source = UserInfoSource {
                started.complete(Unit)
                release.await()
                CurrentUser("A", "old", null, null)
            },
            generations = generations,
            currentUserId = { currentUserId },
            mirror = CurrentUserMirrorLike { mirrorWrites++; true },
            onUserUpdated = { updates++ },
            logWarn = { _, _ -> },
        )

        val refresh = async { store.refresh() }
        started.await()
        currentUserId = "B"
        generations.bumpAuth()
        release.complete(Unit)

        assertFalse(refresh.await())
        assertNull(store.current.value)
        assertEquals(0, mirrorWrites)
        assertEquals(0, updates)
    }

    @Test
    fun `响应 userId 与当前 token subject 不同即拒绝发布`() = runTest {
        var mirrorWrites = 0
        val store = CurrentUserStore(
            source = UserInfoSource { CurrentUser("A", "old", null, null) },
            generations = Generations(),
            currentUserId = { "B" },
            mirror = CurrentUserMirrorLike { mirrorWrites++; true },
            logWarn = { _, _ -> },
        )

        assertFalse(store.refresh())
        assertNull(store.current.value)
        assertEquals(0, mirrorWrites)
    }

    @Test
    fun `登录要求共享快照时镜像失败不得发布半登录状态`() = runTest {
        var updates = 0
        val store = CurrentUserStore(
            source = UserInfoSource { CurrentUser("u1", "Lee", null, null) },
            generations = Generations(),
            currentUserId = { "u1" },
            mirror = CurrentUserMirrorLike { false },
            onUserUpdated = { updates++ },
            logWarn = { _, _ -> },
        )

        assertFalse(store.refresh(requireSharedSnapshot = true))
        assertNull(store.current.value)
        assertEquals(0, updates)
    }

    @Test
    fun `普通刷新镜像失败仍发布 Native 用户`() = runTest {
        var updates = 0
        val store = CurrentUserStore(
            source = UserInfoSource { CurrentUser("u1", "Lee", null, null) },
            generations = Generations(),
            currentUserId = { "u1" },
            mirror = CurrentUserMirrorLike { false },
            onUserUpdated = { updates++ },
            logWarn = { _, _ -> },
        )

        assertTrue(store.refresh())
        assertEquals("u1", store.current.value?.userId)
        assertEquals(1, updates)
    }
}
