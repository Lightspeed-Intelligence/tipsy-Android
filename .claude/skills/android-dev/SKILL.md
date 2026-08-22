---
name: android-dev
description: >
  在 Tipsy Android 壳工程（Kotlin/Compose 原生壳 + tipsy-app RN submodule 的 integrated
  brownfield）里做任何开发时使用。提供任务→文档路由、六条不可协商的不变量、实测可用的
  Gradle 命令、以及本工程特有的静默失败陷阱清单。适用场景：写原生业务页、启用 RN Surface、
  加 tipsy-auth 桥方法、动 Gradle/flavor/manifest、动 auth/token/存储、改 i18n、
  bump submodule pin、判断某功能迁不迁、提交前自查。凡是会编译进这三个 APK 的改动都算。
---

# Tipsy Android 壳开发

这个工程的坑**绝大多数不报错**。它们表现为「线上掉登录」「改了 JS 却不生效」
「非英文用户静默看英文」「文字截断」「间歇崩溃复现不出来」。构建绿、lint 绿、
单测绿，都不代表对。所以下面的清单不是最佳实践建议，是**已经付过学费的事实**。

## 动手前：读哪段文档

`llmdoc/` 是唯一依据（`index.md` 是入口）。四份文档职责不同，别混：

| 文档 | 是什么 |
| --- | --- |
| `architecture/android-native-migration-plan.md` | 决策与约束（ADR、硬约束、契约、验收矩阵）。**§8 是主体** |
| `architecture/android-w1-plan.md` | W1 的执行级细化（每步的实测约束） |
| `reference/android-native-progress.md` | **状态唯一真值**（波次进度、横切能力、Surface 验收、未决问题；~250 行可整读） |
| `reference/android-packet-log.md` | **逐刀工程日志**（append-only）。「进度文档 §2.x」这类历史引用一律在此找同号小节；查某刀为什么这么做 / 踩了什么坑也看这里 |

按要做的事挑着读，不要通读：

| 要做的事 | 读哪里 |
| --- | --- |
| 写原生业务页（W2/W3 主要工作量） | 方案 §8.1 对应页面规格 + §8.2 fixture + §8.4 列表纪律 + §4.5 网络契约 |
| 启用某个 RN Surface | W1 §11.2 微根清单 + 方案 §4.3 Surface 契约 + §9.1 验收矩阵 |
| 加 tipsy-auth 桥方法 | W1 §2.4 演进纪律 + §3.6 主线程约束 + 方案 §5.3 代际判据 |
| 动 Gradle / flavor / manifest | 方案 §3.3 + ADR-004 + §5.1 flavor 矩阵 |
| 动 auth / token / 存储 | 方案 §2.1 + §2.4 + §4.4 + §4.6 + W1 §4 |
| 动 i18n | 方案 §4.8 + W1 §7（**两条解析规则，不是一条**） |
| 打包 / 发渠道 / 发 OTA | 方案 §2.2 + §2.3 + §5 + §6 |
| 判断某功能迁不迁 | 方案 §1.3 归属表 |
| 现在做到哪了 | 进度文档（**不在方案里**） |

无论做什么，先扫方案 **§2（Android 四条硬约束）**——那是照抄 iOS 会静默出错的四处。

**发现文档与代码不一致时，先修文档再继续实现**（进度文档 §7）。

## 谁拥有什么（方案 §4.1）

动手前先定位这次改的能力归哪一侧。**放错一侧的代码不会报错，会双写互踢。**

