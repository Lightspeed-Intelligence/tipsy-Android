package ai.lightspeed.tipsy.shell.pages.search

import ai.lightspeed.tipsy.shell.R
import ai.lightspeed.tipsy.shell.i18n.LocalizedText
import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
import ai.lightspeed.tipsy.shell.pages.home.HomeCard
import ai.lightspeed.tipsy.shell.pages.home.HomeFeedItem
import ai.lightspeed.tipsy.shell.pages.home.HomeText
import ai.lightspeed.tipsy.shell.ui.s
import ai.lightspeed.tipsy.shell.ui.sSp
import coil3.compose.AsyncImage
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 搜索页（`app/search/page.tsx` 的原生对应）。
 *
 * ## 三种屏幕形态由 [SearchState.tab] + [SearchState.query] 决定
 *
 * | 条件 | 展示 |
 * | --- | --- |
 * | `tab == NONE` 且输入为空 | 最近搜索 + 热门搜索 |
 * | `tab == NONE` 且有输入 | 建议词（原始输入恒第一条） |
 * | `tab == CHARACTERS/CREATORS` | 结果 tab 栏 + 对应结果列表 |
 *
 * 注意第二三种的切换时机：输入变化只回到 NONE（展示建议词），
 * **提交**（回车/点建议/点最近/点热门）才切到 CHARACTERS。
 */
@Composable
internal fun SearchScreen(
    state: SearchState,
    statusBarPadding: Dp,
    listBottomPadding: Dp,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClearQuery: () -> Unit,
    onBackClick: () -> Unit,
    onTabChange: (SearchTab) -> Unit,
    onSuggestionClick: (String) -> Unit,
    onRecentClick: (String) -> Unit,
    onPopularClick: (String) -> Unit,
    onClearHistoryRequest: () -> Unit,
    onClearHistoryConfirm: () -> Unit,
    onClearHistoryDismiss: () -> Unit,
    onLoadMore: () -> Unit,
    onCharacterClick: (HomeFeedItem.Character, Int) -> Unit,
    onCharacterExposed: (HomeFeedItem.Character) -> Unit,
    onCreatorClick: (CreatorResult, Int) -> Unit,
    onCreatorExposed: (CreatorResult) -> Unit,
    onCreateCharacterClick: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            // 底色在 padding 之前 —— 要铺满整屏（含状态栏区域），
            // 否则状态栏那条会露出下层的首页
            .background(SearchStyle.pageBackground)
            .padding(top = statusBarPadding)
            .testTag("search_screen"),
    ) {
        SearchBar(
            query = state.query,
            onQueryChange = onQueryChange,
            onSubmit = {
                keyboard?.hide()
                onSubmit()
            },
            onClear = onClearQuery,
            onBack = onBackClick,
        )

        if (state.tab != SearchTab.NONE) {
            SearchResultTabs(
                tab = state.tab,
                onTabChange = onTabChange,
                // P1 无筛选器：不渲染筛选按钮（P2 接 FilterDrawer 时打开）
                onFilterClick = null,
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.tab == SearchTab.CHARACTERS -> CharacterResultList(
                    state = state,
                    bottomPadding = listBottomPadding,
                    onLoadMore = onLoadMore,
                    onItemClick = onCharacterClick,
                    onItemExposed = onCharacterExposed,
                    onCreateCharacterClick = onCreateCharacterClick,
                )

                state.tab == SearchTab.CREATORS -> CreatorResultList(
                    state = state,
                    bottomPadding = listBottomPadding,
                    onLoadMore = onLoadMore,
                    onItemClick = onCreatorClick,
                    onItemExposed = onCreatorExposed,
                )

                // 有输入但没提交：建议词
                state.query.isNotBlank() -> SuggestionList(
                    suggestions = state.displaySuggestions,
                    query = state.query,
                    bottomPadding = listBottomPadding,
                    onClick = {
                        keyboard?.hide()
                        onSuggestionClick(it)
                    },
                )

                // 无输入：最近 + 热门
                state.showsDiscovery -> DiscoveryContent(
                    recentSearches = state.recentSearches,
                    popularTerms = state.popularTerms,
                    bottomPadding = listBottomPadding,
                    onRecentClick = {
                        keyboard?.hide()
                        onRecentClick(it)
                    },
                    onPopularClick = {
                        keyboard?.hide()
                        onPopularClick(it)
                    },
                    onClearHistoryRequest = onClearHistoryRequest,
                )
            }
        }
    }

    if (state.showClearHistoryDialog) {
        ClearHistoryDialog(
            onConfirm = onClearHistoryConfirm,
            onDismiss = onClearHistoryDismiss,
        )
    }
}

