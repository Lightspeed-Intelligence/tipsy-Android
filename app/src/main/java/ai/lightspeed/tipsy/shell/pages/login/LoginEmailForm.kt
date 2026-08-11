package ai.lightspeed.tipsy.shell.pages.login

import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
import ai.lightspeed.tipsy.shell.ui.s
import ai.lightspeed.tipsy.shell.ui.sSp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * 邮箱登录表单：**单页双输入框**（邮箱 + 验证码同屏）。
 *
 * 对齐 RN `tipsy-app/src/login/components/LoginEmailForm.tsx`。
 *
 * ## ⚠️ 不要按 `EmailCode.tsx` 实现两步流程
 *
 * 仓里那个 `src/login/EmailCode.tsx` 是**死代码** —— 全仓无任何 import
 * （`emailCodeStep` 状态也不存在）。真实交互是本组件这样的单页：
 * 邮箱框 + 验证码行（内嵌发送按钮），靠 `emailFlowOpen` 一个布尔量
 * 在同一个页面内与社交按钮组切换。
 *
 * ## 登录按钮不在这里
 *
 * RN 给本组件传 `showLoginButton={false}`（`LoginScreen.tsx:488`），
 * 真正的登录按钮渲染在页面 footer，且**键盘弹起时整块消失** ——
 * 那时唯一的提交路径是软键盘的 done 键（本组件的 [onSubmit]）。
 */
@Composable
fun LoginEmailForm(
    state: EmailLoginState,
    onEmailChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onSendCode: () -> Unit,
    onSubmit: () -> Unit,
    codeFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // ── 邮箱框（错误提示悬浮在下方，不占布局高度）──────────
        Box(Modifier.fillMaxWidth()) {
            LoginTextField(
                value = state.email,
                onValueChange = onEmailChange,
                // RN 里这个 placeholder 是**硬编码**的，不走 i18n
                // （LoginEmailForm.tsx:68），照抄。
                placeholder = "yours@example.com",
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
                testTag = TAG_EMAIL_INPUT,
            )
            if (state.showInvalidEmailWarning) {
                val warning = rememberLocalizedString(KEY_INVALID_EMAIL)
                Text(
                    // 前缀 "* " 是 RN 硬编码的（`LoginEmailForm.tsx:82`）
                    text = "* $warning",
                    color = LoginStyle.ERROR_TEXT,
                    fontSize = LoginStyle.TEXT_SIZE_ERROR.sSp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        // bottom:-24 → 从底边再往下推。用 offset 而非 padding，
                        // 这样不会把父容器撑高（RN 那边是 position:absolute）
                        .offset(
                            x = LoginStyle.INPUT_HORIZONTAL_PADDING.s,
                            y = LoginStyle.ERROR_TEXT_OFFSET.s,
                        )
                        .testTag(TAG_EMAIL_ERROR),
                )
            }
        }

        Spacer(Modifier.height(LoginStyle.CODE_ROW_GAP.s))

        // ── 验证码行（发送按钮叠在框内右侧）─────────────────
        Box(Modifier.fillMaxWidth()) {
            LoginTextField(
                value = state.code,
                onValueChange = { input ->
                    // maxLength=6：超长直接截断而不是拒绝输入，
                    // 与 RN 的 maxLength 行为一致（粘贴长串时只保留前 6 位）
                    onCodeChange(input.take(EmailLoginState.CODE_LENGTH))
                },
                placeholder = rememberLocalizedString(KEY_YOUR_CODE),
                // ⚠️ RN **没给验证码框设数字键盘**（LoginEmailForm.tsx:88-99
                // 无 keyboardType），是默认文本键盘。这里照抄以保持一致 ——
                // 若要改成数字键盘那是**有意偏离**，得单独记一笔。
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
                // 发码前不可输入（RN 的 `editable={hasSentCode}`）
                enabled = state.hasSentCode,
                onSubmit = onSubmit,
                focusRequester = codeFocusRequester,
                // 给内嵌的发送按钮让位
                endPadding = LoginStyle.CODE_INPUT_RIGHT_PADDING,
                testTag = TAG_CODE_INPUT,
            )

            SendCodeButton(
                state = state,
                onClick = onSendCode,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = LoginStyle.SEND_BUTTON_RIGHT.s),
            )
        }
    }
}

