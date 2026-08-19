package ai.lightspeed.tipsy.shell.pages.chatlist

import ai.lightspeed.tipsy.shell.ui.sSp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text

/**
 * Map「時光長廊」（W3-P2 **阶段一：静态结构**）。
 *
 * ## 本阶段做什么 / 不做什么
 *
 * ✅ 楼层倒序铺排 + 裁剪、楼层标题、卡叠的静态铺位、占位卡。
 * ⛔ **不含 transform**（scale/translateX/zIndex）、不含 pan/惯性/吸附、
 * 不含动图按可见性开关 —— 那些分别是阶段二、三。
 *
 * 所以本阶段**画面是"静止的廊道"**：楼层与卡片都在正确位置，但不会随滚动变形。
 * 这样拆是为了让每一层能单独复审，而不是一次交一个巨包。
 *
 * ## 倒序铺排（对齐 RN inverted FlatList）
 *
 * RN 用 `inverted` FlatList：**最新在底、越往上越远**。iOS 端口的做法是
 * 把楼层铺在 `scrollView.contentView` 的 `y = (N-1-index) * rowHeight`
 * 并靠 `clipsToBounds` 裁掉滑出底部的层。
 *
 * 这里用 `Column` + 逐层 `offset` 复刻同一几何：
 * - 层高恒 `rowHeight = listHeight / 3`（⚠️ Android **没有** `/2` 分支，
 *   见 [ChatMapGeometry.rowHeight]）；
 * - `clipToBounds` 对齐 RN 的 `overflow: hidden`。
 *
 * ⚠️ **刻意不用 `LazyColumn`**：楼层数恒为「真实组 + 补齐 + 2 跑道」的小常数
 * （通常 5），懒加载没有收益，而 `LazyColumn` 的回收会让阶段二的
 * per-floor 横滑状态更难保。
 */
@Composable
internal fun ChatMapScreen(
    floors: List<ChatMapFloors.Floor<ChatThread>>,
    messageCountText: (ChatThread) -> String,
    timeText: (ChatThread) -> String,
    hasUnread: (ChatThread) -> Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds() // 对齐 RN overflow: hidden
            .testTag("chat_map"),
    ) {
        val listHeightPx = constraints.maxHeight
        val rowHeightPx = ChatMapGeometry.rowHeight(listHeightPx)
        val widthPx = constraints.maxWidth

        // 固定几何一次算：卡尺寸与 baseX 只随宽度变
        val cardWidthDp = remember(widthPx) { ChatMapGeometry.cardWidth(widthPx) }
        val cardHeightDp = remember(widthPx) { ChatMapGeometry.cardHeight(widthPx) }

        val density = androidx.compose.ui.platform.LocalDensity.current
        val rowHeightDp = with(density) { rowHeightPx.toDp() }
        val cardWidth = with(density) { cardWidthDp.toDp() }
        val cardHeight = with(density) { cardHeightDp.toDp() }

        floors.forEachIndexed { index, floor ->
            // 倒序：index 0（最新）在最底部
            val fromBottom = index
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(rowHeightDp)
                    .offset(y = -(rowHeightDp * fromBottom))
                    .testTag("chat_map_floor_${floor.key}"),
            ) {
                ChatMapFloor(
                    floor = floor,
                    cardWidth = cardWidth,
                    cardHeight = cardHeight,
                    messageCountText = messageCountText,
                    timeText = timeText,
                    hasUnread = hasUnread,
                )
            }
        }
    }
}

/**
 * 一层 = 标题 + 横向卡叠。
 *
 * ⚠️ 跑道层（[ChatMapFloors.FloorKind.RUNWAY]）**零张卡**、只占高度 ——
 * 与"补齐出来的 chat 层"（0 真实会话但要铺 5 张剪影）**不是一回事**。
 * 这个区分由 [ChatMapFloors.Floor.slotCount] 给出，见那里的注释。
 */
@Composable
private fun ChatMapFloor(
    floor: ChatMapFloors.Floor<ChatThread>,
    cardWidth: androidx.compose.ui.unit.Dp,
    cardHeight: androidx.compose.ui.unit.Dp,
    messageCountText: (ChatThread) -> String,
    timeText: (ChatThread) -> String,
    hasUnread: (ChatThread) -> Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 标题（跑道层没有标题）
        if (floor.kind == ChatMapFloors.FloorKind.CHAT) {
            Text(
                text = floor.title,
                color = ChatMapStyle.floorTitleColor,
                fontSize = ChatMapStyle.FLOOR_TITLE_FONT_SP.sSp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ChatMapStyle.FLOOR_TITLE_HEIGHT_DP.dp)
                    .testTag("chat_map_floor_title"),
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .padding(top = ChatMapStyle.FLOOR_TITLE_BOTTOM_GAP_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 阶段一：卡片居中重叠铺位（transform 属阶段二）。
            // slotCount 已含补位；真实卡不足时后面的槽渲染占位卡
            repeat(floor.slotCount) { slot ->
                val thread = floor.items.getOrNull(slot)
                val cardModifier = Modifier.size(width = cardWidth, height = cardHeight)
                if (thread == null) {
                    ChatMapPlaceholderCard(modifier = cardModifier)
                } else {
                    ChatMapCard(
                        thread = thread,
                        messageCountText = messageCountText(thread),
                        timeText = timeText(thread),
                        hasUnread = hasUnread(thread),
                        modifier = cardModifier,
                    )
                }
            }
        }
    }
}
