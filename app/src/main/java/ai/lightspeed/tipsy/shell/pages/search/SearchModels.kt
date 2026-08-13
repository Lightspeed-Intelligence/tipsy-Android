package ai.lightspeed.tipsy.shell.pages.search

import ai.lightspeed.tipsy.shell.network.ScalarCoercion
import ai.lightspeed.tipsy.shell.pages.home.HomeFeedItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * 搜索词的来源（`useSearch.ts:12`）。埋点 `search_type` 直接发这个值，
 * 所以是下划线小写而不是驼峰。
 */
enum class SearchWay(val trackingValue: String) {
    SEARCH("search"),
    RECENT_SEARCH("recent_search"),
    POPULAR_SEARCH("popular_search"),
}

/**
 * 角色搜索的敏感词结果（`types/character.ts:570`）。
 *
 * `IDLE` 不是后端值，是「还没有成功返回过」的本地初态 —— 空态按钮的判定
 * 依赖它区分「搜了没结果」与「还没搜/搜失败」（见 [shouldShowCreateCharacterButton]）。
 */
enum class CharacterSearchOutcome {
    IDLE,
    SAFE,

    /** 直接命中敏感词：不给「创建角色」出口。 */
    DIRECT,

    /** 关联命中：仍然给创建出口。 */
    RELATED,
    ;

    companion object {
        /** `search_sensitive_type` 字段值 → 枚举；缺失即 [SAFE]（`useSearch.ts:144-146`）。 */
        fun fromResponse(raw: String?): CharacterSearchOutcome = when (raw) {
            "direct" -> DIRECT
            "related" -> RELATED
            else -> SAFE
        }
    }
}

/**
 * 空态「Create Now」按钮的显示判定。
 *
 * 照搬 `app/search/searchEmptyState.ts` 的五个条件，**逐条都有理由**：
 * - 有查询词（空搜索框时展示的是最近/热门，不是空态）
 * - `outcome != IDLE`：还没成功返回过就不显示 —— 否则请求失败时会诱导用户去创建
 * - `outcome != DIRECT`：直接命中敏感词不给创建出口（合规要求）
 * - 不在 loading：请求在途时结果数恒 0，会闪一下按钮
 * - 结果数为 0
 *
 * RN 侧 `searchEmptyState.test.ts` 的五个用例在壳侧一比一对等（见 SearchEmptyStateTest）。
 */
fun shouldShowCreateCharacterButton(
    query: String,
    outcome: CharacterSearchOutcome,
    loading: Boolean,
    resultCount: Int,
): Boolean = query.isNotBlank() &&
    outcome != CharacterSearchOutcome.IDLE &&
    outcome != CharacterSearchOutcome.DIRECT &&
    !loading &&
    resultCount == 0

/** 角色搜索一页（`types/character.ts:572-580`）。 */
data class CharacterSearchPage(
    val total: Int,
    val searchSessionId: String,
    val outcome: CharacterSearchOutcome,
    val hits: List<HomeFeedItem.Character>,
    /** 命中结果的标签聚合，仅第 1 页返回。P2 的横滑标签栏用。 */
    val tagAggIds: List<String>,
)

/** 创作者搜索一页（`apis/character.ts:433-447`）。 */
data class CreatorSearchPage(
    val total: Int,
    val searchSessionId: String,
    val hits: List<CreatorResult>,
)

/** 创作者搜索结果行（`types/user.ts:111-122`）。 */
data class CreatorResult(
    val userId: String,
    val nickname: String,
    val avatar: String,
    val avatarDecorationCode: String?,
    val bio: String,
    val followeesCount: Long,
    val totalInteractions: Long,
    val createdCharactersCount: Long,
)

/**
 * 搜索响应解析。
 *
 * ## 为什么角色结果复用 [HomeFeedItem.Character]
 *
 * RN 的 `CharacterResultList` 直接把搜索结果塞进 `HomeCard`（用了 5 个
 * `@ts-ignore` 硬凑字段形状，`CharacterResultList.tsx:88-101`）。壳侧
 * 已有 `HomeCard(item: HomeFeedItem)`，所以这里把搜索的扁平结构**翻译成**
 * 同一个模型 —— 卡片渲染零改动，也不用再养一套搜索专用卡片。
 *
 * ⚠️ **字段名与 Home 列表接口不同**，逐个核对过（`types/character.ts:549-568`）：
 * - `nickname`（Home 卡是 `character.nickname`，这里在顶层）
 * - `total_messages` 是**字符串**（Home 是 `stats.total_messages` 数字）
 * - `creator_nickname` / `creator_id` 在顶层（Home 在 `creator` 子对象）
 * - **没有** `animated_image_url` —— 搜索结果无动图封面，恒静图
 * - **没有** `is_translated` / `lang`（RN 硬写 `is_translated: false`）
 */
internal object SearchParser {

    fun parseCharacterPage(data: JSONObject?): CharacterSearchPage {
        if (data == null) {
            return CharacterSearchPage(0, "", CharacterSearchOutcome.IDLE, emptyList(), emptyList())
        }
        val hitsArray = data.optJSONArray("hits")
        val hits = ArrayList<HomeFeedItem.Character>(hitsArray?.length() ?: 0)
        for (i in 0 until (hitsArray?.length() ?: 0)) {
            val raw = hitsArray?.optJSONObject(i) ?: continue
            hits.add(parseCharacterHit(raw) ?: continue)
        }
        return CharacterSearchPage(
            total = ScalarCoercion.optInt(data, "total") ?: 0,
            searchSessionId = ScalarCoercion.optString(data, "search_session_id").orEmpty(),
            // ⚠️ 有 data 但没有 search_sensitive_type = safe（不是 idle）——
            // idle 只在「请求失败」或「还没搜」时出现，由 ViewModel 设置
            outcome = CharacterSearchOutcome.fromResponse(
                ScalarCoercion.optString(data, "search_sensitive_type"),
            ),
            hits = hits,
            tagAggIds = parseTagAggs(data.optJSONArray("tag_aggs")),
        )
    }

