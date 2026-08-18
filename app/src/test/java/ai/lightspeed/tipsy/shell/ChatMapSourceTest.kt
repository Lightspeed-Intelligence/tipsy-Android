package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.chatlist.ChatDraft
import ai.lightspeed.tipsy.shell.pages.chatlist.ChatListState
import ai.lightspeed.tipsy.shell.pages.chatlist.ChatMapSource
import ai.lightspeed.tipsy.shell.pages.chatlist.ChatThread
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Map 的**生产入口**（W3-P2）。
 *
 * 这里钉的是「签名拦不住、只能靠这一层保证」的两件事：
 * 1. 生产路径**必须**取 `state.threads` 而不是 `state.sortedThreads`；
 * 2. 本地日换算的三个静默错误点（秒/毫秒、UTC vs 本地、key 与标题同源）。
 */
class ChatMapSourceTest {

    private val utcPlus8 = TimeZone.getTimeZone("Asia/Shanghai")
    private val utc = TimeZone.getTimeZone("UTC")

    /** 2026-08-18 12:00 UTC，用作"今天"基准。 */
    private val nowMillis = 1_787_054_400_000L

    private fun floors(
        state: ChatListState,
        tz: TimeZone = utcPlus8,
        now: Long = nowMillis,
    ) = ChatMapSource.floorsFor(
        state = state,
        timeZone = tz,
        nowMillis = now,
        localize = { "L($it)" },
        // 把 includeYear 编进字符串，测试才能断言分支
        formatDate = { t ->
            if (t.includeYear) "${t.year}-${t.month}-${t.dayOfMonth}+Y" else "${t.month}-${t.dayOfMonth}"
        },
    )

    @Test
    fun `生产入口固定取 raw threads —— 即使 sortedThreads 不同`() {
        // 造出 sortedThreads != threads：给较早的会话加草稿 + pin，
        // 让展示序把它顶到最前
        val early = thread("early", timeSeconds = daySeconds(17, hour = 10))
        val late = thread("late", timeSeconds = daySeconds(18, hour = 10))
        val state = ChatListState(
            threads = listOf(late, early), // 接口顺序：late 在前
            drafts = mapOf("early" to ChatDraft(text = "d", imageCount = 0, updatedAt = nowMillis)),
        )

        // 前提校验：这个 state 确实让两者不同（否则这条测试等于没测）
        assertNotEquals(
            "fixture 必须造出 sortedThreads != threads",
            state.threads.map { it.itemId },
            state.sortedThreads.map { it.itemId },
        )

        val result = floors(state)
        // ⚠️ 楼层顺序必须跟 raw threads（late 那天在前），
        // 不是 sortedThreads（early 被草稿顶到前面）
        assertEquals("L(Today)", result[0].title)
        assertEquals("L(Yesterday)", result[1].title)
        assertEquals(listOf("late"), result[0].items.map { it.itemId })
        assertEquals(listOf("early"), result[1].items.map { it.itemId })
    }

    @Test
    fun `latestTimeSeconds 按秒解释而不是毫秒`() {
        // ⚠️ 当成毫秒会把一切算到 1970-01-01 → 全挤进一层、标题变成 1970 日期
        val t = thread("a", timeSeconds = daySeconds(18, hour = 10))
        val result = floors(ChatListState(threads = listOf(t)))
        assertEquals("L(Today)", result[0].title)
        // 反向：若被当毫秒，标题会是 1970-x-x
        assertTrue("标题不得落到 1970", result.none { it.title.startsWith("1970") })
    }

    @Test
    fun `午夜两侧按本地时区分层 —— 不是 UTC`() {
        // UTC+8 的 2026-08-18 00:01 与 23:59 是**同一天**；
        // 而按 UTC 切分（seconds/86400）时 00:01 会落到 08-17。
        // ⚠️ 这正是「直接 /86400」的静默错误：本地 08:00 前的会话算到前一天
        val justAfterMidnight = thread("after", timeSeconds = daySeconds(18, hour = 0, minute = 1))
        val justBeforeMidnight = thread("before", timeSeconds = daySeconds(18, hour = 23, minute = 59))

        val result = floors(ChatListState(threads = listOf(justBeforeMidnight, justAfterMidnight)))
        val chatFloors = result.filter { it.items.isNotEmpty() }
        assertEquals("UTC+8 下这两条必须同层", 1, chatFloors.size)
        assertEquals(2, chatFloors[0].items.size)
    }

    @Test
    fun `换时区会改变分层结果 —— 证明时区真的参与了换算`() {
        // 同一批数据在 UTC+8 与 UTC 下分层不同 —— 若实现忽略时区，两者会相同
        val t = thread("a", timeSeconds = daySeconds(18, hour = 2)) // UTC+8 的 02:00 = UTC 前一天 18:00
        val inShanghai = floors(ChatListState(threads = listOf(t)), tz = utcPlus8)
        val inUtc = floors(ChatListState(threads = listOf(t)), tz = utc)
        assertNotEquals(
            "时区必须参与分日（否则两者标题相同）",
            inShanghai[0].title,
            inUtc[0].title,
        )
    }

