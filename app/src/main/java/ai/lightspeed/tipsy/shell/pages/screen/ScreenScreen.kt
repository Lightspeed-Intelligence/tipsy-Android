package ai.lightspeed.tipsy.shell.pages.screen

import ai.lightspeed.tipsy.shell.R
import androidx.media3.common.util.UnstableApi
import androidx.compose.foundation.Image
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.painterResource
import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
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
import androidx.compose.material3.Text
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
 * 大屏页（Tab1，W4-P1，进度文档 §2.35）。
 *
 * ## P1 不播视频
 *
 * 三形态里 `showcase` 先显示 [ScreenFeedItem.thumbnailUrl] 静态封面
 * （Media3 属 P2）。`animated_image` 交给 Coil ——
 * ⚠️ Coil 3 默认能解 GIF/WebP 动图，但**需要 `coil-gif` artifact**；
 * 缺它时动图只显示第一帧（不报错）。P1 先不引，动图与静图视觉一致，
 * 差异记在验收里。
 *
 * ## 竖向全屏翻页
 *
 * `VerticalPager`，一屏一条。⚠️ **`beyondViewportPageCount` 保持默认 0** ——
 * 那个参数会让相邻页提前组合，P2 接播放器时它直接决定同时存活的播放器数，
 * 也就是 OOM 的直接来源（方案 §8.1「有界池」）。P1 虽不播视频，
 * 但先把这个默认坐实，免得 P2 忘了。
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
                onStartChat = onStartChat,
                onCardEvent = onCardEvent,
                statusBarPadding = statusBarPadding,
                bottomPadding = bottomPadding,
            )
        }
    }
}

