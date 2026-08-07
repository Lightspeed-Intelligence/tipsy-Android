# P00：仓库、构建基线与 Brownfield POC

## 任务元数据

| 字段 | 值 |
|---|---|
| Task ID | `ANDROID-P00` |
| Execution status source | `../reference/android-native-progress.md` 的 P00-W0..W5；本文件不复制状态 |
| 目标仓库/目录 | `tipsy-Android` 根目录 |
| source_rn_commit | `cbd521f02972933c21f90c01787ea5c11200875e` |
| target_android_base_commit | `UNBORN`；若文档已形成首提交，记录实际 HEAD 后继续 |
| Depends on | 无 |
| Blocks | P01-P06 |
| Hotspot owner | 本 packet 唯一执行者：Gradle、Manifest、Application、MainActivity、CI、submodule |

## 开始前必读

- `AGENTS.md`
- `llmdoc/overview/project-overview.md`
- `llmdoc/architecture/android-native-migration-blueprint.md`
- `llmdoc/architecture/android-build-release-ota-architecture.md`
- `llmdoc/reference/android-quality-gates.md`
- `llmdoc/reference/android-native-progress.md`
- RN：`package.json`、`package-lock.json`、`app.config.js`、`eas.json`、`index.surfaces.js`、`plugins/`、`modules/*/android/`

## 唯一目标

把空仓变成可重复构建、可审查、有真实 CI 的 Kotlin/Compose Android brownfield 工程，并用固定 RN SHA 证明一个 `DebugSurface` 可在单 React Runtime 中打开/关闭。此 packet 不迁业务页面。

## 允许修改

- Android 仓根的文档、Gradle、`app/`、`rn-host/`、最小 `core/`、`scripts/`、`.github/`、`.gitignore`、`.gitmodules`。
- 仅以 submodule pin 方式加入 `tipsy-app`。

## 禁止修改

- `tipsy-app` 内任何文件或其生成 `android/`。
- production package id、远端 EAS channel/runtime、商店后台、签名/keystore、生产 versionCode/versionName。允许本 packet 初始化唯一的不可发布开发值 `versionCode=1`、`versionName=0.0.0-dev`。
- Expo/RN/Gradle/Kotlin 版本升级。
- 业务页面、支付、push、OTA 发布。

## 已知事实与失败

- 目标仓无 HEAD、Gradle、CI。
- RN Android 工程由 CNG 生成且未跟踪。
- 临时 prebuild 已观察到 Gradle 8.14.3 / AGP 8.11.0 / Kotlin 2.1.20 / SDK 36 / min 24 / NDK 27.1.12297006。
- `expo-doctor` 审计日只有 13/18 checks 通过，存在 dependency mismatch；本任务记录但不擅自升级。
- CNG 插件会字符串改写 MainActivity/MainApplication，且 RuStore dependency 混入通用构建；正式工程不能沿用该方式。

## 明确交付物

1. `tipsy-app` submodule 固定到 source SHA。
2. 可提交审查的根 Gradle 工程与 wrapper。
3. `:app`、`:rn-host`、`:core:common`、`:core:testing` 最小模块；无空 feature 模块。
4. Kotlin/Compose 原生入口，单 ReactHost + DebugSurface 容器。
5. `googlePlay`、`directApk`、`ruStore` 三 flavor 和 debug/qa/release build type 骨架。
6. 明确 `NOT_FOR_STORE` 的开发版本 `1 / 0.0.0-dev`；release 无 debug signing、无上传能力。
7. flavor manifest/applicationId/dependency 隔离测试。
8. format/static analysis/unit/lint/三 debug assemble 的 GitHub Actions。
9. 基线审计脚本/报告与更新后的 progress debt ledger。
10. 本地开发说明：JDK/SDK/Node、Metro 8083、构建/运行 DebugSurface。

## 非目标

- 不实现 Android `TipsyAuth`（P01）。
- 不打开需要登录的业务 Surface。
- 不接真实支付/push/analytics/OTA。
- 不产出可上传商店的 signed release。
- 不做 isolated AAR。

## 实施步骤

### P00-W0：Preflight 与基线报告

1. 执行 `AGENTS.md` 的 dirty/SHA 检查；保留用户已有改动。
2. 记录 JDK、Android SDK、Node、npm 版本。
3. 若无 submodule，执行等价操作：

   ```bash
   git submodule add git@github.com:Lightspeed-Intelligence/tipsy-app.git tipsy-app
   git -C tipsy-app checkout cbd521f02972933c21f90c01787ea5c11200875e
   ```

   不 commit/push；确认 `.gitmodules` URL 与 pin。
