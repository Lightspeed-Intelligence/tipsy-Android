package ai.lightspeed.tipsy.shell.auth

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 壳内登录态订阅（W1 计划 §3.4）。
 *
 * ## 为什么 W1 就要建
 *
 * iOS 的 `MainTabBarController` 缓存 Tab VC、只在首次加载拉一次且永不销毁 ——
 * 登录/登出只广播给 RN 桥，于是出现两个真实 bug：
 * **登录后无人重拉**、**登出后串上一个账号的数据**。
 *
 * Android 的 Fragment 同样常驻。W1 还没有五 Tab，但机制现在建好，
 * W2 加 Tab 时直接订阅 —— 而不是等 bug 出现再回头补。
 *
 * ## 约定（两条，必须遵守）
 *
 * - [Observer.onDidLogin] → 重拉身份相关数据
 * - [Observer.onDidLogout] → **只清账号私有数据，不发请求**
 *
 * 第二条是硬约束：登出后 authorized 请求必然被前置拒绝，发了只会产生
 * 无意义的 401，还可能触发 [SurfaceAuthContract] 的 auth-reject 兜底形成自触发环。
 */
class AuthStateHub {

    interface Observer {
        /** 登录成功 / 换号完成。[userId] 可空（token 无 `sub` 时）。 */
        fun onDidLogin(userId: String?)

        /** 登出 / token 被清。**不要在这里发请求。** */
        fun onDidLogout()
    }

    private val observers = CopyOnWriteArrayList<Observer>()

    /** 常驻页（Fragment / ViewModel）在创建时注册，销毁时反注册。 */
    fun addObserver(observer: Observer) {
        observers.add(observer)
    }

    fun removeObserver(observer: Observer) {
        observers.remove(observer)
    }

    /**
     * 发一次登录事件。
     *
     * ⚠️ 调用方负责**恰好发一次** —— 重复发会让每个常驻页重复拉数据。
     * 这里不做去重，因为"同一用户再次登录"（换号回来）是合法的重复事件。
     */
    fun notifyDidLogin(userId: String?) {
        observers.forEach { it.onDidLogin(userId) }
    }

    /** 发一次登出事件。对应 W1 计划 §3.5「发**一次** loggedOut」。 */
    fun notifyDidLogout() {
        observers.forEach { it.onDidLogout() }
    }

    /** 仅测试用。 */
    internal fun observerCount(): Int = observers.size
}
