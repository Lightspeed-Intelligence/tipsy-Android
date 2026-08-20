package ai.lightspeed.tipsy.shell.pages.screen

import ai.lightspeed.tipsy.shell.TipsyApplication
import ai.lightspeed.tipsy.shell.R
import androidx.fragment.app.FragmentManager
import ai.lightspeed.tipsy.shell.auth.AuthStateHub
import ai.lightspeed.tipsy.shell.auth.LegacyMmkvStore
import androidx.media3.common.util.UnstableApi
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
import androidx.compose.runtime.mutableStateOf
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
 * 大屏页（Tab1）的宿主（W4-P1/P2，进度文档 §2.35 / §2.42）。
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
     * 是否被 Surface 盖住（R1）。
     *
     * ⚠️ `surface_container` 是 `native_root_container` 的 **sibling**
     * （见 `activity_main.xml`），所以打开 ChatDetail / Create / Search /
     * Settings / Login 时：Screen **不会** hidden、TabHost **不会** stop。
     * 只看 `onStart`/`onHiddenChanged` 两条轴的表现是
     * **盖了 Surface 视频仍在后台播**（还占着音频焦点）。
     */
    private var isCovered = false

    /**
     * Activity back stack 监听：Surface push/pop 后重算遮挡态。
     * **必须在 onDestroyView 反注册** —— 它挂在 Activity 的 FragmentManager 上。
     */
    private val backStackListener = FragmentManager.OnBackStackChangedListener {
        isCovered = hasVisibleSurface()
        applyVisible(computeVisible())
    }

    /**
     * 播放门（W4-P2）：Activity 已 started、Tab 未 hidden、且未被 Surface 覆盖才播。
     *
     * ⚠️ 与 [isFocused] 分开存而不是复用它：[isFocused] 是普通字段，Compose
     * 读不到它的变化。这个必须是 `MutableState` 才能让视频层在切 Tab /
     * 进后台时**立刻**停播 —— 漏了的表现是「切走了还在后台播声音」。
     */
    private val playbackActive = mutableStateOf(false)

    /**
     * 有界播放器池（W4-P2）。**随 view 生命周期**：`onCreateView` 建、
     * `onDestroyView` 释放。
     *
     * ⚠️ 不能挂到 Fragment 本身或 Application 上 —— 池持有 [ExoPlayer]，
     * 而 ExoPlayer 持有 Surface 与解码器。跨 view 重建存活会泄漏解码器，
     * 表现是「反复切 Tab 后视频不再播」（解码器耗尽），且不报错。
     */
    private var playerPool: ScreenPlayerPool? = null

    /**
     * 声音开关。只读 RN 的 `chat-persist-storage` 作**每次可见时的初值**，
     * 页内点击只改内存、**不写回** —— 见 [ScreenSoundPreference] 的所有权说明。
     *
     * ⚠️ **必须每次真正可见时重读，不能只放在 `onStart` 或 `by lazy`**：
     * TabHost 的 show/hide 不会重走 `onStart`；用户又可能在 RN 的 Screen 页或
     * Chat Settings 里改过这个开关（那边是唯一 writer）。只读一次的表现是
     * 「在别处关了声音，回到原生大屏页又出声」—— 而这种不一致用户不会报成缺陷。
     *
     * 页内点击不持久化是**本刀刻意接受的临时偏差**：写回属共享键写协议，另包解决。
     */
    private val soundEnabled = mutableStateOf(ScreenSoundPreference.DEFAULT_SOUND_ENABLED)

    /** Activity 生命周期轴。 */
    private var isStarted = false

    /** 上一次下发的可见性 —— [applyVisible] 的幂等闸。null = 尚未下发过。 */
    private var lastAppliedVisible: Boolean? = null

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

    // 组合 ScreenScreen → ScreenVideoHost（PlayerView 是 Media3 opt-in API）。
    // ⚠️ 标在方法上而**不是类上**：标类会让构造 ScreenFragment 的
    // TabHostFragment 也被要求 opt-in —— 一个内部实现细节不该外溢到 Tab 宿主
    @UnstableApi
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val app = requireActivity().application as TipsyApplication
        app.authStateHub.addObserver(authObserver)
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)

        playerPool = ScreenPlayerPool(requireContext().applicationContext)
        // R1：Surface 遮挡轴。sibling 容器不会让本 Fragment hidden，只能靠 back stack
        isCovered = hasVisibleSurface()
        requireActivity().supportFragmentManager
            .addOnBackStackChangedListener(backStackListener)

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
                    isActive = playbackActive.value,
                    soundEnabled = soundEnabled.value,
                    // 页内切换只改内存（刻意不写回 RN 的共享键，见 soundEnabled 注释）
                    onSoundToggle = { soundEnabled.value = !soundEnabled.value },
                    playerPool = playerPool,
                    onPageChanged = viewModel::onPageChanged,
                    onRefresh = viewModel::onRefresh,
                    onRetry = viewModel::onRetry,
                    onStartChat = ::onStartChat,
                    onCardEvent = ::onCardEvent,
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
        // ⚠️ 切 Tab **不会**走 onStart —— TabHostFragment 用 show/hide 保状态
        // （对齐 RN 的 `detachInactiveScreens={false}`），hide 不改生命周期状态。
        // 这里只是三条轴里的「Activity 级别可见」那条
        isStarted = true
        isCovered = hasVisibleSurface()
        applyVisible(computeVisible())
        viewModel.onAppear()
    }

    /**
     * Tab 切换轴（**show/hide 不触发 onStart/onStop，所以必须有这个**）。
     *
     * 漏了它的后果有两个，都不报错：
     * 1. 切到别的 Tab 后**视频继续在后台播**（`playbackActive` 不复位）；
     * 2. 从 RN 侧改过声音开关再切回来仍用旧值（不重读 MMKV）。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        applyVisible(computeVisible())
    }

    /**
     * 三条轴的合成：`started && !isHidden && !isCovered`。
     *
     * 少任何一条都会留下「某条路径下视频继续后台播」的缺口，而三条的触发方式
     * 各不相同（生命周期 / show-hide / sibling 容器 back stack）。
     */
    private fun computeVisible(): Boolean =
        ScreenVisibility.isVisible(started = isStarted, hidden = isHidden, covered = isCovered)

    /** `surface_container` 里是否有可见 Fragment（= 本页被盖住）。 */
    private fun hasVisibleSurface(): Boolean {
        val container = activity?.findViewById<android.view.View>(R.id.surface_container)
            ?: return false
        val fm = activity?.supportFragmentManager ?: return false
        // 容器自身不可见（gone）时不算被盖；否则看里面有没有已添加且未 hidden 的 Fragment
        if (container.visibility != android.view.View.VISIBLE) return false
        return fm.fragments.any { it.isAdded && !it.isHidden && it.id == R.id.surface_container }
    }

    /**
     * 可见性收口：三条轴（Activity 生命周期 / Tab show-hide / Surface 遮挡）
     * 都汇到这里。
     *
     * **幂等**：同一状态重复下发直接返回 —— back stack listener 与
     * onHiddenChanged 可能在同一次交互里都触发，重复下发会让 ViewModel 的
     * session 埋点重复开合。
     */
    private fun applyVisible(visible: Boolean) {
        if (visible == lastAppliedVisible) return
        lastAppliedVisible = visible
        isFocused = visible
        playbackActive.value = visible
        if (visible) {
            // ⚠️ 每次真正可见都重读，**不是只在创建时读一次**：RN 侧（Screen 页 /
            // Chat Settings）是这个开关的唯一 writer，用户在那边改过我们必须跟上。
            // 漏了的表现是「在别处关了声音、回到原生大屏页又出声」，用户不会报
            soundEnabled.value = ScreenSoundPreference.read(LegacyMmkvStore.open(requireContext()))
        }
        viewModel.onFocusChanged(focused = visible)
    }

    override fun onStop() {
        // 停播（对齐 RN 失焦立即 pause）。⚠️ **只停播不销毁池** ——
        // iOS 研究文档 §4「聚焦/失焦」明写「不重置状态，保留缓冲快速恢复」
        isStarted = false
        applyVisible(false)
        super.onStop()
    }

    override fun onDestroyView() {
        (requireActivity().application as TipsyApplication)
            .authStateHub.removeObserver(authObserver)
        ProcessLifecycleOwner.get().lifecycle.removeObserver(foregroundObserver)
        requireActivity().supportFragmentManager
            .removeOnBackStackChangedListener(backStackListener)
        // 释放全部 ExoPlayer。漏了会泄漏解码器 —— 表现是「反复切 Tab 后
        // 视频不再播」，且不报错，见 playerPool 的字段注释
        playerPool?.release()
        playerPool = null
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
     * 只透传判定素材与列表已有的首帧 preload，由 `ChatDetailSurface` 自决入口屏
     * 并同步 seed 背景 —— 与 ChatList 侧同一条分流纪律（§2.30）。
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
                preload = item.toChatDetailPreload(),
            ),
            AppRouter.Source.IN_APP,
        )
    }

    /**
     * 卡片级事件：埋点全部走 ViewModel（会话内去重在 tracker），
     * 评论点击额外导航 —— iOS `ScreenViewController:835-845` 同序：
     * 先埋点、再 `.comments` 路由。
     *
     * targetType 恒 `character`（iOS 硬编码 `CommentTargetType.character`，
     * Screen feed 全是角色卡）；creatorId 传 feed 的创作者 id（删除权限/
     * 创作者徽章）；commentId/rootId 是互动通知入口的定位参数，这里不传。
     */
    private fun onCardEvent(event: ScreenCardEvent) {
        viewModel.onCardEvent(event)
        if (event == ScreenCardEvent.COMMENT_CLICK) {
            val item = viewModel.state.value.currentItem ?: return
            val app = requireActivity().application as TipsyApplication
            app.requestRoute(
                AppRoute.Comments(
                    targetType = AppRoute.Comments.TARGET_TYPE_CHARACTER,
                    targetId = item.characterId,
                    creatorId = item.creatorId.orEmpty(),
                ),
                AppRouter.Source.IN_APP,
            )
        }
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
