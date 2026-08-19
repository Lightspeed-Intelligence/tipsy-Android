package ai.lightspeed.tipsy.shell.pages.chatlist

import ai.lightspeed.tipsy.shell.R
import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
import ai.lightspeed.tipsy.shell.pages.home.HomeText
import ai.lightspeed.tipsy.shell.ui.sSp
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import coil3.compose.AsyncImage

/**
 * Map 卡叠里的**单卡**（W3-P2 阶段一：静态外观）。
 *
 * 对齐 RN `ChatItem.tsx`（不是 iOS 端口）：竖图 aspectRatio 0.75、圆角 4、
 * 底部渐变 + 名称（+story 标）+ 消息数/时间 + 未读红点。
 *
 * ⚠️ **本阶段不含 transform** —— `scale`/`translateX`/`zIndex` 由
 * [ChatMapCardLayout] 解算，在阶段二经 `graphicsLayer` 接线。
 * 这里只负责"一张卡长什么样"。
 *
 * 仍后置（对齐 iOS 端口注释的"仍后置"清单）：mp4 动态封面、NSFW 模糊、
 * 长按删除/置顶。
 */
@Composable
internal fun ChatMapCard(
    thread: ChatThread,
    messageCountText: String,
    timeText: String,
    hasUnread: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(ChatMapStyle.CARD_CORNER_DP.dp))
            .background(ChatMapStyle.backCardFill)
            .testTag("chat_map_card_${thread.itemId}"),
    ) {
        // 封面。⚠️ 动图（GIF/动画 WebP）靠 coil-gif 解码 —— 缺那个 artifact
        // 只会显示第一帧且不报错。按楼层可见性开关动图属阶段三
        AsyncImage(
            model = thread.imageUrl.takeIf { it.isNotBlank() }
                ?.let { HomeText.transformImageUrl(it) },
            contentDescription = thread.itemName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // 底部黑渐变（`ChatItem.tsx:178` 的 LinearGradient）
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        GRADIENT_START to Color.Transparent,
                        1f to Color.Black.copy(alpha = GRADIENT_BOTTOM_ALPHA),
                    ),
                ),
        )

        // 未读红点（`:240-245`）
        if (hasUnread) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(UNREAD_DOT_MARGIN_DP.dp)
                    .size(UNREAD_DOT_SIZE_DP.dp)
                    .clip(RoundedCornerShape(ChatMapStyle.CARD_CORNER_DP.dp))
                    .background(ChatMapStyle.unreadDotColor)
                    .testTag("chat_map_card_unread"),
            )
        }

        // 底部信息区（`:246-252`）
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(ChatMapStyle.CARD_BOTTOM_PADDING_DP.dp),
        ) {
            CardFooter(
                thread = thread,
                messageCountText = messageCountText,
                timeText = timeText,
            )
        }
    }
}

@Composable
private fun CardFooter(
    thread: ChatThread,
    messageCountText: String,
    timeText: String,
) {
    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = thread.itemName,
                color = ChatMapStyle.cardTextColor,
                fontSize = ChatMapStyle.NAME_FONT_SP.sSp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // ⚠️ RN 那里是 `maxWidth: 100` —— 名称过长时截断而不是挤走 story 标
                modifier = Modifier.widthIn(max = ChatMapStyle.NAME_MAX_WIDTH_DP.dp),
            )

            // story 标：`item_type === 'story' || character_type === 2`（`:187`）
            if (thread.itemType == ITEM_TYPE_STORY || thread.characterType == CHARACTER_TYPE_STORY) {
                Box(
                    Modifier
                        .padding(start = ChatMapStyle.STORY_TAG_H_PADDING_DP.dp)
                        .height(ChatMapStyle.STORY_TAG_MIN_HEIGHT_DP.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        // 壳是语言的唯一 writer；用 remember 版本以便切语言重组
                        text = rememberLocalizedString("Story"),
                        color = ChatMapStyle.storyTagTextColor,
                        fontSize = ChatMapStyle.STORY_TAG_FONT_SP.sSp,
                        modifier = Modifier.testTag("chat_map_card_story_tag"),
                    )
                }
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(META_GAP_DP.dp),
        modifier = Modifier.padding(top = META_TOP_GAP_DP.dp),
    ) {
        Text(
            text = messageCountText,
            color = ChatMapStyle.cardTextColor,
            fontSize = ChatMapStyle.META_FONT_SP.sSp,
        )
        // 分隔竖线 1×6（`:272-276`）
        Box(
            Modifier
                .width(ChatMapStyle.SPLIT_LINE_WIDTH_DP.dp)
                .height(ChatMapStyle.SPLIT_LINE_HEIGHT_DP.dp)
                .background(ChatMapStyle.cardTextColor),
        )
        Text(
            text = timeText,
            color = ChatMapStyle.cardTextColor,
            fontSize = ChatMapStyle.META_FONT_SP.sSp,
        )
    }
}

/**
 * 占位卡（不足 5 张时补位，`ChatMap.tsx:236-248` 的 `isEmpty` 分支）。
 *
 * ⚠️ **近黑实心 + 剪影，不做毛玻璃** —— 见 [ChatMapStyle.placeholderFill]。
 * 素材是 RN 的 `empty-chat.png`（540×720），⚠️ **不是**壳内已有的
 * `ic_chatlist_empty.png`（那个是 Grid 的空列表插画 `chatlist_empty_recent_chat.png`，
 * 两者在 RN 同目录、名字相近，复用会显示错的图且不报错）。
 */
@Composable
internal fun ChatMapPlaceholderCard(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(ChatMapStyle.CARD_CORNER_DP.dp))
            .background(ChatMapStyle.placeholderFill)
            .testTag("chat_map_placeholder_card"),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_chatmap_card_placeholder),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private const val ITEM_TYPE_STORY = "story"
private const val CHARACTER_TYPE_STORY = 2
private const val GRADIENT_START = 0.45f
private const val GRADIENT_BOTTOM_ALPHA = 0.85f
private const val UNREAD_DOT_SIZE_DP = 8
private const val UNREAD_DOT_MARGIN_DP = 6
private const val META_GAP_DP = 4
private const val META_TOP_GAP_DP = 2
