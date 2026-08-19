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
     * 一个日期桶 —— **稳定身份与展示文案分离**。
     *
     * ## ⚠️ 为什么不能用标题当 key
     *
     * RN 的 `formatChatMapTime`（`utils/func.ts:347-362`）产出的是**展示文案**，
     * 它有**两处**不稳定：
     * 1. **随 locale 变**：`dayjs.locale(...)` 后 `format('D MMMM')`
     *    在中/英下是不同字符串；
     * 2. **随"今天"变**：`Today` / `Yesterday` 是相对判定 ——
     *    同一条会话过一天后标题从 `Today` 变 `Yesterday`，
     *    而**跨日后新的会话又会占用 `Today`**。
     *
     * 拿它当 Compose 的 `remember` / `LazyColumn` key，两种都会出事：
     * 切语言让所有 key 变化（状态全丢）；跨日让**昨天那层的横滑状态
     * 被复用给今天**（key 字面相同，实际是不同的一天）。
     *
     * 所以 [bucketKey] 用 **epoch-day**（本地时区的天序号，与 locale/相对
     * 判定都无关），[displayTitle] 单独承担文案。
     *
     * ⚠️ [bucketKey] **由 [localDay] 内部生成，不可自定义** —— 否则任意 String
     * 都能构造出与补齐层（`pad…`）或跑道层（`empty…`）重名的 key，
     * "唯一 namespace"就只是调用约定而不是结构保证。
     *
     * @property localDay 本地日序号（由 `ChatMapSource` 在捕获的时区下算出）
     * @property displayTitle 已本地化的展示文案（`Today` / `18 August` 等）
     */
    data class DateBucket<T>(
        val localDay: Long,
        val displayTitle: String,
        val items: List<T>,
    ) {
        /** 楼层 key —— `day:` 前缀与 `pad`/`empty` 命名空间天然不撞。 */
        val bucketKey: String get() = "$DAY_KEY_PREFIX$localDay"
    }

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
     *
     * ⚠️ **上游必须喂 `ChatListState.threads`（接口累计顺序），不是 `sortedThreads`。**
     * RN 侧 Grid 与 Map 拿的是**同一个** `recentChatList`
     * （`chatList/index.tsx:113` 与 `:126` 都传 `recentChatList = list`）——
     * 草稿混排在 RN 里是 **Grid 的渲染规则**，不进数据源。
     * ⚠️ 壳的 `sortedThreads` 会因草稿重排。**订正早前说重了的一句**：
     * 它**不会**改变会话的日期归属（分组按 `latestTimeSeconds`，重排不改时间戳），
     * 但会改变**楼层顺序与桶内顺序** —— 有草稿的那条被顶到桶内最前，
     * 甚至把它所在的那一天顶到廊道最底层。表现是顺序莫名与 Grid 不一致。
     * @param buckets 已按本地日分组的桶（**由 `ChatMapSource.floorsFor` 产出**；
     *   标题已本地化，key 由 [DateBucket.localDay] 生成）
     */
    fun <T> build(buckets: List<DateBucket<T>>): List<Floor<T>> {
        val entries = buckets.map { RealEntry(it.bucketKey, it.displayTitle, it.items) }
            .toMutableList<Entry<T>>()

        // 不足 3 组时补空组（RN `:337-339`：realSize < 3 才补，且补到 emptySize）
        val realSize = entries.size
        if (realSize < EMPTY_TARGET_GROUPS) {
            repeat(EMPTY_TARGET_GROUPS - realSize) { n ->
                // 补齐层没有真实日期 —— 用独立 namespace，不可能与 day: 撞
                entries.add(PadBucket<T>(entries.size))
            }
        }

        val floors = entries.map { entry ->
            Floor(
                // ⚠️ key 来自 namespace 化的 entry，不是标题、也不是 RN 的下标。
                // 标题随 locale 与"今天"两处变化，下标会在分页后错配 —— 见 [DateBucket]
                key = entry.key,
                title = entry.title,
                items = entry.items,
                kind = FloorKind.CHAT,
            )
        }

        // 尾部固定拼 2 个空占位层（Android 恒 2，见类注释）
        val trailing = (1..TRAILING_EMPTY_FLOORS).map { n ->
            Floor<T>(
                key = "$RUNWAY_KEY_PREFIX$n",
                title = "",
                items = emptyList(),
                kind = FloorKind.RUNWAY,
            )
        }
        return floors + trailing
    }

    /**
     * `currIndex = floor((scrollYDp + 0.5) / rowHeightDp) + index`。
     *
     * ⚠️ **两个参数都是 dp**（`Float`）—— 早前是 `scrollY: Float, rowHeight: Int`，
     * 无单位名且 `Int` 很像可以直接塞 px。而函数内部加的是
     * [ChatMapGeometry.CURR_INDEX_EPSILON_DP]（0.5 **dp**）：
     * 若调用方传 px，那个 0.5 就被当成 0.5px 用，容差实际缩小 density 倍、
     * 挡不住浮点噪声。
     *
     * ⚠️ 那个 0.5 不是随手加的（iOS 端口注释）：初始/吸附后 `scrollY` 可能是
     * `-0.0x` 的浮点噪声，裸 `floor` 会**错位一整行**。
     */
    fun currIndexFor(scrollYDp: Float, rowHeightDp: Float, floorIndex: Int): Int {
        if (rowHeightDp <= 0f) return floorIndex
        val shifted = (scrollYDp + ChatMapGeometry.CURR_INDEX_EPSILON_DP) / rowHeightDp
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

    /**
     * 按 canonical day 分组 —— **Map 的唯一入口**，直接吃完整累计列表。
     *
     * ## ⚠️ 必须传 `ChatListState.threads`，**不是 `sortedThreads`**
     *
     * 这个签名就是契约：接**完整累计的 [ChatThread] 列表**（接口顺序），
     * 而不是预分组的 Pair —— 后者无法阻止调用方先 `sortedThreads` 再分组。
     *
     * RN 侧 Grid 与 Map 拿的是**同一个** `recentChatList`
     * （`chatList/index.tsx:113` 与 `:126` 都传 `list`）——
     * 草稿混排是 **Grid 的渲染规则**，不进数据源。
     *
     * ⚠️ **订正早前一处说重了的注释**：喂 `sortedThreads` **不会**改变会话的
     * 日期归属（分组按 `latestTimeSeconds`，重排不改时间戳），
     * 但会改变**楼层顺序与桶内顺序** —— 即"有草稿的那条被顶到桶内最前、
     * 甚至把它所在的那一天顶到廊道最底层"。表现是顺序莫名与 Grid 不一致。
     *
     * ## 分页
     *
     * 传入的是**累计**列表，所以 page 2 里同一天的会话会**自然合回同一个桶**
     * （相同 `bucketKey`），不会新起一层。桶的出现顺序 = 该天**首次出现**的位置
     * （encounter order），与接口顺序一致。
     *
     * @param threads 完整累计列表（接口顺序）
     * @param epochDayOf 取该会话的**本地日序号**（注入以便单测不依赖时区/时钟）
     * @param titleOf 按本地日序号产出**已本地化**的展示文案
     */
    fun <T> groupByDay(
        threads: List<T>,
        epochDayOf: (T) -> Long,
        titleOf: (Long) -> String,
    ): List<DateBucket<T>> {
        if (threads.isEmpty()) return emptyList()
        // LinkedHashMap 保 encounter order —— ⚠️ 换成 HashMap 会让楼层顺序随机
        val byDay = LinkedHashMap<Long, MutableList<T>>()
        threads.forEach { t -> byDay.getOrPut(epochDayOf(t)) { mutableListOf() }.add(t) }
        return byDay.map { (day, items) ->
            DateBucket(localDay = day, displayTitle = titleOf(day), items = items)
        }
    }

    private interface Entry<T> {
        val key: String
        val title: String
        val items: List<T>
    }

    private data class RealEntry<T>(
        override val key: String,
        override val title: String,
        override val items: List<T>,
    ) : Entry<T>

    private class PadBucket<T>(index: Int) : Entry<T> {
        override val key = "$PAD_KEY_PREFIX$index"
        override val title = ""
        override val items = emptyList<T>()
    }

    /** 楼层 key 的三个命名空间前缀 —— 互不重叠是结构保证，不是约定。 */
    internal const val DAY_KEY_PREFIX = "day:"
    private const val PAD_KEY_PREFIX = "pad:"
    private const val RUNWAY_KEY_PREFIX = "runway:"

}
