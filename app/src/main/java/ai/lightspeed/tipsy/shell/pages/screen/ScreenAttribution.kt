package ai.lightspeed.tipsy.shell.pages.screen

/**
 * 推荐归因（`createScreenRecommendationSource` + `createAttributedScreenItems`，
 * `models.ts:102-132` + `recommendationAttribution.ts` 69 行）。
 *
 * RN 侧有 **92 行现成单测**（`recommendationAttribution.test.ts`），
 * 本实现照它对拍（方案 §8.2）。
 *
 * ## 四个字段全非空才成立，否则整个归因为 null
 *
 * `request_id` / `session_id` / `character_id` 三者任一空 → 归因为 null；
 * `position` 必须是**非负整数**；`owner_user_id` 空 → 也为 null
 * （`models.ts:126` `if (!attribution || !owner_user_id) return null`）。
 *
 * ⚠️ 归因为 null **不是错误** —— distribution 端点本就没有这些字段。
 * 只有 recommendation 端点缺字段才要报诊断事件（见
 * [ScreenAttributionResult.missingFields]）。
 */
data class ScreenAttribution(
    val requestId: String,
    val sessionId: String,
    val characterId: String,
    /** 全局位置 = `page * pageSize + rawIndex`，见 [attribute] 的 ⚠️。 */
    val position: Int,
    val ownerUserId: String,
) {
    companion object {

        /**
         * 给一页条目附归因。
         *
         * ## ⚠️ `position` 用**去重前**的下标
         *
         * `recommendationAttribution.ts:55` 是 `page * pageSize + rawIndex`，
         * 而 `rawIndex` 来自 `list.entries()` —— 同一个循环里
         * `seenCharacterIds` 会 `continue` 掉重复项。所以**去重后第 3 条的
         * position 可能是 4**。
         *
         * 用去重后的下标会让归因位置与后端记录对不上（后端按它算 CTR），
         * 而两端都不报错。照抄。
         *
         * ## distribution 端点不附归因、也不报缺字段
         *
         * 只有 [ScreenEndpoint.RECOMMENDATION] 才检查并上报缺失
         * （`recommendationAttribution.ts:36-40`）。
         */
        fun attribute(
            /**
             * ⚠️ **元素可空**：null 是解析失败的占位，用来保住 `rawIndex`
             * 的原始语义（见 `ScreenPage.parse`）。本方法负责过滤它们。
             */
            items: List<ScreenFeedItem?>,
            endpoint: ScreenEndpoint,
            requestId: String?,
            sessionId: String?,
            ownerUserId: String?,
            page: Int,
            pageSize: Int,
        ): ScreenAttributionResult {
            val normalizedRequestId = requestId?.trim().orEmpty()
            val normalizedSessionId = sessionId?.trim().orEmpty()
            val normalizedOwnerUserId = ownerUserId?.trim().orEmpty()

            val missing = buildList {
                if (endpoint == ScreenEndpoint.RECOMMENDATION) {
                    if (normalizedRequestId.isEmpty()) add(FIELD_REQUEST_ID)
                    if (normalizedSessionId.isEmpty()) add(FIELD_SESSION_ID)
                    if (normalizedOwnerUserId.isEmpty()) add(FIELD_OWNER_USER_ID)
                }
            }

            val seen = HashSet<String>()
            val attributed = ArrayList<ScreenFeedItem>(items.size)
            // ⚠️ rawIndex 是**原始数组下标** —— 无效条目（null）与重复项
            // 都照样占一个号。RN 单测钉死：[无id, a, a, b] + page1/size10
            // → a=11、b=13（不是 10、11）
            items.forEachIndexed { rawIndex, item ->
                if (item == null) return@forEachIndexed
                if (!seen.add(item.characterId)) return@forEachIndexed
                val attribution = if (endpoint == ScreenEndpoint.RECOMMENDATION) {
                    create(
                        requestId = normalizedRequestId,
                        sessionId = normalizedSessionId,
                        characterId = item.characterId,
                        position = page * pageSize + rawIndex,
                        ownerUserId = normalizedOwnerUserId,
                    )
                } else {
                    null
                }
                attributed += item.copy(attribution = attribution)
            }
            return ScreenAttributionResult(items = attributed, missingFields = missing)
        }

        /**
         * 构造一条归因；任一必填为空或 [position] 为负 → null
         * （`models.ts:80-92`）。
         */
        fun create(
            requestId: String,
            sessionId: String,
            characterId: String,
            position: Int,
            ownerUserId: String,
        ): ScreenAttribution? {
            if (requestId.isEmpty() || sessionId.isEmpty() || characterId.isEmpty()) return null
            if (position < 0) return null
            if (ownerUserId.isEmpty()) return null
            return ScreenAttribution(
                requestId = requestId,
                sessionId = sessionId,
                characterId = characterId,
                position = position,
                ownerUserId = ownerUserId,
            )
        }

        const val FIELD_REQUEST_ID = "request_id"
        const val FIELD_SESSION_ID = "session_id"
        const val FIELD_OWNER_USER_ID = "owner_user_id"
    }
}

/**
 * 归因结果。
 *
 * @property missingFields 缺失的归因字段；**非空时要报
 *   `screen_recommend_attribution_missing`**（`screen.tsx:812-820`）。
 *   方案 §8.1 明写「最后一个是诊断事件，说明归因会丢，要保留」——
 *   不报的话归因静默失效，没人会发现
 */
data class ScreenAttributionResult(
    val items: List<ScreenFeedItem>,
    val missingFields: List<String>,
)
