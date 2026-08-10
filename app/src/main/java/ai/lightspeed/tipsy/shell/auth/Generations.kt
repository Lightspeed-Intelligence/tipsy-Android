package ai.lightspeed.tipsy.shell.auth

import java.util.concurrent.atomic.AtomicLong

/**
 * 双 generation 闸门（W1 计划 §3.3）。
 *
 * **两轨互不替代**，合成一个会同时漏掉两类 bug：
 *
 * | 轨 | 何时自增 | 防什么 |
 * | --- | --- | --- |
 * | [auth] | login / logout / 换号 | 在飞响应把**旧账号**的 token/user/缓存/埋点写进新账号 |
 * | [mutation] | 本地乐观变更（删除/置顶） | 在飞的**旧列表响应复活已删行** |
 *
 * 用法：**发请求前**捕获快照，**回写前**校验匹配。
 * ```
 * val snapshot = generations.snapshot()
 * val result = api.fetch()
 * if (!generations.isValid(snapshot)) return  // 期间换过号 / 改过本地列表 → 丢弃
 * ```
 *
 * ⚠️ **只在回写前校验一次是不够的**：若回写包含多步（写 token → 写 user → 发埋点），
 * 每步之间都可能被 logout 打断。长链路应逐步校验，或整体放进单个临界区。
 *
 * 线程安全：[AtomicLong]。generation 会被 UI 线程、网络回调、桥调用同时读。
 */
class Generations {

    private val authGeneration = AtomicLong(0)
    private val mutationGeneration = AtomicLong(0)

    val auth: Long get() = authGeneration.get()
    val mutation: Long get() = mutationGeneration.get()

    /** login / logout / 换号时调用。返回新值。 */
    fun bumpAuth(): Long = authGeneration.incrementAndGet()

    /** 本地乐观变更（删除/置顶）时调用。返回新值。 */
    fun bumpMutation(): Long = mutationGeneration.incrementAndGet()

    /** 发请求前捕获。 */
    fun snapshot(): Snapshot = Snapshot(auth = auth, mutation = mutation)

    /**
     * 回写前校验。**两轨都必须匹配**。
     *
     * 只校验 auth 轨的话，会放过"删除后在飞的旧列表响应把行复活"；
     * 只校验 mutation 轨的话，会放过"换号后旧响应写错账号"。
     */
    fun isValid(snapshot: Snapshot): Boolean =
        snapshot.auth == auth && snapshot.mutation == mutation

    /** 只关心账号是否变过（不涉及列表写入的场景，如写 token 本身）。 */
    fun isAuthValid(snapshot: Snapshot): Boolean = snapshot.auth == auth

    data class Snapshot(val auth: Long, val mutation: Long)
}
