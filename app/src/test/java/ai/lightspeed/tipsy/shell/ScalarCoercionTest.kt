package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.network.ScalarCoercion
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标量漂移容错测试（W1-P6）。
 *
 * 这类缺陷的症状是**偶发的空列表** —— 后端某次把 number 发成 string，
 * Kotlin 严格类型下整个列表解析失败，用户看到「没有内容」而后端明明返回了数据。
 * RN 从没暴露过它，因为 JS 弱类型下 `"123"` 后续运算大多仍能跑通。
 */
class ScalarCoercionTest {

    private fun json(raw: String) = JSONObject(raw)

    // ── Int ───────────────────────────────────────────────────

    @Test
    fun `int 接受数字与字符串两种形态`() {
        assertEquals(123, ScalarCoercion.optInt(json("""{"v":123}"""), "v"))
        assertEquals(123, ScalarCoercion.optInt(json("""{"v":"123"}"""), "v"))
        assertEquals(123, ScalarCoercion.optInt(json("""{"v":" 123 "}"""), "v"))
    }

    @Test
    fun `int 接受整值浮点`() {
        assertEquals(123, ScalarCoercion.optInt(json("""{"v":123.0}"""), "v"))
        assertEquals(123, ScalarCoercion.optInt(json("""{"v":"123.0"}"""), "v"))
    }

    /**
     * **非整值不静默截断**：3.7 变成 3 是个错值，会让业务拿到错的数量/金额。
     * 返回 null 让调用方决定，比悄悄给个近似值安全。
     */
    @Test
    fun `int 拒绝非整值浮点 不静默截断`() {
        assertNull("3.7 截断成 3 是错值", ScalarCoercion.optInt(json("""{"v":3.7}"""), "v"))
        assertNull(ScalarCoercion.optInt(json("""{"v":"3.7"}"""), "v"))
    }

    @Test
    fun `int 对缺失与 null 返回 null`() {
        assertNull(ScalarCoercion.optInt(json("""{}"""), "v"))
        assertNull(ScalarCoercion.optInt(json("""{"v":null}"""), "v"))
    }

    @Test
    fun `int 拒绝非数字字符串`() {
        assertNull(ScalarCoercion.optInt(json("""{"v":"abc"}"""), "v"))
        assertNull(ScalarCoercion.optInt(json("""{"v":""}"""), "v"))
    }

    // ── Long（时间戳常见）──────────────────────────────────────

    @Test
    fun `long 容忍字符串形态的时间戳`() {
        assertEquals(1_700_000_000_000L, ScalarCoercion.optLong(json("""{"t":1700000000000}"""), "t"))
        assertEquals(1_700_000_000_000L, ScalarCoercion.optLong(json("""{"t":"1700000000000"}"""), "t"))
    }

    // ── Boolean ───────────────────────────────────────────────

    @Test
    fun `boolean 接受 bool 数字与字符串`() {
        assertEquals(true, ScalarCoercion.optBoolean(json("""{"v":true}"""), "v"))
        assertEquals(false, ScalarCoercion.optBoolean(json("""{"v":false}"""), "v"))
        assertEquals(true, ScalarCoercion.optBoolean(json("""{"v":1}"""), "v"))
        assertEquals(false, ScalarCoercion.optBoolean(json("""{"v":0}"""), "v"))
        assertEquals(true, ScalarCoercion.optBoolean(json("""{"v":"true"}"""), "v"))
        assertEquals(true, ScalarCoercion.optBoolean(json("""{"v":"TRUE"}"""), "v"))
        assertEquals(false, ScalarCoercion.optBoolean(json("""{"v":"0"}"""), "v"))
    }

    /**
     * **不把任意非零数字当 true**。后端发 `2` 说明语义变了（可能是枚举而非布尔），
     * 猜成 true 会让业务走错分支且无人察觉。
     */
    @Test
    fun `boolean 只认 0 与 1 其他数字返回 null`() {
        assertNull("2 不该被猜成 true", ScalarCoercion.optBoolean(json("""{"v":2}"""), "v"))
        assertNull(ScalarCoercion.optBoolean(json("""{"v":-1}"""), "v"))
    }

    // ── String ────────────────────────────────────────────────

    @Test
    fun `string 容忍数字形态的 id`() {
        assertEquals("123", ScalarCoercion.optString(json("""{"id":123}"""), "id"))
        assertEquals("abc", ScalarCoercion.optString(json("""{"id":"abc"}"""), "id"))
    }

    /**
     * ⚠️ `optString` 的默认行为对 JSON null 返回**字面量 `"null"`**。
     * 那是个静默错值 —— `LegacyTokenReader` 与 `RefreshTokenApi` 都踩过同一个坑。
     */
    @Test
    fun `string 对 JSON null 返回 null 而非字面量`() {
        assertNull(ScalarCoercion.optString(json("""{"v":null}"""), "v"))
        assertNull("字符串 \"null\" 也当作无值", ScalarCoercion.optString(json("""{"v":"null"}"""), "v"))
    }

    @Test
    fun `string 对空串返回 null`() {
        assertNull(ScalarCoercion.optString(json("""{"v":""}"""), "v"))
    }

    // ── 不吞掉真正的结构错误 ───────────────────────────────────

    /**
     * 宽松**只针对类型漂移**，不针对结构错误。
     * 对象/数组出现在标量位置说明契约变了，返回 null 让调用方发现。
     */
    @Test
    fun `对象与数组出现在标量位置返回 null`() {
        assertNull(ScalarCoercion.optInt(json("""{"v":{"a":1}}"""), "v"))
        assertNull(ScalarCoercion.optInt(json("""{"v":[1,2]}"""), "v"))
        assertNull(ScalarCoercion.optString(json("""{"v":{"a":1}}"""), "v"))
    }
}
