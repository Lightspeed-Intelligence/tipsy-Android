package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.R
import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
import ai.lightspeed.tipsy.shell.pages.home.HomeText
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.compose.LocalPlatformContext
import coil3.request.transformations

/**
 * 创作 tab 的三列网格卡（`CharacterGridItem.tsx` 2 千行里的展示部分；
 * story / game 卡的角标结构同构，`StoryItem.tsx` / `GameGridItem.tsx`）。
 *
 * ## P4 补齐的角标层（自下而上）
 *
 * 1. 封面（含模糊，条件见 [ProfileCreatedItem.shouldBlurCover]；模糊在
 *    Coil 解码层做，理由见 [CoverBlurTransformation]）
 * 2. 底部渐变 + 名称 + 计数行（曝光仅 character 且公开；消息数三种卡都有）
 * 3. 左上：审核角标（rejected/pending）＞ 私密锁 ＞ story/18+ 标签
 *    —— **同一位置三选一**，优先级是 RN 的三元链
 *    （`CharacterGridItem.tsx:780-812`）
 * 4. 右上：置顶 Pin（`is_pinned`）
 * 5. `final_hit < 2`：整卡换成「不可用」遮罩，其余全部不画
 *
 * ## P5 补齐的 ⋮ 菜单（[menu] 非 null 时）
 *
 * 动作矩阵按方案 §8.1（与 iOS 壳一致）：**角色=编辑/删除/置顶、
 * 故事=删除/置顶、游戏=置顶**。两处刻意不迁：
 * - **Share** —— 分享卡是 `ShareCharacter` 独立模态（427 行 + 分享基建），
 *   iOS 壳同样未迁；
 * - **圆形模糊揭开动画**（BlurView + Reanimated reveal）—— 纯视觉增强，
 *   壳用静态深色 scrim；动作网格布局照抄。
 *
 * ⚠️ 两条容易做反的 RN 真值：
 * - **遮罩卡（不可用）也显示 ⋮**（`isSelf &&` 块在 `isMasked` 三元**之外**，
 *   `CharacterGridItem.tsx:655`）—— 这是用户删除不可用角色的唯一途径；
 * - more 按钮必须**吃掉点击**（iOS 踩过装饰 View 穿透进详情页）——
 *   Compose `clickable` 天然消费事件。
 *
 * ## 本刀仍不做
 *
 * - **卡片点击进聊天** —— ChatDetail 已启用（P9），但创作卡点击要先种
 *   chat preload（`handleClick` 里那套 `chatPreloadStore`），属独立入口包
 * - 比赛 winner 徽章（`useCharacterDisplayBadge`，运营配置源）与水印
 *   （`watermarkRenderKind`，依赖水印配置 hydrate）
 *
 * @param menu P5 菜单回调组；null = 不渲染 ⋮（他人主页复用本组件时不传，
 *   对齐 RN 的 `isSelf &&`）
 */
@Composable
fun ProfileGridItem(
    item: ProfileCreatedItem,
    modifier: Modifier = Modifier,
    menu: ProfileCardMenuHooks? = null,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(CARD_RADIUS.dp))
            .background(ProfileStyle.CARD_PLACEHOLDER)
            // dedupeKey 是服务端 id（game 带前缀），稳定且不含用户文本（方案 §9.4）
            .testTag("profile_created_card_${item.dedupeKey}"),
    ) {
        if (item.isMaskedUnavailable) {
            // final_hit < 2：整卡不可用，遮罩之外只留 ⋮ 菜单（见类注释）
            UnavailableMask()
        } else {
            CardContent(item)
        }

        if (menu != null) {
            MenuTrigger(
                onClick = menu.onOpen,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
            if (menu.isOpen) {
                CardMenuOverlay(item = item, menu = menu)
            }
        }
    }
}

