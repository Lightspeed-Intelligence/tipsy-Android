package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.network.ApiClient
import ai.lightspeed.tipsy.shell.network.AuthMode
import ai.lightspeed.tipsy.shell.network.ScalarCoercion
import org.json.JSONArray
import org.json.JSONObject

/**
 * 他人主页的接口（W3，进度文档 §2.32）。
 *
 * ## ⚠️ 与自己视角**几乎无一条共用** —— 这是本文件独立存在的理由
 *
 * | 内容 | 自己（[ProfileApi]） | 他人（本类） |
 * | --- | --- | --- |
 * | 头部资料 | user store 的 `/user/info` | `/user/get/public`（**REQUIRED**，见下） |
 * | 四统计 | `/user/stats_info` + `axiosAuth` | 同路径 + **`axiosPublic`** |
 * | 列表 | `/user/created/list`，size 10 | `/character/list/creator{,/v2}`，**size 200** |
 *
 * 开工前审计推翻了「复用自己视角基础设施」的直觉前提（§2.32 记了四处偏差）。
 *
 * ## ⚠️⚠️ `/user/get/public` 走 `axiosAuth`，名字骗人
 *
 * `apis/user.ts:49` 实测是 **`axiosAuth`** —— 名字里的 public 指「查他人的
 * **公开**资料」，**不是免鉴权**。而 `axiosAuth` 取不到有效 token 时会
 * `requestLogin('axios-auth')` 并 reject（`utils/axios.ts:148-175`，
 * 壳宿主下 `isShellAuthHost()` 恒真）。
 *
 * 所以游客点创作者**会看到登录页**，而不是主页。这与
 * `AppRoute.UserProfile.requiresAuth = false` 有张力，但**那是 RN 现网行为，
 * 对等即正确**。
 *
 * ⚠️ **不要"顺手修正"成 [AuthMode.OPPORTUNISTIC]** —— 那会让 401 与登录弹窗
 * 的时序偏离现网（游客能看到半个页面然后各处报错，而不是干净地被引导登录）。
 * 要真正支持游客浏览需后端换实例，属独立决策。
 *
 * ## 统计那条**是 `OPPORTUNISTIC` 不是 `NONE`**
 *
 * §4.5 记的 iOS 事故类型：`axiosPublic` 在**有** token 时是会带上的
 * （`utils/axios.ts:105-112`），拿 `NONE` 实现等于永远不带 —— 后端可能因此
 * 少返回与当前用户相关的字段，而两端都不报错。
 */
class PublicProfileApi(private val apiClient: ApiClient) : PublicProfileSource {

    /**
     * 他人的公开资料（`/user/get/public`）。
     *
     * @param userId 目标用户 id
     * @throws ai.lightspeed.tipsy.shell.network.ApiException 无 token 时抛未认证错误
     *   —— 由 [AuthMode.REQUIRED] 的前置守门产生，见类注释
     */
    override suspend fun fetchPublicUser(userId: String): PublicUserProfile? {
        val body = JSONObject().put(FIELD_USER_ID, userId)
        val envelope = apiClient.post(
            path = PATH_PUBLIC_USER,
            jsonBody = body.toString(),
            // ⚠️ REQUIRED 不是笔误，见类注释「名字骗人」那段
            authMode = AuthMode.REQUIRED,
        )
        return PublicUserProfile.parse(envelope.data)
    }

    /**
     * 他人的四个统计数字。
     *
     * 与自己那条**同路径不同鉴权**（`getPublicFollowerInfo`，`apis/profile.ts:130`）。
     * 复用 [ProfileStats.parse] —— 响应形状相同，交叉映射那个坑也同样适用。
     */
    override suspend fun fetchPublicStats(userId: String): ProfileStats {
        val body = JSONObject().put(FIELD_USER_ID, userId)
        val envelope = apiClient.post(
            path = ProfileApi.PATH_STATS_INFO,
            jsonBody = body.toString(),
            // ⚠️ OPPORTUNISTIC 不是 NONE，见类注释
            authMode = AuthMode.OPPORTUNISTIC,
        )
        return ProfileStats.parse(envelope.data)
    }

    /**
     * 他人的创作列表 **v2**（含 game，`/character/list/creator/v2`）。
     *
     * 请求体照 `useProfile.tsx:123-133`：`creator_id` / `size` / `nsfw` /
     * `types` / `language_code`。
     *
     * ⚠️ `nsfw` 传的是**用户偏好**（RN 从 `useConfigPersistStore` 读），
     * 而壳内该偏好恒 false（后端权威单向镜像，见 `HomeFilterStore`）——
     * 所以这里恒传 false，与壳其它页面一致。
     */
    override suspend fun fetchCreatorListV2(
        userId: String,
        languageCode: String,
    ): CreatorListPage {
        val body = JSONObject()
            .put(FIELD_CREATOR_ID, userId)
            .put(FIELD_PAGE, FIRST_PAGE)
            .put(FIELD_SIZE, PAGE_SIZE_OTHER)
            .put(FIELD_NSFW, false)
            .put(FIELD_TYPES, JSONArray(listOf("character", "story", "game")))
            .put(FIELD_LANGUAGE_CODE, languageCode)
        val envelope = apiClient.post(
            path = PATH_CREATOR_LIST_V2,
            jsonBody = body.toString(),
            authMode = AuthMode.OPPORTUNISTIC,
        )
        return CreatorListPage.parseV2(envelope.data)
    }

