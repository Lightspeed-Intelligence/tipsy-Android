package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.screen.ScreenPlayerLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 有界池账本的四条不变量（W4-P2）。
 *
 * 这四条错了**都不报错**，只表现为「反复进出大屏页后视频不再播」
 * （解码器泄漏）或比预期更早 OOM。Media3 接线那半只能真机验，
 * 见 `ScreenPlayerPool` 与 PR 的冒烟段。
 */
class ScreenPlayerLedgerTest {

    /** 载荷替身：只需要 identity，不需要真 ExoPlayer。 */
    private class FakePlayer(val id: Int)

    private var created = 0
    private fun ledger(capacity: Int) = ScreenPlayerLedger<FakePlayer>(capacity)
    private fun make(): FakePlayer = FakePlayer(created++)

    @Test
    fun `借到容量上限后拒绝而不是新建`() {
        val l = ledger(3)
        repeat(3) { assertNotNull(l.borrow(::make)) }
        assertEquals(3, l.borrowedCount)

        // 第 4 个必须是 null —— 调用方据此降级封面图。
        // 若这里返回实例，池就没有上界了（OOM 的直接来源）
        assertNull("池满必须拒绝", l.borrow(::make))
        assertEquals("拒绝不得改变账面", 3, l.borrowedCount)
        assertEquals("拒绝不得新建实例", 3, created)
    }

    @Test
    fun `归还后可再借且复用同一实例`() {
        val l = ledger(2)
        val a = l.borrow(::make)!!
        assertEquals(ScreenPlayerLedger.Recycle.ACCEPTED, l.recycle(a))
        assertEquals(0, l.borrowedCount)
        assertEquals(1, l.idleCount)

        val again = l.borrow(::make)
        assertSame("应复用空闲实例而不是新建", a, again)
        assertEquals("不得新建", 1, created)
    }

    @Test
    fun `拒绝外来实例的归还`() {
        val l = ledger(2)
        l.borrow(::make)
        // 不是本账本借出的实例
        val foreign = FakePlayer(999)
        assertEquals(
            "外来实例必须被拒",
            ScreenPlayerLedger.Recycle.REJECTED_UNKNOWN,
            l.recycle(foreign),
        )
        // 账面不受影响 —— 若被接受，foreign 会进 idle 而后被借出去，
        // 变成"同一实例被两处持有"
        assertEquals(1, l.borrowedCount)
        assertEquals(0, l.idleCount)
    }

    @Test
    fun `拒绝重复归还`() {
        val l = ledger(2)
        val a = l.borrow(::make)!!
        assertEquals(ScreenPlayerLedger.Recycle.ACCEPTED, l.recycle(a))
        // 第二次归还必须被拒：接受的话 borrowed 会减两次，
        // 后续 borrow 就能超出真实上界
        assertEquals(
            "重复归还必须被拒",
            ScreenPlayerLedger.Recycle.REJECTED_UNKNOWN,
            l.recycle(a),
        )
        assertEquals("idle 不得出现两份同一实例", 1, l.idleCount)
    }

    @Test
    fun `identity 记账而非 equals`() {
        // data class 那种 equals 相等的两个实例仍是两份解码器资源
        data class Equal(val tag: String)
        val l = ScreenPlayerLedger<Equal>(2)
        val first = Equal("same")
        val second = Equal("same")
        assertEquals(first, second) // equals 相等
        val got = l.borrow { first }!!
        assertSame(first, got)
        // 用 equals 相等但 identity 不同的实例归还 → 必须被拒
        assertEquals(
            ScreenPlayerLedger.Recycle.REJECTED_UNKNOWN,
            l.recycle(second),
        )
        assertEquals(1, l.borrowedCount)
    }

    @Test
    fun `release 必须把仍借出的也交出来销毁`() {
        val l = ledger(3)
        val a = l.borrow(::make)!!
        val b = l.borrow(::make)!!
        l.recycle(a) // a 回 idle，b 仍借出

        val toDestroy = l.release()
        // ⚠️ 这条守的是解码器泄漏：早前只 release idle 再把借出数清零，
        // 漏 dispose 的 b 会继续活着而账面为 0
        assertTrue("空闲的要交出", toDestroy.any { it === a })
        assertTrue("仍借出的也要交出", toDestroy.any { it === b })
        assertEquals(2, toDestroy.size)
        assertEquals(0, l.aliveCount)
    }

    @Test
    fun `release 后拒绝借出并让迟到的归还去销毁`() {
        val l = ledger(2)
        val a = l.borrow(::make)!!
        l.release()

        assertNull("释放后不得再借", l.borrow(::make))
        // Fragment 销毁后 Compose 的 onDispose 可能迟到 —— 那些实例要销毁，
        // 不能塞回一个已死的池
        assertEquals(
            ScreenPlayerLedger.Recycle.RELEASE_AFTER_SHUTDOWN,
            l.recycle(a),
        )
        assertTrue(l.isReleased)
    }

    @Test
    fun `借出后立刻归还不留账 —— 装载失败那条路径靠它`() {
        // `ScreenPlayerPool.borrow` 在 setMediaItem/prepare 抛异常时会把实例
        // recycle 回来。若那条路径漏了归还，借出计数只增不减 →
        // 几次之后池永远"满"，整页再也不播视频，**且不报错**
        val l = ledger(2)
        repeat(5) {
            val p = l.borrow(::make)
            assertNotNull("每轮都应借得到", p)
            assertEquals(ScreenPlayerLedger.Recycle.ACCEPTED, l.recycle(p!!))
            assertEquals("归还后不得留账", 0, l.borrowedCount)
        }
        assertEquals("应一直复用同一个实例", 1, created)
    }

    @Test
    fun `超出空闲容量的归还要求销毁而不是无限攒`() {
        val l = ledger(2)
        val a = l.borrow(::make)!!
        val b = l.borrow(::make)!!
        assertEquals(ScreenPlayerLedger.Recycle.ACCEPTED, l.recycle(a))
        assertEquals(ScreenPlayerLedger.Recycle.ACCEPTED, l.recycle(b))
        assertEquals(2, l.idleCount)

        // 借第三个（新建，因为 capacity=2 但 idle 有 2 → 先复用）
        val c = l.borrow(::make)!!
        val d = l.borrow(::make)!!
        assertEquals(0, l.idleCount)
        // 两个都归还 → 正好填满 idle
        l.recycle(c)
        l.recycle(d)
        assertEquals("idle 不得超过 capacity", 2, l.idleCount)
        assertEquals("总存活不得超过 capacity", 2, l.aliveCount)
    }
}
