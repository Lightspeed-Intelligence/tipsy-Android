# Tipsy Android 原生化迁移：现状（唯一状态真值）

> 更新：2026-08-08 ｜ Android 壳：**W0 主体已完成**（三 flavor 可构建 + DebugSurface gate 实测通过；CI 与 manifest 快照待做）
> 配套决策方案：[android-native-migration-plan.md](../architecture/android-native-migration-plan.md)
> **本文是状态权威。** 方案文档只写决策不写状态；任何「进度/是否已实现」的问题一律以本文为准。

## 0. 三十秒速览

- **波次进度**：W0 主体完成 —— 依赖 + 工具链 + autolinking + **DebugSurface gate 实测通过**。
- **代码现状**：`ai.lightspeed.tipsy.shell` 下有 `TipsyApplication`（单 ReactHost）+ `MainActivity`（Compose 原生根）+ `RNSurfaceFragment`。仍是零业务代码。
- **submodule**：pin `93d2c5551`，`node_modules` 已装（1812 包）。
- **已验证**：三 flavor debug 构建通过、applicationId 正确、JS bundle 内嵌、51 个 project autolink、**Surface 两种 bundle 来源均可挂载**（详见 §2.6）。
- **不存在**：五 Tab、Router、core 模块、feature 模块、桥实现、CI。

## 1. 波次状态

| 波次 | 内容 | 业务量 | 状态 | source_rn_sha | target_android_sha |
| --- | --- | --- | --- | --- | --- |
| W0 | 工程地基 + brownfield DebugSurface | 基建 | 🟢 主体完成（gate 已过；剩 CI / manifest 快照 / lint 接入） | `93d2c5551` | `e3df0ed` |
| W1 | 平台契约 + auth + ChatDetailSurface gate | 基建 | ⬜ 阻塞于 W0 | — | — |
| W2 | Bootstrap + 五 Tab shell + **Login** + **Home** | 约 10k 行 RN | ⬜ 阻塞于 W1 | — | — |
| W3 | **Profile** + **ChatList** + **Search** + Settings 列表/语言 | 约 19k 行 RN（最大） | ⬜ 阻塞于 W2 | — | — |
| W4 | **Screen/Media3** + 12 个 Surface + 系统能力 + OTA | 约 5.3k 行 RN + 系统 | ⬜ 阻塞于 W3 | — | — |
| W5 | 对等 / 性能 / 三渠道发布切换 | 发布 | ⬜ 阻塞于 W4 | — | — |

**W0+W1 时间盒**：这两波不产出用户可见价值，目标是"够用就往下走"。若超过总工期 1/4,停下复审是否过度设计（方案 §8.5）。

## 2. 当前工程实况

### 2.1 已跟踪文件（51 个，非 submodule）

Android Studio 新建 Compose 工程的默认产物：`app/build.gradle.kts`、`MainActivity.kt`、`ui/theme/{Color,Theme,Type}.kt`、模板 res、`gradle/libs.versions.toml`、wrapper、`.idea/`。加本次新增的 `llmdoc/`。

### 2.2 工具链（已对齐，实测值）

| 项 | 当前值 | 来源 |
| --- | --- | --- |
| AGP | `8.11.0` | RN 自带 catalog |
| Kotlin | `2.1.20` | 同上 |
| compileSdk / targetSdk / minSdk | `36 / 36 / 24` | 同上 |
| Build Tools | `36.0.0` | 同上 |
| NDK | `27.1.12297006` | 同上 |
| **Gradle wrapper** | **`8.14.3`** | **AGP 8.11 不支持 Gradle 9；模板原为 9.4.1** |
| **Compose BOM** | **`2025.04.01`** | **实测可与 Kotlin 2.1.20 共存（模板原为 2026.02.01）** |
| Gradle DSL | Groovy | 方案 ADR-004；`.kts` 已全部改写 |
| JDK | 17（daemon 跑 21，编译 target 17） | — |

### 2.2.1 实测的 Gradle task 名（方案 §5.4「命令名不靠猜」）

