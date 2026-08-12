package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.profile.ProfileCreatedItem
import ai.lightspeed.tipsy.shell.pages.profile.ProfileCreatedPage
import ai.lightspeed.tipsy.shell.pages.profile.ProfileItemType
import ai.lightspeed.tipsy.shell.pages.profile.ProfileReviewBadge
import ai.lightspeed.tipsy.shell.pages.profile.ProfileStats
import ai.lightspeed.tipsy.shell.user.CurrentUser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Profile 解析层（W3 第一刀）。
 *
 * 覆盖的都是「错了不报错」的点：统计字段的交叉映射、三种 item 的字段名差异、
 * 去重键按类型分流、rawJson 原封保留。
 */
class ProfileParserTest {

    // ── 统计数字：字段与标签交叉（最容易写错，本地几乎测不出）────────

    @Test
    fun `Followers 标签取 followees_count 而不是 followers_count`() {
        // 照字段名直译会把两个数字标反。这条测试就是那道锁
        val json = JSONObject()
            .put("followees_count", 111)
            .put("followers_count", 222)
        val stats = ProfileStats.parse(json)

        assertEquals("Followers 标签必须取 followees_count", 111L, stats.followersLabelCount)
        assertEquals("Following 标签必须取 followers_count", 222L, stats.followingLabelCount)
    }

    @Test
    fun `Likes 与 Interactions 取各自字段`() {
        val json = JSONObject()
            .put("characters_received_likes", 7)
            .put("total_interactions", 9)
        val stats = ProfileStats.parse(json)

        assertEquals(7L, stats.likesCount)
        assertEquals(9L, stats.interactionsCount)
    }

    @Test
    fun `统计缺字段按 0 而不是抛异常`() {
        // 对齐 RN 的 `followerInfo?.x || 0`：统计缺失不该让整页失败
        val stats = ProfileStats.parse(JSONObject())
        assertEquals(ProfileStats.EMPTY, stats)
    }

    @Test
    fun `统计 data 为 null 返回 EMPTY`() {
        assertEquals(ProfileStats.EMPTY, ProfileStats.parse(null))
    }

    @Test
    fun `统计字段是字符串数字时也能解析`() {
        // 后端把大数序列化成字符串的情况（ScalarCoercion 的存在理由）
        val json = JSONObject().put("followees_count", "1234")
        assertEquals(1234L, ProfileStats.parse(json).followersLabelCount)
    }

    // ── item 名称字段：三种类型不同 ──────────────────────

    /**
     * 真实 `/user/created/list` 响应里一条 character item 的**形状**
     * （抓自模拟器，值已简化，字段位置照原样）。
     *
     * ⚠️ 这个 fixture 的形状本身就是回归内容：早先版本用扁平结构造
     * fixture，导致「单测全绿但真机卡片全空白」——
     * `nickname` 只在嵌套 `character` 里，顶层 `image_url` 是**相对路径**。
     */
    private fun realCharacterItem(): JSONObject = JSONObject()
        .put("item_type", "character")
        .put("item_id", "1780987749140555652")
        .put("title", "同学")
        // 顶层是相对路径 —— 喂给 Coil 会静默失败
        .put("image_url", "create_media/image/6a27b7118c532cf56ee2578a_3.jpg")
        .put("face_url", "character/avatar/1780977720500996003_178098.jpg")
        .put(
            "character",
            JSONObject()
                .put("nickname", "同学")
                .put("name", "内部名")
                .put("image_url", "https://img2.tipsy.chat/create_media/image/6a27b.jpg")
                .put("face_url", "https://img2.tipsy.chat/character/avatar/17809.jpg"),
        )

    @Test
    fun `character 的显示名取嵌套 character 里的 nickname`() {
        // 顶层没有 nickname，只有嵌套层有。取顶层会得到 null → 卡片名字空白
        assertEquals("同学", ProfileCreatedItem.parse(realCharacterItem())?.name)
    }