@Composable
@UnstableApi  // 透传 ScreenPlayerPool
private fun ScreenPager(
    state: ScreenState,
    isActive: Boolean,
    soundEnabled: Boolean,
    onSoundToggle: () -> Unit,
    playerPool: ScreenPlayerPool?,
    onPageChanged: (Int) -> Unit,
    onStartChat: () -> Unit,
    onCardEvent: (ScreenCardEvent) -> Unit,
    statusBarPadding: Dp,
    bottomPadding: Dp,
) {
    val pagerState = rememberPagerState(pageCount = { state.items.size })

    // 翻页 → 通知 ViewModel（曝光埋点 + 触发预拉都在那边）
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { onPageChanged(it) }
    }

    Box(Modifier.fillMaxSize()) {
        VerticalPager(
            state = pagerState,
            // ⚠️ 保持默认 0：这个值决定同时存活的相邻页数量，
            // P2 接播放器后它就是 OOM 的直接来源，见文件头注释
            modifier = Modifier.fillMaxSize().testTag("screen_pager"),
            key = { state.items[it].characterId },
        ) { page ->
            ScreenCard(
                item = state.items[page],
                // ±1 窗口（对齐 RN `FeedMediaItem.tsx:594` 与 iOS 池）：
                // 窗口外只渲染封面图，不挂播放器。与池容量共同构成 OOM 上界。
                isWithinVideoWindow = kotlin.math.abs(page - pagerState.currentPage) <= 1,
                isCurrentPage = page == pagerState.currentPage,
                isPageActive = isActive,
                soundEnabled = soundEnabled,
                playerPool = playerPool,
                onStartChat = onStartChat,
                onCardEvent = onCardEvent,
                statusBarPadding = statusBarPadding,
                bottomPadding = bottomPadding,
            )
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
    isWithinVideoWindow: Boolean,
    isCurrentPage: Boolean,
    isPageActive: Boolean,
    soundEnabled: Boolean,
    playerPool: ScreenPlayerPool?,
    onStartChat: () -> Unit,
    onCardEvent: (ScreenCardEvent) -> Unit,
    statusBarPadding: Dp,
    bottomPadding: Dp,
) {
    Box(Modifier.fillMaxSize()) {
        // 背景：P1 三形态都走图片（showcase 用封面，见文件头注释）
        val imageUrl = when (item.mediaSourceType) {
            // ⚠️ showcase 的 backgroundUrl 是视频 URL，不能喂给 Coil ——
            // 用 thumbnailUrl（cover_url 回落 image_url）
            ScreenMediaSourceType.SHOWCASE -> item.thumbnailUrl
            else -> item.backgroundUrl ?: item.thumbnailUrl
        }
        AsyncImage(
            model = imageUrl?.let { HomeText.transformImageUrl(it) },
            contentDescription = item.nickname,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                // 加载中用后端给的主色兜底（img_primary_color），
                // 比灰底更接近成图，切页时不突兀
                .background(parsePrimaryColor(item.primaryColor)),
        )

        // 视频层（P2）：盖在封面之上、渐变之下。
        // ⚠️ 封面图**不卸载** —— 首帧渲染前它就是防黑帧的那一层
        // （对齐 RN 等 currentTime>0 才 setShowThumbnail(false)）。
        // 首帧到达后由 videoHasFrame 把封面盖住，而不是把它移除：
        // 播完回首帧时要立刻再露出来（RN handleVideoEnd 也是 setShowThumbnail(true)）。
        if (item.isVideo && isWithinVideoWindow && playerPool != null) {
            var videoHasFrame by remember(item.characterId) { mutableStateOf(false) }
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
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (videoHasFrame) 1f else 0f)
                    .testTag("screen_video_${item.characterId}"),
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
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    start = CONTENT_PADDING.dp,
                    end = CONTENT_PADDING.dp,
                    bottom = bottomPadding + CONTENT_PADDING.dp,
                ),
        ) {
            CreatorRow(item = item)
            Spacer(Modifier.height(CONTENT_GAP.dp))
            if (item.nickname != null) {
                Text(
                    text = item.nickname,
                    color = Color.White,
                    fontSize = NICKNAME_FONT.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("screen_card_nickname"),
                )
            }
            if (item.tagline.isNotBlank()) {
                Spacer(Modifier.height(TAGLINE_GAP.dp))
                Text(
                    text = item.tagline,
                    color = Color.White.copy(alpha = TEXT_SECONDARY_ALPHA),
                    fontSize = TAGLINE_FONT.sp,
                    // P1 只做折叠态两行；展开（FeedMediaTaglineOverlay 531 行）
                    // 属二期，方案已标「iOS 至今仍在二期清单」
                    maxLines = TAGLINE_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("screen_card_tagline"),
                )
            }
            Spacer(Modifier.height(CONTENT_GAP.dp))
            StatsRow(item = item, onCardEvent = onCardEvent)
            Spacer(Modifier.height(CONTENT_GAP.dp))
            ChatCta(onClick = onStartChat)
        }

        Spacer(Modifier.height(statusBarPadding))
    }
}

@Composable
private fun CreatorRow(item: ScreenFeedItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = item.creatorAvatarUrl?.let { HomeText.transformImageUrl(it) },
            contentDescription = item.creatorNickname,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(AVATAR_SIZE.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = PLACEHOLDER_ALPHA)),
        )
        if (item.creatorNickname != null) {
            Text(
                text = "@" + item.creatorNickname,
                color = Color.White.copy(alpha = TEXT_SECONDARY_ALPHA),
                fontSize = CREATOR_FONT.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = AVATAR_GAP.dp),
            )
        }
    }
}

