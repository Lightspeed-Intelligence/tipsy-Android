package ai.lightspeed.tipsy.shell.pages.login

import ai.lightspeed.tipsy.shell.R
import ai.lightspeed.tipsy.shell.ui.s
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * 原生登录页（W2，方案 §8.1 Login 行）。
 *
 * 目录位置对齐 iOS 壳：`Pages/Login/`（iOS 是
 * `Tipsy-iOS/Pages/Login/LoginViewController.swift` + `LoginControls.swift`）。
 *
 * ## 布局对齐 RN `src/login/LoginScreen.tsx`
 *
 * 纵向结构（`:385-535`）：
 * ```
 * flexGap（弹性）
 * topSection   —— logo，高 = spacing.logoSize
 * flexGap（弹性）
 * middleSection —— 社交按钮组，高 = spacing.formHeight
 * flexGap（弹性，键盘弹出时移除）
 * footerSection —— 条款，高 = spacing.bottomHeight
 * ```
 * 三个弹性留白让内容在不同屏高下均匀分布，各段自身高度由
 * [LoginLayout] 按 812 设计稿高算 ratio 得出。**不是等分** ——
 * 弹性留白吸收剩余空间，段高有各自的 clamp 下限。
 *
 * ## ⚠️ 本轮范围：只有社交按钮 + 条款
 *
 * 邮箱验证码链（`LoginEmailForm` / `EmailCode`）、年龄验证、资料补全属后续，
 * 本文件先落 RN 截图里可见的那一屏。**Google / Apple 点击目前不可用** ——
 * 缺 Firebase 签名指纹（开放问题 §12.8，见 [onGoogleClick] 注释）。
 */