    @Test
    fun `封面取嵌套层的完整 URL 而不是顶层相对路径`() {
        // 这条是"真机卡片只剩占位色"的直接回归：
        // 相对路径不会报错，Coil 静默失败
        val cover = ProfileCreatedItem.parse(realCharacterItem())?.coverUrl
        assertNotNull("封面不该为空", cover)
        assertTrue("必须是完整 URL，实际=$cover", cover!!.startsWith("https://"))
    }

    @Test
    fun `顶层相对路径不会被当成封面`() {
        // 只有顶层相对路径、没有嵌套对象时，宁可返回 null 走占位色，
        // 也不要交给 Coil 静默失败（那样看起来一样，但排查时会误以为图挂了）
        val json = JSONObject()
            .put("item_type", "character")
            .put("item_id", "c1")
            .put("image_url", "create_media/image/relative.jpg")
        assertNull(ProfileCreatedItem.parse(json)?.coverUrl)
    }

    @Test
    fun `嵌套对象缺失时用顶层 title 兜底而不是白卡片`() {
        val json = JSONObject()
            .put("item_type", "character")
            .put("item_id", "c1")
            .put("title", "顶层标题")
        assertEquals("顶层标题", ProfileCreatedItem.parse(json)?.name)
    }

    @Test
    fun `嵌套 image_url 缺失时回落到嵌套 face_url`() {
        val json = JSONObject()
            .put("item_type", "character")
            .put("item_id", "c1")
            .put(
                "character",
                JSONObject().put("face_url", "https://img2.tipsy.chat/avatar/x.jpg"),
            )
        assertEquals(
            "https://img2.tipsy.chat/avatar/x.jpg",
            ProfileCreatedItem.parse(json)?.coverUrl,
        )
    }

    @Test
    fun `character 的显示名不取 name 字段`() {
        // 接口里 name 也存在（角色内部名），取错了能拿到值但显示的不是用户看到的名字
        assertEquals("同学", ProfileCreatedItem.parse(realCharacterItem())?.name)
    }

    @Test
    fun `story 的显示名取 title`() {
        val json = JSONObject()
            .put("item_type", "story")
            .put("item_id", "s1")
            .put("title", "故事标题")
        assertEquals("故事标题", ProfileCreatedItem.parse(json)?.name)
    }

    @Test
    fun `game 的显示名取 title`() {
        val json = JSONObject()
            .put("item_type", "game")
            .put("item_id", "g1")
            .put("game_id", "gid1")
            .put("title", "游戏标题")
        assertEquals("游戏标题", ProfileCreatedItem.parse(json)?.name)
    }

    // ── 去重键：按类型分流 ────────────────────────────

    @Test
    fun `game 的去重键带 game_ 前缀且用 game_id`() {
        val json = JSONObject()
            .put("item_type", "game")
            .put("item_id", "same")
            .put("game_id", "gid1")
        assertEquals("game_gid1", ProfileCreatedItem.parse(json)?.dedupeKey)
    }

    @Test
    fun `character 的去重键是 item_id`() {
        val json = JSONObject()
            .put("item_type", "character")
            .put("item_id", "same")
        assertEquals("same", ProfileCreatedItem.parse(json)?.dedupeKey)
    }

    @Test
    fun `game 与 character 的 item_id 相同时去重键不撞`() {
        // 统一用 item_id 会让两者互相顶掉，表现是"某个游戏卡片莫名消失"
        val game = ProfileCreatedItem.parse(
            JSONObject().put("item_type", "game").put("item_id", "x").put("game_id", "gx"),
        )
        val character = ProfileCreatedItem.parse(
            JSONObject().put("item_type", "character").put("item_id", "x"),
        )
        assertNotNull(game)
        assertNotNull(character)
        assertTrue("两种类型的去重键必须不同", game!!.dedupeKey != character!!.dedupeKey)
    }

    // ── rawJson：编辑入口的数据保真 ────────────────────

    @Test
    fun `rawJson 保留模型未声明的字段`() {
        // 方案 §8.1：原始 JSON 必须原封透传，by-id 重拉会导致保存时字段重置
        val json = JSONObject()
            .put("item_type", "character")
            .put("item_id", "c1")
            .put("nickname", "n")
            .put("custom_prompt", "秘密提示词")
            .put("world_books", "世界书")
        val item = ProfileCreatedItem.parse(json)

        assertTrue("custom_prompt 必须还在", item!!.rawJson.contains("custom_prompt"))
        assertTrue("world_books 必须还在", item.rawJson.contains("world_books"))
    }