    /**
     * 他人的创作列表 **v1**（`/character/list/creator`）—— v2 空时的回落。
     *
     * 请求体照 `useProfile.tsx:100-107`：**只有** `creator_id` / `size` / `nsfw`，
     * 没有 `types`、没有 `language_code`（v1/v2 请求体不同，别抄成一份）。
     *
     * 响应是 `ProfileCharacterListRes`（`characters` 数组 + `total_characters`），
     * 与 v2 的 `list` + `total` **不同形状** —— 见 [CreatorListPage.parseV1]。
     */
    override suspend fun fetchCreatorListV1(userId: String): CreatorListPage {
        val body = JSONObject()
            .put(FIELD_CREATOR_ID, userId)
            .put(FIELD_PAGE, FIRST_PAGE)
            .put(FIELD_SIZE, PAGE_SIZE_OTHER)
            .put(FIELD_NSFW, false)
        val envelope = apiClient.post(
            path = PATH_CREATOR_LIST_V1,
            jsonBody = body.toString(),
            authMode = AuthMode.OPPORTUNISTIC,
        )
        return CreatorListPage.parseV1(envelope.data)
    }

    /**
     * 关注 / 取关（`POST /user/follow/user`，`apis/profile.ts:142`）。
     *
     * ⚠️ **是 toggle 单端点**：同一路径既关注也取关，靠后端翻转。
     * 没有独立的 unfollow 端点 —— 找不到它不是漏了。
     *
     * 调用方**不要**用返回值本地翻转关注态：RN 成功后重拉
     * `/user/get/public` + stats（`useProfile.tsx:241-243` 两个 mutate），
     * 因为 followers 计数也要跟着变。见 [PublicProfileViewModel.onFollowClick]。
     */
    override suspend fun toggleFollow(userId: String) {
        val body = JSONObject().put(FIELD_USER_ID, userId)
        apiClient.post(
            path = PATH_FOLLOW_USER,
            jsonBody = body.toString(),
            authMode = AuthMode.REQUIRED,
        )
    }

    companion object {
        /** ⚠️ `axiosAuth` 尽管路径叫 public，见类注释。 */
        const val PATH_PUBLIC_USER = "/user/get/public"

        /** 他人创作列表 v2（含 game），`apis/profile.ts:285`。 */
        const val PATH_CREATOR_LIST_V2 = "/character/list/creator/v2"

        /** 他人创作列表 v1，`apis/profile.ts:68`。 */
        const val PATH_CREATOR_LIST_V1 = "/character/list/creator"

        /** 关注 toggle，`apis/profile.ts:144`。 */
        const val PATH_FOLLOW_USER = "/user/follow/user"

        /**
         * 他人主页每页 **200**（`useProfile.tsx:30` `PAGE_SIZE`）。
         *
         * ⚠️ 与自己视角的 10 / 20 完全不同（[ProfileApi] 类注释记了三个并存值）。
         * 且他人主页**只拉这一页** —— 见 [FIRST_PAGE]。
         */
        const val PAGE_SIZE_OTHER = 200

        /**
         * 恒取第 0 页 —— **他人主页在 RN 侧翻不了页**（§2.32 第 5 条）。
         *
         * `onEndReached` 的 tab0 分支调的是 `loadMoreCreated()`
         * （`CharacterGrid.tsx:1398-1401`），那是**自己**那条列表的翻页；
         * 两条 creator 列表都没有 `setSize` 出口（`useProfile.tsx` 只导出
         * `selfCharSetSize`）。所以现网他人主页看到的恒是首页 200 条。
         *
         * 「三列网格就该能翻页」是直觉，照它补分页会比 RN 多拉数据 ——
         * size 取 200 本身就说明 RN 是拿单页当全量用。
         */
        const val FIRST_PAGE = 0

        private const val FIELD_USER_ID = "user_id"
        private const val FIELD_CREATOR_ID = "creator_id"
        private const val FIELD_PAGE = "page"
        private const val FIELD_SIZE = "size"
        private const val FIELD_NSFW = "nsfw"
        private const val FIELD_TYPES = "types"
        private const val FIELD_LANGUAGE_CODE = "language_code"
    }
}