/**
 * 点赞 / 评论 / 分享三个操作（`VideoActionButtons`，`layout="horizontal"`）。
 *
 * ⚠️ 形态是**图标在上、计数在下**，不是裸数字胶囊 —— 模拟器实测
 * （2026-08-14）确认我第一版做成了胶囊，与现网差得明显。
 * `:349-352` 的容器是 `flexDirection: row` + `gap: 24`，
 * `:369-376` 的计数是 10sp 半粗白字**带阴影**（压在图片上要保可读性）。
 *
 * ⚠️ P1 **不做点赞写入**（初始 `is_liked` 预拉 / echo 对账 / 动画都在方案的
 * 二期清单）—— 点击只报埋点。所以爱心恒为「未选中」态,
 * 复用 Profile 的 `ic_profile_tab_like`；RN 的两个 like 图标是 **SVG**，
 * Coil 不带 SVG decoder（缺 `coil-svg` artifact 时静默不显示）。
 *
 * 分享同样只报埋点：`MediaShareModal` 426 行 + `react-native-share` 原生库属后续包。
 */
@Composable
private fun StatsRow(item: ScreenFeedItem, onCardEvent: (ScreenCardEvent) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ACTION_GAP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionButton(
            iconRes = R.drawable.ic_profile_tab_like,
            count = item.likeCount,
            testTag = "screen_card_like",
            onClick = { onCardEvent(ScreenCardEvent.LIKE_CLICK) },
        )
        ActionButton(
            iconRes = R.drawable.ic_screen_comment,
            count = item.commentCount,
            testTag = "screen_card_comment",
            onClick = { onCardEvent(ScreenCardEvent.COMMENT_CLICK) },
        )
        ActionButton(
            iconRes = R.drawable.ic_screen_share,
            // 分享没有计数（RN 那里也只有图标）
            count = null,
            testTag = "screen_card_share",
            onClick = { onCardEvent(ScreenCardEvent.SHARE_CLICK) },
        )
    }
}

/** 图标在上、计数在下（`actionItem` + `countText`）。 */
@Composable
private fun ActionButton(
    iconRes: Int,
    count: Long?,
    testTag: String,
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
            modifier = Modifier.size(ACTION_ICON_SIZE.dp),
        )
        if (count != null) {
            Text(
                text = HomeText.formatMessageCount(count),
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
}

@Composable
private fun ChatCta(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CTA_RADIUS.dp))
            .background(CTA_BACKGROUND)
            .clickable(onClick = onClick)
            .padding(vertical = CTA_V_PADDING.dp)
            .testTag("screen_card_cta"),
    ) {
        Text(
            text = rememberLocalizedString("Send"),
            color = Color.White,
            fontSize = CTA_FONT.sp,
            fontWeight = FontWeight.Medium,
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
                    .clip(RoundedCornerShape(CTA_RADIUS.dp))
                    .background(CTA_BACKGROUND)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = CTA_H_PADDING.dp, vertical = CTA_V_PADDING.dp),
            ) {
                Text(
                    text = rememberLocalizedString("Retry"),
                    color = Color.White,
                    fontSize = CTA_FONT.sp,
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

private const val CONTENT_PADDING = 16
private const val CONTENT_GAP = 12
private const val TAGLINE_GAP = 6
private const val TAGLINE_MAX_LINES = 2
private const val NICKNAME_FONT = 20
private const val TAGLINE_FONT = 14
private const val CREATOR_FONT = 13
private const val AVATAR_SIZE = 32
private const val AVATAR_GAP = 8
/** `horizontalContainer.gap: 24`（`VideoActionButtons.tsx:351`）。 */
private const val ACTION_GAP = 24
private const val ACTION_ICON_SIZE = 32
private const val ACTION_COUNT_GAP = 2
/** `countText.fontSize: s(10)`（`:371`）。 */
private const val ACTION_COUNT_FONT = 10
private const val COUNT_SHADOW_ALPHA = 0.45f
private const val COUNT_SHADOW_DY = 1f
private const val COUNT_SHADOW_BLUR = 2f
private const val CTA_RADIUS = 24
private const val CTA_V_PADDING = 14
private const val CTA_H_PADDING = 24
private const val CTA_FONT = 15
private const val EMPTY_FONT = 14

/** CTA 底色（品牌粉，同其它页的 accent）。 */
private val CTA_BACKGROUND = Color(0xFFAD403B)
