package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.surface.JsonRouteParams
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON → route params 的结构保真（P5 `editCharacter` 透传的前提）。
 *
 * 这里错了的表现全在**保存时**：字段丢失/错位让 RN 的 `initCharStateUpdate`
 * 预填出残缺 store，保存把残缺写回服务端 —— 数据损坏且当场无感。
 */
class JsonRouteParamsTest {

    @Test
    fun `标量与嵌套对象原样转换`() {
        val params = JsonRouteParams.toParams(
            JSONObject(
                """{"s":"text","i":7,"b":true,"d":1.5,
                    "nested":{"inner":"v"}}""",
            ),
        )
        assertEquals("text", params["s"])
        assertEquals(7, params["i"])
        assertEquals(true, params["b"])
        assertEquals(1.5, params["d"])
        @Suppress("UNCHECKED_CAST")
        val nested = params["nested"] as Map<String, Any>
        assertEquals("v", nested["inner"])
    }

    @Test
    fun `数组保序且支持对象元素`() {
        val params = JsonRouteParams.toParams(
            JSONObject("""{"tags":["a","b"],"books":[{"id":"w1"},{"id":"w2"}]}"""),
        )
        assertEquals(listOf("a", "b"), params["tags"])
        @Suppress("UNCHECKED_CAST")
        val books = params["books"] as List<Map<String, Any>>
        assertEquals(listOf("w1", "w2"), books.map { it["id"] })
    }

    @Test
    fun `显式 null 保留为哨兵而不是丢键`() {
        // 「键为 null」与「键缺失」在 zustand 展开里语义不同 —— 丢 null 的
        // 表现是编辑清空过某字段的角色时旧值复活
        val params = JsonRouteParams.toParams(
            JSONObject("""{"cleared":null,"kept":"v"}"""),
        )
        assertTrue("null 键必须保留", params.containsKey("cleared"))
        assertSame(JSONObject.NULL, params["cleared"])
    }

    @Test
    fun `大整数不丢精度`() {
        // id 常是 19 位数字（进度 §2.25 的科学计数法教训同源）：
        // 必须以 Long 透传，折成 Double 会舍入
        val params = JsonRouteParams.toParams(
            JSONObject().put("big", 1780977720500996003L),
        )
        assertEquals(1780977720500996003L, params["big"])
    }
}
