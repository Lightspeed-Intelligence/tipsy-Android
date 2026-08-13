package ai.lightspeed.tipsy.shell.pages.chatlist

/**
 * ChatList 的页面状态（单 data class 原子替换，同 `HomeState`/`ProfileState`）。
 *
 * ## [threads] 是**接口顺序**的累计列表，展示排序在 [sortedThreads] 派生
 *
 * RN 的 `sortedData`（`ChatGrid.tsx:99-121`）在 render 层排序、不回写 SWR 数据。
 * 壳同构：分页累计保持接口顺序（去重/翻页游标依赖它稳定），
 * 草稿混排只是显示规则 —— 排序进 [threads] 会让「读一次草稿」变成一次列表写入。
 *
 * ## 展示排序规则（`ChatGrid.tsx:113-119`，实测）
 *
 * 1. `is_pinned` 恒在前（pinned 之间、非 pinned 之间各自保持第 2 条的时间序）
 * 2. 时间 = 草稿 `updatedAt`（若有且非 mini_phone）否则 `latest_time*1000`，降序
 *
 * ⚠️ RN 只在**存在至少一条草稿**时才重排（`draftMap.size === 0` 直接返回原序）。
 * 这意味着无草稿时展示的是接口原序 —— 接口本身已按 pinned+时间排好。
 * 壳照抄这条捷径：不是性能优化，是**行为对等**（接口序与本地排序在边界
 * 数据上可能不同，比如两条同秒的会话）。
 */
data class ChatListState(
    val threads: List<ChatThread> = emptyList(),
    /** `characterId → 草稿`；mini_phone 行不查（见 [ChatDraftStore] 类注释）。 */
    val drafts: Map<String, ChatDraft> = emptyMap(),
    /** `characterId → 关系等级`；徽章晚到只更新此表，不动 [threads]（§8.4）。 */
    val relationshipStats: Map<String, RelationshipStat> = emptyMap(),
    /** 账号级关系开关（`user.relationship_switch`）；null = 未知（未拉到 user）。 */
    val relationshipSwitch: Boolean = false,
    /** 铃铛红点（`unread_messages`）。 */
    val hasUnreadLetters: Boolean = false,
    /** Grid / Map 视图。P1 期间 MAP 显示占位。 */
    val pageType: ChatPageType = ChatPageType.GRID,
    /** 服务端总数（Map 视图的计数用；`need_total: true` 的返回）。 */
    val total: Long = 0L,
    /** 首屏加载中（无内容时显示 loading；有种子时不显示）。 */
    val isInitialLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasReachedEnd: Boolean = false,
    /** 是否已成功拉过首屏（区分「空列表」与「还没拉过」，空态判定用）。 */
    val hasLoadedOnce: Boolean = false,
    /** 首屏错误文案；只在列表为空时展示（方案 §8.4）。 */
    val errorMessage: String? = null,
    /** 正在展示的删除确认对象；null = 弹窗关闭。 */
    val pendingDelete: ChatThread? = null,
    val isDeleting: Boolean = false,
    /** 一次性 Toast 消息（i18n key），UI 消费后调 `consumeToast`。 */
    val toastKey: String? = null,
) {

    /**
     * 空态判定（`index.tsx:86-87` 的 `shouldShowGridEmptyState`）：
     * 无内容 && 不在加载 && 无错误 && **拉过至少一次**。
     */
    val showEmptyState: Boolean
        get() = threads.isEmpty() && !isInitialLoading && errorMessage == null && hasLoadedOnce

    /** 展示序列表，见类注释的排序规则。 */
    val sortedThreads: List<ChatThread>
        get() {
            if (threads.isEmpty()) return threads
            // 与 RN 同构的捷径：无可用草稿时保持接口原序
            val hasAnyDraft = threads.any { draftFor(it) != null }
            if (!hasAnyDraft) return threads
            return threads.sortedWith(
                compareByDescending<ChatThread> { it.isPinned }
                    .thenByDescending { displayTimeMs(it) },
            )
        }

    /** 该行的草稿；mini_phone 行恒 null（草稿键是角色 id，会串到小手机行）。 */
    fun draftFor(thread: ChatThread): ChatDraft? =
        if (thread.isMiniPhone) null else drafts[thread.itemId]

    /** 行时间 = 草稿时间（有则）否则会话时间，毫秒（`ChatGrid.tsx:116-117`）。 */
    fun displayTimeMs(thread: ChatThread): Long =
        draftFor(thread)?.updatedAt ?: (thread.latestTimeSeconds * 1000L)

    /**
     * 该行的 LV 徽章数据；四条件（`ChatListItem.tsx:423-426`）：
     * sub_level > 0 && 账号开关 && 角色开关 && 非 mini_phone。
     */
    fun badgeFor(thread: ChatThread): RelationshipStat? {
        if (thread.isMiniPhone || !relationshipSwitch) return null
        val stat = relationshipStats[thread.itemId] ?: return null
        if (stat.subLevel <= 0 || !stat.isRelationshipOpen) return null
        return stat
    }
}
