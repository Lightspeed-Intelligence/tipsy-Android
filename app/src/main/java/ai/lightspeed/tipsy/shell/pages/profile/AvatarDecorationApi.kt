package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.network.ApiClient
import ai.lightspeed.tipsy.shell.network.AuthMode
import org.json.JSONObject

/** Public avatar-decoration catalogue (RN `avatarDecoration/config/list`). */
class AvatarDecorationApi(private val apiClient: ApiClient) : AvatarDecorationSource {
    override suspend fun fetchImageUrl(code: String?): String? {
        val wanted = code?.takeIf { it.isNotBlank() } ?: return null
        val data = apiClient.post(PATH, authMode = AuthMode.NONE).data ?: return null
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