/**
 * 顶部搜索栏（`SearchBar.tsx`）。
 *
 * ## 自动聚焦
 *
 * RN 用 `setTimeout(600ms)` 后 `focus()`（`SearchBar.tsx:26-31`）——
 * 那是为了等导航转场动画结束，否则键盘会把转场动画顶掉。壳侧用
 * `FocusRequester` + 一次 `LaunchedEffect`，Fragment 事务的转场比 RN
 * 导航短，不需要 600ms 的等待（真机若观察到键盘顶掉转场，再加延迟）。
 */
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(SearchStyle.BAR_PADDING.s),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_search_back),
            contentDescription = rememberLocalizedString("Back"),
            modifier = Modifier
                .size(SearchStyle.BAR_ICON.s)
                .clickable(onClick = onBack)
                .testTag("search_back"),
        )
        SearchInput(
            query = query,
            onQueryChange = onQueryChange,
            onSubmit = onSubmit,
            onClear = onClear,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 输入框本体（`SearchBar.tsx` 的 `searchInputContainer`）。 */
@Composable
private fun SearchInput(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // 进页面自动聚焦并弹键盘（RN 的 600ms setTimeout focus）
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(SearchStyle.INPUT_HEIGHT.s)
            .clip(RoundedCornerShape(SearchStyle.INPUT_RADIUS.s))
            .background(SearchStyle.inputBackground)
            .padding(horizontal = SearchStyle.INPUT_PADDING_H.s),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_search_magnifier),
            contentDescription = null,
            modifier = Modifier.size(SearchStyle.BAR_ICON.s),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(
                color = Color.White,
                fontSize = SearchStyle.INPUT_FONT.sSp,
            ),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .testTag("search_input"),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        LocalizedText(
                            key = "Search characters or creators...",
                            color = SearchStyle.inputPlaceholder,
                            fontSize = SearchStyle.INPUT_FONT.sSp,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )
        // 有输入才显示清空按钮（`SearchBar.tsx:55-63`）
        if (query.isNotEmpty()) {
            Image(
                painter = painterResource(R.drawable.ic_search_close),
                contentDescription = rememberLocalizedString("Clear"),
                modifier = Modifier
                    .size(SearchStyle.BAR_ICON.s)
                    .clickable(onClick = onClear)
                    .testTag("search_clear"),
            )
        }
    }
}

/** 结果 tab 栏（`SearchResultTabs.tsx`）。[onFilterClick] 为 null 时不渲染筛选按钮。 */
@Composable
private fun SearchResultTabs(
    tab: SearchTab,
    onTabChange: (SearchTab) -> Unit,
    onFilterClick: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .height(SearchStyle.TABS_HEIGHT.s)
            .padding(horizontal = SearchStyle.TABS_PADDING_H.s),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SearchStyle.TABS_GAP.s),
            modifier = Modifier.padding(start = SearchStyle.TABS_MARGIN_L.s),
        ) {
            TabItem(
                labelKey = "Characters",
                selected = tab == SearchTab.CHARACTERS,
                onClick = { onTabChange(SearchTab.CHARACTERS) },
                testTag = "search_tab_characters",
            )
            TabItem(
                labelKey = "Creators",
                selected = tab == SearchTab.CREATORS,
                onClick = { onTabChange(SearchTab.CREATORS) },
                testTag = "search_tab_creators",
            )
        }
        // 筛选按钮只在角色 tab 显示（`SearchResultTabs.tsx:38`）
        if (onFilterClick != null && tab == SearchTab.CHARACTERS) {
            Image(
                painter = painterResource(R.drawable.ic_home_filter),
                contentDescription = rememberLocalizedString("Filter"),
                modifier = Modifier
                    .size(SearchStyle.FILTER_ICON.s)
                    .clickable(onClick = onFilterClick),
            )
        }
    }
}

