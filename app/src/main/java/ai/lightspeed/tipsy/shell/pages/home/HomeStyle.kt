package ai.lightspeed.tipsy.shell.pages.home

import androidx.compose.ui.graphics.Color

/**
 * Home 页样式常量（对齐 RN `home.tsx` / `HomeHeader.tsx` / `HomeCard.tsx` 的
 * `ScaledSheet` 值）。同 `LoginStyle` 的约定：这里只存**设计稿原值**，
 * 使用处必须写 `.s` 缩放（见 `ScaledMetrics` 类注释）。
 *
 * **改这里前先去 RN 侧核对。** 样式偏差不报错，只在两端并排看时才显形。
 */
object HomeStyle {

    // ── 颜色 ────────────────────────────────────────────────

    /** 系列 Tab 选中底色 `#AD403B`（`constants/colors.ts:1` TIPSY_PRIMARY_COLOR）。 */
    val SERIES_SELECTED = Color(0xFFAD403B)

    /** 系列 Tab 未选中底 `rgba(255,255,255,0.05)`（`home.tsx:2274`）。 */
    val SERIES_UNSELECTED = Color(0x0DFFFFFF)

    /** 未选中文字 `rgba(255,255,255,0.5)`（`:2284`）。 */
    val SERIES_TEXT = Color(0x80FFFFFF)

    /** 选中文字纯白（`:2288`）。 */
    val SERIES_TEXT_SELECTED = Color(0xFFFFFFFF)

    /** 搜索框底 `rgba(255,255,255,0.05)`（`HomeHeader.tsx:198`）。 */
    val SEARCH_FILL = Color(0x0DFFFFFF)

    /** 搜索框占位文字 `rgba(255,255,255,0.5)`（`:207`）。 */
    val SEARCH_PLACEHOLDER = Color(0x80FFFFFF)

    /** 性别按钮文字纯白（`:222`）。 */
    val HEADER_TEXT = Color(0xFFFFFFFF)

    /** 筛选按钮底 `rgba(0,0,0,0.1)` + opacity 0.5（`home.tsx:2240-2242`）。 */
    val FILTER_FILL = Color(0x1A000000)

    /** 红点 `#FF3B30`（`:2251`）。 */
    val BADGE_DOT = Color(0xFFFF3B30)

    /** 卡片名字纯白（`HomeCard.tsx:445`）。 */
    val CARD_TITLE = Color(0xFFFFFFFF)

    /** 卡片简介 `rgba(255,255,255,0.4)`（`:455`）。 */
    val CARD_SUBTITLE = Color(0x66FFFFFF)

    /** 创作者名 `#fff`（`:463`）。 */
    val CARD_CREATOR = Color(0xFFFFFFFF)

    /** 卡片占位底 `#2B1817`（`home.tsx:2222` bannerLoadingPlaceholder）。 */
    val CARD_PLACEHOLDER = Color(0xFF2B1817)

    /** 性别下拉面板描边 `rgba(255,255,255,0.2)`（`HomeHeader.tsx:249`）。 */
    val PANEL_BORDER = Color(0x33FFFFFF)

    /** 面板行分隔线 `rgba(255,255,255,0.05)`（`:257`）。 */
    val PANEL_DIVIDER = Color(0x0DFFFFFF)

    /**
     * 性别面板底色。
     *
     * ⚠️ RN 在这里**按平台给不同的 alpha**（`HomeHeader.tsx:77-92`）：
     * Android 用 0.97（近乎不透明），iOS 用 0.75/0.85/0.7。
     * 照 iOS 的值做会让面板在 Android 上过于透明、文字压在卡片上难读。
     * 三段对角渐变，这里取 Android 那组。
     */
    val PANEL_GRADIENT_ANDROID = listOf(
        Color(0xF7303229),
        Color(0xF73D2C29),
        Color(0xF7432E3C),
    )

    /** App 全局背景底色 `#34212A`（`AppBackground.tsx` styles.container）。 */
    val APP_BACKGROUND = Color(0xFF34212A)

