package ai.lightspeed.tipsy.shell.pages.screen

import ai.lightspeed.tipsy.shell.R
import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
import ai.lightspeed.tipsy.shell.pages.home.HomeText
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Screen tagline 的展示文本契约。
 *
 * 与 RN `FeedMediaItem.overlayTagline` 同序：先按 `is_translated` 决定
 * `{{user}}` 的语言并替换角色占位符，再只对 Google Play 渠道做敏感词打码。
 * 卡片与全屏预览必须复用同一个结果，避免展开后重新露出原始占位符或未打码文本。
 */
internal fun resolveScreenTagline(
    tagline: String,
    nickname: String,
    isTranslated: Boolean,
    languageCode: String,
    isGooglePlay: Boolean,
): String {
    val resolved = HomeText.replaceIntroductionPlaceholders(
        introduction = tagline,
        characterName = nickname,
        languageCode = if (isTranslated) languageCode else "en",
    )
    return HomeText.maskSensitiveWords(resolved, isGooglePlay)
}

/**
 * Screen 卡片内的 tagline 摘要。
 *
 * 对齐 iOS `ScreenCell.taglineCard`：标题与正文接排、最多四行、92% 宽的
 * 70% 黑色圆角卡片，并在右下角保留 44dp 展开热区。单击展开图标或双击
 * 卡片进入 [ScreenTaglinePreview]；单击卡片正文不改变 Pager 状态。
 */
@Composable
internal fun ScreenTaglineCard(
    text: String,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = rememberLocalizedString("Tagline")
    val expandAction by rememberUpdatedState(onExpand)
    val annotatedText = remember(title, text) {
        buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                ),
            ) {
                append(title)
                append(": ")
            }
            withStyle(SpanStyle(color = Color.White.copy(alpha = TAGLINE_BODY_ALPHA))) {
                append(text)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth(TAGLINE_WIDTH_FRACTION)
            .clip(RoundedCornerShape(TAGLINE_CARD_RADIUS.dp))
            .background(Color.Black.copy(alpha = TAGLINE_CARD_ALPHA))
            .pointerInput(text) {
                detectTapGestures(onDoubleTap = { expandAction() })
            }
            .testTag("screen_card_tagline"),
    ) {
        Text(
            text = annotatedText,
            fontSize = TAGLINE_FONT.sp,
            lineHeight = TAGLINE_LINE_HEIGHT.sp,
            maxLines = TAGLINE_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                start = TAGLINE_CARD_PADDING.dp,
                top = TAGLINE_CARD_PADDING.dp,
                end = TAGLINE_TEXT_END_PADDING.dp,
                bottom = TAGLINE_CARD_PADDING.dp,
            ),
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(TAGLINE_EXPAND_TOUCH_SIZE.dp)
                .clickable(onClick = expandAction)
                .testTag("screen_card_tagline_expand"),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_screen_tagline_expand),
                contentDescription = title,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = TAGLINE_EXPAND_ICON_INSET.dp,
                        bottom = TAGLINE_EXPAND_ICON_INSET.dp,
                    )
                    .size(TAGLINE_EXPAND_ICON_SIZE.dp),
            )
        }
    }
}

/**
 * Tagline 全文预览。
 *
 * 对齐 iOS `ScreenTaglinePreview`：覆盖当前 Tab 与底栏、深色背景、右上关闭、
 * 短文居中而长文可滚动。Dialog 不改变 Screen Fragment 生命周期，因此背后的
 * 视频保持原状态；关闭后无需重新借播放器或重建 Pager。
 */
@Composable
internal fun ScreenTaglinePreview(
    text: String,
    onDismiss: () -> Unit,
) {
    val closeLabel = rememberLocalizedString("Close")
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Android Compose 没有跨 API 24-36 的稳定 backdrop blur；iOS 的预览
                // 本身还有 85% 黑色压底，这里保留同一可读性与生命周期语义。
                .background(Color.Black.copy(alpha = TAGLINE_PREVIEW_BACKGROUND_ALPHA))
                .testTag("screen_tagline_preview"),
        ) {
            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = TAGLINE_PREVIEW_HORIZONTAL_PADDING.dp,
                        top = TAGLINE_PREVIEW_TOP_PADDING.dp,
                        end = TAGLINE_PREVIEW_HORIZONTAL_PADDING.dp,
                        bottom = TAGLINE_PREVIEW_BOTTOM_PADDING.dp,
                    ),
            ) {
                Text(
                    text = text,
                    color = Color.White.copy(alpha = TAGLINE_PREVIEW_TEXT_ALPHA),
                    fontSize = TAGLINE_FONT.sp,
                    lineHeight = TAGLINE_PREVIEW_LINE_HEIGHT.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        // iOS 支持双击预览正文关闭；拖动时 combinedClickable 会取消，
                        // 不影响长文的竖向滚动。
                        .combinedClickable(
                            onClick = {},
                            onDoubleClick = onDismiss,
                        )
                        .testTag("screen_tagline_body"),
                )
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = TAGLINE_PREVIEW_CLOSE_TOP.dp,
                        end = TAGLINE_PREVIEW_CLOSE_END.dp,
                    )
                    .size(TAGLINE_PREVIEW_CLOSE_SIZE.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = TAGLINE_PREVIEW_CLOSE_BACKGROUND_ALPHA))
                    .clickable(onClick = onDismiss)
                    .testTag("screen_tagline_close"),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_screen_tagline_close),
                    contentDescription = closeLabel,
                    modifier = Modifier.size(TAGLINE_PREVIEW_CLOSE_ICON_SIZE.dp),
                )
            }
        }
    }
}

private const val TAGLINE_WIDTH_FRACTION = 0.92f
private const val TAGLINE_CARD_ALPHA = 0.7f
private const val TAGLINE_BODY_ALPHA = 0.7f
private const val TAGLINE_CARD_RADIUS = 12
private const val TAGLINE_CARD_PADDING = 10
private const val TAGLINE_TEXT_END_PADDING = 32
private const val TAGLINE_FONT = 14
private const val TAGLINE_LINE_HEIGHT = 20
private const val TAGLINE_MAX_LINES = 4
private const val TAGLINE_EXPAND_TOUCH_SIZE = 44
private const val TAGLINE_EXPAND_ICON_SIZE = 16
private const val TAGLINE_EXPAND_ICON_INSET = 10

private const val TAGLINE_PREVIEW_BACKGROUND_ALPHA = 0.85f
private const val TAGLINE_PREVIEW_TEXT_ALPHA = 0.85f
private const val TAGLINE_PREVIEW_HORIZONTAL_PADDING = 28
private const val TAGLINE_PREVIEW_TOP_PADDING = 52
private const val TAGLINE_PREVIEW_BOTTOM_PADDING = 20
private const val TAGLINE_PREVIEW_LINE_HEIGHT = 22
private const val TAGLINE_PREVIEW_CLOSE_SIZE = 32
private const val TAGLINE_PREVIEW_CLOSE_ICON_SIZE = 15
private const val TAGLINE_PREVIEW_CLOSE_TOP = 6
private const val TAGLINE_PREVIEW_CLOSE_END = 18
private const val TAGLINE_PREVIEW_CLOSE_BACKGROUND_ALPHA = 0.12f
