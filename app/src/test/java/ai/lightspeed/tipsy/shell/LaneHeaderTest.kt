package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.network.LaneHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 泳道 header 测试（W1-P6）。逐条对齐 RN `src/utils/lane.ts:43-79`。
 *
 * **这里的白名单是安全约束，不是优化**：lane 名会暴露内部测试环境标识，
 * 发给外部域等于泄漏基础设施信息。所以「什么时候**不**发」比「什么时候发」更重要。
 */
class LaneHeaderTest {

    private val prodUrl = "https://api.tipsy.chat/api/v1/user/info"
    private val devUrl = "https://api.dev.fantacy.live/api/v1/user/info"
    private val studioUrl = "https://api-studio.infra.fantacy.live/api/v1/x"

    @Test
    fun `header 名与 RN 一致`() {
        assertEquals("X-Tipsy-Lane", LaneHeader.HEADER_NAME)
    }

    // ── 允许的情形 ────────────────────────────────────────────

    @Test
    fun `dev API host 允许`() {
        assertTrue(LaneHeader.isEligible(devUrl))
        assertEquals(mapOf("X-Tipsy-Lane" to "my-lane"), LaneHeader.headersFor("my-lane", devUrl))
    }

    @Test
    fun `dev API host 的子域允许`() {
        assertTrue(LaneHeader.isEligible("https://foo.api.dev.fantacy.live/x"))
    }

    @Test
    fun `studio host 允许`() {
        assertTrue(LaneHeader.isEligible(studioUrl))
    }

    /**
     * ⚠️ **两个 host 的匹配规则不对称**（实测 `lane.ts:59-63`）：
     * API host 含子域，studio host **仅精确匹配**。
     * 统一成「都允许子域」会放宽白名单（扩大泄漏面）。
     */
    @Test
    fun `studio host 的子域不允许 规则刻意不对称`() {
        assertFalse(
            "lane.ts 只对 DEV_API_HOST 做 endsWith，studio 是精确匹配",
            LaneHeader.isEligible("https://sub.api-studio.infra.fantacy.live/x"),
        )
    }

    // ── 拒绝的情形（安全约束）──────────────────────────────────

    @Test
    fun `生产域不发 lane`() {
        assertFalse("泳道只对 dev 有意义，发给生产会暴露内部标识", LaneHeader.isEligible(prodUrl))
        assertTrue(LaneHeader.headersFor("my-lane", prodUrl).isEmpty())
    }

    @Test
    fun `第三方域不发 lane`() {
        listOf(
            "https://evil.com/x",
            "https://api.dev.fantacy.live.evil.com/x",  // 后缀伪装
            "https://google.com/x",
        ).forEach {
            assertFalse("不得向第三方域发 lane：$it", LaneHeader.isEligible(it))
        }
    }

    @Test
    fun `http 不发 lane`() {
        assertFalse("明文传输不发内部标识", LaneHeader.isEligible("http://api.dev.fantacy.live/x"))
    }

    @Test
    fun `URL 带凭据时不发 lane`() {
        assertFalse(
            LaneHeader.isEligible("https://user:pass@api.dev.fantacy.live/x"),
        )
    }

    @Test
    fun `非 443 端口不发 lane`() {
        assertFalse(LaneHeader.isEligible("https://api.dev.fantacy.live:8443/x"))
        assertTrue("显式 443 允许", LaneHeader.isEligible("https://api.dev.fantacy.live:443/x"))
    }

    @Test
    fun `畸形 URL 不发 lane 且不抛`() {
        listOf("", "not a url", "://", "https://") .forEach {
            val r = runCatching { LaneHeader.isEligible(it) }
            assertTrue("不得抛异常：$it", r.isSuccess)
            assertFalse(r.getOrThrow())
        }
    }

    // ── lane 值本身 ───────────────────────────────────────────

    /**
     * 空串在契约里有特殊含义：**用户显式停用**。
     * 此时不发 header，且调用方不得回退到构建期默认值。
     */
    @Test
    fun `lane 为空时不发 header`() {
        assertTrue(LaneHeader.headersFor(null, devUrl).isEmpty())
        assertTrue("空串 = 用户显式停用", LaneHeader.headersFor("", devUrl).isEmpty())
        assertTrue(LaneHeader.headersFor("   ", devUrl).isEmpty())
    }

    @Test
    fun `lane 值去空白`() {
        assertEquals(mapOf("X-Tipsy-Lane" to "abc"), LaneHeader.headersFor("  abc  ", devUrl))
    }
}
