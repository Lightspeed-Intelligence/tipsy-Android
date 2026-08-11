package ai.lightspeed.tipsy.shell.pages.login

import ai.lightspeed.tipsy.shell.BuildConfig
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment

/**
 * 原生登录页的 Fragment 宿主（方案 ADR-002：Fragment + [ComposeView]）。
 *
 * 目录对齐 iOS 壳的 `Pages/Login/`。
 *
 * ## 为什么 inset 从这里取而不是在 Compose 里
 *
 * [LoginLayout] 的计算要能单测，所以它接收纯数字。inset 的读取是平台细节，
 * 留在 View 层：用 `ViewCompat.setOnApplyWindowInsetsListener` 而不是
 * Compose 的 `WindowInsets` —— 后者在 `ComposeView` 嵌在 Fragment 里时
 * 需要额外配置才生效，而这里本来就要把值传给纯函数。
 */
class LoginFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val composeView = ComposeView(requireContext()).apply {
            // Fragment 里必须设它 —— 默认策略在 Fragment 视图销毁时不释放组合，
            // 会让 Compose 状态泄漏到下一次 onCreateView
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }

        var insetTop = 0f
        var insetBottom = 0f

        fun render() {
            composeView.setContent {
                MaterialTheme {
                    LoginScreen(
                        downloadChannel = BuildConfig.DOWNLOAD_CHANNEL,
                        onGoogleClick = ::onGoogleClick,
                        onAppleClick = ::onAppleClick,
                        onEmailClick = ::onEmailClick,
                        insetTopDp = insetTop,
                        insetBottomDp = insetBottom,
                    )
                }
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(composeView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            insetTop = bars.top / density
            insetBottom = bars.bottom / density
            render()
            // 不消费 —— 上层容器可能也要用（如 Surface 容器的键盘避让）
            insets
        }

        render()
        return composeView
    }

    /**
     * Google 登录。
     *
     * ⚠️ **当前无法工作，且这是外部阻塞而非实现缺失**（开放问题 §12.8）：
     * `/login/firebase` 要先经 Firebase Auth 拿 idToken，而
     * - 壳工程**没有** `google-services.json`、没有 google-services Gradle 插件
     * - 更关键：`conf/google-services.prod.json` 里四个包名共登记 12 个证书指纹，
     *   **壳的 debug keystore SHA-1（`680F515E…`）一个都不在其中**
     *
     * 指纹没登记时 Google Sign-In 直接失败（典型 `ApiException: 10
     * DEVELOPER_ERROR`），且**报错完全不提指纹**。所以这里刻意只记日志：
     * 接一半的 Firebase 代码会让人误以为「实现有 bug」，而真实原因是缺登记。
     *
     * **重启条件**：拿到三个 applicationId × debug/release 的指纹登记。
     */
    private fun onGoogleClick() {
        Log.w(TAG, "Google 登录未接通：缺 Firebase 签名指纹登记（开放问题 §12.8）")
    }

    /**
     * Apple 登录。
     *
     * ⚠️ 除了与 Google 相同的 Firebase 前置，还有一个**产品未决项**
     * （开放问题 §12.9）：`LoginSocialButtons.tsx` 与 `LoginScreen.tsx`
     * **都没有 `Platform.OS` 门控**（已核实），所以现网 Android 包是否真的
     * 显示 Apple 登录、壳内该保留还是隐藏，需产品确认。
     *
     * 本轮**照 RN 现状保留按钮**（截图里它确实在），但点击不接通。
     */
    private fun onAppleClick() {
        Log.w(TAG, "Apple 登录未接通：缺 Firebase 前置 + 展示策略未定（开放问题 §12.9）")
    }

    /**
     * 邮箱验证码登录。
     *
     * 这条链**不依赖 Firebase 也不依赖指纹**（纯 HTTP：
     * `/login/email/send_code` → `/login/email`），是本页唯一今天就能端到端
     * 验证的路径。表单与验证码页属下一步，此处先留入口。
     */
    private fun onEmailClick() {
        Log.i(TAG, "邮箱登录入口被点击 —— 表单页待实现")
    }

    companion object {
        private const val TAG = "LoginFragment"

        fun newInstance(): LoginFragment = LoginFragment()
    }
}
