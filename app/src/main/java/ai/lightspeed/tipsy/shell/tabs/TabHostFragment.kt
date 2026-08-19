package ai.lightspeed.tipsy.shell.tabs

import ai.lightspeed.tipsy.shell.R
import ai.lightspeed.tipsy.shell.TipsyApplication
import ai.lightspeed.tipsy.shell.pages.chatlist.ChatListFragment
import ai.lightspeed.tipsy.shell.pages.home.HomeFragment
import ai.lightspeed.tipsy.shell.pages.profile.ProfileFragment
import ai.lightspeed.tipsy.shell.pages.screen.ScreenFragment
import ai.lightspeed.tipsy.shell.router.AppRoute
import ai.lightspeed.tipsy.shell.router.AppRouter
import android.os.Bundle
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
            // Create 伪 Tab：拉起 CreateSurface，**不切 Tab**（selected 不变）——
            // 对齐 RN `TabNavigator.tsx:422` 的 `e.preventDefault()`：
            // 那边阻止了 tab 切换后才 navigate，所以关掉创建页应回到原来那个 Tab，
            // 而不是停在一个空的 Create tab 上。
            //
            // 经 Router 而不是自己 commit 事务：§4.7 单一入口 ——
            // 创建要求登录，未登录时 Router 会先弹登录页并把本路由排队，
            // 登录后恰好执行一次。自己 commit 会绕过这道 gate。
            requestRoute(AppRoute.Create(AppRoute.CreateEnterSource.TAB_BAR_PLUS))
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

    /**
     * 经 Router 导航（§4.7 单一入口）。与 ProfileFragment.requestRoute 同构 ——
     * 业务页不得自己 commit Fragment 事务，否则 auth gate 与去重会被绕过。
     */
    private fun requestRoute(route: AppRoute) {
        val app = requireActivity().application as TipsyApplication
        app.requestRoute(route, AppRouter.Source.IN_APP)
    }

    private fun createFragment(tab: ShellTab): Fragment = when (tab) {
        ShellTab.HOME -> HomeFragment.newInstance()
        // Profile 是 W3 第一刀：资料头部 + 统计 + 创作/记忆两个内容 tab
        //（其余 3 个内容 tab、钱包区、编辑动作属后续包，见 ProfileViewModel 类注释）
        ShellTab.PROFILE -> ProfileFragment.newInstance()
        // ChatList W3 P1：Grid 视图 + 操作全链路；Map「時光長廊」是 P2
        ShellTab.CHAT_LIST -> ChatListFragment.newInstance()
        // Screen W4-P1/P2：AB 分流 + 竖向翻页 + 归因/会话埋点，showcase 在
        // ±1 窗口内经 Media3 有界池播放；池不可用时仍降级显示封面。
        ShellTab.SCREEN -> ScreenFragment.newInstance()
        // isRealTab 已在 onTabClick 拦下，走到这里说明有人改了那个判断
        ShellTab.CREATE -> error("Create 是伪 Tab，不应创建 Fragment")
    }

    companion object {
        fun newInstance(): TabHostFragment = TabHostFragment()
    }
}
