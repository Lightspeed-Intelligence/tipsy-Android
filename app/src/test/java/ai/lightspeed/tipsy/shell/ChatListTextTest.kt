package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.chatlist.ChatDraft
import ai.lightspeed.tipsy.shell.pages.chatlist.ChatListText
import ai.lightspeed.tipsy.shell.pages.chatlist.ChatListState
import ai.lightspeed.tipsy.shell.pages.chatlist.ChatPageType
import ai.lightspeed.tipsy.shell.pages.chatlist.ChatThread
import ai.lightspeed.tipsy.shell.pages.chatlist.RelationshipStat
import ai.lightspeed.tipsy.shell.pages.chatlist.mergeChatPageTypeIntoEnvelope
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * ChatList 的纯逻辑（时间格式 / cinema 剥离 / 排序派生 / 徽章判定 / 信封 merge）。
 *
 * 这些全是「错了不报错」的行为：时间格式错只是显示怪、排序错只是顺序怪、
 * 徽章误显示是把后端已关的功能亮出来 —— 都要靠断言钉住。
 */
class ChatListTextTest {

    // ── formatRowTime（ChatListItem.tsx:326-351）────────────

    private fun msOf(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance().apply {
            set(y, mo - 1, d, h, mi, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `今天显示 H_mm 且小时不补零`() {
        val now = msOf(2026, 8, 12, 20, 0)
        // RN 用裸 getHours() —— 9:05 而不是 09:05
        assertEquals("9:05", ChatListText.formatRowTime(msOf(2026, 8, 12, 9, 5), now))
    }

    @Test
    fun `今年显示 MM_DD 补零`() {
        val now = msOf(2026, 8, 12, 20, 0)
        assertEquals("03/07", ChatListText.formatRowTime(msOf(2026, 3, 7, 9, 5), now))
    }

    @Test
    fun `跨年显示 MM_DD_YY`() {
        val now = msOf(2026, 8, 12, 20, 0)
        assertEquals("03/07/25", ChatListText.formatRowTime(msOf(2025, 3, 7, 9, 5), now))
    }

    // ── cinema XML 剥离（lib/cinema/index.ts:102-165）───────

    @Test
    fun `cinema 消息剥成纯文本`() {
        val xml = """<initial_script>
            <voiceover>The cafe door swings open...</voiceover>
            <image_prompt>POV shot</image_prompt>
            <dialog>Elaine: It's been five years...</dialog>
            <options><option>Apologize</option></options>
            </initial_script>"""
        val out = ChatListText.convertCinemaXmlToPlainText(xml)
        assertTrue(out.contains("The cafe door swings open..."))
        // dialog 冒号后加引号
        assertTrue(out.contains("Elaine: \"It's been five years...\""))
        // image_prompt 与 options 不出现
        assertTrue(!out.contains("POV shot"))
        assertTrue(!out.contains("Apologize"))
    }

    @Test
    fun `dialog 支持全角冒号`() {
        val xml = "<initial_script><dialog>艾琳：五年了……</dialog></initial_script>"
        val out = ChatListText.convertCinemaXmlToPlainText(xml)
        // 输出恒标准冒号 + 英文引号（RN findColonIndex + 标准化输出）
        assertEquals("艾琳: \"五年了……\"", out)
    }

    @Test
    fun `非 cinema 消息原样返回`() {
        assertEquals("hello", ChatListText.convertCinemaXmlToPlainText("hello"))
    }

    // ── displayLastMessage 分流 ─────────────────────────────

    @Test
    fun `game 用 introduction 其余用 last_message 再 greeting`() {
        assertEquals("intro", ChatListText.displayLastMessage(thread(type = "game", intro = "intro")))
        assertEquals("msg", ChatListText.displayLastMessage(thread(lastMessage = "msg", greeting = "hi")))
        assertEquals("hi", ChatListText.displayLastMessage(thread(lastMessage = null, greeting = "hi")))
        // 全空 → null（UI 层走 No messages yet 词条）
        assertNull(ChatListText.displayLastMessage(thread(lastMessage = null, greeting = null)))
    }

    // ── 排序派生（ChatGrid.tsx:99-121）──────────────────────

    @Test
    fun `无草稿时保持接口原序`() {
        val a = thread(id = "a", time = 100)
        val b = thread(id = "b", time = 200) // 接口给的顺序 a 在前（后端有自己的排序）
        val state = ChatListState(threads = listOf(a, b))
        // RN 的捷径：draftMap 为空直接返回原序 —— 即使 b 时间更新也不重排
        assertEquals(listOf("a", "b"), state.sortedThreads.map { it.itemId })
    }

    @Test
    fun `有草稿时草稿时间参与重排且 pinned 恒在前`() {
        val pinned = thread(id = "p", time = 50, pinned = true)
        val a = thread(id = "a", time = 100)
        val b = thread(id = "b", time = 200)
        val state = ChatListState(
            threads = listOf(pinned, b, a),
            // a 有一条比 b 更新的草稿 → a 应排到 b 前
            drafts = mapOf("a" to ChatDraft(text = "draft", imageCount = 0, updatedAt = 300_000L)),
        )
        assertEquals(listOf("p", "a", "b"), state.sortedThreads.map { it.itemId })
    }

    @Test
    fun `mini_phone 行不吃同角色草稿`() {
        val mini = thread(id = "c1", time = 100, miniPhone = true)
        val state = ChatListState(
            threads = listOf(mini),
            drafts = mapOf("c1" to ChatDraft("draft", 0, 200_000L)),
        )
        // 草稿键是角色 id，普通会话的草稿不得串显到小手机行
        assertNull(state.draftFor(mini))
    }

    // ── 徽章四条件（ChatListItem.tsx:423-426）──────────────

    @Test
    fun `徽章双开关加等级加非小手机四条件`() {
        val stat = RelationshipStat("c1", subLevel = 3, level = 2, isRelationshipOpen = true)
        val base = ChatListState(
            threads = emptyList(),
            relationshipStats = mapOf("c1" to stat),
            relationshipSwitch = true,
        )
        val t = thread(id = "c1")
        assertEquals(stat, base.badgeFor(t))
        // 账号开关关 → 无徽章
        assertNull(base.copy(relationshipSwitch = false).badgeFor(t))
        // 角色开关关 → 无徽章
        assertNull(
            base.copy(relationshipStats = mapOf("c1" to stat.copy(isRelationshipOpen = false)))
                .badgeFor(t),
        )
        // sub_level 0 → 无徽章
        assertNull(
            base.copy(relationshipStats = mapOf("c1" to stat.copy(subLevel = 0))).badgeFor(t),
        )
        // mini_phone → 无徽章
        assertNull(base.badgeFor(thread(id = "c1", miniPhone = true)))
    }

    // ── chatPageType 信封 merge（同 gender 的纪律）──────────

    @Test
    fun `merge 只改 chatPageType 不动其它字段`() {
        val envelope = """{"state":{"gender":"female","nsfw":true,"chatPageType":"grid"},"version":3}"""
        val merged = JSONObject(mergeChatPageTypeIntoEnvelope(envelope, ChatPageType.MAP)!!)
        val state = merged.getJSONObject("state")
        assertEquals("map", state.getString("chatPageType"))
        // 同信封的其它字段一个都不能丢（整体覆盖会静默重置用户设置）
        assertEquals("female", state.getString("gender"))
        assertTrue(state.getBoolean("nsfw"))
        assertEquals(3, merged.getInt("version"))
    }

    @Test
    fun `信封缺失或残缺时不写`() {
        assertNull(mergeChatPageTypeIntoEnvelope(null, ChatPageType.MAP))
        assertNull(mergeChatPageTypeIntoEnvelope("", ChatPageType.MAP))
        assertNull(mergeChatPageTypeIntoEnvelope("not json", ChatPageType.MAP))
        // 缺 state 子对象说明不是 Zustand 信封 —— 不认
        assertNull(mergeChatPageTypeIntoEnvelope("""{"version":3}""", ChatPageType.MAP))
    }

    @Test
    fun `pageType 未知值回落 GRID`() {
        assertEquals(ChatPageType.GRID, ChatPageType.fromStored("holo_deck"))
        assertEquals(ChatPageType.GRID, ChatPageType.fromStored(null))
        assertEquals(ChatPageType.MAP, ChatPageType.fromStored("map"))
    }

    // ── fixture ─────────────────────────────────────────────

    private fun thread(
        id: String = "c1",
        type: String = "character",
        time: Long = 1L,
        pinned: Boolean = false,
        miniPhone: Boolean = false,
        intro: String = "",
        lastMessage: String? = null,
        greeting: String? = null,
    ) = ChatThread(
        itemType = type,
        itemId = id,
        itemName = "n",
        gameId = if (type == "game") "g_$id" else null,
        faceUrl = "",
        imageUrl = "",
        introduction = intro,
        greeting = greeting,
        lastMessageContent = lastMessage,
        latestTimeSeconds = time,
        isPinned = pinned,
        isPushMessage = false,
        isPushMessageViewed = false,
        currentStreakDays = 0,
        chatMode = if (miniPhone) "mini_phone" else null,
        conversationId = null,
        parentConversationId = null,
        characterType = null,
        contentType = null,
        creatorId = null,
        versionChange = false,
    )

    // ── formatMapCardTime（Map 卡时间，func.ts:322-340 / iOS formatChatGridTime）──

    @Test
    fun `Map 卡时间今天走注入的相对时间`() {
        val now = msOf(2026, 8, 12, 20, 0)
        val ts = msOf(2026, 8, 12, 18, 30)
        val out = ChatListText.formatMapCardTime(
            timestampMs = ts,
            nowMs = now,
            locale = java.util.Locale.US,
            relativeToday = { elapsed -> "rel:${elapsed / 60_000}m" },
        )
        assertEquals("今天分支必须走 relativeToday 且传对 elapsed", "rel:90m", out)
    }

    @Test
    fun `Map 卡时间今年是 d MMM 跨年是 MMM d yyyy`() {
        val now = msOf(2026, 8, 12, 20, 0)
        // ⚠️ 与 formatRowTime（Grid 行尾恒数字 03/07）不是同一个格式
        assertEquals(
            "7 Mar",
            ChatListText.formatMapCardTime(
                msOf(2026, 3, 7, 9, 0), now, java.util.Locale.US, relativeToday = { "x" },
            ),
        )
        assertEquals(
            "Mar 7, 2025",
            ChatListText.formatMapCardTime(
                msOf(2025, 3, 7, 9, 0), now, java.util.Locale.US, relativeToday = { "x" },
            ),
        )
    }

    @Test
    fun `Map 卡时间的月份名跟 locale 走`() {
        // RN dayjs 随 locale 换月份名（formatChatGridTime 开头 dayjs.locale(...)）；
        // 恒 US 会让非英语用户在卡片上看到英文月份
        val now = msOf(2026, 8, 12, 20, 0)
        val out = ChatListText.formatMapCardTime(
            msOf(2026, 3, 7, 9, 0), now, java.util.Locale.FRENCH, relativeToday = { "x" },
        )
        assertEquals("7 mars", out)
    }
}
