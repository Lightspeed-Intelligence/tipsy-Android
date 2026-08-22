package ai.lightspeed.tipsy.shell.pages.screen

import ai.lightspeed.tipsy.shell.BuildConfig
import ai.lightspeed.tipsy.shell.R
import androidx.media3.common.util.UnstableApi
import androidx.compose.foundation.Image
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.painterResource
import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
import ai.lightspeed.tipsy.shell.i18n.rememberCurrentLanguage
import ai.lightspeed.tipsy.shell.pages.home.HomeText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/**
 * 大屏页（Tab1，W4-P1/P2，进度文档 §2.35 / §2.42）。
 *
 * ## showcase 视频（P2）
 *
 * `showcase` 在当前页 ±1 窗口内经 [ScreenPlayerPool] 播放；
 * [ScreenFeedItem.thumbnailUrl] 作为覆盖层保留到首帧，并在播放器不可用、
 * 离开窗口、播完或出错时重新显示。`animated_image` 仍交给 Coil ——
 * ⚠️ Coil 3 默认能解 GIF/WebP 动图，但**需要 `coil-gif` artifact**；
 * 当前未引该 artifact，缺它时动图只显示第一帧（不报错），差异记在验收里。
 *
 * ## 竖向全屏翻页
 *
 * `VerticalPager`，一屏一条。⚠️ **`beyondViewportPageCount = 1`**（P2 起）——
 * 它让相邻页提前组合，是 ±1 窗口能成立的前提（默认 0 时邻页不组合，
 * `abs(page-current)<=1` 永远只对当前页成立）。同时它直接决定同时存活的
 * 播放器数，也就是 OOM 的直接来源（方案 §8.1「有界池」），
 * 与 [ScreenPlayerPool.capacity] 共同构成上界。**不要往上调**。
 */
@Composable
@UnstableApi  // 接收 ScreenPlayerPool（Media3 opt-in API）
fun ScreenScreen(
    state: ScreenState,
    /** 页面是否可见（Tab 切走 / App 进后台 → false）：决定视频是否播放。 */
    isActive: Boolean,
    /** 声音开关初值，见 [ScreenSoundPreference]。 */
    soundEnabled: Boolean,
    /** 声音开关点击 —— 只改内存不持久化，见 `ScreenFragment.soundEnabled`。 */
    onSoundToggle: () -> Unit,
    /** 有界播放器池；null 表示本次组合不播视频（预览/测试）。 */
    playerPool: ScreenPlayerPool?,
    onPageChanged: (Int) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onStartChat: () -> Unit,
    onCharacterClick: (ScreenFeedItem) -> Unit,
    onCreatorClick: (ScreenFeedItem) -> Unit,
    /** 携带点击时的卡片快照，避免 pager settledPage 迟到造成分享错卡。 */
    onShareClick: (ScreenFeedItem) -> Unit,
    onCardEvent: (ScreenCardEvent) -> Unit,
    statusBarPadding: Dp,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        when {
            state.showsInitialLoading -> Box(
                Modifier.fillMaxSize().testTag("screen_loading"),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            state.isRetryable -> RetryPane(onRetry = onRetry)

            state.showsEmpty -> Box(
                Modifier.fillMaxSize().testTag("screen_empty"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = rememberLocalizedString("No results"),
                    color = Color.White.copy(alpha = TEXT_SECONDARY_ALPHA),
                    fontSize = EMPTY_FONT.sp,
                )
            }

            else -> ScreenPager(
                state = state,
                isActive = isActive,
                soundEnabled = soundEnabled,
                onSoundToggle = onSoundToggle,
                playerPool = playerPool,
                onPageChanged = onPageChanged,
                onRefresh = onRefresh,
                onStartChat = onStartChat,
                onCharacterClick = onCharacterClick,
                onCreatorClick = onCreatorClick,
                onShareClick = onShareClick,
                onCardEvent = onCardEvent,
                statusBarPadding = statusBarPadding,
                bottomPadding = bottomPadding,
            )
        }
    }
}

