package ai.lightspeed.tipsy.shell.pages.screen.share

import ai.lightspeed.tipsy.shell.pages.screen.ScreenAttribution
import ai.lightspeed.tipsy.shell.share.TipsyShareChannel
import ai.lightspeed.tipsy.shell.share.TipsyShareContent

internal enum class ScreenShareModerationState {
    CHECKING,
    ALLOWED,
    BLOCKED,
}

internal enum class ScreenShareMessageType {
    INFO,
    ERROR,
}

/** Fragment 持有的一次分享会话；content/attribution 都是点按钮时冻结的快照。 */
internal data class ScreenShareUiState(
    val content: TipsyShareContent? = null,
    val attribution: ScreenAttribution? = null,
    val moderation: ScreenShareModerationState = ScreenShareModerationState.CHECKING,
    val channels: List<TipsyShareChannel> = emptyList(),
    val busyChannel: TipsyShareChannel? = null,
    val messageKey: String? = null,
    val messageType: ScreenShareMessageType = ScreenShareMessageType.INFO,
    /** 同一句提示连续触发时也递增，让 UI 重新计算自动消失时长。 */
    val messageSequence: Long = 0L,
) {
    val visible: Boolean get() = content != null
}

/** RN 的 Copy handler 不读 isSharing；外部保存中仍可复制，只有外部→外部要互斥。 */
internal fun ScreenShareUiState.canStartChannel(channel: TipsyShareChannel): Boolean =
    channel == TipsyShareChannel.COPY_LINK || busyChannel == null
