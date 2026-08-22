package ai.lightspeed.tipsy.shell.share

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

interface TipsyShareMediaSaver {
    suspend fun save(content: TipsyShareContent): TipsyShareMediaSaveResult
}

sealed interface TipsyShareMediaSaveResult {
    data class Saved(val uri: Uri) : TipsyShareMediaSaveResult
    data object NeedsLegacyWritePermission : TipsyShareMediaSaveResult
    data class Failed(val cause: Throwable) : TipsyShareMediaSaveResult
}

/**
 * Streams a remote share asset into the system gallery.
 *
 * This is a leaf platform boundary: it owns no auth state and it never routes the CDN request
 * through [ai.lightspeed.tipsy.shell.network.ApiClient]. The injected client is cloned with final
 * credential-stripping interceptors so even an accidentally decorated client cannot put the
 * Tipsy token or auth cookies on the wire, including after redirects.
 */
class AndroidTipsyShareMediaSaver(
    private val context: Context,
    private val contentResolver: ContentResolver,
    okHttpClient: OkHttpClient,
    private val sdkInt: () -> Int = { Build.VERSION.SDK_INT },
    private val hasLegacyWritePermission: () -> Boolean = {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : TipsyShareMediaSaver {

    private val cdnClient = TipsyShareCdnClientPolicy.build(okHttpClient)

    override suspend fun save(content: TipsyShareContent): TipsyShareMediaSaveResult {
        if (sdkInt() <= Build.VERSION_CODES.P && !hasLegacyWritePermission()) {
            return TipsyShareMediaSaveResult.NeedsLegacyWritePermission
        }

        val request = try {
            Request.Builder()
                .url(content.mediaUrl)
                .get()
                .build()
                .withoutTipsyCredentials()
        } catch (error: Exception) {
            return TipsyShareMediaSaveResult.Failed(error)
        }
        val call = try {
            cdnClient.newCall(request)
        } catch (error: Exception) {
            return TipsyShareMediaSaveResult.Failed(error)
        }

        // Continuation stays suspended through response-body/storage processing. Cancellation of
        // the view-lifecycle Job therefore calls Call.cancel(), which closes a stalled body read;
        // saveScoped/saveLegacy then roll back IS_PENDING/.part in their catch paths.
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWith(
                            Result.success(TipsyShareMediaSaveResult.Failed(error)),
                        )
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    val result = try {
                        response.use { saveResponse(content, it) }
                    } catch (error: Exception) {
                        TipsyShareMediaSaveResult.Failed(error)
                    }
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(result))
                    }
                }
            })
        }
    }

    private fun saveResponse(
        content: TipsyShareContent,
        response: Response,
    ): TipsyShareMediaSaveResult {
        if (!response.isSuccessful) {
            throw IOException("Share media download failed with HTTP ${response.code}")
        }
        val body = response.body ?: throw IOException("Share media response has no body")
        val maximumBytes = TipsyShareMediaDownloadPolicy.validateResponse(
            content = content,
            responseContentType = body.contentType()?.toString(),
            responseContentLength = body.contentLength(),
        )
        val spec = TipsyShareMediaFileSpec.forContent(
            content = content,
            timestampMillis = nowMillis(),
            responseContentType = body.contentType()?.toString(),
        )
        val copyBody: (BufferedOutputStream) -> Unit = { output ->
            body.byteStream().use { input ->
                TipsyShareMediaDownloadPolicy.copyWithLimit(
                    input = input,
                    output = output,
                    maximumBytes = maximumBytes,
                )
            }
        }
        return if (isAtLeastQ()) {
            saveScoped(spec, copyBody)
        } else {
            saveLegacy(spec, copyBody)
        }
    }

    /** 注解让 Android lint 理解可注入 SDK seam 对 @RequiresApi(Q) 调用的保护。 */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    private fun isAtLeastQ(): Boolean = sdkInt() >= Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveScoped(
        spec: TipsyShareMediaFileSpec,
        copyBody: (BufferedOutputStream) -> Unit,
    ): TipsyShareMediaSaveResult {
        val collection = when (spec.mediaType) {
            TipsyShareMediaType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            TipsyShareMediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, spec.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, spec.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, spec.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(collection, values)
            ?: throw IOException("MediaStore refused share media insert")

        try {
            val stream = contentResolver.openOutputStream(uri, "w")
                ?: throw IOException("MediaStore did not provide an output stream")
            BufferedOutputStream(stream).use { output ->
                copyBody(output)
                output.flush()
            }
            val published = contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            if (published <= 0) throw IOException("MediaStore failed to publish share media")
            return TipsyShareMediaSaveResult.Saved(uri)
        } catch (error: Throwable) {
            runCatching { contentResolver.delete(uri, null, null) }
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(
        spec: TipsyShareMediaFileSpec,
        copyBody: (BufferedOutputStream) -> Unit,
    ): TipsyShareMediaSaveResult {
        val directory = Environment.getExternalStoragePublicDirectory(spec.legacyDirectory)
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            throw IOException("Cannot create legacy media directory")
        }

        // 临时文件名也必须唯一：同毫秒两次分享不能互相截断下载流。
        val partial = File.createTempFile(".tipsy_share_", ".part", directory)
        var destination: File? = null
        try {
            BufferedOutputStream(FileOutputStream(partial)).use { output ->
                copyBody(output)
                output.flush()
            }

            // createNewFile 是原子 no-clobber 预留。Android/Linux renameTo 可以替换已
            // 存在目标，所以只能替换我们刚预留的空文件，绝不能直接指向历史照片。
            val reservedDestination = reserveLegacyDestination(directory, spec.displayName)
            destination = reservedDestination
            if (!partial.renameTo(reservedDestination)) {
                // 少数外置存储实现不允许 rename 覆盖预留文件；退化为写入我们拥有的
                // reservation。数据已经完整且受大小上限保护，旧文件仍不会被触碰。
                FileInputStream(partial).use { input ->
                    BufferedOutputStream(FileOutputStream(reservedDestination, false)).use { output ->
                        input.copyTo(output)
                        output.flush()
                    }
                }
                if (!partial.delete()) throw IOException("Cannot remove legacy share temp file")
            }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(reservedDestination.absolutePath),
                arrayOf(spec.mimeType),
                null,
            )
            return TipsyShareMediaSaveResult.Saved(Uri.fromFile(reservedDestination))
        } catch (error: Throwable) {
            runCatching { partial.delete() }
            // destination 只有在 createNewFile 原子预留成功后才赋值，所有权明确；
            // 任何同名历史照片都会让预留返回 false 并改试 suffix，不会走到这里。
            destination?.let { owned -> runCatching { owned.delete() } }
            throw error
        }
    }

    private fun reserveLegacyDestination(directory: File, displayName: String): File {
        for (collisionIndex in 0..MAX_LEGACY_FILENAME_COLLISIONS) {
            val candidate = File(
                directory,
                TipsyShareLegacyFileNaming.candidate(displayName, collisionIndex),
            )
            if (candidate.createNewFile()) return candidate
        }
        throw IOException("Cannot reserve a unique legacy share filename")
    }

    private companion object {
        const val MAX_LEGACY_FILENAME_COLLISIONS = 10_000
    }
}

