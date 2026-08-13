package ai.lightspeed.tipsy.shell.pages.chatlist

import ai.lightspeed.tipsy.shell.R
import ai.lightspeed.tipsy.shell.i18n.LocalizedText
import ai.lightspeed.tipsy.shell.pages.home.HomeText
import ai.lightspeed.tipsy.shell.ui.s
import ai.lightspeed.tipsy.shell.ui.sSp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlin.math.roundToInt

import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString

/**
 * 会话列表的一行（RN `ChatListItem.tsx`，W3 ChatList P1）。
 *
 * ## 左滑操作用自绘 drag 而不是 SwipeToDismissBox
 *
 * M3 的 `SwipeToDismissBox` 语义是「滑走删除」（整行位移到消失），而这里要的是
 * iOS 风格「滑开露出两个按钮、停住」。Compose 没有现成的 stay-open swipe，
 * 自绘 `detectHorizontalDragGestures` + `animateFloatAsState` 是最小实现。
 * 行为对齐 RN `Swipeable`：右滑回弹、超过阈值停在露出位、同表最多一行开着
 * （互斥由上层 [openRowKey] 状态控制）。
 *
 * ## more/操作按钮必须是可点击组件吃掉事件
 *
 * 方案 §8.1 Profile 行记的 iOS 事故：装饰性 View 让点击穿透进详情页。
 * 这里两个操作键都是 `clickable`，且滑开状态下点行主体是**收起**而非进详情
 * （对齐 RN Swipeable 的 close-on-tap）。
 */
@Composable
internal fun ChatListRow(
    thread: ChatThread,
    draft: ChatDraft?,
    badge: RelationshipStat?,
    isGooglePlay: Boolean,
    /** 当前滑开的行 key；非本行时本行复位（互斥）。 */
    openRowKey: String?,
    onSwipeOpen: (String) -> Unit,
    onClick: () -> Unit,
    onPinClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val density = LocalDensity.current
    // 两键总宽 148（RN actionButton width）
    val actionsWidthPx = with(density) { (ChatListStyle.ACTION_WIDTH * 2).s.toPx() }
    var dragOffset by remember(thread.stableKey) { mutableFloatStateOf(0f) }
    var isOpen by remember(thread.stableKey) { mutableStateOf(false) }

    // 互斥：别的行打开时本行收起
    LaunchedEffect(openRowKey) {
        if (openRowKey != thread.stableKey && isOpen) {
            isOpen = false
            dragOffset = 0f
        }
    }

    val settledOffset by animateFloatAsState(
        targetValue = if (isOpen) -actionsWidthPx else 0f,
        animationSpec = tween(180),
        label = "swipe",
    )
    // 拖动中用实时值，松手后用动画值
    var dragging by remember { mutableStateOf(false) }
    val shownOffset = if (dragging) dragOffset else settledOffset

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.s) // 头像 48 + paddingVertical 4×2（avatarContainer）
            .testTag("chat_list_item_${thread.stableKey}"),
    ) {
        // 底层：操作键（右对齐，随行主体位移露出）。
        // ⚠️ 只在滑开/拖动中才组合 —— 行主体是透明背景（RN 同款，底色由
        // App 背景提供），恒挂底层会直接透出来（真机实测：全部行的
        // Delete/Pin 常驻显示盖住时间栏）。RN Swipeable 的 renderRightActions
        // 也只在滑动期间渲染。
        if (dragging || shownOffset < -0.5f) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
            ) {
                SwipeActionButton(
                    iconRes = R.drawable.ic_chatlist_delete,
                    labelKey = "Delete",
                    background = ChatListStyle.deleteActionColor,
                    testTag = "chat_list_delete_${thread.stableKey}",
                    onClick = {
                        isOpen = false
                        dragOffset = 0f
                        onDeleteClick()
                    },
                )
                SwipeActionButton(
                    iconRes = if (thread.isPinned) {
                        R.drawable.ic_chatlist_pin_off
                    } else {
                        R.drawable.ic_chatlist_pin_on
                    },
                    labelKey = if (thread.isPinned) "Unpinned" else "Pinned",
                    background = ChatListStyle.pinActionColor,
                    testTag = "chat_list_pin_${thread.stableKey}",
                    onClick = {
                        isOpen = false
                        dragOffset = 0f
                        onPinClick()
                    },
                )
            }
        }

        // 上层：行主体
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .offset { IntOffset(shownOffset.roundToInt(), 0) }
                .fillMaxWidth()
                .fillMaxHeight()
                .background(Color.Transparent)
                .pointerInput(thread.stableKey) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            // RN rightThreshold=40：露出超 40dp 停住，否则回弹
                            val threshold = with(density) { 40.dp.toPx() }
                            isOpen = -dragOffset > threshold
                            dragOffset = if (isOpen) -actionsWidthPx else 0f
                            if (isOpen) onSwipeOpen(thread.stableKey)
                        },
                        onDragCancel = {
                            dragging = false
                            dragOffset = if (isOpen) -actionsWidthPx else 0f
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        // 只允许向左露出（overshootRight=false），最右回到 0
                        dragOffset = (dragOffset + dragAmount).coerceIn(-actionsWidthPx, 0f)
                    }
                }
                .clickable {
                    if (isOpen) {
                        // 滑开时点行主体先收起（对齐 Swipeable close-on-tap）
                        isOpen = false
                        dragOffset = 0f
                    } else {
                        onClick()
                    }
                }
                .padding(horizontal = ChatListStyle.ROW_PADDING_H.s),
        ) {
            ThreadAvatar(thread)
            Spacer12()
            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f),
            ) {
                NameAndBadges(thread, badge, isGooglePlay)
                Box(Modifier.height(4.s))
                LastMessageLine(thread, draft)
            }
            Spacer12()
            TimeAndIndicator(thread, draft)
        }
    }
}

