package ai.lightspeed.tipsy.shell.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计稿尺寸缩放（对齐 RN `src/styles/ScaledSheet.ts`）。
 *
 * ## ⚠️ Android 的 dp **不等于** RN 的设计稿数值
 *
 * RN 侧所有登录页尺寸都经 `ScaledSheet` 处理过：
 * ```
 * scaleFactor = min(screenWidth / 375, 1.3)
 * ```
 * 也就是说 `height: 48` 在 375dp 宽的设备上是 48，在 414dp 上是 **53**，
 * 在平板上封顶 62.4。**直接把 48 写成 `48.dp` 会让所有控件在大屏上偏小** ——
 * 与 RN 版并排看就是「Android 的按钮比 iOS 矮一圈」。
 *
 * iOS 壳踩过同一处并做了 `TabBarMetrics.s()`（注释明写「对齐 RN 侧
 * ScaledSheet.ts」）。Android 同构。
 *
 * ## 为什么不用 `sw<N>dp` 资源限定符
 *
 * 那套是**离散分档**（`sw320dp` / `sw360dp` / `sw411dp`…），而 RN 是**连续**
 * 线性缩放。分档在档位边界会出现 1~2dp 的跳变，和 RN 对不齐；且每个尺寸都要
 * 写 N 份资源，登录页有二十多个数值。
 *
 * ## 用法
 *
 * ```kotlin
 * Modifier.height(48.s)          // 尺寸
 * fontSize = 14.sSp              // 字号
 * ```
 *
 * ⚠️ **不要缩放的东西**：`flex`/`zIndex`/`elevation` 类无量纲值（RN 的
 * `ignoreProps` 也排除了它们）。Compose 里对应的是权重与层级，本来就不该缩放。
 */
object ScaledMetrics {

    /** RN `guidelineBaseWidth`。 */
    const val DESIGN_WIDTH_DP = 375f

    /** RN `maxScaleFactor` —— 防平板上元素过大。 */
    const val MAX_SCALE_FACTOR = 1.3f

    /**
     * 当前缩放因子。
     *
     * ## 为什么用 `LocalWindowInfo.containerSize` 而不是 `LocalConfiguration`
     *
     * `Configuration.screenWidthDp` 已被 Compose 标记为不推荐（lint
     * `ConfigurationScreenWidthHeight`），而且**语义上也是这个更对**：
     * 它是**窗口**尺寸而非屏幕尺寸。分屏 / 自由窗口 / 折叠屏下窗口可以远小于
     * 屏幕，按屏幕算会让控件在小窗里过大。
     *
     * RN 的 `Dimensions.get('window').width` 同样是**窗口**语义，
     * 所以这个选择也让两端更一致。
     *
     * `containerSize` 是 **px**，需自行换成 dp —— 直接拿 px 除 375 会算出
     * 三倍左右的倍数（在 3x 密度设备上）。
     */
    @Composable
    @ReadOnlyComposable
    fun scaleFactor(): Float {
        val widthPx = LocalWindowInfo.current.containerSize.width
        val density = LocalDensity.current.density
        // density 理论上不会是 0，但除零会产生 NaN 并静默污染所有尺寸
        if (density <= 0f) return 1f
        return scaleFactorFor(widthPx / density)
    }

    /**
     * 纯函数版，供单测与非 Compose 代码用。
     *
     * 与 [scaleFactor] 保持同一公式 —— 两份实现分叉会让「测试通过但界面不对」。
     */
    fun scaleFactorFor(screenWidthDp: Float): Float =
        minOf(screenWidthDp / DESIGN_WIDTH_DP, MAX_SCALE_FACTOR)
}

/** 设计稿数值 → 缩放后的 [Dp]（等价 RN 的 `s(value)`）。 */
val Int.s: Dp
    @Composable
    @ReadOnlyComposable
    get() = (this * ScaledMetrics.scaleFactor()).dp

/** 同上，接受小数。 */
val Float.s: Dp
    @Composable
    @ReadOnlyComposable
    get() = (this * ScaledMetrics.scaleFactor()).dp

/**
 * 设计稿字号 → 缩放后的 [TextUnit]。
 *
 * ⚠️ 用 `sp` 而不是 `dp` —— 字号要跟随系统字体缩放（无障碍要求，方案 §9.4
 * 的验收项含「字体缩放」）。RN 的 `fontSize` 在 Android 上同样映射到 sp 语义。
 */
val Int.sSp: TextUnit
    @Composable
    @ReadOnlyComposable
    get() = (this * ScaledMetrics.scaleFactor()).sp
