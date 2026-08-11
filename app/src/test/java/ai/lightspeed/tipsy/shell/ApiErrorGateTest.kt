package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.network.ApiErrorGate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 401/402 汇聚点测试（W1-P6，方案 §4.5）。
 *
 * 两条最容易写错的规则：
 * 1. **不带 token 的 401 不得触发登出** —— 否则旧账号迟到的 401 会踢掉新账号
 * 2. **401 与 402 各自独立防抖** —— 合用一个窗口会让付费墙不弹
 */
class ApiErrorGateTest {

    @Test
    fun `带 token 的 401 触发 auth 兜底`() = runTest {
        val f = fixture()
        f.gate.onUnauthorized("tok-1")
        assertEquals(listOf("tok-1"), f.authRejected)
    }

    /**
     * **本文件最重要的一条。** 对齐 RN `axios.ts:32-33`：
     * 「只有能绑定到实际请求 token 的拒绝才允许触发全局登出」，拿不到就 return。
     *
     * 场景：OPPORTUNISTIC 请求在未登录时发出 → 401。这个 401 不属于任何会话，
     * 若据此登出，会把「用户此刻正在另一个 tab 登录成功」的状态清掉。
     */
    @Test
    fun `不带 token 的 401 不得触发登出`() = runTest {
        val f = fixture()
        f.gate.onUnauthorized(null)
        assertTrue("无法判断会话归属的 401 必须忽略", f.authRejected.isEmpty())
    }

    @Test
    fun `402 触发付费墙`() = runTest {
        val f = fixture()
        f.gate.onPaymentRequired()
        assertEquals(1, f.paymentRequired)
    }

    // ── 防抖 ──────────────────────────────────────────────────

    /**
     * 登出会取消在飞请求，那些请求可能又报 401 → 再触发登出。
     * 防抖是为了断开这个自触发环。
     */
    @Test
    fun `窗口内重复 401 只处理一次`() = runTest {
        var now = 1000L
        val f = fixture(nowProvider = { now })

        f.gate.onUnauthorized("tok-1")
        now += 500  // 仍在 3s 窗口内
        f.gate.onUnauthorized("tok-1")
        now += 500
        f.gate.onUnauthorized("tok-1")

        assertEquals("防抖窗口内只处理一次，否则会形成登出自触发环", 1, f.authRejected.size)
    }

    @Test
    fun `窗口过后的 401 重新处理`() = runTest {
        var now = 1000L
        val f = fixture(nowProvider = { now })

        f.gate.onUnauthorized("tok-1")
        now += 4000  // 超过 3s 窗口
        f.gate.onUnauthorized("tok-2")

        assertEquals("真实的第二次 401 不该被吞", 2, f.authRejected.size)
    }

    /**
     * ⚠️ 401 与 402 **各自独立防抖**。合用一个窗口的症状是：
     * 用户触发 401 后 3 秒内的付费墙**不弹**，看起来是「点了购买没反应」。
     */
    @Test
    fun `401 与 402 防抖互不影响`() = runTest {
        var now = 1000L
        val f = fixture(nowProvider = { now })

        f.gate.onUnauthorized("tok-1")
        now += 100  // 401 的窗口内
        f.gate.onPaymentRequired()

        assertEquals(1, f.authRejected.size)
        assertEquals("402 不该被 401 的防抖窗口吞掉", 1, f.paymentRequired)
    }

    @Test
    fun `首次调用不被防抖挡住`() = runTest {
        // now 从 0 开始时，若实现写成 `now - 0 < window` 会把第一次也挡掉
        val f = fixture(nowProvider = { 0L })
        f.gate.onUnauthorized("tok-1")
        assertEquals("第一次必须放过（lastHandledAt 初始为 0）", 1, f.authRejected.size)
    }

    // ── helpers ───────────────────────────────────────────────

    private class Fixture(
        val gate: ApiErrorGate,
        val authRejected: List<String>,
        val paymentRequiredCounter: () -> Int,
    ) {
        val paymentRequired: Int get() = paymentRequiredCounter()
    }

    private fun fixture(nowProvider: () -> Long = { System.currentTimeMillis() }): Fixture {
        val rejected = mutableListOf<String>()
        var payment = 0
        val gate = ApiErrorGate(
            onAuthRejected = { rejected.add(it) },
            onPaymentRequired = { payment++ },
            nowMillis = nowProvider,
        )
        return Fixture(gate, rejected) { payment }
    }
}
