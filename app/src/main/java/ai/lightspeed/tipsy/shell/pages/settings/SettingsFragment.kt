package ai.lightspeed.tipsy.shell.pages.settings

import ai.lightspeed.tipsy.shell.R
import ai.lightspeed.tipsy.shell.TipsyApplication
import ai.lightspeed.tipsy.shell.auth.AuthStateHub
import ai.lightspeed.tipsy.shell.i18n.AccountLanguageMirror
import ai.lightspeed.tipsy.shell.i18n.L10n
import ai.lightspeed.tipsy.shell.pages.home.HomeFilterStore
import ai.lightspeed.tipsy.shell.router.AppRoute
import ai.lightspeed.tipsy.shell.router.AppRouter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Settings 列表的宿主（W3，进度文档 §2.33）。
 *
 * ## 子页出口已放行（W4 批次 3）
 *
 * 7 个 `SettingsSurface` 子屏（Security/Blacklist/Feedback/About/ContactUs/
 * Delete/Widget）经 `AppRoute.SettingsSubScreen` 进 `SettingsSurface`，
 * 共用一个容器（`initialScreen` 平铺 prop 分流初始屏）。§2.41 的静态 gate
 * （强类型 Screen enum、微根/微栈机器断言、退栈按类型解除去重）先行落地。
 *
 * 三个外部链接行（社区规范/服务条款/官网）不经 Surface。
 *
 * ## 语言页是同一个 ViewModel
 *
 * 两者共享可选语言列表与当前语言，拆成两个 VM 会让语言页每次打开都重拉
 * （RN 侧那个列表在 store 里跨页复用）。故语言页由本 Fragment 压栈打开，
 * 通过 `activityViewModels` 之外的方式共享 —— 见 [openLanguage]。
 */
class SettingsFragment : Fragment() {

    private val viewModel: SettingsViewModel by viewModels(
        ownerProducer = { requireActivity() },
        factoryProducer = { settingsViewModelFactory(requireActivity().application as TipsyApplication) },
    )

