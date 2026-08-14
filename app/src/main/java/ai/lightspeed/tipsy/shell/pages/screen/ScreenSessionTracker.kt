package ai.lightspeed.tipsy.shell.pages.screen

import ai.lightspeed.tipsy.shell.analytics.Analytics
import java.util.UUID

/**
 * 大屏页的**会话埋点**（`screen.tsx:350-440` + `447-540`）。
 *
 * ## 会话是一段「页面可见且 App 在前台」的时间
 *
 * 三个事件都挂在同一个 `session_id`（uuid）上，起止时机是**两条轴的交集**：
 *
 * | 时机 | 动作 | RN 出处 |
 * | --- | --- | --- |
 * | 页面获得焦点 | start + 立即报当前卡曝光 | `:447-451` |
 * | 页面失去焦点 | **end** | `:454` |
 * | 前台 → 后台（页面仍聚焦） | **end** | `:523-526` |
 * | 后台 → 前台（页面仍聚焦） | start + 报曝光 | `:528-532` |
 *
 * ⚠️ **切后台要 end，回前台要重开一个新 session** —— 不是暂停。
 * 只挂 Fragment 生命周期会漏掉「按 Home 键出去再回来」那条，
 * 表现为一个跨越几小时的畸形长会话（后端按 session 算停留时长）。
 *
 * ## 三处去重，粒度各不同
 *
 * - [trackCardEvent] 的 `card_id` 去重是**每类事件一个集合**
 *   （`trackedCardIdsRef` 由调用方传入，`:391`）—— 曝光去重不影响点赞去重
 * - [trackInputClick] 是**一会话一次**（`hasTrackedInputClickRef`，`:430`）
 * - 会话重开时**全部重置**（`resetHomeSessionTrackingState`）——
 *   所以回前台后同一张卡会**再报一次**曝光，这是有意的
 *
 * ## ⚠️ 无会话时静默丢弃事件
 *
 * 所有 track 方法开头都是 `if (!sessionId) return`（`:399` 等）。
 * 不是排队补发 —— 没有会话上下文的卡片事件在后端无法归属。
 */
