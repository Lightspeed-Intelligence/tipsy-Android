package ai.lightspeed.tipsy.shell.pages.home

import ai.lightspeed.tipsy.shell.network.ScalarCoercion
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * For You 冷启动种子缓存（`useForYouListCache.ts` 112 行 + 方案 §4.6 的信封）。
 *
 * ## 为什么存**原始响应片段**而不是解析后的模型
 *
 * RN 侧存的是 `CharacterGetResWithStory[]` —— 也就是 `/recommend_feed/list`
 * 响应里 `list` 的原始元素。壳沿用同一形状，好处是：
 * - **读写都复用 [HomeFeedParser]**，不需要再写一套「模型 → JSON」的序列化，
 *   那会变成第二个真值来源，字段一改就漂移
 * - 与 RN 的 `for-you-cache` 实例**同格式**，两侧互不破坏（虽然 key 不同，
 *   见下）
 *
 * ## ⚠️ 信封是壳自己的，不是 RN 那个 key
 *
 * RN 写的是 `for-you-cache` 实例里的 `forYouListCache`，值是**裸数组**
 * （`JSON.stringify(items)`，无信封）—— 没有 version / authScope / savedAt。
 * 那意味着 RN 的缓存：
 * - **不按账号隔离**（换号后仍显示上一账号的种子，直到首屏刷新覆盖）
 * - **没有 TTL**（一年前的种子照样显示）
 * - **logout 不清**（已核实：全仓没有清这个 key 的代码）
 *
 * 方案 §4.6 明确要求壳**不继承**这三点，改用 iOS 的信封
 * `{version, gender, authScope, savedAt, items}` + authScope 门禁 + 7 天 TTL。
 * 所以壳写**自己的 key** [CACHE_KEY]，与 RN 的并存：
 * - 壳不读 RN 的裸数组（读了就等于继承「跨账号复用」）
 * - 壳不写 RN 的 key（写了 RN 侧会因多出信封而 JSON.parse 出非预期结构）
 *
 * 代价是首次装壳版的用户没有种子（多一次 loading），换来的是不会给
 * A 账号显示 B 账号的推荐。
 *
 * ## 语言**刻意不做门禁**（方案 §4.6 的反直觉修正）
 *
 * 两阶段 i18n 下首屏读到的是瞬态语言，拿它做门禁会永久拒绝缓存
 * （iOS 踩过「二启永远无种子」）。语言真变了靠 `onLanguageSettled` 重拉自愈。
 */
