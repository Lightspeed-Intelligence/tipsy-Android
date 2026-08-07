# P05：全量对等、性能与质量加固

## 任务元数据

| 字段 | 值 |
|---|---|
| Task ID | `ANDROID-P05` |
| Execution status source | `../reference/android-native-progress.md` 的 P05-*；本文件不复制状态 |
| 目标仓库 | `tipsy-Android`；必要时联动自动化仓/向后兼容 RN PR |
| source_rn_commit | `BLOCKED_UNTIL_P04_DONE`；转 READY 前 delta audit 并写完整 40 位 SHA |
| target_android_base_commit | `BLOCKED_UNTIL_P04_DONE`；转 READY 前替换为完整 40 位 SHA |
| Depends on | P04-A/B/C/DL DONE；P04-DE 可等待外部授权，但必须在 P06-GP/APK/RS 前完成 |
| Blocks | P06 |

## 唯一目标

把“主要路径能用”提升为可进入发布候选：清零未审计矩阵项，建立 API24/API36 nightly、视觉/无障碍/性能/泄漏/安全/故障注入证据，并对固定 RN baseline 和本波次 delta 做系统对账。

## 允许修改

- tests、benchmarks、fixtures、test IDs、可观测性、性能修复、文档/CI。
- 为通过已定义 parity gate 所需的小范围 feature/core 修复。
- 外部 Appium 仓只在用户提供且明确授权时修改；否则输出映射/任务单。

## 禁止修改

- 新功能、架构重写、大版本升级、Native 覆盖范围扩大。
- 删除 RN fallback/Surface/旧存储/bridge N-1。
- 修改包名、签名、生产 channel/runtime、发布。
- 为绿色指标放宽门禁、删除测试、扩大 lint baseline。

## 交付物

1. parity matrix 所有 P00-P04 scope 无 `AUDIT_REQUIRED`/UNKNOWN。
2. RN delta audit 报告：接受/补迁/延期/不相关，每个变更有 owner。
3. API24/API36 nightly + 低内存真机报告。
4. 完整 Native↔RN route/deep-link/push/testTag 自动化地图。
5. screenshot/视觉、TalkBack/字体/RTL、Macrobenchmark、Surface leak、media soak 报告。
6. merged manifest/permission/dependency/SBOM/security audit。
7. Sentry/ANR/analytics dashboard 校验与发布阈值提案。
8. QA regression checklist 与 P06 go/no-go 输入。

## 实施步骤

### P05-1：固定源与 Delta audit

1. 记录 Android/RN SHA、工作树、依赖 lock。
2. 用 parity matrix 的 delta 命令比较本波次基线与候选 RN SHA。
3. 每个影响路径映射 matrix ID，分类：
   - 必须补齐才能 release。
   - 保留 RN Surface 已自动包含。
   - Android 平台不适用且有证据。
   - 延期，需要产品 owner 批准。
4. 如需 bump pin，先完成 RN gates/Surface N/N-1，再固定新 SHA；不在 QA 中持续追 head。

### P05-2：功能/错误/生命周期对账

逐 matrix 行核对：

- loading/content/empty/error/offline/retry/pagination。
- 游客/登录/token 过期/logout/account switch/402。
- cold/warm/background/process recreation/rotation/back/predictive back。
- Native↔RN/Push/deep link/Widget/notification routes。
- 三 flavor 内容/支付/SDK/headers。
- analytics event 次数、payload、source/session/recommend context。

每个缺口必须有复现测试后再修；禁止只手工点击后标通过。

### P05-3：自动化与 test ID

1. 导出所有 Compose testTag/contentDescription 与 RN testID，生成 registry；检测重复、缺失、破坏性改名。
2. 建核心 journey：
   - old logged-in install → overlay → Home → Chat Surface → back。
   - Login → Home/Search/Profile/ChatList。
   - Create fake tab → CreateSurface → close。
   - Screen media → Comments/Chat Surface。
   - push/deep link/widget cold/warm。
   - logout/account switch/language。
3. 外部 Appium 仓不可用时，提供精确 ID/route/fixture/expected destination 的任务包，不谎称 E2E 已执行。

### P05-4：视觉与 accessibility

- 每个 Native screen 关键状态 golden；与 RN reference 人工 review 差异，不要求复制 Android 不合适的 iOS 像素。
- fontScale 1.0/1.3/2.0，窄屏/大屏，light/dark（若产品支持），英文/德文/阿拉伯文/中文。
- TalkBack traversal、label/role/state/actions、动态列表、modal focus、map alternative。
- touch target、color contrast、键盘/IME/insets/edge-to-edge。

