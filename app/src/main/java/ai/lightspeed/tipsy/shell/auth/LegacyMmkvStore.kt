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
 * ## 读为主，写受严格约束
 *
 * 方案 §2.4 要求「迁移失败不得清空旧值」，§4.6 要求「写 Zustand persist 信封
 * 必须 merge 而非覆盖」。所以本类只提供 [getString] / [putString] 这对**原始**
 * 读写，**信封的 merge 语义由调用方负责**（如 `HomeFilterStore`）——
 * 这里不提供"改某个字段"之类的便利方法，免得有人绕过 merge 直接覆盖信封。
 *
 * ⚠️ 每个 key 的可写性各不相同（§4.6 owner 列）：`token-storage` 归
 * [MmkvTokenPersistence]，`chat-persist-storage` 归 RN Surface（壳不得写），
 * `config-persist-storage` 里 `gender` 可写而 `nsfw` 只读。
 * **加新写入点前先查那张表。**
 */
class LegacyMmkvStore private constructor(private val mmkv: MMKV?) {

    /** 读原始字符串；实例不可用或 key 不存在时返回 null。 */
    fun getString(key: String): String? = mmkv?.decodeString(key)

    /**
     * 写原始字符串。
     *
     * @return 是否写入成功。MMKV 不可用时返回 false 而**不抛** ——
     *   持久化失败不该让页面崩溃。调用方据此决定是否提示，但通常
     *   内存状态仍应生效（见 `HomeFilterStore.writeGender` 的说明）。
     */
    fun putString(key: String, value: String): Boolean {
        val instance = mmkv ?: run {
            Log.w(TAG, "MMKV 不可用，写入被跳过：key=$key")
            return false
        }
        return instance.encode(key, value)
    }

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
            // ⚠️ 目录不存在时**建目录**，不要返回不可用实例。
            //
            // 原实现在此直接 `return LegacyMmkvStore(null)`（"全新安装的正常情况"），
            // 而调用方 `TipsyApplication` 把本实例 `by lazy` 缓存到**进程结束** ——
            // 全新安装时 bootstrapI18n() 先打开这里（目录还不存在，缓存成不可用），
            // 随后 MmkvTokenPersistence 才建目录。结果整个进程内本实例永久不可用：
            // 首次登录写入账号语言后仍读不到，且 W2 起 HomeFilterStore 的 gender
            // **永久写不进去**（进度文档 §2.16 记录的后续风险，现在有了写入点就是真 bug）。
            //
            // 建目录是安全的：MMKV 自己也会建，且与 MmkvTokenPersistence.open 一致。
            // 全新安装下打开一个空实例，读返回 null（与"没数据"同义），行为不变。
            val mmkv = runCatching {
                if (!dir.isDirectory) {
                    Log.i(TAG, "RN MMKV 目录不存在，创建（全新安装）")
                    dir.mkdirs()
                }
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
