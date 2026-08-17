package ai.lightspeed.tipsy.shell.pages.screen

import ai.lightspeed.tipsy.shell.pages.home.HomeCacheStorage
import android.util.Log
import org.json.JSONObject

/**
 * 首屏缓存的持久化实现（对应 `useShowcaseFirstScreenCache.ts` 76 行）。
 *
 * ## ⚠️ 用壳自己的 key，**不读写 RN 的那个 MMKV 实例**
 *
 * RN 侧是独立实例 `createMMKV({ id: 'showcase-first-screen-cache' })`
 * （`:5-7`），存的是 `ScreenFeedItem` 的完整 JSON。壳**不共用**它：
 * 那份数据的形状由 RN 的 `PublicVideo` 类型决定（30+ 字段），
 * 壳只解析其中 18 个 —— 共用会让「壳写、RN 读」时字段缺失，
 * 而 RN 侧读到 undefined 不报错、只是卡片渲染不全。
 *
 * 同 §2.30 ChatList 种子缓存的理由（壳的 `shell-chat-list-seed` 也不读
 * RN 的 `chat-list-cache`）。
 *
 * ## 签名不匹配即当没缓存
 *
 * `:24-27` 的 `parsed.signature !== signature` → 返回 null。
 * **不判 TTL** —— 维度已经编进签名了（见 [ScreenCacheSignature]），
 * 与 `HomeForYouCache` 的「信封 + TTL」是两种设计。
 *
 * ## ⚠️ 存之前剥掉归因
 *
 * `:29-32` 与 `:39-41` 都 `delete safeItem.recommendationSource` ——
 * 归因是**请求级**的（request_id/session_id 属那一次请求），
 * 存下来下次读出就是过期归因。`mergeShowcaseFirstScreenFeed` 会重新绑定
 * 本次响应的归因，前提是这里存的那份**没有**旧归因。
 */
class ScreenFirstScreenCacheStore(
    /**
     * 复用 Home 的存储接缝（[HomeCacheStorage]）而不是直接吃
     * `LegacyMmkvStore` —— 后者是 final class，测试无法替身。
     * 两处的需求完全相同（getString/putString），没必要造第二个接口。
     */
    private val store: HomeCacheStorage,
    private val logWarn: (String, Throwable?) -> Unit = { msg, t -> Log.w(TAG, msg, t) },
) : ScreenFirstScreenCache {

    override fun get(signature: String): ScreenFeedItem? {
        val raw = store.getString(CACHE_KEY) ?: return null
        return try {
            val envelope = JSONObject(raw)
            // 签名不匹配 → 当没缓存（维度已编进签名，不判 TTL）
            if (envelope.optString(FIELD_SIGNATURE) != signature) return null
            val itemJson = envelope.optJSONObject(FIELD_ITEM) ?: return null
            ScreenFeedItem.parse(itemJson)
        } catch (e: Throwable) {
            // 解析失败当没缓存 —— 缓存损坏不该让页面挂掉
            logWarn("首屏缓存解析失败，当作未命中", e)
            null
        }
    }

    override fun put(signature: String, item: ScreenFeedItem) {
        try {
            val envelope = JSONObject()
                .put(FIELD_SIGNATURE, signature)
                .put(FIELD_SAVED_AT, System.currentTimeMillis())
                // ⚠️ 写回的是**重建的**业务字段，不含归因（见类注释）
                .put(FIELD_ITEM, item.toCacheJson())
            store.putString(CACHE_KEY, envelope.toString())
        } catch (e: Throwable) {
            // 写失败只记日志：持久化失败不该影响本次展示
            logWarn("首屏缓存写入失败", e)
        }
    }

    companion object {
        private const val TAG = "ScreenFirstScreenCache"

        /** 壳自己的 key（**不是** RN 的 `showcaseFirstScreenCache`）。 */
        const val CACHE_KEY = "shell-screen-first-item"

        private const val FIELD_SIGNATURE = "signature"
        private const val FIELD_SAVED_AT = "savedAt"
        private const val FIELD_ITEM = "item"
    }
}

/**
 * 把条目序列化成**接口同形**的 JSON，让 [ScreenFeedItem.parse] 能原样读回。
 *
 * 刻意不用 kotlinx.serialization 造第二套形状：读路径只有一个解析器，
 * 存成接口形状意味着「存→读」走的是与网络完全相同的代码路径，
 * 少一类「存得下但读不回」的 bug。
 *
 * ⚠️ **不写归因** —— 它是请求级的，见 [ScreenFirstScreenCacheStore] 类注释。
 */
private fun ScreenFeedItem.toCacheJson(): JSONObject {
    val character = JSONObject()
        .put("character_id", characterId)
        .put("introduction", tagline)
        .put("greeting", greeting)
    nickname?.let { character.put("nickname", it) }
    creatorId?.let { character.put("creator_id", it) }
    primaryColor?.let { character.put("img_primary_color", it) }
    character.put("is_translated", isTranslated)
    characterType?.let { character.put("character_type", it) }
    contentType?.let { character.put("content_type", it) }

    // ⚠️ 三形态要能被 parse 重建，所以按**原形态**回填对应字段：
    // showcase → greeting_video.video_url；animated → animated_image_url；
    // static → image_url。回填错会让读回来的形态与存进去的不同
    when (mediaSourceType) {
        ScreenMediaSourceType.ANIMATED_IMAGE ->
            backgroundUrl?.let { character.put("animated_image_url", it) }
        ScreenMediaSourceType.STATIC_IMAGE,
        ScreenMediaSourceType.SHOWCASE,
        -> Unit
    }
    // image_url 是静图形态的背景，也是 thumbnail 的回落 —— 两种形态都要有
    thumbnailUrl?.let { character.put("image_url", it) }
    avatarUrl?.let { character.put("face_url", it) }

    val root = JSONObject().put("character", character)
    if (mediaSourceType == ScreenMediaSourceType.SHOWCASE && backgroundUrl != null) {
        root.put(
            "greeting_video",
            JSONObject().put("video_url", backgroundUrl).apply {
                thumbnailUrl?.let { put("cover_url", it) }
            },
        )
    }
    root.put(
        "creator",
        JSONObject().apply {
            creatorId?.let { put("user_id", it) }
            creatorNickname?.let { put("nickname", it) }
            creatorAvatarUrl?.let { put("avatar_url", it) }
        },
    )
    root.put(
        "stats",
        JSONObject()
            .put("like_counts", likeCount)
            .put("comment_count", commentCount)
            .put("total_messages", totalMessages),
    )
    return root
}
