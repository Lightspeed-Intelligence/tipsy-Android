package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.screen.ScreenFeedItem
import ai.lightspeed.tipsy.shell.pages.screen.share.toTipsyShareContent
import ai.lightspeed.tipsy.shell.share.TipsyShareMediaType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenShareContentTest {

    @Test
    fun `showcase 冻结 character 路径但不伪造 video id`() {
        val item = ScreenFeedItem.parse(
            JSONObject(
                """
                {
                  "character": {
                    "character_id": "character/42",
                    "nickname": "A & B",
                    "image_url": "https://cdn.example/cover.jpg"
                  },
                  "creator": {},
                  "stats": {},
                  "greeting_video": {
                    "video_url": "https://cdn.example/movie.mp4?a=1&b=2",
                    "cover_url": "https://cdn.example/poster.webp"
                  }
                }
                """.trimIndent(),
            ),
        )!!

        val content = item.toTipsyShareContent()!!

        assertEquals("character/42", content.characterId)
        assertEquals("character/42", content.reelRouteId)
        assertNull("feed 没有真实视频 id，不能拿 character id 冒充", content.videoId)
        assertEquals(TipsyShareMediaType.VIDEO, content.mediaType)
        assertEquals("https://cdn.example/movie.mp4?a=1&b=2", content.mediaUrl)
        assertEquals("https://cdn.example/poster.webp", content.thumbnailUrl)
        assertEquals("A & B", content.characterName)
    }

    @Test
    fun `animated image 仍按 RN share content type 作为图片`() {
        val item = ScreenFeedItem.parse(
            JSONObject(
                """
                {
                  "character": {
                    "character_id": "animated-1",
                    "animated_image_url": "https://cdn.example/scene.webp",
                    "image_url": "https://cdn.example/cover.jpg"
                  },
                  "creator": {},
                  "stats": {}
                }
                """.trimIndent(),
            ),
        )!!

        assertEquals(TipsyShareMediaType.IMAGE, item.toTipsyShareContent()!!.mediaType)
    }

    @Test
    fun `没有任何媒体 URL 时不打开分享面板`() {
        val item = ScreenFeedItem.parse(
            JSONObject(
                """
                {
                  "character": {"character_id": "empty-media"},
                  "creator": {},
                  "stats": {}
                }
                """.trimIndent(),
            ),
        )!!

        assertNull(item.toTipsyShareContent())
    }
}
