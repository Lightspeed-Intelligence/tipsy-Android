package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.R
import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
import ai.lightspeed.tipsy.shell.pages.home.HomeText
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.compose.LocalPlatformContext
import coil3.request.transformations

/**
 * 创作 tab 的三列网格卡（`CharacterGridItem.tsx` 2 千行里的展示部分；
 * story / game 卡的角标结构同构，`StoryItem.tsx` / `GameGridItem.tsx`）。
 *
 * ## P4 补齐的角标层（自下而上）
 *
 * 1. 封面（含模糊，条件见 [ProfileCreatedItem.shouldBlurCover]；模糊在
 *    Coil 解码层做，理由见 [CoverBlurTransformation]）
 * 2. 底部渐变 + 名称 + 计数行（曝光仅 character 且公开；消息数三种卡都有）
 * 3. 左上：审核角标（rejected/pending）＞ 私密锁 ＞ story/18+ 标签
 *    —— **同一位置三选一**，优先级是 RN 的三元链
 *    （`CharacterGridItem.tsx:780-812`）
 * 4. 右上：置顶 Pin（`is_pinned`）
 * 5. `final_hit < 2`：整卡换成「不可用」遮罩，其余全部不画
 *
 * ## 本刀仍不做
 *
 * - **⋮ 菜单与动作**（编辑/删除/置顶）—— P5。菜单按钮届时必须是可点击组件
 *   吃掉事件（iOS 踩过装饰 View 点击穿透进详情页）
 * - **卡片点击进详情** —— 目标页（角色详情/ChatDetail）不在已启用集合
 * - 比赛 winner 徽章（`useCharacterDisplayBadge`，运营配置源）与水印
 *   （`watermarkRenderKind`，依赖水印配置 hydrate）
 */
@Composable
fun ProfileGridItem(
    item: ProfileCreatedItem,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(CARD_RADIUS.dp))
            .background(ProfileStyle.CARD_PLACEHOLDER)
            // dedupeKey 是服务端 id（game 带前缀），稳定且不含用户文本（方案 §9.4）
            .testTag("profile_created_card_${item.dedupeKey}"),
    ) {
        if (item.isMaskedUnavailable) {
            // final_hit < 2：整卡不可用，遮罩之外什么都不画（CharacterGridItem.tsx:548-559）
            UnavailableMask()
            return@Box
        }

        val cover = item.coverUrl
        if (!cover.isNullOrBlank()) {
            val url = HomeText.transformImageUrl(cover)
            AsyncImage(
                // 模糊是解码层变换：全 API 版本一致，且与原图各占一份内存缓存
                model = if (item.shouldBlurCover) {
                    ImageRequest.Builder(LocalPlatformContext.current)
                        .data(url)
                        .transformations(CoverBlurTransformation())
                        .build()
                } else {
                    url
                },
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 底部渐变 + 名称 + 计数行。渐变是为了让白字在任意封面上都可读
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xCC000000)),
                    ),
                )
                .padding(horizontal = CARD_TEXT_PADDING.dp, vertical = CARD_TEXT_PADDING.dp),
        ) {
            Text(
                text = item.name.orEmpty(),
                color = ProfileStyle.CARD_TITLE,
                fontSize = CARD_TITLE_FONT.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(META_GAP.dp),
                modifier = Modifier.padding(top = META_TOP_GAP.dp),
            ) {
                // 曝光数：仅 character 卡且公开（CharacterGridItem.tsx:619 `isSelf && is_public`；
                // story/game 卡没有这一项）
                if (item.type == ProfileItemType.CHARACTER && item.isPublic) {
                    MetaCount(
                        icon = R.drawable.ic_profile_exposure,
                        text = ProfileText.formatCountMaxThreeDigits(item.exposureCount),
                    )
                }
                MetaCount(
                    icon = R.drawable.ic_profile_msg_count,
                    // character 卡是三位有效数字缩写；story/game 卡走 formatNumber
                    //（Home 的 formatMessageCount 同源，StoryItem.tsx:656 / GameGridItem.tsx:339）
                    text = if (item.type == ProfileItemType.CHARACTER) {
                        ProfileText.formatCountMaxThreeDigits(item.messageCount)
                    } else {
                        HomeText.formatMessageCount(item.messageCount)
                    },
                )
            }
        }

        // 左上角标：审核 ＞ 私密锁 ＞ story/18+ 标签（三选一，见类注释）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BADGE_GAP.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(BADGE_MARGIN.dp),
        ) {
            val reviewBadge = item.reviewBadge
            when {
                reviewBadge != null -> ReviewBadge(reviewBadge)
                !item.isPublic -> Image(
                    painter = painterResource(R.drawable.ic_profile_lock),
                    contentDescription = rememberLocalizedString("Private"),
                    modifier = Modifier
                        .size(LOCK_ICON.dp)
                        .testTag("profile_card_lock"),
                )
                else -> {
                    if (item.showStoryTag) {
                        Image(
                            painter = painterResource(R.drawable.ic_profile_tag_story),
                            contentDescription = null, // 装饰性类型标
                            modifier = Modifier.size(STORY_TAG_ICON.dp),
                        )
                    }
                    if (item.showNsfwTag) {
                        Image(
                            painter = painterResource(R.drawable.ic_profile_tag_18),
                            contentDescription = "18+",
                            modifier = Modifier
                                .width(NSFW_TAG_W.dp)
                                .height(NSFW_TAG_H.dp),
                        )
                    }
                }
            }
        }

        // 右上：置顶 Pin（statusIconRow，top 4 / right 4）
        if (item.isPinned) {
            Image(
                painter = painterResource(R.drawable.ic_profile_pin),
                contentDescription = rememberLocalizedString("Pin"),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(PIN_MARGIN.dp)
                    .size(PIN_ICON.dp)
                    .testTag("profile_card_pin"),
            )
        }
    }
}

