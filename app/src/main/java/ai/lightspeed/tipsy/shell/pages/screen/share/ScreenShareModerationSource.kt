package ai.lightspeed.tipsy.shell.pages.screen.share

import ai.lightspeed.tipsy.shell.network.ApiClient
import ai.lightspeed.tipsy.shell.network.ApiEnvelope
import ai.lightspeed.tipsy.shell.network.ApiException
import ai.lightspeed.tipsy.shell.network.AuthMode
import android.util.Log
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Screen 分享打开时的角色内容审核边界。 */
fun interface ScreenShareModerationSource {
    /** true = 可以继续分享；false = 服务端明确拒绝。 */
    suspend fun isAllowed(characterId: String): Boolean
}

/**
 * 对齐 RN `MediaShareModal` 的审核语义：
 *
 * - 正常响应只有 `data.ok === true` 才通过；
 * - envelope 业务错误仍读取 `data.ok`，通常是 `ok=false` 的明确拒绝；
 * - HTTP、鉴权、网络或畸形响应 fail-open，不能让临时故障永久封死分享入口；
 * - 协程取消必须继续抛出，关闭弹层后不得把迟到结果写回新内容。
 */
class ApiScreenShareModerationSource internal constructor(
    private val request: suspend (String) -> ApiEnvelope,
    private val logWarn: (String, Throwable) -> Unit,
) : ScreenShareModerationSource {

    constructor(api: ApiClient) : this(
        request = { characterId ->
            api.postBounded(
                path = PATH,
                jsonBody = JSONObject().put(FIELD_CHARACTER_ID, characterId).toString(),
                authMode = AuthMode.REQUIRED,
                callTimeoutSeconds = CALL_TIMEOUT_SECONDS,
            )
        },
        logWarn = { message, error -> Log.w(TAG, message, error) },
    )

    override suspend fun isAllowed(characterId: String): Boolean {
        if (characterId.isBlank()) return true
        return try {
            request(characterId).data?.optBoolean(FIELD_OK, false) == true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (business: ApiException.Business) {
            business.data?.optBoolean(FIELD_OK, false) == true
        } catch (error: Throwable) {
            logWarn("分享审核请求失败，按 RN 语义允许继续", error)
            true
        }
    }

    private companion object {
        const val TAG = "ScreenShareModeration"
        const val PATH = "share/moderation"
        const val FIELD_CHARACTER_ID = "character_id"
        const val FIELD_OK = "ok"
        const val CALL_TIMEOUT_SECONDS = 15L
    }
}
