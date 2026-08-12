package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.network.ApiClient
import ai.lightspeed.tipsy.shell.network.AuthMode
import ai.lightspeed.tipsy.shell.network.ScalarCoercion
import org.json.JSONArray
import org.json.JSONObject

/**
 * Profile 接口（`apis/profile.ts` 实测）。
 *
 * ## ⚠️ `/user/stats_info` 在 RN 侧有**两个函数**，按自己/他人分流
 *
 * | RN 函数 | axios | 用于 |
 * | --- | --- | --- |
 * | `getFollowerInfo`（`profile.ts:121`） | `axiosAuth` → [AuthMode.REQUIRED] | **自己** |
 * | `getPublicFollowerInfo`（`profile.ts:131`） | `axiosPublic` → [AuthMode.OPPORTUNISTIC] | 他人 |
 *
 * 同一路径、不同鉴权。分流在 `useProfile.tsx:64-67` 按 `isSelf`。
 * **本刀只做自己主页**，所以只实现 REQUIRED 那条；他人主页那条留给后续包，
 * 届时注意它是 `OPPORTUNISTIC` 而**不是** `NONE`（§4.5 记的 iOS 事故）。
 *
 * ## 分页大小按 tab 配，不是全局常量
 *
 * 已核实三个值并存：创作列表与角色卡 **10**（`useCreatedList.ts:18`、
 * `useRoleCard.ts:11`）、记忆/收藏/点赞/关注列表 **20**、他人主页 **200**
 * （`useProfile.tsx:30`）。方案 §8.1 的「5 个 Tab 共用一个分页壳」指的是
 * **壳复用**，不是 size 统一 —— 统一了会让翻页边界与 RN 不一致。
 *
 * 所以 [PAGE_SIZE_CREATED] 只给创作列表用，后续 tab 各自加常量。
 * （Home 侧是单一 `PAGE_SIZE`，别照搬那个形状。）
 */
class ProfileApi(private val apiClient: ApiClient) : ProfileSource {

    /**
     * 自己的统计数字。
     *
     * @throws ai.lightspeed.tipsy.shell.network.ApiException 含无 token 时的未认证错误
     */
    override suspend fun fetchSelfStats(userId: String): ProfileStats {
        // RN 侧 `getFollowerInfo({ user_id: uid ?? undefined })` —— 传自己的 id。
        // ⚠️ 空串不要发：RN 的 `undefined` 在 JSON.stringify 时字段会被整体省略，
        // 传 "" 语义不同（后端可能当成"查一个 id 为空的用户"）
        val body = JSONObject().apply {
            if (userId.isNotBlank()) put(FIELD_USER_ID, userId)
        }
        val envelope = apiClient.post(
            path = PATH_STATS_INFO,
            jsonBody = body.toString(),
            authMode = AuthMode.REQUIRED,
        )
        return ProfileStats.parse(envelope.data)
    }

    /**
     * 自己的创作列表一页。
     *
     * @param page 从 **0** 开始（RN `useSWRInfinite` 的 pageIndex 即 0-based）
     * @param languageCode 影响返回内容的本地化字段；RN 传 `i18n.language || 'en'`
     */
    override suspend fun fetchCreatedPage(
        page: Int,
        languageCode: String,
    ): ProfileCreatedPage {
        // 四个字段照 `useCreatedList.ts:24-30` 原样发。
        // ⚠️ `types` 必须带全三种：漏掉会让对应类型的卡片整类消失，
        // 而接口不报错（`apis/profile.ts:164` 也在服务端侧再补了一次）
        val body = JSONObject()
            .put(FIELD_PAGE, page)
            .put(FIELD_SIZE, PAGE_SIZE_CREATED)
            .put(FIELD_REVIEW_STAGE, REVIEW_STAGE_ALL)
            .put(FIELD_LANGUAGE_CODE, languageCode)
            .put(FIELD_TYPES, JSONArray(listOf("character", "story", "game")))
        val envelope = apiClient.post(
            path = PATH_CREATED_LIST,
            jsonBody = body.toString(),
            authMode = AuthMode.REQUIRED,
        )
        return ProfileCreatedPage.parse(envelope.data)
    }

