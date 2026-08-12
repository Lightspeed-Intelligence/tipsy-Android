package ai.lightspeed.tipsy.shell.pages.profile

import android.graphics.Bitmap
import androidx.core.graphics.scale
import coil3.size.Size
import coil3.transform.Transformation

/**
 * 封面模糊（对应 RN 的 `expo-blur` BlurView `intensity={40}` +
 * `dimezisBlurView`，`CharacterGridItem.tsx:578` / `StoryItem.tsx` / `PlotItem.tsx`）。
 *
 * ## 为什么是位图变换而不是 `Modifier.blur`
 *
 * `Modifier.blur` 走 `RenderEffect`，**API 31+ 才生效、低版本静默不模糊** ——
 * minSdk 24 且 API 24 是冒烟矩阵的真实一档，18+ 封面在低版本露出是内容合规
 * 问题，不是视觉瑕疵。降采样-升采样在解码线程做一次，全版本一致，
 * 且随 Coil 内存缓存（cacheKey 参与 key，模糊与原图是两份缓存互不污染）。
 *
 * 强度经真机与线上对照取的近似值；像素级对齐属验收阶段视觉 diff。
 */
class CoverBlurTransformation : Transformation() {

    override val cacheKey: String = "profile_cover_blur_v3_$DOWNSCALE"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = (input.width / DOWNSCALE).coerceAtLeast(1)
        val h = (input.height / DOWNSCALE).coerceAtLeast(1)
        // 双线性降采样丢高频，再**逐级 2 倍**放大回全尺寸 ——
        // 渐进上采样每步都做一次双线性插值，叠起来近似高斯；
        // 一步放大 16 倍会留块状锯齿（真机对照过 v1/v2，与 BlurView 观感差很多）
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
        /** 边长缩到 1/16 —— 对照 BlurView intensity 40 的观感取值。 */
        const val DOWNSCALE = 16
    }
}
