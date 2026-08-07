# 项目现状与迁移目标

更新时间：2026-08-07

## 1. 仓库关系

| 仓库 | 角色 | 当前审计状态 |
|---|---|---|
| `tipsy-app` | 线上 RN/Expo 产品真值；Android 旧版本与保留的 RN Surface 源码 | 基线 `cbd521f02972933c21f90c01787ea5c11200875e` |
| `tipsy-iOS` | 已成功运行的 iOS Native brownfield；经验与失败模式参考 | 审计 HEAD `4b42d8d`，submodule 同上 |
| `tipsy-Android` | Android Native 目标仓 | unborn `main`，尚无代码/CI/Gradle |

目标仓会把 `tipsy-app` 作为固定 pin 的 Git submodule 使用。跨仓 RN 修改必须先在 RN 仓独立合入，再 bump pin。

## 2. 当前 RN 技术基线

- App version `1.4.4`；Expo SDK 54（lockfile 安装 `54.0.27`）、React Native 0.81.4、React 19.1。
- Hermes 与 New Architecture 开启。
- TypeScript、React Navigation、Zustand、MMKV、SWR。
- Firebase、Expo Updates、Sentry、QuickTracking 等系统能力。
- Android 生成工程是 Expo CNG 产物，`tipsy-app/android/` 被忽略，不是本迁移的目标工程。
- `index.surfaces.js` 已注册 13 个微根，可复用 iOS 的 Surface 模式。

从固定 SHA 执行只读 `expo prebuild` 得到的兼容基线：

| 项 | 值 |
|---|---|
| Gradle Wrapper | 8.14.3 |
| Android Gradle Plugin | 8.11.0 |
| Kotlin | 2.1.20 |
| compileSdk / targetSdk | 36 / 36 |
| minSdk | 24 |
| NDK | 27.1.12297006 |
| JDK | 17 |

这些版本是 SDK 54 基线，不代表应升级到最新。迁移期间禁止把 RN/Expo/Gradle 大版本升级混入功能 packet。

## 3. 迁移目标

目标不是追求 100% Native 覆盖率，而是建立稳定 Android Native shell：

- Native 接管 Application/Activity 生命周期、五 Tab 外壳、统一 Router、auth、网络、语言、push/deep link、analytics、监控和 Android 系统能力。
- Native 优先承载启动高频、性能敏感、列表/媒体密集且业务相对稳定的页面。
- RN 继续承载迭代频繁、OTA 价值高、导航深、WebView/支付/复杂编辑器等 Native ROI 较低的流程。
- 同一生产 application id 和签名覆盖安装旧 RN 版本，保留用户登录态、语言、账号数据和必要缓存。
- 保留可立即构建的旧 RN release 分支，采用灰度与向前恢复，不虚构 Android 可降 versionCode 回滚。

## 4. 首轮明确归属

### Native 优先

- App shell 与五 Tab。
- Login/Auth、token refresh、API client。
- Home（Android 包含 World 系列差异）。
- Screen 媒体流。
- ChatList 的 Grid/Map、未读、置顶、删除与草稿。
- 自己/他人 Profile、Search。
- Settings 列表与 Language。
- Push、deep link、attribution、Sentry、QuickTracking、widget/voice-call system session 等系统入口。

### 首轮保留 RN Surface

- Create 全流程。
- ChatDetail 及其深层栈。
- Comments。
- EditProfile。
- Settings 子页面。
- Subscription/IAP、wallet/coins、Notification、RoleCard、Onboarding。
- WebView/SimulatorGame 与快速变化的运营流程。

页面最终归属以 parity matrix 审计为准；不得因“已经有 iOS Native 代码”就默认 Android 必须迁移。

## 5. 三个 Android 分发身份

| 渠道 | application id | 特殊能力 |
|---|---|---|
| Google Play | `com.tipsyturbo.app` | Google Play Billing；不启用 side-load 专属内容分级 |
| Direct APK | `ai.lightspeed.tipsy` | 独立支付/分发配置；允许产品定义的 side-load 能力 |
| RuStore | `com.tipsytavern.app` | RuStore Billing 与对应商店配置 |

三个渠道不是同一个产物改名。每个都必须独立验证 Firebase、deep link、支付 provider、签名、覆盖升级和混淆产物。

当前 RN Firebase 配置还包含额外 client `com.tipsytavern_ai.app`；它不在上述三个已确认生产身份中。P00 只验证并选择与 flavor application id 精确匹配的 client，不得据此自动创建第四个 flavor。

## 6. 成功标准

- 用户从对应渠道的当前 RN 商店版 `adb install -r` 到 Native 版后，登录、语言、账号、钱包/关键状态连续。
- Native 与保留 RN Surface 往返稳定；单 React Runtime，无重复 bridge 消费、黑屏、返回栈穿透和持续内存增长。
- 核心页面行为、埋点、异常态、accessibility ID 与 RN 基线对等，Android 平台差异显式记录。
- PR、nightly、release 三层质量门禁真实运行，不依赖“历史上曾手工通过”。
- 三渠道可独立灰度、监控、停止放量，并能以更高 versionCode 向前恢复 last-known-good。
