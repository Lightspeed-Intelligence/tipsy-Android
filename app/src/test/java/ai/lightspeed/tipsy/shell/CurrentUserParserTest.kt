package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.user.CurrentUser
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
                .put("bio", "hello"),
        )
        assertEquals("u1", user?.userId)
        assertEquals("Lee", user?.nickname)
        assertEquals("https://cdn/a.png", user?.avatarUrl)
        assertEquals("https://cdn/bg.png", user?.backgroundImgUrl)
        assertEquals("hello", user?.bio)
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
}
