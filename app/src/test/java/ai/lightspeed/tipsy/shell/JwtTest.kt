package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.auth.Jwt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Jwt] 与 RN `src/lib/auth/jwt.ts` 的**逐行对齐**测试。
 *
 * 这里每条断言都对应 RN 侧一个具体行为，**不是**"合理的 JWT 处理"。
 * 两侧不一致的后果是只在特定剩余时长窗口出现的间歇性登录问题。
 */
class JwtTest {

    private val now = 1_700_000_000L

    @Test
    fun `阈值是 5 分钟 与 RN 一致`() {
        assertEquals("改这个值必须同步改 RN 侧 jwt.ts:102", 300, Jwt.EXPIRING_SOON_SECONDS)
    }

    // ── isExpiringSoon ────────────────────────────────────────

    @Test
    fun `剩余 4 分钟 算即将过期`() {
        val token = tokenWithExp(now + 240)
        assertTrue(Jwt.isExpiringSoon(token, now))
    }

    @Test
    fun `剩余 6 分钟 不算即将过期`() {
        val token = tokenWithExp(now + 360)
        assertFalse(Jwt.isExpiringSoon(token, now))
    }

    /**
     * ⚠️ **本文件最重要的一条**。RN 的条件是 `exp - now > 0 && exp - now < 300`，
     * 所以**已过期的 token 返回 false**，不触发刷新。
     *
     * 看起来像 bug，但这是现网已验证的行为：已过期 token 会被拿去发请求、
     * 得到 401，再走 authRejected 兜底。壳照搬这条。
     * 若有人"修正"成 `< 300`（去掉 `> 0`），已过期 token 会走刷新路径，
     * 与 RN 行为分叉 —— 这个测试会挡住那次修改。
     */
    @Test
    fun `已过期的 token 不算即将过期 照搬 RN 的 exp 减 now 大于 0 条件`() {
        val expired = tokenWithExp(now - 60)
        assertFalse(
            "RN jwt.ts:102 的条件含 `exp - now > 0`，已过期返回 false。" +
                "改成 true 会让已过期 token 走刷新路径，与现网行为分叉",
            Jwt.isExpiringSoon(expired, now),
        )
    }

    @Test
    fun `正好到期 不算即将过期`() {
        assertFalse(Jwt.isExpiringSoon(tokenWithExp(now), now))
    }

    @Test
    fun `无 exp 字段 视为即将过期`() {
        val token = tokenWithPayload(JSONObject().put("sub", "u1"))
        assertTrue("RN 注释：如果没有 exp 字段，假设即将过期", Jwt.isExpiringSoon(token, now))
    }

    @Test
    fun `解析失败 返回 false`() {
        assertFalse(Jwt.isExpiringSoon("not-a-jwt", now))
        assertFalse(Jwt.isExpiringSoon("", now))
    }

    // ── hasNotExpired ─────────────────────────────────────────

    @Test
    fun `未过期返回 true`() {
        assertTrue(Jwt.hasNotExpired(tokenWithExp(now + 10), now))
    }

    @Test
    fun `已过期返回 false`() {
        assertFalse(Jwt.hasNotExpired(tokenWithExp(now - 10), now))
    }

    @Test
    fun `正好到期算已过期 对齐 RN 的大于等于判定`() {
        assertFalse(
            "RN 用 `currentTime >= payload.exp` 判定过期",
            Jwt.hasNotExpired(tokenWithExp(now), now),
        )
    }

    @Test
    fun `无 exp 字段视为未过期`() {
        val token = tokenWithPayload(JSONObject().put("sub", "u1"))
        assertTrue(Jwt.hasNotExpired(token, now))
    }

    @Test
    fun `解析失败视为已过期`() {
        assertFalse("解析不了的 token 不能当作可用", Jwt.hasNotExpired("garbage", now))
    }

    // ── subject ───────────────────────────────────────────────

    @Test
    fun `取得 sub`() {
        assertEquals("u42", Jwt.subject(tokenWithPayload(JSONObject().put("sub", "u42"))))
    }

    @Test
    fun `sub 为 JSON null 时返回 null 而非字面量`() {
        val token = tokenWithPayload(JSONObject().put("sub", JSONObject.NULL))
        assertNull("optString 对 JSON null 返回字面量 \"null\"，必须特判", Jwt.subject(token))
    }

    // ── base64url 细节 ─────────────────────────────────────────

    /**
     * JWT 用 base64**url**（`-`/`_` 替代 `+`/`/`）且常省略 padding。
     * 用标准 base64 解码会在含这些字符的 payload 上失败 ——
     * 症状是"部分用户的 token 解析不了"，取决于 payload 内容碰巧有没有这些字符。
     */
    @Test
    fun `payload 含 base64url 特殊字符时仍可解析`() {
        // 构造一个编码后必然含 `-` 或 `_` 的 payload
        val payload = JSONObject()
            .put("exp", now + 240)
            .put("sub", "u1")
            .put("pad", "???~~~ÿþ")
        val token = tokenWithPayload(payload)
        assertEquals("u1", Jwt.subject(token))
        assertTrue(Jwt.isExpiringSoon(token, now))
    }

    private fun tokenWithExp(exp: Long): String =
        tokenWithPayload(JSONObject().put("exp", exp).put("sub", "u1"))

    private fun tokenWithPayload(payload: JSONObject): String {
        val header = encode("""{"alg":"HS256","typ":"JWT"}""")
        val body = encode(payload.toString())
        return "$header.$body.fake-signature"
    }

    /**
     * base64url 编码器（测试侧）。同样不用 `android.util.Base64`（JVM stub 会抛）
     * 也不用 `java.util.Base64`（API 26 > minSdk 24），理由见 [Jwt.decodeBase64Url]。
     *
     * 这里刻意**不复用**被测代码的解码逻辑 —— 编码与解码各自独立实现，
     * 才能真正验证解码正确；用同一份逻辑往返只能证明它自洽。
     */
    private fun encode(json: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val bytes = json.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1

            sb.append(alphabet[b0 shr 2])
            if (b1 < 0) {
                sb.append(alphabet[(b0 and 0x03) shl 4])
            } else {
                sb.append(alphabet[((b0 and 0x03) shl 4) or (b1 shr 4)])
                if (b2 < 0) {
                    sb.append(alphabet[(b1 and 0x0F) shl 2])
                } else {
                    sb.append(alphabet[((b1 and 0x0F) shl 2) or (b2 shr 6)])
                    sb.append(alphabet[b2 and 0x3F])
                }
            }
            i += 3
        }
        return sb.toString() // 无 padding，与 JWT 惯例一致
    }
}
