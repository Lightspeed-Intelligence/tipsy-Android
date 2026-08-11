package ai.lightspeed.tipsy.shell.pages.login

/**
 * 邮箱登录表单的纯状态与判定。**不含任何 Android 依赖**，可直接单测。
 *
 * 对齐 RN `tipsy-app/src/login/hooks/useLoginFlowState.ts:36-95`。
 *
 * ## 为什么倒计时存 deadline 而不是剩余秒数
 *
 * RN 用的是**绝对截止时刻** + 每秒重算（`:44-51`），并在 App 回前台时
 * 立即重算（`:62-73`）。若改成"剩余秒数每秒减一"，切后台后计时器被系统
 * 冻结，回来时倒计时会**比真实剩余时间长** —— 用户干等，且不报错。
 * Android 侧同理，且要用**单调时钟**（`elapsedRealtime`）而非墙上时钟，
 * 否则用户改系统时间就能绕过冷却。
 */
data class EmailLoginState(
    val email: String = "",
    val code: String = "",
    /** 已成功发过码的邮箱。非空即表示「已发码」，对齐 RN 的 `hasSentCode = !!sentEmail`。 */
    val sentEmail: String = "",
    /** 倒计时截止时刻（[android.os.SystemClock.elapsedRealtime] 基准，毫秒）。null 表示无冷却。 */
    val sendCodeDeadlineMs: Long? = null,
    /** 由 [sendCodeDeadlineMs] 与当前时刻算出的剩余秒数，0 表示可再次发送。 */
    val sendCodeCountdown: Int = 0,
    /** 邮箱格式错误提示。只在点发送且格式非法时置位，输入变化即清除。 */
    val showInvalidEmailWarning: Boolean = false,
    /** 发码与登录**共用**一个 loading（对齐 RN 的单一 `loginLoading`）。 */
    val loading: Boolean = false,
) {

    /** 是否已发过码。未发码时验证码框不可输入（RN 的 `editable={hasSentCode}`）。 */
    val hasSentCode: Boolean get() = sentEmail.isNotEmpty()

    /** 冷却结束才能再发。注意 RN 未把 loading 计入此判定，disable 时另外与 loading 取或。 */
    val canSendCode: Boolean get() = sendCodeCountdown == 0

    /**
     * 能否提交登录。
     *
     * 三个条件对齐 RN `useLoginFlowState.ts:89-90`：已发码、验证码**恰好 6 位**、
     * 且无邮箱格式警告。注意 RN 是 `length === 6` 而非 `>= 6`。
     */
    val canLogin: Boolean
        get() = hasSentCode && code.length == CODE_LENGTH && !showInvalidEmailWarning

    /** 邮箱格式是否合法。正则原文见 [EMAIL_REGEX]。 */
    val isEmailValid: Boolean get() = EMAIL_REGEX.matches(email)

    /**
     * 发送按钮文案的三态，对齐 RN `useLoginFlowState.ts:92-95`：
     * 未发码 → 「发送」；冷却中 → 「Ns」；冷却结束 → 「重新发送」。
     */
    fun sendButtonLabel(): SendLabel = when {
        !hasSentCode -> SendLabel.Send
        sendCodeCountdown > 0 -> SendLabel.Countdown(sendCodeCountdown)
        else -> SendLabel.Resend
    }

    sealed interface SendLabel {
        object Send : SendLabel
        object Resend : SendLabel
        data class Countdown(val seconds: Int) : SendLabel
    }

    /** 按当前时刻重算倒计时。回前台与每秒 tick 都调它。 */
    fun withCountdownSynced(nowMs: Long): EmailLoginState {
        val deadline = sendCodeDeadlineMs ?: return copy(sendCodeCountdown = 0)
        // ceil 对齐 RN 的 Math.ceil：剩 0.2 秒也显示 1s，避免闪现 0 又跳回
        val remaining = maxOf(0, ((deadline - nowMs + 999) / 1000).toInt())
        return if (remaining == 0) {
            copy(sendCodeCountdown = 0, sendCodeDeadlineMs = null)
        } else {
            copy(sendCodeCountdown = remaining)
        }
    }

    /** 发码成功后启动 60 秒冷却并记下邮箱。 */
    fun markCodeSent(email: String, nowMs: Long): EmailLoginState = copy(
        // RN 存的是 trim 后的值（useLoginFlowState.ts:117）
        sentEmail = email.trim(),
        sendCodeDeadlineMs = nowMs + RESEND_COOLDOWN_SECONDS * 1000L,
        sendCodeCountdown = RESEND_COOLDOWN_SECONDS,
    )

    companion object {
        /** 验证码位数。RN 三处硬编码 6（输入框 maxLength、可登录判定）。 */
        const val CODE_LENGTH = 6

        /** 重发冷却秒数，对齐 RN `useLoginFlowState.ts:118`。 */
        const val RESEND_COOLDOWN_SECONDS = 60

        /**
         * 邮箱正则，**原文照抄** RN `src/hooks/login/useEmail.tsx:9-13`。
         *
         * 不自作主张换成更宽或更严的版本 —— 两端校验不一致会让某些邮箱
         * 在壳里能提交、在 RN 里不能（或反之），而这种差异没有任何报错。
         */
        val EMAIL_REGEX =
            Regex("""^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-]+)+$""")

        /**
         * 内部登录模式的触发串（RN `useLoginFlowState.ts:97-109`）。
         *
         * 邮箱框输入正好这个值会切到内部账号密码登录。壳内**本轮不实现**
         * 内部登录页，但保留常量作为记录 —— 漏了它内部测试同学会以为壳坏了。
         */
        const val INTERNAL_LOGIN_TRIGGER = "SolarAscend"
    }
}
