package ai.lightspeed.tipsy.shell.auth

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * `POST /auth/refresh_token`（W1-P1）。**逐条对齐** RN `src/apis/auth.ts:42-70`。
 *
 * ## 为什么用 HttpURLConnection 而不是 OkHttp/Retrofit
 *
 * 完整网络层是 P6（三鉴权模式 + 统一 envelope + 标量漂移容错）。这里只需一个
 * 请求，且它有个特殊处：**它是 auth 的前置**，不能依赖"带 token 的拦截器"
 * （否则取 token → 刷新 → 取 token 循环）。所以刻意独立实现，P6 建网络层时
 * 也不要把它并进去 —— 保持它没有 auth 拦截器依赖。
 *
 * ## 实测的三个易错点
 *
 * 1. **token 走 `token` header，不是 `Authorization: Bearer`**（`auth.ts:54`）。
 * 2. **响应是统一 envelope** `{code, msg, data:{token}}`，`code != 0` 是业务错误。
 *    HTTP 200 + `code != 0` 是常见组合，只看 HTTP 状态码会把失败当成功。
 * 3. **header 名 `platform` 是小写**（`auth.ts:55`），而 `X-App-Version` /
 *    `X-Download-Channel` 是大写驼峰。照抄，不要统一风格。
 */
class RefreshTokenApi(
    private val baseUrl: String,
    private val appVersion: String,
    private val downloadChannel: String,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) : ShellTokenStore.RefreshApi {

    /**
     * @return 新 token（非空）
     * @throws IOException 网络失败、HTTP 非 2xx、envelope `code != 0`、或响应无 token。
     *   调用方（[ShellTokenStore.doRefresh]）按"刷新失败"处理，**不区分具体原因** ——
     *   区分它只会诱使写重试逻辑，而 RN 侧没有重试。
     */
    override suspend fun refresh(currentToken: String): String = withContext(Dispatchers.IO) {
        val connection = (URL("$baseUrl/auth/refresh_token").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.doOutput = true

            // 顺序与名称大小写对齐 RN（auth.ts:53-59）
            connection.setRequestProperty("token", currentToken)
            connection.setRequestProperty("platform", "android")
            connection.setRequestProperty("X-App-Version", appVersion)
            connection.setRequestProperty("X-Download-Channel", downloadChannel)
            connection.setRequestProperty("Content-Type", "application/json")

            // RN 发的是空对象 body（`axios.post(url, {}, ...)`）
            connection.outputStream.use { it.write("{}".toByteArray()) }

            val status = connection.responseCode
            if (status !in 200..299) {
                // **不把响应体写进异常消息** —— 失败响应可能回显请求内容（含 token）
                throw IOException("refresh_token HTTP $status")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseToken(body)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 解析 envelope。`code != 0` 与缺 token 都算失败。
     *
     * ⚠️ 不用 `optString("token")` 直接取 —— 它对 JSON null 返回**字面量 "null"**，
     * 会把一个叫 "null" 的字符串当成 token 存进去（[LegacyTokenReader] 踩过同一个坑）。
     */
    private fun parseToken(body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw IOException("refresh_token 响应不是合法 JSON")

        val code = json.optInt("code", -1)
        if (code != 0) {
            // msg 是服务端给的业务消息，不含 token，可以记
            throw IOException("refresh_token 业务失败 code=$code msg=${json.optString("msg")}")
        }

        val data = json.optJSONObject("data")
            ?: throw IOException("refresh_token 无 data")

        if (!data.has("token") || data.isNull("token")) {
            throw IOException("refresh_token 无 token 字段")
        }
        val token = data.optString("token")
        if (token.isBlank() || token == "null") {
            throw IOException("refresh_token 返回空 token")
        }
        return token
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000
    }
}
