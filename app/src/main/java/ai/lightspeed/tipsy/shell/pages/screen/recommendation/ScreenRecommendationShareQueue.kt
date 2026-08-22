package ai.lightspeed.tipsy.shell.pages.screen.recommendation

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Native 私有持久化接缝；生产实现不会写 RN 共用 MMKV。 */
interface ScreenRecommendationQueueStorage {
    @Throws(Exception::class)
    fun read(key: String): String?

    /** 必须返回真实落盘结果；false 不得被当作成功。 */
    @Throws(Exception::class)
    fun write(key: String, value: String): Boolean

    /** 必须返回真实落盘结果；false 不得被当作成功。 */
    @Throws(Exception::class)
    fun remove(key: String): Boolean
}

/**
 * Screen 推荐反馈的 Native 私有 SharedPreferences。
 *
 * 使用同步 `commit()` 是刻意的：只有确认队列先落盘，reporter 才会启动上传。
 * 该文件不与 RN `screen_recommend_tracking_v2_rn.*` 键或 MMKV namespace 共用。
 */
class SharedPreferencesScreenRecommendationQueueStorage(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) : ScreenRecommendationQueueStorage {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )

    override fun read(key: String): String? = preferences.getString(key, null)

    @SuppressLint("ApplySharedPref")
    override fun write(key: String, value: String): Boolean =
        preferences.edit().putString(key, value).commit()

    @SuppressLint("ApplySharedPref")
    override fun remove(key: String): Boolean = preferences.edit().remove(key).commit()

    companion object {
        const val DEFAULT_PREFERENCES_NAME = "tipsy_screen_recommendation_native"
    }
}

/** 诊断只收协议安全字段；实现方不得附加或记录 auth token。 */
fun interface ScreenRecommendationDiagnostic {
    fun record(eventCode: String, fields: Map<String, Any>)
}

internal data class ScreenRecommendationStorageScope(
    val ownerUserId: String,
    val identity: String,
    val queueKey: String,
    val retryKey: String,
) {
    companion object {
        fun create(apiBaseUrl: String, ownerUserId: String): ScreenRecommendationStorageScope {
            val host = apiHost(apiBaseUrl)
            val prefix = listOf(
                PREFIX,
                encodeSegment(host),
                encodeSegment(ownerUserId),
            ).joinToString(".")
            return ScreenRecommendationStorageScope(
                ownerUserId = ownerUserId,
                identity = prefix,
                queueKey = "$prefix.pending_queue",
                retryKey = "$prefix.retry_state",
            )
        }

        private fun apiHost(apiBaseUrl: String): String {
            val trimmed = apiBaseUrl.trim()
            return runCatching {
                val uri = URI(trimmed)
                val hostname = uri.host?.lowercase(Locale.ROOT).orEmpty()
                require(hostname.isNotEmpty())
                if (uri.port >= 0) "$hostname:${uri.port}" else hostname
            }.getOrElse { trimmed.ifEmpty { "invalid" } }
        }

        private fun encodeSegment(value: String): String =
            URLEncoder.encode(value.trim(), StandardCharsets.UTF_8.toString())
                // URLEncoder 保留 `.`；而 scope 用 `.` 分段，若 owner 也含点会出现
                // `host=a.b,owner=c` 与 `host=a,owner=b.c` 的理论碰撞。
                .replace(".", "%2E")
                .ifEmpty { "invalid" }

        private const val PREFIX = "screen_recommend_tracking_v2_native"
    }
}

internal data class ScreenRecommendationParsedQueue(
    val events: List<ScreenRecommendationShareEvent>,
    val corrupted: Boolean,
    val droppedInvalidCount: Int,
    val droppedDuplicateCount: Int,
)

internal data class ScreenRecommendationPrunedQueue(
    val events: List<ScreenRecommendationShareEvent>,
    val droppedExpiredCount: Int,
    val droppedOverflowCount: Int,
)