| 能力 | 壳模式 owner | RN Surface 能做什么 |
| --- | --- | --- |
| 生命周期 / 启动 | Native | 订阅必要事件 |
| login / refresh / logout | **Native（唯一刷新者）** | 读快照、请求动作；**不得自行 refresh** |
| HTTP | 原生页走 Native client；RN 页保留 Axios | 401/402 策略与 Native 对齐 |
| 语言 | **Native（唯一写入者）** | 初始 props + `onLanguageChanged` |
| 全局导航 / deep link | **Native Router（唯一入口）** | 请求 route，**不直接操纵 Native 返回栈** |
| Push token / 通知入口 | Native | 只处理 Surface 内目的地 |
| Analytics session / attribution | Native | 发业务事件并附 Native context |
| OTA bundle 选择 | Native | 只运行被选中的兼容 entry |
| Widget / voice service | Native | 经明确契约更新，不依赖 `App.tsx` 副作用 |
| `chat-persist-storage` / `chat-background-storage` | **RN Surface** | Native **不写** |

`isShellHost()` 为 true 后，JS **不得**读取/刷新/持久化 token。这是「唯一刷新者」的含义。

**auth 要两轨 generation，互不替代**：`authEpoch`（login/logout/换号自增，Repository
发请求前捕获、回写前校验）+ `mutationEpoch`（本地乐观变更如删除/置顶时自增，防在飞
旧响应复活已删行）。只做一轨会漏另一类 bug。

**常驻 Fragment 必须订阅登录态**：Fragment 常驻且只在首次加载拉一次数据，登录/登出若
只广播给 RN 桥，会「登录后无人重拉」「登出串上一账号数据」（iOS 的 Tab VC 踩过）。
约定：`didLogin` → 重拉身份相关数据；`didLogout` → **只清账号私有数据、不发请求**。

**`notifyServerAuthRejectedForToken(token)` 必须带 token 参数**——只有被拒 token 仍是
当前 token 才登出。回退到无参版本会让旧账号迟到的 401 误登出新账号。

## 六条不变量

违反其中任一条的后果都在线上，不在本地。

### 1. `isShellHost()` 在没有壳 provider 时必须返回 `false`

`tipsy-app/modules/tipsy-auth` 会被 autolink 进**现网三个 RN 包**——那里没有壳、
不注册 provider。此时若 `isShellHost()` 返回 `true`，现网 App 会把 auth 交给一个
不存在的壳 → **直接掉登录**。这是本工程最高危的失败模式。

module 本身只是空壳委托，provider 注册由壳的 `Application` 完成。守这条的测试是
`LiveAppSafetyTest`，它在 G1 CI 里，**且断言 skipped=0**（`assumeTrue` 跳过在
JUnit 里算通过，等于断言静默失效）。

### 2. 新增桥方法的 JS 调用一律 `TipsyAuth.xxx?.()`

同一 OTA 代际横跨多个 store 版本，OTA 会把新 JS 推给代际内**最老**的二进制
（没有该方法），**且这个状态不随发版自动消除**。模块级判空拦不住——要判的是方法级。

JS 要无条件调用新桥方法，唯一途径是升 runtime 代际（`android-bridge-N`）。
升代判据见方案 §5.3。

### 3. 触碰 UI / Router / FragmentManager 的桥方法必须切主线程

Expo 的 `AsyncFunction` **默认在后台线程执行**。契约标 `@MainThread`，桥侧统一经
`dispatchOnMain` 切换，不要下推给壳侧实现（每个实现都得记得做一次，漏一个就是一个
间歇 bug）。

- 用 `withContext` 而非 `Handler.post`——后者发射后不管，JS 的 `await` 会在导航
  真正发生前 resolve。
- **未标 `@MainThread` 但内部要动 UI 的方法要自己切**（`logout()` 就是：主要做存储
  清理，但要收栈）。桥的 `onMain` 只覆盖标注过的方法。

漏掉的表现是**间歇** `CalledFromWrongThreadException` 或导航丢失，取决于 JS 调用落在
哪个线程——本地基本复现不出来。

### 4. 状态只写进度文档一处

不在方案、W1 计划、代码注释、PR 描述里复制「当前进度」快照。重复的进度是 iOS 侧
真实发生过的漂移源（同一文档记过两个不同的 submodule pin）。

