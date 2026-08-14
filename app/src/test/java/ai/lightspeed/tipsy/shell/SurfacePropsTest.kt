package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.router.AppRoute
import ai.lightspeed.tipsy.shell.surface.SurfaceContract
import ai.lightspeed.tipsy.shell.surface.SurfaceProps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * route → Surface 业务 props 的映射（W1-CLOSEOUT-2）。
 *
 * **这个类防的是跨仓形状漂移** —— 壳产出的 props 与 RN 组件要的 props 对不上时，
 * 两边都不报错：RN 侧 props 是 TS 类型，但 initial props 来自原生，**运行期不校验**。
 * 症状是「参数没生效」（点某个角色却进了上次会话），极难归因。
 *
 * 原实现把参数塞进嵌套 `route` Bundle，而 RN 侧 13 个 Surface **无一读 `props.route`**
 * （全仓零命中）—— 等于 `characterId` 恒为 `undefined`。这批断言让它不会再发生。
 *
 * `SurfaceProps` 返回纯 `Map` 而不是 `Bundle`，正是为了让这些断言可跑
 * （`Bundle` 在 JVM 单测里是抛异常的 stub，见 `SurfaceContractTest` 的说明）。
 */
class SurfacePropsTest {

    @Test
    fun `ChatDetail 的 characterId 平铺在顶层`() {
        val props = SurfaceProps.forRoute(AppRoute.ChatDetail("abc123"))
        assertEquals("abc123", props["characterId"])
        // 不该再出现嵌套层 —— RN 侧没有任何 Surface 读它
        assertNull(props["route"])
    }

    @Test
    fun `ChatDetail 无 characterId 时不放该 key`() {
        // RN 侧该深链参数可选（进去恢复上次会话）
        assertTrue(SurfaceProps.forRoute(AppRoute.ChatDetail(null)).isEmpty())
        assertTrue(SurfaceProps.forRoute(AppRoute.ChatDetail("")).isEmpty())
        assertTrue(SurfaceProps.forRoute(AppRoute.ChatDetail("   ")).isEmpty())
    }

    @Test
    fun `MiniPhone 与 ChatDetail 是同一 Surface 的不同初始屏`() {
        val props = SurfaceProps.forRoute(AppRoute.MiniPhoneChat("c1"))
        assertEquals("c1", props["characterId"])
        // 对齐 useChatNavigation.toChatPage 的 mini_phone 分支 —— 不传 initialScreen
        // 会落到默认的 ChatDetailPage，用户点小手机会话进到普通聊天页
        assertEquals("MiniPhoneChat", props["initialScreen"])
    }

    @Test
    fun `UserProfile 传 userId 但不传推荐归因`() {
        val props = SurfaceProps.forRoute(
            AppRoute.UserProfile(userId = "u1", recommendationContextJSON = """{"a":1}"""),
        )
        assertEquals("u1", props["userId"])
        // 归因的消费方是 W3 的原生他人主页，不经 Surface。
        // 多传一个 RN 侧不读的字段会让人误以为它有用
        assertNull(props["recommendationContextJSON"])
    }

    @Test
    fun `尚未启用的 route 不产出 props`() {
        for (route in listOf<AppRoute>(
            AppRoute.DailyGemEntry,
            AppRoute.UserBalance,
            AppRoute.Subscribe,
            AppRoute.Letter,
            AppRoute.CreateProfileDetail("x"),
            AppRoute.GemsPurchase(),
            AppRoute.Login(),
            AppRoute.Search,
        )) {
            assertTrue(
                "${route.javaClass.simpleName} 尚无 Surface 目标",
                SurfaceProps.forRoute(route).isEmpty(),
            )
        }
    }

    // ── 与 RN 侧必填 props 的对齐断言 ─────────────────────────

    /**
     * 本类最重要的一条：编码「`ChatDetailSurface` 的 `characterId` 是必填」这个
     * 跨仓事实（`ChatDetailSurface.tsx:75` 声明为 `characterId: string`，非可选）。
     * 有人改了映射让它不再产出该 key 时这里会红。
     */
    @Test
    fun `带 characterId 的 ChatDetail 深链必须产出必填 prop`() {
        val required = setOf("characterId")
        val actual = SurfaceProps.forRoute(AppRoute.ChatDetail("real-id")).keys
        assertTrue(
            "缺少 ChatDetailSurface 必填 props：${required - actual}",
            actual.containsAll(required),
        )
    }

    @Test
    fun `业务 props 不得使用壳自有字段名`() {
        for (route in listOf<AppRoute>(
            AppRoute.ChatDetail("c"),
            AppRoute.MiniPhoneChat("c"),
            AppRoute.UserProfile("u"),
        )) {
            val clash = SurfaceProps.forRoute(route).keys
                .intersect(SurfaceContract.SHELL_OWNED_KEYS)
            assertTrue(
                "${route.javaClass.simpleName} 的 props 与壳字段撞名：$clash",
                clash.isEmpty(),
            )
        }
    }

    @Test
    fun `props 里绝不含 token 字样`() {
        // initial props 会进 Bundle，可能落入 saved instance state / ANR trace / 崩溃日志
        for (route in listOf<AppRoute>(
            AppRoute.ChatDetail("c"),
            AppRoute.MiniPhoneChat("c"),
            AppRoute.UserProfile("u"),
        )) {
            val keys = SurfaceProps.forRoute(route).keys
            assertFalse(
                "props 含疑似凭据字段：$keys",
                keys.any { it.contains("token", true) || it.contains("jwt", true) },
            )
        }
    }

    // ── 撞名守卫本身 ──────────────────────────────────────────

    @Test
    fun `撞名时抛而不是静默覆盖`() {
        for (shellKey in SurfaceContract.SHELL_OWNED_KEYS) {
            try {
                SurfaceContract.assertNoShellKeyClash(setOf("characterId", shellKey))
                fail("与壳字段 `$shellKey` 撞名应当抛")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "异常消息应指出撞的是哪个 key，实际：${expected.message}",
                    expected.message?.contains(shellKey) == true,
                )
            }
        }
    }

    /**
     * `SettingsSubScreen` → 平铺的 `initialScreen`（§2.33）。
     *
     * ⚠️ 值必须落在 RN 的 `KNOWN_SCREENS` 里 —— 传别的值 RN 会**静默兜底
     * `Feedback`**（`normalizeScreen`），表现为「点安全设置进了反馈页」，
     * 两端都不报错。行级的白名单断言在 `SettingsRowTest`。
     */
    @Test
    fun `SettingsSubScreen 产出平铺 initialScreen`() {
        val props = SurfaceProps.forRoute(AppRoute.SettingsSubScreen("Security"))
        assertEquals(mapOf("initialScreen" to "Security"), props)
    }

    /** 列表本体是原生页，不经 Surface props（传了说明走错路径）。 */
    @Test
    fun `Settings 列表本体不产出 props`() {
        assertTrue(SurfaceProps.forRoute(AppRoute.Settings).isEmpty())
    }

    @Test
    fun `纯业务 key 不触发守卫`() {
        // 不该误报：这些都是 RN 侧真实 props 名
        SurfaceContract.assertNoShellKeyClash(
            setOf("characterId", "initialScreen", "targetType", "targetId", "tab", "userId"),
        )
    }
}