```
./gradlew projects
./gradlew :app:assembleGooglePlayDebug     # → com.tipsyturbo.app
./gradlew :app:assembleDirectApkDebug      # → ai.lightspeed.tipsy
./gradlew :app:assembleRuStoreDebug        # → com.tipsytavern.app
./gradlew :app:testGooglePlayDebugUnitTest
```

注意 RN 的 Gradle plugin 额外引入了 **`debugOptimized`** build type，故实际 variant 数是
`3 flavor × 3 build type`（debug / debugOptimized / release），比方案 §5.1 假设的多一档。

### 2.2.2 W0 踩过的坑（都表现为同一句无用报错）

RN/Expo 生态多处假设 `Gradle root = <rn-project>/android`，本仓布局会让它们落到错误目录。
**症状统一是 `Process 'command 'node'' finished with non-zero exit value 1`，真实 stderr 被 Gradle 吞掉。**
排查方法：在报错任务的 workingDir 手工复现那条 node 命令。

| 出处 | 错误推导 | 处理 |
| --- | --- | --- |
| `expo-constants` `createExpoConfig` | 用 `rootProject.projectDir` 当 projectRoot | doFirst 重定向（配置期改会被写回） |
| `expo-updates` `create*UpdatesResources` | 用 `rootProject.projectDir.parentFile` | **禁用任务**（其 Property 执行期已 final，改不动；OTA 属 W4） |
| `autolinkLibrariesFromCommand` | workingDirectory 默认取 Gradle root 的父目录 | 显式传 `tipsy-app` |
| 第三方模块（apple-authentication / skia 等） | 从 `rootProject.projectDir` 向上找 node_modules | `ext.reactNativeAndroidRoot` 指向 **RN 包根** |
| `react.cliFile` | 默认 RN `cli.js`，但 Expo 工程无 `@react-native-community/cli` | 改 `@expo/cli` + `bundleCommand=export:embed` |

**另一个已知限制**：`expoAutolinking.exclude` 对 `expo-updates` 等无效 —— `AutolinkingCommandBuilder`
把多值 `--exclude` 与 `--project-root` 拼进同一 argv，variadic 参数会吞掉后续 flag
（实测 `--exclude` 在 `--project-root` 之前时不生效）。故 W0 的隔离用「禁用任务」实现。

**磁盘**：debug 默认出四个 ABI，单 flavor 中间产物可达数 GB；曾因磁盘写满导致
`packageRuStoreDebug` 失败且**不提示空间不足**。现 debug 只出 `arm64-v8a`。

### 2.3 环境

| 工具 | 状态 |
| --- | --- |
| `tipsy-app/node_modules` | ✅ 已装（`npm ci`，1812 包，patch-package 与 hermes-O0 patch 均已应用） |
| 根 `node_modules` 符号链接 | ✅ 已建（不入库，见 `.gitignore`；换机器/CI 需重建） |
| `sdkmanager` | 曾观察到不可用（未装 cmdline-tools）。W0 需实测并提供明确环境检查与 CI 安装路径 |
| emulator image | 未固定。W0 记录实际可用的 API 24 / API 36 image |
| Node / npm | ✅ 实测 node `v22.22.3` / npm `10.9.8`；settings.gradle 有四级显式解析（见方案 ADR-004 第 3 条） |

### 2.4 已经不用做的事（RN 侧已就绪，实测）

`tipsy-app` 里已有 **55 个文件**完成壳适配（iOS 壳一年沉淀）：13 个 Surface 入口组件、`SurfaceToastHost`、`TipsyHeader` 栈底 `popSurface` 兜底、`useShellSurfaceRefocus`、`useChatNavigation` 壳分支、`shellGemsEntry`/`shellTaskEntry` 跨栈出口、`axios.ts` 的 401/402 桥上抛、`config_persist` nsfw 镜像接力、`recommendTracking` 壳 outbox、`api.ts`/`lane.ts` 壳 API 地址。

**Android 只要提供能让 `isShellHost()` 返回 true 的 Kotlin 桥，这些全部自动生效。** 另有约 4,500 行现成 RN 测试可作对等 fixture（方案 §8.2）。

### 2.4 DebugSurface gate 实测结果

