package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.settings.SettingsAction
import ai.lightspeed.tipsy.shell.pages.settings.SettingsRow
import ai.lightspeed.tipsy.shell.pages.settings.SupportedLanguage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings 列表的**渠道 gating** —— 本文件是这一刀最该有单测的地方。
 *
 * RN 侧有 9 处 `!isGooglePlay` 加两个独立条件，散在 430 行 JSX 里。
 * 漏掉任何一处的表现是「**GooglePlay 版多出一行不该有的入口**」——
 * 那是会被商店审核抓的合规问题，且本地跑 directApk 完全看不出来
 * （那个渠道所有行都显示）。
 */
class SettingsRowTest {

    // ── 三渠道各断言一遍 ────────────────────────────

    @Test
    fun `GooglePlay 隐藏五行`() {
        val rows = SettingsRow.visibleRows(
            isGooglePlay = true,
            isDirectApk = false,
            accountSecurityExpanded = true,
        )
        // 逐行对齐 page.tsx 的 !isGooglePlay 门控
        assertFalse(SettingsRow.SUBSCRIPTION in rows)
        assertFalse(SettingsRow.SECURITY in rows)
        assertFalse(SettingsRow.COMMUNITY_GUIDELINES in rows)
        assertFalse(SettingsRow.TERMS_OF_SERVICE in rows)
        assertFalse(SettingsRow.OFFICIAL_WEBSITE in rows)
        // 分级开关也不该有（那是 isAndroidAPK 条件）
        assertFalse(SettingsRow.LIMITLESS in rows)
    }

    @Test
    fun `GooglePlay 保留的行`() {
        val rows = SettingsRow.visibleRows(
            isGooglePlay = true,
            isDirectApk = false,
            accountSecurityExpanded = true,
        )
        assertTrue(SettingsRow.LANGUAGE in rows)
        assertTrue(SettingsRow.ACCOUNT_SECURITY in rows)
        assertTrue(SettingsRow.BLOCKED in rows)
        assertTrue(SettingsRow.DELETE_ACCOUNT in rows)
        assertTrue(SettingsRow.FEEDBACK in rows)
        assertTrue(SettingsRow.ABOUT in rows)
        assertTrue(SettingsRow.CONTACT_US in rows)
    }

    @Test
    fun `directApk 显示全部行`() {
        val rows = SettingsRow.visibleRows(
            isGooglePlay = false,
            isDirectApk = true,
            accountSecurityExpanded = true,
        )
        assertEquals(SettingsRow.ALL.size, rows.size)
    }

    /**
     * ⚠️ 本条锁死那个最容易写错的判定：`isAndroidAPK` 是**一个**渠道，
     * 不是「所有 Android」。RuStore 既不是 GooglePlay 也不是 APK ——
     * 它**不该**显示分级开关。
     *
     * 写成「Android 就显示」会让 RuStore 版出现一个不该有的成人内容开关。
     */
    @Test
    fun `RuStore 不显示分级开关但保留非 GooglePlay 的行`() {
        val rows = SettingsRow.visibleRows(
            isGooglePlay = false,
            isDirectApk = false,
            accountSecurityExpanded = true,
        )
        assertFalse(
            "RuStore 不满足 isAndroidAPK，不该有 Limitless",
            SettingsRow.LIMITLESS in rows,
        )
        // 非 GooglePlay 的那五行仍在
        assertTrue(SettingsRow.SUBSCRIPTION in rows)
        assertTrue(SettingsRow.SECURITY in rows)
        assertTrue(SettingsRow.OFFICIAL_WEBSITE in rows)
    }

    // ── 展开态 ──────────────────────────────────────

    @Test
    fun `折叠时不渲染三个账号安全子项`() {
        val rows = SettingsRow.visibleRows(
            isGooglePlay = false,
            isDirectApk = true,
            accountSecurityExpanded = false,
        )
        // RN 是 {accountSecurityExpanded && ...}，不是渲染成禁用态
        assertFalse(SettingsRow.SECURITY in rows)
        assertFalse(SettingsRow.BLOCKED in rows)
        assertFalse(SettingsRow.DELETE_ACCOUNT in rows)
        assertTrue("父行本身要在", SettingsRow.ACCOUNT_SECURITY in rows)
    }

    @Test
    fun `展开后子项紧跟在父行之后`() {
        val rows = SettingsRow.visibleRows(
            isGooglePlay = false,
            isDirectApk = true,
            accountSecurityExpanded = true,
        )
        val parent = rows.indexOf(SettingsRow.ACCOUNT_SECURITY)
        assertEquals(parent + 1, rows.indexOf(SettingsRow.SECURITY))
        assertEquals(parent + 2, rows.indexOf(SettingsRow.BLOCKED))
        assertEquals(parent + 3, rows.indexOf(SettingsRow.DELETE_ACCOUNT))
    }

