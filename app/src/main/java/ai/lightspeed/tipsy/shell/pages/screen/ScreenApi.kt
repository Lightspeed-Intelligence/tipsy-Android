package ai.lightspeed.tipsy.shell.pages.screen

import ai.lightspeed.tipsy.shell.network.ApiClient
import ai.lightspeed.tipsy.shell.network.AuthMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Screen（Tab1 大屏页）的接口（W4-P1，进度文档 §2.35）。
 *
 * ## ⚠️ AB 二选一：两个端点同一份请求体
 *
 * | 端点 | 何时 |
 * | --- | --- |
 * | `/character_distribution/list` | **默认**（游客、非 Android、flag 关） |
 * | `/recommend/home/list` | **仅** Android + 已登录 + flag 开 |
 *
 * 分流判定不在这里，在 [ScreenEndpointResolver] —— 它有三个前置条件，
 * 少判一个的表现是「推荐数据不可比」（方案 §8.1）。
 *
 * ## ⚠️ 请求体字段是 `size`，不是 `page_size`
 *
 * 方案 §8.1 原文写「`page_size` 参数名与 Home 的 `size` **不同**，勿混」——
 * **读反了**（§2.35 已订正）：`page_size` 只是 `getScreenList` 的 **TS 形参名**，
 * 请求体发的是 `size`（`screen.ts:36` `size: params.page_size ?? 20`）。
 * 两个端点线上**同名**。
 *
 * 发 `page_size` 的后果：后端不认这个键 → 很可能回落默认页大小，
 * **不报错**，只是分页边界与现网不同（本地看不出来）。
 */
class ScreenApi(private val apiClient: ApiClient) : ScreenSource {

    /**
     * 拉一页。
     *
     * @param page 从 **0** 开始（`screen.tsx:109` `useState(0)`，与 Profile
     *   的 0-based 一致，但**与 Search 的 1-based 不同**）
     * @param sessionId 翻页时回传上一次响应的 session；首屏/刷新传 null
     *   （见 [ScreenViewModel] 的 session 复用规则）
     */
    override suspend fun fetchPage(
        endpoint: ScreenEndpoint,
        page: Int,
        nsfw: Boolean,
        gender: String?,
        languageCode: String,
        tagIds: List<String>,
        contentType: Int?,
        sessionId: String?,
    ): ScreenPage {
        val body = JSONObject()
            .put(FIELD_PAGE, page)
            // ⚠️ `size` 不是 `page_size`，见类注释
            .put(FIELD_SIZE, PAGE_SIZE)
            .put(FIELD_NSFW, nsfw)
            .put(FIELD_LANGUAGE_CODE, languageCode)
            .put(FIELD_TAG_IDS, JSONArray(tagIds))
        // RN 侧这三个是 `params.x`（undefined 时 JSON.stringify 整键省略），
        // 所以 null 时不发 —— 与 Search 的 gender 同一条纪律
        gender?.let { body.put(FIELD_GENDER, it) }
        contentType?.let { body.put(FIELD_CONTENT_TYPE, it) }
        sessionId?.let { body.put(FIELD_SESSION_ID, it) }

        val envelope = apiClient.post(
            path = endpoint.path,
            jsonBody = body.toString(),
            // `axiosPublic` → OPPORTUNISTIC（**不是 NONE**，§4.5）：
            // 有 token 时会带上，后端据此个性化。用 NONE 等于永远不带，
            // 而两端都不报错
            authMode = AuthMode.OPPORTUNISTIC,
        )
        return ScreenPage.parse(envelope.data)
    }

    companion object {
        /** 每页 20（`screen.tsx` 的 `PAGE_SIZE`，也是 `screen.ts:36` 的默认值）。 */
        const val PAGE_SIZE = 20

        private const val FIELD_PAGE = "page"
        private const val FIELD_SIZE = "size"
        private const val FIELD_NSFW = "nsfw"
        private const val FIELD_GENDER = "gender"
        private const val FIELD_LANGUAGE_CODE = "language_code"
        private const val FIELD_TAG_IDS = "tag_ids"
        private const val FIELD_CONTENT_TYPE = "content_type"
        private const val FIELD_SESSION_ID = "session_id"
    }
}

