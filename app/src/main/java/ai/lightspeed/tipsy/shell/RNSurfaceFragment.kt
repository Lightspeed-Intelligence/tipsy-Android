package ai.lightspeed.tipsy.shell

import android.os.Bundle
import com.facebook.react.ReactFragment

/**
 * RN Surface 的宿主 Fragment。
 *
 * 方案 ADR-002/003：继承官方 [ReactFragment]，它已适配 bridgeless
 * （内部按 `enableBridgelessArchitecture()` 选 ReactDelegate 构造），
 * 且默认经 `activity.application as ReactApplication` 取共享的 ReactHost ——
 * 所以这里不新建 Runtime，只是在共享 Runtime 上挂一个 Surface。
 *
 * **W0 之后必须补的（方案 §4.3，现在刻意留空）**：
 * - `surfaceInstanceId`：每次打开生成唯一 id，ready/close/reappear 事件都带上。
 *   iOS 的 popSurface 闸是**类型判定**，迟到事件会弹错同类型页 —— Android
 *   从一开始就按实例判定，别重复那个 bug。
 * - 首帧协议：ready 前显示原生占位、ready 后单次淡出，**不用固定延时猜**。
 * - `onSurfaceReappeared`：容器非首次 onResume 时发，RN 侧已有
 *   `useShellSurfaceRefocus` 消费（payload 是 `{surface: 组件名}`）。
 * - capability handshake / initial props（**token 绝不经 props 透传**）。
 */
class RNSurfaceFragment : ReactFragment() {

    companion object {
        fun newInstance(componentName: String, initialProps: Bundle? = null): RNSurfaceFragment {
            // ReactFragment 经 Builder 设置组件名与 launchOptions
            val builder = Builder().setComponentName(componentName)
            if (initialProps != null) {
                builder.setLaunchOptions(initialProps)
            }
            val built = builder.build()
            return RNSurfaceFragment().apply { arguments = built.arguments }
        }
    }
}
