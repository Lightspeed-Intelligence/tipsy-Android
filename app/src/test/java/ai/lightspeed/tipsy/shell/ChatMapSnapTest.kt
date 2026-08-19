package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.chatlist.ChatMapSnap
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 卡叠松手吸附（W3-P2 阶段三）。
 *
 * 期望值**不是我算的** —— 是把 RN `TipsyCarousel.tsx:369-381` 的
 * `scrollCurrentIndex` 回调逻辑抄进 node 跑出来的。自己推等于把同一个错抄两遍。
 */
class ChatMapSnapTest {

    private val baseX = 324f

    @Test
    fun `真实卡铺满时吸附到最近的 baseX 倍数`() {
        // node 实测：restX=-1000, realSize=5 → -972
        assertEquals(-972f, ChatMapSnap.snapTarget(-1000f, baseX, realSize = 5), EPS)
        // restX=0 → 0
        assertEquals(0f, ChatMapSnap.snapTarget(0f, baseX, realSize = 3), EPS)
    }

    @Test
    fun `真实卡不足五张时回绕到真实卡 —— 不能停在空占位上`() {
        // ⚠️ 这条守的是 RN `:371-381` 那个分支。漏了的表现：
        // 同日只有 1~4 条会话时，松手可能停在**空占位卡**上，
        // 用户看到"滑完什么都没有" —— 不报错、不崩溃
        //
        // node 实测：
        //   restX=-1000, realSize=2 → -324（回绕，不是 -972）
        //   restX=500,   realSize=1 → 0
        //   restX=-1500, realSize=4 → -1620
        assertEquals(-324f, ChatMapSnap.snapTarget(-1000f, baseX, realSize = 2), EPS)
        assertEquals(0f, ChatMapSnap.snapTarget(500f, baseX, realSize = 1), EPS)
        assertEquals(-1620f, ChatMapSnap.snapTarget(-1500f, baseX, realSize = 4), EPS)
    }

    @Test
    fun `落在真实卡范围内时不回绕`() {
        // realSize=3、目标槽 0~2 → 直接用 nearest
        // node 实测：restX=-700, realSize=3 → -648
        assertEquals(-648f, ChatMapSnap.snapTarget(-700f, baseX, realSize = 3), EPS)
    }

    @Test
    fun `退化输入不崩`() {
        // baseX=0 原样返回（不除零）
        assertEquals(123f, ChatMapSnap.snapTarget(123f, 0f, realSize = 3), EPS)
        // realSize=0（全占位）走 nearest 分支
        assertEquals(-972f, ChatMapSnap.snapTarget(-1000f, baseX, realSize = 0), EPS)
    }

    @Test
    fun `惯性衰减系数对齐 RN`() {
        // RN `withDecay({ deceleration: 0.998 })`
        assertEquals(0.998f, ChatMapSnap.DECELERATION, EPS)
    }

    private companion object {
        const val EPS = 1e-3f
    }
}
