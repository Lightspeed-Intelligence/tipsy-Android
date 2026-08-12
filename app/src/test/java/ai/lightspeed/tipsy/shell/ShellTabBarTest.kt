package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.home.HomeGender
import ai.lightspeed.tipsy.shell.pages.home.HomeSeries
import ai.lightspeed.tipsy.shell.tabs.ShellTab
import ai.lightspeed.tipsy.shell.tabs.TAB_BAR_CONTENT_HEIGHT
import ai.lightspeed.tipsy.shell.tabs.androidTabBarBottomInsetDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 五 Tab 与 Home 枚举的对齐断言（W2）。
 *
 * 这些值错了都不报错，只是「与现网 Android 不一样」——
 * 而没人会同时装两个版本并排看。
 */
class ShellTabBarTest {

    // ── tabbar 底部 inset ─────────────────────────────────

    @Test
    fun `无手势条设备用固定 24 而不是 0`() {
        // 直接用 safeBottom 会让图标贴到屏幕最下沿
        assertEquals(24f, androidTabBarBottomInsetDp(safeBottomDp = 0f, scaleFactor = 1f), 0.01f)
    }

    @Test
    fun `有手势条设备额外加 16`() {
        // ⚠️ **不是** max(safeBottom, 24)：safeBottom=30 时 max 给 30，正确值是 46
        assertEquals(46f, androidTabBarBottomInsetDp(safeBottomDp = 30f, scaleFactor = 1f), 0.01f)
        assertNotEquals(30f, androidTabBarBottomInsetDp(30f, 1f))
    }

    @Test
    fun `边界值等于 24 时走固定分支`() {
        // RN 的条件是严格大于（`safeBottom > ANDROID_TAB_BAR_MIN_BOTTOM_INSET`）
        assertEquals(24f, androidTabBarBottomInsetDp(safeBottomDp = 24f, scaleFactor = 1f), 0.01f)
        // 刚超过就切到 +16 分支
        assertEquals(40.5f, androidTabBarBottomInsetDp(safeBottomDp = 24.5f, scaleFactor = 1f), 0.01f)
    }

    @Test
    fun `safeBottom 不参与缩放 —— 只有两个常量缩放`() {
        // RN 比较的是「实际 dp inset」与「s(24) 缩放值」。把 safeBottom 一起
        // 乘 scaleFactor 会在大屏上凭空多出十几 dp 留白
        val scale = 1.2f
        // 24*1.2 = 28.8 > 30？不 —— 30 > 28.8，所以走 +16*1.2 分支
        assertEquals(30f + 16f * scale, androidTabBarBottomInsetDp(30f, scale), 0.01f)
        // safeBottom=20 < 28.8 → 固定分支给 28.8（而不是 20 或 24）
        assertEquals(24f * scale, androidTabBarBottomInsetDp(20f, scale), 0.01f)
    }

    @Test
    fun `内容高度是 8 加 40`() {
        assertEquals(48, TAB_BAR_CONTENT_HEIGHT)
    }

    // ── Tab 定义 ──────────────────────────────────────────

    @Test
    fun `五个 Tab 顺序对齐 RN`() {
        assertEquals(
            listOf("Screen", "Home", "Create", "ChatList", "Profile"),
            ShellTab.displayOrder.map { it.routeName },
        )
    }

    @Test
    fun `初始 Tab 是 Home 而不是第一个`() {
        // `initialRouteName="Home"`（TabNavigator.tsx:304）—— Screen 才是第一个
        assertEquals(ShellTab.HOME, ShellTab.default)
        assertNotEquals(ShellTab.displayOrder.first(), ShellTab.default)
    }

    @Test
    fun `Create 是伪 Tab 且无选中态图标`() {
        // 点它不切 Tab 而是拉起创建流程；做成普通 Tab 会让底部高亮跳到中间
        assertFalse(ShellTab.CREATE.isRealTab)
        assertNull(ShellTab.CREATE.iconSelected)
        ShellTab.displayOrder.filter { it != ShellTab.CREATE }.forEach {
            assertTrue("${it.routeName} 应是真 Tab", it.isRealTab)
        }
    }

