# Android Native 迁移进度（唯一状态事实源）

> 只有本文件记录“现在做到哪里”。架构、复盘和任务包中的状态示例不得覆盖本文件。

| 字段 | 当前值 |
|---|---|
| 更新时间 | 2026-08-07 |
| Android HEAD | `UNBORN` |
| RN source SHA | `cbd521f02972933c21f90c01787ea5c11200875e` |
| iOS reference HEAD | `4b42d8d` |
| 当前波次 | Phase 00：仓库与 Brownfield 基线 |
| 当前 work unit | `P00-W0`：Preflight 与基线报告 |
| 当前 READY packet | `execution/00-repository-bootstrap-and-baseline.md` |
| Project release state | `NOT_READY` |
| 最近验证环境 | 仅完成只读审计与临时目录 Expo prebuild；目标仓未构建 |

## 总体状态

| ID | 阶段 | 状态 | 依赖 | 完成证据 |
|---|---|---|---|---|
| P00 | 仓库、Gradle、CI、三 flavor、Brownfield POC | READY | 无 | 待执行 |
| P01 | Auth/Storage/Network/i18n/Router/Bridge 契约 | BLOCKED | P00 | 待执行 |
| P02 | Login → Native Home 首个垂直切片 | BLOCKED | P01 | 待执行 |
| P03 | Home/Profile/Search/ChatList 核心迁移波次 | BLOCKED | P02 | 待执行 |
| P04 | Screen 媒体、RN Surface、系统能力 | BLOCKED | P03 | 待执行 |
| P05 | 对等、性能、accessibility、nightly 加固 | BLOCKED | P04 | 待执行 |
| P06 | 三渠道覆盖升级、灰度、切换与恢复演练 | BLOCKED | P06-A 依赖 P05；渠道切换另依赖 P04-DE | 待执行 |

状态只能使用：`READY`、`IN_PROGRESS`、`BLOCKED`、`DONE`、`OBSOLETE`。

## Work unit 状态

这是可恢复执行的细粒度状态；每次只允许一个串行 work unit 为 `IN_PROGRESS`。明确标注可并行的 feature unit 可以同时 READY，但共享 hotspot 仍由单一 owner。

| Work unit | 内容 | 状态 | Depends on | Owner | 证据/阻塞原因 |
|---|---|---|---|---|---|
| P00-W0 | Preflight、submodule 与基线报告 | READY | 无 | unassigned | 待执行 |
| P00-W1 | 固定 Gradle 工程 | BLOCKED | P00-W0 | unassigned | 上游未完成 |
| P00-W2 | Flavor/Manifest | BLOCKED | P00-W1 | unassigned | 上游未完成 |
| P00-W3 | Native UI + DebugSurface | BLOCKED | P00-W2 | unassigned | 上游未完成 |
| P00-W4 | 质量工具与 CI | BLOCKED | P00-W3 | unassigned | 上游未完成 |
| P00-W5 | 命令、文档与 P00 汇总 | BLOCKED | P00-W4 | orchestrator | 上游未完成 |
| P01-A | 冻结 route/API/storage/event/surface registries | BLOCKED | P00 DONE | foundation | 上游未完成 |
| P01-RN | RN Android shell provider 独立 PR | BLOCKED | P01-A | rn-bridge | 上游未完成 |
| P01-C | Storage/Auth | BLOCKED | P01-RN merged | auth | 上游未完成 |
| P01-D | Network | BLOCKED | P01-A | network | 上游未完成 |
| P01-E | i18n/Router/Analytics context | BLOCKED | P01-A | foundation | 上游未完成 |
| P01-F | ChatDetailSurface 端到端 | BLOCKED | P01-C/D/E + RN pin | rn-host | 上游未完成 |
| P02-A | 产品真值审计 | BLOCKED | P01 DONE | orchestrator | 上游未完成 |
| P02-B | 启动与 Shell | BLOCKED | P02-A | app-shell | 上游未完成 |
| P02-C | Native Login | BLOCKED | P02-A/B | auth-ui | 上游未完成 |
| P02-D | Home 首个垂直切片 | BLOCKED | P02-B/C | home | 上游未完成 |
| P02-E | 质量与性能基线 | BLOCKED | P02-C/D | quality | 上游未完成 |
| P03-A | Home production parity | BLOCKED | P02 DONE | home | 上游未完成 |
| P03-B | Profile/Search/Settings | BLOCKED | P02 DONE + shared contracts frozen | discovery | 上游未完成 |
| P03-C | ChatList Grid/Map | BLOCKED | P02 DONE + shared contracts frozen | chatlist | 上游未完成 |
| P03-I | App navigation 集中集成/汇总 | BLOCKED | P03-A/B/C | orchestrator | 上游未完成 |
| P04-A | Screen/Media3 | BLOCKED | P03 DONE | media | 上游未完成 |
| P04-B | 12 个业务 Surface + Debug gate | BLOCKED | P03 DONE | rn-host | 上游未完成 |
| P04-C | Push/Widget/Voice/系统 SDK | BLOCKED | P03 DONE | system | 上游未完成 |
| P04-DL | OTA 本地 abstraction/embedded/fallback contract | BLOCKED | P04-B | ota | 上游未完成 |
| P04-DE | 独立 EAS preview 真机 N/N-1 | BLOCKED | P04-DL + explicit EAS authorization | ota/release | 需后续授权；不阻塞 P05，阻塞 P06 |
| P05-1 | 固定源与 Delta audit | BLOCKED | P04-A/B/C/DL | quality | 上游未完成 |
| P05-2 | 功能/错误/生命周期对账 | BLOCKED | P05-1 | quality | 上游未完成 |
| P05-3 | 自动化与 test ID | BLOCKED | P05-2 | quality | 上游未完成 |
| P05-4 | 视觉与 accessibility | BLOCKED | P05-2 | quality | 上游未完成 |
| P05-5 | 性能、稳定性与故障注入 | BLOCKED | P05-2 | performance | 上游未完成 |
| P05-6 | 安全/供应链/可观测性与汇总 | BLOCKED | P05-3/4/5 | quality | 上游未完成 |
| P06-A1 | Release transaction 工具 | BLOCKED | P05 DONE | release | 上游未完成 |
| P06-A2 | Artifact verification | BLOCKED | P06-A1 | release | 上游未完成 |
| P06-A3 | 覆盖升级 harness | BLOCKED | P06-A2 | release | 上游未完成 |
| P06-A4 | Runbooks/恢复准备与汇总 | BLOCKED | P06-A1/2/3 | release | 上游未完成 |
| P06-GP | Google Play 授权发布 | BLOCKED | P06-A1..4 + P04-DE + explicit authorization | release owner | 不允许推断授权 |
| P06-APK | Direct APK 授权发布 | BLOCKED | P06-A1..4 + P04-DE + explicit authorization | release owner | 不允许推断授权 |
| P06-RS | RuStore 授权发布 | BLOCKED | P06-A1..4 + P04-DE + explicit authorization | release owner | 不允许推断授权 |

