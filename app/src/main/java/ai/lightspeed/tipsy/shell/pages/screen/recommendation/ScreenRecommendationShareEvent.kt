package ai.lightspeed.tipsy.shell.pages.screen.recommendation

import ai.lightspeed.tipsy.shell.pages.screen.ScreenAttribution
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Screen 分享渠道的线上 wire 值。
 *
 * 这六个值与 RN `ScreenRecommendationShareChannel` 完全一致；不要把展示名、
 * Android package name 或 Intent scheme 塞进推荐反馈协议。
 */
enum class ScreenRecommendationShareChannel(val wireValue: String) {
    COPY_LINK("copy_link"),
    INSTAGRAM("instagram"),
    DISCORD("discord"),
    TIKTOK("tiktok"),
    X("x"),
    FACEBOOK("facebook"),
    ;

    companion object {
        fun fromWireValue(value: String): ScreenRecommendationShareChannel? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/** 一条已冻结的 `/tracking_v2/report_batch` Screen share 事件。 */
internal data class ScreenRecommendationShareEvent(
    val eventId: String,
    val eventTimeMs: Long,
    val channel: ScreenRecommendationShareChannel,
    val requestId: String,
    val sessionId: String,
    val characterId: String,
    val position: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put(FIELD_EVENT_ID, eventId)
        .put(FIELD_EVENT_TIME, eventTimeMs)
        .put(
            FIELD_EVENT_DATA,
            JSONObject()
                .put(FIELD_TYPE, EVENT_TYPE_SHARE)
                .put(FIELD_SCENE, SCENE_SCREEN)
                .put(
                    FIELD_EXTRA_INFO,
                    JSONObject().put(FIELD_CHANNEL, channel.wireValue),
                ),
        )
        .put(
            FIELD_CONTEXT,
            JSONObject().put(
                FIELD_RECOMMEND_INFO,
                JSONObject()
                    .put(FIELD_REQUEST_ID, requestId)
                    .put(FIELD_SESSION_ID, sessionId)
                    .put(FIELD_CHARACTER_ID, characterId)
                    .put(FIELD_POSITION, position),
            ),
        )

    companion object {
        private val UUID_PATTERN = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )

        fun create(
            attribution: ScreenAttribution,
            channel: ScreenRecommendationShareChannel,
            eventId: UUID,
            eventTimeMs: Long,
        ): ScreenRecommendationShareEvent? {
            if (eventTimeMs <= 0L || attribution.position < 0) return null
            if (
                attribution.requestId.isBlank() ||
                attribution.sessionId.isBlank() ||
                attribution.characterId.isBlank()
            ) {
                return null
            }
            return ScreenRecommendationShareEvent(
                eventId = eventId.toString().lowercase(Locale.ROOT),
                eventTimeMs = eventTimeMs,
                channel = channel,
                requestId = attribution.requestId,
                sessionId = attribution.sessionId,
                characterId = attribution.characterId,
                position = attribution.position,
            )
        }

        /**
         * 严格读取 Native 私有队列。多余字段也视为无效，避免把旧协议或任意 JSON
         * 重新发到 v2 端点。
         */
        fun parse(json: JSONObject): ScreenRecommendationShareEvent? {
            if (!json.hasExactKeys(FIELD_EVENT_ID, FIELD_EVENT_TIME, FIELD_EVENT_DATA, FIELD_CONTEXT)) {
                return null
            }
            val eventId = json.strictString(FIELD_EVENT_ID)
                ?.takeIf { it == it.lowercase(Locale.ROOT) && UUID_PATTERN.matches(it) }
                ?: return null
            val eventTime = json.strictLong(FIELD_EVENT_TIME)?.takeIf { it > 0L } ?: return null

            val eventData = json.optJSONObject(FIELD_EVENT_DATA)
                ?.takeIf { it.hasExactKeys(FIELD_TYPE, FIELD_SCENE, FIELD_EXTRA_INFO) }
                ?: return null
            if (eventData.strictString(FIELD_TYPE) != EVENT_TYPE_SHARE) return null
            if (eventData.strictString(FIELD_SCENE) != SCENE_SCREEN) return null
            val extraInfo = eventData.optJSONObject(FIELD_EXTRA_INFO)
                ?.takeIf { it.hasExactKeys(FIELD_CHANNEL) }
                ?: return null
            val channel = extraInfo.strictString(FIELD_CHANNEL)
                ?.let(ScreenRecommendationShareChannel::fromWireValue)
                ?: return null

            val context = json.optJSONObject(FIELD_CONTEXT)
                ?.takeIf { it.hasExactKeys(FIELD_RECOMMEND_INFO) }
                ?: return null
            val recommendation = context.optJSONObject(FIELD_RECOMMEND_INFO)
                ?.takeIf {
                    it.hasExactKeys(
                        FIELD_REQUEST_ID,
                        FIELD_SESSION_ID,
                        FIELD_CHARACTER_ID,
                        FIELD_POSITION,
                    )
                }
                ?: return null
            val requestId = recommendation.strictString(FIELD_REQUEST_ID)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val sessionId = recommendation.strictString(FIELD_SESSION_ID)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val characterId = recommendation.strictString(FIELD_CHARACTER_ID)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val position = recommendation.strictInt(FIELD_POSITION)?.takeIf { it >= 0 } ?: return null

            return ScreenRecommendationShareEvent(
                eventId = eventId,
                eventTimeMs = eventTime,
                channel = channel,
                requestId = requestId,
                sessionId = sessionId,
                characterId = characterId,
                position = position,
            )
        }

        fun batchBody(events: List<ScreenRecommendationShareEvent>): String =
            JSONObject()
                .put(
                    FIELD_EVENTS,
                    JSONArray().apply { events.forEach { put(it.toJson()) } },
                )
                .toString()

        private const val FIELD_EVENTS = "events"
        private const val FIELD_EVENT_ID = "event_id"
        private const val FIELD_EVENT_TIME = "event_time"
        private const val FIELD_EVENT_DATA = "event_data"
        private const val FIELD_CONTEXT = "context"
        private const val FIELD_TYPE = "type"
        private const val FIELD_SCENE = "scene"
        private const val FIELD_EXTRA_INFO = "extra_info"
        private const val FIELD_CHANNEL = "channel"
        private const val FIELD_RECOMMEND_INFO = "recommend_info"
        private const val FIELD_REQUEST_ID = "request_id"
        private const val FIELD_SESSION_ID = "session_id"
        private const val FIELD_CHARACTER_ID = "character_id"
        private const val FIELD_POSITION = "position"

        private const val EVENT_TYPE_SHARE = "share"
        private const val SCENE_SCREEN = "screen"
    }
}

private fun JSONObject.hasExactKeys(vararg expected: String): Boolean {
    val actual = keys().asSequence().toSet()
    return actual.size == expected.size && actual == expected.toSet()
}

private fun JSONObject.strictString(key: String): String? =
    opt(key)?.takeIf { it is String } as? String

private fun JSONObject.strictLong(key: String): Long? {
    val number = opt(key) as? Number ?: return null
    val double = number.toDouble()
    if (!double.isFinite()) return null
    val long = number.toLong()
    return long.takeIf { double == long.toDouble() }
}

private fun JSONObject.strictInt(key: String): Int? {
    val long = strictLong(key) ?: return null
    return long.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
}
