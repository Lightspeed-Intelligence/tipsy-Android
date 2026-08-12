package ai.lightspeed.tipsy.shell.pages.profile

import androidx.compose.ui.graphics.Color

/**
 * Profile 的视觉常量（逐条对着 RN 实测值取，同 `HomeStyle` 的做法）。
 *
 * 数值不要"就近取整"—— 三列网格那 2px 总间距和 0.6 宽高比是精确值，
 * 改了会让卡片错位或与现网肉眼可辨的不同。
 */
object ProfileStyle {

    /** 昵称等主文本。 */
    val TEXT_PRIMARY = Color(0xFFFFFFFF)

    /** 统计数字下方的标签（`FollowInfo.tsx:116` `rgba(255,255,255,0.6)`）。 */
    val TEXT_SECONDARY = Color(0x99FFFFFF)

    /** UID 文本（比标签更淡）。 */
    val TEXT_TERTIARY = Color(0x80FFFFFF)

    /** 卡片占位底色（沿用 `HomeStyle.CARD_PLACEHOLDER`，同一套卡片视觉）。 */
    val CARD_PLACEHOLDER = Color(0xFF2B1817)

    /** 页面底色（与 Home 同一个 app 背景）。 */
    val APP_BACKGROUND = Color(0xFF34212A)

    /** `Edit Profile` 按钮描边。 */
    val BUTTON_BORDER = Color(0x33FFFFFF)

    /** 卡片标题。 */
    val CARD_TITLE = Color(0xFFFFFFFF)

    // ── 尺寸（dp）──────────────────────────────────

    /** 头像 65dp（`TipsyAvatar.tsx:126-127`）。 */
    const val AVATAR_SIZE = 65

    /** 三列网格（`CharacterGrid.tsx:1220` `numColumns` + 手工 chunk 成三列）。 */
    const val COLUMN_COUNT = 3

    /**
     * 卡片宽高比 **0.6**（`CharacterGrid.tsx:1671` `aspectRatio: 0.6`）。
     *
     * Compose 的 `aspectRatio` 语义与 RN 一致（宽/高），直接用这个值。
     */
    const val CARD_ASPECT_RATIO = 0.6f

    /**
     * 列间距 —— RN 是每列 `2/3` dp 的 margin，三列总计约 2dp
     * （`CharacterGrid.tsx:579` `maxWidth: (width - 2) / 3`）。
     *
     * Compose 用 `Arrangement.spacedBy(1.dp)` 近似：两条缝各 1dp = 2dp 总间距，
     * 与 RN 的总和一致。逐列写 2/3dp 在 Compose 里没有对应表达，
     * 且亚像素差异肉眼不可辨 —— 保总间距一致比逐列复刻更实际。
     */
    const val GRID_SPACING = 1

    /** 统计区左右边距（`FollowInfo.tsx:96-97`）。 */
    const val STATS_HORIZONTAL_PADDING = 15

    /** 统计项之间的间距（`FollowInfo.tsx:106`）。 */
    const val STATS_ITEM_GAP = 10

    /** 数字与标签之间（`FollowInfo.tsx:107` `gap: 8`）。 */
    const val STATS_LABEL_GAP = 8

    /** 统计数字字号（`FollowInfo.tsx:110`）。 */
    const val STATS_COUNT_FONT = 16

    /** 统计标签字号（`FollowInfo.tsx:115`）。 */
    const val STATS_LABEL_FONT = 12
}
