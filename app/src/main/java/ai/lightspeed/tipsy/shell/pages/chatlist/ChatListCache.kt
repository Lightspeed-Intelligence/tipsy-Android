package ai.lightspeed.tipsy.shell.pages.chatlist

import ai.lightspeed.tipsy.shell.auth.LegacyMmkvStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话列表冷启动缓存（RN `useChatListCache.ts` + 方案 §4.6 信封）。
 *
 * ## 与 [ai.lightspeed.tipsy.shell.pages.home.HomeForYouCache] 同一决策：壳自己的 key + 信封
 *
 * RN 写的是独立 MMKV 实例 `chat-list-cache` 的 `chattedListCache`，值是
 * **裸页数组**（无 authScope / TTL / version，logout 不清 —— 已核实全仓无清理代码）。
 * 方案 §4.6 不继承这三点：壳写默认实例下自己的 key [CACHE_KEY]，信封
 * `{version, authScope, savedAt, items}`。代价同 Home：首装壳版用户没有种子，
 * 换来换号后不显示上一账号的会话列表（会话列表**全是账号私有数据**，
 * 这里跨账号泄漏比 Home 推荐流严重得多）。
 *
 * ## 指纹只比 authScope，**语言刻意不比**（方案 §8.1 ChatList「缓存/预取」行）
 *
 * 两阶段 i18n 下首屏读到的是瞬态语言，做门禁会永久拒绝缓存
 * （iOS「二启永远无种子」的教训）。语言真变了靠 `onLanguageSettled` 重拉自愈。
 *
 * ## 只存第一页
 *
 * 种子的用途是「冷启动先有内容」，首页 50 条足够铺满首屏；
 * 存多页会让信封膨胀且翻页游标对不上（缓存恢复后 nextPage 恒为 1）。
 */
class ChatListCache(
    private val store: LegacyMmkvStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val logWarn: (String, Throwable?) -> Unit = { m, t -> Log.w(TAG, m, t) },
) {

    /**
     * 读种子。任一门禁不过返回 null —— 宁可多一次 loading，不显错数据。
     *
     * @param authScope `user:<id>`；ChatList 无游客态，未登录时调用方不该来读
     */
    fun read(authScope: String): ChatThreadPage? {
        val raw = store.getString(CACHE_KEY) ?: return null
        val envelope = runCatching { JSONObject(raw) }
            .onFailure { logWarn("会话列表缓存不是合法 JSON，丢弃", it) }
            .getOrNull() ?: return null

        if (envelope.optInt(FIELD_VERSION) != VERSION) return null
        if (envelope.optString(FIELD_AUTH_SCOPE) != authScope) return null
        val savedAt = envelope.optLong(FIELD_SAVED_AT, -1L)
        val age = nowMs() - savedAt
        // 负数（时钟回退）也作废，同 HomeForYouCache
        if (savedAt <= 0L || age < 0 || age > TTL_MS) return null

        val items = envelope.optJSONArray(FIELD_ITEMS) ?: return null
        // 复用在线路径的解析器：包一层 {list, total, has_more} 喂给 parse。
        // has_more 恒 true —— 种子只是首屏占位，真实到底判定等在线首页回来
        val page = runCatching {
            ChatThreadPage.parse(
                JSONObject()
                    .put("list", items)
                    .put("total", envelope.optLong(FIELD_TOTAL))
                    .put("has_more", true),
            )
        }.onFailure { logWarn("会话列表缓存解析失败，丢弃", it) }.getOrNull() ?: return null
        return page.takeIf { it.items.isNotEmpty() }
    }

    /**
     * 写种子（首页在线数据到达后调用）。
     *
     * 存**原始响应的 list 片段**而不是模型序列化 —— 与 HomeForYouCache 同理：
     * 读写都走 [ChatThreadPage.parse]，不造第二个真值来源。
     * 所以这里接收原始 `data`（信封的 data 字段），不接收 [ChatThreadPage]。
     */
    fun write(authScope: String, rawListJson: JSONArray, total: Long) {
        val envelope = JSONObject()
            .put(FIELD_VERSION, VERSION)
            .put(FIELD_AUTH_SCOPE, authScope)
            .put(FIELD_SAVED_AT, nowMs())
            .put(FIELD_TOTAL, total)
            .put(FIELD_ITEMS, rawListJson)
        if (!store.putString(CACHE_KEY, envelope.toString())) {
            logWarn("会话列表缓存写入失败（MMKV 不可用）", null)
        }
    }

    /** 登出时清（账号私有数据，方案 §4.6；RN 不清是已记录的不继承项）。 */
    fun clear() {
        store.putString(CACHE_KEY, "")
    }

    companion object {
        private const val TAG = "ChatListCache"

        /** 壳自己的 key，与 RN 的 `chat-list-cache` 实例并存互不读写。 */
        const val CACHE_KEY = "shell-chat-list-seed"

        const val VERSION = 1

        /** 7 天，同 HomeForYouCache（§4.6 的统一 TTL）。 */
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000

        private const val FIELD_VERSION = "version"
        private const val FIELD_AUTH_SCOPE = "authScope"
        private const val FIELD_SAVED_AT = "savedAt"
        private const val FIELD_TOTAL = "total"
        private const val FIELD_ITEMS = "items"

        fun authScopeOf(userId: String?): String =
            if (userId.isNullOrBlank()) "guest" else "user:$userId"
    }
}

/** convEpoch 写入的接缝（让 ViewModel 可单测）。 */
interface ConvEpochLike {
    fun bump(characterId: String)
}

/**
 * 多角色影院缓存失效纪元（RN `multi_cinema_round_cache.ts:52-60` 的共享键契约）。
 *
 * ## 为什么壳删除会话后必须写这个键
 *
 * 删会话后 seq 归零重开，RN 影院轮缓存的 seq 恒大于新会话 —— 重进多角色影院会
 * **假命中旧剧情**。RN 页内删除走 `invalidateMultiCinemaRoundCache`（JS 内存 Map），
 * 但壳的原生删除不经 RN 代码，无人调它。iOS 壳的解法（已在 RN 侧就绪）：
 * 壳删除成功后写 `multi-cinema-conv-epoch:<characterId>` 时间戳，RN 缓存写入时
 * 快照此键、读取时比对，不一致即失效。**RN 侧零改动，壳照写即可。**
 *
 * 只对 character 会话写（story/game 没有多角色影院）；写失败只记日志 ——
 * 影院缓存失效是尽力而为，不该让删除操作报错。
 */
class ConvEpochWriter(
    private val store: LegacyMmkvStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : ConvEpochLike {
    override fun bump(characterId: String) {
        if (characterId.isEmpty()) return
        store.putString("$KEY_PREFIX$characterId", nowMs().toString())
    }

    companion object {
        /** `multi_cinema_round_cache.ts:59` 的键前缀，RN 默认 MMKV 实例。 */
        const val KEY_PREFIX = "multi-cinema-conv-epoch:"
    }
}
