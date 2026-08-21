package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.screen.resolveScreenTagline
import org.junit.Assert.assertEquals
import org.junit.Test

/** Screen tagline 展示文本对拍 RN `FeedMediaItem.overlayTagline`。 */
class ScreenTaglineTest {

    @Test
    fun `已翻译简介按当前语言替换 user`() {
        assertEquals(
            "Mia 想见到你",
            resolveScreenTagline(
                tagline = "{{char}} 想见到{{user}}",
                nickname = "Mia",
                isTranslated = true,
                languageCode = "zh-tw",
                isGooglePlay = false,
            ),
        )
    }

    @Test
    fun `未翻译简介固定使用英文 you`() {
        assertEquals(
            "Mia wants you",
            resolveScreenTagline(
                tagline = "{{char}} wants {{user}}",
                nickname = "Mia",
                isTranslated = false,
                languageCode = "zh-tw",
                isGooglePlay = false,
            ),
        )
    }

    @Test
    fun `Google Play 在占位符替换后执行敏感词打码`() {
        assertEquals(
            "Mia says s*x",
            resolveScreenTagline(
                tagline = "{{char}} says sex",
                nickname = "Mia",
                isTranslated = false,
                languageCode = "en",
                isGooglePlay = true,
            ),
        )
    }
}
