package ai.lightspeed.tipsy.shell.pages.screen.share

import ai.lightspeed.tipsy.shell.pages.screen.ScreenFeedItem
import ai.lightspeed.tipsy.shell.share.TipsyShareContent
import ai.lightspeed.tipsy.shell.share.TipsyShareMediaType

/**
 * 冻结用户点下分享时的卡片快照。
 *
 * RN 的 Screen adapter 当前把 `character_id` 同时用作 Reel 路径 id；这里用
 * `reelRouteId` 明确记录这个兼容事实。feed 没有真实 video-service id，因此
 * [TipsyShareContent.videoId] 必须保持 null，不能拿 characterId 冒充后调用
 * `/video-service/video/share`。
 */
internal fun ScreenFeedItem.toTipsyShareContent(): TipsyShareContent? {
    val sourceUrl = backgroundUrl?.takeIf { it.isNotBlank() } ?: return null
    return TipsyShareContent(
        characterId = characterId,
        reelRouteId = characterId,
        videoId = null,
        mediaUrl = sourceUrl,
        mediaType = if (isVideo) TipsyShareMediaType.VIDEO else TipsyShareMediaType.IMAGE,
        thumbnailUrl = thumbnailUrl,
        characterName = nickname,
    )
}