    /** `character_id` 缺失的条目直接丢弃 —— 没有 id 无法点击也无法去重曝光。 */
    private fun parseCharacterHit(raw: JSONObject): HomeFeedItem.Character? {
        val characterId = ScalarCoercion.optString(raw, "character_id")
            ?.takeIf { it.isNotBlank() } ?: return null
        return HomeFeedItem.Character(
            characterId = characterId,
            nickname = ScalarCoercion.optString(raw, "nickname").orEmpty(),
            introduction = ScalarCoercion.optString(raw, "introduction").orEmpty(),
            imageUrl = ScalarCoercion.optString(raw, "image_url").orEmpty(),
            // 搜索结果不下发动图封面（`ISearchResultData` 无该字段）—— 恒静图
            animatedImageUrl = null,
            creatorId = ScalarCoercion.optString(raw, "creator_id").orEmpty(),
            creatorNickname = ScalarCoercion.optString(raw, "creator_nickname"),
            // ⚠️ 字符串形态的数字（`total_messages: string`），走 ScalarCoercion 兜住
            totalMessages = ScalarCoercion.optLong(raw, "total_messages") ?: 0L,
            voiceSupported = ScalarCoercion.optBoolean(raw, "voice_supported") ?: false,
            // RN 硬写 false（`CharacterResultList.tsx:90`）—— 搜索结果不做译文标记
            isTranslated = false,
            lang = null,
            characterType = ScalarCoercion.optInt(raw, "character_type"),
            contentType = ScalarCoercion.optInt(raw, "content_type"),
            nsfw = ScalarCoercion.optBoolean(raw, "nsfw") ?: false,
            // 搜索结果不带「聊过」标记
            isChatted = false,
        )
    }

    fun parseCreatorPage(data: JSONObject?): CreatorSearchPage {
        if (data == null) return CreatorSearchPage(0, "", emptyList())
        val hitsArray = data.optJSONArray("hits")
        val hits = ArrayList<CreatorResult>(hitsArray?.length() ?: 0)
        for (i in 0 until (hitsArray?.length() ?: 0)) {
            val raw = hitsArray?.optJSONObject(i) ?: continue
            val userId = ScalarCoercion.optString(raw, "user_id")
                ?.takeIf { it.isNotBlank() } ?: continue
            hits.add(
                CreatorResult(
                    userId = userId,
                    nickname = ScalarCoercion.optString(raw, "nickname").orEmpty(),
                    avatar = ScalarCoercion.optString(raw, "avatar").orEmpty(),
                    avatarDecorationCode = ScalarCoercion.optString(raw, "avatar_decoration_code"),
                    bio = ScalarCoercion.optString(raw, "bio").orEmpty(),
                    followeesCount = ScalarCoercion.optLong(raw, "followees_count") ?: 0L,
                    totalInteractions = ScalarCoercion.optLong(raw, "total_interactions") ?: 0L,
                    createdCharactersCount =
                    ScalarCoercion.optLong(raw, "created_characters_count") ?: 0L,
                ),
            )
        }
        return CreatorSearchPage(
            total = ScalarCoercion.optInt(data, "total") ?: 0,
            searchSessionId = ScalarCoercion.optString(data, "search_session_id").orEmpty(),
            hits = hits,
        )
    }

    /** `tag_aggs: { tag_id, count }[]` → 有序 id 列表（后端已按命中数倒序）。 */
    private fun parseTagAggs(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val ids = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val raw = array.optJSONObject(i) ?: continue
            val id = ScalarCoercion.optString(raw, "tag_id")?.takeIf { it.isNotBlank() } ?: continue
            ids.add(id)
        }
        return ids
    }

    /**
     * `data` 为字符串数组的响应（建议词 / 最近搜索）。
     *
     * ⚠️ 这两个接口的 `data` 是**数组不是对象**，调用方要传 `envelope.dataArray`
     * —— `ApiEnvelope.parse` 见到数组时只填 `dataArray`，`data` 恒为 null，
     * 传错的表现是「接口 200 但列表永远空」。
     */
    fun parseStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            // JSON null 会被 optString 读成字面量 "null"（同 ApiEnvelope.parse 的坑）
            if (array.isNull(i)) continue
            val term = array.optString(i).takeIf { it.isNotBlank() && it != "null" } ?: continue
            out.add(term)
        }
        return out
    }

    /**
     * 热门搜索词：`{ Score, Member }[]`。
     *
     * ⚠️ **字段首字母大写** —— Redis ZSET 直出没转过命名风格
     * （`apis/character.ts:379-381`）。写成 `member` 会得到空列表。
     */
    fun parsePopularTerms(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val raw = array.optJSONObject(i) ?: continue
            val term = ScalarCoercion.optString(raw, "Member")
                ?.takeIf { it.isNotBlank() } ?: continue
            out.add(term)
        }
        return out
    }
}
