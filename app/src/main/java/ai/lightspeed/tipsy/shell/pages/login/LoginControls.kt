package ai.lightspeed.tipsy.shell.pages.login

import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
import ai.lightspeed.tipsy.shell.ui.s
import ai.lightspeed.tipsy.shell.ui.sSp
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.ripple
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.testTag

/**
 * 登录页控件（对齐 RN `TipsyButton` 在登录页的用法）。
 *
 * 与 iOS 壳 `Pages/Login/LoginControls.swift` 同构：控件 48 高、42 圆角、
 * `rgba(255,255,255,0.1)` 底。
 *
 * ## 无障碍与 testTag 从第一个组件就做
 *
 * W1 计划 §15：「**无障碍与 testTag 从第一个组件就做，不后补**」——
 * iOS 后期批量补了约 295 个 accessibility ID。所以这里每个控件都带
 * `testTag` 与 `contentDescription`，且**文案走 i18n**（硬编码的
 * contentDescription 在非英文下会让读屏用户听到英文，视觉测试发现不了）。
 */

/**
 * 社交/邮箱登录按钮：左 icon + 文案，整体居中。
 *
 * @param textKey i18n key（= 英文原文，如 `"Continue with Google"`）
 * @param iconRes 图标资源
 * @param testTag 自动化测试用的稳定标识。**不要把用户文本拼进去**
 *   （W1 计划 §15：动态 ID 要脱敏且稳定）
 */
@Composable
fun LoginSocialButton(
    textKey: String,
    iconRes: Int,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val label = rememberLocalizedString(textKey)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(LoginStyle.CONTROL_HEIGHT.s)
            .clip(RoundedCornerShape(LoginStyle.CONTROL_RADIUS.s))
            .background(LoginStyle.CONTROL_FILL)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            )
            .testTag(testTag)
            // 整行是一个按钮 —— contentDescription 挂在容器上，
            // 里面的 Image/Text 不再各自播报（否则读屏会读两遍）
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(iconRes),
            // null：语义由父容器承载，见上
            contentDescription = null,
            modifier = Modifier.size(LoginStyle.ICON_SIZE.s),
            contentScale = ContentScale.Fit,
        )
        androidx.compose.foundation.layout.Spacer(
            Modifier.size(LoginStyle.ICON_TEXT_GAP.s),
        )
        Text(
            text = label,
            color = LoginStyle.TEXT_PRIMARY,
            fontSize = LoginStyle.TEXT_SIZE_BUTTON.sSp,
        )
    }
}

/**
 * 主操作按钮（如 Login）：灰底 → 激活态 `#9C4844`。
 *
 * 对齐 RN 的 `footerLoginBtn` / `footerLoginBtnActive`
 * （`LoginScreen.tsx:696-711`）：**激活时文字也变纯白**。
 */
@Composable
fun LoginPrimaryButton(
    textKey: String,
    isActive: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = rememberLocalizedString(textKey)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(LoginStyle.CONTROL_HEIGHT.s)
            .clip(RoundedCornerShape(LoginStyle.CONTROL_RADIUS.s))
            .background(if (isActive) LoginStyle.CONTROL_ACTIVE else LoginStyle.CONTROL_FILL)
            .clickable(
                // 未激活时不可点（对齐 RN 的 `if (!isDoneActive) return`）——
                // 用 enabled 而不是在 onClick 里 return：后者对读屏用户不可见，
                // 他们会以为按钮可用
                enabled = isActive,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            )
            .testTag(testTag)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (isActive) Color.White else LoginStyle.TEXT_PRIMARY,
            fontSize = LoginStyle.TEXT_SIZE_BUTTON.sSp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
    }
}