/** 两个端点。[path] 即线上路径，**值是契约**。 */
enum class ScreenEndpoint(val path: String) {
    /** 默认端点（`apis/screen.ts:10`）。 */
    DISTRIBUTION("/character_distribution/list"),

    /** AB 命中时的推荐端点（`:11`）。归因字段只有这条才有。 */
    RECOMMENDATION("/recommend/home/list"),
    ;

    /** 归因诊断事件里上报的端点名（`screen.tsx:817`）。 */
    val trackingPath: String get() = path
}

/** 数据源接缝（同其它页：让 ViewModel 编排能用 JVM 单测覆盖）。 */
interface ScreenSource {
    suspend fun fetchPage(
        endpoint: ScreenEndpoint,
        page: Int,
        nsfw: Boolean,
        gender: String?,
        languageCode: String,
        tagIds: List<String>,
        contentType: Int?,
        sessionId: String?,
    ): ScreenPage
}

/**
 * 一页原始响应（`CharacterDistributionRes`，`types/screen.ts:3-7`）。
 *
 * ⚠️ **`requestId` / `sessionId` 只有 recommendation 端点才给**，
 * distribution 端点缺它们是正常的 —— 归因判定据此分流，见
 * [ScreenAttribution.attribute]。
 */
data class ScreenPage(
    /**
     * ⚠️ **元素可空** —— null 是「解析失败/无 character_id」的**占位**，
     * 用来保住归因 `position` 的原始下标，见 [parse] 注释。
     * 过滤与去重在 [ScreenAttribution.attribute]。
     */
    val items: List<ScreenFeedItem?>,
    val requestId: String?,
    val sessionId: String?,
) {
    companion object {
        /**
         * 解析一页。
         *
         * ## ⚠️⚠️ 保留 `null` 占位，**不在这里过滤无效条目**
         *
         * 归因的 `position` 是 `page * pageSize + rawIndex`，而 `rawIndex`
         * 是 RN **原始数组**的下标 —— `recommendationAttribution.ts:45-48`
         * 在同一个循环里既跳过「无 character_id」也跳过「重复 id」，
         * 但 `rawIndex` 两种情况都**照样递增**。
         *
         * RN 单测钉死了这个语义（`recommendationAttribution.test.ts:33`）：
         * `[无id, a, a, b]` + page=1/pageSize=10 → a 的 position 是 **11**、
         * b 是 **13**（不是 10 和 11）。
         *
         * 所以本层如果先把无效条目过滤掉，下游拿到的下标就整体前移，
         * **所有归因位置都会错**（后端按 position 算 CTR，两端都不报错）。
         * 故这里保留 null 占位，过滤与去重统一在
         * [ScreenAttribution.attribute] 里做。
         */
        fun parse(data: JSONObject?): ScreenPage {
            if (data == null) return ScreenPage(emptyList(), null, null)
            val list = data.optJSONArray(FIELD_LIST)
            val items = buildList {
                for (i in 0 until (list?.length() ?: 0)) {
                    // ⚠️ 无论解析成功与否都 add —— null 是占位，见方法注释
                    add(list?.optJSONObject(i)?.let { ScreenFeedItem.parse(it) })
                }
            }
            return ScreenPage(
                items = items,
                requestId = data.optString(FIELD_REQUEST_ID).takeIf { it.isNotBlank() },
                sessionId = data.optString(FIELD_SESSION_ID).takeIf { it.isNotBlank() },
            )
        }

        private const val FIELD_LIST = "list"
        private const val FIELD_REQUEST_ID = "request_id"
        private const val FIELD_SESSION_ID = "session_id"
    }
}
