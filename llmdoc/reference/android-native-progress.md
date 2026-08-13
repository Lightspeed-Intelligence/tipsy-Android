# Tipsy Android 原生化迁移：现状（唯一状态真值）

> 更新：2026-08-11 ｜ Android 壳：**W0 完成**（gate 过 + API24/37 双端验证 + manifest 快照 + lint 硬门）；
> **G1 CI 已激活且在 main 上真绿**（§2.10 / §2.22）
>
> **W1 基本收尾**（细化方案见 [`../architecture/android-w1-plan.md`](../architecture/android-w1-plan.md)）：
> **P0 桥注册已接通、完整能力 PARTIAL**（§2.11）｜ **P1 auth closeout 已实现且 CI 已验**（§2.13 / §2.18 / §2.22）｜ **P2 机制已验、兜底推迟**（§2.12）
> ｜ **P2 剩余 + P3 已决定合并推迟到上线前**（2026-08-10，见 W1 计划 §5.6）
> ｜ **P4 Router/parser 机制已落地，ChatDetail 在 P9 前关闭**｜ **P5 i18n 已完成**（§2.16）
> ｜ **P6 network closeout 已实现且 CI 已验**（§2.14 / §2.22）
> ｜ **§12 Fragment 机制已落地、真实实例关闭链待收口**（§2.15）
> ｜ **P7 Qt / P8 Sentry 已决定推迟到业务迁移后**（2026-08-11，见 §2.17）｜ **P9 未开始**
> ｜ **原生登录页：邮箱验证码链路真机已验**（§2.20）—— Google/Apple 受 §12.8 签名指纹阻塞未接
>
> **W2 进行中**：五 Tab shell + Home 首屏（§2.23，主链路真机已验）+ 标签筛选抽屉
> 与 For You 冷启动种子（§2.24，**抽屉、种子写入门禁、离线渲染种子真机全已验**）。
> Home 剩 banner / 彩蛋弹窗 / mp4 封面三项（前两项评估留 RN Surface）。
> **W3 进行中**：Profile 主体完成（§2.25–§2.29）；ChatList P1 Grid 主链路
> 已实现待真机（§2.30）。
> 配套决策方案：[android-native-migration-plan.md](../architecture/android-native-migration-plan.md)
> **本文是状态权威。** 方案文档只写决策不写状态；任何「进度/是否已实现」的问题一律以本文为准。

## 0. 三十秒速览

- **波次进度**：W0 完成；W1 的契约层全部落地且已在 CI 组合验证（§2.22），只剩 §12 实例关闭链与 P9；P2 剩余/P3/P7/P8 均已决策推迟。W2 主体已落地：五 Tab + Home + Login（§2.23/§2.24，PR #20 已并，剩 banner / 彩蛋 / mp4 封面且倾向留 RN Surface）。**W3 进行中**：Profile 主体完成（§2.25–§2.29）；**ChatList P1 Grid 主链路已实现**（§2.30，真机冒烟待 G1）。
- **代码现状**：`ai.lightspeed.tipsy.shell` 下有 `TipsyApplication`（单 ReactHost + Analytics facade）+ `MainActivity`（Tab 根 + Router/i18n 接线）+ `RNSurfaceFragment` + `auth/` + `network/` + `router/` + `surface/` + `i18n/` + `bridge/` + `analytics/` + `tabs/` + **`user/`** + **`pages/login/`、`pages/home/`、`pages/profile/`、`pages/chatlist/`**。
- **submodule**：pin `95760a6622424bc9be238e7790fdbf38fe7c7fb2`（远端分支 `feat/android-native`，**未合进 main/release**，按约定靠子模块指针引用）。W2 首包至 W3 ChatList P1 **连续五包不动 submodule**（词条全在 SHELL_KEYS，§2.25/§2.30）。
- **已验证**：G1 在 main 上 22 步全绿（§2.22）。W3 侧本机同序列：lint 无新增、`assembleGooglePlayDebug`/`assembleDirectApkDebug`、**app 单测 649 条，failures=0 / skipped=0**、`:tipsy-auth` 15 条全绿。Profile 真机验证累计：七项冒烟、头部视觉、钱包出口、卡片角标与模糊、五 tab 真实数据（§2.25–§2.29）。ChatList 真机冒烟待 PR/G1（§2.30）。
- **不存在 / 未验**：Screen Tab 仍是占位页；ChatList 的 Map「時光長廊」视图是 P2（Grid 已是真页）；Sentry、Qt 实际上报、core/feature 模块、**G3 nightly** 均无。P9 前生产路由白名单为空，ChatDetail 保持 disabled（ChatList 的点击出口因此被明确拒绝）。⚠️ 待 owner：**性别筛选持久化静默失效**（§2.23.1，待定修法）、**Follow 出口无 Surface 可用**与 **EditProfileSurface 属 W3 还是 W4**（§2.25，方案自相矛盾）。

## 1. 波次状态

| 波次 | 内容 | 业务量 | 状态 | source_rn_sha | target_android_sha |
| --- | --- | --- | --- | --- | --- |
| W0 | 工程地基 + brownfield DebugSurface | 基建 | 🟢 完成 | `93d2c5551` | `4f191e8` |
| W1 | 平台契约 + auth + ChatDetailSurface gate | 基建 | 🟡 **契约层已收口且 CI 已验；§12 关闭链 + P9 未完** | `95760a6622424bc9be238e7790fdbf38fe7c7fb2` | —（PR #16/#17 已并） |
| W2 | Bootstrap + 五 Tab shell + **Login** + **Home** | 约 10k 行 RN | 🟡 **主体已落地**：Login 邮箱链路已验、五 Tab + Home 首屏、筛选抽屉 + 冷启动种子均已并入 main（§2.20 / §2.23 / §2.24）。剩 banner / 彩蛋 / mp4 封面（banner 与彩蛋倾向留 RN Surface，方案 §8.1） | `95760a6622424bc9be238e7790fdbf38fe7c7fb2` | —（PR #19 / #20 已并） |
| W3 | **Profile** + **ChatList** + **Search** + Settings 列表/语言 | 约 19k 行 RN（最大） | 🟡 **进行中**：Profile 主体完成（P1–P4、P6，§2.25–§2.29，真机验证）；**ChatList P1 Grid 主链路已实现**（§2.30，真机待 G1）；剩 Profile P5/P7、ChatList P2 Map、Search、Settings | `95760a6622424bc9be238e7790fdbf38fe7c7fb2` | —（PR #21–#24 已并；ChatList P1 在 `feat/android-w3-chatlist-p1`） |
| W4 | **Screen/Media3** + 12 个 Surface + 系统能力 + OTA | 约 5.3k 行 RN + 系统 | ⬜ 阻塞于 W3 | — | — |
| W5 | 对等 / 性能 / 三渠道发布切换 | 发布 | ⬜ 阻塞于 W4 | — | — |

**W0+W1 时间盒**：这两波不产出用户可见价值，目标是"够用就往下走"。若超过总工期 1/4,停下复审是否过度设计（方案 §8.5）。

## 2. 当前工程实况

### 2.1 当前工程范围

当前已包含 Gradle/三 flavor 工程、Compose 原生根、单 ReactHost/Fragment 宿主、
auth/network/router/surface/i18n 契约与测试、G1 workflow 及 `llmdoc/`。文件数会随当前
closeout 改动变化，不再用易漂移的计数或模板期文件清单描述现状。

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
| 从 Android Studio（Dock/Finder）启动 sync | ✅ 2026-08-10 修复。需**两层**配置，缺一层即失败：① `local.properties` 写 `tipsy.node.executable`（用 fnm 的 `aliases/default` 路径，不是 `which node`）；② launchd GUI 域 PATH 含 node 目录（LaunchAgent 固化）。**两者都不入库，换机器需重做**。<br>⚠️ **改完 PATH 必须 ⌘Q 退出 Studio 再打开** —— `launchctl setenv` 只影响此后新启动的进程；Sync / Invalidate Caches / `--stop` 都不够。查证用 `ps eww <studio-pid> \| tr ' ' '\n' \| grep ^PATH=` 看进程**实际**的 PATH，别看 `launchctl getenv`（那是「新进程会拿到什么」）。<br>缺第二层时 `settings.gradle` 现在会**在 1 秒内明确报错**并给出修复步骤（原先要等到 plugin 内部炸出无上下文的 `error=2`）。详见方案 ADR-004 第 3 条 |

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

### 2.3.1 API 24 冒烟（minSdk，已完成）

方案 §5.4 的设备矩阵要求 minSdk 也过一遍。环境：**API 24 / arm64-v8a**
（`Api24_Smoke` AVD，google_apis 镜像）。

| 项 | 结果 |
| --- | --- |
| 安装 + 启动 | ✅ 无崩溃 |
| 原生根渲染 | ✅ |
| Surface 挂载 | ✅ `DebugSurface rendered` —— 无 SoLoader / UnsatisfiedLink 问题 |
| 返回 + 10 轮开关 | ✅ 无崩溃 |
| 单 Runtime 不变量 | ✅ GC 后 `Activities=1`、`ViewRootImpl=1` |
| PSS | 168MB（比 API 37 的 204MB 更低） |

**结论：minSdk 24 可行**，RN 0.81 + 新架构在 Android 7.0 上正常工作。

### 2.5 模拟器上的 fixture —— ⚠️ **不可用作覆盖升级证据**（W1-P1 实测订正）

> **本节此前的记录是错的**，W1 开工核对时发现。原记录称它有 `files/mmkv/` 真实数据、
> 可作 W1 覆盖升级 fixture —— **两点都不成立**。

`emulator-5556`（Pixel_10 / API 37）上装有 `com.tipsyturbo.app` versionName **1.4.4**
（firstInstall 2026-07-29，lastUpdate 2026-08-05），但实测：

| 项 | 实测结果 |
| --- | --- |
| **数据目录** | **不存在**。`run-as` 与 shell 均报 `couldn't stat /data/user/0/com.tipsyturbo.app` |
| 有无 MMKV 数据 | **没有** —— 原记录说的 `mmkv.default` / `chat-list-cache` / `for-you-cache` 均无从读取 |
| **签名** | **`CN=Android Debug`** —— 是 **debug 签名**，不是现网发布签名 |
| 有无内嵌 bundle | **无**（`assets/` 里没有 `index.android.bundle`）→ 靠 Metro 加载 |
| launcher activity | **无**。只声明了 `exp+tipsy-app:` scheme 的 `.MainActivity`，`resolve-activity` 返回 `No activity found`，无法从桌面或 `am start` 正常拉起 |
| dev-launcher 迹象 | `classes2.dex` / `classes3.dex` 命中 `DevLauncher` 符号 |

**结论：这是一个 Expo dev build（debug 签名 + 无内嵌 bundle + dev-launcher），
不是现网 release 包，且从未产生用户数据。**

**对 W1 的影响（重要）**：
1. **P3 三渠道覆盖升级没有现成 fixture** —— 原以为「模拟器上已有现网包可直接用」，
   实际必须**另行取得三渠道的真实 release 产物 + 匹配签名**。这不改变结论
   （方案 §6.1 早写明「debug 签名重装不构成证据」），但**把外部阻塞项前置了**：
   没有真实产物，P3 一步也做不了。
2. **P2 的 MMKV 直读路径目前无真实数据可验** —— 需要先有一个真登录过的现网包，
   或由发布 owner 提供一份脱敏的 MMKV 数据样本。
3. 该 APK 仍可留着（179MB，dev build），但**只能用来看包结构**，
   不能当升级来源。**不要再把它当 fixture 引用。**

W0 用 `directApk` flavor 避免包名冲突这一点仍然有效。

### 2.6 W0 剩余项

1. ~~DebugSurface gate~~ ✅ 已完成，见 §2.4
2. ~~Metro 端口 8083 + cleartext 配置~~ ✅ 已完成
3. ~~merged manifest snapshot 测试~~ ✅ 见 §2.8
4. ~~lint 接入~~ ✅ 见 §2.9（detekt 仍未接）
5. ~~`sdkmanager` 可用性~~ ✅ 已装 cmdline-tools 12.0（`~/Library/Android/sdk/cmdline-tools/latest`）
6. ~~API 24 冒烟~~ ✅ 见 §2.3.1
7. ~~CI 已写但未激活~~ ✅ **已激活并首次真绿**（2026-08-10），见 §2.10
8. detekt 未接（lint 已是硬门，detekt 属增量）。
7. ~~release 产物验证~~ ✅ 已完成，见 §2.7

### 2.7 release 产物验证（已完成）

`assembleDirectApkRelease` 通过（R8 + 两个 ABI，约 3 分钟，产物 168MB **unsigned** ——
W0 刻意不配签名，无发布能力）。逐项断言 debug 配置未泄漏：

| 检查 | 结果 |
| --- | --- |
| `usesCleartextTraffic` | ✅ `false`（debug 的 `true` 未泄漏） |
| `DevSettingsActivity` | ✅ 不存在 |
| dev server 端口 | ✅ `8081`（默认值；8083 只在 debug 生效） |
| `android:largeHeap`（§2.3 必须项） | ✅ `true` |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | ✅ 已移除（Agora patch 生效） |

**过程中修掉两个 release 专属问题**（debug 完全不会暴露）：

1. **`proguard-rules.pro` 不存在** —— `app/build.gradle` 引用了它但文件没建，
   R8 直接报 `Supplied proguard configuration does not exist`。现已补上壳自己的
   keep 规则（Application/Activity 反射实例化、`@ReactMethod`、`@DoNotStrip`、
   Expo 模块、Sentry 需要的 SourceFile/LineNumberTable）。
2. **R8 堆不足 + 两个 Missing class**。50+ 个 RN/Expo 模块在 2G 堆下 R8 OOM
   （报 `Compilation failed to complete` 且 daemon 提示堆耗尽，跑了 13 分钟才失败）；
   提到 6G 后暴露真实错误：`ThrowableExtension`（Bazel desugar 残留，Agora 日志引用）
   与 `DevLog`（QT SDK 调试类，release AAR 未含）。两者运行时都不需要，用 `-dontwarn`
   而非 keep。**注意别无脑 `-dontwarn **` 掩盖后续真实缺失。**

### 2.8 merged manifest 快照测试（已完成）

`app/src/test/.../MergedManifestTest.kt`，5 条断言全绿：

1. **三渠道 applicationId 钉死** —— 改错会破坏覆盖升级
2. **release 不含开发期组件**
3. 有 `intent-filter` 的组件必须显式声明 `exported`
4. 不含已排除的敏感权限（`MEDIA_PROJECTION` / `ACCESS_FINE_LOCATION`）
5. release 保留 `largeHeap`

**这个测试立刻抓到一个真实缺陷**：`androidx.compose.ui.tooling.PreviewActivity`
以 **`exported=true`** 出现在 **release** manifest 里 —— 生产包对外暴露一个调试
Activity，而普通构建完全不报错。

根因链：`expo-dev-client` 在 `tipsy-app/package.json` 里是 **`dependencies`**
（不是 devDependencies）→ autolinking 把 `expo-dev-launcher` / `expo-dev-menu`
接进 release runtime classpath → 带入 `androidx.compose.ui:ui-tooling` →
其 manifest 的 PreviewActivity 被合并。

W0 的处理：`app/src/release/AndroidManifest.xml` 用 `tools:node="remove"` 兜底。
**根治**要么把 `expo-dev-client` 移到 devDependencies（属 `tipsy-app`，本仓不得改），
要么让 autolinking 按 variant 排除 dev 模块 —— 都超出 W0 范围。
**若将来 release 里再出现别的开发期组件，先查这条链，别只加 remove。**

另外权限总数已达 **51 条**（几乎都是 autolinked SDK 传递引入的），其中
`ACCESS_ADSERVICES_*`、`CAMERA`、`RECORD_AUDIO`、`USE_BIOMETRIC` 等会直接影响商店审核 ——
W1 起每次新增依赖都应看一眼这个测试的 diff。

### 2.9 lint 硬门（已完成）

`abortOnError=true` + `warningsAsErrors=true` + `sarifReport`（CI 可喂 GitHub code scanning）。
`checkDependencies=false` —— 50+ 个第三方 RN 模块的告警不由本仓负责。

**lint 抓到一个我自己写的真实缺陷**：`android:useBoundsForWidth`（`withAndroidStyles`
移植项）是 **API 35** 新增属性，而 minSdk=24 —— 报 `NewApi`。已按资源限定符拆分：
`values/styles.xml` 留空壳 style，`values-v35/styles.xml` 放该属性。
**这是真修复，不是记进 baseline。**

#### ⚠️ baseline 对 app 模块外的文件**不可移植**（2026-08-10 CI 首跑实测订正）

> 原记录称 baseline 有 **19 条**。那个数字只在本机成立 —— CI 上只有 5 条生效。

