package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.R
import ai.lightspeed.tipsy.shell.user.CurrentUser
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * Profile 头部昵称下方的社交平台图标行（P7 渠道图标，
 * RN `SocialLinksDisplay.tsx` + `constants/socialPlatforms.ts`）。
 *
 * ## 三层过滤，顺序照 RN
 *
 * 1. `display_status == VISIBLE`（解析期已折成 [CurrentUser.SocialLink.visible]）；
 * 2. 平台在支持名单（9 个，`SocialPlatform` 枚举）—— 未知平台静默跳过，
 *    后端加新平台时旧客户端不画未知图标；
 * 3. 平台不在隐藏名单 —— `HIDDEN_SOCIAL_PLATFORMS = [kofi, patreon]`
 *    （`socialPlatforms.ts:53-56`，RN 注释说"暂时隐藏"）。⚠️ 名单变了要
 *    两端一起改，壳侧只改这里的 [HIDDEN_PLATFORMS]。
 *
 * 点击行为对齐 RN `WebBrowser.openBrowserAsync`：经回调交 Fragment 用
 * `ACTION_VIEW` 打开（同 `SettingsFragment.openExternalUrl` 的既有先例，
 * 含无浏览器设备的 [android.content.ActivityNotFoundException] 防崩）。
 */
object ProfileSocialLinks {

    /** RN `SocialPlatform` 九枚举 → 壳资产。资产名 `ic_profile_social_<platform>`。 */
    private val ICONS: Map<String, Int> = mapOf(
        "discord" to R.drawable.ic_profile_social_discord,
        "instagram" to R.drawable.ic_profile_social_instagram,
        "tiktok" to R.drawable.ic_profile_social_tiktok,
        "kofi" to R.drawable.ic_profile_social_kofi,
        "patreon" to R.drawable.ic_profile_social_patreon,
        "facebook" to R.drawable.ic_profile_social_facebook,
        "wattpad" to R.drawable.ic_profile_social_wattpad,
        "twitter" to R.drawable.ic_profile_social_twitter,
        "youtube" to R.drawable.ic_profile_social_youtube,
    )

    /** `HIDDEN_SOCIAL_PLATFORMS`（`socialPlatforms.ts:53-56`）。 */
    private val HIDDEN_PLATFORMS = setOf("kofi", "patreon")

    /** 展示层过滤（见类注释三层）。保持入参顺序 —— RN 按 `display_urls` 原序渲染。 */
    fun visibleLinks(links: List<CurrentUser.SocialLink>): List<CurrentUser.SocialLink> =
        links.filter { it.visible && it.platform in ICONS && it.platform !in HIDDEN_PLATFORMS }

    /** 平台图标资源；仅对 [visibleLinks] 的产出调用（未知平台已被滤掉）。 */
    fun iconRes(platform: String): Int = ICONS.getValue(platform)
}

/**
 * 图标行本体。空表不占位（RN `activeLinks.length === 0` 返回 null，
 * 外层 `gap` 因此不产生多余空隙）—— 调用方负责按空表跳过本组件。
 */
@Composable
fun ProfileSocialLinksRow(
    links: List<CurrentUser.SocialLink>,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.testTag("profile_social_links"),
        horizontalArrangement = Arrangement.spacedBy(SOCIAL_ICON_GAP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        links.forEach { link ->
            Image(
                painter = painterResource(ProfileSocialLinks.iconRes(link.platform)),
                // 平台名作无障碍标签；RN 侧无文案，这里不引入新词条
                contentDescription = link.platform,
                modifier = Modifier
                    .size(SOCIAL_ICON_SIZE.dp)
                    .clickable { onLinkClick(link.url) }
                    .testTag("profile_social_${link.platform}"),
            )
        }
    }
}

/** `SocialLinksDisplay.tsx` styles：icon 20×20、row gap 12。 */
private const val SOCIAL_ICON_SIZE = 20
private const val SOCIAL_ICON_GAP = 12
