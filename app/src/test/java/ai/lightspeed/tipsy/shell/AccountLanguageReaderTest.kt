package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.i18n.AccountLanguageReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 从 RN `user-storage` 信封读账号语言（W1-P5）。
 *
 * 语言真值在后端，本地 `user-storage.state.languageCode` 是镜像
 * （`useChangeLanguage.ts:57-72` + `store/user.ts:187`）。
 */
class AccountLanguageReaderTest {

    @Test
    fun `从 Zustand persist 信封读出语言码`() {
        val raw = """{"state":{"languageCode":"ja","nickname":"x"},"version":0}"""
        assertEquals("ja", AccountLanguageReader.parse(raw))
    }

    @Test
    fun `字段名是 camelCase 而不是接口的 snake_case`() {
        // 信封里存的是 JS store 字段名（languageCode），
        // 不是接口返回的 language_code —— 用错会永远读不到
        val wrong = """{"state":{"language_code":"ja"},"version":0}"""
        assertNull(AccountLanguageReader.parse(wrong))
    }

    @Test
    fun `账号无语言意见时返回 null 而不是 en`() {
        // 返回 null 表示「不覆盖」，调用方保留设备默认。
        // 若这里返回 "en"，会把设备是日语的新用户强行改成英文
        assertNull(AccountLanguageReader.parse("""{"state":{"languageCode":null}}"""))
        assertNull(AccountLanguageReader.parse("""{"state":{}}"""))
        assertNull(AccountLanguageReader.parse("""{"state":{"languageCode":""}}"""))
    }

    @Test
    fun `JSON null 不会被当成叫 null 的语言码`() {
        // optString 对 JSON null 返回字面量 "null"，normalize 后静默变 en ——
        // 与 LegacyTokenReader 踩过的同一个坑
        assertNull(AccountLanguageReader.parse("""{"state":{"languageCode":null}}"""))
    }

    @Test
    fun `无 state 层或非 JSON 一律 null`() {
        assertNull(AccountLanguageReader.parse("""{"languageCode":"ja"}"""))
        assertNull(AccountLanguageReader.parse("not json"))
        assertNull(AccountLanguageReader.parse(""))
        assertNull(AccountLanguageReader.parse(null))
    }

    @Test
    fun `key 常量与 RN 侧 persist name 一致`() {
        assertEquals("user-storage", AccountLanguageReader.USER_STORAGE_KEY)
    }
}