    @Test
    fun `前两个 Tab 的图标与名字交叉 —— 不是按名字挑的`() {
        // Screen 用 tab_home、Home 用 tab_explore（TabNavigator.tsx:386-406）。
        // 照名字挑会让前两个图标对调
        assertEquals(R.drawable.ic_tab_home, ShellTab.SCREEN.icon)
        assertEquals(R.drawable.ic_tab_explore, ShellTab.HOME.icon)
    }

    // ── Home 系列枚举 ─────────────────────────────────────

    @Test
    fun `六个系列 —— Android 显示 World，隐藏 Multi-character`() {
        // home.tsx:505-511 的 filter：Multi-character 两端都隐藏，
        // World 只在 Android 显示（iOS 壳的 HomeAPI 只有 5 个 case）
        assertEquals(
            listOf("For You", "Weekly Picks", "World", "New Releases", "All-Time Faves", "Following"),
            HomeSeries.displayOrder.map { it.key },
        )
        assertTrue(HomeSeries.displayOrder.none { it.key == "Multi-character" })
    }

    @Test
    fun `sorting 与系列名不是同一个值`() {
        // 凭一个推另一个必错
        assertEquals("Popular", HomeSeries.ALL_TIME_FAVES.sorting)
        assertEquals("WeeklyPicks", HomeSeries.WEEKLY_PICKS.sorting)
        assertEquals("New", HomeSeries.NEW_RELEASES.sorting)
        assertEquals("FollowersCharacterNew", HomeSeries.FOLLOWING.sorting)
        // For You 与 World 不走 public_list
        assertNull(HomeSeries.FOR_YOU.sorting)
        assertNull(HomeSeries.WORLD.sorting)
    }

    @Test
    fun `tabType 与 sorting 也不是同一个值`() {
        assertEquals("trending", HomeSeries.WEEKLY_PICKS.tabType)
        assertEquals("popular", HomeSeries.ALL_TIME_FAVES.tabType)
        assertEquals("latest", HomeSeries.NEW_RELEASES.tabType)
        assertEquals("for_you", HomeSeries.FOR_YOU.tabType)
        assertEquals("worlds", HomeSeries.WORLD.tabType)
    }

    @Test
    fun `Following 与 World 没有标签筛选`() {
        assertFalse(HomeSeries.FOLLOWING.supportsTagFilter)
        assertFalse(HomeSeries.WORLD.supportsTagFilter)
        assertTrue(HomeSeries.FOR_YOU.supportsTagFilter)
        assertTrue(HomeSeries.WEEKLY_PICKS.supportsTagFilter)
    }

    // ── 性别枚举 ──────────────────────────────────────────

    @Test
    fun `NonBinary 的 i18n key 带连字符 —— 与存储值不同`() {
        // ⚠️ 状态值是 `NonBinary`，但 26 个 locale 的 key 是 `Non-binary`。
        // 用 NonBinary 查表会 miss 并回落 key 本身 → 所有语言显示 "NonBinary"
        assertEquals("NonBinary", HomeGender.NON_BINARY.storedValue)
        assertEquals("Non-binary", HomeGender.NON_BINARY.i18nKey)
        assertNotEquals(HomeGender.NON_BINARY.storedValue, HomeGender.NON_BINARY.i18nKey)
    }

    @Test
    fun `gender 的接口值映射`() {
        // NonBinary → "other"，不是 "nonbinary"
        assertNull(HomeGender.ALL.apiValue)
        assertEquals("male", HomeGender.MALE.apiValue)
        assertEquals("female", HomeGender.FEMALE.apiValue)
        assertEquals("other", HomeGender.NON_BINARY.apiValue)
    }

    @Test
    fun `空串与未知值都回落 All`() {
        // RN 的初始值是空串（config_persist.ts:227），不是 'All'
        assertEquals(HomeGender.ALL, HomeGender.fromStored(""))
        assertEquals(HomeGender.ALL, HomeGender.fromStored(null))
        assertEquals(HomeGender.ALL, HomeGender.fromStored("Nonsense"))
        // ⚠️ 用 i18nKey 反查应当失败（它不是存储值）—— 钉住两者不可混用
        assertEquals(HomeGender.ALL, HomeGender.fromStored("Non-binary"))
        assertEquals(HomeGender.NON_BINARY, HomeGender.fromStored("NonBinary"))
    }
}