### 5. RN 侧改动提交到 `tipsy-app` 的 `feat/android-native` 分支

Android 迁移相关的 RN 改动**不走 PR**，直接提交该分支（2026-08-11 owner 决定）。
本仓 PR 里的 submodule 变更**只允许是指针 bump**——改动本身要在 `tipsy-app` 的历史里
可追溯，不能以「本仓顺手改了 submodule 工作树」的形式存在。

⚠️ 该分支**未合进 `main`/`release`**，靠子模块指针引用。bump pin 前确认目标 commit
**已推到远端**，否则 CI 拉不到。

`index.surfaces.js` 是 iOS 壳与 Android 壳**共用入口**，改动需双壳回归。

RN 侧改动同时受 `tipsy-app/AGENTS.md` 约束，其中两条是**跨仓契约**不是风格建议：
- **i18n**：不硬编码用户可见文案（JSX 内 `t(...)`、JSX 外 `i18nKey(...)`、
  确不翻译的加行内 `i18n-ignore`）；**既有翻译 key 默认禁止改名/删除**；
  strict locale 只是抽样目标，非 strict 语言也要真翻译、不得拿英文凑数。
- **testID**：新交互元素必须带（dot-camelCase + 类型后缀，如 `gems.backButton`；
  列表项用业务 id 模板串，**禁用数组下标**）；既有 testID 是自动化仓
  `tipsy-appium-automation` 的契约，**改名/删除默认禁止**。
  完整规范：`tipsy-app/llmdoc/reference/testid-conventions.md`。

### 6. 不继承 RN 侧的弱化质量配置

RN 侧 `modules/qt`、`modules/widget`、`modules/voice-call-system-session` 都有
`lintOptions.abortOnError false`；`vitest.config.ts` 有 `passWithNoTests: true`。
这些是 RN 的历史债务，**Android 新工程不复制这种假绿色**，也不在 Android packet 里
顺手修 RN 的既存红项或加 ignore。

同源纪律：**`NOT RUN` 不等于通过**。任何门禁项标 `NOT RUN` 需要风险 owner 明确批准。

## 验证命令

**命令名不靠猜**（方案 §5.4）。以下是实测存在的：

```bash
./gradlew projects                              # 确认模块结构
./gradlew :app:assembleGooglePlayDebug          # → com.tipsyturbo.app
./gradlew :app:assembleDirectApkDebug           # → ai.lightspeed.tipsy
./gradlew :app:assembleRuStoreDebug             # → com.tipsytavern.app
./gradlew :app:lintDirectApkDebug               # 硬门：abortOnError + warningsAsErrors
./gradlew :app:testGooglePlayDebugUnitTest      # 壳单测（含 MergedManifestTest）
./gradlew :tipsy-auth:testDebugUnitTest         # 桥单测（含 LiveAppSafetyTest）
./gradlew :app:processGooglePlayReleaseMainManifest  # release manifest（不跑 R8）
```

写新命令前先 `./gradlew :app:tasks` 确认存在。不存在的命令不得写成「已通过」。

注意 variant 是 **3 flavor × 3 build type**（debug / debugOptimized / release）——
RN 的 Gradle plugin 额外引入了 `debugOptimized`，比方案 §5.1 假设的多一档。给
build type 加 `buildConfigField` 时别漏它，漏了编译不过。

`MergedManifestTest` 断言的是 merged manifest **产物**，所以必须先 assemble 再跑单测；
没有产物它会 `assumeTrue` 跳过。

## 静默失败陷阱

按「症状」查，因为这些都是先看到症状才知道踩了坑。

