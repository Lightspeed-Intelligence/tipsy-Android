package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.R
import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup

/**
 * 钱包三栏卡（`UserProfileGems.tsx`）：宝石 + / 会员档位 升级 / 金币 →。
 *
 * ## 三个出口全部经 Router（当前明确拒绝）
 *
 * | 栏 | RN 目标 | 壳路由 |
 * | --- | --- | --- |
 * | 宝石 +（`handleAddGem`） | `GemsSubscription` tab=buy_gems | [ProfileWalletAction.ADD_GEMS] |
 * | 升级（`handleUpgrade`） | `GemsSubscription` tab=subscription | [ProfileWalletAction.UPGRADE] |
 * | 金币 →（`handleCoinAction`） | `ProfileStack/UserCoins` | [ProfileWalletAction.COINS] |
 *
 * RN 里**整栏和按钮都可点、动作相同**（外层 TouchableOpacity + 内层按钮都调
 * `onButtonPress`）—— 保持一致，整栏可点。
 *
 * ## ⚠️ 免费栏在无限量时显示 `100`
 *
 * `UserProfileGems.tsx:371`：`amount={typeof leftFreeMsgAmount === 'number'
 * ? leftFreeMsgAmount : 100}` —— `has_inf_msg` 时**硬编码传 100**，
 * 不是 `Unlimited`。看着像权宜，但这是现网行为，别"修好"它。
 *
 * ## 刻意不做（后续包）
 *
 * - **会员栏的 ⓘ**（付费档位显示到期/续费信息）：要 `expires_date` +
 *   日期格式化 + 三条额外词条，测试账号是 Free 也看不到 —— 等订阅 Surface 包
 * - 金币 USD 汇率气泡（`showCoinUsdPopover`，首次引导一次性提示，
 *   依赖 guide-status store —— 属 Onboarding 域）
 */
@Composable
fun ProfileWalletCard(
    wallet: ProfileWallet,
    onAction: (ProfileWalletAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = CARD_MARGIN_H.dp)
            .height(CARD_HEIGHT.dp)
            .clip(RoundedCornerShape(CARD_RADIUS.dp))
            .border(1.dp, BORDER_COLOR, RoundedCornerShape(CARD_RADIUS.dp))
            // RN 是 [0,0]→[1,1] 的对角渐变（红 23% → 透明）
            .background(
                Brush.linearGradient(listOf(CARD_GRADIENT_START, Color.Transparent)),
            )
            .padding(vertical = CARD_PADDING_V.dp, horizontal = CARD_PADDING_H.dp)
            .testTag("profile_wallet"),
    ) {
        // ── 红宝石 ──
        WalletBlock(
            titleKey = "Gems",
            icon = R.drawable.ic_profile_gem_red,
            amount = ProfileText.formatWalletAmount(wallet.gemAmount),
            amountColor = GEM_RED,
            infoTextKey = GEMS_INFO_KEY,
            onClick = { onAction(ProfileWalletAction.ADD_GEMS) },
            testTag = "profile_wallet_gems",
            modifier = Modifier.weight(1f),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_profile_plus),
                contentDescription = rememberLocalizedString("Gems"),
                modifier = Modifier.size(PLUS_ICON.dp),
            )
        }

        SplitLine()

        // ── 会员 / 蓝宝石 ──
        WalletBlock(
            titleKey = wallet.planNameKey,
            icon = R.drawable.ic_profile_gem_blue,
            // ⚠️ 无限量显示 100，见类注释
            amount = if (wallet.freeAmountIsUnlimited) {
                UNLIMITED_DISPLAY
            } else {
                ProfileText.formatWalletAmount(wallet.leftFreeAmount)
            },
            amountColor = if (wallet.isFreePlan) ProfileStyle.TEXT_PRIMARY else GEM_BLUE,
            infoTextKey = null,
            onClick = { onAction(ProfileWalletAction.UPGRADE) },
            testTag = "profile_wallet_plan",
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = rememberLocalizedString("Upgrade"),
                color = BUTTON_TEXT,
                fontSize = BUTTON_FONT.sp,
            )
        }

        SplitLine()

        // ── 金币 ──
        WalletBlock(
            titleKey = "Coins",
            icon = R.drawable.ic_profile_gem_coin,
            amount = ProfileText.formatCoinAmount(wallet.coinAmount),
            amountColor = COIN_GOLD,
            infoTextKey = null,
            onClick = { onAction(ProfileWalletAction.COINS) },
            testTag = "profile_wallet_coins",
            modifier = Modifier.weight(1f),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_profile_arrow),
                contentDescription = rememberLocalizedString("Coins"),
                modifier = Modifier.size(ARROW_ICON.dp),
            )
        }
    }
}

/** 三个出口的语义标识；路由映射在 Fragment（业务页不直接拼路由）。 */
enum class ProfileWalletAction { ADD_GEMS, UPGRADE, COINS }

/**
 * 单栏（`ResourceBlock`）：标题(+ⓘ) / 图标+数字 / 胶囊按钮，纵向 space-between。
 * ⓘ 点开 [Popup] 气泡（RN 是 react-native-popover-view，视觉近似）。
 */
