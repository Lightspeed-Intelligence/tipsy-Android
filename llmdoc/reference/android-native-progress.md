# Tipsy Android 原生化迁移：现状（唯一状态真值）

> 更新：2026-08-10 ｜ Android 壳：**W0 完成**（gate 过 + API24/37 双端验证 + manifest 快照 + lint 硬门）；
> **G1 CI 已激活**（2026-08-10，`PAT_TOKEN` 已配，首次真绿，见 §2.10）
>
> **W1 进行中** —— 细化方案见 [`../architecture/android-w1-plan.md`](../architecture/android-w1-plan.md)。
> **P0 桥已接通**（§2.11）｜ **P1 auth 契约已完成**（§2.13）｜ **P2 机制已验、兜底推迟**（§2.12）
> ｜ **P2 剩余 + P3 已决定合并推迟到上线前**（2026-08-10，见 W1 计划 §5.6）
> ｜ **P4 Router 已完成**（含真机验证）｜ **P6 network 已完成**（§2.14）
> ｜ **P5 / P7 / P8 / P9 未开始**
> 配套决策方案：[android-native-migration-plan.md](../architecture/android-native-migration-plan.md)
> **本文是状态权威。** 方案文档只写决策不写状态；任何「进度/是否已实现」的问题一律以本文为准。

## 0. 三十秒速览

- **波次进度**：W0 完成；**W1 走到 P1**（P0 桥 + P1 auth 契约完成，P2 一半，P3 推迟，P4-P9 未开始）。
- **代码现状**：`ai.lightspeed.tipsy.shell` 下有 `TipsyApplication`（单 ReactHost）+ `MainActivity`（Compose 原生根）+ `RNSurfaceFragment`（**仍是 36 行 stub**）+ `auth/`（6 个类，token 真值）+ `bridge/ShellAuthProvider`。**仍是零业务代码。**
- **submodule**：pin `56c4bbfa7`（分支 `feat/tipsy-auth-android`，**未合进 main/release**，按约定靠子模块指针引用），`node_modules` 已装（1812 包）。
- **已验证**：三 flavor debug 构建通过、applicationId 正确、JS bundle 内嵌、51 个 project autolink、**Surface 两种 bundle 来源均可挂载**（§2.6）、**MMKV 互操作**（§2.12）、**auth 契约单测 62 条**（§2.13）。
- **不存在**：五 Tab、i18n、Sentry、core/feature 模块、**G3 nightly**（G1 已激活，但三 flavor 全量与 release 打包仍无自动防线）。Router 与 network 层已建（§2.14），但**只有 ChatDetail 一个目标启用**。

## 1. 波次状态

| 波次 | 内容 | 业务量 | 状态 | source_rn_sha | target_android_sha |
| --- | --- | --- | --- | --- | --- |
| W0 | 工程地基 + brownfield DebugSurface | 基建 | 🟢 完成 | `93d2c5551` | `4f191e8` |
| W1 | 平台契约 + auth + ChatDetailSurface gate | 基建 | 🟡 **进行中（P0/P1 完成）** | `56c4bbfa7` | — |
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

### 2.13 W1-P1：auth 契约（已完成）

**壳成为 token 的唯一刷新者与持久化者。** 单测 **62 条全绿（skipped=0）**，
人工门禁四步全过（lint 硬门 / assemble / release manifest / 单测）。

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
   `exp - now > 0 && < 300` —— 已过期返回 **false**。这类 token 被当作"未临过期"
   原样返回，发请求得 401，再走 authRejected 兜底。看着像 bug，是现网已验证行为。
   **`JwtTest` 有一条专测它**，防止有人"修正"成主动刷新。
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
**lint-baseline 未新增任何条目**（仍 19 条）。

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

### 2.14 W1-P6：network 层（已完成）

`shell/network/` 六个类，**单测 46 条**（连同既有共 **156 条**，skipped=0）。

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

两个入口（原生页经本层 / RN Surface 经桥）**汇到同一 handler**，
否则防抖各算一套，用户会看到弹两次登录页。

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

## 3. 横切能力

| 能力 | 状态 | 落地处 |
| --- | --- | --- |
| Auth 所有权 | 🟢 **壳已是 owner** | `shell/auth/`（§2.13）。刷新/登出/401 归属已实现；**历史 token 迁移未完**（P2） |
| `tipsy-auth` Android 实现 | 🟢 **已实现** | `modules/tipsy-auth/android/`（12 个必须方法 + 主线程约束），§2.11 / §2.13 |
| 网络层 | 🟢 **已完成** | `shell/network/`（§2.14）。OkHttp（与 RN 共享 client）+ 三鉴权模式 + envelope + 401/402 汇聚 + 标量漂移容错。**未引 Retrofit** |
| i18n | 🔴 未开始 | — |
| Router / 深链 | 🔴 未开始 | — |
| RN Surface 宿主 | 🟡 骨架就位 | `RNSurfaceFragment`（继承官方 `ReactFragment`，共享单 ReactHost）；**instanceId / 首帧协议 / onSurfaceReappeared 尚未实现**，见方案 §4.3 |
| Push | 🔴 未开始 | — |
| Analytics（Qt） | 🔴 未开始 | 归属待决策（方案 §12.1） |
| 营销 SDK（ATT/AppsFlyer/FB/TikTok） | 🔴 未开始 | iOS 事故点，方案 §4.2 |
| Sentry | 🔴 未开始 | — |
| Widget | 🔴 未开始 | — |
| OTA | 🔴 未开始 | 隔离方案见 §5.3。W0 已**显式禁用** expo-updates 资源任务（原因见 §2.2.2），W4 接入时需先解决其 projectRoot 推导 |
| CI | 🟡 **G1 已激活** | `.github/workflows/android-ci.yml`（§2.10）。**G3 nightly 未建** —— 三 flavor 全量与 release 打包无自动防线 |

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
| Node 可执行文件解析（fnm/nvm 下 GUI 启动 sync 失败） | 方案 ADR-004 第 3 条（四级解析优先级 + launchd GUI 域 PATH 两层，含 2026-08-10 对「PATH 前置」的订正） |
| 三渠道 / config plugin / 桥模块等硬约束 | 方案 §2（**已在 pin `93d2c5551` 重新核实过源码**，不依赖旧报告） |
| CNG prebuild 审计报告（基线 `cbd521f02`） | 不再引用。其结论中可核实的部分已重新核实；**RN lint/test/doctor 的具体红项数量待 W0 实跑** |

**纪律**：本仓不再有"去某个分支恢复内容"的路径。方案与本文是唯一依据。

## 7. 状态更新纪律

1. 每个波次开始时把 `source_rn_sha` / `target_android_sha` 填成完整 40 位 SHA。
2. 波次结束跑 RN delta 审计，把变化映射到对等矩阵。
3. 发现文档与代码不一致时，**先修文档再继续实现**。
4. 不在其他文档里复制状态快照——重复的「当前进度」是 iOS 侧真实发生过的漂移源（同一文档记过不同的 submodule pin）。
