package ai.lightspeed.tipsy.shell.pages.screen

/**
 * 大屏页「是否该播放」的三轴合成（W4-P2）。
 *
 * 抽成纯函数是为了能单测 —— 三条轴各自的触发方式完全不同，靠真机把
 * 八种组合走一遍不现实，而漏掉任一条的后果都是**视频在不该播的时候继续播**
 * （还占着音频焦点），且不报错。
 *
 * | 轴 | 何时变化 | 漏了会怎样 |
 * | --- | --- | --- |
 * | `started` | Activity `onStart`/`onStop`（冷启、回前台、进程恢复） | 退到桌面仍在播 |
 * | `hidden` | 切 Tab —— `TabHostFragment` 用 show/hide 保状态，**不走生命周期** | 切到别的 Tab 仍在播 |
 * | `covered` | 打开 Surface —— `surface_container` 是 `native_root_container` 的 **sibling**，本页既不 hidden 也不 stop | 开了 ChatDetail/Search/Settings 仍在后台播 |
 */
internal object ScreenVisibility {

    /** 三条轴全部成立才播。 */
    fun isVisible(started: Boolean, hidden: Boolean, covered: Boolean): Boolean =
        started && !hidden && !covered
}
