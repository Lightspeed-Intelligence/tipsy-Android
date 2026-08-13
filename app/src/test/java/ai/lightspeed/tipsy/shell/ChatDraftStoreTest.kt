package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.chatlist.ChatDraft
import ai.lightspeed.tipsy.shell.pages.chatlist.ChatDraftStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 草稿 LRU dump 解析（`chat_draft_lru`，壳只读）。
 *
 * 格式是 `lru-cache` 的 `dump()` 转储 `[[key,{value}],...]`，
 * 且有 legacy 纯字符串条目 —— 两种形状都要接住，坏数据返回空表不崩。
 */
class ChatDraftStoreTest {

    @Test
    fun `解析现行 ChatDraft 条目`() {
        val drafts = parse(
            """[["c1",{"value":{"text":"hello","updatedAt":1700000000000}}]]""",
        )
        assertEquals("hello", drafts["c1"]!!.text)
        assertEquals(1700000000000L, drafts["c1"]!!.updatedAt)
        assertEquals(0, drafts["c1"]!!.imageCount)
    }

    @Test
    fun `解析带图片附件的草稿`() {
        val drafts = parse(
            """[["c1",{"value":{"text":"","updatedAt":1,
               "imageAttachments":[{"id":"a","localUri":"u"},{"id":"b","localUri":"v"}]}}]]""",
        )
        assertEquals(2, drafts["c1"]!!.imageCount)
    }

    @Test
    fun `legacy 纯字符串条目可读且无时间戳`() {
        val drafts = parse("""[["c1",{"value":"old draft text"}]]""")
        assertEquals("old draft text", drafts["c1"]!!.text)
        assertNull(drafts["c1"]!!.updatedAt)
    }

    @Test
    fun `空草稿与坏条目跳过`() {
        val drafts = parse(
            """[["empty",{"value":{"text":"","updatedAt":1}}],
                ["blank",{"value":""}],
                ["good",{"value":{"text":"x","updatedAt":2}}],
                ["broken","not an object"]]""",
        )
        assertEquals(setOf("good"), drafts.keys)
    }

    @Test
    fun `整表坏 JSON 返回空表不崩`() {
        assertTrue(parse("not json at all").isEmpty())
        assertTrue(parse("").isEmpty())
    }

    /** 直接测解析逻辑：绕开 MMKV，把 JSON 喂给同一套代码。 */
    private fun parse(json: String): Map<String, ChatDraft> =
        ChatDraftStore.parseDumpForTest(json)
}
