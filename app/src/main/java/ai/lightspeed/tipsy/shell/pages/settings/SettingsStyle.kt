package ai.lightspeed.tipsy.shell.pages.settings

import androidx.compose.ui.graphics.Color

/**
 * Settings 与语言页的视觉常量（逐条对着 `TipsyCell.tsx` / `page.tsx` /
 * `language.tsx` 的实测值取，同 `HomeStyle` / `ProfileStyle` 的做法）。
 */
object SettingsStyle {

    /** 页面底色（与 Home/Profile 同一个 app 背景）。 */
    val APP_BACKGROUND = Color(0xFF34212A)

    /** 行标题（`TipsyCell.tsx:96` `white`）。 */
    val TEXT_PRIMARY = Color(0xFFFFFFFF)

    /** 次要文本（`TipsyCell.tsx:112` `rgba(255,255,255,0.7)`）。 */
    val TEXT_SECONDARY = Color(0xB3FFFFFF)

    /**
     * Done 不可点时的文案色。
     *
     * ⚠️ 与 RN 有意不同：RN 的 `doneText` 恒白，不可点时**没有视觉反馈**
     * （`language.tsx:29` 的 `onDone` 直接 return）。壳给一档 alpha ——
     * 「按钮在那儿但点不动且看不出为什么」是可发现性问题，属可接受的视觉 diff。
     */
    val TEXT_DISABLED = Color(0x66FFFFFF)

    /** Group 容器底色（`TipsyCell.tsx:162` `rgba(255,255,255,0.05)`）。 */
    val GROUP_BACKGROUND = Color(0x0DFFFFFF)

    /** 行间细线（`TipsyCell.tsx:168`，与 Group 底同值）。 */
    val DIVIDER = Color(0x0DFFFFFF)

    /** 开关打开时的轨道色（对齐现网品牌粉）。 */
    val SWITCH_ON = Color(0xFFE24A6E)

    // ── 尺寸（dp / sp）─────────────────────────────

    /** 顶栏高度（`TipsyHeader` 实测）。 */
    const val HEADER_HEIGHT = 44
    const val HEADER_FONT = 16
    const val BACK_ICON_SIZE = 24

    /** 列表外边距（`language.tsx:88` / `page.tsx` 的 `margin: 10`）。 */
    const val LIST_MARGIN = 10

    /** Group 圆角（`TipsyCell.tsx:163`）。 */
    const val GROUP_RADIUS = 8

    /** 行最小高度（`TipsyCell.tsx:83` `minHeight: 50`）。 */
    const val CELL_MIN_HEIGHT = 50

    /** 行左右内边距（`TipsyCell.tsx:84` `paddingHorizontal: 10`）。 */
    const val CELL_H_PADDING = 10

    /** 行上下内边距（`TipsyCell.tsx:85` `paddingVertical: 12`）。 */
    const val CELL_V_PADDING = 12

    /** 行标题字号（`TipsyCell.tsx:95` `fontSize: 13`）。 */
    const val CELL_TITLE_FONT = 13

    /** 语言行右内边距（`language.tsx:91` `paddingRight: 16`）。 */
    const val LANGUAGE_CELL_END_PADDING = 16

    /** 右向箭头 / 展开箭头（`TipsyCell.tsx:70` 24×24）。 */
    const val ARROW_SIZE = 24

    /** 选中勾（`language.tsx:67` 24×24）。 */
    const val CHECK_SIZE = 24

    /** Done 文案字号（`language.tsx:100` `fontSize: 16`）。 */
    const val DONE_FONT = 16

    const val LOGOUT_TOP_GAP = 12
    const val LOGOUT_RADIUS = 8
    const val LOGOUT_V_PADDING = 14
}
