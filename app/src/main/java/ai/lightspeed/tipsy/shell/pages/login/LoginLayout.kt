package ai.lightspeed.tipsy.shell.pages.login

/**
 * 登录页纵向布局计算（**逐行复刻** RN `LoginScreen.tsx:138-172` 的 `spacing`）。
 *
 * ## 为什么单独抽出来
 *
 * 这段不是简单的按比例缩放 —— 每个 clamp 边界都有具体作用：
 * - logo 有**上下双限** `min(max(156r, 148), 187)`：小屏不至于太小、大屏不至于
 *   顶掉按钮区
 * - 表单与底部区只有**下限** `max(214, 248r)`：小屏优先保证内容不被压扁
 * - ratio 自身 clamp 在 `[0.85, 1.15]`：极端长宽比（折叠屏展开、分屏）下
 *   不让间距失控
 *
 * 抽成纯函数是为了**可单测**。iOS 侧这段逻辑散在 `LoginViewController` 的
 * 约束里，没法单独验；Android 从一开始分开。
 *
 * ## ⚠️ 这里的输出是「设计稿 dp 值」，不再经 `.s` 缩放
 *
 * RN 侧这段用的是 `windowHeight` / `insets` 这些**已经是 dp 的实测值**，
 * 与 `ScaledSheet`（按宽度缩放设计稿常量）是**两套独立机制**，不叠加。
 * 把输出再乘一次 scaleFactor 会让大屏上纵向间距翻倍。
 */
object LoginLayout {

    /**
     * @param windowHeightDp 窗口高（dp）
     * @param insetTopDp 状态栏 inset（dp）
     * @param insetBottomDp 导航栏 inset（dp）
     * @param keyboardHeightDp 键盘高（dp），0 表示未弹出
     */
    data class Spacing(
        val containerTop: Float,
        val containerBottom: Float,
        val logoSize: Float,
        val formHeight: Float,
        val bottomHeight: Float,
        val keyboardTopGap: Float,
        val keyboardFormGap: Float,
        val socialGap: Float,
        val termsTopGap: Float,
        /** 键盘是否处于 docked 状态 —— 影响是否用弹性留白。 */
        val isKeyboardDocked: Boolean,
    )

    /**
     * 悬浮键盘（小窗输入法）高度通常 < 100dp，此时**不做布局调整**
     * （RN `:136` 的原注释：「悬浮键盘 keyboardHeight 通常 < 100，
     * 此时不需要额外处理」）。
     */
    const val DOCKED_KEYBOARD_MIN_HEIGHT = 100f

    fun compute(
        windowHeightDp: Float,
        insetTopDp: Float,
        insetBottomDp: Float,
        keyboardHeightDp: Float = 0f,
    ): Spacing {
        val insetBottom = maxOf(insetBottomDp, 0f)
        val isKeyboardDocked = keyboardHeightDp > DOCKED_KEYBOARD_MIN_HEIGHT

        // RN `:139-142`：safeViewport 至少 1，避免除零
        val safeViewport = maxOf(1f, windowHeightDp - insetTopDp - insetBottom)
        val ratio = minOf(
            maxOf(safeViewport / LoginStyle.DESIGN_HEIGHT, LoginStyle.RATIO_MIN),
            LoginStyle.RATIO_MAX,
        )

        // `:145` normalBottom = max(16, insets.bottom + 8)
        val normalBottom = maxOf(16f, insetBottom + 8f)

        // `:146` logo 上下双限
        val logoSize = minOf(
            maxOf(LoginStyle.LOGO_BASE * ratio, LoginStyle.LOGO_MIN),
            LoginStyle.LOGO_MAX,
        ).roundToWhole()

        // `:147-148` 表单与底部区只有下限
        val formHeight = maxOf(LoginStyle.FORM_MIN, LoginStyle.FORM_BASE * ratio).roundToWhole()
        val bottomHeight =
            maxOf(LoginStyle.BOTTOM_MIN, LoginStyle.BOTTOM_BASE * ratio).roundToWhole()

        val keyboardFormGap = (16f * ratio).roundToWhole()

        // `:150-157` 键盘弹出时，logo 上方留白 = 可视高 - logo - gap - 表单
        val keyboardVisibleHeight =
            windowHeightDp - keyboardHeightDp - insetTopDp - insetBottom
        val keyboardTopGap = maxOf(
            0f,
            (keyboardVisibleHeight - logoSize - keyboardFormGap - formHeight).roundToWhole(),
        )

        return Spacing(
            containerTop = insetTopDp,
            // `:160` 键盘弹出时不留底部间距 —— 否则表单被推出屏幕
            containerBottom = if (isKeyboardDocked) 0f else normalBottom,
            logoSize = logoSize,
            formHeight = formHeight,
            // `:164` 同上，键盘弹出时底部区整体收掉
            bottomHeight = if (isKeyboardDocked) 0f else bottomHeight,
            keyboardTopGap = keyboardTopGap,
            keyboardFormGap = keyboardFormGap,
            socialGap = (LoginStyle.SOCIAL_GAP_BASE * ratio).roundToWhole(),
            termsTopGap = (LoginStyle.TERMS_TOP_GAP_BASE * ratio).roundToWhole(),
            isKeyboardDocked = isKeyboardDocked,
        )
    }

    /** RN 用 `Math.round`，Kotlin 的 `roundToInt` 对 .5 的处理一致（向上）。 */
    private fun Float.roundToWhole(): Float = kotlin.math.round(this)
}
