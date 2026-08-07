# P04：高风险媒体、RN Surface 舰队与 Android 系统能力

## 任务元数据

| 字段 | 值 |
|---|---|
| Task ID | `ANDROID-P04` |
| Execution status source | `../reference/android-native-progress.md` 的 P04-*；本文件不复制状态 |
| 目标仓库 | Android 主仓 + 必要的 additive RN Surface PR |
| source_rn_commit | `BLOCKED_UNTIL_P03_DONE`；转 READY 前先 delta audit 并写完整 40 位 SHA |
| target_android_base_commit | `BLOCKED_UNTIL_P03_DONE`；转 READY 前替换为完整 40 位 SHA |
| Depends on | P03 DONE |
| Blocks | P05；P04-DE 另外阻塞 P06 的授权发布 |
| 内部波次 | P04-A Screen/Media3；P04-B 12 个业务 Surface + Debug gate；P04-C 系统能力；P04-DL 本地 OTA contract；P04-DE 授权 EAS preview |

## 唯一目标

完成最容易导致 OOM/ANR/黑屏/双 owner 的高风险域：Native Screen 媒体流、12 个保留 RN 业务 Surface 的可靠宿主、仅 debug 的 DebugSurface gate、Android push/deep link/widget/voice/analytics/attribution 系统能力，以及本地 OTA contract。获批的独立 EAS preview 真机 POC 单独记为 P04-DE：不阻塞 P05 质量加固，但阻塞 P06 外部发布。

## 并行规则

- P04-A/B/C 可在接口冻结后分 agent，但 Media3 pool、SurfaceRuntime、Application/Manifest、root Router、analytics schema、OTA/release 配置分别只能一名 owner。
- P04-DL 只能在 P04-B embedded bundle gate 全绿后开始；P04-DE 还需要独立 EAS 授权。
- RN PR 先合入、Android 再 bump pin；feature agent 不直接编辑 submodule/progress。
- system/release/OTA 相关工作不与另一个 agent 同时修改 version/channel/signing。

## 禁止事项

- 不把 Chat/Create/支付/活动等复杂业务改写 Native。
- 不为 Surface 创建多 Runtime。
- 不复用完整 RN App 的 Android production/preview OTA channel。
- 不请求广泛存储/CAMERA/media-projection 权限来规避正确 API。
- 不发布 OTA、上传商店、读取生产 keystore。
- 不以 `largeHeap=true` 作为泄漏/OOM 的唯一修复。

## P04-A：Screen / Media3

### 源码审计

审计 `src/app/screen`、screen API、video components、recommendation tracking/outbox、like/comment/share/CTA、三种媒体形态、前后台/网络策略。保存实际视频/图片/错误脱敏 fixture 和 RN 基线性能。

### 实现

1. Media3 ExoPlayer + `DefaultPreloadManager`（或同版本官方稳定 API）；player/preload 数量与缓存字节明确上限。
2. 可见项唯一 active playback；预加载相邻项，快速 fling 取消过时 prepare。
3. Recycler/Compose lazy item 使用 stable key，player 不由每个 Composable 任意创建。
4. audio focus、耳机拔出、来电/voice session、后台、锁屏、横竖屏遵循产品契约。
5. 三媒体形态、封面/首帧/error/retry；无效 URL/decoder error 不崩全列表。
6. like/comment/share/CTA 使用 typed route；Comments/Chat 进入对应 Surface。
7. recommendation impression/outbox 的 session/account/env/position/context 对等；offline 持久化与重放幂等。
8. filter/language/account/refresh 增量切换；旧响应/player callback 不覆盖新 feed。

### Gate

- RN vs Native startup/first frame/frame time/jank/PSS/native heap/network/cache hit。
- 低内存真机快速滑动、前后台 20 次、Screen↔Surface 30 次、长时播放。
- 播放器/Surface Runtime 数量 bounded；无 ANR、audio 重叠、黑帧持续趋势。
- like/comment/share/CTA/埋点/推荐 outbox 与 RN fixture 对拍。

## P04-B：RN Surface 舰队

boundary contract 共注册 13 个 component，其中 `DebugSurface` 永远只用于 debug/CI 回归，不进入 production route、production smoke 计数或商店功能清单。逐一启用其余 12 个业务 Surface，建议顺序：

