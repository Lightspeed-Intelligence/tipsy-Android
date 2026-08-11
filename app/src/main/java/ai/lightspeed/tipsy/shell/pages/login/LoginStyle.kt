package ai.lightspeed.tipsy.shell.pages.login

import androidx.compose.ui.graphics.Color

/**
 * 登录页样式常量（对齐 RN `src/login/LoginScreen.tsx` +
 * `components/LoginSocialButtons.tsx` 的 `ScaledSheet` 值）。
 *
 * 与 iOS 壳的 `LoginControls.swift` 里的 `LoginStyle` 同构 —— 三端同一批数值。
 *
 * ## 每个值都标了 RN 出处
 *
 * **改这里前先去 RN 侧核对。** 样式偏差不会报错，只会让两端并排看时不一样，
 * 而这类偏差通常没人报（用户不会同时装两个版本）。
 *
 * ## ⚠️ 尺寸都要经 `.s` 缩放
 *
 * 本文件只存**设计稿原值**（Int），使用处必须写 `LoginStyle.CONTROL_HEIGHT.s`。
 * 直接当 dp 用会让大屏上控件偏小，详见 `ScaledMetrics` 类注释。
 */
object LoginStyle {

    // ── 颜色（`ScaledSheet` 不缩放颜色，原样照抄）──────────────

    /** 页面底色 `#180201`（`LoginScreen.tsx:639` drawerBackground）。 */
    val BACKGROUND = Color(0xFF180201)

    /** 控件底 `rgba(255,255,255,0.1)`（`LoginSocialButtons.tsx:143` button）。 */
    val CONTROL_FILL = Color(0x1AFFFFFF)

    /** 主按钮激活态 `#9C4844`（`LoginScreen.tsx:704` footerLoginBtnActive）。 */
    val CONTROL_ACTIVE = Color(0xFF9C4844)

    /** 按钮文案 `rgba(255,255,255,0.8)`（`LoginSocialButtons.tsx:151` buttonText）。 */
    val TEXT_PRIMARY = Color(0xCCFFFFFF)

    /** 次要文案 `rgba(255,255,255,0.6)`（`:155` continueText / 条款文案）。 */
    val TEXT_SECONDARY = Color(0x99FFFFFF)

    /** 条款链接 `#F3A231`（`LoginScreen.tsx:723` link）。 */
    val LINK = Color(0xFFF3A231)

    /** 返回按钮底 `rgba(255,255,255,0.05)`（`LoginScreen.tsx:682` backButton）。 */
    val BACK_BUTTON_FILL = Color(0x0DFFFFFF)

    // ── 尺寸（设计稿原值，使用处加 `.s`）─────────────────────

    /** 控件高 48（`LoginSocialButtons.tsx:145` / `LoginScreen.tsx:699`）。 */
    const val CONTROL_HEIGHT = 48

    /**
     * 控件圆角 42（`:146` / `:698`）。
     *
     * ⚠️ 比高度一半（24）大得多 —— RN 会自动收口成胶囊。Compose 的
     * `RoundedCornerShape` 同样会被高度限制，所以直接用 42 与 RN 表现一致。
     * iOS 侧要显式 `min(radius, height/2)`（`LoginControls.swift:40`），
     * Compose 不需要。
     */
    const val CONTROL_RADIUS = 42

    /** 页面左右内边距 24（`LoginScreen.tsx:645` container paddingHorizontal）。 */
    const val PAGE_HORIZONTAL_PADDING = 24

    /** 社交按钮图标 32（`LoginSocialButtons.tsx:132` icon）。 */
    const val ICON_SIZE = 32

    /** 图标与文案间距 8（`:147` button gap）。 */
    const val ICON_TEXT_GAP = 8

    /** 按钮之间默认间距 12（`LoginSocialButtons.tsx` 的 `buttonGap` 默认值）。 */
    const val BUTTON_GAP = 12

    /** 按钮文案字号 14（`:150`）。 */
    const val TEXT_SIZE_BUTTON = 14

    /** 条款/次要文案字号 12（`LoginScreen.tsx:717` termsText）。 */
    const val TEXT_SIZE_TERMS = 12

    /** 条款行高 20（`:718`）。 */
    const val LINE_HEIGHT_TERMS = 20

    /** 返回按钮 52x32、圆角 16（`LoginScreen.tsx:679-681`）。 */
    const val BACK_BUTTON_WIDTH = 52
    const val BACK_BUTTON_HEIGHT = 32
    const val BACK_BUTTON_RADIUS = 16

    // ── 纵向布局（`LoginScreen.tsx:138-172` 的 spacing）─────────
    //
    // RN 按 812 设计稿高算一个 ratio 再乘各段高度。这套逻辑在
    // `LoginLayout` 里逐行复刻 —— 它不是简单缩放，clamp 边界都有意义。

    /** `DESIGN_HEIGHT`（`:55`）。 */
    const val DESIGN_HEIGHT = 812f

    /** ratio 的上下限（`:143`）。 */
    const val RATIO_MIN = 0.85f
    const val RATIO_MAX = 1.15f

    /** logo 尺寸：`min(max(156 * ratio, 148), 187)`（`:146`）。 */
    const val LOGO_BASE = 156f
    const val LOGO_MIN = 148f
    const val LOGO_MAX = 187f

    /** 表单区高：`max(214, 248 * ratio)`（`:147`）。 */
    const val FORM_BASE = 248f
    const val FORM_MIN = 214f

    /** 底部区高：`max(112, 120 * ratio)`（`:148`）。 */
    const val BOTTOM_BASE = 120f
    const val BOTTOM_MIN = 112f

    /** 社交按钮组与上方的间距：`32 * ratio`（`:166`）。 */
    const val SOCIAL_GAP_BASE = 32f

    /** 条款与上方的间距：`24 * ratio`（`:170`）。 */
    const val TERMS_TOP_GAP_BASE = 24f
}
