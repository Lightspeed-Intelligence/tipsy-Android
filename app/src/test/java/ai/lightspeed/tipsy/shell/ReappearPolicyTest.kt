package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.surface.ReappearPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `onSurfaceReappeared` 发射判定（W1 §12.3）。
 *
 * 两个错法都不报错，只表现为数据刷新行为怪异 —— 所以必须用测试钉死。
 */
class ReappearPolicyTest {

    /**
     * 首次 onResume **不发**。
     * 发了的症状：每次打开页面都多拉一次数据（首帧刚出来就重拉）。
     */
    @Test
    fun `首次 onResume 不发事件`() {
        assertFalse(ReappearPolicy.shouldEmit(hasResumedOnce = false))
    }

    /**
     * 非首次 onResume **要发**。这正是本事件存在的理由：
     * 壳内经桥跳出再返回时 RN 全程 focused，`useFocusEffect` 不重触发，
     * 「去做任务、回来领取」类页面不刷新。
     */
    @Test
    fun `非首次 onResume 发事件`() {
        assertTrue(ReappearPolicy.shouldEmit(hasResumedOnce = true))
    }

    /**
     * 无 saved state = 真正的首次创建。
     * 若默认成 true，每次打开都会多发一次事件。
     */
    @Test
    fun `无 saved state 时视为首次`() {
        assertFalse(ReappearPolicy.restoreHasResumed(null))
    }

    /**
     * ⚠️ **进程重建**后标记必须从 saved state 恢复。
     *
     * 注意不是旋转 —— `MainActivity` 的 `configChanges` 已含 `orientation`
     * （manifest:52），转屏不重建 Fragment。真正走这条路的是「App 在后台被系统
     * 杀掉、用户从最近任务返回」。
     *
     * 丢了的症状：切后台一会儿再回来，页面莫名多拉一次数据。
     */
    @Test
    fun `saved state 为 true 时保留 防止进程重建后误判为首次`() {
        assertTrue(
            "进程重建后必须记得已经 resume 过，否则会误发事件 → 多拉一次数据",
            ReappearPolicy.restoreHasResumed(true),
        )
    }

    @Test
    fun `saved state 为 false 时保持 false`() {
        assertFalse(ReappearPolicy.restoreHasResumed(false))
    }

    /**
     * 完整时序：首次打开 → 跳出再回来 → 进程重建后回来。
     * 关键是**首次不发**，且进程重建不把状态重置成「首次」。
     */
    @Test
    fun `完整时序首次不发 后续都发`() {
        val emissions = mutableListOf<String>()

        // 首次创建 + onResume
        var hasResumed = ReappearPolicy.restoreHasResumed(null)
        if (ReappearPolicy.shouldEmit(hasResumed)) emissions.add("首次")
        hasResumed = true

        // 壳内跳出后返回 → 再次 onResume（这正是本事件要覆盖的场景）
        if (ReappearPolicy.shouldEmit(hasResumed)) emissions.add("返回")

        // 进程重建：Fragment 带 saved state 重建
        hasResumed = ReappearPolicy.restoreHasResumed(hasResumed)
        if (ReappearPolicy.shouldEmit(hasResumed)) emissions.add("进程重建后")

        assertFalse("首次不该发", "首次" in emissions)
        assertTrue("跳出返回必须发", "返回" in emissions)
        assertTrue(
            "进程重建后仍算「重新出现」—— 关键是没被误判成首次而漏发",
            "进程重建后" in emissions,
        )
    }
}
