package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.home.HomeFeedItem
import ai.lightspeed.tipsy.shell.pages.home.parseForYouForTest
import ai.lightspeed.tipsy.shell.pages.home.parsePublicListForTest
import ai.lightspeed.tipsy.shell.pages.home.parseWorldListForTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 响应解析（W2）。
 *
 * 这一层的错法是**静默错值**：字段名挑错、位置算错、把 story 当 character。
 * 两边都不报错，只是卡片显示的内容或归因不对。
 */
class HomeFeedParserTest {

    // ── For You 的 {type, data} 包装 ──────────────────────

    @Test
    fun `解析 character 型 item`() {
        val page = parseForYouForTest(
            JSONObject(
                """
                {"list":[{"type":"character","request_id":"r1","exp_id":"e1","data":{
                  "character":{"character_id":"c1","nickname":"Zara","introduction":"hi",
                    "image_url":"u","animated_image_url":"a","creator_id":"cr0",
                    "voice_supported":true,"is_translated":true,"lang":"en",
                    "character_type":1,"content_type":1,"nsfw":false,"is_chatted":true},
                  "stats":{"total_messages":229},
                  "creator":{"user_id":"cr1","nickname":"Sand"}}}]}
                """.trimIndent(),
            ),
            sessionId = "s1", page = 0, pageSize = 21, fallbackRequestId = "fb",
        )
        val item = page.items.single() as HomeFeedItem.Character
        assertEquals("c1", item.characterId)
        assertEquals("Zara", item.nickname)
        assertEquals(229L, item.totalMessages)
        assertTrue(item.voiceSupported)
        // creator.user_id 优先于 character.creator_id（HomeCard.tsx:83）
        assertEquals("cr1", item.creatorId)
        assertEquals("Sand", item.creatorNickname)
        assertEquals("r1", item.recommendation?.requestId)
        assertEquals("e1", item.recommendation?.expId)
    }

    @Test
    fun `creator 缺失时回落 character 的 creator_id`() {
        val page = parseForYouForTest(
            JSONObject(
                """{"list":[{"type":"character","data":{
                  "character":{"character_id":"c1","creator_id":"cr0"}}}]}""",
            ),
            "s", 0, 21, "fb",
        )
        val item = page.items.single() as HomeFeedItem.Character
        assertEquals("cr0", item.creatorId)
        assertNull(item.creatorNickname)
    }

    @Test
    fun `解析 story 型 item`() {
        val page = parseForYouForTest(
            JSONObject(
                """{"list":[{"type":"story","data":{
                  "story_id":"s1","title":"T","summary":"S","image_url":"u",
                  "creator_id":"cr","creator_nickname":"N","total_messages":57,"nsfw":true}}]}""",
            ),
            "sess", 0, 21, "fb",
        )
        val item = page.items.single() as HomeFeedItem.Story
        assertEquals("s1", item.storyId)
        assertEquals("T", item.title)
        assertEquals(57L, item.totalMessages)
        assertTrue(item.nsfw)
    }

    @Test
    fun `request_id 缺失时用页级 fallback`() {
        // ⚠️ 页级共用一个（home.tsx:696-706），不是逐 item 各发一个 uuid ——
        // 逐个发会让曝光/点击/进聊天的漏斗断开，且不报错
        val page = parseForYouForTest(
            JSONObject(
                """{"list":[
                  {"type":"character","data":{"character":{"character_id":"a"}}},
                  {"type":"character","data":{"character":{"character_id":"b"}}}]}""",
            ),
            "s", 0, 21, fallbackRequestId = "client_fb",
        )
        val ids = page.items.map { (it as HomeFeedItem.Character).recommendation?.requestId }
        assertEquals(listOf("client_fb", "client_fb"), ids)
    }

    @Test
    fun `空串 request_id 也走 fallback`() {
        val page = parseForYouForTest(
            JSONObject("""{"list":[{"type":"character","request_id":"  ","data":{"character":{"character_id":"a"}}}]}"""),
            "s", 0, 21, "client_fb",
        )
        assertEquals(
            "client_fb",
            (page.items.single() as HomeFeedItem.Character).recommendation?.requestId,
        )
    }

    @Test
    fun `position 按请求的 pageSize 算 —— 不用响应回显的 size`() {
        // 尾页回显"实际条数"时按回显算会让 rank 回退（iOS HomeAPI 记的坑）
        val page = parseForYouForTest(
            JSONObject(
                """{"size":3,"list":[
                  {"type":"character","data":{"character":{"character_id":"a"}}},
                  {"type":"character","data":{"character":{"character_id":"b"}}}]}""",
            ),
            sessionId = "s", page = 2, pageSize = 21, fallbackRequestId = "fb",
        )
        val positions = page.items.map { (it as HomeFeedItem.Character).recommendation?.position }
        assertEquals(listOf(2 * 21, 2 * 21 + 1), positions)
    }

