package ai.lightspeed.tipsy.shell.surface

/**
 * Android 壳挂载 `SettingsSurface` 时使用的稳定宿主契约。
 *
 * iOS 将 component name 收在 `SettingsSurfaceViewController`；Android 的容器是
 * 通用 `RNSurfaceFragment`，因此用独立契约对象承接同一职责，避免 Activity、
 * RN 注册校验与后续深链各自复制字符串。
 */
object SettingsSurfaceContract {
    /** 必须逐字等于 `index.surfaces.js` 的 AppRegistry 注册名。 */
    const val COMPONENT_NAME = "SettingsSurface"
}
