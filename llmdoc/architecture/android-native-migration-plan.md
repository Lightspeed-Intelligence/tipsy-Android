# Android Native 迁移技术方案

> 状态：待评审 ｜ 日期：2026-08-08
> 审计基线：`Tipsy-Android@fe349c0`、`tipsy-app@93d2c5551`（submodule 当前 pin）、`Tipsy-iOS@4b42d8d`
> 本文是**决策级方案**。所有事实均在上述 SHA 的源码中核实过，标注了核实位置；未核实的推断显式标为「待验证」。

## 0. 三十秒速览

- **形态**：integrated brownfield。Android 建独立 Kotlin 原生壳，`tipsy-app` 作为 submodule 直接进 Gradle 构建，复用 Expo/RN autolinking 与 Hermes；RN 以 `index.surfaces.js` 的多个 Surface 微根形式被壳托管。**不做 isolated AAR**（首轮）。
- **与 iOS 的最大不同**：iOS 是「一个 bundle id、Keychain 存 token、桥模块已存在」；Android 是「**三个包名三条渠道**、token 在 MMKV + SharedPreferences/KeyStore、**`tipsy-auth` 桥没有 Android 实现**、**6 个 config plugin 的 prebuild patch 会全部失效**」。这四条决定了 Android 不能照抄 iOS 的阶段划分。
- **首轮迁移范围**：壳框架 + Auth/网络/i18n/Router/Surface 宿主 + Login + Home + ChatList + Profile + Search。**Screen（视频流）放在最后**；**Create / ChatDetail / Comments / EditProfile / Settings 子页明确不迁**（继承 iOS 已付学费的结论）。
- **最大风险**：不是像素对齐，是**覆盖升级掉登录**（三渠道各验一次）和**root side-effect 失踪**（App.tsx 不挂载导致营销 SDK / 推送 / 埋点静默不初始化，iOS 上真实发生过）。这两项必须在写页面之前解决。
- **OTA**：Android 已有 `preview`/`production` 两条服务完整 RN App 的 channel，壳**必须另开** channel + `runtimeVersion` + entry 三重隔离，否则完整 App bundle 会被下发到只注册了 Surface 的壳里。
- **工作量的真实分布**：要用 Kotlin/Compose 重写的业务代码约 **32.6k 行 RN**（Profile 12.6k / Home 7.1k / Screen 5.3k / ChatList 3.7k / Login 2.9k / Search 2.5k，逐页实测见 §8.0）；§2-§7 描述的全部基建约 3~5k 行 Kotlin。**§8 是主体，前面的章节是为了让 §8 能安全执行而存在的约束。**
- **一个降低预期成本的实测发现**：`tipsy-app` 里**已有 55 个文件**做好了壳适配（`isShellAuthHost()` 分支、13 个 Surface 入口、toast host、`useShellSurfaceRefocus`、跨栈出口…）。这些是 iOS 壳一年来沉淀的资产，**Android 只要提供一个 Kotlin 桥让 `isShellHost()` 返回 true 就全部自动生效**（§7.2）。Android 的 RN 侧工作量远小于 iOS 当年。

## 0.1 怎么读这份文档

如果你的时间只够读一部分：

| 你要做的事 | 读哪里 |
| --- | --- |
| 写业务页面（**最常见**） | **§8.1 对应页面的规格表** + §8.2 现成 fixture + §8.4 列表纪律 + §4.5 网络契约 |
| 搭工程 / 改 Gradle | §3.3 技术基线 + ADR-004 |
| 动 auth / 存储 | §2.1 + §2.4 + §4.4 + §4.6 |
| 打包 / 发渠道 / 发 OTA | §2.2 + §2.3 + §5 + §6 |
| 判断某功能迁不迁 | §1.3 归属表 |
| 想知道现在做到哪了 | **不在本文**——看 `reference/android-native-progress.md` |

---

## 1. 为什么做，以及迁移什么

### 1.1 收益来源（对齐 iOS 的实测结论）

iOS 的原生化收益集中在三处，Android 同源问题更重：

| 场景 | RN 现状 | Android 原生收益 | 程度 |
| --- | --- | --- | --- |
| 冷启动 / TTI | 25.2 万行 TS/TSX（1030 文件，实测）打进单个 JS bundle，启动即解析 | 原生启动不付 RN 成本 | ⭐⭐⭐ |
| Screen 全屏视频流 | `react-native-video` + JS 播放器池；Android 已有线上 OOM 崩溃（`withAndroidLargeHeap.js` 注释记录 `ExoPlayerImplInternal.shouldContinueLoading` OOM） | Media3 ExoPlayer + 有界 preload manager + 原生内存管理 | ⭐⭐⭐ |
| Home 双列信息流 | FlatList + 自定义 PanResponder 下拉刷新 | RecyclerView/LazyGrid 预排版 + 原生刷新 | ⭐⭐ |
| 低端机 / 长列表 jank | JS 线程与 UI 线程跨桥 | 无跨桥 | ⭐⭐⭐（Android 设备分布比 iOS 差，收益高于 iOS） |

**原生化抬不动的**：富文本聊天（WebView 本质）、SimulatorGame（WebView）、AI 生成延迟（服务端）。

### 1.2 RN 现状基线（实测于 `tipsy-app@93d2c5551`）

| 维度 | 实测值 | 来源 |
| --- | --- | --- |
| 代码规模 | 1030 个 TS/TSX，252,596 行 | `find src -name '*.ts*' \| xargs wc -l` |
| 聊天域规模 | `src/app/chat/` 38 个文件、31,892 行 | 同上 |
| Home / Screen | `home.tsx` 2339 行、`screen.tsx` 1492 行 | 同上 |
| Android 平台分支 | `Platform.OS === 'android'` 84 处、`isAndroid` 43 处 | grep |
| 五 Tab | Screen / Home / Create / ChatList / Profile；Create 是伪 Tab | `src/navigation/TabNavigator.tsx:383-464` |
| 深链路由 | 7 条：`profile/daily-gem-entry`、`profile/user-balance`、`subscribe/page`、`chat/detail`、`chat/mini-phone`、`chat/letter`、`create/profile-detail` | `src/App.tsx:445-465` |
| 深链 scheme | `expo-linking` 生成的 app scheme + `tipsy.chat://` | `src/App.tsx:429` |
| Surface 注册 | 13 个 component（含 `DebugSurface`） | `index.surfaces.js` 尾部 |
| 技术栈固定值 | Expo 54.0.27、RN 0.81.4、React 19.1.0 | `node_modules/*/package.json` 实测 |

### 1.2.1 iOS 迁移复盘：十条经验与十条反模式

iOS 两个月迁移的复盘结论。**这是唯一无法从代码重新推导的资产**（其余事实都能回源码核实），逐条给出 Android 的落点。

**十条经验**：

| # | 经验 | Android 落点 |
| --- | --- | --- |
| 1 | **按业务收益迁移，不以 Native 覆盖率为 KPI** | §1.3 归属表：Create/ChatDetail/Comments 等永久留 RN，**不追求「全原生」** |
| 2 | **应用级能力只能有一个 owner**（token refresh / logout / push / deep link / 语言 / analytics session 不得双写） | §4.1 所有权表 |
| 3 | **先冻结边界契约再迁页面**；Auth / Navigation / Lifecycle / Analytics / Storage **分开版本化**，别全塞进 Auth 模块 | §4.3 内部接口拆 5 个 contract（`TipsyAuth` 已是巨型类的反面教材） |
| 4 | **每个 RN Surface 是微型 App Root** | §4.3 微根依赖清单 + `SurfaceDependencyChecklist` |
| 5 | **OTA runtime 表示 bridge ABI**；新方法 additive + capability gate，破坏性变化才升代 | §5.3 `android-bridge-N` |
| 6 | **覆盖安装与历史数据迁移是 Phase 0/1 的事** | §2.4 + W1 就验三 flavor 真机覆盖升级 |
| 7 | **所有异步回写带 generation** | §4.4 auth + mutation 双轨 |
| 8 | **API 真值来自真实响应 fixture**，TS 类型覆盖不了 number/string/null 漂移 | §4.5 tolerant scalar + §8.2 现成测试 |
| 9 | **路由集中管理并防重入** | §4.7 Router 唯一入口 |
| 10 | **监控与 accessibility 在第一天建立** | §9.4（iOS 到后期才批量补约 295 个 a11y ID） |

**十条反模式（Android 不要复制）**：

1. 在高变动页面做逐页 Native 复制，再长期维护双实现。
2. 假设移除 RN App Root 只影响 UI，**遗漏 root effects / SDK 初始化**（§4.2）。
3. 为每个 Surface 创建 React Runtime。
4. **把 optional auth 错做 no-auth**（§4.5 三种鉴权模式）。
5. token / 缓存不带账号与环境作用域（§4.6 cache scope）。
6. 对长列表或播放器集合做全量替换，导致滚动、曝光、播放状态重置（§8.4）。
7. 只测完整 RN App，不测 Surface 微根、返回栈、旋转、进程恢复和**离线 release bundle**。
8. **在 active source set 保留无标识的 dormant 页面**，让人和 AI 误判线上路由。
   > iOS 现在就有这个问题：`Pages/Comments/`（7 文件）与 Profile 的原生 EditProfile 簇是零引用的休眠备份，靠文档注释才知道"评论现在走 RN"。**Android 若要保留回滚备份，必须在代码里显式标记或直接删除靠 git 历史找回**，不要留无标识的死代码。
9. 页面完成后才补 lint、contract tests、test IDs、QA flavor 和 release drill。
10. **用人工维护的多份「当前进度」文档替代 Git SHA 与唯一 progress**（iOS 的进度文档曾在同一份里记过不同的 submodule pin）。

### 1.3 迁移与不迁移（继承 iOS 已付学费的结论）

iOS 有三个页面**先原生化、后又整体迁回 RN**（Comments `c861b72`→`9fb4ba5`、EditProfile `fae61b4`→`80fc2f6`、Settings 子页 `47988ea` 删掉约 2.7k 行 Swift）。原因一致：高频运营改动区，原生化失去 OTA，且双份实现漂移。Android 直接继承结论，不重复交学费。

| 域 | Android 归属 | 波次 | 理由 |
| --- | --- | --- | --- |
| 生命周期 / 五 Tab / Router | Native | W1 | 应用级能力单一所有者 |
| Auth / HTTP / i18n / 监控 | Native | W1 | 同上；且覆盖升级必须先解决 |
| Home | Native | W2 | 第一个垂直切片 |
| ChatList / Profile / Search | Native | W3 | 页面主体，工作量最大 |
| Screen（视频流） | Native | W4 | 收益最高但风险最高（Media3 + OOM），放最后 |
| **Create** | **RN Surface** | — | iOS 已决策放弃原生化（保 OTA），Android 不再评估 |
| **ChatDetail 全栈（38 页 / 3.2 万行）** | **RN Surface** | — | 耦合最深、OTA 收益最高 |
| **Comments / EditProfile / Settings 子页** | **RN Surface** | — | iOS 迁移后回撤，直接继承 |
| Notification / RoleCard / Onboarding / Coins / Gems | RN Surface | W4 | 首轮不迁 |
| Settings 列表本体 + 语言页 | Native | W3 | 壳是语言唯一写入者（iOS 同边界） |
| Push / Analytics / Attribution / Sentry | Native owner + RN runtime 接入 | W1/W4 | 见 §4 |

---

## 2. Android 的四条硬约束（照抄 iOS 会静默出错）

这一节是本方案存在的主要理由。**做任何 auth / 打包 / 发布决策前先读这四条。**

### 2.1 `tipsy-auth` 桥只有 apple 实现 —— Android 必须新写

已核实 `tipsy-app/modules/tipsy-auth/`：

```json
{ "platforms": ["apple"], "apple": { "modules": ["TipsyAuthModule"] } }
```

