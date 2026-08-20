package ai.lightspeed.tipsy.shell.surface

/**
 * Android 壳挂载 `RoleCardSurface` 时使用的稳定宿主契约。
 *
 * ⚠️ 微容器 root stack 包裹 **EditRoleCard + CreateStack 两个目标** ——
 * 换头像子流程走内嵌 CreateStack，缺它即死链。这正是 §8.3 点名的
 * iOS 原始事故（`RoleCardSurface` 缺 `CreateStack` 时换头像直接死链，
 * React Navigation 对不存在的目标是静默 no-op 不崩），静态 gate 必须锁。
 */
object RoleCardSurfaceContract {
    /** 必须逐字等于 `index.surfaces.js` 的 AppRegistry 注册名。 */
    const val COMPONENT_NAME = "RoleCardSurface"
}
