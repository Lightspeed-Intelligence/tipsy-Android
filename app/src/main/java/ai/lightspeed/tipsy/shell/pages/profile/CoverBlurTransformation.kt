package ai.lightspeed.tipsy.shell.pages.profile

import android.graphics.Bitmap
import androidx.core.graphics.scale
import coil3.size.Size
import coil3.transform.Transformation

/**
 * API 24–30 的封面模糊降级。API 31+ 由 [ProfileCoverImage] 使用实时 RenderEffect；
 * 本类只负责 `Modifier.blur` 不生效的低版本，避免 18+ 封面 fail-open。
 *
 * ## 为什么是位图变换而不是 `Modifier.blur`
 *
 * `Modifier.blur` 走 `RenderEffect`，**API 31+ 才生效、低版本静默不模糊** ——
 * minSdk 24 且 API 24 是冒烟矩阵的真实一档，18+ 封面在低版本露出是内容合规
 * 问题，不是视觉瑕疵。降级变换在解码线程做一次并随 Coil 缓存；边长缩到
 * 1/8 而不是旧版 1/16，减少明显糊块并接近 RN Android 约 10px 的实际半径。
 */
class CoverBlurTransformation : Transformation() {

    override val cacheKey: String = "profile_cover_blur_v4_$DOWNSCALE"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = (input.width / DOWNSCALE).coerceAtLeast(1)
        val h = (input.height / DOWNSCALE).coerceAtLeast(1)
        // 双线性降采样丢高频，再**逐级 2 倍**放大回全尺寸 ——
        // 渐进上采样每步都做一次双线性插值，叠起来近似高斯；
        // 一步大倍率放大会留块状锯齿（真机对照过 v1/v2，与 BlurView 观感差很多）
        var bmp = input.scale(w, h)
        while (bmp.width < input.width || bmp.height < input.height) {
            val nextW = (bmp.width * 2).coerceAtMost(input.width)
            val nextH = (bmp.height * 2).coerceAtMost(input.height)
            val next = bmp.scale(nextW, nextH)
            if (bmp !== next && bmp !== input) bmp.recycle()
            bmp = next
        }
        return bmp
    }

    override fun equals(other: Any?): Boolean = other is CoverBlurTransformation

    override fun hashCode(): Int = cacheKey.hashCode()

    private companion object {
        /** API 24–30 的安全降级；现代设备不走此路径。 */
        const val DOWNSCALE = 8
    }
}
