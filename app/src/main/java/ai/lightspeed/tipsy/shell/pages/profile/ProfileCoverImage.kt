package ai.lightspeed.tipsy.shell.pages.profile

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalPlatformContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transformations

/**
 * Profile 网格封面及其合规模糊层。
 *
 * RN Android 的 `BlurView intensity={40}` 经 expo-blur 默认 reduction factor 4 后，
 * 实际交给 Dimezis BlurView 的半径约为 10px。API 31+ 直接用 Compose RenderEffect
 * 做同类实时高斯模糊；API 24–30 则使用 Coil 位图降级，避免低版本 fail-open。
 *
 * 暗色盖板取 iOS `CoverBlurView` 的 8% fail-safe：只负责系统模糊未就绪时压暗，
 * 不照搬 RN 各卡片在 Android 分支使用的 50%–90% 黑底；那些值直接用于 Compose
 * 会主导观感，让实时模糊几乎不可见。
 */
@Composable
internal fun BoxScope.ProfileCoverImage(
    url: String,
    contentDescription: String?,
    shouldBlur: Boolean,
    modifier: Modifier = Modifier,
) {
    val blurRadius = with(LocalDensity.current) {
        // expo-blur / Dimezis 的半径是 px；换成当前设备的 dp 后再交给 Compose。
        RN_ANDROID_BLUR_RADIUS_PX.toDp()
    }
    val useRealtimeBlur = shouldBlur && supportsRealtimeProfileCoverBlur(Build.VERSION.SDK_INT)
    val model = if (shouldBlur && !useRealtimeBlur) {
        ImageRequest.Builder(LocalPlatformContext.current)
            .data(url)
            .transformations(CoverBlurTransformation())
            .build()
    } else {
        url
    }

    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .matchParentSize()
            .then(if (useRealtimeBlur) Modifier.blur(blurRadius) else Modifier),
    )

    if (shouldBlur) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = FAIL_SAFE_DIM_ALPHA)),
        )
    }
}

/** expo-blur intensity 40 / 默认 blurReductionFactor 4。 */
private const val RN_ANDROID_BLUR_RADIUS_PX = 10f

/** iOS `CoverBlurView.dimView` 的低透明度安全盖板。 */
private const val FAIL_SAFE_DIM_ALPHA = 0.08f

/** 低于 Android 12 时 Compose blur 是 no-op，必须走位图降级。 */
internal fun supportsRealtimeProfileCoverBlur(apiLevel: Int): Boolean =
    apiLevel >= Build.VERSION_CODES.S
