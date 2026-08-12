package ai.lightspeed.tipsy.shell.pages.profile

/**
 * 五个 tab 的条目公共上界。
 *
 * 存在的唯一理由是**让分页壳能去重而不必知道具体类型**：
 * [ProfileTabPaging] 里存 `List<ProfileListEntry>`，翻页时按 [dedupeKey] 去重，
 * 不需要为每个 tab 复制一遍去重逻辑。
 *
 * ⚠️ 不要在这里加展示字段（标题/封面之类）。五个 tab 的卡片形状差异很大
 * （创作是三列网格、记忆是单列大卡、角色卡又是另一种），
 * 硬抽公共展示字段会逼着某些 tab 塞 null 占位，反而让 UI 层多一层判空。
 * UI 层按具体类型分支渲染（`when (entry)`），这是刻意的。
 */
sealed interface ProfileListEntry {
    /**
     * 分页去重键。
     *
     * ⚠️ **同一个 tab 内唯一即可**，不要求跨 tab 唯一 ——
     * 每个 tab 各存一份列表，不会混在一起比。
     */
    val dedupeKey: String
}
