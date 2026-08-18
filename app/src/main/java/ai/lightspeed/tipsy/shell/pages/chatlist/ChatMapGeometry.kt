package ai.lightspeed.tipsy.shell.pages.chatlist

/**
 * Map（時光長廊）的几何与动画常量（W3-P2）。
 *
 * ## 三个「Android 恒值」分支 —— ⚠️ 不要按 iOS 补回来
 *
 * iOS 的 `ChatMapFloorView.swift` 把 `smallScreen` 当**真实分支**，据此分了
 * 三组动画参数 + 两种行高。但 RN 侧那个 flag 是
 * （`ChatMap.tsx:84`）：
 *
 * ```
 * const smallScreen = Platform.OS === 'ios' && realWidth <= 750
 * ```
 *
 * **`Platform.OS === 'ios'` 是与条件的第一项** —— 所以在 Android 上
 * `smallScreen` **恒为 false**，那三组「小屏」参数与 `listHeight/2` 行高
 * **在 Android 上永远不会被使用**。同理 `listBottomPadding`
 * （`ChatMap.tsx:313`）也是 `Platform.OS === 'ios' ? ... : 0`，Android 恒 0。
 *
 * 所以本文件**只实现非 small 的那一套**，且由
 * `ChatMapGeometryTest` 的三条反向测试钉住 —— 照 iOS 补回小屏分支会
 * **在小屏 Android 机上用错整套动画曲线**，而这类偏差按
 * `llmdoc/index.md:64` 的纪律**没人会报**（用户不会同时装两个版本）。
 *
 * ## 与 iOS 同构的部分
 *
 * 卡叠（`TipsyCarousel.tsx`）**没有任何 `Platform` 分支**，两端真同构 ——
 * 所以卡尺寸、`baseX`、ratio 数组、惯性衰减都直接对齐，iOS 的端口可参照。
 */
internal object ChatMapGeometry {

    // ── 行高（廊道一层的高度）────────────────────────────────────

    /**
     * 行高 = `round(listHeight / 3)`（`ChatMap.tsx:316-320`）。
     *
     * ⚠️ **Android 没有 `/2` 分支**：那条是 `smallScreen` 专属，而该 flag
     * 在 Android 恒 false（见类注释）。参数只留 [listHeight] 一个 ——
     * 刻意不接 `smallScreen: Boolean`，免得有人传 true 进来。
     */
    fun rowHeight(listHeight: Int): Int = Math.round(listHeight / 3f)

    /**
     * 样条横轴基准 `floorHeight = (windowHeight - 300) / 3`。
     *
     * ⚠️ 与 [rowHeight] 是**两个不同的量**（iOS 端口注释也专门标了这点）：
     * 前者是列表里一层的物理高度，这个是动画曲线的横轴基准。混用会让
     * 曲线整体错位 —— 而画面仍然会动，所以不容易看出来。
     */
    fun floorHeight(windowHeight: Int): Float = (windowHeight - FLOOR_HEIGHT_OFFSET) / 3f

    /** 底部留白恒 0（`ChatMap.tsx:313` 的 Android 分支）。 */
    const val LIST_BOTTOM_PADDING_DP = 0

    // ── 楼层动画曲线（非 small 那套，见类注释）──────────────────

    /**
     * translateX 样条的输出（`xy`）—— **非 small 那套**。
     * 小屏那套是 `[0, 0, -70, 0, 50, 300]`，Android 用不到。
     */
    val TRANSLATE_X_OUTPUT = floatArrayOf(0f, 20f, -70f, 0f, 170f, 500f)

    /**
     * scale 样条的输出（`sy`）—— **非 small 那套**。
     * 小屏那套是 `[1, 0.8, 0.7, 0.6]`，Android 用不到。
     */
    val SCALE_OUTPUT = floatArrayOf(1f, 0.7f, 0.4f, 0.3f)

    /**
     * translateY 插值的输出 —— **非 small 那套**。
     * 小屏那套是 `[5, -35, -35, -50, -100]`，Android 用不到。
     */
    val TRANSLATE_Y_OUTPUT = floatArrayOf(5f, -88f, -180f, -180f, -50f)

