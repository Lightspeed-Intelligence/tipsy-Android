package ai.lightspeed.tipsy.shell.pages.chatlist

import androidx.compose.ui.graphics.Color

/**
 * ChatList 的样式常量（RN `index.tsx` / `ChatListItem.tsx` 的 ScaledSheet 数值）。
 *
 * dp 数值走 `ui.s()` 缩放（同 Home/Profile 的 ScaledSheet 对应物），
 * 这里只存设计稿原始值。颜色是字面量照抄 —— RN 侧没有主题 token。
 */
internal object ChatListStyle {

    // ── 顶栏（TipsyHeaderLayout, index.tsx:286-329）─────────
    const val HEADER_HEIGHT = 44
    const val HEADER_PADDING_H = 12
    const val HEADER_TITLE_FONT = 16
    const val TOGGLE_ICON = 32
    const val TOGGLE_RADIUS = 32
    const val TOGGLE_HEIGHT = 32
    const val TOGGLE_PADDING_H = 6
    const val SPLIT_LINE_HEIGHT = 16.5f
    const val BELL_DOT = 6

    val headerTitleColor = Color.White
    val toggleBackground = Color(0x33000000) // rgba(0,0,0,0.2)
    val splitLineColor = Color(0x0DFFFFFF) // rgba(255,255,255,0.05)
    val bellDotColor = Color(0xFFF35757)

    // ── 行（ChatListItem.tsx styles）───────────────────────
    const val ROW_PADDING_H = 16
    const val ROW_GAP = 24 // ItemSeparator height（ChatGrid listProps）
    const val AVATAR = 48
    const val AVATAR_MARGIN_R = 12
    const val NAME_FONT = 15
    const val LAST_MSG_FONT = 13
    const val TIME_FONT = 13
    const val BADGE_FONT = 10
    const val BADGE_ICON = 12
    const val CORNER_TAG = 20 // 头像右下 story/inbox 角标（sendIcon 20×20）

    val nameColor = Color.White
    val lastMessageColor = Color(0x4DFFFFFF) // rgba(255,255,255,0.3)
    val timeColor = Color(0x4DFFFFFF)
    val draftTagColor = Color(0xFFF3A231)
    val badgeBackground = Color(0x33000000)
    val pushDotColor = Color(0xFFF35757)

    // ── 左滑操作（ChatListItem.tsx:551-575）────────────────
    const val ACTION_WIDTH = 74 // 单键宽（deleteButtonInner）
    const val ACTION_ICON = 20
    const val ACTION_FONT = 10

    val deleteActionColor = Color(0xFFFF3B30)
    val pinActionColor = Color(0xFFF3A231)

    // ── 空态（index.tsx:443-461）──────────────────────────
    const val EMPTY_ICON_W = 220
    const val EMPTY_ICON_H = 127
    const val EMPTY_FONT = 12
    const val EMPTY_MAX_WIDTH = 270

    val emptyTextColor = Color(0x59FFFFFF) // rgba(255,255,255,0.35)

    // ── 删除确认弹窗（index.tsx:406-421）───────────────────
    const val DELETE_MODAL_FONT = 13

    val deleteModalTextColor = Color(0xB3FFFFFF) // rgba(255,255,255,0.7)

    /** LV 徽章文本色，按大等级 1..5（`getLevelColor`）。 */
    fun levelColor(level: Int): Color = when (level) {
        2 -> Color(0xFF72C761)
        3 -> Color(0xFFDDCD6B)
        4 -> Color(0xFFFF80D0)
        5 -> Color(0xFFF35757)
        else -> Color(0xFF71E2D6) // level 1 与兜底
    }
}
