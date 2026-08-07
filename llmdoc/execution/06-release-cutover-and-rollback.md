# P06：三渠道发布、覆盖升级、灰度切换与向前恢复

## 任务元数据

| 字段 | 值 |
|---|---|
| Task ID | `ANDROID-P06` |
| Execution status source | `../reference/android-native-progress.md` 的 P06-* 与 Project release state；本文件不复制状态 |
| 目标仓库/系统 | Android/RN 仓、受控 CI、Google Play、Direct APK、RuStore、EAS/Sentry/analytics |
| source_rn_commit | `BLOCKED_UNTIL_P05_DONE`；转 READY 前替换为完整 40 位 SHA |
| target_android_base_commit | `BLOCKED_UNTIL_P05_DONE`；转 READY 前替换为完整 40 位 SHA |
| Depends on | P06-A1..A4 依赖 P05 DONE；P06-GP/APK/RS 还依赖 P04-DE DONE、签名/商店/监控 owner 明确 |
| Blocks | 正式切换 |

## 授权边界

本 packet 分两段：

- **P06-A1..A4（Codex 可直接执行）**：构建/校验/发布脚本、dry-run、runbook、非生产 QA、artifact metadata、恢复演练准备。
- **P06-GP / P06-APK / P06-RS（必须逐次人工授权）**：读取受控签名、构建 production artifact、上传、创建/发布生产 OTA、商店提交、灰度比例变更、暂停/恢复 rollout。

用户仅说“执行 P06”不等于授权生产外部写操作。Codex 完成 P06-A1..A4 后必须列出精确命令和影响面，等待当前会话对具体渠道明确授权。

## 唯一目标

证明三个真实 application id 都能从当前 RN 商店版本安全覆盖到 Native 候选，有可观察的 staged rollout 与实际演练过的向前恢复；在授权后按渠道串行切换，不把一个渠道的成功外推到另两个。

## 允许修改

- release/verification/overlay/failure-injection scripts、CI protected workflow skeleton、runbooks、artifact metadata、P06 测试与 progress。
- 获得逐次明确授权后，受控 CI 中与本次渠道发布直接相关的版本/签名引用和外部提交动作。

## 禁止修改或执行

- 未授权的 keystore/secret 读取、production build/upload/OTA/store/rollout 操作。
- application id、签名证书、已批准 channel/runtime/entry 的临时改动。
- 同时发布三个渠道、降低 versionCode、删除旧 RN release branch/fallback。
- 失败时 reset 用户工作树或删除非本次创建的远端状态。

## 开始前必读

- build/release/OTA architecture、quality gates、progress、P05 go/no-go 报告。
- 当前 RN `eas.json`、app config、release scripts/build profiles、三渠道支付/下载/SDK 配置。
- iOS release transaction/failure injection 思路，仅复用事务原则。

## 冻结清单

发布候选必须记录且在整个 rollout 不漂移：

```text
Android source SHA/tag candidate
RN submodule SHA
dependency lock hashes
surface contract/runtime/entry
three application IDs
versionName + monotonically higher versionCode per channel
API/environment/remote-config snapshot
signing certificate SHA-256 per channel
embedded bundle hash + approved OTA update ID（如有）
R8 mapping/native symbols/RN source map build ID
known risks + kill switches + owner
```

versionCode/versionName 的具体值由 release owner批准。脚本可校验/提案，不能自行占号或修改生产版本。

## P06-A：Codex 可执行交付

### P06-A1：Release transaction 工具

实现默认 dry-run 的单一入口，支持 distribution/profile 参数，要求：

1. 所有本地零成本 guard 在任何外部写之前：clean/expected branch、SHA/pin、版本唯一/递增、toolchain、gates、flavor identity、凭据变量是否存在但不打印。
2. dry-run 输出计划、将修改的文件/远端、预期 artifact 名；不改版本/tag/remote。
3. `--execute` 仍需环境中的显式确认变量/CI protected environment。
4. 唯一 build ID 串联 binary、mapping、symbols、source map、Sentry release、QA report。
5. 只回滚本次生成的临时版本改动/tag/state；不 reset 用户工作树。
6. 多 ref 更新原子 push；build trigger/终态 watcher/notification 解耦且防重。
7. 故障注入覆盖 guard、version、build、upload、tag、push、notify 每个失败点。
8. production upload/publish adapter 在本地默认不可用；CI protected environment 才注入。

### P06-A2：Artifact verification

对每个渠道的 QA/unsigned 或受控 signed candidate 自动输出：

- APK/AAB hash/size/application id/version。
- signing cert fingerprint（只读，不导出 key）。
- merged manifest/permissions/exported/schemes。
- Firebase app/client、Billing/SDK/dependency presence/absence。
- RN SHA/entry/runtime/embedded hash。
- R8/mapping/symbol/source-map presence 与 build ID。
- 安装/启动/Surface smoke 结果。

不允许 release fallback 到 debug keystore；缺签名就标 BLOCKED。

### P06-A3：覆盖升级 harness

为每渠道准备脚本化步骤，但旧包/凭据由人提供：

1. 校验旧包 application id/cert/version，安装并设置测试状态。
2. 保存不含 PII/token 的 before checklist：logged-in/guest、language、关键 UI状态、widget、wallet server result。
3. `adb install -r` 候选，断言成功而非自动 uninstall。
4. 启动并验证 migration outcome、Home/Chat/Surface/push/deep link/payment入口。
5. reboot/process kill/account switch；重复安装同/更高版本验证幂等。
6. 输出 before/after 证据与 logcat redaction scan。

### P06-A4：Runbooks

写出并本地演练：

