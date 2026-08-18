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
    /** 播放出错 —— 上层据此把封面重新显示出来（对齐 RN `setShowThumbnail(true)`）。 */
    onPlaybackError: () -> Unit,
    /**
     * 卡片划离时复位封面（对齐 RN 切卡时的 `setShowThumbnail(true)`）。
     *
     * ⚠️ 与 [onPlaybackError] 分开是为了**语义可读**；但更重要的是它
     * **不在失焦时调用** —— 失焦只暂停、保住进度（见播放门那段注释）。
     */
    onResetToCover: () -> Unit,
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
                // 对齐 RN `handleVideoError`（`FeedMediaItem.tsx:495-499`）：
                // 不崩、不重试，**并且把封面重新显示出来** —— RN 那里明写
                // `setShowThumbnail(true)`。
                //
                // ⚠️ 首帧之后才出错（网络断/解码失败）时必须复位，否则视频层
                // 停在最后一帧或黑帧上、封面被它盖住 —— 表现是「画面冻住」。
                // 早前只 pause 不复位就是这个缺陷（Codex review 第 4 条）。
                current.pause()
                onPlaybackError()
            }
        }
        current.addListener(listener)
        onDispose { current.removeListener(listener) }
    }

    // ## 播放门：两种「不播」语义不同，别合并
    //
    // RN 把这两条分得很清（`FeedMediaItem.tsx:326-334`，注释原文
    // 「页面失去焦点时只暂停，不重置状态（保持缓冲，快速恢复）」）：
    //
    // | 情形 | RN 行为 | 这里 |
    // | --- | --- | --- |
    // | **卡片划离/划回**（不再是当前页） | `setShowThumbnail(true)` + `seek(0)` + 重置 | 复位封面 + seekTo(0) |
    // | **Tab/Surface/App 失焦** | 只 `setIsPlaying(false)`，**不重置** | 只 pause，保住进度与缓冲 |
    //
    // 合并成一条的后果：切个 Tab 回来视频从头开始（该保进度的没保住），
    // 或者划走再划回接着上次播（该重置的没重置）。两个方向都不对等。

    // 轴一：是否当前页 —— 变化时重置（对齐 RN 的 seek(0) + 显示封面）
    LaunchedEffect(current, isCurrent) {
        if (isCurrent) {
            current.seekTo(0)
            delay(PLAY_START_DELAY_MS)
            if (isActive) current.playWhenReady = true
        } else {
            // 划离：暂停 + 回首帧 + 让上层重新显示封面
            current.playWhenReady = false
            current.seekTo(0)
            onResetToCover()
        }
    }

    // 轴二：页面是否可见 —— 只控制播放/暂停，**不 seek、不复位封面**
    LaunchedEffect(current, isActive) {
        if (isActive && isCurrent) {
            delay(PLAY_START_DELAY_MS)
            current.playWhenReady = true
        } else if (!isActive) {
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