| 症状 | 真实原因 |
| --- | --- |
| `Process 'command 'node'' finished with non-zero exit value 1`（无其他信息） | RN/Expo 多处假设 `Gradle root = <rn-project>/android`，本仓布局让它们落到错误目录，真实 stderr 被 Gradle 吞掉。排查：在报错任务的 workingDir 手工复现那条 node 命令。已知五处见工程日志 §2.2.2 |
| 改了 JS 却不生效，且不报错 | Metro 端口没经 `resValue` 注入。RN 从 `R.integer.react_native_dev_server_port` 读，debug source set 的同名 integer **不会胜出**（库资源）。本工程用 8083 |
| 挂 Surface 崩在 `onResume`，`ClassCastException` | `MainActivity` 没实现 `DefaultHardwareBackBtnHandler`。`reactDelegate.onHostResume()` 内部强转宿主 Activity。只有真机挂载才暴露 |
| 点了没反应（Surface 内交互失效） | 微根缺项。见下面「启用 Surface」一节——这类**不报错不崩溃**，只能靠用户反馈发现 |
| 非英文用户看到英文，英文环境测不出来 | 新增原生页文案没加入词条白名单并重跑导出脚本。iOS 搜索页 shipped 过一次 |
| RN Surface 内文字截断 / 换行与线上不一致 | `withAndroidStyles.js` 的 `AppTheme` + `useBoundsForWidth=false` 没手工移植。6 个 config plugin 在 brownfield **一个都不跑** |
| Screen 波次炸 OOM | `withAndroidLargeHeap.js` 的 `largeHeap="true"` 没手工移植（线上 ExoPlayer OOM 的缓解措施） |
| release 包对外暴露调试 Activity，普通构建不报错 | `expo-dev-client` 在 `package.json` 里是 `dependencies`，autolinking 把 `ui-tooling` 的 `PreviewActivity`（`exported=true`）合并进 release manifest。**每次新增 RN 依赖都要复查 release manifest diff** |
| 本机 lint 绿、CI 上 13 条新增 | lint baseline 对 app 模块外的文件记的是**机器相关绝对路径**，CI 上匹配不到。baseline 只放 app 模块内的相对路径条目 |
| `packageRuStoreDebug` 失败且不提示空间不足 | 磁盘写满。debug 默认出四个 ABI，单 flavor 中间产物可达数 GB。现 debug 只出 `arm64-v8a` |
| Android Studio 从 Dock 启动 sync 失败（`error=2`） | 先跑 `./scripts/bootstrap-android.sh`，再用原版 Studio。Node 必须经 `gradle.ext.tipsyNodeExecutable` 绝对路径契约传到所有调用点；用 `./scripts/check-node-contract.sh` 在无 Node PATH 下复验。不要恢复包装 App / launchctl 方案 |
| CI 报 `git@github.com: Permission denied (publickey)`，但凭据是好的 | `git submodule sync` 会把 local config 的 URL 覆盖回 `.gitmodules` 的 SSH 值。顺序必须是先 `sync` 再 `config` |
| 覆盖升级「验过了」但线上掉数据 | `adb install -r` 用 debug 签名重装**不算**证据（签名不同，数据目录不继承）。三个 applicationId 的结果**不可外推**，各跑一遍 |

## 启用 Surface

`src/App.tsx` 在 Surface 模式**不挂载**，所以每个 Surface root 是微型 App Root，
必须自行补齐全局件。**漏挂的共同症状是「点击无反应」**：事件写进了 store，但没有
任何宿主渲染它——不报错、不崩溃、日志干净。这是本工程唯一只能靠用户反馈发现的类别。

照 `ChatDetailSurface.tsx`（实测清单）对表：

| 挂什么 | 漏了会怎样 |
| --- | --- |
| `GestureHandlerRootView` / `SafeAreaProvider` / `KeyboardProvider` | 手势 / 安全区 / 键盘避让失效 |
| `NavigationContainer` + 微栈 | 页内导航不可用 |
| `SWRConfig` | 缓存 / 重验策略与线上不一致 |
| **`PortalProvider` + 8 个命名 `PortalHost`** | 经 `<Portal hostName>` 传送的弹窗/抽屉**存入 state 却无宿主渲染 → 点击无反应** |
| **`SurfaceToastHost`** | **丢掉全部 toast**（iOS 上 ChatDetail 与 Comments 真的丢过） |
| `RoleCardLimit` | 角色卡超限弹窗只写 store、无人渲染 |
| `GreetingVideoPortal` | 点开场白视频卡只写 store、无人渲染 |

