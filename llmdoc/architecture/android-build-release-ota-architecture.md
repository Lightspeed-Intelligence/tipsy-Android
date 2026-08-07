# Android 构建、发布与 OTA 架构

本文定义目标与安全规则；不授权发布、签名或生产 OTA。

## 1. 构建维度

### Distribution product flavor

| Flavor | application id | Billing/渠道规则 |
|---|---|---|
| `googlePlay` | `com.tipsyturbo.app` | Google Play Billing；Play 内容政策 |
| `directApk` | `ai.lightspeed.tipsy` | Direct APK 支付/下载渠道 |
| `ruStore` | `com.tipsytavern.app` | RuStore Pay 与专属 activity/metadata |

### Build type

| Build type | 用途 | 签名/服务 |
|---|---|---|
| `debug` | 本地/CI；Metro 可用 | debug keystore；不可用于覆盖线上签名包 |
| `qa` | 内部分发；embedded/QA Surface bundle | 由受控 CI 注入 QA/生产兼容签名，环境明确隔离 |
| `release` | 商店/正式 APK | 生产签名，仅受控发布任务可访问 |

禁止通过 applicationIdSuffix 随意改变生产身份来“简化”测试。side-by-side 测试若确需新 ID，必须单独批准并补 Firebase/deep-link 配置；覆盖升级 gate 始终使用真实 application id 与匹配签名。

空工程 P00 的唯一版本例外：初始化 `versionCode=1`、`versionName=0.0.0-dev`，所有 debug/qa metadata 标记 `NOT_FOR_STORE`；release 不配置 debug signing且不可上传。这只是让 Gradle/产物检查可执行，不代表生产版本。P06 才能在 release owner批准后提出并应用真实递增版本。

预期无签名 CI variant：

```text
googlePlayDebug
directApkDebug
ruStoreDebug
```

P00 实际创建后必须把 `./gradlew :app:tasks` 结果写回质量文档；命令名不能靠计划猜测。

## 2. 固定工具链

- JDK 17。
- Gradle 8.14.3。
- AGP 8.11.0。
- Kotlin 2.1.20。
- compileSdk/targetSdk 36，minSdk 24。
- NDK 27.1.12297006。
- Node/npm 使用 `tipsy-app` lockfile；P00 记录实际 Node/npm 版本并在 CI 固定。
- 所有依赖通过 version catalog 固定；禁止 `+`、floating branch 或构建时隐式升级。

P00 先生成临时 CNG 工程作 diff/reference，不能把 `tipsy-app/android/` 当正式源，也不能运行 prebuild 覆盖本仓 Native 工程。

## 3. Flavor 隔离

每个 flavor 的 source set/config 必须显式包含或验证：

- application id 与 app label。
- Firebase app / `google-services.json` 来源。
- download channel header。
- Billing provider 与订阅 backend platform（Play `2`、RuStore `4`；Direct 以实际 RN 源码审计为准）。
- deep link/app link/intent filters。
- attribution SDK 启用策略；TikTok 等不得跨渠道误初始化。
- content rating/AI 能力渠道策略。
- ProGuard/R8 keep rules。
- 仅该渠道需要的 repository/dependency/metadata。

RuStore 不再通过 config plugin 字符串替换 `MainActivity`/`MainApplication`；使用 flavor source set 和稳定 Gradle/Manifest 配置。

P00 建立 manifest/package snapshot tests，防止 flavor 串配置。

## 4. RN bundle 入口

### Debug

- Metro port `8083`。
- entry `index.surfaces.js`。
- cleartext 只允许 debug manifest/Network Security Config。
- DebugSurface 是第一个 gate；ChatDetailSurface 在 auth bridge 后启用。

### QA/Release embedded

- 使用固定 RN SHA 和 lockfile 安装依赖。
- 通过 Expo CLI/RN Gradle plugin 只打 `index.surfaces.js` 入口。
- bundle、assets 与 Android binary 一起可离线启动。
- build metadata 写入 RN SHA、Android SHA、surface contract/runtime、distribution、environment；不写 secret。
- CI 校验产物里没有完整 App entry 误打包标志，且三 flavor 可打开最小 Surface。

## 5. OTA 隔离

现有 RN Android `preview/production` 更新服务完整 RN App。Native shell 不能复用相同 entry/channel，否则可能把完整 App bundle 下发到 Surface host。

在获得用户明确批准并完成 EAS 配置前，P00-P03 只使用 embedded bundle。P04 提交提案并建立独立矩阵，例如：

