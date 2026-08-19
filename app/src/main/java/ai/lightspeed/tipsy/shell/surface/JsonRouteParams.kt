package ai.lightspeed.tipsy.shell.surface

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON → Surface route params 的结构转换（P5 编辑透传用）。
 *
 * ## 为什么存在
 *
 * 编辑角色要把**完整角色对象**原封透传给 `CreateSurface` 的 `editCharacter`
 * prop（iOS 契约 `create-rn-surface-contract.md` §3：by-id 重拉会在保存时
 * 把 `conversation_style`/`custom_prompt` 等字段重置 = 数据损坏）。
 * 该对象含嵌套对象、数组与 null —— 超出 `SurfaceProps` 手写 map 的形状，
 * 需要一个保真的结构转换。
 *
 * ## 转换规则（对齐 RN `Arguments.fromBundle`/`fromList` 的还原路径）
 *
 * | JSON | 产出 | JS 侧还原 |
 * | --- | --- | --- |
 * | object | `Map<String, Any>` | 嵌套对象 |
 * | array | `List<Any?>` | 数组 |
 * | `null`（`JSONObject.NULL`） | [JSONObject.NULL] 哨兵 | `null` |
 * | string/bool | 原样 | 原样 |
 * | number | `Int`/`Long`/`Double` 原样 | JS number |
 *
 * ⚠️ **null 不能丢**：`initCharStateUpdate` 展开对象时，「键为 null」与
 * 「键缺失」在 zustand 里语义不同（缺失保留 store 旧值）。丢 null 的表现
 * 是编辑一个清空过某字段的角色时旧值复活。哨兵由
 * `SurfaceContract.putRouteParams` 落成 `putString(key, null)` →
 * `Arguments.fromBundle` 的 `putNull`。
 */
object JsonRouteParams {

    /** 把 JSON 对象转成 route params。键序无关（JS 对象无序）。 */
    fun toParams(json: JSONObject): Map<String, Any> {
        val result = LinkedHashMap<String, Any>(json.length())
        for (key in json.keys()) {
            result[key] = convert(json.get(key))
        }
        return result
    }

    private fun convert(value: Any): Any = when (value) {
        is JSONObject -> toParams(value)
        is JSONArray -> toList(value)
        // ⚠️ Number 必须归一：Android 的 org.json 只产 Int/Long/Double，
        // 但 JVM 单测用的真实 org.json 把小数解析成 BigDecimal、超长整数解析成
        // BigInteger —— 不归一它们会撞进 putRouteParams 的“不支持类型”异常
        //（设备上不出现，单测先红是刻意的：归一让两个环境行为一致）。
        // JS 侧 number 本就是 double，Long 由 Arguments.fromBundle 走 putDouble
        is Int, is Long, is Double -> value
        is Number -> {
            val asLong = value.toLong()
            // 整数值且无精度损失 → Long（大 id 不丢精度）；否则 Double
            if (value.toDouble() == asLong.toDouble() &&
                java.math.BigDecimal(value.toString()).stripTrailingZeros().scale() <= 0
            ) {
                asLong
            } else {
                value.toDouble()
            }
        }
        // JSONObject.NULL、String、Boolean 原样透传；
        // putRouteParams 按类型分派（NULL 哨兵在那边落成 null）
        else -> value
    }

    private fun toList(array: JSONArray): List<Any> {
        val result = ArrayList<Any>(array.length())
        for (index in 0 until array.length()) {
            result += convert(array.get(index))
        }
        return result
    }
}