/**
 * 验证码框内嵌的发送按钮，三态：`发送` / `60s` / `重新发送`。
 *
 * 倒计时文案是 RN 硬编码的 `` `${n}s` ``，不走 i18n。
 */
@Composable
private fun SendCodeButton(
    state: EmailLoginState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sendLabel = rememberLocalizedString(KEY_SEND)
    val resendLabel = rememberLocalizedString(KEY_RESEND)
    val label = when (val s = state.sendButtonLabel()) {
        EmailLoginState.SendLabel.Send -> sendLabel
        EmailLoginState.SendLabel.Resend -> resendLabel
        is EmailLoginState.SendLabel.Countdown -> "${s.seconds}s"
    }
    // 冷却中或请求中不可点（RN: `disabled={!canSendCode || loginLoading}`）
    val enabled = state.canSendCode && !state.loading

    Text(
        text = label,
        color = LoginStyle.TEXT_PRIMARY,
        fontSize = LoginStyle.TEXT_SIZE_BUTTON.sSp,
        modifier = modifier
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .testTag(TAG_SEND_CODE),
    )
}

/**
 * 登录页统一样式的输入框。
 *
 * 用 [BasicTextField] 而非 Material 的 `TextField`：后者自带 label/indicator
 * 与固定内边距，压不成 RN 那个 48 高的胶囊（`inputCommon`：高 48、圆角 42、
 * 无边框）。Material 的装饰要一个个关掉，反而比自己画更绕。
 */
@Composable
private fun LoginTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    testTag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    endPadding: Int = LoginStyle.INPUT_HORIZONTAL_PADDING,
    onSubmit: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = LocalTextStyle.current.merge(
            TextStyle(
                color = LoginStyle.TEXT_PRIMARY,
                fontSize = LoginStyle.TEXT_SIZE_BUTTON.sSp,
            ),
        ),
        cursorBrush = SolidColor(LoginStyle.TEXT_PRIMARY),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction,
            // 邮箱不自动大写（RN 的 autoCapitalize="none"）
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
        modifier = modifier
            .fillMaxWidth()
            .height(LoginStyle.CONTROL_HEIGHT.s)
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .testTag(testTag),
        decorationBox = { innerTextField ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(LoginStyle.CONTROL_HEIGHT.s)
                    .background(
                        color = LoginStyle.INPUT_FILL,
                        shape = RoundedCornerShape(LoginStyle.CONTROL_RADIUS.s),
                    )
                    .padding(
                        PaddingValues(
                            start = LoginStyle.INPUT_HORIZONTAL_PADDING.s,
                            end = endPadding.s,
                        ),
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = LoginStyle.INPUT_PLACEHOLDER,
                        fontSize = LoginStyle.TEXT_SIZE_BUTTON.sSp,
                    )
                }
                innerTextField()
            }
        },
    )
}

// ── i18n key（= 英文原文，与 RN 的 t('...') 一致）──────────────

/** RN: `t('Please enter a valid email address.')`。注意**句末有句点**。 */
const val KEY_INVALID_EMAIL = "Please enter a valid email address."
const val KEY_YOUR_CODE = "your code"
const val KEY_SEND = "Send"
const val KEY_RESEND = "Resend"
const val KEY_LOGIN = "Login"

// ── testTag ────────────────────────────────────────────────
const val TAG_EMAIL_INPUT = "login-email-input"
const val TAG_CODE_INPUT = "login-code-input"
const val TAG_SEND_CODE = "login-send-code"
const val TAG_EMAIL_ERROR = "login-email-error"
const val TAG_LOGIN_SUBMIT = "login-submit"
