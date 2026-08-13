package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.chatlist.ChatThread
import ai.lightspeed.tipsy.shell.pages.chatlist.ChatThreadPage
import ai.lightspeed.tipsy.shell.pages.chatlist.RelationshipStat
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/user/chatted/list` 响应解析（W3 ChatList P1）。
 *
 * 重点在「错了不报错」的三处：标量漂移（数字/字符串互串）、
 * 未知 item_type 丢弃、game 的 id 归属分流。
 */
class ChatThreadParserTest {

    @Test
    fun `解析一条普通角色会话`() {
        val thread = ChatThread.parse(
            JSONObject(
                """{"item_type":"character","item_id":"c1","item_name":"Emi",
                   "face_url":"https://x.tipsy.chat/f.png","image_url":"i.png",
                   "introduction":"intro","greeting":"hi","message_num":"3",
                   "latest_time":1700000000,"nsfw":false,"is_pinned":true,
                   "public_background":"bg","character_id":"c1"}""",
            ),
        )!!
        assertEquals("character", thread.itemType)
        assertEquals("c1", thread.itemId)
        assertEquals("Emi", thread.itemName)
        assertEquals(1700000000L, thread.latestTimeSeconds)
        assertTrue(thread.isPinned)
        assertFalse(thread.isMiniPhone)
    }

    @Test
    fun `latest_time 是字符串时容错解析`() {
        // dev/prod 的标量漂移（方案 §4.5）：TS 标 number 的字段可能返 string
        val thread = ChatThread.parse(
            JSONObject(
                """{"item_type":"character","item_id":"c1","item_name":"n",
                   "face_url":"","image_url":"","introduction":"",
                   "latest_time":"1700000000","is_pinned":false,
                   "public_background":"","message_num":"0","nsfw":false}""",
            ),
        )!!
        assertEquals(1700000000L, thread.latestTimeSeconds)
    }

    @Test
    fun `未知 item_type 丢弃而不是崩`() {
        assertNull(
            ChatThread.parse(
                JSONObject("""{"item_type":"hologram","item_id":"x","item_name":"n"}"""),
            ),
        )
    }

    @Test
    fun `game 缺 game_id 是脏数据丢弃`() {
        assertNull(
            ChatThread.parse(
                JSONObject("""{"item_type":"game","item_id":"x","item_name":"n"}"""),
            ),
        )
    }

    @Test
    fun `非 game 缺 item_id 丢弃`() {
        assertNull(
            ChatThread.parse(
                JSONObject("""{"item_type":"character","item_name":"n"}"""),
            ),
        )
    }

    @Test
    fun `game 用 game_id 做 stableKey`() {
        val thread = ChatThread.parse(
            JSONObject(
                """{"item_type":"game","game_id":"g9","item_name":"n",
                   "face_url":"","image_url":"","introduction":"",
                   "latest_time":1,"is_pinned":false,"public_background":"",
                   "message_num":"0","nsfw":false}""",
            ),
        )!!
        assertTrue(thread.stableKey.contains("g9"))
    }

    @Test
    fun `mini_phone 的 stableKey 按 conversation_id 区分`() {
        fun mini(conv: String) = ChatThread.parse(
            JSONObject(
                """{"item_type":"character","item_id":"c1","item_name":"n",
                   "chat_mode":"mini_phone","conversation_id":"$conv",
                   "face_url":"","image_url":"","introduction":"",
                   "latest_time":1,"is_pinned":false,"public_background":"",
                   "message_num":"0","nsfw":false}""",
            ),
        )!!
        // 同角色两个小手机入口必须是不同 key —— 相同会让 LazyColumn 崩
        assertTrue(mini("v1").stableKey != mini("v2").stableKey)
    }

    @Test
    fun `matches 对 mini_phone 比三元组`() {
        fun mini(conv: String?) = ChatThread.parse(
            JSONObject(
                """{"item_type":"character","item_id":"c1","item_name":"n",
                   "chat_mode":"mini_phone",
                   ${if (conv != null) "\"conversation_id\":\"$conv\"," else ""}
                   "face_url":"","image_url":"","introduction":"",
                   "latest_time":1,"is_pinned":false,"public_background":"",
                   "message_num":"0","nsfw":false}""",
            ),
        )!!
        // 只比 item_id 会把同角色其它小手机入口一起删掉（RN index.tsx:158-163）
        assertFalse(mini("v1").matches(mini("v2")))
        assertTrue(mini("v1").matches(mini("v1")))
    }

    @Test
    fun `creator 三级兜底取到嵌套 user_id`() {
        val thread = ChatThread.parse(
            JSONObject(
                """{"item_type":"game","game_id":"g1","item_name":"n",
                   "creator":{"user_id":12345},
                   "face_url":"","image_url":"","introduction":"",
                   "latest_time":1,"is_pinned":false,"public_background":"",
                   "message_num":"0","nsfw":false}""",
            ),
        )!!
        // creator.user_id 的 TS 类型是 string|number —— number 也要转出字符串
        assertEquals("12345", thread.creatorId)
    }

    @Test
    fun `data 为 null 时对齐 RN 的空页兜底`() {
        val page = ChatThreadPage.parse(null)
        assertEquals(0, page.items.size)
        assertEquals(0L, page.total)
        assertFalse(page.hasMore)
    }

    @Test
    fun `列表里的坏条目跳过不影响好条目`() {
        val page = ChatThreadPage.parse(
            JSONObject(
                """{"total":2,"has_more":false,"list":[
                   {"item_type":"character","item_id":"good","item_name":"n",
                    "face_url":"","image_url":"","introduction":"","latest_time":1,
                    "is_pinned":false,"public_background":"","message_num":"0","nsfw":false},
                   {"item_type":"unknown_future_type","item_id":"bad","item_name":"n"}
                   ]}""",
            ),
        )
        assertEquals(1, page.items.size)
        assertEquals("good", page.items[0].itemId)
    }

    @Test
    fun `relationship 条目解析与缺省`() {
        val stat = RelationshipStat.parse(
            JSONObject(
                """{"character_id":"c1","current_sub_level":7,
                   "current_level":3,"is_relationship_open":true}""",
            ),
        )!!
        assertEquals("c1", stat.characterId)
        assertEquals(7, stat.subLevel)
        assertEquals(3, stat.level)
        assertTrue(stat.isRelationshipOpen)

        assertNull(RelationshipStat.parse(JSONObject("""{"current_level":1}""")))
    }
}