/**
 * 审核角标（`ReviewStatusBadge.tsx`）：黑 40% 圆角胶囊，图标 + 文案。
 * pending/rejected 各带图标；approved 在 RN 里不渲染（上游已判 null）。
 */
@Composable
private fun ReviewBadge(badge: ProfileReviewBadge) {
    val (icon, key) = when (badge) {
        ProfileReviewBadge.PENDING -> R.drawable.ic_profile_review_pending to "Pending"
        ProfileReviewBadge.REJECTED -> R.drawable.ic_profile_review_fail to "Rejected"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(REVIEW_BADGE_RADIUS.dp))
            .background(REVIEW_BADGE_BACKGROUND)
            .padding(end = REVIEW_BADGE_PADDING_END.dp)
            .testTag("profile_card_review_${key.lowercase()}"),
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null, // 文案就在旁边
            modifier = Modifier.size(REVIEW_ICON.dp),
        )
        Text(
            text = rememberLocalizedString(key),
            color = REVIEW_BADGE_TEXT,
            fontSize = REVIEW_BADGE_FONT.sp,
        )
    }
}

/**
 * `final_hit < 2` 的不可用遮罩（`CharacterGridItem.tsx:548-559`）：
 * 白 6% 底 + 锁 + `Currently unavailable`。
 * ⚠️ 该词条是 key≠value 的实例（en 值是 "More to come"，脚本注释点过名）——
 * 恰好证明「运行时不得拿 key 当英文文案」，必须走 L10n。
 */
@Composable
private fun UnavailableMask() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MASK_GAP.dp, Alignment.CenterVertically),
        modifier = Modifier
            .fillMaxSize()
            .background(MASK_BACKGROUND)
            .padding(horizontal = MASK_PADDING_H.dp)
            .testTag("profile_card_unavailable"),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_profile_lock),
            contentDescription = null,
            modifier = Modifier.size(MASK_LOCK_ICON.dp),
        )
        Text(
            text = rememberLocalizedString("Currently unavailable"),
            color = ProfileStyle.TEXT_SECONDARY,
            fontSize = MASK_FONT.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MetaCount(icon: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(icon),
            contentDescription = null, // 数字紧随其后
            modifier = Modifier.size(META_ICON.dp),
        )
        Text(
            text = text,
            color = META_TEXT,
            fontSize = META_FONT.sp,
            modifier = Modifier.padding(start = META_ICON_GAP.dp),
        )
    }
}

private const val CARD_RADIUS = 8
private const val CARD_TITLE_FONT = 12
private const val CARD_TEXT_PADDING = 6

// 计数行（characterMetaIcon 13 / characterMetaText 10sp 白 70%）
private const val META_ICON = 13
private const val META_FONT = 10
private const val META_GAP = 8
private const val META_ICON_GAP = 3
private const val META_TOP_GAP = 2
private val META_TEXT = Color(0xB3FFFFFF)

// 左上角标（reviewBadge top/left 10 → 统一 margin；lock/story 18、18+ 26×16）
private const val BADGE_MARGIN = 6
private const val BADGE_GAP = 4
private const val LOCK_ICON = 18
private const val STORY_TAG_ICON = 18
private const val NSFW_TAG_W = 26
private const val NSFW_TAG_H = 16
private const val REVIEW_ICON = 16
private const val REVIEW_BADGE_FONT = 10
private const val REVIEW_BADGE_RADIUS = 100
private const val REVIEW_BADGE_PADDING_END = 6
private val REVIEW_BADGE_BACKGROUND = Color(0x66000000)
private val REVIEW_BADGE_TEXT = Color(0xCCFFFFFF)

// 右上 Pin（statusIconRow top/right 4，图标 18）
private const val PIN_MARGIN = 4
private const val PIN_ICON = 18

// 不可用遮罩（maskCover：白 6% / gap 8 / paddingH 8）
private const val MASK_GAP = 8
private const val MASK_PADDING_H = 8
private const val MASK_LOCK_ICON = 24
private const val MASK_FONT = 12
private val MASK_BACKGROUND = Color(0x0FFFFFFF)