环境：**API 37 / arm64-v8a 模拟器**，`directApk` flavor debug 包。

| 验收项 | 结果 |
| --- | --- |
| 原生根先渲染（不依赖 RN） | ✅ |
| **离线内嵌 bundle** 挂载 Surface | ✅ `[Surfaces:debug] DebugSurface rendered` —— 无 Metro，证明 release 可离线 |
| **Metro 直连**挂载 Surface | ✅ `isMetroRunning=true`，Metro 侧收到 bundle 请求（HTTP 200 / 4.3MB） |
| 返回键回原生根、进程不退出 | ✅ |
| **50 次挂载/卸载** | ✅ 无崩溃、PID 不变；PSS 199→208MB 但增速递减（前 10 轮 +4.4MB，后 10 轮 +0.4MB），GC 后回落至 204MB |
| **单 Runtime 不变量**（ADR-003） | ✅ 50 轮后 GC：`Activities=1`、`ViewRootImpl=1`、`Views=19`，**无滞留 Activity/View** |
| 旋转（横↔竖，Surface 挂载中） | ✅ 无崩溃，Surface 存活并重渲染 |
| 进程重建（force-stop 后重启） | ✅ 新 PID、`rootTag` 归 1、Surface 可再挂 |

**gate 捕获的两个真实缺陷**（构建期与静态检查都发现不了）：

1. **`MainActivity` 必须实现 `DefaultHardwareBackBtnHandler`**。`ReactFragment.onResume`
   → `reactDelegate.onHostResume()` 内部把宿主 Activity 强转成该接口，不实现直接
   `ClassCastException` **崩在 onResume**。只有真机挂载才暴露 —— 这就是 gate 的价值。
2. **Metro 端口必须用 `resValue` 注入**。RN 从 `R.integer.react_native_dev_server_port`
   读端口（`AndroidInfoHelpers.kt`），默认 8081。**debug source set 的 `res/values` 放同名
   integer 不会胜出**（实测 aapt2 dump 仍是 8081），必须 `resValue` 注入 app 自己的资源表。
   漏掉的表现是 `isMetroRunning()` 永远探测 8081 → 静默回退内嵌 bundle，
   **「改了 JS 却不生效」且不报错**。端口取 8083（ADR-003）。

### 2.5 模拟器上的现成 fixture（**勿卸载**）

模拟器已装 `com.tipsyturbo.app` **versionName 1.4.4** 的真实 RN 包，`files/mmkv/` 下有
`mmkv.default`（`token-storage` 所在）、`chat-list-cache`、`for-you-cache` 等真实数据。

