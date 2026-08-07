# iOS Native 迁移复盘：Android 应继承什么

审计基线：`tipsy-iOS@4b42d8d`，`tipsy-app@cbd521f02972933c21f90c01787ea5c11200875e`，2026-08-07。

本文记录已经发生的事实和经验，不代表 Android 已实现状态。文中的源码路径除特别说明外均相对 `tipsy-iOS` 仓库。

## 1. 结果摘要

iOS 的成功并不是“RN 全量重写为 Swift”，而是形成了稳定的 brownfield 产品：

- Native 成为 App 生命周期、根导航、认证、网络、语言、推送、分析、监控和 Extension 的单一系统所有者。
- Screen、Home、ChatList、Profile、Search 等高频/性能敏感路径 Native 化。
- Create、ChatDetail、Comments、EditProfile、Settings 子页等高变化或 OTA 高收益路径由 RN Surface 承载。
- RN 不再作为完整 App Root，而是多个受 Native 管理的微根；单 Runtime、多 Surface。
- Release 内置 Surface Hermes bundle，同时通过独立 channel + `bridge-N` runtime + `index.surfaces.js` entry 接收兼容 OTA。
- 通过固定 submodule pin 管理两仓边界，并逐步建立真实设备、QA、监控、release transaction 与契约测试。

最重要的资产是边界、所有权、升级兼容和失败用例，而不是 Swift 文件本身。

## 2. 实际迁移时间线

### 2.1 Native 壳与核心基础，2026-06-11 ～ 06-14

| Commit | 实际工作 | 对 Android 的含义 |
|---|---|---|
| `e3ec7e6` | 首个 Native shell：Tab、Router、Login、Home、RN Surface，约 99 文件/10k 行 | 第一阶段必须是可运行的完整垂直切片，不是孤立组件库 |
| `5e353f8` | RN 改为 submodule pin | 两仓协作可追踪；固定源码真值 |
| `0f1dbd8` | 接入 RN Surface entry 与 Codegen 依赖 | RN 需要专门微根，不能直接假设 App 根可嵌入 |
| `c34e6f9` | Native 接管 Auth，多来源 token 迁移 | 覆盖升级/历史存储必须在页面迁移前解决 |
| `19952be` | Profile Native | 稳定、高频展示页适合优先迁移 |
| `e5221c8` | Native i18n 与 Settings | 语言是应用级状态，不是页面工具函数 |
| `401f6f8` | ChatList Native | 列表可 Native，深层聊天栈仍可 RN |
| `6afb132` | Screen Native | 媒体流需要专门资源池、预加载和性能 gate |

这一阶段确立了两条正确原则：应用级能力只能有一个所有者；RN 是 Native 宿主管理的 Surface，不再是独立主应用。

对应实现：

- `Tipsy-iOS/App/AppDelegate.swift`
- `Tipsy-iOS/TabBar/MainTabBarController.swift`
- `Tipsy-iOS/Router/TipsyRouter.swift`
- `Tipsy-iOS/RNHost/RNSurfaceHost.swift`
- `Tipsy-iOS/Core/Auth/AuthTokenStore.swift`
- `Tipsy-iOS/Core/APIClient.swift`

### 2.2 页面迁移与 ROI 校准，2026-06-15 ～ 06-25

| Commit | 决策 | 经验 |
|---|---|---|
| `8cc4efb`、`37c68a5` | Create 放弃全 Native，改为 `CreateSurface` | 表单复杂、字段变化快、OTA 高收益；Native 重建不完整 by-id 模型可能丢字段 |
| `30a34fb` | Search Native | 数据与交互边界清晰，迁移收益稳定 |
| `6689c0f` | Chat Map Native | Android 必须重审地图 SDK/权限，不照搬 iOS |
| `c861b72` → `9fb4ba5` | Comments 曾 Native，后路由回 RN | “实现完成”不等于长期 ROI 合理 |
| `fae61b4`、`84652f0` → `80fc2f6` | EditProfile 曾 Native，后回 RN | 高变动编辑表单双端维护漂移严重 |
| `8bd1d01` | Release 内置 Hermes Surface bundle | 不能只验证 Metro；离线 release bundle 是基础 gate |

回撤不是失败，而是成功迁移中最重要的范围校准。Android 首轮直接继承：Create、Comments、EditProfile 不作为 Native 目标。

