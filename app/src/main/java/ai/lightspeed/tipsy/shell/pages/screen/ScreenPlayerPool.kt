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
 * | 不接管 AudioSession（iOS-only prop） | `setAudioAttributes(handleAudioFocus = **true**)` | ⚠️ **这一行不是等价映射**：iOS 的 `disableAudioSessionManagement` 无 Android 对等物，RN Android 默认请求 `AUDIOFOCUS_GAIN`。详见 [createPlayer] |
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
    /**
     * 播放器工厂 seam —— 仅供测试注入假实例。
     *
     * 生产恒为 null（走 [createPlayer]）。有这个 seam 才能在 JVM 上验
     * 容量上界 / 溢出降级 / 归还 / 释放这四条**账面不变量**；
     * 没有它就只能靠真机，而真机验不了「借第 capacity+1 个返回 null」这种边界。
     */
    private val playerFactory: (() -> ExoPlayer)? = null,
) {
    /** 池容量（对齐 iOS：≥6GB→5、≥4GB→4、否则 3）。 */
    val capacity: Int = capacityOverride ?: capacityFor(context)

    /**
     * 借还账本 —— 四条不变量（池满降级 / 外来归还 / 重复归还 / release 覆盖借出）
     * 都在 [ScreenPlayerLedger] 里，那里能在 JVM 上单测。
     * 本类只负责把账本的结论翻译成 Media3 调用。
     */
    private val ledger = ScreenPlayerLedger<ExoPlayer>(capacity)


    /** 当前存活的播放器总数（借出 + 空闲）。测试与真机内存断言用。 */
    val aliveCount: Int get() = ledger.aliveCount

    /** 当前借出数。有界保证的直接观测点。 */
    val borrowedCount: Int get() = ledger.borrowedCount

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
        if (url.isNullOrBlank()) return null
        // ⚠️ 整条借出链都要兜住异常：本方法跑在 Compose 的组合路径上，
        // 抛出去就是整页崩。除了 cache（已在 dataSourceFactory 里降级），
        // ExoPlayer 构造本身也可能失败（设备解码器耗尽、厂商 ROM 差异），
        // 而播视频是**可选增强** —— 失败时降级封面图，不是让大屏页不可用
        // ⚠️ `catch (Exception)` 而**不是** `runCatching` —— 后者会连
        // `OutOfMemoryError` 一起吞。而 OOM 恰恰是本页首要风险（方案 §8.1），
        // 吞掉它只会让崩溃移到别处、更难归因
        val player = try {
            ledger.borrow { playerFactory?.invoke() ?: createPlayer() }
        } catch (error: Exception) {
            Log.w(TAG, "创建播放器失败，降级封面图", error)
            null
        }
        if (player == null) {
            // 池满不是异常路径：翻页快时会短暂命中，降级封面图即可
            Log.d(TAG, "池满/已释放/创建失败（capacity=$capacity），降级封面图")
            return null
        }
        return try {
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player
        } catch (error: Exception) {
            // ⚠️ 坏掉的实例要 **discard 而不是 recycle** —— 它的状态已不可信，
            // 放回 idle 会被下一张卡借走，于是「一次装载失败」变成
            // 「之后每张卡都可能拿到坏播放器」。discard 从账上销掉 + release 一次，
            // 这样借出计数也不会只增不减（否则几次后池永远"满"、整页不播且不报错）
            Log.w(TAG, "装载媒体失败，退役该播放器并降级封面图", error)
            if (ledger.discard(player)) player.release()
            null
        }
    }

    /**
     * 归还播放器：停播 + 清空 media item 回到空闲池；超容量则 release。
     *
     * ⚠️ 必须 [ExoPlayer.clearMediaItems] 而不只是 pause —— 只 pause 的话
     * 解码器与缓冲区仍挂在旧 item 上，池里攒几个就等于没有上界。
     */
    fun recycle(player: ExoPlayer) {
        assertMainThread()
        when (ledger.recycle(player)) {
            ScreenPlayerLedger.Recycle.ACCEPTED -> {
                // ⚠️ 必须 clearMediaItems 而不只是 pause —— 只 pause 的话解码器与
                // 缓冲区仍挂在旧 item 上，池里攒几个就等于没有上界
                player.pause()
                player.clearMediaItems()
            }

            ScreenPlayerLedger.Recycle.RELEASE_OVERFLOW -> player.release()

            ScreenPlayerLedger.Recycle.ALREADY_SHUT_DOWN ->
                // ⚠️ 池已释放：release() 已经销毁过它了，这里**不能再 release**
                // （double release 行为未定义，且会掩盖真正的泄漏）。
                // Compose 的 onDispose 在 Fragment 销毁后迟到触发，走的就是这条
                Log.d(TAG, "池已释放，忽略迟到的归还（已在 release 里销毁）")

            ScreenPlayerLedger.Recycle.REJECTED_UNKNOWN ->
                // 外来实例或重复归还：**什么都不做**。销毁别人持有的实例
                // 同样是缺陷，所以连 release 都不能调
                Log.w(TAG, "拒绝归还：不是本池借出的实例，或已归还过（重复 recycle）")
        }
    }

    /**
     * 释放全部实例（Fragment `onDestroyView`）。之后本池不可再用。
     *
     * ⚠️ **借出的也要 release**：早前只 release idle 再把计数清零 ——
     * 漏 dispose 或迟到 dispose 的 borrowed player 会**继续活着而账面为 0**，
     * 表现是「反复进出大屏页后视频不再播」（解码器被泄漏的实例占满），且不报错。
     */
    fun release() {
        assertMainThread()
        ledger.release().forEach { it.release() }
    }

    // `setLoadControl` 本身也是 opt-in API，理由与 buildLoadControl 同处
    @UnstableApi
    private fun createPlayer(): ExoPlayer =
        ExoPlayer.Builder(context)
            .setLoadControl(buildLoadControl())
            // 50MB 磁盘缓存（对齐 RN `cacheSizeMB: 50`），见 [videoCache]
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    dataSourceFactory(context),
                ),
            )
            .build()
            .apply {
                // 播完不循环 —— 对齐 RN 的 repeat={false}（FeedMediaItem.tsx:614）。
                // 播完由上层切 tagline，不是无缝 loop。iOS 用 actionAtItemEnd = .pause。
                repeatMode = Player.REPEAT_MODE_OFF
                // ⚠️ **必须 handleAudioFocus = true**（对齐 RN Android 的实际行为）。
                //
                // 早前写 false 并引用 RN 的 `disableAudioSessionManagement={true}` 作依据
                // —— **那个 prop 是 iOS-only**（`react-native-video/src/types/video.ts:387`
                // 明注 `// iOS`）。RN Android 侧的对应开关是 `disableFocus`，而
                // `FeedMediaItem.tsx` **没有传它**，所以 Android 走默认分支：
                // `requestAudioFocus()` 在起播前请求 `AUDIOFOCUS_GAIN`
                // （`ReactExoplayerView.java:1316-1324` + `setPlayWhenReady:1326-1338`）。
                //
                // 写 false 等于**未经批准的 Android 行为变更**：来电/其他 App 播音时
                // 本页不会让出焦点，两路声音会叠着放。
                setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
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
         * ⚠️ `cacheSizeMB: 50` 是**磁盘缓存**，不属 LoadControl —— 它由
         * [cacheDataSourceFactory] 实现（`SimpleCache` 单例 + `CacheDataSource`）。
         *
         * ⚠️ 早前这里写「本刀不实现它，因为壳与 RN 共享 OkHttpClient、
         * 两边各配磁盘缓存会打架」—— **那个理由是错的，已推翻**：RN video 走
         * 自己的 `RNVSimpleCache` + 自有 DataSource 链，**压根不经过壳的 OkHttp**。
         * 真正要避开的是「两个 `SimpleCache` 实例指向同一目录」，
         * 所以用独立目录而不是不做，见 [videoCache]。
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
                // ⚠️ 必须为 true：默认 false 时 Media3 会在总分配量触及内部
                // 阈值后**忽略上面的时长窗口**，按字节数决定加载 ——
                // 那样 minBufferMs=2500 就只是"建议"，弱网下起播会被拖长。
                // RN Android 侧靠 `bufferConfig` 全套生效，此处对齐它的语义
                .setPrioritizeTimeOverSizeThresholds(true)
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

        /** 50MB，对齐 RN `bufferConfig.cacheSizeMB`（`FeedMediaItem.tsx:608`）。 */
        private const val CACHE_SIZE_BYTES = 50L * 1024 * 1024

        /** ⚠️ 独立目录，**不复用** RN 的 `RNVCache` —— 见 [videoCache]。 */
        private const val CACHE_DIR_NAME = "ScreenVideoCache"

        /**
         * 进程级 [SimpleCache] 单例。
         *
         * ⚠️ **必须是单例**：`SimpleCache` 对同一目录同时存在两个实例会互相
         * 覆盖索引（RN 侧那份的注释自己都写着 "TODO: when to release?"）。
         *
         * ⚠️ **必须用独立目录 `ScreenVideoCache`，不复用 RN 的 `RNVCache`**：
         * RN 的 `RNVSimpleCache` 是它自己的 object 单例、持有自己的
         * `SimpleCache` 实例。两边指向同一目录就是上面那个「两个实例一个目录」
         * 的情况。**这与 OkHttpClient 共享不是一回事** —— 早前把这一条写成
         * 「壳与 RN 共享 OkHttp，所以磁盘缓存会打架」是错的：RN video 走的是
         * 自己的 DataSource 链，压根不经过壳的 OkHttp。
         */
        // SimpleCache 类型本身就是 opt-in，字段声明也要标（理由同 buildLoadControl）
        @Volatile
        @UnstableApi
        private var videoCache: androidx.media3.datasource.cache.SimpleCache? = null

        /**
         * 取缓存 —— **只返回已在后台就绪的那个**，否则 null（本次走 upstream）。
         *
         * ## 为什么必须后台初始化，不能在调用处等
         *
         * `SimpleCache` 构造只对**一种**失败抛：目录被另一实例锁住
         * （`SimpleCache.java:215` `lockFolder` → `IllegalStateException`）。
         *
         * 索引损坏、DB 初始化失败、建目录失败、`listFiles()` 返回 null
         * 都发生在构造器**启动的后台线程**（`:228-240`
         * `ExoPlayer:SimpleCacheInit`），`initialize()` 把它们**存进
         * `initializationException` 而不是抛**（`:519-560`），
         * 只在 `checkInitialization()` 才浮出来。
         *
         * ⚠️ 而 `checkInitialization()` 是 **`synchronized`**（`:249`），
         * 那个后台线程在 `initialize()` **全程持有同一把锁**（`:233`）——
         * 所以在主线程调它会**阻塞到整目录扫描完成**。50MB 缓存目录冷启时
         * 这就是几十到几百毫秒的主线程卡顿（首次进大屏页最明显），
         * 而且卡顿只在缓存已经攒起来之后才出现，本地空缓存测不出来。
         *
         * 所以：**构造 + 校验整段都放后台线程**，主线程只读结果。
         * 就绪前的那几次 borrow 走无缓存 upstream（不省流量而已，不影响播放）。
         */
        @UnstableApi
        private fun cacheOrNull(context: Context): androidx.media3.datasource.cache.SimpleCache? {
            videoCache?.let { return it }
            if (cacheState.get() == CACHE_IDLE) startCacheInit(context)
            // 未就绪就先不带 cache —— 不阻塞主线程等它
            return videoCache
        }

        /** 只启动一次后台初始化。 */
        @UnstableApi
        private fun startCacheInit(context: Context) {
            if (!cacheState.compareAndSet(CACHE_IDLE, CACHE_OPENING)) return
            val appContext = context.applicationContext
            Thread({ openCacheBlocking(appContext) }, "TipsyScreenCacheInit").apply {
                priority = Thread.MIN_PRIORITY
                isDaemon = true
            }.start()
        }

        /**
         * 后台建缓存：candidate → `checkInitialization()` 通过 → 才发布。
         *
         * 失败要**同时** `release()` 缓存（解开文件夹锁，否则下次连重试都会被
         * `lockFolder` 挡掉）**和** `close()` database provider
         * （`StandaloneDatabaseProvider` 持有 SQLite 句柄，不 close 就泄漏）。
         *
         * ⚠️ `catch (Exception)` 而**不是** `runCatching`/`Throwable`：
         * 后者会把 `OutOfMemoryError` 一并吞掉，吞下去只会让后面在更奇怪的地方崩。
         */
        @UnstableApi
        private fun openCacheBlocking(context: Context) {
            var candidate: androidx.media3.datasource.cache.SimpleCache? = null
            var provider: androidx.media3.database.StandaloneDatabaseProvider? = null
            try {
                provider = androidx.media3.database.StandaloneDatabaseProvider(context)
                candidate = androidx.media3.datasource.cache.SimpleCache(
                    java.io.File(context.cacheDir, CACHE_DIR_NAME),
                    androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(
                        CACHE_SIZE_BYTES,
                    ),
                    provider,
                )
                // 后台 initialize() 的失败只在这里浮出来（本方法已在后台线程）
                candidate.checkInitialization()
                videoCache = candidate
                cacheState.set(CACHE_READY)
            } catch (error: Exception) {
                // 顺序：先 release 缓存（解锁文件夹），再 close provider（放 SQLite 句柄）
                candidate?.let { c -> try { c.release() } catch (ignored: Exception) { } }
                provider?.let { p -> try { p.close() } catch (ignored: Exception) { } }
                cacheState.set(CACHE_FAILED)
                Log.w(TAG, "视频磁盘缓存不可用，降级为无缓存播放", error)
            }
        }

        private const val CACHE_IDLE = 0
        private const val CACHE_OPENING = 1
        private const val CACHE_READY = 2
        private const val CACHE_FAILED = 3

        /**
         * 缓存初始化状态机。FAILED 后不再重试 —— 目录锁/损坏索引这类问题
         * 重试也不会好，而每次 borrow 都试一遍会让每张卡都卡一下。
         */
        private val cacheState = java.util.concurrent.atomic.AtomicInteger(CACHE_IDLE)

        /**
         * DataSource 工厂。
         *
         * ⚠️ **缓存是可选能力，不得击穿页面**：`SimpleCache` 与
         * `StandaloneDatabaseProvider` 的**构造期**就可能抛（目录被另一个实例锁住、
         * 索引损坏、磁盘满、DB 初始化失败），而这条调用栈来自 Compose 的
         * borrow —— 抛出去就是整页崩。
         *
         * `FLAG_IGNORE_CACHE_ON_ERROR` **只覆盖 DataSource 的读写阶段，
         * 不覆盖构造阶段**，所以必须在这里 `runCatching` 后降级成不带 cache 的
         * [androidx.media3.datasource.DefaultDataSource.Factory]。
         * 降级的代价只是「不省流量」，比崩页面好得多。
         */
        @UnstableApi
        internal fun dataSourceFactory(
            context: Context,
        ): androidx.media3.datasource.DataSource.Factory {
            val upstream = androidx.media3.datasource.DefaultDataSource.Factory(context)
            val cache = cacheOrNull(context) ?: return upstream
            return androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstream)
                // 读写阶段出错（磁盘满等）继续走上游，不让播放整体失败
                .setFlags(
                    androidx.media3.datasource.cache.CacheDataSource
                        .FLAG_IGNORE_CACHE_ON_ERROR,
                )
        }

        private fun assertMainThread() {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "ScreenPlayerPool 必须在主线程访问（ExoPlayer 的线程亲和性）"
            }
        }
    }
}
