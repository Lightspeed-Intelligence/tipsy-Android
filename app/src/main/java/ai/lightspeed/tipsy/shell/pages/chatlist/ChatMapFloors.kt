package ai.lightspeed.tipsy.shell.pages.chatlist

/**
 * Map 廊道的「楼层列表」构建（W3-P2）—— 纯函数，可单测。
 *
 * 对齐 RN `ChatMap.tsx:329-363` 的 `list` useMemo：按日期分组 → 不足 3 组时
 * 补空组 → 尾部再拼 2 个空占位层。
 *
 * ## ⚠️ 又有两处 `smallScreen` 分支，Android 恒走非 small
 *
 * 除了已知的行高与动画曲线，列表构建里还有两处（`:336` 与 `:344-352`）：
 *
 * | 项 | RN small（iOS 专属） | **Android 恒值** |
 * | --- | --- | --- |
 * | `emptySize`（补齐目标组数） | 2 | **3** |
 * | 尾部空占位层数 | 1（`empty1`） | **2**（`empty1` + `empty2`） |
 *
 * `smallScreen = Platform.OS === 'ios' && realWidth <= 750`（`:84`）——
 * **与条件第一项就是 iOS**，Android 恒 false。照 iOS 抄会**少铺一层空占位**，
 * 表现是廊道最上方少一层过渡、滚到顶时最后一组卡的位置不对 ——
 * 而这类偏差按 `llmdoc/index.md:64` 的纪律没人会报。
 * 由 `ChatMapFloorsTest` 的反向测试钉住。
 */
internal object ChatMapFloors {

    /** 补齐的目标组数（Android 恒 3，小屏那条是 2）。 */
    const val EMPTY_TARGET_GROUPS = 3

    /** 尾部空占位层数（Android 恒 2，小屏那条是 1）。 */
    const val TRAILING_EMPTY_FLOORS = 2

    /**
     * 一个楼层。
     *
     * @property title 日期分组标题；空串表示空占位层
     * @property items 该组的会话；空表示占位
     * @property isEmpty 占位层（渲染毛玻璃 + 剪影，不可点）
     */
    data class Floor(
        val key: String,
        val title: String,
        val items: List<String>,
        val isEmpty: Boolean,
    )

    /**
     * 构建楼层列表。
     *
     * @param grouped 已按日期分组的会话（保持插入序 —— 分组顺序即时间顺序，
     *   用 `LinkedHashMap` 或有序 List，**不要**换成 HashMap）
     * @param localize 把 `Today` / `Yesterday` 过 i18n（其余标题原样，
     *   对齐 `:355` 的 `key === 'Today' || key === 'Yesterday' ? t(key) : key`）
     */
    fun build(
        grouped: List<Pair<String, List<String>>>,
        localize: (String) -> String,
    ): List<Floor> {
        val entries = grouped.toMutableList()

        // 不足 3 组时补空组（RN `:337-339`：realSize < 3 才补，且补到 emptySize）
        val realSize = entries.size
        if (realSize < EMPTY_TARGET_GROUPS) {
            repeat(EMPTY_TARGET_GROUPS - realSize) { entries.add("" to emptyList()) }
        }

        val floors = entries.mapIndexed { index, (title, items) ->
            Floor(
                key = index.toString(),
                // ⚠️ 只有 Today/Yesterday 过 i18n；其余是后端/格式化好的日期串，
                // 全都过 localize 会把日期当词条 key 查不到而回落原文（看起来没事，
                // 但会在词条表里留下一堆假 key）
                title = if (title == TODAY || title == YESTERDAY) localize(title) else title,
                items = items,
                isEmpty = title.isEmpty() && items.isEmpty(),
            )
        }

        // 尾部固定拼 2 个空占位层（Android 恒 2，见类注释）
        val trailing = (1..TRAILING_EMPTY_FLOORS).map { n ->
            Floor(key = "empty$n", title = "", items = emptyList(), isEmpty = true)
        }
        return floors + trailing
    }

    /**
     * `currIndex = floor((scrollY + 0.5) / rowHeight) + index`。
     *
     * ⚠️ 那个 0.5 不是随手加的（iOS 端口注释）：初始/吸附后 `scrollY` 可能是
     * `-0.0x` 的浮点噪声，裸 `floor` 会**错位一整行**。
     */
    fun currIndexFor(scrollY: Float, rowHeight: Int, floorIndex: Int): Int {
        if (rowHeight <= 0) return floorIndex
        val shifted = (scrollY + ChatMapGeometry.CURR_INDEX_EPSILON) / rowHeight
        return Math.floor(shifted.toDouble()).toInt() + floorIndex
    }

    /** 该层是否在可见范围内（`currIndex ∈ [-1, 3]`，越界整层隐藏）。 */
    fun isFloorVisible(currIndex: Int): Boolean =
        currIndex >= ChatMapGeometry.VISIBLE_INDEX_MIN &&
            currIndex <= ChatMapGeometry.VISIBLE_INDEX_MAX

    /** 标题是否淡出（`currIndex >= 2`）。 */
    fun isTitleHidden(currIndex: Int): Boolean = currIndex >= ChatMapGeometry.TITLE_FADE_INDEX

    private const val TODAY = "Today"
    private const val YESTERDAY = "Yesterday"
}
