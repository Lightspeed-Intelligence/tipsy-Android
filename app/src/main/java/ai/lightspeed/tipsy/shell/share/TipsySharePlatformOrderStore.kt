package ai.lightspeed.tipsy.shell.share

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Native-private, visual-only ordering for the share platform row.
 *
 * This intentionally does not write RN MMKV. iOS likewise owns this preference locally, and no
 * business state depends on preserving an exact order across a Native/RN implementation switch.
 */
class TipsySharePlatformOrderStore(
    private val preferences: SharedPreferences,
) {
    fun orderedChannels(): List<TipsyShareChannel> =
        listOf(TipsyShareChannel.COPY_LINK) +
            TipsySharePlatformOrder.orderedExternal(readStoredIds())

    /**
     * Record the click before launching the destination. Launch success is deliberately irrelevant:
     * RN also promotes a platform as soon as it is clicked.
     */
    fun recordClick(channel: TipsyShareChannel) {
        if (channel == TipsyShareChannel.COPY_LINK) return
        val current = readStoredIds()
        val updated = TipsySharePlatformOrder.recordClick(current, channel)
        if (updated == current) return
        val encoded = JSONArray().apply { updated.forEach(::put) }.toString()
        preferences.edit().putString(STORAGE_KEY, encoded).apply()
    }

    private fun readStoredIds(): List<String> {
        val raw = preferences.getString(STORAGE_KEY, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    companion object {
        const val STORAGE_KEY = "share_platform_order"
        private const val PREFERENCES_NAME = "tipsy_native_share_preferences"

        fun from(context: Context): TipsySharePlatformOrderStore =
            TipsySharePlatformOrderStore(
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            )
    }
}

/** Pure ordering seam: no SharedPreferences or org.json dependency in its behavior tests. */
internal object TipsySharePlatformOrder {
    val defaultExternalChannels: List<TipsyShareChannel> = listOf(
        TipsyShareChannel.DISCORD,
        TipsyShareChannel.INSTAGRAM,
        TipsyShareChannel.TIKTOK,
        TipsyShareChannel.X,
        TipsyShareChannel.FACEBOOK,
    )

    fun orderedExternal(storedIds: List<String>): List<TipsyShareChannel> {
        val stored = storedIds
            .mapNotNull(TipsyShareChannel::fromStorageId)
            .filterNot { it == TipsyShareChannel.COPY_LINK }
            .distinct()
        return stored + defaultExternalChannels.filterNot(stored::contains)
    }

    fun recordClick(storedIds: List<String>, channel: TipsyShareChannel): List<String> {
        if (channel == TipsyShareChannel.COPY_LINK) return normalizedStorageIds(storedIds)
        val current = normalizedStorageIds(storedIds)
        return listOf(channel.storageId) + current.filterNot { it == channel.storageId }
    }

    private fun normalizedStorageIds(storedIds: List<String>): List<String> =
        orderedExternal(storedIds).map(TipsyShareChannel::storageId)
}