@Composable
private fun TabItem(
    labelKey: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            // RN 是 minWidth: 48 + flexShrink: 0 —— 下限，不是固定宽。
            // 用 .width() 会把 "Characters"/"Creators" 截成 "Chara"/"Creato"
            .widthIn(min = SearchStyle.TAB_MIN_WIDTH.s)
            .clickable(onClick = onClick)
            .testTag(testTag),
    ) {
        LocalizedText(
            key = labelKey,
            color = if (selected) {
                SearchStyle.tabTextSelected
            } else {
                SearchStyle.tabTextUnselected
            },
            fontSize = SearchStyle.TAB_FONT.sSp,
            maxLines = 1,
        )
        if (selected) {
            Spacer(Modifier.height(2.s))
            Box(
                modifier = Modifier
                    .width(SearchStyle.TAB_INDICATOR_W.s)
                    .height(SearchStyle.TAB_INDICATOR_H.s)
                    .clip(RoundedCornerShape(SearchStyle.TAB_INDICATOR_RADIUS.s))
                    .background(SearchStyle.tabIndicator),
            )
        }
    }
}

// ── 未搜索态：最近 + 热门 ────────────────────────────────

/**
 * 未搜索态内容（`page.tsx:128-146`）。
 *
 * 两个区块都是「空则整块不渲染」（`RecentSearch.tsx:36`、`PopularTags.tsx:23`）——
 * 不是渲染一个空标题。放在可滚动容器里：标签多时（热门可达数十个）会超屏。
 */
@Composable
private fun DiscoveryContent(
    recentSearches: List<String>,
    popularTerms: List<String>,
    bottomPadding: Dp,
    onRecentClick: (String) -> Unit,
    onPopularClick: (String) -> Unit,
    onClearHistoryRequest: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = bottomPadding),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (recentSearches.isNotEmpty()) {
            item(key = "recent") {
                Column(modifier = Modifier.padding(SearchStyle.SECTION_MARGIN.s)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = SearchStyle.SECTION_TITLE_MARGIN_B.s),
                    ) {
                        LocalizedText(
                            key = "Recent Search",
                            color = SearchStyle.sectionTitleColor,
                            fontSize = SearchStyle.SECTION_TITLE_FONT.sSp,
                        )
                        Image(
                            painter = painterResource(R.drawable.ic_search_history_clear),
                            contentDescription = rememberLocalizedString("Clear"),
                            modifier = Modifier
                                .size(SearchStyle.CLEAR_ICON.s)
                                .clickable(onClick = onClearHistoryRequest)
                                .testTag("search_clear_history"),
                        )
                    }
                    ChipFlowRow(terms = recentSearches, onClick = onRecentClick)
                }
            }
        }

        if (popularTerms.isNotEmpty()) {
            item(key = "popular") {
                Column(modifier = Modifier.padding(SearchStyle.SECTION_MARGIN.s)) {
                    LocalizedText(
                        key = "Popular Search",
                        color = SearchStyle.sectionTitleColor,
                        fontSize = SearchStyle.SECTION_TITLE_FONT.sSp,
                        modifier = Modifier.padding(bottom = SearchStyle.SECTION_TITLE_MARGIN_B.s),
                    )
                    ChipFlowRow(terms = popularTerms, onClick = onPopularClick)
                }
            }
        }
    }
}

/** 标签流式排列（RN 的 `flexWrap: 'wrap'` + `gap: 8`）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlowRow(terms: List<String>, onClick: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(SearchStyle.CHIP_GAP.s),
        verticalArrangement = Arrangement.spacedBy(SearchStyle.CHIP_GAP.s),
        modifier = Modifier.fillMaxWidth(),
    ) {
        terms.forEach { term ->
            Text(
                text = term,
                color = SearchStyle.chipText,
                fontSize = SearchStyle.CHIP_FONT.sSp,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(SearchStyle.CHIP_RADIUS.s))
                    .border(
                        width = 1.dp,
                        color = SearchStyle.chipBorder,
                        shape = RoundedCornerShape(SearchStyle.CHIP_RADIUS.s),
                    )
                    .background(SearchStyle.chipBackground)
                    .clickable { onClick(term) }
                    .padding(
                        horizontal = SearchStyle.CHIP_PADDING_H.s,
                        vertical = SearchStyle.CHIP_PADDING_V.s,
                    ),
            )
        }
    }
}

// ── 建议词 ────────────────────────────────

/**
 * 建议词列表（`SuggestTags.tsx`）。
 *
 * 命中片段用主题橙高亮（大小写不敏感匹配，只高亮**第一处**——
 * 对齐 `highlightMatch` 的 `indexOf`）。
 */
