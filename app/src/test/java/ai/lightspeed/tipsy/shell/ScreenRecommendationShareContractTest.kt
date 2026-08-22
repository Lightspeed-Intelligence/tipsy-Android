package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.auth.Generations
import ai.lightspeed.tipsy.shell.pages.screen.ScreenAttribution
import ai.lightspeed.tipsy.shell.pages.screen.recommendation.ScreenRecommendationBatchSource
import ai.lightspeed.tipsy.shell.pages.screen.recommendation.ScreenRecommendationDiagnostic
import ai.lightspeed.tipsy.shell.pages.screen.recommendation.ScreenRecommendationQueueStorage
import ai.lightspeed.tipsy.shell.pages.screen.recommendation.ScreenRecommendationShareChannel
import ai.lightspeed.tipsy.shell.pages.screen.recommendation.ScreenRecommendationShareReporter
import ai.lightspeed.tipsy.shell.pages.screen.recommendation.ScreenRecommendationTokenProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ScreenRecommendationShareContractTest {

    @Test
    fun `channel wire 集合完全对齐 RN`() {
        assertEquals(
            setOf("copy_link", "instagram", "discord", "tiktok", "x", "facebook"),
            ScreenRecommendationShareChannel.entries.mapTo(linkedSetOf()) { it.wireValue },
        )
    }

    @Test
    fun `JSON exact 且上传发生在持久化之后`() = runTest {
        val storage = ContractStorage()
        var persistedAtUpload = false
        var uploadedBody: String? = null
        val reporter = reporter(
            storage = storage,
            batchSource = ScreenRecommendationBatchSource { body, _ ->
                persistedAtUpload = storage.values.values.any { raw -> raw.contains(FIXED_EVENT_ID) }
                uploadedBody = body
            },
        )

        assertTrue(reporter.trackShare(attribution(), ScreenRecommendationShareChannel.INSTAGRAM))
        runCurrent()

        assertTrue("source 起飞前事件必须已经持久化", persistedAtUpload)
        val root = JSONObject(uploadedBody!!)
        assertEquals(setOf("events"), root.keys().asSequence().toSet())
        val event = root.getJSONArray("events").getJSONObject(0)
        assertEquals(
            setOf("event_id", "event_time", "event_data", "context"),
            event.keys().asSequence().toSet(),
        )
        assertEquals(FIXED_EVENT_ID, event.getString("event_id"))
        assertEquals(1_785_227_000_123L, event.getLong("event_time"))
        val eventData = event.getJSONObject("event_data")
        assertEquals(setOf("type", "scene", "extra_info"), eventData.keys().asSequence().toSet())
        assertEquals("share", eventData.getString("type"))
        assertEquals("screen", eventData.getString("scene"))
        val extraInfo = eventData.getJSONObject("extra_info")
        assertEquals(setOf("channel"), extraInfo.keys().asSequence().toSet())
        assertEquals("instagram", extraInfo.getString("channel"))

        val context = event.getJSONObject("context")
        assertEquals(setOf("recommend_info"), context.keys().asSequence().toSet())
        val recommendInfo = context.getJSONObject("recommend_info")
        assertEquals(
            setOf("request_id", "session_id", "character_id", "position"),
            recommendInfo.keys().asSequence().toSet(),
        )
        assertEquals("request-1", recommendInfo.getString("request_id"))
        assertEquals("session-1", recommendInfo.getString("session_id"))
        assertEquals("character-1", recommendInfo.getString("character_id"))
        assertEquals(3, recommendInfo.getInt("position"))
    }

    @Test
    fun `owner mismatch 不建事件也不上传`() = runTest {
        val storage = ContractStorage()
        var uploadCount = 0
        val reporter = reporter(
            storage = storage,
            ownerProvider = { "owner-2" },
            batchSource = ScreenRecommendationBatchSource { _, _ -> uploadCount += 1 },
        )

        assertFalse(reporter.trackShare(attribution(), ScreenRecommendationShareChannel.COPY_LINK))
        runCurrent()

        assertTrue(storage.values.isEmpty())
        assertEquals(0, uploadCount)
    }

    @Test
    fun `持久化失败返回 false 且不上传`() = runTest {
        val storage = ContractStorage(failWrites = true)
        var uploadCount = 0
        val reporter = reporter(
            storage = storage,
            batchSource = ScreenRecommendationBatchSource { _, _ -> uploadCount += 1 },
        )

        assertFalse(reporter.trackShare(attribution(), ScreenRecommendationShareChannel.FACEBOOK))
        runCurrent()

        assertEquals(0, uploadCount)
    }

    private fun CoroutineScope.reporter(
        storage: ContractStorage,
        ownerProvider: () -> String? = { "owner-1" },
        batchSource: ScreenRecommendationBatchSource,
    ) = ScreenRecommendationShareReporter(
        apiBaseUrl = "https://api.example.com/api/v1",
        storage = storage,
        batchSource = batchSource,
        tokenProvider = object : ScreenRecommendationTokenProvider {
            override suspend fun getValidToken(): String = "current-token"
        },
        ownerUserIdProvider = ownerProvider,
        generations = Generations(),
        coroutineScope = this,
        diagnostic = ScreenRecommendationDiagnostic { _, _ -> },
        clock = { 1_785_227_000_123L },
        eventIdProvider = { UUID.fromString(FIXED_EVENT_ID) },
    )

    private fun attribution() = ScreenAttribution(
        requestId = "request-1",
        sessionId = "session-1",
        characterId = "character-1",
        position = 3,
        ownerUserId = "owner-1",
    )

    private class ContractStorage(
        private val failWrites: Boolean = false,
    ) : ScreenRecommendationQueueStorage {
        val values = linkedMapOf<String, String>()

        override fun read(key: String): String? = values[key]

        override fun write(key: String, value: String): Boolean {
            if (failWrites) return false
            values[key] = value
            return true
        }

        override fun remove(key: String): Boolean {
            values.remove(key)
            return true
        }
    }

    private companion object {
        const val FIXED_EVENT_ID = "123e4567-e89b-42d3-a456-426614174000"
    }
}
