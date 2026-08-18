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
        assertEquals(ChatMapFloors.FloorKind.RUNWAY, floors[3].kind)
        assertEquals(ChatMapFloors.FloorKind.RUNWAY, floors[4].kind)
        assertEquals(ChatMapFloors.TRAILING_EMPTY_FLOORS, 2)
    }

    @Test
    fun `三组或以上不触发补齐`() {
        val four = (1..4).map { "D$it" to listOf("x$it") }
        val floors = ChatMapFloors.build(four, id)
        // RN `:337` 是 `realSize < 3` 才补 —— 4 组不补，只加尾部
        assertEquals(6, floors.size)
        assertEquals("前四层是 CHAT", 4, floors.count { it.kind == ChatMapFloors.FloorKind.CHAT })
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
        val floors = ChatMapFloors.build<String>(emptyList(), id)
        // 0 真实组 → 补 3 空组 + 2 尾部 = 5 层，全空
        assertEquals(5, floors.size)
        // ⚠️ 全空输入下**前 3 层仍是 CHAT**（补齐出来的空分组），只有尾部 2 层是 RUNWAY。
        // 两者渲染不同：CHAT 补 5 张剪影，RUNWAY 零张卡
        assertEquals(3, floors.count { it.kind == ChatMapFloors.FloorKind.CHAT })
        assertEquals(2, floors.count { it.kind == ChatMapFloors.FloorKind.RUNWAY })
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
    fun `补齐的 chat 层补五张剪影，runway 层零张卡`() {
        // ⚠️ 这条守的是我合并过的两种「空」：
        // RN `ChatMap.tsx:196-198` 对 type==='empty' **直接 return []**（零张卡），
        // 而 chat 层走 `:205-212` **补到 5 张**剪影。
        // 合成一个 isEmpty 的后果：廊道顶部两层多画 10 张剪影，或补齐层一张不画
        val floors = ChatMapFloors.build(listOf("Today" to listOf("a")), id)
        // 第 0 层：1 真实 + 4 剪影 = 5 槽
        assertEquals(5, floors[0].slotCount)
        // 第 1、2 层：补齐出来的 CHAT，0 真实但仍要 5 槽剪影
        assertEquals(ChatMapFloors.FloorKind.CHAT, floors[1].kind)
        assertEquals("补齐的 chat 层也要 5 槽剪影", 5, floors[1].slotCount)
        assertEquals(5, floors[2].slotCount)
        // 尾部 runway：**零槽**
        assertEquals(ChatMapFloors.FloorKind.RUNWAY, floors[3].kind)
        assertEquals("runway 必须零张卡", 0, floors[3].slotCount)
        assertEquals(0, floors[4].slotCount)
    }

    @Test
    fun `同日超过五条不截断`() {
        // ⚠️ RN 是 `if (len < 5)` **补位**（`:205`），不是上限。
        // 7 条同日会话要全部保留 —— UI 也不得 take(5)
        val seven = (1..7).map { "c$it" }
        val floors = ChatMapFloors.build(listOf("Today" to seven), id)
        assertEquals("真实会话全保留", 7, floors[0].items.size)
        assertEquals("槽位数 = 真实数，不截断到 5", 7, floors[0].slotCount)
        // 补位下限对 <5 才生效
        assertEquals(5, ChatMapFloors.carouselSlots(0))
        assertEquals(5, ChatMapFloors.carouselSlots(3))
        assertEquals(5, ChatMapFloors.carouselSlots(5))
        assertEquals(9, ChatMapFloors.carouselSlots(9))
    }

    @Test
    fun `分组内顺序与接口顺序一致，且不受草稿混排影响`() {
        // ⚠️ 上游必须喂 threads（接口累计顺序），不是 sortedThreads。
        // RN 侧 Grid 与 Map 拿同一个 recentChatList（chatList/index.tsx:113 与 :126），
        // 草稿混排是 Grid 的**渲染规则**，不进数据源。
        // 喂 sortedThreads 会让有草稿的会话跳到别的日期分组
        val ordered = listOf("t1", "t2", "t3", "t4")
        val floors = ChatMapFloors.build(listOf("Today" to ordered), id)
        assertEquals("分组内必须保持传入顺序", ordered, floors[0].items)
    }

    @Test
    fun `分页追加只在尾部增长，不重排既有层`() {
        val page1 = listOf("Today" to listOf("a", "b"), "Yesterday" to listOf("c"))
        val page2 = page1 + listOf("Aug 12" to listOf("d"))
        val f1 = ChatMapFloors.build(page1, id)
        val f2 = ChatMapFloors.build(page2, id)
        // 既有分组的内容与顺序不得变
        assertEquals(f1[0].items, f2[0].items)
        assertEquals(f1[1].items, f2[1].items)
        // ⚠️ key 必须稳定：page1 时第 2 层是补齐层（key=pad2），
        // page2 时变成真实的 Aug 12（key=Aug 12）—— 用下标做 key 就会
        // 把补齐层的横滑状态复用给 Aug 12 那天
        assertEquals("Today", f1[0].key)
        assertEquals("Today", f2[0].key)
        assertTrue("补齐层 key 与真实层 key 不得相同", f1[2].key != f2[2].key)
    }

    @Test
    fun `key 用未本地化的 bucket 而不是下标或标题`() {
        val localize: (String) -> String = { "译($it)" }
        val floors = ChatMapFloors.build(
            listOf("Today" to listOf("a"), "Aug 12" to listOf("b")),
            localize,
        )
        // key 是 bucket 原文 —— 切语言时 key 不变（标题变）
        assertEquals("Today", floors[0].key)
        assertEquals("Aug 12", floors[1].key)
        assertEquals("译(Today)", floors[0].title)
        // ⚠️ 若 key 用了本地化后的标题，切语言会让所有 key 变化 → 卡叠状态全丢
        assertTrue("key 不得等于本地化标题", floors[0].key != floors[0].title)
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
