package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.surface.ReappearPolicy
import ai.lightspeed.tipsy.shell.surface.SurfaceContract
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.facebook.react.ReactFragment
import expo.modules.tipsyauth.TipsyAuthRegistry

/**
 * RN Surface 的宿主 Fragment。
 *
 * 方案 ADR-002/003：继承官方 [ReactFragment]，它已适配 bridgeless
 * （内部按 `enableBridgelessArchitecture()` 选 ReactDelegate 构造），
 * 且默认经 `activity.application as ReactApplication` 取共享的 ReactHost ——
 * 所以这里不新建 Runtime，只是在共享 Runtime 上挂一个 Surface。
 *
 * ## W1 §12 的四件事（本类实现）
 *
 * 1. **`surfaceInstanceId`**（§12.1）—— 每次打开生成唯一 id
 * 2. **首帧协议**（§12.2）—— 不透明 Native 宿主垫底，透明 RN Root 首帧直接覆盖
 * 3. **`onSurfaceReappeared`**（§12.3）—— 非首次 onResume 时发射
 * 4. **capability handshake / initial props**（§12.4）—— 见 [SurfaceContract]
 */
class RNSurfaceFragment : ReactFragment() {

    /** 本实例的唯一 id。**每次打开都不同**，见 [SurfaceContract.newInstanceId]。 */
    val surfaceInstanceId: String
        get() = arguments?.getString(ARG_INSTANCE_ID).orEmpty()

    private val componentName: String
        get() = arguments?.getString(ARG_COMPONENT_NAME).orEmpty()

    /**
     * 是否已经历过一次 `onResume`。存进 saved state 以跨**进程重建**保留。
     *
     * ⚠️ 注意**旋转不走这条路**：`MainActivity` 的 `configChanges` 已含
     * `orientation|screenSize`（manifest:52），所以转屏不重建 Activity/Fragment，
     * 这个字段原样留在内存里。
     *
     * 真正需要 saved state 的是**进程重建**：App 在后台被系统杀掉、用户从
     * 最近任务返回时，Fragment 会带着 saved state 重建。此时若标记丢了，
     * 重建后的首次 onResume 会被当成「重新出现」而误发事件 ——
     * 表现是「切后台一会儿再回来，页面莫名多拉一次数据」。
     */
    private var hasResumedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 判定规则抽在 ReappearPolicy 里（可单测）；这里只负责喂生命周期状态
        hasResumedOnce = ReappearPolicy.restoreHasResumed(
            if (savedInstanceState?.containsKey(STATE_HAS_RESUMED_ONCE) == true) {
                savedInstanceState.getBoolean(STATE_HAS_RESUMED_ONCE)
            } else {
                null
            },
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_HAS_RESUMED_ONCE, hasResumedOnce)
    }

    /**
     * 对齐 iOS `RNSurfaceHost` / `RNSurfaceContainer` 的普通全屏 Surface：
     *
     * - Native 宿主先画不透明 App 底色；
     * - RN Root 保持透明，首帧内容提交后直接覆盖宿主；
     * - 不增加 cover，也不根据布局/固定延时猜测 RN ready。
     *
     * Android 的 `surface_container` 与 Native Tab 是 sibling。宿主若透明，warm
     * Runtime 下新 Surface 首帧尚未提交时就会透出下面的 Tab，看起来像先 pop
     * 回 Native。这个层级约束与共享的 RN Surface 代码无关，只属于原生宿主。
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val reactView = super.onCreateView(inflater, container, savedInstanceState) ?: return null
        reactView.setBackgroundColor(Color.TRANSPARENT)

        val wrapper = FrameLayout(requireContext()).apply {
            setBackgroundResource(R.color.app_background)
            addView(
                reactView,
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        return wrapper
    }

    /**
     * 非首次 `onResume` 时发 `onSurfaceReappeared`（§12.3）。
     *
     * **为什么需要**：壳内经桥跳出（在当前 Surface 上盖新容器）再返回时，
     * RN 的 NavigationContainer 全程保持 focused —— `useFocusEffect` 不重触发、
     * SWR 不 revalidate。症状是「去做任务、回来领取」类页面不刷新
     * （写完评论回来按钮仍显示 Comment 而不是 Claim）。
     *
     * ⚠️ **payload 是组件名，不是 instanceId**（§12.3 明写，且 RN 侧
     * `useShellSurfaceRefocus.ts:39` 比对的是 `payload.surface !== surface`）——
     * 该事件的去重粒度是 Surface **类型**。传 instanceId 会让 hook 永不匹配，
     * 表现为「事件发了但页面不刷新」。
     */
    override fun onResume() {
        super.onResume()
        val shouldEmit = ReappearPolicy.shouldEmit(hasResumedOnce)
        hasResumedOnce = true
        if (!shouldEmit) return

        if (componentName.isEmpty()) {
            Log.w(TAG, "缺少 componentName，跳过 reappeared 事件")
            return
        }
        // 留一行日志：事件本身发给 JS，壳侧不打印的话排查「页面没刷新」时
        // 无从判断是没发、还是发了但 hook 没匹配上（后者常见于 payload 传错）
        Log.i(TAG, "发射 onSurfaceReappeared: surface=$componentName")
        TipsyAuthRegistry.notifySurfaceReappeared(componentName)
    }

    companion object {
        private const val TAG = "RNSurfaceFragment"

        private const val ARG_INSTANCE_ID = "tipsy.surfaceInstanceId"
        private const val ARG_COMPONENT_NAME = "tipsy.componentName"
        private const val STATE_HAS_RESUMED_ONCE = "tipsy.hasResumedOnce"

        /**
         * @param routeParams 业务参数，**平铺**进 initial props 的顶层 ——
         *   13 个 Surface 一律读平铺 props，塞进 `route` 子 Bundle 它们读不到
         *   （详见 [SurfaceContract] 类注释）。
         *   值类型为 `Any`：P9 起有 Int/Boolean/嵌套 Map（`preload`），
         *   全塞成字符串会让 RN 的严格相等判定失效（见 `putRouteParams`）。
         * @param languageCode 壳的语言意见；null = 无意见。
         */
        fun newInstance(
            componentName: String,
            routeParams: Map<String, Any> = emptyMap(),
            languageCode: String? = null,
            environment: String = if (BuildConfig.DEBUG) "development" else "production",
            distribution: String = BuildConfig.DOWNLOAD_CHANNEL,
        ): RNSurfaceFragment {
            val instanceId = SurfaceContract.newInstanceId()
            val initialProps = SurfaceContract.buildInitialProps(
                instanceId = instanceId,
                componentName = componentName,
                languageCode = languageCode,
                environment = environment,
                distribution = distribution,
                routeParams = routeParams,
            )

            // ReactFragment 经 Builder 设置组件名与 launchOptions
            val built = Builder()
                .setComponentName(componentName)
                .setLaunchOptions(initialProps)
                .build()

            return RNSurfaceFragment().apply {
                // 把 id 与组件名也存进 arguments —— onResume / popSurface 要用，
                // 而 launchOptions 那份是给 JS 的，Kotlin 侧读它要依赖 RN 内部 key
                arguments = built.arguments?.apply {
                    putString(ARG_INSTANCE_ID, instanceId)
                    putString(ARG_COMPONENT_NAME, componentName)
                }
            }
        }
    }
}
