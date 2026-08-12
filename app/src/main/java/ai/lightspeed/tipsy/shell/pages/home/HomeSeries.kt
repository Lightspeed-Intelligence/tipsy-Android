package ai.lightspeed.tipsy.shell.pages.home

/**
 * Home 的系列 Tab（方案 §8.1 Home 行）。
 *
 * ## ⚠️ Android **显示 World**，iOS 不显示
 *
 * 开放问题 §12.4 问「Home 是否包含 World 系列」—— 代码里已有答案：
 * `home.tsx:505-511` 的 filter 条件是
 * `series !== 'Multi-character' && (Platform.OS === 'android' || series !== 'World')`。
 * 即 **Multi-character 两端都隐藏，World 只在 Android 显示**。
 * iOS 壳的 `HomeAPI.swift` 因此只有 5 个 case，Android 是 6 个。
 *
 * World 的列表走 `/game/public/projects`、点进去是 WebView（SimulatorGame 不迁，
 * 方案 §8.1）。本包只做列表展示，点击落 Router 的显式拒绝（未启用）。
 *
 * ## 三组名字不能混
 *
 * | 用途 | 值 | 出处 |
 * | --- | --- | --- |
 * | i18n key / 埋点 scene | `For You` `Weekly Picks` … | `HomeSeriesState` 字面量 |
 * | 接口 `sorting` 参数 | `WeeklyPicks` `Popular` `New` `FollowersCharacterNew` | `constants/common.ts:35-43` |
 * | 埋点 `tab_type` | `for_you` `trending` `popular` `latest` `following` `worlds` | `home.tsx:160-166` |
 *
 * ⚠️ 三者**不是同一个值的大小写变体**：`All-Time Faves` 的 sorting 是 `Popular`
 * 而 tab_type 是 `popular`，`Weekly Picks` 的 sorting 是 `WeeklyPicks` 而
 * tab_type 是 `trending`。凭一个推另一个必错。
 */
enum class HomeSeries(
    /** i18n key，同时是埋点的 `scene`（RN 传的是 `selectedSeries` 原文，非本地化文案）。 */
    val key: String,
    /** `/character/get/public_list` 的 `sorting`；null 表示不走该接口。 */
    val sorting: String?,
    /** 埋点 `discover_page_tab_click` / `discover_subpage_exposure` 的 `tab_type`。 */
    val tabType: String,
) {
    FOR_YOU("For You", null, "for_you"),
    WEEKLY_PICKS("Weekly Picks", "WeeklyPicks", "trending"),
    WORLD("World", null, "worlds"),
    NEW_RELEASES("New Releases", "New", "latest"),
    ALL_TIME_FAVES("All-Time Faves", "Popular", "popular"),
    FOLLOWING("Following", "FollowersCharacterNew", "following"),
    ;

    /**
     * 该系列是否携带标签筛选。
     *
     * `Following` **不带**（`useHomeCharacterLists.ts:89` 的 `isFollowing ? [] : tags`）——
     * 带上会让「关注列表」被标签过滤掉大半，且 UI 上 Following 没有筛选入口
     * （`home.tsx:1283` 隐藏了 filter 按钮），用户无从发现自己被筛了。
     */
    val supportsTagFilter: Boolean get() = this != FOLLOWING && this != WORLD

    companion object {
        /**
         * Tab 行的显示顺序。
         *
         * 顺序照 `Object.keys(HomeSeries)` 的声明序（`constants/common.ts:35-43`），
         * 过滤掉 `Multi-character`。**不要按字母或「好看」重排** ——
         * `discover_page_tab_click` 的位置归因依赖这个顺序。
         */
        val displayOrder: List<HomeSeries> = listOf(
            FOR_YOU, WEEKLY_PICKS, WORLD, NEW_RELEASES, ALL_TIME_FAVES, FOLLOWING,
        )

        /** 默认选中项（`home.tsx:349-350` `useState<HomeSeriesState>('For You')`）。 */
        val default: HomeSeries = FOR_YOU
    }
}

/**
 * 性别筛选（`constants/common.ts:91-96` 的 `HomeGender`）。
 *
 * ⚠️ **i18n key 与枚举名不一致**：状态值是 `NonBinary`（`HomeGenderState`），
 * 但 26 个 locale 文件里的 key 是 **`Non-binary`**（带连字符）。
 * 用 `NonBinary` 查表会找不到、回落到 key 本身 —— 表现为所有语言都显示
 * "NonBinary" 这个非文案字符串。iOS 的 `GenderFilter.swift:26` 注释也记了这条。
 */
enum class HomeGender(
    /** 持久化值（`config-persist-storage` 的 `gender` 字段，即 `HomeGenderState`）。 */
    val storedValue: String,
    /** i18n key —— 见类注释，与 [storedValue] 刻意不同。 */
    val i18nKey: String,
    /** 接口 `gender` 参数；null = 不传该字段（不是传空串）。 */
    val apiValue: String?,
) {
    ALL("All", "All", null),
    MALE("Male", "Male", "male"),
    FEMALE("Female", "Female", "female"),
    NON_BINARY("NonBinary", "Non-binary", "other"),
    ;

    companion object {
        /** 面板行序（`Object.keys(HomeGender)`：All / Male / Female / NonBinary）。 */
        val displayOrder: List<HomeGender> = listOf(ALL, MALE, FEMALE, NON_BINARY)

        /**
         * 从持久化值解析。
         *
         * ⚠️ RN 的初始值是**空串**（`config_persist.ts:227` `gender: '' as HomeGenderState`），
         * 不是 `'All'`。空串与未知值都回落 [ALL] —— 而 `ALL.apiValue` 是 null，
         * 与 RN 的 `genderMap['']` → undefined 行为一致（都不传该字段）。
         */
        fun fromStored(raw: String?): HomeGender =
            displayOrder.firstOrNull { it.storedValue == raw } ?: ALL
    }
}
