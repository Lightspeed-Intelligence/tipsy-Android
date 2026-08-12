package ai.lightspeed.tipsy.shell.pages.home

import ai.lightspeed.tipsy.shell.i18n.LocalizedText
import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
import ai.lightspeed.tipsy.shell.ui.s
import ai.lightspeed.tipsy.shell.ui.sSp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Home 标签筛选抽屉（`HomeFilterDrawer.tsx` 382 行 + `TipsyDrawer.tsx` 的壳）。
 *
 * ## ⚠️ 应用时机是「关闭抽屉」，不是「点确认按钮」
 *
 * RN 的 header 右侧在 `headerStyle="filter"` 下是个 **✓ 图标**
 * （`TipsyDrawer.tsx:338-340`），但它调的就是 `handleClose` ——
 * `onClose` 回调里才 `setSelectedTags`（`HomeFilterDrawer.tsx:119-129`）。
 * 也就是说：
 * - 点 ✓ → 应用并关闭
 * - 点遮罩 / 按返回键 → **同样应用并关闭**（不是丢弃！）
 *
 * 照「确认才生效、点外面丢弃」实现是最自然的猜测，但与现网行为相反 ——
 * 用户点了几个标签然后点遮罩，RN 会应用，壳若丢弃就是静默的行为差异。
 *
 * ## 勾选是本地态，应用时才提交
 *
 * 抽屉内的点选先进 [localSelected]（对齐 RN 的 `localSelectedTags`），
 * 关闭时一次性交给 [onApply]。中途每次点选都触发重拉会让用户勾三个标签
 * 就发三次请求。
 *
 * ## 不做的部分（对齐 RN 的现状，不是漏实现）
 *
 * - **Format 区（Text / HTML(Beta)）在 RN 侧已整段注释掉**
 *   （`HomeFilterDrawer.tsx:169-197`），所以 `contentTypes` 恒为空数组。
 *   壳这里也不做 —— 但请求参数 `content_type` 的分流逻辑保留在 `HomeApi`，
 *   将来放开时只需回填。
 * - 标签的 NEW / 活动角标与红点：要 `tag_new.svg` / `tag_event.svg` 两个资源
 *   与 `guide_status` 的已点击集合，属下一包。当前只显示文字。
 * - 性别筛选**不在这个抽屉里**（在 header 的下拉面板，已实现）。
 *   RN 的 `genderContainer` / `genderItem` 是**死样式**，别照着它加一块。
 */
@Composable
internal fun HomeFilterDrawer(
    catalog: List<HomeTag>,
    selectedTagIds: List<String>,
    onApply: (List<String>) -> Unit,
) {
    // 本地勾选：进入时用已应用的值初始化。
    // ⚠️ 只保留仍在目录里的 id（`HomeFilterDrawer.tsx:80`）—— 目录随 nsfw 变，
    // 留着已下线的 id 会让请求带上后端不认识的标签
    var localSelected by remember {
        mutableStateOf(selectedTagIds.filter { id -> catalog.any { it.id == id } })
    }
    // 目录晚于抽屉到达时（打开即拉）要重新过滤一次，否则首次打开永远是空勾选
    LaunchedEffect(catalog) {
        localSelected = localSelected.filter { id -> catalog.any { it.id == id } }
    }

    val apply = { onApply(localSelected) }

    Dialog(
        // 点遮罩与返回键都走 apply —— 见类注释「应用时机」
        onDismissRequest = apply,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(HomeStyle.DRAWER_SCRIM)
                .clickable(onClick = apply),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // 抽屉高 630 是固定值；小屏上要夹住，否则 header 被推出屏幕。
            //
            // ⚠️ 用 `LocalWindowInfo.containerSize` 而**不是**
            // `LocalConfiguration.screenHeightDp` —— 后者按 targetSdk 有不同的
            // inset 行为且会四舍五入到整 dp（lint `ConfigurationScreenWidthHeight`
            // 会直接报错）。containerSize 是像素，要自己换 dp
            val containerHeightPx = LocalWindowInfo.current.containerSize.height
            val maxHeight = with(LocalDensity.current) { containerHeightPx.toDp() }
            val height = minOf(HomeStyle.DRAWER_HEIGHT.s, maxHeight)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(
                        RoundedCornerShape(
                            topStart = HomeStyle.DRAWER_RADIUS.s,
                            topEnd = HomeStyle.DRAWER_RADIUS.s,
                        ),
                    )
                    .background(Brush.linearGradient(HomeStyle.PANEL_GRADIENT_ANDROID))
                    // 吞掉点击：否则点面板会穿到遮罩上、把抽屉关掉
                    .clickable(enabled = false, onClick = {}),
            ) {
                DrawerHeader(
                    hasSelection = localSelected.isNotEmpty(),
                    onReset = { localSelected = emptyList() },
                    onConfirm = apply,
                )
                TagSection(
                    catalog = catalog,
                    selected = localSelected,
                    onToggle = { id ->
                        localSelected = if (id in localSelected) {
                            localSelected - id
                        } else {
                            localSelected + id
                        }
                    },
                )
            }
        }
    }
}

