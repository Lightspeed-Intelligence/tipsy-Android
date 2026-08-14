package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.R
import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
import ai.lightspeed.tipsy.shell.pages.home.HomeText
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/**
 * 他人主页（W3，进度文档 §2.32）。
 *
 * ## 与自己视角的四处结构差异（`CharacterGrid.tsx:1422-1445` 逐行核实）
 *
 * | | 自己（[ProfileScreen]） | 他人（本文件） |
 * | --- | --- | --- |
 * | 头部按钮 | `Edit Profile` | **关注按钮**（`Follow` / `Following`） |
 * | 钱包卡 | 有 | **无**（`isSelf && UserProfileGems`） |
 * | bio | `RenderBio`（空态占位 + 铅笔） | **`UserBio`**（非空才渲染，无空态） |
 * | tab 栏 | 五图标 | **无**（`renderTabBar` 开头 `if (!isSelf) return null`） |
 *
 * 四统计（`FollowInfo`）**两端都渲染** —— 那不是自己独有的。
 *
 * ## 顶栏有返回箭头、无 UID
 *
 * 他人主页是压栈进来的（`showBackButton = Boolean(userId)`，
 * `user-profile.tsx:159`）。UID 只在 `!isSelf && publicUserIdText && !isDeleted`
 * 时显示（`:162`）—— 本刀**不显示他人 UID**：那三个条件里的 `publicUserIdText`
 * 是自己那份 UID 文本的复用，在他人分支上是否真的上屏未在真机核实，
 * 宁可不显示（少一个元素）也不显示一个可能是**自己 UID** 的字符串。
 *
 * ## 不翻页（不是漏实现）
 *
 * 见 [PublicProfileApi.FIRST_PAGE]：RN 侧他人主页的触底回调打的是**自己**
 * 那条列表，两条 creator 列表都没有分页出口。所以这里没有 `onLoadMore`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicProfileScreen(
    state: PublicProfileState,
    onBackClick: () -> Unit,
    onFollowClick: () -> Unit,
    onRefresh: () -> Unit,
    /** 状态栏高度；与其它页一样是实际 dp。 */
    statusBarPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()

    Box(modifier = modifier.fillMaxSize().background(ProfileStyle.APP_BACKGROUND)) {
        // 渐隐背景图垫底，与自己视角同一套实现（三段 alpha + DstIn）
        PublicProfileBackground(
            url = state.profile?.backgroundImgUrl,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Column(Modifier.fillMaxSize().padding(top = statusBarPadding)) {
            PublicProfileTopBar(onBackClick = onBackClick)

            // ⚠️ 注销用户禁用下拉刷新（`CharacterGrid.tsx:1455`
            // `refreshControl={isDeleted ? undefined : ...}`）—— 不是刷新无效果，
            // 是连手势都没有。PullToRefreshBox 无 enabled 参数，故按条件分叉容器
            if (state.isRefreshEnabled) {
                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    PublicProfileBody(state, gridState, statusBarPadding, onFollowClick)
                }
            } else {
                PublicProfileBody(state, gridState, statusBarPadding, onFollowClick)
            }
        }
    }
}