    /**
     * 登录态订阅。**必须在 onDestroyView 反注册** —— hub 是进程级的。
     */
    private val authObserver = object : AuthStateHub.Observer {
        override fun onDidLogin(userId: String?) = viewModel.onAuthChanged()
        override fun onDidLogout() = viewModel.onAuthChanged()
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

        composeView.setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsState()
                SettingsScreen(
                    state = state,
                    onBackClick = ::popSelf,
                    onRowClick = ::onRowClick,
                    onNsfwToggle = viewModel::onNsfwToggle,
                    onLogoutClick = ::confirmLogout,
                    statusBarPadding = WindowInsets.statusBars
                        .asPaddingValues()
                        .calculateTopPadding(),
                    bottomPadding = WindowInsets.systemBars
                        .asPaddingValues()
                        .calculateBottomPadding(),
                )
            }
        }

        return composeView
    }

    override fun onStart() {
        super.onStart()
        // nsfw 初值读 RN 的 config-persist 镜像（`HomeFilterStore.readNsfw()`，
        // 只读）。⚠️ 不为设置页单独拉 /user/info —— 那份真值由 auth 链维护，
        // 这里只要一个显示初值；读不到按 false（最保守，见 HomeFilterStore）
        val app = requireActivity().application as TipsyApplication
        viewModel.onAppear(nsfwEnabled = HomeFilterStore(app.sharedMmkvStore).readNsfw())
    }

    /**
     * Limitless 开关写失败时弹 Toast。
     *
     * ⚠️ **必须有**：`onNsfwToggle` 失败是「自动回滚」（只有接口成功才改本地值），
     * 没有提示的话表现为**开关点了自己弹回去**，和「没点到」完全无法区分 ——
     * `/user/nsfw` 路径少了 `/update` 那个 404 就是这么藏了一整轮的。
     *
     * ⚠️ 挂 `onViewCreated` 而不是 `onStart`：后者每次前后台切换都会再注册一个
     * 收集器，同一个错误会被弹成好几遍。这里一个 view 生命周期内只注册一次，
     * 常驻收集（不是 `first()`）—— 弹完就 `onLanguageErrorShown()` 清掉标志，
     * 所以不会重弹，且第二次失败仍然能弹。
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state
                .map { it.languageError }
                .filterNotNull()
                .collect { key ->
                    Toast.makeText(
                        requireContext().applicationContext,
                        L10n.t(key),
                        Toast.LENGTH_SHORT,
                    ).show()
                    viewModel.onLanguageErrorShown()
                }
        }
    }

    override fun onDestroyView() {
        (requireActivity().application as TipsyApplication)
            .authStateHub.removeObserver(authObserver)
        super.onDestroyView()
    }

    private fun onRowClick(row: SettingsRow) {
        when (val action = row.action) {
            is SettingsAction.OpenLanguage -> openLanguage()
            is SettingsAction.ToggleAccountSecurity -> viewModel.onToggleAccountSecurity()
            // 开关行由 SettingsScreen 直接接 onNsfwToggle，不该走到这里
            is SettingsAction.ToggleNsfw -> viewModel.onNsfwToggle()
            is SettingsAction.Subscription -> requestRoute(AppRoute.GemsPurchase())
            // 7 个子屏都还未过 §9.1，Router 会明确拒绝并记日志。
            // ⚠️ 不能传 AppRoute.Settings —— 那是列表本体（已在白名单里），
            // 会变成「点子页又打开一层设置列表」
            is SettingsAction.SurfaceScreen ->
                requestRoute(AppRoute.SettingsSubScreen(action.screen))
            is SettingsAction.OpenUrl -> openExternalUrl(action.url)
        }
    }

    /**
     * 打开原生语言页。
     *
     * ⚠️ **必须幂等**（同 `openLogin` / `openSearch` 的理由）：连点两次会叠两层，
     * 返回要按两次。用 tag 判定。
     */
    private fun openLanguage() {
        val fm = parentFragmentManager
        if (fm.findFragmentByTag(TAG_LANGUAGE) != null) {
            Log.i(TAG, "语言页已在栈中，忽略重复请求")
            return
        }
        fm.commit {
            replace(R.id.surface_container, LanguageFragment.newInstance(), TAG_LANGUAGE)
            addToBackStack(TAG_LANGUAGE)
        }
    }

    /**
     * 外部链接（对齐 RN 的 `WebBrowser.openBrowserAsync`）。
     *
     * ⚠️ 必须捕获 [ActivityNotFoundException]：设备可能没有浏览器
     * （精简 ROM / 企业设备），未捕获会直接崩。RN 侧那个 await 也不会崩 App。
     */
    private fun openExternalUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "无可用浏览器，忽略外部链接", e)
        }
    }

    /**
     * 登出确认弹窗（`page.tsx:362-364`）。
     *
     * 文案照抄：`Are you sure you want to log out?` / `Confirm` / `Cancel`，
     * 三个 key 都已在 SHELL_KEYS。
     */
    private fun confirmLogout() {
        AlertDialog.Builder(requireContext())
            .setMessage(L10n.t("Are you sure you want to log out?"))
            .setPositiveButton(L10n.t("Confirm")) { _, _ -> performLogout() }
            .setNegativeButton(L10n.t("Cancel"), null)
            .show()
    }

    /**
     * 执行登出。
     *
     * 走 `clearToken(notifyListener = true)` —— 那条路径会广播
     * `AuthStateHub.didLogout`，各页面自己清数据；`MainActivity` 的
     * bootstrap 链会拉起登录页。**壳自己不 commit 登录页**：那属 Router 职责，
     * 绕过去会出现两层登录页（§2.20 记的幂等坑）。
     */
    private fun performLogout() {
        val app = requireActivity().application as TipsyApplication
        viewLifecycleOwner.lifecycleScope.launch {
            app.tokenStore.clearToken()
        }
    }

    private fun requestRoute(route: AppRoute) {
        val app = requireActivity().application as TipsyApplication
        app.requestRoute(route, AppRouter.Source.IN_APP)
    }

    private fun popSelf() {
        parentFragmentManager.popBackStack()
    }

    companion object {
        private const val TAG = "SettingsFragment"

        /** 语言页 tag —— [openLanguage] 靠它做幂等判定。 */
        const val TAG_LANGUAGE = "settings_language"

        fun newInstance(): SettingsFragment = SettingsFragment()

        /**
         * 共享 VM 工厂。
         *
         * 两个 Fragment（列表与语言页）用 **Activity 作为 ViewModelStoreOwner**
         * 共享同一个实例 —— 见类注释「语言页是同一个 ViewModel」。
         */
        fun settingsViewModelFactory(app: TipsyApplication): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(
                    api = SettingsApi(app.apiClient),
                    // 语言镜像：不接它语言会被静默倒灌回英文（§2.37）
                    languageMirror = AccountLanguageMirror(
                        repository = app.userStorageRepository,
                        currentUserId = { app.tokenStore.currentUserId() },
                    ),
                    generations = app.generations,
                ) as T
            }
    }
}
