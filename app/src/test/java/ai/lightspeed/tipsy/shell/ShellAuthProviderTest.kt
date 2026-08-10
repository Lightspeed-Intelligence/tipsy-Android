package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.auth.AuthStateHub
import ai.lightspeed.tipsy.shell.auth.Generations
import ai.lightspeed.tipsy.shell.auth.ShellTokenStore
import ai.lightspeed.tipsy.shell.bridge.ShellAuthProvider
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ShellAuthProvider] 的 auth 行为测试（W1-P1）。
 *
 * 重点是**401 归属判定**与**登出的完整语义** —— 这两处写错都会产生
 * 「用户莫名被踢下线」，而且日志里看不出原因。
 */
class ShellAuthProviderTest {

    private val now = 1_700_000_000L

    // ── 401 归属判定（W1 计划 §3.2，最高危）─────────────────────

    /**
     * **本文件最重要的一条。** 旧账号迟到的 401 不得登出新账号。
     *
     * 真实场景：用户在 A 账号有个慢请求在飞 → 登出 → 登录 B 账号 →
     * A 的请求返回 401 → 若无归属校验，B 账号被踢下线。
     * 用户看到的是"刚登录就被登出"，且完全无法复现。
     */
    @Test
    fun `旧账号迟到的 401 不得登出新账号`() = runTest {
        val oldToken = tokenWithExp(now + 3600, sub = "old-user")
        val newToken = tokenWithExp(now + 3600, sub = "new-user")
        val fixture = fixture(persisted = newToken)

        fixture.provider.notifyServerAuthRejectedForToken(oldToken)

        assertEquals(
            "被拒 token 已非当前 token → 必须忽略，不能登出",
            newToken,
            fixture.persistence.stored,
        )
        assertEquals("不得广播登出", 0, fixture.logoutCount)
        assertTrue(
            "忽略要**可诊断** —— 静默忽略会让排查时无从判断桥有没有收到 401",
            fixture.logs.any { it.contains("忽略过期会话") },
        )
    }

    /**
     * **token 绝不进日志**（方案 §4.4 / W1 计划 §3.1）。
     *
     * 401 处理是最容易漏的地方：手边正好有个 token 变量，顺手打进日志排查用，
     * 然后它就随崩溃日志、Sentry breadcrumb 一起离开设备了。
     */
    @Test
    fun `任何日志都不得包含 token`() = runTest {
        val current = tokenWithExp(now + 3600)
        val other = tokenWithExp(now + 7200, sub = "other")
        val fixture = fixture(persisted = current)

        fixture.provider.notifyServerAuthRejectedForToken(other)   // 走"忽略"分支
        fixture.provider.notifyServerAuthRejectedForToken(current)  // 走"登出"分支
        fixture.provider.notifyServerAuthRejected()                 // 走无参分支

        fixture.logs.forEach { line ->
            assertTrue("日志泄漏了 token：$line", !line.contains(current))
            assertTrue("日志泄漏了 token：$line", !line.contains(other))
        }
    }

    @Test
    fun `当前 token 被拒时登出`() = runTest {
        val current = tokenWithExp(now + 3600)
        val fixture = fixture(persisted = current)

        fixture.provider.notifyServerAuthRejectedForToken(current)

        assertNull("当前会话被服务端拒绝 → 必须清 token", fixture.persistence.stored)
        assertEquals("必须广播一次登出", 1, fixture.logoutCount)
    }

    // ── logout 完整语义（§3.5）─────────────────────────────────

    /**
     * 登出必须做四件事：清 token、收敛返回栈、广播一次 loggedOut、失效 generation。
     * 少任何一件都有对应的真实症状 —— 见各断言的消息。
     */
    @Test
    fun `logout 清 token 收栈 并广播一次`() = runTest {
        val fixture = fixture(persisted = tokenWithExp(now + 3600))
        val genBefore = fixture.generations.auth

        fixture.provider.logout()

        assertNull("不清 token → 下次启动直接进旧账号", fixture.persistence.stored)
        assertEquals("不收栈 → 登出后仍停在需要登录的页面上", 1, fixture.popSurfaceCount)
        assertEquals("必须**恰好**一次 —— 多次会让每个常驻页重复清理", 1, fixture.logoutCount)
        assertTrue("不失效 generation → 在飞响应会把旧账号数据写回", fixture.generations.auth > genBefore)
    }

    /**
     * `clearToken` 与 `logout` **刻意不同**：前者不收栈、不广播。
     * 调用方（如 DeleteAccountSurface）自己控制后续导航。
     * 若把两者实现成一样，删号流程会在中途被强行弹栈。
     */
    @Test
    fun `clearToken 不收栈不广播`() = runTest {
        val fixture = fixture(persisted = tokenWithExp(now + 3600))

        fixture.provider.clearToken()

        assertNull("token 仍要清", fixture.persistence.stored)
        assertEquals("clearToken 不该收栈", 0, fixture.popSurfaceCount)
    }

