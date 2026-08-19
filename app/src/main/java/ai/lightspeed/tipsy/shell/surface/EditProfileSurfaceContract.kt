package ai.lightspeed.tipsy.shell.surface

/**
 * Android 壳挂载 `EditProfileSurface` 时使用的稳定宿主契约。
 *
 * iOS 把 module name 收在 `EditProfileSurfaceViewController`；Android 复用通用
 * `RNSurfaceFragment`，因此把同一身份边界集中在这里，避免 Activity、退栈去重
 * 与静态 RN 契约测试各自复制字符串。
 */
object EditProfileSurfaceContract {
    /** 必须逐字等于 `index.surfaces.js` 的 AppRegistry 注册名。 */
    const val COMPONENT_NAME = "EditProfileSurface"
}
