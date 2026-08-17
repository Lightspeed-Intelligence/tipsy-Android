package ai.lightspeed.tipsy.shell.pages.screen

/**
 * AB 端点分流（`screen.tsx:185-214` + `lib/abConfig/service.ts`）。
 *
 * ## ⚠️ 三个前置条件，少判一个就「推荐数据不可比」
 *
 * | 条件 | RN 出处 | 不满足时 |
 * | --- | --- | --- |
 * | 平台是 Android | `screen.tsx:191` / `service.ts:24` | distribution |
 * | **已登录**（`ownerUserId` 非空） | `service.ts:24-27` 返回 `{}` | distribution |
 * | flag `enable_recsys_in_home_show_case` 为真 | `service.ts:62` `?? false` | distribution |
 *
 * 壳天然满足第一条。**第二条最容易漏** —— 游客也会进大屏页
 * （端点是 OPPORTUNISTIC），而那时 `ownerUserId` 为空、
 * `resolveConfigsForCurrentOwner` 直接返回空 map，`parseABConfigBoolean(undefined)
 * ?? false` → **distribution**。写成「只看 flag」会让游客走推荐端点，
 * 而后端拿不到 owner 就无法归因（然后诊断事件狂报 `owner_user_id` 缺失）。
 *
 * ## 配置按 owner 缓存，换号要重解析
 *
 * `service.ts:30-32`：`resolvedOwnerUserId === ownerUserId` 才复用缓存。
 * 壳侧对应物是 [resolve] 每次传入当前 owner，由调用方在换号时重调。
 *
 * ## flag 拉取失败 → 静默按 false
 *
 * `service.ts:38-41` 的 `.catch(() => ({}))` —— 拉不到配置就走 distribution，
 * 只 `console.warn`。壳照此：**失败不阻塞页面**，但要留日志
 * （否则 AB 恒不命中而无从判断，同 §2.19 那三个 hydrate 的教训）。
 */
object ScreenEndpointResolver {

    /** AB flag 的 key（`service.ts:7`）。 */
    const val FLAG_KEY = "enable_recsys_in_home_show_case"

    /** AB 配置 bundle 名（`service.ts:6`）。 */
    const val BUNDLE_NAME = "tipsy-chat-app"

    /**
     * 定端点。
     *
     * @param ownerUserId 当前登录用户 id；null/空 = 游客 → 恒 distribution
     * @param flagEnabled AB flag 的值；**拉取失败时传 false**（见类注释）
     */
    fun resolve(ownerUserId: String?, flagEnabled: Boolean): ScreenEndpoint {
        // ⚠️ 顺序无关，但两条都要判 —— 游客即使 flag 为真也走 distribution
        if (ownerUserId.isNullOrBlank()) return ScreenEndpoint.DISTRIBUTION
        return if (flagEnabled) {
            ScreenEndpoint.RECOMMENDATION
        } else {
            ScreenEndpoint.DISTRIBUTION
        }
    }

    /**
     * 解析 AB 配置里的布尔值（`parseABConfigBoolean`，`abConfig/value.ts`）。
     *
     * ⚠️ **接受四种真值写法**：`true` / `1` / `yes` / `on`（大小写不敏感、
     * 先 trim）。只认 `"true"` 会让运营在后台填 `1` 时 AB **静默失效** ——
     * 而那种失效表现为「推荐端点永远不命中」，没人会报。
     *
     * 假值同样有四种：`false` / `0` / `no` / `off`。
     * **认不出的值（含 null / 空 / 其它字符串）→ `null`**，
     * 由调用方按 `?? false` 兜底（`service.ts:62`）—— 这里返回 null 而不是
     * 直接 false，是为了让「没配」与「配了 false」可区分。
     */
    fun parseFlag(raw: String?): Boolean? {
        if (raw == null) return null
        return when (raw.trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
    }
}