@Composable
fun LoginScreen(
    downloadChannel: String,
    onGoogleClick: () -> Unit,
    onAppleClick: () -> Unit,
    onEmailClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 状态栏 inset（dp）。由宿主传入 —— 让布局计算可单测。 */
    insetTopDp: Float = 0f,
    /** 导航栏 inset（dp）。 */
    insetBottomDp: Float = 0f,
    /** 键盘高（dp），0 = 未弹出。驱动 footer 的显隐与上方留白收缩。 */
    keyboardHeightDp: Float = 0f,
    // ── 邮箱流程 ────────────────────────────────────────────
    /** 是否进入邮箱表单（对齐 RN 的 `emailFlowOpen`，同页切换而非新页面）。 */
    emailFlowOpen: Boolean = false,
    emailState: EmailLoginState = EmailLoginState(),
    onBackFromEmail: () -> Unit = {},
    onEmailChange: (String) -> Unit = {},
    onCodeChange: (String) -> Unit = {},
    onSendCode: () -> Unit = {},
    onSubmitLogin: () -> Unit = {},
    /**
     * 验证码框的焦点句柄。发码成功后由宿主 `requestFocus()`，
     * 对齐 RN 的 `requestAnimationFrame(() => codeInputRef.current?.focus())`。
     * 传 null 表示不需要程序化聚焦（如预览）。
     */
    codeFocusRequester: FocusRequester? = null,
) {
    // 用**窗口**高而非屏幕高（见 ScaledMetrics.scaleFactor 注释）：分屏 /
    // 折叠屏下窗口远小于屏幕，按屏幕算会让布局溢出可视区。
    // RN 的 useWindowDimensions 同样是窗口语义。
    val density = LocalDensity.current.density
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val spacing = LoginLayout.compute(
        windowHeightDp = if (density > 0f) windowHeightPx / density else 0f,
        insetTopDp = insetTopDp,
        insetBottomDp = insetBottomDp,
        keyboardHeightDp = keyboardHeightDp,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LoginStyle.BACKGROUND)
            .padding(
                top = spacing.containerTop.dp,
                bottom = spacing.containerBottom.dp,
            )
            // RN container 的 paddingHorizontal: 24（经 ScaledSheet 缩放）
            .padding(horizontal = LoginStyle.PAGE_HORIZONTAL_PADDING.s)
            .testTag(TAG_SCREEN),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── 上方弹性留白 ────────────────────────────────────
        if (spacing.isKeyboardDocked) {
            Spacer(Modifier.height(spacing.keyboardTopGap.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }

        // ── logo ──────────────────────────────────────────
        Box(
            modifier = Modifier
                .height(spacing.logoSize.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.login_logo),
                // 装饰性图像 —— 读屏跳过。品牌 logo 不承载操作语义，
                // 播报「Tipsy 图标」对读屏用户没有帮助
                contentDescription = null,
                modifier = Modifier.size(spacing.logoSize.dp),
                contentScale = ContentScale.Fit,
            )
        }

        if (spacing.isKeyboardDocked) {
            Spacer(Modifier.height(spacing.keyboardFormGap.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }

        // ── 表单区（头部行 + 社交按钮组）──────────────────
        //
        // ⚠️ 三处与首版不同，都是对照 RN 源码改的：
        //
        // 1. **按钮间距是 `spacing.socialGap`（32*ratio ≈ 27~32），不是 12**。
        //    `LoginSocialButtons` 的 `buttonGap` 默认值是 12，但
        //    `LoginScreen.tsx:458` 显式传了 `spacing.socialGap`。用默认值会让
        //    三个按钮明显挤在一起。
        //
        // 2. **头部行必须占位**。首屏里 `flowHeaderRow`（`:425-440`）装的是个
        //    占位 View，但**行本身仍占 32 高 + backBottom 下边距** ——
        //    社交按钮组在 RN 里是被这 32+20 顶下来的。漏掉会让整组偏上。
        //
        // 3. **用 `heightIn(min=)` 而不是固定 `height`**。RN 的 middleSection 是
        //    `justifyContent: flex-start` 且**不裁剪溢出** —— 内容高
        //    （32+20+3*48+2*32 = 260）实际超过 formHeight（248），RN 让它溢出。
        //    固定高会把最后一个按钮压掉一截。
        Column(
            modifier = Modifier
                .heightIn(min = spacing.formHeight.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Top,
        ) {
            // 头部行：邮箱流程下是返回按钮，首屏是等尺寸占位。
            //
            // ⚠️ 首屏也**必须占满 32 高 + backBottom** —— 占位不是可省的，
            // 社交按钮组在 RN 里正是被这 32+20 顶下来的（`:425-440`）。
            // 高度是两套机制相加：32 走宽度缩放（ScaledSheet 值），
            // backBottom 走高度 ratio（来自 spacing）—— 不能混。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LoginStyle.FLOW_HEADER_HEIGHT.s),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (emailFlowOpen) {
                    LoginBackButton(onClick = onBackFromEmail)
                }
            }
            Spacer(Modifier.height(spacing.backBottom.dp))

            if (emailFlowOpen) {
                LoginEmailForm(
                    state = emailState,
                    onEmailChange = onEmailChange,
                    onCodeChange = onCodeChange,
                    onSendCode = onSendCode,
                    onSubmit = onSubmitLogin,
                    codeFocusRequester = codeFocusRequester
                        ?: remember { FocusRequester() },
                )
            } else {
                LoginSocialButton(
                    textKey = KEY_CONTINUE_GOOGLE,
                    iconRes = R.drawable.ic_login_google,
                    testTag = TAG_GOOGLE,
                    onClick = onGoogleClick,
                )
                Spacer(Modifier.height(spacing.socialGap.dp))
                LoginSocialButton(
                    textKey = KEY_CONTINUE_APPLE,
                    iconRes = R.drawable.ic_login_apple,
                    testTag = TAG_APPLE,
                    onClick = onAppleClick,
                )
                Spacer(Modifier.height(spacing.socialGap.dp))
                LoginSocialButton(
                    textKey = KEY_CONTINUE_EMAIL,
                    iconRes = R.drawable.ic_login_email,
                    testTag = TAG_EMAIL,
                    onClick = onEmailClick,
                )
            }
        }

        if (!spacing.isKeyboardDocked) {
            Spacer(Modifier.weight(1f))
        }

        // ── 条款（RN footerSection：`justifyContent: flex-end`）──────
        //
        // 同样用 heightIn 而非固定 height：条款文案在窄屏/大字号下会换到
        // 4 行，固定 112~120 高会把最后一行裁掉。RN 的 footerSection 也不裁剪。
        //
        // ⚠️ `termsTopGap`（24*ratio）是 RN 给条款容器的 marginTop（`:571`），
        // 首版漏了 —— 表现为条款贴着上方的弹性留白，与 RN 比少一段呼吸空间。
        Column(
            modifier = Modifier
                .heightIn(min = spacing.bottomHeight.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Bottom,
        ) {
            // footer 的两块是**互斥**的（RN `:537` 与 `:567`）：
            // 邮箱流程下显示登录按钮，首屏显示条款。且两者都还要 `!isKeyboardDocked` ——
            //
            // ⚠️ 键盘弹起时 footer **整块消失**，此时唯一的提交路径是验证码框
            // 软键盘上的 done 键（`LoginEmailForm` 的 onSubmit）。这不是缺陷，
            // 是 RN 的既有行为；漏掉 done 键会让用户在键盘弹起时**无法提交**。
            if (!spacing.isKeyboardDocked) {
                if (emailFlowOpen) {
                    LoginPrimaryButton(
                        textKey = KEY_LOGIN,
                        // 激活判据**不含 loading**：请求期间按钮仍是砖红激活色
                        // 但不可点（RN 的 style 用 canLogin、disabled 才含 loading）
                        isActive = emailState.canLogin,
                        enabled = emailState.canLogin && !emailState.loading,
                        testTag = TAG_LOGIN_SUBMIT,
                        onClick = onSubmitLogin,
                    )
                    // footerLoginBtn 的 marginBottom: 8
                    Spacer(Modifier.height(FOOTER_LOGIN_BOTTOM_GAP.s))
                } else {
                    Spacer(Modifier.height(spacing.termsTopGap.dp))
                    LoginTermsText(urls = LegalLinks.forChannel(downloadChannel))
                }
            }
        }
    }
}

/** 登录按钮下边距 8（`LoginScreen.tsx:701` footerLoginBtn.marginBottom）。 */
private const val FOOTER_LOGIN_BOTTOM_GAP = 8

// ── i18n key（= 英文原文，与 RN 的 t() 参数逐字一致）───────────
//
// 已确认三个 key 都在 assets/locales 的 26 个语言里（P5 的导出链路）。

const val KEY_CONTINUE_GOOGLE = "Continue with Google"
const val KEY_CONTINUE_APPLE = "Continue with Apple"
const val KEY_CONTINUE_EMAIL = "Continue with Email"

// ── 稳定 testTag（W1 计划 §15：不含用户文本）──────────────────

const val TAG_SCREEN = "login_screen"
const val TAG_GOOGLE = "login_google"
const val TAG_APPLE = "login_apple"
const val TAG_EMAIL = "login_email"
