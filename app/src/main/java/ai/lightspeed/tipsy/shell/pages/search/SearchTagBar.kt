package ai.lightspeed.tipsy.shell.pages.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 搜索结果的二级横滑标签栏（`SearchTagBar.tsx` 214 行，W3-P2 / §2.34）。
 *
 * 多选、交集筛选；**选中后加深并排到前面，取消后回到原位置** ——
 * 排序规则在 [SearchTagOrder]（四层优先级，有 RN 现成单测对拍）。
 *
 * ## 单行横滑 + 展开成多行
 *
 * 默认单行横滑，末尾双箭头展开为多行（`⌄` / `⌃`）。
 * ⚠️ **仅单行内容真实溢出时才显示展开按钮**（RN 的
 * `contentWidth > containerWidth + 1`，`:74`）—— 内容不满一行时显示一个
 * 按不动的箭头会让人以为坏了。壳侧用 `Layout` 测量代价高，
 * 改用**近似判定**：标签数超过阈值即认为会溢出。
 *
 * 这是与 RN 的**已知偏差**：极端情况（标签少但文案极长）壳可能不给展开按钮、
 * 或标签多但文案极短时多给一个。视觉 diff 属验收阶段，不影响功能
 * （展开只是换行显示，不影响筛选结果）。
 *
 * ## 目录里没有的 agg id 不渲染
 *
 * [SearchTagOrder.derive] 已过滤（`visibleTagMap.has(id)`）；这里再用
 * [labels] 兜一次 —— 后端聚合可能给出已下线标签，渲染它是个没有文案的空胶囊。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchTagBar(
    /** 展示顺序的 id（[SearchState.orderedTagIds]）。 */
    orderedTagIds: List<String>,
    /** 已选中的 id。 */
    selectedTagIds: List<String>,
    /** id → 展示文案（[SearchState.tagLabels]）。 */
    labels: Map<String, String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = orderedTagIds.filter { labels.containsKey(it) }
    if (visible.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    // 近似溢出判定，见类注释「已知偏差」
    val mayOverflow = visible.size > SINGLE_LINE_TAG_THRESHOLD
    val showExpandToggle = mayOverflow || expanded

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TAG_GAP.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = BAR_H_PADDING.dp)
            .testTag("search_tag_bar"),
    ) {
        val selectedSet = selectedTagIds.toSet()
        Box(Modifier.weight(1f)) {
            if (expanded) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(TAG_GAP.dp),
                    verticalArrangement = Arrangement.spacedBy(TAG_GAP.dp),
                ) {
                    visible.forEach { id ->
                        TagChip(
                            label = labels.getValue(id),
                            selected = id in selectedSet,
                            onClick = { onToggle(id) },
                            testTag = "search_tag_$id",
                        )
                    }
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(TAG_GAP.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    visible.forEach { id ->
                        TagChip(
                            label = labels.getValue(id),
                            selected = id in selectedSet,
                            onClick = { onToggle(id) },
                            testTag = "search_tag_$id",
                        )
                    }
                }
            }
        }

        if (showExpandToggle) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(EXPAND_BUTTON_SIZE.dp)
                    .clickable { expanded = !expanded }
                    .testTag("search_tag_expand"),
            ) {
                Text(
                    // 双箭头用字形而不是 SVG 资产（RN 用
                    // chevron_double_up/down.svg，壳侧 Coil 不渲染 SVG 资产，
                    // 搬 PNG 要两张；字形在所有字体里都有）
                    text = CHEVRON_GLYPH,
                    color = TAG_TEXT_UNSELECTED,
                    fontSize = EXPAND_FONT.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.rotate(if (expanded) 180f else 0f),
                )
            }
        }
    }
}

/** 一个标签胶囊（`styles.tag` / `tagSelected`）。 */
@Composable
private fun TagChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = TAG_HEIGHT.dp)
            .clip(RoundedCornerShape(TAG_RADIUS.dp))
            .background(if (selected) ACCENT else TAG_BACKGROUND)
            .clickable(onClick = onClick)
            .padding(horizontal = TAG_H_PADDING.dp)
            .testTag(testTag),
    ) {
        Text(
            // ⚠️ 标签文案是**服务端 desc**，不过 t()（对齐 RN 的 TagDisplayLabel）
            text = label,
            color = if (selected) Color.White else TAG_TEXT_UNSELECTED,
            fontSize = TAG_FONT.sp,
        )
    }
}

/**
 * 单行容量的近似阈值。
 *
 * 取 6 是按「32dp 高胶囊、12sp 文案、中位标签名 6-10 字符」在
 * 360dp 宽屏上的估算。见 [SearchTagBar] 类注释的「已知偏差」说明。
 */
private const val SINGLE_LINE_TAG_THRESHOLD = 6

/** `⌄`（展开态旋转 180° 变 `⌃`）。 */
private const val CHEVRON_GLYPH = "⌄"

private val ACCENT = Color(0xFFAD403B)

/** `rgba(255,255,255,0.05)`（`:47`）。 */
private val TAG_BACKGROUND = Color(0x0DFFFFFF)

/** `rgba(255,255,255,0.4)`（`:66`）—— ⚠️ 比抽屉里的 0.5 更淡。 */
private val TAG_TEXT_UNSELECTED = Color(0x66FFFFFF)

private const val BAR_H_PADDING = 10
private const val TAG_GAP = 8
private const val TAG_HEIGHT = 32
private const val TAG_RADIUS = 32
private const val TAG_H_PADDING = 12
private const val TAG_FONT = 12
private const val EXPAND_BUTTON_SIZE = 32
private const val EXPAND_FONT = 16
