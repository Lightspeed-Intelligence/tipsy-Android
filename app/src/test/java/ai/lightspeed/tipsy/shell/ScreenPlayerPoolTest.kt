package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.screen.ScreenPlayerPool
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 有界播放器池的**缓冲参数**（W4-P2）。
 *
 * ## 为什么这里只测缓冲值，不测 borrow/recycle
 *
 * [ScreenPlayerPool.borrow] 要造真的 [androidx.media3.exoplayer.ExoPlayer]，
 * 而 ExoPlayer 需要真实 Looper + Android 图形栈，JVM 单测里造不出来。
 *
 * ⚠️ **不用 `returnDefaultValues = true` 绕** —— 那会让所有未 mock 的
 * Android API 静默返回默认值，是方案 §5.4 点名的「假绿色」。
 * 借还与「同时存活数 ≤ capacity」只能靠真机验证，见 PR 的冒烟段。
 *
 * 这里钉住的是能在 JVM 上验的那部分：六个缓冲值。它们抄的是 RN 的
 * **Android 分支**，写错一位不会报错，只表现为弱网卡顿或大视频 OOM。
 */
class ScreenPlayerPoolTest {

    @Test
    fun `四个缓冲时长与 RN 的 Android 分支逐一对齐`() {
        // 真值：tipsy-app/src/components/video/FeedMediaItem.tsx:602-608
        // ⚠️ iOS 侧**没有** buffer 配置（AVPlayer 默认策略，见 iOS 研究文档 §2.1），
        // 所以这一处没有 iOS 先例可抄 —— 照 iOS 的「不配」做会丢掉 RN 专门为
        // 「防止大视频导致 OOM」加的参数（RN 源码注释原文）
        val loadControl = ScreenPlayerPool.buildLoadControl()

        // DefaultLoadControl 不暴露 getter，反射读私有字段。
        // 这不优雅，但比造 LoadControl.Parameters（需要 PlayerId / Timeline /
        // MediaPeriodId 一整套）更能直接钉住「这六个数字没被改动」这件事本身。
        assertEquals("minBufferMs", 2_500L, loadControl.readUs("minBufferUs") / 1_000L)
        assertEquals("maxBufferMs", 5_000L, loadControl.readUs("maxBufferUs") / 1_000L)
        assertEquals(
            "bufferForPlaybackMs（起播门槛，RN 注释「加快首次播放」）",
            500L,
            loadControl.readUs("bufferForPlaybackUs") / 1_000L,
        )
        assertEquals(
            "bufferForPlaybackAfterRebufferMs",
            1_500L,
            loadControl.readUs("bufferForPlaybackAfterRebufferUs") / 1_000L,
        )
    }

    @Test
    fun `回放缓冲为 2 秒`() {
        // RN 的 backBufferDurationMs: 2000。这个值决定往回 seek 时不用重新下载
        // 的窗口 —— 也是内存占用的一部分，不能"顺手调大"
        assertEquals(
            2_000L,
            ScreenPlayerPool.buildLoadControl().readUs("backBufferDurationUs") / 1_000L,
        )
    }

    /** 反射读 [androidx.media3.exoplayer.DefaultLoadControl] 的私有微秒字段。 */
    private fun Any.readUs(name: String): Long {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.getLong(this)
    }
}
