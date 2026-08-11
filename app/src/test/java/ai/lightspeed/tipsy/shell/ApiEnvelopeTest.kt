package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.network.ApiEnvelope
import ai.lightspeed.tipsy.shell.network.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统一 envelope 解析测试（W1-P6）。
 *
 * 重点是两条：**HTTP 200 + code != 0 必须识别为失败**，
 * 以及**已知业务码保持可分辨**（压平后 UI 会把「宝石不足」显示成「网络错误」）。
 */
class ApiEnvelopeTest {

    @Test
    fun `解析成功响应`() {
        val e = ApiEnvelope.parse("""{"code":0,"msg":"ok","data":{"id":1}}""")
        assertTrue(e.isSuccess)
        assertEquals(0, e.code)
        assertNotNull(e.data)
    }

    @Test
    fun `code 非 0 即失败 即使 HTTP 是 200`() {
        val e = ApiEnvelope.parse("""{"code":6,"msg":"not enough gems","data":null}""")
        assertFalse("HTTP 200 + code != 0 是常见组合，必须识别为失败", e.isSuccess)
        assertEquals(6, e.code)
    }

    /** 业务码常量逐条对齐 RN `AppRespCode`（实测 `src/types/api.ts`）。 */
    @Test
    fun `已知业务码常量与 RN 一致`() {
        assertEquals(0, ApiEnvelope.CODE_SUCCESS)
        assertEquals(2, ApiEnvelope.CODE_INVALID_PARAMETER)
        assertEquals(3, ApiEnvelope.CODE_INTERNAL_ERROR)
        assertEquals(4, ApiEnvelope.CODE_GENERATING)
        assertEquals(5, ApiEnvelope.CODE_NOT_ENOUGH_MEMBERSHIP)
        assertEquals(6, ApiEnvelope.CODE_NOT_ENOUGH_GEMS)
        assertEquals(16, ApiEnvelope.CODE_NOT_ENOUGH_CLOVER)
        assertEquals(17, ApiEnvelope.CODE_DUPLICATE_MESSAGE)
    }

    /**
     * ⚠️ 9（角色卡超限）**不在** RN 的 `AppRespCode` 枚举里 ——
     * 它是 `axios.ts:221` 的字面量 `response?.data?.code === 9`。
     * 别因为「枚举里没有」就当它不存在。
     */
    @Test
    fun `角色卡超限码 9 存在 尽管不在 RN 枚举里`() {
        assertEquals(9, ApiEnvelope.CODE_ROLE_CARD_LIMIT)
    }

    @Test
    fun `三个关键业务码可分辨 不被压平`() {
        listOf(
            6 to { e: ApiException.Business -> e.isNotEnoughGems },
            9 to { e: ApiException.Business -> e.isRoleCardLimit },
            16 to { e: ApiException.Business -> e.isNotEnoughClover },
        ).forEach { (code, check) ->
            val ex = ApiException.Business(code, "msg")
            assertTrue("code=$code 必须可分辨，否则 UI 会显示成通用网络错误", check(ex))
        }
    }

    @Test
    fun `不同业务码互不误判`() {
        val gems = ApiException.Business(6, null)
        assertTrue(gems.isNotEnoughGems)
        assertFalse(gems.isRoleCardLimit)
        assertFalse(gems.isNotEnoughClover)
    }

    // ── data 的多种形态 ───────────────────────────────────────

    @Test
    fun `data 为数组时放进 dataArray`() {
        val e = ApiEnvelope.parse("""{"code":0,"data":[1,2,3]}""")
        assertNull(e.data)
        assertNotNull(e.dataArray)
        assertEquals(3, e.dataArray!!.length())
    }

    @Test
    fun `data 缺失或为 null 时成功仍成立`() {
        assertTrue(ApiEnvelope.parse("""{"code":0}""").isSuccess)
        assertTrue(ApiEnvelope.parse("""{"code":0,"data":null}""").isSuccess)
    }

    // ── msg 的 null 处理 ──────────────────────────────────────

    @Test
    fun `msg 为 JSON null 时返回 null 而非字面量`() {
        assertNull(ApiEnvelope.parse("""{"code":0,"msg":null}""").msg)
        assertNull(ApiEnvelope.parse("""{"code":0,"msg":"null"}""").msg)
        assertNull(ApiEnvelope.parse("""{"code":0,"msg":""}""").msg)
    }

    // ── 标量漂移：code 发成字符串 ──────────────────────────────

    /**
     * code 本身也可能漂移。这条尤其关键：若 code 解析失败就当成 0（成功），
     * 会把**所有业务错误静默当成功**。
     */
    @Test
    fun `code 为字符串时仍能解析`() {
        val e = ApiEnvelope.parse("""{"code":"6","msg":"gems"}""")
        assertEquals(6, e.code)
        assertFalse(e.isSuccess)
    }

    // ── 结构错误：抛而不是假成功 ────────────────────────────────

    @Test
    fun `非 JSON 抛 Malformed`() {
        assertThrows(ApiException.Malformed::class.java) { ApiEnvelope.parse("not json") }
        assertThrows(ApiException.Malformed::class.java) { ApiEnvelope.parse("") }
    }

    @Test
    fun `缺少 code 字段抛 Malformed 而不是当成功`() {
        val ex = assertThrows(ApiException.Malformed::class.java) {
            ApiEnvelope.parse("""{"msg":"ok","data":{}}""")
        }
        assertTrue(ex.message!!.contains("code"))
    }

    @Test
    fun `code 无法解析时抛 Malformed 不默认成 0`() {
        assertThrows(
            "code 不可解析时若默认成 0，所有业务错误会被静默当成功",
            ApiException.Malformed::class.java,
        ) { ApiEnvelope.parse("""{"code":"abc"}""") }
    }

    /** JSON 数组作为顶层响应不符合 envelope 约定。 */
    @Test
    fun `顶层数组抛 Malformed`() {
        assertThrows(ApiException.Malformed::class.java) { ApiEnvelope.parse("""[1,2,3]""") }
    }
}
