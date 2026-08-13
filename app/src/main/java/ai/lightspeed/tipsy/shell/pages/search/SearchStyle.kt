package ai.lightspeed.tipsy.shell.pages.search

import androidx.compose.ui.graphics.Color

/**
 * 搜索页的设计常量（逐个抄自 RN 的 `ScaledSheet`，来源在各字段注释）。
 *
 * 与 `ChatListStyle` 同构：**数值裸放**，`.s` / `.sSp` 由调用点乘 scale
 * —— 在这里乘会让常量与设计稿数字不再一一对应，改稿时要反算。
 */
internal object SearchStyle {

    // ── 顶部搜索栏（SearchBar.tsx styles）─────────────────────
    /** `searchContainer.padding: 10`。 */
    const val BAR_PADDING = 10

    /** 返回箭头 / 放大镜 / 清空按钮都是 32×32（`searchIcon`）。 */
    const val BAR_ICON = 32

    /** `searchInputContainer.height: 40`，`borderRadius: 20`。 */
    const val INPUT_HEIGHT = 40
    const val INPUT_RADIUS = 20
    const val INPUT_PADDING_H = 4

    /** `searchInput.fontSize: 12`。 */
    const val INPUT_FONT = 12

    val inputBackground = Color(0x0DFFFFFF) // rgba(255,255,255,0.05)
    val inputPlaceholder = Color(0x4DFFFFFF) // rgba(255,255,255,0.3)

    // ── 结果 tab 栏（SearchResultTabs.tsx styles）──────────────
    const val TABS_HEIGHT = 32
    const val TABS_PADDING_H = 10
    const val TABS_GAP = 24
    const val TABS_MARGIN_L = 6
    const val TAB_MIN_WIDTH = 48
    const val TAB_FONT = 15
    const val TAB_INDICATOR_H = 2
    const val TAB_INDICATOR_W = 24
    const val TAB_INDICATOR_RADIUS = 4
    const val FILTER_ICON = 32

    val tabTextUnselected = Color(0x4DFFFFFF) // rgba(255,255,255,0.3)
    val tabTextSelected = Color.White
    val tabIndicator = Color(0xFF9C4844)

    // ── 最近 / 热门标签（RecentSearch.tsx + PopularTags.tsx）────
    /** 两个区块的 `container.margin: 8`。 */
    const val SECTION_MARGIN = 8

    /** 区块标题 `fontSize: 14`，与标签行间距 `marginBottom: 16`。 */
    const val SECTION_TITLE_FONT = 14
    const val SECTION_TITLE_MARGIN_B = 16

    /** 清空按钮图标 20×20（`headerIcon`）。 */
    const val CLEAR_ICON = 20

    /** 标签 `paddingHorizontal: 12`/`paddingVertical: 4`，`gap: 8`。 */
    const val CHIP_PADDING_H = 12
    const val CHIP_PADDING_V = 4
    const val CHIP_GAP = 8
    const val CHIP_FONT = 12

    /** `borderRadius: 9999` —— 完全圆角，用一个足够大的值等效。 */
    const val CHIP_RADIUS = 999

    val sectionTitleColor = Color(0xB3FFFFFF) // rgba(255,255,255,0.7)
    val chipBackground = Color(0x08FFFFFF) // rgba(255,255,255,0.03)
    val chipBorder = Color(0x0DFFFFFF) // rgba(255,255,255,0.05)
    val chipText = Color(0xB3FFFFFF) // rgba(255,255,255,0.7)

    // ── 建议词（SuggestTags.tsx styles）───────────────────────
    /** 行 `paddingVertical: 8`/`paddingLeft: 8`，图标与文字 `gap: 8`。 */
    const val SUGGEST_ROW_PADDING_V = 8
    const val SUGGEST_ROW_PADDING_L = 8
    const val SUGGEST_GAP = 8
    const val SUGGEST_ICON = 32

    /** 命中片段高亮色（`highlight.color`）。 */
    val suggestHighlight = Color(0xFFF3A231)
    val suggestText = Color.White

    // ── 角色结果双列（CharacterResultList.tsx styles）─────────
    /** `item.height: 310` —— 固定高不是宽高比。 */
    const val CARD_HEIGHT = 310

    /** `item.margin: 0.5` + `ItemSeparatorComponent width: 1`。 */
    const val CARD_GAP = 1

    // ── 空态（CharacterResultList.tsx empty）─────────────────
    const val EMPTY_MARGIN_T = 64
    const val EMPTY_IMAGE_W = 159
    const val EMPTY_IMAGE_H = 92
    const val EMPTY_BUTTON_W = 200
    const val EMPTY_BUTTON_H = 40
    const val EMPTY_BUTTON_MARGIN_T = 18
    const val EMPTY_BUTTON_FONT = 14

    val emptyText = Color(0x80FFFFFF) // rgba(255,255,255,0.5)

    /** `TipsyButton` 的主色（与 FilterDrawer 的 Done 同色）。 */
    val primaryButton = Color(0xFFAD403B)

    // ── 创作者行（CreatorResultItem.tsx styles）───────────────
    const val CREATOR_ROW_PADDING_H = 10
    const val CREATOR_ROW_PADDING_V = 10
    const val CREATOR_AVATAR = 48
    const val CREATOR_AVATAR_MARGIN_R = 10
    const val CREATOR_NAME_FONT = 15
    const val CREATOR_META_FONT = 12
    const val CREATOR_META_GAP = 8
    const val CREATOR_BIO_FONT = 12
    const val CREATOR_INFO_GAP = 4

    val creatorName = Color.White
    val creatorMeta = Color(0x80FFFFFF) // rgba(255,255,255,0.5)
    val creatorBio = Color(0x80FFFFFF)

    // ── 重查遮罩（P2 的筛选；P1 只在结果列表就位）─────────────
    val refreshingMask = Color(0x66000000) // rgba(0,0,0,0.4)

    // ── 清空历史确认弹窗（复用 ChatList 删除弹窗的视觉）────────
    /**
     * 页面底色。
     *
     * ⚠️ **必须不透明**：搜索页挂在 `surface_container`，它叠在
     * `native_root_container`（五 Tab）之上。不画底色的表现是**首页透过来**
     * —— 两页文字图片重叠，一眼可见（Login 页同样自画底色，
     * `LoginStyle.BACKGROUND`）。窗口底色只覆盖冷启动首帧，盖不住下层 Fragment。
     *
     * 取值 = `values/colors.xml` 的 `app_background`（#FF34212A），
     * 与 Tab 页底色一致。
     */
    val pageBackground = Color(0xFF34212A)

    val dialogBackground = Color(0xFF2B1B19)
    const val DIALOG_RADIUS = 16
    const val DIALOG_PADDING = 20
    const val DIALOG_FONT = 15
    val dialogText = Color.White
}
