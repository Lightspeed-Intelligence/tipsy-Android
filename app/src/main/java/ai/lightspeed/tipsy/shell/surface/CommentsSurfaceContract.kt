package ai.lightspeed.tipsy.shell.surface

/**
 * Android 壳挂载 `CommentsSurface` 时使用的稳定宿主契约。
 *
 * iOS 将 component name 收在 `CommentsSurfaceViewController`；Android 的容器是
 * 通用 `RNSurfaceFragment`，用独立契约对象承接同一职责（同
 * [SettingsSurfaceContract] / [CreateSurfaceContract] 的先例）。
 *
 * 微根形状：`CommentsSurface.tsx` 复用**整条 `ChatDetailStackNavigator`**、
 * 初始屏 `Comments`（`initialParams.screen`）—— 与 `ChatDetailSurface` 同构，
 * 仅初始屏不同。评论页内部跳转（CommentReport 等）因此同栈可用，
 * 不存在微栈死链。
 */
object CommentsSurfaceContract {
    /** 必须逐字等于 `index.surfaces.js` 的 AppRegistry 注册名。 */
    const val COMPONENT_NAME = "CommentsSurface"
}
