package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.R
import androidx.annotation.DrawableRes

/**
 * 自己主页的五个内容 tab（`CharacterGrid.tsx:868` `tabList`）。
 *
 * ## ⚠️ 顺序即 `tabIndex`，不能改
 *
 * RN 侧全篇用**裸下标**做分支判断（`tabIndex === 0` 是创作、`=== 2` 是角色卡…，
 * 见 `CharacterGrid.tsx:569/643/669/682` 与 `1064-1072` 的空态分流）。
 * 调换顺序会让埋点里的 `active_tab_index`（`user-profile.tsx:137`）
 * 与 RN 侧对不上 —— 那是同一个漏斗里的字段，两端必须同轴。
 *
 * ## tab 栏只在自己主页显示
 *
 * `renderTabBar` 开头就是 `if (!isSelf) return null`（`CharacterGrid.tsx:1217`）。
 * 他人主页没有 tab 栏，只有单一列表 —— 接他人主页那刀不要复用这个枚举的全集。
 *
 * ## 每页大小按 tab 分配，不是全局常量
 *
 * 实测三个值并存：创作与角色卡 **10**，记忆/收藏/点赞 **20**
 * （`useCreatedList.ts:18`、`useRoleCard.ts:11`、`useProfileMemories.ts:17`）。
 * 统一成一个常量会让翻页边界与 RN 不一致，见 [ProfileApi] 类注释。
 *
 * @property pageSize 该 tab 的每页条数
 * @property icon 未选中图标
 * @property iconSelected 选中图标（RN 是成对 PNG，不是 tint 变色）
 */
enum class ProfileTab(
    val pageSize: Int,
    @DrawableRes val icon: Int,
    @DrawableRes val iconSelected: Int,
) {
    /** 创作列表 —— 已实现（[ProfileCreatedItem]）。 */
    CREATED(
        pageSize = 10,
        icon = R.drawable.ic_profile_tab_char,
        iconSelected = R.drawable.ic_profile_tab_char_press,
    ),

    /** 记忆（plot）—— 本刀实现（[ProfileMemoryItem]）。 */
    MEMORY(
        pageSize = 20,
        icon = R.drawable.ic_profile_tab_memory,
        iconSelected = R.drawable.ic_profile_tab_memory_press,
    ),

    /** 角色卡 —— 后续包。 */
    ROLE_CARD(
        pageSize = 10,
        icon = R.drawable.ic_profile_tab_rolecard,
        iconSelected = R.drawable.ic_profile_tab_rolecard_press,
    ),

    /** 收藏 —— 后续包。 */
    FAVORITES(
        pageSize = 20,
        icon = R.drawable.ic_profile_tab_favor,
        iconSelected = R.drawable.ic_profile_tab_favor_press,
    ),

    /** 点赞 —— 后续包。 */
    LIKED(
        pageSize = 20,
        icon = R.drawable.ic_profile_tab_like,
        iconSelected = R.drawable.ic_profile_tab_like_press,
    ),
    ;

    /** 本刀是否已接数据源；未接的 tab 走"敬请期待"占位而不是空转 loading。 */
    val isImplemented: Boolean get() = this == CREATED || this == MEMORY
}