class HomeForYouCache(
    private val store: HomeCacheStorage,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val logWarn: (String, Throwable?) -> Unit = { m, t -> Log.w(TAG, m, t) },
) {

    /**
     * 读种子。
     *
     * 任一门禁不过就返回空表 —— **宁可多一次 loading，也不显错数据**
     * （方案 §4.6 原话）。
     *
     * @param authScope 当前作用域：`user:<id>` 或 `guest`
     * @param gender 当前性别筛选 —— 与缓存时不同则作废（缓存的是筛选后的结果）
     */
    fun read(authScope: String, gender: HomeGender): List<HomeFeedItem> {
        val raw = store.getString(CACHE_KEY) ?: return emptyList()
        val envelope = runCatching { JSONObject(raw) }
            .onFailure { logWarn("种子缓存不是合法 JSON，丢弃", it) }
            .getOrNull() ?: return emptyList()

        // version 不匹配直接作废：老信封的字段语义可能已变，
        // 逐字段兼容会让这里越写越长且没人记得哪个版本是什么样
        if (ScalarCoercion.optInt(envelope, FIELD_VERSION) != VERSION) return emptyList()
        // ⚠️ authScope 门禁 —— 漏了就是「换号后显示上一账号的推荐」
        if (ScalarCoercion.optString(envelope, FIELD_AUTH_SCOPE) != authScope) return emptyList()
        // gender 变了缓存无效：种子是按当时筛选拉的
        if (ScalarCoercion.optString(envelope, FIELD_GENDER) != gender.storedValue) {
            return emptyList()
        }
        val savedAt = ScalarCoercion.optLong(envelope, FIELD_SAVED_AT) ?: return emptyList()
        val age = nowMs() - savedAt
        // 负数（设备时钟回退）也作废 —— 不然一个错时钟能让种子永久有效
        if (age < 0 || age > TTL_MS) return emptyList()

        val list = envelope.optJSONArray(FIELD_ITEMS) ?: return emptyList()
        // 复用 For You 的解析器：与在线路径同一套字段处理。
        // fallbackRequestId 每次读都新生成 —— 缓存里的 item 若缺 request_id，
        // 归因走一个客户端 id，与 RN 的 normalizeCachedRecommendFeedMetadata 同义
        val page = runCatching {
            HomeFeedParser.parseForYou(
                data = JSONObject().put("list", list),
                sessionId = CACHE_SESSION_ID,
                page = 0,
                pageSize = HomeApi.PAGE_SIZE,
                fallbackRequestId = "client_cache_${nowMs()}",
            )
        }.onFailure { logWarn("种子缓存解析失败，丢弃", it) }.getOrNull() ?: return emptyList()
        return page.items
    }

    /**
     * 写种子：**只存前 [LOCKED_SIZE] 条**（`useForYouListCache.ts:24`
     * `LOCKED_HOME_CACHE_SIZE = 5`）。
     *
     * 存整页会让冷启动读盘与解析变慢，而首屏可见的也就前几张卡。
     *
     * @param rawItems `/recommend_feed/list` 响应里 `list` 的原始元素
     */
    fun write(authScope: String, gender: HomeGender, rawItems: JSONArray) {
        val locked = JSONArray()
        for (i in 0 until minOf(rawItems.length(), LOCKED_SIZE)) {
            locked.put(rawItems.opt(i))
        }
        if (locked.length() == 0) return
        val envelope = JSONObject()
            .put(FIELD_VERSION, VERSION)
            .put(FIELD_AUTH_SCOPE, authScope)
            .put(FIELD_GENDER, gender.storedValue)
            .put(FIELD_SAVED_AT, nowMs())
            .put(FIELD_ITEMS, locked)
        if (!store.putString(CACHE_KEY, envelope.toString())) {
            logWarn("种子缓存写入失败（MMKV 不可用）", null)
        }
    }

    companion object {
        /**
         * 壳自己的 key，**不是** RN 的 `forYouListCache`。见类注释。
         */
        const val CACHE_KEY = "shell-for-you-seed"

        /** 信封版本。字段语义变化时 +1，老信封整体作废。 */
        const val VERSION = 1

        /** 存前 5 条（`useForYouListCache.ts:24`）。 */
        const val LOCKED_SIZE = 5

        /** 7 天（方案 §4.6）。 */
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000

        /**
         * 种子 item 的 session id。
         *
         * 用固定值而非新 uuid：种子只用于首屏占位，真实数据一到就被 unionBy 覆盖。
         * 给它一个可识别的值便于排查「这条是种子还是在线数据」。
         */
        const val CACHE_SESSION_ID = "cache"

        private const val FIELD_VERSION = "version"
        private const val FIELD_AUTH_SCOPE = "authScope"
        private const val FIELD_GENDER = "gender"
        private const val FIELD_SAVED_AT = "savedAt"
        private const val FIELD_ITEMS = "items"

        private const val TAG = "HomeForYouCache"

        /** `guest` / `user:<id>`（方案 §4.6 的 authScope 取值）。 */
        fun authScopeOf(userId: String?): String =
            if (userId.isNullOrBlank()) "guest" else "user:$userId"
    }
}

/**
 * 种子缓存的存储接缝。
 *
 * 抽出来是为了让 TTL / authScope / version 三个门禁能用 JVM 单测覆盖 ——
 * 那三处**错了都不报错**，只是显示错数据或永远不显示。
 */
interface HomeCacheStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String): Boolean
}

/**
 * 生产实现：写 RN 的默认 MMKV 实例。
 *
 * ⚠️ 与 RN 的 `for-you-cache` **不是同一个实例**（RN 用
 * `createMMKV({id: 'for-you-cache'})` 建了独立实例）。壳写默认实例的自有 key，
 * 理由见 [HomeForYouCache] 类注释 —— 关键是**不碰 RN 那份**，
 * 免得两侧信封格式不同互相解析失败。
 */
class MmkvHomeCacheStorage(
    private val store: ai.lightspeed.tipsy.shell.auth.LegacyMmkvStore,
) : HomeCacheStorage {
    override fun getString(key: String): String? = store.getString(key)
    override fun putString(key: String, value: String): Boolean = store.putString(key, value)
}
