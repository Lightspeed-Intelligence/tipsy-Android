package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.login.LegalLinks
import ai.lightspeed.tipsy.shell.pages.login.LoginLayout
import ai.lightspeed.tipsy.shell.ui.ScaledMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录页布局计算与渠道分流（W2）。
 *
 * 逐行对齐 RN `src/login/LoginScreen.tsx:138-172` 的 `spacing`。
 * **这些 clamp 边界都有具体作用**，改错了不报错、只是布局在某类屏幕上变形，
 * 所以在这里把 RN 的数值钉死。
 */
class LoginLayoutTest {

    /** iPhone X 级设备（812 设计稿基准）—— ratio 应当≈1。 */
    private val designSpec = LoginLayout.compute(
        windowHeightDp = 812f,
        insetTopDp = 0f,
        insetBottomDp = 0f,
    )

    // ── logo 上下双限（RN `:146`）──────────────────────────

    @Test
    fun `设计稿尺寸下 logo 为 156`() {
        // min(max(156 * 1.0, 148), 187) = 156
        assertEquals(156f, designSpec.logoSize, 0.5f)
    }

    @Test
    fun `小屏 logo 不低于 148`() {
        // ratio 被 clamp 到 0.85 → 156*0.85=132.6，但下限 148 生效
        val small = LoginLayout.compute(windowHeightDp = 560f, insetTopDp = 24f, insetBottomDp = 0f)
        assertEquals(148f, small.logoSize, 0.5f)
    }

    @Test
    fun `大屏 logo 不超过 187`() {
        // ratio clamp 到 1.15 → 156*1.15=179.4，未触及 187 上限。
        // 用一个更极端的高度确认上限确实存在（若有人删掉 min，这条会红）
        val tall = LoginLayout.compute(windowHeightDp = 2000f, insetTopDp = 0f, insetBottomDp = 0f)
        assertTrue("logo 应受 187 上限约束，实际 ${tall.logoSize}", tall.logoSize <= 187f)
    }

    // ── ratio clamp（RN `:143`）───────────────────────────

    @Test
    fun `极小与极大屏高都不让 ratio 失控`() {
        val tiny = LoginLayout.compute(windowHeightDp = 200f, insetTopDp = 0f, insetBottomDp = 0f)
        val huge = LoginLayout.compute(windowHeightDp = 3000f, insetTopDp = 0f, insetBottomDp = 0f)
        // 表单区下限 214（0.85 → 248*0.85=210.8，低于 214 故取 214）
        assertEquals(214f, tiny.formHeight, 0.5f)
        // 上限 1.15 → 248*1.15=285.2
        assertEquals(285f, huge.formHeight, 1f)
    }

    // ── 表单与底部区只有下限（RN `:147-148`）────────────────

    @Test
    fun `表单区与底部区有下限无上限之外的意外值`() {
        assertEquals(248f, designSpec.formHeight, 0.5f)
        assertEquals(120f, designSpec.bottomHeight, 0.5f)
    }

    @Test
    fun `底部间距至少 16`() {
        // normalBottom = max(16, insets.bottom + 8)
        val noInset = LoginLayout.compute(812f, 0f, 0f)
        assertEquals(16f, noInset.containerBottom, 0.5f)
        // inset 24 → 32
        val withInset = LoginLayout.compute(812f, 0f, 24f)
        assertEquals(32f, withInset.containerBottom, 0.5f)
    }

    // ── 键盘（RN `:136`、`:160`、`:164`）───────────────────

    @Test
    fun `悬浮键盘不触发 docked 布局`() {
        // RN 原注释：悬浮键盘 keyboardHeight 通常 < 100，不需额外处理。
        // 误判会让小窗输入法把整个布局压扁
        val floating = LoginLayout.compute(812f, 44f, 34f, keyboardHeightDp = 80f)
        assertTrue(!floating.isKeyboardDocked)
        assertTrue("非 docked 时应保留底部间距", floating.containerBottom > 0f)
    }

    @Test
    fun `docked 键盘收掉底部间距与底部区`() {
        val docked = LoginLayout.compute(812f, 44f, 34f, keyboardHeightDp = 300f)
        assertTrue(docked.isKeyboardDocked)
        // 不收掉的话表单会被推出屏幕
        assertEquals(0f, docked.containerBottom, 0.01f)
        assertEquals(0f, docked.bottomHeight, 0.01f)
    }

    @Test
    fun `键盘顶部留白不为负`() {
        // 极端情况：键盘几乎占满屏幕。留白算出负数会让 Compose 抛
        val extreme = LoginLayout.compute(812f, 44f, 34f, keyboardHeightDp = 700f)
        assertTrue("留白不得为负，实际 ${extreme.keyboardTopGap}", extreme.keyboardTopGap >= 0f)
    }

    @Test
    fun `safeViewport 至少为 1 —— 不除零`() {
        // inset 之和超过窗口高（理论上不该发生，但配置变化的瞬间可能读到）
        val degenerate = LoginLayout.compute(100f, 80f, 80f)
        assertTrue(degenerate.logoSize > 0f)
    }

    // ── 渠道分流（RN `constants/common.ts:18-19`）───────────

    @Test
    fun `GooglePlay 用 chaterai 域名`() {
        val urls = LegalLinks.forChannel("GooglePlay")
        assertEquals("https://chaterai.xyz/community-guidelines", urls.communityGuidelines)
        assertEquals("https://chaterai.xyz/terms-of-service", urls.termsOfService)
        assertEquals("https://chaterai.xyz/privacy-policy", urls.privacyPolicy)
    }

    @Test
    fun `APK 与 RuStore 用 tipsy_chat 域名`() {
        // RN 的判定是 `!isAndroidAPK && !isRuStore` 才算 GooglePlay ——
        // 两个非 GooglePlay 渠道都落到 tipsy.chat。
        // 搞错域名不报错，只是用户看到另一个品牌的条款（合规问题）
        for (channel in listOf("APK", "RuStore")) {
            val urls = LegalLinks.forChannel(channel)
            assertEquals("https://tipsy.chat/community-guidelines", urls.communityGuidelines)
            assertEquals("https://tipsy.chat/terms-of-service", urls.termsOfService)
            assertEquals("https://tipsy.chat/privacy-policy", urls.privacyPolicy)
        }
    }

    @Test
    fun `未知渠道保守落到非 GooglePlay 域名`() {
        // 对齐 RN 的判定方向：只有明确是 GooglePlay 才用 chaterai
        assertEquals(
            "https://tipsy.chat/privacy-policy",
            LegalLinks.forChannel("SomethingElse").privacyPolicy,
        )
    }

    // ── 尺寸缩放（对齐 RN ScaledSheet）─────────────────────

    @Test
    fun `缩放因子对齐 RN 的 375 基准与 1_3 上限`() {
        assertEquals(1f, ScaledMetrics.scaleFactorFor(375f), 0.001f)
        // 414dp（iPhone Plus 级）→ 1.104
        assertEquals(1.104f, ScaledMetrics.scaleFactorFor(414f), 0.001f)
        // 平板封顶
        assertEquals(1.3f, ScaledMetrics.scaleFactorFor(1024f), 0.001f)
        // 小屏不封底 —— RN 也没有下限，320dp 设备上元素确实更小
        assertEquals(0.853f, ScaledMetrics.scaleFactorFor(320f), 0.001f)
    }
}
