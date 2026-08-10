package ai.lightspeed.tipsy.shell.network

import org.json.JSONObject

/**
 * 统一响应信封 `{code, msg, data}`（W1-P6，方案 §4.5）。
 *
 * ## ⚠️ HTTP 200 + `code != 0` 是常见组合
 *
 * 后端把业务错误放在 envelope 的 `code` 里，HTTP 状态码仍是 200。
 * **只看 HTTP 状态码会把失败当成功** —— 这是接网络层时最容易漏的一条。
 *
 * ## 已知业务码不得压平成通用错误
 *
 * 方案 §4.5：这几个码调用方要能分别处理，压平成 `IOException` 会让 UI
 * 无法给出正确提示（用户看到「网络错误」而实际是宝石不足）。
 * 取自 RN `src/types/api.ts` 的 `AppRespCode`（实测）：
 *
 * | code | 含义 | RN 侧行为 |
 * | --- | --- | --- |
 * | 0 | 成功 | — |
 * | 2 | 参数非法 | — |
 * | 3 | 服务端内部错误 | — |
 * | 4 | 生成中 | — |
 * | 5 | 会员权益不足 | — |
 * | **6** | **宝石不足** | 聊天/生图/语音 URL 命中时弹宝石弹窗 |
 * | **9** | **角色卡超限** | roleCard URL 命中时弹角色卡弹窗 |
 * | **16** | **三叶草不足** | 幸运蛋礼物时弹专属弹窗 |
 * | 17 | 重复消息 | — |
 *
 * ⚠️ **9 不在 RN 的 `AppRespCode` 枚举里**（实测该枚举只有 0/2/3/4/5/6/16/17），
 * 它是 `axios.ts:221` 直接写的字面量 `response?.data?.code === 9`。
 * 壳侧按字面量对齐，**不要因为「枚举里没有」就当它不存在**。
 */
data class ApiEnvelope(
    val code: Int,
    val msg: String?,
    val data: JSONObject?,
    /** `data` 是数组时放这里（列表接口）。 */
    val dataArray: org.json.JSONArray? = null,
) {
    val isSuccess: Boolean get() = code == CODE_SUCCESS

    companion object {
        const val CODE_SUCCESS = 0
        const val CODE_INVALID_PARAMETER = 2
        const val CODE_INTERNAL_ERROR = 3
        const val CODE_GENERATING = 4
        const val CODE_NOT_ENOUGH_MEMBERSHIP = 5

        /** 宝石不足。调用方通常要弹购买入口。 */
        const val CODE_NOT_ENOUGH_GEMS = 6

        /**
         * 角色卡数量超限。
         * ⚠️ 该码**不在** RN 的 `AppRespCode` 枚举里，是 `axios.ts:221` 的字面量。
         */
        const val CODE_ROLE_CARD_LIMIT = 9

        /** 三叶草（幸运蛋）库存不足。 */
        const val CODE_NOT_ENOUGH_CLOVER = 16
        const val CODE_DUPLICATE_MESSAGE = 17

        /**
         * 解析 envelope。**结构不符合预期时抛 [ApiException.Malformed]**，
         * 不返回一个 code=0 的假成功。
         *
         * ⚠️ `optString` 对 JSON null 返回字面量 `"null"` —— 这里对 msg 做了特判。
         * 同一个坑在 `LegacyTokenReader` 与 `RefreshTokenApi` 都踩过。
         */
        fun parse(body: String): ApiEnvelope {
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: throw ApiException.Malformed("响应不是合法 JSON 对象")

            if (!json.has("code")) {
                throw ApiException.Malformed("响应缺少 code 字段")
            }

            // code 用宽松读取：后端偶发把它发成字符串（标量漂移，见 ScalarCoercion）
            val code = ScalarCoercion.optInt(json, "code")
                ?: throw ApiException.Malformed("code 不是可解析的整数")

            val msg = if (json.isNull("msg")) null else {
                json.optString("msg").takeIf { it.isNotBlank() && it != "null" }
            }

            // data 可能是对象、数组，或缺失/null（部分接口成功时无 data）
            val dataObject = json.optJSONObject("data")
            val dataArray = if (dataObject == null) json.optJSONArray("data") else null

            return ApiEnvelope(code = code, msg = msg, data = dataObject, dataArray = dataArray)
        }
    }
}
