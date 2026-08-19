package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.chatlist.ChatMapFloors
import ai.lightspeed.tipsy.shell.pages.chatlist.ChatMapGeometry
import ai.lightspeed.tipsy.shell.pages.chatlist.ChatMapScreen
import ai.lightspeed.tipsy.shell.pages.chatlist.ChatMapStyle
import ai.lightspeed.tipsy.shell.pages.chatlist.ChatThread
import ai.lightspeed.tipsy.shell.pages.chatlist.cardRowTag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Map 楼层的**测量**验证（W3-P2）—— 防的是纯函数测试结构上看不见的缺陷。
 *
 * ## ⚠️ 必须走真实 production seam
 *
 * 本测试**直接 compose [ChatMapScreen]**，取生产代码里 [cardRowTag]
 * 那个节点的实测 bounds。
 *
 * 早前一版是在测试里手写一个"形状相似"的 `Box(height=row){ Box(height=card) }`
 * —— 那样**永远测不到生产修复**：它重新制造了旧的 preferred-size 路径，
 * 不经过生产那条 `offset → wrapContentHeight(Top, unbounded) → height` 链，
 * 所以生产改对改错都不影响结果。
 * 那是第四种假保护（前三种：常量而非调用点、dividend 恒正、clamp 端取样）。
 *
 * ## ⚠️ fixture 必须落在超约束档
 *
 * **360×640dp**：`cardWidth=172.8`、`cardHeight=230.4`、`rowHeight=213`
 * —— 卡高**超出行高 17.4dp**。压扁后 solver 仍按 230.4 算
 * `offsetY/cardHeight`，进度 `213/230.4=0.9245` 即**偏 7.55%**。
 *
 * 用 Pixel_10 的尺寸会**恰好通过** —— 那又是一个假保护。
 * ⚠️ 且 Pixel_10 是否真的不触发**尚未确认**：923dp 是**整机屏高**，
 * ChatMap 的容器还要减 header/tabbar，实际容器高更小、可能也落进超约束档。
 * 接入口后要用真实容器高复测。
 *
 * ## ⚠️ 运行环境
 *
 * - 需要 **Espresso ≥ 3.7.0**：3.6.1 对 `InputManager.getInstance` 的**反射查找
 *   在 API 36/37 上失败**（不是 AOSP 删了方法，是应用侧反射不可用）
 *   → **基于 `createComposeRule` 的测试**起不来（实测 android-37.1）。
 *   ⚠️ 范围仅限这类测试：`MmkvInteropTest` 不碰 Compose，一直是过的。
 *   官方 3.7.0 改用 `getSystemService`。
 * - **G1 不跑 `connectedAndroidTest`**（`.github/workflows/android-ci.yml`
 *   只有 unit test）→ 这是**本地/真机护栏，不是 CI 护栏**。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ChatMapMeasurementTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * **360×640dp**：卡高 230.4 > 行高 213（超约束 17.4dp）。
     *
     * ⚠️ 刻意不用 411×731：`Api24_Smoke` 的 root 宽只有约 393dp，
     * 411 会被夹住 → 实际 solver 尺寸与测试写死的数字不符，
     * 测试变成在"另一个尺寸"上跑。360 两台设备都放得下。
     */
    private val windowWidthDp = 360f
    private val listHeightDp = 640f

    private fun targetFloorKey() = floors().first().key

    private fun floors() = ChatMapFloors.build(
        listOf(ChatMapFloors.DateBucket(localDay = 1L, displayTitle = "Today", items = listOf(thread()))),
    )

    private fun setContent() {
        composeRule.setContent {
            Box(Modifier.size(width = windowWidthDp.dp, height = listHeightDp.dp)) {
                ChatMapScreen(
                    floors = floors(),
                    messageCountText = { "12" },
                    timeText = { "10:00" },
                    hasUnread = { false },
                )
            }
        }
    }

    @Test
    fun 前提_该档确实是超约束的() {
        // ⚠️ 先钉前提：fixture 必须真触发父约束压扁，否则下面几条
        // 都在"恰好通过"的尺寸上跑，等于没测
        val cardHeight = ChatMapGeometry.cardHeightDp(windowWidthDp)
        val rowHeight = ChatMapGeometry.rowHeightDp(listHeightDp)
        assertTrue(
            "fixture 必须 cardHeight > rowHeight（实测 $cardHeight vs $rowHeight）",
            cardHeight > rowHeight,
        )
        assertEquals(230.4f, cardHeight, 0.05f)
        assertEquals(213f, rowHeight, 0.05f)
    }

    @Test
    fun 卡叠实测高度等于请求值_不被父约束压扁() {
        setContent()
        val cardHeight = ChatMapGeometry.cardHeightDp(windowWidthDp)

        val node = composeRule.onNodeWithTag(cardRowTag(targetFloorKey()), useUnmergedTree = true)
            .fetchSemanticsNode()
        val measuredDp = with(composeRule.density) { node.size.height.toDp().value }

        assertEquals(
            "实测高度必须等于请求的 cardHeight，而不是被压到 rowHeight（213）",
            cardHeight,
            measuredDp,
            1f,
        )
    }

    @Test
    fun 楼层外壳仍是rowHeight_没有被内层撑大() {
        // ⚠️ 与"内层真实 230.4"成对：`wrapContentHeight(unbounded)` 对父
        // **仍上报 rowHeight**，只是把超出的 child 按 Top 放置。
        // 若外壳也被撑到 230.4，说明约束没截住 —— 楼层间距会整体错乱
        setContent()
        val rowHeight = ChatMapGeometry.rowHeightDp(listHeightDp)
        val floorNode = composeRule
            .onNodeWithTag("chat_map_floor_${targetFloorKey()}", useUnmergedTree = true)
            .fetchSemanticsNode()
        val floorDp = with(composeRule.density) { floorNode.size.height.toDp().value }
        assertEquals("楼层外壳必须仍是 rowHeight", rowHeight, floorDp, 1f)
    }

    @Test
    fun 卡叠顶部等于标题高加间距() {
        setContent()
        val node = composeRule.onNodeWithTag(cardRowTag(targetFloorKey()), useUnmergedTree = true)
            .fetchSemanticsNode()

        // ⚠️ 这条与高度那条**必须成对**：`requiredHeight` 也能突破父约束，
        // 但它对超约束 child **默认居中补偿** —— 高度对了而 top 会偏。
        // 只看高度数字会以为修好了
        val expectedTopDp = (
            ChatMapStyle.FLOOR_TITLE_HEIGHT_DP + ChatMapStyle.FLOOR_TITLE_BOTTOM_GAP_DP
            ).toFloat()
        val floorTopPx = node.positionInRoot.y - with(composeRule.density) {
            // 楼层自身相对根的偏移由 offset 决定，这里只验"卡叠相对楼层顶"的差值
            0.dp.toPx()
        }
        // 用同层的楼层节点做基准
        val floorNode = composeRule
            .onNodeWithTag("chat_map_floor_${floors().first().key}", useUnmergedTree = true)
            .fetchSemanticsNode()
        val deltaDp = with(composeRule.density) { (floorTopPx - floorNode.positionInRoot.y).toDp().value }

        assertEquals("卡叠顶 = 标题高 + gap", expectedTopDp, deltaDp, 1f)
    }

    private fun thread() = ChatThread(
        itemType = "character",
        itemId = "t1",
        itemName = "n",
        gameId = null,
        faceUrl = "",
        imageUrl = "",
        introduction = "",
        greeting = null,
        lastMessageContent = null,
        latestTimeSeconds = 1L,
        isPinned = false,
        isPushMessage = false,
        isPushMessageViewed = false,
        currentStreakDays = 0,
        chatMode = null,
        conversationId = null,
        parentConversationId = null,
        characterType = null,
        contentType = null,
        creatorId = null,
        versionChange = false,
    )
}
