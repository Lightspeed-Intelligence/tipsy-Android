package ai.lightspeed.tipsy.shell.network

import ai.lightspeed.tipsy.shell.auth.ShellTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 壳的 API 客户端（W1-P6，方案 §4.5）。
 *
 * ## 为什么用 OkHttp 而不引 Retrofit
 *
 * OkHttp **已在依赖树里**（RN 自己就用它，实测解析到 `4.12.0`），所以这不是
 * 新增依赖。不引 Retrofit 的三个理由：
 *
 * 1. **统一 envelope 与 Retrofit 的模型冲突**：`{code,msg,data}` 且
 *    HTTP 200 + `code != 0` 是常见组合。把业务码接进 Retrofit 要写
 *    `CallAdapter` + `Converter`，代码量不比手写少，还多一层抽象。
 * 2. **三种鉴权模式**是每个 endpoint 的属性，且 `OPPORTUNISTIC` 的语义要在
 *    Interceptor 里做 —— 那部分和 Retrofit 无关。
 * 3. **标量漂移容错**要自定义反序列化（[ScalarCoercion]），Retrofit 只是把
 *    Converter 转交给 Moshi/Gson，不减少工作。
 *
 * W3 若 API 面大到手写吃力，届时业务形态已清楚，再评估。
 *
 * ## ⚠️ 与 RN 共享 OkHttpClient
 *
 * RN 的网络走 `OkHttpClientProvider`。壳另起一套 HTTP 栈会让连接池、DNS 缓存、
 * TLS session 变成两份 —— 不只是浪费，还会让「同一后端两条链路」的问题难查
 * （比如 RN 侧能连、原生页超时）。所以这里接受注入的 client。
 *
 * ## 刷新与本类的关系
 *
 * `/auth/refresh_token` **刻意不走本类**（见 `RefreshTokenApi` 的注释）：
 * 它是 auth 的前置，走这里会形成「取 token → 刷新 → 取 token」的循环。
 */
class ApiClient(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val tokenStore: ShellTokenStore,
    private val errorGate: ApiErrorGate,
    private val appVersion: String,
    private val downloadChannel: String,
    /** 当前泳道。null = 壳无意见；**空串 = 用户显式停用**（见 [LaneHeader]）。 */
    private val laneProvider: () -> String? = { null },
) {

    /**
     * 发一个 GET 请求。
     *
     * @throws ApiException 各种失败形态，见该类的分型说明
     */
    suspend fun get(
        path: String,
        query: Map<String, String> = emptyMap(),
        authMode: AuthMode,
    ): ApiEnvelope = execute(path, authMode) { url ->
        Request.Builder().url(appendQuery(url, query)).get()
    }

    /** 发一个 POST 请求，body 为 JSON。 */
    suspend fun post(
        path: String,
        jsonBody: String = "{}",
        authMode: AuthMode,
    ): ApiEnvelope = execute(path, authMode) { url ->
        Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
    }

    private suspend fun execute(
        path: String,
        authMode: AuthMode,
        buildRequest: (String) -> Request.Builder,
    ): ApiEnvelope = withContext(Dispatchers.IO) {
        val url = "$baseUrl/${path.trimStart('/')}"

        // 取 token。**REQUIRED 模式下取不到就不发请求** —— 对齐 RN axiosAuth：
        // 发一个必然 401 的请求毫无意义，还会触发 auth 兜底造成误登出路径。
        val token = when (authMode) {
            AuthMode.NONE -> null
            // OPPORTUNISTIC / REQUIRED 都要取。⚠️ OPPORTUNISTIC 也要带 ——
            // 见 AuthMode 注释里 iOS 搜索历史那个事故
            else -> tokenStore.getValidToken()
        }
        if (authMode == AuthMode.REQUIRED && token == null) {
            throw ApiException.Unauthenticated()
        }

        val builder = buildRequest(url)
        // header 名与大小写逐条对齐 RN。**不要"统一风格"**：
        // axios.ts:116 用 `Platform`（大写 P），apis/auth.ts:55 用 `platform`（小写）。
        // 两者都在现网跑着，说明后端不区分大小写（HTTP header 本就大小写不敏感），
        // 但这里仍照抄主路径（axios.ts）的写法，减少对拍时的困惑。
        builder.header("Platform", "android")
        builder.header("X-App-Version", appVersion)
        builder.header("X-Download-Channel", downloadChannel)
        token?.let { builder.header("token", it) }
        LaneHeader.headersFor(laneProvider(), url).forEach { (k, v) -> builder.header(k, v) }

        val response = runCatching { client.newCall(builder.build()).execute() }
            .getOrElse { throw ApiException.Transport(it) }

        response.use {
            // 401/402 先交 gate（两个入口汇聚到同一处，见 ApiErrorGate）
            when (it.code) {
                401 -> {
                    // ⚠️ 传**实际使用的** token。null 时 gate 会忽略 ——
                    // 无法判断会话归属的 401 不得触发登出
                    errorGate.onUnauthorized(token)
                    throw ApiException.Http(401)
                }
                402 -> {
                    errorGate.onPaymentRequired()
                    throw ApiException.Http(402)
                }
            }

            if (!it.isSuccessful) throw ApiException.Http(it.code)

            val body = it.body?.string().orEmpty()
            val envelope = ApiEnvelope.parse(body)

            // ⚠️ HTTP 200 + code != 0 是常见组合。业务码**保持可分辨**，
            // 不压平成通用错误 —— 否则 UI 会把「宝石不足」显示成「网络错误」
            if (!envelope.isSuccess) {
                throw ApiException.Business(envelope.code, envelope.msg)
            }
            envelope
        }
    }

    private fun appendQuery(url: String, query: Map<String, String>): String {
        if (query.isEmpty()) return url
        val encoded = query.entries.joinToString("&") { (k, v) ->
            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        return if ('?' in url) "$url&$encoded" else "$url?$encoded"
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
