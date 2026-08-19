package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.R
import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
import ai.lightspeed.tipsy.shell.user.CurrentUser
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/**
 * Profile 顶部：头像（含 P7 头像框）/ 昵称 + 社交渠道图标 / Edit Profile 按钮 /
 * 四个统计数字 / bio 区。
 *
 * **UID 不在这里** —— RN 把它放在悬浮顶栏左侧（`user-profile.tsx:656-674`，
 * 带复制图标），见 `ProfileScreen` 的 `ProfileTopBar`。第一刀曾放在昵称下方，
 * 是没核实位置的错，P2 已按实测挪走。
 *
 * ## 本刀未做的两处
 *
 * 1. **点头像/空白处换背景**（`ProfileHeader.tsx:172` 整个 header 包在
 *    `TouchableWithoutFeedback`，走相册选择）—— 属编辑动作包；
 *    渐隐背景图本体已在 `ProfileScreen` 落地
 * 2. **滚动驱动的悬浮 header**（`user-profile.tsx:488-535`：scrollY 150→450
 *    插值，背景色渐显、小号头像+昵称渐入、UID 渐出）—— 纯视觉增强，
 *    静态版顶栏已就位
 *
 * ## 头像框（avatar decoration，P7）
 *
 * RN 侧 `TipsyAvatar` 支持 `avatarDecorationCode`，配置由
 * `index.surfaces.js` 顶层的 `hydrateAvatarDecorationConfigs` 拉取，
 * 而进度 §2.19 记了那三个 hydrate **静默捕获失败**、失败表现为「头像框空掉」
 * （全新安装必现，升级安装因 MMKV 残留被掩蔽）。壳侧不复刻那套持久层：
 * code → URL 由 `ProfileViewModel.resolveAvatarDecoration` 每次刷新链解析
 * （`AvatarDecorationApi`），本组件只收**已解析的 URL**；URL 为空不绘制，
 * 不能把 code 当 URL 直接交给 Coil。框画满头像同一个 65dp 盒子 ——
 * RN 是 58dp 头像内嵌 65dp 容器、框画 65dp（`TipsyAvatar.tsx:87-92`），
 * 壳的头像本体本就简化为满 65dp 圆（P2 已记），框取同盒即对等。
 *
 * @param topPadding 头像行距屏顶的补偿间距，由 `ProfileScreen` 按
 *   「屏顶 250dp 锚点」换算（RN 是悬浮 header + `paddingTop: 250 - headerOffset`
 *   的配合，`ProfileHeader.tsx:173`，让头像行固定落在背景图下部）
 */
@Composable
fun ProfileHeaderSection(
    user: CurrentUser?,
    stats: ProfileStats,
    wallet: ProfileWallet,
    topPadding: Dp,
    onEditProfileClick: () -> Unit,
    onFollowersClick: () -> Unit,
    onFollowingClick: () -> Unit,
    onWalletAction: (ProfileWalletAction) -> Unit,
    avatarDecorationImageUrl: String? = null,
    onSocialLinkClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = topPadding)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ProfileStyle.STATS_HORIZONTAL_PADDING.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(ProfileStyle.AVATAR_SIZE.dp)) {
                AsyncImage(
                    model = user?.avatarUrl,
                    contentDescription = user?.nickname,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .clip(CircleShape)
                        .background(ProfileStyle.CARD_PLACEHOLDER)
                        .testTag("profile_avatar"),
                )
                avatarDecorationImageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .matchParentSize()
                            .testTag("profile_avatar_decoration"),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .padding(start = AVATAR_TEXT_GAP.dp)
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(NICKNAME_LINKS_GAP.dp),
            ) {
                Text(
                    text = user?.nickname.orEmpty(),
                    color = ProfileStyle.TEXT_PRIMARY,
                    fontSize = NICKNAME_FONT.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 渠道图标（P7）：昵称下方一行社交平台链接。空表整行不渲染，
                // 让 spacedBy 不产生空隙（对齐 RN `SocialLinksDisplay` 返回 null）
                val socialLinks = ProfileSocialLinks.visibleLinks(user?.socialLinks.orEmpty())
                if (socialLinks.isNotEmpty()) {
                    ProfileSocialLinksRow(links = socialLinks, onLinkClick = onSocialLinkClick)
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

        Spacer(Modifier.height(BIO_TOP_GAP.dp))
        ProfileBioRow(bio = user?.bio, onEditClick = onEditProfileClick)
        Spacer(Modifier.height(BIO_BOTTOM_GAP.dp))

        // 钱包卡只在自己主页有（`CharacterGrid.tsx:1431` `isSelf && <UserProfileGems/>`）
        // —— 本刀只有自己视角，无条件渲染；接他人主页时要跟 bio 一起加 isSelf 分流
        ProfileWalletCard(wallet = wallet, onAction = onWalletAction)
        Spacer(Modifier.height(WALLET_BOTTOM_GAP.dp))
    }
}

/**
 * bio 区（`renderBio.tsx`）：白 8% 圆角容器，一行截断，右侧编辑铅笔。
 *
 * 空态文案 `No bio yet. Add one now.`（key = 英文原文，词条已在 SHELL_KEYS）。
 * RN 只有**铅笔可点**（`Pressable` 只包 Image），整行不可点 —— 保持一致。
 * 点击目标与 Edit Profile 按钮相同：RN 都是 `setUserProfileOpen(true)` 开
 * `EditProfileDrawer`，壳侧统一走 `AppRoute.EditProfile`（当前明确拒绝）。
 */
@Composable
private fun ProfileBioRow(bio: String?, onEditClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = BIO_MARGIN_H.dp)
            .clip(RoundedCornerShape(BIO_RADIUS.dp))
            .background(BIO_BACKGROUND)
            .padding(BIO_PADDING.dp)
            .testTag("profile_bio"),
    ) {
        Text(
            text = bio?.takeIf { it.isNotBlank() }
                ?: rememberLocalizedString("No bio yet. Add one now."),
            color = ProfileStyle.TEXT_SECONDARY,
            fontSize = BIO_FONT.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(end = BIO_TEXT_GAP.dp),
        )
        Image(
            painter = painterResource(R.drawable.ic_profile_bio_edit),
            contentDescription = rememberLocalizedString("Edit Profile"),
            modifier = Modifier
                .size(BIO_EDIT_ICON.dp)
                .clickable(onClick = onEditClick)
                .testTag("profile_bio_edit"),
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

private const val AVATAR_TEXT_GAP = 12
private const val NICKNAME_FONT = 18
/** `ProfileHeader.tsx` styles `textContainer.gap: 6` —— 昵称与图标行的间距。 */
private const val NICKNAME_LINKS_GAP = 6
private const val EDIT_BUTTON_FONT = 12
private const val EDIT_BUTTON_RADIUS = 16
private const val EDIT_BUTTON_H_PADDING = 12
private const val EDIT_BUTTON_V_PADDING = 6
private const val STATS_TOP_GAP = 16

// bio 区尺寸照 `renderBio.tsx` styles：marginH 10 / mb 12 / radius 8 / padding 10
private const val BIO_TOP_GAP = 8
private const val BIO_BOTTOM_GAP = 12
private const val WALLET_BOTTOM_GAP = 12
private const val BIO_MARGIN_H = 10
private const val BIO_RADIUS = 8
private const val BIO_PADDING = 10
private const val BIO_FONT = 14
private const val BIO_TEXT_GAP = 8
private const val BIO_EDIT_ICON = 24

/** `rgba(255,255,255,0.08)`（`renderBio.tsx` bioContainer）。 */
private val BIO_BACKGROUND = Color(0x14FFFFFF)
