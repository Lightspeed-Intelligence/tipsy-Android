# Android Native 迁移蓝图

本文只描述目标与约束，不记录完成状态。当前状态见 `../reference/android-native-progress.md`。

## 1. 架构决策

### ADR-001：Integrated brownfield 先行

首轮把固定 pin 的 `tipsy-app` 作为 submodule 直接接入 Android Gradle 工程，复用 Expo/RN autolinking、Hermes 和 `index.surfaces.js`。

原因：

- iOS 实际成功路径也是集成式，而不是最初蓝图中的 XCFramework 隔离产物。
- 当前 RN 固定在 Expo SDK 54/RN 0.81.4；Expo 官方说明 brownfield 支持仍为 alpha，最新 isolated AAR 能力不可假设与 SDK 54 无差异。
- 首先要验证的是生命周期、返回栈、token、离线 bundle、OTA 和覆盖升级；AAR 不应阻塞这些问题暴露。

后续只有在 P04 行为 gate 全绿后，才允许独立 POC `expo-brownfield` AAR。POC 不得同时升级 Expo/RN，不得改变线上入口；未证明等价就保持 integrated。

参考：

- https://docs.expo.dev/brownfield/overview/
- https://docs.expo.dev/brownfield/isolated-approach/
- https://docs.expo.dev/versions/latest/sdk/brownfield/

### ADR-002：Fragment 导航承载 Compose 与 RN

主 Activity 使用 `AppCompatActivity`/`FragmentActivity` 与 AndroidX Navigation Fragment：

```text
MainActivity
└── NavHostFragment / root Fragment containers
    ├── Native screen Fragment
    │   └── ComposeView → Compose UI
    └── RNSurfaceFragment
        └── ReactFragment(componentName, initialProps)
```

理由：

- ReactFragment 是 RN 官方支持的既有 Android App 集成方式。
- ComposeView 是 Android 官方支持的渐进式 View/Compose 互操作方式。
- FragmentManager 更容易统一 RN lifecycle、saved state、predictive back、嵌套返回栈与进程重建。

参考：

- https://reactnative.dev/docs/integration-with-android-fragment
- https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/compose-in-views

纯 Navigation Compose 不是永远禁止，但必须先用单独 ADR/POC 证明 ReactFragment 生命周期与返回栈同等可靠。

### ADR-003：单 React Runtime，多 Surface

- Application 只初始化一个 ReactHost/ReactNativeHost。
- 所有 RN 页面以已注册 componentName 创建 Surface/ReactFragment。
- Native 不为页面创建新 Runtime；Surface 销毁仅销毁 view/fragment。
- Debug 使用独立 Metro 端口 `8083`，避免与 RN App 默认 `8081` 和 iOS shell `8082` 冲突。
- QA/Release 必须离线加载 `index.surfaces.js` 产物，并可选择兼容 OTA。

### ADR-004：Kotlin/Compose 的最小分层

初始模块：

```text
:app                    Application、Activity、flavor、root navigation
:rn-host                React Runtime、Surface Fragment、bridge/capabilities
:core:common            Result、dispatcher、clock、generation 等纯 Kotlin
:core:model             跨 feature 模型
:core:network           API、auth modes、serialization、error policy
:core:auth              token/session/login/logout/migration
:core:storage           MMKV/preferences/cache scope/migrations
:core:i18n              locale catalog、客户端支持码、服务端可选集合、fallback
:core:navigation        typed route/deep link/native-vs-RN mapping
:core:analytics         event schema、Sentry/Qt context contracts
:core:designsystem      token、共享 Compose 组件、semantic IDs
:core:testing           fixtures、fakes、test clocks/dispatchers
:feature:login
:feature:home
:feature:screen
:feature:chatlist
:feature:profile
:feature:search
:feature:settings
```

P00 只建立必要骨架；feature 模块在对应 packet 开始时创建。避免空模块与过度 Clean Architecture。

依赖方向：

```text
app → feature / rn-host
feature → core contracts
rn-host → core contracts
core implementation 不依赖 feature
tipsy-app 不依赖 Android feature 实现，只依赖版本化 bridge API
```

### ADR-005：显式依赖容器先行

P00/P01 使用 `AppContainer` 显式组装 singleton：API、Auth、Storage、Router、Analytics、ReactHost。等边界稳定、测试证明有需要时，再单独评估 Hilt/Koin。不得把 DI 框架迁移与 brownfield 首次接入混在一起。

## 2. 技术基线

首轮保持从固定 SDK 54 生成工程验证出的版本：

- Gradle 8.14.3。
- AGP 8.11.0。
- Kotlin 2.1.20。
- compile/target SDK 36，min SDK 24。
- NDK 27.1.12297006。
- JDK 17。
- Hermes/New Architecture 保持开启。
- Compose 使用稳定 BOM 并在 version catalog 固定；审计日官方稳定示例为 `2026.06.00`。若实际解析失败，只能在 P00 更新记录并解释，不可用动态 `+` 版本。

网络：OkHttp + Retrofit + kotlinx.serialization。UI：Compose Material 3。异步：Coroutines + StateFlow。媒体：Media3 ExoPlayer + 有界 preload manager。图片：Coil。

## 3. 应用状态与单一所有权

