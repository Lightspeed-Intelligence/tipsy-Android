# Tipsy Android 原生化迁移：现状（唯一状态真值）

> 更新：2026-08-08 ｜ Android 壳：**尚未开工**（仅 Android Studio Compose 模板脚手架）
> 配套决策方案：[android-native-migration-plan.md](../architecture/android-native-migration-plan.md)
> **本文是状态权威。** 方案文档只写决策不写状态；任何「进度/是否已实现」的问题一律以本文为准。

## 0. 三十秒速览

- **波次进度**：W0 未开始。
- **代码现状**：`app/src/main/java/com/example/tipsy_android/` 下是模板 `MainActivity` + Greeting Composable，**零业务代码**。
- **submodule**：`tipsy-app` 已挂上，pin `93d2c5551`，**`node_modules` 未安装**。
- **不存在**：flavor、rn-host、core 模块、feature 模块、桥实现、CI。

## 1. 波次状态

| 波次 | 内容 | 业务量 | 状态 | source_rn_sha | target_android_sha |
| --- | --- | --- | --- | --- | --- |
| W0 | 工程地基 + brownfield DebugSurface | 基建 | 🔴 未开始 | `93d2c5551`（待开工确认） | `fe349c0` |
| W1 | 平台契约 + auth + ChatDetailSurface gate | 基建 | ⬜ 阻塞于 W0 | — | — |
| W2 | Bootstrap + 五 Tab shell + **Login** + **Home** | 约 10k 行 RN | ⬜ 阻塞于 W1 | — | — |
| W3 | **Profile** + **ChatList** + **Search** + Settings 列表/语言 | 约 19k 行 RN（最大） | ⬜ 阻塞于 W2 | — | — |
| W4 | **Screen/Media3** + 12 个 Surface + 系统能力 + OTA | 约 5.3k 行 RN + 系统 | ⬜ 阻塞于 W3 | — | — |
| W5 | 对等 / 性能 / 三渠道发布切换 | 发布 | ⬜ 阻塞于 W4 | — | — |

**W0+W1 时间盒**：这两波不产出用户可见价值，目标是"够用就往下走"。若超过总工期 1/4,停下复审是否过度设计（方案 §8.5）。

## 2. 当前工程实况

### 2.1 已跟踪文件（51 个，非 submodule）

Android Studio 新建 Compose 工程的默认产物：`app/build.gradle.kts`、`MainActivity.kt`、`ui/theme/{Color,Theme,Type}.kt`、模板 res、`gradle/libs.versions.toml`、wrapper、`.idea/`。加本次新增的 `llmdoc/`。

### 2.2 与目标基线的已知偏差（W0 第一件事）

| 项 | 当前值 | RN 0.81.4 要求 | 来源 |
| --- | --- | --- | --- |
| AGP | `9.2.1` | `8.11.0` | `tipsy-app/node_modules/react-native/gradle/libs.versions.toml` |
| Kotlin | `2.2.10` | `2.1.20` | 同上 |
| compileSdk | `37` | `36` | 同上 |
| Gradle DSL | `.kts` | Groovy（方案 ADR-004） | — |
| namespace / applicationId | `com.example.tipsy_android` | 三 flavor 三包名 | `tipsy-app/src/constants/app.js` |
| 模块结构 | 单 `:app` | `:app` + `:rn-host` + `:core:*` | 方案 §3.4 |

targetSdk 36 与 minSdk 24 已经对齐，无需改动。

### 2.3 环境

| 工具 | 状态 |
| --- | --- |
| `tipsy-app/node_modules` | **未安装**。W0 需 `npm ci`（lockfile v3） |
| 根 `node_modules` 符号链接 | **未建**。方案 ADR-004 要求 |
| `sdkmanager` | 曾观察到不可用（未装 cmdline-tools）。W0 需实测并提供明确环境检查与 CI 安装路径 |
| emulator image | 未固定。W0 记录实际可用的 API 24 / API 36 image |
| Node / npm | W0 记录实际版本并在 CI 固定 |

### 2.4 已经不用做的事（RN 侧已就绪，实测）

`tipsy-app` 里已有 **55 个文件**完成壳适配（iOS 壳一年沉淀）：13 个 Surface 入口组件、`SurfaceToastHost`、`TipsyHeader` 栈底 `popSurface` 兜底、`useShellSurfaceRefocus`、`useChatNavigation` 壳分支、`shellGemsEntry`/`shellTaskEntry` 跨栈出口、`axios.ts` 的 401/402 桥上抛、`config_persist` nsfw 镜像接力、`recommendTracking` 壳 outbox、`api.ts`/`lane.ts` 壳 API 地址。

**Android 只要提供能让 `isShellHost()` 返回 true 的 Kotlin 桥，这些全部自动生效。** 另有约 4,500 行现成 RN 测试可作对等 fixture（方案 §8.2）。

## 3. 横切能力

| 能力 | 状态 | 落地处 |
| --- | --- | --- |
| Auth 所有权 | 🔴 未开始 | 需 §2.1 桥 + §2.4 迁移 |
| `tipsy-auth` Android 实现 | 🔴 不存在 | `modules/tipsy-auth` 仅 apple |
| 网络层 | 🔴 未开始 | — |
| i18n | 🔴 未开始 | — |
| Router / 深链 | 🔴 未开始 | — |
| RN Surface 宿主 | 🔴 未开始 | — |
| Push | 🔴 未开始 | — |
| Analytics（Qt） | 🔴 未开始 | 归属待决策（方案 §12.1） |
| 营销 SDK（ATT/AppsFlyer/FB/TikTok） | 🔴 未开始 | iOS 事故点，方案 §4.2 |
| Sentry | 🔴 未开始 | — |
| Widget | 🔴 未开始 | — |
| OTA | 🔴 未开始 | 隔离方案见 §5.3，**发布需独立授权** |
| CI | 🔴 不存在 | — |

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
