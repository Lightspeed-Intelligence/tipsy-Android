package ai.lightspeed.tipsy.shell.pages.screen

import ai.lightspeed.tipsy.shell.network.ApiClient
import ai.lightspeed.tipsy.shell.network.AuthMode
import org.json.JSONObject

/**
 * AB 配置拉取（`apis/abConfig.ts` + `lib/abConfig/service.ts`）。
 *
 * ## ⚠️ 走 `axiosAuth` → REQUIRED
 *
 * `abConfig.ts:10` 是 `axiosAuth.post` —— 所以**游客根本拿不到配置**。
 * 这与 [ScreenEndpointResolver] 的「游客恒走 distribution」自洽：
 * 未登录时不必发这个请求（发了也会被 REQUIRED 前置拒绝）。
 *
 * ## 失败静默按「flag 关」
 *
 * `service.ts:38-41` 的 `.catch(() => ({}))` 只 `console.warn`。
 * 壳照此：**失败不阻塞页面**，走 distribution。但要留日志 ——
 * 否则 AB 恒不命中而无从判断（§2.19 那三个静默 hydrate 的教训）。
 *
 * ## 按 owner 缓存
 *
 * `service.ts:30-32`：同一个 owner 只拉一次。壳侧由 [cachedOwnerUserId]
 * 实现同样语义 —— 换号时 owner 变了自然重拉。
 */
class ScreenAbConfigApi(private val apiClient: ApiClient) : ScreenAbConfigSource {

    private var cachedOwnerUserId: String? = null
    private var cachedFlag: Boolean = false

    /**
     * 取 `enable_recsys_in_home_show_case`。
     *
     * @param ownerUserId 当前登录用户；**空 = 游客 → 直接返回 false 不发请求**
     * @return flag 值；拉取失败 / 未登录 / 配置缺失都是 false
     */
    override suspend fun fetchScreenRecommendationFlag(ownerUserId: String?): Boolean {
        val owner = ownerUserId?.takeIf { it.isNotBlank() } ?: return false
        // 同一 owner 复用（对齐 RN 的 resolvedOwnerUserId 比较）
        if (cachedOwnerUserId == owner) return cachedFlag

        val body = JSONObject().put(FIELD_BUNDLE_NAME, ScreenEndpointResolver.BUNDLE_NAME)
        val envelope = apiClient.post(
            path = PATH_AB_CONFIG,
            jsonBody = body.toString(),
            // ⚠️ axiosAuth → REQUIRED，见类注释
            authMode = AuthMode.REQUIRED,
        )
        val configs = envelope.data?.optJSONObject(FIELD_CONFIGS)
        val raw = configs?.optString(ScreenEndpointResolver.FLAG_KEY)
        // ⚠️ `?? false`：认不出的值（含缺失）按 false（`service.ts:62`）
        val flag = ScreenEndpointResolver.parseFlag(raw) ?: false
        cachedOwnerUserId = owner
        cachedFlag = flag
        return flag
    }

    companion object {
        const val PATH_AB_CONFIG = "/ab_config/get_bundle_configs"
        private const val FIELD_BUNDLE_NAME = "bundle_name"
        private const val FIELD_CONFIGS = "configs"
    }
}

/** 接缝：让 ViewModel/Fragment 的 AB 分流能用单测覆盖。 */
interface ScreenAbConfigSource {
    suspend fun fetchScreenRecommendationFlag(ownerUserId: String?): Boolean
}
