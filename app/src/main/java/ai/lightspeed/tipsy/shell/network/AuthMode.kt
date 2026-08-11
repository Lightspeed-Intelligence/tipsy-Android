package ai.lightspeed.tipsy.shell.network

/**
 * 请求的鉴权模式（W1-P6，方案 §4.5）。
 *
 * ## 三种模式必须区分，不能简化成「带/不带 token」
 *
 * 对应 RN 侧两个 axios 实例的行为（实测 `src/utils/axios.ts`）：
 *
 * | 模式 | RN 对应 | 无 token 时 |
 * | --- | --- | --- |
 * | [REQUIRED] | `axiosAuth` | **不发请求**，抛未认证错误（RN 上层请求登录 UI） |
 * | [OPPORTUNISTIC] | `axiosPublic` | **照发**，只是不带 token |
 * | [NONE] | 无（壳侧新增） | 照发，且**从不**带 token |
 *
 * ## ⚠️ `axiosPublic` 不等于「永不带 token」
 *
 * 这是 iOS 踩过的坑，代价是**搜索历史永久为空**：
 * `/search/character_search` 走 `axiosPublic`，但它**带 token 才会把搜索词
 * 记入最近搜索**。iOS 把它实现成 `authorized: false`（等价于 [NONE]），
 * 于是「最近搜索」列表恒空 —— 不报错、不崩溃，只是功能静默失效。
 *
 * 所以 `axiosPublic` 的正确映射是 [OPPORTUNISTIC]：
 * **有 token 就带，没有也照发**。这正是三模式存在的唯一理由。
 *
 * [NONE] 是壳侧新增的，给「明确不该带凭据」的场景用（如向第三方域取资源）。
 * **不要用它替代 [OPPORTUNISTIC]** —— 那就是 iOS 那个 bug。
 */
enum class AuthMode {
    /**
     * 必须已登录。无有效 token 时**不发请求**，直接返回未认证错误；
     * 上层据此请求登录 UI。
     *
     * 对齐 RN `axiosAuth`：取不到有效 token 时 reject 并 `requestLogin('axios-auth')`
     * （`axios.ts:148-188`）。
     */
    REQUIRED,

    /**
     * 有 token 就带上，没有也正常发。
     *
     * 对齐 RN `axiosPublic`（`axios.ts:105-125`）。⚠️ 见类注释里 iOS 的搜索历史事故。
     */
    OPPORTUNISTIC,

    /** 从不带 token。仅用于明确不该携带凭据的请求。 */
    NONE,
}
