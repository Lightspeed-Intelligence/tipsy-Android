package ai.lightspeed.tipsy.shell.surface

import android.os.Bundle
import java.util.UUID

/**
 * Surface 的 initial props 与 capability handshake（W1 计划 §12.4）。
 *
 * ## ⚠️ props 必须**平铺**，不能嵌套（2026-08-11 订正）
 *
 * 原实现把业务参数塞进嵌套的 `route` Bundle，**RN 侧没有任何 Surface 读它** ——
 * 全仓搜 `props.route` 零命中。13 个 Surface 一律读**平铺的顶层 props**：
 *
 * | Surface | 必需 props（实测） |
 * | --- | --- |
 * | `ChatDetailSurface` | `characterId`（**非可选**，`:75`） |
 * | `CommentsSurface` | `targetType` + `targetId`（`:16-24`） |
 * | `SettingsSurface` | `initialScreen?`（`:16-21`） |
 * | `NotificationSurface` | `tab?`（`:16-19`） |
 *
 * iOS 的 `ChatDetailSurfaceViewController.makeInitialProperties()` 产出的正是
 * 平铺形状。**Android 原来的嵌套形状会让 `characterId` 恒为 `undefined`** ——
 * 而 RN 侧不会报错，只会走「无参进入」的兜底分支，表现为「点某个角色却进了
 * 上次的会话」这类难以归因的问题。
 *
 * ## 壳自有字段与业务参数共存于同一层
 *
 * 壳自有的元数据（`surfaceContractVersion` / `surfaceInstanceId` /
 * `componentName` / `capabilities` / `context`）与业务参数**平级**。
 * 已核实 13 个 Surface 的 props 里**没有**任何一个用这些名字，无碰撞。
 * 新增壳字段前先搜一遍 RN 侧，撞名会静默覆盖业务参数。
 *
 * ## 版本兼容规则（**改这里前必读**）
 *
 * - **老 bundle 必须忽略新字段** —— 所以只增字段，不改语义
 * - **新 bundle 调新原生方法前查 capability 或用 `?.()`**
 * - 改语义 / 变必填 / 删字段 → 必须升 [CONTRACT_VERSION] **并**升 OTA runtime generation
 *
 * OTA 会把新 JS 推给旧 binary，也会让旧 JS 跑在新 binary 上。两个方向都要能活。
 *
 * ## ⚠️ token 绝不经 initial props
 *
 * 方案 §12.4 明写。initial props 会随 Fragment 参数进 `Bundle`，可能落入
 * saved instance state、ANR trace、崩溃日志。JS 按需调 `getValidToken()` 拿。
 */
object SurfaceContract {

    /**
     * 契约版本。**只在破坏性变更时递增**（改语义/变必填/删字段）。
     * 加字段不需要动它 —— 老 bundle 会忽略未知字段。
     *
     * ⚠️ 2026-08-11 从嵌套 `route` 改为平铺**没有**递增：嵌套形状从未被任何
     * bundle 消费过（RN 侧零命中），所以不存在「依赖旧形状的 JS」。
     * 这是修正一个从未生效的字段布局，不是契约变更。
     */
    const val CONTRACT_VERSION = 1

    // ── capability 标识 ───────────────────────────────────────
    //
    // 命名规则 `<域>.<能力>.v<n>`。**能力变更用新标识而不是改旧的**：
    // 旧 JS 查 `auth.valid-token.v1` 时若语义已变，它不知情 —— 这正是 capability
    // 机制要防的。语义变了就发 `.v2`，同时保留 `.v1` 直到旧 bundle 淘汰。

    /** `getValidToken()` 可用。 */
    const val CAP_AUTH_VALID_TOKEN = "auth.valid-token.v1"

    /** `onSurfaceReappeared` 事件会发射（§12.3）。 */
    const val CAP_LIFECYCLE_REAPPEARED = "lifecycle.reappeared.v1"

    /** `popSurface` / `openUserProfile` 等导航方法可用。 */
    const val CAP_NAVIGATION_OPEN_CHAT = "navigation.open-chat.v1"

    /** 本 binary 实际提供的能力集。**加能力时同步加在这里**。 */
    val CAPABILITIES: List<String> = listOf(
        CAP_AUTH_VALID_TOKEN,
        CAP_LIFECYCLE_REAPPEARED,
        CAP_NAVIGATION_OPEN_CHAT,
    )

    // ── 壳自有字段的 key ──────────────────────────────────────

