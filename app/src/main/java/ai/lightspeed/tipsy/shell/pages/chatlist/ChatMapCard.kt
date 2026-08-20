package ai.lightspeed.tipsy.shell.pages.chatlist

import ai.lightspeed.tipsy.shell.R
import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
import ai.lightspeed.tipsy.shell.pages.home.HomeText
import ai.lightspeed.tipsy.shell.ui.sSp
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
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
 * 点击（P2 入口接线）：整卡 Pressable（`ChatItem.tsx:150`），链路与 Grid
 * 行完全同构 —— 素材透传给 `onThreadClick`，分流由 Fragment/Router/Surface
 * 自决。RN 的 `'corridor'` 只影响**返回时回到哪个视图**（`chatListEntryType`，
 * RN 侧自持），`chatEnterSource` 两个视图**同为 `chat_list`**
 * （`useChatNavigation.ts:47-50` 的归一），壳不需要新值。
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ⚠️ **两层结构**：外层不裁剪（承载跨角的未读红点），内层才 clip。
    // RN 里红点是 `wrapper` 的直接子节点、在被裁剪的 `chatItem` **之外**
    // （`ChatItem.tsx:155-158`），`top: -size/2` 让它跨出右上角。
    // 放进裁剪容器里会被切掉一半 —— 看起来只是"点小了点"，不易发现
    Box(
        modifier = modifier
            // 点击挂外层：红点区域也可点（RN Pressable 是 wrapper 层）
            .clickable(onClick = onClick)
            .testTag("chat_map_card_${thread.itemId}"),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(ChatMapStyle.CARD_CORNER_DP.dp))
                .background(ChatMapStyle.backCardFill),
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

        // 未读红点：**在裁剪容器之外**，跨出右上角（`:133-141` top=-size/2）
        if (hasUnread) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    // ⚠️ x **为正**才向右越出（TopEnd 坐标系与 CSS 的 right 反号）
                    .offset(
                        x = ChatMapStyle.UNREAD_DOT_OFFSET_X_DP.dp,
                        y = ChatMapStyle.UNREAD_DOT_OFFSET_Y_DP.dp,
                    )
                    .size(ChatMapStyle.UNREAD_DOT_SIZE_DP.dp)
                    .clip(RoundedCornerShape(ChatMapStyle.CARD_CORNER_DP.dp))
                    .background(ChatMapStyle.unreadDotColor)
                    .testTag("chat_map_card_unread"),
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
    // ⚠️ **必须是 Column**：RN/iOS 都是名称行 + 消息行**两行竖排**。
    // 早前写成函数体内并列发两个 composable —— 它们成为外层 Box 的
    // 两个 sibling、都落在 TopStart，**消息行会盖住名称行**
    Column(verticalArrangement = Arrangement.spacedBy(META_TOP_GAP_DP.dp)) {
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
                        .height(ChatMapStyle.STORY_TAG_MIN_HEIGHT_DP.dp)
                        .clip(RoundedCornerShape(ChatMapStyle.STORY_TAG_CORNER_DP.dp))
                        // ⚠️ **三色横向渐变**，不是纯色（`:188-195`）
                        .background(Brush.horizontalGradient(ChatMapStyle.storyTagGradient))
                        .padding(horizontal = ChatMapStyle.STORY_TAG_H_PADDING_DP.dp),
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

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(META_GAP_DP.dp),
        ) {
            // 消息数图标 12×12（`:205-210`）—— 早前整个漏了。
            // ⚠️ 复用壳内已有的 `ic_profile_msg_count`（与 RN 的
            // `profile/message.png` 逐字节相同）—— 另拷一份会被 lint 的
            // `IconDuplicates` 拦下（实测），且资源体积白涨一倍
            Image(
                painter = painterResource(R.drawable.ic_profile_msg_count),
                contentDescription = null,
                modifier = Modifier.size(ChatMapStyle.MESSAGE_ICON_SIZE_DP.dp),
            )
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
            // ⚠️ 圆角 **8** 不是真实卡的 4（`ChatMap.tsx:240`）
            .clip(RoundedCornerShape(ChatMapStyle.PLACEHOLDER_CORNER_DP.dp))
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
private const val META_GAP_DP = 4
private const val META_TOP_GAP_DP = 2
