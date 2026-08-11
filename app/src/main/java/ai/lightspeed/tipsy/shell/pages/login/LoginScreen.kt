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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    /** 键盘高（dp），0 = 未弹出。本轮无输入框，恒 0；留参数供邮箱链接入。 */
    keyboardHeightDp: Float = 0f,
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

        // ── 社交按钮组 ────────────────────────────────────
        Column(
            modifier = Modifier
                .height(spacing.formHeight.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(LoginStyle.BUTTON_GAP.s),
        ) {
            LoginSocialButton(
                textKey = KEY_CONTINUE_GOOGLE,
                iconRes = R.drawable.ic_login_google,
                testTag = TAG_GOOGLE,
                onClick = onGoogleClick,
            )
            LoginSocialButton(
                textKey = KEY_CONTINUE_APPLE,
                iconRes = R.drawable.ic_login_apple,
                testTag = TAG_APPLE,
                onClick = onAppleClick,
            )
            LoginSocialButton(
                textKey = KEY_CONTINUE_EMAIL,
                iconRes = R.drawable.ic_login_email,
                testTag = TAG_EMAIL,
                onClick = onEmailClick,
            )
        }

        if (!spacing.isKeyboardDocked) {
            Spacer(Modifier.weight(1f))
        }

        // ── 条款 ──────────────────────────────────────────
        Column(
            modifier = Modifier
                .height(spacing.bottomHeight.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Bottom,
        ) {
            LoginTermsText(urls = LegalLinks.forChannel(downloadChannel))
        }
    }
}

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