**层序有讲究**：`SurfaceToastHost` 必须在命名 `PortalHost` 群**之前**（弹窗要盖在
toast 之上），对齐 `App.tsx` 的层序。

另需保证（不在组件树里但同样必需）：i18n 初始化与 `onLanguageChanged`、auth/user
hydrate、Sentry runtime、Native navigation adapter、首帧/reappeared/close 协议。

**微栈原则**：RN 页内 `navigate` 的所有目的页必须都在微栈内——iOS 的 RoleCardSurface
因缺 `CreateStack` 出现过死链。React Navigation 对不存在的目标栈是**静默 no-op，不崩**，
所以这个错查不出来。跳出微栈的目的页要么经桥走 Native Router，要么在 shell-host 下
显式降级 no-op。

**跨容器返回不产生 RN blur/focus**：经桥跳出再返回时 `useFocusEffect` 与 SWR
`revalidateOnFocus` 都不触发——「去完成任务回来领奖」类页面刷不出来（写完评论回来
按钮仍是 Comment 而非 Claim）。RN 侧 `useShellSurfaceRefocus` 已就绪，**Android 只需
在容器非首次 `onResume` 时发 `onSurfaceReappeared`**，payload 是 `{ surface: string }`
（**组件名，不是 instanceId**——dedupe 粒度是 Surface 类型）。不要用壳侧标志位的旧解法。

**`surfaceInstanceId` 用于事件归属**：ready/close/reappear 都带此 ID，**旧实例的迟到
事件不得关闭新实例**。iOS 的 `popSurface` 闸是类型判定，迟到事件弹错过同类型页。
`popSurface` 幂等，同一实例最多消费一次。

**首帧**：ready 前显示 Native 占位，ready 后单次淡出。**不用固定延时猜测**。

**Root side-effect 不能漏**（方案 §4.2）：iOS 最贵的事故就是 `App.tsx` 在壳内永不挂载
→ ATT 弹窗不弹、AppsFlyer 与 Facebook SDK 从未初始化，买量归因全断，直到提审预演才
发现。判据：**命令式 store populate → 必须镜像进 surfaces 入口；SWR mutate / 消费面
已原生化 → 可跳过。** 同类症状会被升级安装的 MMKV 残留掩蔽，**全新安装才必现**。

已决策**刻意不做**的别顺手修：视频播放器池在壳内不挂（`GreetingVideoPlayer` 有
`fallbackPlayer` 兜底，池仅为预加载优化）。

## 技术选择：标准答案在这里是错的

这是 brownfield 壳，不是新工程。**现代 Android 的默认推荐在这里多数不适用**——
不是因为落后，是因为要与 RN runtime 共存。别伸手去拿：

| 想用 | 这里用什么 | 为什么 |
| --- | --- | --- |
| Hilt / Koin | 手写 `AppContainer`（ADR-005） | 不把 DI 引入与 brownfield 首次接入混在一起——两个都失败时无法二分。边界稳定后再单独评估 |
| Navigation Compose | `AppCompatActivity` + FragmentManager（ADR-002） | FragmentManager 同时解决返回栈、saved state、predictive back、进程重建；`ReactFragment` 也要求 Fragment 宿主。纯 Compose Navigation 需单独 ADR + POC |
| Retrofit | 手写 `ApiClient` + OkHttp | **已决定不引**（工程日志 §2.14）。三种鉴权模式 + 统一 envelope + tolerant scalar 都要自己控 |
| 新起 `OkHttpClient` | 注入 `TipsyApplication.sharedOkHttpClient()`（经反射取 RN 的） | 各起一套会让连接池 / DNS 缓存 / TLS session 变成两份，「同一后端两条链路」难查（RN 侧能连、原生页超时）。**共享是优化不是正确性前提**——取不到就退化成两个 client，别改成抛异常 |
| DataStore / Room | MMKV（与 RN 同实例同目录） | 壳要**直读 RN 写的** MMKV 文件，这是 token 迁移主路径 |
| `.gradle.kts` | Groovy DSL（ADR-004） | 全仓不混。Expo SDK 54 / RN 0.81.4 的模板与 `autolinking_implementation.gradle` 都是 Groovy |
| 版本写 `+` 或升到最新 | `libs.versions.toml` 显式固定 | 见下 |

