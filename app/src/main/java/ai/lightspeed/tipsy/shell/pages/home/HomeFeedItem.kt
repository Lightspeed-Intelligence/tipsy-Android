package ai.lightspeed.tipsy.shell.pages.home

import ai.lightspeed.tipsy.shell.network.ScalarCoercion
import org.json.JSONArray
import org.json.JSONObject

/**
 * Home 卡片的展示模型（对齐 RN `CharacterGetRes` / `StoryGetRes` / `SimulatorGameProject`
 * 三类 item 里**卡片实际读到的字段**）。
 *
 * ## 为什么不照抄完整的 `Character` 类型
 *
 * RN 的 `Character` 有 40+ 字段，`HomeCard.tsx` 只读其中 12 个。全量映射会引入
 * 大量无消费方的字段，且每个都要处理标量漂移。方案 §8.4 的纪律是稳定 key +
 * 增量更新，不要求模型全等。
 *
 * ## 三种 item 收敛成一个 sealed 而不是三个列表
 *
 * `home.tsx` 的 `renderItem` 按 `'type' in item` / `'story_id' in item` /
 * `'project_id' in item` 做运行期形状判别 —— 那是 TS 联合类型的必然写法。
 * Kotlin 有 sealed，判别交给编译器：新增一类 item 时 `when` 会强制处理。
 */
sealed interface HomeFeedItem {
    /**
     * 列表的稳定 key（方案 §8.4：**禁止全量替换**，`LazyVerticalGrid` 要 stable key）。
     *
     * ⚠️ For You 的 key 里含 `requestId`（对齐 `getItemKey` 的
     * `${requestId}-${characterId}`）—— **不是纯 characterId**。同一角色可能出现在
     * 不同 session 的不同页，纯 id 会撞 key；Compose 撞 key 直接抛。
     */
    val stableKey: String

    /** 角色卡（`CharacterGetRes`）。 */
    data class Character(
        val characterId: String,
        val nickname: String,
        val introduction: String,
        val imageUrl: String,
        /** Android 用 `animated_image_url`（`HomeCard.tsx:85-88` 按平台分流，iOS 用 image_url）。 */
        val animatedImageUrl: String?,
        val creatorId: String,
        val creatorNickname: String?,
        val totalMessages: Long,
        val voiceSupported: Boolean,
        val isTranslated: Boolean,
        val lang: String?,
        /** 2 = 多角色故事型，显示左上角 story 标（`HomeCard.tsx:262`）。 */
        val characterType: Int?,
        val contentType: Int?,
        val nsfw: Boolean,
        val isChatted: Boolean,
        /** 推荐归因，仅 For You 有（`recommend_position` 等）。 */
        val recommendation: Recommendation? = null,
    ) : HomeFeedItem {
        override val stableKey: String
            get() = recommendation?.let { "${it.requestId}-$characterId" } ?: characterId
    }

    /** 多角色故事（`StoryGetRes`）。For You 混排里会出现 `type == "story"`。 */
    data class Story(
        val storyId: String,
        val title: String,
        val summary: String,
        val imageUrl: String,
        val animatedImageUrl: String?,
        val creatorId: String,
        val creatorNickname: String?,
        val totalMessages: Long,
        val isTranslated: Boolean,
        val lang: String?,
        val nsfw: Boolean,
        val recommendation: Recommendation? = null,
    ) : HomeFeedItem {
        override val stableKey: String
            get() = recommendation?.let { "${it.requestId}-$storyId" } ?: storyId
    }

