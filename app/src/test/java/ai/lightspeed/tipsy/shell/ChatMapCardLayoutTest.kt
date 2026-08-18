package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.chatlist.ChatMapCardLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Map 卡叠单卡解算（W3-P2）。
 *
 * 解算逻辑与 RN `TipsyCarousel.tsx` / iOS `ChatMapCarouselView.swift` 三端一致；
 * 帧驱动机制不同（Compose 用重组、iOS 用 CADisplayLink），但**数学一致**，
 * 所以这里能在 JVM 上把它钉死。
 */
class ChatMapCardLayoutTest {

    @Test
    fun `zIndex 最后一档是乘二三分之而不是取中点`() {
        // ⚠️ RN 原文：`scale >= (RATIO0 + RATIO1) * (2 / 3)`（TipsyCarousel.tsx:272）
        // —— 与前两档的取中点写法**不同**。iOS 也照搬了。
        // "顺手改成 /2 更整齐"会改变卡片前后遮挡关系
        val threshold = (0f + 0.74f) * (2f / 3f) // ≈ 0.4933
        assertEquals(2f, ChatMapCardLayout.zIndexFor(threshold), EPS)
        assertEquals(1f, ChatMapCardLayout.zIndexFor(threshold - 0.01f), EPS)
        // 若被改成 /2（=0.37），0.4 会错判成 2；正确实现下 0.4 < 0.4933 → 1
        assertEquals("0.4 必须是 1 档（阈值是 *2/3 不是 /2）", 1f, ChatMapCardLayout.zIndexFor(0.4f), EPS)
    }

    @Test
    fun `zIndex 其余分档`() {
        assertEquals(5f, ChatMapCardLayout.zIndexFor(1f), EPS)
        assertEquals(5f, ChatMapCardLayout.zIndexFor(1.2f), EPS)
        assertEquals(4f, ChatMapCardLayout.zIndexFor(0.93f), EPS) // (0.86+1)/2 = 0.93
        assertEquals(3f, ChatMapCardLayout.zIndexFor(0.8f), EPS) // (0.74+0.86)/2 = 0.80
        assertEquals(1f, ChatMapCardLayout.zIndexFor(0f), EPS)
    }

    @Test
    fun `distance 环绕对负 scrollX 也正确`() {
        // ⚠️ ((x % dis) + dis) % dis 的两次取余不能省 ——
        // Kotlin 的 % 对负数返回负值（与 JS 一致），只取一次会让 scrollX 为负时
        // distance 变负 → 插值落到端点，卡片全挤在一起
        val baseX = 324f
        val disOut = floatArrayOf(-100f, -50f, 0f, 50f, 100f)
        val positive = ChatMapCardLayout.solve(2, 100f, 5, baseX, disOut)
        val negative = ChatMapCardLayout.solve(2, -100f, 5, baseX, disOut)
        // 两个方向都必须解出可见且有限的结果
        assertTrue(positive.visible)
        assertTrue(negative.visible)
        assertFalse("负 scrollX 不得产生 NaN", negative.scale.isNaN())
        assertFalse("负 scrollX 不得产生 NaN", negative.translateX.isNaN())
        assertTrue("scale 必须在 ratio 数组范围内", negative.scale in 0f..1f)
    }

    @Test
    fun `超出可见弧段的卡不绘制`() {
        val baseX = 100f
        val disOut = floatArrayOf(0f, 0f, 0f, 0f, 0f)
        // itemCount 大时会有卡落在 distance > baseX*5.5 的区间
        var foundHidden = false
        for (i in 0 until 12) {
            val t = ChatMapCardLayout.solve(i, 0f, 12, baseX, disOut)
            if (!t.visible) {
                foundHidden = true
                assertEquals("不可见卡的 scale 必须是 0", 0f, t.scale, EPS)
            }
        }
        assertTrue("12 张卡里应有落在可见弧段外的", foundHidden)
    }

