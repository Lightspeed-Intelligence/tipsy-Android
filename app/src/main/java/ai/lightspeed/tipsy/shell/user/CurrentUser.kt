package ai.lightspeed.tipsy.shell.user

import ai.lightspeed.tipsy.shell.network.ScalarCoercion
import org.json.JSONObject

/**
 * 当前登录用户的信息（`POST /user/info` 的 Native 消费字段 + RN 共享快照）。
 *
 * ## 为什么壳需要它
 *
 * **自己主页的昵称/头像/背景不来自任何 Profile 接口** —— 已核实
 * `useProfile.tsx:200-213`：`isSelf` 时这三个字段全取 `useUserStore` 的本地状态，
 * 只有他人主页才拉 `/user/get/public`。而 user store 由 `/user/info` 填充
 * （`store/user.ts:172-186`）。所以没有这一层，原生 Profile 只能显示空头像。
 *
 * Kotlin 属性仍只声明 Native 真正消费的字段；同时 [sharedStorageSnapshot] 保存
 * 与 RN `setUser` 等价的完整非敏感快照，供 Surface rehydrate。二者职责不同：
 * Native 页面不应为了方便去读取未建模的 JSON，Surface 也不应只拿一个 userId。
 *
 * ## Profile 页那个可复制的 UID 就是 [userId]
 *
 * 不要去找 `public_user_id` —— **该字段不存在**。RN 侧 `publicUserId` 只是
 * `useProfile` 返回值的重命名（`useProfile.tsx:100` `userId: currentUserId`，
 * 而 `currentUserId = userId || uid`）。自己主页时它就等于 user store 的 `userId`。
 *
 * @property userId 用户 id（`user_id`）。**也是 Profile 页展示的 UID**
 * @property nickname 昵称，可空 —— 新注册用户可能还没设
 * @property avatarUrl 头像 URL，可空 —— 空时 UI 走占位图
 * @property avatarDecorationCode 头像框配置 code，可空；图片 URL 由配置目录解析
 * @property backgroundImgUrl 主页背景图 URL，可空 —— 空时走内置默认图
 *   （`user-profile.tsx:418-423` fallback 到 `profile_bg.png`）
 * @property bio 个人简介（`bio`，`store/user.ts:195`），可空 —— 空时 UI 显示
 *   "No bio yet. Add one now." 空态文案。**带默认值**是为了既有构造点不受
 *   字段追加影响（测试 fixture 等），parse 永远显式传
 * @property relationshipSwitch 账号级关系功能开关（`relationship_switch`，
 *   `store/user.ts:191`）。ChatList 的 LV 徽章是双开关判定的账号侧
 *   （另一侧是角色的 `is_relationship_open`）。默认 false —— 拉不到时
 *   宁可不显示徽章，也不显示一个后端已关的功能
 */
data class CurrentUser(
    val userId: String,
    val nickname: String?,
    val avatarUrl: String?,
    val backgroundImgUrl: String?,
    val avatarDecorationCode: String? = null,
    val bio: String? = null,
    val relationshipSwitch: Boolean = false,
    val languageCode: String? = null,
    val sharedStorageSnapshot: UserStorageSnapshot? = null,
) {
    companion object {

        /**
         * 从 `/user/info` 的 `data` 解析。
         *
         * ⚠️ 字段全部走 [ScalarCoercion] —— 后端把数字 id 序列化成 number 时
         * `optString` 会拿到 `"1.78097772050099e+18"` 这种科学计数法字符串
         * （Home 侧踩过，见 `ScalarCoercion` 注释）。
         *
         * @return 解析结果；`user_id` 缺失时返回 null —— 没有 id 的用户对象是残缺响应，
         *   宁可当作"没拿到"，也不要写一个 id 为空的身份进内存
         *   （对齐 RN `store/user.ts:169` 的 `if (user.user_id)` 守卫）
         */
        fun parse(data: JSONObject?): CurrentUser? {
            if (data == null) return null
            val userId = ScalarCoercion.optString(data, FIELD_USER_ID)
                ?.takeIf { it.isNotBlank() } ?: return null
            return CurrentUser(
                userId = userId,
                nickname = ScalarCoercion.optString(data, FIELD_NICKNAME)?.takeIf { it.isNotBlank() },
                avatarUrl = ScalarCoercion.optString(data, FIELD_AVATAR_URL)?.takeIf { it.isNotBlank() },
                avatarDecorationCode = ScalarCoercion.optString(data, FIELD_AVATAR_DECORATION_CODE)
                    ?.takeIf { it.isNotBlank() },
                backgroundImgUrl = ScalarCoercion.optString(data, FIELD_BACKGROUND_IMG_URL)
                    ?.takeIf { it.isNotBlank() },
                bio = ScalarCoercion.optString(data, FIELD_BIO)?.takeIf { it.isNotBlank() },
                relationshipSwitch = ScalarCoercion.optBoolean(data, FIELD_RELATIONSHIP_SWITCH)
                    ?: false,
                languageCode = ScalarCoercion.optString(data, FIELD_LANGUAGE_CODE)
                    ?.takeIf { it.isNotBlank() },
                sharedStorageSnapshot = UserStorageSnapshot.fromApi(data, userId),
            )
        }

        private const val FIELD_USER_ID = "user_id"
        private const val FIELD_NICKNAME = "nickname"
        private const val FIELD_AVATAR_URL = "avatar_url"
        private const val FIELD_AVATAR_DECORATION_CODE = "avatar_decoration_code"
        private const val FIELD_BACKGROUND_IMG_URL = "background_img_url"
        private const val FIELD_BIO = "bio"
        private const val FIELD_RELATIONSHIP_SWITCH = "relationship_switch"
        private const val FIELD_LANGUAGE_CODE = "language_code"
    }
}
