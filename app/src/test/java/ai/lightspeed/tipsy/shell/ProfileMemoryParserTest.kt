package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.profile.ProfileMemoryItem
import ai.lightspeed.tipsy.shell.pages.profile.ProfileMemoryPage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/plot/list/self` 解析。
 *
 * ## fixture 来源：**真机抓的响应裁剪**，不是手写
 *
 * Profile 首刀的教训：手写 fixture 用了扁平结构，27 条解析测试全绿，
 * 真机卡片却全空白 —— 因为真实响应把展示字段放在嵌套对象里。
 * 所以这里的形状（关系型 map + 数字时间戳）一律照抄实测响应，
 * 形状本身就是回归内容。
 */
class ProfileMemoryParserTest {

    // ── fixture：裁剪自实测响应（total:1，角色 Emi，创作者 Lee）────────

    private val characterId = "1710914565682712000"
    private val creatorId = "1780977720500996003"
    private val characterImage =
        "https://img2.tipsy.chat/character/image/1707109570364983000_1710914564_mob.jpg"
    private val creatorAvatar =
        "https://img2.tipsy.chat/user/avatar/default/avatar8.png"

    private fun plotJson(): JSONObject = JSONObject()
        .put("plot_id", "1786527088354307428")
        .put("creator_id", creatorId)
        .put("character_id", characterId)
        .put("title", "测试")
        .put("review_stage", "un_reviewed")
        .put("is_public", true)
        .put("nsfw", false)
        .put("weight", 0)
        // ⚠️ 数字而非字符串 —— TS 类型标的是 string，真实响应是 Unix 秒
        .put("created_at", 1786527088)
        .put("updated_at", 1786527088)
        .put(
            "messages",
            JSONArray()
                .put(JSONObject().put("sequence", "1").put("content", "a"))
                .put(JSONObject().put("sequence", "2").put("content", "b"))
                .put(JSONObject().put("sequence", "3").put("content", "c")),
        )

    private fun charactersMap(): JSONObject = JSONObject().put(
        characterId,
        JSONObject()
            .put("character_id", characterId)
            .put("nickname", "Emi")
            .put("image_url", characterImage)
            .put("img_primary_color", "#7c595a"),
    )

    private fun creatorsMap(): JSONObject = JSONObject().put(
        creatorId,
        JSONObject()
            .put("user_id", creatorId)
            .put("nickname", "Lee")
            .put("avatar_url", creatorAvatar),
    )

    private fun pageJson(): JSONObject = JSONObject()
        .put("total", 1)
        .put("plots", JSONArray().put(plotJson()))
        .put("characters", charactersMap())
        .put("creators", creatorsMap())

    // ── join：核心风险点 ──────────────────────────────

    @Test
    fun `按 character_id 从 characters map join 出角色名与配图`() {
        val item = ProfileMemoryItem.parse(plotJson(), charactersMap(), creatorsMap())

        assertEquals("Emi", item?.characterName)
        assertEquals(characterImage, item?.characterImageUrl)
    }

    @Test
    fun `按 creator_id 从 creators map join 出创作者`() {
        val item = ProfileMemoryItem.parse(plotJson(), charactersMap(), creatorsMap())

        assertEquals("Lee", item?.creatorNickname)
        assertEquals(creatorAvatar, item?.creatorAvatarUrl)
    }

    @Test
    fun `join 不中时留空而不是丢弃整条`() {
        // 角色被删：characters map 里没有这一项。记忆本身仍要显示
        val item = ProfileMemoryItem.parse(plotJson(), JSONObject(), JSONObject())

        assertEquals("测试", item?.title)
        assertNull(item?.characterName)
        assertNull(item?.characterImageUrl)
    }

    @Test
    fun `map 为 null 时不崩`() {
        val item = ProfileMemoryItem.parse(plotJson(), null, null)

        assertEquals("测试", item?.title)
        assertNull(item?.characterName)
    }

    // ── 字段类型：与 TS 声明的实测出入 ────────────────────

    @Test
    fun `created_at 是数字而不是字符串`() {
        assertEquals(1786527088L, ProfileMemoryItem.parse(plotJson(), null, null)?.createdAt)
    }

    @Test
    fun `messageCount 取 messages 数组长度`() {
        assertEquals(3, ProfileMemoryItem.parse(plotJson(), null, null)?.messageCount)
    }

    @Test
    fun `messages 缺失时 messageCount 为 0 而不是崩`() {
        val plot = plotJson()
        plot.remove("messages")
        assertEquals(0, ProfileMemoryItem.parse(plot, null, null)?.messageCount)
    }

    @Test
    fun `is_public 与 nsfw 原样解析`() {
        val item = ProfileMemoryItem.parse(plotJson(), null, null)
        assertTrue(item!!.isPublic)
        assertFalse(item.nsfw)
    }

    @Test
    fun `review_stage 原样保留供角标显示`() {
        assertEquals(
            "un_reviewed",
            ProfileMemoryItem.parse(plotJson(), null, null)?.reviewStage,
        )
    }

    // ── 相对路径防护（Profile 首刀的回归）──────────────────

    @Test
    fun `角色配图是相对路径时不采用`() {
        // 相对路径喂 Coil 会静默失败，看起来和"没图"一样但排查方向完全不同
        val chars = JSONObject().put(
            characterId,
            JSONObject().put("nickname", "Emi").put("image_url", "character/image/rel.jpg"),
        )
        val item = ProfileMemoryItem.parse(plotJson(), chars, null)

        assertEquals("Emi", item?.characterName)
        assertNull(item?.characterImageUrl)
    }

    // ── 丢弃规则 ────────────────────────────────────

    @Test
    fun `plot_id 缺失的整条丢弃`() {
        val plot = plotJson()
        plot.remove("plot_id")
        assertNull(ProfileMemoryItem.parse(plot, null, null))
    }

    // ── 整页解析 ────────────────────────────────────

    @Test
    fun `整页解析出 total 与 items`() {
        val page = ProfileMemoryPage.parse(pageJson())

        assertEquals(1L, page.total)
        assertEquals(1, page.items.size)
        assertEquals("Emi", page.items[0].characterName)
    }

    @Test
    fun `plots 为空是正常响应走空态`() {
        // 实测：账号无记忆时返回 total:0 + plots:[]，characters 为 {} 但 creators 有值
        val json = JSONObject()
            .put("total", 0)
            .put("plots", JSONArray())
            .put("characters", JSONObject())
            .put("creators", creatorsMap())
        val page = ProfileMemoryPage.parse(json)

        assertEquals(0L, page.total)
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `data 为 null 时返回空页`() {
        val page = ProfileMemoryPage.parse(null)

        assertEquals(0L, page.total)
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `plots 缺失时返回空页`() {
        val page = ProfileMemoryPage.parse(JSONObject().put("total", 5))

        assertEquals(5L, page.total)
        assertTrue(page.items.isEmpty())
    }
}
