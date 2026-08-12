package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.profile.ProfileText
import ai.lightspeed.tipsy.shell.pages.profile.ProfileWallet
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钱包解析与两套钱包数字格式化。
 *
 * 格式化是本页第四、五套数字规则（统计的去尾 K/M、Home 的千分位、
 * 记忆的时间之外），挑错函数不报错、只是数字与现网对不上。
 */
class ProfileWalletTest {

    // ── 解析 ────────────────────────────────────────

    @Test
    fun `完整字段解析`() {
        val w = ProfileWallet.parse(
            JSONObject()
                .put("gem_amount", 120)
                .put("has_inf_msg", false)
                .put("left_free_msg_amount", 30)
                .put("coin_amount", 4.56),
        )
        assertEquals(120L, w.gemAmount)
        assertFalse(w.freeAmountIsUnlimited)
        assertEquals(30L, w.leftFreeAmount)
        assertEquals(4.56, w.coinAmount, 1e-9)
    }

    @Test
    fun `缺字段一律归零`() {
        // 对齐 RN 的 `?? 0`（useUserWallet.tsx:38-46）
        val w = ProfileWallet.parse(JSONObject())
        assertEquals(0L, w.gemAmount)
        assertEquals(0L, w.leftFreeAmount)
        assertEquals(0.0, w.coinAmount, 1e-9)
        assertFalse(w.freeAmountIsUnlimited)
        assertEquals(ProfileWallet.EMPTY, ProfileWallet.parse(null))
    }

    @Test
    fun `has_inf_msg 为 true 时标记无限量`() {
        val w = ProfileWallet.parse(
            JSONObject().put("has_inf_msg", true).put("left_free_msg_amount", 7),
        )
        assertTrue(w.freeAmountIsUnlimited)
        // 数值仍保留 —— 显示层决定用不用（RN 显示硬编码 100）
        assertEquals(7L, w.leftFreeAmount)
    }

    // ── 档位 ────────────────────────────────────────

    @Test
    fun `档位名映射对齐 MemberShipTierName`() {
        assertEquals("Free", ProfileWallet(planId = 0).planNameKey)
        assertEquals("Get a Taste", ProfileWallet(planId = 1).planNameKey)
        assertEquals("Standard", ProfileWallet(planId = 2).planNameKey)
        assertEquals("Premium", ProfileWallet(planId = 3).planNameKey)
        assertEquals("Deluxe", ProfileWallet(planId = 4).planNameKey)
        assertEquals("On Trial", ProfileWallet(planId = 5).planNameKey)
    }

    @Test
    fun `未知档位回落 Free`() {
        // 新档位上线时不崩、显示保守值
        assertEquals("Free", ProfileWallet(planId = 99).planNameKey)
        assertTrue(ProfileWallet(planId = 0).isFreePlan)
        assertFalse(ProfileWallet(planId = 3).isFreePlan)
    }

    // ── 钱包整数（formatMessageAmount：裸 toLocaleString）──

    @Test
    fun `钱包整数是千分位不做 K 换算`() {
        assertEquals("0", ProfileText.formatWalletAmount(0))
        assertEquals("999", ProfileText.formatWalletAmount(999))
        // ⚠️ 与统计的 formatLargeNumber 分道处：1000 显示 1,000 不是 1K
        assertEquals("1,000", ProfileText.formatWalletAmount(1000))
        assertEquals("1,234,567", ProfileText.formatWalletAmount(1234567))
    }

    // ── 金币（formatCoinAmount：去尾一位小数 + 千分位 + 恒带小数）──

    @Test
    fun `金币恒带一位小数`() {
        assertEquals("0.0", ProfileText.formatCoinAmount(0.0))
        assertEquals("4.5", ProfileText.formatCoinAmount(4.5))
        assertEquals("1,234.5", ProfileText.formatCoinAmount(1234.5))
    }

    @Test
    fun `金币去尾不四舍五入`() {
        // RN 是 floor(x*10+1e-8)/10：0.19 → 0.1，不是 0.2
        assertEquals("0.1", ProfileText.formatCoinAmount(0.19))
        assertEquals("2.9", ProfileText.formatCoinAmount(2.999))
        // 1e-8 容差：0.1 的浮点表示（0.0999...）不得被砍成 0.0
        assertEquals("0.1", ProfileText.formatCoinAmount(0.1))
    }

    @Test
    fun `金币负值与非数归零`() {
        assertEquals("0.0", ProfileText.formatCoinAmount(-3.2))
        assertEquals("0.0", ProfileText.formatCoinAmount(Double.NaN))
    }
}
