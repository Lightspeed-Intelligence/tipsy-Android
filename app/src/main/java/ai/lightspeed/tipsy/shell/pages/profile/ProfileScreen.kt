package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.R
import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
import ai.lightspeed.tipsy.shell.pages.home.HomeText
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * Profile（自己视角）首屏，W3 第一刀（创作 + 记忆两个 tab 已接数据源）+
 * P2 头部视觉（渐隐背景图 / bio / 顶栏 UID 与设置图标）。
 *
 * ## 渐隐背景图在最底层，内容滚在它上面
 *
 * RN 的 `ProfileBackground` 是 absolute 定位在列表**后面**（`user-profile.tsx:564`），
 * 列表内容滚动时背景不动。壳等价：根 Box 第一层画背景（宽 = 屏宽、1:1、
 * 三段 alpha 渐隐），LazyVerticalGrid 全透明滚在上面。
 *
 * ## 头像行锚定屏顶 250dp
 *
 * RN 用「悬浮 header 高度 `headerOffset = top + 50`」+「header 内
 * `paddingTop: 250 - headerOffset`」配合，让头像行固定落在屏顶 250dp
 * （`ProfileHeader.tsx:173`）。壳的顶栏在普通布局流里，等价换算：
 * header item 的补偿间距 = 250 - statusBar - 顶栏高。
 *
 * ## 头部与 tab 栏都随列表滚动，tab 栏滚出屏顶后浮出一份
 *
 * 对齐 RN 的 `ListHeaderComponent`（`CharacterGrid.tsx:1421-1447`）与
 * `renderTabBar(floating)` + `stickyTabsY` 判定（`CharacterGrid.tsx:1215/1322`）。
 * RN 浮层带 LinearGradient 背景，壳先用纯色近似（视觉 diff 属验收阶段）。
 *
 * ## tab 栏纯图标无文字
 *
 * 五个 24×24 图标（`CharacterGrid.tsx:868` `tabList`），选中态换 `_press` 资源
 * 而不是 tint（RN 是成对 PNG）。**顺序 = `active_tab_index`，不能调**
 * （见 [ProfileTab] 类注释）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    state: ProfileState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onTabSelected: (ProfileTab) -> Unit,
    onEditProfileClick: () -> Unit,
    onUidClick: () -> Unit,
    onFollowersClick: () -> Unit,
    onFollowingClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onWalletAction: (ProfileWalletAction) -> Unit,
    avatarDecorationImageUrl: String? = null,
    onSocialLinkClick: (String) -> Unit = {},
    /** 状态栏高度；与 `HomeScreen` 一样是实际 dp，不参与 `.s` 缩放。 */
    statusBarPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    // 触底续拉。用 derivedStateOf 而非每帧算 —— 后者会让整页随滚动重组
    // （与 HomeScreen 同一套写法）
    val shouldLoadMore by remember(gridState) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) return@derivedStateOf false
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index
                ?: return@derivedStateOf false
            // RN 的 onEndReachedThreshold={0.1}（CharacterGrid.tsx:1399）比 Home 的
            // 0.5 更靠后 —— 提前量取可见窗口的 1/10
            lastVisible >= total - (info.visibleItemsInfo.size / 10).coerceAtLeast(1) - 1
        }
    }
    LaunchedEffect(gridState) {
        snapshotFlow { shouldLoadMore }.collect { if (it) onLoadMore() }
    }

    // 浮动 tab 栏判定：inline tab 栏（item index=1）的顶边滚出可视区
    // —— 对齐 RN 的 `scrollY > stickyTabsY`
    val showFloatingTabBar by remember(gridState) {
        derivedStateOf {
            val bar = gridState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == TAB_BAR_ITEM_INDEX }
            gridState.firstVisibleItemIndex > TAB_BAR_ITEM_INDEX ||
                (bar != null && bar.offset.y < 0)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(ProfileStyle.APP_BACKGROUND)) {
        // 背景图垫底，延伸到状态栏之后（内容 Column 才做 statusBar 缩进）
        ProfileBackgroundImage(
            url = state.user?.backgroundImgUrl,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Column(Modifier.fillMaxSize().padding(top = statusBarPadding)) {
            ProfileTopBar(
                userId = state.user?.userId,
                onUidClick = onUidClick,
                onSettingsClick = onSettingsClick,
            )

            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.user == null && state.isInitialLoading) {
                    // 冷启动首屏：头部也还没有数据，整页转圈。
                    // 之后的 tab 首拉走网格内的行内转圈（头部要留在屏上）
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    Box(Modifier.fillMaxSize()) {
                        ProfileGrid(
                            state = state,
                            avatarDecorationImageUrl = avatarDecorationImageUrl,
                            gridState = gridState,
                            // 头像行锚点换算，见类注释。列表首屏内容不足时至少留一点呼吸
                            headerTopPadding = (AVATAR_TOP_ANCHOR.dp - statusBarPadding -
                                TOP_BAR_HEIGHT.dp).coerceAtLeast(MIN_HEADER_TOP.dp),
                            onTabSelected = onTabSelected,
                            onEditProfileClick = onEditProfileClick,
                            onFollowersClick = onFollowersClick,
                            onFollowingClick = onFollowingClick,
                            onWalletAction = onWalletAction,
                            onSocialLinkClick = onSocialLinkClick,
                        )
                        if (showFloatingTabBar) {
                            ProfileTabBar(
                                selected = state.selectedTab,
                                onTabSelected = onTabSelected,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .background(ProfileStyle.APP_BACKGROUND)
                                    .testTag("profile_tab_bar_floating"),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 渐隐背景图（`ProfileBackground.tsx`）：宽 = 屏宽、1:1、
 * 三段 alpha 遮罩（36% 前全显 → 90% 处 0.1 → 尾部 0）。
 *
 * 用 `DstIn` 混合精确复刻 RN 的 MaskedView + LinearGradient；
 * `CompositingStrategy.Offscreen` 是让混合只作用于本层的前提。
 * URL 为空走内置默认图（`user-profile.tsx:418-423` fallback 到 `profile_bg.png`，
 * 资产已搬为 `ic_profile_bg_default`）。
 */
@Composable
private fun ProfileBackgroundImage(url: String?, modifier: Modifier = Modifier) {
    val maskedModifier = modifier
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
            contentDescription = null, // 纯装饰背景
            contentScale = ContentScale.Crop,
            modifier = maskedModifier,
        )
    } else {
        AsyncImage(
            model = HomeText.transformImageUrl(url),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = maskedModifier,
        )
    }
}

@Composable
private fun ProfileGrid(
    state: ProfileState,
    avatarDecorationImageUrl: String?,
    gridState: LazyGridState,
    headerTopPadding: Dp,
    onTabSelected: (ProfileTab) -> Unit,
    onEditProfileClick: () -> Unit,
    onFollowersClick: () -> Unit,
    onFollowingClick: () -> Unit,
    onWalletAction: (ProfileWalletAction) -> Unit,
    onSocialLinkClick: (String) -> Unit,
) {
    val tab = state.selectedTab
    LazyVerticalGrid(
        columns = GridCells.Fixed(ProfileStyle.COLUMN_COUNT),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(ProfileStyle.GRID_SPACING.dp),
        verticalArrangement = Arrangement.spacedBy(ProfileStyle.GRID_SPACING.dp),
        modifier = Modifier
            .fillMaxSize()
            .testTag("profile_grid"),
    ) {
        // 头部占满整行（对齐 RN 的 ListHeaderComponent）
        item(span = { GridItemSpan(maxLineSpan) }, key = KEY_HEADER, contentType = CT_HEADER) {
            ProfileHeaderSection(
                user = state.user,
                stats = state.stats,
                wallet = state.wallet,
                topPadding = headerTopPadding,
                onEditProfileClick = onEditProfileClick,
                onFollowersClick = onFollowersClick,
                onFollowingClick = onFollowingClick,
                onWalletAction = onWalletAction,
                avatarDecorationImageUrl = avatarDecorationImageUrl,
                onSocialLinkClick = onSocialLinkClick,
            )
        }

        // ⚠️ tab 栏必须是 index=1 的 item —— 浮动判定按这个下标找它
        item(span = { GridItemSpan(maxLineSpan) }, key = KEY_TABS, contentType = CT_TABS) {
            ProfileTabBar(
                selected = tab,
                onTabSelected = onTabSelected,
                modifier = Modifier.testTag("profile_tab_bar"),
            )
        }

        when {
            // tab 首拉：行内转圈（头部与 tab 栏留在屏上，别整页替换）
            state.isInitialLoading -> item(
                span = { GridItemSpan(maxLineSpan) },
                key = KEY_STATUS,
                contentType = CT_STATUS,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(EMPTY_PADDING.dp)
                        .testTag("profile_loading"),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            // 首屏失败且列表为空才把错误摆出来（方案 §8.4：已有数据时不清列表）
            state.errorMessage != null && state.items.isEmpty() -> item(
                span = { GridItemSpan(maxLineSpan) },
                key = KEY_STATUS,
                contentType = CT_STATUS,
            ) {
                // 后端 msg 已是可展示文案；兜底串是 i18n key，走 L10n（同 HomeScreen）
                val message = state.errorMessage.orEmpty()
                CenteredNote(
                    text = if (message == ProfileViewModel.FALLBACK_ERROR_KEY) {
                        rememberLocalizedString(message)
                    } else {
                        message
                    },
                    testTag = "profile_error",
                )
            }

            state.items.isEmpty() -> item(
                span = { GridItemSpan(maxLineSpan) },
                key = KEY_STATUS,
                contentType = CT_STATUS,
            ) {
                CenteredNote(
                    text = rememberLocalizedString(tab.emptyTextKey),
                    testTag = "profile_empty",
                )
            }

            // 记忆：单列大卡，每张占满整行
            tab == ProfileTab.MEMORY -> items(
                items = state.memoryItems,
                key = { it.dedupeKey },
                span = { GridItemSpan(maxLineSpan) },
                contentType = { CT_MEMORY },
            ) { item ->
                ProfileMemoryCard(
                    item = item,
                    // PlotItem 左右 margin 10、底 margin 8（网格自带 1dp 行距）
                    modifier = Modifier.padding(
                        start = MEMORY_CARD_MARGIN.dp,
                        end = MEMORY_CARD_MARGIN.dp,
                        bottom = MEMORY_CARD_BOTTOM.dp,
                    ),
                )
            }

            // 角色卡：单列横条（RoleCard.tsx 是 marginBottom 12 的列表）
            tab == ProfileTab.ROLE_CARD -> items(
                items = state.roleCardItems,
                key = { it.dedupeKey },
                span = { GridItemSpan(maxLineSpan) },
                contentType = { CT_ROLE_CARD },
            ) { item ->
                ProfileRoleCardRow(
                    item = item,
                    modifier = Modifier.padding(bottom = ROLE_CARD_BOTTOM.dp),
                )
            }

            // 收藏 / 点赞：三列网格（chunk(_, 3)，卡与创作同比例），共用组件
            tab == ProfileTab.FAVORITES || tab == ProfileTab.LIKED -> items(
                items = state.favoriteItems,
                key = { it.dedupeKey },
                contentType = { CT_FAVORITE },
            ) { item ->
                ProfileFavoriteCard(
                    item = item,
                    modifier = Modifier.aspectRatio(ProfileStyle.CARD_ASPECT_RATIO),
                )
            }

            else -> items(
                items = state.createdItems,
                // ⚠️ key 用 dedupeKey：它按类型分流（game 带 game_ 前缀），
                // 统一用 itemId 会让 game 与 character 撞 key，Compose 会复用错 slot
                key = { it.dedupeKey },
                contentType = { CT_CREATED },
            ) { item ->
                ProfileGridItem(
                    item = item,
                    modifier = Modifier.aspectRatio(ProfileStyle.CARD_ASPECT_RATIO),
                )
            }
        }
    }
}

/**
 * 五图标 tab 栏（`CharacterGrid.tsx:1215-1263`）：等分一行、纯图标、
 * 选中换 `_press` 资源。只在自己主页显示（他人主页那刀不复用，见 [ProfileTab]）。
 */
@Composable
private fun ProfileTabBar(
    selected: ProfileTab,
    onTabSelected: (ProfileTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        ProfileTab.entries.forEach { tab ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clickableNoRipple { onTabSelected(tab) }
                    .padding(vertical = TAB_BAR_PADDING.dp)
                    .testTag("profile_tab_${tab.name.lowercase()}"),
            ) {
                Image(
                    painter = painterResource(
                        if (tab == selected) tab.iconSelected else tab.icon,
                    ),
                    // 图标无文字，可访问性名给 tab 名（词条与空态/占位共用集合）
                    contentDescription = tab.name,
                    modifier = Modifier.size(TAB_ICON_SIZE.dp),
                )
            }
        }
    }
}

@Composable
private fun CenteredNote(text: String, testTag: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(EMPTY_PADDING.dp)
            .testTag(testTag),
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
 * 空态文案 key（`CharacterGridEmpty.tsx:25-31`，key = 英文原文）。
 *
 * RN 的分流是 `tabIndex === 0 ? 'story' : ...`（`CharacterGrid.tsx:1063-1074`）——
 * 创作 tab 用的是 **story 类型的 'No Character'**，不是想当然的
 * "No creations yet"（第一版杜撰过，已按实测改掉）。
 * 后三个 tab 的 key 现在就按实测记好，接数据源时直接可用。
 */
private val ProfileTab.emptyTextKey: String
    get() = when (this) {
        ProfileTab.CREATED -> "No Character"
        ProfileTab.MEMORY -> "No memories"
        ProfileTab.ROLE_CARD -> "No Role Cards Yet"
        ProfileTab.FAVORITES -> "No Favorite"
        ProfileTab.LIKED -> "No Like"
    }

/**
 * 顶栏：左 UID（带复制图标）、右设置图标 —— 对齐 RN 悬浮 header 的静态形态
 * （`user-profile.tsx:656-674` 自己视角 UID 在左，`700-730` 图标在右）。
 * 滚动驱动的 UID 渐出 / 小头像渐入属后续增强包。
 *
 * ## ⚠️ GooglePlay 包**不显示** Discord 与 More 图标
 *
 * `user-profile.tsx:707` 是 `{!isGooglePlay && (...)}` —— `isGooglePlay` 的定义是
 * `Platform.OS === 'android' && !isAndroidAPK && !isRuStore`（`constants/common.ts:18`，
 * 靠 applicationId 判渠道）。也就是 GooglePlay 包上**只剩设置图标**，
 * APK / RuStore 两个渠道要多 Discord + More。本刀只做全渠道共有的设置图标；
 * 补渠道差异时要接渠道判定，不能无条件显示三个。
 */
@Composable
private fun ProfileTopBar(
    userId: String?,
    onUidClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(TOP_BAR_HEIGHT.dp)
            .padding(horizontal = TOP_BAR_PADDING.dp),
    ) {
        if (!userId.isNullOrBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickableNoRipple(onUidClick)
                    .testTag("profile_uid"),
            ) {
                // ⚠️ `UID: ` 前缀**不进 i18n** —— RN 侧是裸文本不走 t()
                //（`user-profile.tsx:665` 有 i18n-ignore 注释），翻了反而不对等。
                // 截断规则在 ProfileText.formatUid（前 3 + 后 3）
                Text(
                    text = "UID: " + ProfileText.formatUid(userId),
                    color = ProfileStyle.TEXT_SECONDARY,
                    fontSize = UID_FONT.sp,
                )
                Image(
                    painter = painterResource(R.drawable.ic_profile_uid_copy),
                    contentDescription = null, // 与文字同一个点击目标，语义已由文字承担
                    alpha = UID_COPY_ALPHA,
                    modifier = Modifier
                        .padding(start = UID_COPY_GAP.dp)
                        .size(UID_COPY_ICON.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Image(
            painter = painterResource(R.drawable.ic_profile_setting),
            contentDescription = rememberLocalizedString("Settings"),
            modifier = Modifier
                .size(TOP_BAR_ICON.dp)
                .clickableNoRipple(onSettingsClick)
                .testTag("profile_settings"),
        )
    }
}

/** 无水波纹点击（图标类点击用，避免默认 ripple 在深色底上过亮）。 */
@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
    )
}

/** tab 栏在网格里的下标（0 = 头部）。浮动判定与 KDoc 都引用它。 */
private const val TAB_BAR_ITEM_INDEX = 1

private const val KEY_HEADER = "header"
private const val KEY_TABS = "tabs"

/** 占位/加载/错误/空态共用一个 key —— 它们互斥且占同一个位置。 */
private const val KEY_STATUS = "status"

private const val CT_HEADER = "header"
private const val CT_TABS = "tabs"
private const val CT_STATUS = "status"
private const val CT_MEMORY = "memory"
private const val CT_CREATED = "created"
private const val CT_ROLE_CARD = "rolecard"
private const val CT_FAVORITE = "favorite"

private const val TOP_BAR_PADDING = 12
private const val TOP_BAR_HEIGHT = 44
private const val TOP_BAR_ICON = 32
private const val UID_FONT = 12
private const val UID_COPY_ICON = 20
private const val UID_COPY_GAP = 4
private const val UID_COPY_ALPHA = 0.8f

/**
 * 头像行的屏顶锚点 250dp（`ProfileHeader.tsx:173` `paddingTop: 250 - headerOffset`
 * 与悬浮 header 高度 `top + 50` 相加的定值）。
 */
private const val AVATAR_TOP_ANCHOR = 250
private const val MIN_HEADER_TOP = 12

// 遮罩三段（`ProfileBackground.tsx` locations [0.36, 0.9, 1] + alpha [1, 0.1, 0]）
private const val BG_FADE_FULL = 0.36f
private const val BG_FADE_LOW = 0.9f
private const val BG_FADE_LOW_ALPHA = 0.1f

private const val EMPTY_PADDING = 32
private const val EMPTY_FONT = 14
private const val TAB_ICON_SIZE = 24
private const val TAB_BAR_PADDING = 10
private const val MEMORY_CARD_MARGIN = 10
private const val MEMORY_CARD_BOTTOM = 7
private const val ROLE_CARD_BOTTOM = 11