@Composable
@UnstableApi  // 透传 ScreenPlayerPool
@OptIn(ExperimentalMaterial3Api::class)
private fun ScreenPager(
    state: ScreenState,
    isActive: Boolean,
    soundEnabled: Boolean,
    onSoundToggle: () -> Unit,
    playerPool: ScreenPlayerPool?,
    onPageChanged: (Int) -> Unit,
    onRefresh: () -> Unit,
    onStartChat: () -> Unit,
    onCharacterClick: (ScreenFeedItem) -> Unit,
    onCreatorClick: (ScreenFeedItem) -> Unit,
    onShareClick: (ScreenFeedItem) -> Unit,
    onCardEvent: (ScreenCardEvent) -> Unit,
    statusBarPadding: Dp,
    bottomPadding: Dp,
) {
    val pagerState = rememberPagerState(pageCount = { state.items.size })
    var taglinePreviewText by remember { mutableStateOf<String?>(null) }

    // 翻页 → 通知 ViewModel（曝光埋点 + 触发预拉都在那边）
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { onPageChanged(it) }
    }

    Box(Modifier.fillMaxSize()) {
        // 与 Home 共用 M3 下拉刷新。Pager 会先消费向上/向下翻页，只有已经回到
        // 第 0 张且继续下拉时父层才收到 overscroll，因此不会在中间页误刷新。
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            VerticalPager(
                state = pagerState,
                // ⚠️ **必须显式设 1**（P1 时是默认 0）：`abs(page-current)<=1` 的
                // ±1 窗口只在邻页**被组合**时才有意义 —— 默认 0 时邻页压根不组合，
                // 那个判断永远只对当前页成立，"±1 预热"是空话。
                //
                // 这个值就是 OOM 的直接来源，与 [ScreenPlayerPool.capacity]
                // 共同构成上界：1 表示同时最多 3 页被组合 → 最多 3 个播放器在用，
                // 池容量 3~5 留出翻页重叠期。**不要往上调**。
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize().testTag("screen_pager"),
                key = { state.items[it].characterId },
            ) { page ->
                val item = state.items[page]
                ScreenCard(
                    item = item,
                    likeState = state.likeStateFor(item),
                    // ±1 窗口（对齐 RN `FeedMediaItem.tsx:594` 与 iOS 池）：
                    // 窗口外只渲染封面图，不挂播放器。与池容量共同构成 OOM 上界。
                    isWithinVideoWindow = kotlin.math.abs(page - pagerState.currentPage) <= 1,
                    isCurrentPage = page == pagerState.currentPage,
                    isPageActive = isActive,
                    soundEnabled = soundEnabled,
                    playerPool = playerPool,
                    onStartChat = onStartChat,
                    onCharacterClick = onCharacterClick,
                    onCreatorClick = onCreatorClick,
                    onShareClick = onShareClick,
                    onTaglineExpand = { taglinePreviewText = it },
                    onCardEvent = onCardEvent,
                    statusBarPadding = statusBarPadding,
                    bottomPadding = bottomPadding,
                )
            }
        }

        // 声音开关：右上角覆盖层，**在 Pager 之外**（对齐 RN 的
        // `TipsyHeaderLayout` 也是 FlatList 的兄弟节点而非 item 内部）。
        // 放进卡片里会让每张卡各有一个按钮，且翻页时跟着滑走
        SoundToggleButton(
            soundEnabled = soundEnabled,
            onToggle = onSoundToggle,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = statusBarPadding)
                .padding(end = SOUND_BUTTON_END_PADDING.dp, top = SOUND_BUTTON_TOP_PADDING.dp),
        )

        taglinePreviewText?.let { previewText ->
            ScreenTaglinePreview(
                text = previewText,
                onDismiss = { taglinePreviewText = null },
            )
        }
    }
}

/**
 * 右上角声音开关（对齐 RN `screen.tsx:1300-1318` 的 `screen.soundToggleButton`）。
 *
 * iOS 研究文档 §3.1 把它列在「常驻控件」里 —— 两端都有，是必做项。
 *
 * ⚠️ 点击**只改内存不持久化**（本刀刻意接受的临时偏差）：写回
 * `chat-persist-storage` 属共享键写协议，另包解决。见
 * [ScreenSoundPreference] 的所有权说明。
 */
@Composable
private fun SoundToggleButton(
    soundEnabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(
            if (soundEnabled) R.drawable.ic_screen_sound_on else R.drawable.ic_screen_sound_off,
        ),
        // 无障碍：读出当前状态而不是"按钮"，否则用户不知道点了会变成什么
        contentDescription = rememberLocalizedString(
            if (soundEnabled) "Sound on" else "Sound off",
        ),
        modifier = modifier
            .size(SOUND_BUTTON_SIZE.dp)
            .testTag("screen_sound_toggle")
            .clickable(onClick = onToggle),
    )
}

