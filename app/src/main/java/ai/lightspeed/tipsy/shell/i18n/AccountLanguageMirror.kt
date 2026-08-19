package ai.lightspeed.tipsy.shell.i18n

import ai.lightspeed.tipsy.shell.user.UserStorageRepository
import ai.lightspeed.tipsy.shell.user.UserStorageSnapshot
import android.util.Log
import expo.modules.tipsyauth.TipsyAuthRegistry
import org.json.JSONObject

/**
 * 账号语言镜像的写入接缝（进度文档 §2.37 的 FAIL 项）。
 *
 * 为什么要接缝而不是让 ViewModel 直接调 [AccountLanguageWriter]：
 * `LegacyMmkvStore` 与 `TipsyAuthRegistry` 在 JVM 单测里都不可用
 * （前者要 MMKV native，后者要 Expo runtime）。同 `HomeFilters` /
 * `ChatPageTypeStoreLike` 的理由。
 */
interface AccountLanguageMirrorLike {
    /**
     * 把语言写进共享信封并通知长驻 JS runtime。
     *
     * @return 是否真的写进去了。false **不应**让调用方回滚 UI 语言 ——
     *   语言已经切了，回滚会让用户看到「选了又跳回去」，比丢一次镜像更糟
     *   （同 `HomeFilterStore.writeGender` 的约定）。
     */
    fun writeLanguage(languageCode: String): Boolean
}

/**
 * 生产实现：merge 写 MMKV → 发 `onUserStoreChanged`。
 *
 * ⚠️ **两步的顺序不能反**。先写盘再通知：反过来的话 JS 收到事件时读到的
 * 还是旧值，而 rehydrate 不会自己重试 —— 表现为「Surface 内语言仍是旧的，
 * 直到下次重开」。
 */
class AccountLanguageMirror(
    private val repository: UserStorageRepository,
    /** 当前账号 id（`onUserStoreChanged` 的 payload 契约要求）。 */
    private val currentUserId: () -> String?,
) : AccountLanguageMirrorLike {

    override fun writeLanguage(languageCode: String): Boolean {
        val written = repository.merge(
            JSONObject().put(UserStorageSnapshot.FIELD_LANGUAGE_CODE, languageCode),
        )
        if (!written) {
            Log.w(TAG, "user-storage 写入失败，语言镜像本次未持久化（内存语言已切）")
            return false
        }

        // 常驻 JS runtime 已 hydrate 过 user store，改 MMKV 它不会知道 ——
        // 必须显式通知重读（index.surfaces.js:72-76 已有监听）。
        //
        // ⚠️ 未登录时不发：payload 契约是 `{ userId: String }`，而未登录本就
        // 没有账号语言可镜像。写盘仍然做了（下次登录前壳自己读得到）
        val userId = currentUserId()
        if (userId == null) {
            Log.w(TAG, "无当前账号，跳过 onUserStoreChanged（信封已写）")
            return true
        }
        TipsyAuthRegistry.notifyUserStoreChanged(userId)
        return true
    }

    private companion object {
        const val TAG = "AccountLanguageMirror"
    }
}
