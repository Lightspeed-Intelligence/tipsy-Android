package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.network.ApiEnvelope
import ai.lightspeed.tipsy.shell.network.ApiException
import ai.lightspeed.tipsy.shell.pages.screen.share.ApiScreenShareModerationSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenShareModerationSourceTest {

    @Test
    fun `正常响应只有 data ok true 才通过`() = runTest {
        val allowed = sourceReturning(ApiEnvelope(0, null, JSONObject().put("ok", true)))
        val blocked = sourceReturning(ApiEnvelope(0, null, JSONObject().put("ok", false)))
        val missing = sourceReturning(ApiEnvelope(0, null, JSONObject()))

        assertTrue(allowed.isAllowed("character-1"))
        assertFalse(blocked.isAllowed("character-1"))
        assertFalse(missing.isAllowed("character-1"))
    }

    @Test
    fun `业务错误读取结构化 ok 并把缺失视为明确拒绝`() = runTest {
        val allowed = sourceThrowing(
            ApiException.Business(7, "moderated", JSONObject().put("ok", true)),
        )
        val blocked = sourceThrowing(
            ApiException.Business(7, "moderated", JSONObject().put("ok", false)),
        )
        val missing = sourceThrowing(ApiException.Business(7, "moderated"))

        assertTrue(allowed.isAllowed("character-1"))
        assertFalse(blocked.isAllowed("character-1"))
        assertFalse(missing.isAllowed("character-1"))
    }

    @Test
    fun `HTTP 鉴权与传输失败按 RN 语义 fail open`() = runTest {
        assertTrue(sourceThrowing(ApiException.Http(500)).isAllowed("character-1"))
        assertTrue(sourceThrowing(ApiException.Unauthenticated()).isAllowed("character-1"))
        assertTrue(
            sourceThrowing(ApiException.Transport(java.io.IOException("offline")))
                .isAllowed("character-1"),
        )
    }

    @Test
    fun `取消不能被 fail open 吞掉`() = runTest {
        var cancelled = false
        try {
            sourceThrowing(CancellationException("closed")).isAllowed("character-1")
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test
    fun `空 character id 跳过请求并直接允许`() = runTest {
        var requestCount = 0
        val source = ApiScreenShareModerationSource(
            request = {
                requestCount++
                error("不应请求")
            },
            logWarn = { _, _ -> },
        )

        assertTrue(source.isAllowed("  "))
        assertTrue(requestCount == 0)
    }

    private fun sourceReturning(envelope: ApiEnvelope) = ApiScreenShareModerationSource(
        request = { envelope },
        logWarn = { _, _ -> },
    )

    private fun sourceThrowing(error: Throwable) = ApiScreenShareModerationSource(
        request = { throw error },
        logWarn = { _, _ -> },
    )
}
