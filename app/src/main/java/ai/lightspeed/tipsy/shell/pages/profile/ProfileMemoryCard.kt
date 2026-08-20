package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
import ai.lightspeed.tipsy.shell.pages.home.HomeText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/**
 * 记忆 tab 的单列大卡（`PlotItem.tsx` 的展示部分）。
 *
 * ## 本刀只做展示，三处刻意不接
 *
 * - **点击不接**：RN 点卡片进 `ChatDetail/ChatMemory`（`PlotItem.tsx:76-84`），
 *   属 ChatDetail 深栈 —— 明确不迁（方案 §11），后续经 `ChatDetailSurface`。
 *   不给卡片挂空的 clickable —— 造一个「点了没反应」的假交互正是 §2.23
 *   stub 抽屉那次真机误判的形态。
 * - **审核失败的 Edit 按钮不接**：编辑/删除动作属后续「卡片菜单」包
 *   （方案 §8.1 卡片菜单行）。
 *
 * ## 布局对照（`PlotItem.tsx:334-`）
 *
 * 高 340 / 圆角 10 / 底 margin 8；背景图 = `character.image_url` 全出血；
 * RN 的两层压暗渐变（`159-167`）近似成一层三段渐变，目的一样 ——
 * 白字在任意封面上可读。`!nsfw偏好 && plot.nsfw` 时背景模糊
 * （`PlotItem.tsx:47,170`，P4 起与创作卡同一套 [ProfileCoverImage]，
 * nsfw 偏好恒 false → 18+ 记忆一律模糊）。审核状态点的 SVG 资产未搬，本刀文字版。
 */
@Composable
fun ProfileMemoryCard(item: ProfileMemoryItem, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(CARD_HEIGHT.dp)
            .clip(RoundedCornerShape(CARD_RADIUS.dp))
            .background(ProfileStyle.CARD_PLACEHOLDER)
            .testTag("profile_memory_card_${item.plotId}"),
    ) {
        // 背景图用 image_url —— face_url 是头像位，两个字段别混（见 item KDoc）
        if (!item.characterImageUrl.isNullOrBlank()) {
            val url = HomeText.transformImageUrl(item.characterImageUrl)
            ProfileCoverImage(
                url = url,
                contentDescription = item.title,
                shouldBlur = item.nsfw,
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(SCRIM_TOP, Color.Transparent, SCRIM_BOTTOM),
                    ),
                ),
        )

        Column(Modifier.fillMaxSize().padding(CARD_PADDING.dp)) {
            // ── 标题行：标题 + 18+/Private 徽标（PlotItem.tsx:193-209）──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.title.orEmpty(),
                    color = ProfileStyle.TEXT_PRIMARY,
                    fontSize = TITLE_FONT.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.nsfw) {
                    // "18+" 是符号不是文案，不进 i18n
                    MemoryBadge("18+")
                }
                if (!item.isPublic) {
                    MemoryBadge(rememberLocalizedString("Private"))
                }
            }
            // ── meta 行：@创作者 · 时间（PlotItem.tsx:211-221）──
            Row(Modifier.padding(top = META_GAP.dp)) {
                item.creatorNickname?.let {
                    Text(
                        text = "@$it",
                        color = ProfileStyle.TEXT_SECONDARY,
                        fontSize = META_FONT.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Spacer(Modifier.weight(1f))
                item.createdAt?.let {
                    Text(
                        text = ProfileText.formatMemoryTime(it),
                        color = ProfileStyle.TEXT_SECONDARY,
                        fontSize = META_FONT.sp,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // ── 主区：头像列 + 消息预览气泡（PlotItem.tsx:225-291）──
            Row(verticalAlignment = Alignment.Bottom) {
                Column(verticalArrangement = Arrangement.spacedBy(AVATAR_GAP.dp)) {
                    MemoryAvatar(url = item.characterFaceUrl, name = item.characterName)
                    MemoryAvatar(url = item.creatorAvatarUrl, name = item.creatorNickname)
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(BUBBLE_GAP.dp),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = CARD_PADDING.dp),
                ) {
                    item.previewMessages.forEach { msg ->
                        MemoryPreviewBubble(msg, item)
                    }
                }
            }

            // ── 底部：审核状态 + N messages（PlotItem.tsx:295-320）──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = CARD_PADDING.dp),
            ) {
                ReviewStatusBadge(item.reviewStage)
                Spacer(Modifier.weight(1f))
                Text(
                    // RN 底部是 "N messages >"，`>` 属于点击进详情的可供性 ——
                    // 点击本刀不接，箭头一并不画，免得暗示可点
                    text = "${item.messageCount} " + rememberLocalizedString("messages"),
                    color = ProfileStyle.TEXT_SECONDARY,
                    fontSize = META_FONT.sp,
                )
            }
        }
    }
}

