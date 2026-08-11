package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.auth.AuthStateHub
import ai.lightspeed.tipsy.shell.auth.Generations
import ai.lightspeed.tipsy.shell.auth.ShellTokenStore
import ai.lightspeed.tipsy.shell.bridge.ShellAuthProvider
import ai.lightspeed.tipsy.shell.network.ApiErrorGate
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
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
@OptIn(ExperimentalCoroutinesApi::class)
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
        assertEquals("不得向 RN 广播登出", 0, fixture.rnLogoutCount)
        assertEquals("不得向壳内广播登出", 0, fixture.logoutCount)
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
        assertEquals("RN Registry 必须收到一次登出", 1, fixture.rnLogoutCount)
        assertEquals("壳内常驻页必须收到一次登出", 1, fixture.logoutCount)
        assertEquals("401 登出必须收栈一次", 1, fixture.popSurfaceCount)
    }

    @Test
    fun `无参 401 无法证明归属不得登出当前账号`() = runTest {
        val current = tokenWithExp(now + 3600)
        val fixture = fixture(persisted = current)

        fixture.provider.notifyServerAuthRejected()

        assertEquals(current, fixture.persistence.stored)
        assertEquals(0, fixture.rnLogoutCount)
        assertEquals(0, fixture.logoutCount)
        assertEquals(0, fixture.popSurfaceCount)
        assertTrue(fixture.logs.any { it.contains("无法校验 token 归属，已忽略") })
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
        assertEquals("RN loggedOut 必须**恰好**一次", 1, fixture.rnLogoutCount)
        assertEquals("壳内 loggedOut 必须**恰好**一次", 1, fixture.logoutCount)
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
        assertEquals("clearToken 不得通知 RN loggedOut", 0, fixture.rnLogoutCount)
        assertEquals("clearToken 不得通知壳内 loggedOut", 0, fixture.logoutCount)
    }

    @Test
    fun `刷新失败且旧 token 已失效时自动登出两端各通知一次`() = runTest {
        var clock = now
        val expiring = tokenWithExp(now + 60)
        val fixture = fixture(
            persisted = expiring,
            nowProvider = { clock },
            refreshApi = {
                clock = now + 120
                throw IllegalStateException("刷新失败")
            },
        )

        assertNull(fixture.provider.getValidToken())

        assertEquals("token-store listener/RN Registry 只通知一次", 1, fixture.rnLogoutCount)
        assertEquals("AuthStateHub 只通知一次", 1, fixture.logoutCount)
        assertEquals("自动失效不经 provider.logout，不应收栈", 0, fixture.popSurfaceCount)
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

    @Test
    fun `桥不得把过期或畸形 token 交给 WebView 等直接消费者`() = runTest {
        val invalid = listOf(tokenWithExp(now - 1), "not-a-jwt")
        invalid.forEach { token ->
            assertNull(fixture(persisted = token).provider.getValidToken())
        }
    }

    // ── 402 付费墙（W1-P6）─────────────────────────────────────

    /**
     * ⚠️ **`notifyServerPaymentRequired` 绝不能是 `notImplemented`。**
     *
     * 401/402 由 `ApiErrorGate` 汇聚后调到它，而 `notImplemented` 在 debug 下
     * **会抛 NotImplementedError** —— 那意味着每次收到 402 都让 App 崩。
     * 这个坑在 P6 接线时真的踩到过（当时该方法还标着「W1-P6 未实现」）。
     *
     * 本测试跑在 `isDebug = true` 下，若有人把它改回 notImplemented，这里会红。
     */
    @Test
    fun `402 不得抛异常 必须导航到宝石购买`() = runTest {
        val f = fixture(persisted = tokenWithExp(now + 3600), isDebug = true)

        f.provider.notifyServerPaymentRequired()
        runCurrent()

        assertEquals("402 必须触发一次宝石购买导航", 1, f.gemsPurchaseCalls.size)
    }

    @Test
    fun `openGemsPurchase 与 402 共用同一出口`() = runTest {
        val f = fixture(persisted = tokenWithExp(now + 3600), isDebug = true)

        f.provider.openGemsPurchase(mapOf("from" to "chat"))
        f.provider.notifyServerPaymentRequired()
        runCurrent()

        assertEquals("两处必须汇到同一出口，否则「未启用」判定会漂移", 2, f.gemsPurchaseCalls.size)
        assertEquals(mapOf("from" to "chat"), f.gemsPurchaseCalls[0])
        assertEquals("402 兜底不带参数", emptyMap<String, String>(), f.gemsPurchaseCalls[1])
    }

    // ── Native / RN 共享进程级 gate ─────────────────────────

    @Test
    fun `Native 与 RN bridge 的 401 共用同一防抖窗口`() = runTest {
        val current = tokenWithExp(now + 3600)
        val f = fixture(persisted = current)

        // 模拟 Native ApiClient 直接进 gate，紧接着 RN 经 provider 进同一 gate。
        f.gate.onUnauthorized(current)
        f.provider.notifyServerAuthRejectedForToken(current)

        assertEquals("RN loggedOut 不得重复", 1, f.rnLogoutCount)
        assertEquals("壳内 loggedOut 不得重复", 1, f.logoutCount)
        assertEquals("只收栈一次", 1, f.popSurfaceCount)
    }

    @Test
    fun `A stale 401 不得吞掉同窗口内 B 的真实 401`() = runTest {
        val accountA = tokenWithExp(now + 3600, sub = "account-a")
        val accountB = tokenWithExp(now + 3600, sub = "account-b")
        val f = fixture(persisted = accountB)

        f.gate.onUnauthorized(accountA)
        f.provider.notifyServerAuthRejectedForToken(accountB)

        assertNull("B 的真实 401 仍必须登出 B", f.persistence.stored)
        assertEquals(1, f.rnLogoutCount)
        assertEquals(1, f.logoutCount)
        f.logs.forEach { line ->
            assertTrue("gate/provider 日志不得泄露 A token", accountA !in line)
            assertTrue("gate/provider 日志不得泄露 B token", accountB !in line)
        }
    }

    @Test
    fun `Native 与 RN bridge 的 402 共用同一防抖窗口`() = runTest {
        val f = fixture(persisted = tokenWithExp(now + 3600))

        f.gate.onPaymentRequired()
        f.provider.notifyServerPaymentRequired()
        runCurrent()

        assertEquals("宝石购买导航只触发一次", 1, f.gemsPurchaseCalls.size)
    }

    @Test
    fun `Native 402 终端必须切到注入的主线程 dispatcher`() = runTest {
        val main = RecordingDispatcher()
        val f = fixture(
            persisted = tokenWithExp(now + 3600),
            mainDispatcher = main,
        )

        // 直接调 gate 模拟 ApiClient 从 Dispatchers.IO 进入，不经桥的 @MainThread。
        f.gate.onPaymentRequired()

        assertTrue("进 Router 前必须显式切 mainDispatcher", main.dispatchCount > 0)
        assertEquals(1, f.gemsPurchaseCalls.size)
    }

    @Test
    fun `401 原子清理与收栈必须在同一主线程顺序段`() = runTest {
        val main = RecordingDispatcher()
        var clearOnMain = false
        var popOnMain = false
        val current = tokenWithExp(now + 3600)
        val f = fixture(
            persisted = current,
            mainDispatcher = main,
            onTokenClearedHook = { clearOnMain = main.isDispatching },
            onPopSurfaceHook = { popOnMain = main.isDispatching },
        )

        f.gate.onUnauthorized(current)

        assertTrue("token 清理/listener 应在 mainDispatcher 顺序段", clearOnMain)
        assertTrue("收栈应紧接着在同一 mainDispatcher 顺序段", popOnMain)
    }

    @Test
    fun `logout 清理与收栈必须在同一主线程顺序段`() = runTest {
        val main = RecordingDispatcher()
        var clearOnMain = false
        var popOnMain = false
        val f = fixture(
            persisted = tokenWithExp(now + 3600),
            mainDispatcher = main,
            onTokenClearedHook = { clearOnMain = main.isDispatching },
            onPopSurfaceHook = { popOnMain = main.isDispatching },
        )

        f.provider.logout()

        assertTrue(clearOnMain)
        assertTrue(popOnMain)
    }

    // ── apiBaseURL（W1-P6）────────────────────────────────────

    /**
     * 壳是 API 地址真值。返回 null 会让 RN 回退构建期地址 ——
     * 那样原生页与 Surface 可能命中不同后端，且两边都不报错。
     */
    @Test
    fun `apiBaseURL 返回注入的地址`() = runTest {
        val f = fixture(persisted = null, apiBaseUrl = "https://api.example.com/v1")
        assertEquals("https://api.example.com/v1", f.provider.apiBaseURL())
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
        val gemsPurchaseCalls: List<Map<String, String>>,
        val gate: ApiErrorGate,
        val rnLogoutCounter: () -> Int,
    ) {
        val popSurfaceCount: Int get() = popSurfaceCounter()
        val logoutCount: Int get() = logoutCounter()
        val rnLogoutCount: Int get() = rnLogoutCounter()
    }

    private fun TestScope.fixture(
        persisted: String?,
        isDebug: Boolean = true,
        apiBaseUrl: String? = null,
        nowProvider: () -> Long = { now },
        refreshApi: ShellTokenStore.RefreshApi = { error("本测试不应触发刷新") },
        mainDispatcher: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        onTokenClearedHook: () -> Unit = {},
        onPopSurfaceHook: () -> Unit = {},
    ): Fixture {
        val persistence = FakePersistence(persisted)
        val generations = Generations()
        var popCount = 0
        var logoutCount = 0
        var rnLogoutCount = 0

        val hub = AuthStateHub()
        hub.addObserver(object : AuthStateHub.Observer {
            override fun onDidLogin(userId: String?) = Unit
            override fun onDidLogout() {
                logoutCount++
            }
        })

        val tokenStore = ShellTokenStore(
            persistence = persistence,
            refreshApi = refreshApi,
            generations = generations,
            scope = this,
            listener = object : ShellTokenStore.Listener {
                override fun onTokenCleared() {
                    rnLogoutCount++
                    hub.notifyDidLogout()
                    onTokenClearedHook()
                }
            },
            nowSeconds = nowProvider,
        )

        val logs = mutableListOf<String>()
        val gemsCalls = mutableListOf<Map<String, String>>()
        lateinit var provider: ShellAuthProvider
        val gate = ApiErrorGate(
            onAuthRejected = { provider.handleServerAuthRejectedForToken(it) },
            onPaymentRequired = { provider.handleServerPaymentRequired() },
            nowMillis = { 1_000L },
            logger = { logs.add("GATE:$it") },
        )
        provider = ShellAuthProvider(
            isDebugBuild = isDebug,
            languageCodeProvider = { null },
            apiBaseUrlProvider = { apiBaseUrl },
            onPopSurface = {
                popCount++
                onPopSurfaceHook()
            },
            onNavigateGemsPurchase = { gemsCalls.add(it) },
            tokenStore = tokenStore,
            apiErrorGate = gate,
            scope = this,
            // android.util.Log 在 JVM 是「调用即抛」的 stub，故注入测试用实现。
            // 见 ShellAuthProvider.logger 的说明（不用 returnDefaultValues 绕）。
            logger = { level, message -> logs.add("$level:$message") },
            // JVM 单测无 Android 主 Looper，真 Dispatchers.Main 会抛
            mainDispatcher = mainDispatcher,
        )
        return Fixture(
            provider, persistence, generations,
            { popCount }, { logoutCount }, logs, gemsCalls, gate, { rnLogoutCount },
        )
    }

    private class FakePersistence(var stored: String?) : ShellTokenStore.TokenPersistence {
        override fun read(): String? = stored
        override fun write(token: String?) {
            stored = token
        }
    }

    private class RecordingDispatcher : CoroutineDispatcher() {
        var dispatchCount: Int = 0
            private set
        var isDispatching: Boolean = false
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatchCount++
            isDispatching = true
            try {
                block.run()
            } finally {
                isDispatching = false
            }
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