**mmkv `2.2.4` 与 coroutines `1.7.3` 是与 RN 侧的耦合约束，不是「越新越好」的普通依赖。**
mmkv 必须与 `react-native-mmkv` 依赖的原生版本**完全一致**（且用的是 fork
`io.github.zhongwuzw:mmkv`，不是腾讯官方），漂移的症状是「读不到 token / 静默当作未登录」。
coroutines 由 `expo-modules-core` 用 `api` 暴露，声明更高版本会把整个 runtime 顶上去。
两处都标了 `#noinspection NewerVersionAvailable`——**看到就别改**。

**minSdk 24**：注意 API level 门槛，且 API 24 是 CI/冒烟矩阵里的真实一档。

**测试**：JVM 单测里 `android.jar` 的 `org.json` 是抛异常的 stub，所以显式依赖了真实
`org.json`。⚠️ **不要用 `testOptions.unitTests.returnDefaultValues = true` 绕**——那会让
所有未 mock 的 Android API 静默返回默认值，是方案 §5.4 点名的「假绿色」。
契约测试用 `MockWebServer` 验「实际发出了什么 header」，mock OkHttp 接口验不了这个
（那只会验到自己写的 stub）。

## 写代码时

**Kotlin 风格**：跟 `app/src/main/java/ai/lightspeed/tipsy/shell/` 现有文件走。
KDoc 用中文，说明**为什么**（引方案/W1 章节号）而不是重复代码在做什么；对易错处直接
写「⚠️ 改这里前必读」。例：`surface/SurfaceContract.kt`、`i18n/L10n.kt`。

**Compose 里的文案必须用 `LocalizedText`，不要写 `Text(L10n.t(key))`**。`L10n.t()` 是
普通函数调用，Compose **不知道**它读了可变状态——语言切换后已组合的 `Text` 不重组，
表现为「切了语言当前页没变，退出重进才变」。同理需要语言值时用 `rememberCurrentLanguage()`。

**Activity/Fragment 持有的跨进程订阅必须在 `onDestroy` 解绑**。`AuthStateHub` 是进程级，
不解绑会让已销毁的 Activity 收到登录事件 → 往死掉的 FragmentManager 提交事务。
`MainActivity` 里 `router.dispose()` 与清 `onPopSurfaceRequested` 回调都是这个原因
（Application 不持 Activity 引用，用回调转接，忘了清就泄漏 Activity）。

**深链要处理 `onNewIntent`**。`launchMode=singleTask` 下热启动的深链走 `onNewIntent`
而**不是** `onCreate`，漏了的表现是「App 在后台时点深链没反应」。去重由 Router 负责。

**返回栈是分层的**：RN 微栈 → `invokeDefaultOnBackPressed()` → FragmentManager 栈 → 退出。
在 `invokeDefaultOnBackPressed` 里再去 pop RN 会跳过一层，表现为「按一次退两层」。

**未实现的路径要显式记录而非静默**。`requestLogin` 在原生 Login 页落地前是 `Log.w`,
不是空实现——静默会让「未登录点深链」表现为点了没反应。同理 Router 里已启用但缺导航
实现的分支直接 `error()`，让实现错误可见。

