package ai.lightspeed.tipsy.shell.pages.chatlist

/**
 * 卡叠松手后的**吸附目标**（W3-P2 阶段三）—— 纯函数，可 JVM 单测。
 *
 * 对齐 RN `TipsyCarousel.tsx:354-395` 的 `scrollCurrentIndex`：
 * `withDecay(deceleration = 0.998)` 惯性衰减停下后，吸附到最近的 `baseX` 倍数；
 * 但**真实卡不足 5 张时要回绕到真实卡**，不能停在补位的空槽上。
 *
 * ## 为什么单独抽出来
 *
 * 惯性动画本身交给 Compose 的 `AnimationState.animateDecay`（平台机制不同，
 * 不照抄 reanimated 的 worklet 形态）；但**吸附目标的算法**三端一致，
 * 而且它有一条容易做丢的分支（下面那个回绕），所以抽出来钉死。
 */
internal object ChatMapSnap {

    /**
     * 惯性停下后的吸附目标（dp）。
     *
     * @param restX 惯性衰减自然停下的位置（dp）
     * @param baseX 见 [ChatMapGeometry.baseXDp]
     * @param realSize 该层**真实**卡数（不含补位）
     *
     * ⚠️ `realSize in 1..4` 时，若吸附目标落在补位空槽上，必须**回绕到最近的
     * 真实卡**（RN `:371-381`）—— 取"回绕到最后一张"与"回绕到第一张"里更近的那边。
     * 漏了这条的表现是：**同日只有 1~4 条会话时，松手可能停在一张空占位卡上**，
     * 用户看到的是"滑完什么都没有"。
     */
    fun snapTarget(restX: Float, baseX: Float, realSize: Int): Float {
        if (baseX <= 0f) return restX

        // 落在第几个槽（对齐 RN 的 `Math.round((-x % (baseX*5)) / baseX) % 5`）
        val raw = Math.round((-restX % (baseX * SLOTS)) / baseX) % SLOTS
        val targetIndex = if (raw < 0) SLOTS + raw else raw

        val nearestBase = ChatMapMath.nearest(restX, baseX)

        // 真实卡铺满（或为 0）时直接吸附到最近的 baseX 倍数
        if (realSize <= 0 || realSize >= SLOTS) return nearestBase
        if (targetIndex <= realSize - 1) return nearestBase

        // 落在补位槽上 —— 回绕到更近的那侧真实卡
        val stepsToLast = SLOTS - targetIndex
        val stepsToFirst = targetIndex - (realSize - 1)
        return if (stepsToLast < stepsToFirst) {
            nearestBase - baseX * stepsToLast
        } else {
            nearestBase + baseX * stepsToFirst
        }
    }

    /** 惯性衰减系数，对齐 RN `withDecay({ deceleration: 0.998 })`。 */
    const val DECELERATION = ChatMapGeometry.DECELERATION

    private const val SLOTS = 5
}