/** Bounded, cookie-isolated clone of RN's process OkHttp client. */
internal object TipsyShareCdnClientPolicy {
    const val CONNECT_TIMEOUT_SECONDS = 15L
    const val READ_TIMEOUT_SECONDS = 30L
    const val WRITE_TIMEOUT_SECONDS = 30L
    const val CALL_TIMEOUT_MINUTES = 5L

    fun build(baseClient: OkHttpClient): OkHttpClient = baseClient.newBuilder()
        // RN 0.81's shared client deliberately uses zero/infinite timeouts. A gallery download is
        // user-triggered and must be bounded even if the server stalls after headers.
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        // Do not send shared cookies and do not let a CDN Set-Cookie mutate RN session state.
        .cookieJar(CookieJar.NO_COOKIES)
        // Last application interceptor removes credentials before cache/network processing.
        .addInterceptor { chain -> chain.proceed(chain.request().withoutTipsyCredentials()) }
        // Last network interceptor is the final wire-level guard after other interceptors/redirects.
        .addNetworkInterceptor { chain -> chain.proceed(chain.request().withoutTipsyCredentials()) }
        .build()
}

/**
 * 下载响应的有界验证。Content-Length 只能用于早拒绝（可能缺失或被 gzip 改写），
 * 实际流仍逐块计数；这样异常 CDN 响应不能无限写入公共相册。MIME 缺失或通用
 * octet-stream 允许继续，以兼容签名 CDN，但明确的 HTML/跨媒体响应会被拒绝。
 */
internal object TipsyShareMediaDownloadPolicy {
    const val MAX_IMAGE_BYTES = 32L * 1024L * 1024L
    const val MAX_VIDEO_BYTES = 256L * 1024L * 1024L

    fun validateResponse(
        content: TipsyShareContent,
        responseContentType: String?,
        responseContentLength: Long,
    ): Long {
        val maximumBytes = when (content.mediaType) {
            TipsyShareMediaType.IMAGE -> MAX_IMAGE_BYTES
            TipsyShareMediaType.VIDEO -> MAX_VIDEO_BYTES
        }
        if (responseContentLength > maximumBytes) {
            throw IOException("Share media response exceeds the size limit")
        }

        val mimeType = responseContentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
        if (mimeType != null && mimeType !in GENERIC_BINARY_MIME_TYPES) {
            val allowed = when (content.mediaType) {
                TipsyShareMediaType.IMAGE -> mimeType in IMAGE_MIME_TYPES
                TipsyShareMediaType.VIDEO -> mimeType in VIDEO_MIME_TYPES
            }
            if (!allowed) throw IOException("Share media response has an unexpected MIME type")
        }
        return maximumBytes
    }

