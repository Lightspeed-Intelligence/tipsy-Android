package ai.lightspeed.tipsy.shell.pages.profile

/**
 * 他人主页的页面状态。
 *
 * ## 为什么不复用 [ProfileState]
 *
 * [ProfileState] 的核心是 `paging: Map<ProfileTab, ProfileTabPaging>` —— 五个
 * tab 各自独立翻页。而他人主页**只有一个列表、且不翻页**（§2.32 第 1、5 条）：
 * 复用会得到一个恒只有一个键、`nextPage` 恒为 1 的 map，以及四个永远为空的
 * 派生属性。那种"复用"只是让读代码的人以为这里也有五个 tab。
 *
 * @property isLoading 首屏加载中（资料与列表任一在飞且还没有内容）
 * @property errorMessage 首屏错误文案；**只在 [items] 为空时展示**（方案 §8.4）
 * @property isFollowPending 关注请求在飞 —— 期间按钮禁用，防连点产生
 *   「关注→取关」的净零操作（toggle 端点连点两次等于没点，但计数会闪两下）
 */
data class PublicProfileState(
    /** 目标用户 id（进页面即已知，来自路由参数）。 */
    val userId: String = "",
    /** 公开资料；null = 还没拉到。 */
    val profile: PublicUserProfile? = null,
    /** 四个统计数字。⚠️ 字段与标签交叉，见 [ProfileStats]。 */
    val stats: ProfileStats = ProfileStats.EMPTY,
    /** 创作列表（单页 200，不翻页）。 */
    val items: List<ProfileCreatedItem> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val isFollowPending: Boolean = false,
) {
    /**
     * 关注按钮是否渲染。
     *
     * `!isDeleted`（`ProfileHeader.tsx:205`）—— 注销用户整块不渲染，
     * 而不是渲染一个禁用态的按钮。资料还没拉到时也不渲染（没有真值可显示）。
     */
    val showFollowButton: Boolean get() = profile != null && !profile.isDeleted

    /** 已关注（决定按钮文案 Following vs Follow）。 */
    val isFollowed: Boolean get() = profile?.isFollowed == true

    /**
     * 下拉刷新是否可用。
     *
     * ⚠️ 注销用户**整个禁用**下拉刷新（`CharacterGrid.tsx:1455`
     * `refreshControl={isDeleted ? undefined : ...}`）—— 不是刷新后无变化，
     * 是连刷新手势都没有。
     */
    val isRefreshEnabled: Boolean get() = profile?.isDeleted != true

    /** bio 只在非空时占位（他人主页走 `UserBio`，`CharacterGrid.tsx:1437`）。 */
    val bio: String? get() = profile?.bio?.takeIf { it.isNotBlank() }
}
