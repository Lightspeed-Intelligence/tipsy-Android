package ai.lightspeed.tipsy.shell.pages.search

import ai.lightspeed.tipsy.shell.pages.home.HomeFeedItem

/**
 * 搜索结果的 tab（`page.tsx:25` 的三态字符串）。
 *
 * RN 用 `''` / `'Characters'` / `'Creators'` 三个字符串表达「未搜索 / 角色 / 创作者」，
 * 壳用枚举 —— `NONE` 是未提交搜索的状态（此时展示最近/热门或建议词）。
 */
enum class SearchTab {
    /** 还没提交搜索：展示最近搜索 + 热门搜索，或（有输入时）建议词。 */
    NONE,
    CHARACTERS,
    CREATORS,
}

/**
 * 搜索页状态（单 data class 原子替换，同 `ChatListState`/`HomeState`）。
 *
 * ## 三个「加载中」不是一回事
 *
 * | 字段 | 触发 | 表现 |
 * | --- | --- | --- |
 * | [isLoading] | 首查 / 翻页 | 列表空时整页 spinner |
 * | [isRefreshing] | 筛选/标签重查（P2） | **保留旧列表** + 半透明遮罩 |
 * | [isLoadingMore] | 翻页在途 | 不额外显示（RN 也只用 loading） |
 *
 * 合并成一个的表现是筛选时列表闪空一下（RN 专门为此拆了 `refreshing`，
 * `useSearch.ts:32-33` 有注释）。P1 不实现筛选，但 [isRefreshing] 先留位 ——
 * 结果列表的遮罩渲染在 P1 就位，P2 只接线。
 */
data class SearchState(
    /** 输入框当前内容（受控值，每次按键都变）。 */
    val query: String = "",
    val tab: SearchTab = SearchTab.NONE,
    /** 本次搜索词的来源，埋点 `search_type` 用。 */
    val searchWay: SearchWay = SearchWay.SEARCH,

    // ── 角色结果 ────────────────────────────────
    val characterResults: List<HomeFeedItem.Character> = emptyList(),
    val characterTotal: Int = 0,
    val characterOutcome: CharacterSearchOutcome = CharacterSearchOutcome.IDLE,
    /** 角色搜索 session_id；为空时卡片不带 searchTracking（对齐 RN 的条件）。 */
    val characterSessionId: String = "",
    /** 命中结果的标签聚合（P2 横滑标签栏用；P1 只存不渲染）。 */
    val tagAggIds: List<String> = emptyList(),

    // ── 创作者结果 ────────────────────────────────
    val creatorResults: List<CreatorResult> = emptyList(),
    val creatorTotal: Int = 0,
    val creatorSessionId: String = "",

    // ── 未搜索态的三块内容 ────────────────────────────────
    val recentSearches: List<String> = emptyList(),
    val popularTerms: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    /** 清空最近搜索的确认弹窗是否展示。 */
    val showClearHistoryDialog: Boolean = false,

    // ── 加载态 ────────────────────────────────
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,

    /** 一次性 Toast（搜索失败等），消费后清空。 */
    val toastKey: String? = null,
) {

    /**
     * 建议词列表：**原始输入恒在第一条**，且去重（`SuggestTags.tsx:54-63`）。
     *
     * 派生而非存字段 —— 输入框每次按键都会变，存字段要多一次写入。
     */
    val displaySuggestions: List<String>
        get() {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return emptyList()
            val seen = hashSetOf(trimmed.lowercase())
            val rest = suggestions.filter { seen.add(it.lowercase()) }
            return listOf(trimmed) + rest
        }

    /** 未搜索态是否展示「最近 + 热门」（`page.tsx:128`：无输入且不在 loading）。 */
    val showsDiscovery: Boolean
        get() = query.isEmpty() && !isLoading

    /** 空态「Create Now」按钮（判定见 [shouldShowCreateCharacterButton]）。 */
    val showsCreateCharacterButton: Boolean
        get() = shouldShowCreateCharacterButton(
            query = query,
            outcome = characterOutcome,
            // ⚠️ RN 传的是 `loading || refreshing`（`CharacterResultList.tsx:57`）——
            // 筛选重查在途时也不显示按钮，否则旧结果被遮罩盖住时会闪出来
            loading = isLoading || isRefreshing,
            resultCount = characterResults.size,
        )

    /** 角色结果是否还能翻页。 */
    val canLoadMoreCharacters: Boolean
        get() = characterResults.size < characterTotal

    /** 创作者结果是否还能翻页。 */
    val canLoadMoreCreators: Boolean
        get() = creatorResults.size < creatorTotal
}
