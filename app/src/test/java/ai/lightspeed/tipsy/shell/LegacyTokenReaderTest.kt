package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.auth.LegacyTokenReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 遗留 token 解析的三形态兼容测试（方案 §2.4）。
 *
 * **少兼容一种形态 = 那批用户覆盖升级后掉登录**，所以三种都要有用例。
 * 形态来源是 `src/store/auth.ts` 的 `parseLegacyPersistedToken`（RN 自己
 * 也在兼容这三种，说明历史上确实都写过）。
 */
class LegacyTokenReaderTest {

    private val jwt =
        "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1MSIsImV4cCI6OTk5OTk5OTk5OX0.sig"

    // ── 三种历史形态 ──────────────────────────────────────────

    @Test
    fun `形态1 裸字符串（当前 RN 版本写入）`() {
        assertEquals(jwt, LegacyTokenReader.parse(jwt))
    }

    @Test
    fun `形态2 Zustand persist 信封`() {
        val raw = """{"state":{"token":"$jwt","user":null},"version":0}"""
        assertEquals(jwt, LegacyTokenReader.parse(raw))
    }

    @Test
    fun `形态3 半迁移形态`() {
        assertEquals(jwt, LegacyTokenReader.parse("""{"token":"$jwt"}"""))
    }

    // ── 边界：必须安全返回 null，不能抛 ─────────────────────────

    @Test
    fun `空与空白返回 null`() {
        assertNull(LegacyTokenReader.parse(null))
        assertNull(LegacyTokenReader.parse(""))
        assertNull(LegacyTokenReader.parse("   "))
    }

    @Test
    fun `畸形 JSON 返回 null 而不抛`() {
        assertNull(LegacyTokenReader.parse("""{"state":"""))
        assertNull(LegacyTokenReader.parse("{{{"))
    }

    @Test
    fun `信封存在但无 token 字段返回 null`() {
        assertNull(LegacyTokenReader.parse("""{"state":{"user":"u1"},"version":0}"""))
        assertNull(LegacyTokenReader.parse("""{"version":0}"""))
    }

    /**
     * 关键边界：`JSONObject.optString` 遇到 JSON null 会返回**字面量 "null"**。
     * 不特判就会把「字段是 null」当成一个叫 "null" 的 token 存进去 ——
     * 静默错值，后续请求全部 401，且很难反推到这里。
     */
    @Test
    fun `JSON null 不得被当成字符串 null`() {
        assertNull(LegacyTokenReader.parse("""{"token":null}"""))
        assertNull(LegacyTokenReader.parse("""{"state":{"token":null}}"""))
        assertNull(LegacyTokenReader.parse("""{"state":{"token":"null"}}"""))
    }

    @Test
    fun `token 为空串视为无 token`() {
        assertNull(LegacyTokenReader.parse("""{"token":""}"""))
        assertNull(LegacyTokenReader.parse("""{"state":{"token":"  "}}"""))
    }

    /** 信封优先于顶层 —— 两者都在时以 state.token 为准（RN 的读取顺序）。 */
    @Test
    fun `信封优先于顶层 token`() {
        val raw = """{"state":{"token":"$jwt"},"token":"stale"}"""
        assertEquals(jwt, LegacyTokenReader.parse(raw))
    }

    /** 裸串不做 JWT 结构校验 —— 校验是调用方职责，这里只如实取出。 */
    @Test
    fun `裸串不校验 JWT 结构`() {
        assertEquals("not-a-jwt", LegacyTokenReader.parse("not-a-jwt"))
    }
}