/**
 * 预览气泡。角色消息靠左、发送者名用角色昵称；用户消息靠右、用创作者昵称
 * （`PlotItem.tsx:263-284` 的两组 bubble 样式与名字分流）。
 * 气泡底色是近似值（RN 用两套 shape 样式，视觉 diff 属验收阶段）。
 */
@Composable
private fun MemoryPreviewBubble(msg: MemoryPreviewMessage, item: ProfileMemoryItem) {
    val sender = if (msg.isFromCharacter) item.characterName else item.creatorNickname
    Box(Modifier.fillMaxWidth()) {
        Text(
            text = buildString {
                if (!sender.isNullOrBlank()) append(sender).append(": ")
                append(msg.content.orEmpty())
            },
            color = ProfileStyle.TEXT_PRIMARY,
            fontSize = BUBBLE_FONT.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(if (msg.isFromCharacter) Alignment.CenterStart else Alignment.CenterEnd)
                .clip(RoundedCornerShape(BUBBLE_RADIUS.dp))
                .background(BUBBLE_BACKGROUND)
                .padding(horizontal = BUBBLE_PADDING_H.dp, vertical = BUBBLE_PADDING_V.dp),
        )
    }
}

@Composable
private fun MemoryAvatar(url: String?, name: String?) {
    Box(
        modifier = Modifier
            .size(AVATAR_SIZE.dp)
            .clip(CircleShape)
            .background(ProfileStyle.CARD_PLACEHOLDER),
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = HomeText.transformImageUrl(url),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun MemoryBadge(text: String) {
    Text(
        text = text,
        color = ProfileStyle.TEXT_PRIMARY,
        fontSize = BADGE_FONT.sp,
        modifier = Modifier
            .padding(start = BADGE_GAP.dp)
            .clip(RoundedCornerShape(BADGE_RADIUS.dp))
            .background(BADGE_BACKGROUND)
            .padding(horizontal = BADGE_PADDING_H.dp, vertical = BADGE_PADDING_V.dp),
    )
}

/**
 * 审核状态徽标（`PlotItem.tsx:92-130`）。
 *
 * `review_stage` 的枚举值是 `un_reviewed / pass / failed`（`types/review.ts`），
 * 对应显示 Pending / Passed / Failed —— **值与文案不同轴**，别把原始值直接上屏。
 * 认不出的值不显示（新枚举上线时不崩也不显示错误文字）。
 */
@Composable
private fun ReviewStatusBadge(reviewStage: String?) {
    val key = when (reviewStage) {
        REVIEW_FAILED -> "Failed"
        REVIEW_UNREVIEWED -> "Pending"
        REVIEW_PASS -> "Passed"
        else -> null
    } ?: return
    Text(
        text = rememberLocalizedString(key),
        color = ProfileStyle.TEXT_SECONDARY,
        fontSize = META_FONT.sp,
    )
}

private const val REVIEW_FAILED = "failed"
private const val REVIEW_UNREVIEWED = "un_reviewed"
private const val REVIEW_PASS = "pass"

/** 高 340（`PlotItem.tsx:335`）。 */
private const val CARD_HEIGHT = 340
private const val CARD_RADIUS = 10
private const val CARD_PADDING = 12
private const val TITLE_FONT = 16
private const val META_FONT = 12
private const val META_GAP = 4
private const val AVATAR_SIZE = 36
private const val AVATAR_GAP = 4
private const val BUBBLE_FONT = 13
private const val BUBBLE_GAP = 6
private const val BUBBLE_RADIUS = 8
private const val BUBBLE_PADDING_H = 10
private const val BUBBLE_PADDING_V = 6
private const val BADGE_FONT = 10
private const val BADGE_GAP = 6
private const val BADGE_RADIUS = 4
private const val BADGE_PADDING_H = 5
private const val BADGE_PADDING_V = 2

private val SCRIM_TOP = Color(0x66000000)
private val SCRIM_BOTTOM = Color(0x99000000)
private val BUBBLE_BACKGROUND = Color(0x59000000)
private val BADGE_BACKGROUND = Color(0x66000000)