## 发布状态模型

`Project release state` 与上述 work-unit 状态分离，只能使用：

```text
NOT_READY
READY_FOR_AUTHORIZED_RELEASE
RELEASING
PARTIALLY_RELEASED
RELEASED
PAUSED
FORWARD_RECOVERY
```

- P06-A1..A4 与 P04-DE 完成但尚未获得渠道授权：`READY_FOR_AUTHORIZED_RELEASE`；P06 总阶段仍不是 DONE。
- 三渠道逐行记录真实终态；只有三个渠道都完成批准范围且恢复演练通过，P06 才可 DONE / project 才可 `RELEASED`。
- 某渠道未发布不能复制另一个渠道的状态；暂停/恢复时写外部 ID、actor 与时间。

## 红黄绿风险

| 等级 | 风险 | 当前动作 |
|---|---|---|
| 红 | 目标仓为空；无可执行 Gradle/CI | P00 首先 bootstrap，不开始页面开发 |
| 红 | 旧 RN token 可能只存在 Expo SecureStore，Native 无法安全猜测密文实现 | P00/P01 验证 MMKV；必要时用一次性 RN AuthBootstrapSurface 迁移 |
| 红 | 三渠道签名/包名/支付/Firebase 不等价 | P00 建立 flavor，P06 每渠道独立覆盖升级 |
| 红 | Expo brownfield 支持为 alpha，SDK 54 与最新 isolated AAR 文档存在版本差 | 首轮 integrated；isolated AAR 仅作为独立 POC，不阻塞迁移 |
| 黄 | `App.tsx` 不再挂载后，push/analytics/widget/deep link side effect 会丢失 | P01 建立 root side-effect ownership 清单，P04 全部迁移/验证 |
| 黄 | 当前 RN CI 不跑 typecheck/lint/test/build，且 Vitest 有历史 exclude | P00 记录 debt ledger；Android CI 不继承弱门禁 |
| 黄 | iOS progress 曾发生 pin/功能状态漂移 | 每个 packet 固定 SHA；本文件为唯一状态真值 |
| 绿 | iOS 已验证 Native shell + RN Surface + OTA ABI 的可行性 | 复用契约与失败用例，不复制平台实现 |

## 基线债务账本

P00 执行时必须填入实际结果，不得以“迁移前就失败”为由隐藏：

| 仓库 | 命令 | 结果/退出码 | 是否阻塞 | Owner/计划 |
|---|---|---|---|---|
| tipsy-app | `npm run ts:check` | NOT RUN | 待判定 | P00 |
| tipsy-app | `npm run lint` | NOT RUN | 待判定 | P00 |
| tipsy-app | `npm test` | NOT RUN | 待判定 | P00 |
| tipsy-app | `npm run i18n:check:strict` | NOT RUN | 待判定 | P00 |
| tipsy-app | `npm run i18n:keys:strict` | NOT RUN | 待判定 | P00 |
| tipsy-Android | `./gradlew check` | 工程不存在 | 是 | P00 |

## 功能摘要

功能逐项归属与对等证据见 `rn-parity-contract-matrix.md`。在该矩阵完成 RN 源码审计前，不得将任何页面标为 DONE。

## Delta queue

波次中发现 RN 基线之后的产品变化，记录在此，不直接漂移 submodule：

| 发现日期 | RN commit/range | 影响功能/契约 | 处理波次 | 状态 |
|---|---|---|---|---|
| - | - | - | - | EMPTY |

## 最近完成回报

暂无。每次仅追加最近一次摘要，历史完整证据应放 PR/CI artifact，不在多份文档复制。