### P05-5：性能、稳定性与故障注入

用旧 RN 同设备/同数据/同网络与 Native 对照：

- cold/warm startup、time-to-interactive/first content。
- Home/ChatList/Screen scroll frame/jank。
- Surface first frame、50 次交替、runtime count、PSS/Java/native/GPU heap。
- Screen 30min soak、快速 fling、网络切换、后台/低内存回收。
- APK/AAB size、各 ABI、R8 effect。

故障注入：超时、断网、5xx、malformed scalar/envelope、refresh race、磁盘满/损坏 cache、OTA 损坏/不兼容、process kill、重复 Intent/push、SDK init failure。App 必须安全失败/重试/fallback，不泄 token。

预算以 P02/P04 实测和产品 owner 批准为准；报告 p50/p95/样本/设备，不只写单次最好值。

### P05-6：安全/供应链/可观测性

- `dependencyVerification`/lock 或等价机制；生成依赖/SBOM/许可证报告。
- 扫描 repo/artifact/log 中 secret、keystore、token、绝对用户路径。
- exported/intent filter/cleartext/FileProvider/WebView/deep-link input/backup policy 审计。
- R8 mapping/native symbols/RN source map 用测试 crash 验证反符号化。
- Dashboard 能按 version/build ID/flavor/runtime/bundle source 分 crash/ANR/startup/Surface/API/业务事件。
- 发布阈值使用旧 RN baseline 与业务 SLO；没有数据不虚构数字，标明 owner/采样期。

## 自动验收

```bash
./gradlew clean check lint test
./gradlew :app:assembleGooglePlayDebug
./gradlew :app:assembleDirectApkDebug
./gradlew :app:assembleRuStoreDebug
# API24 connected smoke
# API36 full instrumentation
# screenshot/golden verification
# macrobenchmark
# dependency/manifest/permission/SBOM verification
```

clean build 只能在确认不影响用户工作树且命令不会删除源码后执行。所有精确 task 名从工程读取并写入完成报告。

若 RN pin/bridge/Surface 变化，运行完整 RN gates、embedded export、N/N-1；否则运行 delta audit 与 targeted tests。

## QA 矩阵

| 维度 | 最低集合 |
|---|---|
| API | 24、36 |
| 设备 | 低内存真机、Play 服务真机、Direct/RuStore 验证设备 |
| 身份 | 游客、登录、token 临过期、历史升级、多次升级、换号 |
| 网络 | Wi-Fi/移动、慢网、断网、切网、代理错误 |
| 生命周期 | cold/warm/background/rotation/process kill/reboot |
| 语言 | en、zh 输入归一化、长文案语言、RTL；active catalog key parity，28/27/26/服务端集合关系测试 |
| Bundle | embedded、批准的 preview OTA、N/N-1、离线/损坏 fallback |
| 渠道 | googlePlay、directApk、ruStore |

## 回滚

- 质量修复保持 feature/surface kill switch；不删除 fallback。
- 发现高风险 delta 时可固定旧 RN SHA，不强追最新版；记录产品差异。
- 测试/基线不可通过回滚工具来“消失”；修复或由明确 owner 接受风险。

## 必须停止的条件

- 任何跨账号/跨环境数据、token 泄露、支付/渠道串配置。
- 持续 OOM/ANR/Surface leak/启动或滚动显著回归且无批准。
- 关键 journey 无法自动/手工在真实渠道验证。
- 需要生产凭据、商店操作、生产 OTA或修改 SLO。
- RN delta 引入需重新决定 Native/RN 边界的大功能。

## Definition of Done

- matrix 对发布 scope 全部 `PARITY_VERIFIED` 或有 owner 批准且不会伪装完成的延期项。
- PR/nightly/QA gates 在干净环境实际绿色；所有 NOT RUN 有明确 blocker/owner。
- 性能/稳定/a11y/visual/security/observability 报告完整，无未接受红项。
- 三渠道候选范围和 P06 go/no-go 输入明确。
- progress P05 DONE、P06 READY。

## 完成回报格式

按 `AGENTS.md` 回报，并附 matrix 未完成项计数、RN delta 分类、API24/API36/真机矩阵、性能 p50/p95 样本信息、a11y/visual/security/observability 结论与所有风险接受者。