    /** translateX 样条的横轴：`[0, fh/2, fh, fh*1.5, fh*2, fh*3]`。 */
    fun translateXInput(floorHeight: Float): FloatArray = floatArrayOf(
        0f,
        floorHeight / 2f,
        floorHeight,
        floorHeight * 1.5f,
        floorHeight * 2f,
        floorHeight * 3f,
    )

    /** scale 样条的横轴：`[0, fh, fh*2, fh*3]`。 */
    fun scaleInput(floorHeight: Float): FloatArray = floatArrayOf(
        0f,
        floorHeight,
        floorHeight * 2f,
        floorHeight * 3f,
    )

    /** translateY 插值的横轴：`[-100, 0, fh, fh*2, fh*3]`。 */
    fun translateYInput(floorHeight: Float): FloatArray = floatArrayOf(
        -100f,
        0f,
        floorHeight,
        floorHeight * 2f,
        floorHeight * 3f,
    )

    /** translateY 前先把 delta clamp 到 `[-180, windowHeight]`（对齐 iOS 端口）。 */
    const val TRANSLATE_Y_CLAMP_LOWER = -180f

    // ── 楼层可见范围 ────────────────────────────────────────────

    /**
     * 可见的 `currIndex` 范围 `[-1, 3]`（上下各留一层过渡缓冲）。
     * 越界整层隐藏，且**要同步关掉该层的动图**——隐藏的层仍在窗口上，
     * 动图不降级会空转解码（iOS 端口踩过并在注释里记了）。
     */
    const val VISIBLE_INDEX_MIN = -1
    const val VISIBLE_INDEX_MAX = 3

    /** 标题在 `currIndex >= 2` 时淡出。 */
    const val TITLE_FADE_INDEX = 2

    /**
     * `currIndex` 的 0.5px 容差：`floor((scrollY + 0.5) / rowHeight) + index`。
     *
     * ⚠️ 这个 0.5 不是随手加的（iOS 端口注释）：初始/吸附后 `scrollY` 可能是
     * `-0.0x` 的浮点噪声，裸 `floor` 会**错位一整行**。
     */
    const val CURR_INDEX_EPSILON = 0.5f

    // ── 卡叠（与 iOS 同构，RN 侧无 Platform 分支）────────────────

    /** 卡宽 = `windowWidth * 12 / 25`（`ChatMap.tsx:233`）。 */
    fun cardWidth(windowWidth: Int): Float = windowWidth * (12f / 25f)

    /** 卡高 = 卡宽 / 0.75（`:234`）。 */
    fun cardHeight(windowWidth: Int): Float = cardWidth(windowWidth) / 0.75f

    /** `baseX = windowWidth * 1.5 / 5`（`TipsyCarousel.tsx:41`）。 */
    fun baseX(windowWidth: Int): Float = windowWidth * 1.5f / 5f

    /**
     * 卡叠 scale 比例数组（`TipsyCarousel.tsx:43-46,78`）：
     * `[RATIO0, RATIO1, RATIO2, RATIO3, RATIO2, RATIO1, RATIO0]`
     * = `[0, 0.74, 0.86, 1, 0.86, 0.74, 0]`（中间那张最大，两侧对称衰减）。
     */
    val CARD_RATIO_ARRAY = floatArrayOf(0f, 0.74f, 0.86f, 1f, 0.86f, 0.74f, 0f)

    /** 松手惯性衰减系数（`TipsyCarousel.tsx:365`）。 */
    const val DECELERATION = 0.998f

    /**
     * 卡叠的**可见弧段槽位数**（distance 环绕的窗口宽度）。
     *
     * ⚠️ **不是"一层最多几张卡"** —— 早前叫 `MAX_CARDS_PER_FLOOR` 是危险的错名：
     * RN 是 `if (len < 5)` **补位**（`ChatMap.tsx:205`），同日超过 5 条会话
     * **全部保留**，靠 distance 环绕显示。UI 不得 `take(5)`。
     * 补位下限见 [ChatMapFloors.MIN_CAROUSEL_SLOTS]。
     */
    const val VISIBLE_ARC_SLOTS = 5

    /** 楼层模式数：`i ∈ {1, 3, 5}`（4/2 归 3，见 `getIndex`）。 */
    val FLOOR_MODES = intArrayOf(1, 3, 5)

    private const val FLOOR_HEIGHT_OFFSET = 300
}
