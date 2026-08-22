package ai.lightspeed.tipsy.shell.network

import ai.lightspeed.tipsy.shell.auth.Jwt
import ai.lightspeed.tipsy.shell.auth.ShellTokenStore
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

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
    /** 与 token 有效性判定共用的时钟。可注入是为了精确测试过期边界。 */
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
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

    /**
     * 发一个有总时限、且协程取消会向下取消 OkHttp Call 的 POST。
     *
     * 用于页面生命周期内的短请求：RN 的共享 client 是无限 timeout，普通同步
     * `execute()` 无法响应协程取消，弹层反复开关会累积 stalled IO。
     */
    suspend fun postBounded(
        path: String,
        jsonBody: String = "{}",
        authMode: AuthMode,
        callTimeoutSeconds: Long,
    ): ApiEnvelope {
        require(callTimeoutSeconds > 0L) { "callTimeoutSeconds must be positive" }
        return execute(
            path = path,
            authMode = authMode,
            cancellableCallTimeoutSeconds = callTimeoutSeconds,
        ) { url ->
            Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
        }
    }

    /**
     * 使用调用方捕获的会话 token 发送 POST。
     *
     * 可靠 outbox 可能在事件产生后才真正发送。如果发送时重新读取 token，账号 A
     * 的排队事件可能在换号后被账号 B 的 token 发出。这里仍复用 REQUIRED 的过期与
     * current-token 双重校验；冻结 token 已不属于当前会话时请求直接失败，不会上网。
     */
    suspend fun postWithFrozenToken(
        path: String,
        jsonBody: String = "{}",
        frozenToken: String,
    ): ApiEnvelope {
        require(frozenToken.isNotBlank()) { "frozenToken must not be blank" }
        return execute(
            path = path,
            authMode = AuthMode.REQUIRED,
            frozenToken = frozenToken,
            cancellableCallTimeoutSeconds = FROZEN_CALL_TIMEOUT_SECONDS,
        ) { url ->
            Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
        }
    }

    private suspend fun execute(
        path: String,
        authMode: AuthMode,
        frozenToken: String? = null,
        cancellableCallTimeoutSeconds: Long? = null,
        buildRequest: (String) -> Request.Builder,
    ): ApiEnvelope = withContext(Dispatchers.IO) {
        val url = "$baseUrl/${path.trimStart('/')}"

        // 取 token。**REQUIRED 模式下取不到就不发请求** —— 对齐 RN axiosAuth：
        // 发一个必然 401 的请求毫无意义，还会触发 auth 兜底造成误登出路径。
        val candidateToken = frozenToken ?: when (authMode) {
                AuthMode.NONE -> null
                // OPPORTUNISTIC / REQUIRED 都要取。⚠️ OPPORTUNISTIC 也要带 ——
                // 见 AuthMode 注释里 iOS 搜索历史那个事故
                else -> tokenStore.getValidToken()
            }

        // ShellTokenStore 的桥契约已保证返回时有效；这里仍在请求真正起飞前二次守门：
        // await 之后可能恰好过期或换号。Native 与 RN axios 都不得把这种 stale token
        // 发上网，且不能因为上游当前已校验就删掉这一层。
        val token = candidateToken?.takeIf {
            Jwt.hasNotExpired(it, nowSeconds()) && tokenStore.isCurrentToken(it)
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

        val request = builder.build()
        val snapshot = if (cancellableCallTimeoutSeconds != null) {
            // RN 的共享 client 是 0/infinite timeout。页面短请求和可靠 outbox 都不能
            // 被 stalled call 永久占住；协程取消也必须真正落到 OkHttp Call。
            executeCancellableCall(request, cancellableCallTimeoutSeconds)
        } else {
            val response = runCatching { client.newCall(request).execute() }
                .getOrElse { throw ApiException.Transport(it) }
            response.use(::snapshotResponse)
        }
        parseResponse(snapshot, token)
    }

    private suspend fun executeCancellableCall(
        request: Request,
        callTimeoutSeconds: Long,
    ): HttpResponseSnapshot =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            call.timeout().timeout(callTimeoutSeconds, TimeUnit.SECONDS)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(ApiException.Transport(error)))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    val result = try {
                        Result.success(response.use(::snapshotResponse))
                    } catch (error: Exception) {
                        Result.failure(ApiException.Transport(error))
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }

    private fun snapshotResponse(response: Response): HttpResponseSnapshot =
        HttpResponseSnapshot(
            code = response.code,
            isSuccessful = response.isSuccessful,
            // 错误响应不需要 materialize body；先交既有 HTTP gate。
            body = if (response.isSuccessful) response.body?.string().orEmpty() else "",
        )

    private suspend fun parseResponse(
        response: HttpResponseSnapshot,
        token: String?,
    ): ApiEnvelope {
        // 401/402 先交 gate（两个入口汇聚到同一处，见 ApiErrorGate）
        when (response.code) {
            401 -> {
                // ⚠️ 传**实际使用的** token。null 时 gate 会忽略 ——
                // 无法判断会话归属的 401 不得触发登出
                // 对齐 live RN response interceptor：只上报并抛错，**不 refresh+retry**。
                errorGate.onUnauthorized(token)
                throw ApiException.Http(401)
            }

            402 -> {
                errorGate.onPaymentRequired()
                throw ApiException.Http(402)
            }
        }

        if (!response.isSuccessful) throw ApiException.Http(response.code)
        val envelope = ApiEnvelope.parse(response.body)

        // ⚠️ HTTP 200 + code != 0 是常见组合。业务码**保持可分辨**，
        // 不压平成通用错误 —— 否则 UI 会把「宝石不足」显示成「网络错误」
        if (!envelope.isSuccess) {
            throw ApiException.Business(envelope.code, envelope.msg, envelope.data)
        }
        return envelope
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
        const val FROZEN_CALL_TIMEOUT_SECONDS = 30L
    }

    private data class HttpResponseSnapshot(
        val code: Int,
        val isSuccessful: Boolean,
        val body: String,
    )
}
