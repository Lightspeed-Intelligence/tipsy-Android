package ai.lightspeed.tipsy.shell.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/**
 * 自订阅语言变化的文案组件（W1-P5，方案 §4.8）。
 *
 * > 「原生页文案必须组件化：提供自订阅语言变化的 Compose 文本组件，而不是每个
 * > 页面手挂 listener。**iOS 是后期才补 `LocalizedLabel`/`LocalizedButton` 的
 * > —— Android 第一天就做。**」（W1 计划 §7.3）
 *
 * 用法：`LocalizedText("Continue with Email")`，key 就是英文原文。
 *
 * ## 为什么必须用它而不是 `Text(L10n.t(key))`
 *
 * `L10n.t()` 是普通函数调用，Compose **不知道**它读了可变状态 ——
 * 语言切换后已组合的 `Text` 不会重组，表现为「切了语言，当前页面没变，
 * 退出重进才变」。这类 bug 在切换后立刻返回的操作路径下很容易漏测。
 * 本组件经 [rememberLocalizedString] 建立订阅，切换即重组。
 */
@Composable
fun LocalizedText(
    key: String,
    modifier: Modifier = Modifier,
    args: Map<String, String>? = null,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    style: TextStyle = androidx.compose.material3.LocalTextStyle.current,
) {
    Text(
        text = rememberLocalizedString(key, args),
        modifier = modifier,
        color = color,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
        style = style,
    )
}

/**
 * 取一个随语言变化自动更新的字符串。
 *
 * 给那些**不能直接用 [LocalizedText]** 的位置用：`contentDescription`、
 * `placeholder`、`TextField` 的 label、传给非 Compose API 的字符串等。
 *
 * ⚠️ **无障碍文案也要走它**（方案 §9.4 + W1 计划 §15：「无障碍与 testTag
 * 从第一个组件就做，不后补」）—— 硬编码的 contentDescription 在非英文下
 * 会让读屏用户听到英文，而视觉测试完全发现不了。
 */
@Composable
fun rememberLocalizedString(key: String, args: Map<String, String>? = null): String {
    // 订阅语言状态：切换后本组合会重新执行，从而重查表
    val language by L10n.languageFlow.collectAsState()
    // language 进 remember 的 key —— 它变了才需要重查。
    // args 也要进：同一 key 不同插值是不同结果
    return remember(key, language, args) {
        if (args.isNullOrEmpty()) L10n.t(key) else L10n.t(key, args)
    }
}

/**
 * 当前语言码的可观察快照。给需要按语言分支的 UI 用
 * （如按语言选不同图片资源）。
 */
@Composable
fun rememberCurrentLanguage(): State<String> = L10n.languageFlow.collectAsState()
