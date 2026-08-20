package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.home.HomeCacheStorage
import ai.lightspeed.tipsy.shell.pages.screen.ScreenFirstScreenCacheStore
import ai.lightspeed.tipsy.shell.pages.screen.ScreenAttribution
import ai.lightspeed.tipsy.shell.pages.screen.ScreenEndpoint
import ai.lightspeed.tipsy.shell.pages.screen.ScreenCacheSignature
import ai.lightspeed.tipsy.shell.pages.screen.ScreenEndpointResolver
import ai.lightspeed.tipsy.shell.pages.screen.ScreenFeedItem
import ai.lightspeed.tipsy.shell.pages.screen.ScreenFirstScreenFeed
import ai.lightspeed.tipsy.shell.pages.screen.ScreenMediaSourceType
import ai.lightspeed.tipsy.shell.pages.screen.toChatDetailPreload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 推荐归因 —— **逐条对拍 RN 的 `recommendationAttribution.test.ts`**
 * （92 行，方案 §8.2「现成 fixture 来源」）。
 *
 * 三个用例的名字与断言照抄那份。第一条最关键：它钉死了
 * **`position` 用原始下标**（无效条目与重复项都占号），
 * 而那个语义如果写错，后端按 position 算的 CTR 会整体偏移，**两端都不报错**。
 */
class ScreenAttributionTest {

    /**
     * `[无id, a, a, b]` + page=1 / pageSize=10 → a 的 position 是 **11**、
     * b 是 **13**（不是 10 和 11）。
     */
    @Test
    fun `position 用去重与过滤前的原始下标`() {
        val result = ScreenAttribution.attribute(
            // null = 无 character_id 的占位（ScreenPage.parse 保留它）
            items = listOf(null, item("character-a"), item("character-a"), item("character-b")),
            endpoint = ScreenEndpoint.RECOMMENDATION,
            requestId = "request-1",
            sessionId = "session-1",
            ownerUserId = "owner-1",
            page = 1,
            pageSize = 10,
        )
        assertEquals(
            listOf("character-a" to 11, "character-b" to 13),
            result.items.map { it.characterId to it.attribution?.position },
        )
    }

    @Test
    fun `distribution 端点不产生归因`() {
        val result = ScreenAttribution.attribute(
            items = listOf(item("character-a")),
            endpoint = ScreenEndpoint.DISTRIBUTION,
            requestId = "request-1",
            sessionId = "session-1",
            ownerUserId = "owner-1",
            page = 0,
            pageSize = 10,
        )
        assertNull(result.items[0].attribution)
        assertTrue("distribution 不该报缺字段", result.missingFields.isEmpty())
    }

    /**
     * 缺归因上下文时**卡片仍要能显示**，但要报诊断事件的字段清单。
     *
     * 方案 §8.1 明写「`screen_recommend_attribution_missing` 是诊断事件，
     * 说明归因会丢，要保留」—— 不报的话归因静默失效，没人会发现。
     */
    @Test
    fun `缺上下文时卡片仍显示但报缺失字段`() {
        val result = ScreenAttribution.attribute(
            items = listOf(item("character-a")),
            endpoint = ScreenEndpoint.RECOMMENDATION,
            requestId = "",
            sessionId = null,
            ownerUserId = "owner-1",
            page = 0,
            pageSize = 10,
        )
        assertEquals(1, result.items.size)
        assertNull("归因不成立", result.items[0].attribution)
        assertEquals(listOf("request_id", "session_id"), result.missingFields)
    }

    // ── 壳侧补充 ────────────────────────────────────

    @Test
    fun `owner_user_id 缺失也进缺失清单且归因为 null`() {
        val result = ScreenAttribution.attribute(
            items = listOf(item("a")),
            endpoint = ScreenEndpoint.RECOMMENDATION,
            requestId = "r",
            sessionId = "s",
            ownerUserId = null,
            page = 0,
            pageSize = 10,
        )
        assertEquals(listOf("owner_user_id"), result.missingFields)
        assertNull(result.items[0].attribution)
    }