目录下只有 `ios/TipsyAuthModule.swift`，**没有 android/**。而 JS 侧：

```ts
// modules/tipsy-auth/src/index.ts
const TipsyAuth = requireOptionalNativeModule<TipsyAuthModule>('TipsyAuth')
export function isShellAuthHost(): boolean { ... TipsyAuth?.isShellHost() ?? false }
```

**后果**：Android 上 `TipsyAuth` 恒为 `null`，`isShellAuthHost()` 恒 `false`。这意味着**在写出 Kotlin 实现并改 `expo-module.config.json` 之前，所有 Surface 在 Android 上都会走「JS 自己拥有 auth」的路径**——JS 会自己读 MMKV、自己刷新 token，与壳的原生 auth 双写互踢。

**必做**（RN 侧独立 PR，见 §7）：
1. `expo-module.config.json` 增 `"android"` 段与 module 类名，platforms 加 `"android"`。
2. 新写 Kotlin module，实现 TS 声明中的全部**非可选**方法（`isShellHost` / `getCurrentLanguageCode` / `getValidToken` / `requestLogin` / `logout` / `clearToken` / `notifyOnboardingCompleted` / `popSurface` / `openUserProfile` / `notifyServerAuthRejected` / `notifyServerPaymentRequired` / `openGemsPurchase`）；可选方法（`?.()` 声明的约 15 个）按 Surface 启用进度增量补。
3. **非壳路径必须字节级等价**：独立 RN App（三渠道现网包）里该 module 会被 autolink 进去，必须保证 `isShellHost()` 在没有壳 provider 注册时返回 `false`——即 provider 注册由壳的 Application 完成，module 本身只是空壳委托。iOS 用的就是 provider + Registry 模式，Android 照此办理。

> 内部设计约束：TS 侧的 `TipsyAuth` 已经把 auth / 导航 / 生命周期 / 推荐上下文混在一个 module 里。Kotlin 侧为兼容必须保留同名 API，但内部**必须拆成多个接口**（`SurfaceAuthContract` / `SurfaceNavigationContract` / `SurfaceLifecycleContract` / …），Expo module 只做参数校验 + 线程切换 + 委托，不写业务。否则会长成一个不可测的巨型类。

### 2.2 三个包名、三条渠道 —— 覆盖升级要验三次

已核实 `tipsy-app/src/constants/app.js` 与 `app.config.js:132-137`：

| 渠道 | applicationId | 选择条件 |
| --- | --- | --- |
| Google Play | `com.tipsyturbo.app` | `APP_BUILD_TYPE` 未设置 |
| Direct APK | `ai.lightspeed.tipsy` | `APP_BUILD_TYPE=apk` |
| RuStore | `com.tipsytavern.app` | `APP_BUILD_TYPE=rustore` |

`conf/google-services.prod.json` 实测含 4 个 android client：上面三个 + `com.tipsytavern_ai.app`（**不为它建第四个 flavor**）。

**与 iOS 的关键差异**：iOS 只需验证一次「同 bundle id 顶替升级」。Android 是**三份独立的 applicationId + 三份签名 + 三个商店/分发渠道**，`com.tipsyturbo.app` 的覆盖升级结果**不能外推**到 APK 与 RuStore。

**后果**：§6 的覆盖升级矩阵必须逐渠道执行，共 3×N 次；`adb install -r` 用 debug 签名重装**不算**覆盖升级证据（签名不同，数据目录不继承）。

### 2.3 6 个 config plugin 在 brownfield 不跑 —— patch 必须手工移植

已核实 `tipsy-app/plugins/` 6 个文件。它们通过 `expo prebuild` 去 patch 生成的 `android/`；壳是**手写原生工程、不跑 prebuild**，这些 patch 一个都不会生效。逐项列出必须手工承接的内容（源码核实）：

| 插件 | 对 Android 做了什么 | 壳侧必须怎么做 |
| --- | --- | --- |
| `withAndroidLargeHeap.js` | `<application android:largeHeap="true">` | 壳 `AndroidManifest.xml` 手写。**注释明确这是线上 ExoPlayer OOM 的缓解措施**，漏掉会在 Screen 波次炸 OOM |
| `withAndroidStyles.js` | 全局 `AppTheme` 加 `android:textViewStyle=@style/Widget.App.TextView`（`useBoundsForWidth=false`） | 壳 `styles.xml` 手写。影响所有 RN Surface 内 Text 的排版宽度，漏掉是**静默的文字截断/换行差异** |
| `withVoiceCallSystemSession.js` | 4 个权限（`FOREGROUND_SERVICE`/`FOREGROUND_SERVICE_MICROPHONE`/`POST_NOTIFICATIONS`/`WAKE_LOCK`）+ `VoiceCallForegroundService`（`foregroundServiceType=microphone`, 非 exported） | 手写进壳 manifest。注意 service 类名硬编码为 `ai.lightspeed.tipsy.voicecallsystemsession.VoiceCallForegroundService`（**与 applicationId 无关，是模块自身包名**，三 flavor 通用） |
| `withRemoveAgoraMediaProjection.js` | 全局把 `io.agora.rtc:full-sdk` 替换为 `voice-sdk`、排除 `full-screen-sharing`、移除 `FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限 | 壳 Gradle 写 `resolutionStrategy.dependencySubstitution` + manifest `tools:node="remove"`。漏掉会引入屏幕录制权限（**商店审核风险**）并增大包体 |
| `withRuStoreBilling.js`（427 行） | RuStore Maven 仓库、`ru.rustore.sdk-wrapper.react-native:pay`、**字符串改写 `MainApplication` 注册 `RuStoreReactPayPackage`**、**字符串改写 `MainActivity` 处理 RuStore Intent**、`PayActivity` + scheme + metadata + `CONSOLE_APPLICATION_ID` string | **不复制字符串改写**。用 `ruStore` flavor 的 source set 提供自己的 Application/Activity 子类 + flavor 专属 manifest/依赖 |
| `withFmtConstevalFix.js` | 仅 iOS Podfile | 无需移植 |

**一个重要事实**（已核实 `app.config.js` 的 plugins 列表与 `withRuStoreBilling.js`）：`app.config.js` **无条件**启用 RuStore 插件与 `react-native-iap`，`IS_RUSTORE` 只切包名、不隔离依赖。所以现网三个渠道的包**都同时携带 Google Billing 与 RuStore Pay 能力**。壳工程不要复制这个状态——**用 flavor 隔离**，并把「现网是混装」记为已知差异，避免被误判成回归。

### 2.4 token 存储：MMKV 可直读，SecureStore 不可猜

已核实两处实现：

**MMKV（可直读）** —— `node_modules/react-native-mmkv/android/.../HybridMMKVPlatformContext.kt`：

```kotlin
override fun getBaseDirectory(): String =
  context.filesDir.absolutePath + "/mmkv"
```

配合 `src/store/mmkv.ts` 的 `createMMKV()`（默认实例）。壳可用同版本 MMKV 直读同一目录。**信封规则**（`src/store/auth.ts` 核实）：
- `token-storage`：**裸字符串** token（`storage.set(TOKEN_STORAGE_KEY, token)`）。注意 `parseLegacyPersistedToken` 说明历史上可能是 `{state:{token}}` 或 `{token}` 形状——**读取必须兼容三种形态**。
- `user-storage` / `auth-storage` 等：Zustand persist 信封 `{state, version}`。**原生写入必须 merge，不得整体覆盖破坏信封**。

**expo-secure-store（不可猜）** —— `node_modules/expo-secure-store/android/.../SecureStoreModule.kt` 实测使用 `SharedPreferences` + `AndroidKeyStore`，值是**加密后的 JSON**（`readJSONEncodedItem`），带 keychainService、`AESEncryptor`/`HybridAESEncryptor` 两套 encryptor、legacy key entry 兼容、以及 keystore 与 prefs 失步时的清除逻辑。

**结论**：`src/store/auth.ts` 的读链是「内存 → MMKV `token-storage` → SecureStore fallback」，写入是**MMKV + SecureStore 双写**。所以：
- 绝大多数升级用户的 token 在 MMKV，壳可直读 → 这是主路径。
- **但不要在 Kotlin 里重新实现 SecureStore 的密文格式**。MMKV 缺 token 时，用一次性隐藏的 **`AuthBootstrapSurface`**（RN 侧新增，走原生 SecureStore module 的 JS API 读出）把 token 交给壳，壳验证后写自己的 store 并落迁移标志。
- 迁移失败**不得清空旧值**，回退到未登录 UI 并上报不含 token 的 error code。

> 与 iOS 的对照：iOS 的四级读链是「内存 → 壳 Keychain → 共享 MMKV → legacy expo-secure-store」，第四级 iOS 能直读是因为 Keychain 的 access group 语义简单。Android 第四级读不了，所以必须多一个 Bootstrap Surface。这是 Android 独有的一步。

---

## 3. 总体架构

### 3.1 运行时结构

```
┌──────────────────────────────────────────────────────────────┐
│  TipsyApplication（Kotlin）                                   │
│    ReactApplication 实现（单 ReactHost）                       │
│    AppContainer：显式装配 singleton                            │
│      AuthHost / ApiClient / Router / LocaleHost               │
│      AnalyticsHost / SurfaceRuntimeManager                    │
│    TipsyAuth provider 注册（早于任何 Surface JS 求值）          │
│    expo-updates 自管初始化                                     │
├──────────────────────────────────────────────────────────────┤
│  MainActivity（AppCompatActivity + FragmentManager）           │
│    原生底部 TabBar（5 Tab；Create 为伪 Tab 拦截）                │
│    ├─ Native Fragment → ComposeView                          │
│    │    Home / ChatList / Profile / Search / Screen / Login   │
│    └─ RNSurfaceFragment(componentName, instanceId, props)     │
│         ChatDetail / Create / Comments / Onboarding /         │
│         Settings 子页 / Notification / RoleCard / Coins / Gems │
│         EditProfile / DeleteAccount / Widget                  │
└──────────────────────────────────────────────────────────────┘

tipsy-app（git submodule，固定 pin）
  ├─ index.surfaces.js        壳的 JS 入口（13 个 AppRegistry 组件）
  ├─ index.surfaces.debug.js  零业务依赖自检入口（管线二分用）
  ├─ index.tsx                完整 App 入口（三渠道现网包仍用，壳不用）
  └─ modules/tipsy-auth/      桥模块（需新增 android 实现）
```

### 3.2 ADR

#### ADR-001：integrated brownfield 先行，不做 isolated AAR

`tipsy-app` 作为 submodule 参与 Gradle 构建，复用 Expo/RN autolinking。

**决定性证据**：iOS 蓝图当年写的是 isolated（XCFramework + SPM + `Packages/` + `Contracts/`），**一年后仍是 integrated**——`Tipsy-iOS` 至今没有这三个目录，而 integrated 形态已支撑 3.2 万行 Swift 与真实发版。另：`expo-brownfield` 未安装在当前依赖里（实测），SDK 54 的官方 brownfield 支持仍是 alpha。

**后置**：W4 全绿后允许独立 POC。POC 不得同时升级 Expo/RN，不得改线上入口。

#### ADR-002：Fragment 承载 Compose 与 RN

`MainActivity` 用 `AppCompatActivity` + FragmentManager；原生页 = Fragment 内挂 `ComposeView`，RN 页 = `ReactFragment` 子类。

**已核实**：`ReactFragment`（`node_modules/react-native/ReactAndroid/src/main/java/com/facebook/react/ReactFragment.kt`）已适配 bridgeless，且 `reactHost` 默认从 `activity.application as ReactApplication` 取——Application 实现 `ReactApplication` 即可复用官方生命周期转发，不必自己实现 `onHostResume/Pause/Destroy`。FragmentManager 同时解决返回栈、saved state、predictive back、进程重建。

纯 Navigation Compose 需单独 ADR + POC，首轮不冒险。

#### ADR-003：单 React Runtime，多 Surface

- 只建一个 ReactHost。**不为每个页面建 Runtime**（iOS 复盘点名的反模式）。
- 每次打开 Surface 生成唯一 `surfaceInstanceId`，ready/close/reappear 事件都带此 ID，**旧实例的迟到事件不得关闭新实例**。iOS 的 `popSurface` 闸是**类型判定**，迟到事件会弹错同类型页（后来用 `closingRef` 兜住）。Android 从第一天用 instanceId。
- `popSurface` 幂等，同一实例最多消费一次。
- Debug Metro 端口 **8083**（8081 归 tipsy-app、8082 归 iOS 壳）。

#### ADR-004：Gradle 布局与 autolinking 落地方式（本方案的关键工程决策）

Expo SDK 54 的 Android autolinking 假设「Gradle root 就是 `<rn-project>/android`」。壳的布局是「Gradle root = 仓库根，RN 在 `./tipsy-app`」，需要处理两处解析：

1. **`expoAutolinking.projectRoot` 是公开可覆盖的**。已核实 `ExpoAutolinkingSettingsExtension.kt`：

```kotlin
/**
 * The root directory of the react native project.
 * Should be used by projects that don't follow the /android folder structure.
 * Defaults to `settings.rootDir`.
 */