1. 先保持 `DebugSurface` debug gate 绿色，再启用业务 `ChatDetailSurface`。
2. `CreateSurface`（五 Tab 伪 tab）。
3. `CommentsSurface`、`SettingsSurface`、`EditProfileSurface`。
4. `NotificationSurface`、`GemsSubscriptionSurface`、`UserCoinsSurface`。
5. `OnboardingSurface`、`RoleCardSurface`、`WidgetSurface`、`DeleteAccountSurface`。

每个 Surface 独立填写矩阵：

- actual registered component 与 initial props schema/fixture。
- 微根 providers、auth/user/locale/config hydrate。
- Native/RN route 出口和 recommendation/source context。
- first-frame、background-ready、reappeared、close/back/instance id。
- keyboard/modal/Portal/Toast/WebView/rotation/process recreation。
- debug + embedded + 后续 OTA N/N-1。
- 连续 50 次、两个 Surface 交替、runtime=1、无 retained Activity/Fragment/View。

未完成 checklist 的 Surface 不接生产入口；路由给出明确错误/安全 fallback，不做 silent no-op。

## P04-C：系统能力单一 owner

### Push

从 `useNotifications` 审计全部 notification type：anniversary、daily gem/check-in、video success/fail、system/user/batch/chat 等。

- Native 管 permission/channel/token rotate/backend register/badge/viewed/auth-ready route。
- Android 13+ denied 有产品 fallback；三 flavor Firebase app/token 不串。
- cold/warm/background、malformed/duplicate、logged-out queue 各测。
- 若后端只接受 Expo push token，先审计 SDK54 expo-notifications native API；不得把 FCM token直接冒充 Expo token。需要 backend 变更时停止。

### Widget

复用/迁移 `modules/widget/android` 的 Glance、2x2/4x4、DataStore/WorkManager/midnight receiver 行为；数据 owner 从 RN `useWidget` 转 Native repository，兼容：

- `android_widget_payload`
- `widget_character_id`
- `tipsy://chat/detail?...source=widget`

测 API24/36、升级旧 widget、进程未启动、午夜/时区变化、账号退出、点击 route。不要依赖定时 JS Root。

### Voice call foreground service

迁移/复用 `modules/voice-call-system-session/android` 的 FGS、notification、hangup/mute event。审计 `FOREGROUND_SERVICE_MICROPHONE`、POST_NOTIFICATIONS、WAKE_LOCK、audio focus；RN Voice UI仍是 owner，Native service 只承载系统 session。测 permission denied、进程死、来电、蓝牙/耳机、hangup 幂等。

### Analytics/Attribution/Remote config/Sentry

- 把 `App.tsx` root side-effect inventory 全部从 UNKNOWN 变为 Native/Surface owner。
- Qt/AB/PostHog/AppsFlyer/Meta/TikTok 等按 flavor/consent 初始化一次；未获得同意不越权。
- TikTok 等只在原 RN 渠道规则启用。
- Sentry Native symbols/R8 mapping 与 RN source map 通过同 build ID 可查询。
- 营销 deep link context 进入统一 Router；无双 session/install/PV。

### 权限/Manifest

- 采用 Photo Picker、SAF、MediaStore scoped storage。
- 审计最终 merged manifest；移除未使用的广泛 READ/WRITE/CAMERA/media-projection。
- 所有 exported component/intent filter 最小化，泛用第三方 scheme 不抢占。

## P04-DL：本地 OTA abstraction 与 fallback contract

前置：P04-B embedded 全绿。不需要 EAS/生产权限，属于 P04 核心 DoD：

1. 实现 updater abstraction、bundle metadata parser、runtime/contract/capability validator 与 embedded fallback。
2. build/export fixture 只接受 `index.surfaces.js`，metadata含 RN/Android SHA/contract/entry/distribution/environment。
3. 使用本地 fixture 测同代、N/N-1、错误 runtime/entry、损坏/缺失、离线 fallback；不得调用外部 publish。
4. 提案三 flavor preview/prod channel 名和 `android-bridge-1` runtime，明确不会触及完整 RN App channel；名称仍待批准。

## P04-DE：独立 EAS preview 真机 POC（需人工授权）

前置：P04-DL 完成，用户明确批准创建/使用独立 EAS project/channel/runtime 配置。

