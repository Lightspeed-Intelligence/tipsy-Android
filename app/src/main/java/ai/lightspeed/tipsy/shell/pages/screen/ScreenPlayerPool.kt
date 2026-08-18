package ai.lightspeed.tipsy.shell.pages.screen

import android.content.Context
import android.os.Looper
import androidx.annotation.MainThread
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import android.app.ActivityManager
import android.util.Log

/**
 * Screen（大屏页）的**有界** ExoPlayer 池（W4-P2）。
 *
 * ## iOS 先例 → Android 映射
 *
 * iOS 侧同一块是 `Tipsy-iOS/Pages/Screen/AVPlayerPool.swift`（93 行），
 * 选型研究见 `Tipsy-iOS/llmdoc/architecture/tab1-screen-migration-research.md` §2.3。
 * 本类照搬其**结构与四个决定**，只把 API 换成 Media3：
 *
 * | iOS | Android | 说明 |
 * | --- | --- | --- |
 * | 容量按 `physicalMemory` 分档 3~5 | 同档位，读 [ActivityManager.getMemoryClass] | 见 [capacityFor] |
 * | `borrow(urlString:)` / `recycle(_:)` | [borrow] / [recycle] | 显式借还，不做自动回收 |
 * | `actionAtItemEnd = .pause` | `repeatMode = REPEAT_MODE_OFF` | **播完不循环**，见下 |
 * | 不接管 AudioSession | `setAudioAttributes(handleAudioFocus = false)` | 见下 |
 *
 * ⚠️ **`assetCache` 那层 iOS 有、Android 刻意没有**：iOS 缓存 `AVURLAsset` 是因为
 * `AVURLAsset` 自己持有加载状态；Media3 的对等物是 `MediaSource`，而它一旦被
 * `ExoPlayer.setMediaSource` 消费就与那个 player 绑定，跨 player 复用是未定义行为。
 * Media3 侧的等价收益由 [DefaultLoadControl] 的缓冲窗口 + HTTP 磁盘缓存拿到，
 * 不需要在池里再缓一层。**偏离理由：平台机制不同，不是简化。**
 *
 * ## 为什么必须有界（这是 P2 的首要风险）
 *
 * 方案 §8.1 把 Screen 标为「唯一首要风险是 OOM 而不是数据正确性的页面」。
 * 每个 [ExoPlayer] 持有解码器 + 缓冲区，`largeHeap="true"`（已移植，见 manifest）
 * 也扛不住无界创建。所以：
 *
 * - 容量硬上限 [capacity]，**超容量的 borrow 返回 null**（调用方降级显示封面图），
 *   不排队、不新建。宁可少播一张卡，不要 OOM。
 * - [recycle] 超容量的实例**直接 release**，不留在池里占内存。
 * - 上层的 ±1 窗口（见 `ScreenVideoHost`）保证同时最多 3 个在用，
 *   容量 3~5 是留给翻页瞬间的重叠期。
 *
 * ## 线程
 *
 * ExoPlayer 实例必须在**创建它的线程**上被访问，本池统一约束为主线程。
 * 违反的表现是 `IllegalStateException: Player is accessed on the wrong thread`
 * —— 会崩，但只在特定时序，所以 [assertMainThread] 显式钉住而不是靠运气。
 */