    // ── 未知类型与坏数据 ─────────────────────────────

    @Test
    fun `未知 item_type 返回 null 而不是崩`() {
        val json = JSONObject().put("item_type", "brand_new_type").put("item_id", "x")
        assertNull(ProfileCreatedItem.parse(json))
    }

    @Test
    fun `缺 item_type 返回 null`() {
        assertNull(ProfileCreatedItem.parse(JSONObject().put("item_id", "x")))
    }

    @Test
    fun `未知类型的条目被整页解析跳过而不影响其他条目`() {
        val data = JSONObject().put(
            "list",
            org.json.JSONArray()
                .put(JSONObject().put("item_type", "character").put("item_id", "ok1"))
                .put(JSONObject().put("item_type", "unknown").put("item_id", "bad"))
                .put(JSONObject().put("item_type", "story").put("item_id", "ok2")),
        ).put("total", 3)
        val page = ProfileCreatedPage.parse(data)

        assertEquals("坏条目跳过，好条目保留", 2, page.items.size)
        assertEquals(ProfileItemType.CHARACTER, page.items[0].type)
        assertEquals(ProfileItemType.STORY, page.items[1].type)
    }

    @Test
    fun `list 为 null 是正常响应不是错误`() {
        // types/character.ts:538 `list: CreatedListItem[] | null`
        val page = ProfileCreatedPage.parse(JSONObject().put("total", 0))
        assertEquals(0, page.items.size)
        assertEquals(0L, page.total)
    }

    @Test
    fun `page data 为 null 返回空页`() {
        val page = ProfileCreatedPage.parse(null)
        assertEquals(0, page.items.size)
        assertNull(page.rawList)
    }

    // ── CurrentUser ─────────────────────────────────

    @Test
    fun `缺 user_id 的响应返回 null 而不是空 id 身份`() {
        // 对齐 RN store/user.ts:169 的 `if (user.user_id)` 守卫：
        // 残缺响应不该写一个 id 为空的身份进内存
        val json = JSONObject().put("nickname", "n").put("avatar_url", "u")
        assertNull(CurrentUser.parse(json))
    }

    @Test
    fun `用户信息可空字段缺失时为 null 而不是空串`() {
        val json = JSONObject().put("user_id", "u1")
        val user = CurrentUser.parse(json)

        assertEquals("u1", user?.userId)
        assertNull(user?.nickname)
        assertNull(user?.avatarUrl)
        assertNull(user?.backgroundImgUrl)
    }

    @Test
    fun `用户信息全字段解析`() {
        val json = JSONObject()
            .put("user_id", "u1")
            .put("nickname", "昵称")
            .put("avatar_url", "https://a")
            .put("background_img_url", "https://b")
        val user = CurrentUser.parse(json)

        assertEquals("u1", user?.userId)
        assertEquals("昵称", user?.nickname)
        assertEquals("https://a", user?.avatarUrl)
        assertEquals("https://b", user?.backgroundImgUrl)
    }

    @Test
    fun `user_id 是数字时不被科学计数法污染`() {
        // ScalarCoercion 的存在理由：optString 对 number 会拿到 1.78e+18
        val json = JSONObject().put("user_id", 1780977720500996003L)
        assertEquals("1780977720500996003", CurrentUser.parse(json)?.userId)
    }

    @Test
    fun `data 为 null 时用户返回 null`() {
        assertNull(CurrentUser.parse(null))
    }

    // ── P4：角标与遮罩派生（值都取自嵌套层）────────────

