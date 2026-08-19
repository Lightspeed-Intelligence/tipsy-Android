package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.auth.AuthStateHub
import java.util.concurrent.CopyOnWriteArrayList

/**
 * EditProfileSurface → 原生 Profile 的进程内刷新接力。
 *
 * ## dirty 只能由成功的 `/user/info` 校准清除
 *
 * RN 的成功通知只把当前账号标成 dirty；[pendingAttempt] 是只读快照，不会提前清除。
 * Profile 完成 `/user/info` 后把响应 userId 与**此刻**的 Native token userId 一起交给
 * [acknowledge]。三者同账号、且期间没有更新的 mutation revision 时才清 dirty。
 * 请求失败、换号、旧请求迟到或请求期间又发生 mutation 都不会误清。
 *
 * ## Surface 真正关闭时再做一次最终校准
 *
 * EditProfileSurface 是盖在常驻 Profile Tab 上的 sibling，退出时底下的 Fragment 可能
 * 一直保持 STARTED，根本不再走 `onStart`。[onEditProfileSurfaceVisibilityChanged] 记录
 * 容器的真实出现/消失；只要该 Surface 会话发生过成功 mutation，关闭沿就生成一个
 * 新 revision 并再次唤醒 observer。即使前一轮刷新已成功，也会做这次最终校准。
 *
 * 登录、登出或换号通过 [AuthStateHub.Observer] 清掉 dirty 与关闭重试归属，旧账号信号
 * 绝不能落到新账号。
 */
class ProfileRefreshHub : AuthStateHub.Observer {

    /** 一次刷新尝试绑定的账号与 mutation revision。 */
    data class Attempt internal constructor(
        val ownerUserId: String,
        internal val revision: Long,
    )

    fun interface Observer {
        /** dirty/retry 信号到达；消费方用 [pendingAttempt] 取得当前尝试。 */
        fun onProfileRefreshPending()
    }

    private val observers = CopyOnWriteArrayList<Observer>()
    private val lock = Any()

    private var nextRevision = 0L
    private var pending: Attempt? = null
    private var editProfileSurfaceVisible = false
    /** 每个 revision 最多一次失败后自动重试；第二次失败保留 dirty 等下一事件。 */
    private var automaticRetryRevision: Long? = null

    /**
     * 当前 EditProfile Surface 会话内发生过 mutation 的账号。
     * 早期刷新成功可以清 [pending]，但本字段保留到真实 close，以强制最终校准。
     */
    private var closeRefreshOwnerUserId: String? = null

    fun addObserver(observer: Observer) {
        observers.add(observer)
    }

    fun removeObserver(observer: Observer) {
        observers.remove(observer)
    }

    /**
     * 由桥 provider 调用。[ownerUserId] 必须来自 Native token store，而不是 JS 参数。
     * 每次业务 mutation 都产生新 revision；内存里仍只保留最新一条，天然合并旧请求。
     */
    fun notifyProfileChanged(ownerUserId: String?) {
        val owner = ownerUserId.normalized() ?: return
        val shouldWake = synchronized(lock) {
            val wasAlreadyDirtyForOwner = pending?.ownerUserId == owner
            pending = newAttempt(owner)
            if (editProfileSurfaceVisible) closeRefreshOwnerUserId = owner
            // Surface 内连续 mutation 仍更新 revision（旧响应不得 ack），但合并唤醒，
            // 真实 close 会负责最终校准。Surface 已关闭时不能合并：这时已没有下一次
            // close/onStart，晚到的新 revision 必须自己唤醒消费者。
            !wasAlreadyDirtyForOwner || !editProfileSurfaceVisible
        }
        if (shouldWake) notifyObservers()
    }

    /**
     * 返回当前 Native 账号的 dirty 快照，不改变状态。账号不匹配时返回 null。
     */
    fun pendingAttempt(currentUserId: String?): Attempt? {
        val current = currentUserId.normalized() ?: return null
        return synchronized(lock) { pending?.takeIf { it.ownerUserId == current } }
    }

    /**
     * `/user/info` 成功后的唯一 ack 入口。
     *
     * - [currentUserId]：回调时重新读取的 Native token userId
     * - [refreshedUserId]：本次 `/user/info` 成功响应里的 userId
     * - [attempt]：请求起飞前取得的 revision
     *
     * 三者必须同账号，且 [attempt] 仍是最新 revision；否则 dirty 保留等待重试。
     */
    fun acknowledge(
        attempt: Attempt,
        currentUserId: String?,
        refreshedUserId: String?,
    ): Boolean {
        val current = currentUserId.normalized() ?: return false
        val refreshed = refreshedUserId.normalized() ?: return false
        return synchronized(lock) {
            if (current != attempt.ownerUserId || refreshed != attempt.ownerUserId || pending != attempt) {
                false
            } else {
                pending = null
                automaticRetryRevision = null
                true
            }
        }
    }

