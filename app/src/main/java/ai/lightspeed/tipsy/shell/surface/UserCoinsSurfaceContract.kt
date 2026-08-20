package ai.lightspeed.tipsy.shell.surface

/**
 * Android 壳挂载 `UserCoinsSurface` 时使用的稳定宿主契约。
 *
 * 微栈是 ProfileStack 的三页子集（UserCoins 栈底 + WithdrawExplain +
 * WithdrawStatus）—— RN 刻意不复用整个 ProfileStackNavigator（注释：避免
 * 引入 UserProfile/Follow 等无关重依赖）。无 props（页面自取 user store）。
 */
object UserCoinsSurfaceContract {
    /** 必须逐字等于 `index.surfaces.js` 的 AppRegistry 注册名。 */
    const val COMPONENT_NAME = "UserCoinsSurface"
}