@Composable
private fun Spacer12() {
    Box(Modifier.width(ChatListStyle.AVATAR_MARGIN_R.s))
}

@Composable
private fun ThreadAvatar(thread: ChatThread) {
    Box {
        AsyncImage(
            model = HomeText.transformImageUrl(thread.faceUrl),
            contentDescription = thread.itemName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(ChatListStyle.AVATAR.s)
                .clip(CircleShape),
        )
        // 右下角标：story 或 mini_phone（两者互斥地占同一位置，
        // RN 是两个并列条件渲染 —— mini_phone 的 chat_mode 与 story 的
        // item_type 实际不会同时为真）
        val tagRes = when {
            thread.isMiniPhone -> R.drawable.ic_chatlist_inbox_tag
            thread.showStoryTag -> R.drawable.ic_chatlist_story_tag
            else -> null
        }
        tagRes?.let {
            Image(
                painter = painterResource(it),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 4.s, y = (-1).s)
                    .size(ChatListStyle.CORNER_TAG.s),
            )
        }
    }
}

@Composable
private fun NameAndBadges(thread: ChatThread, badge: RelationshipStat?, isGooglePlay: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.s),
    ) {
        Text(
            text = HomeText.maskSensitiveWords(thread.itemName, isGooglePlay),
            color = ChatListStyle.nameColor,
            fontSize = ChatListStyle.NAME_FONT.sSp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (badge != null) {
            LevelBadge(badge)
        }
        if (thread.itemType == ChatThread.TYPE_CHARACTER &&
            thread.currentStreakDays > 0 &&
            !thread.isMiniPhone
        ) {
            StreakTag(thread.currentStreakDays)
        }
    }
}

@Composable
private fun LevelBadge(badge: RelationshipStat) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.s),
        modifier = Modifier
            .clip(RoundedCornerShape(40.s))
            .background(ChatListStyle.badgeBackground)
            .padding(start = 3.s, top = 3.s, bottom = 3.s, end = 6.s),
    ) {
        Image(
            painter = painterResource(levelImageRes(badge.level)),
            contentDescription = null,
            modifier = Modifier.size(ChatListStyle.BADGE_ICON.s),
        )
        Text(
            // i18n-ignore：LV 是产品词，RN 侧同为裸文本（`LV{level}`）
            text = "LV${badge.subLevel}",
            color = ChatListStyle.levelColor(badge.level),
            fontSize = ChatListStyle.BADGE_FONT.sSp,
            fontWeight = FontWeight.Bold,
            // RN 用 RobotoBoldItalic —— Compose 里 Roboto 是默认字体，补斜体即可
            fontStyle = FontStyle.Italic,
        )
    }
}