    @Test
    fun `归因四字段齐备时成立`() {
        val result = ScreenAttribution.attribute(
            items = listOf(item("a")),
            endpoint = ScreenEndpoint.RECOMMENDATION,
            requestId = "r",
            sessionId = "s",
            ownerUserId = "o",
            page = 0,
            pageSize = 20,
        )
        val attribution = result.items[0].attribution
        assertNotNull(attribution)
        assertEquals("r", attribution?.requestId)
        assertEquals(0, attribution?.position)
    }

    /** 空白串按空处理（RN 的 `normalizedString` 会 trim）。 */
    @Test
    fun `空白 requestId 视为缺失`() {
        val result = ScreenAttribution.attribute(
            items = listOf(item("a")),
            endpoint = ScreenEndpoint.RECOMMENDATION,
            requestId = "   ",
            sessionId = "s",
            ownerUserId = "o",
            page = 0,
            pageSize = 10,
        )
        assertEquals(listOf("request_id"), result.missingFields)
    }

    @Test
    fun `负 position 不产生归因`() {
        assertNull(
            ScreenAttribution.create(
                requestId = "r",
                sessionId = "s",
                characterId = "c",
                position = -1,
                ownerUserId = "o",
            ),
        )
    }

    @Test
    fun `全 null 列表返回空且不崩`() {
        val result = ScreenAttribution.attribute(
            items = listOf(null, null),
            endpoint = ScreenEndpoint.RECOMMENDATION,
            requestId = "r",
            sessionId = "s",
            ownerUserId = "o",
            page = 0,
            pageSize = 10,
        )
        assertTrue(result.items.isEmpty())
    }

    // ── 首屏合并（对拍 showcaseFirstScreenFeed.test.ts 53 行）────

    /** 缓存头只挂**本次**响应的归因（旧归因必须换掉）。 */
    @Test
    fun `缓存头重绑当次响应的归因`() {
        val stale = attribution("stale-request")
        val current = attribution("current-request")
        val result = ScreenFirstScreenFeed.merge(
            cachedHeadItem = item("character-a").copy(attribution = stale),
            networkItems = listOf(
                item("character-a").copy(attribution = current),
                item("character-b"),
            ),
        )
        assertEquals("current-request", result[0].attribution?.requestId)
    }

    /** 缓存卡不在本次响应里 → **清空归因**，不留旧的。 */
    @Test
    fun `缓存卡不在响应里则清空归因`() {
        val result = ScreenFirstScreenFeed.merge(
            cachedHeadItem = item("character-a").copy(attribution = attribution("stale")),
            networkItems = listOf(item("character-b"), item("character-c")),
        )
        assertEquals("character-a", result[0].characterId)
        assertNull(result[0].attribution)
    }

    /**
     * ⚠️ 无缓存时也 `drop(1)` —— 丢掉的那条已被调用方写进缓存
     * （见 `ScreenFirstScreenFeed` 类注释的原子步骤说明）。
     */
    @Test
    fun `无缓存时丢弃网络第一条`() {
        val result = ScreenFirstScreenFeed.merge(
            cachedHeadItem = null,
            networkItems = listOf(item("a"), item("b"), item("c")),
        )
        assertEquals(listOf("b", "c"), result.map { it.characterId })
    }

    /** 缓存头与 rest 里的同 id 只保留一条（头）。 */
    @Test
    fun `缓存头与列表重复时只留头`() {
        val result = ScreenFirstScreenFeed.merge(
            cachedHeadItem = item("b"),
            networkItems = listOf(item("a"), item("b"), item("c")),
        )
        assertEquals(listOf("b", "c"), result.map { it.characterId })
    }

    // ── AB flag 解析与端点分流 ──────────────────────

