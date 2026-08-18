package ai.lightspeed.tipsy.shell.surface

/**
 * Android 壳挂载 `CreateSurface` 时使用的稳定宿主契约。
 *
 * iOS 将 component name 收在 `CreateSurfaceViewController`；Android 继续复用
 * 通用 `RNSurfaceFragment`，由这个对象承接同一身份边界，避免 Activity、
 * RN 注册校验和后续编辑入口各自复制字符串。
 */
object CreateSurfaceContract {
    /** 必须逐字等于 `index.surfaces.js` 的 AppRegistry 注册名。 */
    const val COMPONENT_NAME = "CreateSurface"
}
