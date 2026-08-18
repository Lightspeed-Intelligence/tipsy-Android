package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.i18n.AccountLanguageReader
import ai.lightspeed.tipsy.shell.i18n.AccountLanguageWriter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账号语言回写 `user-storage` 信封（进度文档 §2.37 的 FAIL 项修复）。
 *
 * 这些断言钉的是**破坏性最大的一类写入**：整体覆盖会静默清掉用户昵称/头像/
 * 引导状态等二十多个字段，且不报错。所以逐字段核，不只核 languageCode。
 */
class AccountLanguageWriterTest {

    @Test
    fun `写入后本类的读取口能读回同一个值`() {
        // 读写必须成对 —— 不成对正是 §2.37 那个缺陷的根因（写服务端、
        // 读信封，于是读到旧值把刚选的语言覆盖回英文）
        val merged = AccountLanguageWriter.merge(
            raw = """{"state":{"languageCode":"en","nickname":"x"},"version":0}""",
            languageCode = "zh-tw",
        )
        assertEquals("zh-tw", AccountLanguageReader.parse(merged))
    }

    @Test
    fun `merge 不动同信封里的其他字段`() {
        // 整体覆盖会重置用户一堆设置且不报错 —— 这是本文件最重要的一条
        val raw = """
            {"state":{"languageCode":"en","nickname":"Ann","avatar":"u",
            "onboardingStatus":3,"relationshipSwitch":true,"nsfw":false},"version":0}
        """.trimIndent()

        val state = JSONObject(AccountLanguageWriter.merge(raw, "ja")).getJSONObject("state")

        assertEquals("ja", state.getString("languageCode"))
        assertEquals("Ann", state.getString("nickname"))
        assertEquals("u", state.getString("avatar"))
        assertEquals(3, state.getInt("onboardingStatus"))
        assertTrue(state.getBoolean("relationshipSwitch"))
        // nsfw 也在这个信封的兄弟信封里被读 —— 顺手改它会破坏单向镜像流
        assertFalse(state.getBoolean("nsfw"))
    }

    @Test
    fun `信封不存在时造一个最小信封而不是放弃写入`() {
        // ⚠️ 与 mergeGenderIntoEnvelope 刻意不同（那边缺信封 return null）。
        // 不造的后果：全新安装用户在 RN 初始化信封之前改语言永不生效 ——
        // 与 §2.23.1 性别筛选同一类静默失效。
        // 安全性已核实：user-storage 无 version / migrate（store/user.ts:286-289），
        // 不会触发 zustand 的 migrate 分支（middleware.js:389）
        for (raw in listOf(null, "", "   ", "not json", "[]")) {
            val merged = AccountLanguageWriter.merge(raw, "ko")
            assertEquals("造信封失败：raw=$raw", "ko", AccountLanguageReader.parse(merged))
        }
    }

    @Test
    fun `造出的信封 version 是 zustand 默认的 0`() {
        // 写错 version 会让 rehydrate 走 migrate 分支；user-storage 没配
        // migrate，zustand 会 console.error 并**丢掉整个持久化状态**
        val envelope = JSONObject(AccountLanguageWriter.merge(null, "ko"))
        assertEquals(0, envelope.getInt("version"))
    }

    @Test
    fun `已有信封的 version 原样保留而不是强写 0`() {
        // 若将来 RN 给这个 store 加了 version，把它覆盖成 0 会**反向**触发
        // migrate —— 那正是我们要避开的分支
        val merged = AccountLanguageWriter.merge("""{"state":{},"version":7}""", "ko")
        assertEquals(7, JSONObject(merged).getInt("version"))
    }

    @Test
    fun `信封有 state 但缺 languageCode 字段时补上`() {
        // 账号此前没有语言意见（AccountLanguageReader 对这种情况返回 null）
        val raw = """{"state":{"nickname":"Ann"},"version":0}"""
        val state = JSONObject(AccountLanguageWriter.merge(raw, "de")).getJSONObject("state")
        assertEquals("de", state.getString("languageCode"))
        assertEquals("Ann", state.getString("nickname"))
    }

    @Test
    fun `state 层缺失或类型不对时重建它`() {
        // 没有 state 层的信封 rehydrate 直接拿不到东西，等于白写
        for (raw in listOf("""{"version":0}""", """{"state":"oops","version":0}""")) {
            val merged = AccountLanguageWriter.merge(raw, "fr")
            assertEquals("state 未重建：raw=$raw", "fr", AccountLanguageReader.parse(merged))
        }
    }

    @Test
    fun `写的是 camelCase 字段名而不是接口的 snake_case`() {
        // 写成 language_code 会让读取口永远读不到（同 Reader 那条测试的镜像面）
        val state = JSONObject(AccountLanguageWriter.merge(null, "ja")).getJSONObject("state")
        assertTrue(state.has("languageCode"))
        assertFalse(state.has("language_code"))
    }

    @Test
    fun `倒灌场景的完整往返`() {
        // 复现 §2.37 的缺陷链，断言修复后不再回退：
        // 信封里是旧语言 en → 语言页选 zh-tw 并回写 → Surface 出栈后
        // refreshAccountLanguage() 再读 → 应读到 zh-tw 而不是 en
        val before = """{"state":{"languageCode":"en"},"version":0}"""
        assertEquals("en", AccountLanguageReader.parse(before))

        val after = AccountLanguageWriter.merge(before, "zh-tw")

        // 这一步就是 refreshAccountLanguage() 会做的事
        assertEquals("zh-tw", AccountLanguageReader.parse(after))
    }

    @Test
    fun `空语言码不会被写成有效值`() {
        // 防御：调用方不该传空串，但若传了，读取口必须仍视为「无意见」
        // 而不是一个叫 "" 的语言码（normalize 后会静默变 en）
        assertNull(AccountLanguageReader.parse(AccountLanguageWriter.merge(null, "")))
    }
}
