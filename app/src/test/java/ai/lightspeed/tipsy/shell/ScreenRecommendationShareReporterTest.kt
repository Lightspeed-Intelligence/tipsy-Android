package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.auth.Generations
import ai.lightspeed.tipsy.shell.network.ApiException
import ai.lightspeed.tipsy.shell.pages.screen.ScreenAttribution
import ai.lightspeed.tipsy.shell.pages.screen.recommendation.ScreenRecommendationBatchSource
import ai.lightspeed.tipsy.shell.pages.screen.recommendation.ScreenRecommendationDiagnostic
import ai.lightspeed.tipsy.shell.pages.screen.recommendation.ScreenRecommendationQueueStorage
import ai.lightspeed.tipsy.shell.pages.screen.recommendation.ScreenRecommendationShareChannel
import ai.lightspeed.tipsy.shell.pages.screen.recommendation.ScreenRecommendationShareQueueCodec
import ai.lightspeed.tipsy.shell.pages.screen.recommendation.ScreenRecommendationShareReporter
import ai.lightspeed.tipsy.shell.pages.screen.recommendation.ScreenRecommendationStorageScope
import ai.lightspeed.tipsy.shell.pages.screen.recommendation.ScreenRecommendationTokenProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ScreenRecommendationShareReporterTest {

    @Test
    fun `上传成功后才从持久化队列删除`() = runTest {
        val storage = ReporterStorage()
        var uploadCount = 0
        val fixture = fixture(
            storage = storage,
            source = ScreenRecommendationBatchSource { _, _ -> uploadCount += 1 },
        )

        assertTrue(
            fixture.reporter.trackShare(
                fixture.attribution(),
                ScreenRecommendationShareChannel.DISCORD,
            ),
        )
        assertEquals(1, fixture.queue().size)

        runCurrent()

        assertEquals(1, uploadCount)
        assertTrue(fixture.queue().isEmpty())
    }

    @Test
    fun `上传失败保留事件并记录退避`() = runTest {
        val storage = ReporterStorage()
        val diagnostics = mutableListOf<String>()
        val fixture = fixture(
            storage = storage,
            diagnostics = diagnostics,
            source = ScreenRecommendationBatchSource { _, _ -> error("network") },
        )

        fixture.reporter.trackShare(
            fixture.attribution(),
            ScreenRecommendationShareChannel.TIKTOK,
        )
        runCurrent()

        assertEquals(1, fixture.queue().size)
        assertTrue(storage.values.containsKey(fixture.scope.retryKey))
        assertTrue(diagnostics.contains("screen_recommend_tracking_flush_failed"))
        coroutineContext.cancelChildren()
    }

    @Test
    fun `已知离线时只持久化 不上传也不增长退避`() = runTest {
        val storage = ReporterStorage()
        var uploadCount = 0
        val fixture = fixture(
            storage = storage,
            connectivityProvider = { false },
            source = ScreenRecommendationBatchSource { _, _ -> uploadCount += 1 },
        )

        assertTrue(
            fixture.reporter.trackShare(
                fixture.attribution(),
                ScreenRecommendationShareChannel.FACEBOOK,
            ),
        )
        runCurrent()

        assertEquals(1, fixture.queue().size)
        assertEquals(0, uploadCount)
        assertTrue(storage.values[fixture.scope.retryKey] == null)
    }

    @Test
    fun `请求失败前网络转为离线 不增长退避等待 reconnect`() = runTest {
        val storage = ReporterStorage()
        var connected = true
        val fixture = fixture(
            storage = storage,
            connectivityProvider = { connected },
            source = ScreenRecommendationBatchSource { _, _ ->
                connected = false
                error("network lost")
            },
        )

        fixture.reporter.trackShare(
            fixture.attribution(),
            ScreenRecommendationShareChannel.FACEBOOK,
        )
        runCurrent()

        assertEquals(1, fixture.queue().size)
        assertTrue(storage.values[fixture.scope.retryKey] == null)
    }

    @Test
    fun `同 event id 重复入队只保留一条`() = runTest {
        val storage = ReporterStorage()
        val fixture = fixture(
            storage = storage,
            source = ScreenRecommendationBatchSource { _, _ -> },
            fixedEventId = UUID.fromString(FIXED_EVENT_ID),
        )

        assertTrue(fixture.reporter.trackShare(fixture.attribution(), ScreenRecommendationShareChannel.X))
        assertTrue(fixture.reporter.trackShare(fixture.attribution(), ScreenRecommendationShareChannel.X))

        assertEquals(listOf(FIXED_EVENT_ID), fixture.queue().map { it.eventId })
        coroutineContext.cancelChildren()
    }

    @Test
    fun `上传期间 auth 换号不得删除旧 owner 队列`() = runTest {
        val storage = ReporterStorage()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = fixture(
            storage = storage,
            source = ScreenRecommendationBatchSource { _, _ ->
                started.complete(Unit)
                release.await()
            },
        )

        fixture.reporter.trackShare(
            fixture.attribution(),
            ScreenRecommendationShareChannel.FACEBOOK,
        )
        runCurrent()
        assertTrue(started.isCompleted)

        fixture.owner.value = "owner-2"
        fixture.generations.bumpAuth()
        release.complete(Unit)
        runCurrent()

        assertEquals("旧账号事件要留在旧 scope 等下次同账号登录", 1, fixture.queue().size)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `同 owner 请求途中刷新 token 仍确认成功响应`() = runTest {
        val storage = ReporterStorage()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val token = MutableToken("token-1")
        val fixture = fixture(
            storage = storage,
            tokenProvider = object : ScreenRecommendationTokenProvider {
                override suspend fun getValidToken(): String = token.value
            },
            source = ScreenRecommendationBatchSource { _, frozenToken ->
                assertEquals("token-1", frozenToken)
                started.complete(Unit)
                release.await()
            },
        )

        fixture.reporter.trackShare(
            fixture.attribution(),
            ScreenRecommendationShareChannel.INSTAGRAM,
        )
        runCurrent()
        assertTrue(started.isCompleted)

        // 正常 refresh 不 bump auth generation；成功响应仍属于同一个 owner。
        token.value = "token-2"
        release.complete(Unit)
        runCurrent()

        assertTrue(fixture.queue().isEmpty())
    }

    @Test
    fun `损坏队列被丢弃并记录后仍可接收新事件`() = runTest {
        val storage = ReporterStorage()
        val diagnostics = mutableListOf<String>()
        val scope = ScreenRecommendationStorageScope.create(API_BASE_URL, "owner-1")
        storage.values[scope.queueKey] = "{"
        val fixture = fixture(
            storage = storage,
            diagnostics = diagnostics,
            source = ScreenRecommendationBatchSource { _, _ -> },
        )

        assertTrue(
            fixture.reporter.trackShare(
                fixture.attribution(),
                ScreenRecommendationShareChannel.COPY_LINK,
            ),
        )

        assertEquals(1, fixture.queue().size)
        assertTrue(diagnostics.contains("screen_recommend_tracking_queue_pruned"))
        coroutineContext.cancelChildren()
    }

    @Test
    fun `code 2 二分隔离并只丢弃永久坏事件`() = runTest {
        val storage = ReporterStorage()
        val diagnostics = mutableListOf<String>()
        var uploadCount = 0
        val eventIds = listOf(
            UUID.fromString("123e4567-e89b-42d3-a456-426614174001"),
            UUID.fromString("123e4567-e89b-42d3-a456-426614174002"),
        )
        var eventIdIndex = 0
        val fixture = fixture(
            storage = storage,
            diagnostics = diagnostics,
            eventIdProvider = { eventIds[eventIdIndex++] },
            source = ScreenRecommendationBatchSource { body, _ ->
                uploadCount += 1
                if (body.contains("\"channel\":\"tiktok\"")) {
                    throw ApiException.Business(code = 2, serverMessage = "invalid")
                }
            },
        )

        assertTrue(
            fixture.reporter.trackShare(
                fixture.attribution(),
                ScreenRecommendationShareChannel.COPY_LINK,
            ),
        )
        assertTrue(
            fixture.reporter.trackShare(
                fixture.attribution(),
                ScreenRecommendationShareChannel.TIKTOK,
            ),
        )
        runCurrent()

        assertEquals(3, uploadCount)
        assertTrue(fixture.queue().isEmpty())
        assertTrue(diagnostics.contains("screen_recommend_tracking_event_dropped"))
        assertTrue("永久坏事件不应进入退避", !storage.values.containsKey(fixture.scope.retryKey))
    }

    private fun CoroutineScope.fixture(
        storage: ReporterStorage,
        source: ScreenRecommendationBatchSource,
        diagnostics: MutableList<String> = mutableListOf(),
        fixedEventId: UUID = UUID.fromString(FIXED_EVENT_ID),
        eventIdProvider: () -> UUID = { fixedEventId },
        tokenProvider: ScreenRecommendationTokenProvider = object : ScreenRecommendationTokenProvider {
            override suspend fun getValidToken(): String = "token-1"
        },
        connectivityProvider: () -> Boolean? = { null },
    ): Fixture {
        val owner = MutableOwner("owner-1")
        val generations = Generations()
        val scope = ScreenRecommendationStorageScope.create(API_BASE_URL, owner.value)
        val reporter = ScreenRecommendationShareReporter(
            apiBaseUrl = API_BASE_URL,
            storage = storage,
            batchSource = source,
            tokenProvider = tokenProvider,
            ownerUserIdProvider = { owner.value },
            generations = generations,
            coroutineScope = this,
            diagnostic = ScreenRecommendationDiagnostic { eventCode, _ -> diagnostics += eventCode },
            connectivityProvider = connectivityProvider,
            clock = { EVENT_TIME_MS },
            eventIdProvider = eventIdProvider,
        )
        return Fixture(reporter, storage, owner, generations, scope)
    }

    private data class Fixture(
        val reporter: ScreenRecommendationShareReporter,
        val storage: ReporterStorage,
        val owner: MutableOwner,
        val generations: Generations,
        val scope: ScreenRecommendationStorageScope,
    ) {
        fun attribution() = ScreenAttribution(
            requestId = "request-1",
            sessionId = "session-1",
            characterId = "character-1",
            position = 2,
            ownerUserId = "owner-1",
        )

        fun queue() = ScreenRecommendationShareQueueCodec.parse(storage.values[scope.queueKey]).events
    }

    private data class MutableOwner(var value: String)
    private data class MutableToken(var value: String)

    private class ReporterStorage : ScreenRecommendationQueueStorage {
        val values = linkedMapOf<String, String>()

        override fun read(key: String): String? = values[key]

        override fun write(key: String, value: String): Boolean {
            values[key] = value
            return true
        }

        override fun remove(key: String): Boolean {
            values.remove(key)
            return true
        }
    }

    private companion object {
        const val API_BASE_URL = "https://api.example.com/api/v1"
        const val EVENT_TIME_MS = 1_785_227_000_123L
        const val FIXED_EVENT_ID = "123e4567-e89b-42d3-a456-426614174000"
    }
}