4. `npm ci --no-audit --no-fund`，运行 quality gates 中全部 RN baseline 命令；把真实退出码、首个错误和是否历史失败写入 progress debt ledger。
5. 在 `mktemp -d` 的固定 SHA 副本运行一次 `npx expo prebuild --platform android --clean --no-install`，保存**版本/Manifest/依赖差异报告**到 `llmdoc/reference/`；不要复制临时目录、绝对路径或 secret 到仓库。

### P00-W1：建立固定 Gradle 工程

1. 以生成工程为兼容参考，创建根 wrapper/settings/build/version catalog；使用 Kotlin DSL 或 Groovy必须全仓统一并记录选择。
2. 固定工具链：JDK17、Gradle8.14.3、AGP8.11.0、Kotlin2.1.20、SDK36/min24、NDK27.1.12297006；无动态版本。
3. Compose 使用固定稳定 BOM；审计日参考 `2026.06.00`。解析/编译不兼容时从官方 metadata 选择兼容稳定值，记录理由与实际值，不改 Kotlin/RN 基线。
4. defaultConfig 初始化 `versionCode=1`、`versionName=0.0.0-dev`，生成 metadata 明示 `NOT_FOR_STORE`；后续生产版本只能由 P06 授权流程修改。
5. 根工程显式将 RN project root 指向 `tipsy-app`；settings 中从其 `node_modules` 解析 RN Gradle plugin 和 Expo autolinking。不得假设 `node_modules` 在 Android 根。
6. RN release/qa entry 显式为 `tipsy-app/index.surfaces.js`；Debug 设置三 flavor 为 debuggable，Metro 端口 8083。
7. `MainApplication` 按 SDK54 生成模板保持 `ReactApplication`、`ReactNativeHostWrapper`、`loadReactNative`、`ApplicationLifecycleDispatcher`，再包入本仓 `SurfaceRuntimeManager`；证明 runtime 单例。
8. MainActivity 优先保留 SDK54 的 `ReactActivity` + `ReactActivityDelegateWrapper`（`ReactActivity` 本身可承载 support Fragment），但不自动挂完整 RN root；Native 设置自己的 Fragment container，再以 ReactFragment 挂 `DebugSurface`。若 nullable main component/自定义 content 与 SDK54 不兼容，才在 POC 中比较 AppCompatActivity + 手动 Expo lifecycle 方案并记录 ADR。处理 API36 back，不使用已废弃的直接 `onBackPressed()` 方案。

### P00-W2：Flavor 与 Manifest

1. 创建 `distribution` dimension：
   - `googlePlay` → `com.tipsyturbo.app`
   - `directApk` → `ai.lightspeed.tipsy`
   - `ruStore` → `com.tipsytavern.app`
2. common namespace 选定后写 ADR；namespace 不作为生产身份，不在 flavor 改源码包。
3. RuStore repository/dependency/activity/intent 仅进入 `ruStore`；Play Billing 仅进入 `googlePlay`；Direct 策略先以空 adapter + 明确 TODO/contract，不伪造支付。
4. 从 RN 固定 SHA 的 Firebase 配置验证每个 application id 有匹配 client；配置来源不得打印 secret。若缺 client，记录 BLOCKED，不复制其他包 client 冒充。
5. 最终 merged manifest 快照断言：applicationId、exported、schemes、permissions、widget/service 目前未误启用、debug cleartext 仅 debug。
6. 创建 `qa` build type 骨架但不接生产凭据。允许本地 debug signing 仅用于 smoke，必须在产物 metadata 标 `NOT_FOR_STORE`；release 不得回退 debug signing。
7. Firebase 文件若含额外 `com.tipsytavern_ai.app` client，只能忽略/审计，不创建未批准第四 flavor。

### P00-W3：最小 Native UI 与 Surface POC

1. Compose 显示一个带以下稳定 tag 的内部首页：
   - `android.bootstrap.root`
   - `android.bootstrap.open_debug_surface`
   - `android.bootstrap.runtime_count`
2. 点击打开 ReactFragment `DebugSurface`，传 versioned initial props 和唯一 instance ID。
3. DebugSurface ready/close 暂以容器生命周期和可见性信号验证；不要在本 packet 设计完整业务 bridge。
4. Back 关闭 Surface 回 Native 首页；重复点击/快速 back 不崩溃、不双 pop。
5. 记录 React Runtime 构造次数，debug/assert 始终为 1；不得把 token/props 内容完整打日志。

### P00-W4：质量工具与 CI