    /**
     * ⚠️ 四种真值写法都要认（`abConfig/value.ts:5-8`）。
     *
     * 只认 `"true"` 会让运营在后台填 `1` 时 AB **静默失效** ——
     * 表现为「推荐端点永远不命中」，没人会报。
     */
    @Test
    fun `flag 接受四种真值与四种假值`() {
        listOf("true", "TRUE", " 1 ", "yes", "on").forEach {
            assertEquals("『$it』应为真", true, ScreenEndpointResolver.parseFlag(it))
        }
        listOf("false", "0", "no", "off").forEach {
            assertEquals("『$it』应为假", false, ScreenEndpointResolver.parseFlag(it))
        }
    }

    /** 认不出的值返回 null（由调用方 `?? false` 兜底）—— 与「配了 false」可区分。 */
    @Test
    fun `flag 认不出的值返回 null`() {
        assertNull(ScreenEndpointResolver.parseFlag(null))
        assertNull(ScreenEndpointResolver.parseFlag(""))
        assertNull(ScreenEndpointResolver.parseFlag("maybe"))
    }

    @Test
    fun `端点分流的三种组合`() {
        // 游客：flag 为真也走 distribution
        assertEquals(
            ScreenEndpoint.DISTRIBUTION,
            ScreenEndpointResolver.resolve(ownerUserId = null, flagEnabled = true),
        )
        assertEquals(
            ScreenEndpoint.DISTRIBUTION,
            ScreenEndpointResolver.resolve(ownerUserId = "  ", flagEnabled = true),
        )
        assertEquals(
            ScreenEndpoint.DISTRIBUTION,
            ScreenEndpointResolver.resolve(ownerUserId = "u1", flagEnabled = false),
        )
        assertEquals(
            ScreenEndpoint.RECOMMENDATION,
            ScreenEndpointResolver.resolve(ownerUserId = "u1", flagEnabled = true),
        )
    }

    // ── 缓存签名 ────────────────────────────────────

    /** ⚠️ `tagIds` 必须排序，否则选择顺序不同会得到不同签名（白降命中率）。 */
    @Test
    fun `签名对 tagIds 顺序不敏感`() {
        fun sig(tags: List<String>) = ScreenCacheSignature.of(
            ownerUserId = "u1",
            endpoint = ScreenEndpoint.RECOMMENDATION,
            nsfw = false,
            gender = null,
            languageCode = "en",
            tagIds = tags,
            contentType = null,
        )
        assertEquals(sig(listOf("a", "b")), sig(listOf("b", "a")))
    }

    /** 游客的 owner 归一成 `anonymous` —— 空串会让游客与拿不到 id 的登录态共用缓存。 */
    @Test
    fun `空 owner 归一成 anonymous`() {
        val a = ScreenCacheSignature.of(null, ScreenEndpoint.DISTRIBUTION, false, null, "en", emptyList(), null)
        val b = ScreenCacheSignature.of("  ", ScreenEndpoint.DISTRIBUTION, false, null, "en", emptyList(), null)
        assertEquals(a, b)
        assertTrue(a.startsWith(ScreenCacheSignature.ANONYMOUS))
    }

    /** 端点变化必须让签名变（否则切 AB 后读到另一个端点的缓存）。 */
    @Test
    fun `端点进签名`() {
        val d = ScreenCacheSignature.of("u1", ScreenEndpoint.DISTRIBUTION, false, null, "en", emptyList(), null)
        val r = ScreenCacheSignature.of("u1", ScreenEndpoint.RECOMMENDATION, false, null, "en", emptyList(), null)
        assertTrue(d != r)
    }

    // ── 首屏缓存往返（存→读同一个解析器）────────────

