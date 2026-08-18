package ai.lightspeed.tipsy.shell.pages.chatlist

import java.util.Calendar
import java.util.TimeZone

/**
 * Map 廊道的**生产入口**（W3-P2）。
 *
 * ## 为什么必须是这一层，而不是"约定调用方传对参数"
 *
 * `ChatMapFloors.groupByDay(threads: List<T>, ...)` 对
 * [ChatListState.threads] 与 [ChatListState.sortedThreads] **完全同型** ——
 * 两者都是 `List<ChatThread>`，签名拦不住传错。早前把它说成"签名锁住契约"
 * 是不成立的：那只是**约定**。
 *
 * 本类接**整个 [ChatListState]**，在内部固定读 `state.threads`。
 * UI 只允许调 [floorsFor]，于是"用 raw 而不是 sorted"变成**结构保证**：
 * 调用方手里没有可传错的参数。
 *
 * ⚠️ 为什么必须是 raw：RN 侧 Grid 与 Map 拿的是**同一个** `recentChatList`
 * （`chatList/index.tsx:113` 与 `:126`），草稿混排是 **Grid 的渲染规则**、
 * 不进数据源。喂 `sortedThreads` **不会**改变会话的日期归属
 * （分组按 `latestTimeSeconds`，重排不动时间戳），但会改变**楼层顺序与
 * 桶内顺序** —— 有草稿的那条被顶到桶内最前，甚至把它所在那天顶到廊道最底层。
 *
 * ## 本地日换算：三个容易静默错的点
 *
 * 1. **`latestTimeSeconds` 是秒**（`ChatListModels.kt:144` 从 `latest_time` 读），
 *    当成毫秒会把所有会话算到 1970-01-01 → 全挤进一层；
 * 2. **必须按设备本地时区分日**，不是 UTC。直接 `seconds / 86400` 等于按 UTC 切，
 *    在 UTC+8 会让**本地 08:00 之前的会话算到前一天** —— 午夜两侧分错层，
 *    而单测若只用人工日号根本发现不了；
 * 3. **桶身份与标题必须用同一个本地日**，否则会出现"标题写 Today、
 *    却和昨天的会话同层"。
 *
 * 所以 [floorsFor] 在**一次捕获的 [TimeZone]** 下算出本地日，
 * 再由同一个值同时生成 key 与标题。
 *
 * ⚠️ **刻意用 `Calendar` 而不是 `java.time`**：本仓 minSdk 24 且
 * **没有开 `coreLibraryDesugaring`**（已核实 `app/build.gradle`）——
 * 直接引 `java.time` 会在 API 24/25 上崩（NoClassDefFoundError），
 * 而 CI 的冒烟矩阵里 API 24 是真实一档。要用 `java.time` 得先加 desugaring
 * 并单独验证，不属本刀范围。
 */
internal object ChatMapSource {

    /**
     * 由 [ChatListState] 直接产出楼层列表。**UI 只调这个。**
     *
     * @param state 完整状态 —— 内部固定取 `state.threads`（见类注释）
     * @param timeZone 分日用的时区；生产传 `TimeZone.getDefault()`，测试注入固定值
     * @param nowMillis "今天"的判定基准（用于 Today/Yesterday），测试可固定
     * @param localize 词条查表（只对 `Today` / `Yesterday` 生效）
     * @param formatDate 把本地日渲染成日期文案（非今天/昨天时用）
     */
    fun floorsFor(
        state: ChatListState,
        timeZone: TimeZone,
        nowMillis: Long,
        localize: (String) -> String,
        formatDate: (year: Int, month: Int, dayOfMonth: Int) -> String,
    ): List<ChatMapFloors.Floor<ChatThread>> {
        // ⚠️ 固定 threads —— **不是** sortedThreads。见类注释
        val threads = state.threads

        // 一次捕获的 calendar：key 与标题共用，避免两处用不同时区
        val calendar = Calendar.getInstance(timeZone)
        val todayDay = localDayOf(calendar, nowMillis)

        val buckets = ChatMapFloors.groupByDay(
            threads = threads,
            epochDayOf = { thread ->
                // ⚠️ 秒 → 毫秒。当成毫秒会把一切算到 1970 → 全挤一层
                localDayOf(calendar, thread.latestTimeSeconds * MILLIS_PER_SECOND)
            },
            titleOf = { day -> titleFor(calendar, day, todayDay, localize, formatDate) },
        )
        return ChatMapFloors.build(buckets)
    }

    /**
     * 本地日序号 —— 以本地时区的午夜为界，把毫秒折成"第几天"。
     *
     * 用 `Calendar` 逐字段取（year/dayOfYear 组合）而不是 `millis / 86400000`：
     * 后者是 UTC 切分，在非零时区会把午夜附近的会话分错天。
     */
    internal fun localDayOf(calendar: Calendar, millis: Long): Long {
        calendar.timeInMillis = millis
        val year = calendar.get(Calendar.YEAR)
        val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
        // year * 512 + dayOfYear：512 > 366，保证不同年份不碰撞且单调
        return year.toLong() * DAYS_NAMESPACE + dayOfYear
    }

    /** 本地日 → 标题（对齐 `formatChatMapTime` 的四分支）。 */
    private fun titleFor(
        calendar: Calendar,
        day: Long,
        todayDay: Long,
        localize: (String) -> String,
        formatDate: (Int, Int, Int) -> String,
    ): String {
        // ⚠️ 只有 Today/Yesterday 过 i18n（对齐 `ChatMap.tsx:355`）；
        // 日期串全过 localize 会把日期当词条 key —— 查不到回落原文所以
        // "看起来没事"，但词条表会留一堆假 key
        if (day == todayDay) return localize(TODAY)
        if (day == todayDay - 1) return localize(YESTERDAY)

        // 还原成年月日交给调用方格式化（壳的 L10n 不做日期本地化）
        val year = (day / DAYS_NAMESPACE).toInt()
        val dayOfYear = (day % DAYS_NAMESPACE).toInt()
        calendar.set(Calendar.YEAR, year)
        calendar.set(Calendar.DAY_OF_YEAR, dayOfYear)
        return formatDate(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }

    /**
     * ⚠️ 注意 `todayDay - 1` 只在**同年内**是"前一天"。跨年（1 月 1 日的
     * 前一天是上一年最后一天）时相减不成立 —— 那种情况会落到 [formatDate]
     * 分支显示完整日期，**与 RN 一致**：RN 的 `formatChatMapTime` 对
     * 非今年的日期用 `MMM D, YYYY`，1 月 1 日看昨天正好跨到去年。
     * 唯一偏差是 12 月 31 日→1 月 1 日这一天不显示 "Yesterday" 而显示日期，
     * 记为**已知偏差**（一年一天、且方向是"更明确"而非丢信息）。
     */
    private const val DAYS_NAMESPACE = 512L

    private const val MILLIS_PER_SECOND = 1_000L
    private const val TODAY = "Today"
    private const val YESTERDAY = "Yesterday"
}