    // ── 筛选抽屉（`HomeFilterDrawer.tsx` + `TipsyDrawer.tsx`）──

    /** 遮罩 `rgba(0,0,0,0.6)`（`TipsyDrawer.tsx:428`）。 */
    val DRAWER_SCRIM = Color(0x99000000)

    /** 标签 chip 底 `rgba(255,255,255,0.04)`（`HomeFilterDrawer.tsx:303`）。 */
    val TAG_FILL = Color(0x0AFFFFFF)

    /**
     * 选中标签底 `#AD403B`（`TIPSY_PRIMARY_COLOR`，`constants/colors.ts:1`）。
     *
     * ⚠️ 这是**品牌主色**，不是 Home 专用色。别就近取个相近的红 ——
     * 与 RN 差一点点在并排对比时看得出来。
     */
    val TAG_FILL_SELECTED = Color(0xFFAD403B)

    /** 标签文字与标题纯白（`:294` / `:318`）。 */
    val DRAWER_TEXT = Color(0xFFFFFFFF)

    /** header 里 `|` 分隔符 `#999`（`:299`）。 */
    val DRAWER_SEPARATOR = Color(0xFF999999)

    // ── 尺寸（设计稿原值，使用处加 `.s`）────────────────────

    /** header 高 50（`HomeHeader.tsx:189`）。 */
    const val HEADER_HEIGHT = 50

    /** header 左内距 12 / 右内距 5（`:193-194`）—— **左右不对称**，照抄。 */
    const val HEADER_PADDING_LEFT = 12
    const val HEADER_PADDING_RIGHT = 5

    /** header 元素间距 8（`:192`）。 */
    const val HEADER_GAP = 8

    /** 搜索框高 32（`:203`）。 */
    const val SEARCH_HEIGHT = 32

    /** 搜索框圆角 20（`:197`）。 */
    const val SEARCH_RADIUS = 20

    /** header 图标 32（`:279`，含订阅/搜索/箭头三处）。 */
    const val HEADER_ICON = 32

    /** 搜索占位字号 13（`:207`）。 */
    const val SEARCH_TEXT_SIZE = 13

    /** 性别按钮字号 13（`:222`）。 */
    const val HEADER_TEXT_SIZE = 13

    /** 系列行左右内距 13（`home.tsx:2236`）。 */
    const val SERIES_ROW_PADDING = 13

    /** 系列行下间距 6（`:2232`）。 */
    const val SERIES_ROW_BOTTOM = 6

    /** 系列 chip 高 32 / 圆角 20 / 左右内距 11 / 右外距 11（`:2269-2275`）。 */
    const val SERIES_CHIP_HEIGHT = 32
    const val SERIES_CHIP_RADIUS = 20
    const val SERIES_CHIP_PADDING = 11
    const val SERIES_CHIP_GAP = 11

    /** 系列文字 12（`:2285`）。 */
    const val SERIES_TEXT_SIZE = 12

    /** 筛选图标 32（`:1287`）。 */
    const val FILTER_ICON = 32

    /** 红点 8（`:2248-2250`）。 */
    const val BADGE_SIZE = 8

    /**
     * 卡片宽高比 185:310（`home.tsx:1350` banner 的 aspectRatio，卡片同比）。
     *
     * ⚠️ RN 的卡片是**固定高 310**（`:2211` `item.height`）+ 宽
     * `(screenWidth - 5) / 2`。在窄屏上这个组合的实际比例会偏离 185:310，
     * 但那正是现网表现 —— 用 aspectRatio 会在小屏上让卡片变矮。
     * 这里照抄"固定高 + 计算宽"。
     */
    const val CARD_HEIGHT = 310

    /** 两列之间的缝 1（`:1325` marginBottom / `columnWrapperStyle` gap）。 */
    const val CARD_GAP = 1

    /**
     * 卡片宽度的减数。
     *
     * `(screenWidth - 5) / 2`（`:1326`）—— 那个 **5 不是 `.s` 缩放值**，
     * 是裸像素常量（`styles.listContainer` 的 paddingHorizontal 2 × 2 + 1 缝）。
     * 给它加缩放会让两列总宽超出屏幕，右列被裁。
     */
    const val CARD_WIDTH_DEDUCTION = 5