1. Android lint + Kotlin format/static analyzer 接入 `check`。精确插件版本固定在 catalog；不使用宽泛 baseline 隐藏新问题。
2. 单元测试：flavor mapping、bundle entry、runtime singleton、surface instance close idempotency。
3. instrumentation：启动 Native root、打开 DebugSurface、断言 component tag/文本、back 返回 root；API36 emulator 优先，API24 可放 nightly。
4. GitHub Actions 使用 JDK17、缓存 Gradle/npm，先 `npm ci` 再跑 Gradle；不得依赖开发机 node_modules。
5. PR gate 跑静态检查、unit、lint、三 debug assemble。instrumentation 如 CI 环境暂不可用，创建 nightly job 并在 progress 明确 NOT RUN，不可假装通过。

### P00-W5：写回可重复命令

1. 新增本地开发文档，列出 clone/submodule、npm ci、SDK、Metro 8083、三 flavor build/run。
2. 从 `./gradlew :app:tasks` 复制真实 task 名到 quality gates，删除不存在的占位。
3. 更新 progress：Android HEAD 仍可能是 UNBORN/工作树；写实际 RN SHA、每条命令结果、风险、下一个 READY。

## 必须新增的测试

- `DistributionConfigTest`：三个 flavor 的 id/channel/payment adapter 映射。
- Gradle/manifest verification：merged manifest 不串 RuStore/Play 组件。
- `SurfaceRuntimeManagerTest`：单例与 Activity 引用不被持有。
- `SurfaceInstanceStateTest`：ready/close 幂等与 stale instance 丢弃。
- `BootstrapSmokeTest`：Native root → DebugSurface → back。
- CI workflow syntax/本地等价命令验证。

## 自动验收命令

P00 实现后按实际 task 名执行，最低预期：

```bash
git submodule status --recursive
test "$(git -C tipsy-app rev-parse HEAD)" = "cbd521f02972933c21f90c01787ea5c11200875e"
./gradlew --version
./gradlew projects
./gradlew check
./gradlew lint
./gradlew test
./gradlew :app:assembleGooglePlayDebug
./gradlew :app:assembleDirectApkDebug
./gradlew :app:assembleRuStoreDebug
```

然后对三 variant 输出执行 package/manifest verification。任何命令不存在、退出非 0 或 variant 串配置，P00 不得 DONE。

## 手工 QA（Given / When / Then）

1. Given Node/Metro 未启动，When 打开 debug App 并请求 Surface，Then 显示可诊断错误/重试，不让 Native root 崩溃。
2. Given Metro 8083 启动且 entry 为 `index.surfaces.js`，When 点击按钮，Then 显示 DebugSurface。
3. Given Surface 可见，When 系统 back/连续 back，Then 只关闭一次并回 root。
4. Given 连续开关 30 次，When 查看 runtime counter/heap，Then runtime 始终 1，无明显单调泄漏；P04 再扩到 50 次正式 gate。
5. Given 旋转和后台恢复，When 返回，Then Native root 或 Surface 以定义的安全状态恢复，不黑屏。

## 回滚与清理

- 仅删除/回退本 packet 新建且可确认属于本任务的文件；不得 reset 用户文档改动。
- 临时 prebuild 用 `mktemp -d`，报告产出后清理临时目录，不清广泛路径。
- submodule 添加失败时保留 `.gitmodules` 前先检查状态；只撤销本次半成品，不改远端。
- 不创建/删除远端 branch/tag，不发布 artifact。

## 必须停止并请求人工输入的条件

- 任一生产 application id 与 RN 源码不一致。
- Firebase 配置无匹配 client。
- Expo/RN 固定版本无法用兼容工具链构建，唯一解决方案似乎是大版本升级。
- 需要生产 keystore/EAS/商店凭据。
- 工作树已有与 Gradle/Manifest/CI 冲突的用户改动。

## Definition of Done

- 所有明确交付物存在并可审查。
- 三 debug flavor 从干净 clone + submodule + npm ci 可 assemble。
- PR fast gate 实际运行且绿色；历史 RN 红项有债务记录，无新增 ignore。
- DebugSurface 在 emulator/设备打开、返回、旋转/恢复；单 runtime 证据存在。
- 未修改 RN 子模块内容、包名、签名、远端 OTA/商店状态。
- progress 将 P00 标 DONE、P01 标 READY，并附实际 SHA/命令/风险。

## 完成回报格式

按 `AGENTS.md` 的 7 项格式，并额外粘贴：三 variant application id/manifest 摘要、runtime 计数证据、RN baseline debt 表。
