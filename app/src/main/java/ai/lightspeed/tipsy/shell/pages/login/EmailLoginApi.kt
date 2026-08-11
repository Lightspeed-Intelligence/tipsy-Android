package ai.lightspeed.tipsy.shell.pages.login

import ai.lightspeed.tipsy.shell.network.LaneHeader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 邮箱验证码登录的两个接口。**逐条对齐** RN `tipsy-app/src/apis/auth.ts:126-188`。
 *
 * - `POST /login/email/send_code` —— body `{email}`
 * - `POST /login/email` —— body `{email, code, avatar?, lang_code}`，返回 token
 *
 * ## 为什么不走 ApiClient
 *
 * 两个原因，都不是"图省事"：
 *
 * 1. **它们是 auth 的前置**，与 [ai.lightspeed.tipsy.shell.auth.RefreshTokenApi]
 *    同理：不能依赖任何"带 token 的拦截器"，否则形成「取 token → 登录 → 取 token」
 *    的循环。RN 侧这两个端点走的也是**裸 axios**，没有拦截器、不共享统一错误处理。
 *    ⚠️ 做「统一网络收口」类重构时别把这两个端点并进 `ApiClient`（同 §4.5 对
 *    `/auth/refresh_token` 的纪律）。
 * 2. **需要 per-request 的 `X-Client-ID` 风控头**，而
 *    [ai.lightspeed.tipsy.shell.network.ApiClient] 的 `post()` 没有自定义 header 参数。
 *
 * 所以照 `RefreshTokenApi` 的先例独立实现（那个文件的注释 :13-19 立了这个先例）。
 *
 * ## 与 RN 的一处**刻意偏离**（已确认）
 *
 * RN 的这两个函数**不检查 envelope 的 `code`**（`auth.ts:126-143` 全文无
 * `code !== 0` 判定；对比同文件的 `loginPassword:115` 是检查的）。后果：
 * 后端限流返回 HTTP 200 + `code≠0` 时，RN 侧**静默当成功**，倒计时照走
 * 60 秒，用户以为码发出去了。
 *
 * 壳这里**检查 `code`** 并把后端 `msg` 抛给 UI 展示。理由：
 * - 与壳内其他接口语义一致（`ApiClient` 也是 `code != 0` 即失败）
 * - 用户能知道"发码失败了"，而不是干等一个永远不来的邮件
 * - 顺带修掉 RN 丢弃后端 `msg` 的问题（RN 验证码错误显示硬编码英文
 *   `Failed to login with email`，中文用户看到的是「登录错误: Failed to login with email」）
 */
/**
 * 邮箱登录的两个操作。抽成接口是为了让 [EmailLoginViewModel] 的编排测试
 * **不碰真实网络** —— 那层的 HTTP/header 契约由 `EmailLoginApiTest` 用
 * MockWebServer 单独验证。
 *
 * 编排测试若走真实 HTTP，请求会落到 `Dispatchers.IO` 的真实线程上，
 * 与 `runTest` 的虚拟时钟脱耦，导致倒计时相关断言不稳定（实测会挂死）。
 */
interface EmailLoginService {
    suspend fun sendCode(email: String)
    suspend fun login(
        email: String,
        code: String,
        langCode: String,
        avatar: String,
    ): EmailLoginApi.LoginResult
}

