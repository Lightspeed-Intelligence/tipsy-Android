package ai.lightspeed.tipsy.shell.auth

import android.content.Context
import android.util.Log
import com.tencent.mmkv.MMKV
import java.io.File

/**
 * [ShellTokenStore.TokenPersistence] 的 MMKV 实现（W1-P1）。
 *
 * ## 为什么写回 RN 的 MMKV，而不是壳自己的存储
 *
 * 方案 §2.4 的迁移算法第 2 步要求「写入 Native token store，**保留兼容读**」。
 * 在 P2 完成完整迁移前，壳与 RN 必须看到**同一个** token：
 * - 壳内 Surface 里仍有 JS 代码读 `token-storage`（虽然 `isShellHost()` 后
 *   主路径不读，但 `readPersistedAuthToken` 仍是 RN 侧公开函数）
 * - 覆盖升级回滚场景：用户装回 RN 版本，token 必须还在原处
 *
 * 所以 P1 阶段壳**读写同一个 key**，形态用 RN 当前写入的**裸字符串**
 * （`src/store/auth.ts:68` `storage.set(TOKEN_STORAGE_KEY, token)`）。
 * 读取仍走 [LegacyTokenReader] 的三形态兼容。
 *
 * ⚠️ **只碰 `token-storage` 这一个 key。** `user-storage` / `auth-storage`
 * 是 Zustand persist 信封（`{state, version}`），写它们必须 merge 而非覆盖 ——
 * 那属 P2，这里不做。
 */
class MmkvTokenPersistence private constructor(
    private val mmkv: MMKV?,
) : ShellTokenStore.TokenPersistence {

    override fun read(): String? =
        LegacyTokenReader.parse(mmkv?.decodeString(LegacyTokenReader.TOKEN_STORAGE_KEY))

    /**
     * 写入裸字符串形态；null 表示清除。
     *
     * 与 RN `writePersistedTokenToMmkv` 对齐（`store/auth.ts:60-69`）：
     * 空值走 `remove` 而不是写空串 —— 写空串会让 RN 侧
     * `storage.getString()` 返回 `""` 而非 null，绕过它的 falsy 判断。
     */
    override fun write(token: String?) {
        val instance = mmkv ?: run {
            // MMKV 打不开时不能静默丢弃写入 —— 那会表现为「登录成功但下次启动没了」
            Log.e(TAG, "MMKV 不可用，token 写入被丢弃")
            return
        }
        if (token.isNullOrBlank()) {
            instance.removeValueForKey(LegacyTokenReader.TOKEN_STORAGE_KEY)
        } else {
            instance.encode(LegacyTokenReader.TOKEN_STORAGE_KEY, token)
        }
    }

    val isAvailable: Boolean get() = mmkv != null

    companion object {
        private const val TAG = "MmkvTokenPersistence"

        /** 与 react-native-mmkv 的 `getBaseDirectory()` 一致（方案 §2.4 实测）。 */
        private const val MMKV_SUBDIR = "mmkv"

        /** `createMMKV()` 的默认实例 id。 */
        private const val DEFAULT_INSTANCE_ID = "mmkv.default"

        /**
         * 打开 RN 侧默认实例。**失败返回不可用实例，绝不抛** ——
         * auth 存储打不开必须退化为「未登录」，不能让壳启动崩溃。
         *
         * 与 [LegacyMmkvStore.open] 的区别：本类需要**可写**实例，且在目录
         * 不存在时也要 `initialize`（全新安装首次登录要能写进去）。
         */
        fun open(context: Context): MmkvTokenPersistence {
            val dir = File(context.filesDir, MMKV_SUBDIR)
            val mmkv = runCatching {
                if (!dir.isDirectory) dir.mkdirs()
                MMKV.initialize(context, dir.absolutePath)
                MMKV.mmkvWithID(DEFAULT_INSTANCE_ID)
            }.onFailure {
                // 只记异常类型，**不记内容** —— 里面是 token
                Log.e(TAG, "打开 MMKV 失败: ${it.javaClass.simpleName}")
            }.getOrNull()
            return MmkvTokenPersistence(mmkv)
        }
    }
}
