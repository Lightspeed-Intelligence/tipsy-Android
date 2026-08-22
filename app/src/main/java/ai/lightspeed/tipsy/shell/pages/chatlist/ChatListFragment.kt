package ai.lightspeed.tipsy.shell.pages.chatlist

import ai.lightspeed.tipsy.shell.BuildConfig
import ai.lightspeed.tipsy.shell.TipsyApplication
import ai.lightspeed.tipsy.shell.auth.AuthStateHub
import ai.lightspeed.tipsy.shell.bridge.RefreshSignalHub
import ai.lightspeed.tipsy.shell.i18n.L10n
import ai.lightspeed.tipsy.shell.i18n.rememberCurrentLanguage
import ai.lightspeed.tipsy.shell.pages.home.HomeText
import ai.lightspeed.tipsy.shell.router.AppRoute
import ai.lightspeed.tipsy.shell.router.AppRouter
import ai.lightspeed.tipsy.shell.tabs.HOME_LIST_BOTTOM_EXTRA
import ai.lightspeed.tipsy.shell.tabs.TAB_BAR_CONTENT_HEIGHT
import ai.lightspeed.tipsy.shell.tabs.androidTabBarBottomInsetDp
import ai.lightspeed.tipsy.shell.ui.ScaledMetrics
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
import androidx.compose.runtime.remember
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
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
                userStore = app.currentUserStore,
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

    /**
     * 桥 `notifyChattedListChanged` → 即时静默重拉（建群/群成员变更）。
     * 进程级 hub，onDestroy 必须反注册（同 authObserver 的泄漏约束）。
     */
    private val chattedListChangedListener =
        RefreshSignalHub.Listener { viewModel.onChattedListChangedSignal() }

    private var hasReportedFirstExposure = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val app = requireActivity().application as TipsyApplication
        app.authStateHub.addObserver(authObserver)
        app.chattedListChangedHub.addListener(chattedListChangedListener)

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

                // 语言的响应式读取：Map 楼层标题（Today/Yesterday）要随切语言重建
                val currentLanguage by rememberCurrentLanguage()

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
                    onBellClick = { requestRoute(AppRoute.Letter()) },
                    onThreadClick = ::onThreadClick,
                    onPinClick = viewModel::togglePin,
                    onDeleteRequest = viewModel::requestDelete,
                    onDeleteConfirm = viewModel::confirmDelete,
                    onDeleteDismiss = viewModel::dismissDelete,
                    onGameCardExposed = viewModel::onGameCardExposed,
                    // Map 廊道（P2）。楼层只在 threads/语言变化时重算 ——
                    // groupByDay + build 是纯函数但不便宜（分桶 + 补位），
                    // 不 remember 会让每次滚动重组都重跑。
                    // ⚠️ key 带 language：Today/Yesterday 走词表，切语言要重建标题
                    mapFloors = remember(state.threads, currentLanguage) {
                        ChatMapSource.floorsFor(
                            state = state,
                            timeZone = TimeZone.getDefault(),
                            nowMillis = System.currentTimeMillis(),
                            localize = L10n::t,
                            formatDate = ::formatMapDateTitle,
                        )
                    },
                    mapMessageCountText = { HomeText.formatMessageCount(it.messageNum) },
                    mapTimeText = { thread ->
                        ChatListText.formatMapCardTime(
                            timestampMs = thread.latestTimeSeconds * 1000L,
                            relativeToday = ::mapCardRelativeTime,
                        )
                    },
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
        app.chattedListChangedHub.removeListener(chattedListChangedListener)
        app.authStateHub.removeObserver(authObserver)
        super.onDestroy()
    }

    /**
     * 点会话行：埋点/红点在 ViewModel（[ChatListViewModel.onThreadClicked]），
     * 导航在这里经 Router。
     *
     * 判定素材（isStory/characterType/contentType/chatEnterSource）**P9 已透传**。
     * 壳只给素材、不复刻分流 —— 由 `ChatDetailSurface.resolveInitialParams`
     * 自决初始屏（分流存两份必漂移）。
     */
    private fun onThreadClick(thread: ChatThread) {
        viewModel.onThreadClicked(thread)
        when {
            thread.itemType == ChatThread.TYPE_GAME ->
                // SimulatorGame 是 WebView 不迁（方案 §8.1）；埋点已发，
                // 明确记日志而非静默（同 Home World 卡）
                android.util.Log.w(TAG, "SimulatorGame 条目点击：WebView 不迁，无导航目标")

            // mini phone 走独立初始屏，不参与影院/html 分流，故不带素材
            // （RN 侧 `:297` 那个分支只读 characterId + parentConversationId）
            thread.isMiniPhone -> requestRoute(
                AppRoute.MiniPhoneChat(characterId = thread.itemId),
            )

            else -> requestRoute(
                AppRoute.ChatDetail(
                    characterId = thread.itemId,
                    chatEnterSource = AppRoute.ChatEnterSource.CHAT_LIST,
                    // ⚠️ isStoryEntry，**不是** showStoryTag —— 后者把多角色
                    // 也算进来，会让影院角色落到普通聊天页（见其 KDoc）
                    isStory = thread.isStoryEntry,
                    characterType = thread.characterType,
                    contentType = thread.contentType,
                ),
            )
        }
    }

    private fun requestRoute(route: AppRoute) {
        val app = requireActivity().application as TipsyApplication
        app.requestRoute(route, AppRouter.Source.IN_APP)
    }

    /**
     * Map 楼层日期标题的格式化侧（分支判定在 [ChatMapSource.titleFor]，
     * 这里只负责渲染）。RN 两形态：同年 `D MMMM`（`12 August`）、
     * 跨年 `MMM D, YYYY`（`Aug 12, 2025`）—— dayjs 会随 locale 换月份名，
     * 这里用 [SimpleDateFormat] + 壳当前语言的 locale 同义。
     */
    private fun formatMapDateTitle(title: ChatMapSource.DateTitle): String {
        val cal = Calendar.getInstance().apply {
            set(title.year, title.month - 1, title.dayOfMonth)
        }
        val pattern = if (title.includeYear) "MMM d, yyyy" else "d MMMM"
        return SimpleDateFormat(pattern, currentLocale()).format(cal.time)
    }

    /**
     * Map 卡片"今天"分支的相对时间（RN dayjs `fromNow()`）。
     *
     * 用 `android.icu.text.RelativeDateTimeFormatter`（API 24+，随 locale
     * 本地化）—— dayjs 的相对文案来自它自带的 locale 包，26 语言词表里
     * 都没有对应词条，手工拼会漏掉 25 种语言。措辞粒度与 dayjs 略有出入
     * （同 iOS 用 `RelativeDateTimeFormatter` 的既有偏差），可接受。
     */
    private fun mapCardRelativeTime(elapsedMs: Long): String {
        val formatter = android.icu.text.RelativeDateTimeFormatter.getInstance(
            android.icu.util.ULocale.forLocale(currentLocale()),
        )
        val minutes = elapsedMs / 60_000L
        return when {
            // dayjs 44 秒内是 "a few seconds ago"；icu 无该档，用 0 分钟档同义
            minutes < 1 -> formatter.format(
                0.0,
                android.icu.text.RelativeDateTimeFormatter.Direction.LAST,
                android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MINUTES,
            )
            minutes < 60 -> formatter.format(
                minutes.toDouble(),
                android.icu.text.RelativeDateTimeFormatter.Direction.LAST,
                android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MINUTES,
            )
            else -> formatter.format(
                (minutes / 60).toDouble(),
                android.icu.text.RelativeDateTimeFormatter.Direction.LAST,
                android.icu.text.RelativeDateTimeFormatter.RelativeUnit.HOURS,
            )
        }
    }

    /** 壳当前语言 → Locale（月份名/相对时间的本地化输入；`zh-tw` 这类带地区）。 */
    private fun currentLocale(): Locale = Locale.forLanguageTag(L10n.current)

    companion object {
        private const val TAG = "ChatListFragment"
        private const val CHANNEL_GOOGLE_PLAY = "GooglePlay"

        fun newInstance() = ChatListFragment()
    }
}