### 2.3 生命周期、并发和系统能力加固，2026-06-26 ～ 07-02

| Commit | 工作/问题 | Android 必须预防 |
|---|---|---|
| `fea72f6` | Native Push | Push token 和通知路由只能有一个 owner |
| `b2773e1` | RN 首帧 gate 与启动淡入 | Surface 不能靠固定延时消除黑屏 |
| `883b53b` | Native analytics/Home 埋点 | 根级 session/PV 必须从 App.tsx 迁出 |
| `639ab9a` | 架构审查修复 token 复活、Surface 泄漏、鉴权语义、MMKV 信封等 | 作为 Android P01/P04 的强制回归清单 |
| `89cd82b` | Native/RN 统一 401/402 | 两套网络栈必须行为一致 |
| `3d927ba` | auth epoch 隔离 | logout/换号后旧异步结果不得回写 |
| `055fb22`、`49e63e8` | 缓存和预取加入账号作用域 | 缓存 key 至少包含环境、账号、筛选维度 |

`639ab9a` 暴露的风险比像素对齐更危险：

1. 退出登录后在途 refresh 又写回 token，形成“账号复活”。
2. 旧账号请求回写新账号 UI/缓存。
3. RN Surface 关闭事件重复消费或 pop 穿透。
4. `public` API 被实现成永远无 token，而原语义是“有 token 就带”。
5. 把 MMKV/SecureStore 历史值错误假设为单一 JSON 形态。

### 2.4 OTA、性能、监控与质量，2026-07-07 ～ 07-15

| Commit | 工作 | 应提前到 Android 哪一阶段 |
|---|---|---|
| `e34ce12`、`33b80a8` | SwiftLint | P00，而不是页面迁移后 |
| `13f0495`、`c936795` | OTA preview/prod 与真机验证 | P04 之前建立隔离，P06 发布 gate |
| `f5d2e60` | 图片与列表性能 | 首个媒体/长列表页面同步建立 |
| `f01a540` | Native + RN Sentry | P01/P04，灰度之前 |
| `47988ea` | Settings 子页面整体回 RN，删除约 2.7k 行 Native | Android 只迁设置入口和语言 |
| `51eba61`、`a0a1502` | 批量补约 295 个 accessibility ID | Android 从第一个组件开始做，不后补 |
| `977db94`、`057b2bc` | 修复 App.tsx/FirstEnter 不挂载导致 AppsFlyer、ATT、Facebook 未初始化 | P01 建 root side-effect inventory，P04 验证所有 SDK |

### 2.5 商店发布、ABI OTA 与自动化，2026-07-16 ～ 08-07

| Commit | 工作 | 经验 |
|---|---|---|
| `393af15`、`d9b666f`、`6a1f841` | Home seed union、增量 banner、语言 settle 与竞态 | 不要全量替换媒体列表；增量更新也必须有版本/乱序 guard |
| `e5cc2bb` | 首个商店 Native 版本 | 发布不是迁移末尾的一条命令，而是一组可复现门禁 |
| `5fcf439` → `8698842` | runtime 从 marketing version 改为 `bridge-N` ABI | OTA runtime 表示 Native/JS ABI，不表示营销版本 |
| `42c5ad7`、`3191db5`、`074511d` | 构建触发、失败回滚、release transaction 测试 | 脚本先守卫、dry-run、只回滚自有状态、原子更新远端 |
| `6dab348` | 独立 QA bundle/profile/API | QA、production、完整 RN OTA、Surface OTA 必须隔离 |
| `43897d2`、`04a070f`、`4ffdf30` | 推荐模型、AB、归因契约测试 | 业务上下文也是跨端 ABI，不只 bridge 方法名 |

最终 OTA 三重隔离：

```text
channel: preview / production
runtime: bridge-N
entry: index.surfaces.js（不是完整 App entry）
```

## 3. 目前的功能归属，而非历史文件存在性

