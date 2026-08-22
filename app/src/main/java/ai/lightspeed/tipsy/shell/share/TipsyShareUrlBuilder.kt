package ai.lightspeed.tipsy.shell.share

import android.net.Uri
import androidx.core.net.toUri

/**
 * Builds the public Reel URL used by the RN Android share flow.
 *
 * Runtime assembly deliberately uses [Uri.Builder], not string interpolation: route ids and CDN
 * URLs are untrusted URL components and must not be allowed to inject path/query delimiters.
 * Encoding is kept in a small pure function so the exact bytes remain covered by JVM tests even
 * though `android.net.Uri` itself is a throwing stub in local unit tests.
 */
class TipsyShareUrlBuilder internal constructor(
    webBaseUrl: String,
    private val renderer: TipsyShareUriRenderer,
) {
    constructor(webBaseUrl: String) : this(webBaseUrl, AndroidTipsyShareUriRenderer)

    private val normalizedBaseUrl = webBaseUrl.trim().trimEnd('/').also {
        require(it.isNotEmpty()) { "webBaseUrl must not be blank" }
    }

    fun reelUrl(content: TipsyShareContent): String {
        val encodedPath = "reel/${encodeComponent(content.reelRouteId)}"
        val query = buildList {
            add("from_share" to "true")
            add("v" to content.mediaUrl)
            content.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { add("p" to it) }
            content.characterName?.takeIf { it.isNotBlank() }?.let { add("n" to it) }
        }.joinToString("&") { (key, value) ->
            // RN uses URLSearchParams here, whose application/x-www-form-urlencoded
            // encoding is not Uri.encode/encodeURIComponent: spaces are `+`, and only
            // alnum plus `*-._` remain literal.
            "${encodeQueryComponent(key)}=${encodeQueryComponent(value)}"
        }
        return renderer.render(normalizedBaseUrl, encodedPath, query)
    }

    fun shareText(localizedMessage: String, content: TipsyShareContent): String =
        "$localizedMessage\n${reelUrl(content)}"

    companion object {
        /** Percent-encode one UTF-8 path/query component with Android [Uri.encode] semantics. */
        internal fun encodeComponent(value: String): String {
            val bytes = value.toByteArray(Charsets.UTF_8)
            return buildString(bytes.size) {
                bytes.forEach { byte ->
                    val unsigned = byte.toInt() and 0xff
                    if (isUriUnreserved(unsigned)) {
                        append(unsigned.toChar())
                    } else {
                        append('%')
                        append(HEX[unsigned ushr 4])
                        append(HEX[unsigned and 0x0f])
                    }
                }
            }
        }

        internal fun encodeQueryComponent(value: String): String {
            val bytes = value.toByteArray(Charsets.UTF_8)
            return buildString(bytes.size) {
                bytes.forEach { byte ->
                    val unsigned = byte.toInt() and 0xff
                    when {
                        unsigned == ' '.code -> append('+')
                        isFormUnreserved(unsigned) -> append(unsigned.toChar())
                        else -> {
                            append('%')
                            append(HEX[unsigned ushr 4])
                            append(HEX[unsigned and 0x0f])
                        }
                    }
                }
            }
        }

        private fun isUriUnreserved(value: Int): Boolean =
            value in 'a'.code..'z'.code ||
                value in 'A'.code..'Z'.code ||
                value in '0'.code..'9'.code ||
                value.toChar() in "_-.!~'()*"

        private fun isFormUnreserved(value: Int): Boolean =
            value in 'a'.code..'z'.code ||
                value in 'A'.code..'Z'.code ||
                value in '0'.code..'9'.code ||
                value.toChar() in "*-._"

        private const val HEX = "0123456789ABCDEF"
    }
}

internal fun interface TipsyShareUriRenderer {
    fun render(baseUrl: String, encodedPath: String, encodedQuery: String): String
}

private object AndroidTipsyShareUriRenderer : TipsyShareUriRenderer {
    override fun render(baseUrl: String, encodedPath: String, encodedQuery: String): String =
        baseUrl.toUri()
            .buildUpon()
            .appendEncodedPath(encodedPath)
            .encodedQuery(encodedQuery)
            .build()
            .toString()
}
