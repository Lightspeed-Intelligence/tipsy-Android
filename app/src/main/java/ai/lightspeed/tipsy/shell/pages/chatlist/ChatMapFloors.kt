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
     * 楼层种类 —— ⚠️ **两种「空」不是一回事**，别再合成一个 `isEmpty`。
     *
     * RN `ChatMap.tsx:195-214` 的 `dataList`：
     * - `item.type === 'empty'` → **直接 `return []`**，卡叠里**零张卡**；
     * - 否则（chat 层）→ 真实会话，**不足 5 张时补剪影占位卡**（`:205-212`）。
     *
     * 所以「补齐出来的 chat 层」（0 真实会话 + 5 张剪影）与
     * 「尾部 runway 层」（0 张卡、只占高度）**渲染完全不同**。
     * 合成一种的表现是：廊道最上方两层多画了 10 张剪影卡，
     * 或者补齐层一张卡都不画 —— 取决于合并时偏向哪边。
     */
    enum class FloorKind {
        /** 日期分组层（含补齐出来的空分组）：卡叠**补到 5 张**剪影。 */
        CHAT,

        /** 尾部跑道层（`empty1`/`empty2`）：**零张卡**，只占高度做过渡。 */
        RUNWAY,
    }

    /**
     * 一个楼层。
     *
     * @property title 日期分组标题；空串表示该 chat 层无真实分组
     * @property items 该组的真实会话（**不截断** —— 同日超过 5 条要全保留，见 [carouselSlots]）
     * @property kind 见 [FloorKind]
     */
    data class Floor<T>(
        val key: String,
        val title: String,
        val items: List<T>,
        val kind: FloorKind,
    ) {
        /** 该层卡叠要铺的**总槽位数**（真实卡 + 剪影补位）。 */
        val slotCount: Int get() = when (kind) {
            FloorKind.RUNWAY -> 0
            FloorKind.CHAT -> carouselSlots(items.size)
        }
    }

    /**
     * 构建楼层列表。
     *
     * @param grouped 已按日期分组的会话（保持插入序 —— 分组顺序即时间顺序，
     *   用 `LinkedHashMap` 或有序 List，**不要**换成 HashMap）
     *
     * ⚠️ **上游必须喂 `ChatListState.threads`（接口累计顺序），不是 `sortedThreads`。**
     * RN 侧 Grid 与 Map 拿的是**同一个** `recentChatList`
     * （`chatList/index.tsx:113` 与 `:126` 都传 `recentChatList = list`）——
     * 草稿混排在 RN 里是 **Grid 的渲染规则**，不进数据源。
     * 壳的 `sortedThreads` 会因草稿重排，喂给 Map 会让**有草稿的会话跳到
     * 别的日期分组**（分组按 `latest_time` 算，而重排改的是列表位置不是时间）
     * —— 表现是"某个会话出现在错误的那一天"，用户大概只觉得奇怪而不会报。
     * @param localize 把 `Today` / `Yesterday` 过 i18n（其余标题原样，
     *   对齐 `:355` 的 `key === 'Today' || key === 'Yesterday' ? t(key) : key`）
     */
    fun <T> build(
        grouped: List<Pair<String, List<T>>>,
        localize: (String) -> String,
    ): List<Floor<T>> {
        val entries = grouped.toMutableList()

        // 不足 3 组时补空组（RN `:337-339`：realSize < 3 才补，且补到 emptySize）
        val realSize = entries.size
        if (realSize < EMPTY_TARGET_GROUPS) {
            repeat(EMPTY_TARGET_GROUPS - realSize) { entries.add("" to emptyList()) }
        }

        val floors = entries.mapIndexed { index, (title, items) ->
            Floor(
                // ⚠️ key 用**未本地化的 date bucket**，不是 RN 那个 `index.toString()`。
                //
                // RN 用下标是安全的（FlatList 每次整树重算，没有 per-item 记忆状态），
                // 但 Compose 的 `remember(key)` / `LazyColumn(key)` 会按 key 复用状态 ——
                // 用下标会把「昨天那层已横滑到第 3 张」的卡叠状态复用给
                // 分页追加后落到同一下标的**另一天**。表现是滚动位置莫名跳。
                //
                // 用 bucket 原文（非本地化）而不是标题：标题过 i18n 后
                // 切换语言会让 key 全变，同样丢状态。空 bucket 回落下标。
                key = title.ifEmpty { "pad$index" },
                // ⚠️ 只有 Today/Yesterday 过 i18n；其余是后端/格式化好的日期串，
                // 全都过 localize 会把日期当词条 key 查不到而回落原文（看起来没事，
                // 但会在词条表里留下一堆假 key）
                title = if (title == TODAY || title == YESTERDAY) localize(title) else title,
                items = items,
                kind = FloorKind.CHAT,
            )
        }

        // 尾部固定拼 2 个空占位层（Android 恒 2，见类注释）
        val trailing = (1..TRAILING_EMPTY_FLOORS).map { n ->
            Floor<T>(key = "empty$n", title = "", items = emptyList(), kind = FloorKind.RUNWAY)
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

    /**
     * 卡叠槽位数：真实卡不足 [MIN_CAROUSEL_SLOTS] 时补剪影补到该数，
     * **超过则全部保留、不截断**（对齐 RN `:205-213` 的 `if (len < 5)`）。
     *
     * ⚠️ 名字刻意不叫 `MAX_...` —— 5 是**补位下限**不是上限。
     * 同一天超过 5 条会话时 RN 会铺满全部，卡叠靠 distance 环绕显示；
     * UI 侧**不得** `take(5)`。
     */
    fun carouselSlots(realCount: Int): Int = maxOf(realCount, MIN_CAROUSEL_SLOTS)

    /** 卡叠补位下限（**不是上限**，见 [carouselSlots]）。 */
    const val MIN_CAROUSEL_SLOTS = 5

    private const val TODAY = "Today"
    private const val YESTERDAY = "Yesterday"
}