    /** 卡片圆角 4（`HomeCard.tsx:367`）。 */
    const val CARD_RADIUS = 4

    /** 卡片内容左右内距 10 / 底部 12（`:429-430`）。 */
    const val CARD_CONTENT_PADDING = 10
    const val CARD_CONTENT_BOTTOM = 12

    /** 卡片名字号 13（`:444`）。 */
    const val CARD_TITLE_SIZE = 13

    /** 卡片简介字号 11（`:454`）。 */
    const val CARD_SUBTITLE_SIZE = 11

    /** 创作者名字号 12（`:462`）。 */
    const val CARD_CREATOR_SIZE = 12

    /** 消息数字号 12（`:398`）。 */
    const val CARD_COUNT_SIZE = 12

    /** 卡片小图标 16（`:352`：消息/语音图标）。 */
    const val CARD_ICON_SMALL = 16

    /** story 角标 20（`:265`）。 */
    const val CARD_ICON_STORY = 20

    /** 列表底部留白 = safeBottom + 50（`home.tsx:257` Android 分支）。 */
    const val LIST_BOTTOM_EXTRA = 50

    /** 顶部渐变高 32（`HomeCard.tsx:376`）。 */
    const val CARD_TOP_GRADIENT = 32

    /** 简介最多 3 行（`:456` numberOfLines={3}）。 */
    const val CARD_SUBTITLE_MAX_LINES = 3

    /** CDN 图片宽度参数 400（`constants/img.ts:4`）。 */
    const val IMAGE_CDN_WIDTH = 400

    // ── 筛选抽屉尺寸（`HomeFilterDrawer.tsx` + `TipsyDrawer.tsx`）──

    /**
     * 抽屉高 **630**（`HomeFilterDrawer.tsx:131`）。
     *
     * 固定值而非百分比。⚠️ 小屏上 630.s 可能超过可用高度 —— 使用处要
     * `coerceAtMost(可用高度)`，否则标题与勾选按钮会被推出屏幕外。
     */
    const val DRAWER_HEIGHT = 630

    /** 抽屉顶部圆角 20（`TipsyDrawer.tsx:376-377`）。 */
    const val DRAWER_RADIUS = 20

    /** 抽屉 header 高 49（`TipsyDrawer.tsx:435`）。 */
    const val DRAWER_HEADER_HEIGHT = 49

    /** header 左右内距 10（`:434`）。 */
    const val DRAWER_HEADER_PADDING = 10

    /** header 标题字号 17（`:456`）。 */
    const val DRAWER_TITLE_SIZE = 17

    /** 内容区：上 12、四周 10（`HomeFilterDrawer.tsx:262-263`）。 */
    const val DRAWER_CONTENT_PADDING_TOP = 12
    const val DRAWER_CONTENT_PADDING = 10

    /** 标签区底部留白 30（`:267`）—— 给最后一行留出滚动余量。 */
    const val DRAWER_CONTENT_PADDING_BOTTOM = 30

    /** 「Tags」小标题字号 15、下间距 15（`:275-278`）。 */
    const val DRAWER_SECTION_TITLE_SIZE = 15
    const val DRAWER_SECTION_TITLE_BOTTOM = 15

    /** header 里 `|` 字号 10（`:299`）。 */
    const val DRAWER_SEPARATOR_SIZE = 10

    /** Reset 文字字号 13（`:364`）。 */
    const val DRAWER_RESET_SIZE = 13

    /** 标签 chip：高 30 / 圆角 20 / 左右内距 18 / 字号 13（`:303-318`）。 */
    const val TAG_HEIGHT = 30
    const val TAG_RADIUS = 20
    const val TAG_PADDING_H = 18
    const val TAG_TEXT_SIZE = 13

    /** 标签间距：横 6（`:266` gap）、竖 6（`:310` marginBottom）。 */
    const val TAG_GAP = 6
}
