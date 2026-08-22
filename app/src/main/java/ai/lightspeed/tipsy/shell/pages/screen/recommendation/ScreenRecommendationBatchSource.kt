package ai.lightspeed.tipsy.shell.pages.screen.recommendation

import ai.lightspeed.tipsy.shell.auth.ShellTokenStore
import ai.lightspeed.tipsy.shell.network.ApiClient

/**
 * `/recommend_report/tracking_v2/report_batch` 上传接缝。
 *
 * [jsonBody] 已是最终 `{events:[...]}` wire body；实现不得重新生成 event id、
 * event_time 或推荐归因。
 */
fun interface ScreenRecommendationBatchSource {
    suspend fun postBatch(jsonBody: String, frozenToken: String)
}

/**
 * token 冻结接缝。`getValidToken` 允许刷新；拿到值后本轮 batch 全程使用同一个 token。
 */
interface ScreenRecommendationTokenProvider {
    suspend fun getValidToken(): String?
}

/** 生产 token seam；reporter 本身不依赖 token 的存储或刷新细节。 */
class ShellTokenScreenRecommendationProvider(
    private val tokenStore: ShellTokenStore,
) : ScreenRecommendationTokenProvider {
    override suspend fun getValidToken(): String? = tokenStore.getValidToken()
}

/** 生产 API 实现；`ApiClient` 负责既有 header、泳道、envelope 与 401/402 gate。 */
class ApiScreenRecommendationBatchSource(
    private val apiClient: ApiClient,
) : ScreenRecommendationBatchSource {
    override suspend fun postBatch(jsonBody: String, frozenToken: String) {
        apiClient.postWithFrozenToken(
            path = PATH_REPORT_BATCH,
            jsonBody = jsonBody,
            frozenToken = frozenToken,
        )
    }

    companion object {
        const val PATH_REPORT_BATCH = "/recommend_report/tracking_v2/report_batch"
    }
}