    // ── 行序与目标 ──────────────────────────────────

    @Test
    fun `语言在第一行`() {
        // 现网行序，不能调（用户肌肉记忆 + 自动化脚本按序断言）
        val rows = SettingsRow.visibleRows(
            isGooglePlay = false,
            isDirectApk = true,
            accountSecurityExpanded = false,
        )
        assertEquals(SettingsRow.LANGUAGE, rows.first())
    }

    /**
     * 子屏名必须落在 RN 的 `KNOWN_SCREENS` 里。
     *
     * 传白名单外的值 RN 会**静默兜底到 `Feedback`**（`normalizeScreen`），
     * 表现为「点安全设置进了反馈页」—— 两端都不报错。
     */
    @Test
    fun `所有 SurfaceScreen 目标都在 RN 白名单内`() {
        val known = setOf(
            "Security", "Blacklist", "Feedback", "About", "ContactUs", "Delete", "Widget",
        )
        val targets = SettingsRow.ALL
            .map { it.action }
            .filterIsInstance<SettingsAction.SurfaceScreen>()
            .map { it.screen }
        assertTrue("至少要有几个子屏出口", targets.isNotEmpty())
        targets.forEach {
            assertTrue("『$it』不在 SettingsSurface 的 KNOWN_SCREENS 里", it in known)
        }
    }

    /** ⚠️ `Language` 刻意**不是** Surface 子屏（语言页原生，§2.33）。 */
    @Test
    fun `语言页不是 Surface 子屏`() {
        assertTrue(
            "语言页必须是原生（KNOWN_SCREENS 刻意不含 Language）",
            SettingsRow.LANGUAGE.action is SettingsAction.OpenLanguage,
        )
    }

    /** 黑名单那行的**文案 key 是 `Blocked`**，屏名才是 `Blacklist`。 */
    @Test
    fun `Blocked 行的文案与屏名不同轴`() {
        assertEquals("Blocked", SettingsRow.BLOCKED.titleKey)
        assertEquals(
            "Blacklist",
            (SettingsRow.BLOCKED.action as SettingsAction.SurfaceScreen).screen,
        )
    }

    /** 三个外部链接逐字对齐 `page.tsx:292,303,313`。 */
    @Test
    fun `外部链接 URL 逐字对齐 RN`() {
        fun urlOf(row: SettingsRow) = (row.action as SettingsAction.OpenUrl).url
        assertEquals(
            "https://tipsy.chat/community-guidelines",
            urlOf(SettingsRow.COMMUNITY_GUIDELINES),
        )
        assertEquals("https://tipsy.chat/terms-of-service", urlOf(SettingsRow.TERMS_OF_SERVICE))
        assertEquals("https://tipsy.chat/", urlOf(SettingsRow.OFFICIAL_WEBSITE))
    }

    /** 展开行是**本地状态**不是导航 —— 写成路由会打开一个不存在的页面。 */
    @Test
    fun `账号与安全是展开而不是导航`() {
        assertTrue(
            SettingsRow.ACCOUNT_SECURITY.action is SettingsAction.ToggleAccountSecurity,
        )
    }
}

/**
 * `SupportedLanguage` 的解析（`/supported_languages` 的元素）。
 *
 * 重点：**`display` 才是上屏文案**（该语言自己的写法，如 `日本語`），
 * 用 `language` 字段会显示英文名，与现网不一致。
 */
class SupportedLanguageParserTest {

    @Test
    fun `解析 language_code 与 display`() {
        val item = SupportedLanguage.parse(
            JSONObject()
                .put("language_code", "ja")
                .put("language", "Japanese")
                .put("display", "日本語"),
        )
        assertEquals("ja", item?.languageCode)
        // ⚠️ 不是 "Japanese"
        assertEquals("日本語", item?.display)
    }

    @Test
    fun `language_code 缺失丢弃`() {
        assertNull(SupportedLanguage.parse(JSONObject().put("display", "日本語")))
    }

    /**
     * `display` 缺失也丢弃 —— 没有文案的行是一条空白可点区域，
     * 点了会把账号语言改成用户看不见的值，比少一行危险。
     * **不回落 `language` 字段**（那是英文名）。
     */
    @Test
    fun `display 缺失丢弃且不回落 language`() {
        assertNull(
            SupportedLanguage.parse(
                JSONObject().put("language_code", "ja").put("language", "Japanese"),
            ),
        )
    }

    @Test
    fun `空白字段视为缺失`() {
        assertNull(
            SupportedLanguage.parse(
                JSONObject().put("language_code", "  ").put("display", "日本語"),
            ),
        )
    }
}