/**
 * 一张全屏卡：背景媒体 + 上下渐变遮罩 + 底部信息 + CTA。
 *
 * 渐变四段照 `FeedMediaItem.tsx:672-683`（`locations=[0,0.15,0.6,1]`，
 * alpha 0.7 → 0 → 0 → 0.8）—— 顶部压暗给状态栏、底部压暗给文案。
 */
@Composable
@UnstableApi  // 透传 ScreenPlayerPool
private fun ScreenCard(
    item: ScreenFeedItem,
    likeState: ScreenLikeState,
    isWithinVideoWindow: Boolean,
    isCurrentPage: Boolean,
    isPageActive: Boolean,
    soundEnabled: Boolean,
    playerPool: ScreenPlayerPool?,
    onStartChat: () -> Unit,
    onCharacterClick: (ScreenFeedItem) -> Unit,
    onCreatorClick: (ScreenFeedItem) -> Unit,
    onShareClick: (ScreenFeedItem) -> Unit,
    onTaglineExpand: (String) -> Unit,
    onCardEvent: (ScreenCardEvent) -> Unit,
    statusBarPadding: Dp,
    bottomPadding: Dp,
) {
    val language by rememberCurrentLanguage()
    val resolvedTagline = remember(
        item.tagline,
        item.nickname,
        item.isTranslated,
        language,
    ) {
        resolveScreenTagline(
            tagline = item.tagline,
            nickname = item.nickname.orEmpty(),
            isTranslated = item.isTranslated,
            languageCode = language,
            isGooglePlay = BuildConfig.DOWNLOAD_CHANNEL == CHANNEL_GOOGLE_PLAY,
        )
    }
    Box(Modifier.fillMaxSize()) {
        // 背景图：静图/动图直接显示；showcase 把封面作为视频首帧前的 overlay
        val imageUrl = when (item.mediaSourceType) {
            // ⚠️ showcase 的 backgroundUrl 是视频 URL，不能喂给 Coil ——
            // 用 thumbnailUrl（cover_url 回落 image_url）
            ScreenMediaSourceType.SHOWCASE -> item.thumbnailUrl
            else -> item.backgroundUrl ?: item.thumbnailUrl
        }
        // ⚠️ **层序：视频在下、封面在上**（P2 起改成这样）。
        //
        // 早前是「封面在下、视频在上 + 对视频加 alpha」——那在 API 24–33 上**不成立**：
        // `PlayerView` 默认用 `SurfaceView`，它是独立的 native surface，
        // View 层的 alpha/透明度对它不起作用（`SurfaceView` 直到 API 34
        // 才对 alpha 有可靠支持）。表现是「视频 alpha=0 但仍然可见」，
        // 也就是**封面根本没起到防黑帧的作用**，而这在高版本模拟器上测不出来。
        //
        // 现在改成：视频始终不透明地铺在最底层，封面作为**上层 overlay**，
        // 靠「有没有渲染封面」而不是 alpha 来决定露哪个。
        // 封面在需要时整块盖住视频，不需要时不组合。
        val videoVisible = item.isVideo && isWithinVideoWindow && playerPool != null
        // ⚠️ key 要含 `videoVisible`：离开 ±1 窗口时播放器被归还、视频层被卸载，
        // 若 frame 状态还留着 true，下次进窗口的**第一帧到达前**封面不会显示
        // —— 那一瞬间露出的是上一个播放器的残留画面或黑帧，
        // 正是防黑帧那条时序要挡的东西。
        // URL 也在 key 里：URL 变了就是另一条媒体，旧的 frame 状态不适用
        var videoHasFrame by remember(item.characterId, item.backgroundUrl, videoVisible) {
            mutableStateOf(false)
        }

        if (videoVisible) {
            ScreenVideoHost(
                url = item.backgroundUrl,
                thumbnailUrl = item.thumbnailUrl,
                isCurrent = isCurrentPage,
                isActive = isPageActive,
                soundEnabled = soundEnabled,
                pool = playerPool,
                onFirstFrame = { videoHasFrame = true },
                // 播完 → 回首帧 + 重新露出封面（切 tagline 的判定属 P3 状态机）
                onPlaybackEnded = { videoHasFrame = false },
                // 出错也要把封面放回来（对齐 RN handleVideoError），
                // 否则首帧后出错会停在黑帧/冻结帧上
                onPlaybackError = { videoHasFrame = false },
                // 划离当前页 → 复位封面（对齐 RN 切卡 setShowThumbnail(true)）。
                // ⚠️ 失焦**不**走这条，那边只 pause 保进度
                onResetToCover = { videoHasFrame = false },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("screen_video_${item.characterId}"),
            )
        }

        // 封面 overlay：非视频卡恒显示；视频卡在首帧到达前 / 播完 / 出错后显示。
        // ⚠️ 用「是否组合」而不是 alpha —— 见上面 SurfaceView 那段
        if (!videoVisible || !videoHasFrame) {
            AsyncImage(
                model = imageUrl?.let { HomeText.transformImageUrl(it) },
                contentDescription = item.nickname,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    // 加载中用后端给的主色兜底（img_primary_color），
                    // 比灰底更接近成图，切页时不突兀
                    .background(parsePrimaryColor(item.primaryColor))
                    .testTag("screen_cover_${item.characterId}"),
            )
        }

        // 四段渐变遮罩
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = GRADIENT_TOP_ALPHA),
                        GRADIENT_STOP_UPPER to Color.Transparent,
                        GRADIENT_STOP_LOWER to Color.Transparent,
                        1f to Color.Black.copy(alpha = GRADIENT_BOTTOM_ALPHA),
                    ),
                ),
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(bottom = bottomPadding + CONTENT_BOTTOM_GAP.dp),
        ) {
            if (resolvedTagline.isNotBlank()) {
                ScreenTaglineCard(
                    text = resolvedTagline,
                    onExpand = { onTaglineExpand(resolvedTagline) },
                )
                Spacer(Modifier.height(TAGLINE_META_GAP.dp))
            }
            ScreenMetaBar(
                item = item,
                likeState = likeState,
                onCharacterClick = onCharacterClick,
                onCreatorClick = onCreatorClick,
                onShareClick = onShareClick,
                onCardEvent = onCardEvent,
                modifier = Modifier.padding(horizontal = CONTENT_HORIZONTAL_PADDING.dp),
            )
            Spacer(Modifier.height(CONTENT_GAP.dp))
            ChatCta(
                onClick = onStartChat,
                modifier = Modifier.padding(horizontal = CONTENT_HORIZONTAL_PADDING.dp),
            )
        }

        Spacer(Modifier.height(statusBarPadding))
    }
}

