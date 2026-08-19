package ai.lightspeed.tipsy.shell.pages.chatlist

import ai.lightspeed.tipsy.shell.ui.sSp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
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
 * ⛔ **不含 solver 驱动的动态 transform**（scale/translateX/动态 zIndex）、
 * 不含 pan/惯性/吸附、不含动图按可见性开关 —— 那些分别是阶段二、三。
 *
 * ⚠️ 当前**已有静态兜底层序**（[ChatMapZOrder]）：楼层 `100-index`、
 * 卡片 `slotCount-slot`。它不是 solver 值，只是防"远层盖近层""占位盖真卡"
 * 这两个当前就可见的问题；阶段二接上 solver 的 zIndex 后替换。
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
 * 这里用逐层 `offset` 复刻同一几何：
 * - 层高恒 `rowHeight = listHeight / 3`（⚠️ Android **没有** `/2` 分支，
 *   见 [ChatMapGeometry.rowHeight]）；
 * - `clipToBounds` 对齐 RN 的 `overflow: hidden`。
 *
 * ## ⚠️ 楼层数**不是**小常数 —— 虚拟化是下一刀的必修项
 *
 * 我早前写过"楼层数恒为小常数（通常 5）"作为不虚拟化的理由 —— **那是错的**：
 * 所有日期桶都保留（`ChatMapFloors.build` 不截断），首 50 条会话若跨 50 天
 * 就是 50 + 2 = **52 层**全量组合。而 `clipToBounds` **不会阻止**
 * 被裁掉的 `AsyncImage` 发起网络请求。
 *
 * 当前实现仍是全量 `forEach` + `repeat`，**已知性能缺口**，
 * 与层序 / Compose key / 横滑状态外置一起在下一刀收口
 * （虚拟化会改变 per-floor 状态的持有方式，所以必须一起做）。
 * RN 侧明确设了 floor 的 `windowSize/initialNumToRender/maxToRenderPerBatch = 3`。
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
        val density = androidx.compose.ui.platform.LocalDensity.current

        // ⚠️ **px → dp 在这里做完**：`ChatMapGeometry` 的所有量都是 dp
        // （RN 的 `useWindowDimensions` 单位等价于 dp；常量 300 / -180 /
        // 样条输出全是 dp 数值）。
        //
        // 纯乘的那几个（cardWidth/baseX）传 px 恰好等价，但
        // `floorHeightDp` 带常量 300、`rowHeightDp` 带 round，
        // **都不可交换** —— 见 ChatMapGeometry 的单位契约段。
        val listHeightDp = with(density) { constraints.maxHeight.toDp().value }
        val windowWidthDp = with(density) { constraints.maxWidth.toDp().value }

        val rowHeightDp = ChatMapGeometry.rowHeightDp(listHeightDp)
        val cardWidthDp = remember(windowWidthDp) { ChatMapGeometry.cardWidthDp(windowWidthDp) }
        val cardHeightDp = remember(windowWidthDp) { ChatMapGeometry.cardHeightDp(windowWidthDp) }

        val rowHeight = rowHeightDp.dp
        val cardWidth = cardWidthDp.dp
        val cardHeight = cardHeightDp.dp

        floors.forEachIndexed { index, floor ->
            // 倒序：index 0（最新）在最底部
            val fromBottom = index
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(rowHeight)
                    .offset(y = -(rowHeight * fromBottom))
                    // ⚠️ **两级层序的第一级**：`100 - index`（对齐 RN/iOS）。
                    // index 0 是最新层、在最底部，**必须画在最上面**。
                    // Compose 默认按 compose 顺序绘制 → 后 compose 的远层会
                    // 盖住近层。不给 zIndex 的表现是"上面那层压着下面那层"，
                    // 卡越出 row cell 之后尤其明显
                    .zIndex(ChatMapZOrder.floorZ(index))
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
    // ⚠️ **不用受限的 Column** —— 见下面 gap 那段。
    // 楼层内部布局：标题占 18dp、gap 10dp、然后是**完整**卡高。
    // 三者之和可能超过 rowHeight（`listHeight/3`），RN/iOS 都允许卡越出
    // floor cell（靠外层裁剪），所以这里也不去压缩它。
    Box(modifier = Modifier.fillMaxWidth()) {
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
                // ⚠️ **modifier 顺序是语义的一部分，不能重排**：
                //
                //   offset → wrapContentHeight(Top, unbounded) → testTag → height
                //
                // 1. `offset(title + gap)`：gap 必须在 `height` **之外**。
                //    写成 `height(x).padding(top = gap)` 的语义是「总高 x，
                //    其中 gap 是内边距」→ 卡片只拿到 `x - gap`，比例 0.75 变 0.78，
                //    而 solver 仍按原值算 `offsetY/cardHeight` —— 测量与解算分叉。
                //
                // 2. `wrapContentHeight(Alignment.Top, unbounded = true)`
                //    **必须在 `height` 之前**（链上更靠外）：它先截住父传下来的
                //    `maxHeight = rowHeight`，把 `Infinity` 传给内层 `height`，
                //    让 `height` 真的拿到 `cardHeight`；对父仍上报 rowHeight，
                //    但按 `Top` 把超出的 child 放在 y=0。
                //
                //    ⚠️ **不能反过来写 `height(x).wrapContentHeight(...)`**：那样外层
                //    `height` 先收到父 max=rowHeight 被约束成 rowHeight，内层再
                //    上报 cardHeight 就是"违约"，Compose 会把它**居中补偿** ——
                //    顶部上移**半个溢出量**。实测（360×640dp、density 2.75）：
                //    `(230.4-213)/2 = 8.7dp`，offset=0 时 card_row 落在楼层顶
                //    **上方 24px**，加上 28dp offset 后 top 变成 19.27 而非 28。
                //    `align(TopStart)` 修不了 —— 它只对齐父看到的那层外壳。
                //
                // 3. `testTag` **必须在 wrap 内侧**：放外侧只能读到对父上报的
                //    rowHeight，读不到真实的 cardHeight。
                //
                // 为什么需要这一整套：外层 floor 是 `height(rowHeight)`，
                // 而普通 `height(cardHeight)` 只是 *preferred* —— 当
                // `cardHeight > rowHeight` 时会被父 `maxHeight` 压回去。
                // 实测触发档 360×640dp：卡高 230.4 > 行高 213。
                // 越出部分由最外层 `clipToBounds` 裁掉（对齐 RN `overflow: hidden`）。
                .offset(
                    y = ChatMapStyle.FLOOR_TITLE_HEIGHT_DP.dp +
                        ChatMapStyle.FLOOR_TITLE_BOTTOM_GAP_DP.dp,
                )
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .testTag(cardRowTag(floor.key))
                .height(cardHeight),
            contentAlignment = Alignment.Center,
        ) {
            // 阶段一：卡片居中重叠铺位（transform 属阶段二）。
            // slotCount 已含补位；真实卡不足时后面的槽渲染占位卡
            repeat(floor.slotCount) { slot ->
                val thread = floor.items.getOrNull(slot)
                // ⚠️ **两级层序的第二级**：卡片的 zIndex。
                // 阶段一没有 solver 值可用（transform 属阶段二），先用
                // **倒序**保证真实卡不被后面的占位卡盖住 ——
                // `repeat` 是升序 compose，占位卡排在真实卡之后，
                // 不给 zIndex 时「1 真卡 + 4 占位」会让占位把真卡完全遮掉。
                // 阶段二接上 `ChatMapCardLayout.solve().zIndex` 后替换这里。
                val cardModifier = Modifier
                    .zIndex(ChatMapZOrder.cardZ(slot, floor.slotCount))
                    // 槽位级 tag：z-order 测试要能逐槽取 bounds/层序
                    .testTag(cardSlotTag(floor.key, slot))
                    .size(width = cardWidth, height = cardHeight)
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

/**
 * 卡叠容器的 testTag —— 测量测试用它取真实 bounds。
 *
 * ⚠️ **必须带 `floor.key`**：一次 build 会产出 3 个 CHAT 层 + 2 个 RUNWAY，
 * 用同一个静态 tag 会让 `onNodeWithTag` 匹配到多个节点而失败
 * （实测语义树里 5 个同名节点）。
 */
internal fun cardRowTag(floorKey: String): String = "chat_map_card_row_$floorKey"

/** 单个卡槽的 testTag —— z-order 测试按槽位取节点。 */
internal fun cardSlotTag(floorKey: String, slot: Int): String = "chat_map_slot_${floorKey}_$slot"


