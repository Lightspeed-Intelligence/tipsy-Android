package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.network.ScalarCoercion
import org.json.JSONObject

/**
 * 他人的公开资料（`/user/get/public` 的响应，`UserPublicInfoRes`
 * = `types/user.ts:78-92`）。
 *
 * ## 为什么不复用 [ai.lightspeed.tipsy.shell.user.CurrentUser]
 *
 * 两者字段**只有一半重叠**，且各有对方没有的关键字段：
 * - 本类独有：[isFollowed]（关注按钮的真值）、[isDeleted]（注销用户走特殊态）
 * - `CurrentUser` 独有：`relationshipSwitch`（ChatList 徽章用）
 *
 * 更要紧的是**语义边界**：`CurrentUser` 是「当前登录者的身份」，由
 * `CurrentUserStore` 单例持有、登出要清；本类是「正在浏览的某个别人」，
 * 是页面级数据。合成一个类会让「登出清空」这条纪律作用到不该清的东西上。
 *
 * @property isFollowed 关注按钮的初始态（`is_followed`）。⚠️ **不要本地翻转它** ——
 *   关注成功后要重拉本接口，见 [PublicProfileApi.toggleFollow]
 * @property isDeleted 已注销用户（`is_deleted`）。RN 侧对它有三处特殊处理：
 *   关注按钮不渲染（`ProfileHeader.tsx:205`）、下拉刷新整个禁用
 *   （`CharacterGrid.tsx:1455`）、UID 不展示（`user-profile.tsx:162`）
 * @property bio 个人简介。他人主页**只在非空时**渲染，且走另一个组件
 *   （`UserBio` 而非自己视角的 `RenderBio`，`CharacterGrid.tsx:1437-1443`）
 */
data class PublicUserProfile(
    val userId: String,
    val nickname: String?,
    val avatarUrl: String?,
    val backgroundImgUrl: String?,
    val bio: String?,
    val isFollowed: Boolean,
    val isDeleted: Boolean,
) {
    companion object {

        /**
         * 解析；`user_id` 缺失返回 null（同 `CurrentUser.parse` 的理由：
         * 没有 id 的身份对象是残缺响应，宁可当「没拿到」）。
         *
         * 字段全部走 [ScalarCoercion] —— 后端把 id 序列化成 number 时
         * `optString` 会拿到科学计数法字符串（Home 侧踩过）。
         */
        fun parse(data: JSONObject?): PublicUserProfile? {
            if (data == null) return null
            val userId = ScalarCoercion.optString(data, FIELD_USER_ID)
                ?.takeIf { it.isNotBlank() } ?: return null
            return PublicUserProfile(
                userId = userId,
                nickname = ScalarCoercion.optString(data, FIELD_NICKNAME)
                    ?.takeIf { it.isNotBlank() },
                avatarUrl = ScalarCoercion.optString(data, FIELD_AVATAR_URL)
                    ?.takeIf { it.isNotBlank() },
                backgroundImgUrl = ScalarCoercion.optString(data, FIELD_BACKGROUND_IMG_URL)
                    ?.takeIf { it.isNotBlank() },
                bio = ScalarCoercion.optString(data, FIELD_BIO)?.takeIf { it.isNotBlank() },
                // 缺失按 false：把「已关注」错显成「未关注」用户点一下就能纠正，
                // 反过来（错显已关注）会让用户以为关注过了而不再点
                isFollowed = ScalarCoercion.optBoolean(data, FIELD_IS_FOLLOWED) ?: false,
                // 缺失按 false：把正常用户错当注销会隐藏关注按钮与刷新，
                // 属功能缺失；反之只是多显示一个按钮
                isDeleted = ScalarCoercion.optBoolean(data, FIELD_IS_DELETED) ?: false,
            )
        }

        private const val FIELD_USER_ID = "user_id"
        private const val FIELD_NICKNAME = "nickname"
        private const val FIELD_AVATAR_URL = "avatar_url"
        private const val FIELD_BACKGROUND_IMG_URL = "background_img_url"
        private const val FIELD_BIO = "bio"
        private const val FIELD_IS_FOLLOWED = "is_followed"
        private const val FIELD_IS_DELETED = "is_deleted"
    }
}
