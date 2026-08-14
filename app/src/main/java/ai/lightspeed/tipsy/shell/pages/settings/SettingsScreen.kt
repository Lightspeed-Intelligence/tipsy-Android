package ai.lightspeed.tipsy.shell.pages.settings

import ai.lightspeed.tipsy.shell.R
import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings 列表（W3，进度文档 §2.33）。
 *
 * ## 行序与渠道 gating 不在这里判
 *
 * 全部收在 [SettingsRow.visibleRows]。RN 侧那 9 处 `!isGooglePlay` 散在
 * 430 行 JSX 里，抄成 9 个 Compose `if` 会让「GooglePlay 版多出一行」
 * 这类合规问题无法被单测覆盖 —— 而本地跑 directApk 时所有行都显示，看不出来。
 *
 * ## 子页出口当前**全部被明确拒绝**
 *
 * 7 个 `SettingsSurface` 子屏未过 §9.1 矩阵，点击走 `rejectNotEnabled`
 * 记明确错误（§8.3：不做 silent no-op）。三个外部链接行不经 Surface，本刀就通。
 */
@Composable
fun SettingsScreen(
    state: SettingsState,
    onBackClick: () -> Unit,
    onRowClick: (SettingsRow) -> Unit,
    onNsfwToggle: () -> Unit,
    onLogoutClick: () -> Unit,
    statusBarPadding: Dp,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SettingsStyle.APP_BACKGROUND)
            .padding(top = statusBarPadding),
    ) {
        SettingsHeader(
            titleKey = "Settings",
            onBackClick = onBackClick,
            testTag = "settings_back",
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = bottomPadding),
        ) {
            // 一个 Group 容器（RN 是 `TipsyCell.Group`：白 5% 底 + 8dp 圆角 + 行间细线）
            Column(
                modifier = Modifier
                    .padding(SettingsStyle.LIST_MARGIN.dp)
                    .clip(RoundedCornerShape(SettingsStyle.GROUP_RADIUS.dp))
                    .background(SettingsStyle.GROUP_BACKGROUND)
                    .testTag("settings_list"),
            ) {
                val rows = state.visibleRows
                rows.forEachIndexed { index, row ->
                    if (index > 0) SettingsDivider()
                    SettingsCell(
                        row = row,
                        state = state,
                        onClick = {
                            if (row.action is SettingsAction.ToggleNsfw) {
                                onNsfwToggle()
                            } else {
                                onRowClick(row)
                            }
                        },
                        onNsfwToggle = onNsfwToggle,
                    )
                }
            }

            Spacer(Modifier.height(SettingsStyle.LOGOUT_TOP_GAP.dp))
            LogoutButton(onClick = onLogoutClick)
            Spacer(Modifier.height(SettingsStyle.LIST_MARGIN.dp))
        }
    }
}

/**
 * 一行（`TipsyCell`）：左标题、右侧值/箭头/开关。
 *
 * 三种右侧形态：
 * - [SettingsAction.ToggleNsfw] → 开关
 * - [SettingsAction.ToggleAccountSecurity] → 可旋转的箭头（展开时转 90°）
 * - 其余 → `isLink` 的右向箭头
 */
@Composable
private fun SettingsCell(
    row: SettingsRow,
    state: SettingsState,
    onClick: () -> Unit,
    onNsfwToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsStyle.CELL_MIN_HEIGHT.dp)
            // 开关行整行可点（RN 的 TipsyCell 也是整行 Pressable）
            .clickable(enabled = !(row.action is SettingsAction.ToggleNsfw && state.nsfwPending)) {
                onClick()
            }
            .padding(
                horizontal = SettingsStyle.CELL_H_PADDING.dp,
                vertical = SettingsStyle.CELL_V_PADDING.dp,
            )
            .testTag(row.testTag),
    ) {
        Text(
            text = rememberLocalizedString(row.titleKey),
            color = SettingsStyle.TEXT_PRIMARY,
            fontSize = SettingsStyle.CELL_TITLE_FONT.sp,
        )

        when (row.action) {
            is SettingsAction.ToggleNsfw -> Switch(
                checked = state.nsfwEnabled,
                onCheckedChange = { onNsfwToggle() },
                enabled = !state.nsfwPending,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = SettingsStyle.SWITCH_ON,
                ),
            )

            is SettingsAction.ToggleAccountSecurity -> Image(
                painter = painterResource(R.drawable.ic_settings_arrow_right),
                contentDescription = null, // 纯装饰，语义由行标题承载
                // 展开时转 90°（RN 的 expandArrowOpen 同样是旋转）
                modifier = Modifier
                    .size(SettingsStyle.ARROW_SIZE.dp)
                    .rotate(if (state.accountSecurityExpanded) ARROW_OPEN_DEGREES else 0f),
            )

            else -> Image(
                painter = painterResource(R.drawable.ic_settings_arrow_right),
                contentDescription = null,
                modifier = Modifier.size(SettingsStyle.ARROW_SIZE.dp),
            )
        }
    }
}

/**
 * 语言页（W3，§2.33）。**原生页** —— RN 的 `SettingsSurface` 白名单刻意不含
 * `Language`（注释：「语言页原生：壳是语言唯一写入者」）。
 *
 * 顶栏右侧 Done：两段选择态相等时**不可点**（`language.tsx:24-27`）。
 * 上屏文案用 `display`（该语言自己的写法，如 `日本語`），**不过 `t()`**。
 */