class EmailLoginApi(
    private val baseUrl: String,
    private val appVersion: String,
    private val downloadChannel: String,
    private val deviceIdProvider: () -> String,
    private val aesKey: String,
    private val laneProvider: () -> String? = { null },
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    /**
     * 仅为让单测能跑：`android.util.Base64` 在 JVM 单测里是空壳
     * （抛 `Method ... not mocked`）。生产用默认值。详见 [Base64Codec]。
     */
    private val base64: Base64Codec = AndroidBase64,
) : EmailLoginService {

    /** 业务失败（HTTP 200 但 `code != 0`）。[msg] 是后端文案，直接给用户看。 */
    class BusinessException(val code: Int, val msg: String) : IOException(msg)

    /** `POST /login/email` 的成功结果。 */
    data class LoginResult(
        /** 裸 JWT。RN 侧字段名就是 `token`，没有 access/refresh 之分。 */
        val token: String,
        /** 只用于埋点；**引导 gating 不看它**（见 LoginFragment 的说明）。 */
        val isNewUser: Boolean,
        /** >1 时 RN 会弹账号合并弹窗，但**仍然照存 token**（`useUserActon.ts:178-182`）。 */
        val linkedAccountCount: Int,
    )

    /** 发验证码。成功即返回；失败抛 [BusinessException] 或 [IOException]。 */
    override suspend fun sendCode(email: String) {
        val body = JSONObject().put("email", email).toString()
        // 只取 envelope 做校验，data 为空是正常的（AppResp<undefined>）
        request("login/email/send_code", body)
    }

    /**
     * 验码登录。
     *
     * @param langCode RN 传 `i18n.language || 'en'`（`useUserActon.ts:177`），
     *   壳侧对应 `L10n.current`
     * @param avatar RN 传的是**客户端随机生成**的默认头像路径
     *   `user/avatar/default/avatar{1..20}.png`（`utils/func.ts:26-29`）
     */
    override suspend fun login(
        email: String,
        code: String,
        langCode: String,
        avatar: String,
    ): LoginResult {
        val body = JSONObject()
            .put("email", email)
            .put("code", code)
            .put("avatar", avatar)
            .put("lang_code", langCode)
            .toString()

        val data = request("login/email", body)
            ?: throw IOException("login/email 成功但无 data")

        // ⚠️ 不用 optString 直接取 —— 它对 JSON null 返回字面量 "null"，
        // 会把一个叫 "null" 的字符串当 token 存进去（LegacyTokenReader 踩过）。
        val token = data.optString("token").takeIf { it.isNotBlank() && it != "null" }
            ?: throw IOException("login/email 响应无 token")

        return LoginResult(
            token = token,
            isNewUser = data.optBoolean("is_new_user", false),
            linkedAccountCount = data.optJSONArray("linked_accounts")?.length() ?: 0,
        )
    }

    /**
     * 发一个带风控头的匿名 POST，返回 envelope 的 `data`（可能为 null）。
     *
     * @throws BusinessException `code != 0`
     * @throws IOException 网络失败、HTTP 非 2xx、响应非法 JSON
     */
    private suspend fun request(path: String, jsonBody: String): JSONObject? =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/$path"
            val connection = (URL(url).openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.doOutput = true

                // header 名的大小写**照抄 RN**（auth.ts:133-141）：
                // `platform` 小写，其余大写驼峰。HTTP 头本身大小写不敏感，
                // 但两种风格在现网都在跑，统一风格没有收益、只增加对照成本。
                connection.setRequestProperty("platform", "android")
                connection.setRequestProperty("X-App-Version", appVersion)
                connection.setRequestProperty("X-Download-Channel", downloadChannel)
                connection.setRequestProperty("Content-Type", "application/json")
                // 风控头。加密失败时是空串（见 ClientIdCipher 的降级说明），
                // 空串也照发 —— 与 RN 行为一致，让后端决定怎么处置。
                connection.setRequestProperty(
                    CLIENT_ID_HEADER,
                    ClientIdCipher.encrypt(deviceIdProvider(), aesKey, base64 = base64),
                )
                // 匿名请求：**不设 token 头**。不是空串、不是匿名 token，
                // 是字面上没有这个 header（auth.ts 的 headers 对象里没有 token 项）。
                LaneHeader.headersFor(laneProvider(), url).forEach { (k, v) ->
                    connection.setRequestProperty(k, v)
                }

                connection.outputStream.use { it.write(jsonBody.toByteArray()) }

                val status = connection.responseCode
                // 业务错误也可能带非 2xx，所以两种流都要能读到 body 拿 msg
                val body = if (status in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                }

                parseEnvelope(status, body)
            } finally {
                connection.disconnect()
            }
        }

    private fun parseEnvelope(status: Int, body: String): JSONObject? {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw IOException("HTTP $status，响应不是合法 JSON")

        val code = json.optInt("code", -1)
        if (code != 0) {
            // msg 是后端给的可展示文案，直接透传给 UI
            throw BusinessException(code, json.optString("msg"))
        }
        return json.optJSONObject("data")
    }

    companion object {
        /** RN 侧常量名同此（`auth.ts:39`）。 */
        const val CLIENT_ID_HEADER = "X-Client-ID"

        private const val DEFAULT_TIMEOUT_MS = 15_000

        /**
         * 随机默认头像路径，对齐 RN `utils/func.ts:26-29`。
         *
         * 注意路径里是 `default/avatar` 而非 `defaultavatar` —— 拼错不会报错，
         * 只会让新用户头像 404。
         */
        fun randomDefaultAvatar(): String =
            "user/avatar/default/avatar${(1..20).random()}.png"
    }
}