- QA distribution runbook。
- Google Play staged rollout runbook。
- Direct APK 发布/校验/下载页缓存 runbook。
- RuStore 提交/支付/deep-link runbook。
- Surface OTA preview/prod runbook。
- pause rollout、kill switch、forward recovery runbook。
- incident ownership/communication/evidence archive checklist。

Runbook 中命令默认 dry-run/占位，不包含 secret。

## P06-GP / P06-APK / P06-RS：需逐渠道授权的真实验证与切换

### 渠道顺序

默认串行：内部 QA → 最低风险的受控外部分发 → Google Play/RuStore/Direct 逐个。最终次序由业务 owner批准；不能三渠道同时首发。

每个渠道单独执行：

1. 受控 CI 构建 signed artifact，完成 artifact verification。
2. 用**当前该渠道线上 RN 包**做 logged-in/guest/history overlay matrix。
3. push、deep link、支付、attribution、Sentry、OTA/embedded、widget/voice 真机。
4. release owner签署 go/no-go；上传/提交。
5. staged rollout 小流量开始；每个观察窗口由 owner预先定义时长/样本/阈值。
6. 观察 crash-free、ANR、startup、auth migration failure、API/decode、Surface/OTA、支付/留存等相对旧 RN baseline。
7. 只在阈值内提升；跨阈值立即暂停，执行 kill switch/向前恢复决策。

Codex 可读报告并给出判断，但不能自行改变 rollout 百分比或外部状态。

## Go / No-Go 条件

### 必须全部 Go

- P05 无未接受红项；source/pin/artifact/build ID 冻结。
- 本渠道 package/signing/version/Firebase/Billing/manifest 全部正确。
- 真实 RN→Native `adb install -r` logged-in/guest/history 通过。
- auth migration 不丢登录、不跨账号；旧 RN last-known-good 可构建。
- core journeys、push/deep link/payment/widget/voice/Surface/embedded/批准 OTA 通过。
- mapping/symbol/source map 可反符号化测试 crash。
- kill switches、pause 权限、forward recovery artifact/source/owner 就绪。

### 任一即 No-Go

- debug/reused-wrong signing、版本不递增、包名/渠道依赖错误。
- 卸载重装才能“通过”覆盖升级。
- token/PII 泄露、跨账号/环境数据。
- crash/ANR/支付/auth/Surface 指标超批准阈值。
- 无法定位 build/symbol/source map，或无可执行恢复路径。
- 关键真机/商店步骤 NOT RUN 且未由风险 owner明确接受。

## 向前恢复演练

Android 不能给已升级用户降 versionCode。正式 rollout 前至少在受控渠道演练：

1. 发布/安装候选 N。
2. 模拟红线，暂停 staged rollout。
3. RN Surface 问题：kill switch/fallback embedded/republish last-known-good 独立 update。
4. Native 问题：从 last-known-good Native 或保留 RN release branch 构建 N+1（更高 versionCode）恢复 artifact。
5. 安装 N→N+1，验证新旧 storage 都可读、没有进一步破坏。
6. 验证 incident dashboard、通知、artifact/mapping/symbol archive。

演练不能真的发布生产，除非单独授权；内部/preview 环境必须完整走一遍。

## 自动验收

P06-A1..A4 至少：

```bash
./gradlew clean check lint test
./gradlew :app:assembleGooglePlayDebug
./gradlew :app:assembleDirectApkDebug
./gradlew :app:assembleRuStoreDebug
# release tool --dry-run for each distribution
# release transaction failure-injection tests
# artifact verifier fixture tests
# overlay harness tests with non-production signed fixtures
```

真实 release/商店/OTA 命令不得预先写成已执行；授权后逐条记录命令、actor、时间、外部 ID、退出/终态。

## 归档

每渠道 last-known-good 归档：

- source tag/Android SHA/RN pin/dependency locks。
- binary hash/build ID/signing fingerprint/version。
- mapping/native symbols/RN source maps/SBOM/license。
- embedded/OTA metadata/update ID/channel/runtime/entry。
- overlay/QA/go-no-go/rollout/forward-recovery 报告。
- 不含 secret 的重建说明和 owner。

## 回滚/清理规则

- 脚本失败只撤销本次自有状态；不删除已存在 tag/artifact/用户改动。
- 已上传 artifact/商店草稿由有权限 owner按平台规则处理，Codex不擅自删除。
- 已发布 Native 不称 rollback；暂停 + 更高 versionCode 向前恢复。
- OTA 只能操作独立 Surface channel；不碰完整 RN App channel。

## Definition of Done

P06-A1..A4 可在无生产凭据下逐项 DONE。只有 P04-DE 也 DONE、所有 release gate 就绪后，progress 的独立 `Project release state` 才可写 `READY_FOR_AUTHORIZED_RELEASE`；P06 总阶段和三个渠道仍不是 DONE。

整体 P06 DONE 需要：

- 三渠道各自 signed artifact、真实覆盖升级、系统/支付/监控 gate 通过。
- staged rollout 和观察由授权 owner执行，外部 ID/证据归档。
- 至少一次受控 pause + forward recovery 演练成功。
- progress 精确记录各渠道状态，不把一个渠道成功复制给其他渠道。
- 所有生产外部写操作都有当前任务明确授权与可审计 actor。

## 完成回报格式

按 `AGENTS.md` 回报；P06-A1..A4 与每次获批 P06-GP/APK/RS 操作分开列出 actor、时间、渠道、命令、外部 build/submission/update ID、终态、artifact hash、签名指纹、观察结果和恢复状态。work-unit 状态与独立 `Project release state` 不得混写。
