package ai.lightspeed.tipsy.shell.pages.search

import ai.lightspeed.tipsy.shell.BuildConfig
import ai.lightspeed.tipsy.shell.pages.settings.SettingsRow

/**
 * 搜索筛选的三组值（W3-P2，进度文档 §2.34）。
 *
 * 逐个核实 `constants/common.ts` 与 `FilterDrawer.tsx`。**值即前后端契约**，
 * 三处都不能"顺手改成更规范的写法"。
 */

/**
 * 性别（`SexList`，`constants/common.ts:99`）。
 *
 * ## ⚠️ 三处陷阱
 *
 * 1. **第四项是 `Non-binary`（带连字符）** —— Home 侧的枚举是 `NonBinary`
 *    （无连字符，`HomeGender`）。两套写法**不可复用**：拿 Home 那个当
 *    UI 文案会导致 i18n 查不到词条（key 就是英文原文），显示成 key 本身。
 * 2. **`All` 映射成「整键不发」**，不是发 `"all"` —— RN 的
 *    `default: genderOption.gender = undefined` 加 `delete params.gender`
 *    （`useSearch.ts:104-121,134`）。发 `all` 后端可能当成一个真实取值。
 * 3. UI 文案与后端值**不同**（`Female`→`female` 等），别直接把文案发出去。
 */
enum class SearchGender(
    /** UI 文案 = i18n key（key 就是英文原文）。 */
    val label: String,
    /** 后端值；null = **整键不发**。 */
    val wire: String?,
) {
    ALL("All", null),
    FEMALE("Female", "female"),
    MALE("Male", "male"),

    /** ⚠️ 带连字符，与 Home 的 `NonBinary` 不同写法。 */
    NON_BINARY("Non-binary", "other"),
    ;

    companion object {
        /** 顺序 = `SexList` 顺序，抽屉按它渲染。 */
        val ALL_OPTIONS: List<SearchGender> = entries.toList()

        val DEFAULT = ALL
    }
}

/**
 * 排序（`SearchSortingList` + `SearchSortingValueMap`，
 * `constants/common.ts:102-117`）。
 *
 * ## ⚠️ UI 文案 ≠ 后端枚举
 *
 * `Most Interacted`→`MostInteracted`（去空格），`Recommended` / `Latest`
 * 两端同名。把文案直接发出去，后端认不出会静默回落，表现为
 * 「选了排序但结果没变」。
 */
enum class SearchSorting(val label: String, val wire: String) {
    RECOMMENDED("Recommended", "Recommended"),
    MOST_INTERACTED("Most Interacted", "MostInteracted"),
    MOST_LIKED("Most Liked", "MostLiked"),
    MOST_FAVORITED("Most Favorited", "MostFavorited"),
    LATEST("Latest", "Latest"),
    ;

    companion object {
        val ALL_OPTIONS: List<SearchSorting> = entries.toList()

        val DEFAULT = RECOMMENDED

        /** 认不出的值回落 `Recommended`（对齐 RN 的 `?? 'Recommended'`）。 */
        fun fromWire(wire: String?): SearchSorting =
            entries.firstOrNull { it.wire == wire } ?: DEFAULT
    }
}

/**
 * 内容分级（`ContentRatingList`，`constants/common.ts:122`）。**值即契约**
 * （UI 文案与后端值同名，三项都是）。
 */
enum class SearchContentRating(val label: String) {
    ALL("All"),
    SFW("SFW"),
    NSFW("NSFW"),
    ;

    companion object {
        val ALL_OPTIONS: List<SearchContentRating> = entries.toList()

        val DEFAULT = ALL
    }
}

/**
 * 搜索筛选的完整状态（对齐 RN 的 `SearchFilterState`，
 * `useSearch.ts:15-19`）。
 *
 * @property tagIds 已选标签，**按选择顺序**（`SearchTagOrder` 的第一层优先级
 *   依赖这个顺序）。多选，交集筛选
 */
data class SearchFilter(
    val gender: SearchGender = SearchGender.DEFAULT,
    val sorting: SearchSorting = SearchSorting.DEFAULT,
    val contentRating: SearchContentRating = SearchContentRating.DEFAULT,
    val tagIds: List<String> = emptyList(),
) {
    /**
     * 是否有任何非默认筛选（决定筛选按钮是否显示"已激活"态）。
     *
     * ⚠️ **标签不算**：RN 的标签栏是独立的二级栏，不影响筛选按钮外观。
     */
    val hasActiveFilter: Boolean
        get() = gender != SearchGender.DEFAULT ||
            sorting != SearchSorting.DEFAULT ||
            contentRating != SearchContentRating.DEFAULT

    /**
     * 提交给接口的 content rating。
     *
     * ## ⚠️ 不显示分级的渠道**固定提交 `All`**，不是不发这个键
     *
     * `FilterDrawer.tsx:75-79` 注释原文：「不展示 Content Rating 的渠道
     * （iOS/GooglePlay）**固定提交 All**，与线上一致」。
     * 漏掉这条会让 GooglePlay 版把用户上次在侧载版选的分级带过去
     * （状态不持久化，实际不会跨渠道，但逻辑上要按渠道归一）。
     */
    fun wireContentRating(canPickContentRating: Boolean): String =
        if (canPickContentRating) contentRating.label else SearchContentRating.ALL.label

    companion object {

        /**
         * 分级筛选是否可选 —— **三重 gating**
         * （`FilterDrawer.tsx:55-57`）：
         * ```
         * Platform.OS === 'android' && !isGooglePlay && nsfw
         * ```
         * 壳天然满足第一条；第二条按 flavor 判（⚠️ 与 `Limitless` 开关的
         * `isAndroidAPK` **不同** —— 这里是「非 GooglePlay」，
         * **RuStore 也算**）；第三条是全局 nsfw 开关（后端权威的本地镜像）。
         *
         * @param nsfwEnabled `HomeFilterStore.readNsfw()` 的值
         */
        fun canPickContentRating(
            nsfwEnabled: Boolean,
            isGooglePlay: Boolean = BuildConfig.DOWNLOAD_CHANNEL ==
                SettingsRow.CHANNEL_GOOGLE_PLAY,
        ): Boolean = !isGooglePlay && nsfwEnabled
    }
}
