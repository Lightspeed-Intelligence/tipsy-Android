package ai.lightspeed.tipsy.shell.pages.screen

import ai.lightspeed.tipsy.shell.network.ScalarCoercion
import org.json.JSONObject

/**
 * 大屏页的一条（`toFeedMediaItem`，`feedMediaItemAdapter.ts` 57 行）。
 *
 * ## 媒体三形态的判定顺序**不能调**
 *
 * `toFeedMediaItem:15-19` 是 if/else-if 链，优先级 **video > 动图 > 静图**：
 * ```
 * greeting_video.video_url 有   → showcase        （媒体是视频）
 * 否则 animated_image_url 有    → animated_image  （媒体是动图）
 * 否则                          → static_image
 * ```
 * 而 [backgroundUrl] 的回落链是**同一个顺序**（`:20-23`）。
 * 顺序反了的表现：有视频的角色被当成静图 —— 大屏页少了主要的视觉内容，
 * 而卡片仍然渲染（拿到 image_url），**不报错**。
 *
 * ⚠️ 埋点 `card_type` 的映射是**另一套名字**（`tracking.ts:16-27`）：
 * `animated_image`→`gif`、`static_image`→`single_character`、
 * **其余（含 showcase）→`showcase`**。见 [ScreenCardType]。
 *
 * ## P1 不播视频
 *
 * `showcase` 形态本刀先显示 [thumbnailUrl] 静态封面（Media3 属 P2，§2.35）。
 * [backgroundUrl] 仍然解析出来 —— P2 接播放器时直接可用，
 * 且它决定了 [mediaType]。
 *
 * @property characterId 也是列表 stable key（`toFeedMediaItem` 的 `id`）
 * @property thumbnailUrl `greeting_video.cover_url` 优先，回落 `character.image_url`
 *   （`:34`）—— ⚠️ 与 [backgroundUrl] 的回落链**不同**，别复用
 */
