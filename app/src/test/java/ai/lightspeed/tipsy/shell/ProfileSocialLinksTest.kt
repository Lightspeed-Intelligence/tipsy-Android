package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.profile.ProfileSocialLinks
import ai.lightspeed.tipsy.shell.user.CurrentUser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P7 渠道图标的展示层过滤，逐条对拍 RN
 * `SocialLinksDisplay.tsx` + `constants/socialPlatforms.ts`：
 * ① `display_status === VISIBLE` ② `isSupportedPlatform`
 * ③ `!isPlatformHidden`（kofi/patreon）。
 * 这类过滤错了不报错 —— 多显示是把用户隐藏的链接放出来，
 * 少显示是功能静默缺失，两个方向都要钉住。
 */
class ProfileSocialLinksTest {

    @Test
    fun `不可见的链接被滤掉`() {
        val links = listOf(
            link("discord", visible = true),
            link("twitter", visible = false),
        )
        assertEquals(listOf("discord"), ProfileSocialLinks.visibleLinks(links).map { it.platform })
    }

    @Test
    fun `未知平台静默跳过`() {
        // 后端加新平台时旧客户端不画未知图标（对齐 RN isSupportedPlatform 早退）
        val links = listOf(link("threads", visible = true), link("youtube", visible = true))
        assertEquals(listOf("youtube"), ProfileSocialLinks.visibleLinks(links).map { it.platform })
    }

    @Test
    fun `kofi 与 patreon 在隐藏名单`() {
        // HIDDEN_SOCIAL_PLATFORMS（socialPlatforms.ts:53-56）——即便可见也不展示
        val links = listOf(
            link("kofi", visible = true),
            link("patreon", visible = true),
            link("instagram", visible = true),
        )
        assertEquals(listOf("instagram"), ProfileSocialLinks.visibleLinks(links).map { it.platform })
    }

    @Test
    fun `保持 display_urls 原序`() {
        val links = listOf(
            link("youtube", visible = true),
            link("discord", visible = true),
            link("tiktok", visible = true),
        )
        assertEquals(
            listOf("youtube", "discord", "tiktok"),
            ProfileSocialLinks.visibleLinks(links).map { it.platform },
        )
    }

    @Test
    fun `九个支持平台都有图标资产`() {
        // iconRes 用 getValue（未知平台抛异常），这里钉住枚举与资产映射的完整性：
        // 有人删资产/删映射时在 JVM 层就失败，不用等到真机上图标静默缺位
        val supported = listOf(
            "discord", "instagram", "tiktok", "kofi", "patreon",
            "facebook", "wattpad", "twitter", "youtube",
        )
        supported.forEach { platform ->
            // 资源 id 是编译期生成的正整数；0 表示没有这个资源
            assertEquals("$platform 缺图标", true, ProfileSocialLinks.iconRes(platform) != 0)
        }
    }

    private fun link(platform: String, visible: Boolean): CurrentUser.SocialLink =
        CurrentUser.SocialLink(platform = platform, url = "https://example.com/$platform", visible = visible)
}