/** 卡片正常内容（P4 的四层）。P5 抽出为函数：遮罩卡也要渲染菜单层。 */
@Composable
private fun BoxScope.CardContent(item: ProfileCreatedItem) {
        val cover = item.coverUrl
        if (!cover.isNullOrBlank()) {
            val url = HomeText.transformImageUrl(cover)
            AsyncImage(
                // 模糊是解码层变换：全 API 版本一致，且与原图各占一份内存缓存
                model = if (item.shouldBlurCover) {
                    ImageRequest.Builder(LocalPlatformContext.current)
                        .data(url)
                        .transformations(CoverBlurTransformation())
                        .build()
                } else {
                    url
                },
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 底部渐变 + 名称 + 计数行。渐变是为了让白字在任意封面上都可读
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xCC000000)),
                    ),
                )
                .padding(horizontal = CARD_TEXT_PADDING.dp, vertical = CARD_TEXT_PADDING.dp),
        ) {
            Text(
                text = item.name.orEmpty(),
                color = ProfileStyle.CARD_TITLE,
                fontSize = CARD_TITLE_FONT.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(META_GAP.dp),
                modifier = Modifier.padding(top = META_TOP_GAP.dp),
            ) {
                // 曝光数：仅 character 卡且公开（CharacterGridItem.tsx:619 `isSelf && is_public`；
                // story/game 卡没有这一项）
                if (item.type == ProfileItemType.CHARACTER && item.isPublic) {
                    MetaCount(
                        icon = R.drawable.ic_profile_exposure,
                        text = ProfileText.formatCountMaxThreeDigits(item.exposureCount),
                    )
                }
                MetaCount(
                    icon = R.drawable.ic_profile_msg_count,
                    // character 卡是三位有效数字缩写；story/game 卡走 formatNumber
                    //（Home 的 formatMessageCount 同源，StoryItem.tsx:656 / GameGridItem.tsx:339）
                    text = if (item.type == ProfileItemType.CHARACTER) {
                        ProfileText.formatCountMaxThreeDigits(item.messageCount)
                    } else {
                        HomeText.formatMessageCount(item.messageCount)
                    },
                )
            }
        }

        // 左上角标：审核 ＞ 私密锁 ＞ story/18+ 标签（三选一，见类注释）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BADGE_GAP.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(BADGE_MARGIN.dp),
        ) {
            val reviewBadge = item.reviewBadge
            when {
                reviewBadge != null -> ReviewBadge(reviewBadge)
                !item.isPublic -> Image(
                    painter = painterResource(R.drawable.ic_profile_lock),
                    contentDescription = rememberLocalizedString("Private"),
                    modifier = Modifier
                        .size(LOCK_ICON.dp)
                        .testTag("profile_card_lock"),
                )
                else -> {
                    if (item.showStoryTag) {
                        Image(
                            painter = painterResource(R.drawable.ic_profile_tag_story),
                            contentDescription = null, // 装饰性类型标
                            modifier = Modifier.size(STORY_TAG_ICON.dp),
                        )
                    }
                    if (item.showNsfwTag) {
                        Image(
                            painter = painterResource(R.drawable.ic_profile_tag_18),
                            contentDescription = "18+",
                            modifier = Modifier
                                .width(NSFW_TAG_W.dp)
                                .height(NSFW_TAG_H.dp),
                        )
                    }
                }
            }
        }

        // 右上：置顶 Pin（statusIconRow，top 4 / right 4）
        if (item.isPinned) {
            Image(
                painter = painterResource(R.drawable.ic_profile_pin),
                contentDescription = rememberLocalizedString("Pin"),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(PIN_MARGIN.dp)
                    .size(PIN_ICON.dp)
                    .testTag("profile_card_pin"),
            )
        }
}

/**
 * P5 菜单的回调组。抽成一个参数对象而不是四个散参：调用点（两处网格）
 * 少抄一遍，且「传了 onOpen 忘了 onPin」这类漏接从类型上排除。
 */
data class ProfileCardMenuHooks(
    /** 本卡菜单是否展开（`state.openMenuKey == item.dedupeKey`）。 */
    val isOpen: Boolean,
    /** 置顶请求在飞（`state.pinningKey == item.dedupeKey`），Pin 键禁用。 */
    val isPinning: Boolean,
    val onOpen: () -> Unit,
    val onDismiss: () -> Unit,
    val onEdit: () -> Unit,
    val onDelete: () -> Unit,
    val onTogglePin: () -> Unit,
)

/**
 * 右下 ⋮ 按钮（`menuTriggerContainer`：right 4 / bottom 12，图 24）。
 * ⚠️ 必须吃掉点击（clickable 天然消费）—— iOS 的装饰 View 穿透教训。
 */
@Composable
private fun MenuTrigger(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_profile_card_more),
        // 无障碍标签裸英文（ChatList 顶栏图标同例）；RN 侧该按钮无 accessibilityLabel
        contentDescription = "More actions",
        modifier = modifier
            .padding(end = MENU_TRIGGER_MARGIN_END.dp, bottom = MENU_TRIGGER_MARGIN_BOTTOM.dp)
            .size(MENU_TRIGGER_ICON.dp)
            .clickable(onClick = onClick)
            .testTag("profile_card_more"),
    )
}

