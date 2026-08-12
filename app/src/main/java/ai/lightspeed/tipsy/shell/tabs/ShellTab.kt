package ai.lightspeed.tipsy.shell.tabs

import ai.lightspeed.tipsy.shell.R
import androidx.annotation.DrawableRes

/**
 * 五个 Tab（对齐 RN `TabNavigator.tsx` 的 `Tab.Screen` 声明顺序）。
 *
 * ## ⚠️ Create 是**伪 Tab**
 *
 * 它的 `component` 是一个空 `View`，`tabPress` 里 `e.preventDefault()` 后
 * 直接 `navigation.navigate('CreateTabStack', ...)`（`TabNavigator.tsx:421-436`）。
 * 也就是说点它**不切 Tab**，而是拉起创建流程。
 *
 * 把它做成普通 Tab 的后果：点了以后底部高亮跳到中间、返回时要多按一次，
 * 且创建流程结束后停在一个空白 Tab 上。iOS 壳同样按"点击拦截"实现
 * （`MainTabBarController.swift` 的 `presentCreateFlow`）。
 *
 * ## 图标顺序与命名的一处反直觉
 *
 * 第一个 Tab 叫 `Screen`，用的图标是 **`tab_home`**；第二个叫 `Home`，
 * 用的是 **`tab_explore`**（`TabNavigator.tsx:386-406`）。名字与图标交叉，
 * 照名字挑图标会让前两个 Tab 的图标对调。
 */
enum class ShellTab(
    /** 埋点与日志用的稳定名（对齐 RN 的 route name）。 */
    val routeName: String,
    @DrawableRes val icon: Int,
    /** 选中态图标；Create 无选中态（它不会被选中）。 */
    @DrawableRes val iconSelected: Int?,
) {
    /** Tab1 全屏分发流（方案 §8.1 Screen，W4 迁移）。图标是 tab_home，见类注释。 */
    SCREEN("Screen", R.drawable.ic_tab_home, R.drawable.ic_tab_home_press),

    /** Tab2 首页/发现（本包实现）。图标是 tab_explore，见类注释。 */
    HOME("Home", R.drawable.ic_tab_explore, R.drawable.ic_tab_explore_press),

    /** Tab3 创建 —— **伪 Tab**，点击拉起 `CreateSurface`（W2 后续包）。 */
    CREATE("Create", R.drawable.ic_tab_create, null),

    /** Tab4 会话列表（方案 §8.1 ChatList，W3 迁移）。 */
    CHAT_LIST("ChatList", R.drawable.ic_tab_chat, R.drawable.ic_tab_chat_press),

    /** Tab5 个人主页（方案 §8.1 Profile，W3 迁移）。 */
    PROFILE("Profile", R.drawable.ic_tab_profile, R.drawable.ic_tab_profile_press),
    ;

    /** 是否是真正会切换内容的 Tab。 */
    val isRealTab: Boolean get() = this != CREATE

    companion object {
        /** 声明顺序即显示顺序。 */
        val displayOrder: List<ShellTab> = entries.toList()

        /** 初始 Tab（`TabNavigator.tsx:304` `initialRouteName="Home"`）。 */
        val default: ShellTab = HOME
    }
}
