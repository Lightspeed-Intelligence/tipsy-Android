package ai.lightspeed.tipsy.shell.pages.home

import ai.lightspeed.tipsy.shell.BuildConfig
import ai.lightspeed.tipsy.shell.R
import ai.lightspeed.tipsy.shell.i18n.rememberCurrentLanguage
import ai.lightspeed.tipsy.shell.ui.s
import ai.lightspeed.tipsy.shell.ui.sSp
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * Home 的角色 / 故事 / World 卡片（对齐 RN `components/home/HomeCard.tsx` +
 * `HomeStoryCard.tsx` + `adaptSimulatorGameToHomeCard.ts`）。
 *
 * ## 三种 item 一个 Composable
 *
 * RN 侧是三个组件（HomeCard / HomeStoryCard / 适配成 HomeCard 的 World），
 * 但**视觉结构完全相同**：封面铺满 + 顶部渐变 + 底部渐变 + 底部信息区。
 * 差异只在字段来源与角标。三份实现会漂移（RN 侧的 HomeCard 与 HomeStoryCard
 * 已经在 `bottomRow` 上不一致了）。
 *
 * ## 图片用 coil3，**不新增依赖**
 *
 * `io.coil-kt.coil3:coil 3.0.4` 已在依赖树里（`react-native-screens` 引入，
 * 已核实其 `android/build.gradle:249-253`）。壳显式声明同一版本，
 * 与 mmkv / coroutines 同性质：**版本是与 RN 侧的耦合约束**，不是"越新越好"。
 *
 * ⚠️ 不用 expo-image 那条链（Glide）—— 那是 RN 模块的内部依赖，壳去用它等于
 * 依赖 RN 模块的实现细节。
 *
 * ## 动图封面：本包只显示静态图
 *
 * RN 的 `AnimatedCoverMedia` 支持 mp4/gif/webp 三形态，mp4 走 expo-video。
 * 本包只用 `image_url` 的静态图 + `animated_image_url` 里的非 mp4 形态。
 * **mp4 动图封面属下一包**（要 Media3 有界池，方案 §8.1 Screen 行的
 * 「largeHeap + 有界池 + 图片内存上限三件套」，不能随手挂播放器）。
 * 当前表现：动图角色显示静态封面 —— 是已知边界，不是漏实现。
 */
@Composable
internal fun HomeCard(
    item: HomeFeedItem,
    onClick: () -> Unit,
    onExposed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 曝光：进入组合即上报一次（对齐 RN 的 `useEffect` 挂载上报，
    // `HomeCard.tsx:182-197`）。key 用 stableKey —— 复用同一个 slot 渲染
    // 另一个角色时必须重新上报，用 Unit 会漏掉
    LaunchedEffect(item.stableKey) { onExposed() }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(HomeStyle.CARD_RADIUS.s))
            .background(HomeStyle.CARD_PLACEHOLDER)
            .clickable(onClick = onClick),
    ) {
        CoverImage(item)
        TopGradient()
        StoryBadge(item)
        BottomGradient()
        CardBottom(item)
    }
}

@Composable
private fun CoverImage(item: HomeFeedItem) {
    val rawUrl = when (item) {
        is HomeFeedItem.Character -> item.imageUrl
        is HomeFeedItem.Story -> item.imageUrl
        is HomeFeedItem.World -> item.coverUrl
    }
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(HomeText.transformImageUrl(rawUrl))
            // 淡入：与 RN 的 expo-image 默认过渡近似。不开会在滚动时"啪"地跳出
            .crossfade(true)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

/** 顶部渐变（`HomeCard.tsx:373-377`：黑 50% → 透明，高 32）。 */
@Composable
private fun TopGradient() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HomeStyle.CARD_TOP_GRADIENT.s)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0x80000000), Color(0x00000000)),
                ),
            ),
    )
}

/**
 * 底部渐变（`:405-412`：透明 → 纯黑，高度 **50%**）。
 *
 * ⚠️ 高度是卡片的 50% 而不是固定 dp —— 卡片高 310 时是 155。
 * 写死 dp 会让不同缩放下的渐变覆盖比例不一致。
 */
@Composable
private fun BoxScope.BottomGradient() {
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(0.5f)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0x00000000), Color(0xFF000000)),
                ),
            ),
    )
}

