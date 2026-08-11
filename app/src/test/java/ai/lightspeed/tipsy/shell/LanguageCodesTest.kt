package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.i18n.LanguageCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语言码规范化（W1-P5）。逐行对齐 RN `src/i18n/i18n-index.ts`。
 *
 * **这个类存在的主要理由**：RN 侧有**两条**规则（`normalizeLanguageCode` 与
 * `defaultLanguage`），对简体 `zh` 给不同答案。方案 §4.8 只记了一条 ——
 * 若有人「统一」成一条，简体设备用户会看到繁体，而这在英文环境测试里
 * 完全看不出来。下面有专门的对照测试钉死这个差异。
 */
class LanguageCodesTest {

    // ── 集合规模（方案 §4.8 的四套集合不可混用）─────────────────

    @Test
    fun `supported 集合是 26 个 —— 不是磁盘上的 28 也不是 import 的 27`() {
        assertEquals(26, LanguageCodes.SUPPORTED.size)
    }

    @Test
    fun `zh 与 ar 都不在 supported 里`() {
        // zh.json 被 i18n-index import 了但**不在** SUPPORTED_LANGUAGES；
        // ar.json 连 import 都没有。把任一个加进来都会让它成为
        // 「产品可选语言」，而方案 §4.8 明确禁止自动提升休眠语言
        assertFalse("zh" in LanguageCodes.SUPPORTED)
        assertFalse("ar" in LanguageCodes.SUPPORTED)
    }

    @Test
    fun `繁中与巴葡用带连字符的码`() {
        assertTrue("zh-tw" in LanguageCodes.SUPPORTED)
        assertTrue("pt-br" in LanguageCodes.SUPPORTED)
    }

    // ── normalize：精确匹配 ────────────────────────────────────

    @Test
    fun `精确匹配直接返回`() {
        assertEquals("en", LanguageCodes.normalize("en"))
        assertEquals("ja", LanguageCodes.normalize("ja"))
        assertEquals("zh-tw", LanguageCodes.normalize("zh-tw"))
    }

    @Test
    fun `大写与下划线都要归一`() {
        // RN: input.toLowerCase().replace(/_/g, '-')
        assertEquals("zh-tw", LanguageCodes.normalize("ZH_TW"))
        assertEquals("pt-br", LanguageCodes.normalize("pt_BR"))
        assertEquals("en", LanguageCodes.normalize("EN"))
    }

    // ── normalize：主语言码回退 ────────────────────────────────

    @Test
    fun `主语言码匹配 —— es-CR 归到 es`() {
        assertEquals("es", LanguageCodes.normalize("es-CR"))
        assertEquals("pt", LanguageCodes.normalize("pt-PT"))
        assertEquals("de", LanguageCodes.normalize("de-AT"))
    }

    // ── normalize：zh 系 ──────────────────────────────────────

    @Test
    fun `繁体变体全部归到 zh-tw`() {
        assertEquals("zh-tw", LanguageCodes.normalize("zh-Hant"))
        assertEquals("zh-tw", LanguageCodes.normalize("zh-HK"))
        assertEquals("zh-tw", LanguageCodes.normalize("zh-Hant-TW"))
    }

    @Test
    fun `normalize 把简体 zh 也归到 zh-tw —— 这是 RN 既有行为`() {
        // ⚠️ 看着像 bug，是照抄。`zh` 不在 SUPPORTED（只有 zh-tw），
        // 所以主码匹配落空后走 `primary == "zh"` 这一支。
        // **不要"修正"成 en** —— 那是 defaultLanguage 那条路径的规则，
        // 两者场景不同（账号语言 vs 设备 locale），见下方对照测试
        assertEquals("zh-tw", LanguageCodes.normalize("zh"))
        assertEquals("zh-tw", LanguageCodes.normalize("zh-Hans"))
        assertEquals("zh-tw", LanguageCodes.normalize("zh-CN"))
    }

    // ── normalize：兜底 ──────────────────────────────────────

    @Test
    fun `未知码与空值兜底 en`() {
        assertEquals("en", LanguageCodes.normalize("xx"))
        assertEquals("en", LanguageCodes.normalize("klingon"))
        assertEquals("en", LanguageCodes.normalize(null))
        assertEquals("en", LanguageCodes.normalize(""))
    }

    @Test
    fun `休眠语言 ar 兜底 en 而不是被启用`() {
        // ar.json 在磁盘上存在，但既未 import 也不在 supported。
        // 若有人把它加进 SUPPORTED，这条会红 —— 那正是提醒
        assertEquals("en", LanguageCodes.normalize("ar"))
    }

    // ── fromDeviceLocale：与 normalize 的差异 ──────────────────

    @Test
    fun `设备简体中文用英文 —— 与 normalize 的答案不同`() {
        // 这是本类最重要的一条：**同一输入，两条规则给不同答案**
        assertEquals("en", LanguageCodes.fromDeviceLocale("zh-Hans-CN"))
        assertEquals("zh-tw", LanguageCodes.normalize("zh-Hans-CN"))

        assertEquals("en", LanguageCodes.fromDeviceLocale("zh"))
        assertEquals("zh-tw", LanguageCodes.normalize("zh"))
    }

    @Test
    fun `设备繁体中文仍用 zh-tw`() {
        // defaultLanguage 的排除条件：zh-hant / zh-tw / zh-hk 三者之一即不算简体
        assertEquals("zh-tw", LanguageCodes.fromDeviceLocale("zh-Hant-TW"))
        assertEquals("zh-tw", LanguageCodes.fromDeviceLocale("zh-TW"))
        assertEquals("zh-tw", LanguageCodes.fromDeviceLocale("zh-HK"))
    }

    @Test
    fun `非中文设备两条规则一致`() {
        for (tag in listOf("ja-JP", "es-CR", "de", "xx")) {
            assertEquals(
                "两条规则对非中文输入应当一致：$tag",
                LanguageCodes.normalize(tag),
                LanguageCodes.fromDeviceLocale(tag),
            )
        }
    }

    @Test
    fun `设备 locale 空值兜底 en`() {
        assertEquals("en", LanguageCodes.fromDeviceLocale(null))
        assertEquals("en", LanguageCodes.fromDeviceLocale(""))
    }
}
