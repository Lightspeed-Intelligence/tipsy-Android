package ai.lightspeed.tipsy.shell.bridge

import java.util.concurrent.CopyOnWriteArrayList

/**
 * RN Surface → 原生常驻页的进程级一次性刷新信号（无 payload 的 fan-out）。
 *
 * ## 为什么是「即时通知」而不是 markStale/onAppear
 *
 * Tab 切换是 `show/hide`（不触发生命周期，工程日志 §2.42 的 hidden 轴结论），
 * Surface 容器是 sibling（关闭时底下的 Fragment 不重走 `onStart`）——
 * 「标脏等下次 appear」在这两种布局下实际等于「等下次冷启动」。
 * iOS 的对应物（NotificationCenter → `silentRefreshFirstPage`）同样是即时刷新。
 *
 * ## 信号丢失是刻意语义
 *
 * 桥是 Application 级、消费方是懒加载 Tab：目标 Fragment 未挂载时无人订阅，
 * 信号直接丢弃 —— **这是对的**：首次挂载本来就会拉全新数据，不需要补投递。
 * 有账号语义的接力（EditProfile → Profile 资料）不用本类，
 * 用带 revision/归属校验的 [ai.lightspeed.tipsy.shell.pages.profile.ProfileRefreshHub]。
 *
 * 订阅方必须在 `onDestroy` 反注册（进程级持有，同 AuthStateHub 的泄漏约束）。
 * [notify] 由桥经 `@MainThread` 通道调入，监听器在主线程执行。
 */
class RefreshSignalHub {

    fun interface Listener {
        fun onSignal()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun notifySignal() {
        listeners.forEach { it.onSignal() }
    }
}
