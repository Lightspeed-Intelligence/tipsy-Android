package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.network.ApiEnvelope
import ai.lightspeed.tipsy.shell.pages.search.CharacterSearchOutcome
import ai.lightspeed.tipsy.shell.pages.search.SearchParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索响应解析。
 *
 * 重点在两类容易静默出错的地方：
 * 1. **字段名与 Home 列表接口不同**（`total_messages` 是字符串、creator 在顶层）
 * 2. **热门词的字段首字母大写**（Redis ZSET 直出）—— 写小写会得到空列表且不报错
 */
class SearchParserTest {

    // ── 角色结果 ────────────────────────────────

    @Test
    fun `角色结果翻译成 HomeCard 模型且吃住标量漂移`() {
        val data = JSONObject(
            """
            {
              "total": 42,
              "search_session_id": "sess-1",
              "hits": [
                {
                  "character_id": "c1",
                  "nickname": "Elaine",
                  "introduction": "hi",
                  "image_url": "https://x/a.png",
                  "creator_id": "u1",
                  "creator_nickname": "maker",
                  "total_messages": "1234",
                  "voice_supported": true,
                  "character_type": 2,
                  "content_type": 1,
                  "nsfw": false
                }
              ]
            }
            """.trimIndent(),
        )
        val page = SearchParser.parseCharacterPage(data)

        assertEquals(42, page.total)
        assertEquals("sess-1", page.searchSessionId)
        assertEquals(1, page.hits.size)
        val hit = page.hits.single()
        assertEquals("c1", hit.characterId)
        assertEquals("Elaine", hit.nickname)
        // total_messages 是**字符串**形态的数字（与 Home 的嵌套数字不同）
        assertEquals(1234L, hit.totalMessages)
        assertEquals("u1", hit.creatorId)
        assertEquals("maker", hit.creatorNickname)
        assertEquals(2, hit.characterType)
        // 搜索结果不下发动图，恒 null；RN 硬写 is_translated=false
        assertNull(hit.animatedImageUrl)
        assertFalse(hit.isTranslated)
        assertFalse(hit.isChatted)
    }

    @Test
    fun `有响应但无敏感类型时是 SAFE`() {
        val page = SearchParser.parseCharacterPage(
            JSONObject("""{"total":0,"search_session_id":"s","hits":[]}"""),
        )
        assertEquals(
            "空结果 + 无敏感标记 = safe（空态才敢显示 Create Now）",
            CharacterSearchOutcome.SAFE,
            page.outcome,
        )
    }

    @Test
    fun `直接命中敏感词的响应解析出 DIRECT`() {
        val page = SearchParser.parseCharacterPage(
            JSONObject(
                """{"total":0,"search_session_id":"s","search_sensitive_type":"direct","hits":[]}""",
            ),
        )
        assertEquals(CharacterSearchOutcome.DIRECT, page.outcome)
    }

    @Test
    fun `缺 character_id 的条目被丢弃`() {
        val page = SearchParser.parseCharacterPage(
            JSONObject(
                """
                {"total":2,"search_session_id":"s","hits":[
                  {"nickname":"no id"},
                  {"character_id":"","nickname":"blank id"},
                  {"character_id":"ok","nickname":"good"}
                ]}
                """.trimIndent(),
            ),
        )
        assertEquals("无 id 无法点击也无法去重曝光，必须丢弃", 1, page.hits.size)
        assertEquals("ok", page.hits.single().characterId)
    }

    @Test
    fun `data 为 null 时返回空页而不是抛异常`() {
        val page = SearchParser.parseCharacterPage(null)
        assertEquals(0, page.total)
        assertTrue(page.hits.isEmpty())
        assertEquals(CharacterSearchOutcome.IDLE, page.outcome)
    }

    @Test
    fun `标签聚合按接口顺序保留且丢弃空 id`() {
        val page = SearchParser.parseCharacterPage(
            JSONObject(
                """
                {"total":1,"search_session_id":"s","hits":[],
                 "tag_aggs":[{"tag_id":"t3","count":9},{"tag_id":"","count":5},{"tag_id":"t1","count":2}]}
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("t3", "t1"), page.tagAggIds)
    }

    // ── 创作者结果 ────────────────────────────────

    @Test
    fun `创作者结果解析且缺 user_id 的行被丢弃`() {
        val page = SearchParser.parseCreatorPage(
            JSONObject(
                """
                {"total":7,"search_session_id":"cs-1","hits":[
                  {"user_id":"u1","nickname":"A","avatar":"https://x/a.png","bio":"hello",
                   "followees_count":12,"total_interactions":"3400","created_characters_count":5,
                   "avatar_decoration_code":"deco"},
                  {"nickname":"no id"}
                ]}
                """.trimIndent(),
            ),
        )
        assertEquals(7, page.total)
        assertEquals("cs-1", page.searchSessionId)
        assertEquals(1, page.hits.size)
        val creator = page.hits.single()
        assertEquals("u1", creator.userId)
        assertEquals(12L, creator.followeesCount)
        // 标量漂移：字符串形态的数字
        assertEquals(3400L, creator.totalInteractions)
        assertEquals("deco", creator.avatarDecorationCode)
    }

    // ── 字符串数组类响应 ────────────────────────────────

    @Test
    fun `建议词跳过 JSON null 与空串`() {
        val array = JSONArray("""["alpha", null, "", "beta"]""")
        assertEquals(listOf("alpha", "beta"), SearchParser.parseStringList(array))
    }

    @Test
    fun `建议词数组为 null 时返回空列表`() {
        assertTrue(SearchParser.parseStringList(null).isEmpty())
    }

    /**
     * ⚠️ 热门词字段是 `Member`（**大写 M**），Redis ZSET 直出。
     * 写成 `member` 会静默得到空列表 —— 接口 200、日志无异常、页面就是不显示。
     */
    @Test
    fun `热门词读大写 Member 字段`() {
        val array = JSONArray(
            """[{"Score":9,"Member":"anime"},{"Score":8,"Member":"cat girl"}]""",
        )
        assertEquals(listOf("anime", "cat girl"), SearchParser.parsePopularTerms(array))
    }

    @Test
    fun `热门词小写 member 读不到 视为格式不符`() {
        val array = JSONArray("""[{"Score":9,"member":"anime"}]""")
        assertTrue(
            "小写字段读不出来 —— 这条测试是防有人「顺手改成小写」",
            SearchParser.parsePopularTerms(array).isEmpty(),
        )
    }

    // ── envelope 形态 ────────────────────────────────

    /**
     * 建议词/最近搜索的 `data` 是**数组**，`ApiEnvelope.parse` 只填 `dataArray`，
     * `data` 恒 null。传错的表现是「接口 200 但列表永远空」。
     */
    @Test
    fun `数组形态响应只填 dataArray`() {
        val envelope = ApiEnvelope.parse("""{"code":0,"msg":"ok","data":["a","b"]}""")
        assertNull("对象位必须为空，否则调用方会读错位置", envelope.data)
        assertEquals(2, envelope.dataArray?.length())
        assertEquals(listOf("a", "b"), SearchParser.parseStringList(envelope.dataArray))
    }
}