/**
 * 卡内菜单浮层（`CharacterGridItem.tsx:674-776` 的静态形状）。
 *
 * 动作矩阵（方案 §8.1）：角色=编辑/删除/置顶、故事=删除/置顶、游戏=置顶。
 * Share 与圆形模糊揭开动画刻意不迁（见 [ProfileGridItem] 类注释）。
 * 布局照 RN：整卡 scrim（点击关闭）+ 居中动作网格（两列、48% 宽、
 * rowGap 18、图标 24、文案 10sp 白）。
 */
@Composable
private fun BoxScope.CardMenuOverlay(item: ProfileCreatedItem, menu: ProfileCardMenuHooks) {
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .matchParentSize()
            // scrim 本体即关闭热区（RN 的 absoluteFill Pressable + 黑 60% 遮罩）
            .clickable(onClick = menu.onDismiss)
            .background(MENU_SCRIM)
            .padding(horizontal = MENU_GRID_PADDING_H.dp, vertical = MENU_GRID_PADDING_V.dp)
            .testTag("profile_card_menu"),
    ) {
        // flexWrap 两列：动作最多 3 个，手工分行（每行两个）比引入 FlowRow 直白
        val actions = menuActionsFor(item, menu)
        actions.chunked(2).forEach { rowActions ->
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = MENU_ROW_GAP.dp),
            ) {
                rowActions.forEach { action ->
                    MenuAction(action, modifier = Modifier.weight(1f))
                }
                // 奇数个动作时补占位，保持 48% 两列的布局（SpaceBetween 不拉伸单元素）
                if (rowActions.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private data class MenuActionSpec(
    val icon: Int,
    /** i18n key = 英文原文。 */
    val labelKey: String,
    val enabled: Boolean,
    val onClick: () -> Unit,
    val testTag: String,
)

/** 动作矩阵（见 [CardMenuOverlay]）。顺序照 RN：编辑 → 删除 → 置顶。 */
private fun menuActionsFor(
    item: ProfileCreatedItem,
    menu: ProfileCardMenuHooks,
): List<MenuActionSpec> = buildList {
    if (item.type == ProfileItemType.CHARACTER) {
        add(
            MenuActionSpec(
                icon = R.drawable.ic_profile_menu_edit,
                labelKey = "Edit",
                enabled = true,
                onClick = menu.onEdit,
                testTag = "profile_card_menu_edit",
            ),
        )
    }
    if (item.type != ProfileItemType.GAME) {
        add(
            MenuActionSpec(
                // RN 的 card_delete.png 与 Search 清除历史是同一张图
                //（lint IconDuplicates 实测抓出），复用已入库的那份
                icon = R.drawable.ic_search_history_clear,
                labelKey = "Delete",
                enabled = true,
                onClick = menu.onDelete,
                testTag = "profile_card_menu_delete",
            ),
        )
    }
    add(
        MenuActionSpec(
            icon = if (item.isPinned) {
                R.drawable.ic_profile_menu_unpin
            } else {
                R.drawable.ic_profile_menu_pin
            },
            labelKey = if (item.isPinned) "Unpin" else "Pin",
            enabled = !menu.isPinning,
            onClick = menu.onTogglePin,
            testTag = "profile_card_menu_pin",
        ),
    )
}

@Composable
private fun MenuAction(spec: MenuActionSpec, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .alpha(if (spec.enabled) 1f else MENU_DISABLED_ALPHA)
            .clickable(enabled = spec.enabled, onClick = spec.onClick)
            .testTag(spec.testTag),
    ) {
        Image(
            painter = painterResource(spec.icon),
            contentDescription = null, // 文案就在下方
            modifier = Modifier.size(MENU_ACTION_ICON.dp),
        )
        Text(
            text = rememberLocalizedString(spec.labelKey),
            color = Color.White,
            fontSize = MENU_ACTION_FONT.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = MENU_ACTION_TEXT_GAP.dp),
        )
    }
}

/**
 * 审核角标（`ReviewStatusBadge.tsx`）：黑 40% 圆角胶囊，图标 + 文案。
 * pending/rejected 各带图标；approved 在 RN 里不渲染（上游已判 null）。
 */
