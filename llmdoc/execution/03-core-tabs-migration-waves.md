# P03：核心 Tab 分波迁移

## 任务元数据

| 字段 | 值 |
|---|---|
| Task ID | `ANDROID-P03` |
| Execution status source | `../reference/android-native-progress.md` 的 P03-*；本文件不复制状态 |
| 目标仓库 | `tipsy-Android`；仅契约缺口才开 additive RN PR |
| source_rn_commit | `BLOCKED_UNTIL_P02_DONE`；转 READY 前替换为完整 40 位 SHA |
| target_android_base_commit | `BLOCKED_UNTIL_P02_DONE`；转 READY 前替换为完整 40 位 SHA |
| Depends on | P02 DONE |
| Blocks | P04-P06 |
| 内部波次 | P03A Home parity；P03B Profile+Search+Settings；P03C ChatList Grid+Map |

## 开始前必读

- Blueprint、boundary contract、quality gates、parity matrix F02/F04-F09/F21。
- 固定 RN SHA 下 `src/app/home`、`profile`、`search`、`chatList`、`settings`，对应 hooks/APIs/stores/navigation/analytics/testID。
- iOS 参考：Home 增量更新/seed union、Profile/Search、ChatList Grid/Map/草稿；其 API/平台 SDK 不是 Android 真值。

## 唯一目标

把 Home、Profile、Search、Settings 列表/语言、ChatList 的首轮明确范围迁为 production-quality Native，使四个非 Create Tab 中 Home/ChatList/Profile 可用；复杂子路由继续进入 RN Surface。Screen 仍留 P04。

## 并行规则

P03A/B/C 在 foundation API 冻结后可由不同 agent 并行修改各自 feature module，但：

- `settings.gradle*`、version catalog、app navigation、Manifest、AppContainer、core models/contracts、bridge schema、CI、progress 同时只能一个 owner。
- feature agent 不直接编辑 progress；完成报告交 orchestrator 汇总。
- 需要 core 接口变化时先提交最小 interface/fixture/test，由 foundation owner 合并，feature 再继续。
- testTag、route 和 analytics event registry 是稳定公共 API，不能各 feature 自行改名。

## 允许修改

- 对应 feature modules、feature-specific fixtures/tests/screenshots。
- 已冻结 designsystem/core interface 的 additive 扩展。
- app navigation 的集中 integration 由单一 orchestrator 完成。

## 禁止修改

- Screen/Media3、完整 RN Surface fleet、push/widget/voice/payment/OTA/release。
- Create/Chat/Comments/EditProfile/Settings 子页 RN 实现。
- 共享 core 大重构、依赖升级、包名/签名/渠道。
- 为追求视觉一致把服务端缺失字段用假数据补齐。

## 共用前置：逐功能 parity packet

每个内部波次开始前，先在 PR 描述/工作文档填写：

```text
Feature/Matrix ID
RN source SHA + paths + symbols
actual registered routes
API/auth mode/fixtures/error codes
storage/cache/account/env scope
analytics/impression/click/session payload
permissions/platform SDK
testID/accessibility mapping
retained RN child routes
Android-specific behavior
kill switch/fallback
```

缺任意一项先审计，不写 UI。

## P03-A：Home production parity

### Scope

在 P02 首个切片上补齐固定 RN SHA 的 Home 生产范围：tag/filter、主 feed、分页/刷新/缓存、banner/activity、splash/Home PV、prefetch、推荐 session/曝光/点击、Android World 系列。

### 实施要点

