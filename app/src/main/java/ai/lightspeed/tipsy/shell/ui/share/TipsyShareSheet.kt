package ai.lightspeed.tipsy.shell.ui.share

import ai.lightspeed.tipsy.shell.R
import ai.lightspeed.tipsy.shell.share.TipsyShareChannel
import ai.lightspeed.tipsy.shell.ui.ScaledMetrics
import ai.lightspeed.tipsy.shell.ui.s
import ai.lightspeed.tipsy.shell.ui.sSp
import android.os.Build
import android.view.View
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * Generic RN-visual share overlay. Screen owns moderation, media saving, URL/count contracts and
 * callback sequencing; this component owns only presentation and click delivery.
 *
 * The Dialog is a same-Fragment overlay: opening it does not navigate, stop Screen exposure, or
 * create another player owner. Background taps are intentionally ignored while hardware back and
 * the close affordance dismiss it.
 */
@Composable
fun TipsyShareSheet(
    visible: Boolean,
    title: String,
    channels: List<TipsyShareChannel>,
    onChannelClick: (TipsyShareChannel) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    busyChannel: TipsyShareChannel? = null,
    message: String? = null,
    messageIsError: Boolean = false,
    copyLinkLabel: String = "Copy Link",
    closeLabel: String = "Close",
    preview: @Composable BoxScope.() -> Unit,
) {
    if (!visible) return
    val density = LocalDensity.current
    val windowWidthPx = LocalWindowInfo.current.containerSize.width
    val messageMaxWidth = if (windowWidthPx > 0) {
        with(density) { windowWidthPx.toDp() * MESSAGE_MAX_WIDTH_FRACTION }
    } else {
        MESSAGE_FALLBACK_MAX_WIDTH.dp
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        ConfigureShareDialogWindow()
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = BACKDROP_ALPHA))
                .testTag("tipsy_share_sheet"),
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("tipsy_share_preview"),
                    content = preview,
                )

                TipsySharePanel(
                    title = title,
                    channels = normalizedChannels(channels),
                    busyChannel = busyChannel,
                    copyLinkLabel = copyLinkLabel,
                    closeLabel = closeLabel,
                    onChannelClick = onChannelClick,
                    onClose = onClose,
                )
            }

            message?.takeIf { it.isNotBlank() }?.let { text ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MESSAGE_ICON_GAP.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = MESSAGE_TOP_OFFSET.dp)
                        .widthIn(max = messageMaxWidth)
                        .clip(RoundedCornerShape(MESSAGE_RADIUS.dp))
                        .background(Color.Black.copy(alpha = MESSAGE_BACKGROUND_ALPHA))
                        .padding(
                            horizontal = MESSAGE_HORIZONTAL_PADDING.dp,
                            vertical = MESSAGE_VERTICAL_PADDING.dp,
                        )
                        .testTag("tipsy_share_message"),
                ) {
                    if (messageIsError) {
                        Image(
                            painter = painterResource(R.drawable.ic_profile_review_fail),
                            contentDescription = null,
                            modifier = Modifier.size(MESSAGE_ICON_SIZE.dp),
                        )
                    }
                    Text(
                        text = text,
                        color = Color.White,
                        fontSize = MESSAGE_FONT.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Compose 1.8's `FloatingDialogWindowTheme` sets `backgroundDimEnabled=true`. The sheet paints its
 * own exact 0.85 scrim, so leaving FLAG_DIM_BEHIND enabled would multiply two dim layers and make
 * the preview materially darker than RN. This Dialog owns its Window and is destroyed on close;
 * no Activity flags are mutated or need restoring.
 */
@Composable
private fun ConfigureShareDialogWindow() {
    val view = LocalView.current
    SideEffect {
        view.findDialogWindow()?.let { window ->
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(0f)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.makeSystemBarsTransparent()
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun Window.makeSystemBarsTransparent() {
    // Dialog owns a separate Window, so MainActivity.enableEdgeToEdge() does not configure it.
    statusBarColor = android.graphics.Color.TRANSPARENT
    navigationBarColor = android.graphics.Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isNavigationBarContrastEnforced = false
    }
}

private fun View.findDialogWindow(): Window? {
    if (this is DialogWindowProvider) return window
    var node: ViewParent? = parent
    while (node != null) {
        if (node is DialogWindowProvider) return node.window
        node = node.parent
    }
    return null
}

@Composable
private fun TipsySharePanel(
    title: String,
    channels: List<TipsyShareChannel>,
    busyChannel: TipsyShareChannel?,
    copyLinkLabel: String,
    closeLabel: String,
    onChannelClick: (TipsyShareChannel) -> Unit,
    onClose: () -> Unit,
) {
    val density = LocalDensity.current
    // Animated.Value(300) is created outside ScaledSheet in RN, so this is physical dp.
    val initialOffsetPx = with(density) { PANEL_INITIAL_OFFSET.dp.toPx() }
    val offset = remember(initialOffsetPx) { Animatable(initialOffsetPx) }
    LaunchedEffect(initialOffsetPx) {
        offset.snapTo(initialOffsetPx)
        offset.animateTo(
            targetValue = 0f,
            // RN uses tension=65/friction=11. Compose exposes stiffness/damping rather than the
            // same physical parameters; medium-low + no-bounce is the closest stable visual
            // mapping across API 24-36 without baking a duration.
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }

    val systemBottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
    // RN computes `max(insets.bottom, 20)` outside ScaledSheet, so the floor is physical dp.
    val bottomPadding = maxOf(systemBottom, PANEL_MIN_BOTTOM_PADDING.dp)
    val shape = RoundedCornerShape(topStart = PANEL_RADIUS.s, topEnd = PANEL_RADIUS.s)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = offset.value }
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(PANEL_TOP_COLOR, PANEL_MIDDLE_COLOR, PANEL_BOTTOM_COLOR),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ),
            )
            .padding(top = PANEL_TOP_PADDING.s, bottom = bottomPadding),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(PANEL_HEADER_HEIGHT.s),
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = TITLE_FONT.sSp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = TITLE_HORIZONTAL_GUARD.s),
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-CLOSE_END_INSET).s, y = (-CLOSE_TOP_OFFSET).s)
                    .size(CLOSE_HIT_SIZE.s)
                    .clickable(onClick = onClose)
                    .testTag("tipsy_share_close"),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_share_close),
                    contentDescription = closeLabel,
                    // JSX inline width/height, deliberately outside RN ScaledSheet.
                    modifier = Modifier.size(CLOSE_ICON_SIZE.dp),
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.Start,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = PLATFORM_ROW_TOP_PADDING.s,
                    bottom = PLATFORM_ROW_BOTTOM_PADDING.s,
                )
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = PLATFORM_ROW_HORIZONTAL_PADDING.s),
        ) {
            channels.forEach { channel ->
                TipsySharePlatformButton(
                    channel = channel,
                    label = if (channel == TipsyShareChannel.COPY_LINK) {
                        copyLinkLabel
                    } else {
                        channel.platformLabel
                    },
                    busy = channel == busyChannel,
                    onClick = { onChannelClick(channel) },
                )
            }
        }
    }
}

