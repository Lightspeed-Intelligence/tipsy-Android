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
 * ## 共享键的读写方向表（**加新读写点前先查这张表**）
 *
 * 这张表是 §2.37 之后系统扫一遍的结果。扫的动因：**「壳读得对、但改动
 * 没回写共享信封，于是被 RN 的旧值倒灌」已经出现两例**（性别筛选 §2.23.1、
 * 账号语言 §2.37）。两例形状完全相同，共同症状是「改了、看起来生效了、
 * 过一会儿又回去了」—— **用户不会报**，所以必须按方向逐个核，不能碰一个修一个。
 *
 * | key / 字段 | 真值在哪 | 壳读 | 壳写 | 不成对会怎样 |
 * | --- | --- | --- | --- | --- |
 * | `token-storage` | 壳（唯一刷新者） | ✅ | ✅ [MmkvTokenPersistence] | — |
 * | `user-storage.languageCode` | 后端 | ✅ [AccountLanguageReader] | ✅ [ai.lightspeed.tipsy.shell.i18n.AccountLanguageWriter] | **已修**（§2.37）：只写服务端 → 被信封旧值倒灌回英文 |
 * | `user-storage` 其余字段 | 后端 | 只读 `languageCode` | ❌ | 壳不消费，无风险；将来要写照 `AccountLanguageWriter` 的 merge |
 * | `config-persist-storage.gender` | 本地偏好 | ✅ | ✅ `HomeFilterStore` | ⚠️ **待 owner**（§2.23.1）：信封缺失时刻意不写 → 全新安装永不持久化 |
 * | `config-persist-storage.nsfw` | **后端** `user.nsfw` | ✅ | ❌ 刻意无 `writeNsfw` | 壳写会破坏 RN 的单向镜像流（关了自己开回来）。写方是 Settings 的 `POST /user/nsfw`，本地镜像由 RN 订阅补 |
 * | `config-persist-storage.chatPageType` | 本地偏好 | ✅ | ✅ `ChatPageTypeStore` | 同 gender 的信封缺失问题（继承，非新增） |
 * | `chat_draft_lru` | **RN ChatDetail** | ✅ 只读 | ❌ | 壳写会与 RN 的 LRU 淘汰打架 |
 * | `multi-cinema-conv-epoch:<id>` | 壳删除动作 | ❌ | ✅ `ChatListCache` | 壳删会话后**必须写**，不写会让重进影院假命中旧剧情 |
 * | `chat-persist-storage` / `chat-background-storage` | RN Surface | ❌ | ❌ | 方案 §4.1 明确归 RN |
 *
 * ⚠️ **写 Zustand 信封（`{state, version}`）一律 merge，不得整体覆盖** ——
 * 覆盖会静默清掉同信封里其余二十多个字段。本类只提供 [getString] /
 * [putString] 这对**原始**读写，**merge 语义由调用方负责**（见
 * `mergeGenderIntoEnvelope` / `AccountLanguageWriter.merge`）——
 * 这里不提供"改某个字段"之类的便利方法，免得有人绕过 merge 直接覆盖信封。
 *
 * ⚠️ **写完共享信封要发 `onUserStoreChanged`**：常驻 JS runtime 已 hydrate 过
 * 对应 store，直接改 MMKV 它不会知道。见 [ai.lightspeed.tipsy.shell.i18n.AccountLanguageMirror]。
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
