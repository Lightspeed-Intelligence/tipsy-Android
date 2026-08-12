package ai.lightspeed.tipsy.shell.pages.home

import ai.lightspeed.tipsy.shell.network.ApiClient
import ai.lightspeed.tipsy.shell.network.AuthMode
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Home 信息流接口（方案 §8.1 Home 行 + `apis/character.ts` 实测）。
 *
 * ## 一个 Repository，不是 7 个
 *
 * 方案 §8.1 明确：「一个 Repository + sorting 枚举，**不要写 7 个 Repository**」。
 * RN 侧 7 个系列共用一个 `useCharacterList`，只在 SWR key 分支上分流。
 *
 * ## ⚠️ 三个接口的鉴权模式都是 OPPORTUNISTIC，不是 NONE
 *
 * 三者在 RN 侧走的都是 `axiosPublic`（已核实 `character.ts:40/59/91`、
 * `simulatorGame.ts:41`）。方案 §4.5 与 `AuthMode` 的注释都记了 iOS 的事故：
 * 把 `axiosPublic` 实现成"永不带 token"会让功能**静默失效** ——
 * 这里对应的是 **For You 拿不到个性化推荐、Following 系列返回空**
 * （Following 靠 token 判断"谁在关注"）。
 *
 * ## 分页参数名不统一，别混
 *
 * | 接口 | 每页字段 | 值 |
 * | --- | --- | --- |
 * | `/recommend/recommend_feed/list` | `size` | 21 |
 * | `/character/get/public_list` | `size` | 21 |
 * | `/game/public/projects` | `size` | **20** |
 *
 * World 是 20 而不是 21（`useHomeCharacterLists.ts:149`）。方案 §8.1「固定值，
 * 不要『优化』」—— 改了会让翻页边界与 RN 不一致，重复/缺失都可能。
 * （Screen 页的同类参数叫 `page_size`，那是另一个接口，见方案 §8.1 Screen 行。）
 */
/**
 * 数据源接缝。
 *
 * `HomeViewModel` 依赖它而不是 [HomeApi] 具体类 —— 让「session 语义 / 去重续拉 /
 * 到底判定」这些**最容易写错且错了不报错**的编排逻辑能用 JVM 单测覆盖，
 * 不必起 MockWebServer。请求契约本身由 `HomeApiContractTest` 用真实 HTTP 验。
 *
 * 同 `ShellTokenStore.TokenPersistence` 的做法（那里也是为可测性抽接口）。
 */
interface HomeFeedSource {
    suspend fun fetchPage(
        series: HomeSeries,
        page: Int,
        gender: HomeGender,
        nsfw: Boolean,
        languageCode: String,
        tagIds: List<String>,
        contentType: Int?,
        sessionId: String,
    ): HomeFeedPage
}

class HomeApi(private val apiClient: ApiClient) : HomeFeedSource {

    /**
     * 拉一页。
     *
     * @param page 从 **0** 开始（RN 的 `useSWRInfinite` pageIndex 即 0-based）
     * @param sessionId For You 的推荐池锁 / public_list 的 tracking session
     */
    override suspend fun fetchPage(
        series: HomeSeries,
        page: Int,
        gender: HomeGender,
        nsfw: Boolean,
        languageCode: String,
        tagIds: List<String>,
        contentType: Int?,
        sessionId: String,
    ): HomeFeedPage {
        // Following 不带标签（见 HomeSeries.supportsTagFilter）
        val effectiveTags = if (series.supportsTagFilter) tagIds else emptyList()

        return when (series) {
            HomeSeries.FOR_YOU -> fetchForYou(
                page, gender, nsfw, languageCode, effectiveTags, contentType, sessionId,
            )
            HomeSeries.WORLD -> fetchWorld(page, nsfw, languageCode)
            else -> fetchPublicList(
                series, page, gender, nsfw, languageCode, effectiveTags, contentType, sessionId,
            )
        }
    }