**`/auth/refresh_token` 刻意不走 `ApiClient`**（走 `RefreshTokenApi`）。它是 auth 的前置，
走 `ApiClient` 会形成「取 token → 刷新 → 取 token」的循环。做「统一网络收口」类重构时
别把它并进去。

**已有的收口点不要绕过**：语言变更收在 `L10n.setLanguage`（统一发桥事件）、401/402 收在
`ApiErrorGate`（唯一汇聚点 + 防抖）、导航收在 `AppRouter`（auth gate / 去重 / 排队都在
Router 里，`ShellNavigator` 只把已决策的路由变成容器操作，不重复判断——两处逻辑会漂移）。
在调用点各自处理会让 bug 只在某些路径下出现。

**列表**（五个页面全适用，iOS 花了整月修）：stable key + `LazyColumn` key / DiffUtil,
禁止全量替换。增量更新必须有版本/乱序 guard，覆盖三组失败用例：晚到的 banner、
语言 settle 后重拉、翻页去重后的空页（**必须主动续拉且限次**防请求循环）。
曝光去重集合与列表更新解耦。

**i18n**：key = 英文原文，与 RN i18next 一致。fallback 链「当前语言 → en → key」。
**壳是语言的唯一 writer**，所有改语言的路径都经 `L10n.setLanguage`（它统一发桥事件）。
四套集合不能混（磁盘 28 / import 27 / 支持码 26 / 服务端列表），别把 dormant 的 `ar`
提升为可选语言。

**testTag / contentDescription 从第一个组件就加**，不后补（iOS 后期批量补了约 295 个）。
壳原生页用 **snake_case**（`chat_list_bell`、动态段只拼服务端稳定 id）；RN 侧是
**另一套** dot-camelCase（`gems.backButton`）——两套并存是刻意的，**别互相照抄**，
详见方案 §9.4。

**网络**（方案 §4.5）三种鉴权模式对应 RN 两个 axios 实例：`REQUIRED` = `axiosAuth`、
`OPPORTUNISTIC` = `axiosPublic`、`NONE` = 明确禁止身份的端点。

⚠️ **别把 `axiosPublic` 实现成「永不带 token」**。很多「公开」接口带不带 token 行为
不同——`/search/character_search` 带 token 才记入最近搜索，iOS 错用导致搜索历史恒空。
**端口化任何走 `axiosPublic` 的接口都要用 `OPPORTUNISTIC`，逐一核对 RN 侧用的哪个实例。**

已知业务 code 不得笼统转成 IOException：`0` 成功、`6` 宝石不足、`9` 角色卡上限、
`16` clover 分支；HTTP `401` 走 token-aware auth reject、`402` 走带防抖的付费墙路由。
**双入口（原生页 + Surface 经桥）必须汇入同一漏斗**。

**标量漂移**：dev/prod 会把 TS 标注 `string` 的字段返成 JSON number。容错做在**统一
serializer 层**，不在业务模型里散落 `Any` / try-catch。iOS 因单字段类型不符导致整个
响应解析失败，而列表路径**静默吞错**（空列表伪装成「无结果」）。

**缓存 key 必须组合** `environment + accountId|anonymousInstallationId + feature +
filters + schemaVersion`，**不得静默跨账号/跨环境复用**。迁移先兼容读旧 key 再写新 key。
反直觉的一条：**语言刻意不做缓存门禁**——两阶段 i18n 下首屏读到的是瞬态语言，做门禁
会永久拒绝缓存（iOS 踩过「二启永远无种子」），语言真变化靠重拉自愈。

**token 绝不经 initial props**——会随 Fragment 参数进 `Bundle`，可能落入 saved instance
state、ANR trace、崩溃日志。JS 按需调 `getValidToken()`。

**契约演进**：只增字段不改语义；改语义/变必填/删字段要升 `CONTRACT_VERSION` **并**升
OTA runtime 代际。capability 变更发 `.v2` 新标识，不改旧的。

## 提交前

