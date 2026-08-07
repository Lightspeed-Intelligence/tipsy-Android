# Android 质量门禁

本文件定义最低门禁。P00 创建工程后必须用真实 task 名替换尖括号/“预期”项；不存在的命令不得写成已通过。

## 1. 门禁分层

| Gate | 触发 | 最低内容 | 阻塞对象 |
|---|---|---|---|
| G0 Baseline | P00/依赖 pin 变化 | SHA、RN debt、工具链、生成工程 diff | 所有开发 |
| G1 PR Fast | 每个 PR | format/static、unit/contract、lint、3 debug assemble | 合并 |
| G2 Feature | 页面/Surface | fixture、state、instrumentation、visual/a11y、analytics | 功能 DONE |
| G3 Nightly | 主干每日 | API24/API36、lifecycle、macrobenchmark、leak/manifest | 灰度候选 |
| G4 Release | 每个渠道候选 | 签名产物、覆盖升级、系统/支付/OTA、artifact archive | 发布 |

## 2. P00 基线记录

先记录 SHA 与 dirty state：

```bash
git status --short --branch
git rev-parse HEAD
git submodule status --recursive
git -C tipsy-app rev-parse HEAD
java -version
node --version
npm --version
```

RN 固定基线，在 `tipsy-app` 内执行并把退出码写入 progress debt ledger：

```bash
npm ci --no-audit --no-fund
npm run ts:check
npm run lint
npm test
npm run i18n:check:strict
npm run i18n:keys:strict
npx expo-doctor
```

已知审计日 `expo-doctor` 为 13/18 checks passed，存在依赖偏差/未验证新架构库；这不是擅自升级的授权。区分：

- 本任务新增回归：必须修复。
- 可稳定复现的历史失败：记录命令、错误、owner 和独立修复 packet；不得新增 ignore。
- 影响 bridge/bundle 的历史失败：在 RN PR 合入前必须得到明确处理或人工批准。

## 3. 预期 Gradle 快门禁

P00 结束时至少存在并通过：

```bash
./gradlew --version
./gradlew projects
./gradlew check
./gradlew lint
./gradlew test
./gradlew :app:assembleGooglePlayDebug
./gradlew :app:assembleDirectApkDebug
./gradlew :app:assembleRuStoreDebug
```

若引入 detekt/ktlint/Spotless，必须在 `check` 依赖图中，不允许 CI 漏跑。静态工具的精确版本固定在 version catalog，并提供自动格式化命令；CI 只跑 check，不在 CI 自动改文件。

## 4. 纯 Kotlin contract tests

P01 最低覆盖：

### Auth

- MMKV raw token、`user-storage` Zustand envelope、SecureStore bootstrap 三路径。
- migration 首次/重复/中断恢复，不清旧值。
- refresh single-flight：N 个并发 401 只调用一次 refresh。
- logout/account switch 使旧 refresh/request 结果失效。
- token-aware 401 不登出新 session。

### Network

- REQUIRED/OPPORTUNISTIC/NONE headers。
- `{code,msg,data}` 成功/缺 data/业务 code/HTTP 401/402。
- ID/time/cost/duration 的 string/number/null fixture。
- endpoint/API base 与 distribution headers。

### Storage/i18n/navigation

- cache key 包含 env/account/filter/schema。
- 28 个文件 inventory、27 个 import、26 个客户端支持码与服务端可选列表的关系；active catalog key parity、normalize/fallback/interpolation，dormant 文件不得被误启用。
- 所有 deep-link route 正常/缺参/恶意值/cold auth queue/dedupe。
- Native/RN destination mapping 与 capability version。

### Surface contract

- unknown/additive props。
- capability present/absent fallback。
- instance ID 防止旧 close/ready 影响新 Surface。
- N/N-1 contract fixture。

纯逻辑不得依赖 emulator，目标是秒级反馈。

## 5. Feature Gate 模板

每个 feature work unit 在 packet/PR 中填写：

