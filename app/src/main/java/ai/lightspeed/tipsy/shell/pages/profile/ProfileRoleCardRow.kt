package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.R
import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
import ai.lightspeed.tipsy.shell.pages.home.HomeText
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/**
 * 角色卡 tab 的单列横条卡（`RoleCard.tsx` 的展示部分）：
 * 圆头像 + 昵称(+Default 标) + meta 行（性别 | 年龄 | 标签）。
 *
 * ## 本刀不做
 *
 * - **⋮ 菜单**（设默认/编辑/删除）与 `EditRoleCard` 跳转 —— 属动作包；
 *   RN 的编辑目标 `ProfileStack/EditRoleCard` 是不迁的 RN Surface（§8.0）
 * - **need_update 红点与升级提示**（`UserRoleCard` 场景专属，Profile 列表
 *   不消费）
 * - 超限提示（`isOverRoleCardLimit`，依赖订阅档位与 `RoleCardLimit` 弹窗，
 *   属 Surface 微根的全局件）
 */
@Composable
fun ProfileRoleCardRow(item: ProfileRoleCardItem, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = CARD_MARGIN_H.dp)
            .height(CARD_HEIGHT.dp)
            .clip(RoundedCornerShape(CARD_RADIUS.dp))
            .background(CARD_BACKGROUND)
            .padding(CARD_PADDING.dp)
            .testTag("profile_rolecard_${item.profileCardId}"),
    ) {
        // 头像：role_pic 解析见 item KDoc；空走内置占位（rolecard_avatar 未搬，
        // 先用底色圆 —— 视觉 diff 属验收阶段）
        Box(
            modifier = Modifier
                .size(AVATAR_SIZE.dp)
                .clip(CircleShape)
                .background(AVATAR_BACKGROUND),
        ) {
            if (!item.rolePicUrl.isNullOrBlank()) {
                AsyncImage(
                    model = HomeText.transformImageUrl(item.rolePicUrl),
                    contentDescription = item.nickname,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(AVATAR_SIZE.dp),
                )
            }
        }

        Column(Modifier.padding(start = TEXT_GAP.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.nickname.orEmpty(),
                    color = ProfileStyle.TEXT_PRIMARY,
                    fontSize = NAME_FONT.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.makeDefault) {
                    Text(
                        text = rememberLocalizedString("Default"),
                        color = DEFAULT_TAG_TEXT,
                        fontSize = DEFAULT_TAG_FONT.sp,
                        modifier = Modifier
                            .padding(start = DEFAULT_TAG_GAP.dp)
                            .clip(RoundedCornerShape(DEFAULT_TAG_RADIUS.dp))
                            .background(DEFAULT_TAG_BACKGROUND)
                            .padding(
                                horizontal = DEFAULT_TAG_PADDING_H.dp,
                                vertical = DEFAULT_TAG_PADDING_V.dp,
                            )
                            .testTag("profile_rolecard_default"),
                    )
                }
            }
            // meta 行 `性别 | 年龄 | 标签`，全空时显示 None（RN `metaText || t('None')`）
            val gender = item.genderKey?.let { rememberLocalizedString(it) }
            val meta = listOfNotNull(
                gender,
                item.age?.toString(),
                item.label,
            ).joinToString(META_SEPARATOR)
            Text(
                text = meta.ifEmpty { rememberLocalizedString("None") },
                color = META_TEXT,
                fontSize = META_FONT.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = META_GAP.dp),
            )
        }
    }
}

/** `' | '`（`RoleCard.tsx:74` 的 join）。 */
private const val META_SEPARATOR = " | "

// 尺寸对 `RoleCard.tsx:310-368` styles
private const val CARD_HEIGHT = 88
private const val CARD_RADIUS = 12
private const val CARD_MARGIN_H = 12
private const val CARD_PADDING = 12
private const val AVATAR_SIZE = 64
private const val TEXT_GAP = 12
private const val NAME_FONT = 15
private const val META_FONT = 14
private const val META_GAP = 4
private const val DEFAULT_TAG_FONT = 11
private const val DEFAULT_TAG_RADIUS = 13
private const val DEFAULT_TAG_GAP = 8
private const val DEFAULT_TAG_PADDING_H = 10
private const val DEFAULT_TAG_PADDING_V = 3

private val CARD_BACKGROUND = Color(0x14FFFFFF)
private val AVATAR_BACKGROUND = Color(0x14FFFFFF)
private val META_TEXT = Color(0x8FFFFFFF)

/** `rgba(248,160,41,0.18)` 底 + 橙字（`RoleCard.tsx:338-344`）。 */
private val DEFAULT_TAG_BACKGROUND = Color(0x2EF8A029)
private val DEFAULT_TAG_TEXT = Color(0xFFF8A029)
