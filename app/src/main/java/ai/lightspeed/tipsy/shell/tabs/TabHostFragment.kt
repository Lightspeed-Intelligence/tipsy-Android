package ai.lightspeed.tipsy.shell.tabs

import ai.lightspeed.tipsy.shell.R
import ai.lightspeed.tipsy.shell.pages.home.HomeFragment
import ai.lightspeed.tipsy.shell.pages.profile.ProfileFragment
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit

/**
 * 五 Tab 容器（W2，对齐 RN `TabNavigator.tsx` 的行为语义）。
 *
 * ## 为什么用 FragmentManager 而不是 Compose 的 NavHost
 *
 * 方案 ADR-002 定的是 Fragment 宿主：RN Surface 必须是 `ReactFragment`
 * （它要 Fragment 生命周期来转发 `onHostResume/Pause`），而 Tab 里迟早要挂
 * Surface（Create 伪 Tab 就落 `CreateSurface`）。两套导航体系混用会让
 * 「Surface 挂在哪个返回栈里」变得难以推理。
 *
 * ## 懒加载 + 不销毁（对齐 `lazy: true` + `detachInactiveScreens={false}`）
 *
 * RN 侧 Android 分支**刻意关掉了 detachInactiveScreens**（`:305`
 * `detachInactiveScreens={Platform.OS !== 'android'}`），配合 `freezeOnBlur`。
 * 也就是：首次访问才挂载，之后切走**不销毁**。
 *
 * 所以这里用 `show`/`hide` 而不是 `replace` —— `replace` 会销毁上一个 Tab 的
 * 视图，回来时列表滚动位置与已加载的分页全部丢失（用户视角：切走再回来回到顶部、
 * 又要重新加载）。
 *
 * ## Create 是伪 Tab
 *
 * 点它不切 Tab，而是拉起创建流程（见 [ShellTab.CREATE] 注释）。
 * 当前该流程落 `CreateSurface`，属 W2 后续包 —— 这里明确记日志而非静默。
 */
class TabHostFragment : Fragment() {

    private var selected by mutableStateOf(ShellTab.default)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = inflater.inflate(R.layout.fragment_tab_host, container, false)
        val tabBarHost = root.findViewById<ComposeView>(R.id.tab_bar)

        tabBarHost.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )

        var safeBottomDp = 0f
        fun renderTabBar() {
            tabBarHost.setContent {
                MaterialTheme {
                    ShellTabBar(
                        selected = selected,
                        onTabClick = ::onTabClick,
                        safeBottomDp = safeBottomDp,
                    )
                }
            }
        }

        // inset 同 LoginFragment/HomeFragment：attach 后读一次兜底 + 监听后续变化
        ViewCompat.setOnApplyWindowInsetsListener(tabBarHost) { _, insets ->
            safeBottomDp = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom /
                resources.displayMetrics.density
            renderTabBar()
            insets
        }
        tabBarHost.doOnAttach { view ->
            ViewCompat.getRootWindowInsets(view)?.let {
                safeBottomDp = it.getInsets(WindowInsetsCompat.Type.systemBars()).bottom /
                    resources.displayMetrics.density
                renderTabBar()
            }
        }
        renderTabBar()

        if (savedInstanceState == null) {
            showTab(ShellTab.default)
        }
        return root
    }

    private fun onTabClick(tab: ShellTab) {
        if (!tab.isRealTab) {
            // Create 伪 Tab：拉起创建流程，**不切 Tab**
            Log.w(TAG, "Create 入口点击：CreateSurface 未接入（W2 后续包）")
            return
        }
        if (tab == selected) {
            // 重复点当前 Tab：RN 侧靠 tabPress 事件让页面回到顶部。
            // 壳内该行为属各页自己的事（Home 的滚到顶要 LazyGridState），
            // 当前不做 —— 记下来避免以为忘了
            return
        }
        selected = tab
        showTab(tab)
    }

    /**
     * 切 Tab：**show/hide 而不是 replace**（见类注释）。
     *
     * 首次访问才 add（懒加载），已存在的 Fragment 只是 hide/show ——
     * 这样列表状态与已加载分页跨 Tab 切换存活。
     */
    private fun showTab(tab: ShellTab) {
        val fm = childFragmentManager
        fm.commit {
            // 隐藏其余已挂载的 Tab
            ShellTab.displayOrder.filter { it.isRealTab && it != tab }.forEach { other ->
                fm.findFragmentByTag(other.routeName)?.let { hide(it) }
            }
            val existing = fm.findFragmentByTag(tab.routeName)
            if (existing == null) {
                add(R.id.tab_content, createFragment(tab), tab.routeName)
            } else {
                show(existing)
            }
        }
    }

    private fun createFragment(tab: ShellTab): Fragment = when (tab) {
        ShellTab.HOME -> HomeFragment.newInstance()
        // Profile 是 W3 第一刀：资料头部 + 统计 + 创作/记忆两个内容 tab
        //（其余 3 个内容 tab、钱包区、编辑动作属后续包，见 ProfileViewModel 类注释）
        ShellTab.PROFILE -> ProfileFragment.newInstance()
        // 其余 Tab 的原生页属后续波次（Screen → W4，ChatList → W3）。
        // 用带明确文案的占位而不是空白 —— 空白会让人以为挂载失败
        ShellTab.SCREEN -> TabPlaceholderFragment.newInstance(tab.routeName, "W4")
        ShellTab.CHAT_LIST -> TabPlaceholderFragment.newInstance(tab.routeName, "W3")
        // isRealTab 已在 onTabClick 拦下，走到这里说明有人改了那个判断
        ShellTab.CREATE -> error("Create 是伪 Tab，不应创建 Fragment")
    }

    companion object {
        private const val TAG = "TabHostFragment"

        fun newInstance(): TabHostFragment = TabHostFragment()
    }
}