@Composable
private fun SuggestionList(
    suggestions: List<String>,
    query: String,
    bottomPadding: Dp,
    onClick: (String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(SearchStyle.SUGGEST_GAP.s),
        modifier = Modifier
            .fillMaxSize()
            .testTag("search_suggestions"),
    ) {
        items(suggestions, key = { it }) { term ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SearchStyle.SUGGEST_GAP.s),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick(term) }
                    .padding(
                        start = SearchStyle.SUGGEST_ROW_PADDING_L.s,
                        top = SearchStyle.SUGGEST_ROW_PADDING_V.s,
                        bottom = SearchStyle.SUGGEST_ROW_PADDING_V.s,
                    ),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_search_magnifier),
                    contentDescription = null,
                    modifier = Modifier.size(SearchStyle.SUGGEST_ICON.s),
                )
                Text(
                    text = highlightMatch(term, query),
                    color = SearchStyle.suggestText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 把 [text] 里第一处匹配 [query] 的片段染成高亮色（`SuggestTags.tsx:37-52`）。 */
private fun highlightMatch(text: String, query: String) = buildAnnotatedString {
    val trimmed = query.trim()
    val index = if (trimmed.isEmpty()) {
        -1
    } else {
        text.indexOf(trimmed, ignoreCase = true)
    }
    if (index < 0) {
        append(text)
        return@buildAnnotatedString
    }
    append(text.substring(0, index))
    withStyle(SpanStyle(color = SearchStyle.suggestHighlight)) {
        append(text.substring(index, index + trimmed.length))
    }
    append(text.substring(index + trimmed.length))
}

// ── 角色结果 ────────────────────────────────

/**
 * 角色结果双列网格（`CharacterResultList.tsx`）。
 *
 * 卡片直接复用 `HomeCard` —— RN 侧也是这么干的（用 5 个 `@ts-ignore` 把
 * 搜索结果硬凑成 Home 卡的形状），壳侧由 `SearchParser` 在解析期就翻译成
 * `HomeFeedItem.Character`，比 RN 干净。
 *
 * [SearchState.isRefreshing] 为 true 时盖半透明遮罩（P2 筛选用；P1 恒 false）。
 */
@Composable
private fun CharacterResultList(
    state: SearchState,
    bottomPadding: Dp,
    onLoadMore: () -> Unit,
    onItemClick: (HomeFeedItem.Character, Int) -> Unit,
    onItemExposed: (HomeFeedItem.Character) -> Unit,
    onCreateCharacterClick: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 首查在途且无结果：整页 spinner
        if (state.isLoading && state.characterResults.isEmpty()) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.s)
                    .size(28.s),
            )
        }

        if (state.characterResults.isEmpty() && !state.isLoading && !state.isRefreshing) {
            EmptyResults(
                showsCreateButton = state.showsCreateCharacterButton,
                onCreateClick = onCreateCharacterClick,
            )
        } else {
            val gridState = rememberLazyGridState()
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                contentPadding = PaddingValues(bottom = bottomPadding),
                horizontalArrangement = Arrangement.spacedBy(SearchStyle.CARD_GAP.dp),
                verticalArrangement = Arrangement.spacedBy(SearchStyle.CARD_GAP.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("search_character_results"),
            ) {
                items(
                    count = state.characterResults.size,
                    key = { index -> state.characterResults[index].stableKey },
                ) { index ->
                    val item = state.characterResults[index]
                    HomeCard(
                        item = item,
                        onClick = { onItemClick(item, index + 1) },
                        onExposed = { onItemExposed(item) },
                        modifier = Modifier.height(SearchStyle.CARD_HEIGHT.s),
                    )
                }
            }
            // 滚到底翻页。lambda 传入而非直接读值 —— layoutInfo 必须在
            // derivedStateOf 里读，否则每帧重组（见该函数注释）
            LoadMoreOnScrollToEnd(
                totalItemsCount = { gridState.layoutInfo.totalItemsCount },
                lastVisibleIndex = { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index },
                visibleItemsCount = { gridState.layoutInfo.visibleItemsInfo.size },
                itemCount = state.characterResults.size,
                isLoadingMore = state.isLoadingMore,
                isRefreshing = state.isRefreshing,
                canLoadMore = state.canLoadMoreCharacters,
                onLoadMore = onLoadMore,
            )
        }

        if (state.isRefreshing) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(SearchStyle.refreshingMask),
            ) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.s))
            }
        }
    }
}