@Composable
private fun TipsySharePlatformButton(
    channel: TipsyShareChannel,
    label: String,
    busy: Boolean,
    onClick: () -> Unit,
) {
    // BaseShareModal defines opacity inside ScaledSheet; unlike most codebases, that helper also
    // scales opacity. Preserve its `0.5 * min(width/375, 1.3)` behavior.
    val busyAlpha = BUSY_ALPHA * ScaledMetrics.scaleFactor()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(PLATFORM_BUTTON_WIDTH.s)
            .alpha(if (busy) busyAlpha else 1f)
            .clip(RoundedCornerShape(PLATFORM_BUTTON_RADIUS.s))
            .clickable(enabled = !busy, onClick = onClick)
            .padding(PLATFORM_BUTTON_PADDING.s)
            .testTag(channel.testTag),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            // The wrapper is a ScaledSheet style while the nested JSX image is fixed 48dp.
            modifier = Modifier.size(PLATFORM_ICON_CONTAINER_SIZE.s),
        ) {
            Image(
                painter = painterResource(channel.iconRes),
                contentDescription = label,
                modifier = Modifier.size(PLATFORM_ICON_SIZE.dp),
            )
        }
        Text(
            text = label,
            color = Color.White.copy(alpha = PLATFORM_LABEL_ALPHA),
            fontSize = PLATFORM_LABEL_FONT.sSp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = PLATFORM_LABEL_TOP_PADDING.s),
        )
    }
}

