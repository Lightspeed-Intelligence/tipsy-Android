# Android Native / RN Surface 边界契约

适用基线：`tipsy-app@cbd521f02972933c21f90c01787ea5c11200875e`。任何实现前先核对 SHA。

## 1. 当前事实

`tipsy-app/index.surfaces.js` 注册 13 个 component：

```text
DebugSurface
ChatDetailSurface
CommentsSurface
OnboardingSurface
CreateSurface
DeleteAccountSurface
EditProfileSurface
GemsSubscriptionSurface
NotificationSurface
RoleCardSurface
SettingsSurface
UserCoinsSurface
WidgetSurface
```

`tipsy-app/modules/tipsy-auth` 当前只声明 Apple module。Android 独立 RN App 中模块为 null，JS 自己拥有 auth；Android Native shell 要在 RN 独立 PR 增加 Android provider，并保证非 shell 构建仍 `isShellHost=false`。

当前 `TipsyAuth` 已混合 auth、导航、生命周期、推荐上下文等职责。P01 为兼容现有 JS 可以实现同名 API，但 Kotlin 内部必须拆分接口，禁止继续形成单个巨型类。

## 2. 运行时模型

```text
Android Application
├── AppContainer
│   ├── AuthHost
│   ├── AppRouter
│   ├── LocaleHost
│   ├── AnalyticsContextHost
│   └── SurfaceRuntimeManager（单例 ReactHost）
└── MainActivity / Fragment navigation
    ├── Compose Native Fragment
    └── RNSurfaceFragment(componentName, routeId, initialProps)
```

不变量：

- 一个进程一个 React Runtime。
- 每次打开 Surface 生成唯一 `surfaceInstanceId`；所有 ready/close/reappear 事件带此 ID，旧实例事件不可关闭新实例。
- `popSurface` 幂等；同一实例最多消费一次。
- Native back 先给当前 RN micro-stack；RN 到栈底才请求 Native pop。
- Surface 首帧 ready 前显示 Native 占位；ready 后单次淡出，不用固定延时猜测。
- Activity/Fragment 销毁后，事件 listener 与 view 引用释放；React Runtime 可以复用，但不得持有旧 Activity。

## 3. 微根必须显式拥有的依赖

完整 `src/App.tsx` 不会在 Surface 模式自动挂载。每个 Surface Root 必须通过共享 wrapper 明确提供：

- Gesture/SafeArea/Keyboard/Portal/Toast。
- i18n 初始化和语言事件。
- auth token/user store hydrate。
- SWR/cache/error boundary。
- tags、badge、avatar、remote config 等页面依赖配置。
- Sentry RN runtime 与 user/release/environment。
- Native navigation adapter。
- Surface first-frame、reappeared、background-ready、close 协议。

P01 必须建立 `SurfaceDependencyChecklist` 测试/文档。新增 Surface 未完成 checklist 不得注册。

## 4. 版本化契约

### 4.1 Capability handshake

Native 向 Surface initial props 提供：

```json
{
  "surfaceContractVersion": 1,
  "surfaceInstanceId": "uuid",
  "componentName": "ChatDetailSurface",
  "capabilities": [
    "auth.valid-token.v1",
    "navigation.open-chat.v1",
    "navigation.open-profile.v1",
    "lifecycle.reappeared.v1"
  ],
  "route": {},
  "context": {
    "languageCode": "en",
    "environment": "production",
    "distribution": "googlePlay"
  }
}
```

规则：

- 旧 bundle 不识别新增字段时必须忽略。
- 新 bundle 调用新增 native 方法前检查 capability 或 `?.()`。
- 方法/字段只能 additive；改语义、改必填、删除字段需要升 `surfaceContractVersion` 和 OTA runtime generation。
- Native 收到未知 route/field 必须可诊断地拒绝或忽略，不崩溃。
- token 不通过 initial props、日志或 analytics 透传；JS 按需调用 `getValidToken()`。

### 4.2 内部接口拆分

兼容层可以继续暴露 `TipsyAuth` 名字，但委托给：

```kotlin
interface SurfaceAuthContract
interface SurfaceNavigationContract
interface SurfaceLifecycleContract
interface SurfaceAnalyticsContextContract
interface SurfaceStorageMigrationContract
```

每个接口有纯 Kotlin contract test。Expo module 仅做参数校验、线程切换和委托，不包含业务实现。

## 5. Auth 契约

### Native owner 模式

- `isShellHost()` 返回 true 后，JS 不得读取/刷新/持久化 token。
- `getValidToken()` 由 Native single-flight refresh；临近过期阈值以 RN 固定 SHA 的实际实现为真，审计值为 5 分钟。
- `logout()`：使 auth generation 失效、取消/废弃 refresh、清 Native 与兼容共享态、收敛返回栈、发布一次 loggedOut。
- `notifyServerAuthRejectedForToken(token)`：仅当被拒 token 仍是当前 token 才登出；禁止回退到无 token 方法导致旧账号迟到 401 登出新账号。
- token 不写 log/Sentry breadcrumb/analytics。

### 历史 token 迁移

RN 当前读取顺序包括内存、MMKV `token-storage`、Expo SecureStore fallback，写入时仍可能双写。Android 默认 MMKV root 需以运行旧包实际路径验证，预期为 app files 下 `mmkv`、实例 `mmkv.default`。

迁移算法：

