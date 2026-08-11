package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.surface.SurfaceContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Surface 契约测试（W1 §12.1 / §12.4）。
 *
 * ⚠️ **不测 `buildInitialProps`**：`android.os.Bundle` 在 JVM 单测里是抛异常的
 * stub（与 `Base64` / `Log` / `Uri` 同类）。要测它得引 Robolectric，
 * 而用 `returnDefaultValues = true` 绕是方案 §5.4 点名的假绿色。
 *
 * 所以这里覆盖的是**不依赖 Android 类型**的部分：instanceId 唯一性、
 * capability 集合、版本常量。initial props 的实际内容由 §9.1 的设备验收覆盖
 * （那本来就要在真机上看 JS 收到了什么）。
 */
class SurfaceContractTest {

    // ── §12.1 instanceId ──────────────────────────────────────

    /**
     * **每次必须不同。** 复用会让「迟到的旧实例事件关掉新实例」——
     * iOS 的 popSurface 闸是类型判定，正因如此弹错过同类型页。
     */
    @Test
    fun `instanceId 每次都不同`() {
        val ids = (1..1000).map { SurfaceContract.newInstanceId() }
        assertEquals("1000 次生成不得有重复", 1000, ids.toSet().size)
    }

    @Test
    fun `instanceId 非空且是 UUID 形态`() {
        val id = SurfaceContract.newInstanceId()
        assertTrue(id.isNotBlank())
        assertTrue(
            "应为 UUID 形态（36 字符含 4 个连字符）",
            id.length == 36 && id.count { it == '-' } == 4,
        )
    }

    // ── §12.4 capability ──────────────────────────────────────

    @Test
    fun `capability 集合非空且无重复`() {
        val caps = SurfaceContract.CAPABILITIES
        assertTrue(caps.isNotEmpty())
        assertEquals("不得有重复标识", caps.size, caps.toSet().size)
    }

    /**
     * 命名规则 `<域>.<能力>.v<n>`。带版本后缀是为了**能力语义变更时发新标识**
     * 而不是改旧的 —— 旧 JS 查 `.v1` 时若语义已变，它不知情。
     */
    @Test
    fun `capability 标识都带版本后缀`() {
        SurfaceContract.CAPABILITIES.forEach { cap ->
            assertTrue(
                "capability 必须带 .v<n> 后缀，否则语义变更时旧 JS 无法区分：$cap",
                Regex("""^[a-z]+(\.[a-z-]+)+\.v\d+$""").matches(cap),
            )
        }
    }

    @Test
    fun `已声明的三项能力都在集合里`() {
        listOf(
            SurfaceContract.CAP_AUTH_VALID_TOKEN,
            SurfaceContract.CAP_LIFECYCLE_REAPPEARED,
            SurfaceContract.CAP_NAVIGATION_OPEN_CHAT,
        ).forEach {
            assertTrue("常量与集合必须同步，加能力时别只加常量：$it", it in SurfaceContract.CAPABILITIES)
        }
    }

    // ── 契约版本 ──────────────────────────────────────────────

    /**
     * 版本号变更是**破坏性信号**：改语义/变必填/删字段才升，加字段不升。
     * 这条断言不是防止升级，而是让升级**必须显式改测试** —— 迫使人想一遍
     * 「是否同时升了 OTA runtime generation」。
     */
    @Test
    fun `契约版本为 1 升级时必须同步升 OTA runtime generation`() {
        assertEquals(
            "改这个值意味着破坏性变更 —— 必须同时升 OTA runtime generation（§12.4）",
            1,
            SurfaceContract.CONTRACT_VERSION,
        )
    }

    // ── token 不得进 props（靠 key 集合守）───────────────────

    /**
     * ⚠️ **token 绝不经 initial props**（§12.4）。
     *
     * initial props 会进 `Bundle`，可能落入 saved instance state、ANR trace、
     * 崩溃日志。这里断言 key 常量里没有任何 token 相关名字 ——
     * 挡不住有人硬编码字符串，但能挡住「顺手加个 KEY_TOKEN」。
     */
    @Test
    fun `props 的 key 里不含 token 相关字样`() {
        val keys = listOf(
            SurfaceContract.KEY_CONTRACT_VERSION,
            SurfaceContract.KEY_INSTANCE_ID,
            SurfaceContract.KEY_COMPONENT_NAME,
            SurfaceContract.KEY_CAPABILITIES,
            SurfaceContract.KEY_ROUTE,
            SurfaceContract.KEY_CONTEXT,
            SurfaceContract.CONTEXT_KEY_LANGUAGE,
            SurfaceContract.CONTEXT_KEY_ENVIRONMENT,
            SurfaceContract.CONTEXT_KEY_DISTRIBUTION,
        )
        keys.forEach { key ->
            assertFalse(
                "token 绝不经 initial props（§12.4）：$key",
                key.contains("token", ignoreCase = true) ||
                    key.contains("auth", ignoreCase = true) ||
                    key.contains("jwt", ignoreCase = true),
            )
        }
    }
}