@MainThread
class ScreenPlayerPool(
    private val context: Context,
    /** 注入点仅供测试覆盖容量分档；生产恒走 [capacityFor]。 */
    capacityOverride: Int? = null,
) {
    /** 池容量（对齐 iOS：≥6GB→5、≥4GB→4、否则 3）。 */
    val capacity: Int = capacityOverride ?: capacityFor(context)

    /** 空闲实例（可复用）。 */
    private val idle = ArrayDeque<ExoPlayer>()

    /** 已借出的实例数 —— 与 [idle] 一起构成「已存活总数」的账。 */
    private var borrowed = 0

    /** 池是否已释放；释放后所有操作变成 no-op（Fragment 销毁后的迟到回调）。 */
    private var released = false

    /** 当前存活的播放器总数（借出 + 空闲）。测试与真机内存断言用。 */
    val aliveCount: Int get() = borrowed + idle.size

    /**
     * 借一个播放器并装载 [url]。
     *
     * @return null 表示**该拒绝播放**：url 空、池已满、或池已释放。
     *   调用方必须降级为封面图，**不要重试或自建 player** —— 那就绕过了有界。
     */
    // 经 createPlayer 触达 DefaultLoadControl（opt-in），故标在这里。
    // 标方法而非整个类：这样持有/传递 ScreenPlayerPool 的代码不必 opt-in
    @UnstableApi
    fun borrow(url: String?): ExoPlayer? {
        assertMainThread()
        if (released || url.isNullOrBlank()) return null
        if (borrowed >= capacity) {
            // 不是异常路径：翻页快时会短暂命中，降级封面图即可
            Log.d(TAG, "池已满（capacity=$capacity），降级封面图")
            return null
        }
        val player = idle.removeLastOrNull() ?: createPlayer()
        borrowed++
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        return player
    }

    /**
     * 归还播放器：停播 + 清空 media item 回到空闲池；超容量则 release。
     *
     * ⚠️ 必须 [ExoPlayer.clearMediaItems] 而不只是 pause —— 只 pause 的话
     * 解码器与缓冲区仍挂在旧 item 上，池里攒几个就等于没有上界。
     */
    fun recycle(player: ExoPlayer) {
        assertMainThread()
        if (released) {
            player.release()
            return
        }
        borrowed = (borrowed - 1).coerceAtLeast(0)
        player.pause()
        player.clearMediaItems()
        if (idle.size < capacity) {
            idle.addLast(player)
        } else {
            player.release()
        }
    }

    /** 释放全部实例（Fragment `onDestroyView`）。之后本池不可再用。 */
    fun release() {
        assertMainThread()
        released = true
        idle.forEach { it.release() }
        idle.clear()
        borrowed = 0
    }

    // `setLoadControl` 本身也是 opt-in API，理由与 buildLoadControl 同处
    @UnstableApi
    private fun createPlayer(): ExoPlayer =
        ExoPlayer.Builder(context)
            .setLoadControl(buildLoadControl())
            .build()
            .apply {
                // 播完不循环 —— 对齐 RN 的 repeat={false}（FeedMediaItem.tsx:614）。
                // 播完由上层切 tagline，不是无缝 loop。iOS 用 actionAtItemEnd = .pause。
                repeatMode = Player.REPEAT_MODE_OFF
                // 静音 feed：不申请音频焦点，避免每划一张卡就打断用户的后台音乐。
                // 对齐 RN 的 disableAudioSessionManagement={true}（同文件 :630）。
                setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
                // 视频铺满，音量由上层按 videoSoundEnabled 设（见 ScreenVideoHost）
                volume = 0f
            }

    companion object {
        private const val TAG = "ScreenPlayerPool"

        /**
         * 缓冲配置 —— ⚠️ **六个值抄的是 RN 的 Android 分支，不是 iOS**。
         *
         * iOS 侧**没有** buffer 配置（`AVPlayer` 默认策略即可，见 iOS 研究文档 §2.1），
         * 所以这一处**没有 iOS 先例可对齐**。真值是 `FeedMediaItem.tsx:602-608`，
         * 那里的注释原文写「Android 缓冲配置 - 防止大视频导致 OOM」：
         *
         * | RN 参数 | 值 | Media3 对等物 |
         * | --- | --- | --- |
         * | `minBufferMs` | 2500 | `minBufferMs` |
         * | `maxBufferMs` | 5000 | `maxBufferMs` |
         * | `bufferForPlaybackMs` | 500 | `bufferForPlaybackMs` |
         * | `bufferForPlaybackAfterRebufferMs` | 1500 | 同名 |
         * | `backBufferDurationMs` | 2000 | `setBackBuffer` |
         * | `cacheSizeMB` | 50 | 磁盘缓存，**不在 LoadControl**（见下） |
         *
         * ⚠️ `cacheSizeMB: 50` 是 **HTTP 磁盘缓存**，Media3 里对应 `SimpleCache` +
         * `CacheDataSource.Factory`，不属 LoadControl。本刀**不实现它** ——
         * 壳与 RN 共享同一个 OkHttpClient（见 `ApiClient` 注释），两边各配一份
         * 磁盘缓存会在同一目录下打架。记为**已知偏差**，写进进度文档。
         *
         * ⚠️ 标 `@UnstableApi`：[DefaultLoadControl] 在 Media3 里是 opt-in API。
         * **这不是能绕开的选择** —— 缓冲窗口只能经 LoadControl 配，
         * 而 RN 侧（react-native-video）用的就是同一个类。
         *
         * 注意用的是 `@UnstableApi` 而**不是 Kotlin 的 `@OptIn(UnstableApi::class)`**：
         * 后者对这个 marker 无效（它是 Java 注解、没有 `@RequiresOptIn`），
         * Kotlin 会给一句 "'@OptIn' has no effect" 的警告，而 lint 的
         * `UnsafeOptInUsageError` 照样报错 —— 看起来处理了，其实没有。
         *
         * 采用**逐点标注而非加 lint baseline**：baseline 会把整条规则静音，
         * 将来别处误用不稳定 API 就没人拦了（方案 §5.4 的「不弱化质量配置」）。
         */
        @UnstableApi
        internal fun buildLoadControl(): DefaultLoadControl =
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 2_500,
                    /* maxBufferMs = */ 5_000,
                    /* bufferForPlaybackMs = */ 500,
                    /* bufferForPlaybackAfterRebufferMs = */ 1_500,
                )
                .setBackBuffer(/* backBufferDurationMs = */ 2_000, /* retainBackBufferFromKeyframe = */ false)
                .build()

        private val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        /**
         * 容量分档 —— 对齐 iOS 的 `≥6GB→5 / ≥4GB→4 / else 3`。
         *
         * ⚠️ **用 [ActivityManager.getMemoryClass] 而不是设备物理内存**：
         * Android 上真正的上界是**本进程的堆上限**，不是设备装了多少内存。
         * 一台 8GB 的低端机 memoryClass 可能只有 128MB，照物理内存开 5 个播放器
         * 就是照着 OOM 走。这是 iOS 那条判据在 Android 上的**必要偏离**
         * （iOS 没有 per-app 堆上限这个概念）。
         *
         * `largeHeap="true"` 下取 `largeMemoryClass`（manifest 已移植该 flag）。
         */
        internal fun capacityFor(context: Context): Int {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return MIN_CAPACITY
            val heapMb = am.largeMemoryClass.takeIf { it > 0 } ?: am.memoryClass
            return when {
                heapMb >= 512 -> 5
                heapMb >= 256 -> 4
                else -> MIN_CAPACITY
            }
        }

        private const val MIN_CAPACITY = 3

        private fun assertMainThread() {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "ScreenPlayerPool 必须在主线程访问（ExoPlayer 的线程亲和性）"
            }
        }
    }
}
