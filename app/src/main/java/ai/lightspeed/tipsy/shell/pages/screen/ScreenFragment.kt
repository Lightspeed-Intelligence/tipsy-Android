package ai.lightspeed.tipsy.shell.pages.screen

import ai.lightspeed.tipsy.shell.TipsyApplication
import ai.lightspeed.tipsy.shell.auth.AuthStateHub
import ai.lightspeed.tipsy.shell.i18n.L10n
import ai.lightspeed.tipsy.shell.pages.home.HomeFilterStore
import ai.lightspeed.tipsy.shell.pages.home.MmkvHomeCacheStorage
import ai.lightspeed.tipsy.shell.tabs.TAB_BAR_CONTENT_HEIGHT
import ai.lightspeed.tipsy.shell.tabs.androidTabBarBottomInsetDp
import ai.lightspeed.tipsy.shell.ui.ScaledMetrics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.lightspeed.tipsy.shell.router.AppRoute
import ai.lightspeed.tipsy.shell.router.AppRouter
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * 大屏页（Tab1）的宿主（W4-P1，进度文档 §2.35）。
 *
 * ## ⚠️ 会话埋点是**焦点 × 前台**两条轴
 *
 * - 焦点轴 = Fragment 的 `onStart` / `onStop`（Tab 切换）
 * - 前台轴 = [ProcessLifecycleOwner]（按 Home 键出去 / 回来）
 *
 * 只挂 Fragment 生命周期会漏掉「按 Home 键出去再回来」那条 ——
 * 表现为一个跨越数小时的畸形长会话（后端按 session 算停留时长）。
 * RN 侧用 `AppState.addEventListener` 覆盖这条（`screen.tsx:520-536`）。
 *
 * ⚠️ 用 `ProcessLifecycleOwner` 而不是 Activity 的 lifecycle：后者在 Tab
 * 切换时不变化，但它也**不区分**「Activity 不可见」与「进程进后台」——
 * 而我们要的正是后者。
 *
 * ## AB 分流在 Fragment 里发起
 *
 * flag 拉取是 REQUIRED 请求（游客拿不到），且要按 owner 缓存 ——
 * 放 Fragment 是因为它能拿到 `tokenStore`。ViewModel 只接收结果
 * （`onEndpointResolved(flagEnabled)`），保持可测。
 */
class ScreenFragment : Fragment() {

