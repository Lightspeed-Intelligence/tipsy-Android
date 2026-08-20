package ai.lightspeed.tipsy.shell.pages.login

import ai.lightspeed.tipsy.shell.BuildConfig
import ai.lightspeed.tipsy.shell.TipsyApplication
import ai.lightspeed.tipsy.shell.i18n.L10n
import android.annotation.SuppressLint
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

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

    /**
     * 邮箱登录的编排器。用 [viewModels] 而非字段 —— 要跨配置变更存活。
     *
     * 手写 Factory 是因为本工程刻意不引 DI（ADR-005：W1/W2 不引 Hilt）。
     */
    private val viewModel: EmailLoginViewModel by viewModels {
        val app = requireActivity().application as TipsyApplication
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                EmailLoginViewModel(
                    api = EmailLoginApi(
                        baseUrl = BuildConfig.API_BASE_URL,
                        appVersion = BuildConfig.VERSION_NAME,
                        downloadChannel = BuildConfig.DOWNLOAD_CHANNEL,
                        deviceIdProvider = {
                            // ANDROID_ID 对齐 RN 的 `DeviceInfo.getUniqueId()`
                            // （`react-native-device-info` 在 Android 上返回的正是
                            // Settings.Secure.ANDROID_ID）。
                            //
                            // ⚠️ lint 的 HardwareIds 在这里**必须**抑制而非绕开：
                            // 这个值加密后作为 `X-Client-ID` 送给后端风控，两端必须
                            // 算出同一个 ID。换成随机 UUID 或 install id 会让后端
                            // **静默拒绝发码**（且不提示与设备 ID 有关）。
                            //
                            // 用途仅限风控头，不做广告标识、不落库、不上报第三方，
                            // 因此不属于 HardwareIds 要防的隐私滥用场景。
                            @SuppressLint("HardwareIds")
                            val androidId = Settings.Secure.getString(
                                requireContext().contentResolver,
                                Settings.Secure.ANDROID_ID,
                            ).orEmpty()
                            androidId
                        },
                        aesKey = BuildConfig.DEVICE_ID_AES_KEY,
                    ),
                    // 壳是语言的唯一 writer（W1-P5），取当前值即可
                    langCodeProvider = { L10n.current },
                    onLoginSucceeded = { result -> onLoginSucceeded(app, result) },
                ) as T
        }
    }

    /**
     * 登录成功的落地动作。
     *
     * 完整顺序收口在 [TipsyApplication.establishUserSession]：先清上一账号共享快照，
     * 再落 token、拉 `/user/info`、发布完整 `user-storage`，最后才同时广播 RN 与
     * Native loggedIn。不会再出现“有 token 但 Surface 只有一个 JWT userId”的
     * 半登录状态；用户拉取失败则回滚 token，交给本 ViewModel 显示登录失败。
     *
     * ## 本轮到此为止（范围边界）
     *
     * Android 现在已经会拉 `POST /user/info` 并按 `language_code` 切语言；尚未接的
     * 是按 `basicRulesCompleted`/`age`/`onboardingStatus` 决定是否进引导流程
     * （权威判定在 `tipsy-app/src/surfaces/onboardingStage.ts`，那边有单测）。
     * 那套引导由 RN 的 `OnboardingSurface` 承接，壳侧的衔接点
     * （`ShellAuthProvider.notifyOnboardingCompleted`）当前标记为 **W4 未实现**。
     *
     * 所以**新用户走完这里只是拿到了 token**，不会自动进年龄验证 ——
     * 这是已知的分期边界，不是漏实现。
     */
    private suspend fun onLoginSucceeded(app: TipsyApplication, result: EmailLoginApi.LoginResult) {
        app.establishUserSession(result.token)
        Log.i(
            TAG,
            "邮箱登录成功（isNewUser=${result.isNewUser} " +
                "linkedAccounts=${result.linkedAccountCount}）",
        )
        // linkedAccountCount > 1 时 RN 会弹账号合并弹窗，但**仍照存 token**
        // （useUserActon.ts:178-182 开弹窗后没有 return）。壳内该弹窗未实现，
        // 属 W4 范围 —— 登录本身已成立，不阻断。
        parentFragmentManager.popBackStack()
    }

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

        fun publishForTest() {
            lastInsetTopForTest = insetTop
            lastInsetBottomForTest = insetBottom
        }

        fun render() {
            composeView.setContent {
                MaterialTheme {
                    // ⚠️ 邮箱流程的状态**必须放在 Compose / ViewModel 里**，
                    // 不能像 inset 那样放 Fragment 字段 + render() 刷新：
                    // render() 会 setContent 重建整棵组合树，用户每敲一个字
                    // 都会**丢焦点、收键盘**。
                    val emailFlowOpen = rememberSaveable { mutableStateOf(false) }
                    val state by viewModel.state.collectAsState()
                    val errorMessage by viewModel.errorMessage.collectAsState()
                    val focusTick by viewModel.focusCodeRequest.collectAsState()
                    val codeFocus = remember { FocusRequester() }
                    val context = LocalContext.current

                    // 发码成功后聚焦验证码框（RN: requestAnimationFrame + ref.focus）
                    LaunchedEffect(focusTick) {
                        if (focusTick > 0) {
                            // 首帧尚未布局完时 requestFocus 会抛，故容错
                            runCatching { codeFocus.requestFocus() }
                        }
                    }

                    // 错误提示走 Toast（对齐 RN 的 Toast.show，非表单内联）
                    // 后端 msg 已是可展示文案，直接用；兜底串是 i18n key，
                    // 走 L10n 翻译后再显示。
                    //
                    // 这里用 L10n.t 而非 LocalizedText 是有意的：Toast 是一次性副作用，
                    // 不参与重组（语言切换后已弹出的 toast 本就不该改字）。组合内的
                    // 静态文案仍必须用 LocalizedText。
                    LaunchedEffect(errorMessage) {
                        errorMessage?.let { msg ->
                            val text = if (msg == FALLBACK_ERROR_KEY) {
                                L10n.t(FALLBACK_ERROR_KEY)
                            } else {
                                msg
                            }
                            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                            viewModel.consumeError()
                        }
                    }

                    // 键盘高度：驱动 footer 显隐与上方留白收缩。
                    // 用 ime inset 而非自己监听，Compose 会在动画期间连续给值。
                    val keyboardHeightDp = WindowInsets.ime
                        .asPaddingValues()
                        .calculateBottomPadding()
                        .value

                    LoginScreen(
                        downloadChannel = BuildConfig.DOWNLOAD_CHANNEL,
                        onGoogleClick = ::onGoogleClick,
                        onAppleClick = ::onAppleClick,
                        onEmailClick = { emailFlowOpen.value = true },
                        insetTopDp = insetTop,
                        insetBottomDp = insetBottom,
                        keyboardHeightDp = keyboardHeightDp,
                        emailFlowOpen = emailFlowOpen.value,
                        emailState = state,
                        onBackFromEmail = {
                            emailFlowOpen.value = false
                            viewModel.onExitEmailFlow()
                        },
                        onEmailChange = viewModel::onEmailChange,
                        onCodeChange = viewModel::onCodeChange,
                        onSendCode = viewModel::sendCode,
                        onSubmitLogin = viewModel::submitLogin,
                        codeFocusRequester = codeFocus,
                    )
                }
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(composeView) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            insetTop = bars.top / density
            insetBottom = bars.bottom / density
            publishForTest()
            render()
            // 不消费 —— 上层容器可能也要用（如 Surface 容器的键盘避让）
            insets
        }

        // ⚠️ inset 必须用 rootWindowInsets 兜底读一次，不能只依赖监听器。
        //
        // 窗口的 inset 派发在 Activity 起来时就发生过了。本 Fragment 的视图是
        // **之后**才 attach 的，`setOnApplyWindowInsetsListener` 装上时那次派发
        // 已经过去 —— 实测监听器**一次都没触发**（加日志确认：`onCreateView 进入`
        // 打了，inset 那行没打）。
        //
        // 表现：条款文字压在导航栏下面（底部 padding 恒为 0），**没有任何报错**。
        //
        // 试过 `addOnAttachStateChangeListener` + `requestApplyInsets`，无效 ——
        // 装监听器时 view 往往已经 attach 完了，回调不会再来。
        // 可靠做法是 attach 后直接读 `rootWindowInsets`；监听器只负责后续变化
        // （旋屏、键盘、导航栏模式切换）。
        composeView.doOnAttach { view ->
            ViewCompat.getRootWindowInsets(view)?.let { insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val density = resources.displayMetrics.density
                insetTop = bars.top / density
                insetBottom = bars.bottom / density
                publishForTest()
                render()
            }
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

    /**
     * 最近一次到达的 inset（dp），**仅供 [LoginInsetTest] 断言「inset 是否到达」**。
     *
     * 生产代码不读它 —— 布局值走 [LoginScreen] 的参数。留这两个字段是因为
     * inset 失效是**静默**的（不崩不报错），没有可观测出口就只能靠人眼比对
     * 真机截图发现。初始值 0 正是失效时的状态，所以测试断言 `> 0`。
     */
    @Volatile
    var lastInsetTopForTest: Float = 0f
        private set

    @Volatile
    var lastInsetBottomForTest: Float = 0f
        private set

    companion object {
        private const val TAG = "LoginFragment"

        fun newInstance(): LoginFragment = LoginFragment()
    }
}
