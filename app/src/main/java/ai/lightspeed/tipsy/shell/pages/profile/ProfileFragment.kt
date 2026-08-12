package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.TipsyApplication
import ai.lightspeed.tipsy.shell.auth.AuthStateHub
import ai.lightspeed.tipsy.shell.i18n.L10n
import ai.lightspeed.tipsy.shell.router.AppRoute
import ai.lightspeed.tipsy.shell.router.AppRouter
import ai.lightspeed.tipsy.shell.user.CurrentUserStore
import ai.lightspeed.tipsy.shell.user.UserInfoApi
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
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
 * Profile Tab 的宿主（W3 第一刀，替掉 `TabPlaceholderFragment`）。
 *
 * ## 出口全部经 Router，且现在**都会被明确拒绝**
 *
 * `ProductionRoutePolicy.enabledRouteTypes` 目前是 `emptySet()`（P9 / §9.1 前
 * 没有业务路由可进生产），所以设置 / 编辑资料 / Follow 列表点下去会走
 * `navigator.rejectNotEnabled` 给出明确错误。
 *
 * 这是 §8.3 要求的形态：「路由到未启用的 Surface 必须给出明确错误或安全 fallback，
 * **不做 silent no-op**」。不是漏实现 —— 留 TODO 让点击无反应才是违反纪律的那种
 * （§2.23 那次 stub 抽屉就因为"点了没反应"造成过真机误判）。
 *
 * 走 `app.requestRoute` 而不是自己 commit 事务：方案 §4.7 单一导航入口，
 * 业务页自己 commit 会绕过 auth gate 与去重。
 */
class ProfileFragment : Fragment() {

    private val viewModel: ProfileViewModel by viewModels {
        val app = requireActivity().application as TipsyApplication
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ProfileViewModel(
                api = ProfileApi(app.apiClient),
                userStore = CurrentUserStore(UserInfoApi(app.apiClient)),
                languageProvider = { L10n.current },
            ) as T
        }
    }

    /**
     * 登录态订阅。**必须在 onDestroy 反注册** —— hub 是进程级的，
     * 不解绑会让已销毁的 Fragment 往死掉的 ViewModel 派发（同 `HomeFragment`）。
     */
    private val authObserver = object : AuthStateHub.Observer {
        override fun onDidLogin(userId: String?) {
            viewModel.onAuthChanged(loggedIn = true)
        }

        override fun onDidLogout() {
            // ⚠️ 传 false：Profile 两个接口都是 REQUIRED，登出后发必然被前置拒绝
            // （AuthStateHub 的硬约束）。这里只清不拉 —— 与 Home 的
            // 「登出后照发 OPPORTUNISTIC 拿游客内容」不同
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

        // 语言 settle 后全 tab 复位重拉（创作列表带 language_code，其余 tab 的
        // RN SWR key 同样含语言）。drop(1) 跳过当前值 —— 不跳会在每次进入页面时
        // 白拉一次（同 HomeFragment）
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                L10n.languageFlow.drop(1).collect { viewModel.onLanguageSettled() }
            }
        }

        composeView.setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsState()
                ProfileScreen(
                    state = state,
                    onRefresh = viewModel::onRefresh,
                    onLoadMore = viewModel::onLoadMore,
                    onTabSelected = viewModel::onTabSelected,
                    onEditProfileClick = { requestRoute(AppRoute.EditProfile) },
                    onUidClick = { copyUid(state.user?.userId) },
                    onFollowersClick = { openFollow(state.user?.userId, TYPE_FOLLOWERS) },
                    onFollowingClick = { openFollow(state.user?.userId, TYPE_FOLLOWING) },
                    onSettingsClick = { requestRoute(AppRoute.Settings) },
                    // ⚠️ 用 Compose 的 inset 而不是 ViewCompat listener + 手动 render：
                    // listener 在**首帧之后**才回调，那之前值是 0，顶栏会画到状态栏底下
                    // （真机实测：Settings 与系统图标重叠）。
                    // WindowInsets.statusBars 是 composition 内的响应式读取，首帧就有值
                    statusBarPadding = WindowInsets.statusBars
                        .asPaddingValues()
                        .calculateTopPadding(),
                )
            }
        }

        return composeView
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppear()
        if (!hasReportedFirstExposure) {
            hasReportedFirstExposure = true
            viewModel.onFirstExposure()
        }
    }

    override fun onDestroy() {
        val app = requireActivity().application as TipsyApplication
        app.authStateHub.removeObserver(authObserver)
        super.onDestroy()
    }

    private fun requestRoute(route: AppRoute) {
        val app = requireActivity().application as TipsyApplication
        app.requestRoute(route, AppRouter.Source.IN_APP)
    }

    /**
     * Follow 列表。userId 为空（还没拉到用户信息）时不导航 ——
     * 传空 id 过去会让目标页查一个不存在的用户。
     */
    private fun openFollow(userId: String?, type: String) {
        if (userId.isNullOrBlank()) return
        requestRoute(AppRoute.Follow(userId = userId, type = type))
    }

    /** 复制 UID（对齐 `user-profile.tsx:429` 的 `expo-clipboard`）。 */
    private fun copyUid(userId: String?) {
        if (userId.isNullOrBlank()) return
        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, userId))
        // ⚠️ 不弹 Toast：Android 13+ 系统自带"已复制"提示，再弹一个会重复
        // （RN 侧用 react-native-toast-message 是因为它跨平台统一处理）
    }

    companion object {
        fun newInstance() = ProfileFragment()

        /** 对齐 `FollowInfo.tsx:57,71` 的导航参数值。 */
        private const val TYPE_FOLLOWERS = "followers"
        private const val TYPE_FOLLOWING = "following"

        private const val CLIP_LABEL = "UID"
    }
}
