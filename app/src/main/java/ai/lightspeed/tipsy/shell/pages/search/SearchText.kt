package ai.lightspeed.tipsy.shell.pages.search

import java.util.Locale

/**
 * 搜索页的纯文本规则。
 *
 * 目前只有一条：创作者行的计数缩写。
 */
internal object SearchText {

    /**
     * 创作者统计的计数缩写（`formatCountMaxFourDigits`，`utils/formatNumbers.ts:88-130`）。
     *
     * ⚠️ **与 Profile 的 [ProfileText.formatCountMaxThreeDigits] 是两套规则**，
     * 别复用（本项目里第七套数字规则）。两条分道处：
     *
     * | 输入 | 四位数规则（本函数） | 三位数规则（Profile 卡片） |
     * | --- | --- | --- |
     * | 1000 | `1000` —— **万以下原样** | `1K` |
     * | 9999 | `9999` | `10K` |
     * | 10000 | `10K` | `10K` |
     * | 12345 | `12.35K` —— 四位有效数字 | `12.3K` |
     *
     * 也就是说四位数规则的**缩写门槛是 10000 而不是 1000**。写错的表现是
     * 「粉丝 1200 显示成 1.2K」—— 与线上不一致但看着"挺合理"，极易漏过 review。
     *
     * 负数保留符号（RN 有 `sign` 处理，虽然计数理论上非负）。
     */
    fun formatCountMaxFourDigits(count: Long?): String {
        if (count == null) return "0"
        val sign = if (count < 0) "-" else ""
        var value = kotlin.math.abs(count.toDouble())

        // 万以下原样输出（取整，RN 用 Math.round）
        if (value < ABBREVIATE_THRESHOLD) {
            return sign + kotlin.math.round(value).toLong().toString()
        }

        var unitIndex = 0
        // ⚠️ 循环条件是 >= 10000（不是 1000）—— 缩放到万以下才停
        while (value >= ABBREVIATE_THRESHOLD && unitIndex < COUNT_UNITS.size - 1) {
            value /= 1000
            unitIndex++
        }

        var formatted = formatFourDigits(value)
        // 四舍五入后又满万（如 9999.6K → 10000K）时再晋一位
        if (formatted.toDouble() >= ABBREVIATE_THRESHOLD && unitIndex < COUNT_UNITS.size - 1) {
            value = formatted.toDouble() / 1000
            unitIndex++
            formatted = formatFourDigits(value)
        }
        return sign + formatted + COUNT_UNITS[unitIndex]
    }

    /** 四位有效数字：整数位越多小数位越少，尾零剥掉（`getFormatted`）。 */
    private fun formatFourDigits(number: Double): String {
        val integerDigits = kotlin.math.floor(number).toLong().toString().length
        val decimalPlaces = (4 - integerDigits).coerceAtLeast(0)
        val fixed = String.format(Locale.US, "%.${decimalPlaces}f", number)
        return if (decimalPlaces > 0) fixed.trimEnd('0').trimEnd('.') else fixed
    }

    /** 缩写门槛：万（不是千）。见 [formatCountMaxFourDigits] 的对照表。 */
    private const val ABBREVIATE_THRESHOLD = 10_000

    private val COUNT_UNITS = listOf("", "K", "M", "B", "T")
}
