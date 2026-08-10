package ai.lightspeed.tipsy.shell.network

import org.json.JSONObject

/**
 * 标量类型漂移容错（W1-P6，方案 §4.5 要求「放在统一序列化层」）。
 *
 * ## 为什么需要
 *
 * 后端偶发把 number 发成 string（`"123"` 而非 `123`），或 bool 发成 `0/1`、
 * `"true"`。JS 是弱类型，`response.data.count` 拿到 `"123"` 后续运算大多仍能跑通，
 * 所以 **RN 侧从未暴露这个问题**。Kotlin 严格类型下 `getInt()` 会直接抛。
 *
 * 症状很难查：不是稳定失败，而是**某个字段偶发导致整个列表解析失败** →
 * 用户看到空列表，而后端明明返回了数据。这类「空列表伪装成没有结果」正是
 * 方案 §4.5 点名要避免的。
 *
 * ## 为什么放在统一层而不是各调用点
 *
 * 放各处意味着每个新字段都要记得容错，漏一个就是一个偶发空列表。
 * 收在这里，所有解析共享同一套宽松规则。
 *
 * ⚠️ **宽松只针对「类型漂移」，不针对「值缺失」**。字段真的不存在时返回 null，
 * 由调用方决定是用默认值还是当作错误 —— 不要在这里替业务做决定。
 */
object ScalarCoercion {

    /**
     * 读整数，容忍字符串形态。
     *
     * 接受：`123`、`"123"`、`123.0`（整值浮点）、`" 123 "`（带空白）
     * 拒绝：`"abc"`、`123.5`（非整值，静默截断会改变语义）、缺失、JSON null
     */
    fun optInt(json: JSONObject, key: String): Int? {
        if (!json.has(key) || json.isNull(key)) return null
        return when (val raw = json.opt(key)) {
            is Int -> raw
            is Long -> raw.toInt()
            is Number -> {
                val d = raw.toDouble()
                // 非整值不静默截断 —— 3.7 变成 3 是个错值
                if (d % 1.0 == 0.0) d.toInt() else null
            }
            is String -> raw.trim().let { s ->
                s.toIntOrNull() ?: s.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }?.toInt()
            }
            else -> null
        }
    }

    /** 读长整数，容忍字符串形态（时间戳常见）。 */
    fun optLong(json: JSONObject, key: String): Long? {
        if (!json.has(key) || json.isNull(key)) return null
        return when (val raw = json.opt(key)) {
            is Long -> raw
            is Int -> raw.toLong()
            is Number -> raw.toDouble().let { if (it % 1.0 == 0.0) it.toLong() else null }
            is String -> raw.trim().let { s ->
                s.toLongOrNull() ?: s.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }?.toLong()
            }
            else -> null
        }
    }

    /** 读浮点，容忍字符串形态。 */
    fun optDouble(json: JSONObject, key: String): Double? {
        if (!json.has(key) || json.isNull(key)) return null
        return when (val raw = json.opt(key)) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else -> null
        }
    }

    /**
     * 读布尔，容忍数字与字符串形态。
     *
     * 接受：`true`/`false`、`1`/`0`、`"true"`/`"false"`（大小写不敏感）、`"1"`/`"0"`
     *
     * ⚠️ **不把任意非零数字当 true**：只认 0 与 1。后端发 `2` 说明语义变了
     * （可能是枚举而非布尔），当作 null 交给调用方，比猜成 true 安全。
     */
    fun optBoolean(json: JSONObject, key: String): Boolean? {
        if (!json.has(key) || json.isNull(key)) return null
        return when (val raw = json.opt(key)) {
            is Boolean -> raw
            is Number -> when (raw.toInt()) {
                0 -> false
                1 -> true
                else -> null
            }
            is String -> when (raw.trim().lowercase()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }
            else -> null
        }
    }

    /**
     * 读字符串，容忍数字形态（id 常见：`123` vs `"123"`）。
     *
     * ⚠️ 对 JSON null 返回 null，**不返回字面量 `"null"`** ——
     * `optString` 的默认行为会返回 `"null"`，那是个静默错值
     * （`LegacyTokenReader` 与 `RefreshTokenApi` 都踩过同一个坑）。
     */
    fun optString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        return when (val raw = json.opt(key)) {
            is String -> raw.takeIf { it.isNotEmpty() && it != "null" }
            is Number, is Boolean -> raw.toString()
            else -> null
        }
    }
}
