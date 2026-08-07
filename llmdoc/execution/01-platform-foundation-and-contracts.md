# P01：平台基础与跨端契约

## 任务元数据

| 字段 | 值 |
|---|---|
| Task ID | `ANDROID-P01` |
| Execution status source | `../reference/android-native-progress.md` 的 P01-*；本文件不复制状态 |
| 目标仓库 | `tipsy-app` 独立兼容 PR + `tipsy-Android` consumer PR |
| source_rn_commit | `cbd521f02972933c21f90c01787ea5c11200875e`；RN PR 合入后由 orchestrator 写回 consumer SHA |
| target_android_base_commit | `BLOCKED_UNTIL_P00_DONE`；转 READY 前替换为完整 40 位 SHA |
| Depends on | P00 DONE |
| Blocks | P02-P06 |
| Hotspot owner | Auth、Network、Storage、Router、Bridge schema、Application、根 side effects |

## 开始前必读

- `AGENTS.md`
- `../architecture/android-rn-boundary-contract.md`
- `../architecture/android-native-migration-blueprint.md`
- `../reference/android-quality-gates.md`
- `../reference/rn-parity-contract-matrix.md` 的 X01-X14
- RN：`src/App.tsx`、`index.surfaces.js`、`modules/tipsy-auth`、`src/store/auth.ts`、`src/store/mmkv.ts`、`src/lib/auth`、`src/utils/axios.ts`、navigation linking、root hooks

## 唯一目标

建立覆盖安装可用的 Auth/Storage/Network/i18n/Router/Analytics/Surface contract 基础，让 Native 成为 shell 模式应用级能力的单一 owner，并在真实 token 下稳定打开 `ChatDetailSurface`。不迁业务 Tab。

## 仓库与提交边界

本 packet 分成两个**串行** PR，不能在 Android superproject 提交中夹带 submodule dirty state：

1. `P01-RN`：在 `tipsy-app` 独立分支增加向后兼容 Android shell module、AuthBootstrapSurface/contract tests。完成后停止，等待用户合入。
2. `P01-ANDROID`：用户给出已合入 RN SHA 后，Android 仓 bump pin，再实现 Native provider/foundation。

Codex 不自行 push/merge。若 RN PR 未合入，不开始 consumer pin；可继续编写不依赖最终 SHA 的纯 Kotlin代码，但不得宣称端到端完成。

## 允许修改

- P01-RN：`tipsy-app/modules/tipsy-auth/**`、`index.surfaces.js`、最小 shell bootstrap/targeted tests/docs；需要其他路径必须先说明。
- P01-ANDROID：`core/auth|network|storage|i18n|navigation|analytics|testing`、`rn-host`、`app` 组装、fixtures、CI/docs/progress。

## 禁止修改

- RN 独立 App 的 `isShellHost=false` 行为、完整 App auth owner、业务页面。
- 现有 bridge 方法的删除/改名/改必填语义。
- 生产 API URL、package id、OTA runtime/channel、签名、依赖大版本。
- 把 token/PII 写日志或 fixture。

## 明确交付物

### P01-RN

- `tipsy-auth` Android Expo module/provider，非 shell 默认无 provider/false。
- Android provider registry，与 iOS 行为等价但 Kotlin 内部按 5 类契约委托。
- 可选 `AuthBootstrapSurface`：仅由 Native 在 MMKV 缺 token 时一次性调用。
- capability/contract version、instance ID 支持；旧 native/JS 兼容。
- targeted Vitest/Android module tests 与文档。

### P01-ANDROID

- `AppContainer` 和 Auth/Network/Storage/i18n/Navigation/Analytics implementations。
- versioned storage registry + 脱敏 fixtures。
- Native token migration/single-flight/epoch。
- 三 auth mode API client 与 tolerant serializers。
- locale 生成/校验与 runtime state：审计 28 文件，保持 27 import/26 supported code 语义，并与服务端可选列表取交集。
- Typed Router/deep-link parser/auth queue/dedupe。
- Sentry/analytics context skeleton；无生产 SDK 双初始化。
- `ChatDetailSurface` 真实登录态 POC 与 N/N-1 contract fixtures。

## 非目标

- 不迁 Home/Tab UI。
- 不实现完整 push/widget/attribution/payment。
- 不发布 OTA；只验证 embedded/local bundle contract。
- 不清理旧 MMKV/SecureStore 字段。
- 不拆掉兼容的 `TipsyAuth` JS 名称；内部解耦先行。

