package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.user.CurrentUser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `CurrentUser.parse`（`/user/info` 响应子集）。
 *
 * 重点是「错了不报错」的两类：残缺响应（无 user_id）宁可整体作废，
 * 可选字段缺失/空串一律归一成 null（UI 只需判一种空）。
 */
class CurrentUserParserTest {

    @Test
    fun `完整字段全部解析`() {
        val user = CurrentUser.parse(
            JSONObject()
                .put("user_id", "u1")
                .put("nickname", "Lee")
                .put("avatar_url", "https://cdn/a.png")
                .put("background_img_url", "https://cdn/bg.png")
                .put("language_code", "ja")
                .put("bio", "hello"),
        )
        assertEquals("u1", user?.userId)
        assertEquals("Lee", user?.nickname)
        assertEquals("https://cdn/a.png", user?.avatarUrl)
        assertEquals("https://cdn/bg.png", user?.backgroundImgUrl)
        assertEquals("hello", user?.bio)
        assertEquals("ja", user?.languageCode)
        assertEquals("u1", user?.sharedStorageSnapshot?.fields()?.getString("userId"))
    }

    @Test
    fun `缺 user_id 整体作废`() {
        // 没有 id 的身份宁可当作"没拿到"（对齐 RN store/user.ts:169 的守卫）
        assertNull(CurrentUser.parse(JSONObject().put("nickname", "Lee")))
        assertNull(CurrentUser.parse(null))
    }

    @Test
    fun `可选字段缺失时为 null 而不是空串`() {
        val user = CurrentUser.parse(JSONObject().put("user_id", "u1"))
        assertNull(user?.nickname)
        assertNull(user?.avatarUrl)
        assertNull(user?.backgroundImgUrl)
        assertNull(user?.bio)
    }

    @Test
    fun `bio 空串归一成 null`() {
        // UI 判空态（"No bio yet. Add one now."）只看 null —— 空串也得走空态
        val user = CurrentUser.parse(
            JSONObject().put("user_id", "u1").put("bio", ""),
        )
        assertNull(user?.bio)
    }

    @Test
    fun `数字形态的 user_id 被容错成字符串`() {
        // ScalarCoercion：后端把数字 id 序列化成 number 时不得变成科学计数法
        val user = CurrentUser.parse(
            JSONObject().put("user_id", 1780977720500996003L),
        )
        assertEquals("1780977720500996003", user?.userId)
    }

    // ── display_urls（P7 渠道图标）─────────────────

    @Test
    fun `display_urls 逐条解析并折出可见性`() {
        val user = CurrentUser.parse(
            JSONObject().put("user_id", "u1").put(
                "display_urls",
                JSONArray()
                    .put(urlEntry("discord", "https://d.gg/x", status = 1))
                    .put(urlEntry("twitter", "https://x.com/x", status = 2)),
            ),
        )
        assertEquals(2, user?.socialLinks?.size)
        assertEquals(
            CurrentUser.SocialLink("discord", "https://d.gg/x", visible = true),
            user?.socialLinks?.get(0),
        )
        assertEquals(false, user?.socialLinks?.get(1)?.visible)
    }

    @Test
    fun `display_urls 单条残缺跳过不弃整表`() {
        // 列表路径静默吞错是 iOS 踩过的反面 —— 这里是逐条防御
        val user = CurrentUser.parse(
            JSONObject().put("user_id", "u1").put(
                "display_urls",
                JSONArray()
                    .put(JSONObject().put("platform", "discord")) // 缺 url
                    .put(JSONObject().put("url", "https://x.com/x")) // 缺 platform
                    .put("not-an-object")
                    .put(urlEntry("youtube", "https://yt.be/x", status = 1)),
            ),
        )
        assertEquals(listOf(CurrentUser.SocialLink("youtube", "https://yt.be/x", true)), user?.socialLinks)
    }

    @Test
    fun `display_status 缺失按不可见处理`() {
        // 状态未知宁可不展示，也不把用户设为 HIDDEN 的链接放出来
        val user = CurrentUser.parse(
            JSONObject().put("user_id", "u1").put(
                "display_urls",
                JSONArray().put(
                    JSONObject().put("platform", "discord").put("url", "https://d.gg/x"),
                ),
            ),
        )
        assertEquals(false, user?.socialLinks?.single()?.visible)
    }

    @Test
    fun `display_urls 缺失时为空表`() {
        assertEquals(emptyList<CurrentUser.SocialLink>(), CurrentUser.parse(JSONObject().put("user_id", "u1"))?.socialLinks)
    }

    private fun urlEntry(platform: String, url: String, status: Int): JSONObject =
        JSONObject().put("platform", platform).put("url", url).put("display_status", status)
}
