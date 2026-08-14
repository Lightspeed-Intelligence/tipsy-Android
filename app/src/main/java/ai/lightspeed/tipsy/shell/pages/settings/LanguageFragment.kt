package ai.lightspeed.tipsy.shell.pages.settings

import ai.lightspeed.tipsy.shell.TipsyApplication
import ai.lightspeed.tipsy.shell.i18n.L10n
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 语言设置页的宿主（W3，进度文档 §2.33）。
 *
 * ## ⚠️ 这是原生页，不是 Surface
 *
 * 曾以为它在 `SettingsSurface` 里 —— **错**。三处独立证据（§2.33）：
 * 1. `SettingsSurface.tsx:34-44` 的 `KNOWN_SCREENS` **刻意不含 `Language`**，
 *    注释：「语言页**原生**：壳是语言唯一写入者，onLanguageChanged 单向广播」
 * 2. `index.surfaces.js:84-85` **刻意不调** `hydrateSupportedLanguages`，
 *    注释：「消费页（语言设置）壳内为**原生**」—— 所以壳内那个 store 字段恒空，
 *    列表必须壳自己拉
 * 3. iOS 侧对应物是原生 `LanguageViewController.swift`
 *
 * 按错的理解会把这个页面整个漏掉，而**漏掉不报错**：i18n 机制 W1 就完成了，
 * 只是没有入口 —— 本地测试一切正常（设备语言恰好合适）。
 *
 * ## Done 是「先应用后保存」且失败不回滚
 *
 * 逐行对齐 `language.tsx:29-40`：点 Done → **立即 goBack()** → 后台切语言 +
 * 打接口。失败只弹 `Save failed` Toast，本地语言已经切了不还原。
 * 写成「等接口成功再切」会有明显卡顿（与现网体感不同）。
 */
class LanguageFragment : Fragment() {

    /** 与 [SettingsFragment] 共享同一实例（Activity 作 owner），见那边类注释。 */
    private val viewModel: SettingsViewModel by viewModels(
        ownerProducer = { requireActivity() },
        factoryProducer = {
            SettingsFragment.settingsViewModelFactory(
                requireActivity().application as TipsyApplication,
            )
        },
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val composeView = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }

        composeView.setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsState()
                LanguageScreen(
                    state = state,
                    onBackClick = ::popSelf,
                    onSelect = viewModel::onLanguageSelect,
                    onDoneClick = ::onDone,
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
        viewModel.onLanguagePageAppear()
    }

    /**
     * 点 Done：**先关页面，再后台保存**（对齐 RN 的乐观流）。
     *
     * ⚠️ Toast 在**保存失败时**由状态驱动弹出，而此刻本 Fragment 已经出栈 ——
     * 所以用 `requireActivity().applicationContext` 发 Toast，不依赖本页面
     * 的生命周期。挂在本页面上会因为 view 已销毁而静默丢失，
     * 表现为「保存失败但用户完全不知道」。
     */
    private fun onDone() {
        val submitted = viewModel.onLanguageDone()
        if (!submitted) return
        observeSaveFailure()
        popSelf()
    }

    /**
     * 订阅一次保存失败。
     *
     * 用 Activity 的 lifecycleScope（本页面即将出栈），只取第一个非空错误
     * 然后自行取消 —— 常驻订阅会在下次进页面时把旧错误再弹一遍。
     */
    private fun observeSaveFailure() {
        val activity = requireActivity()
        val appContext = activity.applicationContext
        activity.lifecycleScope.launch {
            // 等到出现错误就弹一次并清掉标志；页面正常关闭时这个协程会随
            // Activity 结束而取消，不泄漏
            viewModel.state
                .map { it.languageError }
                .filterNotNull()
                .first()
                .let { key ->
                    Toast.makeText(appContext, L10n.t(key), Toast.LENGTH_SHORT).show()
                    viewModel.onLanguageErrorShown()
                }
        }
    }

    private fun popSelf() {
        parentFragmentManager.popBackStack()
    }

    companion object {
        fun newInstance(): LanguageFragment = LanguageFragment()
    }
}
