package ai.lightspeed.tipsy.shell.network

import java.net.URI

/**
 * BOE 泳道 header（W1-P6）。逐条对齐 RN `src/utils/lane.ts`（实测）。
 *
 * ## ⚠️ 这里的白名单是**安全约束**，不是优化
 *
 * `isLaneEligibleBackendURL`（`lane.ts:43-68`）只在满足**全部**条件时才发 header：
 * - scheme 是 `https`
 * - URL 里**没有** username / password
 * - 端口是空或 443
 * - host 在白名单内（dev API host 或其子域、dev studio host）
 *
 * 目的是**防止 lane 值泄漏到第三方域**。lane 名会暴露内部测试环境标识，
 * 发给外部域等于泄漏基础设施信息。照搬这套判定，**不要简化成「只判 host」**。
 *
 * ## 空串与 null 的区别（契约里有含义）
 *
 * - `null` = 壳无意见，调用方沿用自己的判定
 * - **空串 = 用户显式停用**，调用方**不得**再回退到构建期默认值
 *
 * 这条在 `SurfaceEnvContract.boeLane()` 的注释里也写着。混用会让「用户关掉了
 * 泳道」变成「回退到打包时的泳道」。
 */
object LaneHeader {

    /** 实测 `lane.ts:15`。 */
    const val HEADER_NAME = "X-Tipsy-Lane"

    /**
     * 白名单 host。取自 RN 侧常量（`lane.ts` 的 `DEV_API_HOST` / `DEV_STUDIO_API_HOST`）。
     *
     * ⚠️ **生产域不在白名单里** —— 泳道只对 dev 环境有意义。
     * 往生产请求发 lane header 不会生效，但会暴露内部标识。
     */
    // 实测 lane.ts:17-18。**逐字抄，别凭 base URL 推断** ——
    // studio host 是 `api-studio.infra.` 而不是 `studio.dev.`，猜错会让
    // studio 请求静默不带 lane（表现为「studio 上泳道不生效」，无报错）。
    private const val DEV_API_HOST = "api.dev.fantacy.live"
    private const val DEV_STUDIO_API_HOST = "api-studio.infra.fantacy.live"

    /**
     * ⚠️ **两个 host 的匹配规则不对称**（实测 `lane.ts:59-63`）：
     * - `DEV_API_HOST`：本身 **或其子域**（`host.endsWith(".$DEV_API_HOST")`）
     * - `DEV_STUDIO_API_HOST`：**仅精确匹配**，不含子域
     *
     * 统一成「都允许子域」会放宽白名单（等于扩大 lane 泄漏面）；
     * 统一成「都精确」会让 API host 的子域静默失去泳道。两种简化都是错的。
     */
    private fun isAllowedHost(host: String): Boolean =
        host == DEV_API_HOST ||
            host.endsWith(".$DEV_API_HOST") ||
            host == DEV_STUDIO_API_HOST

    /**
     * 该 URL 是否允许携带 lane header。
     *
     * @return true 仅当 https + 无凭据 + 端口 443/空 + host 命中白名单
     */
    fun isEligible(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false

        if (!"https".equals(uri.scheme, ignoreCase = true)) return false
        // userInfo 非空表示 URL 里带了凭据，这类 URL 一律不发
        if (!uri.userInfo.isNullOrEmpty()) return false
        if (uri.port != -1 && uri.port != 443) return false

        val host = uri.host?.lowercase() ?: return false
        return isAllowedHost(host)
    }

    /**
     * 构造 header。不符合条件或 lane 为空时返回空 map（**不发 header**）。
     *
     * @param lane 当前泳道。null 或空白 → 不发。
     */
    fun headersFor(lane: String?, url: String): Map<String, String> {
        val normalized = lane?.trim().orEmpty()
        if (normalized.isEmpty()) return emptyMap()
        if (!isEligible(url)) return emptyMap()
        return mapOf(HEADER_NAME to normalized)
    }
}
