package ai.lightspeed.tipsy.shell.i18n

import org.json.JSONObject

/**
 * 一个语言的词条表（key = 英文原文 → 译文）。
 *
 * 表内容由 `tipsy-app` 的 `scripts/export-shell-locales.mjs` 从 RN locale JSON
 * 按 `SHELL_KEYS` 白名单抽取而来 —— **不得手编**。该脚本与 keys 清单
 * **双壳共用**（iOS 与 Android 同一份，2026-08-11 决定）。
 *
 * ## ⚠️ `en` 也必须查表
 *
 * 存在 **key ≠ 英文值** 的词条（iOS 注释记录的实例：`Currently unavailable`
 * → `More to come`）。实测 `en.json` 里 1838 个 key 中有 94 个如此。
 * 所以**不能拿 key 当英文文案** —— 那会让这批词条在英文下显示错误文案，
 * 且因为「看起来像正常英文」而不会被发现。
 */
class LocaleTable private constructor(private val entries: Map<String, String>) {

    val size: Int get() = entries.size

    operator fun get(key: String): String? = entries[key]

    companion object {

        val EMPTY = LocaleTable(emptyMap())

        /**
         * 解析导出脚本产出的扁平 JSON。
         *
         * **宽松逐值解析**（与 iOS `L10n.swift:120-131` 同一决定）：导出脚本保证
         * 扁平 `String → String`，但万一出现异常值（嵌套对象/数字），
         * 整表 cast 失败会让**该语言全量静默回退英文** —— 那是「一个坏词条
         * 毁掉整个语言」。逐值跳过只丢那一条。
         */
        fun parse(json: String): LocaleTable {
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: return EMPTY
            val map = LinkedHashMap<String, String>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                // isNull 判定必要：optString 对 JSON null 返回字面量 "null"
                // （同 LegacyTokenReader 踩过的坑），会把它当成合法译文
                if (obj.isNull(key)) continue
                val value = obj.optString(key)
                if (value.isNotEmpty()) map[key] = value
            }
            return LocaleTable(map)
        }
    }
}
