package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.profile.profileListBottomPaddingDp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Profile 短列表仍可滚动、且最后一行不被覆盖式 TabBar 挡住的布局护栏。 */
class ProfileLayoutTest {

    @Test
    fun `375dp 无系统底边时保留 RN 400 加完整 TabBar`() {
        // RN 页面余量 400 + TabBar 内容 48 + 无手势条设备固定底边 24。
        assertEquals(
            472f,
            profileListBottomPaddingDp(safeBottomDp = 0f, scaleFactor = 1f),
            0.01f,
        )
    }

    @Test
    fun `宽屏和手势条分别遵循 RN 缩放与 TabBar inset 规则`() {
        val scale = 1.2f
        // RN: (400 + 30) * 1.2 = 516
        // TabBar: 48 * 1.2 + (30 + 16 * 1.2) = 106.8
        assertEquals(
            622.8f,
            profileListBottomPaddingDp(safeBottomDp = 30f, scaleFactor = scale),
            0.01f,
        )
    }
}