@Composable
private fun PublicProfileBody(
    state: PublicProfileState,
    gridState: LazyGridState,
    statusBarPadding: Dp,
    onFollowClick: () -> Unit,
) {
    // 冷启动首屏：资料都还没有，整页转圈（同自己视角的判定）
    if (state.profile == null && state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(ProfileStyle.COLUMN_COUNT),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(ProfileStyle.GRID_SPACING.dp),
        verticalArrangement = Arrangement.spacedBy(ProfileStyle.GRID_SPACING.dp),
        modifier = Modifier.fillMaxSize().testTag("public_profile_grid"),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = KEY_HEADER, contentType = CT_HEADER) {
            PublicProfileHeader(
                state = state,
                // 头像行锚点换算同自己视角（屏顶 250dp，`ProfileHeader.tsx:173`）
                topPadding = (AVATAR_TOP_ANCHOR.dp - statusBarPadding - TOP_BAR_HEIGHT.dp)
                    .coerceAtLeast(MIN_HEADER_TOP.dp),
                onFollowClick = onFollowClick,
            )
        }

        when {
            state.isLoading && state.items.isEmpty() -> item(
                span = { GridItemSpan(maxLineSpan) },
                key = KEY_STATUS,
                contentType = CT_STATUS,
            ) {
                Box(
                    Modifier.fillMaxWidth().padding(EMPTY_PADDING.dp)
                        .testTag("public_profile_loading"),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            // 首屏失败且列表为空才显错误（方案 §8.4）
            state.errorMessage != null && state.items.isEmpty() -> item(
                span = { GridItemSpan(maxLineSpan) },
                key = KEY_STATUS,
                contentType = CT_STATUS,
            ) {
                val message = state.errorMessage.orEmpty()
                CenteredNote(
                    text = if (message == ProfileViewModel.FALLBACK_ERROR_KEY) {
                        rememberLocalizedString(message)
                    } else {
                        message
                    },
                    testTag = "public_profile_error",
                )
            }

            state.items.isEmpty() -> item(
                span = { GridItemSpan(maxLineSpan) },
                key = KEY_STATUS,
                contentType = CT_STATUS,
            ) {
                // 空态文案与自己视角创作 tab 同一个 key（`EMPTY_TEXT_MAP.story`
                // = 'No Character'，`CharacterGrid.tsx:1063-1074` 的 tabIndex 0 分支）
                CenteredNote(
                    text = rememberLocalizedString(EMPTY_TEXT_KEY),
                    testTag = "public_profile_empty",
                )
            }

            else -> items(
                items = state.items,
                // dedupeKey 按类型分流（game 带前缀）——统一用 itemId 会让
                // game 与 character 撞 key，LazyGrid 复用错 slot
                key = { it.dedupeKey },
                contentType = { CT_CARD },
            ) { item ->
                // 卡片与自己视角同构：RN 他人分支渲染的也是 CharacterGridItem
                // （`CharacterGrid.tsx:728-735`，只是 isSelf=false）。
                // ⚠️ isSelf=false 的差异是**菜单与批量选择**，那两者本刀两端都没做，
                // 所以此处直接复用 ProfileGridItem 即对等
                ProfileGridItem(
                    item = item,
                    modifier = Modifier.aspectRatio(ProfileStyle.CARD_ASPECT_RATIO),
                )
            }
        }
    }
}

/**
 * 顶栏：只有返回箭头。
 *
 * 无 UID、无设置图标（那两个是自己视角的，见文件头注释）。
 * 返回箭头资产复用搜索页搬来的那张（`ic_search_back`，同一个
 * `chat/header_back.png` 来源）。
 */
@Composable
private fun PublicProfileTopBar(onBackClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(TOP_BAR_HEIGHT.dp)
            .padding(horizontal = TOP_BAR_H_PADDING.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_search_back),
            contentDescription = rememberLocalizedString("Back"),
            modifier = Modifier
                .size(BACK_ICON_SIZE.dp)
                .clickable(onClick = onBackClick)
                .testTag("public_profile_back"),
        )
    }
}

/**
 * 他人主页头部：头像 / 昵称 / 关注按钮 / 四统计 / bio（非空才有）。
 *
 * ⚠️ **没有钱包卡**（`isSelf && UserProfileGems`）。
 */
@Composable
private fun PublicProfileHeader(
    state: PublicProfileState,
    topPadding: Dp,
    onFollowClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = topPadding)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ProfileStyle.STATS_HORIZONTAL_PADDING.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = state.profile?.avatarUrl,
                contentDescription = state.profile?.nickname,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(ProfileStyle.AVATAR_SIZE.dp)
                    .clip(CircleShape)
                    .background(ProfileStyle.CARD_PLACEHOLDER)
                    .testTag("public_profile_avatar"),
            )

            Text(
                text = state.profile?.nickname.orEmpty(),
                color = ProfileStyle.TEXT_PRIMARY,
                fontSize = NICKNAME_FONT.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = AVATAR_TEXT_GAP.dp)
                    .weight(1f),
            )

            if (state.showFollowButton) {
                FollowButton(
                    isFollowed = state.isFollowed,
                    isPending = state.isFollowPending,
                    onClick = onFollowClick,
                )
            }
        }

        Spacer(Modifier.height(STATS_TOP_GAP.dp))
        // 四统计两端都有；交叉映射那个坑同样适用（见 ProfileStats）。
        // ⚠️ 他人主页的 followers/following 数字**不可点** —— Follow 列表出口
        // 尚未定案（§2.25 owner 决策点 1：RN 无 FollowSurface）。
        // 自己视角那两个数字可点是因为它有 AppRoute.Follow 备好（当前也被拒绝）
        PublicStatsRow(stats = state.stats)

        val bio = state.bio
        if (bio != null) {
            // ⚠️ 只在非空时渲染，**无空态占位** —— 他人主页走 UserBio
            // （`CharacterGrid.tsx:1437` `{!isSelf && bio && bio.trim() && <UserBio/>}`），
            // 与自己视角 RenderBio 的「空了显示 No bio yet」不同
            Spacer(Modifier.height(BIO_TOP_GAP.dp))
            PublicBio(bio = bio)
        }
        Spacer(Modifier.height(HEADER_BOTTOM_GAP.dp))
    }
}

