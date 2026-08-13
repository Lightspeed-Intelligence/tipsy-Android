package ai.lightspeed.tipsy.shell.pages.search

import ai.lightspeed.tipsy.shell.network.ApiClient
import ai.lightspeed.tipsy.shell.network.AuthMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Search 的接口层（方案 §8.1 Search 行，六个端点 2026-08-13 逐个核实）。
 *
 * ## 鉴权模式：四个 OPPORTUNISTIC + 两个 REQUIRED
 *
 * | 端点 | RN axios 实例 | 本层 |
 * | --- | --- | --- |
 * | `/search/character_search` | `axiosPublic` | `OPPORTUNISTIC` |
 * | `/search/user_search` | `axiosPublic` | `OPPORTUNISTIC` |
 * | `/search/character/suggest` | `axiosPublic` | `OPPORTUNISTIC` |
 * | `/search/popular_search_terms/app` | `axiosPublic` | `OPPORTUNISTIC` |
 * | `/search/recent_history` | `axiosAuth` | `REQUIRED` |
 * | `/search/clear_history` | `axiosAuth` | `REQUIRED` |
 *
 * ⚠️ **前四个必须 `OPPORTUNISTIC`，不是 `NONE`**。`axiosPublic` 在 RN 侧
 * 仍会带上 token（拦截器统一注入），而 `character_search` **带 token 才会把
 * 搜索词记入最近搜索**。iOS 曾错用 `authorized: false` 发这个请求，症状是
 * 「搜了很多次，最近搜索永远是空的」—— 而且不报错，极难定位（`AuthMode`
 * 类注释也记着这个事故）。
 */
interface SearchSource {
    suspend fun searchCharacters(query: SearchCharacterQuery): CharacterSearchPage
    suspend fun searchCreators(searchTerm: String, page: Int): CreatorSearchPage
    suspend fun fetchSuggestions(searchTerm: String): List<String>
    suspend fun fetchPopularTerms(): List<String>
    suspend fun fetchRecentHistory(): List<String>
    suspend fun clearRecentHistory()
}

/**
 * 角色搜索请求参数（`apis/character.ts:395-412` + `useSearch.ts:104-134`）。
 *
 * `gender` 是 **UI 文案 → 后端枚举**映射后的值（`Female`→`female`、
 * `Male`→`male`、`Non-binary`→`other`、`All`/空→null 且**整个键不发**）。
 * 映射由 P2 的筛选状态负责，不在本层重复。
 */
data class SearchCharacterQuery(
    val searchTerm: String,
    val page: Int,
    val tagIds: List<String> = emptyList(),
    val nsfw: Boolean = false,
    val languageCode: String,
    /** 已映射的后端枚举值；null 表示不发这个键。 */
    val gender: String? = null,
    /** 已映射的后端 sorting 枚举（`Most Interacted`→`MostInteracted` 等）。 */
    val sorting: String = "Recommended",
    val contentRating: String = "All",
)

class SearchApi(
    private val apiClient: ApiClient,
) : SearchSource {

    /**
     * 角色搜索。
     *
     * 请求体照 `useSearch.ts:120-134`：`size` 恒 20；`gender` 为 null 时
     * **整个键不发**（RN 用 `delete params.gender`，不是发 null —— 后端对
     * 「键存在但为 null」和「键不存在」处理不同）。
     */
    override suspend fun searchCharacters(query: SearchCharacterQuery): CharacterSearchPage {
        val body = JSONObject()
            .put("search_term", query.searchTerm)
            .put("page", query.page)
            .put("size", PAGE_SIZE)
            .put("tags", JSONArray(query.tagIds))
            .put("nsfw", query.nsfw)
            .put("language_code", query.languageCode)
            .put("sorting", query.sorting)
            .put("content_rating", query.contentRating)
        // ⚠️ null 时不发这个键（对齐 RN 的 delete params.gender）
        query.gender?.let { body.put("gender", it) }

        val envelope = apiClient.post(
            path = "search/character_search",
            jsonBody = body.toString(),
            authMode = AuthMode.OPPORTUNISTIC,
        )
        return SearchParser.parseCharacterPage(envelope.data)
    }

    /** 创作者搜索（`apis/character.ts:433-447`，`size` 恒 20）。 */
    override suspend fun searchCreators(searchTerm: String, page: Int): CreatorSearchPage {
        val body = JSONObject()
            .put("search_term", searchTerm)
            .put("page", page)
            .put("size", PAGE_SIZE)

        val envelope = apiClient.post(
            path = "search/user_search",
            jsonBody = body.toString(),
            authMode = AuthMode.OPPORTUNISTIC,
        )
        return SearchParser.parseCreatorPage(envelope.data)
    }

    /**
     * 搜索建议词（`apis/character.ts:385-391`）。
     *
     * ⚠️ 响应 `data` 是**字符串数组**，走 `dataArray` 不是 `data`
     * （`ApiEnvelope.parse` 对数组形态只填 `dataArray`，`data` 恒 null）。
     */
    override suspend fun fetchSuggestions(searchTerm: String): List<String> {
        val envelope = apiClient.post(
            path = "search/character/suggest",
            jsonBody = JSONObject().put("search_term", searchTerm).toString(),
            authMode = AuthMode.OPPORTUNISTIC,
        )
        return SearchParser.parseStringList(envelope.dataArray)
    }

    /**
     * 热门搜索词（`apis/character.ts:377-383`）。
     *
     * ⚠️ 响应是 `{ Score, Member }[]`（**大写首字母**，Redis ZSET 直出），
     * 取 `Member` 作为词。空 body 的 POST。
     */
    override suspend fun fetchPopularTerms(): List<String> {
        val envelope = apiClient.post(
            path = "search/popular_search_terms/app",
            authMode = AuthMode.OPPORTUNISTIC,
        )
        return SearchParser.parsePopularTerms(envelope.dataArray)
    }

    /** 最近搜索（`apis/character.ts:361-367`，`size: 10`）。响应同为数组形态。 */
    override suspend fun fetchRecentHistory(): List<String> {
        val envelope = apiClient.post(
            path = "search/recent_history",
            jsonBody = JSONObject().put("size", RECENT_HISTORY_SIZE).toString(),
            authMode = AuthMode.REQUIRED,
        )
        return SearchParser.parseStringList(envelope.dataArray)
    }

    /** 清空最近搜索（`apis/character.ts:370-375`，空 body）。 */
    override suspend fun clearRecentHistory() {
        apiClient.post(
            path = "search/clear_history",
            authMode = AuthMode.REQUIRED,
        )
    }

    private companion object {
        /** 两个搜索接口的分页大小（`useSearch.ts:123`、`apis/character.ts:445`）。 */
        const val PAGE_SIZE = 20

        /** 最近搜索条数（`apis/character.ts:364`）。 */
        const val RECENT_HISTORY_SIZE = 10
    }
}
