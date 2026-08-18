package ai.lightspeed.tipsy.shell.pages.chatlist

/**
 * Map（時光長廊）廊道/卡叠动画的**纯数学端口**（W3-P2）。
 *
 * ## iOS 先例 → Android 映射
 *
 * iOS 侧同一块是 `Tipsy-iOS/Pages/ChatList/ChatMapMath.swift`（152 行），
 * 它本身又是 RN `tipsy-app/src/components/ui-kit/animate/func.ts`（106 行）
 * 与 reanimated `interpolate` 的端口。
 *
 * **本文件是一比一移植，零平台偏离** —— 三处实现（RN / iOS / 这里）都不依赖
 * 各自的 UI 框架，所以不存在"Android 平台约束不同"的余地。
 * 我逐函数对拍了 RN 原文与 iOS 端口，**行为完全一致**（含下面两处易错点）。
 *
 * ## 两处最容易做丢的细节
 *
 * 1. **`interpolate` 要支持递减 `inputRange`**。reanimated 允许单调递减
 *    （卡叠里真的传 `[1, 0]`），实现方式是把两个 range 同时反转再算。
 *    漏了的表现是那类插值恒返回端点值 —— 动画"不动"，但不报错。
 *
 * 2. **`nearest` 的取余符号必须与 JS 的 `%` 一致**。JS 的 `%` 对负数返回
 *    负余数（`-7 % 3 === -1`），Kotlin 的 `%` 同样如此 —— 所以这里可以直接用，
 *    但**不能换成 `Math.floorMod` 之类**（那会返回正余数），换了负向吸附就反了。
 *
 * ⚠️ 这些函数在滚动回调里每帧被调用，都写成无分配的 [Float] 运算。
 * 不要为了"更 Kotlin"改成返回 data class 或用序列 —— 那会在滚动时产生逐帧垃圾。
 */
internal object ChatMapMath {

    /**
     * 复刻 reanimated `interpolate(value, inputRange, outputRange, Extrapolation.CLAMP)`。
     *
     * @param inputRange 单调递增**或递减**（见类注释第 1 条）
     * @param clamp true 时两端裁剪到端点输出。本工程所有调用点都传 clamp
     *   （iOS 端口注释：「本工程所有调用点均传 'clamp'」），
     *   非 clamp 分支照 reanimated 语义实现为按端段延长线外推。
     */
    fun interpolate(
        x: Float,
        inputRange: FloatArray,
        outputRange: FloatArray,
        clamp: Boolean = true,
    ): Float {
        val n = inputRange.size
        if (n < 2 || outputRange.size != n) return outputRange.firstOrNull() ?: 0f

        // 递减 inputRange：同时反转两个 range 再算（reanimated 允许，卡叠传 [1, 0]）
        if (inputRange[0] > inputRange[n - 1]) {
            return interpolate(x, inputRange.reversedArray(), outputRange.reversedArray(), clamp)
        }

        if (x <= inputRange[0]) {
            return if (clamp) {
                outputRange[0]
            } else {
                segment(x, inputRange[0], inputRange[1], outputRange[0], outputRange[1])
            }
        }
        if (x >= inputRange[n - 1]) {
            return if (clamp) {
                outputRange[n - 1]
            } else {
                segment(
                    x,
                    inputRange[n - 2], inputRange[n - 1],
                    outputRange[n - 2], outputRange[n - 1],
                )
            }
        }
        var i = 0
        while (i < n - 1 && x > inputRange[i + 1]) i++
        return segment(x, inputRange[i], inputRange[i + 1], outputRange[i], outputRange[i + 1])
    }

    private fun segment(x: Float, x0: Float, x1: Float, y0: Float, y1: Float): Float {
        if (x1 == x0) return y0
        return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    }

    /** clamp（对齐 reanimated `clamp`）。 */
    fun clamp(v: Float, lower: Float, upper: Float): Float = minOf(maxOf(v, lower), upper)