    /**
     * World 系列的模拟游戏（`SimulatorGameProject`）。点击落 WebView —— 不迁。
     *
     * 字段映射照 `adaptSimulatorGameToHomeCard.ts`（RN 把 project 适配成 HomeCard
     * 的形状复用同一个卡片组件）。三处**不能凭字面猜**：
     * - 封面在 `assets.cover.content_url`，不是 `image_url`
     * - 消息数用 `stats.studio_chat_count`，不是 `play_count` / `chat_count`
     * - 简介字段名是 `introduction`，不是 `description`
     */
    data class World(
        val projectId: String,
        val name: String,
        val introduction: String,
        val coverUrl: String,
        val creatorId: String,
        val creatorNickname: String?,
        val interactionCount: Long,
        val versionChange: Boolean,
        val nsfw: Boolean,
    ) : HomeFeedItem {
        override val stableKey: String get() = projectId
    }

    /**
     * 推荐归因（For You + public_list 系列共用的最小集）。
     *
     * `requestId` 缺失时由**页级 fallback** 补一个 `client_<uuid>`
     * （对齐 `home.tsx:696-706` 的 `getFallbackRecommendRequestId(page)`）——
     * 同一响应页共用一个，这样曝光/点击/进聊天仍能串成完整漏斗。
     * 逐 item 各发一个 uuid 会让漏斗断开且**不报错**。
     */
    data class Recommendation(
        val requestId: String,
        val expId: String?,
        /** 原始响应里的下标（过滤前冻结）。 */
        val position: Int,
        val sessionId: String,
    )
}

/** 一页数据 + 分页元信息。 */
data class HomeFeedPage(
    val items: List<HomeFeedItem>,
    /**
     * **过滤前**的原始条数。
     *
     * ⚠️ 到底判定必须用这个而不是 `items.size`：For You 的一页可能全是当前
     * 还不支持的类型，过滤后 `items` 为空但**后端还有下一页**。
     * 用 `items.isEmpty()` 判到底会让列表提前停在半屏（iOS 的 HomeAPI 也记了这条）。
     */
    val rawItemCount: Int,
    /** World 接口独有；其余系列为 null，到底靠 [rawItemCount] == 0 判定。 */
    val hasMore: Boolean?,
    /**
     * 原始 `list` 数组，**仅 For You 第 0 页**带上，其余为 null。
     *
     * 给冷启动种子缓存用（[HomeForYouCache] 存的是原始响应片段，理由见该类注释）。
     * 不是所有页都带：其余页与其他系列不进缓存，留着只是白占内存。
     */
    val rawList: JSONArray? = null,
)

/**
 * 测试入口。
 *
 * `HomeFeedParser` 是 internal object，同模块单测本可直接调 —— 但那样测试要
 * import 一个实现细节。这三个薄包装让测试只依赖「解析这段 JSON 得到什么」，
 * 而解析器的内部结构可以改。
 */
internal fun parseForYouForTest(
    data: JSONObject,
    sessionId: String,
    page: Int,
    pageSize: Int,
    fallbackRequestId: String,
): HomeFeedPage = HomeFeedParser.parseForYou(data, sessionId, page, pageSize, fallbackRequestId)

internal fun parsePublicListForTest(
    data: JSONObject,
    sessionId: String,
    page: Int,
    pageSize: Int,
): HomeFeedPage = HomeFeedParser.parsePublicList(data, sessionId, page, pageSize)

internal fun parseWorldListForTest(data: JSONObject): HomeFeedPage =
    HomeFeedParser.parseWorldList(data)

internal fun parseTagsForTest(data: JSONObject): List<HomeTag> = HomeTagParser.parse(data)

/** JSON → 模型的解析。**宽松逐值**，单个字段坏不整页丢（对齐 `LocaleTable` 的策略）。 */
internal object HomeFeedParser {

