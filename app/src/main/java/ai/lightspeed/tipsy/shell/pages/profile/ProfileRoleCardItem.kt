package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.network.ScalarCoercion
import org.json.JSONObject

/**
 * 角色卡 tab 的一条（`/user/profile_card/list` 的 `list` 元素，
 * `types/character.ts:387` `ProfileCard`）。
 *
 * @property rolePicUrl 已解析的头像地址。解析顺序照 `RoleCard.tsx:31-44`
 *   `resolveRoleCardImageUri`：`role_pic_url` → `role_pic`（已是 http 直用，
 *   否则拼 CDN 前缀 `https://img.tipsy.chat/`）→ null（UI 走内置占位）。
 *   ⚠️ 这个 CDN 前缀是 RN 侧**组件里的硬编码**（两处重复定义），不是后端可换
 *   配置 —— 照抄；它与创作卡「不许拼域名」那条注释不冲突（那边的顶层相对路径
 *   没有约定前缀，这边有）。
 * @property makeDefault 默认卡 —— 列表排序**默认卡置顶**（`sortRoleCardsWithDefaultFirst`），
 *   排序在 [ProfileState.roleCardItems] 派生层做（对齐 RN 的 useMemo 时机）
 * @property label 自定义标签，与性别/年龄拼 meta 行（`gender | age | label`）
 */
data class ProfileRoleCardItem(
    val profileCardId: String,
    val nickname: String?,
    val gender: String?,
    val age: Int?,
    val label: String?,
    val makeDefault: Boolean,
    val rolePicUrl: String?,
) : ProfileListEntry {

    /** 去重键 `profile_card_id`（`useRoleCard.ts:41` `uniqueByKey(..., 'profile_card_id')`）。 */
    override val dedupeKey: String get() = profileCardId

    /**
     * meta 行的性别 i18n key（`RoleCard.tsx:68-73`：male/female 之外全归 Other）。
     * gender 缺失时 RN 的 `[gender ? genderText : '']` 会整段省略 —— 返回 null。
     */
    val genderKey: String?
        get() = when (gender) {
            null, "" -> null
            "male" -> "Male"
            "female" -> "Female"
            else -> "Other"
        }

    companion object {

        fun parse(json: JSONObject): ProfileRoleCardItem? {
            val id = ScalarCoercion.optString(json, FIELD_PROFILE_CARD_ID)
                ?.takeIf { it.isNotBlank() } ?: return null
            return ProfileRoleCardItem(
                profileCardId = id,
                nickname = ScalarCoercion.optString(json, FIELD_NICKNAME)
                    ?.takeIf { it.isNotBlank() },
                gender = ScalarCoercion.optString(json, FIELD_GENDER)?.takeIf { it.isNotBlank() },
                age = ScalarCoercion.optInt(json, FIELD_AGE),
                label = ScalarCoercion.optString(json, FIELD_LABEL)?.takeIf { it.isNotBlank() },
                makeDefault = json.optBoolean(FIELD_MAKE_DEFAULT, false),
                rolePicUrl = resolvePicUrl(json),
            )
        }

        /** `resolveRoleCardImageUri` 的三段解析，见类注释。 */
        private fun resolvePicUrl(json: JSONObject): String? {
            ScalarCoercion.optString(json, FIELD_ROLE_PIC_URL)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
            val rolePic = ScalarCoercion.optString(json, FIELD_ROLE_PIC)
                ?.takeIf { it.isNotBlank() } ?: return null
            return if (rolePic.startsWith("http://") || rolePic.startsWith("https://")) {
                rolePic
            } else {
                CDN_PREFIX + rolePic
            }
        }

        /** `RoleCard.tsx:29`（组件内硬编码，见类注释）。 */
        private const val CDN_PREFIX = "https://img.tipsy.chat/"

        private const val FIELD_PROFILE_CARD_ID = "profile_card_id"
        private const val FIELD_NICKNAME = "nickname"
        private const val FIELD_GENDER = "gender"
        private const val FIELD_AGE = "age"
        private const val FIELD_LABEL = "label"
        private const val FIELD_MAKE_DEFAULT = "make_default"
        private const val FIELD_ROLE_PIC_URL = "role_pic_url"
        private const val FIELD_ROLE_PIC = "role_pic"
    }
}

/**
 * 角色卡列表的一页（`{total, list}`）。到底判定与创作 tab 同款：
 * 累计数 >= total（`useRoleCard.ts:58-61`），`!total` 直接到底。
 */
data class ProfileRoleCardPage(
    val items: List<ProfileRoleCardItem>,
    val total: Long,
) {
    companion object {
        fun parse(data: JSONObject?): ProfileRoleCardPage {
            if (data == null) return ProfileRoleCardPage(emptyList(), 0L)
            val list = data.optJSONArray(FIELD_LIST)
            val items = buildList {
                for (i in 0 until (list?.length() ?: 0)) {
                    val obj = list?.optJSONObject(i) ?: continue
                    ProfileRoleCardItem.parse(obj)?.let(::add)
                }
            }
            return ProfileRoleCardPage(
                items = items,
                total = ScalarCoercion.optLong(data, FIELD_TOTAL) ?: 0L,
            )
        }

        private const val FIELD_LIST = "list"
        private const val FIELD_TOTAL = "total"
    }
}
