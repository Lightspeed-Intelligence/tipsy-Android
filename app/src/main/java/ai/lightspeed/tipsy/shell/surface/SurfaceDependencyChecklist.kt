package ai.lightspeed.tipsy.shell.surface

/**
 * Surface 微根依赖清单（W1-P9，方案 §4.3 / W1 计划 §11.2）。
 *
 * ## 为什么需要它
 *
 * `src/App.tsx` 在 Surface 模式下**永不挂载**，所以每个 Surface 根必须自备全局件。
 * **缺项的共同症状是「点了没反应」** —— 事件进了 store 但没人渲染，
 * 不报错、不崩溃，只能靠用户反馈发现。iOS 在 ChatDetail 与 Comments
 * 真的丢过全部 toast。
 *
 * 这个清单不是运行期校验（壳读不到 JS 组件树），而是**启用 Surface 前的人工
 * 核对表 + 可测的事实记录**。它的价值在于：
 * 1. 把「该有什么」写成代码而不是文档，改 RN 侧微根时这里会被 review 注意到
 * 2. 每项标注**缺失后果**，让核对时知道在看什么
 * 3. 启用新 Surface 时按同一模板加一行，不必重新读一遍 App.tsx
 *
 * ## 纪律
 *
 * **未过清单的 Surface 不得注册生产入口**（方案 §8.3）。
 * 路由到未启用的 Surface 必须给明确错误或安全 fallback，不做 silent no-op。
 */
object SurfaceDependencyChecklist {

    /** 一个微根依赖项。 */
    data class Requirement(
        /** 组件名（RN 侧）。 */
        val component: String,
        /** 缺失后果 —— 核对时看这个，不是看名字。 */
        val symptomIfMissing: String,
    )

    /**
     * `ChatDetailSurface` 的微根（实测 `src/surfaces/ChatDetailSurface.tsx:546-631`）。
     *
     * 顺序即嵌套顺序，**顺序本身有语义**：
     * - `SurfaceToastHost` 必须在具名 `PortalHost` 群**之前** —— 弹窗要盖在 toast
     *   之上（对齐 `App.tsx` 层序）
     * - 世界入口的 Loading 门在 provider 树**内层** —— 解析期 toast 宿主照常在场，
     *   全局错误提示不丢
     */
    val CHAT_DETAIL: List<Requirement> = listOf(
        Requirement("SafeAreaProvider", "安全区失效（内容顶到状态栏/挖孔下）"),
        Requirement("KeyboardProvider", "键盘避让失效 —— 聊天输入框被键盘盖住"),
        Requirement("SWRConfig", "缓存与 revalidate 语义和现网不一致"),
        Requirement("GestureHandlerRootView", "手势失效（左滑返回、抽屉拖拽）"),
        Requirement("PortalProvider", "所有 portal 投递无宿主"),
        Requirement("NavigationContainer", "页内导航不可用"),
        Requirement("Stack.Navigator", "五个微栈目标都到不了"),
        Requirement("RoleCardLimit", "角色卡超限弹窗只写 session store，无人渲染"),
        Requirement("GreetingVideoPortal", "点开场白视频卡只写 store —— 「点了没反应」"),
        Requirement("SurfaceToastHost", "**所有 toast 丢失**（iOS 在 ChatDetail/Comments 真实发生过）"),
        Requirement("PortalHost:RelationshipGuide", "关系引导弹窗不显示"),
        Requirement("PortalHost:GoldenEasterEggGuide", "金彩蛋引导不显示"),
        Requirement("PortalHost:LuckyEasterEggGuide", "幸运彩蛋引导不显示"),
        Requirement("PortalHost:LinkAccountModal", "绑定账号弹窗不显示"),
        Requirement("PortalHost:ValentineOpeningAnimation", "活动开场动画不显示"),
        Requirement("PortalHost:MayBallSplashPV", "活动开屏不显示（⚠️ 见 [NOTES]）"),
        Requirement("PortalHost:DeleteConfirmModal", "删除确认弹窗不显示 —— 用户点删除无反应"),
        Requirement("PortalHost:BaseModal", "**BaseModal 系全部不显示**（TipsyModal / 分享 / 通知弹窗）"),
    )

    /**
     * `ChatDetailSurface` 内部注册的导航目标（实测 `:568-600`）。
     *
     * 方案 §8.3 要求启用每个 Surface 时**枚举其内部所有 navigate 目标**，
     * 确认要么在微栈里、要么有桥出口 —— iOS 的 `RoleCardSurface` 缺
     * `CreateStack` 时换头像子流程直接死链。
     *
     * ChatDetail 这五个都在栈内，**不存在那种死链**。
     */
    val CHAT_DETAIL_STACK_TARGETS: List<String> = listOf(
        "ChatDetail",
        "ProfileStack",
        "CreateStack",
        "SimulatorGameDetail",
        "SimulatorGameProfile",
    )

    /**
     * 核对时需要人工确认的事项（写下来，避免下次重新调查）。
     *
     * 1. **`MayBallSplashPV` 与 `App.tsx` 的 `SplashPV` 名字不一致**（实测
     *    `ChatDetailSurface.tsx:628` vs `App.tsx:478`）。全仓搜下来**两个名字都
     *    没有对应的 `Portal hostName` 消费方**，且 `components/animations/SplashPV.tsx`
     *    根本不用 Portal —— 所以两侧看起来都是休眠的遗留项，不影响 ChatDetail。
     *    **但这是推断，不是实测结论**：真机验收时若发现活动开屏不弹，先查这里。
     *    ⚠️ 别"顺手统一"名字 —— 改 `index.surfaces.js` 系文件需要**双壳回归**。
     *
     * 2. **`VideoPlayerPoolInitializer` 刻意不挂**（`ChatDetailSurface.tsx:609-614`
     *    注释）。`GreetingVideoPlayer` 对 `preloadedPlayer` 空值有 `fallbackPlayer`
     *    兜底，池仅为预加载优化。方案 §4.2 已把它记为**已接受的取舍** ——
     *    **不要"顺手修复"**。
     */
    val NOTES: String = "见 KDoc"
}