    /** `/recommend/recommend_feed/list` 的 `{type, data}` 包装形态。 */
    fun parseForYou(data: JSONObject, sessionId: String, page: Int, pageSize: Int, fallbackRequestId: String): HomeFeedPage {
        val list = data.optJSONArray("list") ?: JSONArray()
        val items = ArrayList<HomeFeedItem>(list.length())
        for (i in 0 until list.length()) {
            val wrapper = list.optJSONObject(i) ?: continue
            val type = ScalarCoercion.optString(wrapper, "type") ?: "character"
            val payload = wrapper.optJSONObject("data") ?: continue
            val recommendation = HomeFeedItem.Recommendation(
                requestId = ScalarCoercion.optString(wrapper, "request_id")
                    ?.takeIf { it.isNotBlank() } ?: fallbackRequestId,
                expId = ScalarCoercion.optString(wrapper, "exp_id")?.takeIf { it.isNotBlank() },
                // ⚠️ 用**请求的** pageSize 算 rank，不用响应回显的 size ——
                // 尾页回显实际条数时按回显算会让 rank 回退（iOS HomeAPI 记的坑）
                position = page * pageSize + i,
                sessionId = sessionId,
            )
            when (type) {
                "story" -> parseStory(payload, recommendation)?.let(items::add)
                else -> parseCharacter(payload, recommendation)?.let(items::add)
            }
        }
        return HomeFeedPage(
            items = items,
            rawItemCount = list.length(),
            hasMore = null,
            // 只有第 0 页需要（种子缓存），其余页留 null 省内存
            rawList = if (page == 0) list else null,
        )
    }

    /** `/character/get/public_list` 的裸 `CharacterGetRes` 形态。 */
    fun parsePublicList(data: JSONObject, sessionId: String, page: Int, pageSize: Int): HomeFeedPage {
        val list = data.optJSONArray("list") ?: JSONArray()
        val items = ArrayList<HomeFeedItem>(list.length())
        for (i in 0 until list.length()) {
            val payload = list.optJSONObject(i) ?: continue
            parseCharacter(
                payload,
                HomeFeedItem.Recommendation(
                    // public_list 无 request_id，归因靠 session + position
                    requestId = sessionId,
                    expId = null,
                    position = page * pageSize + i,
                    sessionId = sessionId,
                ),
            )?.let(items::add)
        }
        return HomeFeedPage(items = items, rawItemCount = list.length(), hasMore = null)
    }

    /** `/game/public/projects` 的 World 列表。**它有 `has_more`**，与其他系列不同。 */
    fun parseWorldList(data: JSONObject): HomeFeedPage {
        val list = data.optJSONArray("items") ?: JSONArray()
        val items = ArrayList<HomeFeedItem>(list.length())
        for (i in 0 until list.length()) {
            val payload = list.optJSONObject(i) ?: continue
            // project_id 在 RN 类型里是 `string | number` —— optString 已容忍数字形态
            val projectId = ScalarCoercion.optString(payload, "project_id")
                ?.takeIf { it.isNotBlank() } ?: continue
            val creator = payload.optJSONObject("creator")
            items.add(
                HomeFeedItem.World(
                    projectId = projectId,
                    name = ScalarCoercion.optString(payload, "name").orEmpty(),
                    introduction = ScalarCoercion.optString(payload, "introduction").orEmpty(),
                    coverUrl = resolveWorldCoverUrl(payload),
                    creatorId = creator?.let { ScalarCoercion.optString(it, "user_id") }.orEmpty(),
                    creatorNickname = creator?.let { ScalarCoercion.optString(it, "nickname") }
                        ?.takeIf { it.isNotBlank() },
                    // ⚠️ 是 `studio_chat_count` 而**不是** play_count / chat_count
                    // （`adaptSimulatorGameToHomeCard.ts:64`）。挑错字段不会报错，
                    // 只是卡片上的数字与 RN 版对不上
                    interactionCount = payload.optJSONObject("stats")
                        ?.let { ScalarCoercion.optLong(it, "studio_chat_count") } ?: 0L,
                    versionChange = ScalarCoercion.optBoolean(payload, "version_change") ?: false,
                    nsfw = ScalarCoercion.optBoolean(payload, "nsfw") ?: false,
                ),
            )
        }
        return HomeFeedPage(
            items = items,
            rawItemCount = list.length(),
            hasMore = ScalarCoercion.optBoolean(data, "has_more") ?: false,
        )
    }

