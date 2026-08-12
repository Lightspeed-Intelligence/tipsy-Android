package ai.lightspeed.tipsy.shell.pages.home

import ai.lightspeed.tipsy.shell.R
import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
import ai.lightspeed.tipsy.shell.ui.s
import ai.lightspeed.tipsy.shell.ui.sSp
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * Home 顶栏（对齐 RN `components/home/HomeHeader.tsx`）。
 *
 * 三个元素：订阅入口 / 搜索框 / 性别筛选（含下拉面板）。
 *
 * ## 本包的边界
 *
 * 订阅入口与搜索框**只做 UI 与埋点，点击落 Router 的显式拒绝** ——
 * 它们的目标页分属 `GemsSubscriptionSurface`（W4）与 Search（W3）。
 * 方案 §8.3 的纪律是「路由到未启用目标必须给明确错误或安全兜底，
 * **不做 silent no-op**」，所以点了会在日志里留下拒绝记录，不是没反应。
 */
@Composable
internal fun HomeHeader(
    gender: HomeGender,
    onGenderSelected: (HomeGender) -> Unit,
    onSearchClick: () -> Unit,
    onSubscriptionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var panelOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(HomeStyle.HEADER_HEIGHT.s)
            .padding(
                // ⚠️ 左右内距不对称（12 / 5），照抄 RN
                start = HomeStyle.HEADER_PADDING_LEFT.s,
                end = HomeStyle.HEADER_PADDING_RIGHT.s,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HomeStyle.HEADER_GAP.s),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_home_subscription),
            contentDescription = rememberLocalizedString("Gems"),
            modifier = Modifier
                .size(HomeStyle.HEADER_ICON.s)
                .clickable(onClick = onSubscriptionClick),
        )

        SearchBar(
            onClick = onSearchClick,
            modifier = Modifier.weight(1f),
        )

        Box {
            GenderButton(
                gender = gender,
                isOpen = panelOpen,
                onClick = { panelOpen = true },
            )
            if (panelOpen) {
                GenderPanel(
                    selected = gender,
                    onSelect = {
                        onGenderSelected(it)
                        panelOpen = false
                    },
                    onDismiss = { panelOpen = false },
                )
            }
        }
    }
}

@Composable
private fun SearchBar(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(HomeStyle.SEARCH_HEIGHT.s)
            .clip(RoundedCornerShape(HomeStyle.SEARCH_RADIUS.s))
            .background(HomeStyle.SEARCH_FILL)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_home_search),
            contentDescription = null,
            modifier = Modifier.size(HomeStyle.HEADER_ICON.s),
        )
        Text(
            text = rememberLocalizedString("Search"),
            color = HomeStyle.SEARCH_PLACEHOLDER,
            fontSize = HomeStyle.SEARCH_TEXT_SIZE.sSp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun GenderButton(gender: HomeGender, isOpen: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            // ⚠️ 用 i18nKey 而非 storedValue —— NonBinary 的词条 key 是
            // `Non-binary`（带连字符），见 HomeGender 类注释
            text = rememberLocalizedString(gender.i18nKey),
            color = HomeStyle.HEADER_TEXT,
            fontSize = HomeStyle.HEADER_TEXT_SIZE.sSp,
            maxLines = 1,
        )
        Image(
            painter = painterResource(
                if (isOpen) R.drawable.ic_home_arrow_up else R.drawable.ic_home_arrow_down,
            ),
            contentDescription = null,
            modifier = Modifier.size(HomeStyle.HEADER_ICON.s),
        )
    }
}

/**
 * 性别下拉面板（对齐 `renderPopoverContent`，`HomeHeader.tsx:74-123`）。
 *
 * ## 为什么用 `Popup` 而不是 `DropdownMenu`
 *
 * Material3 的 `DropdownMenu` 自带背景、圆角、elevation 与进出动画，
 * 要全部覆盖成 RN 的样式（三段对角渐变 + 0.5dp 描边 + 无阴影）反而更绕，
 * 且它的 padding 改不干净。`Popup` 是无样式容器，正合这里。
 *
 * ⚠️ **不做模糊**：RN 的 Android 分支用 `BlurView intensity={40}`，但
 * 底色 alpha 是 **0.97**（见 `HomeStyle.PANEL_GRADIENT_ANDROID`）——
 * 0.97 之下模糊几乎不可见。Compose 侧要模糊得用 `RenderEffect`（API 31+）
 * 并给低版本降级，为一个看不出来的效果引入版本分叉不值得。
 */
@Composable
private fun GenderPanel(
    selected: HomeGender,
    onSelect: (HomeGender) -> Unit,
    onDismiss: () -> Unit,
) {
    Popup(
        alignment = Alignment.TopEnd,
        // 锚定按钮下方（RN 的 Popover from=filterButtonRef，无箭头）
        offset = IntOffset(0, 0),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .padding(top = 6.s)
                // minWidth 180 / maxWidth 350（`HomeHeader.tsx:244-245`）
                .widthIn(min = 180.s, max = 350.s)
                .clip(RoundedCornerShape(8.s))
                .background(Brush.linearGradient(HomeStyle.PANEL_GRADIENT_ANDROID))
                .border0(),
        ) {
            HomeGender.displayOrder.forEachIndexed { index, option ->
                GenderPanelRow(
                    option = option,
                    isSelected = option == selected,
                    isLast = index == HomeGender.displayOrder.lastIndex,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun GenderPanelRow(
    option: HomeGender,
    isSelected: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.s)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.s),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = rememberLocalizedString(option.i18nKey),
                // 选中纯白、未选 50% 白（`:266-271`）
                color = if (isSelected) HomeStyle.SERIES_TEXT_SELECTED else HomeStyle.SERIES_TEXT,
                fontSize = 15.sSp,
                maxLines = 1,
            )
        }
        // 最后一行无分隔线（`selectItemBorderNone`）
        if (!isLast) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.s)
                    .background(HomeStyle.PANEL_DIVIDER),
            )
        }
    }
}

/**
 * 0.5dp 白 20% 描边（`HomeHeader.tsx:248-249`）。
 *
 * ⚠️ 宽度是 `0.5.s` —— **不能取整成 1dp**。RN 的 0.5 在多数密度下渲染成一条
 * 亚像素细线，写 1dp 会明显粗一圈（面板在深色背景上，边框很显眼）。
 */
@Composable
private fun Modifier.border0(): Modifier = this.then(
    Modifier.border(
        width = 0.5f.s,
        color = HomeStyle.PANEL_BORDER,
        shape = RoundedCornerShape(8.s),
    ),
)

/** 筛选按钮（系列行右侧）。opacity 0.5 是 RN 的 `styles.filterIcon`。 */
@Composable
internal fun HomeFilterButton(
    showBadge: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Image(
            painter = painterResource(R.drawable.ic_home_filter),
            contentDescription = rememberLocalizedString("Filter"),
            modifier = Modifier
                .size(HomeStyle.FILTER_ICON.s)
                .clip(RoundedCornerShape(16.s))
                .background(HomeStyle.FILTER_FILL)
                .alpha(0.5f)
                .clickable(onClick = onClick),
        )
        if (showBadge) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(HomeStyle.BADGE_SIZE.s)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(HomeStyle.BADGE_DOT),
            )
        }
    }
}