    /**
     * `/user/info` 失败或成功响应无法按账号/revision ack 后的有界重试门禁。
     *
     * Surface 仍在时不自动重试：它的真实 close 会生成最终 revision。Surface 已关闭时
     * 没有后续生命周期沿，因此同一 revision 允许一次 completion-driven retry；再次失败
     * 只保留 dirty，等待新 mutation、onStart 或下一次 Surface 会话，绝不形成紧循环。
     */
    internal fun requestRetryAfterUnconfirmedAttempt(
        attempt: Attempt,
        currentUserId: String?,
    ): Boolean {
        val current = currentUserId.normalized() ?: return false
        return synchronized(lock) {
            if (
                current != attempt.ownerUserId ||
                pending != attempt ||
                editProfileSurfaceVisible ||
                automaticRetryRevision == attempt.revision
            ) {
                false
            } else {
                automaticRetryRevision = attempt.revision
                true
            }
        }
    }

    /**
     * 由 Activity 的 back-stack listener 传入真实容器可见性，覆盖桥 pop 与系统返回两条路。
     * `true → false` 才是 close；重复的 false 不触发刷新。
     */
    fun onEditProfileSurfaceVisibilityChanged(
        isVisible: Boolean,
        currentUserId: String?,
    ) {
        val current = currentUserId.normalized()
        val shouldRetry = synchronized(lock) {
            val didClose = editProfileSurfaceVisible && !isVisible
            editProfileSurfaceVisible = isVisible
            if (!didClose) return@synchronized false

            val owner = closeRefreshOwnerUserId ?: pending?.ownerUserId
            closeRefreshOwnerUserId = null
            if (owner == null || owner != current) {
                false
            } else {
                // 即使早期 refresh 已 ack，也在真实 close 生成一个新 revision，确保
                // sibling Profile 不靠 onStart 也能拿到最终服务端状态。
                pending = newAttempt(owner)
                true
            }
        }
        if (shouldRetry) notifyObservers()
    }

    /** 登录成功也可能是换号；不论 userId 是否相同，都开始一个干净会话。 */
    override fun onDidLogin(userId: String?) = clearAccountState()

    /** 登出后不允许留下任何账号私有刷新信号。 */
    override fun onDidLogout() = clearAccountState()

    private fun newAttempt(ownerUserId: String): Attempt =
        Attempt(ownerUserId = ownerUserId, revision = ++nextRevision)

    private fun notifyObservers() {
        observers.forEach { it.onProfileRefreshPending() }
    }

    private fun clearAccountState() {
        synchronized(lock) {
            pending = null
            closeRefreshOwnerUserId = null
            automaticRetryRevision = null
        }
    }

    private fun String?.normalized(): String? = this?.takeIf { it.isNotBlank() }
}

/**
 * 把 Fragment 生命周期、Native 账号与 [ProfileViewModel] 的成功回调收敛成一处。
 * 提成纯 Kotlin 协调器是为了能确定性覆盖「底层 Fragment 始终 STARTED，close 不触发
 * onStart，但必须重试」的 sibling Surface 时序。
 */
internal class ProfileRefreshCoordinator(
    private val hub: ProfileRefreshHub,
    private val currentUserIdProvider: () -> String?,
    private val isStarted: () -> Boolean,
    /** `/user/info` 成功/失败各走一个 completion callback。 */
    private val refresh: (
        onUserInfoRefreshed: (String) -> Unit,
        onUserInfoRefreshFailed: () -> Unit,
    ) -> Unit,
    /** Fragment 注入 `lifecycleScope.launch { yield(); retry() }`，避免同步自取消。 */
    private val scheduleRetry: (retry: () -> Unit) -> Unit,
) : ProfileRefreshHub.Observer {

    override fun onProfileRefreshPending() {
        if (isStarted()) refreshPending()
    }

    /** @return true 表示发现当前账号 dirty 并启动了定向刷新。 */
    fun onStart(): Boolean = refreshPending()

    private fun refreshPending(): Boolean {
        val attempt = hub.pendingAttempt(currentUserIdProvider()) ?: return false
        refresh(
            { refreshedUserId ->
                val acknowledged = hub.acknowledge(
                    attempt = attempt,
                    currentUserId = currentUserIdProvider(),
                    refreshedUserId = refreshedUserId,
                )
                if (!acknowledged) scheduleRetryIfEligible(attempt)
            },
            { scheduleRetryIfEligible(attempt) },
        )
        return true
    }

    private fun scheduleRetryIfEligible(attempt: ProfileRefreshHub.Attempt) {
        if (!hub.requestRetryAfterUnconfirmedAttempt(attempt, currentUserIdProvider())) return
        scheduleRetry {
            // 排队期间若来了新 revision，它已自行唤醒；旧 retry 不得再
            // 取消最新请求。生命周期掉到后台也留给下一次 onStart。
            val current = currentUserIdProvider()
            if (isStarted() && hub.pendingAttempt(current) == attempt) {
                refreshPending()
            }
        }
    }
}