1. 以 RN 实际 endpoint/query 为真；每类 card/banner/activity 用脱敏 fixture。
2. cold cache 立即显示 + 静默 refresh；cache schema/account/env/language/filter scope。
3. seed union/增量 item update，避免 banner/点赞更新全量替换列表；乱序响应带 request key/version guard。
4. paging 统一去重/stable keys/end-of-list；filter/language 切换取消或忽略旧页。
5. prefetch 有界，尊重网络/内存/数据节省；不可预创建无界播放器。
6. Home 自己创建并拥有推荐 `session_id`，点击进入 RN detail 时透传；返回不新建无意义 session。
7. World/Content Rating/AI 能力按 `distribution` registry；Google Play 禁止启用 side-load 专属策略。
8. 每个卡片 CTA 路由经 AppRouter；未迁子页进入明确 Surface，不做 silent no-op。

### 特有验收

- 冷启动有缓存/无缓存、弱网/离线、banner 更新、filter 快切、语言切换、logout/account switch。
- 滚动/曝光不重复，增量更新保留位置。
- Android World 展示与渠道策略经 snapshot/contract test。
- RN/Native analytics 录制结果逐事件对拍。

## P03-B：Profile、Search、Settings 入口

### Profile self/public

1. 审计自己/他人 profile 的 route 参数、counts、created/favorite/social lists、daily tasks。
2. 同一用户 route 防重复 push；自己用户从任何入口切 Profile Tab。
3. follow/favorite 使用乐观更新时保留 server rollback，且 auth generation 防旧账号写回。
4. recommendation context 透传到用户主页和后续聊天；缺 capability 安全降级。
5. EditProfile、RoleCard、Coins、Notifications 等仍走 Surface。

### Search

1. 审计 character/creator filters、debounce、paging、history/empty/retry。
2. query generation 防旧搜索覆盖新关键词。
3. Content Rating 只在产品允许的 Direct/RuStore 渠道展示；Play snapshot 保证隐藏。
4. 结果卡复用稳定 domain model/design token，但不耦合 Home ViewModel。
5. 点击用户/角色 route 与 recommendation/source context 正确。

### Settings list/language

1. 只迁设置列表和语言 picker；12 个实际注册子路由按 matrix 进入 `SettingsSurface`/对应 Surface。
2. locale catalog 保留“28 文件 / 27 import / 26 supported codes”事实；Settings 只展示服务端 `/supported_languages` 与批准客户端支持码的交集。切换即时更新 Compose、缓存、network header（若有）、所有活跃 Surface。
3. About/version/channel 从 BuildConfig/metadata 读取，不手写。
4. logout/delete 等危险操作进入既有 RN flow或 Native Auth 契约；不在列表里复制业务逻辑。

### 特有验收

- Profile 各入口/同用户/换号、乐观失败恢复。
- Search 快速输入/分页/清空/离线/渠道 rating。
- 所有设置行都到正确 destination；无静默点击。
- active locale key parity、服务端未知 code fallback、长文案、RTL、进程重建/Surface 同步；dormant 文件不出现在可选列表。

## P03-C：ChatList Grid / Map

### Scope

ChatList 只迁列表/地图入口与管理能力；ChatDetail、Comments、Notification 等下钻保留 RN Surface。

### 实施要点

1. 审计 `/user/chatted/*` 等实际 endpoints、Grid/Map 数据差异、draft/unread/pin/delete 语义。
2. grid paging/cache/stable key；draft 预览与排序不丢失。
3. pin/delete swipe 乐观更新：失败精确回滚单 item；并发/重复操作幂等。
4. unread 与 push/event 更新增量合并，不全量闪烁。
5. Map SDK、API key、区域/隐私/最低 API 重新做 Android 选型；不可按 iOS MapKit 实现。缺凭据先用 fake provider + contract，停止真实集成。
6. 点击会话用 typed route 打 ChatDetailSurface，附 character/session/source；back 恢复 grid/map mode 与位置。
7. account switch/logout 清内存视图并切 scoped cache；旧 paging/delete 结果丢弃。

### 特有验收

- grid/map 切换、分页、缓存、空/错/离线。
- unread、draft、pin/delete 的成功/失败/并发/进程恢复。
- push/event 到达时 UI 增量更新且只一次。
- ChatDetailSurface 往返 30 次；ChatList 状态与 runtime 稳定。