/** 数据源接缝（同 [ProfileSource]：让 ViewModel 的编排能用 JVM 单测覆盖）。 */
interface PublicProfileSource {
    suspend fun fetchPublicUser(userId: String): PublicUserProfile?
    suspend fun fetchPublicStats(userId: String): ProfileStats
    suspend fun fetchCreatorListV2(userId: String, languageCode: String): CreatorListPage
    suspend fun fetchCreatorListV1(userId: String): CreatorListPage
    suspend fun toggleFollow(userId: String)
}

/**
 * 他人创作列表的一页 —— **v1 与 v2 的响应形状不同**，归一到本类。
 *
 * | | v2（`CreatedList`） | v1（`ProfileCharacterListRes`） |
 * | --- | --- | --- |
 * | 数组字段 | `list` | **`characters`** |
 * | 元素形状 | `{item_type, character:{…}}` 嵌套 | **扁平** `ProfileCharacterList` |
 * | 总数字段 | `total` | `total_characters` |
 *
 * 两条都复用 [ProfileCreatedItem] 承载 —— 卡片视觉与自己视角同构
 * （`CharacterGrid.tsx:728-735` 他人分支渲染的也是 `CharacterGridItem`，
 * 只是 `isSelf=false`）。
 */
data class CreatorListPage(val items: List<ProfileCreatedItem>) {
    companion object {

        /** v2：`list` 数组，元素与 `/user/created/list` 同形（可直接复用解析）。 */
        fun parseV2(data: JSONObject?): CreatorListPage {
            if (data == null) return CreatorListPage(emptyList())
            val list = data.optJSONArray(FIELD_LIST)
            return CreatorListPage(parseItems(list))
        }

        /**
         * v1：`characters` 数组，元素是**扁平** `ProfileCharacterList`。
         *
         * ⚠️ 扁平元素**没有 `item_type`**，而 [ProfileCreatedItem.parse] 认不出
         * `item_type` 会整条返回 null（那是为 v2/created 列表的新类型容错设计的）。
         * 所以这里逐条**补一个 `item_type: "character"`** 再交给同一个 parser：
         * v1 端点按定义只返回角色（`ProfileCharacterList`，`types/profile.ts:24`），
         * game/story 是 v2 才有的。
         *
         * 不补的表现是**回落路径恒空**：v2 空 → 回落 v1 → 全被过滤 → 空态。
         * 而 v1 回落本身就是 v2 缺数据时才走，很容易在联调时看不出来。
         */
        fun parseV1(data: JSONObject?): CreatorListPage {
            if (data == null) return CreatorListPage(emptyList())
            val list = data.optJSONArray(FIELD_CHARACTERS)
            val items = buildList {
                for (i in 0 until (list?.length() ?: 0)) {
                    val flat = list?.optJSONObject(i) ?: continue
                    ProfileCreatedItem.parse(wrapFlatCharacter(flat))?.let(::add)
                }
            }
            return CreatorListPage(items)
        }

        /**
         * 把 v1 的扁平角色包成 created 列表的形状。
         *
         * 复制原对象再补两个键，**不改入参**（JSONObject 是可变的，原地 put
         * 会污染 [ProfileCreatedItem.rawJson] 里存的原文）。
         *
         * `item_id` 缺失时用 `character_id` 兜底 —— 扁平形状里两者都可能是
         * 那个 id（`ProfileCharacterList extends HomeCharacterDetail` 带
         * `item_id`，但 v1 实测响应不保证）。
         */
        private fun wrapFlatCharacter(flat: JSONObject): JSONObject {
            val itemId = ScalarCoercion.optString(flat, FIELD_ITEM_ID)
                ?.takeIf { it.isNotBlank() }
                ?: ScalarCoercion.optString(flat, FIELD_CHARACTER_ID)?.takeIf { it.isNotBlank() }
            return JSONObject(flat.toString()).apply {
                put(FIELD_ITEM_TYPE, ITEM_TYPE_CHARACTER)
                if (itemId != null) put(FIELD_ITEM_ID, itemId)
                // 嵌套层也指向自己：ProfileCreatedItem 的展示字段（nickname /
                // 完整 URL 的 image_url）都从嵌套层取，扁平元素的这些字段就在顶层
                put(FIELD_NESTED_CHARACTER, JSONObject(flat.toString()))
            }
        }

        private fun parseItems(list: JSONArray?): List<ProfileCreatedItem> = buildList {
            for (i in 0 until (list?.length() ?: 0)) {
                val obj = list?.optJSONObject(i) ?: continue
                ProfileCreatedItem.parse(obj)?.let(::add)
            }
        }

        private const val FIELD_LIST = "list"
        private const val FIELD_CHARACTERS = "characters"
        private const val FIELD_ITEM_TYPE = "item_type"
        private const val FIELD_ITEM_ID = "item_id"
        private const val FIELD_CHARACTER_ID = "character_id"
        private const val FIELD_NESTED_CHARACTER = "character"
        private const val ITEM_TYPE_CHARACTER = "character"
    }
}