```text
entry: index.surfaces.js
runtime: android-bridge-1
channel: android-native-<distribution>-preview
         android-native-<distribution>-production
```

最终名称以批准后的 EAS 配置为准。不可破坏规则：

- runtime 表示 Native/RN ABI generation，不绑定 `1.4.x` marketing version。
- 三 distribution 不串 channel，preview 不串 production。
- bundle metadata 声明 contract version；不兼容时拒绝并 fallback embedded。
- 新 Native + 旧 JS、旧 Native + 新 JS 均做 N/N-1 contract test。
- OTA 只恢复 RN Surface；Native 崩溃/数据迁移问题不能靠 JS OTA 宣称回滚。
- 发布 OTA 需要独立明确授权，本仓脚本默认 dry-run。

参考：https://docs.expo.dev/eas-update/integration-in-existing-native-apps/

## 6. CI 分层

### PR fast gate

- Gradle wrapper/依赖校验。
- formatting/static analysis/Android lint。
- 纯 Kotlin unit/contract tests。
- 三个 debug flavor assemble。
- 影响 RN pin/bridge/bundle 时，额外执行 RN typecheck、lint、targeted tests、i18n 和 embedded Surface export smoke。

### Nightly

- API 24 与 API 36 emulator matrix（实际可用 image 由 P00 固定）。
- instrumentation：启动、tabs、Native↔RN、back、deep link、auth state。
- Surface 多次开关、rotation/process recreation。
- Macrobenchmark：startup、Home/Screen scroll、Surface first frame。
- dependency/permission/manifest diff。

### Release gate

- 三渠道 release/QA 签名产物分别验证。
- application id、version、证书 SHA-256、Firebase、Billing、deep link、权限快照。
- R8 mapping、native symbols、RN source maps 与 build ID 唯一关联并归档。
- 当前 RN 商店包 → `adb install -r` Native 包覆盖升级。
- push、支付、widget、voice FGS、attribution、Sentry、OTA N/N-1 真机验收。

## 7. 事务化发布脚本要求

脚本必须：

1. 先执行所有零成本守卫：工作树、分支、SHA、version 唯一性、flavor、凭据存在性但不打印值。
2. 默认 dry-run；显式 `--execute` 才允许外部写操作。
3. 生成唯一 build ID，并验证 artifact/metadata/mapping/symbol/source-map 一一对应。
4. 只回滚脚本本次创建的本地文件/tag/状态，不触碰用户已有改动。
5. 多个远端 ref 需要一起更新时使用原子 push；失败不留下半套版本状态。
6. build trigger 与终态监控/通知解耦，并按 profile/build ID 防重。
7. 支持故障注入测试：guard、build、upload、tag、push 每一步失败都验证清理范围。

这些要求来自 iOS 已验证的 `eas-store-release.sh` 与 transaction test 思路，但 Android 需重新实现，不复制 Xcode 细节。

## 8. 覆盖升级矩阵

每个 distribution 至少准备：

| 旧包 | 新包 | 场景 |
|---|---|---|
| 当前线上 RN release、已登录 | Native QA/release | token/user/语言/筛选/会话连续 |
| 当前线上 RN release、游客 | Native | anonymous installation/推荐连续 |
| 当前线上 RN release、历史安装升级多次 | Native | SecureStore/MMKV fallback 与幂等迁移 |
| Native last-known-good | 候选 Native | schema 前向兼容 |
| 候选 Native + embedded JS | 同 binary + OTA N/N-1 | runtime/capability/fallback |

必须用真实匹配签名；debug 重装不能替代覆盖升级证据。

## 9. 灰度与恢复

Android 已安装包不能通过降低 versionCode 回滚。恢复策略：

- PR/QA：revert 当前 packet 自有 commit，或重装 last-known-good QA artifact。
- RN Surface JS：在独立 channel republish 已验证 update，或 fallback embedded。
- Native 灾难：暂停 staged rollout，用更高 versionCode 向前发布 last-known-good Native 源码构建。
- 迁移早期：保留旧 RN release branch，可用更高 versionCode 快速构建/提交 RN 外壳恢复版本。

发布前必须实际演练“暂停放量 + 向前恢复”；仅写 runbook 不算 P06 完成。

归档每渠道 last-known-good：source tag、RN pin、artifact hash、签名指纹、mapping、symbols、source map、SBOM/依赖清单、QA 报告和 build ID。
