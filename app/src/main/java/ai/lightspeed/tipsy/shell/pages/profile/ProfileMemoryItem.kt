package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.network.ScalarCoercion
import org.json.JSONArray
import org.json.JSONObject

/**
 * 记忆 tab 的一条（`/plot/list/self`）。
 *
 * ## ⚠️ 这个接口是**关系型响应**，不是扁平列表
 *
 * 真实响应（抓自模拟器，`total:1`）：
 * ```
 * { "total":1,
 *   "plots":[ { "plot_id":"...", "character_id":"1710914565682712000",
 *               "creator_id":"1780977720500996003", "title":"测试", ... } ],
 *   "characters":{ "1710914565682712000":{ "nickname":"Emi", "image_url":"https://..." } },
 *   "creators":{  "1780977720500996003":{ "nickname":"Lee",  "avatar_url":"https://..." } } }
 * ```
 *
 * `characters` / `creators` 是 **map（id → 对象）**，不是数组 ——
 * 每条 plot 靠 `character_id` / `creator_id` 去 map 里 join
 * （`apis/plot.ts:86-88`）。这与创作列表（嵌套对象内联在 item 里）**形状完全不同**，
 * 别照搬 [ProfileCreatedItem] 的写法。
 *
 * ## 与 TS 类型 `Plot`（`types/plot.ts:14`）的实测出入
 *
 * | 字段 | TS 声明 | 真实响应 |
 * | --- | --- | --- |
 * | `created_at` / `updated_at` | `string` | **数字**（Unix 秒） |
 * | `weight` | 无 | 有（`0`） |
 * | `messages[].material_id` | 无 | 有 |
 *
 * 所以时间字段走 [ScalarCoercion.optLong] 而不是 optString —— 类型文件不是权威，
 * 真实响应才是（Profile 首刀「卡片全空白」就是信了类型文件的字段位置）。
 *
 * @property characterName join 来的角色名；join 不中时为 null（UI 走占位）
 * @property characterImageUrl join 来的角色配图（**卡片背景**，`PlotItem.tsx:146`），已是完整 URL
 * @property characterFaceUrl join 来的角色头像（**头像位**，`PlotItem.tsx:229` 用 `face_url`，
 *   与背景是两个不同字段，别混用）
 * @property messageCount `messages` 数组长度，RN 卡片底部显示 "N messages >"
 * @property previewMessages 卡片预览的前 3 条消息（`PlotItem.tsx:69` `filter(index < 3)`）
 */
