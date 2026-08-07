# llmdoc 索引

## 文档分类

| 类型 | 用途 | 是否记录完成状态 |
|---|---|---|
| `overview` | 项目背景、仓库关系、迁移目标 | 否 |
| `retrospective` | iOS 已完成迁移的事实复盘与经验 | 否 |
| `architecture` | 长期目标、边界与不可破坏契约 | 否 |
| `reference` | 功能矩阵、质量规则、唯一进度 | 仅 progress 可以 |
| `execution` | 一次可由 Codex 独立执行并验收的任务包 | 否；状态只引用 progress |

禁止在多份文档复制“当前完成状态”。发现漂移时，以代码、Git SHA 和 `android-native-progress.md` 为准，修正其他文档中的错误快照。

## 核心文档

### 背景与复盘

- [项目现状与目标](overview/project-overview.md)
- [iOS Native 迁移复盘](retrospective/ios-native-migration-retrospective.md)

### 架构契约

- [Android Native 迁移蓝图](architecture/android-native-migration-blueprint.md)
- [Android Native / RN Surface 边界](architecture/android-rn-boundary-contract.md)
- [构建、发布与 OTA 架构](architecture/android-build-release-ota-architecture.md)

### 执行依据

- [唯一进度状态](reference/android-native-progress.md)
- [RN 功能对等与归属矩阵](reference/rn-parity-contract-matrix.md)
- [Android 质量门禁](reference/android-quality-gates.md)

### 顺序执行的任务包

1. [00 仓库、基线与 Brownfield POC](execution/00-repository-bootstrap-and-baseline.md)
2. [01 平台基础与跨端契约](execution/01-platform-foundation-and-contracts.md)
3. [02 首个 Native 垂直切片](execution/02-first-native-vertical-slice.md)
4. [03 核心 Tab 分波迁移](execution/03-core-tabs-migration-waves.md)
5. [04 高风险媒体、RN Surface 与系统能力](execution/04-high-risk-media-surfaces-and-system.md)
6. [05 对等、性能与质量加固](execution/05-parity-performance-and-quality.md)
7. [06 三渠道发布、覆盖升级与切换](execution/06-release-cutover-and-rollback.md)

## 执行原则

- 一次 Codex 工作只处理 progress 中唯一 READY 的 work unit；大型 packet 定义内部子任务及串行/并行规则，但状态仍只在 progress 更新。
- 上游 gate 不通过，下游 packet 保持 `BLOCKED`。
- 外部凭据、生产发布、商店操作只提供 runbook 和验证脚本，不由 Codex 自行执行。
- 每个迁移波次结束都要固定新的 RN SHA 或确认仍使用原 SHA，并运行一次 parity delta audit。