@Composable
private fun ReviewBadge(badge: ProfileReviewBadge) {
    val (icon, key) = when (badge) {
        ProfileReviewBadge.PENDING -> R.drawable.ic_profile_review_pending to "Pending"
        ProfileReviewBadge.REJECTED -> R.drawable.ic_profile_review_fail to "Rejected"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(REVIEW_BADGE_RADIUS.dp))
            .background(REVIEW_BADGE_BACKGROUND)
            .padding(end = REVIEW_BADGE_PADDING_END.dp)
            .testTag("profile_card_review_${key.lowercase()}"),
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null, // 文案就在旁边
            modifier = Modifier.size(REVIEW_ICON.dp),
        )
        Text(
            text = rememberLocalizedString(key),
            color = REVIEW_BADGE_TEXT,
            fontSize = REVIEW_BADGE_FONT.sp,
        )
    }
}

/**
 * `final_hit < 2` 的不可用遮罩（`CharacterGridItem.tsx:548-559`）：
 * 白 6% 底 + 锁 + `Currently unavailable`。
 * ⚠️ 该词条是 key≠value 的实例（en 值是 "More to come"，脚本注释点过名）——
 * 恰好证明「运行时不得拿 key 当英文文案」，必须走 L10n。
 */
@Composable
private fun UnavailableMask() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MASK_GAP.dp, Alignment.CenterVertically),
        modifier = Modifier
            .fillMaxSize()
            .background(MASK_BACKGROUND)
            .padding(horizontal = MASK_PADDING_H.dp)
            .testTag("profile_card_unavailable"),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_profile_lock),
            contentDescription = null,
            modifier = Modifier.size(MASK_LOCK_ICON.dp),
        )
        Text(
            text = rememberLocalizedString("Currently unavailable"),
            color = ProfileStyle.TEXT_SECONDARY,
            fontSize = MASK_FONT.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MetaCount(icon: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(icon),
            contentDescription = null, // 数字紧随其后
            modifier = Modifier.size(META_ICON.dp),
        )
        Text(
            text = text,
            color = META_TEXT,
            fontSize = META_FONT.sp,
            modifier = Modifier.padding(start = META_ICON_GAP.dp),
        )
    }
}

private const val CARD_RADIUS = 8
private const val CARD_TITLE_FONT = 12
private const val CARD_TEXT_PADDING = 6

// 计数行（characterMetaIcon 13 / characterMetaText 10sp 白 70%）
private const val META_ICON = 13
private const val META_FONT = 10
private const val META_GAP = 8
private const val META_ICON_GAP = 3
private const val META_TOP_GAP = 2
private val META_TEXT = Color(0xB3FFFFFF)

// 左上角标（reviewBadge top/left 10 → 统一 margin；lock/story 18、18+ 26×16）
private const val BADGE_MARGIN = 6
private const val BADGE_GAP = 4
private const val LOCK_ICON = 18
private const val STORY_TAG_ICON = 18
private const val NSFW_TAG_W = 26
private const val NSFW_TAG_H = 16
private const val REVIEW_ICON = 16
private const val REVIEW_BADGE_FONT = 10
private const val REVIEW_BADGE_RADIUS = 100
private const val REVIEW_BADGE_PADDING_END = 6
private val REVIEW_BADGE_BACKGROUND = Color(0x66000000)
private val REVIEW_BADGE_TEXT = Color(0xCCFFFFFF)

// 右上 Pin（statusIconRow top/right 4，图标 18）
private const val PIN_MARGIN = 4
private const val PIN_ICON = 18

// 不可用遮罩（maskCover：白 6% / gap 8 / paddingH 8）
private const val MASK_GAP = 8
private const val MASK_PADDING_H = 8
private const val MASK_LOCK_ICON = 24
private const val MASK_FONT = 12
private val MASK_BACKGROUND = Color(0x0FFFFFFF)

// P5 ⋮ 菜单（menuTrigger right4/bottom12 图24；menuGrid paddingH10/V18；
// menuGridContent rowGap18；menuAction 图24/文10sp；disabled opacity 0.4；
// scrim = menuOverlayMask 黑 60%）
private const val MENU_TRIGGER_ICON = 24
private const val MENU_TRIGGER_MARGIN_END = 4
private const val MENU_TRIGGER_MARGIN_BOTTOM = 12
private const val MENU_GRID_PADDING_H = 10
private const val MENU_GRID_PADDING_V = 18
private const val MENU_ROW_GAP = 18
private const val MENU_ACTION_ICON = 24
private const val MENU_ACTION_FONT = 10
private const val MENU_ACTION_TEXT_GAP = 10
private const val MENU_DISABLED_ALPHA = 0.4f
private val MENU_SCRIM = Color(0x99000000)
