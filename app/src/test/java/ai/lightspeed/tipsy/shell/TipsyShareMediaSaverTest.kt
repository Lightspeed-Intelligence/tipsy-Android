package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.share.TipsyShareContent
import ai.lightspeed.tipsy.shell.share.TipsyShareCdnClientPolicy
import ai.lightspeed.tipsy.shell.share.TipsyShareMediaDownloadPolicy
import ai.lightspeed.tipsy.shell.share.TipsyShareMediaFileSpec
import ai.lightspeed.tipsy.shell.share.TipsyShareMediaType
import ai.lightspeed.tipsy.shell.share.TipsyShareLegacyFileNaming
import ai.lightspeed.tipsy.shell.share.withoutTipsyCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request

class TipsyShareMediaSaverTest {
    @Test
    fun `CDN request 剥掉全部认证头但保留普通头`() {
        val sanitized = Request.Builder()
            .url("https://cdn.example/media.webp")
            .header("token", "secret-token")
            .header("Authorization", "Bearer secret")
            .header("Cookie", "session=secret")
            .header("X-Auth-Token", "secret-x")
            .header("User-Agent", "Tipsy-test")
            .build()
            .withoutTipsyCredentials()

        assertNull(sanitized.header("token"))
        assertNull(sanitized.header("Authorization"))
        assertNull(sanitized.header("Cookie"))
        assertNull(sanitized.header("X-Auth-Token"))
        assertEquals("Tipsy-test", sanitized.header("User-Agent"))
    }

    @Test
    fun `CDN client 覆盖 RN 无限 timeout 且禁用共享 cookie jar`() {
        val client = TipsyShareCdnClientPolicy.build(OkHttpClient())

        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
        assertEquals(30_000, client.writeTimeoutMillis)
        assertEquals(5 * 60_000, client.callTimeoutMillis)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
    }

    @Test
    fun `GIF URL 带 query 仍保留 gif 后缀与 MIME`() {
        val spec = imageSpec(
            url = "https://cdn.example/animated.GIF?signature=a.b",
            responseContentType = null,
        )

        assertEquals("tipsy_image_123.gif", spec.displayName)
        assertEquals("image/gif", spec.mimeType)
    }

    @Test
    fun `URL 扩展优先 保持 RN 保存文件语义`() {
        val spec = imageSpec(
            url = "https://cdn.example/asset.jpg",
            responseContentType = "image/webp; charset=binary",
        )

        assertEquals("tipsy_image_123.jpg", spec.displayName)
        assertEquals("image/jpeg", spec.mimeType)
    }

    @Test
    fun `无扩展 CDN route 用 HTTP Content Type 推断`() {
        val spec = imageSpec(
            url = "https://cdn.example/signed-asset?format=dynamic",
            responseContentType = "image/webp; charset=binary",
        )

        assertEquals("tipsy_image_123.webp", spec.displayName)
        assertEquals("image/webp", spec.mimeType)
    }

    @Test
    fun `支持 png jpeg 与 webp URL`() {
        val png = imageSpec("https://cdn.example/a.PNG", null)
        val jpeg = imageSpec("https://cdn.example/a.jpeg#fragment", null)
        val webp = imageSpec("https://cdn.example/a.webp", null)

        assertEquals("image/png", png.mimeType)
        assertEquals("tipsy_image_123.png", png.displayName)
        assertEquals("image/jpeg", jpeg.mimeType)
        assertEquals("tipsy_image_123.jpeg", jpeg.displayName)
        assertEquals("image/webp", webp.mimeType)
    }

    @Test
    fun `媒体格式归一化不受土耳其语系统 Locale 影响`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val gif = imageSpec("https://cdn.example/a.GIF", "IMAGE/GIF")
            val maximumBytes = TipsyShareMediaDownloadPolicy.validateResponse(
                content = content("https://cdn.example/a.GIF", TipsyShareMediaType.IMAGE),
                responseContentType = "IMAGE/GIF",
                responseContentLength = 128L,
            )

