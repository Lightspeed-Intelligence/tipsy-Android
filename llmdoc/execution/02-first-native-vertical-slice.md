# P02：首个 Native 垂直切片（Login → Shell → Home）

## 任务元数据

| 字段 | 值 |
|---|---|
| Task ID | `ANDROID-P02` |
| Execution status source | `../reference/android-native-progress.md` 的 P02-*；本文件不复制状态 |
| 目标仓库 | `tipsy-Android`；只有发现 RN 契约缺口才另开 additive RN PR |
| source_rn_commit | `BLOCKED_UNTIL_P01_DONE`；转 READY 前替换为完整 40 位 SHA |
| target_android_base_commit | `BLOCKED_UNTIL_P01_DONE`；转 READY 前替换为完整 40 位 SHA |
| Depends on | P01 DONE |
| Blocks | P03-P06 |
| Hotspot owner | Main navigation、login gate、design tokens、Home contract |

## 开始前必读

- Blueprint、quality gates、parity matrix F02/X02/X04。
- RN：`TabNavigator.tsx`、login/auth 页面与 API、`src/app/home`、`src/hooks/home`、相关 character/activity APIs、tracking。
- iOS 仅作为交互/失败参考：Home API/cache/seed union/增量 banner/语言 settle，不复制 Swift 结构。

## 唯一目标

交付第一条真实可用的 Native 产品路径：启动恢复/登录 → 五 Tab shell → Native Home 首屏/分页 → 打开一个 RN ChatDetailSurface → 正确返回。以此证明平台基础能够承载业务，而不是继续堆抽象。

## 允许修改

- `app` 根导航/Tab/启动。
- `feature:login`、`feature:home`、必要 `core:designsystem` 与 fixtures/tests。
- 已冻结 core 接口的实现修复；改接口需先记录影响和 contract test。

## 禁止修改

- Screen/ChatList/Profile/Search 的生产实现；可有明确标记的 internal placeholder，不能宣称对等。
- Create/ChatDetail 业务 RN 源码。
- push/payment/production OTA/release/signing。
- 为赶进度跳过 token migration、API fixture、analytics/test IDs。

## 明确交付物

1. Splash/bootstrap state machine：restoring / unauthenticated / authenticated / fatal-retry。
2. Native Login：固定 RN SHA 下实际存在且产品要求的登录方式；不得凭旧文档增删。
3. 五 Tab shell 与 Create 伪 Tab；未迁 Tab internal-only 明确状态。
4. Native Home 第一纵切：真实接口、缓存、首屏、分页、刷新、错误/空/离线、点击到 RN 详情。
5. 最小 Design System 与稳定 semantic IDs。
6. Login/Home 的 unit、fixture、instrumentation、screenshot、analytics 对拍。
7. 旧 RN vs Native 的 startup/Home baseline 报告。

## 实施顺序

### P02-A：产品真值审计

1. 从实际 RN 注册、feature flags、渠道条件列出登录方式；检查 Firebase/Google/Apple Web/email/password hidden flow。
2. Home 列出 section/source endpoint、query/filter、paging、cache、activity/banner、World Android 差异、session/曝光事件。
3. 保存脱敏响应 fixtures和 UI reference；更新 parity matrix F02/X02/X04。
4. 对不确定产品规则标 `BLOCKED_DECISION`，不能自行选择。

### P02-B：启动与 Shell

1. 建 sealed bootstrap state；等待 P01 auth migration/locale/config settle 后再选 destination。
2. Splash 首帧与 analytics 只触发一次；retry 不重复初始化 SDK/session。
3. Fragment root navigation + Native bottom bar：Screen、Home、Create、ChatList、Profile，Home 为当前生产-ready tab。
4. Create 点击打开 `CreateSurface` 之前，若 P04 尚未验证，只在 internal build 使用 Debug/明确不可用提示；不得静默假成功。
5. tab reselect、system back、process saved state 规则写测试。

### P02-C：Native Login

1. ViewModel/StateFlow 表达输入/发送验证码/登录/loading/error；UI 不直接调用 Retrofit。
2. Firebase token/Email API 与 fraud `X-Client-ID` 按 registry；不 log credential/code/token。
3. 登录成功只通过 AuthRepository 更新 session/generation；Router 收敛到 Home。
4. 错误文案使用生成 i18n；重复点击/旋转不重复提交。
5. 需要外部 OAuth 配置时先用 fake/instrumented flow，真实 client ID/签名 fingerprint 属停止条件。

