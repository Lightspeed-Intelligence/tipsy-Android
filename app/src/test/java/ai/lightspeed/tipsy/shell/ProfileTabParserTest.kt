package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.profile.ProfileFavoriteItem
import ai.lightspeed.tipsy.shell.pages.profile.ProfileFavoritePage
import ai.lightspeed.tipsy.shell.pages.profile.ProfileRoleCardItem
import ai.lightspeed.tipsy.shell.pages.profile.ProfileRoleCardPage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 两个新 tab 的解析层。重点：role_pic 的三段 CDN 解析、
 * `message_num` 的 string/number 双形态容错、`total_pages` 的页数语义。
 */
class ProfileTabParserTest {

    // ── 角色卡 ──────────────────────────────────────

    @Test
    fun `role_pic_url 优先`() {
        val item = ProfileRoleCardItem.parse(
            JSONObject()
                .put("profile_card_id", "rc1")
                .put("role_pic_url", "https://cdn/a.png")
                .put("role_pic", "relative/path.png"),
        )
        assertEquals("https://cdn/a.png", item?.rolePicUrl)
    }

    @Test
    fun `role_pic 相对路径拼 CDN 前缀`() {
        // RoleCard.tsx:44 的硬编码前缀
        val item = ProfileRoleCardItem.parse(
            JSONObject().put("profile_card_id", "rc1").put("role_pic", "role/abc.png"),
        )
        assertEquals("https://img.tipsy.chat/role/abc.png", item?.rolePicUrl)
    }

    @Test
    fun `role_pic 已是完整 URL 直用`() {
        val item = ProfileRoleCardItem.parse(
            JSONObject().put("profile_card_id", "rc1").put("role_pic", "https://x/y.png"),
        )
        assertEquals("https://x/y.png", item?.rolePicUrl)
    }

    @Test
    fun `角色卡性别归一`() {
        fun gender(g: String?) = ProfileRoleCardItem.parse(
            JSONObject().put("profile_card_id", "rc").apply { if (g != null) put("gender", g) },
        )?.genderKey

        assertEquals("Male", gender("male"))
        assertEquals("Female", gender("female"))
        // male/female 之外全归 Other（RoleCard.tsx:68-73）
        assertEquals("Other", gender("nonbinary"))
        assertNull("缺失整段省略", gender(null))
    }

    @Test
    fun `角色卡缺 id 整条丢弃`() {
        assertNull(ProfileRoleCardItem.parse(JSONObject().put("nickname", "n")))
    }

    @Test
    fun `角色卡页解析`() {
        val page = ProfileRoleCardPage.parse(
            JSONObject(
                """{"total": 3, "list": [
                    {"profile_card_id":"a"}, {"nickname":"无 id 丢弃"}, {"profile_card_id":"b"}
                ]}""",
            ),
        )
        assertEquals(listOf("a", "b"), page.items.map { it.profileCardId })
        assertEquals(3L, page.total)
    }

    // ── 收藏/点赞 ───────────────────────────────────

    @Test
    fun `message_num 字符串与数字都容错`() {
        // TS 声明 string、实测可 number —— tolerant scalar 两头接
        val fromString = ProfileFavoriteItem.parse(
            JSONObject().put("character_id", "c1").put("message_num", "42"),
        )
        val fromNumber = ProfileFavoriteItem.parse(
            JSONObject().put("character_id", "c2").put("message_num", 42),
        )
        assertEquals(42L, fromString?.messageCount)
        assertEquals(42L, fromNumber?.messageCount)
    }

    @Test
    fun `收藏封面只认完整 URL`() {
        val item = ProfileFavoriteItem.parse(
            JSONObject().put("character_id", "c1").put("image_url", "relative/x.png"),
        )
        assertNull("相对路径喂 Coil 会静默失败，宁可走占位", item?.imageUrl)
    }

    @Test
    fun `收藏页解析 total_pages`() {
        val page = ProfileFavoritePage.parse(
            JSONObject(
                """{"total_pages": 5, "characters": [
                    {"character_id":"a","nsfw":true}, {"nickname":"无 id 丢弃"}
                ]}""",
            ),
        )
        assertEquals(1, page.items.size)
        assertTrue(page.items.single().nsfw)
        assertEquals("total_pages 是页数不是条数", 5L, page.totalPages)
    }

    @Test
    fun `收藏页 characters 为 null 是空页不是错误`() {
        // types/profile.ts:213 `characters?: ... | null | []`
        val page = ProfileFavoritePage.parse(JSONObject("""{"total_pages": 0}"""))
        assertTrue(page.items.isEmpty())
        assertEquals(0L, page.totalPages)
    }
}
