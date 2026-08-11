# W1-CLOSEOUT-1 READY：认证与网络安全收口

> 状态：IMPLEMENTED / VALIDATION NOT RUN（2026-08-11）
> Android base：`3f2f4675eda17f11134fe9b6182348932bf50de2`
> RN pin：`a4eb9055d2b82932df3017dc411e5b810c5d3cb9`

## 目标

在继续 P5/P9 前，关闭已知不安全业务入口，并把 auth/network 的跨账号、过期 token、
401/402 双入口语义收敛到可测试的单一实现。

## 本包边界

包含：

- P9 前禁用 `ChatDetail` route；拒绝必须显式、可诊断，不能挂未注册组件。
- `ShellTokenStore` 的 refresh 所有退出路径都校验 auth generation；旧刷新不得返回旧账号
  token，也不得清除新账号 token；完成后的 auth 事件必须回到主线程再触达 Router/UI。
- bridge `clearToken()` 只清 token；完整 `logout()` 才收栈并发一次 loggedOut。
  refresh 失败导致 token 自动失效时也必须发一次 loggedOut，但不由 provider 重复广播。
- Native `ApiClient` 发送前校验 token：`REQUIRED` 遇无效 token 不发请求，
  `OPPORTUNISTIC` 遇无效 token 省略 header 后继续。
- bridge `getValidToken()` 对 expired/malformed 返回 null，覆盖 WebView/SSE 等不经过
  axios 二次过滤的 RN 消费者。
- RN bridge 与 Native 请求的 401/402 共用同一个进程级 `ApiErrorGate` 与防抖窗口。
- 订正唯一进度真值与 live RN 的 401 契约。

不包含：

- 不改 `tipsy-app/` 内部文件，不 bump submodule。
- 不实现 401 response refresh/retry；live RN 只在请求前刷新。
- 不修 ChatDetail props、instance-aware close、Login/Profile 桥；这些属于
  `W1-CLOSEOUT-2`。
- 不开始 P5/P7/P8/P9，不发布 OTA，不改签名或版本号。

## 验收

1. A 账号 refresh 在飞时切换到 B，A refresh 抛错或返回空 token，都不能返回 A token、
   清除 B token或发 loggedOut。
2. 当前账号 refresh 失败：旧 token 尚未过期时可继续返回；已过期时清除并只通知一次。
3. bridge 对 expired/malformed 返回 null；`REQUIRED` 对它们抛 `Unauthenticated` 且
   请求数为 0；`OPPORTUNISTIC` 继续请求但无 token header。
4. RN 与 Native 在同一窗口上报同类 401/402，只执行一次全局副作用；401/402 轨道互不吞。
5. ChatDetail 深链返回明确 disabled 结果，不调用 Surface navigator。
6. 不记录 token，不新增 lint baseline，不通过弱化测试配置换绿。

## 验证

零成本静态守卫：

- `git diff --check`
- `git status --short --branch`
- 确认 `git diff -- tipsy-app` 为空。

需要用户授权后运行：

- `./gradlew :app:testGooglePlayDebugUnitTest --tests 'ai.lightspeed.tipsy.shell.ShellTokenStoreTest' --tests 'ai.lightspeed.tipsy.shell.ApiClientTest' --tests 'ai.lightspeed.tipsy.shell.ApiErrorGateTest' --tests 'ai.lightspeed.tipsy.shell.ShellAuthProviderTest' --tests 'ai.lightspeed.tipsy.shell.AppRouterTest' --no-daemon --stacktrace`
- 现有 G1：lint、assemble、release manifest、app unit、tipsy-auth unit、skipped=0 守卫。

截至 2026-08-11，本包生产代码与回归测试已写完；仅执行了上述静态守卫。
Gradle、编译与单测均按工作区约定保持 **NOT RUN**，在得到用户授权前不得写成“通过”。

## 回滚与停止条件

- 回滚只 revert 本包自有 commit；不改用户其他 WIP，不回退 submodule pin。
- 若修复需要改变 `tipsy-auth` TS/Kotlin bridge 签名，停止本包，另开 tipsy-app PR。
- 若测试暴露跨账号写入/清除仍可能发生，保持 ChatDetail disabled，不开始 P5/P9。
- 若只能通过放宽 lint、跳过测试或 `returnDefaultValues` 通过，停止并复审设计。