### P02-D：Home 首个垂直切片

1. 选择 RN Home 中稳定且能代表真实 paging/cache/tracking 的主列表，不只做 mock card。
2. DTO/domain/UI model 分层，真实 fixture覆盖 scalar/null 漂移。
3. ViewModel state：cold cache → refresh、content、empty、error/retry、pagination append/error。
4. cache key 含 env/account/language/filter/schema；logout/换号/换语言让旧请求失效。
5. 列表 stable key；增量更新，不全量替换导致滚动/曝光重置。
6. 点击卡片经 typed Router 打开 RN ChatDetailSurface，透传最小必要 route/recommend context；back 回到相同滚动位置。
7. analytics recording sink 与 RN baseline 对拍：splash PV、Home PV、impression、click、session/source。

### P02-E：质量与性能基线

1. API/state/cache/auth generation unit tests。
2. Compose screenshot：loading/content/empty/error/长文案/至少一 RTL locale。
3. instrumentation：游客/登录、tab/reselect、paging、retry、Native→RN→Native、rotate/process recreation。
4. TalkBack label/order/role、fontScale 1.3/2.0、48dp target、testTag registry。
5. 测旧 RN 与 Native cold/warm start、Home first content、scroll frame/heap；先记录分布，再由 owner批准预算。

## 稳定 ID 最低集合

```text
android.login.root
android.login.email
android.login.code
android.login.submit
android.tab.screen
android.tab.home
android.tab.create
android.tab.chatlist
android.tab.profile
android.home.root
android.home.refresh
android.home.feed
android.home.card.<stable-id>
android.state.loading|empty|error
```

动态 ID 必须脱敏且稳定，不把用户文本直接拼入 tag。

## 自动验收

```bash
./gradlew check
./gradlew lint
./gradlew test
./gradlew :app:assembleGooglePlayDebug
./gradlew :app:assembleDirectApkDebug
./gradlew :app:assembleRuStoreDebug
# P00 固定的 connected/instrumentation task：Login/Home/Native↔RN suite
# screenshot/golden verification task
```

如本 packet 没改 RN pin，不要求修 RN 全仓历史债务；必须运行 route/contract targeted tests。若改 RN，则执行全部 RN baseline gates并说明 delta。

## 手工 QA

1. fresh install 游客、旧测试包已登录覆盖、token 过期三种启动。
2. 登录快速重复点击/旋转/后台，只有一次请求与一次导航。
3. Home 慢网、离线缓存、空、5xx、分页末尾/分页失败重试。
4. 换账号/语言/filter 时旧响应不覆盖新列表。
5. Home 滚动后进 ChatDetailSurface，back 保留位置；连续 back 不退出错误层级。
6. 五 Tab/reselect/进程恢复；未迁 tab 明示 internal 状态，不崩溃。
7. 三 flavor 至少 smoke Home 与正确 download channel header；无跨渠道 UI/SDK。

## 回滚

- 通过 internal feature flag/Router 映射把 Home 切回受控 RN/旧 App 路径；没有已验证 fallback 时不进入 release。
- 登录/存储 schema 保持 P01 兼容，不因 UI revert 清数据。
- feature module 可独立 revert，不回退 core generation/数据版本。

## 必须停止的条件

- 登录方式/World/content rating 的渠道规则无法从源码/产品 owner 确认。
- 缺 Firebase/OAuth 指纹使真实登录不可验证。
- Native Home 与 RN API/analytics 语义无法对齐，需要 backend 变更。
- 旧 RN 覆盖升级 auth 失败或出现跨账号数据。

## Definition of Done

- 真实 Login→Home→RN Chat→Home 路径可运行，不是 mock。
- Home 所选 scope 的 parity matrix 有全部 10 类证据。
- 三 flavor debug 通过；instrumentation/screenshot/a11y/analytics/performance baseline 完成。
- 未迁 Tab/流程清楚标记且不进入 production-ready 声明。
- progress P02 DONE、P03 READY，写实际 SHA 与未解决产品差异。

## 完成回报格式

使用 `AGENTS.md` 的 7 项格式；另附 Login→Home→RN Chat journey、三 flavor header 摘要、RN/Native 性能基线和 parity matrix 更新行。任何 placeholder tab 必须逐项列出，不能混入 production-ready 结论。
