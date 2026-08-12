package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.network.ScalarCoercion
import org.json.JSONObject

/**
 * 收藏 / 点赞 tab 的一条 —— 两个接口**同一响应形状**
 * （`likeCharacterlistRes`，`types/profile.ts:211-219`；`characters` 数组元素
 * 是 `characterLists`，`:221-237`）。收藏与点赞共用本模型，靠 tab 区分。
 *
 * ## ⚠️ 与创作卡的三处形状差异
 *
 * 1. 元素**扁平**（不是创作列表的 `{item_type, character:{...}}` 嵌套）
 * 2. 计数字段是 **`message_num`（TS 声明 string）**，不是 `total_messages` ——
 *    实测 dev 返回 number，正是 tolerant scalar 的用武之地；显示走 `formatNumber`
 *    （`FavoriteCharacterCard.tsx:283`，= Home 的 formatMessageCount）
 * 3. 到底判定用 **`total_pages`**（页数不是条数！`useProfileFavorites.ts:63-66`
 *    `favoritesSize >= total_pages`），与创作/角色卡的「累计数 >= total」不同轨
 *
 * @property nsfw 封面模糊（`!nsfw偏好 && nsfw`，偏好恒 false → 18+ 一律模糊，
 *   `FavoriteCharacterCard.tsx:242`；注意这卡的 BlurView intensity 是 25 不是 40，
 *   壳复用同一 [CoverBlurTransformation] —— 强度差异属验收阶段视觉 diff）
 */
data class ProfileFavoriteItem(
    val characterId: String,
    val nickname: String?,
    val imageUrl: String?,
    val messageCount: Long,
    val nsfw: Boolean,
) : ProfileListEntry {

    /** 去重键 `character_id`（`uniqueByKey(..., 'character_id')`）。 */
    override val dedupeKey: String get() = characterId

    companion object {

        fun parse(json: JSONObject): ProfileFavoriteItem? {
            val id = ScalarCoercion.optString(json, FIELD_CHARACTER_ID)
                ?.takeIf { it.isNotBlank() } ?: return null
            return ProfileFavoriteItem(
                characterId = id,
                nickname = ScalarCoercion.optString(json, FIELD_NICKNAME)
                    ?.takeIf { it.isNotBlank() },
                imageUrl = ScalarCoercion.optString(json, FIELD_IMAGE_URL)
                    ?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
                // TS 声明 string、实测可 number —— ScalarCoercion 两头都接
                messageCount = ScalarCoercion.optLong(json, FIELD_MESSAGE_NUM) ?: 0L,
                nsfw = json.optBoolean(FIELD_NSFW, false),
            )
        }

        private const val FIELD_CHARACTER_ID = "character_id"
        private const val FIELD_NICKNAME = "nickname"
        private const val FIELD_IMAGE_URL = "image_url"
        private const val FIELD_MESSAGE_NUM = "message_num"
        private const val FIELD_NSFW = "nsfw"
    }
}

/**
 * 收藏/点赞列表的一页。
 *
 * ⚠️ [totalPages] 是**总页数**，到底判定 = `已拉页数 >= total_pages`
 * （不是条数比较）。`!total_pages` 直接到底 —— 两个 hook 同款
 * （`useProfileFavorites.ts:63` / `useProfileLiked.ts:62`）。
 */
data class ProfileFavoritePage(
    val items: List<ProfileFavoriteItem>,
    val totalPages: Long,
) {
    companion object {
        fun parse(data: JSONObject?): ProfileFavoritePage {
            if (data == null) return ProfileFavoritePage(emptyList(), 0L)
            val list = data.optJSONArray(FIELD_CHARACTERS)
            val items = buildList {
                for (i in 0 until (list?.length() ?: 0)) {
                    val obj = list?.optJSONObject(i) ?: continue
                    ProfileFavoriteItem.parse(obj)?.let(::add)
                }
            }
            return ProfileFavoritePage(
                items = items,
                totalPages = ScalarCoercion.optLong(data, FIELD_TOTAL_PAGES) ?: 0L,
            )
        }

        private const val FIELD_CHARACTERS = "characters"
        private const val FIELD_TOTAL_PAGES = "total_pages"
    }
}