data class ProfileMemoryItem(
    val plotId: String,
    val title: String?,
    val reviewStage: String?,
    val isPublic: Boolean,
    val nsfw: Boolean,
    val createdAt: Long?,
    val messageCount: Int,
    val previewMessages: List<MemoryPreviewMessage>,
    val characterId: String?,
    val characterName: String?,
    val characterImageUrl: String?,
    val characterFaceUrl: String?,
    val creatorNickname: String?,
    val creatorAvatarUrl: String?,
) : ProfileListEntry {

    /** 去重键用 `plot_id`（RN 是 `uniqueMap.set(item.plot.plot_id, item)`，
     * `useProfileMemories.ts:88`）。 */
    override val dedupeKey: String get() = plotId

    companion object {

        /**
         * 解析一条 plot，并从两个 map 里 join 出角色与创作者。
         *
         * `plotId` 缺失的整条丢弃 —— 它是去重键，没有它无法参与分页去重。
         *
         * @param characters 顶层 `characters` map，可为 null（join 不中就留空字段）
         * @param creators 顶层 `creators` map
         */
        fun parse(
            plot: JSONObject,
            characters: JSONObject?,
            creators: JSONObject?,
        ): ProfileMemoryItem? {
            val plotId = ScalarCoercion.optString(plot, FIELD_PLOT_ID)
                ?.takeIf { it.isNotBlank() } ?: return null

            val characterId = ScalarCoercion.optString(plot, FIELD_CHARACTER_ID)
                ?.takeIf { it.isNotBlank() }
            val creatorId = ScalarCoercion.optString(plot, FIELD_CREATOR_ID)
                ?.takeIf { it.isNotBlank() }

            // join：map 里按 id 取。取不到不是错误 —— 角色被删时 map 里就没有这一项
            val character = characterId?.let { characters?.optJSONObject(it) }
            val creator = creatorId?.let { creators?.optJSONObject(it) }

            return ProfileMemoryItem(
                plotId = plotId,
                title = ScalarCoercion.optString(plot, FIELD_TITLE)?.takeIf { it.isNotBlank() },
                reviewStage = ScalarCoercion.optString(plot, FIELD_REVIEW_STAGE)
                    ?.takeIf { it.isNotBlank() },
                isPublic = plot.optBoolean(FIELD_IS_PUBLIC, false),
                nsfw = plot.optBoolean(FIELD_NSFW, false),
                // ⚠️ 数字而非字符串，见类注释的实测出入表
                createdAt = ScalarCoercion.optLong(plot, FIELD_CREATED_AT),
                messageCount = plot.optJSONArray(FIELD_MESSAGES)?.length() ?: 0,
                previewMessages = parsePreviewMessages(plot.optJSONArray(FIELD_MESSAGES)),
                characterId = characterId,
                characterName = character?.let {
                    ScalarCoercion.optString(it, FIELD_NICKNAME)?.takeIf { s -> s.isNotBlank() }
                },
                // map 里已是完整 URL（实测），但仍做一次校验：
                // 相对路径喂给 Coil 会静默失败（Profile 首刀的教训）
                characterImageUrl = character
                    ?.let { ScalarCoercion.optString(it, FIELD_IMAGE_URL) }
                    ?.takeIf { it.isHttpUrl() },
                characterFaceUrl = character
                    ?.let { ScalarCoercion.optString(it, FIELD_FACE_URL) }
                    ?.takeIf { it.isHttpUrl() },
                creatorNickname = creator?.let {
                    ScalarCoercion.optString(it, FIELD_NICKNAME)?.takeIf { s -> s.isNotBlank() }
                },
                creatorAvatarUrl = creator
                    ?.let { ScalarCoercion.optString(it, FIELD_AVATAR_URL) }
                    ?.takeIf { it.isHttpUrl() },
            )
        }

        /**
         * 预览取前 3 条，只留卡片要用的两个字段。
         * `sender_type` 只区分 `character` 与其他（RN 的分支就是二元判等，
         * `PlotItem.tsx:263`）。
         */
        private fun parsePreviewMessages(messages: JSONArray?): List<MemoryPreviewMessage> =
            buildList {
                for (i in 0 until minOf(messages?.length() ?: 0, PREVIEW_MESSAGE_COUNT)) {
                    val msg = messages?.optJSONObject(i) ?: continue
                    add(
                        MemoryPreviewMessage(
                            isFromCharacter =
                                ScalarCoercion.optString(msg, FIELD_SENDER_TYPE) == SENDER_CHARACTER,
                            content = ScalarCoercion.optString(msg, FIELD_CONTENT)
                                ?.takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }

        private fun String.isHttpUrl(): Boolean =
            startsWith("http://") || startsWith("https://")

        /** 卡片预览条数（`PlotItem.tsx:69`）。 */
        const val PREVIEW_MESSAGE_COUNT = 3

        private const val FIELD_PLOT_ID = "plot_id"
        private const val FIELD_CHARACTER_ID = "character_id"
        private const val FIELD_CREATOR_ID = "creator_id"
        private const val FIELD_TITLE = "title"
        private const val FIELD_REVIEW_STAGE = "review_stage"
        private const val FIELD_IS_PUBLIC = "is_public"
        private const val FIELD_NSFW = "nsfw"
        private const val FIELD_CREATED_AT = "created_at"
        private const val FIELD_MESSAGES = "messages"
        private const val FIELD_SENDER_TYPE = "sender_type"
        private const val FIELD_CONTENT = "content"
        private const val SENDER_CHARACTER = "character"
        private const val FIELD_NICKNAME = "nickname"
        private const val FIELD_IMAGE_URL = "image_url"
        private const val FIELD_FACE_URL = "face_url"
        private const val FIELD_AVATAR_URL = "avatar_url"
    }
}

/**
 * 记忆卡预览气泡的一条。
 *
 * @property isFromCharacter `sender_type == "character"`。角色消息的发送者名
 *   显示角色昵称、用户消息显示创作者昵称（`PlotItem.tsx:281-284`）
 */
data class MemoryPreviewMessage(
    val isFromCharacter: Boolean,
    val content: String?,
)

/**
 * 记忆列表的一页。
 *
 * ⚠️ **到底判定用 `total`**，同创作列表：RN 的判据是
 * `已去重累计数 >= total`（`useProfileMemories.ts:112-115`），
 * 且 `plots` 为空时直接算到底（`if (!memoryData?.[0]?.length) return true`）。
 * 那一步在 ViewModel，不在这里。
 *
 * 注意 RN 侧 `getSelfPlotList` 在 `!total` 时**直接返回空数组**
 * （`apis/plot.ts:80`），连 join 都不做 —— 所以 total 为 0 时 items 必然为空。
 */
data class ProfileMemoryPage(
    val items: List<ProfileMemoryItem>,
    val total: Long,
) {
    companion object {
        fun parse(data: JSONObject?): ProfileMemoryPage {
            if (data == null) return ProfileMemoryPage(emptyList(), 0L)
            val plots = data.optJSONArray(FIELD_PLOTS)
            val characters = data.optJSONObject(FIELD_CHARACTERS)
            val creators = data.optJSONObject(FIELD_CREATORS)
            val items = buildList {
                for (i in 0 until (plots?.length() ?: 0)) {
                    val plot = plots?.optJSONObject(i) ?: continue
                    ProfileMemoryItem.parse(plot, characters, creators)?.let(::add)
                }
            }
            return ProfileMemoryPage(
                items = items,
                total = ScalarCoercion.optLong(data, FIELD_TOTAL) ?: 0L,
            )
        }

        private const val FIELD_PLOTS = "plots"
        private const val FIELD_CHARACTERS = "characters"
        private const val FIELD_CREATORS = "creators"
        private const val FIELD_TOTAL = "total"
    }
}
