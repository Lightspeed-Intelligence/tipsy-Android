package ai.lightspeed.tipsy.shell.user

import ai.lightspeed.tipsy.shell.network.ApiClient
import ai.lightspeed.tipsy.shell.network.AuthMode

/**
 * `POST /user/info` —— 当前登录用户信息（`apis/user.ts:20`）。
 *
 * ## 鉴权是 REQUIRED，不是 OPPORTUNISTIC
 *
 * RN 侧走 `axiosAuth`（已核实 `apis/user.ts:20`），**且无请求体**。
 * 对应 [AuthMode.REQUIRED]：取不到 token 就不发请求。这与 Home 那三个
 * `axiosPublic` 接口相反 —— 别照抄 Home 的 `OPPORTUNISTIC`
 * （那里带不带 token 决定的是"有没有个性化"，这里没 token 根本没有"当前用户"）。
 *
 * ## ⚠️ 壳刻意**不做** RN `updateUserInfo` 的四件副作用
 *
 * RN 的 `updateUserInfo`（`useUserActon.ts:255-268`）拿到响应后还会：
 * 1. `clearChatHistoryCacheOnUserSwitch` —— 聊天缓存属 W4
 * 2. `applyDefaultInputModeOnce` —— 输入模式属 ChatDetail
 * 3. `i18n.changeLanguage(...)` —— **语言链刻意不碰**，见下
 * 4. `useGuideStatusStore.initializeForUser` —— 引导状态属 `OnboardingSurface`（W4）
 *
 * 本类只取用户信息字段。多做一件都会越过当前波次边界。
 *
 * ## 为什么不碰 `language_code`
 *
 * 响应里有 `language_code`，但语言真值链是
 * `/user/set_language` → RN `updateUserInfo()` → `user-storage` 本地镜像，
 * 而壳**只读** `user-storage`（`AccountLanguageReader` 的既定边界：
 * 「语言设置页刻意不迁移，壳不需要写这条链」）。
 *
 * 在这里跟着切语言会引入第二个 writer，与 §3 记的「i18n：壳是唯一 writer」冲突，
 * 且两阶段 i18n 下时序难以推理（iOS 踩过「二启永远无种子」的同类问题）。
 * 语言真变了由 `onLanguageSettled` 自愈。
 */
class UserInfoApi(private val apiClient: ApiClient) : UserInfoSource {

    /**
     * 拉当前用户信息。
     *
     * @return 解析后的用户；响应 `data` 缺 `user_id` 时返回 null（见 [CurrentUser.parse]）
     * @throws ai.lightspeed.tipsy.shell.network.ApiException 网络/协议失败，
     *   **包括无 token 时的未认证错误**（REQUIRED 模式不发请求直接抛）
     */
    override suspend fun fetchCurrentUser(): CurrentUser? {
        // 无请求体：RN 侧 `axiosAuth.post(url)` 不传第二个参数。
        // ApiClient.post 的默认 jsonBody 是 "{}"，与之等价（后端两者都接受）
        val envelope = apiClient.post(path = PATH, authMode = AuthMode.REQUIRED)
        return CurrentUser.parse(envelope.data)
    }

    companion object {
        const val PATH = "/user/info"
    }
}

/**
 * 数据源接缝。
 *
 * 抽出来的理由同 `HomeFeedSource`：让 [CurrentUserStore] 的「失败不清已有身份」
 * 这类**错了不报错**的编排能用 JVM 单测覆盖，不必起 MockWebServer。
 */
interface UserInfoSource {
    suspend fun fetchCurrentUser(): CurrentUser?
}