/**
 * header：标题 `Filter` + `|` + `Reset`，右侧 ✓。
 *
 * ⚠️ `Reset` 只清**本地**勾选，不直接提交（`HomeFilterDrawer.tsx:102-106`）——
 * 用户还得点 ✓（或关抽屉）才生效。且**无勾选时它是空操作**（那行的
 * early return），不要让它变成"重置并立即重拉"。
 */
@Composable
private fun DrawerHeader(
    hasSelection: Boolean,
    onReset: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HomeStyle.DRAWER_HEADER_HEIGHT.s)
            .padding(horizontal = HomeStyle.DRAWER_HEADER_PADDING.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LocalizedText(
            key = "Filter",
            color = HomeStyle.DRAWER_TEXT,
            style = androidx.compose.material3.LocalTextStyle.current.copy(
                fontSize = HomeStyle.DRAWER_TITLE_SIZE.sSp,
            ),
        )
        Text(
            text = "|",
            color = HomeStyle.DRAWER_SEPARATOR,
            fontSize = HomeStyle.DRAWER_SEPARATOR_SIZE.sSp,
            modifier = Modifier.padding(horizontal = 10.s),
        )
        // Reset 在无勾选时不可点（对齐 RN 的 early return）
        LocalizedText(
            key = "Reset",
            color = HomeStyle.DRAWER_TEXT,
            style = androidx.compose.material3.LocalTextStyle.current.copy(
                fontSize = HomeStyle.DRAWER_RESET_SIZE.sSp,
            ),
            modifier = Modifier
                .clickable(enabled = hasSelection, onClick = onReset)
                .padding(top = 4.s, end = 8.s),
        )
        Box(modifier = Modifier.weight(1f))
        // ✓ 用 Canvas 画：RN 用的是 AntDesign 字体图标（`TipsyDrawer.tsx:339`），
        // 壳侧既没有那套字体也没有对应位图。为一个对勾引 material-icons-extended
        // （约 2MB）不值得，两条线段足够
        val confirmLabel = rememberLocalizedString("Filter")
        Canvas(
            modifier = Modifier
                .size(20.s)
                .clickable(onClick = onConfirm)
                .semantics { contentDescription = confirmLabel },
        ) {
            val w = size.width
            val h = size.height
            val stroke = Stroke(width = w * 0.12f, cap = StrokeCap.Round)
            drawPath(
                path = Path().apply {
                    moveTo(w * 0.2f, h * 0.52f)
                    lineTo(w * 0.42f, h * 0.74f)
                    lineTo(w * 0.8f, h * 0.28f)
                },
                color = HomeStyle.DRAWER_TEXT,
                style = stroke,
            )
        }
    }
}

/**
 * 标签区：横向流式排列，超出换行。
 *
 * RN 用 `flexWrap: 'wrap'` + `gap: 6`（`:265-266`）。Compose 用
 * `FlowRow` —— 它在 `foundation` 里已稳定，不需要额外依赖。
 */
@Composable
private fun TagSection(
    catalog: List<HomeTag>,
    selected: List<String>,
    onToggle: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                top = HomeStyle.DRAWER_CONTENT_PADDING_TOP.s,
                start = HomeStyle.DRAWER_CONTENT_PADDING.s,
                end = HomeStyle.DRAWER_CONTENT_PADDING.s,
                bottom = HomeStyle.DRAWER_CONTENT_PADDING_BOTTOM.s,
            ),
    ) {
        LocalizedText(
            key = "Tags",
            color = HomeStyle.DRAWER_TEXT,
            style = androidx.compose.material3.LocalTextStyle.current.copy(
                fontSize = HomeStyle.DRAWER_SECTION_TITLE_SIZE.sSp,
            ),
            modifier = Modifier.padding(bottom = HomeStyle.DRAWER_SECTION_TITLE_BOTTOM.s),
        )
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(HomeStyle.TAG_GAP.s),
            verticalArrangement = Arrangement.spacedBy(HomeStyle.TAG_GAP.s),
        ) {
            catalog.forEach { tag ->
                TagChip(
                    tag = tag,
                    isSelected = tag.id in selected,
                    onClick = { onToggle(tag.id) },
                )
            }
        }
    }
}

@Composable
private fun TagChip(tag: HomeTag, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(HomeStyle.TAG_HEIGHT.s)
            .clip(RoundedCornerShape(HomeStyle.TAG_RADIUS.s))
            .background(if (isSelected) HomeStyle.TAG_FILL_SELECTED else HomeStyle.TAG_FILL)
            .clickable(onClick = onClick)
            .padding(horizontal = HomeStyle.TAG_PADDING_H.s)
            // 读屏要能听出选中态 —— 视觉上只有底色区别，无障碍下等于没有
            .semantics { contentDescription = tag.label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = tag.label,
            color = HomeStyle.DRAWER_TEXT,
            fontSize = HomeStyle.TAG_TEXT_SIZE.sSp,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}
