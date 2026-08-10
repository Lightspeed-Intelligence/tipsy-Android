package ai.lightspeed.tipsy.shell.network

import java.io.IOException

/**
 * 网络层异常（W1-P6）。
 *
 * ## 为什么要分型而不是全压成 IOException
 *
 * 方案 §4.5：已知业务码（6 宝石不足 / 9 角色卡上限 / 16 三叶草）
 * **不得压平成通用错误**。压平的后果是 UI 无法给出正确提示 ——
 * 用户看到「网络错误，请重试」，而实际是宝石不足需要充值，重试一万次也没用。
 *
 * 继承 [IOException] 是为了让调用方能用一个 catch 兜住所有网络失败，
 * 同时**保留**按类型细分的能力。
 */
sealed class ApiException(message: String) : IOException(message) {

    /**
     * 业务错误：HTTP 2xx 但 envelope 的 `code != 0`。
     *
     * ⚠️ **这是最容易被忽略的失败形态** —— HTTP 层看起来完全成功。
     */
    class Business(
        val code: Int,
        val serverMessage: String?,
    ) : ApiException("业务错误 code=$code msg=$serverMessage") {

        val isNotEnoughGems: Boolean get() = code == ApiEnvelope.CODE_NOT_ENOUGH_GEMS
        val isRoleCardLimit: Boolean get() = code == ApiEnvelope.CODE_ROLE_CARD_LIMIT
        val isNotEnoughClover: Boolean get() = code == ApiEnvelope.CODE_NOT_ENOUGH_CLOVER
        val isGenerating: Boolean get() = code == ApiEnvelope.CODE_GENERATING
    }

    /** HTTP 层错误（非 2xx）。401/402 已由 [ApiErrorGate] 先行处理。 */
    class Http(val status: Int) : ApiException("HTTP $status")

    /**
     * 未登录且 [AuthMode.REQUIRED]。**请求根本没发出去。**
     *
     * 对齐 RN `axiosAuth` 的行为：取不到有效 token 时 reject 并请求登录 UI，
     * 而不是发一个必然 401 的请求（`axios.ts:158-174`）。
     */
    class Unauthenticated : ApiException("未登录，请求未发出")

    /** 响应结构不符合 envelope 约定。**不返回假成功。** */
    class Malformed(reason: String) : ApiException("响应格式错误：$reason")

    /** 传输层失败（连接、超时、DNS）。 */
    class Transport(cause: Throwable) : ApiException("传输失败：${cause.javaClass.simpleName}")
}