## 实施步骤

### P01-A：冻结 registries

在写 implementation 前从固定 RN SHA 产出：

1. Route registry：实际 `<Stack.Screen>`、deep link、push/widget sources、Native/RN destination。
2. API registry：endpoint、method、auth mode、headers、envelope、401/402/business codes、SSE/upload。
3. Storage registry：key/schema/account/env/encryption/TTL/logout/rollback owner。
4. Event/analytics registry：root side effects、event、owner、session/source context。
5. Surface registry：component、initial props、providers、routes、close/first-frame/OTA contract。

更新 parity matrix 的 X01-X14；任何 `UNKNOWN` owner 阻塞 P02。

### P01-RN：RN Android shell provider（独立 PR）

1. 在 `expo-module.config.json` 增加 Android module，保持 Apple 配置。
2. Android module 默认 provider=null；`isShellHost()` false，不改变当前独立 Android RN App。
3. 由 Native Application 在任何 Surface JS 运行前注册 provider；同步方法不得阻塞网络/磁盘。
4. 保持现有 `TipsyAuth` API；新增方法 optional/capability gated。
5. 加 `surfaceContractVersion`/capabilities 查询或 initial props 解析 helper。
6. `AuthBootstrapSurface` 仅复用现有 `readPersistedAuthToken`/SecureStore 逻辑；不渲染业务 UI、不 log token；Native ack 后关闭。
7. 给 standalone、shell provider、provider late/missing、old capability 添加测试。
8. 运行全部 RN gates；历史失败与新增失败分开。完成后交付 commit/patch，等待合入 SHA。

### P01-C：Storage/Auth

1. 用实际覆盖安装设备确认 MMKV root/id/key byte 形态；保存脱敏 schema fixture，不复制真实 token。
2. 实现 dual-read migration：MMKV → Native versioned store；缺失才调用 bootstrap Surface；幂等且失败不清旧值。
3. 定义 `SessionSnapshot(tokenMetadata,userId,generation)`，token 内容只驻留受控 store/memory。
4. 实现 single-flight refresh、5 分钟阈值的基线对等、token-aware 401、logout/account switch generation。
5. `user-storage` 按 Zustand `{state,version}` 解析；新缓存 key 具 env/account/filter/schema scope。
6. 对 RN Surface 发布 auth/user events；主线程与实例生命周期明确。

### P01-D：Network

1. OkHttp interceptor 只负责 header/transport；Authenticator/refresh coordinator 防递归。
2. Retrofit/kotlinx.serialization DTO 与 domain 分层；集中 tolerant scalar。
3. 实现 REQUIRED/OPPORTUNISTIC/NONE。
4. 对 code 6/9/16、HTTP401/402 建 typed error policy；不会把业务分支吞为通用网络失败。
5. 保存 Home/Login/ChatList 等首批端点真实脱敏 fixtures；禁止只由 TS interface 生成。
6. API base/environment 是进程单一配置，并通过 bridge 给 Surface。

### P01-E：i18n、Router、Analytics context

1. 在 RN 独立 PR或现有脚本扩展 locale catalog：审计 28 个文件，显式输出实际 imported resources 与 26 个 supported codes；Android 构建校验源 SHA/active key parity，禁止自动启用 `ar`/`zh` 等 dormant/非 supported code。
2. Settings runtime 可选集合使用服务端 `/supported_languages` 与批准的客户端支持码交集；未知 code fallback 并记录非 PII 诊断。
3. Compose locale StateFlow；切换后 Native 重组并发 `onLanguageChanged`。
4. Router 统一解析 Intent/Push/Widget/RN 请求；auth-ready queue 每个 route 只消费一次。
5. 审计 `tipsy`/`tipsy.chat` 与泛用 scheme；只注册明确拥有的 scheme/host。
6. 建 analytics context：installation/session/user/distribution/source/recommend context；先接 fake/recording sink，P04 再接所有生产 SDK。
7. Sentry Native 初始化并建立 release/environment/user contract；确保 token 不进 breadcrumb。

### P01-F：ChatDetailSurface 端到端

