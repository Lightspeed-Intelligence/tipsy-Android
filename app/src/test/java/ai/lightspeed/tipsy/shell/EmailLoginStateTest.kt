package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.login.EmailLoginState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EmailLoginState] 的纯逻辑单测 —— 判定与倒计时。
 *
 * 这些规则错了都是**静默**的：按钮该亮不亮、冷却该结束不结束，
 * 不崩不报错，只能靠人对着 RN 版点一遍才发现。
 */
class EmailLoginStateTest {

    // ── 邮箱正则（原文照抄 RN，不能自行放宽或收紧）──────────

    @Test
    fun `合法邮箱通过校验`() {
        val valid = listOf(
            "a@b.co",
            "user@example.com",
            "first.last@sub.domain.org",
            "u+tag@gmail.com",
            "x!#\$%&'*+/=?^_`{|}~-@example.com",
            "user@multi.level.domain.io",
        )
        valid.forEach {
            assertTrue("应判为合法：$it", EmailLoginState(email = it).isEmailValid)
        }
    }

    @Test
    fun `非法邮箱被拦下`() {
        val invalid = listOf(
            "",
            "plainstring",
            "@example.com",
            "user@",
            "user@nodot",          // 正则要求至少一段 .xxx
            "user@@example.com",
            "user name@example.com", // 含空格
            "user@exam ple.com",
        )
        invalid.forEach {
            assertFalse("应判为非法：$it", EmailLoginState(email = it).isEmailValid)
        }
    }

    // ── 提交闸门 ──────────────────────────────────────────

    @Test
    fun `未发码时不能提交登录`() {
        val s = EmailLoginState(email = "a@b.com", code = "123456")
        assertFalse("sentEmail 为空即未发码", s.canLogin)
    }

    @Test
    fun `验证码必须恰好六位 —— 五位或七位都不能提交`() {
        val base = EmailLoginState(email = "a@b.com", sentEmail = "a@b.com")
        assertFalse(base.copy(code = "12345").canLogin)
        assertTrue(base.copy(code = "123456").canLogin)
        // RN 是 length === 6，不是 >= 6
        assertFalse("七位也不该放过", base.copy(code = "1234567").canLogin)
    }

    @Test
    fun `存在邮箱格式警告时不能提交`() {
        val s = EmailLoginState(
            email = "a@b.com",
            code = "123456",
            sentEmail = "a@b.com",
            showInvalidEmailWarning = true,
        )
        assertFalse(s.canLogin)
    }

    @Test
    fun `未发码时验证码框应不可输入`() {
        assertFalse(EmailLoginState().hasSentCode)
        assertTrue(EmailLoginState(sentEmail = "a@b.com").hasSentCode)
    }

    // ── 倒计时：deadline 基准，不是每秒减一 ──────────────────

    @Test
    fun `发码后启动 60 秒冷却`() {
        val s = EmailLoginState().markCodeSent("a@b.com", nowMs = 10_000)
        assertEquals(60, s.sendCodeCountdown)
        assertEquals(70_000L, s.sendCodeDeadlineMs)
        assertFalse("冷却中不能再发", s.canSendCode)
    }

    @Test
    fun `切后台再回来按真实时间重算 —— 不是冻结的计数器`() {
        // 发码于 t=0，冷却到 t=60s
        val sent = EmailLoginState().markCodeSent("a@b.com", nowMs = 0)
        // 切后台 45 秒后回前台：应剩 15 秒，而非仍显示 60
        val resumed = sent.withCountdownSynced(nowMs = 45_000)
        assertEquals(15, resumed.sendCodeCountdown)
        // 再过 20 秒（已超期）：归零并清 deadline
        val expired = resumed.withCountdownSynced(nowMs = 65_000)
        assertEquals(0, expired.sendCodeCountdown)
        assertNull("超期后应清掉 deadline 以停掉 tick", expired.sendCodeDeadlineMs)
        assertTrue(expired.canSendCode)
    }

    @Test
    fun `剩余不足一秒时向上取整为 1 —— 避免闪现 0 又跳回`() {
        val sent = EmailLoginState().markCodeSent("a@b.com", nowMs = 0)
        // 距截止还有 200ms
        assertEquals(1, sent.withCountdownSynced(nowMs = 59_800).sendCodeCountdown)
    }

    @Test
    fun `无 deadline 时倒计时恒为 0`() {
        val s = EmailLoginState().withCountdownSynced(nowMs = 12_345)
        assertEquals(0, s.sendCodeCountdown)
        assertTrue(s.canSendCode)
    }

    @Test
    fun `发码记录的邮箱做了 trim`() {
        val s = EmailLoginState().markCodeSent("  a@b.com  ", nowMs = 0)
        assertEquals("a@b.com", s.sentEmail)
    }

    // ── 发送按钮三态 ──────────────────────────────────────

    @Test
    fun `发送按钮文案三态`() {
        val fresh = EmailLoginState()
        assertEquals(EmailLoginState.SendLabel.Send, fresh.sendButtonLabel())

        val counting = fresh.markCodeSent("a@b.com", nowMs = 0)
        assertEquals(
            EmailLoginState.SendLabel.Countdown(60),
            counting.sendButtonLabel(),
        )

        val done = counting.withCountdownSynced(nowMs = 61_000)
        assertEquals(EmailLoginState.SendLabel.Resend, done.sendButtonLabel())
    }
}
