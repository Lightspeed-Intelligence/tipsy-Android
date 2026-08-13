package ai.lightspeed.tipsy.shell.pages.chatlist

import ai.lightspeed.tipsy.shell.R
import ai.lightspeed.tipsy.shell.i18n.LocalizedText
import ai.lightspeed.tipsy.shell.i18n.rememberLocalizedString
import ai.lightspeed.tipsy.shell.ui.s
import ai.lightspeed.tipsy.shell.ui.sSp
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import ai.lightspeed.tipsy.shell.pages.home.HomeText
import kotlinx.coroutines.delay

/**
 * ChatList 页（Tab4「時光長廊」，方案 §8.1 ChatList 行，W3 P1）。
 *
 * ## 列表纪律（方案 §8.4）
 *
 * - stable key：`LazyColumn` 的 `key = thread.stableKey`（业务四元组，
 *   不掺 index/latest_time，见 [ChatThread.stableKey]）
 * - 徽章晚到只更新徽章位（`badgeFor` 派生读 map），列表不重配
 * - 翻页去重 + 空页续拉限次在 ViewModel
 *
 * ## P1 边界
 *
 * Map「時光長廊」是 P2（重视觉自绘）—— Map 按钮可点、显示 Coming soon 占位
 * （同 Profile 三 tab 的先例：可见的占位而非隐藏入口，隐藏会让真机验收
 * 误以为切换按钮没做）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatListScreen(
    state: ChatListState,
    isGooglePlay: Boolean,
    statusBarPadding: Dp,
    listBottomPadding: Dp,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onPageTypeSelected: (ChatPageType) -> Unit,
    onBellClick: () -> Unit,
    onThreadClick: (ChatThread) -> Unit,
    onPinClick: (ChatThread) -> Unit,
    onDeleteRequest: (ChatThread) -> Unit,
    onDeleteConfirm: () -> Unit,
    onDeleteDismiss: () -> Unit,
    onGameCardExposed: (ChatThread) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = statusBarPadding)
            .testTag("chat_list_screen"),
    ) {
        ChatListHeader(
            pageType = state.pageType,
            hasUnread = state.hasUnreadLetters,
            onPageTypeSelected = onPageTypeSelected,
            onBellClick = onBellClick,
        )
        when (state.pageType) {
            ChatPageType.GRID -> GridContent(
                state = state,
                isGooglePlay = isGooglePlay,
                listBottomPadding = listBottomPadding,
                onRefresh = onRefresh,
                onLoadMore = onLoadMore,
                onThreadClick = onThreadClick,
                onPinClick = onPinClick,
                onDeleteRequest = onDeleteRequest,
                onGameCardExposed = onGameCardExposed,
            )

            ChatPageType.MAP -> MapPlaceholder()
        }
    }

    state.pendingDelete?.let { target ->
        DeleteConfirmDialog(
            target = target,
            isDeleting = state.isDeleting,
            onConfirm = onDeleteConfirm,
            onDismiss = onDeleteDismiss,
        )
    }
}

/** 顶栏：标题 + Grid/Map 切换胶囊 + 铃铛（`index.tsx:286-329`）。 */
@Composable
private fun ChatListHeader(
    pageType: ChatPageType,
    hasUnread: Boolean,
    onPageTypeSelected: (ChatPageType) -> Unit,
    onBellClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(ChatListStyle.HEADER_HEIGHT.s)
            .padding(horizontal = ChatListStyle.HEADER_PADDING_H.s),
    ) {
        LocalizedText(
            key = "Time Corridor",
            color = ChatListStyle.headerTitleColor,
            fontSize = ChatListStyle.HEADER_TITLE_FONT.sSp,
            modifier = Modifier.padding(start = 10.s),
        )
        Spacer(Modifier.weight(1f))
        // Grid/Map 切换胶囊
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(ChatListStyle.TOGGLE_RADIUS.s))
                .background(ChatListStyle.toggleBackground)
                .height(ChatListStyle.TOGGLE_HEIGHT.s)
                .padding(horizontal = ChatListStyle.TOGGLE_PADDING_H.s),
        ) {
            Image(
                painter = painterResource(
                    if (pageType == ChatPageType.GRID) {
                        R.drawable.ic_chatlist_grid_on
                    } else {
                        R.drawable.ic_chatlist_grid_off
                    },
                ),
                contentDescription = "Grid view",
                modifier = Modifier
                    .size(ChatListStyle.TOGGLE_ICON.s)
                    .clickable { onPageTypeSelected(ChatPageType.GRID) }
                    .testTag("chat_list_toggle_grid"),
            )
            Box(
                Modifier
                    .padding(horizontal = 4.s)
                    // 发丝分割线不缩放（缩放后会消失或变粗）
                    .width(1.dp)
                    .height(ChatListStyle.SPLIT_LINE_HEIGHT.s)
                    .background(ChatListStyle.splitLineColor),
            )
            Image(
                painter = painterResource(
                    if (pageType == ChatPageType.MAP) {
                        R.drawable.ic_chatlist_map_on
                    } else {
                        R.drawable.ic_chatlist_map_off
                    },
                ),
                contentDescription = "Corridor view",
                modifier = Modifier
                    .size(ChatListStyle.TOGGLE_ICON.s)
                    .clickable { onPageTypeSelected(ChatPageType.MAP) }
                    .testTag("chat_list_toggle_map"),
            )
        }
        Spacer(Modifier.width(12.s))
        // 铃铛 + 未读红点
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(ChatListStyle.TOGGLE_HEIGHT.s)
                .clip(CircleShape)
                .clickable(onClick = onBellClick)
                .testTag("chat_list_bell"),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_chatlist_bell),
                contentDescription = "Notifications",
                modifier = Modifier.size(ChatListStyle.TOGGLE_ICON.s),
            )
            if (hasUnread) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.s, end = 7.s)
                        .size(ChatListStyle.BELL_DOT.s)
                        .clip(CircleShape)
                        .background(ChatListStyle.bellDotColor),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GridContent(
    state: ChatListState,
    isGooglePlay: Boolean,
    listBottomPadding: Dp,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onThreadClick: (ChatThread) -> Unit,
    onPinClick: (ChatThread) -> Unit,
    onDeleteRequest: (ChatThread) -> Unit,
    onGameCardExposed: (ChatThread) -> Unit,
) {
    when {
        state.isInitialLoading && state.threads.isEmpty() -> Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .testTag("chat_list_loading"),
        ) {
            CircularProgressIndicator(color = Color.White)
        }

        state.errorMessage != null && state.threads.isEmpty() -> ErrorState(
            message = state.errorMessage,
            onRetry = onRefresh,
        )

        state.showEmptyState -> EmptyState()

        else -> ThreadList(
            state = state,
            isGooglePlay = isGooglePlay,
            listBottomPadding = listBottomPadding,
            onRefresh = onRefresh,
            onLoadMore = onLoadMore,
            onThreadClick = onThreadClick,
            onPinClick = onPinClick,
            onDeleteRequest = onDeleteRequest,
            onGameCardExposed = onGameCardExposed,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThreadList(
    state: ChatListState,
    isGooglePlay: Boolean,
    listBottomPadding: Dp,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onThreadClick: (ChatThread) -> Unit,
    onPinClick: (ChatThread) -> Unit,
    onDeleteRequest: (ChatThread) -> Unit,
    onGameCardExposed: (ChatThread) -> Unit,
) {
    val listState = rememberLazyListState()
    // 同表最多一行滑开（RN closeOtherRows 的对应物）
    var openRowKey by remember { mutableStateOf<String?>(null) }

    // 触底翻页（同 HomeScreen 的 derivedStateOf + snapshotFlow 惯用法）
    val shouldLoadMore by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) return@derivedStateOf false
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= total - (info.visibleItemsInfo.size / 2).coerceAtLeast(1) - 1
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { shouldLoadMore }.collect { if (it) onLoadMore() }
    }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                top = ChatListStyle.ROW_GAP.s,
                bottom = listBottomPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(ChatListStyle.ROW_GAP.s),
            modifier = Modifier
                .fillMaxSize()
                .testTag("chat_list"),
        ) {
            items(
                items = state.sortedThreads,
                key = { it.stableKey },
            ) { thread ->
                // game 卡曝光：可见即报（95%+1s 的 RN 精确判定后置 ——
                // LazyColumn 的组合即近似可见，2s 节流在 ViewModel 兜底防刷）
                if (thread.itemType == ChatThread.TYPE_GAME) {
                    LaunchedEffect(thread.stableKey) {
                        delay(SIMULATOR_EXPOSURE_DWELL_MS)
                        onGameCardExposed(thread)
                    }
                }
                ChatListRow(
                    thread = thread,
                    draft = state.draftFor(thread),
                    badge = state.badgeFor(thread),
                    isGooglePlay = isGooglePlay,
                    openRowKey = openRowKey,
                    onSwipeOpen = { openRowKey = it },
                    onClick = { onThreadClick(thread) },
                    onPinClick = { onPinClick(thread) },
                    onDeleteClick = { onDeleteRequest(thread) },
                )
            }
            if (state.isLoadingMore) {
                item(key = "loading_more") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.s),
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(24.s),
                        )
                    }
                }
            }
        }
    }
}

