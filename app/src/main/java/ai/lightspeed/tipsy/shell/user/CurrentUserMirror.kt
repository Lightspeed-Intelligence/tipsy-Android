package ai.lightspeed.tipsy.shell.user

import android.util.Log
import expo.modules.tipsyauth.TipsyAuthRegistry

/** `/user/info` 成功后发布到 RN 共享 user store 的接缝。 */
fun interface CurrentUserMirrorLike {
    /**
     * @return 是否已持久化。普通刷新失败不阻止 Native 上屏；登录事务会把失败视为失败。
     */
    fun write(user: CurrentUser): Boolean

    companion object {
        val NOOP = CurrentUserMirrorLike { true }
    }
}

/** 生产实现：完整字段 merge 写盘后，再通知常驻 JS runtime rehydrate。 */
class CurrentUserMirror(
    private val repository: UserStorageRepository,
    private val notifyChanged: (String) -> Unit = { userId ->
        TipsyAuthRegistry.notifyUserStoreChanged(userId)
    },
) : CurrentUserMirrorLike {

    override fun write(user: CurrentUser): Boolean {
        val snapshot = user.sharedStorageSnapshot ?: run {
            Log.w(TAG, "/user/info 用户缺共享快照，跳过 user-storage 发布")
            return false
        }
        if (!repository.merge(snapshot.fields())) {
            Log.w(TAG, "user-storage 写入失败，Surface 本次无法 rehydrate 新用户")
            return false
        }

        // 顺序不能反：事件到达时，MMKV 必须已经是新快照。
        notifyChanged(user.userId)
        return true
    }

    private companion object {
        const val TAG = "CurrentUserMirror"
    }
}