private fun normalizedChannels(channels: List<TipsyShareChannel>): List<TipsyShareChannel> =
    listOf(TipsyShareChannel.COPY_LINK) +
        channels.filterNot { it == TipsyShareChannel.COPY_LINK }.distinct()

private val TipsyShareChannel.platformLabel: String
    get() = when (this) {
        TipsyShareChannel.COPY_LINK -> "Copy Link"
        TipsyShareChannel.DISCORD -> "Discord"
        TipsyShareChannel.INSTAGRAM -> "Instagram"
        TipsyShareChannel.TIKTOK -> "TikTok"
        TipsyShareChannel.X -> "X"
        TipsyShareChannel.FACEBOOK -> "Facebook"
    }

private val TipsyShareChannel.testTag: String
    get() = if (this == TipsyShareChannel.COPY_LINK) {
        "tipsy_share_copy_link"
    } else {
        "tipsy_share_platform_$trackingId"
    }

@get:DrawableRes
private val TipsyShareChannel.iconRes: Int
    get() = when (this) {
        TipsyShareChannel.COPY_LINK -> R.drawable.ic_share_link
        TipsyShareChannel.DISCORD -> R.drawable.ic_profile_social_discord
        TipsyShareChannel.INSTAGRAM -> R.drawable.ic_profile_social_instagram
        TipsyShareChannel.TIKTOK -> R.drawable.ic_profile_social_tiktok
        TipsyShareChannel.X -> R.drawable.ic_profile_social_twitter
        TipsyShareChannel.FACEBOOK -> R.drawable.ic_profile_social_facebook
    }

private val PANEL_TOP_COLOR = Color(0xFF303229)
private val PANEL_MIDDLE_COLOR = Color(0xFF3D2C29)
private val PANEL_BOTTOM_COLOR = Color(0xFF432E3C)
private const val BACKDROP_ALPHA = 0.85f
private const val PANEL_INITIAL_OFFSET = 300
private const val PANEL_RADIUS = 20
private const val PANEL_TOP_PADDING = 20
private const val PANEL_MIN_BOTTOM_PADDING = 20
private const val PANEL_HEADER_HEIGHT = 24
private const val TITLE_FONT = 18
private const val TITLE_HORIZONTAL_GUARD = 64
private const val CLOSE_HIT_SIZE = 40
private const val CLOSE_ICON_SIZE = 32
private const val CLOSE_END_INSET = 16
private const val CLOSE_TOP_OFFSET = 12
private const val PLATFORM_ROW_TOP_PADDING = 8
private const val PLATFORM_ROW_BOTTOM_PADDING = 8
private const val PLATFORM_ROW_HORIZONTAL_PADDING = 16
private const val PLATFORM_BUTTON_WIDTH = 80
private const val PLATFORM_BUTTON_RADIUS = 16
private const val PLATFORM_BUTTON_PADDING = 8
private const val PLATFORM_ICON_CONTAINER_SIZE = 48
private const val PLATFORM_ICON_SIZE = 48
private const val PLATFORM_LABEL_TOP_PADDING = 6
private const val PLATFORM_LABEL_FONT = 12
private const val PLATFORM_LABEL_ALPHA = 0.3f
private const val BUSY_ALPHA = 0.5f
private const val MESSAGE_FONT = 13
private const val MESSAGE_ICON_SIZE = 24
private const val MESSAGE_ICON_GAP = 10
private const val MESSAGE_RADIUS = 999
private const val MESSAGE_HORIZONTAL_PADDING = 16
private const val MESSAGE_VERTICAL_PADDING = 10
private const val MESSAGE_BACKGROUND_ALPHA = 0.8f
private const val MESSAGE_TOP_OFFSET = 300
private const val MESSAGE_MAX_WIDTH_FRACTION = 0.8f
private const val MESSAGE_FALLBACK_MAX_WIDTH = 300