/**
 * 关注按钮（`ProfileHeader.tsx:206-224`）。
 *
 * 未关注：实心 + `+` 前缀 + `Follow`；已关注：描边 + `Following`（无 `+`）。
 * 在飞期间禁用（防连点，见 [PublicProfileViewModel.onFollowClick]）。
 */
@Composable
private fun FollowButton(
    isFollowed: Boolean,
    isPending: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(FOLLOW_BUTTON_RADIUS.dp)
    val base = if (isFollowed) {
        Modifier.border(width = 1.dp, color = ProfileStyle.BUTTON_BORDER, shape = shape)
    } else {
        Modifier.background(color = FOLLOW_BUTTON_FILL, shape = shape)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .then(base)
            // enabled=false 时仍渲染但不响应 —— 比整个换成占位更稳（不跳布局）
            .clickable(enabled = !isPending, onClick = onClick)
            .padding(
                horizontal = FOLLOW_BUTTON_H_PADDING.dp,
                vertical = FOLLOW_BUTTON_V_PADDING.dp,
            )
            .testTag("public_profile_follow"),
    ) {
        if (!isFollowed) {
            Text(
                text = "+",
                color = ProfileStyle.TEXT_PRIMARY,
                fontSize = FOLLOW_BUTTON_FONT.sp,
                modifier = Modifier.padding(end = FOLLOW_PLUS_GAP.dp),
            )
        }
        Text(
            // 两个 key 都已在 SHELL_KEYS（`Follow` / `Following`）
            text = rememberLocalizedString(if (isFollowed) "Following" else "Follow"),
            color = ProfileStyle.TEXT_PRIMARY,
            fontSize = FOLLOW_BUTTON_FONT.sp,
        )
    }
}

/** 四统计（他人视角：全部不可点，见调用点注释）。 */
@Composable
private fun PublicStatsRow(stats: ProfileStats, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ProfileStyle.STATS_HORIZONTAL_PADDING.dp),
        horizontalArrangement = Arrangement.spacedBy(ProfileStyle.STATS_ITEM_GAP.dp),
    ) {
        // ⚠️ 交叉映射：Followers 标签配 followersLabelCount（接口 followees_count）
        PublicStatItem("Followers", stats.followersLabelCount, "public_profile_stat_followers")
        PublicStatItem("Following", stats.followingLabelCount, "public_profile_stat_following")
        PublicStatItem("Likes", stats.likesCount, "public_profile_stat_likes")
        PublicStatItem(
            "Interactions",
            stats.interactionsCount,
            "public_profile_stat_interactions",
        )
    }
}

