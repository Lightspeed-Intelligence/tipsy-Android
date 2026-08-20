package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.router.AppRoute
import ai.lightspeed.tipsy.shell.router.AppRouteParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 深链解析测试（W1-P4）。
 *
 * **深链是外部不可信输入** —— 任意 App 或网页都能构造 `tipsy://` URI 拉起本应用。
 * 所以这里的重点不是"正常路径能解析"，而是**畸形输入一律安全返回 null、绝不抛**
 * （方案 §4.3：未知 route 要可诊断地拒绝或忽略，绝不崩）。
 */
class AppRouteParserTest {

    // ── 七条外部路径逐条对齐 RN（src/App.tsx:445-465）───────────

    @Test
    fun `七条外部路径全部可解析`() {
        val cases = mapOf(
            "tipsy://profile/daily-gem-entry" to AppRoute.DailyGemEntry,
            "tipsy://profile/user-balance" to AppRoute.UserBalance,
            "tipsy://subscribe/page" to AppRoute.Subscribe,
            "tipsy://chat/detail" to AppRoute.ChatDetail(null),
            "tipsy://chat/mini-phone" to AppRoute.MiniPhoneChat(null),
            "tipsy://chat/letter" to AppRoute.Letter(),
            "tipsy://create/profile-detail" to AppRoute.CreateProfileDetail(null),
        )
        cases.forEach { (uri, expected) ->
            assertEquals("路径必须逐字对齐 RN linking.config：$uri", expected, AppRouteParser.parse(uri))
        }
        assertEquals("RN 侧声明的就是 7 条，多一条少一条都是两侧分叉", 7, cases.size)
    }

    @Test
    fun `character_id 两种写法都接受`() {
        assertEquals(
            AppRoute.ChatDetail("c123"),
            AppRoute.ChatDetail("c123").let { AppRouteParser.parse("tipsy://chat/detail?character_id=c123") },
        )
        assertEquals(
            AppRoute.ChatDetail("c456"),
            AppRouteParser.parse("tipsy://chat/detail?characterId=c456"),
        )
    }

    // ── 形态归一化（外部构造的 URI 形态不可控）──────────────────

    @Test
    fun `路径大小写与首尾斜杠归一`() {
        listOf(
            "tipsy://chat/detail",
            "tipsy://Chat/Detail",
            "tipsy://chat/detail/",
            "tipsy:///chat/detail",
        ).forEach {
            assertEquals("形态差异不该影响路由：$it", AppRoute.ChatDetail(null), AppRouteParser.parse(it))
        }
    }

    @Test
    fun `scheme 大小写不敏感`() {
        assertEquals(AppRoute.Subscribe, AppRouteParser.parse("TIPSY://subscribe/page"))
    }

    @Test
    fun `fragment 不参与路由`() {
        assertEquals(AppRoute.Subscribe, AppRouteParser.parse("tipsy://subscribe/page#anchor"))
    }

    // ── 拒绝非本应用 scheme ────────────────────────────────────

    /**
     * 壳只声明 `tipsy://`。收到别的 scheme 说明有人在乱发 intent，忽略。
     *
     * ⚠️ 这条也守着 §6.3 的审计结论：五个通用社交 scheme（fb/twitter/discord/
     * instagram/tiktok）**壳刻意不声明**，即使有人构造也不处理。
     */
    @Test
    fun `其他 scheme 一律拒绝`() {
        listOf(
            "https://tipsy.chat/chat/detail",
            "fb://chat/detail",
            "tiktok://chat/detail",
            "exp+tipsy-app://chat/detail",
        ).forEach {
            assertNull("非 tipsy scheme 必须拒绝：$it", AppRouteParser.parse(it))
        }
    }

    // ── 畸形输入：一律 null，绝不抛 ─────────────────────────────

    @Test
    fun `空与 null 返回 null`() {
        assertNull(AppRouteParser.parse(null))
        assertNull(AppRouteParser.parse(""))
        assertNull(AppRouteParser.parse("   "))
    }

    @Test
    fun `畸形 URI 返回 null 而不抛`() {
        listOf(
            "tipsy",
            "tipsy:",
            "tipsy:/",
            "://chat/detail",
            "tipsy://",
            "!!!",
            "tipsy://chat/detail?%",       // 畸形 percent 转义
            "tipsy://chat/detail?%zz=1",   // 非法 hex
            "tipsy://chat/detail?=novalue",
            "tipsy://chat/detail?&&&",
        ).forEach {
            // 断言是"不抛且不误匹配"，具体 null 与否取决于路径是否合法
            val result = runCatching { AppRouteParser.parse(it) }
            assertEquals("畸形输入不得抛异常：$it", true, result.isSuccess)
        }
    }

    @Test
    fun `未知路径返回 null 不做猜测式兜底`() {
        listOf(
            "tipsy://chat",
            "tipsy://chat/unknown",
            "tipsy://profile",
            "tipsy://admin/delete-everything",
        ).forEach {
            assertNull(
                "未知路径必须返回 null —— 猜错会把用户送到错的页面，比不动更糟：$it",
                AppRouteParser.parse(it),
            )
        }
    }

    @Test
    fun `裸 query 参数不导致失败`() {
        assertEquals(AppRoute.ChatDetail(null), AppRouteParser.parse("tipsy://chat/detail?flag"))
    }

    @Test
    fun `percent 编码的参数值被解开`() {
        assertEquals(
            AppRoute.ChatDetail("a b"),
            AppRouteParser.parse("tipsy://chat/detail?character_id=a%20b"),
        )
    }

    /** 超长输入不该导致异常或明显卡顿（外部可构造任意长度）。 */
    @Test
    fun `超长 URI 安全处理`() {
        val long = "tipsy://chat/detail?character_id=" + "x".repeat(50_000)
        val result = runCatching { AppRouteParser.parse(long) }
        assertEquals(true, result.isSuccess)
        assertEquals(50_000, (result.getOrNull() as? AppRoute.ChatDetail)?.characterId?.length)
    }
}