@Composable
private fun WalletBlock(
    titleKey: String,
    icon: Int,
    amount: String,
    amountColor: Color,
    infoTextKey: String?,
    onClick: () -> Unit,
    testTag: String,
    /** 必须由调用点带上 `Modifier.weight(1f)`（RowScope 成员，函数体内拿不到）。 */
    modifier: Modifier,
    buttonContent: @Composable () -> Unit,
) {
    var showInfo by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxHeight()
            .clickableNoRippleWallet(onClick)
            .testTag(testTag),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = rememberLocalizedString(titleKey),
                color = TITLE_COLOR,
                fontSize = TITLE_FONT.sp,
                maxLines = 2,
            )
            if (infoTextKey != null) {
                Box {
                    Image(
                        painter = painterResource(R.drawable.ic_profile_info),
                        contentDescription = rememberLocalizedString(titleKey),
                        alpha = INFO_ALPHA,
                        modifier = Modifier
                            .padding(start = INFO_GAP.dp)
                            .size(INFO_ICON.dp)
                            .clickableNoRippleWallet { showInfo = true }
                            .testTag("${testTag}_info"),
                    )
                    if (showInfo) {
                        Popup(onDismissRequest = { showInfo = false }) {
                            Text(
                                text = rememberLocalizedString(infoTextKey),
                                color = ProfileStyle.TEXT_PRIMARY,
                                fontSize = INFO_TEXT_FONT.sp,
                                modifier = Modifier
                                    .widthIn(max = INFO_POPUP_MAX_WIDTH.dp)
                                    .clip(RoundedCornerShape(INFO_POPUP_RADIUS.dp))
                                    .background(INFO_POPUP_BACKGROUND)
                                    .padding(INFO_POPUP_PADDING.dp),
                            )
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(icon),
                contentDescription = null, // 数字紧随其后，语义由文字承担
                modifier = Modifier.size(GEM_ICON.dp),
            )
            Text(
                text = amount,
                color = amountColor,
                fontSize = AMOUNT_FONT.sp,
                modifier = Modifier.padding(start = AMOUNT_GAP.dp),
            )
        }

        // 胶囊按钮：RN 内外层同一动作，这里按钮不再单独接 click（外层已可点）
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .defaultMinSize(minWidth = BUTTON_MIN_WIDTH.dp)
                .height(BUTTON_HEIGHT.dp)
                .clip(RoundedCornerShape(BUTTON_RADIUS.dp))
                .border(1.dp, BORDER_COLOR, RoundedCornerShape(BUTTON_RADIUS.dp))
                .background(
                    Brush.horizontalGradient(listOf(BUTTON_GRADIENT_START, BUTTON_GRADIENT_END)),
                )
                .padding(horizontal = BUTTON_PADDING_H.dp),
        ) {
            buttonContent()
        }
    }
}

@Composable
private fun SplitLine() {
    Box(
        Modifier
            .padding(horizontal = SPLIT_MARGIN.dp)
            .width(1.dp)
            .fillMaxHeight(SPLIT_HEIGHT_FRACTION)
            .background(BORDER_COLOR),
    )
}

/** 无水波纹点击（同 ProfileScreen 的版本；私有 util 不跨文件共享，保持文件自洽）。 */
@Composable
private fun Modifier.clickableNoRippleWallet(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
    )
}

/** 宝石 ⓘ 的说明文案（key = 英文原文，`UserProfileGems.tsx:349`，词条已在表内）。 */
private const val GEMS_INFO_KEY =
    "We offer two types of gems: Red Gems, which lasts forever and can be earned or " +
        "purchased, and Blue Gems, refreshed monthly with a subscription. " +
        "Both available for all models."

/** `has_inf_msg` 时的显示值（RN 硬编码 100，见类注释）。 */
private const val UNLIMITED_DISPLAY = "100"

// 尺寸对 `UserProfileGems.tsx:432-503` styles
private const val CARD_HEIGHT = 96
private const val CARD_RADIUS = 8
private const val CARD_MARGIN_H = 10
private const val CARD_PADDING_V = 16
private const val CARD_PADDING_H = 24
private const val TITLE_FONT = 12
private const val AMOUNT_FONT = 14
private const val AMOUNT_GAP = 3
private const val GEM_ICON = 16
private const val PLUS_ICON = 12
private const val ARROW_ICON = 16
private const val INFO_ICON = 14
private const val INFO_GAP = 4
private const val INFO_ALPHA = 0.5f
private const val INFO_TEXT_FONT = 12
private const val INFO_POPUP_MAX_WIDTH = 260
private const val INFO_POPUP_RADIUS = 8
private const val INFO_POPUP_PADDING = 10
private const val BUTTON_MIN_WIDTH = 56
private const val BUTTON_HEIGHT = 20
private const val BUTTON_RADIUS = 16
private const val BUTTON_PADDING_H = 6
private const val BUTTON_FONT = 10
private const val SPLIT_MARGIN = 12
private const val SPLIT_HEIGHT_FRACTION = 0.8f

private val CARD_GRADIENT_START = Color(0x3BFF5C5C)
private val BORDER_COLOR = Color(0x0DFFFFFF)
private val TITLE_COLOR = Color(0x80FFFFFF)
private val BUTTON_TEXT = Color(0x80FFFFFF)
private val BUTTON_GRADIENT_START = Color(0x4DFF5C5C)
private val BUTTON_GRADIENT_END = Color(0x0DFFFFFF)
private val GEM_RED = Color(0xFFFF6480)
private val GEM_BLUE = Color(0xFF8BA2EE)
private val COIN_GOLD = Color(0xFFF2B64A)
private val INFO_POPUP_BACKGROUND = Color(0xEE2B1817)
