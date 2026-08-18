package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.screen.ScreenVisibility
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 大屏页三轴可见性（W4-P2）。
 *
 * 每条轴漏掉的后果都是「视频在不该播的时候继续播」并占着音频焦点，
 * 且**不报错、不崩溃** —— 用户只会觉得"这 App 怎么在后台出声"。
 * 三条轴的触发方式互不相同，所以这里把八种组合全钉住。
 */
class ScreenVisibilityTest {

    @Test
    fun `三条都成立才播`() {
        assertTrue(ScreenVisibility.isVisible(started = true, hidden = false, covered = false))
    }

    @Test
    fun `未 started 不播`() {
        // Activity 轴：退到桌面 / 进程未恢复
        assertFalse(ScreenVisibility.isVisible(started = false, hidden = false, covered = false))
    }

    @Test
    fun `被 hide 不播 —— 切 Tab 走的是这条`() {
        // ⚠️ TabHostFragment 用 show/hide 保状态（对齐 RN
        // detachInactiveScreens={false}），hide **不改生命周期状态**，
        // 所以 started 仍为 true。只看 started 的实现会在切 Tab 后继续播
        assertFalse(ScreenVisibility.isVisible(started = true, hidden = true, covered = false))
    }

    @Test
    fun `被 Surface 盖住不播 —— 这条既不 hidden 也不 stop`() {
        // ⚠️ surface_container 是 native_root_container 的 sibling，
        // 打开 ChatDetail / Create / Search / Settings / Login 时本页
        // **既不 hidden 也不 stop**。只看前两条轴的实现会在盖着 Surface 时后台播
        assertFalse(ScreenVisibility.isVisible(started = true, hidden = false, covered = true))
    }

    @Test
    fun `任意两条或三条同时不成立也不播`() {
        assertFalse(ScreenVisibility.isVisible(started = false, hidden = true, covered = false))
        assertFalse(ScreenVisibility.isVisible(started = false, hidden = false, covered = true))
        assertFalse(ScreenVisibility.isVisible(started = true, hidden = true, covered = true))
        assertFalse(ScreenVisibility.isVisible(started = false, hidden = true, covered = true))
    }
}