    /**
     * 吸附到最近的 [step] 倍数（对齐 `func.ts` 的 `nearest`）。
     *
     * ⚠️ 取余符号必须与 JS 的 `%` 一致 —— Kotlin 的 `%` 恰好同语义（负数返回负余数）。
     * **别换成 floorMod**，见类注释第 2 条。
     */
    fun nearest(v: Float, step: Float): Float {
        if (step == 0f) return v
        val remainder = v % step
        return when {
            remainder == 0f -> v
            remainder >= step / 2 -> v + (step - remainder)
            remainder <= -step / 2 -> v - (step + remainder)
            else -> v - remainder
        }
    }
}

/**
 * 自然三次样条（对齐 `func.ts` 的 `cubicSplineCoefficients` + `spline`）。
 *
 * 构造即求出各段系数，[valueAt] 复刻 `spline` 取值。
 * 与 [ChatMapMath] 同为一比一移植，见那里的类注释。
 *
 * ⚠️ **`valueAt` 的 `i < 0` 兜底是 iOS 端口加的，RN 没有**：RN 的
 * `while (i >= 0 && xVal < x[i]) i--` 在 `xVal` 小于 `x[0]` 时会退到 `i = -1`，
 * 随后 `a[-1]` 在 JS 里是 `undefined` → 整个表达式变 `NaN`。
 * iOS 端口把 `i` 夹回 0（= 用第一段的多项式外推）。
 * **这里保留 iOS 的兜底**：Kotlin 下 `a[-1]` 会直接抛
 * `ArrayIndexOutOfBoundsException`，而这是滚动回调 —— 崩在那里比外推糟得多。
 * 记为**有意偏离 RN**（偏离方向是"不崩"，且 iOS 已先行）。
 */
internal class CubicSpline(private val x: FloatArray, y: FloatArray) {
    private val a: FloatArray = y.copyOf()
    private val b: FloatArray
    private val c: FloatArray
    private val d: FloatArray

    init {
        val n = x.size
        // h[i] = x[i+1] - x[i]
        val h = FloatArray(maxOf(0, n - 1))
        for (i in 0 until n - 1) h[i] = x[i + 1] - x[i]

        // alpha（alpha[0] = 0）
        val alpha = FloatArray(n)
        for (i in 1 until n - 1) {
            alpha[i] = 3 / h[i] * (a[i + 1] - a[i]) - 3 / h[i - 1] * (a[i] - a[i - 1])
        }

        // 三对角求解（l[0]=1, mu[0]=0, z[0]=0）
        val l = FloatArray(n)
        val mu = FloatArray(n)
        val z = FloatArray(n)
        if (n > 0) l[0] = 1f
        if (n >= 2) {
            for (i in 1 until n - 1) {
                l[i] = 2 * (x[i + 1] - x[i - 1]) - h[i - 1] * mu[i - 1]
                mu[i] = h[i] / l[i]
                z[i] = (alpha[i] - h[i - 1] * z[i - 1]) / l[i]
            }
            l[n - 1] = 1f
            z[n - 1] = 0f
        }

        // 回代求 b/c/d（b/d 末位保持 0，spline 取值用不到）
        b = FloatArray(n)
        c = FloatArray(n)
        d = FloatArray(n)
        for (j in n - 2 downTo 0) {
            c[j] = z[j] - mu[j] * c[j + 1]
            b[j] = (a[j + 1] - a[j]) / h[j] - h[j] * (c[j + 1] + 2 * c[j]) / 3
            d[j] = (c[j + 1] - c[j]) / (3 * h[j])
        }
    }

    /** 复刻 `spline(xVal, coef)`：定位段 i，再按三次多项式求值。 */
    fun valueAt(xVal: Float): Float {
        if (x.size < 2) return a.firstOrNull() ?: 0f
        var i = x.size - 2
        while (i >= 0 && xVal < x[i]) i--
        if (i < 0) i = 0 // 见类注释：iOS 加的兜底，RN 在这里会出 NaN
        val dx = xVal - x[i]
        return a[i] + b[i] * dx + c[i] * dx * dx + d[i] * dx * dx * dx
    }
}