    @Test
    fun `楼层模式 getIndex 的 4 与 2 归 3`() {
        // sel: currIndex ∈ {-1,0} → 5；1 → 3；其余 → 1，再 min(itemCount, sel)
        assertEquals(5, ChatMapCardLayout.floorMode(0, 5))
        assertEquals(5, ChatMapCardLayout.floorMode(-1, 5))
        assertEquals(3, ChatMapCardLayout.floorMode(1, 5))
        assertEquals(1, ChatMapCardLayout.floorMode(2, 5))
        // ⚠️ min 结果为 4 或 2 时归 3 —— 不归的话卡叠会用一套不存在的模式
        assertEquals("itemCount=4 时 min=4 → 归 3", 3, ChatMapCardLayout.floorMode(0, 4))
        assertEquals("itemCount=2 时 min=2 → 归 3", 3, ChatMapCardLayout.floorMode(0, 2))
        assertEquals("itemCount=1 保持 1", 1, ChatMapCardLayout.floorMode(0, 1))
        assertEquals("itemCount=3 保持 3", 3, ChatMapCardLayout.floorMode(0, 3))
    }

    @Test
    fun `五卡模式的横向偏移左右对称`() {
        val out = ChatMapCardLayout.floorOffsets(
            mode = 5,
            nextMode = 5,
            offsetY = 0f,
            windowWidth = 1080,
            cardWidth = 518.4f,
            cardHeight = 691.2f,
        )
        assertEquals(5, out.size)
        assertEquals("中间那张不偏移", 0f, out[2], EPS)
        assertEquals("外侧对称", -out[0], out[4], EPS)
        assertEquals("内侧对称", -out[1], out[3], EPS)
        assertTrue("外侧偏移量应大于内侧", Math.abs(out[0]) > Math.abs(out[1]))
    }

    @Test
    fun `单卡模式在未展开时全零`() {
        // processOne：yRatio==0 或 nextMode!=3 时恒 0（不横向展开）
        val out = ChatMapCardLayout.floorOffsets(
            mode = 1,
            nextMode = 1,
            offsetY = 0f,
            windowWidth = 1080,
            cardWidth = 518.4f,
            cardHeight = 691.2f,
        )
        assertEquals(5, out.size)
        out.forEach { assertEquals(0f, it, EPS) }
    }

    @Test
    fun `yRatio 用卡高而不是行高`() {
        // 对齐 TipsyCarousel 的 offsetY / itemSize.height（卡高）。
        // 用行高会让展开动画的进度整体错 —— 画面仍会动，不容易看出来
        val cardHeight = 691.2f
        val a = ChatMapCardLayout.floorOffsets(3, 3, cardHeight / 2f, 1080, 518.4f, cardHeight)
        val b = ChatMapCardLayout.floorOffsets(3, 3, cardHeight, 1080, 518.4f, cardHeight)
        // yRatio 从 0.5 → 1.0，插值输出 [n5Dis2, 0] 递减 → 偏移量应变小
        assertTrue("yRatio 越大偏移越小", Math.abs(a[4]) > Math.abs(b[4]))
        assertEquals("yRatio=1 时收拢到 0", 0f, b[4], EPS)
    }

    @Test
    fun `退化输入不崩`() {
        val disOut = floatArrayOf(0f, 0f, 0f, 0f, 0f)
        assertFalse("itemCount=0 不可见", ChatMapCardLayout.solve(0, 0f, 0, 324f, disOut).visible)
        // disOut 长度不对 → 退化成恒等而不是抛
        val bad = ChatMapCardLayout.solve(0, 0f, 5, 324f, floatArrayOf(1f, 2f))
        assertTrue(bad.visible)
        assertEquals(1f, bad.scale, EPS)
        // cardHeight=0 不除零
        val out = ChatMapCardLayout.floorOffsets(3, 3, 100f, 1080, 518.4f, 0f)
        out.forEach { assertFalse(it.isNaN()) }
    }

    private companion object {
        const val EPS = 1e-3f
    }
}
