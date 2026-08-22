package ai.lightspeed.tipsy.shell.share

/**
 * A share destination understood by the native shell.
 *
 * [trackingId] follows the Screen recommendation contract. [storageId] follows the
 * visual-only platform ordering contract inherited from RN, where X is still stored as
 * `twitter`. Keeping the two values explicit prevents a local UI preference from leaking into
 * analytics.
 */
enum class TipsyShareChannel(
    val trackingId: String,
    val storageId: String,
) {
    COPY_LINK(trackingId = "copy_link", storageId = "copy_link"),
    DISCORD(trackingId = "discord", storageId = "discord"),
    INSTAGRAM(trackingId = "instagram", storageId = "instagram"),
    TIKTOK(trackingId = "tiktok", storageId = "tiktok"),
    X(trackingId = "x", storageId = "twitter"),
    FACEBOOK(trackingId = "facebook", storageId = "facebook"),
    ;

    companion object {
        fun fromStorageId(value: String?): TipsyShareChannel? =
            entries.firstOrNull { it.storageId == value }
    }
}

enum class TipsyShareMediaType {
    IMAGE,
    VIDEO,
}

/**
 * Immutable snapshot of the media card that was visible when the share sheet opened.
 *
 * Identity fields intentionally stay separate:
 * - [characterId] is used by character moderation;
 * - [reelRouteId] is the public Reel path segment and is currently character-backed for Screen;
 * - [videoId] is a real video-service id when one exists, and must stay null otherwise.
 *
 * In particular, callers must never rename or copy [characterId]/[reelRouteId] into [videoId]
 * merely to make the optional share-count request possible.
 */
data class TipsyShareContent(
    val characterId: String?,
    val reelRouteId: String,
    val videoId: String?,
    val mediaUrl: String,
    val mediaType: TipsyShareMediaType,
    val thumbnailUrl: String? = null,
    val characterName: String? = null,
) {
    init {
        require(reelRouteId.isNotBlank()) { "reelRouteId must not be blank" }
        require(mediaUrl.isNotBlank()) { "mediaUrl must not be blank" }
    }
}
