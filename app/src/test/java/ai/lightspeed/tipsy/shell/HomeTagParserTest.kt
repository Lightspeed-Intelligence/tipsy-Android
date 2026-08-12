package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.home.parseTagsForTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标签目录解析（`POST /character/tags`）。
 *
 * 这一层三种错法都**不报错**，只是筛选面板内容不对：
 * 排序漏了 → 每次冷启动顺序变；`show_in_filter` 判反 → 标签集体消失；
 * `desc`/`alias` 取反 → 显示内部别名。
 */
class HomeTagParserTest {

    private fun parse(json: String) = parseTagsForTest(JSONObject(json))

    @Test
    fun `按 sort_order 升序排，不按返回序`() {
        val tags = parse(
            """{"tags":[
              {"tag_id":"t3","desc":"C","sort_order":30},
              {"tag_id":"t1","desc":"A","sort_order":10},
              {"tag_id":"t2","desc":"B","sort_order":20}]}""",
        )
        assertEquals(listOf("t1", "t2", "t3"), tags.map { it.id })
    }

    @Test
    fun `sort_order 相同时保持返回序（稳定排序）`() {
        val tags = parse(
            """{"tags":[
              {"tag_id":"b","desc":"B","sort_order":5},
              {"tag_id":"a","desc":"A","sort_order":5}]}""",
        )
        assertEquals(listOf("b", "a"), tags.map { it.id })
    }

    @Test
    fun `label 取 desc`() {
        val tags = parse("""{"tags":[{"tag_id":"t1","desc":"浪漫","alias":"romance"}]}""")
        // ⚠️ 不是 alias —— 取反会让用户看到内部别名
        assertEquals("浪漫", tags.single().label)
    }

    @Test
    fun `desc 为空时回落 alias`() {
        val tags = parse("""{"tags":[{"tag_id":"t1","desc":"","alias":"romance"}]}""")
        assertEquals("romance", tags.single().label)
    }

    @Test
    fun `show_in_filter 缺失时要显示`() {
        // ⚠️ 判据是「!== false」不是「=== true」：写成后者会让所有
        // 没带该字段的标签消失
        val tags = parse("""{"tags":[{"tag_id":"t1","desc":"A"}]}""")
        assertEquals(1, tags.size)
    }

    @Test
    fun `show_in_filter 为 false 的被剔除`() {
        val tags = parse(
            """{"tags":[
              {"tag_id":"t1","desc":"A","show_in_filter":false},
              {"tag_id":"t2","desc":"B","show_in_filter":true}]}""",
        )
        assertEquals(listOf("t2"), tags.map { it.id })
    }

    @Test
    fun `id 或 label 为空的项被丢弃`() {
        val tags = parse(
            """{"tags":[
              {"tag_id":"","desc":"无 id"},
              {"tag_id":"t2","desc":"","alias":""},
              {"tag_id":"t3","desc":"好的"}]}""",
        )
        assertEquals(listOf("t3"), tags.map { it.id })
    }

    @Test
    fun `isNew 与 isEvent 缺失时为 false`() {
        val tags = parse("""{"tags":[{"tag_id":"t1","desc":"A"}]}""")
        val tag = tags.single()
        assertTrue(!tag.isNew && !tag.isEvent)
    }

    @Test
    fun `isNew 与 isEvent 正确读取`() {
        val tags = parse(
            """{"tags":[{"tag_id":"t1","desc":"A","is_new":true,"is_event":true}]}""",
        )
        val tag = tags.single()
        assertTrue(tag.isNew && tag.isEvent)
    }

    @Test
    fun `缺 tags 字段返回空表而不抛`() {
        assertTrue(parse("""{"total":0}""").isEmpty())
    }

    @Test
    fun `数组里的非对象项被跳过而不整表丢`() {
        val tags = parse("""{"tags":["坏项",{"tag_id":"t1","desc":"A"}]}""")
        assertEquals(listOf("t1"), tags.map { it.id })
    }
}
