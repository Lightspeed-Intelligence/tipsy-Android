package ai.lightspeed.tipsy.shell.user

import android.util.Log
import ai.lightspeed.tipsy.shell.auth.Generations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 当前用户信息的进程级持有者（所有 Native 页与 RN Surface 共用）。
 *
 * Android Native 是 token 与 `/user/info` 的 owner，因此成功响应会同时更新内存并经
 * [CurrentUserMirrorLike] merge 到 RN 的 `user-storage`。这与 iOS AuthSession 的职责
 * 对齐；Surface 只负责 rehydrate，不再从 JWT 猜一个最小身份。
 *
 * ## ⚠️ 拉取失败**不清**已有身份
 *
 * [refresh] 失败时保留上一次的 [current]。这是"错了不报错"的一处：
 * 清掉会让用户在一次网络抖动后看到自己的头像昵称突然变空白，
 * 而数据其实还在服务端 —— 与方案 §8.4「失败不清列表」同一条纪律。
 *
 * 真正该清的时机只有**登出或新登录的账号屏障**（[clear]）；网络失败不清。
 */
class CurrentUserStore(
    private val source: UserInfoSource,
    private val generations: Generations? = null,
    private val currentUserId: (() -> String?)? = null,
    private val mirror: CurrentUserMirrorLike = CurrentUserMirrorLike.NOOP,
    private val onUserUpdated: (CurrentUser) -> Unit = {},
    private val logWarn: (String, Throwable?) -> Unit = { m, t -> Log.w(TAG, m, t) },
) {

    private val _current = MutableStateFlow<CurrentUser?>(null)

    /** 当前用户；未拉到 / 已登出时为 null。 */
    val current: StateFlow<CurrentUser?> = _current.asStateFlow()

    /**
     * 拉一次并更新。
     *
     * @param requireSharedSnapshot 是否要求 RN 共享快照也必须成功落盘。登录事务传 true，
     *   避免发布「Native 有用户、RN 无用户」的半登录状态；普通后台刷新保持 false，
     *   镜像失败时仍允许 Native 使用本次网络结果。
     * @return true 表示拿到了可发布的新数据；false 表示请求、响应校验或必需镜像失败。
     *   失败时 [current] 保持原值不动（见类注释）
     */
    suspend fun refresh(requireSharedSnapshot: Boolean = false): Boolean {
        // 账号私有请求发出前捕获 auth generation 与 token subject；响应回来后一起校验。
        // subject 可能缺失，所以它只是附加校验，generation 才是主闸。
        val authSnapshot = generations?.snapshot()
        val expectedUserId = currentUserId?.invoke()
        val fetched = runCatching { source.fetchCurrentUser() }
            .onFailure {
                // ⚠️ 取消不是失败：这里吞掉 CancellationException 会让登出时已在飞的
                // 响应继续执行后续非挂起代码，把旧账号的用户信息写回已清空的状态
                //（「登出串上一账号数据」）。必须原样抛回去，让协程正常终止
                if (it is CancellationException) throw it
                logWarn("拉取 /user/info 失败，保留已有用户信息", it)
            }
            .getOrNull()
        if (fetched == null) return false

        if (authSnapshot != null && generations?.isAuthValid(authSnapshot) != true) {
            logWarn("/user/info 返回时账号已变化，丢弃旧响应", null)
            return false
        }
        if (expectedUserId != null && fetched.userId != expectedUserId) {
            logWarn("/user/info userId 与当前 token subject 不一致，拒绝发布", null)
            return false
        }

        // 下列步骤均为同步、无挂起操作；生产调用方在 Main.immediate 上恢复，避免
        // generation 校验与发布之间被另一次 login/logout 插入。
        val mirrored = mirror.write(fetched)
        if (requireSharedSnapshot && !mirrored) {
            logWarn("登录所需 user-storage 快照写入失败，拒绝发布半登录状态", null)
            return false
        }

        _current.value = fetched
        onUserUpdated(fetched)
        return true
    }

    /**
     * 清空 —— **只在登出或新登录账号屏障时调**。
     *
     * 不要在拉取失败时调这个，理由见类注释。
     */
    fun clear() {
        _current.value = null
    }

    private companion object {
        const val TAG = "CurrentUserStore"
    }
}