1. 在同 application id 的覆盖升级设备读取 MMKV `token-storage`。
2. 能解析则验证 JWT/refresh，写入 Native versioned token store，保留兼容读。
3. MMKV 缺失时只启动一次隐藏 `AuthBootstrapSurface`；它使用原 RN/Expo SecureStore JS 读取逻辑，将 token 通过内存 bridge 交给 Native。
4. Native 验证并持久化后写迁移标志；重复启动幂等。
5. 迁移失败不得清空旧值；回退到 logged-out UI，并记录不含 token 的 error code。

禁止在 Kotlin 中凭猜测重实现 Expo SecureStore 密文格式。

### 事件

保持现有事件并增加 instance/generation 字段时向后兼容：

- `onAuthStateChanged`
- `onUserStoreChanged`
- `onLanguageChanged`
- `onSurfaceReappeared`

事件到达晚于 Surface 销毁时必须丢弃。

## 6. Storage 契约

P01 必须从 RN 源码生成 registry，至少覆盖：

| Namespace/key | 语义 | 首轮策略 |
|---|---|---|
| `token-storage` | token | Native migrate + 兼容读 |
| `user-storage` | Zustand `{state, version}` user envelope | 解析信封，不当裸 user JSON |
| `auth-storage` | auth 状态 | 审计 schema 后 dual-read |
| `rating-storage` | content rating | 保留且按渠道策略解释 |
| `config-persist-storage` | config | 仅迁真正跨壳需要字段 |
| `chat-persist-storage` | chat 状态 | RN Surface owner，Native 不重写 |
| `guide-status-storage` | guide flags | 覆盖升级保留 |
| `subscribe-storage` | subscription UI/cache | 非支付真值，谨慎保留 |
| `chat-background-storage` | chat background | RN Surface owner |
| `android_widget_payload` | widget payload | P04 转 Native repository，兼容旧 key |
| `widget_character_id` | widget target | P04 保留 deep-link 连续性 |

Registry 每行还需记录 schema/version、账号作用域、环境作用域、是否加密、TTL、logout policy、rollback readability、owner 和 fixture。

## 7. 网络与错误契约

Native/RN 两套 client 必须一致携带：

- `token`（按 auth mode）。
- `Platform`。
- `X-App-Version`。
- `X-Download-Channel`。
- 防欺诈/安装标识，如当前端点要求的 `X-Client-ID`。

通用 envelope：`{ code, msg, data? }`。已知业务 code 不能被笼统转换成 IOException：

- `0` 成功。
- `6` gem 不足。
- `9` role card。
- `16` clover 类业务分支。
- HTTP 401 走 token-aware auth reject。
- HTTP 402 走带防抖的付费墙路由。

Native 页面逐个端点迁移；RN Surface 保持 Axios/SSE。两个 client 共享 API base URL 与环境，不允许一个请求 QA、一个请求 production。

## 8. Navigation 契约

Native Router 是唯一入口：

```text
Intent / Push / Widget / Compose click / RN bridge
                    ↓
             Typed AppRoute parser
                    ↓
      auth gate + dedupe + source attribution
                    ↓
          Native destination | RN Surface
```

当前必须支持的外部路径至少包括：

- `profile/daily-gem-entry`
- `profile/user-balance`
- `subscribe/page`
- `chat/detail`
- `chat/mini-phone`
- `chat/letter`
- `create/profile-detail`

scheme 至少审计 `tipsy://` 与 `tipsy.chat://` 的现有不一致。`app.config.js` 中泛用外部 schemes 不可原样照搬；P01 做 intent hijack 安全审计。

每条 route 测：cold/warm/background、logged-out queue、auth ready 后只执行一次、malformed input、相同 Intent dedupe、返回目标、source attribution。

## 9. Surface 验收矩阵

每个 Surface 在启用前复制一行并填证据：

| Surface | 初始 route fixture | 无登录 | 登录切换 | 语言切换 | Back/栈底 | 旋转/进程恢复 | 首帧 | 50 次泄漏 | Embedded | OTA N/N-1 |
|---|---|---|---|---|---|---|---|---|---|---|
| DebugSurface | 待 P00 | N/A | N/A | 待测 | 待测 | 待测 | 待测 | 待测 | 待测 | P04 |
| ChatDetailSurface | 待 P01 | 待测 | 待测 | 待测 | 待测 | 待测 | 待测 | 待测 | 待测 | P04 |
| CreateSurface | 待 P04 | 待测 | 待测 | 待测 | 待测 | 待测 | 待测 | 待测 | 待测 | 待测 |

其余 10 个 Surface 在 P04 展开；未填满的行不能标 production-ready。

## 10. Root side-effect ownership 清单

P01 必须逐项审计 `src/App.tsx` 及 root hooks，填写以下表；`UNKNOWN` 阻塞 cutover：

| 能力 | 原 RN symbol/path | Native owner | RN Surface 仍需 | 验证 |
|---|---|---|---|---|
| auth restore | `src/App.tsx`、auth hooks | P01 | hydrate only | 待填 |
| remote config / AB | root hooks、QT module | P04 | read context | 待填 |
| push | `useNotifications` | P04 | route target only | 待填 |
| deep link | RootNavigator/config | P01/P04 | request route | 待填 |
| widget sync | `useWidget` | P04 | transitional only | 待填 |
| Sentry | App root | P01 | RN runtime init | 待填 |
| usage/session/recommendation | root hooks | P01/P04 | business events | 待填 |
| AppsFlyer/Meta/TikTok/PostHog/QT | root effects | P04 | capability-specific | 待填 |
