package ai.lightspeed.tipsy.shell.pages.search

import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 搜索筛选抽屉（`FilterDrawer.tsx` 292 行，W3-P2 / §2.34）。
 *
 * ## 三段 + 双按钮
 *
 * 标题 **`Sort by`**（不是 `Filter`）与右上 X 同行；`Sort by` 五项 /
 * `Gender` 四项 / `Content Rating` 三项（**仅三重 gating 通过时**），
 * 底部 **`Reset`（左半透明）+ `Done`（右 #AD403B）**。
 *
 * ⚠️ 按钮是 `Reset` / `Done`，**不是 `Apply`** —— 逐字核实
 * `FilterDrawer.tsx:193-203`。
 *
 * ## 待提交与已生效是两份状态
 *
 * 抽屉里的选择只改 `pendingFilter`，点 Done 才写回 `filter` 并重查；
 * 点 X / 遮罩关闭**不提交**（对齐 RN 的 `handleClose`）。
 * Reset 只把三项回默认、**不关抽屉也不提交**，且**不清标签**。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchFilterDrawer(
    state: SearchState,
    onDismiss: () -> Unit,
    onGenderSelect: (SearchGender) -> Unit,
    onSortingSelect: (SearchSorting) -> Unit,
    onContentRatingSelect: (SearchContentRating) -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val pending = state.pendingFilter ?: return

    Box(modifier = modifier.fillMaxSize()) {
        // 遮罩：点击关闭（不提交）
        Box(
            Modifier
                .fillMaxSize()
                .background(SCRIM)
                .clickable(onClick = onDismiss)
                .testTag("search_filter_scrim"),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = SHEET_RADIUS.dp, topEnd = SHEET_RADIUS.dp))
                .background(SHEET_BACKGROUND)
                .padding(bottom = bottomPadding)
                .testTag("search_filter_drawer"),
        ) {
            // 标题行：Sort by + 右上 X
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(HEADER_HEIGHT.dp)
                    .padding(horizontal = SECTION_PADDING.dp),
            ) {
                Text(
                    text = rememberLocalizedString("Sort by"),
                    color = Color.White,
                    fontSize = HEADER_FONT.sp,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                Text(
                    text = CLOSE_GLYPH,
                    color = CLOSE_COLOR,
                    fontSize = CLOSE_FONT.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clickable(onClick = onDismiss)
                        .padding(CLOSE_HIT_PADDING.dp)
                        .testTag("search_filter_close"),
                )
            }

            // ① Sort by（无小标题 —— 标题就是抽屉标题，对齐 RN 的 :117-138）
            FilterSection(title = null) {
                SearchSorting.ALL_OPTIONS.forEach { option ->
                    FilterChip(
                        labelKey = option.label,
                        selected = pending.sorting == option,
                        onClick = { onSortingSelect(option) },
                        testTag = "search_filter_sorting_${option.wire}",
                    )
                }
            }

            // ② Gender
            FilterSection(title = "Gender") {
                SearchGender.ALL_OPTIONS.forEach { option ->
                    FilterChip(
                        labelKey = option.label,
                        selected = pending.gender == option,
                        onClick = { onGenderSelect(option) },
                        testTag = "search_filter_gender_${option.name.lowercase()}",
                    )
                }
            }

            // ③ Content Rating —— ⚠️ 仅三重 gating 通过时渲染
            // （android && !GooglePlay && nsfw，见 SearchFilter.canPickContentRating）
            if (state.canPickContentRating) {
                FilterSection(title = "Content Rating") {
                    SearchContentRating.ALL_OPTIONS.forEach { option ->
                        FilterChip(
                            labelKey = option.label,
                            selected = pending.contentRating == option,
                            onClick = { onContentRatingSelect(option) },
                            testTag = "search_filter_rating_${option.name.lowercase()}",
                        )
                    }
                }
            }

            // 底部双按钮
            Row(
                horizontalArrangement = Arrangement.spacedBy(FOOTER_GAP.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SECTION_PADDING.dp),
            ) {
                FooterButton(
                    labelKey = "Reset",
                    background = CHIP_BACKGROUND,
                    onClick = onReset,
                    testTag = "search_filter_reset",
                    modifier = Modifier.weight(1f),
                )
                FooterButton(
                    labelKey = "Done",
                    background = ACCENT,
                    onClick = onDone,
                    testTag = "search_filter_done",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(title: String?, content: @Composable () -> Unit) {
    Column(Modifier.padding(SECTION_PADDING.dp)) {
        if (title != null) {
            Text(
                text = rememberLocalizedString(title),
                color = Color.White,
                fontSize = SECTION_TITLE_FONT.sp,
            )
            Spacer(Modifier.height(SECTION_TITLE_GAP.dp))
        }
        // FlowRow：标签数量与文案长度都不定，固定列数会在长文案语言下截断
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(CHIP_GAP.dp),
            verticalArrangement = Arrangement.spacedBy(CHIP_GAP.dp),
        ) {
            content()
        }
    }
}

/** 一个筛选胶囊（`styles.tag` / `tagSelected`）。 */
@Composable
private fun FilterChip(
    labelKey: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = CHIP_HEIGHT.dp)
            .clip(RoundedCornerShape(CHIP_RADIUS.dp))
            .background(if (selected) ACCENT else CHIP_BACKGROUND)
            .clickable(onClick = onClick)
            .padding(horizontal = CHIP_H_PADDING.dp)
            .testTag(testTag),
    ) {
        Text(
            text = rememberLocalizedString(labelKey),
            // 未选中时文字半透明（`tagTextUnselected`）
            color = if (selected) Color.White else CHIP_TEXT_UNSELECTED,
            fontSize = CHIP_FONT.sp,
        )
    }
}

