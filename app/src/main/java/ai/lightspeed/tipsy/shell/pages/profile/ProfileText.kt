package ai.lightspeed.tipsy.shell.pages.profile

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Profile 的文案与数字格式化。
 */
object ProfileText {

    /**
     * 统计数字格式化（`utils/formatNumbers.ts:11-38` 的 `formatLargeNumber`）。
     *
     * ## ⚠️ 这是**第三套**数字格式化规则，不要复用 Home 的
     *
     * 壳里已有 `HomeText.formatMessageCount`（对应 RN 的 `formatNumber`），
     * 与本函数**规则不同**：
     *
     * | 输入 | `formatMessageCount`（Home 卡片） | 本函数（Profile 统计） |
     * | --- | --- | --- |
     * | 1000 | `1,000` | `1K` |
     * | 1999 | `1,999` | **`1.9K`**（去尾，不是 2.0K） |
     * | 12500 | `12.5K` | `12.5K` |
     * | 999999 | `1000.0K` | `999.9K` |
     *
     * 两处最容易错的：
     * 1. **去尾法而非四舍五入**（RN 用 `Math.floor`）—— 用 `%.1f` 会把 1999
     *    显示成 `2.0K`，与现网差一位
     * 2. **1000 就进位到 K**，没有千分位阶段
     *
     * 挑错函数不报错，只是数字与现网对不上（Home 那边的注释记了同一条教训）。
     */
    fun formatLargeNumber(value: Long): String {
        if (value == 0L) return "0"
        val negative = value < 0
        // ⚠️ 用 Long 取绝对值前先处理 MIN_VALUE：abs(Long.MIN_VALUE) 仍是负数
        val abs = if (value == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(value)

        val body = when {
            abs < 1_000L -> abs.toString()
            // 去尾到一位小数：floor(abs / 100) / 10
            abs < 1_000_000L -> trimTrailingZero(abs / 100L, "K")
            abs < 1_000_000_000L -> trimTrailingZero(abs / 100_000L, "M")
            else -> trimTrailingZero(abs / 100_000_000L, "B")
        }
        return if (negative) "-$body" else body
    }

    /**
     * 把"放大 10 倍的整数"还原成最多一位小数并去掉末尾 `.0`。
     *
     * 对齐 RN 的两步：`Math.floor(x/100)/10 + 'K'` 然后
     * `.replace(/\.0([KMB])?$/, '$1')`（`formatNumbers.ts:34`）。
     * 即 1000 → `1K` 而不是 `1.0K`。
     */
    private fun trimTrailingZero(tenths: Long, unit: String): String {
        val whole = tenths / 10L
        val frac = tenths % 10L
        return if (frac == 0L) "$whole$unit" else "$whole.$frac$unit"
    }

    /**
     * UID 显示。
     *
     * RN 侧 `uid()`（`utils/func.ts:277-279`）取**前 3 + 后 3**：
     * `id.substring(0,3) + '...' + id.substring(len-3, len)`。
     * 已核实自己主页显示的就是 `userId`（不存在 `public_user_id` 字段，
     * 见 `CurrentUser` 类注释）。
     *
     * ⚠️ RN **不做长度检查** —— 短 id 也照切，`"ab"` 会得到 `"ab...ab"`。
     * 这里保留同样行为：加"长度不足就原样返回"的优化会让短 id 的显示与现网不同。
     * 真实 userId 都是 19 位雪花 id，这个分支实际不会命中，但别擅自改语义。
     *
     * 页面上的完整展示是 `UID: ` + 本函数结果（`user-profile.tsx:665`），
     * 前缀由 UI 层拼，不放这里（那是文案，会进 i18n）。
     */
    fun formatUid(userId: String): String {
        if (userId.isEmpty()) return ""
        return userId.take(UID_HEAD) + "..." + userId.takeLast(UID_TAIL)
    }

    /**
     * 记忆卡的时间显示（`PlotItem.tsx:219` `formatTimestampToAMPMTime`）。
     *
     * RN 用 `Intl.DateTimeFormat('en-US', {hour, minute, hour12})` 且**调用点
     * 不传 locale** —— 恒为 en-US，输出形如 `3:07 PM`。两处别"修"：
     * 1. 这是"创建时间"却只显示**时:分不显示日期** —— RN 就是这样；
     * 2. 不要按设备 locale 本地化 —— RN 没做，做了会与现网肉眼可辨地不同。
     *
     * @param epochSeconds Unix **秒**（不是毫秒，见 [ProfileMemoryItem] 的实测出入表）
     */
    fun formatMemoryTime(epochSeconds: Long): String =
        SimpleDateFormat("h:mm a", Locale.US).format(Date(epochSeconds * 1000L))

    /**
     * 钱包整数（宝石/免费条数）：纯千分位分组，**没有 K/M 换算**
     * （`formatMessageAmount`，`utils/subscribe.ts:3-9` 就是裸 `toLocaleString()`）。
     * 这是本页**第四套**数字规则 —— 别复用统计的 [formatLargeNumber]（那套会把
     * 1000 显示成 1K）。分组固定 en-US 形状（RN 按设备 locale，绝大多数拉丁
     * 数字 locale 分组一致；固定是为了可测）。
     */
    fun formatWalletAmount(value: Long): String =
        NumberFormat.getIntegerInstance(Locale.US).format(value)

    /**
     * 金币：**去尾到一位小数 + 千分位 + 恒带一位小数**（`formatCoinAmount`，
     * `utils/coin.ts:50-58`：`floor(x*10+1e-8)/10` 再 min/max FractionDigits 1）。
     * 0 显示 `0.0` 不是 `0`；负值与 NaN 归 0（RN 的 `Math.max(amount, 0)`）。
     */
    fun formatCoinAmount(value: Double): String {
        val normalized = if (value.isFinite()) kotlin.math.max(value, 0.0) else 0.0
        val truncated = kotlin.math.floor(normalized * 10 + 1e-8) / 10
        val format = DecimalFormat("#,##0.0", DecimalFormatSymbols(Locale.US))
        return format.format(truncated)
    }

    /**
     * 卡片计数（曝光/消息）：**最多三位有效数字**的 K/M/B/T/Q 缩写
     * （`formatCountMaxThreeDigits`，`utils/formatNumbers.ts:45-75`，
     * 有 RN 侧单测锁行为）。本页**第六套**数字规则：
     *
     * | 输入 | 输出 | 与其它规则的分道处 |
     * | --- | --- | --- |
     * | 999 | `999` | 千内原样（同钱包） |
     * | 1000 | `1K` | 千即缩写（钱包是 `1,000`） |
     * | 4730 | `4.73K` | **两位小数**（统计的 formatLargeNumber 只留一位） |
     * | 12500 | `12.5K` | 尾零剥掉 |
     * | 999950 | `1M` | 四舍五入后 `1000K` 要晋位 |
     *
     * 非数/缺失显示 `0`（RN 的 `Number.isFinite` 守卫）。
     */
    fun formatCountMaxThreeDigits(count: Long?): String {
        if (count == null) return "0"
        val sign = if (count < 0) "-" else ""
        var value = kotlin.math.abs(count.toDouble())
        var unitIndex = 0
        while (value >= 1000 && unitIndex < COUNT_UNITS.size - 1) {
            value /= 1000
            unitIndex++
        }
        var formatted = formatThreeDigits(value)
        // 999.5K 四舍五入成 1000K 时晋位到 1M（formatNumbers.ts:73-77）
        if (formatted.toDouble() >= 1000 && unitIndex < COUNT_UNITS.size - 1) {
            value = formatted.toDouble() / 1000
            unitIndex++
            formatted = formatThreeDigits(value)
        }
        return sign + formatted + COUNT_UNITS[unitIndex]
    }

    /** 三位有效数字：整数位越多小数位越少，尾零剥掉（`formatValue`）。 */
    private fun formatThreeDigits(number: Double): String {
        val integerDigits = when {
            number >= 100 -> 3
            number >= 10 -> 2
            number >= 1 -> 1
            else -> 0
        }
        val decimalPlaces = (3 - integerDigits).coerceAtLeast(0)
        val fixed = String.format(Locale.US, "%.${decimalPlaces}f", number)
        return if (decimalPlaces > 0) fixed.trimEnd('0').trimEnd('.') else fixed
    }

    private val COUNT_UNITS = arrayOf("", "K", "M", "B", "T", "Q")

    private const val UID_HEAD = 3
    private const val UID_TAIL = 3
}