lint 把 app 模块**外**的文件（`gradle/libs.versions.toml`、
`gradle/wrapper/gradle-wrapper.properties`）的 location 记成
`$HOME/Developer/Tipsy-Android/...` 这种**机器相关的绝对路径**，
CI 的 checkout 在 `/home/runner/work/...`，**一条都匹配不到**。

| 环境 | baseline 过滤 | 新增 | 结果 |
| --- | --- | --- | --- |
| 本机（订正前） | 18 | 0 | ✅ 绿 |
| CI（订正前） | 5 | **13** | ❌ 硬门失败 |

13 + 5 = 18 正好对上 —— 不是新增了问题，是那 13 条在 CI 上失效。

**这类缺陷只有真在 CI 跑一次才会暴露**：本机永远绿，因为路径恰好匹配。
§2.10 记的「本机模拟整条 1m59s」模拟不了它。

**处理**：那 13 条全是「有新版可用」三类，与 §3.3 **刻意钉死工具链**的决定
直接冲突（版本是 RN 0.81.4 的兼容事实，不是选型；`mmkv` 与 `coroutines` 更是
与 RN 侧的**耦合约束**，升了会静默出错）。故在 `app/build.gradle` 显式
`disable` 掉 `GradleDependency` / `AndroidGradlePluginVersion` /
`NewerVersionAvailable`，**而不是重新生成一份仍然不可移植的 baseline**。

baseline 重新生成后只剩 **5 条**，全部是 app 模块内的相对路径、可移植：
`RedundantLabel` / `ChromeOsAbiSupport` / `MergeRootFrame` / 2 条 `UseTomlInstead`。
**本机与 CI 现在都是「5 条过滤、0 新增」。**

⚠️ **代价（明确写下，避免日后当成没人提醒过）**：真正需要关注的依赖升级
**包括安全更新**不再由 lint 提醒，改为跟随 RN 侧节奏人工评估。
若要恢复提醒，应改用「只对 app 模块内依赖生效」的方式，
**不要把 baseline 退回不可移植状态**。

**baseline 仍是技术债台账而非豁免** —— 剩下 5 条的清理属后续波次。

顺带修掉：`expo-dev-client` 声明了 `org.webkit:android-jsc:+` 这个可选依赖，
但本工程用 Hermes、`jsc-android` 未安装也无对应仓库，任何需要解析它的任务
（实测 lint 的 `generate*LintModel`）都会失败。已在根 `build.gradle` 全局排除。

### 2.10 G1 fast gate CI（**已激活**，2026-08-10）

`.github/workflows/android-ci.yml`。与 `tipsy-app` / `tipsy-iOS` 的 `ci.yml` **分开** ——
那些是 agentic workflow（issue/PR 智能体），本文件是纯构建门禁。

序列：**lint（硬门）→ assemble googlePlayDebug → release manifest → 单测
→ `:tipsy-auth` 桥单测 → `skipped=0` 守卫**。

