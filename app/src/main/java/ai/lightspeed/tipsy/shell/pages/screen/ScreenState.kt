package ai.lightspeed.tipsy.shell.pages.screen

/**
 * 大屏页状态（单 data class 原子替换，同其它页）。
 *
 * @property items 竖向翻页的条目（已去重、已附归因）
 * @property currentIndex 当前可见卡的下标 —— 埋点的 `card_id` / `screen_bucket`
 *   都由它算，且决定 P2 的播放器 ±1 窗口
 * @property endpoint 本次会话解析出的端点；null = AB 还没定（此时不发请求）
 * @property isRetryable 请求超时/失败后展示重试（RN 有 **5 秒超时**就置 retry，
 *   `screen.tsx:765-771` —— 不是等 HTTP 超时）
 */
data class ScreenState(
    val items: List<ScreenFeedItem> = emptyList(),
    val currentIndex: Int = 0,
    val endpoint: ScreenEndpoint? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isRetryable: Boolean = false,
    /** 已到底：不再触发翻页。 */
    val hasReachedEnd: Boolean = false,
) {
    /** 当前卡；越界返回 null（列表空或正在刷新时可能发生）。 */
    val currentItem: ScreenFeedItem? get() = items.getOrNull(currentIndex)

    /** 首屏骨架：无内容且在加载。 */
    val showsInitialLoading: Boolean get() = items.isEmpty() && isLoading

    /** 空态：加载完成但没有内容，且不是错误态。 */
    val showsEmpty: Boolean
        get() = items.isEmpty() && !isLoading && !isRetryable
}

/**
 * 首屏缓存的**签名**（`showcaseCacheSignature`，`screen.tsx:216-234`）。
 *
 * ## 七个维度全进签名 —— 任一变化即缓存失效
 *
 * `ownerUserId`（空则 `anonymous`）/ `endpoint` / `nsfw` / `gender` /
 * `languageCode` / `tagIds`（**排序后**）/ `contentType`。
 *
 * ⚠️ 与 Home 的 `HomeForYouCache` 设计**不同**：那边是「信封带 authScope +
 * TTL 门禁」，这里是「**维度编进 key**」。RN 自己的新代码走的是后者
 * （§2.25 记过：`profileCreatedListCache` 也是这个形状）——
 * 签名不匹配直接当没缓存，不必判 TTL。
 *
 * ⚠️ `tagIds` **必须排序**（`:223` `[...selectedTags.tags].sort()`）——
 * 不排会让「选 A 再选 B」与「选 B 再选 A」得到不同签名，
 * 缓存命中率白降一半，且**不报错**。
 *
 * ⚠️ `ownerUserId` 空串要归一成 `anonymous`（`:220`）—— 直接用空串会让
 * 游客与「拿不到 id 的登录态」共用同一份缓存。
 */
object ScreenCacheSignature {

    /** 游客的 owner 占位（`:220` `ownerUserId || 'anonymous'`）。 */
    const val ANONYMOUS = "anonymous"

    /**
     * 拼签名。
     *
     * 用 `|` 分隔而不是 JSON：RN 那边是 `JSON.stringify`，但签名只做**相等比较**
     * 、不被解析，所以格式无需对等（跨端也不共享这份缓存 —— 壳有自己的 key，
     * 同 §2.30 ChatList 种子缓存的理由）。
     */
    fun of(
        ownerUserId: String?,
        endpoint: ScreenEndpoint,
        nsfw: Boolean,
        gender: String?,
        languageCode: String,
        tagIds: List<String>,
        contentType: Int?,
    ): String = listOf(
        ownerUserId?.takeIf { it.isNotBlank() } ?: ANONYMOUS,
        endpoint.name,
        nsfw.toString(),
        gender ?: "",
        languageCode,
        // ⚠️ 排序，见类注释
        tagIds.sorted().joinToString(","),
        contentType?.toString() ?: "",
    ).joinToString("|")
}