## P03-I：集中集成与汇总

1. 由 orchestrator 串行修改 app root navigation/AppContainer/version catalog 等 hotspot，接入 P03-A/B/C。
2. 跑完整 Tab/route/flavor suite，解决集成而非隐藏 feature tests。
3. 汇总三个 agent 完成回报，更新 parity matrix 与 progress；feature agent 不并发编辑 progress。
4. P03-A/B/C 任一未满足独立 DoD 时，P03-I 不得把总阶段标 DONE。

## 共享 UI 与 accessibility

- Designs system 只抽取至少两个 feature 真正复用的 token/component。
- dynamic test tag 使用稳定服务端 ID 的不可逆摘要或安全 ID，不包含昵称/query/token。
- TalkBack traversal、role/state、48dp target；map 有可替代列表路径。
- fontScale 2.0、RTL、长德语/阿拉伯语/多行按钮无截断关键操作。
- screenshot/golden 覆盖每 feature 的 loading/content/empty/error 与关键 flavor 差异。

## 自动验收

每个内部 PR：

```bash
./gradlew check
./gradlew lint
./gradlew test
./gradlew :feature:<feature>:test
# feature screenshot/contract task
# feature instrumentation suite on API36
```

P03 汇总：

```bash
./gradlew :app:assembleGooglePlayDebug
./gradlew :app:assembleDirectApkDebug
./gradlew :app:assembleRuStoreDebug
# full tabs + Native/RN route instrumentation
# API24 smoke and API36 full suite
```

精确 task 名在 P00/P02 写回后使用。依赖/pin/bridge 变化时附 RN gates；否则至少运行 targeted route/contract tests。

## 手工 QA 汇总

1. 游客/登录/换号/过期 token，四 feature 不串数据。
2. 低网/离线/恢复/服务端 scalar 漂移 fixture。
3. 三 flavor Home/Search/Settings/ChatList，验证渠道功能差异。
4. tab switch/reselect/back/deep link/process recreation 保持状态。
5. Native card → RN Surface → Native，语言/auth/recommend context/scroll 正确。
6. TalkBack/字体 2.0/RTL/低内存设备。

## 可观测性

每 feature dashboard/event fixture至少含：PV、load outcome/latency、empty/error/retry、impression/click、route outcome；禁止重复 session/PV。错误按 network/business/decode/auth/cancelled/stale 分类，不把取消当失败。

## 回滚

- 每个 Tab/feature 有独立 route kill switch 或 internal fallback；fallback 目标必须已在固定 RN entry 验证。
- 不删除 RN 业务源码/route/旧 storage。
- 单 feature revert 不应要求回滚其他 feature/core schema。
- Map/SDK 可关闭回 Grid，不阻塞 ChatList 核心。

## 必须停止的条件

- RN 行为、产品 owner 与 iOS 实现冲突且影响渠道/内容政策。
- Map/API key、Firebase、远端 config 需要新外部权限。
- API 数据不足以无损实现编辑/乐观操作。
- 任一 feature 需要破坏 P01 bridge/storage schema。
- 跨账号数据、analytics 双报或稳定性回归无法收敛。

## Definition of Done

- P03A/B/C 各自 parity matrix 行完成全部证据并通过 feature gate。
- Home、ChatList、Profile 为 Native production-ready；Search/Settings list/language 可从正确入口使用。
- 所有保留 RN 子路由明确、可达、返回正确。
- 三 flavor/API24/API36、a11y/visual/analytics/performance 无未批准红项。
- progress P03 DONE、P04 READY，记录每个子波次 SHA/证据。

## 完成回报格式

每个 P03A/B/C agent 使用 `AGENTS.md` 的 7 项格式且不编辑 progress；orchestrator 汇总各 feature SHA、matrix 行、测试/截图/埋点/性能证据、共享 hotspot 变更及 remaining RN routes。
