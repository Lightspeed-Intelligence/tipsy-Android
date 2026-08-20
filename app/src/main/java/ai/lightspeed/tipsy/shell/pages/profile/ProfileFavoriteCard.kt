package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.R
import ai.lightspeed.tipsy.shell.pages.home.HomeText
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 收藏 / 点赞 tab 的三列网格卡（`FavoriteCharacterCard.tsx` 的展示部分，
 * 两个 tab 共用 —— RN 里 LIKED tab 渲染的也是这个组件）。
 *
 * 结构与创作卡同构（封面 + 底部渐变 + 名称 + 消息数），差异：
 * - 无角标层（收藏/点赞列表没有审核/置顶/私密概念）
 * - 消息数字段是 `message_num`、走 `formatNumber`（= Home formatMessageCount）
 * - 模糊只看 `nsfw` 一个条件（`FavoriteCharacterCard.tsx:242`；
 *   RN 这里 intensity 25 与创作卡 40 不同，壳复用同一变换 —— 见 item KDoc）
 *
 * 本刀不做：⋮ 菜单（取消收藏/批量管理）—— 属动作包；卡片点击进详情 ——
 * 目标页未启用。
 */
@Composable
fun ProfileFavoriteCard(item: ProfileFavoriteItem, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(CARD_RADIUS.dp))
            .background(ProfileStyle.CARD_PLACEHOLDER)
            .testTag("profile_favorite_card_${item.characterId}"),
    ) {
        if (!item.imageUrl.isNullOrBlank()) {
            val url = HomeText.transformImageUrl(item.imageUrl)
            ProfileCoverImage(
                url = url,
                contentDescription = item.nickname,
                shouldBlur = item.nsfw,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))),
                )
                .padding(CARD_TEXT_PADDING.dp),
        ) {
            Text(
                text = item.nickname.orEmpty(),
                color = ProfileStyle.CARD_TITLE,
                fontSize = CARD_TITLE_FONT.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = META_TOP_GAP.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_profile_msg_count),
                    contentDescription = null, // 数字紧随其后
                    modifier = Modifier.size(META_ICON.dp),
                )
                Text(
                    text = HomeText.formatMessageCount(item.messageCount),
                    color = META_TEXT,
                    fontSize = META_FONT.sp,
                    modifier = Modifier.padding(start = META_ICON_GAP.dp),
                )
            }
        }
    }
}

private const val CARD_RADIUS = 8
private const val CARD_TITLE_FONT = 12
private const val CARD_TEXT_PADDING = 6

// 计数行（FavoriteCharacterCard 的 message 图标是 10×10，比创作卡小）
private const val META_ICON = 10
private const val META_FONT = 10
private const val META_ICON_GAP = 3
private const val META_TOP_GAP = 2
private val META_TEXT = Color(0xB3FFFFFF)
