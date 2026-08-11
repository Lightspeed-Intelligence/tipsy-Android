package ai.lightspeed.tipsy.shell.pages.login

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 邮箱登录的编排。持有 [EmailLoginState]，跑发码 / 验码两个请求。
 *
 * ## 为什么用 ViewModel 而不是 Fragment 里的普通字段
 *
 * 表单状态必须**跨重组与配置变更存活**。`LoginFragment` 现有的 `render()`
 * 每次都 `setContent`，若把 email/code 放在 Fragment 字段里靠 render 刷新，
 * 用户每敲一个字都会重建整棵组合树 —— 焦点丢失、键盘收起。
 *
 * ## 时钟用 elapsedRealtime 而非 currentTimeMillis
 *
 * 倒计时是**单调**语义。用墙上时钟的话，用户改系统时间就能绕过 60 秒冷却，
 * 或者时区/NTP 校正会让倒计时突然跳变。`elapsedRealtime` 从开机起单调递增，
 * 且**包含深睡时间** —— 切后台再回来算出的剩余秒数是对的（这正是 RN 用
 * `Date.now()` + AppState 回前台重算所要达到的效果）。
 */
class EmailLoginViewModel(
    private val api: EmailLoginService,
    private val langCodeProvider: () -> String,
    /** 登录成功后的落地：存 token + 广播。由宿主注入，便于测试。 */
    private val onLoginSucceeded: suspend (EmailLoginApi.LoginResult) -> Unit,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    /**
     * 失败诊断日志。默认走 `android.util.Log`。
     *
     * 注入而非直接调用是因为 JVM 单测里 `android.util.Log` 是未 mock 的桩，
     * 一调就抛 "not mocked"。同 [nowMs] 的处理方式：**默认参数给生产实现，
     * 测试传 lambda**，不必为此开 `returnDefaultValues`（那会把整个 android.jar
     * 静默变成返回 0/null 的桩，掩盖真实问题）。
     */
    private val logWarn: (String) -> Unit = { Log.w(TAG, it) },
) : ViewModel() {

    private val _state = MutableStateFlow(EmailLoginState())
    val state: StateFlow<EmailLoginState> = _state.asStateFlow()

    /** 一次性事件：给用户看的错误文案。UI 消费后调 [consumeError]。 */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** 发码成功后请求聚焦验证码框的一次性信号。 */
    private val _focusCodeRequest = MutableStateFlow(0)
    val focusCodeRequest: StateFlow<Int> = _focusCodeRequest.asStateFlow()

    private var countdownJob: Job? = null

    fun onEmailChange(value: String) {
        // 输入变化即清掉格式警告（对齐 RN useLoginFlowState.ts:100）
        _state.value = _state.value.copy(email = value, showInvalidEmailWarning = false)
    }

    fun onCodeChange(value: String) {
        _state.value = _state.value.copy(code = value)
    }

    /** 退出邮箱流程时清理。**不清倒计时** —— 对齐 RN（只在关闭登录页时清）。 */
    fun onExitEmailFlow() {
        _state.value = _state.value.copy(
            email = "",
            code = "",
            showInvalidEmailWarning = false,
        )
    }

    fun consumeError() {
        _errorMessage.value = null
    }

    /**
     * 发验证码。
     *
     * 本地校验失败**不发请求**，只置警告（对齐 RN `useEmail.tsx:16-19`）。
     * 只有请求成功才启动倒计时并解锁验证码框 —— 这点很重要：格式错或发码失败
     * 时若也启动倒计时，用户要白等 60 秒才能重试。
     */
    fun sendCode() {
        val current = _state.value
        if (current.loading) return
        if (!current.isEmailValid) {
            _state.value = current.copy(showInvalidEmailWarning = true)
            return
        }
        if (!current.canSendCode) return

        // 同 submitLogin：loading 必须在 launch 之前同步置位，否则连点会发两次。
        _state.value = current.copy(loading = true)

        viewModelScope.launch {
            try {
                api.sendCode(current.email)
                _state.value = _state.value.markCodeSent(current.email, nowMs())
                startCountdown()
                // 请求聚焦验证码框（RN: requestAnimationFrame(() => ref.focus())）
                _focusCodeRequest.value += 1
            } catch (e: EmailLoginApi.BusinessException) {
                // 与 RN 的**刻意偏离**：RN 不检查 envelope 的 code，限流会被
                // 静默当成功（倒计时照走、用户干等邮件）。这里报错并**不启动
                // 倒计时**，让用户能立刻重试。
                _errorMessage.value = e.msg.ifBlank { FALLBACK_ERROR_KEY }
                logWarn("发码业务失败 code=${e.code}")
            } catch (e: IOException) {
                // ⚠️ 这里**必须给出兜底文案**。曾经写成 `null` 想表达"让 UI 用
                // 默认文案"，但 UI 是 `errorMessage?.let { toast }` —— null 等于
                // 什么都不显示，网络失败就变成**点了没反应**（真机实测踩到）。
                _errorMessage.value = FALLBACK_ERROR_KEY
                logWarn("发码网络失败：${e.javaClass.simpleName}")
            } finally {
                _state.value = _state.value.copy(loading = false)
            }
        }
    }

    /** 验码登录。成功后走 [onLoginSucceeded]（存 token + 广播）。 */
    fun submitLogin() {
        val current = _state.value
        if (!current.canLogin || current.loading) return

        // ⚠️ loading 必须在 launch **之前**同步置位。
        //
        // 若放在协程体里，两次快速点击会**都通过守卫**再各自启动协程
        // （协程不是立刻执行的），结果发出两个登录请求。实测：单测里
        // 用 StandardTestDispatcher 稳定复现，真机上表现为双击登录发两次。
        _state.value = current.copy(loading = true)

        viewModelScope.launch {
            try {
                val result = api.login(
                    // 用发码时记下的邮箱而非当前输入框值：用户可能发完码又改了
                    // 邮箱内容（RN 同样用 `sentEmail || email`，LoginScreen.tsx:342）
                    email = current.sentEmail.ifEmpty { current.email },
                    code = current.code,
                    langCode = langCodeProvider(),
                    avatar = EmailLoginApi.randomDefaultAvatar(),
                )
                onLoginSucceeded(result)
            } catch (e: EmailLoginApi.BusinessException) {
                // 透传后端 msg（如「验证码错误」）。RN 这里显示的是硬编码英文
                // `Failed to login with email`，中文用户也看英文。
                _errorMessage.value = e.msg.ifBlank { FALLBACK_ERROR_KEY }
                logWarn("登录业务失败 code=${e.code}")
            } catch (e: IOException) {
                // 同 sendCode：不能是 null，否则失败时静默无反应。
                _errorMessage.value = FALLBACK_ERROR_KEY
                logWarn("登录网络失败：${e.javaClass.simpleName}")
            } finally {
                _state.value = _state.value.copy(loading = false)
            }
        }
    }

    /** 回前台时按真实时间重算倒计时。 */
    fun syncCountdown() {
        _state.value = _state.value.withCountdownSynced(nowMs())
        if (_state.value.sendCodeDeadlineMs != null && countdownJob?.isActive != true) {
            startCountdown()
        }
    }

    /**
     * 每秒重算一次倒计时，直到归零。
     *
     * ⚠️ 循环上限 [MAX_TICKS] 是**防挂死**的保险，不是业务逻辑：循环的退出
     * 条件依赖 [nowMs] 真的往前走，若时钟被注入成常量（测试里很容易这样写），
     * `deadlineMs` 永远不会被清掉，这里就变成死循环。加个与冷却秒数同阶的
     * 上限，最坏情况多转几圈就退出，不会把进程或测试拖死。
     */
    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            var ticks = 0
            while (_state.value.sendCodeDeadlineMs != null && ticks < MAX_TICKS) {
                delay(COUNTDOWN_TICK_MS)
                _state.value = _state.value.withCountdownSynced(nowMs())
                ticks++
            }
        }
    }

    override fun onCleared() {
        countdownJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val TAG = "EmailLogin"

        const val COUNTDOWN_TICK_MS = 1_000L

        /** tick 次数上限：比冷却秒数留一点余量即可。见 [startCountdown] 的说明。 */
        const val MAX_TICKS = EmailLoginState.RESEND_COOLDOWN_SECONDS + 5
    }
}

/**
 * 网络失败或后端未给 msg 时的兜底文案 **i18n key**。
 *
 * ViewModel 只存 key（它不该碰 Android 资源/语言），由宿主翻译后再展示
 * —— 见 `LoginFragment` 的 Toast 处理。
 *
 * 选这个 key 而不是 RN 的 `Something went wrong`：后者**不在 26 个 locale
 * 文件里任何一个**，`L10n.t` 找不到就回落到 key 本身，结果所有语言都显示英文。
 * `Please try again later` 已在全部 26 个 locale 中有翻译（实测校验过）。
 */
const val FALLBACK_ERROR_KEY = "Please try again later"
