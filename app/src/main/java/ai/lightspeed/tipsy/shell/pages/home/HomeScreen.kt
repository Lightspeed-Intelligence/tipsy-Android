package ai.lightspeed.tipsy.shell.pages.home

import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
import ai.lightspeed.tipsy.shell.ui.s
import ai.lightspeed.tipsy.shell.ui.sSp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp

/**
 * Home 页（Tab2，方案 §8.1 Home 行）。
 *
 * ## 列表纪律（方案 §8.4，iOS 花了整月修的那组）
 *
 * - **stable key**：`LazyVerticalGrid` 的 `key = item.stableKey`（含 requestId，
 *   见 [HomeFeedItem.stableKey]）。禁止全量替换，追加时已存在的 item 不重组。
 * - **翻页不换 session**、筛选/刷新才换 —— 在 [HomeViewModel] 里。
 * - **翻页去重后空页主动续拉**且限次 —— 同上。
 *
 * ## 本包未做（明确边界）
 *
 * banner 插位（RN 在第 3 个位置插一个占位，`insertBannerPlaceholders`）、
 * 标签筛选抽屉、冷启动缓存、可见性驱动的曝光去重。
 * 筛选按钮**在位但点击落 Router 拒绝** —— 方案 §8.3 要求不做 silent no-op。
 *
 * ## 下拉刷新用系统控件
 *
 * RN 侧 Android 分支用的正是 `RefreshControl`（系统控件），iOS 才是自绘的
 * 拉伸动画（`home.tsx:1911-1913` 按平台分叉，注释写明「Android使用系统刷新控件」）。
 * 所以这里用 M3 的 `PullToRefreshBox` 与现网对齐，**不移植那套 Reanimated 动画**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    state: HomeState,
    onSeriesSelected: (HomeSeries) -> Unit,
    onGenderSelected: (HomeGender) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onItemClick: (HomeFeedItem) -> Unit,
    onItemExposed: (HomeFeedItem) -> Unit,
    onSearchClick: () -> Unit,
    onSubscriptionClick: () -> Unit,
    onFilterClick: () -> Unit,
    /** 抽屉关闭（点 ✓ / 遮罩 / 返回键）时提交勾选 —— 三者语义相同，见抽屉注释。 */
    onTagsApplied: (List<String>) -> Unit,
    /** 列表底部留白 = safeBottom + 50（`home.tsx:257` Android 分支）。 */
    listBottomPadding: Dp,
    /**
     * 状态栏留白（`home.tsx:2125` 的 `paddingTop: insets.top`）。
     *
     * ⚠️ **不能漏**：壳是 edge-to-edge（`enableEdgeToEdge()`），内容默认铺到
     * 状态栏之下 —— 漏了顶栏的订阅图标与搜索框会被时间/信号图标压住。
     * 首版真机验证就是这么发现的（单测与 lint 都测不出这个）。
     *
     * 与 bottom 一样是**实际 dp，不参与 `.s` 缩放** —— 系统 inset 本身已是 dp。
     */
    statusBarPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    // 触底续拉：阈值 0.5 屏（RN 的 `onEndReachedThreshold={0.5}`）。
    // 用 derivedStateOf 而不是在每帧 recompose 里算 —— 后者会让整页随滚动重组
    val shouldLoadMore by remember(gridState) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) return@derivedStateOf false
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            // 可见窗口的一半作为提前量，近似 RN 的 0.5 屏
            lastVisible >= total - (info.visibleItemsInfo.size / 2).coerceAtLeast(1) - 1
        }
    }
    LaunchedEffect(gridState) {
        snapshotFlow { shouldLoadMore }.collect { if (it) onLoadMore() }
    }

    // paddingTop 加在**整个容器**上（对齐 `home.tsx:2120-2127`），不是只加在 header 上：
    // 加在 header 内部会让 header 自身变高，而 RN 的 header 是固定 50 高、
    // 由外层容器让出状态栏空间
    Column(modifier = modifier.fillMaxSize().padding(top = statusBarPadding)) {
        HomeHeader(
            gender = state.gender,
            onGenderSelected = onGenderSelected,
            onSearchClick = onSearchClick,
            onSubscriptionClick = onSubscriptionClick,
        )

        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                // 首屏加载：列表还没有任何内容
                state.isInitialLoading && state.items.isEmpty() -> LoadingOrError(
                    seriesRow = {
                        SeriesRow(state.selectedSeries, onSeriesSelected, onFilterClick)
                    },
                    content = { CenteredSpinner() },
                )

                state.errorMessage != null && state.items.isEmpty() -> LoadingOrError(
                    seriesRow = {
                        SeriesRow(state.selectedSeries, onSeriesSelected, onFilterClick)
                    },
                    content = { ErrorText(state.errorMessage) },
                )

                else -> FeedGrid(
                    state = state,
                    gridState = gridState,
                    onSeriesSelected = onSeriesSelected,
                    onFilterClick = onFilterClick,
                    onItemClick = onItemClick,
                    onItemExposed = onItemExposed,
                    listBottomPadding = listBottomPadding,
                )
            }
        }
    }

    // 抽屉走 Dialog，铺在整个 Column 之外 —— 放进 Column 里会被它的
    // padding 约束住，且遮罩盖不住状态栏
    if (state.isFilterDrawerOpen) {
        HomeFilterDrawer(
            catalog = state.tagCatalog,
            selectedTagIds = state.selectedTagIds,
            onApply = onTagsApplied,
        )
    }
}