/** 空态（`index.tsx:91-107`）：插图 + 文案。 */
@Composable
private fun EmptyState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.s, Alignment.CenterVertically),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 36.s)
            .testTag("chat_list_empty"),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_chatlist_empty),
            contentDescription = null,
            modifier = Modifier.size(
                width = ChatListStyle.EMPTY_ICON_W.s,
                height = ChatListStyle.EMPTY_ICON_H.s,
            ),
        )
        LocalizedText(
            key = "You haven't chatted with anyone yet. Start a conversation now.",
            color = ChatListStyle.emptyTextColor,
            fontSize = ChatListStyle.EMPTY_FONT.sSp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(ChatListStyle.EMPTY_MAX_WIDTH.s),
        )
    }
}

/** 首屏失败（列表为空时才出现，§8.4）。文案同 Home/Profile 的错误位。 */
@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.s, Alignment.CenterVertically),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 36.s)
            .testTag("chat_list_error"),
    ) {
        val text = if (message == ChatListViewModel.FALLBACK_ERROR_KEY) {
            rememberLocalizedString(ChatListViewModel.FALLBACK_ERROR_KEY)
        } else {
            message
        }
        Text(
            text = text,
            color = ChatListStyle.emptyTextColor,
            fontSize = 14.sSp,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetry) {
            LocalizedText(key = "Retry", color = Color.White, fontSize = 14.sSp)
        }
    }
}