    @Test
    fun `审核角标 rejected 优先于 pending`() {
        // minor_review_status 与 review_stage 都可能触发，rejected 吃掉 pending
        //（CharacterGridItem.tsx:355-374 的判定顺序）
        val both = createdItem(
            """{"item_type":"character","item_id":"a",
                "character":{"nickname":"n","minor_review_status":"final_rejected",
                             "review_stage":"un_reviewed"}}""",
        )
        assertEquals(ProfileReviewBadge.REJECTED, both.reviewBadge)

        val pending = createdItem(
            """{"item_type":"character","item_id":"a",
                "character":{"nickname":"n","review_stage":"un_reviewed"}}""",
        )
        assertEquals(ProfileReviewBadge.PENDING, pending.reviewBadge)

        val approved = createdItem(
            """{"item_type":"character","item_id":"a",
                "character":{"nickname":"n","review_stage":"pass",
                             "minor_review_status":"approved"}}""",
        )
        assertNull("通过态不渲染角标", approved.reviewBadge)
    }

    @Test
    fun `封面模糊三条件任一命中`() {
        // ① nsfw（壳内偏好恒 false → 18+ 一律模糊）
        assertTrue(
            createdItem(
                """{"item_type":"character","item_id":"a",
                    "character":{"nsfw":true}}""",
            ).shouldBlurCover,
        )
        // ② final_hit & 8
        assertTrue(
            createdItem(
                """{"item_type":"character","item_id":"a",
                    "character":{"final_hit":10}}""",
            ).shouldBlurCover,
        )
        // ③ 未成年审核拦截
        assertTrue(
            createdItem(
                """{"item_type":"character","item_id":"a",
                    "character":{"minor_review_status":"pending"}}""",
            ).shouldBlurCover,
        )
        // 全不中不模糊（final_hit 缺失按 0）
        assertFalse(
            createdItem(
                """{"item_type":"character","item_id":"a",
                    "character":{"nsfw":false,"review_stage":"pass"}}""",
            ).shouldBlurCover,
        )
    }

    @Test
    fun `final_hit 小于 2 整卡不可用`() {
        assertTrue(
            createdItem(
                """{"item_type":"character","item_id":"a","character":{"final_hit":1}}""",
            ).isMaskedUnavailable,
        )
        assertFalse(
            createdItem(
                """{"item_type":"character","item_id":"a","character":{"final_hit":2}}""",
            ).isMaskedUnavailable,
        )
        // ⚠️ 缺失不算不可用（RN 是 `final_hit != null && < 2`）——
        // 反过来写会把老数据整页蒙掉
        assertFalse(
            createdItem(
                """{"item_type":"character","item_id":"a","character":{}}""",
            ).isMaskedUnavailable,
        )
    }

    @Test
    fun `18+ 标签只在审核通过时显示`() {
        // 待审时左上位置属于审核角标，18+ 不重复出现（CharacterGridItem.tsx:528-531）
        val pending = createdItem(
            """{"item_type":"character","item_id":"a",
                "character":{"nsfw":true,"review_stage":"un_reviewed"}}""",
        )
        assertFalse(pending.showNsfwTag)

        val passed = createdItem(
            """{"item_type":"character","item_id":"a",
                "character":{"nsfw":true,"review_stage":"pass"}}""",
        )
        assertTrue(passed.showNsfwTag)
    }

    @Test
    fun `置顶与私密与消息数取嵌套层`() {
        val item = createdItem(
            """{"item_type":"character","item_id":"a",
                "character":{"nickname":"n","is_pinned":true,"is_public":false,
                             "total_messages":7,
                             "stats":{"total_messages":42,"exposure_count":9}}}""",
        )
        assertTrue(item.isPinned)
        assertFalse(item.isPublic)
        assertEquals("stats 优先于顶层 total_messages", 42L, item.messageCount)
        assertEquals(9L, item.exposureCount)
        assertTrue("story 类型标记", createdItem(
            """{"item_type":"character","item_id":"a","character":{"character_type":2}}""",
        ).showStoryTag)
    }

    @Test
    fun `is_public 缺失按公开处理`() {
        // 多画一把锁比漏画显眼 —— 缺失按 true（不画锁），见 parse 注释
        val item = createdItem(
            """{"item_type":"character","item_id":"a","character":{"nickname":"n"}}""",
        )
        assertTrue(item.isPublic)
        assertFalse(item.isPinned)
        assertEquals(0L, item.messageCount)
    }

    private fun createdItem(json: String): ProfileCreatedItem =
        ProfileCreatedItem.parse(JSONObject(json))!!
}
