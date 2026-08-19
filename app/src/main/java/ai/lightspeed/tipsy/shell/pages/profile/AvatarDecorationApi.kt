package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.network.ApiClient
import ai.lightspeed.tipsy.shell.network.AuthMode

/**
 * 头像框公开目录（RN `apis/avatarDecoration.ts:13-20` 的
 * `getAvatarDecorationConfigList`），按 code 解析出 `image_url`。
 *
 * ## ⚠️ 鉴权模式是 [AuthMode.OPPORTUNISTIC]，不是 NONE
 *
 * RN 侧走 **`axiosPublic`**（`avatarDecoration.ts:16`），按方案 §4.5 的映射
 * 表就是 OPPORTUNISTIC：有 token 带上、没有也照发。写成 NONE 就是
 * `AuthMode` 类注释里 iOS「搜索历史恒空」那个 bug 的形状 —— 带不带 token
 * 的行为差异只在服务端，客户端看不出来。守这条的是
 * `AvatarDecorationApiContractTest`。
 *
 * ## 与 RN 的取数时机差异（刻意）
 *
 * RN 把目录 hydrate 进 `config-persist` store（启动拉一次、MMKV 持久化，
 * 失败静默吞 —— 进度 §2.19 记过这类 hydrate 的坑）。壳侧不复刻这套持久层，
 * 每次 Profile 刷新链解析一次；失败时的「保留旧值」语义由
 * `ProfileViewModel.resolveAvatarDecoration` 负责，这里只管单次往返。
 */
class AvatarDecorationApi(private val apiClient: ApiClient) : AvatarDecorationSource {
    override suspend fun fetchImageUrl(code: String?): String? {
        val wanted = code?.takeIf { it.isNotBlank() } ?: return null
        val data = apiClient.post(PATH, authMode = AuthMode.OPPORTUNISTIC).data ?: return null
        val list = data.optJSONArray("list") ?: return null
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            if (item.optString("code") == wanted) {
                return item.optString("image_url").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    companion object {
        const val PATH = "/avatar_decoration/config/list"
    }
}

fun interface AvatarDecorationSource {
    suspend fun fetchImageUrl(code: String?): String?
}