    fun copyWithLimit(
        input: InputStream,
        output: OutputStream,
        maximumBytes: Long,
    ): Long {
        require(maximumBytes > 0L)
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            copied += read
            if (copied > maximumBytes) {
                throw IOException("Share media response exceeds the size limit")
            }
            output.write(buffer, 0, read)
        }
        if (copied == 0L) throw IOException("Share media response is empty")
        return copied
    }

    private val GENERIC_BINARY_MIME_TYPES = setOf(
        "application/octet-stream",
        "binary/octet-stream",
    )
    private val IMAGE_MIME_TYPES = setOf(
        "image/jpeg",
        "image/jpg",
        "image/png",
        "image/gif",
        "image/webp",
    )
    private val VIDEO_MIME_TYPES = setOf(
        "video/mp4",
        "application/mp4",
    )
    private const val COPY_BUFFER_BYTES = 64 * 1024
}

/** Pure naming/destination seam used by local tests; Android storage APIs stay at the leaf. */
internal data class TipsyShareMediaFileSpec(
    val mediaType: TipsyShareMediaType,
    val displayName: String,
    val mimeType: String,
    val relativePath: String,
    val legacyDirectory: String,
) {
    companion object {
        fun forContent(
            content: TipsyShareContent,
            timestampMillis: Long,
            responseContentType: String? = null,
        ): TipsyShareMediaFileSpec =
            when (content.mediaType) {
                TipsyShareMediaType.IMAGE -> imageFormat(
                    responseContentType = responseContentType,
                    mediaUrl = content.mediaUrl,
                ).let { format ->
                    TipsyShareMediaFileSpec(
                        mediaType = content.mediaType,
                        displayName = "tipsy_image_$timestampMillis.${format.extension}",
                        mimeType = format.mimeType,
                        relativePath = "$PICTURES_DIRECTORY/Tipsy",
                        legacyDirectory = PICTURES_DIRECTORY,
                    )
                }
                TipsyShareMediaType.VIDEO -> TipsyShareMediaFileSpec(
                    mediaType = content.mediaType,
                    displayName = "tipsy_video_$timestampMillis.mp4",
                    mimeType = "video/mp4",
                    relativePath = "$MOVIES_DIRECTORY/Tipsy",
                    legacyDirectory = MOVIES_DIRECTORY,
                )
            }

        private fun imageFormat(responseContentType: String?, mediaUrl: String): ImageFormat {
            val path = mediaUrl.substringBefore('?').substringBefore('#')
            val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
            // RN preserves a recognized URL file extension; do the same before consulting the
            // response header. Header is the fallback for extension-less/signed CDN routes.
            IMAGE_FORMATS.firstOrNull { extension in it.urlExtensions }?.let { return it }

            val headerMime = responseContentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase(Locale.ROOT)
            return IMAGE_FORMATS.firstOrNull { it.mimeType == headerMime } ?: DEFAULT_IMAGE_FORMAT
        }

        private data class ImageFormat(
            val extension: String,
            val mimeType: String,
            val urlExtensions: Set<String>,
        )

        private val DEFAULT_IMAGE_FORMAT = ImageFormat(
            extension = "jpg",
            mimeType = "image/jpeg",
            urlExtensions = setOf("jpg"),
        )
        private val IMAGE_FORMATS = listOf(
            DEFAULT_IMAGE_FORMAT,
            ImageFormat("jpeg", "image/jpeg", setOf("jpeg")),
            ImageFormat("png", "image/png", setOf("png")),
            ImageFormat("gif", "image/gif", setOf("gif")),
            ImageFormat("webp", "image/webp", setOf("webp")),
        )

        // Environment's directory fields are null stubs in local JVM tests. Their platform
        // contract values are stable literals, so keep the pure file-spec seam Android-free.
        private const val PICTURES_DIRECTORY = "Pictures"
        private const val MOVIES_DIRECTORY = "Movies"
    }
}

internal fun Request.withoutTipsyCredentials(): Request = newBuilder()
    .removeHeader("token")
    .removeHeader("Authorization")
    .removeHeader("Cookie")
    .removeHeader("X-Auth-Token")
    .build()

/** Pure suffix contract for API 24-28 atomic destination reservation. */
internal object TipsyShareLegacyFileNaming {
    fun candidate(displayName: String, collisionIndex: Int): String {
        require(displayName.isNotBlank())
        require(collisionIndex >= 0)
        if (collisionIndex == 0) return displayName

        val extensionStart = displayName.lastIndexOf('.').takeIf { it > 0 }
        val stem = extensionStart?.let { displayName.substring(0, it) } ?: displayName
        val extension = extensionStart?.let { displayName.substring(it) }.orEmpty()
        return "${stem}_$collisionIndex$extension"
    }
}