    /**
     * 三形态都必须能**原样读回**。
     *
     * 存的是「接口同形」JSON，读路径复用 `ScreenFeedItem.parse` ——
     * 回填字段错了会让读回来的形态与存进去的不同（如 showcase 变 static），
     * 而那只在下次冷启动才显现。
     */
    @Test
    fun `缓存往返保留三形态`() {
        val store = FakeMmkv()
        val cache = ScreenFirstScreenCacheStore(store, logWarn = { _, _ -> })

        val showcase = item("s").copy(
            mediaSourceType = ScreenMediaSourceType.SHOWCASE,
            backgroundUrl = "https://cdn/v.mp4",
            thumbnailUrl = "https://cdn/cover.jpg",
            imageUrl = "https://cdn/character.jpg",
            gender = "female",
            nsfw = false,
            lang = "en",
        )
        cache.put("sig", showcase)
        val readBack = cache.get("sig")
        assertEquals(ScreenMediaSourceType.SHOWCASE, readBack?.mediaSourceType)
        assertEquals("https://cdn/v.mp4", readBack?.backgroundUrl)
        assertEquals("https://cdn/cover.jpg", readBack?.thumbnailUrl)
        assertEquals(
            "视频封面不能冒充影院首帧角色图",
            "https://cdn/character.jpg",
            readBack?.imageUrl,
        )
        assertEquals("female", readBack?.gender)
        assertEquals(false, readBack?.nsfw)
        assertEquals("en", readBack?.lang)

        val chatPreload = showcase.toChatDetailPreload()
        assertEquals("https://cdn/character.jpg", chatPreload.imageUrl)
        assertEquals("https://cdn/v.mp4", chatPreload.greetingVideoUrl)
        assertEquals("https://cdn/cover.jpg", chatPreload.greetingVideoCoverUrl)

        val animated = item("a").copy(
            mediaSourceType = ScreenMediaSourceType.ANIMATED_IMAGE,
            backgroundUrl = "https://cdn/a.webp",
            thumbnailUrl = "https://cdn/a.jpg",
        )
        cache.put("sig", animated)
        assertEquals(
            ScreenMediaSourceType.ANIMATED_IMAGE,
            cache.get("sig")?.mediaSourceType,
        )

        val static = item("t").copy(
            mediaSourceType = ScreenMediaSourceType.STATIC_IMAGE,
            backgroundUrl = "https://cdn/t.jpg",
            thumbnailUrl = "https://cdn/t.jpg",
        )
        cache.put("sig", static)
        assertEquals(ScreenMediaSourceType.STATIC_IMAGE, cache.get("sig")?.mediaSourceType)
    }

    @Test
    fun `签名不匹配当未命中`() {
        val cache = ScreenFirstScreenCacheStore(FakeMmkv(), logWarn = { _, _ -> })
        cache.put("sig-a", item("x"))
        assertNull(cache.get("sig-b"))
        assertNotNull(cache.get("sig-a"))
    }

    /** ⚠️ 归因是请求级的，**不得**存进缓存（否则读出的是过期归因）。 */
    @Test
    fun `缓存不保存归因`() {
        val cache = ScreenFirstScreenCacheStore(FakeMmkv(), logWarn = { _, _ -> })
        cache.put("sig", item("x").copy(attribution = attribution("r")))
        assertNull(cache.get("sig")?.attribution)
    }

    @Test
    fun `缓存损坏当未命中不抛`() {
        val store = FakeMmkv()
        store.data[ScreenFirstScreenCacheStore.CACHE_KEY] = "{ not json"
        val cache = ScreenFirstScreenCacheStore(store, logWarn = { _, _ -> })
        assertNull(cache.get("sig"))
    }

    private class FakeMmkv : HomeCacheStorage {
        val data = mutableMapOf<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun putString(key: String, value: String): Boolean {
            data[key] = value
            return true
        }
    }

    private fun attribution(requestId: String) = ScreenAttribution(
        requestId = requestId,
        sessionId = "session-1",
        characterId = "character-a",
        position = 0,
        ownerUserId = "owner-1",
    )

    private fun item(id: String) = ScreenFeedItem(
        characterId = id,
        mediaSourceType = ScreenMediaSourceType.STATIC_IMAGE,
        backgroundUrl = null,
        thumbnailUrl = null,
        imageUrl = null,
        tagline = "",
        greeting = "",
        nickname = id,
        creatorId = null,
        creatorNickname = null,
        creatorAvatarUrl = null,
        avatarUrl = null,
        likeCount = 0,
        commentCount = 0,
        totalMessages = 0,
        primaryColor = null,
        gender = null,
        nsfw = null,
        isTranslated = false,
        lang = null,
        characterType = null,
        contentType = null,
    )
}
