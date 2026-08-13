package ai.lightspeed.tipsy.shell.pages.search

import ai.lightspeed.tipsy.shell.TipsyApplication
import ai.lightspeed.tipsy.shell.auth.AuthStateHub
import ai.lightspeed.tipsy.shell.i18n.L10n
import ai.lightspeed.tipsy.shell.pages.home.HomeFeedItem
import ai.lightspeed.tipsy.shell.pages.home.HomeFilterStore
import ai.lightspeed.tipsy.shell.router.AppRoute
import ai.lightspeed.tipsy.shell.router.AppRouter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * 搜索页宿主（W3，`app/search/page.tsx` 的原生对应）。
 *
 * ## 壳里第一个「原生全屏页」
 *
 * 此前的原生页都是 Tab 根（Home/ChatList/Profile，挂在 TabsFragment 里）或
 * 登录页（走 `Navigator.requestLogin` 专用口）。搜索页是第一个经
 * **正常 Router 路径**打开的原生全屏页 —— 因此 `AppRoute.Search` 是
 * `ProductionRoutePolicy` 白名单里的第一项（那里有为什么它不受 §9.1
 * Surface 矩阵约束的说明）。
 *
 * ## 返回：交给系统栈，不自己拦
 *
 * `MainActivity.openSearch` 用 `addToBackStack`，返回键由 FragmentManager
 * 处理。**不注册 OnBackPressedCallback** —— 搜索页没有「先收起某层再退出」
 * 的需求（RN 侧 SearchBar 的返回箭头就是 `navigation.goBack()`）。
 * 顶栏返回箭头调 [popSelf]，与系统返回同一条路径。
 */
class SearchFragment : Fragment() {

    private val viewModel: SearchViewModel by viewModels {
        val app = requireActivity().application as TipsyApplication
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SearchViewModel(
                api = SearchApi(app.apiClient),
                generations = app.generations,
                languageProvider = { L10n.current },
                // 全局 nsfw 开关：复用 Home 的**唯一读取口**（`config-persist-storage`
                // 信封里的 `state.nsfw` 镜像，只读不写）。读不到时回落 false ——
                // 按最保守值走，不能默认 true
                nsfwProvider = { HomeFilterStore(app.sharedMmkvStore).readNsfw() },
                userIdProvider = { app.tokenStore.currentUserId() },
            ) as T
        }
    }

    /** 登录重拉；登出只清私有数据，不发 REQUIRED 请求（AuthStateHub 硬约束）。 */
    private val authObserver = object : AuthStateHub.Observer {
        override fun onDidLogin(userId: String?) {
            viewModel.onAuthChanged(loggedIn = true)
        }

        override fun onDidLogout() {
            viewModel.onAuthChanged(loggedIn = false)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        (requireActivity().application as TipsyApplication)
            .authStateHub.addObserver(authObserver)

        val composeView = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }

        composeView.setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsState()
                val context = LocalContext.current

                LaunchedEffect(state.toastKey) {
                    state.toastKey?.let { key ->
                        Toast.makeText(context, L10n.t(key), Toast.LENGTH_SHORT).show()
                        viewModel.consumeToast()
                    }
                }

                SearchScreen(
                    state = state,
                    statusBarPadding = WindowInsets.statusBars
                        .asPaddingValues()
                        .calculateTopPadding(),
                    // 搜索页是全屏页，没有 Tab 栏 —— 底部留白只需系统 inset
                    listBottomPadding = WindowInsets.systemBars
                        .asPaddingValues()
                        .calculateBottomPadding(),
                    onQueryChange = viewModel::onQueryChange,
                    onSubmit = viewModel::submitCurrentQuery,
                    onClearQuery = viewModel::clearQuery,
                    onBackClick = ::popSelf,
                    onTabChange = viewModel::onTabChange,
                    onSuggestionClick = { viewModel.submitQuery(it, SearchWay.SEARCH) },
                    onRecentClick = { viewModel.submitQuery(it, SearchWay.RECENT_SEARCH) },
                    onPopularClick = { viewModel.submitQuery(it, SearchWay.POPULAR_SEARCH) },
                    onClearHistoryRequest = viewModel::onClearHistoryRequest,
                    onClearHistoryConfirm = viewModel::onClearHistoryConfirm,
                    onClearHistoryDismiss = viewModel::onClearHistoryDismiss,
                    onLoadMore = viewModel::loadMore,
                    onCharacterClick = ::onCharacterClick,
                    onCharacterExposed = viewModel::onCharacterExposed,
                    onCreatorClick = ::onCreatorClick,
                    onCreatorExposed = viewModel::onCreatorExposed,
                    onCreateCharacterClick = {
                        requestRoute(AppRoute.CreateProfileDetail())
                    },
                )
            }
        }

        return composeView
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppear()
    }

    override fun onDestroyView() {
        (requireActivity().application as TipsyApplication)
            .authStateHub.removeObserver(authObserver)
        super.onDestroyView()
    }

    /**
     * 点角色卡 → 聊天页。
     *
     * `ChatDetail` 在 P9 前**不在白名单**，Router 会明确拒绝并记日志
     * （不是 silent no-op）—— 与 Home/ChatList 点卡片同样的现状。
     */
    private fun onCharacterClick(item: HomeFeedItem.Character, itemPosition: Int) {
        viewModel.onCharacterClick(item, itemPosition)
        requestRoute(AppRoute.ChatDetail(characterId = item.characterId))
    }

    private fun onCreatorClick(creator: CreatorResult, itemPosition: Int) {
        viewModel.onCreatorClick(creator, itemPosition)
        requestRoute(AppRoute.UserProfile(userId = creator.userId))
    }

    private fun requestRoute(route: AppRoute) {
        val app = requireActivity().application as TipsyApplication
        app.requestRoute(route, AppRouter.Source.IN_APP)
    }

    /** 关掉自己（顶栏返回箭头）。与系统返回键同一条路径。 */
    private fun popSelf() {
        parentFragmentManager.popBackStack()
    }

    companion object {
        fun newInstance(): SearchFragment = SearchFragment()
    }
}
