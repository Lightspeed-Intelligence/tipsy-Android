package ai.lightspeed.tipsy.shell.pages.chatlist

import androidx.compose.ui.graphics.Color

/**
 * Map（時光長廊）的视觉常量（W3-P2）。
 *
 * 数值来自 RN `ChatItem.tsx:228-296` 与 `ChatMap.tsx`，
 * **不是照 iOS 端口** —— 见 [placeholderFill] 那条。
 */
internal object ChatMapStyle {

    // ── 卡片 ────────────────────────────────────────────────

    /** 卡片圆角 4dp（`ChatItem.tsx:236` `borderRadius: 4`）。 */
    const val CARD_CORNER_DP = 4

    /** 卡片长宽比 0.75（宽/高，`:230` `aspectRatio: 0.75`）。 */
    const val CARD_ASPECT_RATIO = 0.75f

    /** 卡面底部信息区内边距 10dp（`:251` `padding: 10`）。 */
    const val CARD_BOTTOM_PADDING_DP = 10

    /** 名称 13sp / 白 50%（`:253-256`）。⚠️ `maxWidth: 100` 也在那里。 */
    const val NAME_FONT_SP = 13
    const val NAME_MAX_WIDTH_DP = 100

    /** 消息数与时间 12sp / 白 50%（`:264-271`）。 */
    const val META_FONT_SP = 12

    /** 分隔竖线 1×6dp / 白 50%（`:272-276`）。 */
    const val SPLIT_LINE_WIDTH_DP = 1
    const val SPLIT_LINE_HEIGHT_DP = 6

    /**
     * story 标的**三色横向渐变**（`ChatItem.tsx:188-195`）——
     * ⚠️ 不是纯色：`rgba(157,79,131,0.7)` → `rgba(148,77,64,0.85)`
     * → `rgba(127,147,54,0.75)`，`start=(0,0)` `end=(1,0)` 即水平方向。
     */
    val storyTagGradient = listOf(
        Color(0xB39D4F83),
        Color(0xD9944D40),
        Color(0xBF7F9336),
    )

    /** story 标 9sp / 白 70%，圆角胶囊（`:282-295`）。 */
    const val STORY_TAG_FONT_SP = 9
    const val STORY_TAG_H_PADDING_DP = 8
    const val STORY_TAG_MIN_HEIGHT_DP = 14

    /** story 标圆角 999 → 完全胶囊（`:284`）。 */
    const val STORY_TAG_CORNER_DP = 999

    /** 消息数图标 12×12（`ChatItem.tsx:205-210`）。 */
    const val MESSAGE_ICON_SIZE_DP = 12

    /**
     * 未读红点（`ChatItem.tsx:240-245`）：`#F35757`、圆角 4、8dp。
     *
     * ⚠️ **它是 `wrapper` 的直接子节点、在被裁剪的 `chatItem` 之外**
     * （`:155-158`）—— 所以能**跨出右上角**（`top: -size/2`）。
     * 放进裁剪容器里会被 `clip` 切掉一半，看起来只是"点小了一点"。
     */
    val unreadDotColor = Color(0xFFF35757)

    /** 红点直径 8dp（`:135` `const size = 8 * scale`）。 */
    const val UNREAD_DOT_SIZE_DP = 8

    /**
     * 红点相对卡片**右上角**的偏移（Compose `Alignment.TopEnd` 坐标系）。
     *
     * ## ⚠️ 两个坐标系的符号是反的，别直接搬 RN 的数
     *
     * RN（CSS 绝对定位，`ChatItem.tsx:138-142`）：
     * ```
     * top:   -offset            = -4   // 负 = 向上越出
     * right: -offset + leftOffset = -2   // 负 = 向右越出
     * ```
     * CSS 的 `right` 是「距右边缘的距离」，**负值表示越出**。
     *
     * Compose 的 `Alignment.TopEnd` + `offset(x)` 里，
     * **x 为正才向右外移**、负值往卡内缩。所以：
     *
     * | 轴 | RN | Compose |
     * | --- | --- | --- |
     * | 竖直 | `top: -4` | `y = -4.dp`（同号，都是向上） |
     * | 水平 | `right: -2` | **`x = +2.dp`**（**反号**） |
     *
     * 我第一版直接抄了 RN 的 `-2`，结果红点缩回卡内 —— 而它已经不被 clip 了，
     * 所以看起来"就在角上"，只有和 RN 并排比才看得出差 4dp。
     * iOS 端口的 `ChatMapCardView` trailing offset 也是 `+2`，与这里同号。
     */
    const val UNREAD_DOT_OFFSET_X_DP = 2
    const val UNREAD_DOT_OFFSET_Y_DP = -(UNREAD_DOT_SIZE_DP / 2) // -4

    /** 卡面文字：白 50%。 */
    val cardTextColor = Color(0x80FFFFFF)

    /** story 标文字：白 70%。 */
    val storyTagTextColor = Color(0xB3FFFFFF)

    /** 背卡底色 `rgba(0,0,0,0.5)`（`:277-281`）。 */
    val backCardFill = Color(0x80000000)

    // ── 占位卡 ──────────────────────────────────────────────

    /**
     * 占位卡底色 **`rgba(0,0,0,0.9)`** —— ⚠️ **不做毛玻璃**。
     *
     * RN 用 `<TipsyBlurView intensity={30}>`（`ChatMap.tsx:239`），iOS 端口据此
     * 实现了毛玻璃。但 `TipsyBlurView.tsx:11-13` 在 **Android 直接短路**：
     *
     * ```tsx
     * if (Platform.OS === 'android') {
     *   return <View style={[styles.blurView, style]}>{children}</View>
     * }
     * ```
     *
     * 且底色两端不同（`:26-29`）：iOS `rgba(55,55,55,0.1)`、
     * **Android `rgba(0,0,0,0.9)`**。所以 Android 侧是**近黑实心卡 + 剪影**，
     * `intensity={30}` 在 Android 上是死参数。
     *
     * 照 iOS 做要接 `RenderEffect`（还得处理 minSdk 24 的降级），
     * **做出来还是错的**，而且 0.1 vs 0.9 的差异很显眼。
     * 同型先例：`ShellTabBar` 的注释也记了 tabbar 在 Android 是纯色而非 blur。
     */
    val placeholderFill = Color(0xE6000000)

    /**
     * 占位卡圆角 **8dp**（`ChatMap.tsx:240` `borderRadius: 8`）——
     * ⚠️ **与真实卡的 4dp 不同**，别复用 [CARD_CORNER_DP]。
     */
    const val PLACEHOLDER_CORNER_DP = 8

    // ── 楼层 ────────────────────────────────────────────────

    /** 楼层标题 15sp / 白 70%，居中（iOS 端口 `ChatMapFloorView.swift:66-69`）。 */
    const val FLOOR_TITLE_FONT_SP = 15
    const val FLOOR_TITLE_HEIGHT_DP = 18
    const val FLOOR_TITLE_BOTTOM_GAP_DP = 10

    val floorTitleColor = Color(0xB3FFFFFF)
}
