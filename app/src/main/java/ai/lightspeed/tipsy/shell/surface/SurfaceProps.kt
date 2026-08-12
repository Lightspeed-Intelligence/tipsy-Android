package ai.lightspeed.tipsy.shell.surface

import ai.lightspeed.tipsy.shell.router.AppRoute

/**
 * 把 [AppRoute] 映射成目标 Surface 的**业务 props**（W1-CLOSEOUT-2）。
 *
 * ## 为什么要单独一层
 *
 * 壳侧的 route 是类型化的 sealed class，RN 侧的 props 是一组约定好的 key。
 * 中间这层映射是**唯一容易静默出错的地方** —— key 拼错、漏传必填参数，
 * 两边都不报错：
 * - RN 侧 props 是 TS 类型，但 initial props 来自原生，**运行期不校验**
 * - Kotlin 侧只知道自己放了什么，不知道 JS 要什么
 *
 * 所以映射集中在这里并配单测，而不是散在各导航调用点。
 *
 * ## props 名称必须逐字对齐 RN
 *
 * 每个常量都标了实测出处。**改名前先去 RN 侧确认** —— 这些是跨仓契约，
 * 壳单方面改名的表现是「参数没生效」而不是编译失败。
 *
 * ## ⚠️ 返回 `Map` 而不是 `Bundle`
 *
 * `android.os.Bundle` 在 JVM 单测里是**抛异常的 stub**（与 `Base64`/`Log`/`Uri` 同类，
 * `SurfaceContractTest` 已记过这条）。映射逻辑正是最该被单测覆盖的部分 ——
 * 返回纯 Kotlin `Map` 让它可测，转 `Bundle` 留在 [SurfaceContract] 里做。
 *
 * 用 `returnDefaultValues = true` 绕是方案 §5.4 点名的假绿色，本仓已三次拒绝。
 */
object SurfaceProps {

    // ── ChatDetailSurface（`src/surfaces/ChatDetailSurface.tsx:75-140`）──

    /** **必填**（`:75` 声明为 `characterId: string`，非可选）。 */
    const val CHAT_CHARACTER_ID = "characterId"

    /** 初始子屏。缺省 = `ChatDetailPage`（`:120-140` 的联合类型）。 */
    const val CHAT_INITIAL_SCREEN = "initialScreen"

    /** mini phone 聊天页的初始屏值（`:118` 注释：对齐 `toChatPage` 的 mini_phone 分支）。 */
    const val CHAT_SCREEN_MINI_PHONE = "MiniPhoneChat"

    // ── 通用 ────────────────────────────────────────────────

    /** 用户 id。`openUserProfile` 的目标页属 W3，此处先留常量。 */
    const val USER_ID = "userId"

    /**
     * Follow 列表的类型：`followers` / `following`
     * （对齐 `FollowInfo.tsx:57,71` 传的 `type`）。
     */
    const val FOLLOW_TYPE = "type"

    /**
     * 把 route 转成业务 props。
     *
     * @return 平铺的业务参数；无参数的 route 返回**空 map**。
     *   空 map 与 null 在这里没有语义差别，返回空 map 让调用方少一层判空。
     */
    fun forRoute(route: AppRoute): Map<String, String> = when (route) {
        is AppRoute.ChatDetail -> buildMap {
            // characterId 是必填 prop，但 route 里可空（RN 侧该深链参数可选，
            // 进去后恢复上次会话）。
            //
            // 空白就不放，而不是放空串：RN 侧主要用 falsy 判定
            // （`if (!characterId)`，`:225`），所以空串与缺省**行为等价**；
            // 但不放能让「壳到底传了什么」在 props 里一目了然，
            // 排查时不必区分「传了空」与「没传」。
            // ⚠️ 例外：世界（SimulatorGame）入口**刻意传空串**（`:109` 注释），
            // 那条路径走 projectId，属 W4，届时不要套用这里的规则。
            route.characterId?.takeIf { it.isNotBlank() }?.let {
                put(CHAT_CHARACTER_ID, it)
            }
        }

        is AppRoute.MiniPhoneChat -> buildMap {
            route.characterId?.takeIf { it.isNotBlank() }?.let {
                put(CHAT_CHARACTER_ID, it)
            }
            // mini phone 与 ChatDetail 是**同一个 Surface 的不同初始屏**，
            // 不是两个 Surface（对齐 useChatNavigation.toChatPage 的分支）
            put(CHAT_INITIAL_SCREEN, CHAT_SCREEN_MINI_PHONE)
        }

        is AppRoute.UserProfile -> mapOf(USER_ID to route.userId)

        /*
         * Follow 列表需要 userId + type。
         *
         * ⚠️ 这两个 prop 现在**没有消费方** —— RN 侧不存在 FollowSurface
         * （已核实 `tipsy-app/src/surfaces/` 下无该文件），`follow.tsx` 是
         * ProfileStack 里的普通页面。这里先按 `FollowInfo.tsx:57,71` 的导航参数
         * 形状备好，真正启用时要么建 Surface、要么改成原生页（需 owner 定）。
         */
        is AppRoute.Follow -> mapOf(
            USER_ID to route.userId,
            FOLLOW_TYPE to route.type,
        )

        // 其余 route 的目标页尚未启用（Router 会先拦下）。
        // **不写 else -> null**：加新 route 时编译器要强制我来这里想一次
        // 「它需要什么 props」，而不是静默传空
        //
        // Settings / EditProfile / UserCoins 都是无参入口：
        // - Settings 是原生列表（§8.1），根本不经 Surface props
        // - EditProfile 在 RN 侧是同页 Drawer，无路由参数
        // - UserCoins 页自己从 user store 取当前用户，不需要壳传 id
        is AppRoute.DailyGemEntry,
        is AppRoute.UserBalance,
        is AppRoute.Subscribe,
        is AppRoute.Letter,
        is AppRoute.CreateProfileDetail,
        is AppRoute.GemsPurchase,
        is AppRoute.Login,
        is AppRoute.Settings,
        is AppRoute.EditProfile,
        is AppRoute.UserCoins,
        -> emptyMap()
    }
}
