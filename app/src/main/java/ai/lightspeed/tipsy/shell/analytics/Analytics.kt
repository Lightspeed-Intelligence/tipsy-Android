package ai.lightspeed.tipsy.shell.analytics

/**
 * 埋点 facade（进度文档 §2.17 的**对冲条件**，W2 第一个业务页开工前必须建）。
 *
 * ## 为什么先建 facade 而不是直接接 Qt
 *
 * 2026-08-11 owner 决定把 Qt 接线推迟到业务迁移之后。但两项推迟的成本不对称：
 * Sentry 是单点（一个 `init`），Qt 的**调用点散在每个业务页** —— Home 一个页面
 * 就有 11 个事件。现在不定调用写法，迁完要回头改几十处。
 *
 * 所以业务页照常调 [Analytics.track]，Qt 接上前只落日志。接 Qt 时只改
 * [Sink] 的实现，业务页一行不动。
 *
 * ## ⚠️ 这里刻意**不**遵循「未实现项 debug 抛异常」纪律
 *
 * §2.11 定的两条实现纪律要求未实现能力在 debug 抛 `NotImplementedError`。
 * 埋点是唯一例外 —— 每次事件都抛会让 debug 完全不可用。
 * 代价写在这里：**Qt 接上之前，埋点缺失不会有任何运行期信号**，
 * 只能靠 §4.2 的 root side-effect 清单人工核对。
 *
 * ## uid 注入语义照抄 RN，不要简化
 *
 * RN 的 `sendEvent`（`modules/qt/src/QtAnalytics.ts:404-420`）对 [UID_REQUIRED_EVENTS]
 * 这四个事件做特殊处理：没有 `uid` 参数时，若用户 id 尚未绑定就**排队**
 * （上限 50 条，超出丢最旧），绑定后一次性补 `uid` 冲出。
 *
 * 方案 §8.1 Home 行记的「`character_page_exposure` 需手动补 uid」说的就是这条 ——
 * 但「手动补」只是壳侧没有 JS 那层封装时的下策。这里把封装照搬过来，
 * 业务页就不必各自记得补，漏一处的症状是**该事件永久不上报**（不是报错）。
 */
object Analytics {

    /** 事件的实际出口。接 Qt 时换实现即可。 */
    fun interface Sink {
        fun send(eventId: String, params: Map<String, Any?>, pageName: String?)
    }

    /**
     * 这四个事件缺 `uid` 时必须排队等用户绑定（`QtAnalytics.ts:5-10` 实测）。
     *
     * ⚠️ 名单照抄，**不要按「看起来该带 uid」增删** —— 多加一个会让该事件在
     * 未登录期被排队而不是立即上报（游客期事件就此丢失）。
     */
    private val UID_REQUIRED_EVENTS = setOf(
        "character_page_exposure",
        "chat_page_exposure",
        "orientation_gender_page_exposure",
        "orientation_tag_page_exposure",
    )

    /** `QtAnalytics.ts:12` 的 `MAX_DEFERRED_UID_EVENT_COUNT`。 */
    private const val MAX_DEFERRED_UID_EVENTS = 50

    private data class Deferred(
        val eventId: String,
        val params: Map<String, Any?>,
        val pageName: String?,
    )

    private val lock = Any()
    private val deferred = ArrayDeque<Deferred>()
    private var boundUserId: String? = null
    private var sink: Sink = Sink { _, _, _ -> }

    /** 由 `TipsyApplication` 在 onCreate 装配。 */
    fun install(sink: Sink) {
        synchronized(lock) { this.sink = sink }
    }

    /**
     * 发一个事件。
     *
     * @param pageName Qt 的 pageName 维度；null 走不带 pageName 的重载语义。
     */
    fun track(eventId: String, params: Map<String, Any?> = emptyMap(), pageName: String? = null) {
        // 决策在锁内，**发送在锁外** —— sink 的实现（未来的 Qt SDK）可能同步
        // 回调进来，持锁发送会自锁死
        val currentSink: Sink
        val toSend: Deferred
        synchronized(lock) {
            currentSink = sink
            if (eventId in UID_REQUIRED_EVENTS && params["uid"] == null) {
                val userId = boundUserId
                if (userId == null) {
                    deferred.addLast(Deferred(eventId, params, pageName))
                    // 超限丢**最旧**（对齐 RN 的 slice(-50)）—— 丢最新会让
                    // 刚发生的曝光永远上不去，排查时更难判断
                    while (deferred.size > MAX_DEFERRED_UID_EVENTS) deferred.removeFirst()
                    return
                }
                toSend = Deferred(eventId, params + ("uid" to userId), pageName)
            } else {
                toSend = Deferred(eventId, params, pageName)
            }
        }
        currentSink.send(toSend.eventId, toSend.params, toSend.pageName)
    }

    /**
     * 绑定用户 id 并冲出排队事件（对齐 `flushDeferredUidEvents`）。
     *
     * 登录成功、会话恢复成功都要调。**在锁外发送** —— sink 可能同步回调进来。
     */
    fun bindUserId(userId: String?) {
        val flushing: List<Deferred>
        val currentSink: Sink
        val bound: String?
        synchronized(lock) {
            bound = userId?.takeIf { it.isNotBlank() }
            boundUserId = bound
            currentSink = sink
            flushing = if (bound == null) emptyList() else deferred.toList()
            if (bound != null) deferred.clear()
        }
        // 取局部变量而不是再读 boundUserId：并发登出可能已把它改回 null，
        // 那样这批事件会带一个空 uid 发出去（后端侧表现为归因缺失）
        val resolved = bound ?: return
        flushing.forEach {
            currentSink.send(it.eventId, it.params + ("uid" to resolved), it.pageName)
        }
    }

    /** 登出：解绑 uid。**不清排队** —— 未上报的曝光在下次登录后仍应补齐。 */
    fun unbindUserId() {
        synchronized(lock) { boundUserId = null }
    }

    internal fun resetForTest() {
        synchronized(lock) {
            deferred.clear()
            boundUserId = null
            sink = Sink { _, _, _ -> }
        }
    }

    internal fun deferredCountForTest(): Int = synchronized(lock) { deferred.size }
}
