package ai.lightspeed.tipsy.shell.tabs

import ai.lightspeed.tipsy.shell.ui.ScaledMetrics
import ai.lightspeed.tipsy.shell.ui.s
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 底部 Tab 栏（**对齐 RN Android 现网**，`TabNavigator.tsx:203-272` 的 `AndroidTabBar`）。
 *
 * ## ⚠️ Android 与 iOS 是两套不同的 tabbar，不要照 iOS 那套做
 *
 * RN 侧按平台分叉（`TabNavigator.tsx:306-323`）：
 *
 * | | iOS（`AnimatedCapsuleTabBar`） | **Android（本实现）** |
 * | --- | --- | --- |
 * | 形态 | 悬浮胶囊，左右外边距 s(12) | **通栏实心，贴底** |
 * | 圆角 | s(28) | **无** |
 * | 背景 | 白 5% + BlurView(55, dark) + 顶部内高光 | **纯色 `#341F1D`** |
 * | 选中态 | s(64)×s(40) 胶囊 200ms 滑动 | **无**（只换图标） |
 *
 * 照 iOS 那套实现会与现网 Android 用户看到的界面明显不同（本包 owner 已明确
 * 选择对齐现网）。iOS 壳的 `FloatingTabBarView.swift` 是那一套的参考实现，
 * **本文件刻意不参考它**。
 *
 * ## 高度构成（逐项对齐，别自己凑）
 *
 * ```
 * height = s(8) 顶部内距 + s(40) 图标 + bottomInset
 * bottomInset = safeBottom > s(24) ? safeBottom + s(16) : s(24)
 * ```
 *
 * 那个条件（`getAndroidTabBarBottomInset`，`:66-72`）不是"取 max"：
 * 有手势条的设备额外 +s(16)，没有的设备**固定 s(24)** 而不是 0 —— 直接用
 * safeBottom 会让全面屏以外的设备图标贴到屏幕最下沿。
 */
@Composable
fun ShellTabBar(
    selected: ShellTab,
    onTabClick: (ShellTab) -> Unit,
    /** 系统导航栏 inset（dp）。由宿主读 WindowInsets 传入，便于纯函数化布局计算。 */
    safeBottomDp: Float,
    modifier: Modifier = Modifier,
) {
    // ⚠️ bottomInset 已是**实际 dp**（内部只缩放两个设计稿常量），
    // 这里不能再写 `.s` —— 那会把系统 inset 也乘一遍
    val bottomInset = androidTabBarBottomInsetDp(safeBottomDp, ScaledMetrics.scaleFactor()).dp
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 背景在最外层：它要延伸到导航栏之下（`androidTabBarSurface` 是
            // StyleSheet.absoluteFill 铺满整个栏，含 paddingBottom 区域）
            .background(SURFACE_COLOR)
            .padding(top = TOP_PADDING.s, bottom = bottomInset),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(ICON_SIZE.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShellTab.displayOrder.forEach { tab ->
                TabButton(
                    tab = tab,
                    isSelected = tab == selected,
                    onClick = { onTabClick(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TabButton(
    tab: ShellTab,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(ICON_SIZE.s)
            // ⚠️ 整格可点，不是只有图标可点：图标 s(40) 在 1/5 屏宽里只占一半，
            // 只让图标响应会让 Tab 边缘点不动（`androidTabBarItem` 是 flex:1）
            .selectable(
                selected = isSelected,
                role = Role.Tab,
                onClick = onClick,
            )
            // 语义收在可点区域这一层：图标自己不带描述（下面传 null），
            // 否则 TalkBack 会把一个 Tab 读成两个节点。
            // 名字用 routeName（英文稳定值）—— RN 侧 Tab 也没有本地化标题
            // （`tabBarShowLabel: false`，`options.title` 就是英文原文）
            .semantics { contentDescription = tab.routeName },
        contentAlignment = Alignment.Center,
    ) {
        val icon = if (isSelected) tab.iconSelected ?: tab.icon else tab.icon
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(ICON_SIZE.s),
        )
    }
}

/**
 * 底部 inset（`getAndroidTabBarBottomInset`，`TabNavigator.tsx:66-72`）。
 *
 * 抽成纯函数是为了可单测 —— 两处都容易写错：
 *
 * 1. **别写成 `max(safeBottom, 24)`**：safeBottom=30 时 max 给 30，
 *    而正确值是 46（30 + 16）。差 16dp 不报错，只是手势条压住图标。
 * 2. **safeBottom 不参与缩放**：RN 里比较的是「实际 dp inset」与「`s(24)` 缩放值」
 *    （`safeBottom > ANDROID_TAB_BAR_MIN_BOTTOM_INSET`，右侧是 `s(24)`）。
 *    把 safeBottom 一起乘 scaleFactor 会在大屏上凭空多出十几 dp 留白。
 *    所以这里返回的是**实际 dp**，调用方不要再乘。
 *
 * @param safeBottomDp 系统导航栏 inset，实际 dp
 * @param scaleFactor `ScaledMetrics.scaleFactor()` 的值，只作用于两个设计稿常量
 */
internal fun androidTabBarBottomInsetDp(safeBottomDp: Float, scaleFactor: Float): Float {
    val minInset = MIN_BOTTOM_INSET * scaleFactor
    return if (safeBottomDp > minInset) {
        safeBottomDp + EXTRA_SAFE_BOTTOM_INSET * scaleFactor
    } else {
        minInset
    }
}

/** `androidTabBarSurface` 的 `backgroundColor: '#341F1D'`（`:494`）。 */
private val SURFACE_COLOR = Color(0xFF341F1D)

/** `ANDROID_TAB_BAR_ICON_SIZE = s(40)`（`:55`）。 */
private const val ICON_SIZE = 40

/** `ANDROID_TAB_BAR_TOP_PADDING = s(8)`（`:56`）。 */
private const val TOP_PADDING = 8

/**
 * 内容高度（不含 bottomInset）。宿主用它算列表底部留白。
 *
 * ⚠️ 必须声明在 [TOP_PADDING] / [ICON_SIZE] **之后** —— Kotlin 顶层属性按
 * 声明顺序初始化，放前面会拿到 0（编译器在同文件内会直接报错，但跨文件时
 * 是静默的 0）。
 */
internal const val TAB_BAR_CONTENT_HEIGHT = TOP_PADDING + ICON_SIZE

/** `ANDROID_TAB_BAR_MIN_BOTTOM_INSET = s(24)`（`:59`）。 */
private const val MIN_BOTTOM_INSET = 24

/** `ANDROID_TAB_BAR_EXTRA_SAFE_BOTTOM_INSET = s(16)`（`:60`）。 */
private const val EXTRA_SAFE_BOTTOM_INSET = 16

/** 供宿主计算列表底部留白：`insets.bottom + 50`（`home.tsx:257`）。 */
internal const val HOME_LIST_BOTTOM_EXTRA = 50