/** Map 视图占位（P2 落地即删；同 Profile 未接 tab 的 Coming soon 先例）。 */
@Composable
private fun MapPlaceholder() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .testTag("chat_list_map_placeholder"),
    ) {
        // 壳专属占位，刻意不进 SHELL_KEYS（fallback 到 key 本身，P2 即删）
        Text(
            text = "Coming soon",
            color = ChatListStyle.emptyTextColor,
            fontSize = 14.sSp,
        )
    }
}

/**
 * 删除二次确认（`index.tsx:338-359` 的 TipsyBottomSheet）。
 *
 * 壳用居中 Dialog 而不是底部弹层：`HomeFilterDrawer` 已用 Dialog 先例，
 * 且 M3 ModalBottomSheet 的手势域会与列表行左滑冲突。按钮语义照 RN：
 * Cancel（关闭）/ Delete（确认，loading 中禁用）。
 */
@Composable
private fun DeleteConfirmDialog(
    target: ChatThread,
    isDeleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = { if (!isDeleting) onDismiss() }) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(16.s))
                .background(Color(0xFF2B1B19))
                .padding(20.s)
                .testTag("chat_list_delete_dialog"),
        ) {
            AsyncImage(
                model = HomeText.transformImageUrl(
                    target.faceUrl.ifEmpty { target.imageUrl },
                ),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.s)
                    .clip(CircleShape),
            )
            Spacer(Modifier.height(7.s))
            // RN：t('Permanently delete the chat with characterName', {characterName}) + '？'
            // 全角问号在词条外（`index.tsx:352-356`）
            Text(
                text = rememberLocalizedString(
                    "Permanently delete the chat with characterName",
                    mapOf("characterName" to target.itemName),
                ) + "？",
                color = ChatListStyle.deleteModalTextColor,
                fontSize = ChatListStyle.DELETE_MODAL_FONT.sSp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.s))
            Row(horizontalArrangement = Arrangement.spacedBy(12.s)) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isDeleting,
                    modifier = Modifier.testTag("chat_list_delete_cancel"),
                ) {
                    LocalizedText(key = "Cancel", color = Color.White, fontSize = 14.sSp)
                }
                Button(
                    onClick = onConfirm,
                    enabled = !isDeleting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ChatListStyle.deleteActionColor,
                    ),
                    modifier = Modifier.testTag("chat_list_delete_confirm"),
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(16.s),
                        )
                    } else {
                        LocalizedText(key = "Delete", color = Color.White, fontSize = 14.sSp)
                    }
                }
            }
        }
    }
}

/** simulator 曝光的停留判定（RN viewabilityConfig `minimumViewTime: 1000`）。 */
private const val SIMULATOR_EXPOSURE_DWELL_MS = 1000L
