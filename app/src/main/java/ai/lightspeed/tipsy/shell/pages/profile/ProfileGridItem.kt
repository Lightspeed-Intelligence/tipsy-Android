package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.pages.home.HomeText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/**
 * 创作列表的一张卡片（三种 item 类型共用）。
 *
 * ## 三种类型目前只在文案上有差异
 *
 * RN 侧 `CharacterGridItem`(1106) / `StoryItem`(1026) / `GameGridItem`(585)
 * 是三个独立组件，差异主要在**菜单动作**（角色=编辑/删除/置顶、故事=删除/置顶、
 * 游戏=置顶）与角标。本刀不做菜单与动作，所以三种类型的**展示**是同构的 ——
 * 用一个 composable，按 [ProfileCreatedItem.type] 取不同字段（已在解析层抹平）。
 *
 * 后续包接菜单时再按类型分流，那时才有必要拆开。
 *
 * ## ⚠️ 未做：NSFW 封面模糊
 *
 * RN 侧对 18+ 封面加 `BlurView`（Android 走 `dimezisBlurMethod`，
 * `CharacterGridItem.tsx:581`）。且注释明说：因为 `config_persist.nsfw` 恒为
 * `false`（后端为权威源单向镜像），**所有 18+ 角色封面一律模糊**。
 * 本刀不实现 —— 需要先决定 Compose 侧的模糊方案（`RenderEffect` 要 API 31+）。
 * 记在这里免得当成漏实现：**自己创作的 18+ 角色封面目前不打码**。
 */
@Composable
fun ProfileGridItem(
    item: ProfileCreatedItem,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(CARD_RADIUS.dp))
            .background(ProfileStyle.CARD_PLACEHOLDER)
            // dedupeKey 是服务端 id（game 带前缀），稳定且不含用户文本（方案 §9.4）
            .testTag("profile_created_card_${item.dedupeKey}"),
    ) {
        val cover = item.coverUrl
        if (!cover.isNullOrBlank()) {
            AsyncImage(
                // 复用 Home 的 CDN 变换：只对 *.tipsy.chat 生效且跳过 mp4，
                // 非匹配 URL 原样返回（见 HomeText.transformImageUrl）
                model = HomeText.transformImageUrl(cover),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 底部渐变 + 名称。渐变是为了让白字在任意封面上都可读 ——
        // 没有它，浅色封面上的名字会看不见
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0x99000000)),
                    ),
                ),
        ) {
            Text(
                text = item.name.orEmpty(),
                color = ProfileStyle.CARD_TITLE,
                fontSize = CARD_TITLE_FONT.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CARD_TEXT_PADDING.dp, vertical = CARD_TEXT_PADDING.dp),
            )
        }
    }
}

private const val CARD_RADIUS = 8
private const val CARD_TITLE_FONT = 12
private const val CARD_TEXT_PADDING = 6