@Composable
private fun PublicStatItem(labelKey: String, count: Long, tag: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.testTag(tag),
    ) {
        Text(
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

/**
 * 他人 bio（`UserBio.tsx`）：白 8% 圆角容器，**默认折叠 3 行**。
 *
 * RN 有展开/收起按钮（超过 maxLines 时右下角放大镜图标）——
 * 本刀只做**折叠态 3 行截断**，不做展开：那个交互依赖 `onTextLayout`
 * 测量实际行数才决定按钮是否出现（`UserBio.tsx:56-72`），
 * Compose 侧要用 `onTextLayout` + `hasVisualOverflow` 重做一遍。
 * 属独立视觉增强，**先保证 bio 能看到**。
 *
 * ⚠️ maxLines 传 3（`CharacterGrid.tsx:1440` 显式 `maxLines={3}`），
 * 不是 `UserBio` 的默认 4。
 */
@Composable
private fun PublicBio(bio: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = BIO_MARGIN_H.dp)
            .clip(RoundedCornerShape(BIO_RADIUS.dp))
            .background(BIO_BACKGROUND)
            .padding(
                start = BIO_PADDING_START.dp,
                end = BIO_PADDING_END.dp,
                top = BIO_PADDING_V.dp,
                bottom = BIO_PADDING_V.dp,
            )
            .testTag("public_profile_bio"),
    ) {
        Text(
            text = bio,
            color = ProfileStyle.TEXT_SECONDARY,
            fontSize = BIO_FONT.sp,
            maxLines = BIO_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 渐隐背景图 —— 与自己视角同一套实现（三段 alpha + `DstIn`）。 */
@Composable
private fun PublicProfileBackground(url: String?, modifier: Modifier = Modifier) {
    val masked = modifier
        .fillMaxWidth()
        .aspectRatio(1f)
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                brush = Brush.verticalGradient(
                    BG_FADE_FULL to Color.Black,
                    BG_FADE_LOW to Color.Black.copy(alpha = BG_FADE_LOW_ALPHA),
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    if (url.isNullOrBlank()) {
        Image(
            painter = painterResource(R.drawable.ic_profile_bg_default),
            contentDescription = null, // 纯装饰
            contentScale = ContentScale.Crop,
            modifier = masked,
        )
    } else {
        AsyncImage(
            model = HomeText.transformImageUrl(url),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = masked,
        )
    }
}

@Composable
private fun CenteredNote(text: String, testTag: String) {
    Box(
        Modifier.fillMaxWidth().padding(EMPTY_PADDING.dp).testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = ProfileStyle.TEXT_SECONDARY,
            fontSize = EMPTY_FONT.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 空态文案 key —— 与自己视角创作 tab 同一个（`EMPTY_TEXT_MAP.story`
 * = `No Character`）。已在 SHELL_KEYS，本刀零新增词条。
 */
private const val EMPTY_TEXT_KEY = "No Character"

private const val KEY_HEADER = "public_header"
private const val KEY_STATUS = "public_status"
private const val CT_HEADER = "public_header"
private const val CT_STATUS = "public_status"
private const val CT_CARD = "public_card"

/** 头像行锚定屏顶 250dp（`ProfileHeader.tsx:173`），同自己视角。 */
private const val AVATAR_TOP_ANCHOR = 250
private const val MIN_HEADER_TOP = 12
private const val TOP_BAR_HEIGHT = 44
private const val TOP_BAR_H_PADDING = 12
private const val BACK_ICON_SIZE = 24

private const val AVATAR_TEXT_GAP = 12
private const val NICKNAME_FONT = 18
private const val STATS_TOP_GAP = 16
private const val HEADER_BOTTOM_GAP = 12

private const val FOLLOW_BUTTON_FONT = 12
private const val FOLLOW_BUTTON_RADIUS = 16
private const val FOLLOW_BUTTON_H_PADDING = 12
private const val FOLLOW_BUTTON_V_PADDING = 6
private const val FOLLOW_PLUS_GAP = 2

/** 未关注时的实心底色（`ProfileHeader.tsx` 的 followButton 系样式）。 */
private val FOLLOW_BUTTON_FILL = Color(0xFFE24A6E)

private const val BIO_TOP_GAP = 10
private const val BIO_MARGIN_H = 10
private const val BIO_RADIUS = 8
private const val BIO_PADDING_START = 16
private const val BIO_PADDING_END = 8
private const val BIO_PADDING_V = 8
private const val BIO_FONT = 12

/** `CharacterGrid.tsx:1440` 显式传 3（不是 UserBio 的默认 4）。 */
private const val BIO_MAX_LINES = 3

/** `rgba(255,255,255,0.08)`（`UserBio.tsx:143`）。 */
private val BIO_BACKGROUND = Color(0x14FFFFFF)

private const val EMPTY_PADDING = 32
private const val EMPTY_FONT = 14

// 渐隐遮罩三段（`ProfileBackground.tsx` 的 locations/alpha），同自己视角
private const val BG_FADE_FULL = 0.36f
private const val BG_FADE_LOW = 0.9f
private const val BG_FADE_LOW_ALPHA = 0.1f
