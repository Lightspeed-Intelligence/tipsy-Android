package ai.lightspeed.tipsy.shell.pages.login

import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
import ai.lightspeed.tipsy.shell.ui.sSp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration

/**
 * 条款文案（对齐 RN `LoginScreen.tsx:567-620`）。
 *
 * 结构：`<前缀> <社区规范>, <服务条款> and <隐私政策>.`
 * 三个链接橙色 `#F3A231` + 下划线，正文 `rgba(255,255,255,0.6)`。
 *
 * ## ⚠️ 用 `LinkAnnotation` 而不是自己算点击区域
 *
 * `LinkAnnotation.Url` 让链接**天然可被读屏识别为链接**并能用键盘/开关控制
 * 聚焦。手写 `pointerInput` + `getOffsetForPosition` 的做法视觉一样，但
 * TalkBack 用户完全无法访问这三个链接 —— 而条款链接是合规要求的一部分。
 *
 * ## ⚠️ 文案拼接必须按 i18n 结果拼，不能硬编码顺序
 *
 * RN 侧是 `{前缀} {链接1}, {链接2} {and} {链接3}.` —— 中间的逗号、空格、
 * 句点是**英文排版**。这里照抄 RN 的拼法（连同 `and` 也走 i18n key），
 * 因为多语言下 RN 就是这么拼的，壳与 RN 必须一致。
 *
 * 已知限制：某些语言的语序与英文不同，这个拼法在那些语言下读起来会怪 ——
 * **但那是 RN 侧现网已存在的行为**，壳不在这里"修正"，否则两端不一致。
 * 要改得先改 RN。
 */
@Composable
fun LoginTermsText(
    urls: LegalLinks.Urls,
    modifier: Modifier = Modifier,
) {
    val prefix = rememberLocalizedString(LegalLinks.KEY_TERMS_PREFIX)
    val guidelines = rememberLocalizedString(LegalLinks.KEY_COMMUNITY_GUIDELINES)
    val terms = rememberLocalizedString(LegalLinks.KEY_TERMS_OF_SERVICE)
    val privacy = rememberLocalizedString(LegalLinks.KEY_PRIVACY_POLICY)
    val and = rememberLocalizedString(LegalLinks.KEY_AND)

    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = LoginStyle.LINK,
            textDecoration = TextDecoration.Underline,
        ),
    )

    val text = buildAnnotatedString {
        append(prefix)
        append(' ')
        appendLink(guidelines, urls.communityGuidelines, linkStyles)
        append(", ")
        appendLink(terms, urls.termsOfService, linkStyles)
        append(' ')
        append(and)
        append(' ')
        appendLink(privacy, urls.privacyPolicy, linkStyles)
        append('.')
    }

    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAG_TERMS),
        color = LoginStyle.TEXT_SECONDARY,
        fontSize = LoginStyle.TEXT_SIZE_TERMS.sSp,
        lineHeight = LoginStyle.LINE_HEIGHT_TERMS.sSp,
        textAlign = TextAlign.Center,
    )
}

private fun AnnotatedString.Builder.appendLink(
    label: String,
    url: String,
    styles: TextLinkStyles,
) {
    withLink(LinkAnnotation.Url(url, styles)) { append(label) }
}

/** 稳定 testTag。**不含用户文本**（W1 计划 §15）。 */
const val TAG_TERMS = "login_terms"

/** `withLink` 的内联包装 —— 让上面的拼接读起来是线性的。 */
private inline fun AnnotatedString.Builder.withLink(
    link: LinkAnnotation,
    block: AnnotatedString.Builder.() -> Unit,
) {
    val index = pushLink(link)
    block()
    pop(index)
}