internal data class ScreenRecommendationRetryState(
    val attempt: Int,
    val nextRetryAtMs: Long,
) {
    fun toJson(): String = JSONObject()
        .put(FIELD_ATTEMPT, attempt)
        .put(FIELD_NEXT_RETRY_AT_MS, nextRetryAtMs)
        .toString()

    companion object {
        fun parse(raw: String?): ScreenRecommendationRetryState? {
            if (raw.isNullOrEmpty()) return null
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            if (json.keys().asSequence().toSet() != setOf(FIELD_ATTEMPT, FIELD_NEXT_RETRY_AT_MS)) {
                return null
            }
            val attempt = json.opt(FIELD_ATTEMPT).asExactLong()
                ?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }
                ?.toInt()
                ?: return null
            val nextRetryAt = json.opt(FIELD_NEXT_RETRY_AT_MS).asExactLong()
                ?.takeIf { it > 0L }
                ?: return null
            return ScreenRecommendationRetryState(attempt, nextRetryAt)
        }

        private const val FIELD_ATTEMPT = "attempt"
        private const val FIELD_NEXT_RETRY_AT_MS = "next_retry_at_ms"
    }
}

internal object ScreenRecommendationShareQueueCodec {
    const val EVENT_TTL_MS = 7L * 24L * 60L * 60L * 1000L
    const val MAX_QUEUE_SIZE = 10_000
    const val MAX_BATCH_SIZE = 800

    fun serialize(events: List<ScreenRecommendationShareEvent>): String =
        JSONArray().apply { events.forEach { put(it.toJson()) } }.toString()

    fun parse(raw: String?): ScreenRecommendationParsedQueue {
        if (raw.isNullOrEmpty()) {
            return ScreenRecommendationParsedQueue(emptyList(), false, 0, 0)
        }
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: return ScreenRecommendationParsedQueue(emptyList(), true, 1, 0)

        val events = ArrayList<ScreenRecommendationShareEvent>(array.length())
        val ids = HashSet<String>()
        var droppedInvalid = 0
        var droppedDuplicate = 0
        for (index in 0 until array.length()) {
            val event = array.optJSONObject(index)?.let(ScreenRecommendationShareEvent::parse)
            if (event == null) {
                droppedInvalid += 1
                continue
            }
            if (!ids.add(event.eventId)) {
                droppedDuplicate += 1
                continue
            }
            events += event
        }
        return ScreenRecommendationParsedQueue(
            events = events,
            corrupted = false,
            droppedInvalidCount = droppedInvalid,
            droppedDuplicateCount = droppedDuplicate,
        )
    }

    fun prune(
        events: List<ScreenRecommendationShareEvent>,
        nowMs: Long,
        eventTtlMs: Long = EVENT_TTL_MS,
        maximumQueueSize: Int = MAX_QUEUE_SIZE,
    ): ScreenRecommendationPrunedQueue {
        val oldestAllowedAtMs = nowMs - eventTtlMs.coerceAtLeast(0L)
        val unexpired = events.filter { event ->
            event.eventTimeMs > 0L && event.eventTimeMs >= oldestAllowedAtMs
        }
        val normalizedMaximum = maximumQueueSize.coerceAtLeast(0)
        val overflowCount = (unexpired.size - normalizedMaximum).coerceAtLeast(0)
        return ScreenRecommendationPrunedQueue(
            events = unexpired.drop(overflowCount),
            droppedExpiredCount = events.size - unexpired.size,
            droppedOverflowCount = overflowCount,
        )
    }
}

internal object ScreenRecommendationRetryPolicy {
    const val BASE_DELAY_MS = 2_000L
    const val MAX_DELAY_MS = 5L * 60L * 1000L
    const val MAX_ATTEMPT = 16

    fun delayMs(attempt: Int): Long {
        var result = BASE_DELAY_MS
        repeat(attempt.coerceIn(1, MAX_ATTEMPT) - 1) {
            result = (result * 2L).coerceAtMost(MAX_DELAY_MS)
        }
        return result
    }
}

private fun Any?.asExactLong(): Long? {
    val number = this as? Number ?: return null
    val double = number.toDouble()
    if (!double.isFinite()) return null
    val long = number.toLong()
    return long.takeIf { double == long.toDouble() }
}