1. bump 到已合入 RN SHA，submodule clean。
2. Native 取得有效 token，打开 `ChatDetailSurface`；Surface 通过 provider 获取 token/API/locale。
3. 验证首帧、back/micro-stack、pop 幂等、language/auth/user/reappear、401/402。
4. embedded bundle 无 Metro 打开；debug Metro 8083 打开。
5. 连续 50 次开关、旋转、后台、进程恢复；runtime=1，无 retained Activity 趋势。

## 必须新增的测试

完整列表见 quality gates P01；此外：

- Android provider standalone/shell registry contract。
- AuthBootstrap success/missing/corrupt/ack interrupted/idempotent。
- 旧 token 401 到达新账号时不 logout。
- 两个并发 Surface 的事件按 instance 路由。
- route cold/warm/background/auth queue/dedupe。
- RN `TipsyAuth` optional methods 在旧 Native 缺 capability 时安全降级。
- API base/locale 两端一致。

## 自动验收

### P01-RN

```bash
npm ci --no-audit --no-fund
npm run ts:check
npm run lint
npm test
npm run i18n:check:strict
npm run i18n:keys:strict
# 运行新增 Android module/bridge targeted tests，命令写回本文件
```

### P01-ANDROID

```bash
test -z "$(git -C tipsy-app status --short)"
./gradlew check
./gradlew lint
./gradlew test
./gradlew :app:assembleGooglePlayDebug
./gradlew :app:assembleDirectApkDebug
./gradlew :app:assembleRuStoreDebug
# P00 固定的 instrumentation command
```

必须有覆盖安装 token migration 真机证据；没有匹配签名只能标 `NOT RUN`，P01 不可称“升级兼容完成”，但可以将需凭据的最终矩阵留到 P06。至少要用可控旧测试包证明算法。

## 手工 QA

1. 旧 RN 已登录 → 覆盖安装 → Native 仍登录；MMKV 路径和 SecureStore fallback 各一台/一组 fixture。
2. refresh 进行中 logout/换号 → 旧 token 永不恢复，旧列表不回写。
3. ChatDetailSurface 中 token 过期 → 只 refresh 一次并恢复；401/402 路由不循环。
4. Surface 可见时换语言/用户 → 微根刷新；销毁实例不再收事件。
5. cold/warm deep link logged-out → 登录后只导航一次；坏 URL 不崩溃。
6. 50 次 Surface、back、旋转、后台恢复 → 单 runtime、无双 pop/黑屏趋势。

## 可观测性

- auth migration 只记录枚举结果与耗时，不记录 key value/token/user PII。
- refresh 记录 single-flight count、outcome、generation mismatch count。
- route 记录 route ID/source/dedupe outcome，不记录敏感 query。
- Surface 记录 instance/component/bundle source/first-frame/close reason/runtime count。
- Sentry 测试事件能以 build ID 定位 Native；RN source map 在 P04 完成。

## 回滚

- RN 变更必须 additive；revert RN PR 后 standalone 行为不变。
- Android migration 不删除旧存储；回到旧 RN 包仍可读原数据。
- Foundation 通过接口/fake 隔离；出现问题可关闭 Native route，Surface/旧 RN release 分支保留。
- 不回滚用户数据 schema；新写入格式需保证旧 RN 可忽略或读取。

## 必须停止的条件

- RN provider 需要破坏现有 iOS API或 standalone Android 行为。
- 无法确定真实 storage schema/key/encryption，或唯一方案是清数据。
- 生产 API/refresh/401 语义与 registries 冲突且需产品决策。
- 需要 merge/push RN PR、真实凭据、生产 EAS 配置。
- 50 次 Surface 出现持续 runtime/Activity 泄漏，或 back 无法可靠收敛。

## Definition of Done

- 两仓变更边界干净，Android pin 指向已合入 RN commit。
- 五类 contract 与 registries/fixtures/tests 完整。
- Native 是 shell auth/route/locale/session owner，standalone RN 行为不变。
- token migration、single-flight/epoch、API modes、i18n/deep-link tests 通过。
- ChatDetailSurface 在 debug + embedded 打开，50 次/lifecycle gate 通过。
- progress P01 DONE、P02 READY；所有 UNKNOWN/NOT RUN 明确列出。

## 完成回报格式

严格使用 `AGENTS.md` 的 7 项格式；另列 P01-RN 合入 SHA、Android consumer pin、storage migration 样本、50 次 Surface runtime/leak 数据。未获得 RN merge 或真机签名时，明确写 `BLOCKED/NOT RUN`，不得标 DONE。