            assertEquals("image/gif", gif.mimeType)
            assertEquals(TipsyShareMediaDownloadPolicy.MAX_IMAGE_BYTES, maximumBytes)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `未知图片格式才回退 jpg`() {
        val spec = imageSpec(
            url = "https://cdn.example/image",
            responseContentType = "application/octet-stream",
        )

        assertEquals("tipsy_image_123.jpg", spec.displayName)
        assertEquals("image/jpeg", spec.mimeType)
    }

    @Test
    fun `视频落 Movies 并保持 mp4 契约`() {
        val spec = TipsyShareMediaFileSpec.forContent(
            content = content("https://cdn.example/video", TipsyShareMediaType.VIDEO),
            timestampMillis = 456L,
            responseContentType = "video/mp4",
        )

        assertEquals("tipsy_video_456.mp4", spec.displayName)
        assertEquals("video/mp4", spec.mimeType)
        assertEquals("Movies/Tipsy", spec.relativePath)
        assertEquals("Movies", spec.legacyDirectory)
    }

    @Test
    fun `旧系统同名目标使用 suffix 且保留扩展名`() {
        assertEquals(
            "tipsy_image_123.jpg",
            TipsyShareLegacyFileNaming.candidate("tipsy_image_123.jpg", 0),
        )
        assertEquals(
            "tipsy_image_123_1.jpg",
            TipsyShareLegacyFileNaming.candidate("tipsy_image_123.jpg", 1),
        )
        assertEquals("asset_2", TipsyShareLegacyFileNaming.candidate("asset", 2))
    }

    @Test
    fun `明确的跨媒体 MIME 响应会被拒绝`() {
        assertThrows(IOException::class.java) {
            TipsyShareMediaDownloadPolicy.validateResponse(
                content("https://cdn.example/image.jpg", TipsyShareMediaType.IMAGE),
                responseContentType = "text/html; charset=utf-8",
                responseContentLength = 128L,
            )
        }
        assertThrows(IOException::class.java) {
            TipsyShareMediaDownloadPolicy.validateResponse(
                content("https://cdn.example/video.mp4", TipsyShareMediaType.VIDEO),
                responseContentType = "image/png",
                responseContentLength = 128L,
            )
        }
    }

    @Test
    fun `Content Length 与实际流都受大小上限保护`() {
        val maximumBytes = 4L
        assertThrows(IOException::class.java) {
            TipsyShareMediaDownloadPolicy.validateResponse(
                content("https://cdn.example/image.jpg", TipsyShareMediaType.IMAGE),
                responseContentType = "image/jpeg",
                responseContentLength = TipsyShareMediaDownloadPolicy.MAX_IMAGE_BYTES + 1L,
            )
        }
        assertThrows(IOException::class.java) {
            TipsyShareMediaDownloadPolicy.copyWithLimit(
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)),
                output = ByteArrayOutputStream(),
                maximumBytes = maximumBytes,
            )
        }
    }

    @Test
    fun `缺 MIME 的小型非空流仍兼容签名 CDN`() {
        val content = content("https://cdn.example/signed", TipsyShareMediaType.VIDEO)
        val maximumBytes = TipsyShareMediaDownloadPolicy.validateResponse(
            content = content,
            responseContentType = null,
            responseContentLength = -1L,
        )
        val output = ByteArrayOutputStream()

        val copied = TipsyShareMediaDownloadPolicy.copyWithLimit(
            input = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
            output = output,
            maximumBytes = maximumBytes,
        )

        assertEquals(3L, copied)
        assertEquals(listOf<Byte>(1, 2, 3), output.toByteArray().toList())
    }

    private fun imageSpec(url: String, responseContentType: String?): TipsyShareMediaFileSpec =
        TipsyShareMediaFileSpec.forContent(
            content = content(url, TipsyShareMediaType.IMAGE),
            timestampMillis = 123L,
            responseContentType = responseContentType,
        )

    private fun content(url: String, type: TipsyShareMediaType) = TipsyShareContent(
        characterId = "c1",
        reelRouteId = "c1",
        videoId = null,
        mediaUrl = url,
        mediaType = type,
    )
}
