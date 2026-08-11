package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.login.LoginFragment
import androidx.fragment.app.commit
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **登录页 inset 到达性验证** —— 防的是一个**静默**失效。
 *
 * ## 为什么需要这个测试
 *
 * 登录页的上下避让来自 `LoginFragment` 读取的 window inset。首版把值挂在
 * `ViewCompat.setOnApplyWindowInsetsListener` 上，而 Fragment 的视图是在
 * Activity 的 inset 派发**之后**才 attach 的 —— 监听器一次都不触发，
 * `insetTop/insetBottom` 恒为 0。
 *
 * 表现是**条款文字压在导航栏下面**，且：
 * - 不崩、不报错、不打任何日志
 * - 单测测不到（[ai.lightspeed.tipsy.shell.LoginLayoutTest] 只验纯函数算术，
 *   而算术一直是对的 —— 错的是喂给它的 inset 是 0）
 * - 只有真机与 RN 版并排看才能发现
 *
 * 所以断言的是「**inset 确实到达了 Compose 层**」这一件事，而不是具体像素。
 *
 * ## 为什么不断言具体 dp 值
 *
 * 状态栏/导航栏高度随设备、系统版本、手势导航开关而变（本机 24/48dp，
 * 手势导航下底部可能是 0）。钉死数值会让测试变成设备绑定。这里只要求
 * **至少有一次非零派发到达** —— 恰好能区分「机制通」与「恒为 0」。
 *
 * 底部允许为 0（全屏手势导航），故只对 top 断言非零：状态栏在任何
 * 已知配置下都存在。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class LoginInsetTest {

    @Test
    fun 登录页挂载后inset必须到达而不是恒为零() {
        val renders = AtomicInteger(0)
        var observedTop = -1f

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val fragment = LoginFragment.newInstance()
                activity.supportFragmentManager.commit {
                    replace(R.id.surface_container, fragment, "login-inset-test")
                }
                activity.supportFragmentManager.executePendingTransactions()
            }

            // inset 的兜底读取挂在 doOnAttach 上，attach 与首帧之间有一拍。
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline && observedTop <= 0f) {
                scenario.onActivity { activity ->
                    val f = activity.supportFragmentManager
                        .findFragmentByTag("login-inset-test") as? LoginFragment
                    val top = f?.lastInsetTopForTest ?: -1f
                    if (top > 0f) {
                        observedTop = top
                        renders.incrementAndGet()
                    }
                }
                if (observedTop <= 0f) Thread.sleep(100)
            }
        }

        assertTrue(
            "inset 从未到达登录页（top=$observedTop）—— 说明只挂了 " +
                "setOnApplyWindowInsetsListener 而漏了 doOnAttach 的兜底读取，" +
                "条款文字会压在导航栏下面",
            observedTop > 0f,
        )
    }
}
