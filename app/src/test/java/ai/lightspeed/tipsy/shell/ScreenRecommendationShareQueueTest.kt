package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.screen.ScreenAttribution
import ai.lightspeed.tipsy.shell.pages.screen.recommendation.ScreenRecommendationShareChannel
import ai.lightspeed.tipsy.shell.pages.screen.recommendation.ScreenRecommendationShareEvent
import ai.lightspeed.tipsy.shell.pages.screen.recommendation.ScreenRecommendationShareQueueCodec
import ai.lightspeed.tipsy.shell.pages.screen.recommendation.ScreenRecommendationStorageScope
import ai.lightspeed.tipsy.shell.pages.screen.recommendation.ScreenRecommendationRetryPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ScreenRecommendationShareQueueTest {

    @Test
    fun `持久化队列按 event id 去重并丢无效项`() {
        val event = event(FIRST_EVENT_ID, 1_000L)
        val raw = JSONArray()
            .put(event.toJson())
            .put(event.toJson())
            .put(JSONObject().put("event_id", "broken"))
            .toString()

        val parsed = ScreenRecommendationShareQueueCodec.parse(raw)

        assertFalse(parsed.corrupted)
        assertEquals(listOf(FIRST_EVENT_ID), parsed.events.map { it.eventId })
        assertEquals(1, parsed.droppedDuplicateCount)
        assertEquals(1, parsed.droppedInvalidCount)
    }

    @Test
    fun `损坏 JSON 标记整队丢弃而不抛异常`() {
        val parsed = ScreenRecommendationShareQueueCodec.parse("{")

        assertTrue(parsed.corrupted)
        assertTrue(parsed.events.isEmpty())
    }

    @Test
    fun `TTL 七天且容量只保留最新一万条`() {
        assertEquals(7L * 24L * 60L * 60L * 1_000L, ScreenRecommendationShareQueueCodec.EVENT_TTL_MS)
        assertEquals(10_000, ScreenRecommendationShareQueueCodec.MAX_QUEUE_SIZE)
        assertEquals(800, ScreenRecommendationShareQueueCodec.MAX_BATCH_SIZE)

        val pruned = ScreenRecommendationShareQueueCodec.prune(
            events = listOf(
                event(FIRST_EVENT_ID, 100L),
                event(SECOND_EVENT_ID, 950L),
                event(THIRD_EVENT_ID, 1_000L),
            ),
            nowMs = 1_000L,
            eventTtlMs = 100L,
            maximumQueueSize = 1,
        )

        assertEquals(listOf(THIRD_EVENT_ID), pruned.events.map { it.eventId })
        assertEquals(1, pruned.droppedExpiredCount)
        assertEquals(1, pruned.droppedOverflowCount)
    }

    @Test
    fun `持久化 key 按 API host 与 owner 隔离并保留 owner 大小写`() {
        val ownerA = ScreenRecommendationStorageScope.create(
            "https://preview.example.com/api/v1",
            "owner-a",
        )
        val ownerB = ScreenRecommendationStorageScope.create(
            "https://preview.example.com/api/v1",
            "owner-b",
        )
        val production = ScreenRecommendationStorageScope.create(
            "https://api.example.com/api/v1",
            "owner-a",
        )
        val caseDistinct = ScreenRecommendationStorageScope.create(
            "https://preview.example.com/api/v1",
            "Owner-A",
        )
        val dottedHost = ScreenRecommendationStorageScope.create("https://a.b", "c")
        val dottedOwner = ScreenRecommendationStorageScope.create("https://a", "b.c")

        assertNotEquals(ownerA.queueKey, ownerB.queueKey)
        assertNotEquals(ownerA.queueKey, production.queueKey)
        assertNotEquals(ownerA.queueKey, caseDistinct.queueKey)
        assertNotEquals(dottedHost.queueKey, dottedOwner.queueKey)
    }

    @Test
    fun `失败退避从两秒指数增长并封顶五分钟`() {
        assertEquals(2_000L, ScreenRecommendationRetryPolicy.delayMs(1))
        assertEquals(4_000L, ScreenRecommendationRetryPolicy.delayMs(2))
        assertEquals(256_000L, ScreenRecommendationRetryPolicy.delayMs(8))
        assertEquals(300_000L, ScreenRecommendationRetryPolicy.delayMs(9))
        assertEquals(300_000L, ScreenRecommendationRetryPolicy.delayMs(16))
    }

    private fun event(id: String, time: Long): ScreenRecommendationShareEvent =
        ScreenRecommendationShareEvent.create(
            attribution = ScreenAttribution(
                requestId = "request-1",
                sessionId = "session-1",
                characterId = "character-1",
                position = 0,
                ownerUserId = "owner-1",
            ),
            channel = ScreenRecommendationShareChannel.COPY_LINK,
            eventId = UUID.fromString(id),
            eventTimeMs = time,
        )!!

    private companion object {
        const val FIRST_EVENT_ID = "123e4567-e89b-42d3-a456-426614174000"
        const val SECOND_EVENT_ID = "123e4567-e89b-42d3-a456-426614174001"
        const val THIRD_EVENT_ID = "123e4567-e89b-42d3-a456-426614174002"
    }
}