data class ScreenFeedItem(
    val characterId: String,
    val mediaSourceType: ScreenMediaSourceType,
    /** 背景媒体 URL（视频 / 动图 / 静图，按三形态回落）。 */
    val backgroundUrl: String?,
    /** 封面图 —— P1 的 showcase 形态显示它。 */
    val thumbnailUrl: String?,
    /** `character.introduction`，空串而非 null（对齐 `tagline: ... || ''`）。 */
    val tagline: String,
    /** `character.greeting`。 */
    val greeting: String,
    val nickname: String?,
    val creatorId: String?,
    val creatorNickname: String?,
    val creatorAvatarUrl: String?,
    /** `character.face_url` 回落 `image_url`（`:38` 的 `character_avatars`）。 */
    val avatarUrl: String?,
    val likeCount: Long,
    val commentCount: Long,
    val totalMessages: Long,
    /** `character.img_primary_color` —— 加载中的占位底色。 */
    val primaryColor: String?,
    val isTranslated: Boolean,
    /** `character.character_type`：CTA 四路分流要用（1/2 有特殊含义）。 */
    val characterType: Int?,
    /** `character.content_type`：同上（`type===1 && content===2` → html）。 */
    val contentType: Int?,
    /** 推荐归因；distribution 端点恒 null（见 [ScreenAttribution]）。 */
    val attribution: ScreenAttribution? = null,
) {
    /** 埋点 `card_type`（映射是另一套名字，见类注释）。 */
    val cardType: ScreenCardType get() = ScreenCardType.of(mediaSourceType)

    /** 媒体是否为视频（`media_type: greeting_video ? 'video' : 'image'`）。 */
    val isVideo: Boolean get() = mediaSourceType == ScreenMediaSourceType.SHOWCASE

    companion object {

        /**
         * 解析一条。
         *
         * `character.character_id` 缺失返回 null —— 没有 id 的条目无法去重、
         * 无法归因、点击也没有目标（对齐
         * `recommendationAttribution.ts:46` 的 `if (!characterId) continue`）。
         */
        fun parse(json: JSONObject): ScreenFeedItem? {
            val character = json.optJSONObject(FIELD_CHARACTER) ?: return null
            val characterId = ScalarCoercion.optString(character, FIELD_CHARACTER_ID)
                ?.takeIf { it.isNotBlank() } ?: return null
            val creator = json.optJSONObject(FIELD_CREATOR)
            val stats = json.optJSONObject(FIELD_STATS)
            val greetingVideo = json.optJSONObject(FIELD_GREETING_VIDEO)

            val videoUrl = greetingVideo
                ?.let { ScalarCoercion.optString(it, FIELD_VIDEO_URL) }
                ?.takeIf { it.isNotBlank() }
            val animatedUrl = ScalarCoercion.optString(character, FIELD_ANIMATED_IMAGE_URL)
                ?.takeIf { it.isNotBlank() }
            val imageUrl = ScalarCoercion.optString(character, FIELD_IMAGE_URL)
                ?.takeIf { it.isNotBlank() }

            // ⚠️ 三形态判定与背景回落是**同一顺序**，见类注释
            val sourceType = when {
                videoUrl != null -> ScreenMediaSourceType.SHOWCASE
                animatedUrl != null -> ScreenMediaSourceType.ANIMATED_IMAGE
                else -> ScreenMediaSourceType.STATIC_IMAGE
            }
            val faceUrl = ScalarCoercion.optString(character, FIELD_FACE_URL)
                ?.takeIf { it.isNotBlank() }

            return ScreenFeedItem(
                characterId = characterId,
                mediaSourceType = sourceType,
                backgroundUrl = videoUrl ?: animatedUrl ?: imageUrl,
                // ⚠️ 封面的回落链与背景**不同**：cover_url → image_url
                thumbnailUrl = greetingVideo
                    ?.let { ScalarCoercion.optString(it, FIELD_COVER_URL) }
                    ?.takeIf { it.isNotBlank() }
                    ?: imageUrl,
                tagline = ScalarCoercion.optString(character, FIELD_INTRODUCTION).orEmpty(),
                greeting = ScalarCoercion.optString(character, FIELD_GREETING).orEmpty(),
                nickname = ScalarCoercion.optString(character, FIELD_NICKNAME)
                    ?.takeIf { it.isNotBlank() },
                // `creator.user_id` 优先，回落 `character.creator_id`（`:36`）
                creatorId = creator?.let { ScalarCoercion.optString(it, FIELD_USER_ID) }
                    ?.takeIf { it.isNotBlank() }
                    ?: ScalarCoercion.optString(character, FIELD_CREATOR_ID)
                        ?.takeIf { it.isNotBlank() },
                creatorNickname = creator
                    ?.let { ScalarCoercion.optString(it, FIELD_NICKNAME) }
                    ?.takeIf { it.isNotBlank() },
                creatorAvatarUrl = creator
                    ?.let { ScalarCoercion.optString(it, FIELD_AVATAR_URL) }
                    ?.takeIf { it.isNotBlank() },
                avatarUrl = faceUrl ?: imageUrl,
                likeCount = stats?.let { ScalarCoercion.optLong(it, FIELD_LIKE_COUNTS) } ?: 0L,
                commentCount = stats?.let { ScalarCoercion.optLong(it, FIELD_COMMENT_COUNT) } ?: 0L,
                totalMessages = stats?.let { ScalarCoercion.optLong(it, FIELD_TOTAL_MESSAGES) }
                    ?: 0L,
                primaryColor = ScalarCoercion.optString(character, FIELD_IMG_PRIMARY_COLOR)
                    ?.takeIf { it.isNotBlank() },
                isTranslated = ScalarCoercion.optBoolean(character, FIELD_IS_TRANSLATED) ?: false,
                characterType = ScalarCoercion.optInt(character, FIELD_CHARACTER_TYPE),
                contentType = ScalarCoercion.optInt(character, FIELD_CONTENT_TYPE),
            )
        }

        private const val FIELD_CHARACTER = "character"
        private const val FIELD_CREATOR = "creator"
        private const val FIELD_STATS = "stats"
        private const val FIELD_GREETING_VIDEO = "greeting_video"
        private const val FIELD_CHARACTER_ID = "character_id"
        private const val FIELD_VIDEO_URL = "video_url"
        private const val FIELD_COVER_URL = "cover_url"
        private const val FIELD_ANIMATED_IMAGE_URL = "animated_image_url"
        private const val FIELD_IMAGE_URL = "image_url"
        private const val FIELD_FACE_URL = "face_url"
        private const val FIELD_INTRODUCTION = "introduction"
        private const val FIELD_GREETING = "greeting"
        private const val FIELD_NICKNAME = "nickname"
        private const val FIELD_USER_ID = "user_id"
        private const val FIELD_CREATOR_ID = "creator_id"
        private const val FIELD_AVATAR_URL = "avatar_url"
        private const val FIELD_LIKE_COUNTS = "like_counts"
        private const val FIELD_COMMENT_COUNT = "comment_count"
        private const val FIELD_TOTAL_MESSAGES = "total_messages"
        private const val FIELD_IMG_PRIMARY_COLOR = "img_primary_color"
        private const val FIELD_IS_TRANSLATED = "is_translated"
        private const val FIELD_CHARACTER_TYPE = "character_type"
        private const val FIELD_CONTENT_TYPE = "content_type"
    }
}

/** 媒体形态（`media_source_type`）。**值是接口契约**，也进埋点。 */
enum class ScreenMediaSourceType(val wire: String) {
    SHOWCASE("showcase"),
    ANIMATED_IMAGE("animated_image"),
    STATIC_IMAGE("static_image"),
}

/**
 * 埋点 `card_type`（`getHomeCardType`，`tracking.ts:16-27`）。
 *
 * ⚠️ **与 [ScreenMediaSourceType] 不同名**：`animated_image`→`gif`、
 * `static_image`→`single_character`、其余→`showcase`。
 * 直接把 `media_source_type` 发进埋点会让 `card_type` 与现网对不上，
 * 而那是同一个漏斗里的字段。
 */
enum class ScreenCardType(val wire: String) {
    SHOWCASE("showcase"),
    GIF("gif"),
    SINGLE_CHARACTER("single_character"),
    ;

    companion object {
        fun of(sourceType: ScreenMediaSourceType): ScreenCardType = when (sourceType) {
            ScreenMediaSourceType.ANIMATED_IMAGE -> GIF
            ScreenMediaSourceType.STATIC_IMAGE -> SINGLE_CHARACTER
            // 「其余」—— RN 是 default 分支，不是显式匹配 showcase
            ScreenMediaSourceType.SHOWCASE -> SHOWCASE
        }
    }
}
