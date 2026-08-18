package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.chatlist.ChatMapCardLayout
import org.junit.Assert.assertArrayEquals
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
    fun `双取余：dividend 真为负时也必须环绕正确`() {
        // ⚠️ 早前这条测试是**假保护**：用 index=2/scrollX=-100 时
        // dividend = scrollX + baseX*(index+2.5) = -100 + 1458 = **+1358**，
        // 压根没走到负数分支 —— 删掉第二次 `%` 它照样绿。
        //
        // 要让 dividend 真为负，scrollX 必须 < -baseDistance。
        val baseX = 324f
        val itemCount = 5
        val index = 2
        val disOut = floatArrayOf(-100f, -50f, 0f, 50f, 100f)
        val baseDistance = baseX * (index + 2.5f)
        val negativeScrollX = -baseDistance - 200f // dividend = -200 < 0

        val t = ChatMapCardLayout.solve(index, negativeScrollX, itemCount, baseX, disOut)
        assertFalse("负 dividend 不得产生 NaN", t.scale.isNaN())
        assertFalse("负 dividend 不得产生 NaN", t.translateX.isNaN())

        // 关键断言：环绕是模 dis 的，所以 scrollX 与 scrollX + k*dis 必须**完全等价**。
        // 只取一次余时负 dividend 会得到负 distance → 插值落到端点，
        // 与 +k*dis 的结果不同 → 这条会挂
        val dis = baseX * itemCount
        for (k in 1..3) {
            val shifted = ChatMapCardLayout.solve(
                index,
                negativeScrollX + k * dis,
                itemCount,
                baseX,
                disOut,
            )
            assertEquals("scrollX 与 +${k}*dis 的 scale 必须相等", t.scale, shifted.scale, EPS)
            assertEquals(
                "scrollX 与 +${k}*dis 的 translateX 必须相等",
                t.translateX,
                shifted.translateX,
                EPS,
            )
            assertEquals("zIndex 也必须相等", t.zIndex, shifted.zIndex, EPS)
            assertEquals("visible 也必须一致", t.visible, shifted.visible)
        }
    }

    @Test
    fun `多个负 dividend 采样都落在有效弧段内`() {
        // 扫一圈：任何 scrollX（含大负值）解出的 scale 都必须在 ratio 数组范围内。
        // 只取一次余的实现会在负区间把 scale 压到端点 0，这里能抓到
        val baseX = 324f
        val disOut = floatArrayOf(-100f, -50f, 0f, 50f, 100f)
        var visibleCount = 0
        var x = -5000f
        while (x <= 5000f) {
            val t = ChatMapCardLayout.solve(1, x, 5, baseX, disOut)
            assertTrue("scale 越界: $t @ scrollX=$x", t.scale in 0f..1f)
            if (t.visible) visibleCount++
            x += 137f // 非整数步长，避免只采到整齐位置
        }
        assertTrue("扫描中应有可见样本", visibleCount > 0)
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
    fun `五卡模式稳定态是展开的`() {
        // processFive 默认 [-n5Dis1, -n5Dis2, 0, n5Dis2, n5Dis1]（`:155`）
        val out = ChatMapCardLayout.floorOffsets(5, 5, 0f, 1080, 518.4f, 691.2f)
        assertEquals(5, out.size)
        assertEquals("中间那张不偏移", 0f, out[2], EPS)
        assertEquals("外侧对称", -out[0], out[4], EPS)
        assertEquals("内侧对称", -out[1], out[3], EPS)
        assertTrue("外侧偏移量应大于内侧", Math.abs(out[0]) > Math.abs(out[1]))
    }

    @Test
    fun `三卡模式的稳定态是展开而不是收拢`() {
        // ⚠️ 这条守的是一个我写错过的实现：processThree 的默认值是
        // **[-n5Dis2, -n5Dis2, 0, n5Dis2, n5Dis2]**（`TipsyCarousel.tsx:135`），
        // 不是全 0、也不是"随 yRatio 收拢到 0"。
        // 写成收拢的话 3 卡模式在稳定态会把卡片全叠到中间。
        val out = ChatMapCardLayout.floorOffsets(3, 3, 0f, 1080, 518.4f, 691.2f)
        val n5Dis2 = 0.3f * 1080 + (0.2f - 0.5f * 0.86f) * 518.4f
        assertEquals(-n5Dis2, out[0], EPS)
        assertEquals(-n5Dis2, out[1], EPS)
        assertEquals(0f, out[2], EPS)
        assertEquals(n5Dis2, out[3], EPS)
        assertEquals(n5Dis2, out[4], EPS)
        // 三卡模式下内外两侧偏移量**相等**（与五卡不同）
        assertEquals("3 卡模式内外侧偏移相等", Math.abs(out[0]), Math.abs(out[1]), EPS)
    }

    @Test
    fun `三到三即使 yRatio 非零也保持默认展开`() {
        // ⚠️ RN 的 processThree 只在 nextI==5 或 nextI==1 时改 disOut，
        // **3→3 走 else 保持默认**（`:137-146`）。
        // 早前实现忽略 nextMode，导致 3→3 被错误地收拢
        val stable = ChatMapCardLayout.floorOffsets(3, 3, 0f, 1080, 518.4f, 691.2f)
        val moving = ChatMapCardLayout.floorOffsets(3, 3, 300f, 1080, 518.4f, 691.2f)
        assertArrayEquals("3→3 不随 yRatio 变化", stable, moving, EPS)
    }

    @Test
    fun `三到五外侧张开到 n5Dis1`() {
        // processThree 的 nextI==5 分支（`:138-141`）：
        // dis2 从 n5Dis2 插值到 n5Dis1（yRatio 1→0）
        val n5Dis1 = (1080 - 518.4f * 0.74f) * 0.5f
        val n5Dis2 = 0.3f * 1080 + (0.2f - 0.5f * 0.86f) * 518.4f
        // yRatio = 0 端（offsetY 很小但非 0）→ 外侧接近 n5Dis1
        val nearZero = ChatMapCardLayout.floorOffsets(3, 5, 0.001f, 1080, 518.4f, 691.2f)
        assertEquals("外侧张到 n5Dis1", n5Dis1, Math.abs(nearZero[4]), 1f)
        assertEquals("内侧保持 n5Dis2", n5Dis2, Math.abs(nearZero[3]), 1f)
        // yRatio = 1 端 → 内外都是 n5Dis2
        val atOne = ChatMapCardLayout.floorOffsets(3, 5, 691.2f, 1080, 518.4f, 691.2f)
        assertEquals(n5Dis2, Math.abs(atOne[4]), EPS)
        assertEquals(n5Dis2, Math.abs(atOne[3]), EPS)
    }

    @Test
    fun `三到一收拢到零`() {
        // processThree 的 nextI==1 分支（`:142-145`）：
        // dis1 = interpolate(1-yRatio, [0,1], [0, n5Dis2])
        // yRatio=1 → 1-yRatio=0 → dis1=0（完全收拢）
        val collapsed = ChatMapCardLayout.floorOffsets(3, 1, 691.2f, 1080, 518.4f, 691.2f)
        assertEquals(0f, collapsed[0], EPS)
        assertEquals(0f, collapsed[4], EPS)
        // yRatio 接近 0 → 接近 n5Dis2（还没收）
        val n5Dis2 = 0.3f * 1080 + (0.2f - 0.5f * 0.86f) * 518.4f
        val open = ChatMapCardLayout.floorOffsets(3, 1, 0.001f, 1080, 518.4f, 691.2f)
        assertEquals(n5Dis2, Math.abs(open[4]), 1f)
    }

    @Test
    fun `五到三外侧收到 n5Dis2`() {
        // processFive 的 nextI==3 分支（`:157-161`）
        val n5Dis2 = 0.3f * 1080 + (0.2f - 0.5f * 0.86f) * 518.4f
        val atOne = ChatMapCardLayout.floorOffsets(5, 3, 691.2f, 1080, 518.4f, 691.2f)
        assertEquals("外侧收到 n5Dis2", n5Dis2, Math.abs(atOne[4]), EPS)
        assertEquals(n5Dis2, Math.abs(atOne[3]), EPS)
    }

    @Test
    fun `单卡模式只在 next 为三时展开`() {
        // processOne（`:117-129`）：默认全 0；只有 yRatio≠0 且 nextMode==3 才展开
        ChatMapCardLayout.floorOffsets(1, 1, 0f, 1080, 518.4f, 691.2f)
            .forEach { assertEquals(0f, it, EPS) }
        // nextMode=1 即使 yRatio≠0 也不展开
        ChatMapCardLayout.floorOffsets(1, 1, 300f, 1080, 518.4f, 691.2f)
            .forEach { assertEquals(0f, it, EPS) }
        // nextMode=3 且 yRatio≠0 → 展开
        val expanded = ChatMapCardLayout.floorOffsets(1, 3, 300f, 1080, 518.4f, 691.2f)
        assertTrue("1→3 应展开", Math.abs(expanded[4]) > 0f)
    }

    @Test
    fun `yRatio 的分母是卡高不是行高`() {
        // 对齐 `TipsyCarousel` 的 `offsetY.value / height`，其中 height 是
        // itemSize.height = **卡高**（`ChatMap.tsx:278` 传的 itemSize）。
        // 用行高会让转场进度整体错 —— 画面仍会动，不容易看出来。
        //
        // 验法：3→1 分支在 yRatio=1 时应完全收拢到 0。
        // 若分母误用行高（约 cardHeight*0.43），传 cardHeight 时 yRatio 会 >1，
        // interpolate 被 clamp 后仍是 0 —— 所以要从**未收拢端**验：
        // 传 cardHeight/2 时 yRatio=0.5，收拢量应恰好是 n5Dis2 的一半
        val cardHeight = 691.2f
        val n5Dis2 = 0.3f * 1080 + (0.2f - 0.5f * 0.86f) * 518.4f
        val half = ChatMapCardLayout.floorOffsets(3, 1, cardHeight / 2f, 1080, 518.4f, cardHeight)
        assertEquals(
            "yRatio=0.5 时收拢量应为 n5Dis2 的一半（分母必须是卡高）",
            n5Dis2 * 0.5f,
            Math.abs(half[4]),
            0.5f,
        )
    }

    @Test
    fun `Stops 复用后热路径结果与便利重载一致`() {
        // 热路径版（UI 每帧走这个，横轴由 remember 复用）与便利版必须等价。
        // ⚠️ 性能动机：便利版每次新建 2 个 stop 数组 —— 一层 5 张 × 可见 5 层
        // = 每帧 50 个短命数组，低端机上表现为滚动 GC 抖动（掉帧，不崩）
        val baseX = 324f
        val disOut = floatArrayOf(-100f, -50f, 0f, 50f, 100f)
        val stops = ChatMapCardLayout.stopsFor(baseX)
        var x = -2000f
        while (x <= 2000f) {
            val convenient = ChatMapCardLayout.solve(1, x, 5, baseX, disOut)
            val hot = ChatMapCardLayout.solve(1, x, 5, stops, disOut)
            assertEquals(convenient, hot)
            x += 211f
        }
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
