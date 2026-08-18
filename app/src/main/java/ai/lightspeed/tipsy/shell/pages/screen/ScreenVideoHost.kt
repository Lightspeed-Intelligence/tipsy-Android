package ai.lightspeed.tipsy.shell.pages.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/**
 * 单条 showcase 的视频层（W4-P2）。
 *
 * ## 只在 ±1 窗口内挂载
 *
 * 调用方（`ScreenScreen`）用 `abs(page - currentPage) <= 1` 决定是否组合本函数
 * —— 对齐 RN `FeedMediaItem.tsx:594` 的 `Math.abs(index - currentIndex) <= 1`，
 * 也对齐 iOS 池的「当前项 ±1 范围内挂播放器」。窗口外只渲染封面图。
 *
 * ⚠️ 窗口是**正确性前提不是优化**：它与 [ScreenPlayerPool.capacity] 一起构成
 * OOM 的上界。改窗口宽度前先看池容量（方案 §8.1「有界池」）。
 *
 * ## 缩略图时序（最容易做丢的一条）
 *
 * **不在 `onLoad`/`STATE_READY` 隐藏封面，而是等真的有画面帧**。
 * RN 侧靠 `onProgress` 的 `currentTime > 0` 判定（`FeedMediaItem.tsx:487-492`
 * + `progressUpdateInterval={250}`），iOS 研究文档 §4 把这条列为
 * 「必须复刻的隐藏行为」并注明理由是**防黑帧**。
 *
 * Media3 的对等信号是 [Player.Listener.onRenderedFirstFrame] —— 比轮询
 * `currentPosition` 更准且不用起 250ms 定时器。**这是等价替换，不是偏离**：
 * 两者都表示「解码器真的吐出画面了」，而 `STATE_READY` 只表示缓冲够了。
 *
 * ## 播完不循环
 *
 * 对齐 RN `repeat={false}` + `handleVideoEnd`（`:475-484`）：播完 → 暂停 →
 * `seekTo(0)` 回首帧 → **重新显示封面** → 通知上层切 tagline。
 * 池侧的 `REPEAT_MODE_OFF` 只保证不自动重播，回首帧与切 tagline 在这里做。
 */
@Composable
@UnstableApi  // PlayerView / ExoPlayer 均为 Media3 opt-in API
fun ScreenVideoHost(
    url: String?,
    thumbnailUrl: String?,
    /** 是否当前页 —— 只有当前页播放，±1 的邻页只预备好（对齐 RN 的 `paused={!isPlaying}`）。 */
    isCurrent: Boolean,
    /** 页面是否可见（Tab 切走 / App 进后台 → false）。 */
    isActive: Boolean,
    /** 声音开关，见 [ScreenSoundPreference]。 */
    soundEnabled: Boolean,
    pool: ScreenPlayerPool,
    /** 播完回调 —— 上层据此切 tagline（`hasUserInteracted` 的判定在上层）。 */
    onPlaybackEnded: () -> Unit,
    /** 首帧已渲染 —— 上层据此隐藏封面并结算首屏埋点。 */
    onFirstFrame: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (url.isNullOrBlank()) return

    val context = LocalContext.current
    // 借到的实例；null 表示池已满 —— 此时**什么都不渲染**，上层的封面图留在原位。
    var player by remember(url) { mutableStateOf<ExoPlayer?>(null) }

    // 借还与 url 绑定：url 变了就是另一条卡，必须换 item 而不是复用旧的
    DisposableEffect(url, pool) {
        val borrowed = pool.borrow(url)
        player = borrowed
        onDispose {
            player = null
            borrowed?.let(pool::recycle)
        }
    }

    val current = player
    if (current == null) return

    // 播完 / 首帧：listener 的生命周期跟着 player 实例，换实例要重挂
    DisposableEffect(current) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                onFirstFrame()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    // 对齐 RN handleVideoEnd：暂停 + 回首帧 + 上层切 tagline
                    current.pause()
                    current.seekTo(0)
                    onPlaybackEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 对齐 RN handleVideoError：不崩、不重试，把封面图留着
                // （上层的 showThumbnail 本就还是 true —— 首帧没来过就没隐藏过）
                current.pause()
            }
        }
        current.addListener(listener)
        onDispose { current.removeListener(listener) }
    }

    // 播放门：当前页 + 页面可见。邻页 prepare 好但不播（预热效果，对齐 iOS 预取）
    LaunchedEffect(current, isCurrent, isActive) {
        if (isCurrent && isActive) {
            // 轻微延后起播：翻页动画未落定就起播会争解码器，且用户可能只是划过
            delay(PLAY_START_DELAY_MS)
            current.playWhenReady = true
        } else {
            current.playWhenReady = false
        }
    }

    LaunchedEffect(current, soundEnabled) {
        current.volume = if (soundEnabled) 1f else 0f
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                // RN 是 resizeMode="cover" —— Media3 的对等是 ZOOM（裁切铺满），
                // 不是 FIT（会留黑边）。全屏 feed 留黑边等于视觉不对等。
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                // 封面由上层的 Coil 图承担，PlayerView 自己不要画背景色，
                // 否则首帧前会盖住封面变成黑屏 —— 正是缩略图时序要防的那个黑帧。
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { view -> view.player = current },
        onRelease = { view -> view.player = null },
    )
}

/** 起播前的落定延时。翻页动画约 300ms，取其后半段避免争解码器。 */
private const val PLAY_START_DELAY_MS = 180L
