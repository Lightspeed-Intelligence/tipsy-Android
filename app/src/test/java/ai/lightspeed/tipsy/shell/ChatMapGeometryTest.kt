package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.chatlist.ChatMapGeometry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
        assertEquals(300, ChatMapGeometry.rowHeight(900))
        assertEquals(200, ChatMapGeometry.rowHeight(600))
        // 若有人加了 smallScreen 分支，小屏尺寸下会返回 /2 的值 —— 这里会挂。
        // 640 逻辑高的小屏：/3 = 213（四舍五入），/2 = 320
        assertEquals("小屏也必须走 /3，不得回落 /2", 213, ChatMapGeometry.rowHeight(640))
        // round 而非截断（RN 用 Math.round）
        assertEquals(167, ChatMapGeometry.rowHeight(500))
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
        assertEquals(200f, ChatMapGeometry.floorHeight(900), EPS)
        assertEquals(300, ChatMapGeometry.rowHeight(900))
    }

    @Test
    fun `卡叠几何对齐 RN（两端同构、无 Platform 分支）`() {
        // TipsyCarousel.tsx 里没有任何 Platform 分支，故可直接对齐 iOS 端口
        // cardWidth = windowWidth * 12/25（ChatMap.tsx:233）
        assertEquals(518.4f, ChatMapGeometry.cardWidth(1080), EPS)
        // cardHeight = cardWidth / 0.75（:234）
        assertEquals(691.2f, ChatMapGeometry.cardHeight(1080), EPS)
        // baseX = windowWidth * 1.5/5（TipsyCarousel.tsx:41）
        assertEquals(324f, ChatMapGeometry.baseX(1080), EPS)
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

    private companion object {
        const val EPS = 1e-3f
    }
}