@Composable
private fun FeedGrid(
    state: HomeState,
    gridState: LazyGridState,
    onSeriesSelected: (HomeSeries) -> Unit,
    onFilterClick: () -> Unit,
    onItemClick: (HomeFeedItem) -> Unit,
    onItemExposed: (HomeFeedItem) -> Unit,
    listBottomPadding: Dp,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMN_COUNT),
        state = gridState,
        // 两列间 1dp 缝（`columnWrapperStyle={{ gap: 1 }}`）+ 外侧 2dp
        // （`styles.listContainer` paddingHorizontal: 2）
        contentPadding = PaddingValues(
            start = LIST_HORIZONTAL_PADDING.s,
            end = LIST_HORIZONTAL_PADDING.s,
            bottom = listBottomPadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(HomeStyle.CARD_GAP.s),
        verticalArrangement = Arrangement.spacedBy(HomeStyle.CARD_GAP.s),
        modifier = Modifier.fillMaxSize(),
    ) {
        // 系列行是列表头（`ListHeaderComponent`）—— 随列表滚动，不是固定栏。
        // 做成固定栏会让首屏可见内容少一行，与 RN 并排看能看出来
        item(span = { GridItemSpan(maxLineSpan) }, key = SERIES_ROW_KEY) {
            SeriesRow(state.selectedSeries, onSeriesSelected, onFilterClick)
        }

        items(
            items = state.items,
            // ⚠️ stable key（方案 §8.4）。For You 的 key 含 requestId ——
            // 纯 characterId 会在同一角色跨 session 出现时撞 key，Compose 直接抛
            key = { it.stableKey },
        ) { item ->
            HomeCard(
                item = item,
                onClick = { onItemClick(item) },
                onExposed = { onItemExposed(item) },
                modifier = Modifier.height(HomeStyle.CARD_HEIGHT.s),
            )
        }

        if (state.isLoadingMore) {
            item(span = { GridItemSpan(maxLineSpan) }, key = FOOTER_KEY) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(20.s),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.height(24.s).width(24.s),
                    )
                }
            }
        }

        // 空态：只有 Following 有文案，其余系列留白（见 HomeState.emptyMessageKey）
        val emptyKey = state.emptyMessageKey
        if (state.isEmpty && emptyKey != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = EMPTY_KEY) {
                Text(
                    text = rememberLocalizedString(emptyKey),
                    color = HomeStyle.SERIES_TEXT,
                    fontSize = 14.sSp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 60.s, start = 24.s, end = 24.s),
                )
            }
        }
    }
}

