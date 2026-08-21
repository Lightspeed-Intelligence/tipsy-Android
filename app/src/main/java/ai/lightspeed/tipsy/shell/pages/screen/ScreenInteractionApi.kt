package ai.lightspeed.tipsy.shell.pages.screen

import ai.lightspeed.tipsy.shell.network.ApiClient
import ai.lightspeed.tipsy.shell.network.ApiException
import ai.lightspeed.tipsy.shell.network.AuthMode
import ai.lightspeed.tipsy.shell.network.ScalarCoercion
import ai.lightspeed.tipsy.shell.router.AppRoute
import org.json.JSONObject

/**
 * Screen 卡片交互接口。
 *
 * 列表仍走 [ScreenSource] 的 OPPORTUNISTIC 请求；点赞状态、点赞写入与评论列表
 * 都对齐 RN 的 `axiosAuth`，必须使用 [AuthMode.REQUIRED]。把两类请求拆成两个
 * 接缝，避免卡片交互失败取消正在进行的推荐分页链。
 */
class ScreenInteractionApi(private val apiClient: ApiClient) : ScreenInteractionSource {

    override suspend fun fetchLikeStatus(characterId: String): Boolean {
        val data = apiClient.post(
            path = PATH_CHARACTER_STATS,
            jsonBody = characterBody(characterId).toString(),
            authMode = AuthMode.REQUIRED,
        ).data ?: throw ApiException.Malformed("character_stats 缺少 data")
        return ScalarCoercion.optBoolean(data, FIELD_IS_LIKED)
            ?: throw ApiException.Malformed("character_stats 缺少 is_liked")
    }

    override suspend fun toggleLike(characterId: String) {
        // iOS 的同一接口按 EmptyData 处理；业务成功以 envelope code 为准，
        // 不要求 data 非空。最终状态再由 character_stats 权威对账。
        apiClient.post(
            path = PATH_TOGGLE_LIKE,
            jsonBody = characterBody(characterId).toString(),
            authMode = AuthMode.REQUIRED,
        )
    }

    override suspend fun fetchCommentCount(characterId: String): Long {
        val body = JSONObject()
            .put(FIELD_TARGET_TYPE, AppRoute.Comments.TARGET_TYPE_CHARACTER)
            .put(FIELD_TARGET_ID, characterId)
            .put(FIELD_ROOT_ID, ROOT_COMMENTS)
            .put(FIELD_PAGE, FIRST_PAGE)
            .put(FIELD_SIZE, COUNT_PROBE_SIZE)
            .put(FIELD_SORT_BY, SORT_HOT)
        val data = apiClient.post(
            path = PATH_COMMENT_LIST,
            jsonBody = body.toString(),
            authMode = AuthMode.REQUIRED,
        ).data ?: throw ApiException.Malformed("comment/list 缺少 data")
        return ScalarCoercion.optLong(data, FIELD_CHARACTER_COMMENT_COUNT)
            ?.coerceAtLeast(0L)
            ?: throw ApiException.Malformed("comment/list 缺少 char_comment_count")
    }

    private fun characterBody(characterId: String): JSONObject =
        JSONObject().put(FIELD_CHARACTER_ID, characterId)

    private companion object {
        const val PATH_CHARACTER_STATS = "/user/character_stats"
        const val PATH_TOGGLE_LIKE = "/user/like/character"
        const val PATH_COMMENT_LIST = "/comment/list"

        const val FIELD_CHARACTER_ID = "character_id"
        const val FIELD_IS_LIKED = "is_liked"
        const val FIELD_TARGET_TYPE = "target_type"
        const val FIELD_TARGET_ID = "target_id"
        const val FIELD_ROOT_ID = "root_id"
        const val FIELD_PAGE = "page"
        const val FIELD_SIZE = "size"
        const val FIELD_SORT_BY = "sort_by"
        const val FIELD_CHARACTER_COMMENT_COUNT = "char_comment_count"

        const val ROOT_COMMENTS = "0"
        const val FIRST_PAGE = 1
        const val COUNT_PROBE_SIZE = 1
        const val SORT_HOT = "hot"
    }
}

/** ViewModel 的卡片交互接缝；生产由 [ScreenInteractionApi] 实现。 */
interface ScreenInteractionSource {
    suspend fun fetchLikeStatus(characterId: String): Boolean
    suspend fun toggleLike(characterId: String)
    suspend fun fetchCommentCount(characterId: String): Long
}
