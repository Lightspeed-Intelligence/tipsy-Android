package ai.lightspeed.tipsy.shell.pages.settings

import ai.lightspeed.tipsy.shell.network.ApiClient
import ai.lightspeed.tipsy.shell.network.AuthMode
import ai.lightspeed.tipsy.shell.network.ScalarCoercion
import org.json.JSONObject

/**
 * Settings 与语言页的接口（W3，进度文档 §2.33）。
 *
 * ## ⚠️ 语言页的可选集合**必须壳自己拉**
 *
 * RN 侧由 `hydrateSupportedLanguages` 填 `config-persist` 的
 * `supportedLanguages`（`config_persist.ts:341`），但 `index.surfaces.js:84-85`
 * **刻意不调它**，注释原文：「消费页（语言设置）壳内为**原生**」。
 * 所以壳里那个 store 字段**恒为空** —— 读它只会得到空列表。
 *
 * 这同时是「语言页要原生实现」的证据之一（§2.33 记了三处）。
 *
 * ## 三个端点的鉴权模式
 *
 * | 端点 | RN axios | 壳 |
 * | --- | --- | --- |
 * | `/supported_languages` | `axiosPublic` | [AuthMode.OPPORTUNISTIC] |
 * | `/user/set_language` | `axiosAuth` | [AuthMode.REQUIRED] |
 * | `/user/nsfw` | `axiosAuth` | [AuthMode.REQUIRED] |
 *
 * `/supported_languages` **刻意不用 `NONE`**（§4.5）：`axiosPublic` 在有 token
 * 时是会带上的，`NONE` 等于永远不带，后端可能因此少返回与账号相关的字段 ——
 * 而两端都不报错。
 */
class SettingsApi(private val apiClient: ApiClient) : SettingsSource {

    /**
     * 服务端支持的语言列表（`POST /supported_languages`，`apis/user.ts:77`）。
     *
     * **无请求体** —— RN 侧 `axiosPublic.post(url)` 不传 data。
     *
     * 返回的是**数组**（`SupportedLanguage[]`），不是对象，所以读
     * `envelope.dataArray`。
     */
    override suspend fun fetchSupportedLanguages(): List<SupportedLanguage> {
        val envelope = apiClient.post(
            path = PATH_SUPPORTED_LANGUAGES,
            jsonBody = "{}",
            authMode = AuthMode.OPPORTUNISTIC,
        )
        val array = envelope.dataArray ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                SupportedLanguage.parse(obj)?.let(::add)
            }
        }
    }

    /**
     * 保存账号语言（`POST /user/set_language`，`apis/user.ts:66`）。
     *
     * ⚠️ 调用方**不要**等它成功再切 UI 语言：RN 是先本地切、再打接口，
     * 失败也不回滚（见 [SettingsViewModel.onLanguageDone]）。
     */
    override suspend fun setLanguage(languageCode: String) {
        val body = JSONObject().put(FIELD_LANGUAGE_CODE, languageCode)
        apiClient.post(
            path = PATH_SET_LANGUAGE,
            jsonBody = body.toString(),
            authMode = AuthMode.REQUIRED,
        )
    }

    /**
     * 写 nsfw 偏好（`POST /user/nsfw`，`apis/user.ts:132`）。
     *
     * ## ⚠️ 这是 `nsfw` 的**唯一写方**
     *
     * 方案 §8.1 Home 行原写「App 不回写后端」——**需限定**（§2.33 已订正）：
     * 正确表述是「**Home/筛选侧**不回写，写入只在 Settings 这一处」。
     * `config_persist.ts:225` 那条「后端权威、单向镜像」说的是**读**方向。
     *
     * RN 的顺序（`settings/page.tsx:76-81`）：接口成功 → 才写本地镜像 →
     * 再重拉 `hydrateTags`（标签目录随分级变化）。**不是乐观更新** ——
     * 与语言页那条刻意相反，别抄错。壳侧 `hydrateTags` 由 RN 侧持有，
     * 壳不复刻（Home 的标签行属另一条链）。
     */
    override suspend fun setNsfw(enabled: Boolean) {
        val body = JSONObject().put(FIELD_NSFW, enabled)
        apiClient.post(
            path = PATH_SET_NSFW,
            jsonBody = body.toString(),
            authMode = AuthMode.REQUIRED,
        )
    }

    companion object {
        const val PATH_SUPPORTED_LANGUAGES = "/supported_languages"
        const val PATH_SET_LANGUAGE = "/user/set_language"

        /** `apis/user.ts:132` `updateUserNsfw`。 */
        const val PATH_SET_NSFW = "/user/nsfw"

        private const val FIELD_LANGUAGE_CODE = "language_code"
        private const val FIELD_NSFW = "nsfw"
    }
}

/** 数据源接缝（同 `ProfileSource`：让 ViewModel 编排能用 JVM 单测覆盖）。 */
interface SettingsSource {
    suspend fun fetchSupportedLanguages(): List<SupportedLanguage>
    suspend fun setLanguage(languageCode: String)
    suspend fun setNsfw(enabled: Boolean)
}

/**
 * 服务端返回的一个可选语言（`SupportedLanguage`，`types/user.ts:94-98`）。
 *
 * ⚠️ **`display` 才是上屏文案**，不是 `language`。RN 的语言页渲染
 * `language.display`（`language.tsx:61`）—— 那是**该语言自己的写法**
 * （如 `日本語` 而不是 `Japanese`），所以**不过 `t()`**。
 * 用 `language` 字段会显示英文名，与现网不一致。
 *
 * @property languageCode 提交给 `/user/set_language` 的值，也是选中态比较键
 * @property display 上屏文案（该语言的自称），**不翻译**
 */
data class SupportedLanguage(
    val languageCode: String,
    val display: String,
) {
    companion object {

        /**
         * 解析一条；`language_code` 或 `display` 缺失返回 null。
         *
         * `display` 缺失也丢弃：没有文案的行渲染出来是一条空白可点区域，
         * 点了会把账号语言改成一个用户看不见的值 —— 比少一行危险。
         * 回落用 `language` 字段是**错的**（那是英文名，见类注释）。
         */
        fun parse(json: JSONObject): SupportedLanguage? {
            val code = ScalarCoercion.optString(json, FIELD_LANGUAGE_CODE)
                ?.takeIf { it.isNotBlank() } ?: return null
            val display = ScalarCoercion.optString(json, FIELD_DISPLAY)
                ?.takeIf { it.isNotBlank() } ?: return null
            return SupportedLanguage(languageCode = code, display = display)
        }

        private const val FIELD_LANGUAGE_CODE = "language_code"
        private const val FIELD_DISPLAY = "display"
    }
}