    private suspend fun fetchForYou(
        page: Int,
        gender: HomeGender,
        nsfw: Boolean,
        languageCode: String,
        tagIds: List<String>,
        contentType: Int?,
        sessionId: String,
    ): HomeFeedPage {
        val body = JSONObject().apply {
            put("page", page)
            put("size", PAGE_SIZE)
            put("nsfw", nsfw)
            put("language_code", languageCode)
            // tag_ids **必须发空数组而不是省略**（`character.ts:44` 显式
            // `tag_ids: req.tag_ids || []`）—— 省略时后端行为未定义
            put("tag_ids", JSONArray(tagIds))
            put("session_id", sessionId)
            // gender=All 与 content_type 多选时**省略字段**，不是发 null。
            // `content_type` 只在恰好选中一个时才传（`useHomeCharacterLists.ts:40-43`），
            // 这个条件很容易漏 —— 漏了会让多选筛选变成"只按第一个筛"
            gender.apiValue?.let { put("gender", it) }
            contentType?.let { put("content_type", it) }
        }
        val envelope = apiClient.post(
            path = "/recommend/recommend_feed/list",
            jsonBody = body.toString(),
            authMode = AuthMode.OPPORTUNISTIC,
        )
        val data = envelope.data ?: return HomeFeedPage(emptyList(), 0, null)
        return HomeFeedParser.parseForYou(
            data = data,
            sessionId = sessionId,
            page = page,
            pageSize = PAGE_SIZE,
            // 页级 fallback request_id（对齐 `home.tsx:696-706`）：后端偶发漏
            // request_id 时同一页共用一个，曝光/点击/进聊天仍串成完整漏斗。
            // 逐 item 各发一个会让漏斗断开且不报错
            fallbackRequestId = "client_${UUID.randomUUID()}",
        )
    }

    private suspend fun fetchPublicList(
        series: HomeSeries,
        page: Int,
        gender: HomeGender,
        nsfw: Boolean,
        languageCode: String,
        tagIds: List<String>,
        contentType: Int?,
        sessionId: String,
    ): HomeFeedPage {
        val body = JSONObject().apply {
            // sorting 是该接口的必需参数。走到这里 series.sorting 必非空
            // （FOR_YOU/WORLD 已在上面分流），null 说明枚举加了新成员却没分流
            put("sorting", requireNotNull(series.sorting) { "系列 ${series.key} 缺 sorting 却走了 public_list" })
            put("page", page)
            put("size", PAGE_SIZE)
            put("nsfw", nsfw)
            put("language_code", languageCode)
            put("tag_ids", JSONArray(tagIds))
            gender.apiValue?.let { put("gender", it) }
            contentType?.let { put("content_type", it) }
            // ⚠️ `recommend_tracking_session_id` 是**客户端字段，请求前必须剥掉**
            // （`character.ts:59-61` 解构出来后只把 apiReq 发出去）。
            // 发上去不会报错，但那是后端不认识的字段，且会让请求体与 RN 不一致
        }
        val envelope = apiClient.post(
            path = "/character/get/public_list",
            jsonBody = body.toString(),
            authMode = AuthMode.OPPORTUNISTIC,
        )
        val data = envelope.data ?: return HomeFeedPage(emptyList(), 0, null)
        return HomeFeedParser.parsePublicList(data, sessionId, page, PAGE_SIZE)
    }

    private suspend fun fetchWorld(page: Int, nsfw: Boolean, languageCode: String): HomeFeedPage {
        val body = JSONObject().apply {
            put("page", page)
            put("size", WORLD_PAGE_SIZE)
            put("nsfw", nsfw)
            // RN 在这里对空语言回落 'en'（`useHomeCharacterLists.ts:147`
            // `language_code: language || 'en'`）—— 其他接口不做这个回落
            put("language_code", languageCode.ifBlank { "en" })
        }
        val envelope = apiClient.post(
            path = "/game/public/projects",
            jsonBody = body.toString(),
            authMode = AuthMode.OPPORTUNISTIC,
        )
        val data = envelope.data ?: return HomeFeedPage(emptyList(), 0, hasMore = false)
        return HomeFeedParser.parseWorldList(data)
    }

    companion object {
        /** For You / public_list 每页 21（`useHomeCharacterLists.ts:55`）。 */
        const val PAGE_SIZE = 21

        /** World 每页 **20**（`useHomeCharacterLists.ts:149`）—— 与上面不同，见类注释。 */
        const val WORLD_PAGE_SIZE = 20
    }
}
