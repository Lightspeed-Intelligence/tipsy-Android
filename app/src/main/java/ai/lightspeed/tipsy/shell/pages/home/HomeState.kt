package ai.lightspeed.tipsy.shell.pages.home

/**
 * Home 的页面状态（方案 §8.1「收敛到单个 sealed state，**不要 12 个 StateFlow**」）。
 *
 * RN 侧 `home.tsx` 有 12 个 `useState` + 89 处 hook 调用。原生化时逐个搬成
 * StateFlow 会让"同时变化的两个值分两帧到达 UI"—— 那正是 RN 侧 For You
 * session 那个坑的成因（`home.tsx:535-539` 的注释记录：筛选条件与 session_id
 * 分两帧更新会让接口被调用两次，且第一次带旧 session）。
 *
 * 这里用**一个** data class，所有相关字段一次性原子替换。
 */
data class HomeState(
    val selectedSeries: HomeSeries = HomeSeries.default,
    val gender: HomeGender = HomeGender.ALL,
    /** 后端 `user.nsfw` 的本地镜像。**壳只读不写**（方案 §8.1 筛选持久化行）。 */
    val nsfw: Boolean = false,
    val items: List<HomeFeedItem> = emptyList(),
    /** 首屏加载中（列表为空且在请求）—— 与 [isRefreshing] / [isLoadingMore] 互斥展示。 */
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** 已到底：不再触发翻页。 */
    val hasReachedEnd: Boolean = false,
    /**
     * 首屏失败的错误文案（i18n key 或后端 msg）。
     *
     * ⚠️ **只在列表为空时展示**。已有数据时翻页失败不清列表、不弹全屏错误 ——
     * 那会把用户已经在看的内容抹掉（方案 §8.4「禁止全量替换」的同一精神）。
     */
    val errorMessage: String? = null,
) {
    /** 空态：请求已结束、无错误、无数据。 */
    val isEmpty: Boolean
        get() = items.isEmpty() && !isInitialLoading && !isRefreshing && errorMessage == null

    /**
     * 空态文案 key，**null 表示不显示任何文案**。
     *
     * ⚠️ 只有 Following 有空态文案（`home.tsx:1885-1901`：其余系列
     * `renderListEmptyComponent` 直接 `return null`）。
     * 给其他系列补一个「No results」看起来更完整，但那是**与 RN 的行为偏离** ——
     * 空 For You 通常意味着请求失败或筛选过窄，RN 刻意留白等下一次刷新。
     */
    val emptyMessageKey: String?
        get() = if (selectedSeries == HomeSeries.FOLLOWING) {
            "Empty. You haven't followed anyone yet."
        } else {
            null
        }
}

/**
 * 一个系列的分页游标 + 会话。
 *
 * ## session 语义（方案 §8.1「session 语义」行，最容易写错的一处）
 *
 * - **切性别 / 切标签 / 下拉刷新 / 切语言 → 换新 session id**
 * - **翻页不换**
 *
 * 写错的两个方向都有明确症状：
 * - 翻页也换 → 后端按 session 锁推荐池，每页都是新池 → **重复内容刷屏**
 * - 筛选不换 → 池被锁在旧筛选条件上 → **切了性别列表不变**（`home.tsx:535-539`
 *   记录的正是这个真实 bug）
 *
 * `filterKey` 是「哪些条件变化要换 session」的判据。For You 的 key 含
 * gender + tags + contentTypes（`home.tsx:539-547`），**不含 nsfw 与语言** ——
 * 那两个走另一条兜底路径（RN 用 `useDirtyRef` 在离开/返回首页时重置）。
 * 这里统一进 filterKey：壳内 Home 是常驻 Fragment，没有"离开再回来"的挂载周期
 * 可依赖，不进 key 会让改语言后推荐池永远不换。
 */
internal data class SeriesCursor(
    /** 下一页页码（0-based）。 */
    val nextPage: Int = 0,
    val sessionId: String,
    /** 生成本 session 时的筛选条件指纹。 */
    val filterKey: String,
    val hasReachedEnd: Boolean = false,
    /**
     * 连续「翻页去重后无新增」的次数。
     *
     * For You 的翻页会返回当前 session 已展示过的角色（RN 实测每页 1~3 条重复，
     * 方案 §8.4 第 3 条）。全量去重后若本页无新 item，**必须主动续拉**，
     * 否则列表停在半屏且不再触发加载。
     *
     * ⚠️ 必须限次（RN 限连续 3 页）—— 不限次时异常数据会形成**无限请求循环**。
     */
    val emptyAfterDedupeStreak: Int = 0,
)
