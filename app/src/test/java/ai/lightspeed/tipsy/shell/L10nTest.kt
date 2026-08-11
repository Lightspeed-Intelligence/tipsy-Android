package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.i18n.L10n
import ai.lightspeed.tipsy.shell.i18n.LocaleTable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [L10n] 的查表、fallback 链与广播收口（W1-P5，方案 §4.8）。
 */
class L10nTest {

    private val notified = mutableListOf<String>()
    private val logs = mutableListOf<String>()

    /** 表内容：只有 en 有 `Currently unavailable`，模拟真实的 key≠value 词条。 */
    private val tables = mutableMapOf(
        "en" to """{"Cancel":"Cancel","Currently unavailable":"More to come","Hi {{name}}":"Hi {{name}}"}""",
        "ja" to """{"Cancel":"キャンセル"}""",
        "zh-tw" to """{"Cancel":"取消"}""",
    )

    private val loader = L10n.TableLoader { code ->
        tables[code]?.let { LocaleTable.parse(it) }
    }

    @Before
    fun setUp() {
        L10n.resetForTest()
        notified.clear()
        logs.clear()
    }

    @After
    fun tearDown() {
        // 全局单例：不清理会让测试间互相影响（顺序相关的假绿/假红）
        L10n.resetForTest()
    }

    private fun bootstrap(initial: String = "en") {
        L10n.bootstrap(
            loader = loader,
            initialLanguage = initial,
            listener = { notified += it },
            logger = { logs += it },
        )
    }

    // ── fallback 链：当前语言 → en → key ───────────────────────

    @Test
    fun `命中当前语言的译文`() {
        bootstrap()
        L10n.setLanguage("ja")
        assertEquals("キャンセル", L10n.t("Cancel"))
    }

    @Test
    fun `当前语言缺该词条时回退英文`() {
        bootstrap()
        L10n.setLanguage("ja")
        // ja 表里没有这条 → 回退 en 的值（**不是** key）
        assertEquals("More to come", L10n.t("Currently unavailable"))
    }

    @Test
    fun `英文也缺时才返回 key`() {
        bootstrap()
        assertEquals("Never Exported", L10n.t("Never Exported"))
    }

    @Test
    fun `en 必须走查表 —— 不能拿 key 当英文文案`() {
        bootstrap()
        // 实测 en.json 里 1838 个 key 有 94 个 key≠value。
        // 若实现改成「en 直接返回 key」，这条会红 —— 那批词条会显示错文案，
        // 且因为「看起来像正常英文」而不会被发现
        assertEquals("More to come", L10n.t("Currently unavailable"))
    }

    // ── 插值 ─────────────────────────────────────────────────

    @Test
    fun `插值替换 RN 的双花括号语法`() {
        bootstrap()
        assertEquals("Hi Ada", L10n.t("Hi {{name}}", mapOf("name" to "Ada")))
    }

    @Test
    fun `插值参数缺失时保留占位符而不是崩`() {
        bootstrap()
        // 宁可显示 `Hi {{name}}` 也不要抛 —— 文案缺参数不该让页面崩
        assertEquals("Hi {{name}}", L10n.t("Hi {{name}}", emptyMap()))
    }

    // ── 语言切换与广播 ────────────────────────────────────────

    @Test
    fun `切换语言更新 current 与 flow`() {
        bootstrap()
        L10n.setLanguage("ja")
        assertEquals("ja", L10n.current)
        assertEquals("ja", L10n.languageFlow.value)
    }

    @Test
    fun `setLanguage 会 normalize 输入`() {
        bootstrap()
        L10n.setLanguage("ZH_TW")
        assertEquals("zh-tw", L10n.current)
    }

    @Test
    fun `切换语言广播一次`() {
        bootstrap()
        L10n.setLanguage("ja")
        assertEquals(listOf("ja"), notified)
    }

    @Test
    fun `重复设置同一语言不重复广播`() {
        bootstrap()
        L10n.setLanguage("ja")
        L10n.setLanguage("ja")
        L10n.setLanguage("JA")
        // 重复广播会让 RN 侧 i18next.changeLanguage 被无谓调用、
        // Compose 订阅方无谓重组
        assertEquals(listOf("ja"), notified)
    }

    @Test
    fun `bootstrap 不广播 —— 那时还没有 Surface 在听`() {
        bootstrap(initial = "ja")
        assertEquals("ja", L10n.current)
        assertTrue("启动阶段不该发桥事件", notified.isEmpty())
    }

    // ── 缺资源的安全兜底 ──────────────────────────────────────

    @Test
    fun `无资源的语言仍然切换 current 但查表回退英文`() {
        bootstrap()
        // fil 在 SUPPORTED 里但本测试没给表
        L10n.setLanguage("fil")
        // 方案 §4.8：未知/无资源 code 安全 fallback。
        // current 仍是 fil —— 它要作为 language_code 请求参数发给后端，
        // 不能因为壳没有词条就篡改成 en
        assertEquals("fil", L10n.current)
        assertEquals("Cancel", L10n.t("Cancel"))
    }

    @Test
    fun `缺资源要留诊断日志`() {
        bootstrap()
        L10n.setLanguage("fil")
        // 没有日志的话，「选了语言但还是英文」无从判断是漏导出还是解析失败
        assertTrue(
            "应记录缺表诊断，实际日志：$logs",
            logs.any { it.contains("fil") },
        )
    }

    @Test
    fun `英文表本身缺失时记录 fallback 链退化`() {
        tables.remove("en")
        bootstrap()
        assertTrue(
            "英文表缺失必须可见，实际日志：$logs",
            logs.any { it.contains("英文") },
        )
        // 仍可运行，只是全部回落到 key
        assertEquals("Cancel", L10n.t("Cancel"))
    }

    // ── 表解析的宽松性 ────────────────────────────────────────

    @Test
    fun `坏值只丢那一条 不毁整张表`() {
        // 导出脚本保证扁平 String→String，但整表 cast 失败会让该语言
        // **全量**静默回退英文 —— 那是「一个坏词条毁掉整个语言」
        val table = LocaleTable.parse(
            """{"good":"ok","nested":{"a":1},"nulled":null,"empty":""}"""
        )
        assertEquals("ok", table["good"])
        assertEquals(null, table["nulled"])
        assertEquals(null, table["empty"])
    }

    @Test
    fun `JSON null 不会变成字面量 null 字符串`() {
        // optString 对 JSON null 返回 "null"，不特判就会把它当成合法译文
        val table = LocaleTable.parse("""{"k":null}""")
        assertEquals(null, table["k"])
    }

    @Test
    fun `非法 JSON 返回空表而不是抛`() {
        assertEquals(0, LocaleTable.parse("not json").size)
    }
}
