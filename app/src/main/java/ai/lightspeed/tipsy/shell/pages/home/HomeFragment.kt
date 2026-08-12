package ai.lightspeed.tipsy.shell.pages.home

import ai.lightspeed.tipsy.shell.TipsyApplication
import ai.lightspeed.tipsy.shell.analytics.Analytics
import ai.lightspeed.tipsy.shell.auth.AuthStateHub
import ai.lightspeed.tipsy.shell.i18n.L10n
import ai.lightspeed.tipsy.shell.router.AppRoute
import ai.lightspeed.tipsy.shell.router.AppRouter
import ai.lightspeed.tipsy.shell.tabs.HOME_LIST_BOTTOM_EXTRA
import ai.lightspeed.tipsy.shell.tabs.TAB_BAR_CONTENT_HEIGHT
import ai.lightspeed.tipsy.shell.tabs.androidTabBarBottomInsetDp
import ai.lightspeed.tipsy.shell.ui.ScaledMetrics
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Home Tab 的宿主（方案 ADR-002：Fragment + ComposeView）。
 *
 * ## 常驻 Fragment 必须订阅登录态（W1 计划 §3.4）
 *
 * iOS 的 Tab VC 缓存后只在首次加载拉一次且永不销毁，于是出现两个真实 bug：
 * **登录后无人重拉**、**登出后串上一个账号的数据**。Android 的 Fragment
 * 同样常驻，所以这里必须订阅 [AuthStateHub] —— 这正是 W1 建它的目的。
 *
 * ## 语言变化也要重拉
 *
 * 方案 §8.4 第 2 条：账号语言 ≠ 设备语言时，冷启动数秒后才 settle，
 * 触发换 session 强拉。这个时间窗与"晚到的 banner"同窗，iOS 曾误判成
 * banner 引发查了两轮。这里显式订阅 `L10n.languageFlow`。
 */
class HomeFragment : Fragment() {

