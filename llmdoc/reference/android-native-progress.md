# Tipsy Android 原生化迁移：现状（唯一状态真值）

> 更新：2026-08-08 ｜ Android 壳：**W0 完成**（gate 过 + API24/37 双端验证 + manifest 快照 + lint 硬门）；
> G1 CI 已写但**未激活**（缺 `PAT_TOKEN`，见 §2.10）
>
> **W1 进行中** —— 细化方案见 [`../architecture/android-w1-plan.md`](../architecture/android-w1-plan.md)；
> **P0 auth 桥已接通**（`isShellHost()` 实测 true，见 §2.11）
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
| W0 | 工程地基 + brownfield DebugSurface | 基建 | 🟢 完成 | `93d2c5551` | `4f191e8` |
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
7. **CI 已写但未激活** —— workflow 文件已进主干，只留手动触发；缺 `PAT_TOKEN` secret，见 §2.10
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

`app/lint-baseline.xml` 记录 **19 条**既有问题（9 条 `GradleDependency`、2 条
`UseTomlInstead`、2 条 `NewerVersionAvailable`、2 条 `AndroidGradlePluginVersion` 等）。
**baseline 是技术债台账而非豁免** —— 多数是「有更新版本可用」类提示，与 §3.3
钉死工具链的决定冲突，清理属后续波次。

顺带修掉：`expo-dev-client` 声明了 `org.webkit:android-jsc:+` 这个可选依赖，
但本工程用 Hermes、`jsc-android` 未安装也无对应仓库，任何需要解析它的任务
（实测 lint 的 `generate*LintModel`）都会失败。已在根 `build.gradle` 全局排除。

### 2.10 G1 fast gate CI（**已写，未激活**）

`.github/workflows/android-ci.yml`。与 `tipsy-app` / `tipsy-iOS` 的 `ci.yml` **分开** ——
那些是 agentic workflow（issue/PR 智能体），本文件是纯构建门禁。

序列：**lint（硬门）→ assemble googlePlayDebug → release manifest → 单测**。
本机模拟整条 **1m59s**（CI 上未实跑过）。

> ⚠️ **当前只保留 `workflow_dispatch`（手动触发），没有 `pull_request` / `push`。**
> 本仓还没有 `PAT_TOKEN`，拉不到私有子模块 → `npm ci` / autolinking / assemble / 单测
> 全都跑不了。自动触发只会让主干挂一个**永久红**的 workflow，比没有 CI 更糟。
> 启用步骤写在 workflow 文件头：配 secret → 放开 `on:` 里注释的两段 → 手动跑一次确认绿。
>
> **这意味着 G1 目前不构成任何门禁** —— 合并前的检查仍然靠人工在本地跑
> `./gradlew :app:lintDirectApkDebug :app:assembleGooglePlayDebug
> :app:processGooglePlayReleaseMainManifest :app:testGooglePlayDebugUnitTest`。
> 按方案 §5.4 的纪律，这属于 `NOT RUN`，**不等于通过**。

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

**前置条件：本仓需自己配 `PAT_TOKEN` secret（尚未完成，故 CI 未激活）。**

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

当前做法是**同一 PAT 值在 `tipsy-iOS` 与本仓各存一份**。
⚠️ **轮换该 PAT 时必须两个仓都改**，漏改一处会让对应仓 CI 在子模块那步失败。
若 Android 侧后续要加更多 workflow，值得改成 org secret + 授权本仓，只留一处真值。

技术细节：`.gitmodules` 用 SSH URL 而 CI 只有 HTTPS token，故 workflow 不用 checkout 的
`submodules` 选项，而是手工把 submodule URL 换成带 token 的 HTTPS（只改本地配置，
不写进 `.gitmodules`）。auth 形式用 `x-access-token`（已实测对私有仓有效）。
**缺该 secret 时 workflow 明确报错并说明原因，不静默跳过。**

**不得用 `--depth 1` 拉子模块**（这条经验来自 `tipsy-iOS` 的 `eas-build.yml`）：
子模块 pin 常滞后于 `tipsy-app` 的 main tip —— 实测当前 pin **落后 `origin/main` 175 个
commit**，浅拉只能拿到 tip、取不到 pin 的那个 commit，CI 会直接在子模块那步失败。

与 iOS 的一处有意差异：iOS 用 `git config --global ... insteadOf` 全局改写所有
`git@github.com:` 前缀；本仓只改 `submodule.tipsy-app.url` 一项，范围更窄、不影响
其他 SSH 操作，行为等价。

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