@Composable
private fun FooterButton(
    labelKey: String,
    background: Color,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(FOOTER_BUTTON_HEIGHT.dp)
            .clip(RoundedCornerShape(FOOTER_BUTTON_RADIUS.dp))
            .background(background)
            .clickable(onClick = onClick)
            .testTag(testTag),
    ) {
        Text(
            text = rememberLocalizedString(labelKey),
            color = Color.White,
            fontSize = FOOTER_FONT.sp,
        )
    }
}

// ── 视觉常量（逐条取自 FilterDrawer.tsx 的 styles）──────────

/** 遮罩。RN 的 TipsyDrawer 用 blur + gradient，壳先用纯色近似（视觉 diff 属验收）。 */
private val SCRIM = Color(0x99000000)

/** 抽屉底色（对齐 app 背景，RN 是 backgroundGradient）。 */
private val SHEET_BACKGROUND = Color(0xFF34212A)

/** 选中胶囊与 Done 按钮（`#AD403B`，`:50` / `:75`）。 */
private val ACCENT = Color(0xFFAD403B)

/** 未选中胶囊底（`rgba(255,255,255,0.05)`，`:40`）。 */
private val CHIP_BACKGROUND = Color(0x0DFFFFFF)

/** 未选中文字（`rgba(255,255,255,0.5)`，`:57`）。 */
private val CHIP_TEXT_UNSELECTED = Color(0x80FFFFFF)

/** X 的颜色（`rgba(255,255,255,0.5)`，`:105`）。 */
private val CLOSE_COLOR = Color(0x80FFFFFF)

/** 用字形而不是图标资产：省一张图，且 × 在所有字体里都有。 */
private const val CLOSE_GLYPH = "✕"

private const val SHEET_RADIUS = 16
private const val HEADER_HEIGHT = 49
private const val HEADER_FONT = 17
private const val CLOSE_FONT = 18
private const val CLOSE_HIT_PADDING = 8
private const val SECTION_PADDING = 10
private const val SECTION_TITLE_FONT = 14
private const val SECTION_TITLE_GAP = 8
private const val CHIP_GAP = 8
private const val CHIP_HEIGHT = 32
private const val CHIP_RADIUS = 30
private const val CHIP_H_PADDING = 12
private const val CHIP_FONT = 14
private const val FOOTER_GAP = 12
private const val FOOTER_BUTTON_HEIGHT = 40
private const val FOOTER_BUTTON_RADIUS = 47
private const val FOOTER_FONT = 13