/** 左上角角标：`character_type == 2` 显示 story 标（`:260-266`）。 */
@Composable
private fun BoxScope.StoryBadge(item: HomeFeedItem) {
    val show = when (item) {
        is HomeFeedItem.Character -> item.characterType == CHARACTER_TYPE_STORY
        // Story item 在 RN 侧走 HomeStoryCard，它的角标是「Story」文字条 ——
        // 那个组件的顶部布局与 HomeCard 不同（`HomeStoryCard.tsx:178-210`）。
        // 本包统一用图标角标：视觉更接近且避免引入一处 RN 侧自己都不一致的布局
        is HomeFeedItem.Story -> true
        is HomeFeedItem.World -> false
    }
    if (!show) return
    Image(
        painter = painterResource(R.drawable.ic_card_story),
        contentDescription = null,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(8.s)
            .size(HomeStyle.CARD_ICON_STORY.s),
    )
}

@Composable
private fun BoxScope.CardBottom(item: HomeFeedItem) {
    val language by rememberCurrentLanguage()
    val isGooglePlay = BuildConfig.DOWNLOAD_CHANNEL == CHANNEL_GOOGLE_PLAY

    Column(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .padding(
                horizontal = HomeStyle.CARD_CONTENT_PADDING.s,
                vertical = 0.s,
            )
            .padding(bottom = HomeStyle.CARD_CONTENT_BOTTOM.s),
    ) {
        val title = when (item) {
            is HomeFeedItem.Character -> item.nickname
            is HomeFeedItem.Story -> item.title
            is HomeFeedItem.World -> item.name
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.s),
        ) {
            Text(
                text = HomeText.maskSensitiveWords(title, isGooglePlay),
                color = HomeStyle.CARD_TITLE,
                fontSize = HomeStyle.CARD_TITLE_SIZE.sSp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (item is HomeFeedItem.Character && item.voiceSupported) {
                Image(
                    painter = painterResource(R.drawable.ic_card_voice),
                    contentDescription = null,
                    modifier = Modifier.size(HomeStyle.CARD_ICON_SMALL.s),
                )
            }
        }

        val subtitle = when (item) {
            // ⚠️ 占位符替换的语言参数取决于 is_translated（见 HomeText 注释）：
            // 未翻译角色恒用 en，否则简介英文里会插一个中文「你」
            is HomeFeedItem.Character -> HomeText.replaceIntroductionPlaceholders(
                introduction = item.introduction,
                characterName = item.nickname,
                languageCode = if (item.isTranslated) language else "en",
            )
            // story / world 的简介**不做占位符替换**（RN 侧直接显示 summary /
            // introduction，没有 {{char}} 语义）
            is HomeFeedItem.Story -> item.summary
            is HomeFeedItem.World -> item.introduction
        }
        Text(
            text = HomeText.maskSensitiveWords(subtitle, isGooglePlay),
            color = HomeStyle.CARD_SUBTITLE,
            fontSize = HomeStyle.CARD_SUBTITLE_SIZE.sSp,
            maxLines = HomeStyle.CARD_SUBTITLE_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.s, bottom = 3.s),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val creator = when (item) {
                is HomeFeedItem.Character -> item.creatorNickname
                is HomeFeedItem.Story -> item.creatorNickname
                is HomeFeedItem.World -> item.creatorNickname
            }
            if (creator != null) {
                Text(
                    text = "@" + HomeText.maskSensitiveWords(creator, isGooglePlay),
                    color = HomeStyle.CARD_CREATOR,
                    fontSize = HomeStyle.CARD_CREATOR_SIZE.sSp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.s),
                )
            } else {
                Box(modifier = Modifier.weight(1f))
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.s),
            ) {
                Image(
                    painter = painterResource(
                        // World 用互动数图标，其余用消息数图标（`HomeCard.tsx:344-350`
                        // 按 character_type == 9 分流；壳侧类型已分开，不用魔法数）
                        if (item is HomeFeedItem.World) {
                            R.drawable.ic_card_world_interaction
                        } else {
                            R.drawable.ic_card_chat_message
                        },
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(HomeStyle.CARD_ICON_SMALL.s),
                )
                val count = when (item) {
                    is HomeFeedItem.Character -> item.totalMessages
                    is HomeFeedItem.Story -> item.totalMessages
                    is HomeFeedItem.World -> item.interactionCount
                }
                Text(
                    text = HomeText.formatMessageCount(count),
                    color = HomeStyle.CARD_TITLE,
                    fontSize = HomeStyle.CARD_COUNT_SIZE.sSp,
                    maxLines = 1,
                )
            }
        }
    }
}

/** `character_type == 2` 是多角色故事（`HomeCard.tsx:262`）。 */
private const val CHARACTER_TYPE_STORY = 2

/** `BuildConfig.DOWNLOAD_CHANNEL` 的 googlePlay 值（`app/build.gradle` flavor 定义）。 */
private const val CHANNEL_GOOGLE_PLAY = "GooglePlay"