1. 只在批准的 preview sandbox 发布 Surface entry；不发布 production。
2. 真机验证 embedded、同代 OTA、N-1 Native+新 JS、N Native+旧 JS、错误 runtime、损坏下载、离线。
3. 12 个业务 Surface 都做 smoke；DebugSurface 仅额外 debug 诊断，不能替代业务矩阵。
4. production publish 脚本默认 dry-run；本 work unit 禁止真实 production publish。
5. 未获授权时 P04-DE 保持 BLOCKED；P05 可继续，P06-GP/APK/RS 外部发布不可开始。

## 必须新增的测试

- Media player/preload pool bounds、visibility/audio/lifecycle、stale callback。
- Screen state/cache/outbox/analytics fixtures。
- 12 个业务 Surface + 1 个 debug-only Surface 的 initial props/route/provider/capability fixtures，并断言 DebugSurface 不进入 release route。
- stale instance ready/close、back/predictive back、process recreation。
- push payload registry/cold-warm-auth-dedupe。
- widget old key migration/receiver/deep link。
- voice FGS state machine/permission/hangup/mute。
- flavor SDK initialization/manifest snapshots。
- OTA runtime/entry/channel/fallback N/N-1 contract。

## 自动验收

每个子波次先 feature/module tests；P04 汇总至少：

```bash
./gradlew check
./gradlew lint
./gradlew test
./gradlew :app:assembleGooglePlayDebug
./gradlew :app:assembleDirectApkDebug
./gradlew :app:assembleRuStoreDebug
# API24/API36 instrumentation suites
# Surface fleet/Media3/Push/Widget/Voice tests
# Macrobenchmark and manifest snapshots
```

任何 RN bridge/Surface/provider/bundle 变更运行 RN 全 gates + targeted micro-root tests。P04-DL 本地 contract 必须执行；P04-DE 只运行经批准的 preview sandbox，无授权不执行 publish。

## 手工/真机 QA

- 低内存设备 Screen 长滑/快滑/前后台/通话中断/网络切换。
- 12 个业务 Surface 的生产入口，以及 DebugSurface 的 debug-only gate；分别验证 back、键盘/modal、语言/账号切换、进程恢复、50 次交替。
- push 每一类 payload 的 cold/warm/background/logged-out/duplicate。
- 已安装旧 RN widget → 覆盖 Native；点击/午夜/换号。
- Voice FGS 在 API24/API36 的 permission/notification/hangup/进程死亡。
- 三 flavor SDK/支付入口/attribution/deep-link/permission；不能用一个渠道代替另两个。
- embedded/local fixture fallback 必测；preview OTA N/N-1/fallback/offline 只在 P04-DE 获批环境。

## 回滚

- Screen route kill switch 可回安全旧路径；Media pool/缓存 schema 不删除旧数据。
- 每个 Surface 独立开关；embedded bundle 永远保留。
- Push/analytics owner 切换必须防双 owner；回滚时恢复旧 owner前先禁新 owner。
- Widget/voice 新 schema 兼容旧 key/event。
- OTA 可 republish last-known-good preview 或 fallback embedded；Native 问题仍需向前版本。

## 必须停止的条件

- Media3 需要无界资源/largeHeap 才能避免 OOM，或性能明显劣于 RN 且无解释。
- Surface 出现多 runtime、稳定泄漏、back/进程恢复无法收敛。
- Expo push token/native API、支付、attribution 语义无法从源码/官方 SDK确认。
- 需要 EAS/生产凭据、channel/runtime 创建、生产 OTA。
- 权限/隐私/渠道政策需要产品或法务选择。

## Definition of Done

- Screen parity/性能/稳定性 gate 全绿。
- 12 个业务 Surface 生产入口矩阵填满，DebugSurface 仅 debug 可达；单 runtime、embedded 可离线。
- Root side effects 无 UNKNOWN；push/widget/voice/analytics/attribution/Sentry owner 与三 flavor 行为清楚。
- merged manifest/权限最小化，无渠道串配置。
- P04-A/B/C/DL 完成后 progress 可将核心 P04 标 DONE、P05 标 READY；P04-DE 若未授权仍保持独立 BLOCKED，并明确阻塞 P06-GP/APK/RS 而非 P05。

## 完成回报格式

按 `AGENTS.md` 回报，并分别附 P04-A/B/C/DL/DE 证据：Media3 资源/性能、12 个业务 Surface + Debug gate 矩阵与 runtime/leak、系统 owner/manifest、本地 OTA contract、获批 preview N/N-1。未经批准的 P04-DE/SDK/凭据项必须写 `BLOCKED/NOT RUN`。
