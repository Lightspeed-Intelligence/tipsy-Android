package ai.lightspeed.tipsy.shell.pages.chatlist

/**
 * Map 卡叠单卡的**布局解算**（W3-P2）—— 纯函数，无 Compose/View 依赖。
 *
 * ## iOS 先例 → Android 映射：**架构照 iOS，机制必须换**
 *
 * iOS 的 `ChatMapCarouselView.swift`（420 行）把 RN 的 reanimated worklet
 * 重写成了 **UIKit 命令式 + `CADisplayLink` 逐帧调 `layoutCards()`**。
 *
 * ⚠️ **Compose 侧不照抄那个形态**（必要偏离）：
 * - iOS 需要 `CADisplayLink` 是因为 UIKit 没有声明式重算机制，必须手动驱动每帧；
 * - Compose 有 `graphicsLayer` + snapshot 状态：`scrollX` 变化会自动触发重组/重绘，
 *   **不需要**自建帧循环。自建反而会与 Compose 的重组时机打两套。
 *
 * 但**解算逻辑本身**（distance 环绕、scale/translateX 插值、zIndex 分档、
 * 楼层模式 1/3/5 的横向展开）是纯数学，两端一致 —— 所以抽成本文件的纯函数，
 * 由 Compose 层在绘制时按 `scrollX` 调用。这样：
 * - 数学部分**可 JVM 单测**（与 [ChatMapMath] 同策略）；
 * - 帧驱动交给 Compose，不引入第二套动画时钟。
 *
 * ## ⚠️ transform 顺序：RN 是 `[{translateX}, {scale}]`
 *
 * RN 数组**左乘合成**（`TipsyCarousel.tsx:280-287`），效果是
 * **先缩放（绕中心）再平移**，且**平移量不被缩放放大** ——
 * iOS 端口用 `CGAffineTransform(translationX:).scaledBy()` 复刻了这个序。
 *
 * Compose 侧对应：`graphicsLayer { scaleX = s; scaleY = s; translationX = tx }`
 * —— Compose 的 `translationX` 作用在**缩放之后的图层**上、以像素为单位，
 * **不**被 `scaleX` 放大，与 RN 语义一致。
 * ⚠️ 别改成 `Modifier.offset { }` 再 `.scale()` —— 那个顺序下平移会被缩放影响。
 * （注意这与楼层那层相反：楼层是 `[{scale},{translateX},{translateY}]`，
 * 平移**会**被 scale 收住，见 iOS `ChatMapFloorView` 注释。两层顺序不同，别串。）
 */
internal object ChatMapCardLayout {

    /** 一张卡的解算结果。 */
    data class CardTransform(
        /** 缩放（0 表示该卡不可见，调用方应跳过绘制）。 */
        val scale: Float,
        /** 横向平移（像素）。⚠️ 不被 scale 放大，见类注释。 */
        val translateX: Float,
        /** 绘制层级 1~5，越大越靠前。 */
        val zIndex: Float,
        /** false 时整卡不绘制（distance 超出可见弧段）。 */
        val visible: Boolean,
    )

