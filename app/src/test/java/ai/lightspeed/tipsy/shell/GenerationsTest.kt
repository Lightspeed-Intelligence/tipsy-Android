package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.auth.Generations
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Generations] 双轨闸门测试（W1 计划 §3.3）。
 *
 * 核心要验的是**两轨互不替代** —— 这是最容易被"优化"掉的地方：
 * 看起来两个计数器做同一件事，合成一个会同时漏掉两类 bug。
 */
class GenerationsTest {

    @Test
    fun `初始快照有效`() {
        val g = Generations()
        assertTrue(g.isValid(g.snapshot()))
    }

    @Test
    fun `auth 自增后旧快照失效`() {
        val g = Generations()
        val snapshot = g.snapshot()
        g.bumpAuth()
        assertFalse("换号后在飞响应必须被丢弃", g.isValid(snapshot))
    }

    @Test
    fun `mutation 自增后旧快照失效`() {
        val g = Generations()
        val snapshot = g.snapshot()
        g.bumpMutation()
        assertFalse("本地删行后在飞的旧列表响应必须被丢弃", g.isValid(snapshot))
    }

    /**
     * **两轨不能合并。** 这条测试就是那个设计决策的守卫：
     * 只自增 mutation 时，`isAuthValid` 必须仍为真 —— 因为账号没变，
     * 写 token 是安全的。若两轨合成一个，一次本地删行会误判成"换号了"，
     * 让正常的 token 刷新结果被丢弃。
     */
    @Test
    fun `只动 mutation 时 auth 轨仍有效`() {
        val g = Generations()
        val snapshot = g.snapshot()
        g.bumpMutation()

        assertFalse("整体快照失效", g.isValid(snapshot))
        assertTrue("但账号没变 —— 写 token 仍应允许", g.isAuthValid(snapshot))
    }

    /** 反向：换号后 auth 轨必须失效，即使没有任何本地变更。 */
    @Test
    fun `只动 auth 时 auth 轨失效`() {
        val g = Generations()
        val snapshot = g.snapshot()
        g.bumpAuth()
        assertFalse(g.isAuthValid(snapshot))
    }

    @Test
    fun `快照是值语义 不随后续自增变化`() {
        val g = Generations()
        val snapshot = g.snapshot()
        g.bumpAuth()
        g.bumpMutation()
        // 若 snapshot 持的是引用而非值，这里会误判为有效
        assertFalse(g.isValid(snapshot))
        assertTrue("新快照当然有效", g.isValid(g.snapshot()))
    }

    @Test
    fun `并发自增不丢计数`() {
        val g = Generations()
        val threads = (1..8).map {
            Thread { repeat(100) { g.bumpAuth() } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue("AtomicLong 必须保证 800 次自增不丢（generation 会被多线程读写）", g.auth == 800L)
    }
}