/**
 * 底部信息栏：左侧角色头像/名称/作者，右侧横排操作栏。
 *
 * 结构同时对齐 RN `FeedMediaItem.metaBarContent` 与 iOS `ScreenCell.metaBar`。
 * 操作栏不能单独占下一行，否则会像旧实现一样落在左下角并把 CTA 整体顶高。
 */
@Composable
private fun ScreenMetaBar(
    item: ScreenFeedItem,
    likeState: ScreenLikeState,
    onCharacterClick: (ScreenFeedItem) -> Unit,
    onCreatorClick: (ScreenFeedItem) -> Unit,
    onShareClick: (ScreenFeedItem) -> Unit,
    onCardEvent: (ScreenCardEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            // RN 用 `character_avatars[0]`，iOS 用 `faceUrl`；这里对应 [avatarUrl]。
            // `creatorAvatarUrl` 是作者头像，放这里会让头像与旁边的角色名不匹配。
            model = item.avatarUrl?.let { HomeText.transformImageUrl(it) },
            contentDescription = item.nickname,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(AVATAR_SIZE.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = PLACEHOLDER_ALPHA))
                .clickable { onCharacterClick(item) }
                .testTag("screen_card_avatar"),
        )
        Column(
            modifier = Modifier
                .padding(start = AVATAR_GAP.dp)
                .weight(1f),
        ) {
            item.nickname?.let { nickname ->
                Text(
                    text = nickname,
                    color = Color.White,
                    fontSize = NICKNAME_FONT.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clickable { onCharacterClick(item) }
                        .testTag("screen_card_nickname"),
                )
            }
            item.creatorNickname?.let { creatorNickname ->
                Text(
                    text = "@$creatorNickname",
                    color = Color.White.copy(alpha = CREATOR_ALPHA),
                    fontSize = CREATOR_FONT.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clickable { onCreatorClick(item) }
                        .testTag("screen_card_creator"),
                )
            }
        }
        Spacer(Modifier.size(META_BAR_GAP.dp))
        StatsRow(
            item = item,
            likeState = likeState,
            onShareClick = onShareClick,
            onCardEvent = onCardEvent,
        )
    }
}