    // ── getValidToken ────────────────────────────────────────

    @Test
    fun `getValidToken 委派 token store`() = runTest {
        val token = tokenWithExp(now + 3600)
        assertEquals(token, fixture(persisted = token).provider.getValidToken())
    }

    @Test
    fun `未登录时返回 null 这是合法业务态`() = runTest {
        assertNull(fixture(persisted = null).provider.getValidToken())
    }

    // ── 未实现项必须可见（P0 建立的纪律，P1 不得破坏）───────────

    /**
     * `requestLogin` 属 W2。debug 下必须**抛**，不能静默 no-op ——
     * 静默的症状是「点了没反应」，不报错不崩溃，只能靠用户反馈发现。
     */
    @Test(expected = NotImplementedError::class)
    fun `debug 下未实现项抛异常`() = runTest {
        fixture(persisted = null, isDebug = true).provider.requestLogin("test")
    }

    /** release 下记 error 日志并继续，不把用户卡死在未接线的入口上。 */
    @Test
    fun `release 下未实现项不抛`() = runTest {
        // 能走到断言即未抛。android.util.Log 的静态方法在 JVM 返回 int，
        // 不属于"调用即抛"那类 stub；若将来变了这个测试会红，那也是有用的信号
        val fixture = fixture(persisted = null, isDebug = false)
        fixture.provider.requestLogin("test")
        assertTrue(
            "release 不抛，但必须留下 error 日志 —— 否则就是静默 no-op",
            fixture.logs.any { it.startsWith("ERROR:") && it.contains("尚未实现") },
        )
    }

    // ── helpers ───────────────────────────────────────────────

    private class Fixture(
        val provider: ShellAuthProvider,
        val persistence: FakePersistence,
        val generations: Generations,
        val popSurfaceCounter: () -> Int,
        val logoutCounter: () -> Int,
        val logs: List<String>,
    ) {
        val popSurfaceCount: Int get() = popSurfaceCounter()
        val logoutCount: Int get() = logoutCounter()
    }

    private fun TestScope.fixture(
        persisted: String?,
        isDebug: Boolean = true,
    ): Fixture {
        val persistence = FakePersistence(persisted)
        val generations = Generations()
        var popCount = 0
        var logoutCount = 0

        val tokenStore = ShellTokenStore(
            persistence = persistence,
            refreshApi = { error("本测试不应触发刷新") },
            generations = generations,
            scope = this,
            nowSeconds = { now },
        )
        val hub = AuthStateHub()
        hub.addObserver(object : AuthStateHub.Observer {
            override fun onDidLogin(userId: String?) = Unit
            override fun onDidLogout() {
                logoutCount++
            }
        })

        val logs = mutableListOf<String>()
        val provider = ShellAuthProvider(
            isDebugBuild = isDebug,
            languageCodeProvider = { null },
            onPopSurface = { popCount++ },
            tokenStore = tokenStore,
            authStateHub = hub,
            scope = this,
            // android.util.Log 在 JVM 是「调用即抛」的 stub，故注入测试用实现。
            // 见 ShellAuthProvider.logger 的说明（不用 returnDefaultValues 绕）。
            logger = { level, message -> logs.add("$level:$message") },
            // JVM 单测无 Android 主 Looper，真 Dispatchers.Main 会抛
            mainDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        return Fixture(provider, persistence, generations, { popCount }, { logoutCount }, logs)
    }

    private class FakePersistence(var stored: String?) : ShellTokenStore.TokenPersistence {
        override fun read(): String? = stored
        override fun write(token: String?) {
            stored = token
        }
    }

    private fun tokenWithExp(exp: Long, sub: String = "u1"): String {
        val payload = JSONObject().put("exp", exp).put("sub", sub)
        return "${encode("""{"alg":"HS256"}""")}.${encode(payload.toString())}.sig"
    }

    private fun encode(json: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val bytes = json.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            sb.append(alphabet[b0 shr 2])
            if (b1 < 0) {
                sb.append(alphabet[(b0 and 0x03) shl 4])
            } else {
                sb.append(alphabet[((b0 and 0x03) shl 4) or (b1 shr 4)])
                if (b2 < 0) {
                    sb.append(alphabet[(b1 and 0x0F) shl 2])
                } else {
                    sb.append(alphabet[((b1 and 0x0F) shl 2) or (b2 shr 6)])
                    sb.append(alphabet[b2 and 0x3F])
                }
            }
            i += 3
        }
        return sb.toString()
    }
}
