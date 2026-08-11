package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.login.EmailLoginApi
import ai.lightspeed.tipsy.shell.pages.login.EmailLoginService
import ai.lightspeed.tipsy.shell.pages.login.EmailLoginState
import ai.lightspeed.tipsy.shell.pages.login.EmailLoginViewModel
import ai.lightspeed.tipsy.shell.pages.login.FALLBACK_ERROR_KEY
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [EmailLoginViewModel] 的编排单测。
 *
 * 重点验三类容易静默出错的规则：
 * 1. **失败时不能启动倒计时** —— 否则用户要白等 60 秒才能重试
 * 2. **本地校验失败不发请求** —— 否则白占后端限流额度
 * 3. **登录成功必须落地 token** —— 漏了会「登录成功但仍是未登录态」
 *
 * ## 为什么用 fake 而不是 MockWebServer
 *
 * 这层只测**编排**（判定、状态流转、倒计时）。HTTP 与 header 契约由
 * `EmailLoginApiTest` 用真实 MockWebServer 单独验证。
 * 编排测试若走真实网络，请求会落到 `Dispatchers.IO` 的真实线程，
 * 与 `runTest` 的虚拟时钟脱耦 —— 实测会让倒计时相关的用例挂死。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmailLoginViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    /** 可编程的假服务：按需返回成功或抛错，并记录调用。 */
    private class FakeService : EmailLoginService {
        var sendCodeError: Exception? = null
        var loginError: Exception? = null
        var loginResult = EmailLoginApi.LoginResult("jwt-ok", false, 0)
        var sendCodeCalls = 0
        var loginCalls = 0
        val loginEmails = mutableListOf<String>()
        var lastLangCode: String? = null
        var lastAvatar: String? = null

        override suspend fun sendCode(email: String) {
            sendCodeCalls++
            sendCodeError?.let { throw it }
        }

        override suspend fun login(
            email: String,
            code: String,
            langCode: String,
            avatar: String,
        ): EmailLoginApi.LoginResult {
            loginCalls++
            loginEmails += email
            lastLangCode = langCode
            lastAvatar = avatar
            loginError?.let { throw it }
            return loginResult
        }
    }

    private lateinit var service: FakeService
    private val landedTokens = mutableListOf<String>()

    /**
     * 假时钟。**跟随 runTest 的虚拟时间**推进 —— 若写成常量，
     * `withCountdownSynced` 永远算不到 0，倒计时循环就退不出去。
     */
    private var fakeNow = 0L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        service = FakeService()
        landedTokens.clear()
        fakeNow = 0L
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * 时钟 = 虚拟时间 + 手工偏移。
     *
     * ⚠️ 必须跟随 `dispatcher.scheduler.currentTime` —— 倒计时循环每轮
     * `delay(1000)` 都会推进虚拟时间，若时钟是常量，`withCountdownSynced`
     * 永远算不出 0、`deadlineMs` 永不清空，`advanceUntilIdle()` 就**挂死**
     * （实测踩过）。[fakeNow] 只用来额外模拟"切后台若干秒"。
     */
    /**
     * 只让挂起的请求跑完，**不推进虚拟时间** —— 因此不会消耗倒计时。
     *
     * 用 `advanceUntilIdle()` 会把 60 秒倒计时一路跑到 0，
     * 那些断言「刚发码后剩 60 秒」的用例就失效了。
     */
    private fun kotlinx.coroutines.test.TestScope.flushRequests() {
        testScheduler.runCurrent()
    }

    /** VM 打出的诊断日志，断言用（也避免真去调未 mock 的 android.util.Log）。 */
    private val warnings = mutableListOf<String>()

    private fun newViewModel() = EmailLoginViewModel(
        api = service,
        langCodeProvider = { "zh-tw" },
        onLoginSucceeded = { landedTokens += it.token },
        nowMs = { dispatcher.scheduler.currentTime + fakeNow },
        logWarn = { warnings += it },
    )

    // ── 发码 ────────────────────────────────────────────────

    @Test
    fun `发码成功启动 60 秒倒计时并解锁验证码框`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onEmailChange("user@example.com")

        vm.sendCode()
        flushRequests()

        val s = vm.state.value
        assertTrue("应记为已发码", s.hasSentCode)
        assertEquals(60, s.sendCodeCountdown)
        assertFalse("冷却中不能再发", s.canSendCode)
    }

    @Test
    fun `邮箱格式非法时不发请求只置警告`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onEmailChange("not-an-email")

        vm.sendCode()
        advanceUntilIdle()

        assertTrue(vm.state.value.showInvalidEmailWarning)
        assertFalse("不该记为已发码", vm.state.value.hasSentCode)
        assertEquals("不该发出任何请求", 0, service.sendCodeCalls)
    }

    @Test
    fun `发码业务失败时不启动倒计时 —— 用户能立刻重试`() = runTest(dispatcher) {
        service.sendCodeError = EmailLoginApi.BusinessException(429, "发送过于频繁")
        val vm = newViewModel()
        vm.onEmailChange("user@example.com")

        vm.sendCode()
        advanceUntilIdle()

        assertEquals("后端 msg 应透传给用户", "发送过于频繁", vm.errorMessage.value)
        assertEquals("倒计时不该启动", 0, vm.state.value.sendCodeCountdown)
        assertFalse("不该解锁验证码框", vm.state.value.hasSentCode)
        assertTrue("应能立刻重试", vm.state.value.canSendCode)
    }

    @Test
    fun `发码网络失败时也不启动倒计时`() = runTest(dispatcher) {
        service.sendCodeError = IOException("boom")
        val vm = newViewModel()
        vm.onEmailChange("user@example.com")

        vm.sendCode()
        advanceUntilIdle()

        assertEquals(0, vm.state.value.sendCodeCountdown)
        assertFalse(vm.state.value.hasSentCode)
    }

    /**
     * 回归：网络失败必须**给用户可见反馈**。
     *
     * 曾经这里写 `_errorMessage.value = null`，本意是"让 UI 用默认文案"，但 UI 是
     * `errorMessage?.let { toast }` —— null 等于什么都不弹。真机上表现为**点发送
     * 完全没反应**（API 24 模拟器 TLS 握手失败时踩到，排查花了很久）。
     *
     * 原有的"不启动倒计时"用例只断言了状态，断言不到"用户被告知"，所以漏掉了。
     */
    @Test
    fun `发码网络失败必须给出兜底错误文案 —— 不能静默无反应`() = runTest(dispatcher) {
        service.sendCodeError = IOException("boom")
        val vm = newViewModel()
        vm.onEmailChange("user@example.com")

        vm.sendCode()
        advanceUntilIdle()

        assertNotNull("网络失败必须有可弹的文案，null 会导致静默", vm.errorMessage.value)
        assertEquals(FALLBACK_ERROR_KEY, vm.errorMessage.value)
    }

    @Test
    fun `登录网络失败必须给出兜底错误文案 —— 不能静默无反应`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onEmailChange("user@example.com")
        // 必须先发码成功：canLogin 要求 hasSentCode，否则 submitLogin 直接被守卫拦掉
        vm.sendCode()
        advanceUntilIdle()
        vm.onCodeChange("123456")

        service.loginError = IOException("boom")
        vm.submitLogin()
        advanceUntilIdle()

        assertNotNull(vm.errorMessage.value)
        assertEquals(FALLBACK_ERROR_KEY, vm.errorMessage.value)
        assertTrue("登录失败不应落地 token", landedTokens.isEmpty())
    }

    /** 后端返回空 msg 时也不能静默（envelope 里 msg 缺失是真实存在的情况）。 */
    @Test
    fun `业务失败但后端 msg 为空时回落到兜底文案`() = runTest(dispatcher) {
        service.sendCodeError = EmailLoginApi.BusinessException(500, "")
        val vm = newViewModel()
        vm.onEmailChange("user@example.com")

        vm.sendCode()
        advanceUntilIdle()

        assertEquals(FALLBACK_ERROR_KEY, vm.errorMessage.value)
    }

    @Test
    fun `冷却中重复点发送不发第二个请求`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onEmailChange("user@example.com")

        vm.sendCode()
        flushRequests()
        vm.sendCode() // 冷却中
        flushRequests()

        assertEquals("只应发出一个请求", 1, service.sendCodeCalls)
    }

    @Test
    fun `连点发送只发一次 —— loading 必须同步置位`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onEmailChange("user@example.com")

        // 两次连点之间**不给协程执行机会**：这正是真机双击的情形。
        // loading 若在协程体内置位，两次都会通过守卫。
        vm.sendCode()
        vm.sendCode()
        advanceUntilIdle()

        assertEquals("连点只应发出一个请求", 1, service.sendCodeCalls)
    }

    @Test
    fun `输入邮箱即清除格式警告`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onEmailChange("bad")
        vm.sendCode()
        advanceUntilIdle()
        assertTrue(vm.state.value.showInvalidEmailWarning)

        vm.onEmailChange("bad@")
        assertFalse("输入变化应清掉警告", vm.state.value.showInvalidEmailWarning)
    }

    // ── 登录 ────────────────────────────────────────────────

    @Test
    fun `登录成功把 token 交给落地动作`() = runTest(dispatcher) { // send_code
        service.loginResult = EmailLoginApi.LoginResult("jwt-ok", false, 0)

        val vm = newViewModel()
        vm.onEmailChange("user@example.com")
        vm.sendCode()
        advanceUntilIdle()
        vm.onCodeChange("123456")

        vm.submitLogin()
        advanceUntilIdle()

        assertEquals(listOf("jwt-ok"), landedTokens)
    }

    @Test
    fun `验证码不足六位时不发登录请求`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onEmailChange("user@example.com")
        vm.sendCode()
        advanceUntilIdle()
        val loginsBefore = service.loginCalls

        vm.onCodeChange("123")
        vm.submitLogin()
        advanceUntilIdle()

        assertEquals("不该发出登录请求", loginsBefore, service.loginCalls)
        assertTrue(landedTokens.isEmpty())
    }

    @Test
    fun `登录失败不落地 token 且透传后端 msg`() = runTest(dispatcher) {
        service.loginError = EmailLoginApi.BusinessException(1001, "验证码错误")

        val vm = newViewModel()
        vm.onEmailChange("user@example.com")
        vm.sendCode()
        advanceUntilIdle()
        vm.onCodeChange("000000")

        vm.submitLogin()
        advanceUntilIdle()

        assertEquals("验证码错误", vm.errorMessage.value)
        assertTrue("失败绝不能落地 token", landedTokens.isEmpty())
    }

    @Test
    fun `登录用发码时记下的邮箱而非当前输入值`() = runTest(dispatcher) {
        service.loginResult = EmailLoginApi.LoginResult("t", false, 0)

        val vm = newViewModel()
        vm.onEmailChange("sent@example.com")
        vm.sendCode()
        advanceUntilIdle()

        // 发完码后用户又改了邮箱内容
        vm.onEmailChange("changed@example.com")
        vm.onCodeChange("123456")
        vm.submitLogin()
        advanceUntilIdle()

        assertEquals(
            "应用 sentEmail 而非当前输入值，否则验证码与邮箱不匹配",
            listOf("sent@example.com"),
            service.loginEmails,
        )
    }

    @Test
    fun `请求期间重复提交只发一次`() = runTest(dispatcher) {
        service.loginResult = EmailLoginApi.LoginResult("t", false, 0)

        val vm = newViewModel()
        vm.onEmailChange("user@example.com")
        vm.sendCode()
        advanceUntilIdle()
        vm.onCodeChange("123456")

        vm.submitLogin()
        vm.submitLogin() // loading 中
        advanceUntilIdle()

        assertEquals("只该落地一次", 1, landedTokens.size)
    }

    // ── 倒计时与退出 ────────────────────────────────────────

    @Test
    fun `回前台时按真实时间重算倒计时`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onEmailChange("user@example.com")
        vm.sendCode()
        flushRequests()
        assertEquals(60, vm.state.value.sendCodeCountdown)

        // 模拟切后台 45 秒
        fakeNow += 45_000
        vm.syncCountdown()

        assertEquals("应剩 15 秒而非仍显示 60", 15, vm.state.value.sendCodeCountdown)
    }

    @Test
    fun `退出邮箱流程清输入但保留倒计时`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onEmailChange("user@example.com")
        vm.sendCode()
        flushRequests()
        vm.onCodeChange("123456")

        vm.onExitEmailFlow()

        assertEquals("", vm.state.value.email)
        assertEquals("", vm.state.value.code)
        // 对齐 RN：倒计时只在关闭整个登录页时清，返回首屏不清
        assertEquals(60, vm.state.value.sendCodeCountdown)
    }

    @Test
    fun `错误消费后清空 —— 不会重复弹同一个 Toast`() = runTest(dispatcher) {
        service.sendCodeError = EmailLoginApi.BusinessException(500, "服务异常")
        val vm = newViewModel()
        vm.onEmailChange("user@example.com")
        vm.sendCode()
        advanceUntilIdle()
        assertEquals("服务异常", vm.errorMessage.value)

        vm.consumeError()
        assertNull(vm.errorMessage.value)
    }

    @Test
    fun `loading 期间状态正确复位`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onEmailChange("user@example.com")

        vm.sendCode()
        advanceUntilIdle()

        assertFalse("请求结束后必须复位 loading", vm.state.value.loading)
    }

    @Test
    fun `发码成功发出聚焦验证码框的信号`() = runTest(dispatcher) {
        val vm = newViewModel()
        val before = vm.focusCodeRequest.value
        vm.onEmailChange("user@example.com")

        vm.sendCode()
        advanceUntilIdle()

        assertTrue("应递增聚焦信号", vm.focusCodeRequest.value > before)
    }

    @Test
    fun `发码失败不发聚焦信号`() = runTest(dispatcher) {
        service.sendCodeError = EmailLoginApi.BusinessException(429, "限流")
        val vm = newViewModel()
        val before = vm.focusCodeRequest.value
        vm.onEmailChange("user@example.com")

        vm.sendCode()
        advanceUntilIdle()

        assertEquals("失败不该聚焦（框还锁着）", before, vm.focusCodeRequest.value)
    }

    @Test
    fun `未发码时验证码位数够也不能提交`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onEmailChange("user@example.com")
        vm.onCodeChange("123456")

        vm.submitLogin()
        advanceUntilIdle()

        assertEquals(0, service.loginCalls)
        assertTrue(landedTokens.isEmpty())
    }

    @Test
    fun `验证码超过六位被截断`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onCodeChange("1234567890".take(EmailLoginState.CODE_LENGTH))
        assertEquals("123456", vm.state.value.code)
    }
}