    /**
     * 解算第 [index] 张卡。
     *
     * @param scrollX 当前横向滚动量（像素，可正可负）
     * @param itemCount 该层铺的卡数（含补齐的空占位）
     * @param baseX 见 [ChatMapGeometry.baseX]
     * @param disOut 该楼层模式下的 5 个横向偏移（由 [floorOffsets] 给出）
     */
    fun solve(
        index: Int,
        scrollX: Float,
        itemCount: Int,
        baseX: Float,
        disOut: FloatArray,
    ): CardTransform {
        if (itemCount <= 0) return HIDDEN

        val dis = baseX * itemCount
        val baseDistance = baseX * (index + 2.5f)
        // ((x % dis) + dis) % dis —— 环绕到 [0, dis)。
        // ⚠️ 两次取余不能省：Kotlin 的 % 对负数返回负值（与 JS 一致），
        // 只取一次会让 scrollX 为负时 distance 变负 → 插值落到端点，卡片挤在一起
        var distance = (scrollX + baseDistance) % dis
        distance = (distance + dis) % dis

        // 超出可见弧段：RN 返回 `transform: [{scale: 0}]`（`TipsyCarousel.tsx:178-182`）
        if (distance > baseX * 5.5f) return HIDDEN

        // i 恒为 1/3/5（楼层已裁剪）；disOut 空则退化成恒等，不崩
        if (disOut.size != CARD_SLOTS) {
            return CardTransform(scale = 1f, translateX = 0f, zIndex = 1f, visible = true)
        }

        val scale = ChatMapMath.interpolate(distance, scaleInput(baseX), ChatMapGeometry.CARD_RATIO_ARRAY)
        val translateX = ChatMapMath.interpolate(distance, distanceArray(baseX), disOut)
        return CardTransform(
            scale = scale,
            translateX = translateX,
            zIndex = zIndexFor(scale),
            visible = true,
        )
    }

    /**
     * zIndex 分档（`TipsyCarousel.tsx:264-276`，iOS `zIndex(for:)` 逐条一致）。
     *
     * ⚠️ 最后一档的阈值是 `(RATIO0 + RATIO1) * (2/3)` —— **不是** `/2`，
     * 与前两档的取中点写法不同。RN 原文如此，iOS 也照搬了；
     * "顺手改成 /2 更整齐"会改变卡片的前后遮挡关系。
     */
    fun zIndexFor(scale: Float): Float = when {
        scale >= RATIO3 -> 5f
        scale >= (RATIO2 + RATIO3) / 2f -> 4f
        scale >= (RATIO1 + RATIO2) / 2f -> 3f
        scale >= (RATIO0 + RATIO1) * (2f / 3f) -> 2f
        else -> 1f
    }

