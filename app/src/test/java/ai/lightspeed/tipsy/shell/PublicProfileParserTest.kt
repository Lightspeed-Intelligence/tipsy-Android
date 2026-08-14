package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.profile.CreatorListPage
import ai.lightspeed.tipsy.shell.pages.profile.ProfileItemType
import ai.lightspeed.tipsy.shell.pages.profile.PublicUserProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 他人主页的解析层（§2.32）。
 *
 * 重点是 **v1 与 v2 的响应形状不同** —— v1 扁平无 `item_type`，
 * 不补键会让回落路径恒空（而回落只在 v2 缺数据时才走，联调很难发现）。
 */
class PublicProfileParserTest {

    // ── 公开资料 ────────────────────────────────────

    @Test
    fun `公开资料解析全字段`() {
        val profile = PublicUserProfile.parse(
            JSONObject()
                .put("user_id", "u1")
                .put("nickname", "Alice")
                .put("avatar_url", "https://cdn/a.png")
                .put("background_img_url", "https://cdn/bg.png")
                .put("bio", "hello")
                .put("is_followed", true)
                .put("is_deleted", false),
        )
        assertEquals("u1", profile?.userId)
        assertEquals("Alice", profile?.nickname)
        assertEquals("hello", profile?.bio)
        assertTrue(profile?.isFollowed == true)
        assertFalse(profile?.isDeleted == true)
    }

    @Test
    fun `user_id 缺失整条返回 null`() {
        // 没有 id 的身份对象是残缺响应 —— 宁可当「没拿到」
        assertNull(PublicUserProfile.parse(JSONObject().put("nickname", "Alice")))
    }

    @Test
    fun `is_followed 与 is_deleted 缺失都按 false`() {
        val profile = PublicUserProfile.parse(JSONObject().put("user_id", "u1"))
        assertFalse(profile?.isFollowed == true)
        assertFalse(profile?.isDeleted == true)
    }

    @Test
    fun `空串 bio 归一成 null`() {
        // UI 靠 null 判断「不渲染 bio 区」；空串会渲染一个空容器
        val profile = PublicUserProfile.parse(
            JSONObject().put("user_id", "u1").put("bio", "   "),
        )
        assertNull(profile?.bio)
    }

    @Test
    fun `数字 user_id 不被序列化成科学计数法`() {
        // ScalarCoercion 的既有教训：optString 对大数会给 "1.78e+18"
        val profile = PublicUserProfile.parse(
            JSONObject().put("user_id", 1780977720500999L),
        )
        assertEquals("1780977720500999", profile?.userId)
    }

    // ── v2 列表（含 game）────────────────────────────

    @Test
    fun `v2 解析 list 三种类型`() {
        val page = CreatorListPage.parseV2(
            JSONObject().put(
                "list",
                JSONArray()
                    .put(createdCharacter("c1", "Alice"))
                    .put(
                        JSONObject()
                            .put("item_type", "story")
                            .put("item_id", "s1")
                            .put("story", JSONObject().put("title", "Tale")),
                    )
                    .put(
                        JSONObject()
                            .put("item_type", "game")
                            .put("item_id", "g1")
                            .put("game_id", "gid1")
                            .put("game", JSONObject().put("title", "Play")),
                    ),
            ),
        )
        assertEquals(3, page.items.size)
        assertEquals(ProfileItemType.CHARACTER, page.items[0].type)
        assertEquals(ProfileItemType.STORY, page.items[1].type)
        assertEquals(ProfileItemType.GAME, page.items[2].type)
        // game 的去重键带前缀（与 character 的 id 空间独立）
        assertEquals("game_gid1", page.items[2].dedupeKey)
    }

    @Test
    fun `v2 缺 list 字段是正常空响应`() {
        assertTrue(CreatorListPage.parseV2(JSONObject()).items.isEmpty())
        assertTrue(CreatorListPage.parseV2(null).items.isEmpty())
    }

    // ── v1 列表（扁平，回落用）──────────────────────

    @Test
    fun `v1 扁平元素补 item_type 后能解析出角色`() {
        // ⚠️ 本条是回落路径的命门：v1 元素没有 item_type，
        // ProfileCreatedItem.parse 认不出会整条返回 null → 回落恒空
        val page = CreatorListPage.parseV1(
            JSONObject().put(
                "characters",
                JSONArray().put(
                    JSONObject()
                        .put("character_id", "ch1")
                        .put("nickname", "Bob")
                        .put("image_url", "https://cdn/b.png"),
                ),
            ),
        )
        assertEquals(1, page.items.size)
        assertEquals(ProfileItemType.CHARACTER, page.items[0].type)
        assertEquals("Bob", page.items[0].name)
        assertEquals("https://cdn/b.png", page.items[0].coverUrl)
    }

    @Test
    fun `v1 用 character_id 兜底 item_id`() {
        val page = CreatorListPage.parseV1(
            JSONObject().put(
                "characters",
                JSONArray().put(JSONObject().put("character_id", "ch9").put("nickname", "X")),
            ),
        )
        // 去重键非空 —— 空 key 会让多条互相顶掉，列表只剩一条
        assertEquals("ch9", page.items[0].dedupeKey)
    }

    @Test
    fun `v1 的 item_id 存在时优先于 character_id`() {
        val page = CreatorListPage.parseV1(
            JSONObject().put(
                "characters",
                JSONArray().put(
                    JSONObject()
                        .put("item_id", "it1")
                        .put("character_id", "ch1")
                        .put("nickname", "X"),
                ),
            ),
        )
        assertEquals("it1", page.items[0].dedupeKey)
    }

    @Test
    fun `v1 读 characters 而不是 list`() {
        // 两个端点的数组字段名不同（v1 characters / v2 list）——
        // 抄错的表现是「v2 有数据但回落时空」
        val page = CreatorListPage.parseV1(
            JSONObject().put("list", JSONArray().put(createdCharacter("c1", "Alice"))),
        )
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `v1 包装不污染原始 JSON 的 rawJson`() {
        // wrapFlatCharacter 必须复制而不是原地 put —— 原地改会让
        // rawJson（编辑入口要原封透传的那份）多出壳造的键
        val flat = JSONObject().put("character_id", "ch1").put("nickname", "Bob")
        CreatorListPage.parseV1(JSONObject().put("characters", JSONArray().put(flat)))
        assertFalse(flat.has("item_type"))
        assertFalse(flat.has("character"))
    }

    @Test
    fun `v1 空 characters 返回空列表不抛`() {
        assertTrue(CreatorListPage.parseV1(JSONObject()).items.isEmpty())
        assertTrue(CreatorListPage.parseV1(null).items.isEmpty())
    }

    private fun createdCharacter(itemId: String, nickname: String) = JSONObject()
        .put("item_type", "character")
        .put("item_id", itemId)
        .put("character", JSONObject().put("nickname", nickname))
}
