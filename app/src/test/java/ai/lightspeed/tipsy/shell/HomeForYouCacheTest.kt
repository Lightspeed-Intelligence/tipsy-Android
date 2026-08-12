package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.home.HomeCacheStorage
import ai.lightspeed.tipsy.shell.pages.home.HomeForYouCache
import ai.lightspeed.tipsy.shell.pages.home.HomeGender
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * For You 冷启动种子缓存（方案 §4.6 的信封 + 三道门禁）。
 *
 * 这里每一条都是「错了不报错」：门禁写松 → **给 A 账号显示 B 账号的推荐**
 * 或显示一年前的数据；写紧 → 永远没有种子（白屏依旧），而两者本地都看不出来。
 */
class HomeForYouCacheTest {

    private class FakeStorage(var value: String? = null) : HomeCacheStorage {
        var writable = true
        override fun getString(key: String): String? = value
        override fun putString(key: String, value: String): Boolean {
            if (!writable) return false
            this.value = value
            return true
        }
    }

    /** 一条 For You 的原始 item（`{type, request_id, data:{character:{...}}}`）。 */
    private fun rawItem(id: String) = JSONObject(
        """{"type":"character","request_id":"r-$id","data":{"character":{
            "character_id":"$id","nickname":"n$id","introduction":"i",
            "image_url":"u","creator_id":"c"}}}""",
    )

    private fun rawList(vararg ids: String) = JSONArray().apply {
        ids.forEach { put(rawItem(it)) }
    }

    private fun cache(storage: FakeStorage, now: Long = 1_000_000L) =
        HomeForYouCache(storage, nowMs = { now }, logWarn = { _, _ -> })

    // ── 往返 ──────────────────────────────────────────────

    @Test
    fun `写入后能读回`() {
        val storage = FakeStorage()
        val c = cache(storage)
        c.write("user:u1", HomeGender.ALL, rawList("a", "b"))

        val items = c.read("user:u1", HomeGender.ALL)
        // stableKey 是 `${requestId}-${characterId}` = `r-a-a`
        assertEquals(listOf("r-a-a", "r-b-b"), items.map { it.stableKey })
    }

    @Test
    fun `只存前 5 条`() {
        // 存整页会让冷启动读盘与解析变慢，首屏可见的也就前几张
        val storage = FakeStorage()
        val c = cache(storage)
        c.write("guest", HomeGender.ALL, rawList("a", "b", "c", "d", "e", "f", "g"))

        assertEquals(HomeForYouCache.LOCKED_SIZE, c.read("guest", HomeGender.ALL).size)
    }

    @Test
    fun `空数组不写`() {
        val storage = FakeStorage()
        cache(storage).write("guest", HomeGender.ALL, JSONArray())
        assertTrue("不该写出空信封", storage.value == null)
    }

    // ── authScope 门禁（漏了就是跨账号串数据）────────────

    @Test
    fun `换账号后读不到上一账号的种子`() {
        val storage = FakeStorage()
        val c = cache(storage)
        c.write("user:u1", HomeGender.ALL, rawList("a"))

        assertTrue(c.read("user:u2", HomeGender.ALL).isEmpty())
    }

    @Test
    fun `登出后读不到已登录时的种子`() {
        val storage = FakeStorage()
        val c = cache(storage)
        c.write("user:u1", HomeGender.ALL, rawList("a"))

        assertTrue(c.read("guest", HomeGender.ALL).isEmpty())
    }

    @Test
    fun `guest 的种子在 guest 下可读`() {
        val storage = FakeStorage()
        val c = cache(storage)
        c.write("guest", HomeGender.ALL, rawList("a"))

        assertEquals(1, c.read("guest", HomeGender.ALL).size)
    }

    @Test
    fun `authScopeOf 空 userId 是 guest`() {
        assertEquals("guest", HomeForYouCache.authScopeOf(null))
        assertEquals("guest", HomeForYouCache.authScopeOf(""))
        assertEquals("guest", HomeForYouCache.authScopeOf("   "))
        assertEquals("user:u1", HomeForYouCache.authScopeOf("u1"))
    }

    // ── TTL ───────────────────────────────────────────────

    @Test
    fun `7 天内有效`() {
        val storage = FakeStorage()
        cache(storage, now = 0L).write("guest", HomeGender.ALL, rawList("a"))
        val justUnder = HomeForYouCache.TTL_MS - 1
        assertEquals(1, cache(storage, now = justUnder).read("guest", HomeGender.ALL).size)
    }

    @Test
    fun `超 7 天作废`() {
        val storage = FakeStorage()
        cache(storage, now = 0L).write("guest", HomeGender.ALL, rawList("a"))
        val justOver = HomeForYouCache.TTL_MS + 1
        assertTrue(cache(storage, now = justOver).read("guest", HomeGender.ALL).isEmpty())
    }

    @Test
    fun `设备时钟回退时作废而不是永久有效`() {
        // savedAt 在"未来" → age 为负。不挡的话一个错时钟能让种子永不过期
        val storage = FakeStorage()
        cache(storage, now = 10_000L).write("guest", HomeGender.ALL, rawList("a"))
        assertTrue(cache(storage, now = 5_000L).read("guest", HomeGender.ALL).isEmpty())
    }

    // ── gender 门禁 ───────────────────────────────────────

    @Test
    fun `性别筛选变了作废`() {
        // 种子是按当时筛选拉的，换了性别还显示就是错数据
        val storage = FakeStorage()
        val c = cache(storage)
        c.write("guest", HomeGender.FEMALE, rawList("a"))

        assertTrue(c.read("guest", HomeGender.MALE).isEmpty())
        assertEquals(1, c.read("guest", HomeGender.FEMALE).size)
    }

    // ── version 门禁与坏数据 ──────────────────────────────

    @Test
    fun `version 不匹配作废`() {
        val storage = FakeStorage(
            """{"version":999,"authScope":"guest","gender":"undefined",
               "savedAt":1000000,"items":[]}""",
        )
        assertTrue(cache(storage).read("guest", HomeGender.ALL).isEmpty())
    }

    @Test
    fun `坏 JSON 不抛异常只返回空`() {
        // 冷启动路径上抛异常等于启动即崩
        val storage = FakeStorage("{不是合法 JSON")
        assertTrue(cache(storage).read("guest", HomeGender.ALL).isEmpty())
    }

    @Test
    fun `缺 savedAt 作废`() {
        val storage = FakeStorage(
            """{"version":1,"authScope":"guest","gender":"undefined","items":[]}""",
        )
        assertTrue(cache(storage).read("guest", HomeGender.ALL).isEmpty())
    }

    @Test
    fun `无缓存返回空`() {
        assertTrue(cache(FakeStorage()).read("guest", HomeGender.ALL).isEmpty())
    }

    @Test
    fun `MMKV 不可写时不抛`() {
        val storage = FakeStorage()
        storage.writable = false
        cache(storage).write("guest", HomeGender.ALL, rawList("a"))
        // 持久化失败不该让页面崩溃
        assertTrue(storage.value == null)
    }
}