    private val viewModel: HomeViewModel by viewModels {
        val app = requireActivity().application as TipsyApplication
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(
                api = HomeApi(app.apiClient),
                filters = HomeFilterStore(app.sharedMmkvStore),
                languageProvider = { L10n.current },
            ) as T
        }
    }

    /**
     * 登录态订阅。**必须在 onDestroy 反注册** —— hub 是进程级的，
     * 不解绑会让已销毁的 Fragment 收到事件并往死掉的 ViewModel 派发。
     */
    private val authObserver = object : AuthStateHub.Observer {
        override fun onDidLogin(userId: String?) {
            // 登录后 uid 才可用：绑定 Analytics 并冲出排队的曝光事件
            Analytics.bindUserId(userId)
            viewModel.onAuthChanged()
        }

        override fun onDidLogout() {
            Analytics.unbindUserId()
            // ⚠️ 这里只清+重拉游客态列表，**不发 authorized 请求**
            // （AuthStateHub 的硬约束）。Home 的三个接口都是 OPPORTUNISTIC，
            // 无 token 时照发且返回游客内容，符合该约束
            viewModel.onAuthChanged()
        }
    }

    private var hasReportedFirstAppear = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val app = requireActivity().application as TipsyApplication
        app.authStateHub.addObserver(authObserver)

        val composeView = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }

        // 语言 settle 后重拉（见类注释）。drop(1) 跳过当前值 ——
        // 不跳会在每次进入页面时白拉一次
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                L10n.languageFlow.drop(1).collect { viewModel.onLanguageSettled() }
            }
        }

        var safeBottomDp = 0f
        var safeTopDp = 0f

        fun render() {
            composeView.setContent {
                MaterialTheme {
                    val state by viewModel.state.collectAsState()
                    HomeScreen(
                        state = state,
                        onSeriesSelected = viewModel::onSeriesSelected,
                        onGenderSelected = viewModel::onGenderSelected,
                        onRefresh = viewModel::onRefresh,
                        onLoadMore = viewModel::onLoadMore,
                        onItemClick = ::onItemClick,
                        onItemExposed = viewModel::onItemExposed,
                        onSearchClick = ::onSearchClick,
                        onSubscriptionClick = ::onSubscriptionClick,
                        onFilterClick = ::onFilterClick,
                        listBottomPadding = listBottomPadding(safeBottomDp),
                        // 系统 inset 直接给 dp，不乘 scaleFactor
                        statusBarPadding = safeTopDp.dp,
                    )
                }
            }
        }

        // inset 读取与 LoginFragment 同构：attach 后先 rootWindowInsets 读一次
        // （监听器装上时那次派发已过去，实测一次都不触发 —— 见 LoginFragment 注释），
        // 监听器只负责后续变化
        ViewCompat.setOnApplyWindowInsetsListener(composeView) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            safeBottomDp = bars.bottom / resources.displayMetrics.density
            safeTopDp = bars.top / resources.displayMetrics.density
            render()
            insets
        }
        composeView.doOnAttach { view ->
            ViewCompat.getRootWindowInsets(view)?.let { insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                safeBottomDp = bars.bottom / resources.displayMetrics.density
                safeTopDp = bars.top / resources.displayMetrics.density
                render()
            }
        }

        render()
        return composeView
    }

    override fun onResume() {
        super.onResume()
        if (!hasReportedFirstAppear) {
            hasReportedFirstAppear = true
            viewModel.onFirstAppear()
        } else {
            viewModel.onReappear()
        }
        // nsfw 是后端 user.nsfw 的本地镜像，可能被 RN Surface（设置页）改过。
        // 桥没有 JS→壳 的通知（同语言那条），所以回前台时重读一次
        val app = requireActivity().application as TipsyApplication
        viewModel.onNsfwChanged(HomeFilterStore(app.sharedMmkvStore).readNsfw())
    }

    override fun onDestroyView() {
        (requireActivity().application as TipsyApplication)
            .authStateHub.removeObserver(authObserver)
        super.onDestroyView()
    }

    /**
     * 列表底部留白。
     *
     * `insets.bottom + 50`（`home.tsx:257` Android 分支）**再加 tabbar 高度** ——
     * RN 侧 tabbar 是 `Tab.Navigator` 的一部分，列表容器本来就不含它；
     * 壳的 tabbar 与内容是叠放（内容 fillMaxSize），所以要自己让出这段。
     * 漏了的表现是「最后一行卡片被 tabbar 压住一半」。
     */
    private fun listBottomPadding(safeBottomDp: Float): Dp {
        val scale = ScaledMetrics.scaleFactorFor(screenWidthDp())
        // 两个设计稿常量乘 scale，系统 inset 不乘（见 androidTabBarBottomInsetDp 注释）
        val scaledExtras = (HOME_LIST_BOTTOM_EXTRA + TAB_BAR_CONTENT_HEIGHT) * scale
        return (scaledExtras + androidTabBarBottomInsetDp(safeBottomDp, scale)).dp
    }

    private fun screenWidthDp(): Float =
        resources.displayMetrics.widthPixels / resources.displayMetrics.density

    private fun onItemClick(item: HomeFeedItem) {
        viewModel.onItemClicked(item)
        val app = requireActivity().application as TipsyApplication
        when (item) {
            // 进聊天：ChatDetail 在 P9 / §9.1 矩阵前不在生产白名单里，
            // Router 会走 rejectNotEnabled 记录明确拒绝（方案 §8.3：不做 silent no-op）
            is HomeFeedItem.Character -> app.requestRoute(
                AppRoute.ChatDetail(item.characterId),
                AppRouter.Source.IN_APP,
            )
            // 故事卡也进聊天（RN 的 toChatPage 对 story 型走同一入口）
            is HomeFeedItem.Story -> app.requestRoute(
                AppRoute.ChatDetail(item.storyId),
                AppRouter.Source.IN_APP,
            )
            // World 落 SimulatorGame WebView —— **不迁**（方案 §8.1）。
            // 该目标尚无壳内实现，明确记日志而不是静默
            is HomeFeedItem.World -> Log.w(
                TAG,
                "World 卡片点击：SimulatorGame WebView 未接入（方案 §8.1 不迁，W4 处理）",
            )
        }
    }

    private fun onSearchClick() {
        // 埋点照 RN 发（`HomeHeader.tsx:63`），目标页属 W3
        Analytics.track(
            "search_click_search_box",
            mapOf("platform" to "app"),
        )
        Log.w(TAG, "搜索入口点击：Search 页属 W3，尚未实现")
    }

    private fun onSubscriptionClick() {
        val app = requireActivity().application as TipsyApplication
        Analytics.track(
            "payment_hub_click",
            // is_new（注册 24h 内）需要 user.created_at，壳内 user 信息属下一包；
            // landing_page 需要订阅状态。两者都缺时**不发假值** —— 发错值比不发更难查
            mapOf("landing_page" to "subscribe"),
        )
        app.requestRoute(AppRoute.GemsPurchase(), AppRouter.Source.IN_APP)
    }

    private fun onFilterClick() {
        Log.w(TAG, "标签筛选抽屉未实现（HomeFilterDrawer 382 行，下一包）")
    }

    companion object {
        private const val TAG = "HomeFragment"
        fun newInstance(): HomeFragment = HomeFragment()
    }
}