1. 三个 debug flavor 都能 assemble（至少 `googlePlayDebug` + 动了 flavor 相关时全跑）
2. `:app:lintDirectApkDebug` 过（硬门，新增告警即失败）
3. `:app:testGooglePlayDebugUnitTest` 与 `:tipsy-auth:testDebugUnitTest` 过，且**没有
   被跳过的守边界测试**
4. 动了 manifest / RN 依赖 → 复查 **release** merged manifest diff（exported、权限、
   开发期组件）
5. 动了 RN 侧 → 改动已提交到 `tipsy-app` 的 `feat/android-native` 并**推到远端**，
   本仓只 bump 指针
6. 状态变化只写进了进度文档一处
7. 未验证的项如实标注，不写成「已通过」

Surface 或页面要标 DONE 另需过验收矩阵：Surface 十项（W1 §11.4 / 方案 §9.1）、
页面十类证据（方案 §9.2）。**未填满的行不得标 production-ready。**

## 不要做的事

**明确非目标**（方案 §11）——这些不是「还没做」，是**决定了不做**：不全量移除
RN/Expo/Node 构建链；不迁 Create / Comments / EditProfile / Settings 子页 / ChatDetail
深栈；不同时升级 Expo/RN/AGP/Kotlin；不做 isolated AAR（W4 全绿后才允许 POC）；
不做逐页 native↔RN feature flag。

**别把工具链「升级」当改进**。AGP `8.11.0` / Kotlin `2.1.20` / Gradle wrapper `8.14.3` /
Compose BOM `2025.04.01` 是 RN 0.81.4 与 SDK 54 的**兼容事实，不是选型**（AGP 8.11 不
支持 Gradle 9）。mmkv 与 coroutines 版本更是与 RN 侧的耦合约束，升了会静默出错。
lint 的三类「有新版可用」检查已 disable，**所以 lint 不会提醒你版本问题**。

**W0/W1 是时间盒**：目标是「够用就往下走」，不是做完美。别在这两波做完整的存储
registry、CI nightly/release gate、对等矩阵——按页面波次增量填。

**这些不在本仓权限内**：直接保留对 `tipsy-app/node_modules` 的手工修改；改动依赖源码时
必须在 `tipsy-app/patches/` 生成版本化 patch-package 补丁、保留 standalone 的 upstream
fallback，并通过无 Node PATH 契约检查。未经对应 RN owner 评审，不改 `tipsy-app` 的依赖分类。

**缺前提时不要假装验证过**：Google 登录要求每个 applicationId × 每个签名证书的 SHA-1
登记到 Firebase，壳的 debug keystore 与现网包不同 → **没有这些指纹，`/login/firebase`
在壳里无法真机验证**（方案 §12.8，阻塞 W2）。缺真实签名或旧包时，**不声称覆盖升级完成**。

**凭据**（方案 §12.7）：`app.config.js` 与 `modules/qt/android/build.gradle` 内有
credential-like 字面量，`modules/qt/android/local.properties` 被跟踪。安全 owner 出结论前
**不读取、不打印、不复制**这些值。壳工程只用无凭据 placeholder / CI secret。

**scheme 别原样照搬**：`app.config.js` 注册了 `fb`/`twitter`/`discord`/`instagram`/`tiktok`
五个泛用外部 scheme 的 VIEW + BROWSABLE。注册成自己的 intent filter 意味着壳声明能打开
这些 scheme，**存在 intent 劫持面**，W1 要逐条审计必要性。

## 需要独立授权

OTA 发布、签名、真实版本号递增、发渠道。发布脚本默认 `--dry-run`,显式 `--execute`
才写外部。

遇到方案 §12 的 10 项开放问题（Qt listener 归属、`AuthBootstrapSurface` 可接受性、
QA 分发形态、签名指纹、Apple 登录是否展示…）——这些**需要决策才能推进**，不要自己
替 owner 拍板。用 `AskUserQuestion` 问，或明确标为阻塞。