    private val viewModel: ScreenViewModel by viewModels {
        val app = requireActivity().application as TipsyApplication
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ScreenViewModel(
                api = ScreenApi(app.apiClient),
                tracker = ScreenSessionTracker(),
                cache = ScreenFirstScreenCacheStore(
                    MmkvHomeCacheStorage(app.sharedMmkvStore),
                ),
                generations = app.generations,
                languageProvider = { L10n.current },
                // 全局 nsfw：复用 Home 的唯一读取口（只读镜像）
                nsfwProvider = { HomeFilterStore(app.sharedMmkvStore).readNsfw() },
                ownerUserIdProvider = { app.tokenStore.currentUserId() },
            ) as T
        }
    }

    private val abConfigApi by lazy {
        ScreenAbConfigApi((requireActivity().application as TipsyApplication).apiClient)
    }

    /** 页面是否聚焦 —— 前台轴的回调要用它判断「与本页是否相关」。 */
    private var isFocused = false

    /**
     * 前台轴观察者。**必须在 onDestroyView 反注册** ——
     * `ProcessLifecycleOwner` 是进程级的。
     */
    private val foregroundObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            viewModel.onAppForegroundChanged(foreground = true, focused = isFocused)
        }

        override fun onStop(owner: LifecycleOwner) {
            viewModel.onAppForegroundChanged(foreground = false, focused = isFocused)
        }
    }

    private val authObserver = object : AuthStateHub.Observer {
        override fun onDidLogin(userId: String?) {
            // ⚠️ 换号要**重解析 AB**：配置按 owner 缓存，且游客与登录用户端点不同
            viewModel.onAuthChanged()
            resolveEndpoint()
        }

        override fun onDidLogout() {
            viewModel.onAuthChanged()
            // 登出后是游客 → 恒 distribution，仍要重解析（否则页面空着）
            resolveEndpoint()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val app = requireActivity().application as TipsyApplication
        app.authStateHub.addObserver(authObserver)
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)

        val composeView = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }

        // 语言 settle 后重拉（请求体带 language_code）。drop(1) 跳过当前值
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                L10n.languageFlow.drop(1).collect { viewModel.onLanguageSettled() }
            }
        }

        composeView.setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsState()
                ScreenScreen(
                    state = state,
                    onPageChanged = viewModel::onPageChanged,
                    onRefresh = viewModel::onRefresh,
                    onRetry = viewModel::onRetry,
                    onStartChat = ::onStartChat,
                    onCardEvent = viewModel::onCardEvent,
                    statusBarPadding = WindowInsets.statusBars
                        .asPaddingValues()
                        .calculateTopPadding(),
                    // ⚠️ **必须含 Tab 栏高度**，不能只用系统 inset。
                    //
                    // 壳的 tabbar 与内容是**叠放**（内容 fillMaxSize），而
                    // RN 侧 tabbar 是 Tab.Navigator 的一部分、容器本来就不含它。
                    // 只用 systemBars 的表现是「CTA 按钮被 tabbar 切掉」——
                    // 模拟器实测（2026-08-14）确认，Home/ChatList 早已踩过同型
                    // （`HomeFragment.listBottomPadding` 注释：「漏了的表现是
                    // 最后一行卡片被 tabbar 压住一半」）。复用同一套算法
                    bottomPadding = tabBarBottomPadding(
                        WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
                    ),
                )
            }
        }

        resolveEndpoint()
        return composeView
    }

    override fun onStart() {
        super.onStart()
        isFocused = true
        viewModel.onFocusChanged(focused = true)
        viewModel.onAppear()
    }

    override fun onStop() {
        isFocused = false
        viewModel.onFocusChanged(focused = false)
        super.onStop()
    }

    override fun onDestroyView() {
        (requireActivity().application as TipsyApplication)
            .authStateHub.removeObserver(authObserver)
        ProcessLifecycleOwner.get().lifecycle.removeObserver(foregroundObserver)
        super.onDestroyView()
    }

    /**
     * 拉 AB flag 并交给 ViewModel 定端点。
     *
     * **失败静默按 false**（走 distribution）—— 对齐 RN 的
     * `.catch(() => ({}))`。但留日志：否则 AB 恒不命中而无从判断。
     */
    private fun resolveEndpoint() {
        val app = requireActivity().application as TipsyApplication
        viewLifecycleOwner.lifecycleScope.launch {
            val owner = app.tokenStore.currentUserId()
            val flag = runCatching { abConfigApi.fetchScreenRecommendationFlag(owner) }
                .onFailure { Log.w(TAG, "AB 配置拉取失败，走 distribution", it) }
                .getOrDefault(false)
            viewModel.onEndpointResolved(flagEnabled = flag)
        }
    }

    /**
     * 点 CTA 进聊天。
     *
     * ⚠️ 顺序照 RN（`screen.tsx:639-640`）：先报 `home_input_click`、
     * 再 `endHomeSession`、最后导航 —— 会话必须在离开前收掉。
     *
     * ⚠️ 壳**不复刻** `resolveChatEntryScreen` 四路分流（§2.35 记的有意偏差）：
     * 只透传判定素材，由 `ChatDetailSurface` 自决入口屏 —— 与 ChatList 侧
     * 同一条纪律（§2.30）。
     *
     * ⚠️ 入口来源必须是 **`big_screen`**：RN 侧靠它把影院 `sourceType`
     * 判成 `first_tab`（`useChatNavigation.ts:59` 的 `=== 'big_screen'`）。
     * 传别的值不报错，只是影院埋点的来源轴变成 `chat_list`。
     */
    private fun onStartChat() {
        val item = viewModel.state.value.currentItem ?: return
        viewModel.onStartChat()
        val app = requireActivity().application as TipsyApplication
        app.requestRoute(
            AppRoute.ChatDetail(
                characterId = item.characterId,
                chatEnterSource = AppRoute.ChatEnterSource.BIG_SCREEN,
                characterType = item.characterType,
                contentType = item.contentType,
            ),
            AppRouter.Source.IN_APP,
        )
    }

    /**
     * 底部留白 = 设计稿常量（乘 scale）+ 系统 inset。
     *
     * 与 `HomeFragment.listBottomPadding` / `ChatListFragment` 同一套算法。
     * ⚠️ **两个设计稿常量乘 scale，系统 inset 不乘**
     * （见 `androidTabBarBottomInsetDp` 注释）。
     *
     * ⚠️ 大屏页**不加** `HOME_LIST_BOTTOM_EXTRA`：那 50dp 是给可滚动列表的
     * 尾部余量，而这里是全屏单卡，加了会让 CTA 离 tabbar 过远。
     */
    private fun tabBarBottomPadding(systemBottom: Dp): Dp {
        val scale = ScaledMetrics.scaleFactorFor(screenWidthDp())
        val safeBottomDp = systemBottom.value
        return (TAB_BAR_CONTENT_HEIGHT * scale +
            androidTabBarBottomInsetDp(safeBottomDp, scale)).dp
    }

    private fun screenWidthDp(): Float =
        resources.displayMetrics.widthPixels / resources.displayMetrics.density

    companion object {
        private const val TAG = "ScreenFragment"

        fun newInstance(): ScreenFragment = ScreenFragment()
    }
}
