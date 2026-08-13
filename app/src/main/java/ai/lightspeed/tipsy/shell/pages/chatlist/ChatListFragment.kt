package ai.lightspeed.tipsy.shell.pages.chatlist

import ai.lightspeed.tipsy.shell.BuildConfig
import ai.lightspeed.tipsy.shell.TipsyApplication
import ai.lightspeed.tipsy.shell.auth.AuthStateHub
import ai.lightspeed.tipsy.shell.i18n.L10n
import ai.lightspeed.tipsy.shell.router.AppRoute
import ai.lightspeed.tipsy.shell.router.AppRouter
import ai.lightspeed.tipsy.shell.tabs.HOME_LIST_BOTTOM_EXTRA
import ai.lightspeed.tipsy.shell.tabs.TAB_BAR_CONTENT_HEIGHT
import ai.lightspeed.tipsy.shell.tabs.androidTabBarBottomInsetDp
import ai.lightspeed.tipsy.shell.ui.ScaledMetrics
import ai.lightspeed.tipsy.shell.user.CurrentUserStore
import ai.lightspeed.tipsy.shell.user.UserInfoApi
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
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
 * ChatList Tab 的宿主（W3 P1，替掉 `TabPlaceholderFragment`）。
 *
 * ## 出口现在**都会被 Router 明确拒绝**（与 Profile/Home 现状同型）
 *
 * 点会话行 → `AppRoute.ChatDetail` / `MiniPhoneChat`（P9 gate 前白名单为空）；
 * 铃铛 → `AppRoute.Letter`（NotificationSurface，W4）。
 * 走 `app.requestRoute` 是 §4.7 单一导航入口 —— 拒绝提示由 Router 的
 * `rejectNotEnabled` 统一给，业务页不自己弹「未开放」。
 *
 * game 条目连路由类型都没有（SimulatorGame 是 WebView，方案 §8.1 已定不迁），
 * 对齐 Home World 卡的处理：ViewModel 发埋点，这里明确记日志不导航。
 */
class ChatListFragment : Fragment() {

    private val viewModel: ChatListViewModel by viewModels {
        val app = requireActivity().application as TipsyApplication
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatListViewModel(
                api = ChatListApi(app.apiClient, BuildConfig.DOWNLOAD_CHANNEL),
                drafts = ChatDraftStore(app.sharedMmkvStore),
                pageTypeStore = ChatPageTypeStore(app.sharedMmkvStore),
                cache = ChatListCache(app.sharedMmkvStore),
                convEpoch = ConvEpochWriter(app.sharedMmkvStore),
                generations = app.generations,
                languageProvider = { L10n.current },
                userIdProvider = { app.tokenStore.currentUserId() },
                userStore = CurrentUserStore(UserInfoApi(app.apiClient)),
            ) as T
        }
    }

    /** 登录态订阅。必须在 onDestroy 反注册（hub 进程级，同 Home/Profile）。 */
    private val authObserver = object : AuthStateHub.Observer {
        override fun onDidLogin(userId: String?) {
            viewModel.onAuthChanged(loggedIn = true)
        }

        override fun onDidLogout() {
            // 全接口 REQUIRED：登出只清不拉（AuthStateHub 硬约束）
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

        // 语言 settle 后重拉（请求体带 language_code；drop(1) 跳过当前值，同 Home）
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                L10n.languageFlow.drop(1).collect { viewModel.onLanguageSettled() }
            }
        }

        composeView.setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsState()
                val context = LocalContext.current

                // 一次性 Toast（pin/delete 的结果反馈，同 LoginFragment 的处理：
                // Toast 是一次性副作用不参与重组，用 L10n.t 而非 LocalizedText）
                LaunchedEffect(state.toastKey) {
                    state.toastKey?.let { key ->
                        Toast.makeText(context, L10n.t(key), Toast.LENGTH_SHORT).show()
                        viewModel.consumeToast()
                    }
                }

                // inset 都在 composition 内响应式读取（Profile 先例：listener 在
                // 首帧后才回调，那之前值是 0，顶栏会画进状态栏）。底部留白的
                // 设计稿常量部分乘 scale，系统 inset 不乘（HomeFragment 同规则）
                val safeBottomDp = WindowInsets.systemBars
                    .asPaddingValues()
                    .calculateBottomPadding().value
                val scale = ScaledMetrics.scaleFactor()
                val bottomPadding =
                    ((HOME_LIST_BOTTOM_EXTRA + TAB_BAR_CONTENT_HEIGHT) * scale +
                        androidTabBarBottomInsetDp(safeBottomDp, scale)).dp

                ChatListScreen(
                    state = state,
                    isGooglePlay = BuildConfig.DOWNLOAD_CHANNEL == CHANNEL_GOOGLE_PLAY,
                    statusBarPadding = WindowInsets.statusBars
                        .asPaddingValues()
                        .calculateTopPadding(),
                    listBottomPadding = bottomPadding,
                    onRefresh = viewModel::onRefresh,
                    onLoadMore = viewModel::onLoadMore,
                    onPageTypeSelected = viewModel::onPageTypeSelected,
                    onBellClick = { requestRoute(AppRoute.Letter) },
                    onThreadClick = ::onThreadClick,
                    onPinClick = viewModel::togglePin,
                    onDeleteRequest = viewModel::requestDelete,
                    onDeleteConfirm = viewModel::confirmDelete,
                    onDeleteDismiss = viewModel::dismissDelete,
                    onGameCardExposed = viewModel::onGameCardExposed,
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
        (requireActivity().application as TipsyApplication)
            .authStateHub.removeObserver(authObserver)
        super.onDestroy()
    }

    /**
     * 点会话行：埋点/红点在 ViewModel（[ChatListViewModel.onThreadClicked]），
     * 导航在这里经 Router。
     *
     * 判定素材（isStory/characterType/contentType/chatEnterSource）P9 接
     * ChatDetailSurface 时随 route 参数透传 —— `AppRoute.ChatDetail` 当前
     * 只带 characterId，扩参属 P9 包（先扩会让未启用的路由类型积累
     * 未验证字段）。
     */
    private fun onThreadClick(thread: ChatThread) {
        viewModel.onThreadClicked(thread)
        when {
            thread.itemType == ChatThread.TYPE_GAME ->
                // SimulatorGame 是 WebView 不迁（方案 §8.1）；埋点已发，
                // 明确记日志而非静默（同 Home World 卡）
                android.util.Log.w(TAG, "SimulatorGame 条目点击：WebView 不迁，无导航目标")

            thread.isMiniPhone -> requestRoute(
                AppRoute.MiniPhoneChat(characterId = thread.itemId),
            )

            else -> requestRoute(AppRoute.ChatDetail(characterId = thread.itemId))
        }
    }

    private fun requestRoute(route: AppRoute) {
        val app = requireActivity().application as TipsyApplication
        app.requestRoute(route, AppRouter.Source.IN_APP)
    }

    companion object {
        private const val TAG = "ChatListFragment"
        private const val CHANNEL_GOOGLE_PLAY = "GooglePlay"

        fun newInstance() = ChatListFragment()
    }
}
