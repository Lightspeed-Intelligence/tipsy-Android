package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.share.TipsyShareContent
import ai.lightspeed.tipsy.shell.share.TipsyShareMediaType
import ai.lightspeed.tipsy.shell.share.TipsyShareUriRenderer
import ai.lightspeed.tipsy.shell.share.TipsyShareUrlBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TipsyShareUrlBuilderTest {
    private val pureJvmRenderer = TipsyShareUriRenderer { base, path, query ->
        "$base/$path?$query"
    }

    @Test
    fun `编码 route 与媒体查询值并移除 base 尾斜杠`() {
        val builder = TipsyShareUrlBuilder(" https://tipsy.chat/// ", pureJvmRenderer)
        val url = builder.reelUrl(
            content(
                reelRouteId = "角色/ a",
                mediaUrl = "https://cdn.example/a b.gif?sig=x&y=1",
                thumbnailUrl = "https://cdn.example/p 1.jpg",
                characterName = "A&B 角色",
            ),
        )

        assertEquals(
            "https://tipsy.chat/reel/" +
                "%E8%A7%92%E8%89%B2%2F%20a?" +
                "from_share=true&" +
                "v=https%3A%2F%2Fcdn.example%2Fa+b.gif%3Fsig%3Dx%26y%3D1&" +
                "p=https%3A%2F%2Fcdn.example%2Fp+1.jpg&" +
                "n=A%26B+%E8%A7%92%E8%89%B2",
            url,
        )
    }

    @Test
    fun `可选字段空白时省略且身份字段不会混进 query`() {
        val builder = TipsyShareUrlBuilder("https://example.com", pureJvmRenderer)
        val url = builder.reelUrl(
            content(
                reelRouteId = "character-route",
                mediaUrl = "https://cdn.example/video.mp4",
                thumbnailUrl = " ",
                characterName = null,
                characterId = "character-moderation-id",
                videoId = "real-video-id",
            ),
        )

        assertEquals(
            "https://example.com/reel/character-route?" +
                "from_share=true&v=https%3A%2F%2Fcdn.example%2Fvideo.mp4",
            url,
        )
        assertFalse(url.contains("character-moderation-id"))
        assertFalse(url.contains("real-video-id"))
    }

    @Test
    fun `分享文本是本地化文案换行再接 reel URL`() {
        val builder = TipsyShareUrlBuilder("https://tipsy.chat/", pureJvmRenderer)
        val content = content(reelRouteId = "c1", mediaUrl = "https://cdn.example/a.webp")

        assertEquals(
            "来看看这个角色\n" +
                "https://tipsy.chat/reel/c1?" +
                "from_share=true&v=https%3A%2F%2Fcdn.example%2Fa.webp",
            builder.shareText("来看看这个角色", content),
        )
    }

    @Test
    fun `编码器按 UTF8 字节并保留 Android Uri 安全字符`() {
        val encoded = TipsyShareUrlBuilder.encodeComponent("a z/你_-.!~'()*")

        assertEquals("a%20z%2F%E4%BD%A0_-.!~'()*", encoded)
        assertTrue(encoded.contains("%E4%BD%A0"))
    }

    @Test
    fun `query 编码逐字对齐 RN URLSearchParams`() {
        val encoded = TipsyShareUrlBuilder.encodeQueryComponent("a b!'()~*_-.你")

        assertEquals("a+b%21%27%28%29%7E*_-.%E4%BD%A0", encoded)
    }

    private fun content(
        reelRouteId: String,
        mediaUrl: String,
        thumbnailUrl: String? = null,
        characterName: String? = null,
        characterId: String? = "character-id",
        videoId: String? = null,
    ) = TipsyShareContent(
        characterId = characterId,
        reelRouteId = reelRouteId,
        videoId = videoId,
        mediaUrl = mediaUrl,
        mediaType = TipsyShareMediaType.IMAGE,
        thumbnailUrl = thumbnailUrl,
        characterName = characterName,
    )
}
