package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
import ai.lightspeed.tipsy.shell.user.CurrentUser
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/**
 * Profile 顶部：头像 / 昵称 / UID / 四个统计数字 / Edit Profile 按钮。
 *
 * ## 本刀未做的两处视觉
 *
 * 1. **渐隐背景图**（`ProfileBackground.tsx` 用 MaskedView + LinearGradient
 *    locations `[0.36,0.9,1]`）—— 需要 Compose 侧的遮罩方案，且 RN 那里
 *    「点空白处即换背景」（`ProfileHeader.tsx:172` 整个 header 包在
 *    `TouchableWithoutFeedback`），换背景走相册选择，属编辑动作，不在本刀
 * 2. **滚动驱动的悬浮 header**（`user-profile.tsx:488-535`：scrollY 150→450
 *    插值，背景色渐显、小号头像+昵称渐入、UID 渐出）—— 纯视觉增强，
 *    先把数据链路跑通
 *
 * ## ⚠️ 头像框（avatar decoration）未做
 *
 * RN 侧 `TipsyAvatar` 支持 `avatarDecorationCode`，配置由
 * `index.surfaces.js` 顶层的 `hydrateAvatarDecorationConfigs` 拉取，
 * 而进度 §2.19 记了那三个 hydrate **静默捕获失败**、失败表现为「头像框空掉」
 * （全新安装必现，升级安装因 MMKV 残留被掩蔽）。Profile 是头像框最显眼的页面。
 * 本刀不做，避免在一个已知会静默失败的配置源上再叠一层。
 */
@Composable
fun ProfileHeaderSection(
    user: CurrentUser?,
    stats: ProfileStats,
    onEditProfileClick: () -> Unit,
    onUidClick: () -> Unit,
    onFollowersClick: () -> Unit,
    onFollowingClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = HEADER_TOP_PADDING.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ProfileStyle.STATS_HORIZONTAL_PADDING.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = user?.avatarUrl,
                contentDescription = user?.nickname,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(ProfileStyle.AVATAR_SIZE.dp)
                    .clip(CircleShape)
                    .background(ProfileStyle.CARD_PLACEHOLDER)
                    .testTag("profile_avatar"),
            )

            Column(
                modifier = Modifier
                    .padding(start = AVATAR_TEXT_GAP.dp)
                    .weight(1f),
            ) {
                Text(
                    text = user?.nickname.orEmpty(),
                    color = ProfileStyle.TEXT_PRIMARY,
                    fontSize = NICKNAME_FONT.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (user != null) {
                    Spacer(Modifier.height(UID_TOP_GAP.dp))
                    // ⚠️ `UID: ` 前缀**不进 i18n** —— RN 侧是裸文本不走 t()
                    //（`user-profile.tsx:665`），翻了反而与现网不一致。
                    // 截断规则在 ProfileText.formatUid（前 3 + 后 3，对齐 utils/func.ts:277）
                    Text(
                        text = "UID: " + ProfileText.formatUid(user.userId),
                        color = ProfileStyle.TEXT_TERTIARY,
                        fontSize = UID_FONT.sp,
                        modifier = Modifier
                            .clickable(onClick = onUidClick)
                            .testTag("profile_uid"),
                    )
                }
            }

            Text(
                text = rememberLocalizedString("Edit Profile"),
                color = ProfileStyle.TEXT_PRIMARY,
                fontSize = EDIT_BUTTON_FONT.sp,
                modifier = Modifier
                    .border(
                        width = 1.dp,
                        color = ProfileStyle.BUTTON_BORDER,
                        shape = RoundedCornerShape(EDIT_BUTTON_RADIUS.dp),
                    )
                    .clickable(onClick = onEditProfileClick)
                    .padding(
                        horizontal = EDIT_BUTTON_H_PADDING.dp,
                        vertical = EDIT_BUTTON_V_PADDING.dp,
                    )
                    .testTag("profile_edit"),
            )
        }

        Spacer(Modifier.height(STATS_TOP_GAP.dp))
        ProfileStatsRow(
            stats = stats,
            onFollowersClick = onFollowersClick,
            onFollowingClick = onFollowingClick,
        )
    }
}

/**
 * 四个统计数字。
 *
 * ⚠️ **标签与字段是交叉的** —— 这里必须用 [ProfileStats.followersLabelCount]
 * 配 "Followers"、[ProfileStats.followingLabelCount] 配 "Following"。
 * 那两个属性名已经说明了各自给哪个标签用，别按接口字段名重排（见 [ProfileStats] 类注释）。
 *
 * 前两个可点（跳 Follow 列表），后两个不可点 —— 对齐 `FollowInfo.tsx:47-87`
 * （只有 `userId` 存在时前两项才带 `onPress`）。
 */
@Composable
private fun ProfileStatsRow(
    stats: ProfileStats,
    onFollowersClick: () -> Unit,
    onFollowingClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ProfileStyle.STATS_HORIZONTAL_PADDING.dp),
        horizontalArrangement = Arrangement.spacedBy(ProfileStyle.STATS_ITEM_GAP.dp),
    ) {
        StatItem("Followers", stats.followersLabelCount, onFollowersClick, "profile_stat_followers")
        StatItem("Following", stats.followingLabelCount, onFollowingClick, "profile_stat_following")
        StatItem("Likes", stats.likesCount, null, "profile_stat_likes")
        StatItem("Interactions", stats.interactionsCount, null, "profile_stat_interactions")
    }
}

@Composable
private fun StatItem(labelKey: String, count: Long, onClick: (() -> Unit)?, tag: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = (if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .testTag(tag),
    ) {
        Text(
            // 去尾法格式化，与 Home 的 formatMessageCount 规则不同，见 ProfileText 注释
            text = ProfileText.formatLargeNumber(count),
            color = ProfileStyle.TEXT_PRIMARY,
            fontSize = ProfileStyle.STATS_COUNT_FONT.sp,
        )
        Spacer(Modifier.height(ProfileStyle.STATS_LABEL_GAP.dp))
        Text(
            text = rememberLocalizedString(labelKey),
            color = ProfileStyle.TEXT_SECONDARY,
            fontSize = ProfileStyle.STATS_LABEL_FONT.sp,
        )
    }
}

private const val HEADER_TOP_PADDING = 12
private const val AVATAR_TEXT_GAP = 12
private const val NICKNAME_FONT = 18
private const val UID_FONT = 12
private const val UID_TOP_GAP = 4
private const val EDIT_BUTTON_FONT = 12
private const val EDIT_BUTTON_RADIUS = 16
private const val EDIT_BUTTON_H_PADDING = 12
private const val EDIT_BUTTON_V_PADDING = 6
private const val STATS_TOP_GAP = 16
