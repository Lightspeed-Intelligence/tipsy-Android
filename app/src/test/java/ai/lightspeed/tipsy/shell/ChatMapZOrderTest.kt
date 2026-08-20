package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.chatlist.ChatMapZOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Map 两级层序的**规则**（W3-P2）。
 *
 * ⚠️ 这里钉的是**规则**，不是"接线接对了" —— 语义树不暴露 zIndex，
 * 只有截图比对能验绘制顺序。接线由 review 保证，见 `ChatMapZOrder` 类注释。
 */
class ChatMapZOrderTest {

    @Test
    fun `楼层层序倒置 —— index 0 最新必须画最上面`() {
        // index 0 铺在最底部但要画最上；不倒置的话远层会盖住近层
        assertTrue("index 0 必须高于 index 1", ChatMapZOrder.floorZ(0) > ChatMapZOrder.floorZ(1))
        assertTrue(ChatMapZOrder.floorZ(1) > ChatMapZOrder.floorZ(4))
        assertEquals(100f, ChatMapZOrder.floorZ(0), EPS)
        assertEquals(96f, ChatMapZOrder.floorZ(4), EPS)
    }

    @Test
    fun `楼层层序在最坏楼层数下仍不为负`() {
        // 楼层数不是小常数：所有日期桶都保留，首 50 条跨 50 天就是 52 层
        assertTrue("52 层时仍应为正", ChatMapZOrder.floorZ(51) > 0f)
    }

    @Test
    fun `卡片层序倒置 —— 真实卡必须高于其后的占位卡`() {
        // ⚠️ 这条守的是「1 真卡 + 4 占位」时占位完全盖住真卡：
        // repeat 升序 compose，占位排在真实卡之后、同尺寸同位置
        val slotCount = 5
        val realCardZ = ChatMapZOrder.cardZ(slot = 0, slotCount = slotCount)
        val lastPlaceholderZ = ChatMapZOrder.cardZ(slot = 4, slotCount = slotCount)
        assertTrue(
            "slot0（真实卡）必须高于 slot4（末位占位）",
            realCardZ > lastPlaceholderZ,
        )
        assertEquals(5f, realCardZ, EPS)
        assertEquals(1f, lastPlaceholderZ, EPS)
    }

    @Test
    fun `卡片层序对同日超过五条也单调递减`() {
        // 同日超过 5 条全部保留（不截断），层序仍要单调
        val slotCount = 9
        val z = (0 until slotCount).map { ChatMapZOrder.cardZ(it, slotCount) }
        assertEquals(z, z.sortedDescending())
        assertTrue("末位仍为正", z.last() > 0f)
    }

    private companion object {
        const val EPS = 1e-3f
    }
}