/** 空态（`CharacterResultList.tsx` 的 `ListEmptyComponent`）。 */
@Composable
private fun EmptyResults(showsCreateButton: Boolean, onCreateClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = SearchStyle.EMPTY_MARGIN_T.s)
            .testTag("search_empty"),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_search_empty),
            contentDescription = null,
            modifier = Modifier
                .width(SearchStyle.EMPTY_IMAGE_W.s)
                .height(SearchStyle.EMPTY_IMAGE_H.s),
        )
        LocalizedText(
            key = "No results",
            color = SearchStyle.emptyText,
        )
        if (showsCreateButton) {
            Spacer(Modifier.height(SearchStyle.EMPTY_BUTTON_MARGIN_T.s))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .width(SearchStyle.EMPTY_BUTTON_W.s)
                    .height(SearchStyle.EMPTY_BUTTON_H.s)
                    .clip(RoundedCornerShape(SearchStyle.EMPTY_BUTTON_H.s / 2))
                    .background(SearchStyle.primaryButton)
                    .clickable(onClick = onCreateClick)
                    .testTag("search_create_now"),
            ) {
                LocalizedText(
                    key = "Create Now",
                    color = Color.White,
                    fontSize = SearchStyle.EMPTY_BUTTON_FONT.sSp,
                )
            }
        }
    }
}

// ── 创作者结果 ────────────────────────────────

/** 创作者结果列表（`CreatorResultList.tsx` + `CreatorResultItem.tsx`）。 */
@Composable
private fun CreatorResultList(
    state: SearchState,
    bottomPadding: Dp,
    onLoadMore: () -> Unit,
    onItemClick: (CreatorResult, Int) -> Unit,
    onItemExposed: (CreatorResult) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (state.isLoading && state.creatorResults.isEmpty()) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.s)
                    .size(28.s),
            )
        }

        if (state.creatorResults.isEmpty() && !state.isLoading) {
            // 创作者 tab 空态只有图 + 文案，**没有** Create Now
            // （创建入口是角色专属，`CreatorResultList.tsx` 无该按钮）
            EmptyResults(showsCreateButton = false, onCreateClick = {})
        } else {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = bottomPadding),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("search_creator_results"),
            ) {
                itemsIndexed(state.creatorResults, key = { _, it -> it.userId }) { index, creator ->
                    CreatorRow(
                        creator = creator,
                        onClick = { onItemClick(creator, index + 1) },
                        onExposed = { onItemExposed(creator) },
                    )
                }
            }
            LoadMoreOnScrollToEnd(
                totalItemsCount = { listState.layoutInfo.totalItemsCount },
                lastVisibleIndex = { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index },
                visibleItemsCount = { listState.layoutInfo.visibleItemsInfo.size },
                itemCount = state.creatorResults.size,
                isLoadingMore = state.isLoadingMore,
                isRefreshing = state.isRefreshing,
                canLoadMore = state.canLoadMoreCreators,
                onLoadMore = onLoadMore,
            )
        }
    }
}

/** 创作者行（`CreatorResultItem.tsx`）：头像 + 昵称 + 三项统计 + bio。 */
@Composable
private fun CreatorRow(
    creator: CreatorResult,
    onClick: () -> Unit,
    onExposed: () -> Unit,
) {
    LaunchedEffect(creator.userId) { onExposed() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = SearchStyle.CREATOR_ROW_PADDING_H.s,
                vertical = SearchStyle.CREATOR_ROW_PADDING_V.s,
            ),
    ) {
        AsyncImage(
            model = HomeText.transformImageUrl(creator.avatar),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(SearchStyle.CREATOR_AVATAR.s)
                .clip(CircleShape),
        )
        Spacer(Modifier.width(SearchStyle.CREATOR_AVATAR_MARGIN_R.s))
        Column(verticalArrangement = Arrangement.spacedBy(SearchStyle.CREATOR_INFO_GAP.s)) {
            Text(
                text = creator.nickname,
                color = SearchStyle.creatorName,
                fontSize = SearchStyle.CREATOR_NAME_FONT.sSp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(SearchStyle.CREATOR_META_GAP.s)) {
                CreatorMeta("Followers", creator.followeesCount)
                CreatorMeta("Interactions", creator.totalInteractions)
            }
            CreatorMeta("Characters", creator.createdCharactersCount)
            if (creator.bio.isNotBlank()) {
                Text(
                    text = creator.bio,
                    color = SearchStyle.creatorBio,
                    fontSize = SearchStyle.CREATOR_BIO_FONT.sSp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** `{label}: {count}` 一项。计数走四位数缩写（对齐 `formatCountMaxFourDigits`）。 */
@Composable
private fun CreatorMeta(labelKey: String, count: Long) {
    Text(
        text = "${rememberLocalizedString(labelKey)}: ${SearchText.formatCountMaxFourDigits(count)}",
        color = SearchStyle.creatorMeta,
        fontSize = SearchStyle.CREATOR_META_FONT.sSp,
        maxLines = 1,
    )
}

// ── 清空历史确认 ────────────────────────────────

/**
 * 清空最近搜索的确认弹窗（`RecentSearch.tsx:66-77` 的 `TipsyModal`）。
 *
 * 视觉复用 ChatList 删除弹窗（同为居中 Dialog）；按钮语义照 RN：
 * Cancel / **Clear**（不是 Delete）。
 */
@Composable
private fun ClearHistoryDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(SearchStyle.DIALOG_RADIUS.s))
                .background(SearchStyle.dialogBackground)
                .padding(SearchStyle.DIALOG_PADDING.s)
                .testTag("search_clear_history_dialog"),
        ) {
            LocalizedText(
                key = "Are you sure to clear all search records?",
                color = SearchStyle.dialogText,
                fontSize = SearchStyle.DIALOG_FONT.sSp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.s))
            Row(horizontalArrangement = Arrangement.spacedBy(12.s)) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("search_clear_history_cancel"),
                ) {
                    LocalizedText(key = "Cancel", color = Color.White, fontSize = 14.sSp)
                }
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SearchStyle.primaryButton,
                    ),
                    modifier = Modifier.testTag("search_clear_history_confirm"),
                ) {
                    LocalizedText(key = "Clear", color = Color.White, fontSize = 14.sSp)
                }
            }
        }
    }
}