    /**
     * 楼层模式 → 5 个横向偏移（对齐 `processOne/Three/Five`）。
     *
     * @param mode 楼层模式，恒为 1 / 3 / 5（4 与 2 归 3，见 [floorMode]）
     * @param nextMode 下一层的模式（展开动画要用）
     * @param offsetY 该层的纵向偏移
     * @param cardHeight 卡高 —— ⚠️ yRatio 用的是**卡高**不是行高
     *   （对齐 `TipsyCarousel` 的 `offsetY / itemSize.height`）
     */
    fun floorOffsets(
        mode: Int,
        nextMode: Int,
        offsetY: Float,
        windowWidth: Int,
        cardWidth: Float,
        cardHeight: Float,
    ): FloatArray {
        val n5Dis1 = (windowWidth - cardWidth * RATIO1) * 0.5f
        val n5Dis2 = 0.3f * windowWidth + (0.2f - 0.5f * RATIO2) * cardWidth
        val yRatio = if (cardHeight == 0f) 0f else offsetY / cardHeight

        return when (mode) {
            // processOne（`TipsyCarousel.tsx:117-129`）：默认全 0（不展开）。
            // 只有 yRatio≠0 **且** nextMode==3 时才向 3 卡展开
            1 -> {
                if (yRatio != 0f && nextMode == 3) {
                    val d = ChatMapMath.interpolate(
                        1f - yRatio,
                        floatArrayOf(0f, 1f),
                        floatArrayOf(0f, n5Dis2),
                    )
                    floatArrayOf(-d, -d, 0f, d, d)
                } else {
                    FloatArray(CARD_SLOTS)
                }
            }

            // processThree（`:131-149`）：⚠️ 默认是 **[-n5Dis2, -n5Dis2, 0, n5Dis2, n5Dis2]**
            // 不是全 0。且有**两条**转场分支，按 nextMode 分流。
            //
            // ⚠️ 早前版本这里既忽略了 nextMode、又把默认写成"随 yRatio 从 n5Dis2 收拢到 0"
            // —— 那等于让 3 卡模式在稳定态（yRatio=0 → 收拢到 0）把卡片全叠到中间。
            // 而 RN 的稳定态恰恰是**展开**的（默认值就是展开量）。
            3 -> {
                var out = floatArrayOf(-n5Dis2, -n5Dis2, 0f, n5Dis2, n5Dis2)
                if (yRatio != 0f) {
                    when (nextMode) {
                        // 3 → 5：外侧从 n5Dis2 张到 n5Dis1
                        5 -> {
                            val d1 = ChatMapMath.interpolate(
                                yRatio,
                                floatArrayOf(1f, 0f),
                                floatArrayOf(n5Dis2, n5Dis2),
                            )
                            val d2 = ChatMapMath.interpolate(
                                yRatio,
                                floatArrayOf(1f, 0f),
                                floatArrayOf(n5Dis2, n5Dis1),
                            )
                            out = floatArrayOf(-d2, -d1, 0f, d1, d2)
                        }
                        // 3 → 1：收拢到 0
                        1 -> {
                            val d = ChatMapMath.interpolate(
                                1f - yRatio,
                                floatArrayOf(0f, 1f),
                                floatArrayOf(0f, n5Dis2),
                            )
                            out = floatArrayOf(-d, -d, 0f, d, d)
                        }
                        // 3 → 3：**保持默认展开**，不做任何收拢
                        else -> Unit
                    }
                }
                out
            }

            // processFive（`:151-164`）：默认 [-n5Dis1, -n5Dis2, 0, n5Dis2, n5Dis1]，
            // 只有 yRatio≠0 且 nextMode==3 时外侧收到 n5Dis2
            else -> {
                var out = floatArrayOf(-n5Dis1, -n5Dis2, 0f, n5Dis2, n5Dis1)
                if (yRatio != 0f && nextMode == 3) {
                    val d1 = ChatMapMath.interpolate(
                        yRatio,
                        floatArrayOf(1f, 0f),
                        floatArrayOf(n5Dis2, n5Dis2),
                    )
                    val d2 = ChatMapMath.interpolate(
                        yRatio,
                        floatArrayOf(1f, 0f),
                        floatArrayOf(n5Dis2, n5Dis1),
                    )
                    out = floatArrayOf(-d2, -d1, 0f, d1, d2)
                }
                out
            }
        }
    }

    /**
     * 楼层模式（对齐 `getIndex`）：`currIndex` → `min(itemCount, sel)`，且 4/2 归 3。
     *
     * `sel`：`currIndex ∈ {-1, 0}` → 5；`1` → 3；其余 → 1。
     */
    fun floorMode(currIndex: Int, itemCount: Int): Int {
        val sel = when (currIndex) {
            -1, 0 -> 5
            1 -> 3
            else -> 1
        }
        val m = minOf(itemCount, sel)
        return if (m == 4 || m == 2) 3 else m
    }

    /** scale 插值横轴：`baseX * [-0.5, 0.5, 1.5, 2.5, 3.5, 4.5, 5.5]`。 */
    private fun scaleInput(baseX: Float) = floatArrayOf(
        baseX * -0.5f, baseX * 0.5f, baseX * 1.5f, baseX * 2.5f,
        baseX * 3.5f, baseX * 4.5f, baseX * 5.5f,
    )

    /** translateX 插值横轴：`baseX * [0.5, 1.5, 2.5, 3.5, 4.5]`。 */
    private fun distanceArray(baseX: Float) = floatArrayOf(
        baseX * 0.5f, baseX * 1.5f, baseX * 2.5f, baseX * 3.5f, baseX * 4.5f,
    )

    private val HIDDEN = CardTransform(scale = 0f, translateX = 0f, zIndex = 0f, visible = false)

    private const val RATIO0 = 0f
    private const val RATIO1 = 0.74f
    private const val RATIO2 = 0.86f
    private const val RATIO3 = 1f

    /** 一层最多 5 张，故 disOut 恒为 5 个。 */
    private const val CARD_SLOTS = 5
}