var projectRoot: File = settings.rootDir
```

   即官方**明确支持**非 `/android` 布局。`settings.gradle` 里设 `expoAutolinking.projectRoot = file("tipsy-app")` 再 `useExpoModules()` 即可，配套 `reactNativeGradlePlugin` / `reactNative` 两个 lazy 属性也都以 `projectRoot` 为 workingDir 解析。

2. **`ExpoGradleHelperExtension.getReactNativeDir` 硬编码 `project.rootDir`，且无 override**。已核实 `expo-modules-core/expo-module-gradle-plugin/.../ExpoGradleHelperExtension.kt`：

```kotlin
reactNativeDir = reactNativeDirFromSource ?: File(
  project.providers.exec { env ->
    env.workingDir(project.rootDir)          // ← 硬编码 Gradle root
    env.commandLine("node", "--print", "require.resolve('react-native/package.json')")
  }.standardOutput.asText.get().trim()
).parentFile
```

   **解法：在仓库根做 `node_modules` 符号链接指向 `tipsy-app/node_modules`**，让从仓库根跑的 `require.resolve('react-native/package.json')` 自然命中。这正是 iOS 壳在用的方案（实测 `Tipsy-iOS/node_modules -> tipsy-app/node_modules`，且 `.eas/build/*.yml` 三个 profile 都有 `ln -sf tipsy-app/node_modules node_modules` 步骤）。

> **W0 实测结论（2026-08-08）**：上面两个支点**均已验证成立** —— 51 个 project 正确 autolink，
> 三个 flavor 的 debug 包构建通过，**未使用任何反射**。但实测另外发现**第三类问题**：
> RN/Expo 生态里有至少 5 处按「Gradle root = `<rn-project>/android`」推导路径，
> 且推导方式各不相同（`rootProject.projectDir`、其 `.parentFile`、向上遍历找
> `node_modules`…）。**症状统一是 `Process 'command 'node'' finished with non-zero
> exit value 1`，真实 stderr 被 Gradle 吞掉**，逐个排查代价很高。
> 完整清单与处理方式见进度文档 §2.2.2 —— 新增依赖后若再撞同类报错，
> **先在报错任务的 workingDir 手工复现那条 node 命令拿到真实 stderr**，再决定怎么改。
>
> 另有一项已知限制：`expoAutolinking.exclude` 对经 `useExpoModules()` 链接的模块无效
> （`AutolinkingCommandBuilder` 把多值 `--exclude` 与 `--project-root` 拼进同一 argv，
> variadic 参数吞掉后续 flag）。**W0 的严格隔离因此改用「禁用相关任务」实现**，
> 不能依赖 exclude 列表。

> **不采用反射 hack** 去改写 `ExpoGradleHelperExtension` 的私有 `lateinit` 字段缓存：依赖私有字段名、Kotlin backing field、Gradle decorated subclass 与回调时序——脆、且不承诺 configuration cache 兼容。符号链接 + 公开的 `projectRoot` 覆盖是两个稳定支点，优先用它们。若实测仍有解析缺口，先补最小 shim（如非递归的 `tipsy-app/android` 占位软链，对齐 iOS 的 `ios/` shim 做法），再考虑反射，且必须写下删除条件。

3. **Node 可执行文件必须显式解析（已实测，W0 必做；落地方式于 2026-08-10 订正，见本条末）**。上面两个支点都靠 `providers.exec` 跑 `node --print require.resolve(...)`，**Node 因此是本仓库真实的构建输入**（RN Gradle plugin、Expo autolinking、Hermes bundle 全部 shell out 到它）。而 Expo/RN 的调用点都是裸 `"node"`、依赖 `$PATH`：

   - fnm / nvm / asdf **只在交互式 shell 注入 PATH**。从 Finder/Dock 启动的 Android Studio、launchd、多数 CI runner 都读不到 → Gradle sync 报 `A problem occurred starting process 'command 'node''`，且报错无任何上下文。
   - **Expo 的 settings 插件硬编码裸 `"node"` 且无覆盖入口**（`ExpoAutolinkingSettingsPlugin`、`ExpoAutolinkingSettingsExtension`、`AutolinkingCommandBuilder`），又位于禁止修改的 `tipsy-app/node_modules` → 光靠显式传参覆盖不了全部调用点。

   **落地方式**——分两层，**缺任何一层 GUI 启动的 sync 都会失败**：

   **第一层：本仓自己的调用点用显式路径**（`settings.gradle` 已实现）
   - 按优先级解析：`TIPSY_NODE_EXECUTABLE` 环境变量（CI）→ `local.properties` 的 `tipsy.node.executable`（开发机，不跟踪）→ 同名 Gradle property（团队默认）→ 回退裸 `"node"` 走 PATH（保持原行为）。
   - 解析结果校验绝对路径 + 可执行位，并在 settings 阶段先跑一次 `node --version` 验证，失败时给出**带解析来源**的可操作报错。
   - 同时设置 `react.nodeExecutableAndArgs`（默认也是裸 `"node"`，影响 Hermes bundle 与 codegen）。
   - `pluginManagement` 必须是 settings 脚本的第一条语句，所以这段逻辑**不能挪进 applied script 或顶层方法**，只能内联后经 `gradle.extraProperties` republish 给后续构建使用。
   - 填 `local.properties` 时**不要用 `which node` 的输出**：fnm 给的是 `~/.local/state/fnm_multishells/<pid>_<ts>/bin/node`，带 PID 与时间戳，换 shell 即失效。用 `~/.local/share/fnm/aliases/default/bin/node`（nvm 对应 `~/.nvm/versions/node/<v>/bin/node`，升级 node 后需改）。

   **第二层：node_modules 内的裸 `"node"` 调用点只能靠 PATH**
   - `ExpoAutolinkingSettingsPlugin.getExpoGradlePluginsFile` 用裸 `"node"` 跑 `providers.exec`（SDK 54 实测），位于禁止修改的 `tipsy-app/node_modules`、无覆盖入口。第一层的显式路径**覆盖不到它**。
   - 开发机：让 **launchd GUI 域**的 PATH 含 node 目录——Finder/Dock 启动的应用只继承 GUI 域环境，不读 `~/.zshrc`。手工 `launchctl setenv PATH` 重启即丢，须固化成 `RunAtLoad` 的 LaunchAgent。**改完必须完全退出并重启 Android Studio**：进程环境在启动时快照，已在跑的实例改不动。
   - CI / 命令行：正常的 `$PATH` 即可（shell 环境本来就有 node），无需额外处理。

   > **订正（2026-08-10 实测）**：本条原写「把解析出的 node 目录前置到 daemon PATH 即可兜住裸 `"node"` 的调用点」，**该做法无效**。用 `ProcessEnvironment.maybeSetEnvironmentVariable('PATH', ...)` patch 后，子进程确实继承了新 PATH（`sh -c 'command -v node'` 能找到），**但 Gradle 解析 program name 时不看它** —— 同一次构建内 `providers.exec { commandLine('node','--version') }` 仍抛 `Cannot run program "node": error=2`。`settings.gradle` 里那段代码还包在 `catch (Throwable ignored)` 中，失败时完全静默，因此长期未暴露。真正的修法是上面第二层。原结论中「必须早于首次 `providers.exec`」的时序约束也随之失去意义。
   >
   > **验证方式**（复现 GUI 环境，比开 Studio 快）：
   > `env -i HOME=$HOME USER=$USER PATH="$(launchctl getenv PATH)" JAVA_HOME=<studio-jbr> ./gradlew --no-daemon projects`
   > 要复现「GUI 域也没有 node」的最坏情形，把 `PATH` 换成 `/usr/bin:/bin:/usr/sbin:/sbin`。
   >
   > **那段无效代码已删除（2026-08-10）**，换成 `settings.gradle` 里的**前置检查**：
   > 若当前进程 `$PATH` 里找不到可执行的 `node`，**在 1 秒内失败**并给出可操作步骤
   > （launchctl 命令带展开后的完整值 + `--stop` + **完全退出 Studio**）。
   >
   > 为什么改成「提前失败」而不是继续找兜底：裸 `node` 的调用点在
   > `tipsy-app/node_modules`（禁止修改）且无覆盖入口，**壳侧没有任何合法手段能修好它**。
   > 留一段静默失败的 hack 只会让人误以为有兜底，从而看不懂为什么还报错 ——
   > 失败点会漂移到某个 plugin 内部，报出无上下文的 `Cannot run program "node"`
   > （本次就是这样重现的：Studio 进程 PATH 是 `/usr/bin:/bin:/usr/sbin:/sbin`）。
   >
   > ⚠️ **最容易忽略的一点**：`launchctl setenv` 只影响**此后新启动**的进程。
   > 已在跑的 Studio 继承的是**它自己启动那一刻**的环境 —— 所以 Sync、
   > Invalidate Caches、乃至 `./gradlew --stop` 都不够，**必须 ⌘Q 退出 Studio 再打开**。
   > 判断依据：`ps eww <studio-pid> | tr ' ' '\n' | grep ^PATH=` 看进程**实际**的 PATH，
   > 而不是看 `launchctl getenv PATH`（后者是「新进程会拿到什么」，两者可能不一致）。

**其余 Gradle 决定**：
- **Groovy DSL**，全仓不混 `.gradle.kts`。Expo SDK 54 / RN 0.81.4 的 settings/root/app 模板与文档都以 Groovy 为基线，`autolinking_implementation.gradle` 本身就是 Groovy 脚本。**当前脚手架是 `.kts`，需要改写**。
- 版本全部由 `gradle/libs.versions.toml` 固定，**禁止 `+`**。
- `react { root = file("tipsy-app"); entryFile = ...; bundleAssetName = ...; autolinkLibrariesWithApp() }`——已核实 `@react-native/gradle-plugin` 的 `ReactExtension.kt` 提供 `root`、`entryFile`、`bundleAssetName`、`cliFile`、`autolinkLibrariesWithApp()` 全部所需入口。

#### ADR-005：显式 AppContainer，首轮不引 DI 框架

W1/W2 用手写 `AppContainer` 装配 singleton。边界稳定、且有测试证明需要之后再单独评估 Hilt。**不把 DI 框架引入与 brownfield 首次接入混在一起**——两个都会失败时无法二分。

### 3.3 技术基线（不是选型，是 SDK 54 的兼容事实）

已核实 `tipsy-app/node_modules/react-native/gradle/libs.versions.toml`：

| 组件 | 固定值 | 当前脚手架 | 动作 |
| --- | --- | --- | --- |
| **Gradle wrapper** | **8.14.3** | 9.4.1 | **必须降**（AGP 8.11 不支持 Gradle 9；W0 实测） |
| **AGP** | **8.11.0** | **9.2.1** | **必须降** |
| **Kotlin** | **2.1.20** | **2.2.10** | **必须降** |
| **compileSdk / targetSdk / minSdk** | **36 / 36 / 24** | **37 / 36 / 24** | **compileSdk 必须降到 36** |
| Build Tools | 36.0.0 | — | 对齐 |
| NDK | 27.1.12077973/27.1.12297006 | — | 对齐 27.1.12297006 |
| JDK | 17 | — | 对齐 |
| Hermes / 新架构 | 开启 | — | 保持 |
| Release ABI | `armeabi-v7a,arm64-v8a` | — | 对齐（`app.config.js` 的 `buildArchs`） |

> **这是 W0 的第一件事**。当前 `gradle/libs.versions.toml` 的 AGP 9.2.1 / Kotlin 2.2.10 / compileSdk 37 是 Android Studio 模板默认值，与 RN 0.81.4 要求的原生编译基线不兼容，且 `modules/widget/android/build.gradle` 会读 `rootProject.ext.kotlinVersion` 去解析 Compose 编译器插件——版本不一致会在 autolinking 阶段炸。Compose BOM 已实测定为 **`2025.04.01`**（模板默认的 `2026.02.01` 与 Kotlin 2.1.20 不匹配）。

**其余选型**：网络直接使用 OkHttp + 显式 envelope/标量容错层（W1 决策：不引 Retrofit，见进度 §2.14）；UI Compose Material 3；异步 Coroutines + StateFlow；媒体 Media3 ExoPlayer + 有界 preload manager；图片 Coil。

### 3.4 模块划分

```
:app                 Application / MainActivity / flavor / 根导航
:rn-host             ReactHost、RNSurfaceFragment、bridge 实现、capability
:core:common         Result / dispatcher / clock / generation
:core:model          跨 feature 模型
:core:network        API client / auth mode / 序列化 / 错误策略
:core:auth           token / session / login / logout / 迁移
:core:storage        MMKV / preferences / cache scope / 迁移
:core:i18n           locale catalog / 支持码 / fallback
:core:navigation     typed route / deep link / native-vs-RN 映射
:core:analytics      事件 schema / Sentry & Qt context
:core:designsystem   token / 共享 Compose 组件 / semantic ID
:core:testing        fixtures / fakes / test clock
:feature:{login,home,chatlist,profile,search,settings,screen}
```

依赖方向：`app → feature/rn-host`、`feature → core 契约`、`rn-host → core 契约`、**core 不依赖 feature**。

> iOS 有一条现成的教训：它的 `Core/` 里残留了 `Push/PushNotificationRouter → TipsyRouter` 的向上依赖，并因此把 auth 的 UI 编排单独拆出 `AuthUI/`（注释写明「引用 Pages/RNHost 故不放 Core」）。Android 从一开始就把「有 UI 编排」的部分放 feature 层，不进 core。

**W1 只建必要骨架**，feature 模块在对应波次开始时创建。不做空模块。

---

## 4. 单一所有权与跨界契约

### 4.1 所有权表

| 能力 | 壳模式 owner | RN Surface 权限 |
| --- | --- | --- |
| 生命周期 / 启动 | Native | 订阅必要事件 |
| login / refresh / logout | **Native（唯一刷新者）** | 读快照、请求动作；**不得自行 refresh** |
| HTTP | 原生页走 Native client；RN 页保留 Axios | 401/402 策略与 Native 对齐 |
| 语言 | **Native（唯一写入者）** | 初始 props + `onLanguageChanged` 事件同步 |
| 全局导航 / deep link | **Native Router（唯一入口）** | 请求 route，不直接操纵 Native 返回栈 |
| Push token / 通知入口 | Native | 只处理 Surface 内目的地 |
| Analytics session / attribution | Native | 发业务事件并附 Native context |
| Sentry | 双 Runtime | 同 release/env/user，各自上传 mapping / source map |
| OTA bundle 选择 | Native | 只运行被选中的兼容 Surface entry |
| Widget / voice service | Native | 经明确契约更新/启动，不依赖 App.tsx 副作用 |

### 4.2 Root side-effect 清单（**必须在 W1 完成，否则会静默丢能力**）

iOS 最贵的一次事故：`src/App.tsx` 与 `FirstEnter` 在壳内**永不挂载** → **ATT 弹窗不弹、AppsFlyer 与 Facebook SDK 从未初始化**（买量归因/投放事件全断），直到提审预演才发现。同类：`hydrateTags` 不跑导致 Create 标签抽屉空白，**升级安装因 MMKV 残留会掩蔽、全新安装必现**。

`tipsy-app/src/App.tsx`（497 行）实测的 root 副作用，逐项定 owner：

| 能力 | RN 真值 | Android owner | 波次 | 备注 |
| --- | --- | --- | --- | --- |
| Qt / QuickTracking 初始化 | `initializeQt()`、`QtAnalytics` | Native | W1 | **Android 特有**：`modules/qt` 的 `QtPackage.createReactActivityLifecycleListeners()` 会在 **Activity onCreate 就 `QtConfigure.preInit`**（已核实 `QtReactActivityLifecycleListener.kt`）。这与「壳是 analytics 单一 owner」冲突——壳必须决定是保留该 listener 还是排除模块自管，二选一并写下 |
| AB Test 初始化 | `initABTest(url, 10min)` | Native | W1 | appKey 按平台取；壳需自己拉 |
| auth restore | `useUserAction().restoreSession` | Native | W1 | 见 §4.4 |
| Sentry | `src/hooks/root/sentry.ts` | Native + RN runtime | W1 | RN 侧挂靠原生实例，不重复 init |
| PostHog | `usePostHog` / `usePostHogAuth` | Native | W4 | |
| AppsFlyer / Meta / TikTok / ATT | root effects（TikTok appID 按平台分流，`app.config.js:40-52`） | Native | W4 | **iOS 事故点**。Android TikTok 有独立 App ID + token，不能复制 iOS 的值 |
| Widget 同步 | `useWidget(userId)` | Native | W4 | 见 §4.6 |
| Push | `useNotifications` | Native | W4 | |
| deep link | `linking` config | Native Router | W1 | |
| remote config / tags / badges / avatar decorations | `useInitializeConfig`、`hydrateTags` 等 | **RN Surface 入口已镜像** | W1 | `index.surfaces.js` 顶层已补 `hydrateTags` / `hydrateCharacterBadgeConfigs` / `hydrateAvatarDecorationConfigs`（实测），Android 复用同一入口即自动获得 |
| splash 隐藏 | `hideSplashScreenWhenActive()` | Native | W1 | **Android 特有**：注释记录 Android 12~13 后台隐藏 splash 会触发 `SurfaceControl.checkNotReleased` NPE（Play Console 崩溃榜）。壳的 splash 逻辑必须保留「仅前台时隐藏」语义 |
| 字体 / asset 预载 | `useFonts(appFonts)`、`Asset.loadAsync` | 分侧 | W1 | 原生页用原生字体；Surface 侧由入口保证 |
| 语音通话残留清理 | `endAllStaleSystemVoiceCallActivities()` | Native | W4 | |
| 推荐埋点管线 | `initializeScreenRecommendationTracking`、`prefetchScreenRecommendationConfig` | Native | W4 | 与 Screen 波次同步 |
| 视频播放器池 | `VideoPlayerPoolInitializer`（挂在 `RootNavigator`）+ `useVideoPlayerPool` + `videoPlayerPoolStore` | **RN 侧已决策：刻意不挂** | — | 壳内 `RootNavigator` 不挂载 → 池不初始化。**这是已核实且已接受的取舍**：`ChatDetailSurface` 挂了 `GreetingVideoPortal` 却刻意不挂池初始化，因 `GreetingVideoPlayer` 对 `preloadedPlayer` 空值有 `fallbackPlayer` 兜底、池仅为预加载优化（`ChatDetailSurface.tsx:609-614` 注释）。Android 复用同一入口即继承该决策，**不要「顺手修复」** |

**判据**（iOS 总结出来的，直接采用）：**命令式 store populate → 必须镜像进 surfaces 入口；SWR mutate / 消费面已原生化 → 可跳过。**

**W1 交付物**：这张表每行填「已验证」证据，`UNKNOWN` 阻塞后续波次。

### 4.3 Surface 契约

#### capability handshake

Native 通过 initial props 下发：

```json
{
  "surfaceContractVersion": 1,
  "surfaceInstanceId": "uuid",
  "componentName": "ChatDetailSurface",
  "capabilities": ["auth.valid-token.v1", "navigation.open-chat.v1", "lifecycle.reappeared.v1"],
  "route": {},
  "context": { "languageCode": "en", "environment": "production", "distribution": "googlePlay" }
}
```

规则：
- 旧 bundle 遇到新增字段必须忽略；新 bundle 调用新增 native 方法前检查 capability 或用 `?.()`。
- 字段/方法只能 **additive**。改语义、改必填、删字段 → 升 `surfaceContractVersion` **且**升 OTA runtime 代际。
- Native 收到未知 route/field 必须可诊断地拒绝或忽略，**不崩溃**。
- **token 绝不经 initial props / 日志 / analytics 透传**；JS 按需调 `getValidToken()`。

#### 微根依赖清单

`src/App.tsx` 在 Surface 模式不挂载 → 每个 Surface root 必须自行补齐全局件。**漏挂的共同症状是「点击无反应」**：事件写进了 store，但没有任何宿主渲染它，**不报错、不崩溃**，只能靠用户反馈发现。

已核实 `ChatDetailSurface.tsx` 实际挂载的清单（**新接 Surface 照此对表**）：

| 挂什么 | 数量 | 漏了会怎样 |
| --- | --- | --- |
| `GestureHandlerRootView` / `SafeAreaProvider` / `KeyboardProvider` | 各 1 | 手势/安全区/键盘避让失效 |
| `NavigationContainer` + 微栈 | 1 | 页内导航不可用 |
| `SWRConfig` | 1 | 缓存/重验策略与线上不一致 |
| **`PortalProvider` + 8 个命名 `PortalHost`** | 1 + 8 | 经 `<Portal hostName>` 传送的弹窗/抽屉（`TipsyAutoHeightDrawer`、`BaseModal` 系、分享弹窗等）**存入 state 却无宿主渲染 → 点击无反应** |
| **`SurfaceToastHost`** | 1 | **丢掉全部 toast**（iOS 上 ChatDetail 与 Comments 真的丢过） |
| `RoleCardLimit` | 1 | 角色卡超限弹窗只写 session store、无人渲染 |
| `GreetingVideoPortal` | 1 | 点开场白视频卡只写 store、无人渲染 |

另需保证（不在组件树里但同样必需）：i18n 初始化与 `onLanguageChanged`、auth/user hydrate、页面依赖的远程配置（`hydrateTags` 等已在入口顶层）、Sentry runtime、Native navigation adapter、首帧/reappeared/close 协议。

**层序有讲究**：`SurfaceToastHost` 必须在命名 `PortalHost` 群**之前** —— 弹窗要盖在 toast 之上，对齐 `App.tsx` 的层序。

**W1 建立 `SurfaceDependencyChecklist`（测试 + 文档），逐项对照上表。未过清单的 Surface 不得注册。**

#### 宿主侧的两个硬前提（W0 实测，构建期查不出来）

1. **宿主 Activity 必须实现 `DefaultHardwareBackBtnHandler`**。`ReactFragment.onResume` →
   `reactDelegate.onHostResume()` 内部把宿主强转成该接口，不实现直接
   `ClassCastException: Host Activity does not implement DefaultHardwareBackBtnHandler`，
   **崩在 onResume**。语义是「RN 不处理返回键时回调原生默认返回」——
   W1 接 Router 时这里要改成「先给 RN 微栈，到栈底才 pop 原生」（§4.7）。
2. **Metro 端口要用 `resValue` 注入，不能靠 source set 的 `res/values`**。RN 从
   `R.integer.react_native_dev_server_port` 读端口（`AndroidInfoHelpers.kt`），默认 8081；
   app 侧 `res/values` 放同名 integer **不会覆盖库资源**（实测 aapt2 dump 仍是 8081）。
   漏掉的表现是 `isMetroRunning()` 恒探测 8081 → **静默回退内嵌 bundle，改了 JS 不生效且不报错**。

#### 微栈原则

Surface 只挂最小 root stack。**RN 页内 `navigate` 的所有目的页必须都在微栈内**——iOS 的 RoleCardSurface 因缺 `CreateStack` 出现过死链。跳出微栈的目的页要么经桥走 Native Router，要么在 shell-host 下降级 no-op（React Navigation 对不存在的目标栈是静默 no-op，不崩）。

#### 跨容器返回刷新

壳内经桥跳出（push 新容器盖住当前 Surface）再返回**不产生 RN blur/focus**，`useFocusEffect` 与 SWR `revalidateOnFocus` 都不触发——「去完成任务回来领奖」类页面状态刷不出来（写完评论回来按钮仍是 Comment 而非 Claim）。

**RN 侧已经就绪**：`src/hooks/useShellSurfaceRefocus.ts` 实测已存在，把 `useFocusEffect` 与 `onSurfaceReappeared` 事件收敛为同一回调，且注释明确「旧壳（无该事件）上订阅空转，行为退化为仅 useFocusEffect」。**Android 侧只需在容器非首次 `onResume` 时发 `onSurfaceReappeared`**，payload 是 `{ surface: string }`（**组件名，不是 instanceId**——事件的 dedupe 粒度是 Surface 类型）。不要用壳侧标志位的旧解法。

#### 首帧协议

Surface 首帧 ready 前显示 Native 占位，ready 后单次淡出。**不用固定延时猜测**（iOS `b2773e1` 处理过同一问题）。

### 4.4 Auth 契约

**Native owner 模式**：
- `isShellHost()` 为 true 后，JS **不得**读取/刷新/持久化 token。
- `getValidToken()` 由 Native 做 **single-flight refresh**；临过期阈值以 RN 实现为准（`isJwtExpiringSoon`，审计值 5 分钟）。
- `logout()`：失效 auth generation → 取消/废弃在飞 refresh → 清 Native 与兼容共享态 → 收敛返回栈 → 发一次 `loggedOut`。
- `notifyServerAuthRejectedForToken(token)`：**只有被拒 token 仍是当前 token 才登出**。禁止回退到无参版本——否则旧账号迟到的 401 会误登出新账号（TS 注释明确写了这条）。
- token 不写 log / Sentry breadcrumb / analytics。

**auth generation（必做）**：每次 login/logout/换号自增。Repository 在发请求前捕获 generation，回写前校验匹配才允许写 token/user、写账号缓存、更新 UI state、发用户归属埋点。iOS 用 `authEpoch` 实现，并额外用 `mutationEpoch` 管本地乐观变更（删除/置顶时自增，防在飞旧响应复活已删行）——**两轨互不替代，Android 同样需要两轨**。

**常驻 Tab 页必须订阅登录态变化**：iOS 踩过——`MainTabBarController` 缓存的 Tab VC 只在首次加载时拉一次数据且永不销毁，登录/登出若只广播给 RN 桥，会出现「登录后无人重拉」「登出串上一账号数据」。Android 的 Fragment 同样常驻，必须显式订阅 `didLogin`/`didLogout`。**约定**：`didLogin` → 重拉身份相关数据；`didLogout` → **只清账号私有数据、不发请求**（authorized 此刻必被前置拒绝）。

**历史 token 迁移算法**（见 §2.4）：
1. 同 applicationId 覆盖升级设备读 MMKV `token-storage`（兼容裸串 / `{token}` / `{state:{token}}` 三形态）。
2. 能解析则验证 JWT，写入 Native versioned token store，**保留兼容读**。
3. MMKV 缺失 → 启动一次隐藏 `AuthBootstrapSurface`，用 RN 侧原有 SecureStore JS 逻辑读出 token 经内存桥交给 Native。
4. Native 验证并持久化后写迁移标志；**重复启动幂等**。
5. 迁移失败**不清空旧值**，回退未登录 UI + 上报不含 token 的 error code。

### 4.5 网络契约

**三种鉴权模式**（已核实 `src/utils/axios.ts`：`axiosAuth` 与 `axiosPublic` 两个实例）：

| 模式 | 语义 | 对应 RN |
| --- | --- | --- |
| `REQUIRED` | 请求前取有效 token；临过期时 single-flight refresh；缺失/已过期直接 auth error；服务端 401 走 token-aware auth reject，**不做 response retry** | `axiosAuth` |
| `OPPORTUNISTIC` | **有 token 就带，无 token 也发** | `axiosPublic` |
| `NONE` | 永不带用户 token | （RN 无对应实例，仅用于明确禁止身份的端点） |

> **iOS 踩过的坑，Android 必须避免**：把 `axiosPublic` 当成 `authorized:false` 实现成「永远不带 token」。很多「公开」接口带不带 token 行为不同——`/search/character_search` 带 token 才会记入最近搜索，iOS 错用导致搜索历史恒空。**端口化任何走 `axiosPublic` 的接口都必须用 `OPPORTUNISTIC`，逐一核对 RN 侧用的哪个实例。**

**公共头**（已核实 `axios.ts:116-118`）：`Platform`、`X-App-Version`、`X-Download-Channel`（由 `getDownloadChannel()` 按 flavor 给 `GooglePlay`/`RuStore`/`APK`，已核实 `src/constants/common.ts:28-33`），加上端点要求的防欺诈标识（如 `X-Client-ID`）。

> 2026-08-11 契约订正：live RN 的刷新发生在请求前 `getAuthToken()` 路径；服务端已经返回
> 401 后不会再次 refresh/retry，而是上报带实际请求 token 的 auth reject。此前写成
> “401 refresh + retry”是计划漂移，不作为 Android 验收目标。

**统一信封** `{ code, msg, data? }`，`code != 0` 即业务错误。已知业务 code **不得**被笼统转成 IOException：`0` 成功、`6` 宝石不足、`9` 角色卡上限、`16` clover 类分支；HTTP `401` 走 token-aware auth reject、HTTP `402` 走带防抖的付费墙路由。双入口（原生页 + Surface 经桥）必须汇入**同一个漏斗**：401 → 登出并弹登录（防自触发循环）、402 → 宝石页 + 防抖。

**标量漂移容错**：dev/prod 接口会把 TS 标注 `string` 的字段返成 JSON number。iOS 因单字段类型不符导致整个响应解析失败，而列表加载路径**静默吞错**（空列表，伪装成「无结果」）。Android 必须在**统一 serializer 层**做 tolerant scalar，业务模型里不散落 `Any` / try-catch，且每种见过的变体都存脱敏 fixture。

**两个 client 共享 API base URL 与环境**，不允许一个请求 QA、一个请求 production。

### 4.6 存储契约

W1 从 RN 源码生成 registry，至少覆盖：

| key | 语义 | 首轮策略 |
| --- | --- | --- |
| `token-storage` | token（裸串，含历史三形态） | Native migrate + 兼容读 |
| `user-storage` | Zustand `{state, version}` 信封 | 解析信封，**不当裸 user JSON** |
| `auth-storage` | pushToken / isNewUser / pushEnabled（`partialize` 实测） | 审计 schema 后 dual-read |
| `rating-storage` | content rating | 保留，按渠道策略解释 |
| `config-persist-storage` | tags / nsfw 镜像 / 性别筛选 | 只迁真正跨壳需要的字段 |
| `chat-persist-storage` | 聊天状态 | **RN Surface owner，Native 不写**；Screen P2 仅只读 `videoSoundEnabled` 初值（进度 §2.42），每次真正可见重读，页内切换只存内存；这是待 owner 收口的窄例外，不扩成整信封读写权 |
| `guide-status-storage` | guide flags | 覆盖升级保留 |
| `subscribe-storage` | 订阅 UI 缓存 | **非支付真值**，谨慎保留 |
| `chat-background-storage` | 聊天背景 | RN Surface owner |
| `android_widget_payload` / `widget_character_id` | widget | W4 转 Native repository，兼容旧 key |

每行记录：schema/version、账号作用域、环境作用域、是否加密、TTL、logout 策略、rollback 可读性、owner、fixture。

**cache scope 规则**：所有用户/推荐相关 key 组合 `environment + accountId|anonymousInstallationId + feature + filters + schemaVersion`。迁移先兼容读旧 key 再写新 key，**不得静默跨账号/跨环境复用**。

> iOS 的 Home 缓存实践值得直接借鉴：信封 `{version, gender, authScope, language, savedAt, items}`，按 authScope 门禁（`guest` / `user:<id>`），`savedAt` 超 7 天作废，宁退 loading 占位也不显错数据。**但要继承它的一个反直觉修正：语言刻意不做缓存门禁**——两阶段 i18n 初始化下首屏读到的是瞬态语言，做门禁会永久拒绝缓存（iOS 踩过「二启永远无种子」），语言真变化靠 `languageDidChange` 重拉自愈。

### 4.7 导航契约

Native Router 是唯一入口：

```
Intent / Push / Widget / Compose click / RN bridge
        ↓  typed AppRoute parser
        ↓  auth gate + dedupe + source attribution
   Native destination | RN Surface
```

必须支持的外部路径（实测 `src/App.tsx:445-465` 的 7 条）：`profile/daily-gem-entry`、`profile/user-balance`、`subscribe/page`、`chat/detail`、`chat/mini-phone`、`chat/letter`、`create/profile-detail`。

**scheme 安全审计（W1 必做）**：`app.config.js:147-171` 的 Android `intentFilters` 注册了 `fb` / `twitter` / `discord` / `instagram` / `tiktok` 五个**泛用外部 scheme** 的 VIEW + BROWSABLE。这是给「跳转到社交 App」用的意图，但注册成自己的 intent filter 意味着**壳会声明能打开这些 scheme**，存在 intent 劫持面。**不要原样照搬**，W1 单独审计每条是否必要。

每条 route 都测：cold/warm/background、未登录排队、auth ready 后只执行一次、malformed 输入、相同 Intent dedupe、返回目标、source attribution。

**`.userProfile` 分流**：自己 → Profile Tab，他人 → 原生他人主页。分流**收口在 Router**（iOS 实践，含 MMKV 冷启兜底防对自己 Follow），不在调用点各自判断。

### 4.8 i18n 契约

**三层集合不能混写**（已逐项核实）：

| 层 | 实测值 | 说明 |
| --- | --- | --- |
| 磁盘 locale JSON | **28** 个 | `src/i18n/locales/*.json` |
| `i18n-index.ts` 实际 import | **27** 个 | `ar.json` 未 import |
| `SUPPORTED_LANGUAGES` 客户端支持码 | **26** 个 | `zh` 虽被 import 但不在支持码内 |
| Settings 可选列表 | 服务端 `/supported_languages` 返回 | **≠** 上面任何一个 |

`normalizeLanguageCode`（`i18n-index.ts:64-75`）规则：精确匹配 → 主语言码匹配（`es-CR` → `es`）→ `zh` 系一律 → `zh-tw` → 兜底 `en`。**Android 必须逐行对齐**，包括「`zh` 简体归到 `zh-tw`」这个产品决策。

**做法**：
- 生成脚本审计全部 28 个文件，但**不得**把 dormant 文件（`ar`）自动提升为产品可选语言。
- runtime 可选集合 = 「服务端列表 ∩ 当前批准的客户端支持码」；未知/无资源 code 安全 fallback 并上报非 PII 诊断。
- RN key 含空格与符号（key 就是英文原文），**不强行映射成 Android resource name**；Native 提供 key-based `L10n` + 可观察 locale state。
- fallback 链：当前语言 → en → key。
- **原生页文案组件化**：提供自订阅语言变更的 Compose 文案组件，不让每个页面手挂监听（iOS 后期才补 `LocalizedLabel`/`LocalizedButton`，Android 从第一天就做）。
- 生成脚本改动属于 `tipsy-app` 独立 PR；生成物与源 SHA、支持码清单一起固定。

> iOS 的两个具体教训：① 新增原生页文案**必须**加入词条白名单并重跑导出，否则非英文用户静默看英文——**英文环境测试看不出来**，搜索页 shipped 过一次；② 词条量随波次增长，导出脚本必须能增量重跑。

---

## 5. 构建、渠道与 OTA

### 5.1 Flavor 矩阵

```
distribution flavor × build type
  googlePlay / directApk / ruStore   ×   debug / qa / release
```

> **W0 实测发现的一类风险：autolinking 会把开发期模块接进 release。**
> `expo-dev-client` 在 `tipsy-app/package.json` 里是 **`dependencies`**（非 devDependencies），
> autolinking 因此把 `expo-dev-launcher` / `expo-dev-menu` 接进 **release** runtime classpath，
> 进而把 `androidx.compose.ui:ui-tooling` 的 `PreviewActivity`（**`exported=true`**）
> 合并进 release manifest —— **生产包对外暴露一个调试 Activity，且普通构建不报错**。
> W0 用 `src/release/AndroidManifest.xml` 的 `tools:node="remove"` 兜底，并由
> merged manifest 断言长期看守（§5.1）。根治需改 `tipsy-app` 的依赖分类或按 variant
> 排除 dev 模块 —— 前者不在本仓权限内。**每次新增 RN 依赖都要复查 release manifest diff。**
>
> 同理，`expo-dev-client` 声明的 `org.webkit:android-jsc:+`（本工程用 Hermes、未装 jsc-android）
> 会让任何解析它的任务失败（实测 lint 的 `generate*LintModel`），需全局排除。

> **W0 实测补正**：RN 的 Gradle plugin 会额外引入一个 **`debugOptimized`** build type，
> 所以实际 variant 数比上式多一档（W0 现状 = 3 flavor × {debug, debugOptimized, release}）。
> 规划 `qa` build type 时要把它算进矩阵，CI 任务名也以 `./gradlew :app:tasks` 实际输出为准。
> 另：debug 默认出四个 ABI，单 flavor 中间产物可达数 GB —— 曾因磁盘写满导致
> `packageRuStoreDebug` 失败且**不提示空间不足**，现 debug 只出 `arm64-v8a`。

| Flavor | applicationId | 渠道规则 |
| --- | --- | --- |
| `googlePlay` | `com.tipsyturbo.app` | Google Play Billing；Play 内容政策 |
| `directApk` | `ai.lightspeed.tipsy` | Direct APK 分发 |
| `ruStore` | `com.tipsytavern.app` | RuStore Pay + 专属 activity/metadata/Maven |

每个 flavor 的 source set 必须显式包含或验证：applicationId 与 label、Firebase app / `google-services.json` 选择、`X-Download-Channel` 取值、Billing provider 与订阅后端 platform、deep link / app link / intent filter、attribution SDK 启用策略（**TikTok 等不得跨渠道误初始化**）、content rating 策略、ProGuard/R8 keep 规则（`app.config.js:220-226` 的 `extraProguardRules` 需移植：`com.tiktok.**`、`com.android.billingclient.api.**`、`androidx.lifecycle.**`、`com.android.installreferrer.**` 的 keep + 两条 dontwarn）、仅该渠道需要的 repository/dependency。

**RuStore 不用 config plugin 的字符串替换**（§2.3），改用 flavor source set 提供自己的 Application/Activity 子类。

**W1 建立 manifest / package snapshot 测试**，防 flavor 串配置。三个 flavor 的 **merged manifest**（不是 source manifest）逐项断言 exported、scheme、permission、service/receiver/provider 与渠道组件。

**签名纪律**：release **不得**配置 debug signing。**禁止**用 `applicationIdSuffix` 改生产身份来「简化」测试——覆盖升级 gate 必须用真实 applicationId + 匹配签名。

**版本号**：工程初始化期用 `versionCode=1` / `versionName=0.0.0-dev` 并在 debug/qa metadata 标 `NOT_FOR_STORE`；真实递增版本由 W5 发布 owner 批准后引入。

### 5.2 RN bundle 入口

| 场景 | entry | 说明 |
| --- | --- | --- |
| Debug | Metro `:8083` | 端口避让 tipsy-app(8081) 与 iOS 壳(8082) |
| W0 管线自检 | `tipsy-app/index.surfaces.debug.js` | 零业务依赖，二分「挂载层问题 vs 业务 import 链问题」 |
| W1+ Debug/QA/Release | `tipsy-app/index.surfaces.js` | 业务 native module 经 capability gate 接回后切换 |

**离线可启动是硬 gate**（iOS `8bd1d01` 的教训：不能只验证 Metro）。QA/Release 的 embedded bundle 必须和 binary 一起离线启动成功，且 build metadata 写入 RN SHA、Android SHA、surface contract version、runtime 代际、distribution、environment（**不写 secret**）。

`cleartext` 只允许在 debug manifest / Network Security Config 中开启（现网 CNG 的 debug manifest 允许 cleartext、main manifest 不允许，保持这个边界）。

### 5.3 OTA 隔离（**最容易出事的一处**）

**现状事实**：Android 已有 `preview` / `production` 两条 channel，服务的是**完整 RN App**（entry `index.tsx`，`package.json main` 实测就是 `index.tsx`），runtimeVersion 走 `{policy: 'appVersion'}`（`app.config.js:340` 附近，`IS_SHELL_OTA` 未设时的默认分支）。

**风险**：壳只注册了 13 个 Surface 组件、且原生模块集与完整 App 不相等。**若壳复用同一 channel，完整 App 的 OTA 包会被下发到壳里**——加载不存在的 root 组件，或 ABI 不匹配直接崩。

**三重隔离**（对齐 iOS 已实证的模型，命名按 Android 调整）：

```
entry:    index.surfaces.js          （不是完整 App entry）
runtime:  android-bridge-N           （桥 ABI 代际，不绑 marketing version）
channel:  android-native-<distribution>-preview
          android-native-<distribution>-production
```

**runtime 语义（iOS 走过弯路，Android 直接采用终态）**：iOS 曾把 rtv 锁到 marketing version（`1.3.23`…），结果**每次发版都换代际，热修只能投给最新 store 版本，存量用户永远收不到**；2026-07-22 改为桥版本 `bridge-N` 才解决。Android 从第一天就用 `android-bridge-N`：

- **何时升代**：① 改/删已有桥方法的签名或语义；② RN/Expo/expo-updates 等含原生代码的依赖升级（Hermes 字节码或 ABI 变化）；③ 新增含原生模块的 RN 依赖且 JS 无条件 require；④ `index.surfaces` 入口/Surface 注册契约变化。
- **何时不升代**：纯 JS 改动；纯壳侧改动不影响 JS 可见协议；新增桥方法且 JS 侧用方法级可选链调用。
- **桥方法演进纪律（代际内永久约束）**：新增桥方法的 JS 调用**一律** `TipsyAuth.xxx?.()`。同一代际横跨多个 store 版本，OTA 会把新 JS 推给代际内**最老**的二进制（没有该方法），**且这个状态不随发版自动消除**。JS 要无条件调用新桥方法，唯一途径是升代。模块级判空拦不住。
- 三个 distribution **不串 channel**，preview 不串 production。
- bundle metadata 声明 contract version；不兼容时拒绝并 fallback 到 embedded。
- **N/N-1 双向 contract test**：新 Native + 旧 JS、旧 Native + 新 JS 都要测。
- **OTA 只能恢复 RN Surface**。Native 崩溃或数据迁移问题不能声称「靠 JS OTA 回滚」。

**Android 特有的技术接线**（已核实源码，与 iOS 的 plist 机制不同）：
- expo-updates 的 Android 配置源是 **`AndroidManifest` meta-data**，键名实测于 `@expo/config-plugins/build/android/Updates.js`：`expo.modules.updates.ENABLED` / `EXPO_UPDATE_URL` / `EXPO_RUNTIME_VERSION` / `EXPO_UPDATES_CHECK_ON_LAUNCH` / `EXPO_UPDATES_LAUNCH_WAIT_MS` / `UPDATES_CONFIGURATION_REQUEST_HEADERS_KEY`（channel 就放这里）/ `HAS_EMBEDDED_UPDATE`。
- 壳自管初始化：`UpdatesController.initializeWithoutStarting(context)`（实测存在于 `expo-updates/android/.../UpdatesController.kt:35`），Surface 挂载时 bundle 取 `UpdatesController.instance.launchAssetFile`，无则回退内置 asset。
- 另有 `UpdatesController.overrideConfiguration(context, map)`（`:155`），可在运行时按 flavor 注入不同 channel——比 iOS 那套「构建期 PlistBuddy 覆写」更干净，**W4 优先评估这条路**。
- **懒生效语义**：壳首屏是原生，只有进 Surface 才挂 RN。所以走 expo-updates 默认的「后台下载、下次冷启生效」，`LAUNCH_WAIT_MS` 设小值不阻塞原生首屏。这对壳是优点：天然避免会话中途换 bundle。

**授权纪律**：W1~W3 只用 embedded bundle。发布 OTA 需独立明确授权，本仓脚本默认 `--dry-run`。

### 5.4 质量门禁分层（G0-G4）

| Gate | 触发 | 最低内容 | 阻塞对象 |
| --- | --- | --- | --- |
| **G0** Baseline | W0 / 依赖 pin 变化 | SHA 记录、RN 既存债务登记、工具链固定值、与 CNG 参考工程的 diff | 所有开发 |
| **G1** PR Fast | 每个 PR | format/静态分析 → Android lint → 纯 Kotlin unit + contract test → 三个 debug flavor assemble。动了 RN pin/桥/bundle 时额外跑 RN typecheck、lint、targeted tests、i18n、embedded Surface export smoke | 合并 |
| **G2** Feature | 每个页面 / Surface | §9.2 十类证据 + §9.1 Surface 矩阵一行 | 该功能标 DONE |
| **G3** Nightly | 主干每日 | API 24 + API 36 emulator matrix（可用 image 在 W0 固定）→ instrumentation（启动、tabs、Native↔RN、back、deep link、auth 切换）→ Surface 反复开关 + rotation + 进程重建 → Macrobenchmark（startup、Home/Screen scroll、Surface 首帧）→ dependency/permission/manifest diff | 灰度候选 |
| **G4** Release | 每个渠道候选 | 三渠道产物分别验证 → applicationId/version/证书 SHA-256/Firebase/Billing/deep link/权限快照 → R8 mapping + native symbols + RN source map 与 build ID 唯一关联并归档 → **现网 RN 包覆盖升级** → push/支付/widget/voice FGS/attribution/Sentry/OTA N/N-1 真机验收 | 发布 |

**两条纪律**：
- **`NOT RUN` 不等于通过**。任何门禁项标 `NOT RUN` 都需要风险 owner 明确批准，不得自行视为已通过。
- **命令名不靠猜**。文档里的 Gradle task 名必须在 W0 跑完 `./gradlew :app:tasks` 后按实际结果写回；不存在的命令不得写成"已通过"。

**设备矩阵最低要求**（W0 固定可用 image 后）：API 24（最低 SDK：启动、登录态读取、Widget/通知兼容、关键 Native/RN route）、API 36（target 行为：predictive back、edge-to-edge、通知/FGS/媒体权限）、至少一台**低内存真机**（Screen、Surface 50 次开关、后台回收）、至少一台有 Google Play 服务的真机 + 一条 RuStore/Direct 真实渠道链路。

> instrumentation **不能只调 deep link 命令然后假设页面正确** —— 必须断言目标 testTag 与参数。

> **lint 不得继承 RN 侧的弱化配置**。实测 `modules/qt`、`modules/widget`、`modules/voice-call-system-session` 三个 Android module 都有 `lintOptions.abortOnError false`；RN 侧 `vitest.config.ts` 有 `passWithNoTests: true` 与历史 suite exclude。这些是 RN 的历史债务，**Android 新工程不复制这种假绿色**。同理，RN 侧 `npm run lint` / `npm test` / `expo-doctor` 在固定 SHA 上有既存红项（**具体数量待 W0 实跑确认**——本仓 `tipsy-app/node_modules` 未安装，无法核实）。这些属 RN 独立债务，**不在 Android packet 里顺手修或加 ignore**。

### 5.5 发布脚本要求

脚本必须：① 先跑所有零成本守卫（工作树/分支/SHA/version 唯一性/flavor/凭据存在性但不打印值）；② **默认 dry-run**，显式 `--execute` 才写外部；③ 生成唯一 build ID 并验证 artifact/metadata/mapping/symbol/source-map 一一对应；④ **只回滚脚本本次创建的**本地文件/tag/状态，不碰用户已有改动；⑤ 多个远端 ref 需一起更新时用原子 push，失败不留半套状态；⑥ 触发与终态通知解耦，按 profile/build ID 防重；⑦ 支持故障注入测试——guard/build/upload/tag/push 每步失败都验证清理范围。

这些要求来自 iOS 已验证的 `eas-store-release.sh` 与其 transaction test（iOS 侧实测有 `scripts/tests/eas-store-release-transaction-test.sh`），但 **Android 重新实现，不复制 Xcode 细节**。

---

## 6. 覆盖升级与恢复

### 6.1 覆盖升级矩阵（每个 distribution 各跑一遍）

| 旧包 | 新包 | 验什么 |
| --- | --- | --- |
| 现网 RN release，已登录 | Native QA/release | token / user / 语言 / 筛选 / 会话列表 / 钱包余额连续；深链可用 |
| 现网 RN release，游客 | Native | anonymous installation id / 推荐连续性 |
| 现网 RN release，历史多次升级过 | Native | SecureStore/MMKV fallback 与**迁移幂等** |
| Native last-known-good | 候选 Native | schema 前向兼容 |
| 候选 Native + embedded JS | 同 binary + OTA N/N-1 | runtime / capability / fallback |

**必须用真实匹配签名。`adb install -r` 用 debug 签名重装不构成覆盖升级证据。**

> iOS 至今未完成真机覆盖升级验证（进度文档 §4.D.14 仍是待办，只在模拟器验过）。Android 有三倍的验证面，**不要把它留到最后**——它是 §2.4 迁移算法唯一的正确性证据。

### 6.2 恢复策略

Android **已安装包不能通过降低 versionCode 回滚**。

| 层级 | 手段 |
| --- | --- |
| PR / QA | revert 本 packet 自有 commit，或重装 last-known-good QA artifact |
| RN Surface JS bug | 在独立 channel republish 已验证 update，或 fallback embedded |
| Native 灾难 | 暂停 staged rollout；用**更高 versionCode** 向前发布 last-known-good Native 构建 |
| 迁移早期 | 保留旧 RN release 分支，可用更高 versionCode 快速构建/提交 RN 版本恢复 |

**不做逐页 native↔RN feature flag**（iOS 评审结论：双实现 + flag 基建 + 常态化双轨，投产比最差；且壳大部分已原生，原生 bug 本来就 OTA 修不了）。

发布前必须**实际演练**「暂停放量 + 向前恢复」。只写 runbook 不算完成。

每渠道归档 last-known-good：source tag、RN pin、artifact hash、签名指纹、mapping、symbols、source map、依赖清单、QA 报告、build ID。

---

## 7. 双仓协作边界

### 7.1 职责

| | `tipsy-app`（submodule） | `Tipsy-Android`（本仓） |
| --- | --- | --- |
| 定位 | RN 业务唯一来源 + 三渠道现网宿主 | Android 壳 + 原生页 |
| 日常迭代 | 绝大多数 PR | 壳/原生开发 |
| 产物 | 三渠道包（流程不变）+ 完整 App OTA | Native 三渠道包 + Surface OTA |

**纪律**：
- **禁止在本仓直接改 `tipsy-app/` 内部文件**。RN 侧改动走 tipsy-app 自己的 PR 流程，合入后本仓 bump submodule pin。
- 本仓 PR 中的 submodule 变更只允许是指针 bump，且目标 commit 必须已存在于 tipsy-app 远端。
- 纯壳能力（推送、埋点、Sentry 原生侧等）不需要动 submodule、不需要 bump pin。

### 7.2 好消息：RN 侧的壳适配已经做完了 95%

**实测：`tipsy-app` 里已有 55 个文件引用 `isShellAuthHost()` / `isShellHost()`**，覆盖了 iOS 壳一年来沉淀的全部适配——13 个 Surface 入口组件、`SurfaceToastHost`、栈底 `popSurface` 兜底（`TipsyHeader` 自带）、`useShellSurfaceRefocus`、`useChatNavigation` 的壳分支、`shellGemsEntry`/`shellTaskEntry` 跨栈出口、`axios.ts` 的 401/402 桥上抛、`config_persist` 的 nsfw 镜像接力、`recommendTracking` 的壳 outbox、`Rating` 组件的壳分支、`api.ts`/`lane.ts` 的壳 API 地址与 BOE lane。

**这意味着 Android 的 RN 侧工作量远小于 iOS 当年**：iOS 要从零建这 55 个文件的适配，Android 只要提供一个能让 `isShellHost()` 返回 true 的 Kotlin 桥，**这些适配全部自动生效**。

**这是本方案对 W1 范围的一个重要收窄**：W1 的 RN 侧真实缺口只有下面第一、二两项，其余都是「已存在，复用即可」。

### 7.3 必须落在 tipsy-app 的改动清单

每一项都是 additive、且**非壳路径（三渠道现网 RN App）必须行为等价**：

| 改动 | 内容 | 现网 Android 影响 |
| --- | --- | --- |
| **`tipsy-auth` Android 实现** | `expo-module.config.json` 加 android 段 + Kotlin module + provider 注册模式 | **有**——module 会被 autolink 进现网包。必须保证无 provider 时 `isShellHost()` 返回 false，行为与现在（module 为 null）等价 |
| `AuthBootstrapSurface` | 新 Surface：读 SecureStore token 交给壳（§2.4） | 无（新增注册，现网不挂载） |
| ~~栈底返回桥~~ | **已存在**（`TipsyHeader` 自带兜底，自绘返回键的页面已就地加分支） | 无 |
| ~~`useShellSurfaceRefocus`~~ | **已存在**（`src/hooks/useShellSurfaceRefocus.ts`，实测），Android 侧只需发 `onSurfaceReappeared` 事件即可复用 | 无 |
| 词条导出脚本 | 复用/扩展 `scripts/export-shell-locales.mjs` 的白名单（iOS 已建）；Android 原生页新增词条走同一管线 | 无 |
| Surface 入口增补 | **仅在**发现新的 root side-effect 缺口时才补（现有三个 `hydrate*` 已就位） | **iOS 侧共享**——双壳共用入口，改动要同时回归 iOS 壳 |
| OTA 发布脚本 | Android 壳专用 channel/rtv 注入（对齐 `eas-shell-hot-update.mjs` 体例） | 无（新增脚本，channel 白名单隔离） |

> **`index.surfaces.js` 是 iOS 壳与 Android 壳共用的入口**——这是本次迁移新增的耦合点（iOS 独占时不存在）。任何对该文件的改动都需要**双壳回归**，PR 模板要求附对侧关联信息。这一条 iOS 的文档里没有，是 Android 加入后才出现的约束。

### 7.4 pin 管理与 delta 审计

- 每个波次固定 `source_rn_sha` 与 `target_android_sha`，写进进度文档。
- 波次结束跑 delta 审计：

```bash
git -C tipsy-app log --oneline <wave-source-sha>..<candidate-sha>
git -C tipsy-app diff --name-status <wave-source-sha>..<candidate-sha> -- \
  src/App.tsx src/navigation src/app src/apis src/hooks src/store src/i18n \
  index.surfaces.js modules plugins app.config.js eas.json
```

- 把变化映射到对等矩阵；不相关的变化进下一个 pin，影响当前契约的**先更新契约与测试再 bump**。

> **本方案的所有事实核实于 pin `93d2c5551`**。submodule 会随 RN 迭代前进，**W0 开工时须先跑一次 delta 审计**，确认从 `93d2c5551` 到开工时的 pin 之间没有引入新的 config plugin / 桥契约 / 存储 key / 渠道配置变化。

---

## 8. 业务迁移范围（本方案的主体）

**这一节是方案的重心。** §2-§7 是为了让这一节能安全执行而存在的前置约束，不是工作量的主体。

### 8.0 实测工作量分布

原生化的五个页面，各自要搬的 RN 代码量（实测于 `93d2c5551`）：

| 页面 | 主页面 | 组件 | hooks | 合计 | 主要接口 |
| --- | --- | --- | --- | --- | --- |
| **Profile（自己+他人）** | 865（`user-profile.tsx`） | 10,498 | 1,272 | **约 12.6k** | `/user/created/list`、`/user/followed/character/list`、`/user/likes/character_list`、`/user/follower_list`、`/user/stats_info`、`/user/character_stats`、`/character/list/{self,creator}`、`/user/{follow,like,share}/*`（`apis/profile.ts` 全量核实） |
| **Home** | 2,339 | 3,783 | 936 | **约 7.1k** | `/recommend/recommend_feed/list`、`/character/get/public_list`、`/story/list` |
| **ChatList** | 462（`index.tsx`） | 3,003 | 228 | **约 3.7k** | `/user/chatted/list` |
| **Screen** | 2,101（整目录） | 2,313（`components/video/` 的 feed 簇） | 884（`hooks/video/`，与 ChatDetail 共享） | **约 5.3k** + `video.ts` 559 | `/character_distribution/list`、`/recommend/home/list`（AB 二选一） |
| **Search** | 251 | 1,775 | 495 | **约 2.5k** | `/search/character_search`、`/search/user_search`、`/search/character/suggest`、`/search/popular_search_terms/app`、`/search/recent_history`、`/search/clear_history` |
| **Login + Onboarding** | 5,285（`src/login/` + `src/hooks/login/`，其中 starter-picks 2,364） | — | — | **约 5.3k**（Onboarding 留 RN，见右） | `/login/firebase`、`/login/email`、`/login/email/send_code`、`/login/email/did_not_get_code`、`/login/password`、`/auth/refresh_token` |
| Settings 列表 + 语言页 | 430 + 136 | — | — | 约 0.6k | `/supported_languages` |

**约 35k 行 RN 需要用 Kotlin/Compose 重新实现**（Onboarding 的 2.4k 留 RN，故净约 32.6k；不是 1:1 翻译，原生实现通常更短，但**业务分支数一样**）。对比：本方案 §2-§7 描述的全部基建，落地约 3~5k 行 Kotlin。**这个比例才是真实的工作分布。**

两处**容易误读的行数**，避免按目录总量估工期：

> **Profile 的 6,084 行里只有 865 要迁**。`src/app/profile/` 下 `user-coins`(1304) / `edit-rolecard`(897) / `subscribe`(717) / `withDraw-status`(585) / `gems-subscription`(526) / `withDraw-explain`(377) / `user-balance`(351) / `follow`(445) 全是 **RN Surface，不迁**；真正原生化的只有 `user-profile.tsx`(865)。Profile 的 12.6k 大头在 `components/profile/`（`CharacterGrid` 1903 + `CharacterGridItem` 1106 + `StoryItem` 1026 + …），因为自己主页的 5 个内容 Tab 全靠这些组件。

> **Screen 的组件不在 `app/screen/` 里**（本方案早期版本据此低估了约 3.2k）。播放器实现在 `src/components/video/`，且**与 ChatDetail 共享部分文件，必须分清归属**：
> - **Screen 专属**（要迁）：`FeedMediaItem`(982，`react-native-video` 实际调用处) + 其三个仅被它引用的子组件 `FeedMediaTaglineOverlay`(531) / `MediaShareModal`(426) / `VideoActionButtons`(374) = **2,313 行**。
> - **ChatDetail 侧仍用**（不迁，留 RN）：`GreetingVideoPlayer`(381) ← 被 `GreetingVideoPortal`(32) / `components/chat/AppChatList.tsx` / `surfaces/ChatDetailSurface.tsx` 引用。
> - **播放器池是跨页共享设施**：`hooks/video/useVideoPlayerPool.ts` + `store/videoPlayerPoolStore.ts` + `VideoPlayerPoolInitializer`(16，挂在 `RootNavigator`)。壳内不挂载，但**已核实这是刻意取舍**（有 `fallbackPlayer` 兜底，池仅为预加载优化），Android 继承即可，见 §4.2 对应行。Screen 原生化后自建 Media3 池，与这个 JS 池无关。

### 8.1 页面迁移规格

每个页面按同一模板落地。**模板本身就是验收清单**——写代码前先把这张表填完，`?` 是停止条件。

#### Home（Tab2，约 7.1k）

| 维度 | 实测真值 | 原生实现要点 |
| --- | --- | --- |
| 数据源 | 7 个系列共用一个 `useCharacterList`，按 `selectedSeries` 取一路（`useHomeCharacterLists.ts` 实测）：`For You` → `/recommend/recommend_feed/list`；`Weekly Picks`/`All-Time Faves`/`New Releases`/`Following` → `/character/get/public_list`（靠 `sorting` 区分：`WeeklyPicks`/`Popular`/`New`/`FollowersCharacterNew`）；`Multi-character` → `/story/list`；`World` → simulatorGame 列表 | 一个 Repository + sorting 枚举，**不要写 7 个 Repository** |
| 分页 | `size: 21`（For You / public / story），World `size: 20` | 固定值，不要「优化」 |
| 请求参数 | `gender`（`Female/Male/NonBinary/All` → `female/male/other/undefined` 映射）、`nsfw`、`language_code`、`tag_ids`、`content_type`（**仅当 `contentTypes.length === 1` 才传**）、`session_id`、`recommend_tracking_session_id` | 映射表照抄，`content_type` 那个条件容易漏 |
| session 语义 | `forYouSessionId` 锁 For You 推荐池；其余系列各有 `homeRecommendationSessionIds[series]` | 切性别/标签/下拉刷新/切语言换新 id，**翻页不换** |
| 筛选持久化 | `config-persist-storage` 的 `gender` / `nsfw` / `tags`。**`nsfw` 以后端 `user.nsfw` 为权威，由 store 底部订阅镜像到本地**（`config_persist.ts:225` 注释）。⚠️ **原文「App 不回写后端」需限定**（2026-08-14 订正）：**Settings 的 Limitless 开关是唯一的写方** —— `POST /user/nsfw`（`settings/page.tsx:78` `updateUserNsfw`）成功后才 `setNsfw` 本地镜像并重拉 `hydrateTags`。正确表述是「**Home/筛选侧**不回写后端，写入只在 Settings 一处」。该开关**仅侧载渠道可见**（`shouldShowNsfwSetting(isAndroidAPK)` = `isAndroidAPK`，即 directApk；GooglePlay 与 RuStore 都不显示）。⚠️ **`tags` 是从 `/character/tags` 拉来的标签目录，不是用户的勾选**（`config_persist.ts:293-320` `hydrateTags`）；**用户勾选存在不持久化的 `session.ts`**（`selectedTags: {series, tags, contentTypes}`，`session.ts:29/67`，该 store **无 persist 中间件**，已核实 `grep -c persist` = 0）—— 所以**杀进程后勾选归零，只有 gender 存活**。照「tags 也持久化」实现会让原生版比 RN 多记住筛选，且不报错 | 原生写入不得破坏这个单向流；勾选**不要**写盘 |
| 冷启动缓存 | `useForYouListCache.ts`（112 行）+ `selectLockedForYouHomeItems` + `prefetchForYouHomeImages` | 见 §4.6 的信封 + authScope 门禁 + TTL；**语言不做门禁** |
| 埋点 | 11 个事件（实测）：`page_exposure`、`discover_subpage_exposure`、`discover_page_tab_click`、`character_page_click`、`character_page_exposure`、`search_click_search_box`、`search_content_click`、`activity_banner_{exposure,click,open_result}`、`payment_hub_click` | `character_page_exposure` 需手动补 `uid`（RN 由 JS 封装自动注入） |
| 组件 | `CardBanner`(946) + `BannerItems`(606) + `HomeCard`(474) + `HomeStoryCard`(395) + `HomeFilterDrawer`(382) + `HomeHeader`(272) + `DailyEggSmashModal`(708) | banner 与彩蛋弹窗**评估留 RN Surface**（运营高频改动区），减 1.6k |
| 页面状态 | 12 个 `useState` + 89 处 hook 调用 | 收敛到单个 sealed state，不要 12 个 StateFlow |
| Android 差异 | `World` 系列在 Home 内（`HomeSeries` 含 `World`），但 SimulatorGame 是 WebView 不迁 | **开放问题 §12.4** |

#### Profile 自己视角 + 他人主页（Tab5，约 12.6k，**最大的一块**）

| 维度 | 实测真值 | 原生实现要点 |
| --- | --- | --- |
| 页面本体 | `user-profile.tsx` 865 行（自己/他人共用，靠 `userId` 分流） | 分流收口在 Router（§4.7） |
| 内容 Tab | **自己 5 个**：创作 / 记忆 / 角色卡 / 收藏 / 点赞。分别由 `useCreatedList`(118) / `useProfileMemories`(126) / `useProfileFavorites`(83) / `useProfileLiked`(82) 驱动。⚠️ **他人主页只有 1 个 tab**（2026-08-14 逐行核实）：`CharacterGrid.tsx:980-1005` 的 `isSelf` 否分支 `return [...]` 里**只有一个 `data: chunk(otherUserData, 3)` 元素** —— 该分支上方注释写「他人主页显示角色和视频两个tab」，**注释与代码不符，代码是真值**。照注释做会多出一个无数据源的空 tab | 5 个 Tab 共用一个分页壳，差异只在 endpoint 与 item 类型；**他人主页不要复用五图标 tab 栏** |
| 他人主页的数据源（2026-08-14 逐个核实，与自己视角**几乎无一条相同**） | 头部资料 = `/user/get/public`（**`axiosAuth`**，`apis/user.ts:49`）；统计 = `/user/stats_info` 但走 `getPublicFollowerInfo` → **`axiosPublic`**（`apis/profile.ts:130`）；列表 = `/character/list/creator`（v1）**与** `/character/list/creator/v2` **两个都发**，v2 带 `types:['character','story','game']` + `language_code`，均 `axiosPublic`。⚠️ **`/plot/list/creator` 不要实现** —— 它的 SWR key 恒 `undefined`（唯一调用点 `CharacterGrid.tsx:250` 不传 `isPersonal`，默认 `true`），现网从未被调用 | **`size` 是 200**（`useProfile.tsx:30` `PAGE_SIZE`），不是自己视角的 10/20。**v1/v2 双发不是冗余**：网格取 `creatorCreatedList`（v2，含 game）**非空时用 v2，空则回落 v1 的 `characterData`**（`CharacterGrid.tsx:980-983`）—— 壳照此优先级，别只实现一个。v2 按 `item_type==='game' ? game_<game_id> : item_id` 去重。⚠️ **他人主页翻不了页**：`onEndReached` 的 tab0 分支调的是 `loadMoreCreated()`（自己那条列表），两条 creator 列表都无 `setSize` 出口 —— 壳按「单页 200、不翻页」即对等 |
| 他人主页头部的四处结构差异（`CharacterGrid.tsx:1422-1445`） | ① 关注按钮取代 Edit Profile ② **无钱包卡**（`isSelf && UserProfileGems`）③ bio 走 `UserBio`(198) 而非 `RenderBio` ④ `FollowInfo` 四统计**两端都渲染** | `isDeleted` 用户：关注按钮不渲染 + **下拉刷新整个禁用**（`refreshControl={isDeleted ? undefined : ...}`） |
| ⚠️ 他人主页头部**不是纯公开接口** | `/user/get/public` 走 `axiosAuth`，而 `axiosAuth` 取不到有效 token 会 **`requestLogin('axios-auth')` 并 reject**（`utils/axios.ts:148-175`）—— 壳宿主下 `isShellAuthHost()` 为真，会**直接弹原生登录页** | 与 `AppRoute.UserProfile.requiresAuth = false`（游客可浏览）**存在张力**：游客点创作者会拿到登录页而不是主页。这是 RN 现网行为，**壳按 REQUIRED 接线即对等**；若要真游客可浏览需后端换实例，属独立决策。**别自作主张改成 OPPORTUNISTIC** —— 那会让 401 与登录弹窗时序偏离现网 |
| 组件大头 | `CharacterGrid`(1903) + `CharacterGridItem`(1106) + `StoryItem`(1026) + `GameGridItem`(585) + `PlotItem`(568) + `FavoriteCharacterCard`(590) + `RoleCard`(468) | **item 类型有 6 种**（角色/故事/游戏/记忆/收藏/角色卡），这是 12.6k 的主要来源 |
| 缓存 | `profileCreatedListCache.ts`(196) + 已有测试(129) | 有现成测试可作为对等 fixture 来源 |
| 卡片菜单 | 按类型给不同动作：角色=编辑/删除/置顶、故事=删除/置顶、游戏=置顶 | iOS 踩过：more 按钮做成装饰 `View` 导致点击穿透进详情页。**Android 用可点击组件吃掉事件** |
| 编辑保真 | 创作列表的**原始 JSON 必须原封透传**给 `CreateSurface`。by-id 重拉会导致保存时字段重置（= 数据损坏） | iOS 明确记录的坑，直接继承 |
| 出口 | 编辑资料→`EditProfileSurface`；钱包/Upgrade→`GemsSubscriptionSurface`；Coins→`UserCoinsSurface`；角色卡→`RoleCardSurface`；设置→原生列表 | 5 个 Surface 出口，全部经桥 |
| 不迁 | `user-coins`/`subscribe`/`withDraw-*`/`gems-subscription`/`user-balance`/`edit-rolecard`/`follow` 共约 5.2k | 见 §8.0 的修正说明 |
| 关注按钮（**要做**） | `POST /user/follow/user`（`axiosAuth` → REQUIRED，`apis/profile.ts:142`，请求体 `{user_id}`，响应 `{character_id, status}`）。按钮在 **`ProfileHeader.tsx:200-225`**（不在 `user-profile.tsx`，那里搜不到）：`isSelf` 假分支渲染，`isDeleted` 时**整块不渲染**。`isFollow` = `publicUser.is_followed`（`useProfile.tsx:219-221`），来自 `/user/get/public` | 是**toggle 单端点**（同一个 path 关注/取关，靠后端翻转），不是两个。文案 `Follow`（带 `+` 前缀）↔ `Following`。成功后 RN **重拉 `/user/get/public` + stats**（`handleFollowUser` 里两个 mutate）——壳照此重拉而非本地翻转，否则 followers 数不动 |
| 埋点 | `page_exposure`（`profile`，自己+他人两处都发）。他人主页的 `refSource` 是 `other_tab`，自己是 `profile_tab`（`user-profile.tsx:95,178`）；`page_exposure` 另带 `entry_type`（他人 `stack` / 自己 `tab`）与 `is_self`（`:201-203`） | 两处都发但参数不同轴，别只发一个 |

#### ChatList（Tab4，约 3.7k）

| 维度 | 实测真值 | 原生实现要点 |
| --- | --- | --- |
| 数据源 | `/user/chatted/list`（`axiosAuth` → REQUIRED，`page`/`size=50`/`language_code`/`need_total: true`），`useUserChattedList`(110) + `userChattedListPagination`(30)。**LV 徽章是第二个接口**：`/user/character/relationship/batch_get`（`axiosAuth`，批量 `character_ids`），且参与首屏 ready 判据（`chatListFirstInteractive.ts`）；`RELATIONSHIP_LEVEL_UPDATED` 事件触发重拉 | 徽章晚到只更新徽章位、不整列重配（§8.4 的「晚到 banner」同型）。徽章显示是**双开关**：`user.relationshipSwitch` && item 的 `is_relationship_open`，mini_phone 条目不显示 |
| 操作接口（2026-08-12 逐个核实补齐） | `/user/chatted/{pin,unpin}`、`/user/chatted/{character,story,game}/delete`（character 删除带 `chat_mode` + `conversation_id`——小手机对话级精确定位，漏传会误删同角色其它入口）、`/user/chatted/update_push_message_view_time`（点击 `is_push_message` 条目时消红点）、铃铛未读 `/message/notification/get_unread_status`（POST，带 `platform`: `ios`/`google_play`/`apk` —— ⚠️ RN 的 SWR key 写的是 `/system_message_notification/read_status`，那是**缓存键不是端点**，照 key 实现会 404）。全部 `axiosAuth` → REQUIRED | pin/unpin 是**成功后**本地重排（pinned 组按 `latest_time` 插入）+ Toast；delete 是真乐观（先移除后调 API，失败 mutate 恢复）。**需 mutation generation**：在飞旧响应不得复活已删行（§4.4） |
| 删除的跨界一致性 | RN `multi_cinema_round_cache.ts` 已就绪壳共享键契约：`multi-cinema-conv-epoch:${characterId}`（RN 默认 MMKV 实例；iOS 壳 `ChatListViewController.performDelete` 删除成功后写时间戳，RN 影院缓存写入时快照、读取时比对不一致即失效） | **壳删除会话成功后必须写同一键**（RN 侧零改动）。不写的后果：删会话后 seq 归零重开，旧影院轮缓存 seq 恒大于新会话，重进多角色影院**假命中旧剧情** |
| 双视图 | `ChatGrid`(531) + `ChatMap`(562)。Map 是「時光長廊」廊道视觉。视图偏好 `chatPageType` 在 `config-persist-storage` 信封 | **不是地图，别去选地图 SDK**——已核实 `ChatMap.tsx` 只用 Reanimated(`interpolate`/`useAnimatedStyle`/`withTiming`) + `expo-image` 做滚动驱动的透视廊道，`package.json` **无任何 map/mapbox/amap 依赖**。Android 对应实现 = Compose 自绘 + `graphicsLayer` 变换，不涉及 SDK/API key/区域合规。`chatPageType` 壳可写（本地偏好，merge 写同 gender；继承进度文档 §2.23.1 信封缺失问题）。Map 分组标题：`formatChatMapTime` 返回裸 `Today`/`Yesterday`，但**消费点 `ChatMap.tsx:355` 对这两个值包 `t(key)`** —— 分组标题走翻译（2026-08-13 订正：先前误记为「裸英文不走 t」，那是把返回值层当成了展示层）；月份名（`D MMMM`/`MMM D, YYYY`）走 dayjs locale。⚠️ `Today`/`Yesterday`/`Chats`/`Story` 四词条**不在 SHELL_KEYS**（iOS 壳未迁 Map 视图），P2 需加词条 + bump submodule |
| item | `ChatListItem`(668) + `ChatItem`(297)：LV 徽章 / streak / 未读红点 / html 型分流；名字过 `maskTextWithPlatform`（**GooglePlay 渠道**敏感词替换，壳已有 `HomeText.kt` 先例）；最后消息过 cinema XML 转换（`lib/cinema`，列表只需纯文本剥离） | 点击**只透传判定素材**：`chatEnterSource`/`isStory`/`characterType`/`contentType`——html/影院分流由 ChatDetailSurface 挂载时 `resolveInitialParams` 自决，**壳不复刻 `resolveChatEntryMode`**。⚠️ **订正**（2026-08-17，进度文档 §2.36）：原文「协议对齐 `useChatNavigation.ts` 壳分支的 bridgeParams」把四个素材说成同一通道，实际是 **2+2** —— `chatEnterSource`/`isStory` 走顶层 props（`ChatDetailSurface.tsx:356,378`），而 `characterType`/`contentType` **必须进嵌套 `preload`**：`resolveInitialParams` 读的是 `preloadState.*`（`:377-378`，由 `seedChatPreloadFromShell` 从 `props.preload` 灌入），`props.characterType` **全仓零命中**。平铺的表现是 html 富文本与多角色影院**一律落普通聊天页**，两端都不报错。且必须传**数字**（RN 用 `=== 1`/`=== 2` 严格比较，`"1" === 1` 为 false）。另：ChatList 的 `isStory` **只看 `item_type === 'story'`**（`ChatListItem.tsx:286`），不含角标那条 `character_type === 2`，否则多角色角色看不到影院。mini_phone 条目 → `MiniPhoneChat(characterId, parent_conversation_id)`；game 条目 → SimulatorGame WebView（不迁，对齐 Home World 的明确记日志） |
| 跨容器刷新 | `CHATTED_LIST_REFRESH` 事件的发送方全在 ChatDetail 深栈（发消息/重开会话/翻译后让列表重拉），JS 进程内 eventEmitter **跨不过 Surface→原生页边界** | 原生对应 = Surface 返回 / Fragment 重新可见时标脏重拉；另按常驻 Fragment 纪律 didLogin 重拉 / didLogout 只清数据 |
| 站内信 | `letter.tsx`(497) + `letter-detail.tsx`(343) + `LetterItem`(594)，入口是顶栏铃铛 → `NotificationStack` | **建议留 `NotificationSurface`**，减 1.4k；铃铛点击走 `AppRoute.Letter` |
| 缓存/预取 | `useChatListCache`(88) | 启动后台预发 page 0（仅已登录、一次启动一次）；指纹只比 authScope，**语言不比** |
| 草稿 | **可读**：MMKV key `chat_draft_lru`（`PersistLRU` 走同一 `storage`，值是 `lru-cache` 的 `dump()` JSON，容量 100）。~~iOS 至今未做草稿展示~~ **已过时（2026-08-12 核实）**：RN `ChatGrid` 现用草稿参与排序（置顶 > 草稿 `updatedAt`/`latest_time*1000` 降序）并行内展示（`[Draft]` 橙色前缀 + 无文本时显示 `Image` + 时间取草稿时间），mini_phone 条目除外 | 壳按该 key 解 lru dump 即可（**只读**，写方仍是 RN ChatDetail）。注意这是 LRU 转储格式（`[[key,{value,...}]]`），不是普通对象；**还有 legacy 纯字符串条目**要兼容（`getChatDraft` 读时迁移）。Android 对等实现必须含草稿排序与展示 |

#### Search（约 2.5k）

| 维度 | 实测真值 | 原生实现要点 |
| --- | --- | --- |
| 数据源（含 axios 实例，已逐个核实） | `character_search` / `user_search` / `character/suggest` / `popular_search_terms/app` 走 **`axiosPublic`**；`recent_history` / `clear_history` 走 **`axiosAuth`** | **前四个必须用 `OPPORTUNISTIC`,不是 `NONE`**——`character_search` 带 token 才会把词记入最近搜索（iOS 错用 `authorized:false` 致历史恒空）。后两个是 `REQUIRED` |
| hook | `useSearch.ts` 495 行（单文件承载全部逻辑） | 一个 ViewModel 对应即可 |
| 组件 | `FilterDrawer`(292) + `CharacterResultList`(231) + `SearchTagBar`(214) + `CreatorResultItem`(163) + `RecentSearch`(125) + `SuggestTags`(104) 等 10 个 | **分两包**：P1 是搜索主链路（结果/最近/热门/建议/空态）；`FilterDrawer` + `SearchTagBar` 与性别/排序/分级筛选留 P2（2026-08-13，§2.31）。P2 实测（2026-08-14，§2.34）：底部按钮是 **`Reset` + `Done`**、抽屉标题是 **`Sort by`**；`SexList` 的第四项是 **`Non-binary`**（带连字符，与 Home 的枚举写法不同）；content rating 是**三重 gating**（android && !GooglePlay && nsfw），不显示时**固定提交 `All`** 而不是不发键；标签栏排序 `deriveResultTagOrder` 需要 `sort_order` **原始值** + 配置顺序 + 「特殊呈现」判定（含一张 6 条 legacy 名称表），壳现有 `HomeTag` 丢掉了 `sort_order`，要扩字段 |
| 已知缺陷 | 搜索接口内嵌 `tags` 是瘦身版，`watermark_url` 恒空串 → RN 搜索页显示不出活动水印 | iOS 侧做了增强（按 tag_id 查全局配置回填）。~~Android 决定是否跟进~~ **已定：P1 不跟进**（2026-08-13，§2.31）——壳侧 Home/Search 都还没有水印渲染，跟进 iOS 增强等于先补一套全局 tag 配置查表，属独立包；**与 RN 行为对等（都不显示）**，不是新增缺陷 |
| 空态 | `searchEmptyState.ts` + 已有测试 | 有现成测试 |
| 埋点 | `search_trigger_page_exposure`、`search_result_page_exposure`、`search_content_exposure`（去重）、`search_content_click`、`character_page_{click,exposure}`；`searchWay` = `search`/`recent_search`/`popular_search` | — |

#### Screen（Tab1，约 5.3k + video API 559，**放最后**）

| 维度 | 实测真值 | 原生实现要点 |
| --- | --- | --- |
| 数据源 | **按 AB 二选一**：`/character_distribution/list`（distribution）或 `/recommend/home/list`（recommendation），`src/apis/screen.ts:10-11`。走 **`axiosPublic`** → `OPPORTUNISTIC` | AB 分流逻辑必须对齐，否则推荐数据不可比。⚠️ **两处订正**（2026-08-14，§2.35）：① `page_size` 是 **TS 形参名**，实际请求体发的是 **`size`**（`screen.ts:36`）—— 原文「参数名与 Home 的 `size` 不同」读反了，两者线上同名；② **AB 分流是 Android 专属且要求已登录**：`Platform.OS !== 'android'` 恒走 distribution，`ownerUserId` 为空时 `resolveConfigsForCurrentOwner` 直接返回 `{}` → 游客也恒走 distribution（`abConfig/service.ts:23-27`）。flag key `enable_recsys_in_home_show_case`，bundle `tipsy-chat-app` |
| 媒体三形态 | `media_source_type`：`animated_image` → `gif`、`static_image` → `single_character`、其余 → `showcase`（`tracking.ts` 实测的 `getHomeCardType`） | 映射直接影响埋点 `card_type` |
| 支撑文件 | `layout.ts`(89) + `feedMediaItemAdapter.ts`(57) + `chatBackgroundPrefetch.ts`(35) + `useShowcaseFirstScreenCache.ts`(76) + `useShowcaseNextItemCache.ts`(71) + `recommendationAttribution.ts`(69) | **`recommendationAttribution` 与 `showcaseFirstScreenFeed` 都有现成单测**（92 + 53 行）——直接作为 Kotlin 侧对等 fixture |
| 播放器 | 按设备内存定池大小；±1 借还 | Media3 ExoPlayer + **有界** preload manager。`largeHeap`(§2.3) + 有界池 + 图片内存上限**三件套必须同时到位** |
| OOM | 现网已有崩溃（`withAndroidLargeHeap.js` 注释记录 `ExoPlayerImplInternal.shouldContinueLoading`） | **首要风险**。Macrobenchmark 进 gate |
| 埋点 | `home_session_start`/`home_session_end`（uuid 会话）、`home_card_exposure`（会话内去重）、`home_card_{like,comment,share}_click`、`home_input_click`（一会话一报）、`screen_recommend_attribution_missing` | 最后一个是诊断事件，说明归因会丢，要保留 |
| CTA | `transitionSource: 'big_screen'` + `sourceType: 'first_tab'` | ⚠️ **订正**（2026-08-14，§2.35）：原文「进聊天**恒**普通聊天页（不走 html 分流）」**不成立** —— 实测走 `resolveChatEntryScreen` **四路分流**（`screen.tsx:655`）：`isStory`/强制 → `ChatDetailPage`；`characterType===1 && contentType===2` → **`ChatDetailHtml`**；非 INTERACTIVE → `ChatDetailPage`；否则 `characterType===2 ? MultiCinema : Interactive`。Screen 传的 `chatMode` 恒为 `INTERACTIVE`、`isStory: false`，所以四路都可达。`chat_mode_lru.test.ts`(143) 是现成 fixture |
| 二期可后置 | 动图 WebP 动画、fade 转场 + 预载、点赞增强（初始 `is_liked` 预拉 / echo 对账 / 动画）、分享增强 | iOS 至今仍在二期清单 |

#### Login + Settings 列表 + 语言页

| 维度 | 要点 |
| --- | --- |
| Login 登录方式（已核实，**不是"待审计"**） | 6 个端点：`/login/firebase`（社交，走 Firebase Auth）、`/login/email` + `/login/email/send_code` + `/login/email/did_not_get_code`（邮箱验证码）、`/login/password`（`usePasswordLogin.ts` 仅 38 行，配 `InternalLoginForm` = 内部/测试入口，**上线前确认是否对外可见**）、`/auth/refresh_token`。社交按钮只有 **Google + Apple**（`LoginSocialButtons.tsx` 只有 `onGooglePress`/`onApplePress`，**无 Facebook/Discord 按钮**——`app.config.js` 里的 fb/discord scheme 是「跳出到社交 App」用途，别误当登录方式）。**Apple 按钮在两个文件里都没有 `Platform.OS` 门控**，Android 上的展示策略需产品确认 |
| Login 页面构成 | `LoginScreen.tsx` + `EmailCode` + `AgeVerifyScreen` + `ProfileSetupScreen` + `LinkAccountModal` + `components/{LoginSocialButtons,LoginEmailForm,InternalLoginForm}` + `hooks/useLoginFlowState` | 年龄验证与资料补全是登录链的一部分，别漏 |
| Onboarding（starter-picks，2,364 行） | `src/login/starter-picks/`：`StarterPicksFlow` + `OrientationStep`/`TagSelectionStep`/`CharacterSelectionStep` | **留 `OnboardingSurface`**（RN 已有该 Surface）。壳按 `onboardingStatus` 拉起，完成后经桥回执 |
| 停止条件 | 缺 Firebase / Google OAuth 的 **Android 签名指纹**（三个 applicationId 各一套）使真实登录无法验证 | 这是 W2 的硬前置，见 §12 |
| Settings 列表 | `page.tsx` 430 行。列表行序与渠道 gating 由壳控制；子页全部经 `SettingsSurface` |
| 语言页 | `language.tsx` 136 行。**要原生实现**（不是「不迁」—— 2026-08-14 订正措辞：原文「刻意不迁」指的是**不由 RN 承载**，被读反过）。已核实 `SettingsSurface.tsx:34-44` 的 `KNOWN_SCREENS` **刻意不含 `Language`**，注释写明「语言页原生：壳是语言唯一写入者」；iOS 对应物是原生 `LanguageViewController.swift`。所以 Settings 那一刀**必须连语言页一起做**，否则壳内没有任何入口能改语言。可选集合 = 服务端 `/supported_languages` ∩ 26 个客户端支持码（§4.8）；写入走 `POST /user/set_language` **并回写 `user-storage` 信封**。⚠️ 原文「不经 Zustand 信封」**是错的，且正是一个真实缺陷的根因**（2026-08-18 订正）：壳在每次 Surface 容器出栈时从该信封读回语言并覆盖当前值，只写服务端会让刚选的语言被旧值倒灌回英文。**壳是语言唯一 writer ⇒ 信封镜像也必须由壳维护**，读写方向必须成对。落地与证据见进度文档 §2.38 |

### 8.2 现成的对等 fixture 来源（省掉大量猜测）

`tipsy-app` 里有 **51 个测试文件、约 4,500 行**，其中很多**直接编码了业务规则**。迁移对应页面时，这些是最可靠的对等真值来源——比读组件代码快，比问人准。**Kotlin 侧照着写同名测试即可获得可比对的行为断言。**

| 迁哪个页面/能力 | 直接可用的 RN 测试 |
| --- | --- |
| **Screen** | `app/screen/recommendationAttribution.test.ts`(92)、`showcaseFirstScreenFeed.test.ts`(53)、`lib/screenRecommendationTracking/`（`manager` 384 + `models` 214 + `homeTracking` 215 + `exposureTracker` 110 + `queue` 97 + `retry` 20 = **1,040 行**） |
| **Search** | `app/search/searchEmptyState.test.ts`(67)、`components/search/searchTagOrder.test.ts`(144)、`searchInputInteraction.test.ts`(25)、`filterDrawerLayout.test.ts`(11) |
| **Profile** | `hooks/profile/profileCreatedListCache.test.ts`(129)、`lib/profileCreationTracking.test.ts`(162)、`analytics/characterCreateAnalytics.test.ts`(161) |
| **Home / 推荐埋点** | `lib/recommendTracking/`（`queue` 89 + `invalidBatchIsolation` 96 + `chatSession` 77 + `feedMetadata` 72 + `bigScreenContext` 63 + `retry` 45 + `interactionStatus` 22 = **464 行**） |
| **ChatList** | `hooks/chat/activeConversation.test.ts`(65)、`store/chat_mode_lru.test.ts`(143) |
| 渠道 / 环境 | `utils/lane.test.ts`(143)、`store/lane.test.ts`(59)、`apis/auth.lane.test.ts`(84)、`constants/api.test.ts`(27) |
| **RuStore 支付** | `lib/RuStoreReactPay/index.android.test.ts`(106) —— **Android 专属**，迁支付相关时必读 |
| 内容分级 | `utils/nsfwPolicy.test.ts`(92) |
| Surface 参数 | `surfaces/chatDetailCinemaInitialParams.test.ts`(54)、`onboardingStage.test.ts`(87) |
| 工具 | `utils/coin.test.ts`(96)、`formatNumbers.test.ts`(26)、`richContentPlainText.test.ts`(20) |

> 注意：这些 suite 在固定 SHA 上**并非全部可跑通**（已知有 Vitest 无法解析 Flow、缺 `jest` global 一类的环境问题，属环境而非业务逻辑错；确切范围待 W0 实跑）。**读测试内容取业务规则是安全的；但不要以"RN 测试全绿"为前提**，也不要在 Android packet 里顺手修 RN 测试环境。

### 8.3 RN Surface 启用顺序（13 个 component）

`index.surfaces.js` 注册 13 个 component，但**其中 `DebugSurface` 永远只用于 debug/CI 回归**——不进 production route、不计入 production smoke、不进商店功能清单。**CI 需断言 `DebugSurface` 不出现在 release route 表里。**

其余 12 个业务 Surface 按依赖与风险递增启用（每个独立填 §9.1 矩阵一行）：

| 批次 | Surface | 时机 | 备注 |
| --- | --- | --- | --- |
| 0 | `DebugSurface` | W0 | 管线 gate，保持常绿；零业务依赖，用于二分「挂载层 vs 业务 import 链」 |
| 1 | `ChatDetailSurface` | W1 | **第一个真实业务 Surface**，也是最重的一个（SSE、WebView DOM、媒体、深微栈）。它过了说明宿主可用。✅ **已启用**（进度文档 §2.36，2026-08-17）—— 微根 18 项机器断言 + 桥桩回填 + 判定素材透传；§9.1 真机十项待冒烟 |
| 2 | `CreateSurface` | W2 | 五 Tab 的 Create 伪 Tab 落点；编辑走原始 JSON 透传（§8.1 Profile 行） |
| 3 | `CommentsSurface` / `SettingsSurface` / `EditProfileSurface` | W3 | 分别由 Screen/Settings 列表/Profile 入口触达 |
| 4 | `NotificationSurface` / `GemsSubscriptionSurface` / `UserCoinsSurface` | W4 | 涉及支付与站内信，需渠道分流验证 |
| 5 | `OnboardingSurface` / `RoleCardSurface` / `WidgetSurface` / `DeleteAccountSurface` | W4 | Onboarding 需 auth 完成回执且只执行一次 |

**未过 §9.1 矩阵的 Surface 不接生产入口。** 路由到未启用的 Surface 必须给出明确错误或安全 fallback，**不做 silent no-op**。

> 微栈死链检查（iOS 踩过）：`RoleCardSurface` 缺 `CreateStack` 时，换头像子流程直接死链。启用每个 Surface 时都要枚举它内部 `navigate` 的全部目的页，确认要么在微栈内、要么有桥出口。

### 8.4 列表更新纪律（iOS 花了整月修的，五个页面全适用）

- **禁止全量替换**。iOS 用 `reloadData` 换数据导致「全屏卡片重配、动图封面齐跳第 0 帧」，后来改成锁定头 + `performBatchUpdates` 增量。Android 从第一天就用 stable key + `LazyColumn` key / DiffUtil。
- **增量更新也必须有版本/乱序 guard**。iOS 踩过的具体时序（Android 必须覆盖同一组失败用例，机制不必照抄）：
  1. **晚到的 banner**：banner 接口恒比首屏 feed 慢数秒，一次性刷新会让全屏可见卡片重配。
  2. **语言 settle 后重拉**：账号语言 ≠ 设备语言时，冷启动数秒后 `restoreSession` 才 settle，触发换 session 强拉——与 banner 晚到**同窗**，iOS 曾误判成 banner 引发，查了两轮。
  3. **翻页去重后的空页**：For You 新 session 的任意一页都可能返回旧 session 已显示的角色（iOS dev 实测每页 1~3 条重复）。全量去重后若无新 item，不会再触发下一次加载 → **必须主动续拉**，且要限次（iOS 限连续 3 页）防异常数据形成请求循环。
- **曝光去重集合与列表更新解耦**：锁定/复用的 item 不重报曝光。
- **翻页不换 session id，筛选/刷新才换**（§8.1 Home 行）。

### 8.5 波次计划

原则：**首个波次必须是可运行的完整垂直切片**（iOS 首个 shell commit `e3ec7e6` 就是 Tab + Router + Login + Home + Surface 一起落地的约 99 文件/1 万行，事后证明是对的）。

| 波次 | 内容 | 业务/基建比 |
| --- | --- | --- |
| **W0** 地基 | 工具链降级（§3.3）+ Gradle 布局（ADR-004）+ 三 flavor 骨架 + 6 个 plugin patch 移植 + `TipsyApplication`/`MainActivity`/`RNSurfaceFragment` + `DebugSurface` gate + CI | 纯基建。**目标是尽量短** |
| **W1** 契约 | `tipsy-auth` Android 桥 + token 迁移（含三 flavor 真机覆盖升级）+ auth/mutation 双 generation + network 三鉴权模式 + i18n + Router + root side-effect 清单 + Sentry + **`ChatDetailSurface` gate**。**RN 侧只需补桥实现**——其余 55 个文件的壳适配已存在（§7.2） | 基建为主，但 `ChatDetailSurface` 是真实业务 gate |
| **W2** 垂直切片 | Bootstrap 状态机 + 五 Tab shell + **Login** + **Home 完整对等** | 业务为主 |
| **W3** 页面主体 | **Profile**（12.6k，最大）+ **ChatList** + **Search** + Settings 列表/语言页。feature 可并行，集成串行 | **纯业务，工作量最大的一波** |
| **W4** Screen + 系统 | **Screen/Media3** + 全量 12 个业务 Surface 逐个过矩阵（EditProfile 在 W3 预接，但生产验收仍在本波）+ Push/deep link/Widget/voice/营销 SDK + OTA 三重隔离 | 业务 + 系统能力 |
| **W5** 切换 | 全量对等/性能/无障碍/nightly + 三渠道覆盖升级 + staged rollout + 向前恢复演练 + last-known-good 归档 | 发布 |

> **W1 的执行级细化见 [`android-w1-plan.md`](android-w1-plan.md)** —— 任务依赖链、
> 外部阻塞项、每步的实测约束。本节只给波次边界与判据。

**W0/W1 的时间盒纪律**：这两波是纯前置，**不产出用户可见价值**。它们的目标是"够用就往下走"，不是"做完美"。具体判据：
- W0 只要三个 flavor 能装、能开 `DebugSurface`（Metro + 离线）就进 W1。**不在 W0 做 CI 分层的 nightly/release gate**——那些等有产物再建。
- W1 只要 auth 不掉登录、`ChatDetailSurface` 过矩阵、root side-effect 表零 `UNKNOWN` 就进 W2。**不在 W1 做完整的存储 registry 与对等矩阵**——按页面波次增量填。
- 若 W0+W1 超过总工期的 1/4，停下来复审是否在做过度设计。

**W0 停止条件**：autolinking 在符号链接 + `projectRoot` 覆盖后仍无法解析；Kotlin/AGP 降级后 `modules/widget` 的 Compose 插件冲突无解。
**W1 停止条件**：覆盖升级 auth 失败或出现跨账号数据；`AuthBootstrapSurface` 无法可靠读出历史 token。
**W2 停止条件**：登录方式/World/content rating 的渠道规则无法从源码或产品 owner 确认；Native Home 与 RN 的 API/埋点语义无法对齐需后端改动。

**排期**：本文不给人月数。iOS 用约两个月做到 Wave 1+2（3.2 万行 Swift）。Android 有三渠道 + 桥从零 + 无 Keychain 等值物三项额外成本，但少了"摸索边界"的成本。**排期在 W0 结束、autolinking 与工具链的未知量消除后再定**，且按 §8.0 的 32.6k 行业务量估，不按基建量估。

---

## 9. 验收矩阵

### 9.1 Surface 验收（每个 Surface 启用前填满一行）

| Surface | 初始 route fixture | 未登录 | 登录切换 | 语言切换 | Back/栈底 | 旋转/进程恢复 | 首帧 | 50 次泄漏 | Embedded | OTA N/N-1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| DebugSurface | W0 | N/A | N/A | — | ✎ | ✎ | ✎ | ✎ | ✎ | W4 |
| ChatDetailSurface | ✅ W1 | ✎ | ✎ | ✎ | ✎ | ✎ | ✎ | ✎ | ✎ | W4 |
| CreateSurface | W2 | ✎ | ✎ | ✎ | ✎ | ✎ | ✎ | ✎ | ✎ | W4 |
| EditProfileSurface | W3（仅预接，生产关闭） | ✎ | ✎ | ✎ | ✎ | ✎ | ✎ | ✎ | ✎ | W4 |
| 其余 9 个 | W4 | — | — | — | — | — | — | — | — | — |

未填满的行**不得**标 production-ready。

> **`ChatDetailSurface` 的填表进度不写在这里** —— 见进度文档 §2.36（实现、
> 微根机器断言、桥桩回填）与 §2.37（§9.1 冒烟的逐项结果）。本文只定规则：
> 路由已进生产白名单，但**这一行填满前不得标 production-ready**，
> 且模拟器证据不算 —— 见进度文档 §2.5。
>
> **`CreateSurface` 同样已放开路由但未填表**（进度文档 §2.40，2026-08-18，
> Tab3 的 ➕）。§2.41 已补微根、root stack、10 个实际微栈目标、注册名与
> `hydrateTags` 前置的机器断言，关闭此前“只有人工比对”的静态 gate 欠账。
> ⚠️ 这仍不等于 §9.1：设备生命周期矩阵未填满前不得标 production-ready。
>
> **`EditProfileSurface` 已按 W3 预接静态契约/测试源码、账号隔离与 Profile 刷新接力**
> （进度文档 §2.43），但相关测试并未执行、生产 policy 仍关闭，
> 该行 8 个设备/生命周期验收格仍全 `✎`。这解决“W3 还是 W4”
> 的批次歧义，不改变“未填满矩阵不得启用”的门槛。

### 9.2 页面/横切能力对等（十类证据）

只有同时满足以下十项才可标 `PARITY_VERIFIED`：

1. 固定 RN SHA 的源文件、**实际 route 注册**（不是 `type.ts` 里的陈旧声明）、API 与隐藏副作用审计。
2. Native/Surface 归属与明确非目标。
3. 脱敏 API fixture、decoder/error policy 测试。
4. loading / empty / error / offline / pagination / refresh / auth 切换 / 进程生命周期。
5. route / deep link / push / back 的 Given-When-Then。
6. analytics 事件名、触发次数、payload、source/session context 与 RN 对拍。
7. 稳定 testTag / contentDescription 与自动化映射。
8. screenshot 验收 + TalkBack / 字体缩放 / 触控区域。
9. 性能、内存、crash/ANR 与旧 RN baseline 对比。
10. rollback / kill switch，或保留 RN route。

### 9.3 性能预算的建立方式

**W2 前先测旧 RN 现网包基线，再冻结数值。本文不虚构毫秒数。**

至少测：cold/warm start 到可交互；Native Home 首内容 与 RN Surface 首帧；Screen 滚动 frame time/jank、播放器切换、预加载命中率；30/50 次 Surface 开关后的 PSS/Java/native heap 与 **Runtime 数量**（应恒为 1）；后台恢复/旋转/进程重建；APK/AAB size 与每 ABI 增量。

Native 不得显著劣于旧 RN 基线。若为稳定性接受已知性能差异，必须有测量、原因与批准记录。

### 9.4 无障碍与 test ID

**从第一个组件就做，不后补**（iOS 到后期才批量补约 295 个 accessibility ID，`51eba61`/`a0a1502`）。稳定 ID 最低集合：

```
android.login.{root,email,code,submit}
android.tab.{screen,home,create,chatlist,profile}
android.home.{root,refresh,feed}
android.home.card.<stable-id>
android.state.{loading,empty,error}
```

动态 ID 必须脱敏且稳定，**不把用户文本拼进 tag**。

---

## 10. 风险登记

| 风险 | 等级 | 对策 |
| --- | --- | --- |
| **三渠道覆盖升级掉登录** | **高** | §2.4 迁移算法 + §6.1 矩阵；W1 就在真机三 flavor 验，不留到最后 |
| **Root side-effect 失踪**（营销 SDK/推送/埋点静默不初始化） | **高** | §4.2 清单 W1 填满，`UNKNOWN` 阻塞 W2。iOS 真实事故，Android 有更多 SDK |
| **config plugin patch 漏移植** | **高** | §2.3 逐项表 + merged manifest snapshot 测试。漏一个是静默失效（largeHeap 漏 → Screen 波次 OOM；textViewStyle 漏 → 全局排版差异） |
| **Gradle/autolinking 在非标准布局下不闭合** | **高** | ADR-004 两个稳定支点（符号链接 + `projectRoot`）；W0 第一优先验证。有 iOS 同构先例 |
| **工具链版本不兼容**（AGP 9.2.1/Kotlin 2.2.10/compileSdk 37 vs RN 要求 8.11.0/2.1.20/36） | 高 | W0 第一件事降级对齐；`modules/widget` 会读 `rootProject.ext.kotlinVersion` |
| **OTA 包串通道**（完整 App bundle 下发到壳） | **高** | §5.3 三重隔离；channel 白名单；rtv 用 `android-bridge-N` 与 appVersion policy 永不同名 |
| Screen/Media3 OOM 与 ANR | 高 | largeHeap + 有界 preload + 图片内存上限；放 W4 做；Macrobenchmark 进 gate |
| **`tipsy-auth` 改动影响三渠道现网包** | 高 | provider 模式保证无 provider 时 `isShellHost()` 恒 false；改动走 additive RN PR + 现网回归 |
| RuStore flavor 隔离（现网是 Google+RuStore 混装） | 中高 | flavor source set 隔离；不复制字符串替换；记录「现网混装」为已知差异 |
| 列表全量替换导致滚动/曝光/播放状态重置 | 中高 | stable key + 增量更新 + 版本/乱序 guard（§8.4） |
| 登出竞态：在飞旧请求 200 返回后无守卫写回 | 中高 | auth generation + mutation generation 双轨（§4.4） |
| **`index.surfaces.js` 成为双壳共用入口** | 中高 | §7.2：改动需双壳回归。这是 Android 加入后的新耦合，iOS 文档未覆盖 |
| Surface 迟到事件关错实例 | 中 | instanceId 判定（ADR-003），不用类型判定 |
| i18n 三层集合混写 / 词条漏导出 | 中 | §4.8 三层表 + 导出脚本 + 非英文环境测试 |
| 标量类型漂移致整个响应解析失败并静默吞错 | 中 | 统一 serializer tolerant scalar + fixture；列表「无结果」先查解析异常 |
| intent scheme 劫持面（5 个泛用社交 scheme） | 中 | W1 逐条安全审计，不原样照搬 |
| Qt lifecycle listener 与 analytics 单一 owner 冲突 | 中 | W1 决策：保留 listener 或排除模块自管，二选一并写下 |
| 团队 Kotlin/Compose 与 Media3 技能 | 中 | W2/W3 配对开发；W4 前预研 Media3 |
| 文档漂移（多份「当前进度」互相矛盾） | 中 | 唯一状态真值 = `reference/android-native-progress.md`；本文只写决策不写状态 |

---

## 11. 明确非目标

- 首轮不全量移除 RN/Expo/Node 构建链。
- 不迁 Create、Comments、EditProfile、Settings 子页、ChatDetail 深栈。
- 不同时升级 Expo/RN/AGP/Kotlin。
- 不做 isolated AAR（W4 全绿后才允许独立 POC）。
- 不做逐页 native↔RN feature flag。
- 不把「isolated AAR / 全 Compose Navigation / DI 框架 / 模块数量」当成功指标。
- 缺真实签名或旧包时，不声称覆盖升级完成。

---

## 12. 开放问题（需决策才能推进）

1. **Qt lifecycle listener 归属**：`QtPackage.createReactActivityLifecycleListeners()` 在 Activity onCreate 就 `preInit`。壳保留它（承认 analytics 有两个初始化点）还是从 autolinking 排除 `modules/qt` 由壳自管？——影响 §4.2 与埋点对拍。
2. **OTA channel 命名与 flavor 维度**：三个 distribution × preview/production = 6 条 channel，还是用 `overrideConfiguration` 运行时注入单套 channel 名？后者更省，但需验证 EAS 侧的投递语义。
3. **QA 分发形态**：iOS 有独立 `ios-shell-qa` profile + QA bundle/API 隔离（`6dab348`）。Android 用哪种内部分发（Firebase App Distribution / Play internal testing / 直接 APK）？影响 W0 的 build type 设计。
4. **W2 的 Home 是否包含 World 系列**：`HomeSeries` 含 `World`（`src/constants/common.ts:38`），而 SimulatorGame 是 WebView 不迁。World 系列在 Home 里的原生呈现边界需产品确认。
5. **`AuthBootstrapSurface` 的可接受性**：它意味着某些升级用户首启会挂一次隐藏 RN Runtime（增加首启耗时）。是否接受，或改为「MMKV 无 token 即视为未登录」（更简单但会让部分历史用户被登出）？
6. **`com.tipsytavern_ai.app`**：Firebase 里有这个 client 但不建 flavor。它是什么、是否需要壳支持，需人工确认。
7. **凭据与敏感值**：`app.config.js` 与 `modules/qt/android/build.gradle` 内有 credential-like 字面量（Facebook client token、TikTok access token、QT app key），且 `modules/qt/android/local.properties` 被跟踪。壳工程只用无凭据 placeholder/CI secret；这些值的分类、历史暴露面与是否轮换需要安全 owner 结论——**在结论前不读取、不打印、不复制**。
8. **【阻塞 W2】Google/Firebase 的 Android 签名指纹**：Google 登录要求把**每个 applicationId × 每个签名证书**的 SHA-1 指纹登记到 Firebase/Google Cloud console。壳工程是新的 Gradle 工程 → **debug keystore 与现网 RN 包不同**，三个 flavor 各需登记 debug + release 指纹。**没有这些指纹，`/login/firebase` 在壳里无法真机验证**（§8.1 Login 行的停止条件）。谁来登记、能否复用现网 release 证书，需在 W2 开工前确认。
9. **Apple 登录按钮在 Android 上是否展示**：`LoginSocialButtons.tsx` 与 `LoginScreen.tsx` 都**没有 `Platform.OS` 门控**（已核实）。现网 Android 包是否真的显示 Apple 登录、以及壳内保持还是隐藏，需产品确认。
10. **`/login/password` 是否对外**：`usePasswordLogin.ts` 仅 38 行且配 `InternalLoginForm`，看起来是内部/测试入口。壳内要不要实现，需确认它在现网三渠道包里的可见性。

---

## 附录：参考

**iOS 侧文档**（`/Users/maoqi/Developer/Tipsy-iOS`）：
- `llmdoc/reference/ios-native-progress.md` —— iOS 状态权威 + 陷阱全集（§5，15 条）
- `llmdoc/architecture/ios-native-shell-migration-plan.md` —— 决策蓝图（注意 §3.2.2 的目录蓝图**从未落地**）
- `llmdoc/architecture/ios-packaging-ota-architecture.md` —— 打包/OTA 与 rtv 治理规则（§6）
- `llmdoc/architecture/create-rn-surface-contract.md` —— Surface 契约范式

**iOS 迁移复盘**：其结论已吸收进本文——功能归属见 §1.3、反模式见 §1.2.1（十条经验 + 十条反模式全文）、失败用例见 §10。不再依赖任何 git ref（原载体分支已废弃）。

**官方文档**：
- [Expo Brownfield overview](https://docs.expo.dev/brownfield/overview/) / [isolated approach](https://docs.expo.dev/brownfield/isolated-approach/)
- [RN Android Fragment 集成](https://reactnative.dev/docs/integration-with-android-fragment)
- [Compose in Views](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/compose-in-views)
- [EAS Update in existing native apps](https://docs.expo.dev/eas-update/integration-in-existing-native-apps/)

**关键源码位置**（核实用）：
- 桥模块：`tipsy-app/modules/tipsy-auth/{expo-module.config.json,src/index.ts}`
- Surface 入口：`tipsy-app/index.surfaces.js`、`index.surfaces.debug.js`
- auth 存储：`tipsy-app/src/store/{auth.ts,mmkv.ts}`、`src/lib/auth/{jwt.ts,gateway.ts}`
- 网络：`tipsy-app/src/utils/axios.ts`、`src/constants/common.ts`
- i18n：`tipsy-app/src/i18n/i18n-index.ts`
- 导航/深链：`tipsy-app/src/App.tsx:425-470`、`src/navigation/`
- 渠道：`tipsy-app/src/constants/app.js`、`app.config.js`、`eas.json`
- plugin patch：`tipsy-app/plugins/`（6 个）
- Android 兼容基线：`tipsy-app/node_modules/react-native/gradle/libs.versions.toml`
- autolinking 布局支点：`node_modules/expo-modules-autolinking/android/expo-gradle-plugin/expo-autolinking-settings-plugin/src/main/kotlin/expo/modules/plugin/ExpoAutolinkingSettingsExtension.kt`、`node_modules/expo-modules-core/expo-module-gradle-plugin/src/main/kotlin/expo/modules/plugin/gradle/ExpoGradleHelperExtension.kt`
- MMKV 路径：`node_modules/react-native-mmkv/android/src/main/java/com/margelo/nitro/mmkv/HybridMMKVPlatformContext.kt`
- SecureStore 实现：`node_modules/expo-secure-store/android/src/main/java/expo/modules/securestore/SecureStoreModule.kt`
- expo-updates Android：`node_modules/expo-updates/android/src/main/java/expo/modules/updates/UpdatesController.kt`、`node_modules/@expo/config-plugins/build/android/Updates.js`