@Composable
private fun StreakTag(days: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.s),
        modifier = Modifier
            .clip(RoundedCornerShape(10.s))
            .background(ChatListStyle.badgeBackground)
            .padding(horizontal = 6.s, vertical = 3.s),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_rel_heart_streak),
            contentDescription = null,
            modifier = Modifier.size(ChatListStyle.BADGE_ICON.s),
        )
        Text(
            // i18n-ignore：`${n}d` 是 RN 裸模板（`ChatListItem.tsx:450`）
            text = "${days}d",
            color = Color.White,
            fontSize = ChatListStyle.BADGE_FONT.sSp,
            maxLines = 1,
        )
    }
}

@Composable
private fun LastMessageLine(thread: ChatThread, draft: ChatDraft?) {
    val hasDraft = draft != null && (draft.text.isNotBlank() || draft.imageCount > 0)
    if (hasDraft) {
        // RN：`[{t('Draft')}] ` 橙色前缀 + 草稿文本（无文本显示 t('Image')）。
        // 单个 AnnotatedString 渲染保证整行只有一个省略号语义（分两个 Text
        // 会让前缀独占宽度、正文被过度截断）。括号在词条外，照 RN。
        val draftLabel = rememberLocalizedString("Draft")
        val body = if (draft.text.isNotBlank()) {
            draft.text
        } else {
            rememberLocalizedString("Image")
        }
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = ChatListStyle.draftTagColor)) {
                    append("[$draftLabel] ")
                }
                append(body)
            },
            color = ChatListStyle.lastMessageColor,
            fontSize = ChatListStyle.LAST_MSG_FONT.sSp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    val message = ChatListText.displayLastMessage(thread)
    if (message != null) {
        Text(
            text = message,
            color = ChatListStyle.lastMessageColor,
            fontSize = ChatListStyle.LAST_MSG_FONT.sSp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        LocalizedText(
            key = "No messages yet",
            color = ChatListStyle.lastMessageColor,
            fontSize = ChatListStyle.LAST_MSG_FONT.sSp,
            maxLines = 1,
        )
    }
}

@Composable
private fun TimeAndIndicator(thread: ChatThread, draft: ChatDraft?) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.s),
    ) {
        val timeMs = draft?.updatedAt ?: (thread.latestTimeSeconds * 1000L)
        Text(
            text = ChatListText.formatRowTime(timeMs),
            color = ChatListStyle.timeColor,
            fontSize = ChatListStyle.TIME_FONT.sSp,
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(16.s)
                .padding(end = 0.dp),
        ) {
            when {
                thread.isPushMessage && !thread.isPushMessageViewed -> Box(
                    Modifier
                        .size(8.s)
                        .clip(CircleShape)
                        .background(ChatListStyle.pushDotColor),
                )

                thread.isPinned -> Image(
                    painter = painterResource(R.drawable.ic_chatlist_pin_grey),
                    contentDescription = null,
                    modifier = Modifier.size(ChatListStyle.ACTION_ICON.s),
                )
            }
        }
    }
}

@Composable
private fun SwipeActionButton(
    iconRes: Int,
    labelKey: String,
    background: Color,
    testTag: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .width(ChatListStyle.ACTION_WIDTH.s)
            .fillMaxHeight()
            .background(background)
            .clickable(onClick = onClick)
            .testTag(testTag),
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(ChatListStyle.ACTION_ICON.s),
        )
        LocalizedText(
            key = labelKey,
            color = Color.White,
            fontSize = ChatListStyle.ACTION_FONT.sSp,
        )
    }
}

/** RN 用 RobotoBoldItalic 的 LV 徽章字体 —— Compose 默认字体即 Roboto，补粗斜即可。 */
private fun levelImageRes(level: Int): Int = when (level) {
    2 -> R.drawable.ic_rel_level2
    3 -> R.drawable.ic_rel_level3
    4 -> R.drawable.ic_rel_level4
    5 -> R.drawable.ic_rel_level5
    else -> R.drawable.ic_rel_level1
}