class ScreenSessionTracker(
    /** 注入是为了测试；生产用 [UUID.randomUUID]。 */
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
    /** 注入埋点出口，测试可断言。 */
    private val track: (String, Map<String, String>) -> Unit = { name, params ->
        Analytics.track(name, params)
    },
) {

    /** 当前会话 id；null = 无会话（此时所有事件静默丢弃）。 */
    var sessionId: String? = null
        private set

    /** 按事件名分表的 card_id 去重集合（曝光与点赞互不影响）。 */
    private val trackedCardIds = mutableMapOf<String, MutableSet<Int>>()

    /** `home_input_click` 一会话一次。 */
    private var hasTrackedInputClick = false

    /**
     * 开会话。**幂等** —— 已有会话时直接返回（`:351` `if (homeSessionIdRef.current) return`）。
     *
     * 不幂等的后果：焦点抖动（Fragment 重建、抽屉开合）会开出多个会话，
     * 后端看到一堆 1 秒会话。
     */
    fun startSession() {
        if (sessionId != null) return
        val id = sessionIdFactory()
        sessionId = id
        resetSessionState()
        track(EVENT_SESSION_START, mapOf(PARAM_SESSION_ID to id))
    }

    /**
     * 结束会话。无会话时静默返回。
     *
     * ⚠️ 结束后**清去重状态** —— 下次开会话时同一张卡要重报曝光。
     */
    fun endSession() {
        val id = sessionId ?: return
        track(EVENT_SESSION_END, mapOf(PARAM_SESSION_ID to id))
        sessionId = null
        resetSessionState()
    }

    /**
     * 卡片事件（曝光 / 点赞 / 评论 / 分享）。
     *
     * @param index 列表下标 —— `card_id` 是 **index + 1**（`tracking.ts:8`），
     *   不是下标本身。发下标会让 card_id 整体偏移 1
     * @return 是否真的报了（false = 无会话或已去重）
     */
    fun trackCardEvent(
        event: ScreenCardEvent,
        index: Int,
        item: ScreenFeedItem?,
    ): Boolean {
        val id = sessionId ?: return false
        val cardId = cardIdOf(index)
        val seen = trackedCardIds.getOrPut(event.wire) { mutableSetOf() }
        if (!seen.add(cardId)) return false
        track(
            event.wire,
            mapOf(
                PARAM_SESSION_ID to id,
                PARAM_CARD_ID to cardId.toString(),
                // ⚠️ card_type 是**另一套名字**（gif / single_character / showcase），
                // 不是 media_source_type，见 ScreenCardType
                PARAM_CARD_TYPE to (item?.cardType ?: ScreenCardType.SHOWCASE).wire,
                PARAM_SCREEN_BUCKET to screenBucketOf(index).toString(),
            ),
        )
        return true
    }

    /**
     * 输入框点击 —— **一会话只报一次**（`:430`）。
     *
     * P1 的 `input_type` 恒 `text`（`:434`；语音输入属二期）。
     * ⚠️ RN 那里也是硬编码 `'text'`，尽管 `getHomeInputType` 支持 voice ——
     * 照抄，不要「顺手接上」那个函数。
     */
    fun trackInputClick(): Boolean {
        val id = sessionId ?: return false
        if (hasTrackedInputClick) return false
        hasTrackedInputClick = true
        track(
            EVENT_INPUT_CLICK,
            mapOf(PARAM_SESSION_ID to id, PARAM_INPUT_TYPE to INPUT_TYPE_TEXT),
        )
        return true
    }

    /**
     * 归因缺失诊断（`screen_recommend_attribution_missing`，`:812-820`）。
     *
     * ⚠️ **不带 session_id**（RN 那里只发 page / endpoint / missing_fields）——
     * 它是请求级诊断，不是会话内的用户行为。
     * 方案 §8.1：「说明归因会丢，要保留」。
     */
    fun trackAttributionMissing(page: Int, endpoint: ScreenEndpoint, missingFields: List<String>) {
        if (missingFields.isEmpty()) return
        track(
            EVENT_ATTRIBUTION_MISSING,
            mapOf(
                PARAM_PAGE to page.toString(),
                PARAM_ENDPOINT to endpoint.trackingPath,
                PARAM_MISSING_FIELDS to missingFields.joinToString(","),
            ),
        )
    }

    private fun resetSessionState() {
        trackedCardIds.clear()
        hasTrackedInputClick = false
    }

    companion object {
        /** `card_id` = **index + 1**（`tracking.ts:7-9`）。 */
        fun cardIdOf(index: Int): Int = index + 1

        /**
         * `screen_bucket`：前 4 张是 1，其余是 2（`tracking.ts:11-13`）。
         *
         * ⚠️ 判据是 `index < 4`（下标，不是 card_id）—— 用 card_id 判
         * 会让第 4 张卡落错桶。
         */
        fun screenBucketOf(index: Int): Int = if (index < 4) 1 else 2

        const val EVENT_SESSION_START = "home_session_start"
        const val EVENT_SESSION_END = "home_session_end"
        const val EVENT_INPUT_CLICK = "home_input_click"
        const val EVENT_ATTRIBUTION_MISSING = "screen_recommend_attribution_missing"

        private const val PARAM_SESSION_ID = "session_id"
        private const val PARAM_CARD_ID = "card_id"
        private const val PARAM_CARD_TYPE = "card_type"
        private const val PARAM_SCREEN_BUCKET = "screen_bucket"
        private const val PARAM_INPUT_TYPE = "input_type"
        private const val PARAM_PAGE = "page"
        private const val PARAM_ENDPOINT = "endpoint"
        private const val PARAM_MISSING_FIELDS = "missing_fields"
        private const val INPUT_TYPE_TEXT = "text"
    }
}

/** 四个卡片级事件（`:377-381`）。**事件名是契约**。 */
enum class ScreenCardEvent(val wire: String) {
    EXPOSURE("home_card_exposure"),
    LIKE_CLICK("home_card_like_click"),
    COMMENT_CLICK("home_card_comment_click"),
    SHARE_CLICK("home_card_share_click"),
}
