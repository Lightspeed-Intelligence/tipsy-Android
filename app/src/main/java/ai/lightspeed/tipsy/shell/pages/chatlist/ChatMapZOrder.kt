package ai.lightspeed.tipsy.shell.pages.chatlist

/**
 * Map 的**两级绘制层序**（W3-P2）—— 抽成纯函数以便 JVM 单测。
 *
 * ## 为什么不在 UI 测试里验
 *
 * Compose 的语义树**不暴露 zIndex**，`assertIsDisplayed` 对完全重叠的
 * 同尺寸节点也照样通过 —— 我写过一版"断言节点有 tag"的 UI 测试，
 * 那与 zIndex 完全无关，删掉生产的 `.zIndex()` 照样绿（第五次假保护，已弃）。
 *
 * 能真正验证绘制顺序的只有截图像素比对，成本与脆性都高。
 * 所以把**规则**抽成纯函数在这里钉死，UI 层只负责把它接上 ——
 * 这样至少"规则本身"有回归护栏，接线由 review 保证。
 */
internal object ChatMapZOrder {

    /**
     * 楼层层序：`100 - index`（对齐 RN/iOS）。
     *
     * ⚠️ index 0 是**最新**那层、铺在**最底部**，但必须画在**最上面**。
     * Compose 默认按 compose 顺序绘制 → 后 compose 的远层会盖住近层。
     * 不给 zIndex 的表现是"上面那层压着下面那层"，卡越出 row cell 后尤其明显。
     */
    fun floorZ(index: Int): Float = (FLOOR_Z_BASE - index).toFloat()

    /**
     * 卡片层序：`slotCount - slot`（倒序）。
     *
     * ⚠️ `repeat` 是**升序** compose，占位卡排在真实卡之后 ——
     * 不给 zIndex 时「1 真卡 + 4 占位」会让占位**完全盖住**真卡
     * （同尺寸、同位置、后绘制）。
     *
     * 阶段二接 [ChatMapCardLayout.CardTransform.zIndex] 后替换本函数。
     */
    fun cardZ(slot: Int, slotCount: Int): Float = (slotCount - slot).toFloat()

    private const val FLOOR_Z_BASE = 100
}
