package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.profile.ProfileText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `ProfileText` 的格式化规则（逐条对着 RN 取真值）。
 *
 * 这里每一条错了都不报错，只是数字/文本与现网对不上。
 */
class ProfileTextTest {

    // ── formatLargeNumber：去尾法，不是四舍五入 ──────────────

    @Test
    fun `0 显示 0`() {
        assertEquals("0", ProfileText.formatLargeNumber(0))
    }

    @Test
    fun `小于 1000 原样显示且无千分位`() {
        // ⚠️ 与 HomeText.formatMessageCount 不同：那个 999 也是 "999"，
        // 但 1000 会变 "1,000"，这里是 "1K"
        assertEquals("999", ProfileText.formatLargeNumber(999))
    }

    @Test
    fun `1000 进位成 1K 且去掉小数 0`() {
        // RN: Math.floor(1000/100)/10 = 1 → "1K"，再被 /\.0([KMB])?$/ 规则清理
        assertEquals("1K", ProfileText.formatLargeNumber(1000))
    }

    @Test
    fun `1999 去尾成 1_9K 而不是 2_0K`() {
        // 这条是去尾法与四舍五入的分水岭。用 %.1f 会得到 "2.0K"
        assertEquals("1.9K", ProfileText.formatLargeNumber(1999))
    }

    @Test
    fun `12500 显示 12_5K`() {
        assertEquals("12.5K", ProfileText.formatLargeNumber(12500))
    }

    @Test
    fun `999999 显示 999_9K 而不是进位到 1M`() {
        // 四舍五入会得到 "1000.0K"；RN 的去尾法停在 999.9K
        assertEquals("999.9K", ProfileText.formatLargeNumber(999_999))
    }

    @Test
    fun `1000000 进位成 1M`() {
        assertEquals("1M", ProfileText.formatLargeNumber(1_000_000))
    }

    @Test
    fun `1550000 显示 1_5M`() {
        assertEquals("1.5M", ProfileText.formatLargeNumber(1_550_000))
    }

    @Test
    fun `十亿进位成 1B`() {
        assertEquals("1B", ProfileText.formatLargeNumber(1_000_000_000))
    }

    @Test
    fun `负数带负号`() {
        // RN: isNegative ? '-' + result : result
        assertEquals("-1.9K", ProfileText.formatLargeNumber(-1999))
    }

    @Test
    fun `负数小值也带负号`() {
        assertEquals("-5", ProfileText.formatLargeNumber(-5))
    }

    @Test
    fun `Long 最小值不会因取绝对值溢出成负数`() {
        // abs(Long.MIN_VALUE) 仍是负数 —— 不特判会输出带两个负号的怪串。
        // B 单位之上不再收敛，所以这里就是个大数字，只断言"没被溢出污染"。
        // 真实统计数字永远到不了这个量级，纯防御性用例
        val result = ProfileText.formatLargeNumber(Long.MIN_VALUE)
        assertEquals("负号只应有一个", 1, result.count { it == '-' })
        assertEquals("-9223372036.8B", result)
    }

    // ── formatUid：前 3 + 后 3 ─────────────────────────

    @Test
    fun `UID 取前 3 后 3`() {
        // utils/func.ts:277-279
        assertEquals("178...003", ProfileText.formatUid("1780977720500996003"))
    }

    @Test
    fun `空 UID 返回空串`() {
        assertEquals("", ProfileText.formatUid(""))
    }

    @Test
    fun `短 UID 照切与 RN 一致而不是原样返回`() {
        // RN 不做长度检查：substring(0,3) + '...' + substring(len-3)
        // "ab" → "ab" + "..." + "ab" = "ab...ab"。
        // 加"长度不足原样返回"的优化会与现网不同
        assertEquals("ab...ab", ProfileText.formatUid("ab"))
    }
}
