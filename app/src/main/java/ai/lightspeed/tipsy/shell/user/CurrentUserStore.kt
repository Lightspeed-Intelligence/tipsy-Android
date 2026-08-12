package ai.lightspeed.tipsy.shell.user

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 当前用户信息的进程内持有者（Profile / 后续 Settings 的数据源）。
 *
 * ## 只在内存，不持久化 —— 这是刻意的
 *
 * RN 侧 user store 是 `persist` 的（`store/user.ts:82`，信封 `user-storage`），
 * 但壳**不写**那个信封：
 * - `AccountLanguageReader` 已定边界「壳只读 `user-storage`」，写它就要按 §4.6
 *   做 merge（整体覆盖会破坏 Zustand 信封里其余二十多个字段 ——
 *   与 §2.23 `config-persist-storage` 同一类事故）
 * - 壳这一刀不需要跨进程存活：Profile 每次进页面都会拉 `/user/info`
 *
 * 代价写在这里免得当成 bug：**冷启动首次进 Profile 会有一次 loading**
 * （没有本地镜像可先上屏）。RN 侧因为有 persist 所以是秒显。
 * 如果后续要消掉这个 loading，正确做法是**读** RN 已有的 `user-storage` 镜像
 * 作种子（同 `HomeForYouCache` 的思路），而不是让壳去写那个信封。
 *
 * ## ⚠️ 拉取失败**不清**已有身份
 *
 * [refresh] 失败时保留上一次的 [current]。这是"错了不报错"的一处：
 * 清掉会让用户在一次网络抖动后看到自己的头像昵称突然变空白，
 * 而数据其实还在服务端 —— 与方案 §8.4「失败不清列表」同一条纪律。
 *
 * 真正该清的时机只有**登出**（[clear]），由 `AuthStateHub.didLogout` 驱动。
 */
class CurrentUserStore(
    private val source: UserInfoSource,
    private val logWarn: (String, Throwable?) -> Unit = { m, t -> Log.w(TAG, m, t) },
) {

    private val _current = MutableStateFlow<CurrentUser?>(null)

    /** 当前用户；未拉到 / 已登出时为 null。 */
    val current: StateFlow<CurrentUser?> = _current.asStateFlow()

    /**
     * 拉一次并更新。
     *
     * @return true 表示拿到了新数据；false 表示失败或响应残缺
     *   —— **两种情况下 [current] 都保持原值不动**（见类注释）
     */
    suspend fun refresh(): Boolean {
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
        _current.value = fetched
        return true
    }

    /**
     * 清空 —— **只在登出时调**。
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
