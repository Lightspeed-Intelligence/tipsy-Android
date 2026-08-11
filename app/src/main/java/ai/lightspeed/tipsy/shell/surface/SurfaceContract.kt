package ai.lightspeed.tipsy.shell.surface

import android.os.Bundle
import java.util.UUID

/**
 * Surface 的 initial props 与 capability handshake（W1 计划 §12.4）。
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

    // ── initial props 的 key ──────────────────────────────────

    const val KEY_CONTRACT_VERSION = "surfaceContractVersion"
    const val KEY_INSTANCE_ID = "surfaceInstanceId"
    const val KEY_COMPONENT_NAME = "componentName"
    const val KEY_CAPABILITIES = "capabilities"
    const val KEY_ROUTE = "route"
    const val KEY_CONTEXT = "context"

    const val CONTEXT_KEY_LANGUAGE = "languageCode"
    const val CONTEXT_KEY_ENVIRONMENT = "environment"
    const val CONTEXT_KEY_DISTRIBUTION = "distribution"

    /**
     * 生成一个 Surface 实例 id。
     *
     * ⚠️ **每次打开都要新的**（ADR-003 / §12.1）。复用会让「迟到的旧实例事件
     * 关掉新实例」—— iOS 的 `popSurface` 闸是**类型判定**，正因如此弹错过同类型页
     * （后来用 `closingRef` 补的）。Android 从第一天就按实例判定。
     */
    fun newInstanceId(): String = UUID.randomUUID().toString()

    /**
     * 构造 initial props。
     *
     * @param route 深链/导航参数。**Native 侧不校验其内容** —— 未知字段由 JS 忽略，
     *   这是「只增不改」规则的另一面。
     */
    fun buildInitialProps(
        instanceId: String,
        componentName: String,
        languageCode: String?,
        environment: String,
        distribution: String,
        route: Bundle? = null,
    ): Bundle = Bundle().apply {
        putInt(KEY_CONTRACT_VERSION, CONTRACT_VERSION)
        putString(KEY_INSTANCE_ID, instanceId)
        putString(KEY_COMPONENT_NAME, componentName)
        putStringArrayList(KEY_CAPABILITIES, ArrayList(CAPABILITIES))
        putBundle(KEY_ROUTE, route ?: Bundle())
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
