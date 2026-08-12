package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.network.ScalarCoercion
import org.json.JSONObject

/**
 * Profile 头部的四个统计数字（`POST /user/stats_info`）。
 *
 * ## ⚠️⚠️ 字段与标签是**交叉的** —— 这是本文件存在的唯一理由
 *
 * 已在 `FollowInfo.tsx:52,66` 逐行核实：
 *
 * | 显示标签 | 接口字段 |
 * | --- | --- |
 * | **Followers** | `followees_count` |
 * | **Following** | `followers_count` |
 * | Likes | `characters_received_likes` |
 * | Interactions | `total_interactions` |
 *
 * 前两行是**反的**。照字段名直译（`followers_count` → "Followers"）会把两个数字
 * 标反，而且**本地几乎测不出来**：只有当账号的关注数与粉丝数不相等时才看得见差异，
 * 测试账号常常两个都是 0 或恰好相等。
 *
 * 所以这里用 [followersLabelCount] / [followingLabelCount] 命名 ——
 * 名字直接说"这是给哪个标签用的"，而不是复述接口字段名。谁想"顺手改成一致"，
 * 先看这段注释和 `ProfileStatsTest`。
 *
 * （没有考证后端为什么这样命名。可能是历史语义翻转，但**不能**在客户端"修正" ——
 * 现网 RN 就是这个映射，改了就是壳与现网不一致。）
 */
data class ProfileStats(
    /** 显示在 **"Followers"** 标签下的数字（接口字段 `followees_count`）。 */
    val followersLabelCount: Long,
    /** 显示在 **"Following"** 标签下的数字（接口字段 `followers_count`）。 */
    val followingLabelCount: Long,
    /** "Likes"（`characters_received_likes`）。 */
    val likesCount: Long,
    /** "Interactions"（`total_interactions`）。 */
    val interactionsCount: Long,
) {
    companion object {

        /** 全零 —— 拉取失败时的占位，UI 仍显示四个 0（对齐 RN 的 `|| 0`）。 */
        val EMPTY = ProfileStats(0, 0, 0, 0)

        /**
         * 解析；`data` 为 null 时返回 [EMPTY]。
         *
         * 缺字段按 0 处理 —— 对齐 RN 侧 `followerInfo?.followees_count || 0`
         * （`FollowInfo.tsx:52`）。不抛异常：统计数字缺失不该让整页失败。
         */
        fun parse(data: JSONObject?): ProfileStats {
            if (data == null) return EMPTY
            return ProfileStats(
                // ⚠️ 交叉映射，见类注释。不要"修正"成同名字段
                followersLabelCount = ScalarCoercion.optLong(data, FIELD_FOLLOWEES_COUNT) ?: 0L,
                followingLabelCount = ScalarCoercion.optLong(data, FIELD_FOLLOWERS_COUNT) ?: 0L,
                likesCount = ScalarCoercion.optLong(data, FIELD_CHARACTERS_RECEIVED_LIKES) ?: 0L,
                interactionsCount = ScalarCoercion.optLong(data, FIELD_TOTAL_INTERACTIONS) ?: 0L,
            )
        }

        private const val FIELD_FOLLOWEES_COUNT = "followees_count"
        private const val FIELD_FOLLOWERS_COUNT = "followers_count"
        private const val FIELD_CHARACTERS_RECEIVED_LIKES = "characters_received_likes"
        private const val FIELD_TOTAL_INTERACTIONS = "total_interactions"
    }
}