| 领域 | 当前 iOS 归属 | Android 结论 |
|---|---|---|
| 生命周期、五 Tab、Router | Native | Native 首批 |
| Login/Auth/HTTP/i18n | Native | Native 首批，先解决覆盖升级 |
| Home、Screen、ChatList、Profile、Search | Native | Native ROI 高；按风险分波 |
| Create | RN Surface | 直接保留 RN |
| Chat Detail | RN Surface | 直接保留 RN |
| Comments | 当前 RN；Native 历史实现 dormant | 直接保留 RN，不误读备份代码 |
| EditProfile | 当前 RN；Native 历史实现 dormant | 直接保留 RN |
| Settings | 列表/语言 Native，子页 RN | Android 同边界 |
| Notification、RoleCard、Onboarding、Coins | RN Surface | 首轮保留 RN |
| Push、analytics、marketing、Sentry | Native owner + RN runtime 接入 | Native 首批建立 owner |
| OTA | Native 选择 Surface bundle | 独立 channel/runtime/entry |

## 4. 可直接复用的十条经验

1. **按业务收益迁移，不以 Native 覆盖率为 KPI。**
2. **应用级能力只能有一个 owner。** Token refresh、logout、push、deep link、语言、analytics session 不得 Native/RN 双写。
3. **先冻结边界契约再迁页面。** Auth、Navigation、Lifecycle、Analytics/Context、Storage 分开版本化，不把所有能力塞进 Auth 模块。
4. **每个 RN Surface 是微型 App Root。** 显式提供 i18n、auth/user hydrate、toast/portal、配置、Sentry、首帧与关闭协议。
5. **OTA runtime 表示 bridge ABI。** 新方法 additive + capability gate；破坏性变化才升 `bridge-N`。
6. **覆盖安装与历史数据迁移是 Phase 0/1。** 不能等页面完成后才发现用户全部退出登录。
7. **所有异步回写带 generation。** auth、筛选、分页、乐观更新、Surface 生命周期都要防止陈旧结果。
8. **API 真值来自真实响应 fixture。** TypeScript 类型无法覆盖 number/string/null 漂移。
9. **路由集中管理并防重入。** Activity、Fragment、Compose、RN 不各自解析 deep link。
10. **监控与 accessibility 在迁移第一天建立。** 没有可观测性就无法安全灰度。

## 5. Android 不应复制的反模式

- 在高变动页面做逐页 Native 复制，再长期维护双实现。
- 假设移除 RN App Root 只影响 UI，遗漏 root effects/SDK 初始化。
- 为每个 Surface 创建 React Runtime。
- 把 optional auth 错做 no-auth。
- token/缓存不带账号与环境作用域。
- 对长列表或播放器集合做全量替换，导致滚动、曝光、播放状态重置。
- 只测完整 RN App，不测 Surface 微根、返回栈、旋转、进程恢复和离线 release bundle。
- 在 active source set 保留无标识的 dormant 页面，使人和 AI 误判线上路由。
- 页面完成后才补 lint、contract tests、test IDs、QA flavor 和 release drill。
- 用人工维护的多份“当前进度”文档替代 Git SHA 与唯一 progress。

## 6. 对 Android 方案的直接决策

- 先采用 integrated brownfield，固定 SDK 54/RN 0.81.4；isolated AAR 仅做后置独立 POC。
- 主导航采用 Fragment 宿主：Native Fragment 内使用 ComposeView，RN 使用 ReactFragment，统一处理返回栈与生命周期。
- 单 React Runtime，Debug Metro 与 Release/QA embedded Surface bundle 都必须测试。
- Phase 0 先跑通 `DebugSurface`，再跑 `ChatDetailSurface`；连续开关、返回、旋转、进程重建、内存和首帧都是 gate。
- Native Auth 首先兼容 RN MMKV；MMKV 缺 token 时，不重新猜 Expo SecureStore 加密实现，而用一次性 RN `AuthBootstrapSurface` 读取并移交。
- Screen/Media3 放在基础和普通列表之后；Create/Comments/EditProfile/Settings 子页明确不迁。
- 三个渠道分别构建和覆盖升级；无法用 Google Play 结果推断 APK/RuStore。

## 7. 复盘资料的已知漂移

旧 `llmdoc/reference/ios-native-progress.md` 同一文档曾记录不同 submodule pin，且部分功能状态已落后当前代码。旧 README 的版本描述也曾落后 `app.json`。因此：

- 不把 iOS progress 快照直接复制到 Android。
- Android 每个 packet 固定 source/target SHA。
- `android-native-progress.md` 是唯一状态真值。
- 每个波次结束运行 Git/RN delta audit；发现不一致先修文档，再继续实现。