| 类别 | 必测场景 |
|---|---|
| 数据 | 首屏、分页、刷新、缓存命中/过期、乱序响应、去重 |
| 状态 | loading、content、empty、error、offline、retry |
| Auth | 游客、登录、token 过期、logout、切账号、402 |
| Lifecycle | tab 切换、back、后台恢复、旋转、进程重建 |
| 导航 | Native↔Native、Native↔RN、deep link/push、重复点击 |
| UI | RN 对照 screenshot、dark/字体缩放/长文案/RTL（适用时） |
| A11y | TalkBack 顺序/label/role、48dp target、stable testTag |
| Analytics | event 次数、payload、session/source/recommend context |
| 性能 | 与旧 RN baseline 比较；无持续 heap/播放器/图片增长 |
| 回滚 | kill switch、RN fallback 或可 revert 的自有改动 |

## 6. Instrumentation 与设备矩阵

P00 固定可用镜像后，最低：

- API 24：最低 SDK，启动、登录态读取、Widget/通知兼容、关键 Native/RN route。
- API 36：target 行为、predictive back、edge-to-edge、通知/FGS/媒体权限。
- 至少一台低内存真机：Screen、Surface 50 次、后台回收。
- 至少一台 Google Play 服务真机和一条 RuStore/Direct 真实渠道链路。

测试不能只调用 deep link 命令并假设页面正确；必须断言 destination testTag 与参数。

## 7. Surface 专项 Gate

- Debug/embedded/OTA 三种 bundle source 可诊断。
- 首个与后续 Surface 都正确创建。
- 连续打开/关闭 50 次，React Runtime 数量始终 1，无 retained Activity/Fragment/View 趋势。
- Native back、RN micro-stack back、栈底 pop、predictive back 无穿透/双 pop。
- keyboard/modal/Portal/Toast/背景占位/首帧切换。
- rotation、background、process recreation 后 route 可恢复或安全降级。
- auth/user/language/surface reappeared 事件实时且不重复。
- RN exception 有 Sentry/source map，Native shell 可回到安全 destination。

## 8. 媒体与性能 Gate

P04 Screen 使用 Media3 后至少测：

- 三类媒体形态与无效 URL/error fallback。
- 可见项唯一播放；离屏暂停/释放；audio focus 与通话中断。
- bounded player/preload/image cache；快速 fling 不无限建资源。
- 列表增量更新不重置当前播放、滚动和曝光。
- 语言/filter/account 变化后的旧响应不覆盖新集合。
- startup、first content、frame time/jank、PSS/heap 与 RN baseline。

Media3 preload 方案参考：https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager/concepts

## 9. Manifest 与渠道快照

CI 对三个 merged manifest/variant metadata 建断言：

- application id。
- exported components 与 intent filters。
- INTERNET/debug cleartext。
- POST_NOTIFICATIONS/FGS microphone 等按功能需要。
- 不包含不必要的广泛存储/CAMERA/media-projection 权限。
- Play 不带 RuStore activity/repository/payment；RuStore 不带 Play billing 初始化；Direct 策略明确。
- Widget receivers、voice service、Firebase provider、scheme 唯一且不冲突。

`app.config.js` 的声明不是最终真值，merged manifest 才是。

## 10. Release Gate

每渠道必须分别留下：

- artifact SHA-256、build ID、source SHA、RN SHA、version、application id。
- signing certificate SHA-256（不泄露 key）。
- R8 mapping/native symbols/RN source map 上传与检索验证。
- `adb install -r` 从当前线上 RN 包覆盖升级的录像/日志/检查表。
- 登录/游客/历史安装、语言/账号/钱包/会话/widget 状态连续。
- push cold/warm、deep link、Billing、voice FGS、attribution、Sentry、OTA N/N-1。
- staged rollout 监控阈值和暂停/向前恢复演练。

任何一项 `NOT RUN` 都需要风险 owner 明确批准；Codex 不可自行将其视为通过。