/**
 * 触底翻页（同 `ChatListScreen`/`HomeScreen` 的 `derivedStateOf` 惯用法）。
 *
 * ⚠️ **不能在 composition 里直读 `layoutInfo`** —— 它带
 * `@FrequentlyChangingValue`，每一帧滚动都会触发整列表重组（lint 会报
 * `FrequentlyChangingValue`）。`derivedStateOf` 把它折叠成一个布尔量，
 * 只有「该不该翻页」翻转时才重组这个小函数。
 *
 * 阈值 = 剩余不足半个可见窗口（近似 RN 的 `onEndReachedThreshold: 0.5`）——
 * 用可见条目数算，比硬编码条数能自适应双列/单列与屏幕高度。
 * `itemCount` / `isLoadingMore` 故意作为 effect key：如果新页只增加少量唯一
 * 条目，列表仍处于阈值内，布尔量会保持 true。仅收集布尔翻转会永久
 * 卡在该页；完成后以新条目数重新评估才能继续填满视口。
 * ViewModel 侧有并发守卫和空页上限，重复触发是安全的。
 */
@Composable
private fun LoadMoreOnScrollToEnd(
    totalItemsCount: () -> Int,
    lastVisibleIndex: () -> Int?,
    visibleItemsCount: () -> Int,
    itemCount: Int,
    isLoadingMore: Boolean,
    isRefreshing: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
) {
    val shouldLoadMore by remember {
        derivedStateOf {
            val total = totalItemsCount()
            if (total == 0) return@derivedStateOf false
            val lastVisible = lastVisibleIndex() ?: return@derivedStateOf false
            lastVisible >= total - (visibleItemsCount() / 2).coerceAtLeast(1) - 1
        }
    }
    val requestGate = remember { LoadMoreRequestGate() }
    LaunchedEffect(shouldLoadMore, isLoadingMore, isRefreshing, itemCount, canLoadMore) {
        if (
            requestGate.shouldRequest(
                nearEnd = shouldLoadMore,
                isBlocked = isLoadingMore || isRefreshing,
                canLoadMore = canLoadMore,
                itemCount = itemCount,
            )
        ) {
            onLoadMore()
        }
    }
}

/**
 * 触底请求的小型状态门。同一条目数只允许请求一次：成功增量后可继续
 * 填满视口，失败或去重空页则不会因 loading 回落而无限自动重试。
 * 用户滚出阈值后再进入会重置，因此普通失败仍可手动重试；
 * 连续去重空页则另由 ViewModel 的三页上限终止。
 */
internal class LoadMoreRequestGate {
    private var lastRequestedItemCount: Int? = null

    fun shouldRequest(
        nearEnd: Boolean,
        isBlocked: Boolean,
        canLoadMore: Boolean,
        itemCount: Int,
    ): Boolean {
        if (!nearEnd) {
            lastRequestedItemCount = null
            return false
        }
        if (isBlocked || !canLoadMore || lastRequestedItemCount == itemCount) return false
        lastRequestedItemCount = itemCount
        return true
    }
}