/** 系列切换行 + 筛选按钮（`home.tsx:1239-1296`）。 */
@Composable
private fun SeriesRow(
    selected: HomeSeries,
    onSelected: (HomeSeries) -> Unit,
    onFilterClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = HomeStyle.SERIES_ROW_PADDING.s,
                vertical = 0.s,
            )
            .padding(bottom = HomeStyle.SERIES_ROW_BOTTOM.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(HomeStyle.SERIES_CHIP_HEIGHT.s)
                // 右边缘渐变淡出（RN 用 `MaskedView` + 横向 LinearGradient，
                // `locations=[0, 0.8, 0.9, 1]`）。⚠️ 没有它最后一个 chip 会被
                // **硬裁**成半截，看起来像布局坏了 —— 首版真机验证就是这么发现的。
                // Compose 侧用 `graphicsLayer` + DstIn 混合实现同一效果，
                // 不引 MaskedView 那类依赖
                .fadingEdgeRight()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(HomeStyle.SERIES_CHIP_GAP.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeSeries.displayOrder.forEach { series ->
                SeriesChip(
                    series = series,
                    isSelected = series == selected,
                    onClick = { onSelected(series) },
                )
            }
            // `seriesNavContainer.paddingRight: 6` —— 末尾留白，让最后一个 chip
            // 能完整滚出淡出区
            Spacer(modifier = Modifier.width(6.s))
        }
        // Following 与 World 没有标签筛选（`home.tsx:1283`）——
        // 给它们显示筛选按钮会让用户点了没反应（那两个系列的接口不接受 tag_ids）
        if (selected.supportsTagFilter) {
            HomeFilterButton(
                // 红点逻辑（未点击过的带红点标签）属标签抽屉那一包
                showBadge = false,
                onClick = onFilterClick,
                modifier = Modifier.padding(start = 6.s),
            )
        }
    }
}

@Composable
private fun SeriesChip(series: HomeSeries, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(HomeStyle.SERIES_CHIP_HEIGHT.s)
            .clip(RoundedCornerShape(HomeStyle.SERIES_CHIP_RADIUS.s))
            .background(
                if (isSelected) HomeStyle.SERIES_SELECTED else HomeStyle.SERIES_UNSELECTED,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = HomeStyle.SERIES_CHIP_PADDING.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // ⚠️ 用 rememberLocalizedString 而不是 L10n.t —— 后者是普通函数调用，
            // Compose 不知道它读了可变状态，切语言后已组合的文本不重组
            // （进度文档 §2.16 记的那条纪律）
            text = rememberLocalizedString(series.key),
            color = if (isSelected) {
                HomeStyle.SERIES_TEXT_SELECTED
            } else {
                HomeStyle.SERIES_TEXT
            },
            fontSize = HomeStyle.SERIES_TEXT_SIZE.sSp,
            maxLines = 1,
        )
    }
}

/** 首屏 loading / 错误时仍要显示系列行 —— 否则用户无法切到别的系列自救。 */
@Composable
private fun LoadingOrError(
    seriesRow: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        seriesRow()
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
private fun CenteredSpinner() {
    CircularProgressIndicator(color = Color.White, modifier = Modifier.height(32.s).width(32.s))
}

@Composable
private fun ErrorText(message: String) {
    // 后端 msg 已是可展示文案，直接用；兜底串是 i18n key，走 L10n 翻译
    val text = if (message == HomeViewModel.FALLBACK_ERROR_KEY) {
        rememberLocalizedString(message)
    } else {
        message
    }
    Text(
        text = text,
        color = HomeStyle.SERIES_TEXT,
        fontSize = 14.sSp,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 24.s),
    )
}

/**
 * 右边缘渐变淡出，等价于 RN 的 `MaskedView` + 横向 `LinearGradient`
 * （`colors=[black,black,black,transparent]`, `locations=[0,0.8,0.9,1]`）。
 *
 * ⚠️ 必须 `compositingStrategy = Offscreen`：`BlendMode.DstIn` 需要一个离屏层
 * 才能作用到已绘制内容上。漏了它在部分设备上表现为**整行不可见**
 * （混合作用到了整个窗口）。
 */
private fun Modifier.fadingEdgeRight(): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Black,
                0.8f to Color.Black,
                0.9f to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/** 两列（`numColumns={2}`）。 */
private const val COLUMN_COUNT = 2

/** `styles.listContainer` 的 `paddingHorizontal: 2`。 */
private const val LIST_HORIZONTAL_PADDING = 2

private const val SERIES_ROW_KEY = "series-row"
private const val FOOTER_KEY = "footer-loading"
private const val EMPTY_KEY = "empty-state"
