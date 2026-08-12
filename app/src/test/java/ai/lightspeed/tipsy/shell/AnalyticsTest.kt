package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.analytics.Analytics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 埋点 facade 的 uid 排队语义（W2，对齐 `modules/qt/src/QtAnalytics.ts:404-420`）。
 *
 * ## 为什么这层必须有测试
 *
 * 排队逻辑错了的症状是**该事件永久不上报** —— 不报错、不崩溃，只有等到看报表
 * 时才发现数据缺了一块。方案 §8.1 记的「`character_page_exposure` 需手动补 uid」
 * 说的就是这条链，现在由 facade 统一处理，所以正确性集中在这里。
 */
class AnalyticsTest {

    private val sent = mutableListOf<Triple<String, Map<String, Any?>, String?>>()

    @Before
    fun setUp() {
        Analytics.resetForTest()
        sent.clear()
        Analytics.install { id, params, page -> sent.add(Triple(id, params, page)) }
    }

    @After
    fun tearDown() {
        Analytics.resetForTest()
    }

    @Test
    fun `普通事件立即发出 —— 不受 uid 绑定影响`() {
        // 只有那四个 uid-required 事件才排队。多排一个会让游客期事件丢失
        Analytics.track("page_exposure", mapOf("page_name" to "discover"))
        assertEquals(1, sent.size)
        assertEquals("page_exposure", sent.single().first)
    }

    @Test
    fun `uid-required 事件在未绑定时排队而不是发出`() {
        Analytics.track("character_page_exposure", mapOf("characterId" to "c1"))
        assertTrue("不应立即发出", sent.isEmpty())
        assertEquals(1, Analytics.deferredCountForTest())
    }

    @Test
    fun `绑定后补 uid 并冲出`() {
        Analytics.track("character_page_exposure", mapOf("characterId" to "c1"))
        Analytics.track("character_page_exposure", mapOf("characterId" to "c2"))
        Analytics.bindUserId("u1")

        assertEquals(2, sent.size)
        assertEquals("u1", sent[0].second["uid"])
        assertEquals("u1", sent[1].second["uid"])
        // 冲出后队列要清空 —— 不清会让下次绑定重复上报
        assertEquals(0, Analytics.deferredCountForTest())
        // 顺序保留（FIFO）：曝光顺序即用户浏览顺序
        assertEquals("c1", sent[0].second["characterId"])
        assertEquals("c2", sent[1].second["characterId"])
    }

    @Test
    fun `已绑定时 uid-required 事件直接发出并带 uid`() {
        Analytics.bindUserId("u1")
        Analytics.track("character_page_exposure", mapOf("characterId" to "c1"))
        assertEquals(1, sent.size)
        assertEquals("u1", sent.single().second["uid"])
    }

    @Test
    fun `调用方已给 uid 时不覆盖也不排队`() {
        // RN 的条件是 `!params?.uid`（QtAnalytics.ts:409）
        Analytics.track("character_page_exposure", mapOf("uid" to "explicit"))
        assertEquals(1, sent.size)
        assertEquals("explicit", sent.single().second["uid"])
    }

    @Test
    fun `排队上限 50 且丢最旧`() {
        // 对齐 RN 的 `slice(-50)`。⚠️ 丢**最旧**而不是拒绝新的 ——
        // 丢最新会让刚发生的曝光永远上不去，排查时更难判断
        repeat(55) { i ->
            Analytics.track("character_page_exposure", mapOf("characterId" to "c$i"))
        }
        assertEquals(50, Analytics.deferredCountForTest())

        Analytics.bindUserId("u1")
        assertEquals(50, sent.size)
        // 最早的 5 条（c0..c4）被丢弃，第一条应是 c5
        assertEquals("c5", sent.first().second["characterId"])
        assertEquals("c54", sent.last().second["characterId"])
    }

    @Test
    fun `空白 userId 视为未绑定`() {
        Analytics.track("character_page_exposure", mapOf("characterId" to "c1"))
        Analytics.bindUserId("   ")
        assertTrue("空白 id 不应冲出队列", sent.isEmpty())
        assertEquals(1, Analytics.deferredCountForTest())
    }

    @Test
    fun `登出解绑后新事件重新排队`() {
        Analytics.bindUserId("u1")
        Analytics.unbindUserId()
        Analytics.track("character_page_exposure", mapOf("characterId" to "c1"))
        assertTrue(sent.isEmpty())
        assertEquals(1, Analytics.deferredCountForTest())
    }

    @Test
    fun `登出不清空已排队事件`() {
        // 未上报的曝光在下次登录后仍应补齐 —— 清掉等于丢数据
        Analytics.track("character_page_exposure", mapOf("characterId" to "c1"))
        Analytics.unbindUserId()
        assertEquals(1, Analytics.deferredCountForTest())
        Analytics.bindUserId("u2")
        assertEquals(1, sent.size)
        assertEquals("u2", sent.single().second["uid"])
    }

    @Test
    fun `四个 uid-required 事件名逐一生效`() {
        // 名单照抄 RN（QtAnalytics.ts:5-10）。少一个 → 那个事件缺 uid 上报；
        // 多一个 → 该事件在未登录期被排队而不是立即上报
        val ids = listOf(
            "character_page_exposure",
            "chat_page_exposure",
            "orientation_gender_page_exposure",
            "orientation_tag_page_exposure",
        )
        ids.forEach { Analytics.track(it) }
        assertTrue("四个都该排队", sent.isEmpty())
        assertEquals(ids.size, Analytics.deferredCountForTest())
    }

    @Test
    fun `pageName 透传`() {
        Analytics.track("page_exposure", emptyMap(), pageName = "discover")
        assertEquals("discover", sent.single().third)
    }

    @Test
    fun `sink 内再次 track 不死锁`() {
        // sink 的真实实现（未来的 Qt SDK）可能同步回调进来。持锁发送会自锁死 ——
        // 表现为**整个 App 卡住**，是这个类里最严重的失败模式
        Analytics.resetForTest()
        val seen = mutableListOf<String>()
        Analytics.install { id, _, _ ->
            seen.add(id)
            if (id == "outer") Analytics.track("inner")
        }
        Analytics.track("outer")
        assertEquals(listOf("outer", "inner"), seen)
    }
}
