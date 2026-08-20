package ai.lightspeed.tipsy.shell.surface

/**
 * Android 壳挂载 `GemsSubscriptionSurface` 时使用的稳定宿主契约。
 *
 * 入口有三：Profile 钱包卡（宝石 +/Upgrade）、桥 `openGemsPurchase`
 * （RN 页内的购买入口）、402 付费墙兜底（`ApiErrorGate` 防抖后）——
 * 三者全部汇到 `AppRoute.GemsPurchase`，props 形状照 iOS
 * `GemsSubscriptionSurfaceViewController`（camelCase + snake 别名归一）。
 */
object GemsSubscriptionSurfaceContract {
    /** 必须逐字等于 `index.surfaces.js` 的 AppRegistry 注册名。 */
    const val COMPONENT_NAME = "GemsSubscriptionSurface"
}
