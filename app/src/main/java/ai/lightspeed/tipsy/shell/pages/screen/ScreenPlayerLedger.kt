package ai.lightspeed.tipsy.shell.pages.screen

/**
 * 有界池的**账本**（W4-P2）—— 借出/归还/释放的不变量，与 Media3 无关。
 *
 * ## 为什么单独抽出来
 *
 * [ScreenPlayerPool] 的正确性有两半：
 * - **账面**（同时借出数 ≤ capacity、拒绝外来/重复归还、release 覆盖借出的）；
 * - **Media3 接线**（LoadControl 参数、audio focus、cache、PlayerView）。
 *
 * 后者只能真机验；前者是纯逻辑，但混在池里就验不了 —— 造一个 [ExoPlayer]
 * 需要真实 Looper 与图形栈，而 JVM 单测里造不出来，本工程又**禁止**用
 * `returnDefaultValues = true` 绕（方案 §5.4 的「假绿色」）。
 *
 * 抽出账本后，四条**会静默出错**的不变量就能在 JVM 上钉死：
 * 池满降级、外来归还、重复归还、release 泄漏。这四条错了都不报错，
 * 只表现为「反复进出后视频不再播」（解码器泄漏）或提前 OOM。
 *
 * @param T 载荷类型；生产是 `ExoPlayer`，测试传任意对象。
 *   **按 identity 记账**（不是 equals）—— 播放器是有状态资源，
 *   两个"相等"的实例仍是两份解码器。
 */
internal class ScreenPlayerLedger<T : Any>(val capacity: Int) {

    private val idle = ArrayDeque<T>()
    private val borrowed =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap<T, Boolean>())

    private var released = false

    /** 借出 + 空闲的总数。 */
    val aliveCount: Int get() = borrowed.size + idle.size

    /** 当前借出数 —— 有界保证的直接观测点。 */
    val borrowedCount: Int get() = borrowed.size

    val idleCount: Int get() = idle.size

    val isReleased: Boolean get() = released

    /**
     * 取一个可用载荷：优先复用空闲的，否则用 [create] 新建。
     *
     * @return null 表示**该拒绝**（池满或已释放），调用方降级、**不得**自建绕过。
     */
    fun borrow(create: () -> T): T? {
        if (released) return null
        if (borrowed.size >= capacity) return null
        val item = idle.removeLastOrNull() ?: create()
        borrowed.add(item)
        return item
    }

    /**
     * 归还。
     *
     * @return [Recycle.ACCEPTED] 回到空闲池；[Recycle.RELEASE_OVERFLOW] 超容量，
     *   调用方要真正销毁它；[Recycle.REJECTED_UNKNOWN] 不是本账本借出的或已归还过
     *   —— 调用方**什么都别做**（销毁别人的实例同样是缺陷）；
     *   [Recycle.RELEASE_AFTER_SHUTDOWN] 账本已释放，调用方销毁它。
     */
    fun recycle(item: T): Recycle {
        if (released) return Recycle.RELEASE_AFTER_SHUTDOWN
        if (!borrowed.remove(item)) return Recycle.REJECTED_UNKNOWN
        if (idle.size >= capacity) return Recycle.RELEASE_OVERFLOW
        idle.addLast(item)
        return Recycle.ACCEPTED
    }

    /**
     * 释放：返回**所有**需要销毁的载荷（空闲的 + 仍借出的）。
     *
     * ⚠️ 借出的也必须返回给调用方销毁 —— 只清空闲、把借出数归零，
     * 会让漏 dispose 的实例继续活着而账面为 0（解码器泄漏，不报错）。
     */
    fun release(): List<T> {
        released = true
        val all = ArrayList<T>(idle.size + borrowed.size)
        all.addAll(idle)
        all.addAll(borrowed)
        idle.clear()
        borrowed.clear()
        return all
    }

    enum class Recycle { ACCEPTED, RELEASE_OVERFLOW, REJECTED_UNKNOWN, RELEASE_AFTER_SHUTDOWN }
}
