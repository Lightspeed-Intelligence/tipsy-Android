package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.chatlist.ChatMapMath
import ai.lightspeed.tipsy.shell.pages.chatlist.CubicSpline
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Map（時光長廊）纯数学端口的对拍（W3-P2）。
 *
 * 期望值**不是我算的**，是拿 RN 原文（`src/components/ui-kit/animate/func.ts`）
 * 在 node 里跑出来的 —— 自己推一遍等于把同一个错抄两遍。
 */
class ChatMapMathTest {

    @Test
    fun `interpolate 支持递减 inputRange`() {
        // reanimated 允许单调递减，卡叠里真的传 [1, 0]。
        // 漏了这条的表现是恒返回端点值 —— 动画"不动"但不报错
        val input = floatArrayOf(1f, 0f)
        val output = floatArrayOf(100f, 0f)
        assertEquals(100f, ChatMapMath.interpolate(1f, input, output), EPS)
        assertEquals(0f, ChatMapMath.interpolate(0f, input, output), EPS)
        assertEquals(50f, ChatMapMath.interpolate(0.5f, input, output), EPS)
    }

    @Test
    fun `interpolate 递增区间与多段`() {
        val input = floatArrayOf(0f, 1f, 2f)
        val output = floatArrayOf(0f, 10f, 100f)
        assertEquals(5f, ChatMapMath.interpolate(0.5f, input, output), EPS)
        assertEquals(55f, ChatMapMath.interpolate(1.5f, input, output), EPS)
        assertEquals(10f, ChatMapMath.interpolate(1f, input, output), EPS)
    }

    @Test
    fun `clamp 模式两端裁到端点`() {
        val input = floatArrayOf(0f, 1f)
        val output = floatArrayOf(0f, 10f)
        assertEquals(0f, ChatMapMath.interpolate(-5f, input, output, clamp = true), EPS)
        assertEquals(10f, ChatMapMath.interpolate(99f, input, output, clamp = true), EPS)
        // 非 clamp 按端段延长线外推
        assertEquals(-50f, ChatMapMath.interpolate(-5f, input, output, clamp = false), EPS)
        assertEquals(990f, ChatMapMath.interpolate(99f, input, output, clamp = false), EPS)
    }

    @Test
    fun `相邻输入相等时不产生除零`() {
        // 数据异常（两个相同的 stop）时返回前一个输出而不是 NaN/Inf
        val v = ChatMapMath.interpolate(
            1f,
            floatArrayOf(0f, 1f, 1f, 2f),
            floatArrayOf(0f, 5f, 9f, 20f),
        )
        assertEquals(5f, v, EPS)
    }

    @Test
    fun `nearest 的取余符号与 JS 一致`() {
        // 期望值来自 node 跑 func.ts 的 nearest：
        // nearest(7,3)=6 / nearest(-7,3)=-6 / nearest(4.5,3)=6 / nearest(-4.5,3)=-6
        // ⚠️ 换成 floorMod 会让负向吸附反向 —— 这四条里后两条会挂
        assertEquals(6f, ChatMapMath.nearest(7f, 3f), EPS)
        assertEquals(-6f, ChatMapMath.nearest(-7f, 3f), EPS)
        assertEquals(6f, ChatMapMath.nearest(4.5f, 3f), EPS)
        assertEquals(-6f, ChatMapMath.nearest(-4.5f, 3f), EPS)
        // 整数倍原样返回
        assertEquals(9f, ChatMapMath.nearest(9f, 3f), EPS)
    }

    @Test
    fun `clamp 函数`() {
        assertEquals(0f, ChatMapMath.clamp(-1f, 0f, 1f), EPS)
        assertEquals(1f, ChatMapMath.clamp(2f, 0f, 1f), EPS)
        assertEquals(0.5f, ChatMapMath.clamp(0.5f, 0f, 1f), EPS)
    }

    @Test
    fun `三次样条逐点对拍 RN`() {
        // 期望值来自 node 跑 func.ts 的 cubicSplineCoefficients + spline，
        // 输入 x=[0,1,2,3] y=[0,1,4,9]：
        //   spline(0.5)=0.35000000000000003
        //   spline(1.5)=2.1999999999999997
        //   spline(2.5)=6.35
        //   spline(2)  =4
        val spline = CubicSpline(floatArrayOf(0f, 1f, 2f, 3f), floatArrayOf(0f, 1f, 4f, 9f))
        assertEquals(0.35f, spline.valueAt(0.5f), EPS)
        assertEquals(2.2f, spline.valueAt(1.5f), EPS)
        assertEquals(6.35f, spline.valueAt(2.5f), EPS)
        // 节点上必须精确回到 y
        assertEquals(4f, spline.valueAt(2f), EPS)
        assertEquals(0f, spline.valueAt(0f), EPS)
    }

    @Test
    fun `样条在左端外侧外推而不是崩`() {
        // ⚠️ 有意偏离 RN：RN 的 while 会退到 i=-1，a[-1] 是 undefined → NaN。
        // Kotlin 下 a[-1] 会抛 ArrayIndexOutOfBoundsException，而这是滚动回调，
        // 崩在那里比外推糟得多。iOS 端口已先行做同样的兜底
        val spline = CubicSpline(floatArrayOf(0f, 1f, 2f), floatArrayOf(0f, 1f, 4f))
        val v = spline.valueAt(-1f)
        assertEquals("不得为 NaN", false, v.isNaN())
        assertEquals("不得为无穷", false, v.isInfinite())
    }

    @Test
    fun `退化输入不崩`() {
        // 单点：取值恒为那个 y
        assertEquals(3f, CubicSpline(floatArrayOf(0f), floatArrayOf(3f)).valueAt(5f), EPS)
        // 空：回落 0
        assertEquals(0f, CubicSpline(FloatArray(0), FloatArray(0)).valueAt(1f), EPS)
        // interpolate 的 range 长度不匹配 / 太短
        assertEquals(7f, ChatMapMath.interpolate(1f, floatArrayOf(0f), floatArrayOf(7f)), EPS)
        assertEquals(
            1f,
            ChatMapMath.interpolate(1f, floatArrayOf(0f, 1f), floatArrayOf(1f)),
            EPS,
        )
        // step=0 原样返回（不做除零）
        assertEquals(5f, ChatMapMath.nearest(5f, 0f), EPS)
    }

    private companion object {
        /** Float 单精度 + RN 是 double，容差取到能区分实现差异又不误报的量级。 */
        const val EPS = 1e-4f
    }
}