    const val KEY_CONTRACT_VERSION = "surfaceContractVersion"
    const val KEY_INSTANCE_ID = "surfaceInstanceId"
    const val KEY_COMPONENT_NAME = "componentName"
    const val KEY_CAPABILITIES = "capabilities"
    const val KEY_CONTEXT = "context"

    const val CONTEXT_KEY_LANGUAGE = "languageCode"
    const val CONTEXT_KEY_ENVIRONMENT = "environment"
    const val CONTEXT_KEY_DISTRIBUTION = "distribution"

    /**
     * 壳自有字段名集合。用于 [buildInitialProps] 的撞名守卫与测试断言。
     *
     * `context` 是嵌套的 —— 它**不是**业务参数，是壳环境描述（语言/环境/渠道），
     * RN 侧目前不读它（业务 Surface 各自从 store 取），但保留以备 W4 的
     * distribution 分流。它嵌套是刻意的：与业务参数分层，不占顶层命名空间。
     */
    val SHELL_OWNED_KEYS: Set<String> = setOf(
        KEY_CONTRACT_VERSION,
        KEY_INSTANCE_ID,
        KEY_COMPONENT_NAME,
        KEY_CAPABILITIES,
        KEY_CONTEXT,
    )

    /**
     * 生成一个 Surface 实例 id。
     *
     * ⚠️ **每次打开都要新的**（ADR-003 / §12.1）。复用会让「迟到的旧实例事件
     * 关掉新实例」—— iOS 的 `popSurface` 闸是**类型判定**，正因如此弹错过同类型页
     * （后来用 `closingRef` 补的）。Android 从第一天就按实例判定。
     */
    fun newInstanceId(): String = UUID.randomUUID().toString()

    /**
     * 构造 initial props：壳自有字段 + **平铺的**业务参数。
     *
     * @param routeParams 业务参数，会**平铺到顶层**（不是塞进 `route` 子 Bundle）。
     *   Native 侧不校验其内容 —— 未知字段由 JS 忽略，这是「只增不改」规则的另一面。
     *
     * @throws IllegalArgumentException 业务参数与壳自有字段撞名，见
     *   [assertNoShellKeyClash]。
     */
    fun buildInitialProps(
        instanceId: String,
        componentName: String,
        languageCode: String?,
        environment: String,
        distribution: String,
        routeParams: Map<String, String> = emptyMap(),
    ): Bundle {
        assertNoShellKeyClash(routeParams.keys)

        return Bundle().apply {
            // 业务参数先放，壳字段后放 —— 顺序不影响结果（已有撞名守卫），
            // 但这样读起来更清楚「壳字段是附加在业务 props 之上的」
            for ((key, value) in routeParams) putString(key, value)

            putInt(KEY_CONTRACT_VERSION, CONTRACT_VERSION)
            putString(KEY_INSTANCE_ID, instanceId)
            putString(KEY_COMPONENT_NAME, componentName)
            putStringArrayList(KEY_CAPABILITIES, ArrayList(CAPABILITIES))
            putBundle(
                KEY_CONTEXT,
                Bundle().apply {
                    // languageCode 可为 null（壳无意见，JS 用自己的判定）——
                    // 放 null 而不是空串：空串在 boeLane 契约里有「显式停用」的含义，
                    // 这里保持同一套约定避免混淆
                    putString(CONTEXT_KEY_LANGUAGE, languageCode)
                    putString(CONTEXT_KEY_ENVIRONMENT, environment)
                    putString(CONTEXT_KEY_DISTRIBUTION, distribution)
                },
            )
            // ⚠️ 这里**没有** token，且不要加。见类注释。
        }
    }

    /**
     * 撞名守卫。抽成独立函数是为了**可单测** —— [buildInitialProps] 依赖
     * `android.os.Bundle`（JVM 单测里是抛异常的 stub），而这条校验本身
     * 不需要 Bundle，是最容易写错也最该被测到的部分。
     *
     * @throws IllegalArgumentException 撞名时。**刻意抛而不是静默覆盖**：
     *   撞名意味着要么业务参数丢失、要么壳元数据被污染，两者都只在运行期
     *   表现为「参数没生效」，极难归因。这是壳自己的装配错误，应当开发期就崩。
     */
    fun assertNoShellKeyClash(keys: Set<String>) {
        keys.firstOrNull { it in SHELL_OWNED_KEYS }?.let { clash ->
            throw IllegalArgumentException(
                "业务参数 `$clash` 与壳自有字段撞名：改业务参数名，或确认壳字段是否真该占这个名字",
            )
        }
    }
}
