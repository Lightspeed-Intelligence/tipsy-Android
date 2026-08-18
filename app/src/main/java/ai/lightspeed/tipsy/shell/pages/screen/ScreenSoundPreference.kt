package ai.lightspeed.tipsy.shell.pages.screen

import ai.lightspeed.tipsy.shell.auth.LegacyMmkvStore
import android.util.Log
import org.json.JSONObject

/**
 * Screen 视频的声音开关（W4-P2）。
 *
 * ## ⚠️ 这是一处**待 owner 定**的所有权例外，别照抄它去读别的 RN 私有键
 *
 * RN 的真值 `videoSoundEnabled` 住在 **`chat-persist-storage`**
 * （`chat_persist.ts:24`，默认 **`true`（开声）**，`:137`），而方案 §4.6 / §4.1
 * 把整个 `chat-persist-storage` 划给 **RN Surface**，[LegacyMmkvStore] 的结论表里
 * 这一行原本是 **壳读 ❌ / 壳写 ❌**。
 *
 * 原生 Screen 页要播视频就必须知道这个开关的值（否则默认静音还是开声都是猜，
 * 而 RN 默认开声），所以本类把那一行改成 **只读 `videoSoundEnabled` ✅ / 写 ❌**，
 * 并已同步改 [LegacyMmkvStore] 的表。
 *
 * ## 为什么只读是安全的（而写不是）
 *
 * §2.23.1（性别筛选）和 §2.37（账号语言）两例倒灌的形状完全相同：
 * **壳写了共享信封但没 merge / 没回写 / 没通知 RN**。纯读**不可能**产生这一类缺陷
 * —— 读到的最坏情况是过期值，不会破坏 RN 的状态。所以三条路里只读是唯一
 * 「不新开写口子」的选项：
 *
 * | 路 | 行为对等 | 风险 |
 * | --- | --- | --- |
 * | **1 只读（本实现）** | 初值对，页内切换不持久化 | 无写风险；用户在 Screen 调的声音下次进来回落 |
 * | 2 壳内独立键 | 初值可能与 RN 不一致 | Screen 与 ChatDetail Surface 声音状态分叉 |
 * | 3 允许壳写这一个键 | 完全对等 | 新开一个 Native→RN 私有键的写口子，要按 §2.38 重核 merge |
 *
 * ⚠️ **TODO(owner)**：owner 若选路 2/3，改这里 + [LegacyMmkvStore] 的表 + 进度文档。
 * 选 3 之前必须先核 `chat-persist-storage` 有没有自定义 `merge`
 * —— §2.38 的教训是 `config-persist-storage` 有而 `user-storage` 没有，**结论不可外推**。
 *
 * ## 读法
 *
 * Zustand persist 信封 `{state: {...}, version: n}`，取 `state.videoSoundEnabled`。
 * 缺键 / 解析失败一律回落 [DEFAULT_SOUND_ENABLED]（= RN 的默认值 `true`），
 * **不回落 false** —— 静音是「看起来能用但不对」的那类偏差，用户未必意识到是 bug。
 */
object ScreenSoundPreference {

    /** 对齐 RN `chat_persist.ts:137` 的 `videoSoundEnabled: true`。 */
    const val DEFAULT_SOUND_ENABLED = true

    private const val TAG = "ScreenSoundPreference"
    private const val ENVELOPE_KEY = "chat-persist-storage"
    private const val FIELD = "videoSoundEnabled"

    /**
     * 读当前声音开关。
     *
     * ⚠️ 只读，**没有对应的写方法，也不要加** —— 见类注释的所有权说明。
     */
    fun read(store: LegacyMmkvStore): Boolean = parse(store.getString(ENVELOPE_KEY))

    /**
     * 解析信封（纯函数，与 [AccountLanguageReader] 同形，便于单测）。
     *
     * 任何异常路径都回落 [DEFAULT_SOUND_ENABLED]（`true`）而**不是 false**：
     * 静音属于「看起来能用但不对」，用户未必意识到是缺陷。
     */
    internal fun parse(raw: String?, onParseError: (Throwable) -> Unit = ::logParseError): Boolean {
        if (raw.isNullOrBlank()) return DEFAULT_SOUND_ENABLED
        return runCatching {
            val state = JSONObject(raw).optJSONObject("state")
                ?: return@runCatching DEFAULT_SOUND_ENABLED
            // ⚠️ 必须先 has() 再 optBoolean()：缺键时 optBoolean 的默认值参数
            // 与「显式存了 false」无法区分，而这两种情况的正确答案相反
            if (!state.has(FIELD)) DEFAULT_SOUND_ENABLED else state.optBoolean(FIELD, DEFAULT_SOUND_ENABLED)
        }.getOrElse { error ->
            // 信封损坏不是致命错误：回落默认值继续播，别让声音开关搞崩大屏页
            onParseError(error)
            DEFAULT_SOUND_ENABLED
        }
    }

    /**
     * 默认的错误上报。
     *
     * ⚠️ 之所以把它做成可注入的参数而不是直接调 [Log]：`android.util.Log` 在
     * JVM 单测里是**抛异常的 stub**，而本工程明确禁止用
     * `returnDefaultValues = true` 绕（方案 §5.4 的「假绿色」）。
     * 唯一剩下的干净办法就是让测试传一个不碰 Android API 的收集器。
     */
    private fun logParseError(error: Throwable) {
        Log.w(TAG, "chat-persist-storage 解析失败，回落默认开声", error)
    }
}