    /** 封面在 `assets.cover.content_url`（`adaptSimulatorGameToHomeCard.ts:4-13`）。 */
    private fun resolveWorldCoverUrl(payload: JSONObject): String =
        payload.optJSONObject("assets")
            ?.optJSONObject("cover")
            ?.let { ScalarCoercion.optString(it, "content_url") }
            ?.takeIf { it.isNotBlank() }
            .orEmpty()

    private fun parseCharacter(
        payload: JSONObject,
        recommendation: HomeFeedItem.Recommendation?,
    ): HomeFeedItem.Character? {
        val character = payload.optJSONObject("character") ?: return null
        val characterId = ScalarCoercion.optString(character, "character_id")
            ?.takeIf { it.isNotBlank() } ?: return null
        val stats = payload.optJSONObject("stats")
        val creator = payload.optJSONObject("creator")
        return HomeFeedItem.Character(
            characterId = characterId,
            nickname = ScalarCoercion.optString(character, "nickname").orEmpty(),
            introduction = ScalarCoercion.optString(character, "introduction").orEmpty(),
            imageUrl = ScalarCoercion.optString(character, "image_url").orEmpty(),
            animatedImageUrl = ScalarCoercion.optString(character, "animated_image_url")
                ?.takeIf { it.isNotBlank() },
            // creator.user_id 优先，回落 character.creator_id（对齐 `HomeCard.tsx:83`）
            creatorId = creator?.let { ScalarCoercion.optString(it, "user_id") }
                ?.takeIf { it.isNotBlank() }
                ?: ScalarCoercion.optString(character, "creator_id").orEmpty(),
            creatorNickname = creator?.let { ScalarCoercion.optString(it, "nickname") }
                ?.takeIf { it.isNotBlank() },
            totalMessages = stats?.let { ScalarCoercion.optLong(it, "total_messages") } ?: 0L,
            voiceSupported = ScalarCoercion.optBoolean(character, "voice_supported") ?: false,
            isTranslated = ScalarCoercion.optBoolean(character, "is_translated") ?: false,
            lang = ScalarCoercion.optString(character, "lang")?.takeIf { it.isNotBlank() },
            characterType = ScalarCoercion.optInt(character, "character_type"),
            contentType = ScalarCoercion.optInt(character, "content_type"),
            nsfw = ScalarCoercion.optBoolean(character, "nsfw") ?: false,
            isChatted = ScalarCoercion.optBoolean(character, "is_chatted") ?: false,
            recommendation = recommendation,
        )
    }

    private fun parseStory(
        payload: JSONObject,
        recommendation: HomeFeedItem.Recommendation?,
    ): HomeFeedItem.Story? {
        val storyId = ScalarCoercion.optString(payload, "story_id")
            ?.takeIf { it.isNotBlank() } ?: return null
        return HomeFeedItem.Story(
            storyId = storyId,
            title = ScalarCoercion.optString(payload, "title").orEmpty(),
            summary = ScalarCoercion.optString(payload, "summary").orEmpty(),
            imageUrl = ScalarCoercion.optString(payload, "image_url").orEmpty(),
            animatedImageUrl = ScalarCoercion.optString(payload, "animated_image_url")
                ?.takeIf { it.isNotBlank() },
            creatorId = ScalarCoercion.optString(payload, "creator_id").orEmpty(),
            creatorNickname = ScalarCoercion.optString(payload, "creator_nickname")
                ?.takeIf { it.isNotBlank() },
            totalMessages = ScalarCoercion.optLong(payload, "total_messages") ?: 0L,
            isTranslated = ScalarCoercion.optBoolean(payload, "is_translated") ?: false,
            lang = ScalarCoercion.optString(payload, "lang")?.takeIf { it.isNotBlank() },
            nsfw = ScalarCoercion.optBoolean(payload, "nsfw") ?: false,
            recommendation = recommendation,
        )
    }
}