**首次真绿**：[run 31373202424](https://github.com/Lightspeed-Intelligence/tipsy-Android/actions/runs/31373202424)
—— 22 步全过，**36 分钟**（冷缓存）。核实的输出：

```
tipsy-app pin=a4eb9055d actual=a4eb9055d
Lint found no new issues (and 5 errors filtered by baseline)
MergedManifestTest: 5 条，跳过 0 条
LiveAppSafetyTest: 3 条，跳过 0 条
```

`pull_request` / `push` 自动触发已生效（开 PR #11 时自动起了一次 run，
非手动触发）—— **G1 从此构成真门禁**。

> ⚠️ **36 分钟只有一个数据点**，且是冷缓存首跑。后续有 Gradle 缓存应更快，
> 但**不做承诺**。原记录的「本机模拟 1m59s」不可用作 CI 耗时参考 ——
> 它模拟的只是 Gradle 那几步，不含 checkout / `npm ci` / NDK 安装。

#### 激活过程抓出三个缺陷（**全是本机绿、CI 红的类型**）

| # | 缺陷 | 症状的迷惑之处 |
| --- | --- | --- |
| 1 | `PAT_TOKEN` 存了**空值** | `gh secret set NAME` 无值时靠 TTY 弹提示；非交互环境从空 stdin 读了空串，**且正常退出** |
| 2 | `git submodule sync` **覆盖** URL → 仍走 SSH | 报 `Permission denied (publickey)`，**看着像凭据没配**，实际凭据正常、只是没被用上 |
| 3 | lint baseline 用 `$HOME` 绝对路径 | 见 §2.9 订正 |

**#2 的根因值得记住**：`sync` 会把 local config 的 url 覆盖回 `.gitmodules`
里的值（SSH URL）。W0 原先的顺序是「先 `git config` 设 HTTPS → 再 `sync`」，
等于自己撤销刚设的值。已本地复现验证：调换顺序后走 HTTPS（用假 token 报的是
HTTPS 认证失败而非 `publickey`，证明链路确实换了）。

iOS 用全局 `insteadOf` 改写，**不依赖 local config**，所以不受影响 ——
「与 iOS 同构」这个判断**掩盖了差异**：更窄的做法需要更小心的顺序。

顺带加两条断言：子模块 SHA 必须等于 pin、工作树非空（`package.json` 存在）。
`git submodule update` 在某些失败模式下会「成功」但留下空目录，
那样要到 `npm ci` 才炸，**报错离根因很远**。

**这三个都是 W0 遗留**（#2/#3）或配置环节引入（#1），在 CI 真跑之前一直藏着。
方案 §5.4 的「`NOT RUN` 不等于通过」在这里得到三次实证。

#### `:tipsy-auth` 桥单测此前**完全不在 CI 里**（已补）

那 15 条测试住在 submodule 里，但**它是壳的一部分** —— 契约、registry、
主线程切换都由本仓的壳消费。其中 `LiveAppSafetyTest` 守的是本项目**最高危**的
失败模式：模块会被 autolink 进现网三个 RN 包，那里没有壳、不注册 provider，
此时 `isShellHost()` 必须为 false；若为 true，现网 App 把 auth 交给一个不存在的壳
→ **直接掉登录**。这条断言不进 CI 等于没有防线。

`skipped=0` 守卫从只覆盖 `MergedManifestTest` 扩成同时覆盖两个 suite，
并加了 `tests=0` 检查（一条没跑也是假绿）。守卫逻辑已验证在
「文件缺失 / 有跳过 / 零测试」三种场景下**确实会失败** ——
一个只会通过的守卫没有价值。

`pull_request` 的 `paths` 含 **`tipsy-app`**：桥模块改动不动本仓任何 path，
但 pin 前进一定伴随这个文件变化。漏了它会让「只改桥」的 PR 不触发 CI。

**范围取舍**：assemble 只跑单个 flavor，三 flavor 全量与 release 打包留 G3 nightly ——
PR 门要快。**代价是 flavor 专属与 release 专属问题不由 G1 拦**（§2.8 抓到的 release
暴露 PreviewActivity 正属后者），**nightly 必须补上三 flavor + release 全量**。

**过程中修掉一个「假绿」隐患**（正是 §5.4「NOT RUN 不等于通过」警告的情形）：
`MergedManifestTest` 原先把 variant 写死成 `directApkRelease` / `directApkDebug`，
而 CI 只 assemble `googlePlayDebug` —— 实测 **5 条断言里 4 条被 `assumeTrue` 跳过，
而跳过在 JUnit 里算通过**，等于断言静默失效。两处修正：

1. 断言改为「任一同 build type 的 variant」，与 flavor 解耦
2. 同时识别 AGP 的**两个**输出目录：`merged_manifest/`（单数，`process*MainManifest`
   产物，只跑 manifest 任务即有）与 `merged_manifests/`（复数，打包产物）——
   只认复数那个会导致「只跑 manifest 任务时断言被跳过」

CI 侧再加一道防线：显式校验 `MergedManifestTest` 的 `skipped=0`，跳过即失败。

**前置条件：本仓自己的 `PAT_TOKEN` secret —— ✅ 已配（2026-08-10）。**

⚠️ **那个 PAT 不在 @WishQi 的个人账号下** —— fine-grained 与 classic 两个列表均为空。
但 `tipsy-iOS` 的 `eas-build.yml`（唯一真正用它拉子模块的 workflow）**2026-08-07 仍成功运行**，
说明该 PAT 有效且属于 org 内其他成员（org 共 30 人）。
所以要么向持有者取得该值，要么新建一个有 `tipsy-app` 读权限的 token。

secret 是**按仓库**隔离的，没有跨仓引用语法 —— `tipsy-iOS` 上的同名 secret 在本仓
workflow 里不可见；内置 `GITHUB_TOKEN` 也只对本仓有权限，读不了私有的 `tipsy-app`。
实测本仓可继承的 org 级 secret 只有 `ANTHROPIC_API_KEY` / `ANTHROPIC_BASE_URL` /
`FEISHU_LLMDOC_WEBHOOK_TOKEN`，**不含 `PAT_TOKEN`**（它是 `tipsy-iOS` 的 repo secret）。

**与 `tipsy-iOS` 同构** —— 它的 `ci.yml` / `eas-build.yml` 都是用 repo 级 `PAT_TOKEN`
改写 submodule URL 走 HTTPS(runner 无 SSH key)。该 PAT 是 **classic token**。

⚠️ **`tipsy-app` 上那个只读 deploy key(`eas-submodule-ro`)状态是 `Never used`** ——
它是当初为 EAS 建的残留,**不是在用的链路**。iOS 仓的 secrets 里也没有任何 SSH 私钥。
别误以为 SSH/deploy key 那条路在本环境验证过。

#### 凭据现状（2026-08-10 订正，与原记录不同）

> 原记录称「同一 PAT 值在 `tipsy-iOS` 与本仓各存一份，轮换必须两个仓都改」。
> **这条已不适用。**

本仓的 `PAT_TOKEN` 是**独立签发**的 classic token（只勾 `repo` scope，够拉私有子模块），
**与 `tipsy-iOS` 的同名 secret 不是同一个值**。

- ✅ **轮换本仓这个不影响 iOS，反之亦然** —— 比共用一个值更清晰、影响面更小
- org secret 需 `admin:org` scope（当前账号没有，实测 403），故仍走 repo secret
- 那个「iOS 的 PAT 不属于 @WishQi 个人账号」的旧结论不再是障碍：**不需要去找它的持有者取值**

⚠️ **`gh secret set` 有一个会导致明文泄漏的坑（2026-08-10 真踩过）**：

第一个参数是 secret 的**名字**，值必须经**交互提示或 stdin** 传入。
把值直接写成第一个参数会创建一个**以 token 明文为名**的 secret ——
而 **secret 的名字是可读的**（值不可读），等于当场泄漏。

且 `gh secret set NAME` 不带值时靠 **TTY** 弹提示：**非交互环境下它从空 stdin
读到空串、存进去、并正常退出**（退出码 0、无输出），看着像成功。
症状是 CI 里 `PAT_TOKEN:` 后面空白。

正确写法：
```bash
pbpaste | tr -d '\n' | gh secret set PAT_TOKEN --repo <owner>/<repo>
```
`tr -d '\n'` 必要 —— 复制时易带尾随换行，token 混入换行会认证失败且报错不提示原因。

泄漏那次已吊销重签、删除错误条目，并验证旧 token 返回 401。

技术细节：`.gitmodules` 用 SSH URL 而 CI 只有 HTTPS token，故 workflow 不用 checkout 的
`submodules` 选项，而是手工把 submodule URL 换成带 token 的 HTTPS（只改本地配置，
不写进 `.gitmodules`）。auth 形式用 `x-access-token`（已实测对私有仓有效）。
**缺该 secret 时 workflow 明确报错并说明原因，不静默跳过。**

**不得用 `--depth 1` 拉子模块**（这条经验来自 `tipsy-iOS` 的 `eas-build.yml`）：
子模块 pin 常滞后于 `tipsy-app` 的 main tip —— 实测当前 pin **落后 `origin/main` 175 个
commit**，浅拉只能拿到 tip、取不到 pin 的那个 commit，CI 会直接在子模块那步失败。

与 iOS 的一处有意差异：iOS 用 `git config --global ... insteadOf` 全局改写所有
`git@github.com:` 前缀；本仓只改 `submodule.tipsy-app.url` 一项，范围更窄、不影响
其他 SSH 操作。

⚠️ **但两者不是「行为等价」（原记录如此写，已订正）**：本仓的做法**依赖 local
config**，而 `git submodule sync` 会把它覆盖回 `.gitmodules` 的 SSH URL ——
所以**必须先 `sync` 再 `git config`**，顺序颠倒就静默退回 SSH。
iOS 的 `insteadOf` 不依赖 local config，没有这个顺序约束。
「与 iOS 同构」的判断曾**掩盖了这个差异**，见 §2.10 缺陷 #2。

另：`cmake` 版本已钉进 `libs.versions.toml`（原先只有 `ndk`）。AGP 默认挑「已装的最高版」，
本机与 CI 不一致会产生难复现的构建差异。workflow 从 catalog 读取并做空值检查 ——
grep 未匹配时 `cut` 输出空串**不报错**，静默装错版本比直接失败更糟。

### 2.11 W1-P0：auth 桥接通（已完成）

**W1 的开关打开了。** RN 侧 `isShellAuthHost()` 返回 true → 那 55 个文件里已存在的
壳适配分支自动激活（方案 §7.2）。API 24 实测:

```
bridge probe: {"present":true,"isHost":true,
               "hasGetValidToken":true,"hasPopSurface":true,"lang":null}
```

(`lang: null` 是**正确的** —— 语言真值属 P5，此时壳无意见、RN 沿用自己的判定。)

RN 侧 `modules/tipsy-auth/android/`（分支 `feat/tipsy-auth-android`）三层结构与 iOS 同构：
契约拆四个接口(Auth/Navigation/Lifecycle/Env)、registry(壳注册 + 事件广播)、
Expo Module DSL(12 个必须方法)。iOS 用 NotificationCenter 广播，Android 无等价物，
改用进程内 `CopyOnWriteArrayList` 监听器。

**最高危项已用测试钉死**：模块合并后会被 autolink 进**现网三个 RN 包**，那里没有壳、
不注册 provider。此时 `isShellHost()` 必须为 false、与「模块不存在」等价 ——
否则现网 App 把 auth 交给不存在的壳会**直接掉登录**。`LiveAppSafetyTest` 专测这条。
单测 9 条全绿(skipped=0)。

**两条实现纪律**（都是从 iOS 教训来的）：
1. **未实现项绝不静默 no-op** —— debug 抛 `NotImplementedError`，release 记 error
   日志并继续。静默 no-op 的典型症状是「点了没反应」，不报错不崩溃，只能靠用户
   反馈发现(iOS 在 ChatDetail 与 Comments 真实踩过)
2. **严格区分「返回 null」与「未实现」** —— `getValidToken()` 返回 null 是合法业务态
   (当前未登录)；`requestLogin()` 未实现是能力缺失，必须可见

**provider 注册时机是个坑**：RN 侧 `isShellAuthHost()` 会**缓存首次结果**(它在高频
render 路径上被调用)，注册晚于首个 Surface 会让 JS **永久**认为不在壳内 ——
这类 bug 只在冷启动竞态下出现。故注册放在 `Application.onCreate` 内、生命周期分发之前。
且壳必须**自持强引用**：registry 侧是弱引用(对齐 iOS 的 `weak var`)，被回收会让
`isShellHost()` 悄悄变回 false。

顺带核实一处 W0 遗留疑问：`reactHost` getter 每次调 `createReactHost` 看似会新建实例，
实际 `ExpoReactHostFactory` 内部有 `if (reactHost == null)` 缓存(已核实
`ExpoReactHostFactory.kt:85`)，**单 Runtime 不变量成立**。

### 2.12 MMKV 互操作性已验证（W1-P2 机制部分）

**§2.4 迁移路径最大的技术未知项已消除**：壳的 Kotlin 代码能读到 `react-native-mmkv`
写的数据。API 24 真机 instrumented test **3/3 通过**（skipped=0）。

| 事实 | 值 | 来源 |
| --- | --- | --- |
| MMKV 目录 | `filesDir/mmkv` | `HybridMMKVPlatformContext.getBaseDirectory()` |
| 默认实例 id | `mmkv.default` | `MMKVFactory.nitro.d.ts` 的 `@default` |
| **原生库** | **`io.github.zhongwuzw:mmkv:2.2.4`** | `react-native-mmkv/android/build.gradle:142` |

⚠️ **原生库是 fork,不是腾讯官方 `com.tencent:mmkv`**（但包名仍是 `com.tencent.mmkv`,
所以 import 看着像官方的）。版本已钉进 `libs.versions.toml` 并**显式压制
lint 的 NewerVersionAvailable**（它建议升到 2.4.1）—— 这个版本号是与 RN 侧的
**耦合约束**,不是"越新越好"的普通依赖。升了它壳可能读不了 RN 的文件,
**且不报错**,只表现为用户升级后掉登录。

**三种历史形态解析**（裸串 / `{token}` / `{state:{token}}`）单测 10 条 + 真机往返 3 条全绿。
一个易错边界已覆盖:`JSONObject.optString` 遇 JSON null 返回**字面量 `"null"`**,
不特判会把它当成一个叫 `null` 的 token 存进去 —— 静默错值,后续请求全 401 且难反推。

**这个验证不能过度解读**（按方案 §5.4 纪律）:
- ❌ **不**证明能读**真实历史数据** —— 需真登录过的现网包,当前拿不到（§2.5）
- ❌ **不**构成覆盖升级证据 —— 需真实签名,**已决定推迟到上线前**

它证明的是**机制**。P2 状态是「机制已验证,真实数据待验」,**不是完成**。

### 2.13 W1-P1：auth 契约（closeout 已实现；组合验证已于 §2.22 补上）

**壳成为 token 的唯一刷新者与持久化者。** 原落地 checkpoint 的单测
**62 条全绿（skipped=0）**，人工门禁四步全过；§2.18 的 correctness closeout
随后修改了同一实现，当前组合结果尚未动态验证。

落地的 6 个类（`shell/auth/` + `bridge/`）：

| 类 | 职责 | 关键约束 |
| --- | --- | --- |
| `Jwt` | payload 解析 + 过期判定 | 逐行对齐 RN `lib/auth/jwt.ts`，阈值 **5 分钟** |
| `ShellTokenStore` | token 真值 + single-flight 刷新 | 见下方三条语义 |
| `Generations` | 双轨闸门 | `auth` / `mutation` **互不替代** |
| `AuthStateHub` | 登录态订阅 | W2 五 Tab 直接用 |
| `MmkvTokenPersistence` | 读写 `token-storage` | 裸字符串形态，只碰这一个 key |
| `RefreshTokenApi` | `POST /auth/refresh_token` | `token` header（非 Bearer）+ envelope |

**三条照搬 RN 而非重新设计的语义**（偏差会产生只在特定时间窗出现的问题）：

1. **已过期 token 不走刷新**。RN `isJwtExpiringSoon` 的条件是
   `exp - now > 0 && < 300` —— 已过期返回 **false**。持久层保留原值且不主动刷新，
   但壳的 `getValidToken()` 按 Android/iOS bridge 契约返回 null；否则 WebView/SSE 等
   不经过 axios 的消费者会直接发送失效值。Native HTTP 在起飞前再做一次校验，覆盖
   await 后换号/恰好过期窗口。
2. **刷新失败但旧 token 未过期 → 返回旧 token**（`jwt.ts:127-129`）。
3. **不重试**。RN 侧没有重试，加了会让登录态在网络抖动时行为分叉。

**401 归属判定是本步最高危项**：`notifyServerAuthRejectedForToken` 只在被拒 token
仍是当前 token 时登出。旧账号迟到的 401 若无条件登出，会把刚登录的新账号踢下线 ——
用户看到"刚登录就被登出"且无法复现。已用测试钉死，另有一条断言**任何日志都不含 token**。

**`logout()` 与 `clearToken()` 刻意不同**：前者清 token + 收栈 + 广播一次 loggedOut；
后者只清 token。合并两者会让删号流程在中途被强行弹栈。

#### 顺带修掉的桥缺陷（跨仓，tipsy-app 侧）

`TipsyAuthModule` 的 6 个 UI/导航方法在 `AsyncFunction` 里**直接调 provider，
无主线程约束** —— 而 Expo 的 `AsyncFunction` 默认在后台线程执行。
iOS 契约对同组方法全标了 `@MainActor`（已核实 `TipsyAuthModule.swift`），Android 漏了。
这是 PR #1614 的审查机器人提的 REQUEST_CHANGES，此前未处理。

处理：契约加 `@MainThread` 标注，桥侧统一经 `dispatchOnMain` 切主线程，
新增 `MainThreadDispatchTest`（6 条）。**用 `withContext` 而非 `Handler.post`** ——
后者发射后不管，JS 的 await 会在导航真正发生前 resolve。

⚠️ **`logout()` 在契约里不是 `@MainThread`**（它主要做存储清理），但它要收栈，
所以**自己切主线程**。桥的 `onMain` 只覆盖标注过的方法，不会替它做。

#### 三处踩到的「假绿色」诱惑（都已按方案 §5.4 拒绝）

| 遇到什么 | 诱惑 | 实际做法 |
| --- | --- | --- |
| `android.util.Base64` JVM 单测抛 stub 异常 | `returnDefaultValues = true` | `Jwt` 自带 base64url 解码（`java.util.Base64` 要 API 26 > minSdk 24） |
| `android.util.Log` 同样抛 | 同上 | provider 注入 `Logger` 抽象，顺带让日志可断言 |
| token 判定用真实系统时钟 | 测试跟着改时间 | 注入 `nowSeconds` —— 否则"刷新中过期"这类分支根本测不了 |

`returnDefaultValues` 会让**所有**未 mock 的 Android API 静默返回默认值，
是方案 §5.4 点名的假绿色（§2.12 为 `org.json` 记过同一决定）。

#### coroutines 版本是耦合约束，不是普通依赖

lint 硬门抓到新增的 `kotlinx-coroutines-test` 有更新版可用。**没有升**：
`expo-modules-core/android/build.gradle:191-192` 用 `api` 暴露 coroutines **1.7.3**，
那是壳运行时实际加载的版本。声明更高版本会经 Gradle 版本冲突解析**把整个 RN
运行时的 coroutines 顶上去** —— 抬升 RN 生态运行时依赖不属 W1 范围。
已钉在 `libs.versions.toml` 并按 mmkv 同样的方式 `#noinspection` 压制。
**lint-baseline 未新增任何条目**（当前 5 条）。

#### 新增的 BuildConfig 字段

`API_BASE_URL` 按 build type 注入（debug/debugOptimized → dev，release → prod），
值与 `tipsy-app` 的 `.env.*` 一致。**壳与 RN Surface 必须命中同一后端** ——
不一致会让原生页与 Surface 看到不同数据，且两边都不报错。
这不是凭据，是公开端点；真凭据仍走 CI secret（方案 §12.7）。
注意 RN plugin 引入的 `debugOptimized` 也要给值，否则编译不过。

#### P1 未做的（明确边界）

- `requestLogin()` 仍未实现（W2 原生 Login 页）—— debug 抛、release 记 error
- **SecureStore 兜底读未做**（P2）：覆盖升级设备上 SecureStore 里的 token
  目前读不出来，那批用户会被当作未登录。**这是已知缺口，不是 bug**
- `mutationGeneration` 已建但**无消费方** —— 它的使用点（ChatList 左滑删除/置顶、
  Profile 卡片菜单）都在 W3
- 权限总数仍 **51 条**，未因本步新增

**顺带修掉一处「假绿色」诱惑**:`android.jar` 里的 `org.json` 是抛异常的 stub,
JVM 单测会全红。**没有**用 `testOptions.unitTests.returnDefaultValues = true` 去绕 ——
那会让所有未 mock 的 Android API 静默返回默认值,正是方案 §5.4 点名的假绿色。
改为引入真实 `org.json:json` 测试依赖。

### 2.14 W1-P6：network 层（closeout 已实现；组合验证已于 §2.22 补上）

`shell/network/` 七个类。原落地 checkpoint 新增/验证 **46 条**网络单测，
当时 app 单测共 **156 条**、skipped=0；§2.18 随后修改了同一实现，
这组数字是历史 gate，不代表当前合并 worktree 已验证。

| 类 | 职责 |
| --- | --- |
| `AuthMode` | 三鉴权模式枚举 |
| `ApiClient` | OkHttp 请求 + header + envelope 解析 |
| `ApiEnvelope` | `{code,msg,data}` 与已知业务码 |
| `ApiException` | 分型异常（业务码保持可分辨） |
| `ApiErrorGate` | 401/402 唯一汇聚点 + 独立防抖 |
| `ScalarCoercion` | 标量漂移容错 |
| `LaneHeader` | BOE 泳道 header（含安全白名单） |

#### 选型：用 OkHttp，**不引 Retrofit**

OkHttp **已在依赖树里** —— RN 自己就用它，实测三个来源（3.14.9 / 3.9.1 / 4.9.2）
全部解析到 **4.12.0**。所以这不是新增依赖；坚持用 `HttpURLConnection` 也省不了体积。

且壳与 RN **共享同一个 `OkHttpClient`**（经反射取 `OkHttpClientProvider`，
失败则退化为自建、不抛）。各起一套会让连接池 / DNS 缓存 / TLS session 变成两份，
还会让「同一后端两条链路」的问题难查（如 RN 侧能连、原生页超时）。

不引 Retrofit 的理由：
1. **统一 envelope 与它的模型冲突** —— HTTP 200 + `code != 0` 是常见组合，
   接进去要写 `CallAdapter` + `Converter`，代码量不比手写少还多一层抽象
2. **三鉴权模式**的 `OPPORTUNISTIC` 语义要在 Interceptor 里做，与 Retrofit 无关
3. **标量漂移容错**要自定义反序列化，Retrofit 只是转交 Moshi/Gson

W1 只需一个 endpoint（`/auth/refresh_token` 已在 P1 写好且**刻意不走本层** ——
它是 auth 前置，走这里会形成「取 token → 刷新 → 取 token」循环）。
W3 若 API 面大到手写吃力，届时业务形态已清楚再评估。

#### ⚠️ `axiosPublic` 不等于「永不带 token」

三模式存在的**唯一理由**。iOS 把 `/search/character_search` 实现成
`authorized: false`，结果**最近搜索历史永久为空** —— 那个接口带 token 才会
把搜索词记入历史。不报错、不崩溃，功能静默失效。

正确映射：`axiosPublic` → `OPPORTUNISTIC`（**有 token 就带，没有也照发**），
不是 `NONE`。已用 `ApiClientTest` 的真实 HTTP 往返钉死「实际发出了什么 header」。

#### 逐条对齐 RN 的实测细节

- **token 走 `token` header**，不是 `Authorization: Bearer`
- header 大小写在 RN 内部就不一致：`axios.ts:116` 是 `Platform`（大写），
  `apis/auth.ts:55` 是 `platform`（小写）。两者都在现网跑 → 后端不区分大小写。
  壳照抄主路径（`axios.ts`）的写法
- **业务码 9（角色卡超限）不在 RN 的 `AppRespCode` 枚举里** ——
  它是 `axios.ts:221` 的字面量。别因为「枚举里没有」就当它不存在
- `REQUIRED` 无 token 时**不发请求**（对齐 `axiosAuth`）：发一个必然 401 的
  请求毫无意义，还会触发 auth 兜底造成误登出路径

#### lane header 的白名单是**安全约束**

`lane.ts:43-68` 的判定要全部满足：https + 无 userInfo + 端口 443/空 + host 白名单。
目的是**防 lane 值泄漏到第三方域**（lane 名暴露内部测试环境标识）。

⚠️ **两个 host 的匹配规则不对称**（实测 `lane.ts:59-63`）：
`api.dev.fantacy.live` 含子域，`api-studio.infra.fantacy.live` **仅精确匹配**。
统一成「都允许子域」会扩大泄漏面；统一成「都精确」会让 API 子域静默失去泳道。

（写这段时我一开始把 studio host 猜成 `studio.dev.fantacy.live`，实际是
`api-studio.infra.` —— **不要凭 base URL 推断常量**。）

#### 401/402 汇聚与防抖

两个入口（原生页经本层 / RN Surface 经桥）现在由 Application 注入同一个
`ApiErrorGate`。401 按 token 指纹区分会话：同 token 的错误浪潮去重，A 的窗口不吞 B；
旧 token 的终端处理返回 false，不占当前会话窗口。402 独立防抖并在终端显式切主线程。
这些 closeout 改动已写测试，但组合 Gradle 验证尚未运行，见 §2.18。

- **不带 token 的 401 不得触发登出**（对齐 `axios.ts:32-33`）：
  无法判断会话归属，登出会踢掉刚登录的新账号
- **401 与 402 各自独立防抖**：合用一个窗口会让 401 后 3 秒内的付费墙不弹

#### P6 接线时踩到并修掉的一个真 bug

`notifyServerPaymentRequired()` 原本标着「W1-P6 未实现」，
而 `notImplemented` 在 debug 下**会抛** —— 一旦接上 `ApiErrorGate`，
**每次收到 402 都会让 App 崩**。已实现为经 Router 导航（目标 Surface 属 W4，
Router 会明确拒绝并记日志，不静默）。`ShellAuthProviderTest` 加了一条
在 `isDebug = true` 下的断言防止改回去。

#### 本步同时收口的

`apiBaseURL()` 从返回 null 改为**壳侧真值**（`BuildConfig.API_BASE_URL`）。
RN 的 `constants/api.ts` 会优先用它 —— 保证原生页与 Surface 命中同一后端。

新增测试依赖 `okhttp3:mockwebserver:4.12.0`（版本与 RN 解析出的 okhttp 对齐）。
**用真实 HTTP 往返而非 mock OkHttp 接口** —— 后者只会验到自己写的 stub，
验不了「实际发出了什么 header」。

### 2.15 W1-§12：RNSurfaceFragment 四项机制（主体已落地，实例关闭链待收口）

`RNSurfaceFragment` 从 36 行 stub 补齐了 UUID、占位、reappear 与 props builder。
这些是生产 Surface 的前置机制，但当前业务接线尚不能据此标 production-ready。

单测 13 条（`SurfaceContractTest` 7 + `ReappearPolicyTest` 6）。该 checkpoint 当时
app 单测共 **169 条**、skipped=0；这是历史 gate，不代表当前合并 worktree 已验证。

| 要求 | 实现 |
| --- | --- |
| §12.1 `surfaceInstanceId` | `SurfaceContract.newInstanceId()`，每次打开新 UUID |
| §12.2 首帧协议 | `surface_placeholder.xml` + 等首个非零尺寸子节点后**单次**淡出 |
| §12.3 `onSurfaceReappeared` | 非首次 `onResume` 发射，payload 是**组件名** |
| §12.4 capability handshake | `SurfaceContract.buildInitialProps()` |

#### 真机验证（API 37）

- Surface 挂载正常（`RN Surface OK` 渲染出来），无崩溃
- 切后台再回前台 → 实测日志 `发射 onSurfaceReappeared: surface=DebugSurface`

⚠️ **payload 必须是组件名，不是 instanceId**。RN 侧
`useShellSurfaceRefocus.ts:39` 比对的是 `payload.surface !== surface`，
传 instanceId 会让 hook **永不匹配** —— 表现为「事件发了但页面不刷新」，
而且两边都不报错。该事件的去重粒度是 Surface **类型**。

#### `popSurface` 改为按实例判定（§12.1）

Activity 已具备 `surfaceInstanceId` 比对，不符则忽略并记日志；但真实 TS
`popSurface()` 无参数，Android bridge 固定传 `null`，会绕过这层比对。
因此“迟到旧实例不得关闭新实例”仍未闭环，留给下一 closeout packet。

iOS 的闸是**类型判定**，于是「迟到的旧实例事件弹掉了新打开的同类型页」——
用户点返回后又被弹掉一层，后来靠 `closingRef` 补。Android 从第一天按实例判定。

#### 首帧不用固定延时猜（§12.2）

判据是「RN root view 有了非零尺寸的子节点」。固定延时短了闪白屏、长了拖慢首帧，
且真机与模拟器的合适值不同（iOS `b2773e1` 处理过同一问题）。
`isRevealed` 守着只淡出一次 —— 重复淡出会让快速切换时闪烁，
且 listener 不摘会一直跑在每帧上。

占位层刻意**不放 loading 指示器**：首帧目标是几十毫秒级，转圈一闪而过更像卡顿。

#### ⚠️ 一处自我订正：旋转不重建 Fragment

实现时我写了「旋转会重建 Fragment，所以标记要存 saved state」——**该前提不成立**：
`MainActivity` 的 `configChanges` 已含 `orientation|screenSize`（manifest:52），
转屏不重建 Activity/Fragment。

saved state 仍然需要，但理由是**进程重建**（App 后台被杀、用户从最近任务返回）。
注释与测试名都已改准。若照原来的错理由写，日后有人删掉 `configChanges`
就会失去这层保护而无人察觉。

#### token 不进 initial props（§12.4）

`SurfaceContractTest` 有一条断言 key 常量里不含 `token`/`auth`/`jwt` 字样。
挡不住硬编码字符串，但能挡住「顺手加个 KEY_TOKEN」。
initial props 会进 `Bundle`，可能落入 saved instance state、ANR trace、崩溃日志。

### 2.16 W1-P5：i18n（已完成）

`shell/i18n/` 六个类 + 26 份词条资源。该落地 checkpoint 新增并验证
**42 条** i18n 单测，当时 app 单测共 **211 条**、skipped=0；这是 P5 的历史 gate，
不代表合入 §2.18 后的 244 条组合结果已执行。

| 类 | 职责 |
| --- | --- |
| `LanguageCodes` | **两条** normalize 规则 + 26 个 supported 码 |
| `L10n` | 查表 + fallback 链 + 语言状态 + 广播收口 |
| `LocaleTable` | 一个语言的词条表（宽松逐值解析） |
| `AssetLocaleLoader` | 从 `assets/locales/<code>.json` 读 |
| `AccountLanguageReader` | 从 `user-storage` 信封读账号语言（只读） |
| `LocalizedText` / `rememberLocalizedString` | Compose 自订阅组件 |

#### ⚠️ `normalizeLanguageCode` 实际是**两条**规则，方案文档记漏了

方案 §4.8 与 W1 计划 §7.2 都只提了一个函数，但 `i18n-index.ts` 里有两条对
**同一输入给不同答案**的规则：

| 场景 | RN 出处 | 简体 `zh` 的结果 |
| --- | --- | --- |
| 账号语言 / 任意码 | `normalizeLanguageCode`（`:64-75`） | **`zh-tw`** |
| 启动读设备 locale | `defaultLanguage`（`:118-135`） | **`en`** |

即：账号 `language_code` 存 `zh` 的用户看繁体，设备语言是简体的新用户看英文。
**这不是 bug，是两个不同场景的产品决策**（iOS 的 `L10n.swift:56-79` 同样拆两个函数）。
只实现一条的后果是简体设备用户看到繁体 —— 而这在英文环境测试里看不出来。
`LanguageCodesTest` 有一条专门的**对照测试**钉死这个差异。

#### `en` 也必须查表

实测 `en.json` 的 1838 个 key 里 **94 个 key ≠ value**（如 `Currently unavailable`
→ `More to come`）。拿 key 当英文文案会让这批词条显示错文案，且因为
「看起来像正常英文」而不会被发现。`LocaleAssetsTest` 断言导出产物里
确实存在这类词条 —— 否则这条约束会被悄悄绕过。

#### 导出脚本双壳共用（2026-08-11 决定）

`tipsy-app/scripts/export-shell-locales.mjs` 改为按**探测仓库标记目录**决定输出：
iOS → `Tipsy-iOS/Resources/Locales/`，Android → `app/src/main/assets/locales/`。
`SHELL_KEYS` **不按平台分叉** —— 分叉的代价是「某平台非英文用户静默看到英文」，
多导出几条未用词条只是几 KB。实测 26 个语言 × 180 条，0 缺失。

**为什么用 assets 而不是 `res/raw` / `strings.xml`**：资源名不允许连字符
（`zh-tw`/`pt-br` 要改名再映射），而 key 是含空格标点的英文原文，做不成合法资源名
（方案 §4.8 已明确排除）。也不用 `values-<qualifier>`：壳的语言真值来自**账号**，
让系统按设备 locale 挑资源会与账号语言打架。

#### 语言真值链与一处已知缺口

真值在**后端**，本地是镜像：设置页 → `POST /user/set_language` → `updateUserInfo()`
重拉 → `user.language_code` → `user-storage.state.languageCode`
（`useChangeLanguage.ts:57-72` + `store/user.ts:187`）。

**语言设置页刻意不迁**（方案 §8.1），仍在 `SettingsSurface` 里。所以壳**只读**
这个 key，不写 —— 写 Zustand 信封必须 merge（§4.6），那属 P2。

⚠️ **桥契约没有 JS→壳 的语言通知方法**（已核实 `modules/tipsy-auth/src/index.ts`
只有壳→JS 的 `onLanguageChanged`）。当前处理：`MainActivity` 挂
`OnBackStackChangedListener`，Surface 容器出栈时重读。
**用 listener 而不是在 `popSurface()` 里调** —— 返回键有两条路径（桥的 popSurface /
系统返回键直接走 FragmentManager），只挂前者会漏掉「按系统返回键退出设置页」。
若将来该时机不够（如设置页不关就切 Tab），再考虑给桥加可选方法，
**不要为了「更干净」提前改跨仓契约。**

#### Compose 组件从第一天就做

方案 §4.8 与 W1 计划 §7.3 都要求「不让每个页面手挂 listener，iOS 后期才补，
Android 第一天就做」。已提供 `LocalizedText` 与 `rememberLocalizedString`。

⚠️ **不要写 `Text(L10n.t(key))`** —— 那是普通函数调用，Compose 不知道它读了
可变状态，语言切换后已组合的文本**不会重组**，表现为「切了语言当前页没变，
退出重进才变」。这类 bug 在切完立刻返回的路径下很容易漏测。

#### 两阶段初始化，且语言**不**作为缓存闸

`TipsyApplication.bootstrapI18n()` 先按设备 locale 起步，再按账号语言覆盖
（对齐 RN 两段式）。必然结果是**首屏可能读到过渡语言** ——
方案 §4.6 与 W1 计划 §7.6 明确：**不要拿语言当缓存闸**（iOS 那样做导致
「第二次启动永远没有种子」）。壳当前无缓存层，只在代码里留了约束注释，
**没有造一个没人用的抽象**。

**合并复核发现的后续风险**：全新安装时 `bootstrapI18n()` 会先打开
`LegacyMmkvStore`；若 MMKV 目录尚不存在，它会把“不可用”实例缓存到进程结束，
而随后的 token persistence 才创建目录。这可能让同一进程首次写入账号语言后仍读不到，
需另包让 legacy store 可重试或先统一初始化 MMKV。此项不由冲突解决顺手改行为。

### 2.17 P7 Qt / P8 Sentry 推迟到业务迁移后（2026-08-11 决定）

> **决策**：Qt 埋点接线与 Sentry 原生实例推迟到业务代码迁移（W2/W3）完成之后。
> **决策人**：项目 owner（用户）。**风险 owner 同上。**

**进入 W2 的判据不受影响**：三条判据里的「root side-effect 表零 `UNKNOWN`」
—— **已决策推迟不等于 UNKNOWN**，按 W1 计划 §5.6 的格式写成显式推迟即可。
故 P7 收窄为「填表 + 记两条决策」，不是删除。

**两项的推迟成本不对称（重要）**：

| | Sentry | Qt |
| --- | --- | --- |
| 接入形态 | 单点（一个 `init`） | 埋点调用点散在**每个**业务页 |
| 推迟成本 | 干净，迁完补即可 | 现在不定调用点写法 → 迁完要回头改几十个页面 |

**对冲**：Qt 需要现在就定一个薄 `Analytics` facade，业务页照常调用，
Qt 接上前只在 debug 打日志。⚠️ **这一处刻意不遵循「未实现项 debug 抛异常」的
纪律**（§2.11 那两条实现纪律）—— 埋点每次事件都抛会让 debug 不可用。
~~**facade 尚未落地**，W2 第一个业务页开工前必须建。~~ ✅ **已落地**（§2.23）。

**两处已存在的静默洞（不是「还没做的功能」，2026-08-11 实测）**：

1. **Qt 的 `preInit` 在壳里一次都不会调。** `QtPackage` 只实现
   `createReactActivityLifecycleListeners`，而该回调**只由 expo 的
   `ReactActivityDelegateWrapper` 分发**（`ReactActivityDelegateWrapper.kt:53-54`）。
   壳没有 `ReactActivity` —— 用的是 `ReactFragment` + 裸 `ReactDelegate`
   （`ReactFragment.kt:47`），壳侧也搜不到任何 `ReactActivityLifecycleListener` 分发点。
   **所以开放问题 §12.1 的前提不成立**：不是「保留 listener vs 排除模块」二选一，
   而是「Qt 目前完全没初始化」。这正是方案 §4.2 拿 iOS 的 AppsFlyer 事故举例的
   那类失败模式。
2. **Sentry 的 JS 事件交给了一个从未 init 的原生 SDK。** 壳没有任何 Sentry 依赖
   （`app/build.gradle` 与 catalog 均无），但 `sentry_react-native` 被 autolink 进来，
   JS 侧 `src/surfaces/sentry.ts` 写 `autoInitializeNativeSdk: false` 且注释说
   「原生层由壳自持」。按 wrapper 实现（`wrapper.js:132-137`）这一支把
   `enableNative` 置 true 后返回 —— **Surface 里的 JS 报错既不上报也不报错。**

**已告知的代价**：W2/W3 那 32.6k 行迁移期间远端崩溃证据缺位，只能靠 logcat
与本机复现。**Sentry 的价值恰在迁移过程中最高**，而不是迁完之后。

### 2.18 W1-CLOSEOUT-1：实现完成（组合验证当时 NOT RUN，**已由 §2.22 兑现**）

执行包：[`../architecture/android-w1-closeout-ready.md`](../architecture/android-w1-closeout-ready.md)。

已实现：

- P9 前移除 ChatDetail 的 runtime enable，命中时明确拒绝且零 Surface 导航。
- refresh 的所有成功/失败/空值路径都受 auth generation + 当前 token 约束；迟到 A
  不得覆盖、返回或清掉 B；single-flight slot 在替换前会重验会话。生产 refresh
  使用独立 Main.immediate scope，HTTP 内部再切 IO，避免自动失效从后台线程改 Router；
  单个 waiter 取消不会清掉仍在飞的共享 refresh，生命周期取消也不会被当作刷新失败。
- token-aware 401 用原子 compare-and-clear，清理与收栈位于同一主线程顺序段；
  Native/RN 401/402 共用一个进程级 gate。
- bridge 对 expired/malformed token 统一返回 null，保护不经过 axios 的 WebView/SSE；
  Native HTTP 在发送前再过滤 expired/malformed/stale token，REQUIRED 零请求失败，
  OPPORTUNISTIC 省略 token 后继续。
- token clear 事件由唯一 Application listener 同步 RN Registry 与 AuthStateHub；bridge
  `clearToken()` 保持静默，完整 logout/自动失效各广播一次。

静态守卫覆盖 tracked diff、冲突标记、submodule pin/worktree 与 RN delta；
`a4eb9055d..95760a662` 只包含 locale exporter 变化，auth/network 契约未变。

**未执行组合验证**：Gradle、Kotlin/Java 编译、JVM 单测、lint、assemble、设备验证。
当时本包是“实现完成、组合验证待跑”。**该状态已被 §2.22 取代** —— main 上的 G1 已 22 步全绿，
这批实现现有 CI 层面的组合证据。

当前合并 worktree 静态可见 `app/src/test` 有 **244** 个 `@Test`，`tipsy-auth`
Android 子模块有 **15** 个；这里只是声明数量，**不等于执行通过**。

本包后仍保留的已知契约债：§3.5 目标顺序是 `clear → pop → emit loggedOut`，当前为
`clear → emit → pop`（listener 在 token 状态临界区同步分发）。当前 listener 只做
RN/AuthStateHub 的有界状态通知且整段位于主线程，未发现跨账号破坏；精确顺序放入下一
closeout，不得据此把 P1 整体标绿。另一个债务是 `notifyServerPaymentRequired()` 的
Promise 会在异步 gate/导航完成前 resolve；当前 RN 只消费 rejection，终端副作用仍切到
主线程，但后续若调用方依赖 Promise 完成语义，必须补成可等待链路。

### 2.19 W1-CLOSEOUT-2：Surface 上线前置（已完成，2026-08-11）

P9 的三层前置。**单测 17 条**（连同既有共 **228 条**，skipped=0）。

#### ⚠️ 比 initial props 形状更靠前的一层：组件不在包里

`app/build.gradle` 的 `entryFile` 原先指向 `index.surfaces.debug.js`，而那个文件
**只注册 `DebugSurface`**（`:59`）。所以任何指向业务 Surface 的路由都会去挂一个
**包里不存在的组件**。这是 W0 刻意的隔离（方案 §5.2「由所属 packet 切回」），
到这一步才该切。

已切到 `index.surfaces.js`，**两处同时改**：`app/build.gradle` 的 `entryFile`
（离线内嵌包）与 `TipsyApplication.getJSMainModuleName()`（Metro 直连）。
⚠️ 只改一处会出现「Metro 加载业务包、离线包却是自检包」的错配，
**debug 下看不出来**（Metro 那份是对的），只有 release 或关掉 Metro 才暴露。

实测切换后 bundle 从 27MB / 426 asset 起步，13 个业务 Surface 的组件名与业务入口
独有标记（`index.surfaces.js evaluated` / `align i18n to shell language`）都在包里。

⚠️ **风险面随之变大**：`index.surfaces.js` 顶层会跑 sentry init、i18n 初始化，
以及 `hydrateTags` / `hydrateCharacterBadgeConfigs` / `hydrateAvatarDecorationConfigs`
三个网络引导。**这三个内部都静默捕获失败** —— 失败不报错，只表现为标签行 /
角色徽章 / 头像框空掉（全新安装必现，升级安装因 MMKV 残留会被掩蔽）。
真机验收时要专门看这三条。

#### initial props 从嵌套 `route` 改为**平铺**

原实现把业务参数塞进嵌套的 `route` Bundle，而 RN 侧 **13 个 Surface 无一读
`props.route`**（全仓搜零命中）。它们一律读平铺的顶层 props：

| Surface | 必需 props（实测） |
| --- | --- |
| `ChatDetailSurface` | `characterId`（**非可选**，`:75`） |
| `CommentsSurface` | `targetType` + `targetId`（`:16-24`） |
| `SettingsSurface` | `initialScreen?` |
| `NotificationSurface` | `tab?` |

iOS 的 `makeInitialProperties()` 产出的正是平铺形状。**嵌套形状会让
`characterId` 恒为 `undefined`**，而 RN 侧不报错，只走「无参进入」兜底 ——
表现为「点某个角色却进了上次的会话」。

`CONTRACT_VERSION` **未递增**：嵌套形状从未被任何 bundle 消费过，
这是修正一个从未生效的字段布局，不是契约变更。

新增 `SurfaceProps`（route → 业务 props 映射）。**刻意返回 `Map` 而非 `Bundle`** ——
`Bundle` 在 JVM 单测里是抛异常的 stub，而这层映射正是最该被测的部分
（key 拼错、漏必填参数，两边都不报错）。撞名守卫也抽成不依赖 Bundle 的
`assertNoShellKeyClash`，**撞名直接抛**而不是静默覆盖。

#### `SurfaceDependencyChecklist`（P9 第一个交付物）

`ChatDetailSurface` 的 18 项微根 + 5 个微栈目标，每项标注**缺失后果** ——
缺项的共同症状是「点了没反应」（事件进 store 无人渲染，不报错不崩溃）。

配套测试**双向断言**「清单 ⊆ RN 源码」与「RN 源码 ⊆ 清单」——
只有前者时，RN 侧新增一个 `PortalHost` 清单仍会全绿，那是虚假的安心感。
另有一条钉死 `SurfaceToastHost` 必须在具名 `PortalHost` 群之前（顺序反了
表现为「弹窗被 toast 盖住」，测试很难抓）。

⚠️ 核对时发现一处**双端不一致**：`ChatDetailSurface.tsx:628` 是
`PortalHost name="MayBallSplashPV"`，而 `App.tsx:478` 是 `"SplashPV"`。
全仓搜下来**两个名字都没有对应的 `Portal hostName` 消费方**，
且 `components/animations/SplashPV.tsx` 根本不用 Portal —— 看起来两侧都是休眠遗留。
**但这是推断，不是实测结论**：真机验收若发现活动开屏不弹，先查这里。
**别"顺手统一"名字** —— 改 `index.surfaces.js` 系文件需要双壳回归。

#### 仍未做（明确边界）

- ChatDetail **未**放回生产白名单 —— 等 §9.1 矩阵填满（与并行的 PR #16 一致）
- 真机验收未跑：本包所有验证都是单测 + bundle 内容核对，按 §5.4 纪律
  「Surface 能否真的跑起来」当前状态是 `NOT RUN`

### 2.20 原生登录页：邮箱验证码链路（真机已验，2026-08-11）

首个原生业务页。`/login/email/send_code` + `/login/email` 全链路接通：发码、
60s 冷却、验码、成功后 `tokenStore.onLoggedIn` + `authStateHub.notifyDidLogin`。
状态收在 `EmailLoginViewModel`（跨重组/配置变更存活）。

**Google / Apple 登录仍未接**（社交按钮在位但无实现）—— `/login/firebase` 受
§12.8 签名指纹阻塞，**无法真机验证**，不是漏实现。`/login/password` 与
`/login/email/did_not_get_code` 未做。年龄验证 / 资料补全 / 账号合并弹窗属 W4。

#### 静默失败：`errorMessage = null` 等于不弹 toast

网络失败时原先把 `errorMessage` 置 `null`，本意「让 UI 用默认文案」，但 UI 是
`errorMessage?.let { toast }` —— **null 等于什么都不弹**，真机表现为「点发送完全
没反应」。API 24 模拟器 TLS 握手失败时踩到（那一档是 CI/冒烟矩阵里的真实环境）。
现回落到 `FALLBACK_ERROR_KEY`；后端 `code≠0` 但 `msg` 为空时同样回落。

⚠️ 兜底文案用 `Please try again later` 而**不是** RN 的 `Something went wrong`：
后者**不在 26 个 locale 文件里任何一个**，`L10n.t` 找不到会回落到 key 本身，
结果所有语言都显示英文（正是 §4.8 那条「非英文用户静默看英文」）。前者 26 个
locale 均已有翻译，已逐一校验。

#### 与 RN 的一处刻意偏离

RN 的发码/登录**不检查 envelope 的 `code`**（`auth.ts:126-143`），后端限流返回
HTTP 200 + `code≠0` 时 RN 静默当成功、倒计时照走，用户等一封永不到的邮件。
壳这里检查 `code` 并把后端 `msg` 抛给 UI。

#### 测试与验证

app 单测 **49 条**覆盖本页（ViewModel 编排 / envelope 契约 / 状态机 / `X-Client-ID`
加密），skipped=0。契约测试用 `MockWebServer` 验实际发出的 header。

⚠️ `android.util.Log` 在 JVM 单测里是抛异常的 stub，故 ViewModel 的失败日志经
`logWarn` 注入（默认参数给生产实现，同 `nowMs` 的处理）。**没有**开
`returnDefaultValues` —— 那正是 §5.4 点名的假绿色，且会掩盖上面那个 null 静默。

原有「网络失败不启动倒计时」用例只断言状态、断言不到「用户被告知」，所以漏掉了
这个 bug。新增用例直接断言用户可见文案。

真机（API 36，`ai.lightspeed.tipsy`）：正确码登录成功并落地 token；错误码弹
「验证码错误」且停在原页；倒计时到 0 恢复「重新发送」；断网点发送弹
「Please try again later」且不启动倒计时（可立即重试）。

**未验**：API 24 真机/模拟器（该档 TLS 连不上本后端，是发现此 bug 的环境但未跑
通完整链路）；三个 applicationId 的覆盖升级。RN 侧 `onAuthStateChanged` 目前
**只有类型声明、无 JS 订阅方**，所以登录只发 `authStateHub`、未发 `TipsyAuthRegistry`；
接 Surface 前需补齐对称性。

> ⚠️ **本节原写「`didLogin` 广播的下游消费（W2 五 Tab 尚不存在）」—— 该前提已失效**：
> §2.23 的 `HomeFragment` 已订阅 `AuthStateHub`（登录/登出都重拉列表 + 绑定/解绑
> 埋点 uid）。也就是说登录链现在**有真实下游**了，但那条链本身仍未真机验证。

### 2.21 CI 挂死：`runTest` 里嵌 `runBlocking`（2026-08-11 修复）

`ApiClientTest.store 返回后恰好过期 REQUIRED 仍不得起飞` 会**永久死锁**，
表现为 G1 Fast Gate 在「单元测试」步骤耗到 **job 60 分钟超时被 cancel**，
后续「桥单测」与「skipped=0 校验」两步直接 skipped。

PR #16 的 G1 记录是 `fail 1h0m15s`，PR #17 首跑是 `cancelled 1h0m15s`
—— **同一个签名**。该 PR 描述里也写明「未执行组合验证」，所以这条是带着红 CI
合进 main 的，不是本次合并引入。

机制：`fixture` 传 `scope = this`（TestScope），该用例的 token 落在 refresh
窗口内（`exp = now+1`、`requestNow = now+2`），于是 `getValidToken()` 走到
`refreshSingleFlight`，那里 `scope.async` 把 refresh 排到**虚拟时间调度器**上，
随后 `deferred.await()` 等它。而外层 `assertThrows { runBlocking { ... } }` 已经
占住唯一的 test 线程 —— 调度器永远拿不到执行机会。

同文件另有九处 `runBlocking` 侥幸不死锁：它们的 token 无效或不在 refresh 窗口内，
`getValidToken()` 在真正 suspend 前就 return 了。**别以为那个写法是安全的。**

修法：直接在 `runTest` 协程里 `try/catch` 调 suspend 函数，不嵌 `runBlocking`
（本仓未依赖 kotlin-test，故不用 `assertFailsWith`）。

⚠️ **这个坑的二次伤害是「报告看起来是绿的」**：测试 task 挂死时不产生新报告，
`build/reports/tests/**/index.html` 还是上一次成功运行的内容。排查期间据此读到
过「303 条全绿」，而那是挂死前的旧产物 —— 真实数字是 **336**。
判据：**先看报告 mtime，再看数字**；挂死的 task 没有 mtime 更新。

修复后实测：`:app:test{DirectApk,GooglePlay}DebugUnitTest --rerun-tasks`
→ 各 **336 条**、failures=0、ignored=0，全程 2m35s（此前是无限挂）；
`:tipsy-auth:testDebugUnitTest --rerun-tasks` → 15 条、ignored=0，
`LiveAppSafetyTest` 已执行；`:app:lintDirectApkDebug` 过。

### 2.22 W1 组合验证已在 CI 真绿（2026-08-11）

§2.18 曾把 W1-CLOSEOUT-1 记为「实现完成、组合验证 NOT RUN」。**那条已由 CI 兑现**：
`main` 上 §2.21 修复后的第一次 push run
（[31490358140](https://github.com/Lightspeed-Intelligence/tipsy-Android/actions/runs/31490358140)）
**22 步全过、32m27s**，含 lint 硬门 → assembleGooglePlayDebug → release manifest →
app 单测 → `:tipsy-auth` 桥单测 → `skipped=0` 守卫。

也就是说 §2.18 / §2.13 / §2.14 里那批 closeout 实现现在有 CI 层面的组合证据，
不再是「只跑过静态守卫」。**仍未覆盖的**：三 flavor 全量、release 打包（属 G3
nightly，未建）、真机验收（§2.19 的 `NOT RUN` 依然成立）。

### 2.23 W2 第一刀：五 Tab shell + Home 首屏（2026-08-11）

第一个 W2 工作包。**壳从「自检根」变成真实首页**：启动进 Home，底部五 Tab 可切。

落地的模块：

| 目录 | 内容 |
| --- | --- |
| `shell/analytics/` | `Analytics` facade（Qt 前置，见下） |
| `shell/tabs/` | `ShellTab` / `ShellTabBar` / `TabHostFragment` / `TabPlaceholderFragment` |
| `shell/pages/home/` | 系列与性别枚举、`HomeApi`、解析、`HomeViewModel`、`HomeScreen` 与卡片、`HomeFilterStore` |

**tabbar 对齐 RN Android 现网**（owner 2026-08-11 决定）：实心 `#341F1D`、无圆角、
无模糊、无选中胶囊。⚠️ RN 侧 iOS 分支是**另一套**（悬浮胶囊 + BlurView + 200ms
滑动胶囊，即 iOS 壳 `FloatingTabBarView.swift` 那套）—— 照 iOS 做会与现网 Android
用户看到的界面明显不同。

**未登录冷启动直接弹登录页**（owner 决定，对齐 RN `restoreSession`）：无 token /
已过期 → `requestLogin`。Tab 骨架先建好、登录页盖在其上。

#### Home 做到哪（明确边界）

已做：6 个系列（**含 World** —— 见下）、真实分页、下拉刷新（系统控件，对齐 RN 的
Android 分支）、性别筛选与持久化、session 语义、翻页去重 + 限次续拉、5 个页面级埋点。

未做（下一包）：banner（946 行，方案 §8.1 评估留 RN Surface）、每日彩蛋弹窗、
mp4 动图封面。

✅ **标签筛选抽屉与 For You 冷启动种子已做**（§2.24）。

⚠️ **「可见性驱动的曝光去重」这条原是误记，已核实不存在**（2026-08-12）：
`character_page_exposure` 在 RN 侧由 **mount `useEffect`** 发出
（`HomeCard.tsx:182-196`），**不经 viewability**。壳侧 `LaunchedEffect(item.stableKey)`
已是同一语义，且 `LazyVerticalGrid` 的 `key = stableKey` 保证复用 slot 不重报
（正是方案 §8.4「曝光去重集合与列表更新解耦」要求的）—— **这条已满足，不是待办**。

`home.tsx` 里那两套 `viewabilityConfig` 各有别的用途，别误当成本事件的门禁：
- `itemVisiblePercentThreshold: 1` → `VisibleItemsContext`，管 **mp4 封面播放**
  （`AnimatedCoverMedia.tsx`）
- `itemVisiblePercentThreshold: 50` + 连续可见 ≥100ms → **另一条批量上报管道**，
  POST `/recommend_report/tracking/report_batch` 报停留时长
  （`lib/recommendTracking/`），不是埋点事件。这条属推荐反馈，未迁，也不在 W2 范围

#### ✅ 开放问题 §12.4 可以关闭：Android **显示** World

方案 §12.4 问「Home 是否包含 World 系列」—— 代码里早有答案，不需要产品决策：
`home.tsx:505-511` 的 filter 是
`series !== 'Multi-character' && (Platform.OS === 'android' || series !== 'World')`。

即 **Multi-character 两端都隐藏，World 只在 Android 显示**（iOS 壳的 `HomeAPI.swift`
因此只有 5 个 case，Android 是 6 个）。World 列表走 `/game/public/projects`
（每页 **20**，不是 21），点进去是 SimulatorGame WebView —— 方案 §8.1 已定不迁，
本包点击落明确日志而非静默。

#### Qt facade 已落地（§2.17 的对冲条件解除）

§2.17 写明「facade 尚未落地，W2 第一个业务页开工前必须建」—— **本包已建**。
业务页调 `Analytics.track`，Qt 接上前只在 debug 落日志，接线时只改
`TipsyApplication.installAnalytics()` 一处。

uid 排队语义照搬 RN（`QtAnalytics.ts:404-420`）：四个 uid-required 事件在用户 id
绑定前排队（上限 50，超出丢**最旧**），绑定后补 `uid` 冲出。方案 §8.1 记的
「`character_page_exposure` 需手动补 uid」由 facade 统一处理，业务页不必各自记得。

⚠️ **Qt 本身仍未初始化**（§2.17 的两处静默洞依旧）：facade 的存在不等于埋点在上报。

#### 顺带修掉的真实缺陷：`LegacyMmkvStore` 全新安装永久不可用

§2.16 末尾记的「后续风险」在本包变成真 bug —— 因为有了写入点（gender）。

`LegacyMmkvStore.open` 在 MMKV 目录不存在时直接返回**不可用实例**，而
`TipsyApplication` 用 `by lazy` 把它缓存到**进程结束**。全新安装时
`bootstrapI18n()` 先打开它（目录还不存在 → 缓存成不可用），随后
`MmkvTokenPersistence` 才建目录。结果整个进程内：首次登录写入账号语言后仍读不到，
且 gender **永久写不进去**。现改为目录不存在时 `mkdirs()`（与
`MmkvTokenPersistence.open` 一致）。

#### `config-persist-storage` 的写入是本包破坏性最大的一处

它是 Zustand persist 信封 `{state, version}`，整体覆盖会丢掉同一信封里其余二十多个
字段（模型选择、上下文长度、已点击标签…）→ **用户一堆设置被重置且不报错**。
故写入走纯函数 `mergeGenderIntoEnvelope`（只 put 一个 key）并有专门单测。

⚠️ **`nsfw` 只读不写**：它的真值在后端 `user.nsfw`，由 RN 的 store 底部订阅单向
镜像（`config_persist.ts` 末尾）。壳写它会破坏单向流，表现为「关了 NSFW 过一会儿
自己开回来」。所以 `HomeFilters` 接口**刻意没有 `writeNsfw`** —— 别为了对称补一个。

#### 依赖：coil3 **不是新增依赖**

`io.coil-kt.coil3:coil 3.0.4` 已由 `react-native-screens` 引入（已核实其
`android/build.gradle:249-253`）。壳显式声明**同一版本**，与 mmkv / coroutines
同性质 —— 版本是与 RN 侧的耦合约束，声明更高版本会经 Gradle 冲突解析把 RN 那份
也顶上去。必须同时引 `coil-network-okhttp`：不引则任何 http(s) URL **静默不加载**
（只报一句 "no fetcher"，图片位置空白）。

#### 位图资源放 `drawable-nodpi`

RN 侧这些图**只有一份**（无 `@2x`/`@3x`），像素恰好是设计稿的 3 倍。
RN bundler 自己把它们打进 `drawable-mdpi` —— 说明 RN 完全不参与 Android 密度分档。
故放 `nodpi` 并由使用点显式给 dp（漏给会按原始像素铺开，40dp 图标变 120dp）。
`IconMissingDensityFolder` 已显式 disable 并写明理由，详见
[`android-bitmap-assets.md`](android-bitmap-assets.md)。

#### 验证

- **app 单测 431 条**（新增 95 条）、failures=0、**skipped=0**；报告 mtime 已核对
  （§2.21 的判据：先看 mtime 再看数字）
- lint 硬门通过：**no new issues**，baseline 仍 5 条
- `assembleGooglePlayDebug` + `:tipsy-auth` 桥单测：与 G1 同序列本机跑过
- **真机验收已跑**（2026-08-12，emulator-5554 / Android 16 / googlePlayDebug）：
  启动进首页、五 Tab 切换与选中态、五个 series tab（For You / Trending / World /
  Latest / Popular）各自加载真实列表、滚动续拉翻页、World tab 隐藏筛选图标 ——
  **均通过，无崩溃**。埋点 `discover_page_tab_click` + `discover_subpage_exposure`
  在每次切 tab 时按对出现（`tab_type` 正确）。
  - ⚠️ 观察到 `page=null`：facade 的 page 字段在 tab 事件上没填。不影响本包验收
    结论（Qt 上报本身推迟，§2.17），但**接 Qt 前要确认 RN 侧该字段是否也为空**，
    否则会是一处静默的埋点字段回归。
  - 标签筛选抽屉点击**按预期无反应** —— `HomeFragment.onFilterClick` 仍是 stub
    （`HomeFilterDrawer` 382 行，下一包）。点击链路本身已接通（日志可见）。
  - World 卡片的 `∞ 0` 是**真实数据**不是 bug：图标按 `character_type == 9` 分流
    走 `ic_card_world_interaction`，计数取 `stats.studio_chat_count`
    （`HomeFeedParserTest` 断言 42），测试环境多数 world 该字段确为 0。
  - ✅ **下拉刷新 / 性别筛选 / 进程重建恢复已补验**（2026-08-12，Pixel 10 模拟器 /
    Android 17）—— 详见 §2.23.1。**性别筛选查出一处真实缺陷**（持久化静默失效）。

新增测试按「错了不报错」的风险点组织：`HomeTextTest`(19，逐条对着 RN 实现取真值)、
`HomeViewModelTest`(19，session 语义/去重续拉/失败不清列表)、
`HomeApiContractTest`(10，真实 HTTP 验实际请求体)、`HomeFeedParserTest`(15)、
`ShellTabBarTest`(16)、`AnalyticsTest`(12，含"sink 内再次 track 不死锁")、
`HomeFilterEnvelopeTest`(4)。

#### 2.23.1 补验三项真机（2026-08-12，Pixel 10 模拟器 / Android 17）

§2.23 遗留的三项。**下拉刷新与进程重建通过；性别筛选查出一处真实缺陷。**

**✅ 下拉刷新** —— 刷新前首屏 Elara / Niko / Ben，下拉后换成 Emi / test，
一批新 `characterId` 重新曝光。种子信封同步被**覆盖**而非叠加：
14:22:55 存 `[Elara, Niko, Kai, Ben, Dylan]` → 14:23:32 存 `[test, Emi, ...]`，
与两次首屏一一对应，证明走的是 `isRefresh && nextPage == 0` 清 `lockedHead` 的路径。

⚠️ 手势前提：列表**必须在顶部**下拉才触发。我第一次在滑到中段时下拉，无任何反应
也无日志 —— 不是缺陷，但会让人误判成刷新没接线。

**✅ 进程重建恢复** —— `KEYCODE_HOME` 后台化 + `kill -9`（保留 task，比
`force-stop` 更接近系统回收），PID 13267 → 14089，恢复后**无 FATAL / ANR**，
首屏渲染 Emi / test 即 14:23:32 那份种子，说明冷启动读种子在进程重建路径同样成立。

⚠️ **series 选择不恢复是设计如此，不是缺陷**：kill 前停在 Trending，恢复回 For You。
已核实 `selectedSeries` 无任何持久化、也不进 `SavedStateHandle`（全仓 grep 无命中），
RN 侧同样不持久化 series。

**🔴 性别筛选：内存态正确，持久化静默失效**

内存态没问题：选 Female 后顶部标签变 `Female`、列表换成 Esmeralda / Iris，
新种子信封也正确记为 `gender: 'Female'`（14:26:50）。

但 `config-persist-storage` 这个 key 在设备上**始终不存在**（dump `mmkv.default`
确认 0 命中），于是 `kill -9` 重启后性别**退回 All**。

根因是 `mergeGenderIntoEnvelope` 的刻意设计：信封缺 `state` 子对象就
`return null` → 调用方不写（`HomeFilterStore.kt:109-117`）。这个保守策略本身是对的
（§2.23 记了整体覆盖会重置用户二十多项设置），**但它假设信封已由 RN 建好**。

已核实这不是壳的路径写错：RN 的 `zustandStorage` 用 `createMMKV()` **无参数**，
即默认实例 `mmkv.default`（`tipsy-app/src/store/mmkv.ts:4`），与壳读写同一个 store。
信封不存在只是因为这台模拟器上 RN 的 config store 从未初始化过。

**所以缺陷是真实的**：全新安装的用户，在 RN 侧首次初始化该 store 之前，
改性别**永远不持久化且无任何提示** —— 每次冷启动都退回 All。
`writeGender` 的返回值虽然是 `false`，但调用方按注释刻意不回滚 UI、也不告警，
于是本地完全看不出异常（与 §2.24 种子那处同类的"静默"缺陷）。

⚠️ **修法不能是"信封不存在就建一个"** —— 壳凭空造 Zustand 信封要猜 `version` 和
其余二十多个字段的默认值，猜错等于给 RN 侧一个结构不对的信封，
比不持久化更糟。合理方向是二者之一，需 owner 定：
1. 只在信封缺失时写一个**仅含 `{state:{gender}}` + 正确 `version`** 的最小信封，
   靠 Zustand persist 的 merge 语义补齐其余字段（要先核实 RN 的 `version` 与
   `merge` 配置，否则可能触发 migrate 分支）
2. 判定为"可接受"：等 W3 迁 Settings 时 RN store 必然已初始化，届时自愈

**未验**：上述任一修法都没做，本次只定位。也没验"信封已存在时 merge 是否只动
`gender`"—— 设备上无信封可比对，该行为目前只有 `HomeFilterEnvelopeTest`(4) 的
单测覆盖。

### 2.24 W2 第二刀：标签筛选抽屉 + For You 冷启动种子（2026-08-12）

W2 的 Home 收尾。**单测与构建全绿**（476 条）。真机**已全验**
（Pixel 10 模拟器 / Android 17，2026-08-12 复验）：

- ✅ 抽屉拉取并渲染真实标签、选中高亮、关抽屉后列表收敛到筛选结果
- ✅ **选了标签不写种子**：勾 Anime 后 feed 收敛到 1 条，dump `mmkv.default`
  确认**没有**新信封落地；取消勾选再确认，14:03:09 立刻落一份 5 条的新信封
  —— 证明「不写」只发生在有筛选时，无筛选路径正常
- ✅ **离线冷启动渲染种子**：断网 + `force-stop` 后冷启，首屏渲染出信封里的
  33 / Luciano / 111 / Evelyn Sharp（第 5 条在屏外）。**无全屏 spinner**
  —— UI 树里唯一的 `ProgressBar` 是 42x42、y≈213 在搜索栏内，种子之上没有遮挡层，
  §2.24 的「有种子时先显示种子且不显示 spinner」在真机成立
- ✅ **种子与真实数据衔接**：恢复网络后冷启，首屏仍是同 4 个角色**且无重复卡片**
  （去重按 stableKey 生效）；下滑出现 Tomboy Lena / Yuto / Luna / Sylvan
  —— 种子在前、真实数据追加在后

⚠️ 我第一次报的「已在真机确认」是**无效的** —— 为清种子删了 `mmkv.default`，
那是 app 共用的 MMKV store（`token-storage` 也在里面），会话被清空、应用重启到
登录页，后续盲点坐标全落在登录页上，而我把登出后写的 `guest` 信封读成了通过。
**清种子要用下拉刷新**（`isRefresh && nextPage == 0` 会清 `lockedHead`），
不要删共享 store。

真机操作两条经验（下次省时间）：
- launcher activity 是 `ai.lightspeed.tipsy/.shell.MainActivity`（**不是** `.MainActivity`，
  用后者 `am start` 会静默落到桌面）；`adb shell monkey -p ... -c LAUNCHER 1` 最省事
- `adb screencap` 拉回的 PNG 多次为空，**改用 `uiautomator dump` 读 UI 树**取控件
  文本与坐标，比截图可靠

#### 标签筛选抽屉（`HomeFragment.onFilterClick` 的 stub 已删除）

- `HomeTag` + `HomeTagParser`：`POST /character/tags`（**只发 `{nsfw}`**，不带
  `language_code`）。按 `sort_order` 稳定排序；**`show_in_filter !== false`** 才进
  筛选（不是 `== true` —— 字段缺失时要显示，写反会让标签集体消失）；
  label 取 `desc` 回落 `alias`
- `HomeFilterDrawer`：`Dialog` + 底部面板。高 630 / 圆角 20 / header 49 /
  chip 30 高 18 内距 / 选中色 `#AD403B`（品牌主色）
- ✓ 图标用 `Canvas` 两条线段画 —— RN 用 AntDesign 字体图标，壳没有那套字体，
  为一个对勾引 `material-icons-extended`（约 2MB）不值得

⚠️ **应用时机是「关闭抽屉」而不是「点确认按钮」**（`TipsyDrawer.tsx:338` 的 ✓ 调的
就是 `handleClose`，`onClose` 回调里才 `setSelectedTags`）：点 ✓ / 点遮罩 / 按返回键
**三者都应用**。照「确认才生效、点外面丢弃」实现与现网相反。

**两处按系列分流**（都容易漏）：
- Following / World 请求**不带 `tag_ids`**（`useHomeCharacterLists.ts:89`）
- 它们的 `filterKey` 也**不含标签**，且 `onTagsApplied` 只清受影响系列的游标。
  第一版写 `cursors.clear()`，被 `改标签不作废 World 已缓存的列表` 这条测试挡下

#### For You 冷启动种子（方案 §4.6 的信封）

`HomeForYouCache`：`{version, authScope, gender, savedAt, items}`，
authScope 门禁（`guest` / `user:<id>`）+ 7 天 TTL + 只存**前 5 条**
（`LOCKED_HOME_CACHE_SIZE = 5`）。**语言刻意不做门禁**（§4.6 的反直觉修正）。

⚠️ **壳写自己的 key（`shell-for-you-seed`），不读也不写 RN 的 `for-you-cache`**。
RN 那份是**裸数组**（`JSON.stringify(items)`，无信封）—— 已核实它因此
**不按账号隔离 / 无 TTL / logout 不清**（全仓没有清该 key 的代码）。§4.6 要求壳不
继承这三点，所以两份并存：读 RN 的等于继承跨账号复用，写 RN 的会让 RN 侧解析到
非预期结构。代价是首次装壳版没有种子。

存**原始响应片段**而非解析后模型 —— 读写都复用 `HomeFeedParser`，
不必再写一套序列化（那会是第二个真值来源）。

⚠️ **信封刻意不含 tags，代价是「选了标签时不写种子」**（2026-08-12 自测发现，
PR #20 修）。写进去也能当门禁，但那样带标签这一次的种子对下次无标签的冷启动
永远失效，等于白存 —— 所以选择不写。

这条不是洁癖，缺了它是真缺陷：标签勾选存在**无 persist 的 session store**，
杀进程后归零，于是「筛选出的 2 条」会被当作未筛选的 For You 首屏渲染，
**三道门禁全过、本地完全看不出异常**。改前真机抓到两份信封作证 ——
12:52 未筛选 5 条，12:57 应用 Action 后只剩 2 条却仍标着 `gender: All`。

同一处还有第二个缺陷：`onTagsApplied` 里必须丢掉 `lockedHead`。合并列表时读
`lockedHead` **早于**第 0 页落地后清空它，所以首屏失败（种子保留 —— 失败不清
列表是对的）之后改标签，那几条未筛选的角色会混进筛选结果，用户无从分辨。

**RN 侧同样有这两个缺陷**：`getForYouListReq` 带 `tag_ids`
（`useHomeCharacterLists.ts:59`），而 cache 写入 effect 只看 `forYouFirstPage`
变化、不看筛选状态（同文件 `163-169`）。按 §4.6 壳不继承缓存缺陷。

种子与真实数据的衔接（两条都是被测试逼出来的）：
- 种子**不写进 `loaded`** —— `loadIfNeeded` 靠它判「已有数据就不拉」，
  写进去会让首屏永远停在种子上、真实数据一次都不拉
- `loadIfNeeded` **不能无条件置 `isInitialLoading = true`** —— 会在种子之上再盖
  全屏 spinner，种子白读。第一版就是这么写的，`有种子时先显示种子且不显示 spinner`
  这条挡下了

真实第 0 页到达后种子作为**锁定头**在前、真实数据去重追加
（对齐 `home.tsx:711` 的 `unionBy(cachedList, flatList)`）；下拉刷新丢弃种子
（RN 的 `setShowForYouCache(false)` 同义）。

#### 顺带修的两处文档失真

1. 方案 §8.1「筛选持久化」称含 `tags` —— 实际 `config-persist-storage` 的 `tags` 是
   **标签目录**，用户勾选存在**无 persist 中间件**的 `session.ts`
   （已核实 `grep -c persist` = 0）。**杀进程后勾选归零，只有 gender 存活**。
   照文档实现会让原生版比 RN 多记住筛选
2. 「可见性驱动的曝光去重」是误记，见 §2.23 的更正 —— 该条已满足，不是待办

#### 验证

- app 单测 **476 条**（新增 43）、failures=0、**skipped=0**（2026-08-12 复跑确认）
- lint 无新增（baseline 仍 5 条）、`assembleGooglePlayDebug` 通过
- **真机 `PASS`**（2026-08-12，Pixel 10 模拟器 / Android 17）：抽屉打开/勾选/应用、
  「选了标签不写种子」、离线冷启动渲染种子、种子与真实数据衔接**四项全过**，
  详见本节开头。种子这项尤其需要真机：它依赖 MMKV 实际可读写，而 §2.23 刚修过
  `LegacyMmkvStore` 全新安装不可用的缺陷 —— 已确认冷启动读得到信封

新增测试：`HomeTagParserTest`(11)、`HomeForYouCacheTest`(16，三道门禁 + 坏数据)、
`HomeViewModelTest` +16（标签分流 9 + 种子 7）。

### 2.25 W3 第一刀：Profile 自己视角（资料头部 + 创作/记忆两 tab，2026-08-12）

W3 开工，Profile Tab 从占位换真页。新增 `pages/profile/`（16 文件）与 `user/`
（`CurrentUser` / `CurrentUserStore` / `UserInfoApi`，进程内用户信息，**刻意不持久化**
—— 壳只读不写 RN 的 `user-storage` 信封，冷启动首进 Profile 多一次 loading 是
记录在案的代价，要消掉走「读信封作种子」而不是壳写信封）。

**范围**：`/user/info` 资料头部、`/user/stats_info` 四统计、`/user/created/list`
创作三列网格、`/plot/list/self` 记忆单列大卡、五图标 tab 栏（含滚出屏顶后浮出）、
按 tab 分表的分页壳、四个出口路由类型（Settings / EditProfile / Follow / UserCoins，
全部**未启用**，点击走 Router 明确拒绝）。

**未做**（后续包）：角色卡/收藏/点赞三 tab、钱包区、卡片菜单与编辑/删除/置顶动作、
记忆卡点击进 ChatMemory（属 ChatDetail 深栈）、他人主页（stats 走 OPPORTUNISTIC）、
创作列表首屏缓存（`profileCreatedListCache` 的 key 带维度设计值得单独一刀，
顺带印证 §2.23.1 的修法方向）、NSFW 封面模糊（等 Compose 模糊方案，两处卡片一起做）、
头像框（配置源 hydrate 会静默失败，§2.19）、`onFirstTabDataReady` 一族性能埋点。

#### 实测抓出的对等陷阱（写码前逐条核 RN 源码）

- **`/user/stats_info` 字段与标签交叉**：Followers 标签下是 `followees_count`、
  Following 标签下是 `followers_count`（`FollowInfo.tsx:52,66`，两行是反的）。
  照字段名直译会标反且**本地几乎测不出来**（测试账号两数常相等）。
  命名收口在 `ProfileStats.followersLabelCount / followingLabelCount`。
- **`/plot/list/self` 是关系型响应**：`plots` + `characters`/`creators` 两个
  id→对象 map 靠 join（`apis/plot.ts:86-88`），与创作列表的内联嵌套形状完全不同。
  TS 类型与实测出入：`created_at` 声明 `string` 实为 Unix 秒**数字** → 走
  `ScalarCoercion`。背景图用 `image_url`、头像位用 `face_url`，两个字段别混。
- **同路径/同能力自己与他人各一条**：stats 是同路径不同鉴权（axiosAuth vs
  axiosPublic）；记忆是不同路径（`/plot/list/self` vs `/plot/list/creator`）。
  本刀只接自己那条（REQUIRED）；接他人主页时是 **OPPORTUNISTIC 不是 NONE**（§4.5）。
- **整页 loading 不能照抄 RN**：RN 的 `isLoading` 接的是**不上屏的死请求**
  `/character/list/self`（`useProfile.tsx:165-186` 注释确认）。壳直接接各 tab 列表请求。
- **创作 tab 空态文案是 `No Character`**：空态分流 `tabIndex===0 → 'story'`
  （`CharacterGrid.tsx:1063-1074`），`EMPTY_TEXT_MAP.story = 'No Character'`。
  第一版杜撰过 "No creations yet"，已按实测改。
- **分页 size 按 tab 配**：创作/角色卡 10、记忆/收藏/点赞 20、他人主页 200。
  「5 tab 共用一个分页壳」指壳复用，size 不统一。
- **`UID:` 前缀不进 i18n**：RN 是裸文本（`user-profile.tsx:665` 不走 `t()`），翻了反而不对等。
- **下拉刷新 RN 把五个 tab 全 mutate**（`CharacterGrid.tsx:252-262`
  `Promise.allSettled`）：壳的对应物 = 当前 tab 立即重拉 + 其它 tab 复位待重拉（见下）。
- **记忆卡时间是恒 en-US 的 `h:mm a`**（`formatTimestampToAMPMTime` 调用点不传
  locale），且创建时间只显示时:分不显示日期 —— 别顺手"修"。

#### 并发模型：单在飞分页链（与 RN 的刻意差异）

RN 每 tab 独立 `useSWRInfinite` 并行；壳采用 Home 同款**单 inFlight 链**：任一时刻
至多一条分页链在飞且必属当前 tab，切 tab / 刷新 / 语言 settle / 登录态变化都先
cancel。被打断且未完成首屏的 tab **整体复位**（否则 `isInitialLoading` 卡 true，
`loadFirstPageIfNeeded` 永远跳过它）。代价是切 tab 偶尔废弃一个在飞请求；换来页级
`isRefreshing` 无归属歧义、跨 tab 响应竞态整类消失。分页游标（`nextPage`/`total`/
空页续拉 streak）按 tab 分表存 `ProfileState.paging` —— 裸字段会让切 tab 后
从对方页码继续拉，首屏缺前 N 页。

#### 顺带修掉的真实缺陷：`CurrentUserStore.refresh` 吞 CancellationException

`runCatching` 不分流会让登出瞬间在飞的 `/user/info` 响应把旧账号资料写回已清空的
状态（「登出串上一账号数据」的 Profile 变体：取消发生在挂起点，吞掉异常后非挂起
代码照常执行到写状态）。已改为 rethrow，且 `ProfileViewModel.onAuthChanged` 同时
取消 `userStatsJob`。JVM 测试用 gate 挂起在飞请求验证了取消语义
（`切走打断在飞首屏后切回能重拉`）。

#### i18n 与 testTag

- 全部对等词条**已在 SHELL_KEYS**（Settings / Edit Profile / 四统计标签 /
  No Character / No memories / Private / Failed / Pending / Passed / messages /
  Please try again later），**本刀不动 tipsy-app、不 bump submodule**；ja 抽查有译文。
- `Coming soon`（未接 tab 的壳专属占位）**刻意不进 SHELL_KEYS**：RN 无此词条，
  加了也没有任何语言的译文，fallback 链（当前语言 → en → key）走到 key，
  与加了行为一致；三个 tab 落地即删。
- testTag 按 Login 的 snake_case 现行约定（方案 §9.4 的 `android.` 点分风格是
  草案，代码先例是 `login_*`）：`profile_{avatar,uid,edit,settings,grid,loading,
  empty,error,tab_placeholder}`、`profile_stat_*`、`profile_tab_*`、
  `profile_created_card_<id>`、`profile_memory_card_<id>`。动态段只用服务端 id。
- 审核状态徽标是**值与文案不同轴**：`un_reviewed/pass/failed`（`types/review.ts`）
  → Pending/Passed/Failed，原始值不上屏，认不出的值不显示。

#### 两个 owner 决策点（阻塞后续包，不阻塞本刀）

1. **Follow 出口无处可去**：方案 §8.1 把 `follow`（445 行）列为「不迁、走 Surface」，
   但 RN **不存在 FollowSurface**（已核实 `src/surfaces/` 无此文件，`follow.tsx`
   是 ProfileStack 普通页）。要么 RN 侧新建 Surface、要么原生实现。
   `AppRoute.Follow(userId, type)` 与 props 形状已按 `FollowInfo.tsx:57,71` 备好。
2. **EditProfileSurface 过矩阵在 W3 还是 W4**：方案 §8.3 批次表列 W3、§9.1 矩阵把
   「其余 10 个」标 W4，**方案自相矛盾**，需 owner 定夺后修方案文档。

#### 验证

- app 单测 **563 条**（新增 87：`ProfileViewModelTest` 30 含多 tab 游标隔离 /
  切走取消在飞链 / 刷新复位其它 tab / 语言 settle / 占位 tab 刷新收圈；
  `ProfileParserTest` 27；`ProfileMemoryParserTest` 15；`ProfileTextTest` 15）、
  failures=0、**skipped=0**
- `:tipsy-auth:testDebugUnitTest` 15 条全绿（含 `LiveAppSafetyTest`）
- lint 无新增（baseline 仍 5 条）；`assembleGooglePlayDebug` 与
  `assembleDirectApkDebug` 通过
- **未动** manifest / RN 依赖 / flavor → release manifest diff 本刀不适用；
  RuStore flavor 未单独 assemble（flavor 相关零改动）
- **真机冒烟 `PASS`**（2026-08-12，Pixel 模拟器 / Android 17，directApk 覆盖安装
  保留登录态）：① 头部 + 统计真实数据（0/1/0/70，交叉映射下 Following=1 与账号
  实况一致）② 创作网格 6 卡全渲染 ③ 记忆卡关系 join 生效（角色名 Emi 来自
  `characters` map）+ 三条预览气泡按角色/用户分流 + Pending 徽标 + `3 messages` +
  `5:31 PM` ④ ROLE_CARD 显示 "Coming soon" 占位 ⑤ 切回创作即时显示不重拉
  ⑥ Settings 点击明确拒绝（`拒绝导航：Settings —— 该目标在当前波次尚未启用`）
  ⑦ 占位 tab 下拉刷新不卡圈不崩溃
- **真机 NOT RUN**（数据或后续包具备时再验）：翻页触底续拉（账号仅 6 条创作，
  不足一页）、tab 栏滚动浮出（列表不够长）、语言切换全 tab 复位重拉、
  首屏错误态展示、进程重建恢复

### 2.26 W3 Profile P2：头部视觉对齐（渐隐背景 + bio + 顶栏，2026-08-12）

对照线上截图补齐头部三大块，头部结构与现网一致（仍差钱包卡 = P3、
头像框与渠道图标 = P7、卡片角标 = P4）。

- **渐隐背景图**（`ProfileBackground.tsx`）：宽 = 屏宽、1:1，三段 alpha 遮罩
  `locations [0.36, 0.9, 1]` × `alpha [1, 0.1, 0]`。Compose 用
  `CompositingStrategy.Offscreen` + `drawWithContent` + `BlendMode.DstIn`
  **精确复刻**（不是叠底色渐变的近似）。URL 空走内置默认图
  （`user-profile.tsx:418-423` fallback `profile_bg.png`，已搬为
  `ic_profile_bg_default`）。背景 absolute 垫底、列表滚在上面（对齐 RN）。
- **⚠️ 订正第一刀的一处位置错**：UID **不在昵称下方** —— RN 把它放在悬浮
  顶栏**左侧**（`user-profile.tsx:656-674`，带 `popover_copy` 复制图标、
  alpha 0.8）。已挪到顶栏左，右侧同时把 "Settings" 文字占位换成
  `profile_setting.png` 图标资产。
- **头像行锚定屏顶 250dp**：RN 是「悬浮 header 高 `top+50`」+「header 内
  `paddingTop: 250 - headerOffset`」（`ProfileHeader.tsx:173`）的配合。
  壳顶栏在布局流里，等价换算 = 250 − statusBar − 顶栏高（44）。
- **bio 区**（`renderBio.tsx`）：白 8% 圆角容器 / marginH 10 / padding 10 /
  一行截断 + 右侧铅笔（仅铅笔可点，与 RN 一致），点击与 Edit Profile 同走
  `AppRoute.EditProfile`（当前明确拒绝）。空态文案
  `No bio yet. Add one now.` **已在 SHELL_KEYS** —— 本包仍零 submodule 改动。
- `CurrentUser` 补 `bio` 字段（带默认值，既有构造点不受影响）；
  统计排版经实测核对**无需改**（16/12sp、边距 15/10/8 与 `FollowInfo.tsx`
  styles 一致，第一刀就抄对了）。
- 新资产 4 个：`ic_profile_setting` / `ic_profile_uid_copy` /
  `ic_profile_bio_edit` / `ic_profile_bg_default`（均单文件直搬 RN assets）。

#### 验证

- app 单测 **568 条**（新增 `CurrentUserParserTest` 5：残缺响应作废 / 可选字段
  归一 null / bio 空串走空态 / 数字 id 容错）、failures=0、skipped=0；
  lint 无新增；`assembleGooglePlayDebug` + `assembleDirectApkDebug` 通过
- **真机截图对照 `PASS`**（同 §2.25 环境）：背景图渐隐上屏且与线上同构、
  顶栏 UID+复制图标 / 设置齿轮、头像行落点与线上一致、bio 空态条完整渲染、
  统计与网格不回归
- **NOT RUN**：背景 URL 为空的默认图分支（测试账号有背景图；分支只是
  painterResource 换 AsyncImage，风险低）、非空 bio 的显示（账号无 bio；
  同一 `Text` 只是数据分支）

### 2.27 W3 Profile P3：钱包三栏卡（2026-08-12）

头部最后一块大件。`ProfileWalletApi` + `ProfileWalletCard`，接进 header
（bio 之下、tab 栏之上，`CharacterGrid.tsx:1434` 的位置）。

- **数据 = 两接口合成**：`/wallet/info`（宝石/免费条数/金币，`subscribe.ts:91`）
  + `/subscription/get/active`（档位 → 中栏标题与配色，`subscribe.ts:102`），
  都是 `axiosAuth` → REQUIRED。**各自失败各自保留旧值**（同 stats 纪律），
  两个都失败整块不动。`membership_rights/info` **刻意不拉** —— 卡片不消费
  权益字段，RN 在此组件只用它做预取。
- **只解析上屏的五个字段**：`useUserWallet` 派生的十几个值（汇率/提现/佣金/
  生图余量）消费方是 UserCoins/提现页，都不迁（§8.0）。
- **两套新数字规则**（本页第四、五套，`ProfileWalletTest` 13 条锁死）：
  钱包整数 = 裸千分位**无 K/M 换算**（`formatMessageAmount`）；
  金币 = 去尾一位小数 + 千分位 + **恒带 `.0`**（`formatCoinAmount` 的
  `floor(x*10+1e-8)/10`，0.19 → `0.1`、0 → `0.0`）。
- **⚠️ 反直觉但对等的两处**：`has_inf_msg=true` 时中栏显示**硬编码 100**
  （`UserProfileGems.tsx:371` 的三元，不是 Unlimited —— 现网行为，别修）；
  RN 整栏和胶囊按钮**同一动作都可点**（外层 TouchableOpacity + 内层按钮
  都调 `onButtonPress`）—— 壳整栏可点。
- **三出口对齐 RN 三个 handler**：宝石+ → `GemsPurchase(initialTab=buy_gems)`、
  升级 → `GemsPurchase(initialTab=subscription)`、金币→ → `UserCoins`。
  真机逐个点过，全部走 Router 明确拒绝日志（`GemsPurchase`×2 + `UserCoins`）。
- **档位名映射**（`MemberShipTierName`）：0-5 → Free/Get a Taste/Standard/
  Premium/Deluxe/On Trial（key = 英文原文**全在 SHELL_KEYS**，含宝石 ⓘ 的整段
  说明文案 —— P2/P3 连续三包零 submodule 改动）。未知档位回落 Free。
- **刻意不做**：会员栏 ⓘ 的到期/续费信息（要 `expires_date` + 日期格式化，
  Free 账号不可见）、金币 USD 汇率首次引导气泡（依赖 guide-status store，
  属 Onboarding 域）。
- 资产：`gem_{red,blue,coin}` + `info` 直搬 PNG；`plus`/`arrow` 是 RN 内联
  SVG，转写成 vector drawable（两笔描边，逐 path 对照）。

#### 验证

- app 单测 **581 条**（+13：`ProfileWalletTest` 9 解析/档位/两套格式化 +
  `ProfileViewModelTest` 4 合成/失败保留/单独失败/登出清空）、failures=0、
  skipped=0；lint 无新增；`assembleDirectApkDebug` 通过
- **真机 `PASS`**（同 §2.25 环境）：三栏卡上屏（真实数据 1,234,567 千分位 /
  Free 0 + Upgrade / Coins `0.0` 恒一位小数）、三个出口点击均落明确拒绝日志、
  无崩溃
- **NOT RUN**：`has_inf_msg` 显示 100 的分支、非 Free 档位的蓝色数字与档位名
  （测试账号 Free 且无订阅 —— 纯数据分支，解析侧已有 JVM 覆盖）、ⓘ 气泡的
  Popup 视觉（点击路径无真机截图，组件为标准 Compose Popup）

### 2.28 W3 Profile P4：卡片角标 + 封面模糊（2026-08-12）

创作网格与线上的最后一块显著差距。`ProfileCreatedItem` 补 9 个字段与 5 个
派生判定，`ProfileGridItem` 重写为五层结构，`CoverBlurTransformation` 落地。

- **⚠️ 订正第一刀的一处解析错**：`review_stage` 等状态字段 RN 从**嵌套对象**取
  （`character.review_stage`），第一刀解析的顶层同名字段实测不总在 ——
  已改为嵌套优先、顶层兜底（`nestedThenTop`）。
- **左上角标三选一**（优先级 = RN 的三元链，`CharacterGridItem.tsx:780-812`）：
  审核角标（rejected/pending，approved **不渲染**）＞ 私密锁（`!is_public`）＞
  story/18+ 标签。18+ 标签只在**审核通过**时出现（待审时位置属于审核角标）。
  右上：置顶 Pin。rejected 判定并合 `minor_review_status`（rejected/
  final_rejected）与 `review_stage=failed`，rejected 优先于 pending。
- **封面模糊三条件**（`CharacterGridItem.tsx:571-577` 注释照录）：
  ① nsfw（壳内偏好恒 false → **18+ 一律模糊**）② `final_hit & 8`
  ③ 未成年审核拦截。记忆卡（`plot.nsfw`）同一套变换复用。
- **模糊选型：Coil 位图变换，不是 `Modifier.blur`** —— 后者 RenderEffect
  只在 API 31+ 生效、**低版本静默不模糊**，而 minSdk 24 是冒烟矩阵真实一档，
  18+ 封面在低版本露出是内容合规问题。实现走了三版：一步 16× 上采样有块状
  锯齿、两段式仍不够 → **渐进 2× 逐级上采样**（叠加双线性近似高斯）真机
  对照与 BlurView 磨砂观感一致。cacheKey 带版本号，模糊与原图各占缓存。
- **`final_hit < 2` 整卡不可用遮罩**：锁 + `Currently unavailable`。
  ⚠️ 该词条是 key≠value 实例（en 值 "More to come"）——正好验证「运行时
  不得拿 key 当英文文案」。**缺失不算不可用**（RN 是 `!= null && < 2`，
  反过来会把老数据整页蒙掉）。
- **计数行**：曝光数仅 character 卡且 `is_public`（`stats.exposure_count`）；
  消息数 character 卡走 `formatCountMaxThreeDigits`（**第六套数字规则**：
  三位有效数字 K/M/B/T/Q，`4730 → 4.73K` 两位小数、`999950 → 1M` 晋位，
  行为对齐 RN 自带单测），story/game 卡走 `formatNumber`（= Home 的
  `formatMessageCount`，直接复用）。`stats.total_messages` 优先于顶层。
- **`is_public` 缺失按 true**（不画锁）：多画锁比漏画显眼，方向刻意。
- 资产 8 个：pending/fail/lock/Pin/message/tag_story/tag_18_plus/exposure 直搬。
- **仍不做**：⋮ 菜单与动作（P5，届时菜单按钮必须可点击组件吃事件——iOS 的
  点击穿透坑）、卡片点击进详情（目标页未启用）、winner 徽章与水印（运营
  配置源）。

#### 验证

- app 单测 **590 条**（+9：角标优先级/模糊三条件/final_hit 边界/18+ 仅过审/
  嵌套层取值/is_public 缺省 + 计数格式化 3）、failures=0、skipped=0；
  lint 无新增（`Bitmap.scale` KTX 替换后）；googlePlay + directApk assemble 通过
- **真机截图对照 `PASS`**（同 §2.25 环境，账号 6 创作含全部形态）：
  Pending 徽标（黑胶囊+沙漏）、置顶 Pin 右上、私密锁 + 封面磨砂模糊
  （Leeke 卡与线上观感一致）、评论数、story 卡创作者名注①，五层结构与线上同构
- **NOT RUN**：rejected 徽标（账号无被驳内容）、`final_hit` 遮罩与 &8 模糊
  （无命中数据）、game 卡（账号无 game）—— 判定全部有 JVM 覆盖，纯数据分支
- 注①：story 卡的创作者名是 P4 前已有的底行内容，本包未动它

### 2.29 W3 Profile P6：角色卡/收藏/点赞三 tab（2026-08-12）

五个内容 tab 全部接通真实数据源，"Coming soon" 占位与 `isImplemented`
语义整体删除。新增 `ProfileRoleCardItem`/`ProfileFavoriteItem` 两个模型
（收藏与点赞**同响应形状共用模型**，RN 侧也是共用 `FavoriteCharacterCard`）
与两个卡片组件。

- **接口**（全部 REQUIRED）：角色卡 `/user/profile_card/list`（size 10）；
  收藏 `/user/followed/character/list` 与点赞 `/user/likes/character_list`
  （size 20 + **`is_reverse: true` 硬编码**，两 hook 同款请求体只差路径）。
- **⚠️ 到底判定出现第二条轨**：收藏/点赞的响应给 **`total_pages`（页数）**，
  判定是 `已拉页数 >= total_pages`（`useProfileFavorites.ts:63-66`）——
  与创作/记忆/角色卡的「累计条数 >= total」不同量纲。`ProfileTabPaging`
  加 `totalIsPages` 标记分轨，拿条数比页数会**第一页就误判到底**
  （JVM 测试 `收藏 tab 按 total_pages 判到底` 锁死两页场景）。
- **角色卡默认卡置顶**：`sortRoleCardsWithDefaultFirst` 在**派生层**复刻
  （`ProfileState.roleCardItems` 的 stable sort），分页累计保持接口顺序 ——
  排序是显示规则不是数据规则。
- **role_pic 三段解析**（`RoleCard.tsx:31-44`）：`role_pic_url` → `role_pic`
  （http 直用 / 相对路径拼 `https://img.tipsy.chat/`）→ 占位。⚠️ 该 CDN 前缀
  是 RN **组件里的硬编码**（两处重复定义）—— 照抄不改，与创作卡「不许拼
  域名」不冲突（那边顶层相对路径无约定前缀）。
- **`message_num` 是 TS 声明 string 的字段**：实测可返 number，走
  ScalarCoercion 双形态容错（§4.5 标量漂移的又一实例）。
- 收藏/点赞卡 = 创作卡同构减角标层；nsfw 模糊复用 `CoverBlurTransformation`
  （RN 这里 intensity 25 与创作卡 40 不同，壳统一一档 —— 视觉 diff 属验收）。
  角色卡横条：白 8% 底 / 64 圆头像 / Default 橙标 / meta `性别 | 年龄 | 标签`
  全空显 None；性别 male/female 之外**全归 Other**（`RoleCard.tsx:68-73`）。
- **仍不做**：角色卡 ⋮ 菜单（设默认/编辑/删除，编辑目标 `EditRoleCard` 是
  不迁的 RN Surface）、超限提示（`isOverRoleCardLimit` 依赖 `RoleCardLimit`
  全局弹窗，属 Surface 微根件）、收藏卡取消收藏/批量管理、卡片点击进详情。
- 词条零新增（Default/None/Male/Female/Other 及三个空态 key 全在 SHELL_KEYS）；
  **连续第四包零 submodule 改动**。

#### 验证

- app 单测 **604 条**（+14：`ProfileTabParserTest` 10（role_pic 三段/性别归一/
  message_num 双形态/total_pages 语义/characters null）+ `ProfileViewModelTest`
  净增 4（页数轨两条/点赞收藏分流/默认卡置顶；两条旧「占位 tab」测试改写为
  真实数据链语义））、failures=0、**skipped=0**；lint 无新增；双 flavor
  assemble 通过
- **真机 `PASS`**（同 §2.25 环境）：五 tab 逐个切换 —— 角色卡横条（Lee +
  Default 橙标 + `Male | 18`）、收藏网格（5555555）、点赞网格（Haruka /
  Fire Mage / Marbles + 消息数），切换往返数据不重拉、无崩溃
- **NOT RUN**：收藏/点赞翻页续拉与页数轨真机验证（账号数据不足一页；
  判定有 JVM 两页场景覆盖）、角色卡多页（同）、收藏 nsfw 模糊（列表无
  18+ 内容；变换与创作卡同一实现已真机验过）

### 2.30 W3 ChatList P1：Grid 主链路（2026-08-12）

ChatList Tab 从占位换真页。新增 `pages/chatlist/`（10 文件）：
Grid 视图全链路 —— 分页列表、LV 徽章、草稿混排、左滑 pin/delete、
推送红点、铃铛未读、Grid/Map 偏好持久化、冷启动种子缓存。
**Map「時光長廊」是 P2**（562+297 行重视觉自绘，Map 按钮切到 Coming soon 占位）。

开工前按纪律先修方案 §8.1 ChatList 行（三处偏差：草稿展示「iOS 未做」已过时、
操作接口清单漏项、convEpoch 契约未记录；铃铛端点笔误——RN SWR key
`/system_message_notification/read_status` 是缓存键，真实端点是
`/message/notification/get_unread_status` 带 `platform` 参数）。

- **接口**（全部 REQUIRED，`apis/chat.ts`/`relationship.ts`/`letter.ts` 逐个核实）：
  `/user/chatted/list`（page/size 50/language_code/need_total）、
  `/user/character/relationship/batch_get`（LV 徽章，id 去重排序后发）、
  `/user/chatted/{pin,unpin}`（game 用 `{item_type,game_id}`，其余带
  `chat_mode`+`conversation_id` 小手机对话级定位）、三个 delete
  （**plot 走 character 端点**，RN else 分支语义）、消红点、铃铛未读。
- **双 generation 的 mutation 轨第一个实战用例**（§4.4）：删除乐观移除同帧
  `bumpMutation()` + 分页链每页回写前 `isValid` 双轨校验 —— JVM 测试
  `删除期间在飞的旧响应不得复活已删行` 用 gate 挂起响应锁死该时序。
  pin/unpin 是**成功后**本地重排（非乐观，对齐 RN），重排的插入位置循环
  照 `ChatListItem.tsx:175-226` 逐行移植并单测锁死。
- **convEpoch 共享键契约落地**：character 会话删除成功后写
  `multi-cinema-conv-epoch:<characterId>`（RN `multi_cinema_round_cache.ts:52-60`
  已就绪的壳侧契约，iOS 壳同款；不写则原生删会话后重进多角色影院假命中旧剧情）。
  story/game 不写；失败路径不写（JVM 测试覆盖三种情形）。
- **草稿只读混排**：解 `chat_draft_lru` 的 lru-cache dump（`[[key,{value}],...]`
  两层包装 + legacy 纯字符串条目读时兼容）。排序照 `ChatGrid.tsx:99-121`：
  **无草稿时保持接口原序**（RN 的 `draftMap.size===0` 捷径是行为对等不是优化）、
  有草稿时 pinned 恒前 + 草稿 `updatedAt`/`latest_time*1000` 混排降序；
  **mini_phone 行不吃同角色草稿**（草稿键是角色 id 会串显）。
  排序在 `ChatListState.sortedThreads` 派生层，`threads` 保持接口序。
- **stableKey 刻意不对等**：RN FlatList 的 key 掺 index 与 latest_time
  （对 key 冲突宽容的历史妥协）；LazyColumn 遇重复 key 直接崩，改用业务四元组
  `item_type:id:chat_mode:conversation_id`（mini_phone 同角色多入口靠
  conversation_id 区分，JVM 测试锁死）。
- **徽章四条件**（`ChatListItem.tsx:423-426`）：`sub_level>0` && 账号
  `relationship_switch`（`CurrentUser` 新增该字段）&& 角色 `is_relationship_open`
  && 非 mini_phone。徽章批拉是独立旁路任务，晚到只更新徽章 map 不触列表
  （§8.4「晚到 banner」同型）；只走 auth 轨校验（mutation 轨会被本地删行误废）。
- **种子缓存**：壳自己的 key `shell-chat-list-seed`（信封 version/authScope/
  savedAt/TTL 7 天），**不读不写** RN 的 `chat-list-cache` 实例（裸数组无门禁，
  会话列表全是账号私有数据，跨账号泄漏比 Home 严重）；**登出清**。
  语言刻意不做门禁（§4.6 反直觉条款）。只存第一页。
- **跨容器刷新**：`CHATTED_LIST_REFRESH` 事件（发送方全在 ChatDetail 深栈）
  跨不过 Surface→原生页边界 —— 原生对应 `markStale()` + 下次 onAppear 重拉，
  接线属 P9（ChatDetailSurface 启用时）。
- **cinema XML 剥离**：`convertCinemaXmlToMarkdown` 移植（image_prompt/options
  整块删、dialog 四种冒号支持 + 标准冒号引号输出、异常回退原文）。
- **时间格式恒数字不走 locale**：今天 `H:mm` 小时**不补零**、今年 `MM/DD`、
  跨年 `MM/DD/YY`（RN 裸 `getHours()`，别顺手本地化）。
- **出口现状**：点会话行 → `AppRoute.ChatDetail`/`MiniPhoneChat`、铃铛 →
  `AppRoute.Letter`，P9 前全部被 Router 明确拒绝（§8.3 形态，与 Home 卡同型）；
  game 条目无路由（SimulatorGame WebView 不迁），埋点后 `Log.w`。
  点击判定素材（isStory/characterType/contentType）**暂不透传** ——
  `AppRoute.ChatDetail` 扩参属 P9 包。
- **埋点**：`page_exposure`（chat_list）、simulator 卡曝光/点击
  （`time_corridor`，2s 节流照 `simulatorGameTracking.ts:59`；曝光停留 1s
  近似 RN 的 `minimumViewTime`，95% 可见精确判定后置）。
- **左滑操作自绘**：M3 `SwipeToDismissBox` 是滑走删除语义，不合 iOS 风格
  stay-open —— `detectHorizontalDragGestures` + 148dp 双键（Delete 红 +
  Pin 橙）、40dp 阈值、同表单行互斥、滑开点行主体收起（对齐 RN Swipeable）。
- **基建顺带改动**：`TipsyApplication.generations` 从局部变量提升为属性
  （页面级消费者第一次出现）；`LocalizedText` 加 `fontSize` 参数；
  `CurrentUser` 加 `relationshipSwitch` 字段。位图移植 18 张
  （chatlist 12 + relationship 徽章 6，`drawable-nodpi` 命名规范化 `ic_*`）。
- **仍不做**（P2/P9/后置）：Map 廊道视图、启动后台预取 page 0、
  `firstInteractive` 性能埋点族、simulator 曝光 95% 精确判定、
  批量 relationship 徽章的 `RELATIONSHIP_LEVEL_UPDATED` 事件重拉
  （发送方在 ChatDetail 深栈，同跨容器刷新一并属 P9）。
- **词条零新增**（Time Corridor / Draft / Image / Pinned× 4 / Delete failed /
  空态长句等 14 个 key 全在 SHELL_KEYS 且 26 locale 已导出——iOS 壳先迁时加过）；
  **连续第五包零 submodule 改动**。

#### 验证

- app 单测 **649 条**（+45：`ChatThreadParserTest` 12（标量漂移/未知类型丢弃/
  game id 归属/mini_phone 三元组/creator 三级兜底/坏条目跳过）+
  `ChatListTextTest` 14（时间三段/cinema 剥离含全角冒号/排序派生含无草稿
  原序捷径/徽章四条件/信封 merge 只改一键）+ `ChatDraftStoreTest` 5
  （现行/图片/legacy 字符串/空草稿跳过/坏 JSON）+ `ChatListViewModelTest` 14
  （分页续拉限次/失败不清列表/**mutation 闸门防复活**/convEpoch 三情形/
  pin 重排两方向/auth 闸门丢在飞/登出只清不拉/未登录不发/徽章过滤/偏好写入））、
  failures=0、**skipped=0**
- `:tipsy-auth:testDebugUnitTest` 15 条全绿（含 `LiveAppSafetyTest`）
- lint 无新增（baseline 仍 5 条）；`assembleGooglePlayDebug` 与
  `assembleDirectApkDebug` 通过
- **未动** manifest / RN 依赖 / flavor → release manifest diff 本刀不适用
- **NOT RUN**：真机冒烟（列表渲染/左滑操作/删除对账/草稿显示/打码/徽章）——
  推 PR 走 G1 后按 §9.2 补；RuStore flavor 未单独 assemble（flavor 相关零改动）

## 3. 横切能力


| 能力 | 状态 | 落地处 |
| --- | --- | --- |
| Auth 所有权 | 🟡 **closeout 已实现且 CI 已验**（§2.22） | `shell/auth/`（§2.13 / §2.18）。single-flight/generation/原子条件清理已收口；历史 token 迁移未完（P2） |
| `tipsy-auth` Android 实现 | 🟡 **桥已注册、能力 PARTIAL** | `modules/tipsy-auth/android/` + `ShellAuthProvider`；主线程约束已落地，Login/Profile 等真实能力仍按波次接线 |
| 网络层 | 🟡 **closeout 已实现且 CI 已验**（§2.22） | `shell/network/`（§2.14 / §2.18）。过期 token 发送守门与双入口共享 gate 已实现。**未引 Retrofit** |
| i18n | 🟢 **已完成** | `shell/i18n/`（§2.16）。壳是唯一 writer；key-based 查表 + 两条 normalize 规则 + Compose 自订阅组件。**语言设置页仍在 RN**（刻意，方案 §8.1） |
| Router / 深链 | 🟡 parser/router 机制已落地 | `shell/router/`；真实 Surface 参数、Login/Profile 接线与 P9 matrix 未完成，ChatDetail 在 P9 前保持关闭 |
| RN Surface 宿主 | 🟡 机制已落地、闭环待收口 | `RNSurfaceFragment`（共享单 ReactHost）；UUID/首帧/reappear/props builder 已有，真实 instance-aware close 尚未闭环 |
| Push | 🔴 未开始 | — |
| Analytics（Qt） | ⏸️ **推迟，但 facade 已落地** | `shell/analytics/Analytics`（§2.23）：业务页照常调用、uid 排队语义照搬 RN，debug 落日志。Qt 接线本身仍推迟（§2.17）—— ⚠️ **`preInit` 一次都不会调**，facade 存在 ≠ 埋点在上报 |
| 营销 SDK（ATT/AppsFlyer/FB/TikTok） | 🔴 未开始 | iOS 事故点，方案 §4.2 |
| Sentry | ⏸️ **已决定推迟** | 同上（§2.17）。⚠️ JS 侧 `autoInitializeNativeSdk: false` 已把事件交给一个从未 init 的原生 SDK |
| Widget | 🔴 未开始 | — |
| OTA | 🔴 未开始 | 隔离方案见 §5.3。W0 已**显式禁用** expo-updates 资源任务（原因见 §2.2.2），W4 接入时需先解决其 projectRoot 推导 |
| CI | 🟡 **G1 已激活** | `.github/workflows/android-ci.yml`（§2.10）。**G3 nightly 未建** —— 三 flavor 全量与 release 打包无自动防线 |

## 4. Surface 验收矩阵

`DebugSurface` 已完成 W0 的宿主机制验证，但它是自检入口，不代表生产 Surface
通过 §9.1。其余 12 个生产 Surface 均未验收，且 ChatDetail 在 P9 前保持 disabled：

`ChatDetailSurface` / `CommentsSurface` / `OnboardingSurface` / `CreateSurface` / `DeleteAccountSurface` / `EditProfileSurface` / `GemsSubscriptionSurface` / `NotificationSurface` / `RoleCardSurface` / `SettingsSurface` / `UserCoinsSurface` / `WidgetSurface`

矩阵表格见方案 §9.1。**未填满的行不得标 production-ready。**

## 5. 未决问题

方案 §12 是开放问题登记，不再能写成“10 项全部未决”：

- **§12.1 Qt lifecycle** 已按 §2.17 决策推迟；原“保留 listener / 排除模块”
  二选一前提也已被源码证据推翻。
- **§12.5 `AuthBootstrapSurface`** 随 P2 剩余/P3 合并推迟到上线前。
- **§12.3 QA 分发形态**仍需发布阶段定案，但 W0 已完成，不能继续写成“阻塞 W0”。

当前仍需 owner 结论的 W1 项：

- **§12.7 凭据分类与轮换**（安全 owner 结论）。

阻塞 W2 的：

- **§12.8 Google/Firebase 的 Android 签名指纹**（三 flavor × debug/release，**没有它 Firebase 登录无法真机验证**）
- ~~**§12.4 Home 是否包含 World 系列**~~ ✅ **已关闭**（2026-08-11，§2.23）：
  不需要产品决策 —— `home.tsx:505-511` 的 filter 已给出答案，**Android 显示 World、
  Multi-character 两端都隐藏**。World 点进去是 SimulatorGame WebView，方案 §8.1 已定不迁。
- **§12.9 Apple 登录按钮在 Android 是否展示**、**§12.10 `/login/password` 是否对外**

W2 真机验证新增的一项（2026-08-12，§2.23.1）：

- **性别筛选持久化在信封缺失时静默失效** —— `config-persist-storage` 不存在时
  `mergeGenderIntoEnvelope` 刻意 `return null` 不写，导致全新安装用户改性别
  永不持久化、每次冷启动退回 `All`，且 UI 无任何提示。已核实壳读写路径正确
  （RN 的 `zustandStorage` 也是默认 MMKV 实例），根因是信封尚未被 RN 初始化。
  **需 owner 在两条路里定**：(1) 缺失时写仅含 `{state:{gender}}` 的最小信封
  （须先核实 RN 的 `version` / `merge` 配置，否则可能触发 migrate 分支）；
  (2) 判为可接受，等 W3 迁 Settings 时 RN store 必然已初始化而自愈。

## 6. 已废弃的历史尝试

`migration/android-native-p00-bootstrap` 分支（P00 文档基线 + Gradle 脚手架尝试）
**已于 2026-08-08 废弃并删除远端**，其上工作作废，**不作为任何决策依据、不要去恢复参考**。

其中仍然有效的知识已全部吸收进当前两份文档：

| 原分支上的内容 | 现在在哪 |
|---|---|
| iOS 迁移复盘（时间线 / 十条经验 / 反模式） | 方案 §1.3 归属表、§3.2 各 ADR、§1.2.1 十条经验与反模式、§8.4 列表纪律、§10 风险登记 |
| Node 可执行文件解析（fnm/nvm 下 GUI 启动 sync 失败） | 方案 ADR-004 第 3 条（四级解析优先级 + launchd GUI 域 PATH 两层，含 2026-08-10 对「PATH 前置」的订正） |
| 三渠道 / config plugin / 桥模块等硬约束 | 方案 §2（**已在 pin `93d2c5551` 重新核实过源码**，不依赖旧报告） |
| CNG prebuild 审计报告（基线 `cbd521f02`） | 不再引用。其结论中可核实的部分已重新核实；**RN lint/test/doctor 的具体红项数量待 W0 实跑** |

**纪律**：本仓不再有"去某个分支恢复内容"的路径。方案与本文是唯一依据。

## 7. 状态更新纪律

1. 每个波次开始时把 `source_rn_sha` / `target_android_sha` 填成完整 40 位 SHA。
2. 波次结束跑 RN delta 审计，把变化映射到对等矩阵。
3. 发现文档与代码不一致时，**先修文档再继续实现**。
4. 不在其他文档里复制状态快照——重复的「当前进度」是 iOS 侧真实发生过的漂移源（同一文档记过不同的 submodule pin）。
