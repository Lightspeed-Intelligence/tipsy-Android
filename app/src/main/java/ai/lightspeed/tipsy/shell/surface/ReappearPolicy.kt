package ai.lightspeed.tipsy.shell.surface

/**
 * 「是否该发 `onSurfaceReappeared`」的判定（W1 §12.3）。
 *
 * ## 为什么单独抽出来
 *
 * 这条规则有两个真实的错法，都不报错、只表现为数据刷新行为怪异：
 *
 * | 错法 | 症状 |
 * | --- | --- |
 * | 首次 onResume 也发 | 每次打开页面**多拉一次数据**（首帧后立刻重拉） |
 * | 旋转后当成首次 | 转一下屏幕就**重新拉一次数据** |
 *
 * 判定逻辑放在 Fragment 里就只能靠设备验收；抽成纯函数才能用单测钉死。
 * Fragment 只负责把生命周期与 saved state 喂进来。
 */
object ReappearPolicy {

    /**
     * @param hasResumedOnce 本 Surface 实例是否已经历过一次 onResume
     *   （**必须跨配置变更保留** —— 存 saved instance state）
     * @return true 表示该发事件
     */
    fun shouldEmit(hasResumedOnce: Boolean): Boolean = hasResumedOnce

    /**
     * 从 saved state 恢复标记。
     *
     * ⚠️ **null（无 saved state）表示真正的首次创建**，返回 false。
     * 若这里默认成 true，每次打开页面都会多发一次事件 → 多拉一次数据。
     */
    fun restoreHasResumed(savedValue: Boolean?): Boolean = savedValue ?: false
}