    @Test
    fun `rawItemCount 是过滤前的条数`() {
        // 一页全是坏 item 时 items 空而 rawItemCount 非零 —— 到底判定要用后者，
        // 否则列表提前停在半屏
        val page = parseForYouForTest(
            JSONObject("""{"list":[{"type":"character","data":{}},{"type":"character"}]}"""),
            "s", 0, 21, "fb",
        )
        assertEquals(0, page.items.size)
        assertEquals(2, page.rawItemCount)
    }

    @Test
    fun `无 type 字段默认按 character 解析`() {
        // public_list 的 item 没有 type；For You 偶发也可能缺
        val page = parseForYouForTest(
            JSONObject("""{"list":[{"data":{"character":{"character_id":"a"}}}]}"""),
            "s", 0, 21, "fb",
        )
        assertTrue(page.items.single() is HomeFeedItem.Character)
    }

    @Test
    fun `list 为 null 时给空页而不是抛`() {
        val page = parseForYouForTest(JSONObject("""{"list":null}"""), "s", 0, 21, "fb")
        assertEquals(0, page.items.size)
        assertEquals(0, page.rawItemCount)
    }

    // ── public_list 的裸形态 ──────────────────────────────

    @Test
    fun `public_list 解析裸 CharacterGetRes`() {
        val page = parsePublicListForTest(
            JSONObject(
                """{"list":[{"character":{"character_id":"c1","nickname":"N"},
                  "stats":{"total_messages":5}}]}""",
            ),
            sessionId = "sess", page = 1, pageSize = 21,
        )
        val item = page.items.single() as HomeFeedItem.Character
        assertEquals("c1", item.characterId)
        // public_list 无 request_id，归因用 session
        assertEquals("sess", item.recommendation?.requestId)
        assertEquals(21, item.recommendation?.position)
    }

    // ── World ─────────────────────────────────────────────

    @Test
    fun `World 封面在 assets cover content_url`() {
        // ⚠️ 不是 image_url（adaptSimulatorGameToHomeCard.ts:4-13）
        val page = parseWorldListForTest(
            JSONObject(
                """{"has_more":true,"items":[{"project_id":"p1","name":"G",
                  "introduction":"I","assets":{"cover":{"content_url":"http://c"}},
                  "stats":{"studio_chat_count":42,"play_count":999},
                  "creator":{"user_id":"u","nickname":"N"},"version_change":true}]}""",
            ),
        )
        val item = page.items.single() as HomeFeedItem.World
        assertEquals("http://c", item.coverUrl)
        // ⚠️ 是 studio_chat_count 而不是 play_count —— 挑错字段不报错，只是数字对不上
        assertEquals(42L, item.interactionCount)
        assertEquals("I", item.introduction)
        assertTrue(item.versionChange)
        assertEquals(true, page.hasMore)
    }

    @Test
    fun `World 的 project_id 容忍数字形态`() {
        // RN 类型是 `string | number`
        val page = parseWorldListForTest(
            JSONObject("""{"items":[{"project_id":123,"name":"G"}],"has_more":false}"""),
        )
        assertEquals("123", (page.items.single() as HomeFeedItem.World).projectId)
    }

    @Test
    fun `World 缺 assets 时封面为空串而不是抛`() {
        val page = parseWorldListForTest(
            JSONObject("""{"items":[{"project_id":"p","name":"G"}],"has_more":false}"""),
        )
        assertEquals("", (page.items.single() as HomeFeedItem.World).coverUrl)
    }

    @Test
    fun `World 的 has_more 缺失时按 false`() {
        val page = parseWorldListForTest(JSONObject("""{"items":[]}"""))
        assertEquals(false, page.hasMore)
    }

    // ── stableKey ─────────────────────────────────────────

    @Test
    fun `有归因的 key 含 requestId —— 跨 session 不撞 key`() {
        // 纯 characterId 会在同一角色跨 session 出现时撞 key，Compose 直接抛
        val a = parseForYouForTest(
            JSONObject("""{"list":[{"type":"character","request_id":"r1","data":{"character":{"character_id":"c"}}}]}"""),
            "s1", 0, 21, "fb",
        ).items.single()
        val b = parseForYouForTest(
            JSONObject("""{"list":[{"type":"character","request_id":"r2","data":{"character":{"character_id":"c"}}}]}"""),
            "s2", 0, 21, "fb",
        ).items.single()
        assertEquals("r1-c", a.stableKey)
        assertEquals("r2-c", b.stableKey)
    }
}