**这是 W1 覆盖升级验证（§2.4 迁移算法 + §6.1 矩阵）的现成 fixture**，请勿卸载。
W0 验证刻意改用 `directApk` flavor（包名 `ai.lightspeed.tipsy` 未被占用）以避免破坏它。
注意壳的 debug 签名与该包不同，直接覆盖装会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` ——
真实覆盖升级验证需用匹配签名的产物（方案 §6.1 已写明「debug 签名重装不构成证据」）。

### 2.6 W0 剩余项

1. ~~DebugSurface gate~~ ✅ 已完成，见 §2.4
2. ~~Metro 端口 8083 + cleartext 配置~~ ✅ 已完成
3. **merged manifest snapshot 测试**（方案 §5.1）：三 flavor 逐项断言 exported/scheme/权限。
4. **lint / detekt 接入**并进 `check` 依赖图；CI 的 G1 fast gate（方案 §5.4）。
5. `sdkmanager` 可用性与 CI 的 SDK 安装路径。
6. **API 24 冒烟**：目前只在 API 37 验过，方案 §5.4 的设备矩阵要求 minSdk 也过一遍。
7. release 产物验证：确认 debug 专属配置（cleartext / DevSettings / 8083 端口）**未**泄漏进 release。

## 3. 横切能力

| 能力 | 状态 | 落地处 |
| --- | --- | --- |
| Auth 所有权 | 🔴 未开始 | 需 §2.1 桥 + §2.4 迁移 |
| `tipsy-auth` Android 实现 | 🔴 不存在 | `modules/tipsy-auth` 仅 apple |
| 网络层 | 🔴 未开始 | — |
| i18n | 🔴 未开始 | — |
| Router / 深链 | 🔴 未开始 | — |
| RN Surface 宿主 | 🟡 骨架就位 | `RNSurfaceFragment`（继承官方 `ReactFragment`，共享单 ReactHost）；**instanceId / 首帧协议 / onSurfaceReappeared 尚未实现**，见方案 §4.3 |
| Push | 🔴 未开始 | — |
| Analytics（Qt） | 🔴 未开始 | 归属待决策（方案 §12.1） |
| 营销 SDK（ATT/AppsFlyer/FB/TikTok） | 🔴 未开始 | iOS 事故点，方案 §4.2 |
| Sentry | 🔴 未开始 | — |
| Widget | 🔴 未开始 | — |
| OTA | 🔴 未开始 | 隔离方案见 §5.3。W0 已**显式禁用** expo-updates 资源任务（原因见 §2.2.2），W4 接入时需先解决其 projectRoot 推导 |
| CI | 🔴 不存在 | W0 剩余项 |

## 4. Surface 验收矩阵

13 个 Surface（`index.surfaces.js` 实测注册）全部未验收：

`DebugSurface` / `ChatDetailSurface` / `CommentsSurface` / `OnboardingSurface` / `CreateSurface` / `DeleteAccountSurface` / `EditProfileSurface` / `GemsSubscriptionSurface` / `NotificationSurface` / `RoleCardSurface` / `SettingsSurface` / `UserCoinsSurface` / `WidgetSurface`

矩阵表格见方案 §9.1。**未填满的行不得标 production-ready。**

## 5. 未决问题

方案 §12 的 10 项开放问题全部未决。其中阻塞 W0 的：

- **§12.3 QA 分发形态** —— 影响 build type 设计。

阻塞 W1 的：

- **§12.1 Qt lifecycle listener 归属**
- **§12.5 `AuthBootstrapSurface` 可接受性**
- **§12.7 凭据分类与轮换**（安全 owner 结论）

阻塞 W2 的：

- **§12.8 Google/Firebase 的 Android 签名指纹**（三 flavor × debug/release，**没有它 Firebase 登录无法真机验证**）
- **§12.4 Home 是否包含 World 系列**
- **§12.9 Apple 登录按钮在 Android 是否展示**、**§12.10 `/login/password` 是否对外**

## 6. 已废弃的历史尝试

`migration/android-native-p00-bootstrap` 分支（P00 文档基线 + Gradle 脚手架尝试）
**已于 2026-08-08 废弃并删除远端**，其上工作作废，**不作为任何决策依据、不要去恢复参考**。

其中仍然有效的知识已全部吸收进当前两份文档：

| 原分支上的内容 | 现在在哪 |
|---|---|
| iOS 迁移复盘（时间线 / 十条经验 / 反模式） | 方案 §1.3 归属表、§3.2 各 ADR、§1.2.1 十条经验与反模式、§8.4 列表纪律、§10 风险登记 |
| Node 可执行文件解析（fnm/nvm 下 GUI 启动 sync 失败） | 方案 ADR-004 第 3 条（含已验证的四级解析优先级与 PATH 时序约束） |
| 三渠道 / config plugin / 桥模块等硬约束 | 方案 §2（**已在 pin `93d2c5551` 重新核实过源码**，不依赖旧报告） |
| CNG prebuild 审计报告（基线 `cbd521f02`） | 不再引用。其结论中可核实的部分已重新核实；**RN lint/test/doctor 的具体红项数量待 W0 实跑** |

**纪律**：本仓不再有"去某个分支恢复内容"的路径。方案与本文是唯一依据。

## 7. 状态更新纪律

1. 每个波次开始时把 `source_rn_sha` / `target_android_sha` 填成完整 40 位 SHA。
2. 波次结束跑 RN delta 审计，把变化映射到对等矩阵。
3. 发现文档与代码不一致时，**先修文档再继续实现**。
4. 不在其他文档里复制状态快照——重复的「当前进度」是 iOS 侧真实发生过的漂移源（同一文档记过不同的 submodule pin）。
