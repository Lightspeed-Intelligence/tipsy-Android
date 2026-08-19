package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.chatlist.ChatMapGeometry
import ai.lightspeed.tipsy.shell.pages.chatlist.ChatMapStyle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Map 几何常量（W3-P2）。
 *
 * ## 前三条是**反向测试**，钉的是「Android 恒值分支」
 *
 * iOS 的 `ChatMapFloorView.swift` 有 `smallScreen` 分支（三组动画参数 +
 * 行高 `/2`），但 RN 侧那个 flag 是
 * `Platform.OS === 'ios' && realWidth <= 750`（`ChatMap.tsx:84`）——
 * **与条件第一项就是 iOS**，所以 Android 恒 false。
 *
 * 这三条测试存在的唯一目的：**以后有人照 iOS 把小屏分支补回来时让 CI 红**。
 * 补回去的后果是小屏 Android 机上整套动画曲线错，而这类偏差按
 * `llmdoc/index.md:64` 的纪律**没人会报**（用户不会同时装两个版本）——
 * 也就是说，没有这三条测试，这个错会一路上线。
 */
class ChatMapGeometryTest {

    @Test
    fun `行高恒为三分之一 —— 不存在小屏的二分之一分支`() {
        // RN Android 恒走 `Math.round(listHeight / 3)`（ChatMap.tsx:320）
        assertEquals(300f, ChatMapGeometry.rowHeightDp(900f), EPS)
        assertEquals(200f, ChatMapGeometry.rowHeightDp(600f), EPS)
        // 若有人加了 smallScreen 分支，小屏尺寸下会返回 /2 的值 —— 这里会挂。
        // 640 逻辑高的小屏：/3 = 213（四舍五入），/2 = 320
        assertEquals("小屏也必须走 /3，不得回落 /2", 213f, ChatMapGeometry.rowHeightDp(640f), EPS)
        // round 而非截断（RN 用 Math.round）
        assertEquals(167f, ChatMapGeometry.rowHeightDp(500f), EPS)
    }

    @Test
    fun `三组动画曲线只有非小屏那一套`() {
        // 小屏那套（iOS 用）分别是：
        //   xy = [0, 0, -70, 0, 50, 300]
        //   sy = [1, 0.8, 0.7, 0.6]
        //   translateY = [5, -35, -35, -50, -100]
        // Android 必须是下面这套，任何一处被换成小屏值都会挂
        assertArrayEquals(
            floatArrayOf(0f, 20f, -70f, 0f, 170f, 500f),
            ChatMapGeometry.TRANSLATE_X_OUTPUT,
            EPS,
        )
        assertArrayEquals(
            floatArrayOf(1f, 0.7f, 0.4f, 0.3f),
            ChatMapGeometry.SCALE_OUTPUT,
            EPS,
        )
        assertArrayEquals(
            floatArrayOf(5f, -88f, -180f, -180f, -50f),
            ChatMapGeometry.TRANSLATE_Y_OUTPUT,
            EPS,
        )
    }

    @Test
    fun `底部留白恒为零`() {
        // RN：`Platform.OS === 'ios' ? getFloatingTabBarTotalOffset(...) : 0`
        // （ChatMap.tsx:313）。Android 不接 tabbar offset ——
        // ⚠️ 注意这与 Home/ChatList 列表那条「必须含 Tab 栏高度」不同轴：
        // 那是壳自绘 tabbar 与内容叠放导致的，而这里 RN 自己在 Android 上就是 0
        assertEquals(0, ChatMapGeometry.LIST_BOTTOM_PADDING_DP)
    }

    @Test
    fun `floorHeight 与 rowHeight 是两个不同的量`() {
        // floorHeight = (windowHeight - 300) / 3 —— 动画曲线横轴基准
        // rowHeight   = listHeight / 3          —— 列表一层的物理高度
        // 混用会让曲线整体错位，但画面仍会动，所以不容易看出来
        assertEquals(200f, ChatMapGeometry.floorHeightDp(900f), EPS)
        assertEquals(300f, ChatMapGeometry.rowHeightDp(900f), EPS)
    }

    @Test
    fun `卡叠几何对齐 RN（两端同构、无 Platform 分支）`() {
        // TipsyCarousel.tsx 里没有任何 Platform 分支，故可直接对齐 iOS 端口
        // cardWidth = windowWidth * 12/25（ChatMap.tsx:233）
        assertEquals(518.4f, ChatMapGeometry.cardWidthDp(1080f), EPS)
        // cardHeight = cardWidth / 0.75（:234）
        assertEquals(691.2f, ChatMapGeometry.cardHeightDp(1080f), EPS)
        // baseX = windowWidth * 1.5/5（TipsyCarousel.tsx:41）
        assertEquals(324f, ChatMapGeometry.baseXDp(1080f), EPS)
        // ratio 数组两侧对称（RATIO0/1/2/3 = 0/0.74/0.86/1）
        assertArrayEquals(
            floatArrayOf(0f, 0.74f, 0.86f, 1f, 0.86f, 0.74f, 0f),
            ChatMapGeometry.CARD_RATIO_ARRAY,
            EPS,
        )
        assertEquals(0.998f, ChatMapGeometry.DECELERATION, EPS)
    }