@Composable
fun LanguageScreen(
    state: SettingsState,
    onBackClick: () -> Unit,
    onSelect: (String) -> Unit,
    onDoneClick: () -> Unit,
    statusBarPadding: Dp,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SettingsStyle.APP_BACKGROUND)
            .padding(top = statusBarPadding),
    ) {
        SettingsHeader(
            titleKey = "Language",
            onBackClick = onBackClick,
            testTag = "language_back",
            trailing = {
                Text(
                    text = rememberLocalizedString("Done"),
                    // 不可点时降透明度（RN 的 doneText 恒白，但那边不可点时没有
                    // 视觉反馈 —— 壳给一档 alpha 让"为什么点不动"可见）
                    color = if (state.isLanguageDoneEnabled) {
                        SettingsStyle.TEXT_PRIMARY
                    } else {
                        SettingsStyle.TEXT_DISABLED
                    },
                    fontSize = SettingsStyle.DONE_FONT.sp,
                    modifier = Modifier
                        .clickable(enabled = state.isLanguageDoneEnabled, onClick = onDoneClick)
                        .padding(horizontal = SettingsStyle.CELL_H_PADDING.dp)
                        .testTag("language_done"),
                )
            },
        )

        when {
            state.isLanguageLoading -> CenteredNote(
                text = "",
                testTag = "language_loading",
                showSpinner = true,
            )

            // ⚠️ 必须有错误态：RN 拉不到时是永久 loading（isLoading =
            // languages.length === 0），照抄会让用户对着转圈无从判断
            state.supportedLanguages.isEmpty() -> CenteredNote(
                text = rememberLocalizedString(
                    state.languageError ?: SettingsViewModel.LOAD_FAILED_KEY,
                ),
                testTag = "language_error",
            )

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(SettingsStyle.LIST_MARGIN.dp)
                    .clip(RoundedCornerShape(SettingsStyle.GROUP_RADIUS.dp))
                    .background(SettingsStyle.GROUP_BACKGROUND)
                    .testTag("language_list"),
            ) {
                state.supportedLanguages.forEachIndexed { index, language ->
                    if (index > 0) SettingsDivider()
                    LanguageCell(
                        language = language,
                        selected = state.pendingLanguage == language.languageCode,
                        onClick = { onSelect(language.languageCode) },
                    )
                }
                Spacer(Modifier.height(bottomPadding))
            }
        }
    }
}

@Composable
private fun LanguageCell(
    language: SupportedLanguage,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsStyle.CELL_MIN_HEIGHT.dp)
            .clickable(onClick = onClick)
            .padding(
                start = SettingsStyle.CELL_H_PADDING.dp,
                end = SettingsStyle.LANGUAGE_CELL_END_PADDING.dp,
                top = SettingsStyle.CELL_V_PADDING.dp,
                bottom = SettingsStyle.CELL_V_PADDING.dp,
            )
            // 动态段用服务端语言码（稳定值，可被自动化按码定位）
            .testTag("language_row_${language.languageCode}"),
    ) {
        Text(
            // ⚠️ display 是该语言自己的写法（`日本語`），**不过 t()**。
            // 用 `language` 字段会显示英文名，与现网不一致
            text = language.display,
            color = SettingsStyle.TEXT_PRIMARY,
            fontSize = SettingsStyle.CELL_TITLE_FONT.sp,
        )
        if (selected) {
            Image(
                painter = painterResource(R.drawable.ic_settings_check),
                contentDescription = null, // 选中态由 testTag + 行文案承载
                modifier = Modifier.size(SettingsStyle.CHECK_SIZE.dp),
            )
        }
    }
}

/** 顶栏：返回箭头 + 居中标题 + 可选右侧区（语言页的 Done）。 */
@Composable
private fun SettingsHeader(
    titleKey: String,
    onBackClick: () -> Unit,
    testTag: String,
    trailing: @Composable (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SettingsStyle.HEADER_HEIGHT.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_search_back),
            contentDescription = rememberLocalizedString("Back"),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = SettingsStyle.CELL_H_PADDING.dp)
                .size(SettingsStyle.BACK_ICON_SIZE.dp)
                .clickable(onClick = onBackClick)
                .testTag(testTag),
        )
        Text(
            text = rememberLocalizedString(titleKey),
            color = SettingsStyle.TEXT_PRIMARY,
            fontSize = SettingsStyle.HEADER_FONT.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.Center),
        )
        if (trailing != null) {
            Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
        }
    }
}

/** 登出按钮（`page.tsx:354`）。确认弹窗由 Fragment 用 AlertDialog 承载。 */
@Composable
private fun LogoutButton(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsStyle.LIST_MARGIN.dp)
            .clip(RoundedCornerShape(SettingsStyle.LOGOUT_RADIUS.dp))
            .background(SettingsStyle.GROUP_BACKGROUND)
            .clickable(onClick = onClick)
            .padding(vertical = SettingsStyle.LOGOUT_V_PADDING.dp)
            .testTag("settings_logout"),
    ) {
        Text(
            text = rememberLocalizedString("Log out"),
            color = SettingsStyle.TEXT_PRIMARY,
            fontSize = SettingsStyle.CELL_TITLE_FONT.sp,
        )
    }
}

@Composable
private fun SettingsDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsStyle.CELL_H_PADDING.dp)
            .height(1.dp)
            .background(SettingsStyle.DIVIDER),
    )
}

@Composable
private fun CenteredNote(text: String, testTag: String, showSpinner: Boolean = false) {
    Box(
        Modifier.fillMaxSize().testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        if (showSpinner) {
            androidx.compose.material3.CircularProgressIndicator()
        } else {
            Text(
                text = text,
                color = SettingsStyle.TEXT_SECONDARY,
                fontSize = SettingsStyle.CELL_TITLE_FONT.sp,
            )
        }
    }
}

/** 展开态箭头旋转角度（RN 的 `expandArrowOpen`）。 */
private const val ARROW_OPEN_DEGREES = 90f