    @Test
    fun `key 与标题来自同一个本地日`() {
        // ⚠️ 两处用不同时区会出现"标题写 Today、却和昨天的会话同层"
        val todayA = thread("a", timeSeconds = daySeconds(18, hour = 1))
        val todayB = thread("b", timeSeconds = daySeconds(18, hour = 22))
        val result = floors(ChatListState(threads = listOf(todayA, todayB)))
        val chat = result.filter { it.items.isNotEmpty() }
        assertEquals(1, chat.size)
        assertEquals("L(Today)", chat[0].title)
        // 同层 → 同 key，且 key 在 day: 命名空间
        assertTrue("key 必须是 day: 命名空间", chat[0].key.startsWith("day:"))
    }

    @Test
    fun `楼层 key 三个命名空间互不碰撞`() {
        val t = thread("a", timeSeconds = daySeconds(18, hour = 10))
        val result = floors(ChatListState(threads = listOf(t)))
        // 1 真实组 → 3 组（补 2）+ 2 跑道 = 5 层
        assertEquals(5, result.size)
        assertEquals("key 必须全唯一", result.size, result.map { it.key }.distinct().size)
        assertTrue(result[0].key.startsWith("day:"))
        assertTrue(result[1].key.startsWith("pad:"))
        assertTrue(result[3].key.startsWith("runway:"))
    }

    @Test
    fun `跨年时 1月1日 看 12月31日 仍是 Yesterday`() {
        // ⚠️ 早前用 `todayDay - 1` 判定，而序号是 `year*512 + dayOfYear`、
        // **在年界不连续**（2027-01-01 与 2026-12-31 相差 660 不是 1），
        // 所以跨年会漏判成日期标题。
        // RN 是 `today.subtract(1,'day')`（func.ts:355）、iOS 是 isDateInYesterday
        // —— 两端跨年都显示 Yesterday，这不是可接受偏差
        val newYearNoon = millisAt(2027, java.util.Calendar.JANUARY, 1, 12)
        val lastDayOfPrevYear = thread("nye", timeSeconds = millisAt(2026, java.util.Calendar.DECEMBER, 31, 10) / 1000L)

        val result = floors(ChatListState(threads = listOf(lastDayOfPrevYear)), now = newYearNoon)
        assertEquals("跨年必须显示 Yesterday", "L(Yesterday)", result[0].title)
    }

    @Test
    fun `跨年时今天仍是 Today`() {
        val newYearNoon = millisAt(2027, java.util.Calendar.JANUARY, 1, 12)
        val today = thread("t", timeSeconds = millisAt(2027, java.util.Calendar.JANUARY, 1, 9) / 1000L)
        val result = floors(ChatListState(threads = listOf(today)), now = newYearNoon)
        assertEquals("L(Today)", result[0].title)
    }

    @Test
    fun `同年普通日不带年份，跨年普通日带年份`() {
        // RN：同年 `D MMMM`；跨年 `MMM D, YYYY`（func.ts:357-361）。
        // ⚠️ 分支必须在 Source 里判完 —— 只给 UI (y,m,d) 的话 UI 得自己
        // 再捕获一次 now/timezone，两处捕获时机不同会在午夜/跨年给出矛盾结果
        val sameYear = thread("a", timeSeconds = daySeconds(10, hour = 10)) // 2026-08-10
        val prevYear = thread("b", timeSeconds = millisAt(2025, java.util.Calendar.AUGUST, 12, 10) / 1000L)

        val result = floors(ChatListState(threads = listOf(sameYear, prevYear)))
        val titles = result.filter { it.items.isNotEmpty() }.map { it.title }
        assertEquals(2, titles.size)
        assertEquals("同年不带年份", "8-10", titles[0])
        assertEquals("跨年带年份", "2025-8-12+Y", titles[1])
    }

    @Test
    fun `空状态也铺满层`() {
        val result = floors(ChatListState())
        assertEquals(5, result.size)
        assertTrue(result.all { it.items.isEmpty() })
    }

    /** UTC+8 下指定年月日时的毫秒时间戳。 */
    private fun millisAt(year: Int, month: Int, day: Int, hour: Int): Long {
        val cal = java.util.Calendar.getInstance(utcPlus8)
        cal.set(year, month, day, hour, 0, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** UTC+8 下 2026-08-<day> <hour>:<minute> 的秒级时间戳。 */
    private fun daySeconds(day: Int, hour: Int, minute: Int = 0): Long {
        val cal = java.util.Calendar.getInstance(utcPlus8)
        cal.set(2026, java.util.Calendar.AUGUST, day, hour, minute, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis / 1000L
    }

    private fun thread(id: String, timeSeconds: Long) = ChatThread(
        itemType = "character",
        itemId = id,
        itemName = "n_$id",
        gameId = null,
        faceUrl = "",
        imageUrl = "",
        introduction = "",
        greeting = null,
        lastMessageContent = null,
        latestTimeSeconds = timeSeconds,
        isPinned = false,
        isPushMessage = false,
        isPushMessageViewed = false,
        currentStreakDays = 0,
        chatMode = null,
        conversationId = null,
        parentConversationId = null,
        characterType = null,
        contentType = null,
        creatorId = null,
        versionChange = false,
    )
}