    /**
     * 自己的记忆（plot）列表一页。
     *
     * ⚠️ 请求体照 `useProfileMemories.ts:22-26`：`size` / `review_stage` / `nsfw`，
     * **没有 `page` 以外的分页参数**，也**不传 `creator_id`**
     * （那是他人主页走 `/plot/list/creator` 时才有的，见类注释的分流表）。
     *
     * `nsfw: false` 是 RN 的硬编码值，不是用户开关 —— 别接成设置项。
     *
     * @param page 从 **0** 开始
     */
    override suspend fun fetchMemoryPage(page: Int): ProfileMemoryPage {
        val body = JSONObject()
            .put(FIELD_PAGE, page)
            .put(FIELD_SIZE, ProfileTab.MEMORY.pageSize)
            .put(FIELD_REVIEW_STAGE, REVIEW_STAGE_ALL)
            .put(FIELD_NSFW, false)
        val envelope = apiClient.post(
            path = PATH_PLOT_LIST_SELF,
            jsonBody = body.toString(),
            authMode = AuthMode.REQUIRED,
        )
        return ProfileMemoryPage.parse(envelope.data)
    }

    companion object {
        const val PATH_STATS_INFO = "/user/stats_info"
        const val PATH_CREATED_LIST = "/user/created/list"

        /**
         * 自己的记忆列表。
         *
         * ⚠️ 他人主页是**另一个路径** `/plot/list/creator` 且走 `axiosPublic`
         * （`apis/plot.ts:127`）—— 同 `/user/stats_info` 的自己/他人分流，
         * 接他人主页时不要复用这一条。
         */
        const val PATH_PLOT_LIST_SELF = "/plot/list/self"

        /** 创作列表每页 10（`useCreatedList.ts:18` `LIMIT = 10`）。 */
        const val PAGE_SIZE_CREATED = 10

        /** `ReviewStage.All` —— 自己主页要看到待审/驳回的内容。 */
        const val REVIEW_STAGE_ALL = "All"

        private const val FIELD_USER_ID = "user_id"
        private const val FIELD_PAGE = "page"
        private const val FIELD_SIZE = "size"
        private const val FIELD_REVIEW_STAGE = "review_stage"
        private const val FIELD_LANGUAGE_CODE = "language_code"
        private const val FIELD_TYPES = "types"
        private const val FIELD_NSFW = "nsfw"
    }
}

/**
 * 数据源接缝（同 `HomeFeedSource` 的理由：让分页/去重/到底判定能用 JVM 单测覆盖）。
 */
interface ProfileSource {
    suspend fun fetchSelfStats(userId: String): ProfileStats
    suspend fun fetchCreatedPage(page: Int, languageCode: String): ProfileCreatedPage
    suspend fun fetchMemoryPage(page: Int): ProfileMemoryPage
}

/**
 * 创作列表的一页。
 *
 * @property total 服务端总数 —— **到底判定用它**，见 [ProfileCreatedPage.parse] 注释
 */
data class ProfileCreatedPage(
    val items: List<ProfileCreatedItem>,
    val total: Long,
    val rawList: JSONArray?,
) {
    companion object {

        /**
         * 解析一页。
         *
         * `list` 为 null 是**正常响应**（`types/character.ts:538`
         * `list: CreatedListItem[] | null`），不是错误 —— 空列表就走空态。
         *
         * ⚠️ **到底判定不在这里做**，在 ViewModel：RN 的判据是
         * `已去重累计数 >= total`（`useCreatedList.ts:98-101`），需要跨页状态。
         * 且注意 `total` 为 0 / 缺失时 RN **直接算到底**（`if (!total) return true`）——
         * 那一步也在 ViewModel。
         */
        fun parse(data: JSONObject?): ProfileCreatedPage {
            if (data == null) return ProfileCreatedPage(emptyList(), 0L, null)
            val list = data.optJSONArray(FIELD_LIST)
            val items = buildList {
                for (i in 0 until (list?.length() ?: 0)) {
                    val obj = list?.optJSONObject(i) ?: continue
                    // 认不出 item_type 的整条跳过（新类型上线时不崩，见 ProfileItemType）
                    ProfileCreatedItem.parse(obj)?.let(::add)
                }
            }
            return ProfileCreatedPage(
                items = items,
                total = ScalarCoercion.optLong(data, FIELD_TOTAL) ?: 0L,
                rawList = list,
            )
        }

        private const val FIELD_LIST = "list"
        private const val FIELD_TOTAL = "total"
    }
}
