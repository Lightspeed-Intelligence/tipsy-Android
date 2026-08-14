package ai.lightspeed.tipsy.shell.tabs

import ai.lightspeed.tipsy.shell.ui.s
import ai.lightspeed.tipsy.shell.ui.sSp
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextAlign
import androidx.fragment.app.Fragment

/**
 * 尚未迁移的 Tab 的占位页。
 *
 * ## 为什么要有明确文案，而不是留白
 *
 * 空白页与「挂载失败」在视觉上无法区分 —— 真机验收时会浪费时间去查是不是
 * Fragment 没起来。这里明确写出「哪个页面、属哪一波」。
 *
 * 文案**刻意不走 i18n**：它不是产品文案，是给开发/QA 看的状态说明，
 * 而且这几个页面上线前必然被真实实现替掉。给它导 26 个语言的词条是浪费。
 *
 * ## ⚠️ 当前**没有调用方**（2026-08-14，Screen W4-P1 起）
 *
 * 五个 Tab 都已有真实页面：Home（§2.23）/ Screen（§2.35）/ ChatList（§2.30）
 * / Profile（§2.25）/ Create（伪 Tab，不建 Fragment）。
 *
 * **刻意保留不删**：W4 还有若干页面要接（Letter / GemsSubscription 等），
 * 届时若某个入口的目标页未就绪，这个占位比空白或崩溃都好。
 * 若到上线前仍无人用，那时再删。
 */
class TabPlaceholderFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        val tabName = arguments?.getString(ARG_TAB_NAME).orEmpty()
        val wave = arguments?.getString(ARG_WAVE).orEmpty()
        setContent {
            MaterialTheme {
                Placeholder(tabName = tabName, wave = wave)
            }
        }
    }

    companion object {
        private const val ARG_TAB_NAME = "tabName"
        private const val ARG_WAVE = "wave"

        fun newInstance(tabName: String, wave: String): TabPlaceholderFragment =
            TabPlaceholderFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TAB_NAME, tabName)
                    putString(ARG_WAVE, wave)
                }
            }
    }
}

@Composable
private fun Placeholder(tabName: String, wave: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.s),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = tabName,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 20.sSp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "此页属 $wave，尚未迁移",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            fontSize = 14.sSp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.s),
        )
    }
}
