package ai.lightspeed.tipsy.shell.pages.screen.share

import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
import ai.lightspeed.tipsy.shell.pages.screen.ScreenPlayerPool
import ai.lightspeed.tipsy.shell.pages.screen.ScreenVideoHost
import ai.lightspeed.tipsy.shell.share.TipsyShareContent
import ai.lightspeed.tipsy.shell.share.TipsyShareMediaType
import ai.lightspeed.tipsy.shell.ui.share.TipsyShareSheet
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/** Screen 对通用分享面板的薄封装：只负责媒体预览与本地化。 */
@Composable
@UnstableApi
internal fun ScreenShareOverlay(
    state: ScreenShareUiState,
    playerPool: ScreenPlayerPool?,
    isActive: Boolean,
    onChannelClick: (ai.lightspeed.tipsy.shell.share.TipsyShareChannel) -> Unit,
    onClose: () -> Unit,
    onMessageConsumed: (Long) -> Unit,
) {
    val message = state.messageKey?.let { rememberLocalizedString(it) }
    LaunchedEffect(state.messageSequence, state.messageKey) {
        if (state.messageKey == null) return@LaunchedEffect
        delay(MESSAGE_DURATION_MS)
        onMessageConsumed(state.messageSequence)
    }

    TipsyShareSheet(
        visible = state.visible,
        title = rememberLocalizedString("Share"),
        channels = state.channels,
        busyChannel = state.busyChannel,
        message = message,
        messageIsError = state.messageType == ScreenShareMessageType.ERROR,
        copyLinkLabel = rememberLocalizedString("Copy Link"),
        closeLabel = rememberLocalizedString("Close"),
        onChannelClick = onChannelClick,
        onClose = onClose,
    ) {
        state.content?.let { content ->
            ScreenShareMediaPreview(
                content = content,
                playerPool = playerPool,
                isActive = isActive,
            )
        }
    }
}

/**
 * 对齐 RN `MediaPreview`：固定 32/90/24dp 留白，卡片高为窗口 60%、最大宽 300dp，
 * 图片/视频均 contain。视频借用 Screen 的同一个有界池；池满就保留封面，绝不
 * 为弹层另建无界播放器。
 */
@Composable
@UnstableApi
private fun ScreenShareMediaPreview(
    content: TipsyShareContent,
    playerPool: ScreenPlayerPool?,
    isActive: Boolean,
) {
    // RN 的 useWindowDimensions 是窗口语义；LocalConfiguration 在新 targetSdk 下
    // 还会带来不一致的 inset/取整，并触发仓库的 ConfigurationScreenWidthHeight lint。
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val windowHeight = with(LocalDensity.current) { windowHeightPx.toDp() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 32.dp, top = 90.dp, end = 32.dp, bottom = 24.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 300.dp)
                .height(windowHeight * 0.6f)
                .shadow(16.dp)
                .background(Color.Transparent)
                .testTag("screen_share_preview_media"),
        ) {
            when (content.mediaType) {
                TipsyShareMediaType.IMAGE -> AsyncImage(
                    model = content.mediaUrl,
                    contentDescription = content.characterName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )

                TipsyShareMediaType.VIDEO -> ScreenShareVideoPreview(
                    content = content,
                    playerPool = playerPool,
                    isActive = isActive,
                )
            }
        }
    }
}

@Composable
@UnstableApi
private fun ScreenShareVideoPreview(
    content: TipsyShareContent,
    playerPool: ScreenPlayerPool?,
    isActive: Boolean,
) {
    var hasFrame by remember(content.mediaUrl) { mutableStateOf(false) }
    if (playerPool != null) {
        ScreenVideoHost(
            url = content.mediaUrl,
            thumbnailUrl = content.thumbnailUrl,
            isCurrent = true,
            isActive = isActive,
            soundEnabled = false,
            loop = true,
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
            pool = playerPool,
            onPlaybackEnded = { hasFrame = false },
            onPlaybackError = { hasFrame = false },
            onResetToCover = { hasFrame = false },
            onFirstFrame = { hasFrame = true },
            modifier = Modifier.fillMaxSize(),
        )
    }
    if (!hasFrame) {
        AsyncImage(
            model = content.thumbnailUrl,
            contentDescription = content.characterName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private const val MESSAGE_DURATION_MS = 2_500L
