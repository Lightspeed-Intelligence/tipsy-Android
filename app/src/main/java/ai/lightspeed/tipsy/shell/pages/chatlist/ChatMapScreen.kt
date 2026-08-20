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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
    onThreadClick: (ChatThread) -> Unit,
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

        // ⚠️ 楼层动画曲线的横轴基准用**窗口高**（RN `useWindowDimensions().height`，
        // iOS 端口同），不是列表容器高 —— 两者差一个顶栏 + tabbar。
        // `containerSize` 即 RN window 的对等物（lint 点名弃用
        // Configuration.screenHeightDp：insets 语义随 targetSdk 变且有取整）
        val windowHeightDp = with(density) {
            androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp().value
        }

        val rowHeightDp = ChatMapGeometry.rowHeightDp(listHeightDp)
        val cardWidthDp = remember(windowWidthDp) { ChatMapGeometry.cardWidthDp(windowWidthDp) }
        val cardHeightDp = remember(windowWidthDp) { ChatMapGeometry.cardHeightDp(windowWidthDp) }

        val rowHeight = rowHeightDp.dp
        val cardWidth = cardWidthDp.dp
        val cardHeight = cardHeightDp.dp

        // 纵向滚动量（dp）—— 廊道整体的滚动，决定每层的 currIndex 与模式。
        // ⚠️ **符号约定对齐 RN/iOS：初始 0，手指下拉（看更早）→ 变负**
        // （iOS `ChatMapView.swift:283`：`scrollY = contentOffset.y - maxOffset`，
        // RN onScroll 是 `-offsetY`）。搞反的表现是滚动方向反 + 曲线整段错位。
        // 用 Animatable：RN 侧纵向有 `snapToInterval={height}`（一层一档吸附），
        // 松手要走惯性 + 吸附动画
        val scope = rememberCoroutineScope()
        val scrollY = remember { Animatable(0f) }
        val scrollYDp = scrollY.value
        // 行程 = contentSize − 视口（iOS：contentView 高 rowHeight*N，视口装 3 层）
        // = rowHeight * (N - 3)。按 N-1 算会让最后几档滚出全空屏
        val minScrollYDp = -((floors.size - VISIBLE_FLOORS).coerceAtLeast(0) * rowHeightDp)

        // ⚠️ 固定横轴只在 baseX 变化时重建：`solve()` 每帧每卡各调一次，
        // 不复用会每帧产生数十个短命数组（低端机滚动 GC 抖动）
        val baseXDp = remember(windowWidthDp) { ChatMapGeometry.baseXDp(windowWidthDp) }
        val stops = remember(baseXDp) { ChatMapCardLayout.stopsFor(baseXDp) }

        // ⚠️ 纵向手势挂在楼层的**祖先容器**上，楼层是它的 children ——
        // 不能做成垫底的 sibling 层：Compose 的 hit-test 对 sibling 不共享
        // 指针（sharePointerInputWithSiblings 默认 false），楼层（zIndex
        // 96~100）几乎铺满容器，垫底 sibling 永远收不到事件，表现是
        // 「怎么拖都不滚」。祖先链上的 pointerInput 则总在 hit path 里；
        // 卡叠的横滑/点击在子层各自消费，竖向拖动到达这里，两轴互不抢
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(minScrollYDp, rowHeightDp) {
                    val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = { tracker.resetTracking() },
                        onDragEnd = {
                            val vyDp = tracker.calculateVelocity().y.toDp().value
                            scope.launch {
                                // snapToInterval 对等：惯性投影 → 吸到最近档。
                                // 目标档按「衰减后的静止位置」取整 —— 直接用
                                // animateDecay 会停在层间，RN 的 FlatList 不会
                                val decay = exponentialDecay<Float>(
                                    frictionMultiplier = ChatMapSnap.DECELERATION,
                                )
                                val projected = decay.calculateTargetValue(
                                    scrollY.value,
                                    // 下拉（vy>0）→ scrollY 变负，与 onDrag 同向
                                    -vyDp,
                                )
                                val target = (Math.round(projected / rowHeightDp) * rowHeightDp)
                                    .coerceIn(minScrollYDp, 0f)
                                scrollY.animateTo(target, tween(SNAP_DURATION_MS))
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                val target = (Math.round(scrollY.value / rowHeightDp) * rowHeightDp)
                                    .coerceIn(minScrollYDp, 0f)
                                scrollY.animateTo(target, tween(SNAP_DURATION_MS))
                            }
                        },
                    ) { change, dragAmountPx ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        // ⚠️ px → dp：手势给的是 px，solver 全链是 dp。
                        // 下拉（delta>0）→ scrollY 变负（看更早），对齐 iOS 符号
                        val deltaDp = dragAmountPx.toDp().value
                        scope.launch {
                            scrollY.snapTo(
                                (scrollY.value - deltaDp).coerceIn(minScrollYDp, 0f),
                            )
                        }
                    }
                },
        ) {
            // 楼层动画曲线（floorHeight 是曲线横轴基准，≠ rowHeight，见 Geometry）。
            // 样条构造有解三对角的成本，只在窗口尺寸变化时重建
            val floorHeightDp = remember(windowHeightDp) {
                ChatMapGeometry.floorHeightDp(windowHeightDp)
            }
            val translateXSpline = remember(floorHeightDp) {
                CubicSpline(
                    ChatMapGeometry.translateXInputDp(floorHeightDp),
                    ChatMapGeometry.TRANSLATE_X_OUTPUT,
                )
            }
            val scaleSpline = remember(floorHeightDp) {
                CubicSpline(
                    ChatMapGeometry.scaleInputDp(floorHeightDp),
                    ChatMapGeometry.SCALE_OUTPUT,
                )
            }
            val translateYInput = remember(floorHeightDp) {
                ChatMapGeometry.translateYInputDp(floorHeightDp)
            }

            floors.forEachIndexed { index, floor ->
                // 楼层模式与展开偏移：currIndex 决定 1/3/5 模式
                val currIndex = ChatMapFloors.currIndexFor(scrollYDp, rowHeightDp, index)
                // 可见范围 [-1, 3] 之外整层不 compose（RN scale 0 / iOS isHidden 等价；
                // 不画就不发图片请求，比 alpha 0 更接近虚拟化的目标）
                if (!ChatMapFloors.isFloorVisible(currIndex)) return@forEachIndexed

                // delta = scrollY + rowHeight*index：该层相对「视口底部档位」的
                // 名义偏移（iOS FloorView.update 的同名量）。它身兼两职：
                // 1. **物理位置** —— iOS 楼层是 scrollView cell、随滚动真实移动，
                //    Compose 对应物即 offset(-delta)（scrollY=0 时 index0 在底档、
                //    index1 在上一档…下拉后各层下移、最底层滑出被 clip 裁掉）；
                // 2. **曲线横轴** —— translateX/scale/translateY 三条曲线都吃它
                val deltaDp = scrollYDp + rowHeightDp * index
                val translateYDp = ChatMapMath.interpolate(
                    ChatMapMath.clamp(
                        deltaDp,
                        ChatMapGeometry.TRANSLATE_Y_CLAMP_LOWER_DP,
                        windowHeightDp,
                    ),
                    translateYInput,
                    ChatMapGeometry.TRANSLATE_Y_OUTPUT,
                )
                val floorTranslateXDp = translateXSpline.valueAt(
                    ChatMapMath.clamp(deltaDp, 0f, windowHeightDp),
                )
                val floorScale = scaleSpline.valueAt(
                    ChatMapMath.clamp(deltaDp, 0f, windowHeightDp),
                )

                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(rowHeight)
                        // 物理滚动位置（见 delta 注释职责 1）
                        .offset(y = -deltaDp.dp)
                        // ⚠️ **两级层序的第一级**：`100 - index`（对齐 RN/iOS）。
                        // index 0 是最新层、在最底部，**必须画在最上面**。
                        // Compose 默认按 compose 顺序绘制 → 后 compose 的远层会
                        // 盖住近层。不给 zIndex 的表现是"上面那层压着下面那层"，
                        // 卡越出 row cell 之后尤其明显
                        .zIndex(ChatMapZOrder.floorZ(index))
                        // 楼层 transform（RN `[{scale},{translateX},{translateY}]` 左乘：
                        // 平移**被 scale 缩放** —— 越远的层 scale 越小、上移量同比
                        // 收住，三层才叠得紧。graphicsLayer 的 translation 作用在
                        // 缩放后图层上，语义相反，所以这里**预乘 scale**（对齐 iOS
                        // 「先 scale 再 translatedBy」的同一结论）
                        .graphicsLayer {
                            scaleX = floorScale
                            scaleY = floorScale
                            translationX = floorTranslateXDp * floorScale * density.density
                            translationY = translateYDp * floorScale * density.density
                        }
                        .testTag("chat_map_floor_${floor.key}"),
                ) {
                // ⚠️ **每层独立的横滑状态**，key 用 canonical `floor.key`
                // （不是下标）—— 分页/跨日后下标会错配，把昨天的横滑位置
                // 复用给今天。见 ChatMapFloors.DateBucket 的 key 说明
                // ⚠️ 用 Animatable 而不是裸 Float：惯性衰减与吸附都要动画驱动。
                // 交给 Compose 的 animateDecay / animateTo —— **不自建帧循环**
                // （iOS 用 CADisplayLink 是因为 UIKit 没有声明式重算，见
                // ChatMapCardLayout 类注释）
                val scrollX = remember(floor.key) { Animatable(0f) }
                val scrollXDp = scrollX.value

                // 楼层模式与展开偏移（currIndex 已在可见性判定处算过）
                val mode = ChatMapCardLayout.floorMode(currIndex, floor.slotCount)
                val nextMode = ChatMapCardLayout.floorMode(currIndex + 1, floor.slotCount)
                val disOut = ChatMapCardLayout.floorOffsets(
                    mode = mode,
                    nextMode = nextMode,
                    // 该层相对当前档的纵向偏移（iOS：delta.rounded() - currIndex*rowHeight）
                    offsetYDp = deltaDp - currIndex * rowHeightDp,
                    windowWidthDp = windowWidthDp,
                    cardWidthDp = cardWidthDp,
                    cardHeightDp = cardHeightDp,
                )

                ChatMapFloor(
                    floor = floor,
                    // 标题淡出（currIndex >= 2，RN textStyle / iOS titleLabel.alpha）
                    isTitleHidden = ChatMapFloors.isTitleHidden(currIndex),
                    scrollXDp = scrollXDp,
                    onHorizontalDrag = { deltaDp ->
                        scope.launch { scrollX.snapTo(scrollX.value + deltaDp) }
                    },
                    onHorizontalDragEnd = { velocityDpPerSec ->
                        scope.launch {
                            // 1. 惯性衰减（对齐 RN withDecay deceleration 0.998）
                            val decay = exponentialDecay<Float>(
                                frictionMultiplier = ChatMapSnap.DECELERATION,
                            )
                            scrollX.animateDecay(velocityDpPerSec, decay)
                            // 2. 停下后吸附 —— 真实卡不足 5 张时会回绕到真实卡
                            val target = ChatMapSnap.snapTarget(
                                restX = scrollX.value,
                                baseX = baseXDp,
                                realSize = floor.items.size,
                            )
                            scrollX.animateTo(target, tween(SNAP_DURATION_MS))
                        }
                    },
                    disOut = disOut,
                    stops = stops,
                    cardWidth = cardWidth,
                    cardHeight = cardHeight,
                    messageCountText = messageCountText,
                    timeText = timeText,
                    hasUnread = hasUnread,
                    onThreadClick = onThreadClick,
                )
            }
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
    /** 标题淡出（`currIndex >= 2`，与楼层缩小同步）。 */
    isTitleHidden: Boolean,
    /** 该层的横向滚动量（dp）—— 每层独立，由调用方按 floor.key 持有。 */
    scrollXDp: Float,
    /** 横向拖动增量（dp）。 */
    onHorizontalDrag: (Float) -> Unit,
    /** 松手速度（dp/s）—— 调用方做惯性 + 吸附。 */
    onHorizontalDragEnd: (Float) -> Unit,
    /** 该层的楼层模式偏移（`floorOffsets` 产出，5 个 dp 值）。 */
    disOut: FloatArray,
    /** 复用的插值横轴，见 [ChatMapCardLayout.Stops]。 */
    stops: ChatMapCardLayout.Stops,
    cardWidth: androidx.compose.ui.unit.Dp,
    cardHeight: androidx.compose.ui.unit.Dp,
    messageCountText: (ChatThread) -> String,
    timeText: (ChatThread) -> String,
    hasUnread: (ChatThread) -> Boolean,
    onThreadClick: (ChatThread) -> Unit,
) {
    // ⚠️ **不用受限的 Column** —— 见下面 gap 那段。
    // 楼层内部布局：标题占 18dp、gap 10dp、然后是**完整**卡高。
    // 三者之和可能超过 rowHeight（`listHeight/3`），RN/iOS 都允许卡越出
    // floor cell（靠外层裁剪），所以这里也不去压缩它。
    Box(modifier = Modifier.fillMaxWidth()) {
        // 标题（跑道层没有标题）
        if (floor.kind == ChatMapFloors.FloorKind.CHAT) {
            // 淡出用 animateFloatAsState（RN 是 withTiming）；不移除节点 ——
            // 标题占位参与卡叠 offset，移除会让卡跳位
            val titleAlpha by animateFloatAsState(
                targetValue = if (isTitleHidden) 0f else 1f,
                label = "floorTitleAlpha",
            )
            Text(
                text = floor.title,
                color = ChatMapStyle.floorTitleColor,
                fontSize = ChatMapStyle.FLOOR_TITLE_FONT_SP.sSp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ChatMapStyle.FLOOR_TITLE_HEIGHT_DP.dp)
                    .graphicsLayer { alpha = titleAlpha }
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
                // 横向拖动 + 松手惯性/吸附。⚠️ 只挂在卡叠上，不挂整层 ——
                // 挂整层会和纵向拖动抢事件
                .pointerInput(floor.key) {
                    // ⚠️ 用 VelocityTracker 取真实松手速度 —— 传 0 等于**关掉惯性**，
                    // 那样 `withDecay(0.998)` 的对等就只是个摆设：手指一松立刻吸附，
                    // 没有 RN 那种滑一下能连过好几张的手感
                    val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = { tracker.resetTracking() },
                        onDragEnd = {
                            // px/s → dp/s
                            val vxDp = tracker.calculateVelocity().x.toDp().value
                            onHorizontalDragEnd(vxDp)
                        },
                        onDragCancel = { onHorizontalDragEnd(0f) },
                    ) { change, dragAmountPx ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        // ⚠️ px → dp（solver 全链 dp）
                        onHorizontalDrag(dragAmountPx.toDp().value)
                    }
                }
                .height(cardHeight),
            contentAlignment = Alignment.Center,
        ) {
            // 阶段二：卡片 transform 来自 solver。
            // slotCount 已含补位；真实卡不足时后面的槽渲染占位卡
            repeat(floor.slotCount) { slot ->
                val thread = floor.items.getOrNull(slot)
                val t = ChatMapCardLayout.solve(
                    index = slot,
                    scrollX = scrollXDp,
                    itemCount = floor.slotCount,
                    stops = stops,
                    disOut = disOut,
                )
                // 超出可见弧段的卡不绘制（对齐 RN 的 `scale: 0`）
                if (!t.visible) return@repeat

                val cardModifier = Modifier
                    // ⚠️ 用 **solver 的 zIndex**（阶段一那套静态倒序已被取代）——
                    // 它按 scale 分档，保证中间那张最大的画在最上
                    .zIndex(t.zIndex)
                    // 槽位级 tag：测试要能逐槽取 bounds
                    .testTag(cardSlotTag(floor.key, slot))
                    .graphicsLayer {
                        scaleX = t.scale
                        scaleY = t.scale
                        // ⚠️ **dp → px 的唯一出口**：solver 全链是 dp，
                        // 而 `translationX` 收 px。`density` 来自
                        // graphicsLayer 的 scope，不要在外面用 LocalDensity 再转一次
                        translationX = t.translateX * density
                        // ⚠️ RN 是 `[{translateX},{scale}]` —— 平移**不被缩放放大**。
                        // graphicsLayer 的 translationX 作用在缩放后的图层上，
                        // 语义一致（见 ChatMapCardLayout 类注释）
                    }
                    .size(width = cardWidth, height = cardHeight)
                if (thread == null) {
                    ChatMapPlaceholderCard(modifier = cardModifier)
                } else {
                    ChatMapCard(
                        thread = thread,
                        messageCountText = messageCountText(thread),
                        timeText = timeText(thread),
                        hasUnread = hasUnread(thread),
                        onClick = { onThreadClick(thread) },
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

/** 吸附动画时长（对齐 RN `withTiming(..., { duration: 300 })`）。 */
private const val SNAP_DURATION_MS = 300

/** 视口同时装的楼层数（`rowHeight = listHeight/3` 的那一个 3）。 */
private const val VISIBLE_FLOORS = 3