| 能力 | Shell 模式 owner | RN Surface 权限 |
|---|---|---|
| 生命周期/启动 | Native | 订阅必要事件 |
| 登录、refresh、logout | Native | 读快照、请求 Native 动作；不得自行 refresh |
| HTTP | Native 页面走 Native client；RN 页面保留 Axios | 401/402 策略与 Native 对齐 |
| 语言 | Native | 初始 props + 事件同步 |
| 全局导航/deep link | Native Router | 请求 route，不直接操纵 Native back stack |
| Push token/通知入口 | Native | 仅处理 Surface 内目的地 |
| Analytics session/attribution | Native | 发送业务事件并附 Native context |
| Sentry | 双 Runtime | 同 release/environment/user，分别上传 symbols/source map |
| OTA bundle 选择 | Native | 只运行被选择的兼容 Surface entry |
| Widget/voice service | Native | 通过明确 contract 更新/启动，不依赖 App.tsx side effect |

## 4. 数据与并发规则

### Auth generation

每次 login/logout/account switch 都增加 generation。Repository 请求发起时捕获 generation，完成时必须匹配才可：

- 写 token/user。
- 写账号缓存。
- 更新 UI state。
- 发送归属于用户的埋点上下文。

refresh 使用 single-flight；并发 401 共享一个 refresh。logout 会取消/失效 refresh，旧 refresh 不能复活 session。

### API auth modes

- `REQUIRED`：必须 token；缺失直接 auth error；401 可走一次 single-flight refresh + retry。
- `OPPORTUNISTIC`：有 token 就带，无 token 也发送；401 行为按 endpoint 契约，不等价于 REQUIRED。
- `NONE`：永远不带用户 token，仅用于明确禁止身份的端点。

### Cache scope

所有用户/推荐相关 key 组合：

```text
environment + accountId/anonymousInstallationId + feature + filters + schemaVersion
```

迁移先兼容读旧 key，再写新 key；不得静默跨账号/环境复用。

### Serialization

真实 API 中 number/string/null 可能漂移。只在统一 serializer 层做 tolerant scalar；业务模型不能散落 `Any` 或 try/catch。每种已见变体必须保存脱敏 fixture。

## 5. i18n

固定 RN SHA 的三层事实不能混写：

- 磁盘有 28 个 locale JSON。
- `i18n-index.ts` 实际 import 27 个资源；`SUPPORTED_LANGUAGES`/`supportedLngs` 只有 26 个客户端支持码。`ar.json` 未 import，`zh.json` 虽 import 但 `zh` 不在 supported codes，现有 normalize 会把中文主语言归到 `zh-tw`。
- Settings 可选列表来自 `/supported_languages` 服务端结果，不等于磁盘文件数量或客户端支持码全集。

因此 Android：

- 生成脚本审计全部 28 个文件，但不得自动把 dormant 文件提升为产品可选语言。
- runtime 可选集合以“服务端列表 ∩ 当前批准的客户端支持码”为准；未知/无资源 code 安全 fallback 并上报非 PII 诊断。
- RN key 含空格/符号，不强行映射成 Android resource name；Native 提供 key-based `L10n` 与可观察 locale state。
- 语言码规范化、资源覆盖、fallback、复数/插值、切换后 Compose 与 RN Surface 同步需要 contract tests。
- 生成脚本改动属于 `tipsy-app` 独立 PR；生成物与源 SHA、支持码清单一起固定。

## 6. 页面迁移波次

```text
P00  Build/CI/Brownfield DebugSurface POC
 ↓
P01  Auth/Storage/Network/i18n/Router/Bridge/Observability + ChatDetailSurface POC
 ↓
P02  Native Login → Home read-only vertical slice
 ↓
P03-A Home production parity
P03-B Profile/Search/Settings；P03-C ChatList Grid/Map（feature 可并行，P03-I 串行集成）
 ↓
P04-A Screen/Media3
P04-B 12 business RN Surfaces + debug-only gate
P04-C Push/deep link/widget/voice/marketing system ownership
P04-DL local OTA contract；P04-DE authorized preview POC（阻塞发布，不阻塞 P05）
 ↓
P05  Full parity/performance/accessibility/nightly/chaos
 ↓
P06  Three-channel overlay upgrade/staged rollout/forward recovery
```

首个垂直切片必须穿过真实登录态、API、路由、Compose UI、埋点与测试；不接受先搭大量无产品路径的抽象层。

## 7. 性能预算的建立方式

P00/P02 先测旧 RN 商店包基线，再冻结数值预算。文档不虚构毫秒数。至少测：

- cold/warm start 到可交互。
- Native Home 首内容与 RN Surface 首帧。
- Screen 滚动 frame time/jank、播放器切换、预加载命中。
- 30/50 次 Surface 开关后的 PSS/Java/native heap 与 Runtime 数量。
- 后台恢复、旋转、进程重建。
- APK/AAB size 与每个 ABI 增量。

Native 方案不得显著劣于旧 RN 基线；若为稳定性换取已知性能差异，必须有测量、原因和批准。

## 8. 明确非目标

- 首轮不全量移除 RN/Expo/Node 构建链。
- 不迁 Create、Comments、EditProfile、Settings 子页、ChatDetail 深栈。
- 不同时升级 Expo/RN/AGP/Kotlin。
- 不在缺少真实签名/旧包时声称覆盖升级完成。
- 不把 isolated AAR、全 Compose Navigation、DI 框架或多模块数量当成功指标。