/**
 * 点赞 / 评论 / 分享三个操作（`VideoActionButtons`，`layout="horizontal"`）。
 * 由 [ScreenMetaBar] 固定在角色信息右侧，不能作为独立内容行渲染。
 *
 * ⚠️ 形态是**图标在上、计数在下**，不是裸数字胶囊 —— 模拟器实测
 * （2026-08-14）确认我第一版做成了胶囊，与现网差得明显。
 * `:349-352` 的容器是 `flexDirection: row` + `gap: 24`，
 * `:369-376` 的计数是 10sp 半粗白字**带阴影**（压在图片上要保可读性）。
 *
 * 点赞状态与计数来自 [ScreenLikeState]：初始 echo、乐观更新与失败回滚都在
 * ViewModel，UI 只负责选中图与一次弹跳动画。
 *
 * 分享走独立回调并携带这张卡的快照；不能在 Fragment 里重读 currentItem，
 * 否则翻页动画与 settledPage 更新之间点击会把相邻卡分享出去。
 */
@Composable
private fun StatsRow(
    item: ScreenFeedItem,
    likeState: ScreenLikeState,
    onShareClick: (ScreenFeedItem) -> Unit,
    onCardEvent: (ScreenCardEvent) -> Unit,
) {
    val likeScale = remember(item.characterId) { Animatable(1f) }
    LaunchedEffect(likeState.animationPulse) {
        if (likeState.animationPulse <= 0L) return@LaunchedEffect
        likeScale.snapTo(1f)
        likeScale.animateTo(1.3f, tween(durationMillis = LIKE_BOUNCE_HALF_MS))
        likeScale.animateTo(1f, tween(durationMillis = LIKE_BOUNCE_HALF_MS))
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(ACTION_GAP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionButton(
            iconRes = if (likeState.isLiked) {
                R.drawable.ic_screen_like_selected
            } else {
                R.drawable.ic_screen_like
            },
            label = HomeText.formatMessageCount(likeState.count),
            testTag = "screen_card_like",
            modifier = Modifier.graphicsLayer {
                scaleX = likeScale.value
                scaleY = likeScale.value
            },
            onClick = { onCardEvent(ScreenCardEvent.LIKE_CLICK) },
        )
        ActionButton(
            iconRes = R.drawable.ic_screen_comment,
            label = HomeText.formatMessageCount(item.commentCount),
            testTag = "screen_card_comment",
            onClick = { onCardEvent(ScreenCardEvent.COMMENT_CLICK) },
        )
        ActionButton(
            iconRes = R.drawable.ic_screen_share,
            label = rememberLocalizedString("Share"),
            testTag = "screen_card_share",
            onClick = { onShareClick(item) },
        )
    }
}

/** 图标在上、计数在下（`actionItem` + `countText`）。 */
@Composable
private fun ActionButton(
    iconRes: Int,
    label: String,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag(testTag),
    ) {
        Image(
            painter = painterResource(iconRes),
            // 语义由相邻计数与 testTag 承载；图标本身是装饰
            contentDescription = null,
            modifier = modifier.size(ACTION_ICON_SIZE.dp),
        )
        Text(
            text = label,
            color = Color.White,
            fontSize = ACTION_COUNT_FONT.sp,
            fontWeight = FontWeight.SemiBold,
            // 压在图片上，用阴影保可读性（对齐 RN 的 textShadow）
            style = LocalTextStyle.current.copy(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = COUNT_SHADOW_ALPHA),
                    offset = Offset(0f, COUNT_SHADOW_DY),
                    blurRadius = COUNT_SHADOW_BLUR,
                ),
            ),
            modifier = Modifier.padding(top = ACTION_COUNT_GAP.dp),
        )
    }
}

