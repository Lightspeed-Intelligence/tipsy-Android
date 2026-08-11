package ai.lightspeed.tipsy.shell.i18n

import android.content.Context
import android.util.Log

/**
 * 从 `assets/locales/<code>.json` 读词条表。
 *
 * ## 为什么用 assets 而不是 `res/raw` 或 `strings.xml`
 *
 * - **`strings.xml`**：方案 §4.8 明确排除 —— key 是英文原文（含空格、标点、
 *   `{{}}` 插值），要为 1838 个 key 造合法资源名，且 RN 侧每次改文案都要重映射。
 * - **`res/raw`**：资源名不允许连字符，`zh-tw` / `pt-br` 得改名再映射一层。
 *   assets 的文件名可以直接用 i18next 语言码，与导出脚本产出一一对应。
 * - 另外 `res/values-<qualifier>` 那套系统语言限定符也不适用：壳的语言真值来自
 *   **账号** `language_code`（服务端），不是设备 locale，让系统按 locale 挑资源
 *   反而会与账号语言打架。
 *
 * 读失败一律返回 null，由 [L10n] 回退英文表 —— 缺一个语言不该让壳崩。
 */
class AssetLocaleLoader(private val context: Context) : L10n.TableLoader {

    override fun load(languageCode: String): LocaleTable? {
        val path = "$LOCALES_DIR/$languageCode.json"
        return runCatching {
            context.assets.open(path).use { stream ->
                LocaleTable.parse(stream.readBytes().decodeToString())
            }
        }.onFailure {
            // 正常缺失（未导出的语言）与真实 IO 错误都走这里。
            // 记类型即可 —— 词条内容不敏感，但没必要往日志灌 1838 条
            Log.w(TAG, "读取词条表失败：$path（${it.javaClass.simpleName}）")
        }.getOrNull()
    }

    private companion object {
        const val TAG = "AssetLocaleLoader"

        /** 与导出脚本的输出目录约定一致。 */
        const val LOCALES_DIR = "locales"
    }
}
