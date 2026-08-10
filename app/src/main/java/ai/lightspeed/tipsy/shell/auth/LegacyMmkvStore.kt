package ai.lightspeed.tipsy.shell.auth

import android.content.Context
import android.util.Log
import com.tencent.mmkv.MMKV
import java.io.File

/**
 * 访问 RN 侧写的 MMKV（方案 §2.4）。
 *
 * ## 为什么能直读
 *
 * `react-native-mmkv` 的 `HybridMMKVPlatformContext.getBaseDirectory()` 返回
 * `context.filesDir.absolutePath + "/mmkv"`（已核实），默认实例 id 是
 * `mmkv.default`（`MMKVFactory.nitro.d.ts` 的 `@default`）。
 * 同 applicationId 覆盖升级时数据目录被继承，所以壳用**同版本原生 MMKV**
 * 就能打开同一批文件。
 *
 * ## ⚠️ 版本必须对齐
 *
 * RN 侧用的是 fork **`io.github.zhongwuzw:mmkv:2.2.4`**，不是腾讯官方
 * `com.tencent:mmkv`（已核实 `react-native-mmkv/android/build.gradle:142`）。
 * 但包名仍是 `com.tencent.mmkv` —— 所以 import 看起来是官方的，实际来自 fork。
 * **升 RN 侧 MMKV 时必须同步 `libs.versions.toml` 的 `mmkv` 版本**；
 * 版本漂移的症状是「读不到 token / 静默当作未登录」，不会报错。
 *
 * ## 只读语义
 *
 * 本类**只读不写**。方案 §2.4 要求「迁移失败不得清空旧值」，且写入 Zustand
 * persist 信封必须 merge 而非覆盖 —— 那属于后续步骤，不在这里做，
 * 免得一个"顺手写一下"破坏掉回滚能力。
 */
class LegacyMmkvStore private constructor(private val mmkv: MMKV?) {

    /** 读原始字符串；实例不可用或 key 不存在时返回 null。 */
    fun getString(key: String): String? = mmkv?.decodeString(key)

    /** 用于诊断：MMKV 是否真的打开了（区分「没数据」与「打不开」）。 */
    val isAvailable: Boolean get() = mmkv != null

    /** 遗留 token；已按三种历史形态解析。 */
    fun readLegacyToken(): String? =
        LegacyTokenReader.parse(getString(LegacyTokenReader.TOKEN_STORAGE_KEY))

    companion object {
        private const val TAG = "LegacyMmkvStore"

        /** 与 react-native-mmkv 的 `getBaseDirectory()` 一致。 */
        private const val MMKV_SUBDIR = "mmkv"

        /** `createMMKV()` 的默认实例 id。 */
        private const val DEFAULT_INSTANCE_ID = "mmkv.default"

        /**
         * 打开 RN 侧的默认 MMKV 实例。
         *
         * **失败一律返回不可用实例，绝不抛** —— 迁移读取失败必须退化为
         * 「当作未登录」，而不是让壳启动崩溃。方案 §2.4：迁移失败回退未登录 UI，
         * 不清空旧值。
         */
        fun open(context: Context): LegacyMmkvStore {
            val dir = File(context.filesDir, MMKV_SUBDIR)
            if (!dir.isDirectory) {
                // 全新安装的正常情况，不是错误
                Log.i(TAG, "无 RN MMKV 目录（全新安装或未曾写入）")
                return LegacyMmkvStore(null)
            }
            val mmkv = runCatching {
                MMKV.initialize(context, dir.absolutePath)
                MMKV.mmkvWithID(DEFAULT_INSTANCE_ID)
            }.onFailure {
                // 只记类型与消息，**不记内容** —— 里面是 token
                Log.e(TAG, "打开 RN MMKV 失败: ${it.javaClass.simpleName}")
            }.getOrNull()
            return LegacyMmkvStore(mmkv)
        }
    }
}
