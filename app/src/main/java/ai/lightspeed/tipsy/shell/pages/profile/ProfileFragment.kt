package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.TipsyApplication
import ai.lightspeed.tipsy.shell.auth.AuthStateHub
import ai.lightspeed.tipsy.shell.i18n.L10n
import ai.lightspeed.tipsy.shell.router.AppRoute
import ai.lightspeed.tipsy.shell.router.AppRouter
import ai.lightspeed.tipsy.shell.user.CurrentUserStore
import ai.lightspeed.tipsy.shell.user.UserInfoApi
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Profile Tab 的宿主（W3 第一刀，替掉 `TabPlaceholderFragment`）。
 *
 * ## 出口全部经 Router，启用状态集中在 policy
 *
 * `ProductionRoutePolicy.enabledRouteTypes` 已放行原生 Settings，但 EditProfile、
 * Follow、Gems 与 UserCoins 仍会走 `navigator.rejectNotEnabled`给出明确错误。
 * EditProfile 的 Surface/auth/refresh 预接线已落地，只是 §9.1 未跑，因此生产
 * policy 刻意保持关闭。
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
                walletApi = ProfileWalletApi(app.apiClient),
                userStore = CurrentUserStore(UserInfoApi(app.apiClient)),
                languageProvider = { L10n.current },
                avatarDecorationSource = AvatarDecorationApi(app.apiClient),
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

    /**
     * EditProfileSurface 是 sibling 容器：它盖住 Profile 时，本 Fragment 可能一直
     * 保持 STARTED，关闭也不会再走 [onStart]。协调器同时处理前台即时刷新、
     * 非前台的 onStart 补消费，以及 `/user/info` 成功后才 ack dirty。
     */
    private lateinit var profileRefreshCoordinator: ProfileRefreshCoordinator

    private var hasReportedFirstExposure = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = requireActivity().application as TipsyApplication
        profileRefreshCoordinator = ProfileRefreshCoordinator(
            hub = app.profileRefreshHub,
            currentUserIdProvider = { app.tokenStore.currentUserId() },
            isStarted = { lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
            refresh = { onUserInfoRefreshed, onUserInfoRefreshFailed ->
                viewModel.onProfileChanged(
                    onUserInfoRefreshed = onUserInfoRefreshed,
                    onUserInfoRefreshFailed = onUserInfoRefreshFailed,
                )
            },
            scheduleRetry = { retry ->
                lifecycleScope.launch {
                    // 失败 completion 发生在当前 userStatsJob 收尾；让出一次主线程后
                    // 再发 retry，避免 refreshUserAndStats 从自己的 completion 里同步取消自己。
                    yield()
                    retry()
                }
            },
        )
        app.profileRefreshHub.addObserver(profileRefreshCoordinator)
    }

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
                    // 三个出口对齐 UserProfileGems 的三个 handler（方案 §8.1 出口表）：
                    // 宝石+/升级 → GemsSubscriptionSurface（RN 同页不同 tab，路由参数
                    // 形状照 handleAddGem/handleUpgrade）；金币 → UserCoinsSurface
                    onWalletAction = { action ->
                        when (action) {
                            ProfileWalletAction.ADD_GEMS -> requestRoute(
                                AppRoute.GemsPurchase(mapOf("initialTab" to "buy_gems")),
                            )
                            ProfileWalletAction.UPGRADE -> requestRoute(
                                AppRoute.GemsPurchase(mapOf("initialTab" to "subscription")),
                            )
                            ProfileWalletAction.COINS -> requestRoute(AppRoute.UserCoins)
                        }
                    },
                    avatarDecorationImageUrl = state.avatarDecorationImageUrl,
                    onSocialLinkClick = ::openExternalUrl,
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
        // dirty 轨已发起定向用户资料刷新时，onAppear 只保留「首屏列表
        // 按需加载」，避免紧接着取消定向请求并重发第二轮 /user/info。
        val startedTargetedRefresh = profileRefreshCoordinator.onStart()
        viewModel.onAppear(refreshProfile = !startedTargetedRefresh)
        if (!hasReportedFirstExposure) {
            hasReportedFirstExposure = true
            viewModel.onFirstExposure()
        }
    }

    override fun onDestroy() {
        val app = requireActivity().application as TipsyApplication
        app.profileRefreshHub.removeObserver(profileRefreshCoordinator)
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

    /**
     * 社交链接（P7 渠道图标，对齐 RN `WebBrowser.openBrowserAsync`）。
     *
     * ⚠️ 必须捕获 [ActivityNotFoundException]（同 `SettingsFragment.openExternalUrl`）：
     * 设备可能没有浏览器，未捕获会直接崩；RN 那个 await 也不会崩 App。
     */
    private fun openExternalUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "无可用浏览器，忽略社交链接", e)
        }
    }

    companion object {
        fun newInstance() = ProfileFragment()

        /** 对齐 `FollowInfo.tsx:57,71` 的导航参数值。 */
        private const val TYPE_FOLLOWERS = "followers"
        private const val TYPE_FOLLOWING = "following"

        private const val CLIP_LABEL = "UID"

        private const val TAG = "ProfileFragment"
    }
}
