package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.chatlist.ChatMapFloors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Map 楼层列表构建（W3-P2）。
 *
 * ⚠️ 前两条又是**反向测试**：列表构建里还有两处 `smallScreen` 分支
 * （`ChatMap.tsx:336` 的 `emptySize` 与 `:344-352` 的尾部占位层数），
 * Android 恒走非 small。照 iOS 抄会少铺一层空占位 —— 表现是廊道最上方
 * 少一层过渡、滚到顶时最后一组卡位置不对，而这类偏差没人会报。
 */
class ChatMapFloorsTest {

    private val id: (String) -> String = { it }

    @Test
    fun `补齐目标是三组而不是小屏的两组`() {
        // 只有 1 组真实数据 → 补到 3 组，再加 2 个尾部占位 = 5 层。
        // 小屏那条是补到 2 组 + 1 个尾部 = 3 层，若被改成小屏值这里会挂
        val floors = ChatMapFloors.build(listOf("Today" to listOf("a")), id)
        assertEquals("1 真实组 → 3 组 + 2 尾部 = 5 层", 5, floors.size)
        assertEquals(ChatMapFloors.EMPTY_TARGET_GROUPS, 3)
    }

    @Test
    fun `尾部空占位恒为两层`() {
        val floors = ChatMapFloors.build(
            listOf("Today" to listOf("a"), "Yesterday" to listOf("b"), "Mon" to listOf("c")),
            id,
        )
        // 3 组真实（不触发补齐）+ 2 尾部 = 5
        assertEquals(5, floors.size)
        assertEquals("empty1", floors[3].key)
        assertEquals("empty2", floors[4].key)
        assertTrue(floors[3].isEmpty)
        assertTrue(floors[4].isEmpty)
        assertEquals(ChatMapFloors.TRAILING_EMPTY_FLOORS, 2)
    }

    @Test
    fun `三组或以上不触发补齐`() {
        val four = (1..4).map { "D$it" to listOf("x$it") }
        val floors = ChatMapFloors.build(four, id)
        // RN `:337` 是 `realSize < 3` 才补 —— 4 组不补，只加尾部
        assertEquals(6, floors.size)
        assertEquals("前四层是真实组", 4, floors.count { !it.isEmpty })
    }

    @Test
    fun `只有 Today 和 Yesterday 过 i18n`() {
        val calls = mutableListOf<String>()
        val localize: (String) -> String = { calls += it; "L($it)" }
        val floors = ChatMapFloors.build(
            listOf("Today" to listOf("a"), "Yesterday" to listOf("b"), "Aug 12" to listOf("c")),
            localize,
        )
        assertEquals("L(Today)", floors[0].title)
        assertEquals("L(Yesterday)", floors[1].title)
        // ⚠️ 日期串不过 localize —— 全过会把日期当词条 key，
        // 查不到回落原文（看起来没事，但词条表会留一堆假 key）
        assertEquals("Aug 12", floors[2].title)
        assertEquals(listOf("Today", "Yesterday"), calls)
    }

    @Test
    fun `空输入也铺满层`() {
        val floors = ChatMapFloors.build(emptyList(), id)
        // 0 真实组 → 补 3 空组 + 2 尾部 = 5 层，全空
        assertEquals(5, floors.size)
        assertTrue(floors.all { it.isEmpty })
    }

    @Test
    fun `currIndex 的 0_5px 容差挡住浮点噪声`() {
        val rowHeight = 300
        // ⚠️ 初始/吸附后 scrollY 可能是 -0.0x 的浮点噪声。
        // 裸 floor(-0.04/300) = floor(-0.000133) = -1 → 错位一整行；
        // 加 0.5 后 floor(0.4987) = 0 ✓
        assertEquals("负浮点噪声不得错位", 0, ChatMapFloors.currIndexFor(-0.04f, rowHeight, 0))
        assertEquals(0, ChatMapFloors.currIndexFor(0f, rowHeight, 0))
        // 正常滚动仍然分档正确
        assertEquals(1, ChatMapFloors.currIndexFor(300f, rowHeight, 0))
        assertEquals(2, ChatMapFloors.currIndexFor(600f, rowHeight, 0))
        // floorIndex 叠加
        assertEquals(3, ChatMapFloors.currIndexFor(300f, rowHeight, 2))
        // rowHeight=0 不除零
        assertEquals(2, ChatMapFloors.currIndexFor(100f, 0, 2))
    }

    @Test
    fun `楼层可见范围与标题淡出`() {
        assertTrue(ChatMapFloors.isFloorVisible(-1))
        assertTrue(ChatMapFloors.isFloorVisible(0))
        assertTrue(ChatMapFloors.isFloorVisible(3))
        assertFalse("低于 -1 整层隐藏", ChatMapFloors.isFloorVisible(-2))
        assertFalse("高于 3 整层隐藏", ChatMapFloors.isFloorVisible(4))

        assertFalse(ChatMapFloors.isTitleHidden(1))
        assertTrue(ChatMapFloors.isTitleHidden(2))
        assertTrue(ChatMapFloors.isTitleHidden(3))
    }
}
