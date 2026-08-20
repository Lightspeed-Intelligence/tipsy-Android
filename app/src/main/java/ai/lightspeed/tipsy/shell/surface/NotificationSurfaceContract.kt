package ai.lightspeed.tipsy.shell.surface

/**
 * Android 壳挂载 `NotificationSurface` 时使用的稳定宿主契约。
 *
 * 微根形状：复用 `NotificationStackNavigator` 两页（Notification 列表 +
 * NotificationDetail 详情）、初始屏 Notification。Engagement tab 的
 * 跨栈出口（头像/评论卡/作品图/回馈）经桥走壳原生容器 ——
 * `openUserProfile`/`openComments`/`openChatDetail`/`openFeedback`；
 * World 作品图（`openSimulatorGame`）壳侧无路由，RN 注释已明确降级不响应。
 */
object NotificationSurfaceContract {
    /** 必须逐字等于 `index.surfaces.js` 的 AppRegistry 注册名。 */
    const val COMPONENT_NAME = "NotificationSurface"
}
