package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.TipsyApplication
import ai.lightspeed.tipsy.shell.auth.AuthStateHub
import ai.lightspeed.tipsy.shell.i18n.L10n
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
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
 * 他人主页的宿主（W3，进度文档 §2.32）。
 *
 * ## 这是第一个被真实打通的卡片出口
 *
 * Search 的创作者点击一直在请求 `AppRoute.UserProfile`，但白名单里只有
 * `Search`，所以此前恒被 `rejectNotEnabled` 拒绝。本刀把 `UserProfile`
 * 加进 `ProductionRoutePolicy` 并在 `ShellNavigator` 加分支 —— **两处必须同时改**
 * （只加白名单会走到 `error()`，见 `AppRouter` 注释）。
 *
 * ⚠️ 放开它的理由与 Search 同款（§2.31）：这是**纯原生 Fragment，不开 Surface**，
 * §9.1 矩阵管的是 RN Surface 的桥/生命周期风险，对它不适用。
 * **不能据此推论「原生页都能加」** —— 每个目标都要有自己的单测与冒烟。
 *
 * ## ⚠️ 游客会看到登录页，这是对等行为
 *
 * `AppRoute.UserProfile.requiresAuth = false`（游客可浏览），但头部接口
 * `/user/get/public` 走 `axiosAuth`（[PublicProfileApi] 类注释详述），
 * 无 token 时 RN 侧直接 `requestLogin`。壳按 REQUIRED 接线即对等。
 */
class PublicProfileFragment : Fragment() {

    private val viewModel: PublicProfileViewModel by viewModels {
        val app = requireActivity().application as TipsyApplication
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PublicProfileViewModel(
                api = PublicProfileApi(app.apiClient),
                languageProvider = { L10n.current },
                generations = app.generations,
            ) as T
        }
    }

    /**
     * 登录态订阅。**必须在 onDestroyView 反注册** —— hub 是进程级的
     * （同 `SearchFragment` / `HomeFragment`）。
     */
    private val authObserver = object : AuthStateHub.Observer {
        override fun onDidLogin(userId: String?) {
            viewModel.onAuthChanged(loggedIn = true)
        }

        override fun onDidLogout() {
            // ⚠️ 与自己主页不同：他人主页登出**不清页面**，只清账号私有的关注态
            // （列表走 OPPORTUNISTIC，游客也能看）。理由见 ViewModel.onAuthChanged
            viewModel.onAuthChanged(loggedIn = false)
        }
    }

    private var hasReportedFirstExposure = false

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

        // 语言 settle 后重拉（v2 请求体带 language_code）。drop(1) 跳过当前值 ——
        // 不跳会在每次进入页面时白拉一次（同 HomeFragment / ProfileFragment）
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                L10n.languageFlow.drop(1).collect { viewModel.onLanguageSettled() }
            }
        }

        composeView.setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsState()
                PublicProfileScreen(
                    state = state,
                    onBackClick = ::popSelf,
                    onFollowClick = viewModel::onFollowClick,
                    onRefresh = viewModel::onRefresh,
                    // 与其它页同款：用 Compose 的 inset 而非 ViewCompat listener
                    // （后者首帧之后才回调，那之前是 0，顶栏会画到状态栏底下）
                    statusBarPadding = WindowInsets.statusBars
                        .asPaddingValues()
                        .calculateTopPadding(),
                )
            }
        }

        return composeView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // bind 幂等：同一 userId 已有资料时不重拉（进程重建后会再走一次）
        viewModel.bind(requireArguments().getString(ARG_USER_ID).orEmpty())
    }

    override fun onStart() {
        super.onStart()
        if (!hasReportedFirstExposure) {
            hasReportedFirstExposure = true
            viewModel.onFirstExposure()
        }
    }

    override fun onDestroyView() {
        (requireActivity().application as TipsyApplication)
            .authStateHub.removeObserver(authObserver)
        super.onDestroyView()
    }

    /** 关掉自己（顶栏返回箭头）。与系统返回键同一条路径，同 `SearchFragment`。 */
    private fun popSelf() {
        parentFragmentManager.popBackStack()
    }

    companion object {
        private const val ARG_USER_ID = "user_id"

        /**
         * @param userId 目标用户 id。**必须非空** —— 空 id 会让页面查一个
         *   不存在的用户；调用方（Router 分支）已保证非空
         */
        fun newInstance(userId: String): PublicProfileFragment =
            PublicProfileFragment().apply {
                arguments = Bundle().apply { putString(ARG_USER_ID, userId) }
            }
    }
}