@Composable
private fun ChatCta(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(STORY_CTA_HEIGHT.dp)
            .clip(RoundedCornerShape(STORY_CTA_RADIUS.dp))
            // RN Android 没有开启 expo-blur 的实验性真模糊，实际稳定降级就是
            // 半透明材质底色；Compose 的 Modifier.blur 只会糊自身且 API 31 以下
            // 不生效，不能拿来冒充背景毛玻璃。
            .background(STORY_CTA_BACKGROUND)
            .clickable(onClick = onClick)
            .testTag("screen_card_cta"),
    ) {
        Text(
            text = rememberLocalizedString("Let Your Story Begin"),
            color = Color.White.copy(alpha = STORY_CTA_TEXT_ALPHA),
            fontSize = STORY_CTA_FONT.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RetryPane(onRetry: () -> Unit) {
    Box(
        Modifier.fillMaxSize().testTag("screen_retry"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = rememberLocalizedString("Please try again later"),
                color = Color.White.copy(alpha = TEXT_SECONDARY_ALPHA),
                fontSize = EMPTY_FONT.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(CONTENT_GAP.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(RETRY_CTA_RADIUS.dp))
                    .background(RETRY_CTA_BACKGROUND)
                    .clickable(onClick = onRetry)
                    .padding(
                        horizontal = RETRY_CTA_H_PADDING.dp,
                        vertical = RETRY_CTA_V_PADDING.dp,
                    ),
            ) {
                Text(
                    text = rememberLocalizedString("Retry"),
                    color = Color.White,
                    fontSize = RETRY_CTA_FONT.sp,
                )
            }
        }
    }
}

/**
 * 解析后端给的主色（`img_primary_color`）。
 *
 * 认不出时回落黑色 —— 那个字段的格式后端可能给 `#RRGGBB` 或裸 hex，
 * 解析失败不该崩（一个占位底色不值得）。
 */
private fun parsePrimaryColor(raw: String?): Color {
    if (raw.isNullOrBlank()) return Color.Black
    val hex = raw.removePrefix("#").trim()
    if (hex.length != 6) return Color.Black
    return runCatching { Color(0xFF000000L.toInt() or hex.toInt(16)) }.getOrDefault(Color.Black)
}

// ── 视觉常量（对着 FeedMediaItem.tsx 取）──────────

/** 渐变四段（`:672-683` 的 colors + locations）。 */
// 声音开关（对齐 RN `screen.tsx` styles：soundButtonIcon 32×32、
// soundHeader paddingRight 12 / height 44 —— 按钮在 44 高的头部里右对齐）
private const val SOUND_BUTTON_SIZE = 32
private const val SOUND_BUTTON_END_PADDING = 12
private const val SOUND_BUTTON_TOP_PADDING = 6

private const val GRADIENT_TOP_ALPHA = 0.7f
private const val GRADIENT_BOTTOM_ALPHA = 0.8f
private const val GRADIENT_STOP_UPPER = 0.15f
private const val GRADIENT_STOP_LOWER = 0.6f

private const val TEXT_SECONDARY_ALPHA = 0.7f
private const val PLACEHOLDER_ALPHA = 0.15f

/** RN `contentContainer.paddingHorizontal = 12` / iOS ScreenCell 两侧 inset 12。 */
private const val CONTENT_HORIZONTAL_PADDING = 12
/** CTA 底边距底栏 10（iOS `contentBottomInset`；RN 对应 showcase base inset）。 */
private const val CONTENT_BOTTOM_GAP = 10
private const val CONTENT_GAP = 12
private const val TAGLINE_META_GAP = 16
private const val NICKNAME_FONT = 14
private const val CREATOR_FONT = 13
private const val CREATOR_ALPHA = 0.5f
private const val AVATAR_SIZE = 40
private const val AVATAR_GAP = 9
private const val META_BAR_GAP = 12
/** `horizontalContainer.gap: 24`（`VideoActionButtons.tsx:351`）。 */
private const val ACTION_GAP = 24
private const val ACTION_ICON_SIZE = 32
private const val ACTION_COUNT_GAP = 2
/** `countText.fontSize: s(10)`（`:371`）。 */
private const val ACTION_COUNT_FONT = 10
private const val LIKE_BOUNCE_HALF_MS = 150
private const val COUNT_SHADOW_ALPHA = 0.45f
private const val COUNT_SHADOW_DY = 1f
private const val COUNT_SHADOW_BLUR = 2f

/** Screen 转化 CTA：RN `storyBeginButton` / iOS `storyButton` 的共同规格。 */
private const val STORY_CTA_HEIGHT = 40
private const val STORY_CTA_RADIUS = 20
private const val STORY_CTA_FONT = 14
private const val STORY_CTA_TEXT_ALPHA = 0.9f
private val STORY_CTA_BACKGROUND = Color.White.copy(alpha = 0.15f)

/** 重试按钮不是 Screen 转化 CTA，继续保留原来的品牌色样式。 */
private const val RETRY_CTA_RADIUS = 24
private const val RETRY_CTA_V_PADDING = 14
private const val RETRY_CTA_H_PADDING = 24
private const val RETRY_CTA_FONT = 15
private val RETRY_CTA_BACKGROUND = Color(0xFFAD403B)
private const val EMPTY_FONT = 14

private const val CHANNEL_GOOGLE_PLAY = "GooglePlay"
