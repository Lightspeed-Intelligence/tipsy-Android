package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.search.CharacterSearchOutcome
import ai.lightspeed.tipsy.shell.pages.search.SearchText
import ai.lightspeed.tipsy.shell.pages.search.shouldShowCreateCharacterButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空态按钮判定 + 计数缩写。
 *
 * 前半部分是 `app/search/searchEmptyState.test.ts`（67 行，5 个用例）的
 * **一比一对等移植** —— RN 侧有现成测试，直接作为对等 fixture 用，
 * 不重新设计用例（方案 §8.1「已有测试」栏的用法）。
 */
class SearchEmptyStateTest {

    // ── 空态按钮：照搬 RN 五例 ────────────────────────────────

    @Test
    fun `安全搜索无结果时显示创建按钮`() {
        assertTrue(
            shouldShowCreateCharacterButton(
                query = "unknown character",
                outcome = CharacterSearchOutcome.SAFE,
                loading = false,
                resultCount = 0,
            ),
        )
    }

    @Test
    fun `直接命中敏感词不显示创建按钮`() {
        assertFalse(
            "direct 命中是合规要求：不给创建出口",
            shouldShowCreateCharacterButton(
                query = "blocked character",
                outcome = CharacterSearchOutcome.DIRECT,
                loading = false,
                resultCount = 0,
            ),
        )
    }

    @Test
    fun `关联命中敏感词仍显示创建按钮`() {
        assertTrue(
            "related 与 direct 的分道处 —— 写成「敏感就不给」会误伤 related",
            shouldShowCreateCharacterButton(
                query = "related character",
                outcome = CharacterSearchOutcome.RELATED,
                loading = false,
                resultCount = 0,
            ),
        )
    }

    @Test
    fun `还没成功返回过或仍在加载时不显示创建按钮`() {
        assertFalse(
            "IDLE = 请求失败或还没搜；此时显示按钮会把「网络错误」误导成「没这个角色」",
            shouldShowCreateCharacterButton(
                query = "unknown character",
                outcome = CharacterSearchOutcome.IDLE,
                loading = false,
                resultCount = 0,
            ),
        )
        assertFalse(
            "loading 中结果数恒 0，不挡住会闪一下按钮",
            shouldShowCreateCharacterButton(
                query = "unknown character",
                outcome = CharacterSearchOutcome.SAFE,
                loading = true,
                resultCount = 0,
            ),
        )
    }

    @Test
    fun `有结果时不显示创建按钮`() {
        assertFalse(
            shouldShowCreateCharacterButton(
                query = "known character",
                outcome = CharacterSearchOutcome.SAFE,
                loading = false,
                resultCount = 1,
            ),
        )
    }

    /** RN 的 `!!query.trim()` —— 纯空白不算有查询词。 */
    @Test
    fun `空白查询词不显示创建按钮`() {
        assertFalse(
            shouldShowCreateCharacterButton(
                query = "   ",
                outcome = CharacterSearchOutcome.SAFE,
                loading = false,
                resultCount = 0,
            ),
        )
    }

    // ── 敏感类型映射 ────────────────────────────────

    @Test
    fun `敏感类型缺失时是 SAFE 而不是 IDLE`() {
        assertEquals(CharacterSearchOutcome.SAFE, CharacterSearchOutcome.fromResponse(null))
        assertEquals(CharacterSearchOutcome.DIRECT, CharacterSearchOutcome.fromResponse("direct"))
        assertEquals(CharacterSearchOutcome.RELATED, CharacterSearchOutcome.fromResponse("related"))
        // 未知值按 SAFE 处理（不认识的敏感类型不该把创建出口也关掉）
        assertEquals(CharacterSearchOutcome.SAFE, CharacterSearchOutcome.fromResponse("whatever"))
    }

    // ── 四位数计数缩写 ────────────────────────────────

    /**
     * 缩写门槛是 **10000**，不是 1000 —— 与 Profile 卡片的三位数规则分道。
     * 写错的表现是「粉丝 1200 显示成 1.2K」，看着合理但与线上不一致。
     */
    @Test
    fun `计数万以下原样显示`() {
        assertEquals("0", SearchText.formatCountMaxFourDigits(0))
        assertEquals("999", SearchText.formatCountMaxFourDigits(999))
        assertEquals("1000", SearchText.formatCountMaxFourDigits(1000))
        assertEquals("9999", SearchText.formatCountMaxFourDigits(9999))
    }

    /**
     * ⚠️ 期望值全部取自 RN 实现的**实跑输出**（`formatCountMaxFourDigits`
     * 逐值对过），不是我推的。有两个反直觉点，都是 RN 的真实行为：
     *
     * - `1_000_000` → **`1000K`** 而不是 `1M`：循环条件是 `>= 10000`，
     *   缩到 1000K 就停了（1000 < 10000）。要到 9_999_999 才会进 `M`。
     * - `999_999` → `1000K`：四位有效数字把 999.999K 进位成 1000K，
     *   而 1000 < 10000 所以**不再晋位**。
     *
     * 把这两条"修正"成数学上更漂亮的 `1M` 会与线上不一致。
     */
    @Test
    fun `计数满万起缩写且保四位有效数字`() {
        assertEquals("10K", SearchText.formatCountMaxFourDigits(10_000))
        assertEquals("10K", SearchText.formatCountMaxFourDigits(10_001))
        assertEquals("12.35K", SearchText.formatCountMaxFourDigits(12_345))
        assertEquals("100K", SearchText.formatCountMaxFourDigits(99_999))
        assertEquals("999.9K", SearchText.formatCountMaxFourDigits(999_900))
        // 下面两条是上述反直觉点
        assertEquals("1000K", SearchText.formatCountMaxFourDigits(999_999))
        assertEquals("1000K", SearchText.formatCountMaxFourDigits(1_000_000))
        assertEquals("10M", SearchText.formatCountMaxFourDigits(9_999_999))
        assertEquals("12.35M", SearchText.formatCountMaxFourDigits(12_345_678))
        assertEquals("10B", SearchText.formatCountMaxFourDigits(10_000_000_000))
    }

    @Test
    fun `计数负值保留符号`() {
        assertEquals("-15K", SearchText.formatCountMaxFourDigits(-15_000))
    }

    @Test
    fun `计数缺失显示 0`() {
        assertEquals("0", SearchText.formatCountMaxFourDigits(null))
    }
}