    @Test
    fun `楼层可见范围与容差`() {
        // currIndex ∈ [-1, 3]，上下各留一层过渡缓冲
        assertEquals(-1, ChatMapGeometry.VISIBLE_INDEX_MIN)
        assertEquals(3, ChatMapGeometry.VISIBLE_INDEX_MAX)
        assertEquals(2, ChatMapGeometry.TITLE_FADE_INDEX)
        // ⚠️ 0.5px 容差不能去掉：初始/吸附后 scrollY 可能是 -0.0x 的浮点噪声，
        // 裸 floor 会错位一整行（iOS 端口踩过）
        assertEquals(0.5f, ChatMapGeometry.CURR_INDEX_EPSILON, EPS)
    }

    @Test
    fun `纯乘法的量对单位不敏感 —— 但带常量的不是`() {
        // ⚠️ 这条记录的是这套 dp 契约**为什么必要**，以及它**不**必要的那部分。
        //
        // cardWidth 是纯乘（*12/25），而乘法与密度换算**可交换** ——
        // 先 px 算再 toDp() 与先 toDp() 再算完全相同：
        //   (1080 * 12/25) / 2.625 = 197.49
        //   (1080 / 2.625) * 12/25 = 197.49
        // 所以卡尺寸传 px **不会错**（我早前说"阶段一放大 2.6 倍"是错的）。
        val density = 2.625f
        val widthPx = 1080f
        val widthDp = widthPx / density
        assertEquals(
            "纯乘法：px 路径与 dp 路径必须等价",
            ChatMapGeometry.cardWidthDp(widthPx) / density,
            ChatMapGeometry.cardWidthDp(widthDp),
            0.01f,
        )

        // 但 floorHeight 带常量 300（dp 数值），**不可交换**：
        val heightPx = 2835f
        val heightDp = heightPx / density
        assertTrue(
            "带常量：px 路径与 dp 路径必须不等价（这才是契约的必要性）",
            Math.abs(
                ChatMapGeometry.floorHeightDp(heightPx) / density -
                    ChatMapGeometry.floorHeightDp(heightDp),
            ) > 1f,
        )
    }

    @Test
    fun `floorHeight 的 300 偏移是 dp 数值`() {
        // 900dp 屏：(900-300)/3 = 200dp
        assertEquals(200f, ChatMapGeometry.floorHeightDp(900f), EPS)
        // 传 px 会得到不同的 dp 结果 —— 见上一条的不可交换性
        assertEquals(700f, ChatMapGeometry.floorHeightDp(2400f), EPS)
    }

    @Test
    fun `未读点几何：x 为正向右越出、y 为负向上越出`() {
        // ⚠️ 这条守的是一次**坐标系符号翻转**：
        // RN 用 CSS 绝对定位 `right: -2`（负 = 向右越出，`ChatItem.tsx:141`），
        // 而 Compose 的 `Alignment.TopEnd` + `offset(x)` 是 **x 正才向右外移**。
        // 直接抄 RN 的 -2 会让红点缩回卡内 —— 而它已不被 clip，
        // 所以看起来"就在角上"，只有与 RN 并排才看得出差 4dp。
        assertEquals("x 必须为正（向右越出）", 2, ChatMapStyle.UNREAD_DOT_OFFSET_X_DP)
        assertEquals("y 必须为负（向上越出）", -4, ChatMapStyle.UNREAD_DOT_OFFSET_Y_DP)
        // y = -size/2：半个点越出上边缘（RN `top: -offset`）
        assertEquals(
            "y 恒为 -size/2",
            -(ChatMapStyle.UNREAD_DOT_SIZE_DP / 2),
            ChatMapStyle.UNREAD_DOT_OFFSET_Y_DP,
        )
        assertEquals(8, ChatMapStyle.UNREAD_DOT_SIZE_DP)
    }

    @Test
    fun `占位卡圆角与真实卡不同`() {
        // RN：真实卡 `borderRadius: 4`（ChatItem.tsx:237）、
        // 占位卡 `borderRadius: 8`（ChatMap.tsx:240）—— 复用会错
        assertEquals(4, ChatMapStyle.CARD_CORNER_DP)
        assertEquals(8, ChatMapStyle.PLACEHOLDER_CORNER_DP)
        assertTrue(
            "两者必须不同，别复用",
            ChatMapStyle.CARD_CORNER_DP != ChatMapStyle.PLACEHOLDER_CORNER_DP,
        )
    }

    private companion object {
        const val EPS = 1e-3f
    }
}
