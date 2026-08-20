# Tipsy Android 原生化迁移：现状（唯一状态真值）

> 更新：2026-08-20 ｜ Android 壳：**W0 完成**（gate 过 + API24/37 双端验证 + manifest 快照 + lint 硬门）；
> **G1 CI 已激活且在 main 上真绿**（§2.10 / §2.22）
>
> **W1 基本收尾**（细化方案见 [`../architecture/android-w1-plan.md`](../architecture/android-w1-plan.md)）：
> **P0 桥注册已接通、完整能力 PARTIAL**（§2.11）｜ **P1 auth closeout 已实现且 CI 已验**（§2.13 / §2.18 / §2.22）｜ **P2 机制已验、兜底推迟**（§2.12）
> ｜ **P2 剩余 + P3 已决定合并推迟到上线前**（2026-08-10，见 W1 计划 §5.6）
> ｜ **P4 Router/parser 机制已落地，ChatDetail 已于 P9 放开**（§2.36）｜ **P5 i18n 已完成**（§2.16）
> ｜ **P6 network closeout 已实现且 CI 已验**（§2.14 / §2.22）
> ｜ **§12 Fragment 机制已落地、真实实例关闭链待收口**（§2.15）
> ｜ **P7 Qt / P8 Sentry 已决定推迟到业务迁移后**（2026-08-11，见 §2.17）
> ｜ **P9 已完成**（§2.36）；**冒烟 7 过 / 3 未跑**（§2.37 + §2.39，语言那项
> 壳缺陷已修并**复跑 PASS（模拟器）**；旋转/进程恢复仍未跑）
> ｜ **原生登录页：邮箱验证码链路真机已验**（§2.20）—— Google/Apple 受 §12.8 签名指纹阻塞未接
>
> **W2 进行中**：五 Tab shell + Home 首屏（§2.23，主链路真机已验）+ 标签筛选抽屉
> 与 For You 冷启动种子（§2.24，**抽屉、种子写入门禁、离线渲染种子真机全已验**）。
> Home 剩 banner / 彩蛋弹窗 / mp4 封面三项（前两项评估留 RN Surface）。
> **W3 进行中**：Profile 主体完成（§2.25–§2.29；**P7 完成 = 头像框 + 渠道图标**，
> §2.44，含对 PR #41 两处偏差的收尾修正；**P5 卡片 ⋮ 菜单完成**，§2.45 ——
> **Profile 全部批次至此收口**）；ChatList P1 Grid 主链路
> 已并入 main 且模拟器冒烟 PASS（§2.30）；**P2 ChatMap 已生产接线**（§2.47，
> 2026-08-20，模拟器全链路实测，PR #42 待脱 draft）；Search P1 主链路已实现且
> directApk 真机冒烟 PASS（§2.31）；**P2 筛选器已实现**（§2.34）。
> **W3 业务面全部落地。**
> **他人主页已实现并并入 main**（§2.32，PR #28）：`AppRoute.UserProfile` 进白名单，
> 搜索 → 创作者 → 主页成为**壳的第一条端到端可用路径**；真机冒烟未跑。
> **Settings 列表 + 语言页已实现**（§2.33）：`AppRoute.Settings` 进白名单，
> 壳终于有了改语言的入口；⚠️ 审计订正了一处读反的归属 —— **语言页要原生实现**，
> 不在 `SettingsSurface` 里。
> **Search P2 筛选器已实现**（§2.34）：Search 达成完整对等；P1 主链路
> owner 已在模拟器验过。
> **EditProfile 已完成 W3 静态预接与账号安全/刷新接力**（§2.43）：RN Surface
> 仍是业务 owner，Android 补精确 token + JWT sub 账号闸、跨卸载 mutation 串行、
> RN→Native Profile dirty/retry 接力；⚠️ 生产 policy 仍关闭、§9.1 全 NOT RUN。
> **W4 进行中：Screen P1 + P2 已实现**（§2.35 / §2.42）——**五个 Tab 至此
> 全部有真实页面**；`showcase` 已接 Media3、有界池、±1 窗口、RN Android buffer、
> 动态 50MB cache、三轴播放门与声音开关。⚠️ 真实视频、cache 失败、API24–33
> 层序、audio focus 四项仍 NOT RUN，**不得标 production-ready**；next-item / fade /
> firstInteractive 与 P3 二期项仍未做。
> **Tab3 创建入口已接 CreateSurface**（§2.40，2026-08-18）：`AppRoute.Create`
> 进白名单，**五个 Tab 全部可用**；模拟器三轮开关 + 连点幂等已验。
> ⚠️ 真机实测出一个**类别性**缺陷并已修：无参路由（实例恒相等）若不解除
> Router 去重，表现是「关掉页面后再点永远打不开」—— 后续每个无参 Surface
> 路由都要配解除。✅ §2.41 已补 `CreateSurface` 微根/微栈/注册/bootstrap
> 机器断言；⚠️ §9.1 的 8 个设备/生命周期验收格仍全 `✎`，不得标 production-ready。
> **W1-P9 已完成**（§2.36，2026-08-17）：`ChatDetail` / `MiniPhoneChat` 进生产
> 白名单，**四个原生列表页的卡片点击第一次有下一屏**；顺带查出三个过期桥桩
> （`requestLogin` / `openUserProfile` 系，debug 会抛）与「`openSurface`
> 从来没传业务参数」两个真实缺陷。§12 实例关闭链按 owner 决定记为**已接受偏差**。
> **语言倒灌已修 + 共享 MMKV 键读写方向已系统扫过**（§2.38，2026-08-18）：
> 零 RN 改动、零 submodule bump（跨仓契约本就齐全，只是壳没调）。
> ✅ **§9.1「语言切换」已复跑 PASS（模拟器）**（§2.39，2026-08-18）；同轮查出
> **分级开关 `POST /user/nsfw` 少了 `/update` 导致 404**（已修 + 补契约测试）。
> ⚠️ owner 2026-08-14 决定**真机冒烟统一推迟到功能完成后**，
> 故待验清单只累积不清空（当前四刀 + §2.38 的语言复跑）；但 owner 2026-08-17
> 追加决定 **P9 与 Screen P2（Media3）各插一次冒烟** —— 这两刀的失败模式
> （Surface 泄漏、OOM）只能真机暴露，单测与 mock 都抓不到。
> 配套决策方案：[android-native-migration-plan.md](../architecture/android-native-migration-plan.md)
> **本文是状态权威。** 方案文档只写决策不写状态；任何「进度/是否已实现」的问题一律以本文为准。

## 0. 三十秒速览

- **波次进度**：W0 完成；**W1 完成**（契约层已在 CI 组合验证（§2.22）；**P9 已完成**（§2.36）；§12 关闭链记为已接受偏差）；P2 剩余/P3/P7/P8 均已决策推迟。W2 主体已落地：五 Tab + Home + Login（§2.23/§2.24，PR #20 已并，剩 banner / 彩蛋 / mp4 封面且倾向留 RN Surface）。**W3 进行中**：Profile 主体完成（§2.25–§2.29）；ChatList P1 已随 PR #25 并入 main（§2.30）；**Search P1 主链路已实现并完成 directApk 冒烟**（§2.31）；**P2 筛选器已实现**（§2.34，Search 完整对等）；**EditProfile 已完成静态预接/账号隔离/刷新接力**（§2.43），但 production policy 仍关闭；**P7 完成 = 头像框 + 渠道图标**（§2.44，PR #41 + 收尾 PR #43，含 AuthMode 契约修正与失败保留语义）；**P5 卡片 ⋮ 菜单完成**（§2.45，PR #46：编辑原始 JSON 透传 `CreateSurface` 编辑态、删除/置顶非乐观 + 重拉对账；**owner 模拟器冒烟目测 PASS**，2026-08-19）；**ChatList P2 ChatMap 已生产接线**（§2.47，2026-08-20：接手 #42 draft 收尾 —— 纵向滚动/连续楼层/卡片点击/四词条 + bump pin，模拟器全链路实测）—— **W3 业务面全部落地**，剩 EditProfile §9.1 设备矩阵与累积真机冒烟。
- **代码现状**：`ai.lightspeed.tipsy.shell` 下有 `TipsyApplication`（单 ReactHost + Analytics facade）+ `MainActivity`（Tab 根 + Router/i18n 接线）+ `RNSurfaceFragment` + `auth/` + `network/` + `router/` + `surface/` + `i18n/` + `bridge/` + `analytics/` + `tabs/` + **`user/`** + **`pages/login/`、`pages/home/`、`pages/profile/`、`pages/chatlist/`、`pages/search/`、`pages/screen/`**；EditProfile 刷新接力落在 `pages/profile/ProfileRefreshHub` 与 `tipsy-auth.notifyProfileChanged`。
- **submodule**：pin **`f4fe474d2`**（§2.51 的桥三件套 openComments/openChatDetail/openFeedback + 测试修复；前一 pin `93c8647f3` 是 §2.47 ChatMap 四词条，更早的 `5ba22c8bb` 是 §2.46 账号缓存清理 + KeyboardProvider inset patch）。⚠️ `da4f65a` 与 PR #34 的三处壳改动**必须同时存在**：指针回退则 exclude 仍失效，而 styles/lifecycle 两处已让构建变绿 —— 会得到「构建通过但图片仍坏」的假绿。
- **已验证**：main 上 PR #25 的 G1 Fast Gate 全绿。W3 Search P1 提交前快照的本机证据：`lintDirectApkDebug` 无新增（baseline 5 条）、`assembleGooglePlayDebug`/`assembleDirectApkDebug` 通过、**DirectApk app 单测 695 条，failures=0 / skipped=0**、`:tipsy-auth` 15 条全绿；directApk 真机主链路冒烟 PASS（§2.31）。提交前审查再新增 13 条、扩展 2 条回归测试并修正并发/auth/Router/点击归因/分页去重行为，最终源码预计 708 条；**最终 head 未在本机重跑 Gradle，交 G1 验证**。
- **他人主页已实现**（§2.32，2026-08-14）：6 文件 1,469 行，`AppRoute.UserProfile` 进白名单 —— **搜索 → 创作者 → 他人主页是壳的第一条端到端可用路径**。审计推翻了「复用自己视角」的前提（七处偏差）：只有 **1 个 tab**（RN 注释说两个，代码是一个）、数据源另有四条、`size` **200 且不翻页**、`/user/get/public` 走 `axiosAuth` 会对游客弹登录页、`/plot/list/creator` **现网从未被调用**、关注按钮在 `ProfileHeader.tsx` 而非 `user-profile.tsx`。真机冒烟 **NOT RUN**。
- **Settings 列表 + 语言页已实现**（§2.33，8 文件 1,501 行）：补上了真实功能缺失 —— 此前**壳内没有任何入口能改语言**。审计订正三处：语言页**要原生实现**（RN 与 iOS 双证据）、`supportedLanguages` 壳内**恒为空**必须自己拉、Limitless 开关是 `nsfw` 的**唯一写方**且仅 directApk 可见。渠道 gating 收在 `SettingsRow` 并对三渠道各有单测。7 个 Surface 子屏（`AppRoute.SettingsSubScreen`）仍被明确拒绝。真机冒烟 **NOT RUN**。
- **P9 冒烟部分兑现**（§2.37，2026-08-17）：§9.1 十项里 **6 项 PASS**（未登录/登录切换/Back 栈底/首帧/Embedded/50 次泄漏 —— 无泄漏、容器实例恒为 1、PSS +3.7%）、**语言切换**当轮 FAIL（壳缺陷倒灌，非 ChatDetail 问题；已修并于 §2.39 复跑 **PASS**）、旋转恢复 NOT RUN。⚠️ 跑在**模拟器**上，§2.5 已定不作覆盖升级证据。同时查出 **autolinking exclude 从 W0 起一直静默失效**（PR #34）—— 此前所有构建都带着 dev-launcher 在跑。
- **语言倒灌已修 + 共享键系统扫描**（§2.38，2026-08-18）：owner 选路 1 —— 语言页确认时回写 `user-storage` 信封（`AccountLanguageWriter` merge + `notifyUserStoreChanged`）。**跨仓契约是现成的**：RN 侧监听、桥方法、iOS 实现三者都在，只是 Android 壳从没调过 → **零 RN 改动、零 submodule bump**。前置顾虑核实排除（该 key 无 `version`/`migrate`，⚠️ 但性别筛选那个 key 有自定义 `merge`，结论**不可外推**）。9 个共享键的读写方向已全扫，表落在 `LegacyMmkvStore` 类注释。app 单测 **907 条** skipped=0，三组测试均做过反向验证。✅ **§9.1 语言那列已复跑 PASS（模拟器，非真机）**（§2.39）。
- **语言复跑 PASS + 分级开关 404**（§2.39，2026-08-18）：语言那列 FAIL → **PASS**（`initialProps.context.languageCode` 实测 `zh-tw`，往返 5 次稳定）。同轮查出**`POST /user/nsfw` 少了 `/update` → 404**：因 `onNsfwToggle` 是刻意的非乐观更新（失败自动回滚），404 表现为「开关点了自己弹回去」，与「没点到」无法区分；fake-API 单测验不到真实路径，已补 `SettingsApiContractTest`（MockWebServer 真往返，已反向验证）。顺带修 `SettingsFragment` 从不观察 `languageError` 导致的**零提示**（收集器挂 `onViewCreated`，不是 `onStart`）。⚠️ 读共享 MMKV 必须先解析头部 `actualSize` —— 追加写会让 `tail` 读到上一代残留，方向正好相反。
- **2026-08-19 Surface/会话稳定性收口**（§2.46）：Profile 短列表恢复滚动；Android 原生登录把 token、完整 `/user/info` 快照与 loggedIn 收成一个事务；登出/换号同步清 `user-storage` 与无账号维度聊天 LRU；Surface 首帧宿主对齐 iOS；修复 KeyboardProvider 污染共享 Activity 状态栏 inset。RN pin 已推 `feat/android-native`。
- **✅ 卡片点击已解锁**（§2.36，2026-08-17）：`ChatDetail` / `MiniPhoneChat` 进白名单，Home/ChatList/Search/Screen 四个页面的卡片点击**第一次有下一屏**。Profile 的 `EditProfileSurface` 已完成静态预接（§2.43），但因 §9.1 全 NOT RUN 仍由 policy 明确拒绝；其余 Gems/UserCoins/RoleCard/Follow 与 Settings 7 子屏仍点不动。§12 实例关闭链**记为已接受偏差**（单层容器弹不错；根治要改 RN 侧 9 个调用点 + 双壳回归）。
- **Tab3 创建入口已接通**（§2.40，2026-08-18，219 行）：`AppRoute.Create` 进白名单，Tab3 的 ➕ 从「只打一行日志」变成挂 `CreateSurface` 直达创建表单 —— **五个 Tab 全部可用**。壳只传 `createEnterSource` 一个 prop，**刻意不复刻** RN tabPress 那四个参数（Surface 自决落地页，§2.30 纪律）。⚠️ 真机抓到**类别性**缺陷并已修：`AppRoute.Create()` 无参 ⇒ 实例恒相等 ⇒ 去重不解除就「只能用一次」，ChatDetail 因每次带不同 characterId 而侥幸未暴露；后续每个无参路由都要配解除。✅ §2.41 已补微根/微栈/注册/bootstrap 机器断言；⚠️ §9.1 的 8 个设备/生命周期验收格仍全 `✎`。
- **Screen P1 + P2 代码已实现**（§2.35 / §2.42）：P1 落 AB 端点分流、归因、首屏缓存与会话埋点；P2 让 `showcase` 首次接入 Media3、有界播放器池、±1 窗口、RN Android buffer、动态 50MB cache、三轴播放门与声音开关。PR #39 最终 head `13cc633` 的 G1 全绿；⚠️ feed 无 showcase，真实视频/cache 失败/API24–33 层序/audio focus 四项仍 NOT RUN，故**不是 production-ready**。
- **Search P2 筛选器已实现**（§2.34，4 文件 723 行）：性别/排序/分级抽屉 + 二级标签栏，Search 达成完整对等。`SearchTagOrderTest` **逐条对拍 RN 的 144 行现成单测**。⚠️ 分级筛选的门是「非 GooglePlay && nsfw 开」，与 Settings 的 Limitless（只有 directApk）**不同轴** —— RuStore 在这里算可选。
- **不存在 / 未验**：Screen P2 已落代码但四项核心验收未跑，next-item/fade/firstInteractive/P3 仍无；Sentry、Qt 实际上报、core/feature 模块、**G3 nightly** 均无。生产路由白名单 **13 类**（§2.52 后）：三纯原生（Search/UserProfile/Settings）+ 十 Surface/带参目标（ChatDetail/MiniPhoneChat/Create/EditCharacter/SettingsSubScreen/EditProfile/Comments/Letter/GemsPurchase/UserCoins）。13 个业务 Surface 已启用 **8 个**（ChatDetail/Create/Settings/EditProfile/Comments/Notification/GemsSubscription/UserCoins），未启用 5 个：RoleCard/Onboarding/DeleteAccount/Widget（后两者的独立容器 iOS 已删、走 Settings 子屏，待核实 Android 是否同判）+ Debug 不计。各 Surface 的真机格仍在累积清单。⚠️ 待 owner：**性别筛选持久化静默失效**（§2.23.1）与 **Follow 出口无 Surface 可用**。

## 1. 波次状态

| 波次 | 内容 | 业务量 | 状态 | source_rn_sha | target_android_sha |
| --- | --- | --- | --- | --- | --- |
| W0 | 工程地基 + brownfield DebugSurface | 基建 | 🟢 完成 | `93d2c5551` | `4f191e8` |
| W1 | 平台契约 + auth + ChatDetailSurface gate | 基建 | 🟢 **完成**：契约层已收口且 CI 已验；**P9 已完成**（§2.36，白名单放开 + 桥桩回填）；§12 关闭链记为**已接受偏差**（owner 2026-08-17）。**冒烟部分兑现**（§2.37 + §2.39）：§9.1 **7 过 / 3 未跑**，业务分流项未验。语言那项的壳缺陷已修（§2.38）且**复跑 PASS（模拟器）**（§2.39） | `95760a6622424bc9be238e7790fdbf38fe7c7fb2` | —（PR #16/#17/#33 已并） |
| W2 | Bootstrap + 五 Tab shell + **Login** + **Home** | 约 10k 行 RN | 🟡 **主体已落地**：Login 邮箱链路已验、五 Tab + Home 首屏、筛选抽屉 + 冷启动种子均已并入 main（§2.20 / §2.23 / §2.24）。剩 banner / 彩蛋 / mp4 封面（banner 与彩蛋倾向留 RN Surface，方案 §8.1） | `95760a6622424bc9be238e7790fdbf38fe7c7fb2` | —（PR #19 / #20 已并） |
| W3 | **Profile** + **ChatList** + **Search** + Settings 列表/语言 | 约 19k 行 RN（最大） | 🟡 **业务面全部落地**：**Profile 全部批次完成**（P1–P7，§2.25–§2.29、§2.44、§2.45）；ChatList P1 + **P2 ChatMap**（§2.47）、Search P1/P2、他人主页、Settings 列表/语言均已落；**EditProfile 已完成静态预接、账号隔离与 Profile 刷新接力**（§2.43），会话/Surface 稳定性收口见 §2.46，但生产 policy 仍关闭。剩 EditProfile §9.1 设备矩阵与累积真机冒烟 | `93c8647f3`（§2.47 bump，含 §2.46 的 `5ba22c8bb`） | —（PR #21–#30、#41、#43、#44、#46 已并；ChatMap PR #42 已脱 draft 待并；#45 是 #46 的前身，因叠栈 base 删除被自动关闭） |
| W4 | **Screen/Media3** + 12 个 Surface + 系统能力 + OTA | 约 5.3k 行 RN + 系统 | 🟡 **进行中**：Screen P1 数据链（§2.35）与 P2 Media3 有界播放机制（§2.42，PR #39 / `6084df0`）已实现；P2 仍有真实视频/cache 失败/API24–33 层序/audio focus 四项 NOT RUN，故不 production-ready。Tab3 已接 `CreateSurface`（§2.40/§2.41）；剩 Screen next-item/fade/firstInteractive/P3、10 个未启用业务 Surface（含 W3 已预接但未放行的 EditProfile）、系统能力、OTA | `da4f65a04f50bc098c2df3bd9f8fbcc13018f7a5` | `6084df0d401e610d6fbcf26ce88c2bc494025927` |
| W5 | 对等 / 性能 / 三渠道发布切换 | 发布 | ⬜ 阻塞于 W4 | — | — |

**W0+W1 时间盒**：这两波不产出用户可见价值，目标是"够用就往下走"。若超过总工期 1/4,停下复审是否过度设计（方案 §8.5）。

## 2. 当前工程实况

### 2.1 当前工程范围

当前已包含 Gradle/三 flavor 工程、Compose 原生根、单 ReactHost/Fragment 宿主、
auth/network/router/surface/i18n 契约与测试、G1 workflow 及 `llmdoc/`。文件数会随当前
closeout 改动变化，不再用易漂移的计数或模板期文件清单描述现状。

### 2.2 工具链（已对齐，实测值）

| 项 | 当前值 | 来源 |
| --- | --- | --- |
| AGP | `8.11.0` | RN 自带 catalog |
| Kotlin | `2.1.20` | 同上 |
| compileSdk / targetSdk / minSdk | `36 / 36 / 24` | 同上 |
| Build Tools | `36.0.0` | 同上 |
| NDK | `27.1.12297006` | 同上 |
| **Gradle wrapper** | **`8.14.3`** | **AGP 8.11 不支持 Gradle 9；模板原为 9.4.1** |
| **Compose BOM** | **`2025.04.01`** | **实测可与 Kotlin 2.1.20 共存（模板原为 2026.02.01）** |
| Gradle DSL | Groovy | 方案 ADR-004；`.kts` 已全部改写 |
| JDK | 17（daemon 跑 21，编译 target 17） | — |

### 2.2.1 实测的 Gradle task 名（方案 §5.4「命令名不靠猜」）

```
./gradlew projects
./gradlew :app:assembleGooglePlayDebug     # → com.tipsyturbo.app
./gradlew :app:assembleDirectApkDebug      # → ai.lightspeed.tipsy
./gradlew :app:assembleRuStoreDebug        # → com.tipsytavern.app
./gradlew :app:testGooglePlayDebugUnitTest
```

注意 RN 的 Gradle plugin 额外引入了 **`debugOptimized`** build type，故实际 variant 数是
`3 flavor × 3 build type`（debug / debugOptimized / release），比方案 §5.1 假设的多一档。

### 2.2.2 W0 踩过的坑（都表现为同一句无用报错）

RN/Expo 生态多处假设 `Gradle root = <rn-project>/android`，本仓布局会让它们落到错误目录。
**症状统一是 `Process 'command 'node'' finished with non-zero exit value 1`，真实 stderr 被 Gradle 吞掉。**
排查方法：在报错任务的 workingDir 手工复现那条 node 命令。

| 出处 | 错误推导 | 处理 |
| --- | --- | --- |
| `expo-constants` `createExpoConfig` | 用 `rootProject.projectDir` 当 projectRoot | doFirst 重定向（配置期改会被写回） |
| `expo-updates` `create*UpdatesResources` | 用 `rootProject.projectDir.parentFile` | **禁用任务**（其 Property 执行期已 final，改不动；OTA 属 W4） |
| `autolinkLibrariesFromCommand` | workingDirectory 默认取 Gradle root 的父目录 | 显式传 `tipsy-app` |
| 第三方模块（apple-authentication / skia 等） | 从 `rootProject.projectDir` 向上找 node_modules | `ext.reactNativeAndroidRoot` 指向 **RN 包根** |
| `react.cliFile` | 默认 RN `cli.js`，但 Expo 工程无 `@react-native-community/cli` | 改 `@expo/cli` + `bundleCommand=export:embed` |

**另一个已知限制**：`expoAutolinking.exclude` 对 `expo-updates` 等无效 —— `AutolinkingCommandBuilder`
把多值 `--exclude` 与 `--project-root` 拼进同一 argv，variadic 参数会吞掉后续 flag
（实测 `--exclude` 在 `--project-root` 之前时不生效）。故 W0 的隔离用「禁用任务」实现。

**磁盘**：debug 默认出四个 ABI，单 flavor 中间产物可达数 GB；曾因磁盘写满导致
`packageRuStoreDebug` 失败且**不提示空间不足**。现 debug 只出 `arm64-v8a`。

### 2.3 环境

| 工具 | 状态 |
| --- | --- |
| `tipsy-app/node_modules` | ✅ 已装（`npm ci`，1812 包，patch-package 与 hermes-O0 patch 均已应用） |
| 根 `node_modules` 符号链接 | ✅ 已建（不入库，见 `.gitignore`；换机器/CI 需重建） |
| `sdkmanager` | 曾观察到不可用（未装 cmdline-tools）。W0 需实测并提供明确环境检查与 CI 安装路径 |
| emulator image | 未固定。W0 记录实际可用的 API 24 / API 36 image |
| Node / npm | ✅ 实测 node `v22.22.3` / npm `10.9.8`；settings.gradle 有四级显式解析（见方案 ADR-004 第 3 条） |
| 从 Android Studio 启动 sync | ✅ **2026-08-14 根治**（此前 08-10/11/13 三次复发均只治症状）。需**两层**，缺一层即失败：① `local.properties` 写 `tipsy.node.executable`（用 fnm 的 `aliases/default` 路径，不是 `which node`）；② **用 `/Applications/Android Studio (Tipsy).app` 启动**（Dock 换成它）。**两者都不入库，换机器需重做**（重建方式见下）。<br>**根因**：`exec` 解析 program name 读 **native process environ**，而 Studio 的 "shell environment loaded" 只补 **JVM 层 env map** —— 故报错里 JVM `$PATH` 含 node、fork 仍 `error=2`。且 runningboardd 按 bundle id **缓存** native 启动环境、缓存**跨 ⌘Q 存活**。<br>⚠️ **`launchctl setenv` / LaunchAgent 从原理上治不了**（只改「launchd 往后发什么」，无法失效已缓存那份）；旧 LaunchAgent 已退役到 `~/Library/LaunchAgents/disabled/`。**别再往那个方向试。**<br>**包装 app 重建**：bundle 里放 `Contents/MacOS/launch-studio`，首行 `#!/bin/zsh -l`（登录 shell 重建 PATH 后 `exec` 真 `Android Studio.app/Contents/MacOS/studio`），`chmod +x` + `lsregister -f`。等价临时手段：终端跑 `/Applications/Android\ Studio.app/Contents/MacOS/studio &`。日志 `$TMPDIR/tipsy-studio-launcher.log`。<br>验证包装 app 必须用 Finder/`osascript` 启动，**不能用 `open`**（会泄漏当前 shell 环境，测出假通过）。查证用 `ps eww <studio-pid> \| tr ' ' '\n' \| grep ^PATH=` 看进程**实际**的 PATH，别看 `launchctl getenv`。<br>缺第二层时 `settings.gradle` 会**在 1 秒内明确报错**并给出三步修复。详见方案 ADR-004 第 3 条 |

### 2.4 已经不用做的事（RN 侧已就绪，实测）

`tipsy-app` 里已有 **55 个文件**完成壳适配（iOS 壳一年沉淀）：13 个 Surface 入口组件、`SurfaceToastHost`、`TipsyHeader` 栈底 `popSurface` 兜底、`useShellSurfaceRefocus`、`useChatNavigation` 壳分支、`shellGemsEntry`/`shellTaskEntry` 跨栈出口、`axios.ts` 的 401/402 桥上抛、`config_persist` nsfw 镜像接力、`recommendTracking` 壳 outbox、`api.ts`/`lane.ts` 壳 API 地址。

**Android 只要提供能让 `isShellHost()` 返回 true 的 Kotlin 桥，这些全部自动生效。** 另有约 4,500 行现成 RN 测试可作对等 fixture（方案 §8.2）。

### 2.4 DebugSurface gate 实测结果

环境：**API 37 / arm64-v8a 模拟器**，`directApk` flavor debug 包。

| 验收项 | 结果 |
| --- | --- |
| 原生根先渲染（不依赖 RN） | ✅ |
| **离线内嵌 bundle** 挂载 Surface | ✅ `[Surfaces:debug] DebugSurface rendered` —— 无 Metro，证明 release 可离线 |
| **Metro 直连**挂载 Surface | ✅ `isMetroRunning=true`，Metro 侧收到 bundle 请求（HTTP 200 / 4.3MB） |
| 返回键回原生根、进程不退出 | ✅ |
| **50 次挂载/卸载** | ✅ 无崩溃、PID 不变；PSS 199→208MB 但增速递减（前 10 轮 +4.4MB，后 10 轮 +0.4MB），GC 后回落至 204MB |
| **单 Runtime 不变量**（ADR-003） | ✅ 50 轮后 GC：`Activities=1`、`ViewRootImpl=1`、`Views=19`，**无滞留 Activity/View** |
| 旋转（横↔竖，Surface 挂载中） | ✅ 无崩溃，Surface 存活并重渲染 |
| 进程重建（force-stop 后重启） | ✅ 新 PID、`rootTag` 归 1、Surface 可再挂 |

**gate 捕获的两个真实缺陷**（构建期与静态检查都发现不了）：

1. **`MainActivity` 必须实现 `DefaultHardwareBackBtnHandler`**。`ReactFragment.onResume`
   → `reactDelegate.onHostResume()` 内部把宿主 Activity 强转成该接口，不实现直接
   `ClassCastException` **崩在 onResume**。只有真机挂载才暴露 —— 这就是 gate 的价值。
2. **Metro 端口必须用 `resValue` 注入**。RN 从 `R.integer.react_native_dev_server_port`
   读端口（`AndroidInfoHelpers.kt`），默认 8081。**debug source set 的 `res/values` 放同名
   integer 不会胜出**（实测 aapt2 dump 仍是 8081），必须 `resValue` 注入 app 自己的资源表。
   漏掉的表现是 `isMetroRunning()` 永远探测 8081 → 静默回退内嵌 bundle，
   **「改了 JS 却不生效」且不报错**。端口取 8083（ADR-003）。

### 2.3.1 API 24 冒烟（minSdk，已完成）

方案 §5.4 的设备矩阵要求 minSdk 也过一遍。环境：**API 24 / arm64-v8a**
（`Api24_Smoke` AVD，google_apis 镜像）。

| 项 | 结果 |
| --- | --- |
| 安装 + 启动 | ✅ 无崩溃 |
| 原生根渲染 | ✅ |
| Surface 挂载 | ✅ `DebugSurface rendered` —— 无 SoLoader / UnsatisfiedLink 问题 |
| 返回 + 10 轮开关 | ✅ 无崩溃 |
| 单 Runtime 不变量 | ✅ GC 后 `Activities=1`、`ViewRootImpl=1` |
| PSS | 168MB（比 API 37 的 204MB 更低） |

**结论：minSdk 24 可行**，RN 0.81 + 新架构在 Android 7.0 上正常工作。

### 2.5 模拟器上的 fixture —— ⚠️ **不可用作覆盖升级证据**（W1-P1 实测订正）

> **本节此前的记录是错的**，W1 开工核对时发现。原记录称它有 `files/mmkv/` 真实数据、
> 可作 W1 覆盖升级 fixture —— **两点都不成立**。

`emulator-5556`（Pixel_10 / API 37）上装有 `com.tipsyturbo.app` versionName **1.4.4**
（firstInstall 2026-07-29，lastUpdate 2026-08-05），但实测：

| 项 | 实测结果 |
| --- | --- |
| **数据目录** | **不存在**。`run-as` 与 shell 均报 `couldn't stat /data/user/0/com.tipsyturbo.app` |
| 有无 MMKV 数据 | **没有** —— 原记录说的 `mmkv.default` / `chat-list-cache` / `for-you-cache` 均无从读取 |
| **签名** | **`CN=Android Debug`** —— 是 **debug 签名**，不是现网发布签名 |
| 有无内嵌 bundle | **无**（`assets/` 里没有 `index.android.bundle`）→ 靠 Metro 加载 |
| launcher activity | **无**。只声明了 `exp+tipsy-app:` scheme 的 `.MainActivity`，`resolve-activity` 返回 `No activity found`，无法从桌面或 `am start` 正常拉起 |
| dev-launcher 迹象 | `classes2.dex` / `classes3.dex` 命中 `DevLauncher` 符号 |

**结论：这是一个 Expo dev build（debug 签名 + 无内嵌 bundle + dev-launcher），
不是现网 release 包，且从未产生用户数据。**

**对 W1 的影响（重要）**：
1. **P3 三渠道覆盖升级没有现成 fixture** —— 原以为「模拟器上已有现网包可直接用」，
   实际必须**另行取得三渠道的真实 release 产物 + 匹配签名**。这不改变结论
   （方案 §6.1 早写明「debug 签名重装不构成证据」），但**把外部阻塞项前置了**：
   没有真实产物，P3 一步也做不了。
2. **P2 的 MMKV 直读路径目前无真实数据可验** —— 需要先有一个真登录过的现网包，
   或由发布 owner 提供一份脱敏的 MMKV 数据样本。
3. 该 APK 仍可留着（179MB，dev build），但**只能用来看包结构**，
   不能当升级来源。**不要再把它当 fixture 引用。**

W0 用 `directApk` flavor 避免包名冲突这一点仍然有效。

### 2.6 W0 剩余项

1. ~~DebugSurface gate~~ ✅ 已完成，见 §2.4
2. ~~Metro 端口 8083 + cleartext 配置~~ ✅ 已完成
3. ~~merged manifest snapshot 测试~~ ✅ 见 §2.8
4. ~~lint 接入~~ ✅ 见 §2.9（detekt 仍未接）
5. ~~`sdkmanager` 可用性~~ ✅ 已装 cmdline-tools 12.0（`~/Library/Android/sdk/cmdline-tools/latest`）
6. ~~API 24 冒烟~~ ✅ 见 §2.3.1
7. ~~CI 已写但未激活~~ ✅ **已激活并首次真绿**（2026-08-10），见 §2.10
8. detekt 未接（lint 已是硬门，detekt 属增量）。
7. ~~release 产物验证~~ ✅ 已完成，见 §2.7

### 2.7 release 产物验证（已完成）

`assembleDirectApkRelease` 通过（R8 + 两个 ABI，约 3 分钟，产物 168MB **unsigned** ——
W0 刻意不配签名，无发布能力）。逐项断言 debug 配置未泄漏：

| 检查 | 结果 |
| --- | --- |
| `usesCleartextTraffic` | ✅ `false`（debug 的 `true` 未泄漏） |
| `DevSettingsActivity` | ✅ 不存在 |
| dev server 端口 | ✅ `8081`（默认值；8083 只在 debug 生效） |
| `android:largeHeap`（§2.3 必须项） | ✅ `true` |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | ✅ 已移除（Agora patch 生效） |

**过程中修掉两个 release 专属问题**（debug 完全不会暴露）：

1. **`proguard-rules.pro` 不存在** —— `app/build.gradle` 引用了它但文件没建，
   R8 直接报 `Supplied proguard configuration does not exist`。现已补上壳自己的
   keep 规则（Application/Activity 反射实例化、`@ReactMethod`、`@DoNotStrip`、
   Expo 模块、Sentry 需要的 SourceFile/LineNumberTable）。
2. **R8 堆不足 + 两个 Missing class**。50+ 个 RN/Expo 模块在 2G 堆下 R8 OOM
   （报 `Compilation failed to complete` 且 daemon 提示堆耗尽，跑了 13 分钟才失败）；
   提到 6G 后暴露真实错误：`ThrowableExtension`（Bazel desugar 残留，Agora 日志引用）
   与 `DevLog`（QT SDK 调试类，release AAR 未含）。两者运行时都不需要，用 `-dontwarn`
   而非 keep。**注意别无脑 `-dontwarn **` 掩盖后续真实缺失。**

### 2.8 merged manifest 快照测试（已完成）

`app/src/test/.../MergedManifestTest.kt`，5 条断言全绿：

1. **三渠道 applicationId 钉死** —— 改错会破坏覆盖升级
2. **release 不含开发期组件**
3. 有 `intent-filter` 的组件必须显式声明 `exported`
4. 不含已排除的敏感权限（`MEDIA_PROJECTION` / `ACCESS_FINE_LOCATION`）
5. release 保留 `largeHeap`

**这个测试立刻抓到一个真实缺陷**：`androidx.compose.ui.tooling.PreviewActivity`
以 **`exported=true`** 出现在 **release** manifest 里 —— 生产包对外暴露一个调试
Activity，而普通构建完全不报错。

根因链：`expo-dev-client` 在 `tipsy-app/package.json` 里是 **`dependencies`**
（不是 devDependencies）→ autolinking 把 `expo-dev-launcher` / `expo-dev-menu`
接进 release runtime classpath → 带入 `androidx.compose.ui:ui-tooling` →
其 manifest 的 PreviewActivity 被合并。

W0 的处理：`app/src/release/AndroidManifest.xml` 用 `tools:node="remove"` 兜底。
**根治**要么把 `expo-dev-client` 移到 devDependencies（属 `tipsy-app`，本仓不得改），
要么让 autolinking 按 variant 排除 dev 模块 —— 都超出 W0 范围。
**若将来 release 里再出现别的开发期组件，先查这条链，别只加 remove。**

另外权限总数已达 **51 条**（几乎都是 autolinked SDK 传递引入的），其中
`ACCESS_ADSERVICES_*`、`CAMERA`、`RECORD_AUDIO`、`USE_BIOMETRIC` 等会直接影响商店审核 ——
W1 起每次新增依赖都应看一眼这个测试的 diff。

### 2.9 lint 硬门（已完成）

`abortOnError=true` + `warningsAsErrors=true` + `sarifReport`（CI 可喂 GitHub code scanning）。
`checkDependencies=false` —— 50+ 个第三方 RN 模块的告警不由本仓负责。

**lint 抓到一个我自己写的真实缺陷**：`android:useBoundsForWidth`（`withAndroidStyles`
移植项）是 **API 35** 新增属性，而 minSdk=24 —— 报 `NewApi`。已按资源限定符拆分：
`values/styles.xml` 留空壳 style，`values-v35/styles.xml` 放该属性。
**这是真修复，不是记进 baseline。**

#### ⚠️ baseline 对 app 模块外的文件**不可移植**（2026-08-10 CI 首跑实测订正）

> 原记录称 baseline 有 **19 条**。那个数字只在本机成立 —— CI 上只有 5 条生效。

lint 把 app 模块**外**的文件（`gradle/libs.versions.toml`、
`gradle/wrapper/gradle-wrapper.properties`）的 location 记成
`$HOME/Developer/Tipsy-Android/...` 这种**机器相关的绝对路径**，
CI 的 checkout 在 `/home/runner/work/...`，**一条都匹配不到**。

| 环境 | baseline 过滤 | 新增 | 结果 |
| --- | --- | --- | --- |
| 本机（订正前） | 18 | 0 | ✅ 绿 |
| CI（订正前） | 5 | **13** | ❌ 硬门失败 |

13 + 5 = 18 正好对上 —— 不是新增了问题，是那 13 条在 CI 上失效。

**这类缺陷只有真在 CI 跑一次才会暴露**：本机永远绿，因为路径恰好匹配。
§2.10 记的「本机模拟整条 1m59s」模拟不了它。

**处理**：那 13 条全是「有新版可用」三类，与 §3.3 **刻意钉死工具链**的决定
直接冲突（版本是 RN 0.81.4 的兼容事实，不是选型；`mmkv` 与 `coroutines` 更是
与 RN 侧的**耦合约束**，升了会静默出错）。故在 `app/build.gradle` 显式
`disable` 掉 `GradleDependency` / `AndroidGradlePluginVersion` /
`NewerVersionAvailable`，**而不是重新生成一份仍然不可移植的 baseline**。

baseline 重新生成后只剩 **5 条**，全部是 app 模块内的相对路径、可移植：
`RedundantLabel` / `ChromeOsAbiSupport` / `MergeRootFrame` / 2 条 `UseTomlInstead`。
**本机与 CI 现在都是「5 条过滤、0 新增」。**

⚠️ **代价（明确写下，避免日后当成没人提醒过）**：真正需要关注的依赖升级
**包括安全更新**不再由 lint 提醒，改为跟随 RN 侧节奏人工评估。
若要恢复提醒，应改用「只对 app 模块内依赖生效」的方式，
**不要把 baseline 退回不可移植状态**。

**baseline 仍是技术债台账而非豁免** —— 剩下 5 条的清理属后续波次。

顺带修掉：`expo-dev-client` 声明了 `org.webkit:android-jsc:+` 这个可选依赖，
但本工程用 Hermes、`jsc-android` 未安装也无对应仓库，任何需要解析它的任务
（实测 lint 的 `generate*LintModel`）都会失败。已在根 `build.gradle` 全局排除。

### 2.10 G1 fast gate CI（**已激活**，2026-08-10）

`.github/workflows/android-ci.yml`。与 `tipsy-app` / `tipsy-iOS` 的 `ci.yml` **分开** ——
那些是 agentic workflow（issue/PR 智能体），本文件是纯构建门禁。

序列：**lint（硬门）→ assemble googlePlayDebug → release manifest → 单测
→ `:tipsy-auth` 桥单测 → `skipped=0` 守卫**。

**首次真绿**：[run 31373202424](https://github.com/Lightspeed-Intelligence/tipsy-Android/actions/runs/31373202424)
—— 22 步全过，**36 分钟**（冷缓存）。核实的输出：

```
tipsy-app pin=a4eb9055d actual=a4eb9055d
Lint found no new issues (and 5 errors filtered by baseline)
MergedManifestTest: 5 条，跳过 0 条
LiveAppSafetyTest: 3 条，跳过 0 条
```

`pull_request` / `push` 自动触发已生效（开 PR #11 时自动起了一次 run，
非手动触发）—— **G1 从此构成真门禁**。

> ⚠️ **36 分钟只有一个数据点**，且是冷缓存首跑。后续有 Gradle 缓存应更快，
> 但**不做承诺**。原记录的「本机模拟 1m59s」不可用作 CI 耗时参考 ——
> 它模拟的只是 Gradle 那几步，不含 checkout / `npm ci` / NDK 安装。

#### 激活过程抓出三个缺陷（**全是本机绿、CI 红的类型**）

| # | 缺陷 | 症状的迷惑之处 |
| --- | --- | --- |
| 1 | `PAT_TOKEN` 存了**空值** | `gh secret set NAME` 无值时靠 TTY 弹提示；非交互环境从空 stdin 读了空串，**且正常退出** |
| 2 | `git submodule sync` **覆盖** URL → 仍走 SSH | 报 `Permission denied (publickey)`，**看着像凭据没配**，实际凭据正常、只是没被用上 |
| 3 | lint baseline 用 `$HOME` 绝对路径 | 见 §2.9 订正 |

**#2 的根因值得记住**：`sync` 会把 local config 的 url 覆盖回 `.gitmodules`
里的值（SSH URL）。W0 原先的顺序是「先 `git config` 设 HTTPS → 再 `sync`」，
等于自己撤销刚设的值。已本地复现验证：调换顺序后走 HTTPS（用假 token 报的是
HTTPS 认证失败而非 `publickey`，证明链路确实换了）。

iOS 用全局 `insteadOf` 改写，**不依赖 local config**，所以不受影响 ——
「与 iOS 同构」这个判断**掩盖了差异**：更窄的做法需要更小心的顺序。

顺带加两条断言：子模块 SHA 必须等于 pin、工作树非空（`package.json` 存在）。
`git submodule update` 在某些失败模式下会「成功」但留下空目录，
那样要到 `npm ci` 才炸，**报错离根因很远**。

**这三个都是 W0 遗留**（#2/#3）或配置环节引入（#1），在 CI 真跑之前一直藏着。
方案 §5.4 的「`NOT RUN` 不等于通过」在这里得到三次实证。

#### `:tipsy-auth` 桥单测此前**完全不在 CI 里**（已补）

那 15 条测试住在 submodule 里，但**它是壳的一部分** —— 契约、registry、
主线程切换都由本仓的壳消费。其中 `LiveAppSafetyTest` 守的是本项目**最高危**的
失败模式：模块会被 autolink 进现网三个 RN 包，那里没有壳、不注册 provider，
此时 `isShellHost()` 必须为 false；若为 true，现网 App 把 auth 交给一个不存在的壳
→ **直接掉登录**。这条断言不进 CI 等于没有防线。

`skipped=0` 守卫从只覆盖 `MergedManifestTest` 扩成同时覆盖两个 suite，
并加了 `tests=0` 检查（一条没跑也是假绿）。守卫逻辑已验证在
「文件缺失 / 有跳过 / 零测试」三种场景下**确实会失败** ——
一个只会通过的守卫没有价值。

`pull_request` 的 `paths` 含 **`tipsy-app`**：桥模块改动不动本仓任何 path，
但 pin 前进一定伴随这个文件变化。漏了它会让「只改桥」的 PR 不触发 CI。

**范围取舍**：assemble 只跑单个 flavor，三 flavor 全量与 release 打包留 G3 nightly ——
PR 门要快。**代价是 flavor 专属与 release 专属问题不由 G1 拦**（§2.8 抓到的 release
暴露 PreviewActivity 正属后者），**nightly 必须补上三 flavor + release 全量**。

**过程中修掉一个「假绿」隐患**（正是 §5.4「NOT RUN 不等于通过」警告的情形）：
`MergedManifestTest` 原先把 variant 写死成 `directApkRelease` / `directApkDebug`，
而 CI 只 assemble `googlePlayDebug` —— 实测 **5 条断言里 4 条被 `assumeTrue` 跳过，
而跳过在 JUnit 里算通过**，等于断言静默失效。两处修正：

1. 断言改为「任一同 build type 的 variant」，与 flavor 解耦
2. 同时识别 AGP 的**两个**输出目录：`merged_manifest/`（单数，`process*MainManifest`
   产物，只跑 manifest 任务即有）与 `merged_manifests/`（复数，打包产物）——
   只认复数那个会导致「只跑 manifest 任务时断言被跳过」

CI 侧再加一道防线：显式校验 `MergedManifestTest` 的 `skipped=0`，跳过即失败。

**前置条件：本仓自己的 `PAT_TOKEN` secret —— ✅ 已配（2026-08-10）。**

⚠️ **那个 PAT 不在 @WishQi 的个人账号下** —— fine-grained 与 classic 两个列表均为空。
但 `tipsy-iOS` 的 `eas-build.yml`（唯一真正用它拉子模块的 workflow）**2026-08-07 仍成功运行**，
说明该 PAT 有效且属于 org 内其他成员（org 共 30 人）。
所以要么向持有者取得该值，要么新建一个有 `tipsy-app` 读权限的 token。

secret 是**按仓库**隔离的，没有跨仓引用语法 —— `tipsy-iOS` 上的同名 secret 在本仓
workflow 里不可见；内置 `GITHUB_TOKEN` 也只对本仓有权限，读不了私有的 `tipsy-app`。
实测本仓可继承的 org 级 secret 只有 `ANTHROPIC_API_KEY` / `ANTHROPIC_BASE_URL` /
`FEISHU_LLMDOC_WEBHOOK_TOKEN`，**不含 `PAT_TOKEN`**（它是 `tipsy-iOS` 的 repo secret）。

**与 `tipsy-iOS` 同构** —— 它的 `ci.yml` / `eas-build.yml` 都是用 repo 级 `PAT_TOKEN`
改写 submodule URL 走 HTTPS(runner 无 SSH key)。该 PAT 是 **classic token**。

⚠️ **`tipsy-app` 上那个只读 deploy key(`eas-submodule-ro`)状态是 `Never used`** ——
它是当初为 EAS 建的残留,**不是在用的链路**。iOS 仓的 secrets 里也没有任何 SSH 私钥。
别误以为 SSH/deploy key 那条路在本环境验证过。

#### 凭据现状（2026-08-10 订正，与原记录不同）

> 原记录称「同一 PAT 值在 `tipsy-iOS` 与本仓各存一份，轮换必须两个仓都改」。
> **这条已不适用。**

本仓的 `PAT_TOKEN` 是**独立签发**的 classic token（只勾 `repo` scope，够拉私有子模块），
**与 `tipsy-iOS` 的同名 secret 不是同一个值**。

- ✅ **轮换本仓这个不影响 iOS，反之亦然** —— 比共用一个值更清晰、影响面更小
- org secret 需 `admin:org` scope（当前账号没有，实测 403），故仍走 repo secret
- 那个「iOS 的 PAT 不属于 @WishQi 个人账号」的旧结论不再是障碍：**不需要去找它的持有者取值**

⚠️ **`gh secret set` 有一个会导致明文泄漏的坑（2026-08-10 真踩过）**：

第一个参数是 secret 的**名字**，值必须经**交互提示或 stdin** 传入。
把值直接写成第一个参数会创建一个**以 token 明文为名**的 secret ——
而 **secret 的名字是可读的**（值不可读），等于当场泄漏。

且 `gh secret set NAME` 不带值时靠 **TTY** 弹提示：**非交互环境下它从空 stdin
读到空串、存进去、并正常退出**（退出码 0、无输出），看着像成功。
症状是 CI 里 `PAT_TOKEN:` 后面空白。

正确写法：
```bash
pbpaste | tr -d '\n' | gh secret set PAT_TOKEN --repo <owner>/<repo>
```
`tr -d '\n'` 必要 —— 复制时易带尾随换行，token 混入换行会认证失败且报错不提示原因。

泄漏那次已吊销重签、删除错误条目，并验证旧 token 返回 401。

技术细节：`.gitmodules` 用 SSH URL 而 CI 只有 HTTPS token，故 workflow 不用 checkout 的
`submodules` 选项，而是手工把 submodule URL 换成带 token 的 HTTPS（只改本地配置，
不写进 `.gitmodules`）。auth 形式用 `x-access-token`（已实测对私有仓有效）。
**缺该 secret 时 workflow 明确报错并说明原因，不静默跳过。**

**不得用 `--depth 1` 拉子模块**（这条经验来自 `tipsy-iOS` 的 `eas-build.yml`）：
子模块 pin 常滞后于 `tipsy-app` 的 main tip —— 实测当前 pin **落后 `origin/main` 175 个
commit**，浅拉只能拿到 tip、取不到 pin 的那个 commit，CI 会直接在子模块那步失败。

与 iOS 的一处有意差异：iOS 用 `git config --global ... insteadOf` 全局改写所有
`git@github.com:` 前缀；本仓只改 `submodule.tipsy-app.url` 一项，范围更窄、不影响
其他 SSH 操作。

⚠️ **但两者不是「行为等价」（原记录如此写，已订正）**：本仓的做法**依赖 local
config**，而 `git submodule sync` 会把它覆盖回 `.gitmodules` 的 SSH URL ——
所以**必须先 `sync` 再 `git config`**，顺序颠倒就静默退回 SSH。
iOS 的 `insteadOf` 不依赖 local config，没有这个顺序约束。
「与 iOS 同构」的判断曾**掩盖了这个差异**，见 §2.10 缺陷 #2。

另：`cmake` 版本已钉进 `libs.versions.toml`（原先只有 `ndk`）。AGP 默认挑「已装的最高版」，
本机与 CI 不一致会产生难复现的构建差异。workflow 从 catalog 读取并做空值检查 ——
grep 未匹配时 `cut` 输出空串**不报错**，静默装错版本比直接失败更糟。

### 2.11 W1-P0：auth 桥接通（已完成）

**W1 的开关打开了。** RN 侧 `isShellAuthHost()` 返回 true → 那 55 个文件里已存在的
壳适配分支自动激活（方案 §7.2）。API 24 实测:

```
bridge probe: {"present":true,"isHost":true,
               "hasGetValidToken":true,"hasPopSurface":true,"lang":null}
```

(`lang: null` 是**正确的** —— 语言真值属 P5，此时壳无意见、RN 沿用自己的判定。)

RN 侧 `modules/tipsy-auth/android/`（分支 `feat/tipsy-auth-android`）三层结构与 iOS 同构：
契约拆四个接口(Auth/Navigation/Lifecycle/Env)、registry(壳注册 + 事件广播)、
Expo Module DSL(12 个必须方法)。iOS 用 NotificationCenter 广播，Android 无等价物，
改用进程内 `CopyOnWriteArrayList` 监听器。

**最高危项已用测试钉死**：模块合并后会被 autolink 进**现网三个 RN 包**，那里没有壳、
不注册 provider。此时 `isShellHost()` 必须为 false、与「模块不存在」等价 ——
否则现网 App 把 auth 交给不存在的壳会**直接掉登录**。`LiveAppSafetyTest` 专测这条。
单测 9 条全绿(skipped=0)。

**两条实现纪律**（都是从 iOS 教训来的）：
1. **未实现项绝不静默 no-op** —— debug 抛 `NotImplementedError`，release 记 error
   日志并继续。静默 no-op 的典型症状是「点了没反应」，不报错不崩溃，只能靠用户
   反馈发现(iOS 在 ChatDetail 与 Comments 真实踩过)
2. **严格区分「返回 null」与「未实现」** —— `getValidToken()` 返回 null 是合法业务态
   (当前未登录)；`requestLogin()` 未实现是能力缺失，必须可见

**provider 注册时机是个坑**：RN 侧 `isShellAuthHost()` 会**缓存首次结果**(它在高频
render 路径上被调用)，注册晚于首个 Surface 会让 JS **永久**认为不在壳内 ——
这类 bug 只在冷启动竞态下出现。故注册放在 `Application.onCreate` 内、生命周期分发之前。
且壳必须**自持强引用**：registry 侧是弱引用(对齐 iOS 的 `weak var`)，被回收会让
`isShellHost()` 悄悄变回 false。

顺带核实一处 W0 遗留疑问：`reactHost` getter 每次调 `createReactHost` 看似会新建实例，
实际 `ExpoReactHostFactory` 内部有 `if (reactHost == null)` 缓存(已核实
`ExpoReactHostFactory.kt:85`)，**单 Runtime 不变量成立**。

### 2.12 MMKV 互操作性已验证（W1-P2 机制部分）

**§2.4 迁移路径最大的技术未知项已消除**：壳的 Kotlin 代码能读到 `react-native-mmkv`
写的数据。API 24 真机 instrumented test **3/3 通过**（skipped=0）。

| 事实 | 值 | 来源 |
| --- | --- | --- |
| MMKV 目录 | `filesDir/mmkv` | `HybridMMKVPlatformContext.getBaseDirectory()` |
| 默认实例 id | `mmkv.default` | `MMKVFactory.nitro.d.ts` 的 `@default` |
| **原生库** | **`io.github.zhongwuzw:mmkv:2.2.4`** | `react-native-mmkv/android/build.gradle:142` |

⚠️ **原生库是 fork,不是腾讯官方 `com.tencent:mmkv`**（但包名仍是 `com.tencent.mmkv`,
所以 import 看着像官方的）。版本已钉进 `libs.versions.toml` 并**显式压制
lint 的 NewerVersionAvailable**（它建议升到 2.4.1）—— 这个版本号是与 RN 侧的
**耦合约束**,不是"越新越好"的普通依赖。升了它壳可能读不了 RN 的文件,
**且不报错**,只表现为用户升级后掉登录。

**三种历史形态解析**（裸串 / `{token}` / `{state:{token}}`）单测 10 条 + 真机往返 3 条全绿。
一个易错边界已覆盖:`JSONObject.optString` 遇 JSON null 返回**字面量 `"null"`**,
不特判会把它当成一个叫 `null` 的 token 存进去 —— 静默错值,后续请求全 401 且难反推。

**这个验证不能过度解读**（按方案 §5.4 纪律）:
- ❌ **不**证明能读**真实历史数据** —— 需真登录过的现网包,当前拿不到（§2.5）
- ❌ **不**构成覆盖升级证据 —— 需真实签名,**已决定推迟到上线前**

它证明的是**机制**。P2 状态是「机制已验证,真实数据待验」,**不是完成**。

### 2.13 W1-P1：auth 契约（closeout 已实现；组合验证已于 §2.22 补上）

**壳成为 token 的唯一刷新者与持久化者。** 原落地 checkpoint 的单测
**62 条全绿（skipped=0）**，人工门禁四步全过；§2.18 的 correctness closeout
随后修改了同一实现，当前组合结果尚未动态验证。

落地的 6 个类（`shell/auth/` + `bridge/`）：

| 类 | 职责 | 关键约束 |
| --- | --- | --- |
| `Jwt` | payload 解析 + 过期判定 | 逐行对齐 RN `lib/auth/jwt.ts`，阈值 **5 分钟** |
| `ShellTokenStore` | token 真值 + single-flight 刷新 | 见下方三条语义 |
| `Generations` | 双轨闸门 | `auth` / `mutation` **互不替代** |
| `AuthStateHub` | 登录态订阅 | W2 五 Tab 直接用 |
| `MmkvTokenPersistence` | 读写 `token-storage` | 裸字符串形态，只碰这一个 key |
| `RefreshTokenApi` | `POST /auth/refresh_token` | `token` header（非 Bearer）+ envelope |

**三条照搬 RN 而非重新设计的语义**（偏差会产生只在特定时间窗出现的问题）：

1. **已过期 token 不走刷新**。RN `isJwtExpiringSoon` 的条件是
   `exp - now > 0 && < 300` —— 已过期返回 **false**。持久层保留原值且不主动刷新，
   但壳的 `getValidToken()` 按 Android/iOS bridge 契约返回 null；否则 WebView/SSE 等
   不经过 axios 的消费者会直接发送失效值。Native HTTP 在起飞前再做一次校验，覆盖
   await 后换号/恰好过期窗口。
2. **刷新失败但旧 token 未过期 → 返回旧 token**（`jwt.ts:127-129`）。
3. **不重试**。RN 侧没有重试，加了会让登录态在网络抖动时行为分叉。

**401 归属判定是本步最高危项**：`notifyServerAuthRejectedForToken` 只在被拒 token
仍是当前 token 时登出。旧账号迟到的 401 若无条件登出，会把刚登录的新账号踢下线 ——
用户看到"刚登录就被登出"且无法复现。已用测试钉死，另有一条断言**任何日志都不含 token**。

**`logout()` 与 `clearToken()` 刻意不同**：前者清 token + 收栈 + 广播一次 loggedOut；
后者只清 token。合并两者会让删号流程在中途被强行弹栈。

#### 顺带修掉的桥缺陷（跨仓，tipsy-app 侧）

`TipsyAuthModule` 的 6 个 UI/导航方法在 `AsyncFunction` 里**直接调 provider，
无主线程约束** —— 而 Expo 的 `AsyncFunction` 默认在后台线程执行。
iOS 契约对同组方法全标了 `@MainActor`（已核实 `TipsyAuthModule.swift`），Android 漏了。
这是 PR #1614 的审查机器人提的 REQUEST_CHANGES，此前未处理。

处理：契约加 `@MainThread` 标注，桥侧统一经 `dispatchOnMain` 切主线程，
新增 `MainThreadDispatchTest`（6 条）。**用 `withContext` 而非 `Handler.post`** ——
后者发射后不管，JS 的 await 会在导航真正发生前 resolve。

⚠️ **`logout()` 在契约里不是 `@MainThread`**（它主要做存储清理），但它要收栈，
所以**自己切主线程**。桥的 `onMain` 只覆盖标注过的方法，不会替它做。

#### 三处踩到的「假绿色」诱惑（都已按方案 §5.4 拒绝）

| 遇到什么 | 诱惑 | 实际做法 |
| --- | --- | --- |
| `android.util.Base64` JVM 单测抛 stub 异常 | `returnDefaultValues = true` | `Jwt` 自带 base64url 解码（`java.util.Base64` 要 API 26 > minSdk 24） |
| `android.util.Log` 同样抛 | 同上 | provider 注入 `Logger` 抽象，顺带让日志可断言 |
| token 判定用真实系统时钟 | 测试跟着改时间 | 注入 `nowSeconds` —— 否则"刷新中过期"这类分支根本测不了 |

`returnDefaultValues` 会让**所有**未 mock 的 Android API 静默返回默认值，
是方案 §5.4 点名的假绿色（§2.12 为 `org.json` 记过同一决定）。

#### coroutines 版本是耦合约束，不是普通依赖

lint 硬门抓到新增的 `kotlinx-coroutines-test` 有更新版可用。**没有升**：
`expo-modules-core/android/build.gradle:191-192` 用 `api` 暴露 coroutines **1.7.3**，
那是壳运行时实际加载的版本。声明更高版本会经 Gradle 版本冲突解析**把整个 RN
运行时的 coroutines 顶上去** —— 抬升 RN 生态运行时依赖不属 W1 范围。
已钉在 `libs.versions.toml` 并按 mmkv 同样的方式 `#noinspection` 压制。
**lint-baseline 未新增任何条目**（当前 5 条）。

#### 新增的 BuildConfig 字段

`API_BASE_URL` 按 build type 注入（debug/debugOptimized → dev，release → prod），
值与 `tipsy-app` 的 `.env.*` 一致。**壳与 RN Surface 必须命中同一后端** ——
不一致会让原生页与 Surface 看到不同数据，且两边都不报错。
这不是凭据，是公开端点；真凭据仍走 CI secret（方案 §12.7）。
注意 RN plugin 引入的 `debugOptimized` 也要给值，否则编译不过。

#### P1 未做的（明确边界）

- `requestLogin()` 仍未实现（W2 原生 Login 页）—— debug 抛、release 记 error
- **SecureStore 兜底读未做**（P2）：覆盖升级设备上 SecureStore 里的 token
  目前读不出来，那批用户会被当作未登录。**这是已知缺口，不是 bug**
- `mutationGeneration` 已建但**无消费方** —— 它的使用点（ChatList 左滑删除/置顶、
  Profile 卡片菜单）都在 W3
- 权限总数仍 **51 条**，未因本步新增

**顺带修掉一处「假绿色」诱惑**:`android.jar` 里的 `org.json` 是抛异常的 stub,
JVM 单测会全红。**没有**用 `testOptions.unitTests.returnDefaultValues = true` 去绕 ——
那会让所有未 mock 的 Android API 静默返回默认值,正是方案 §5.4 点名的假绿色。
改为引入真实 `org.json:json` 测试依赖。

### 2.14 W1-P6：network 层（closeout 已实现；组合验证已于 §2.22 补上）

`shell/network/` 七个类。原落地 checkpoint 新增/验证 **46 条**网络单测，
当时 app 单测共 **156 条**、skipped=0；§2.18 随后修改了同一实现，
这组数字是历史 gate，不代表当前合并 worktree 已验证。

| 类 | 职责 |
| --- | --- |
| `AuthMode` | 三鉴权模式枚举 |
| `ApiClient` | OkHttp 请求 + header + envelope 解析 |
| `ApiEnvelope` | `{code,msg,data}` 与已知业务码 |
| `ApiException` | 分型异常（业务码保持可分辨） |
| `ApiErrorGate` | 401/402 唯一汇聚点 + 独立防抖 |
| `ScalarCoercion` | 标量漂移容错 |
| `LaneHeader` | BOE 泳道 header（含安全白名单） |

#### 选型：用 OkHttp，**不引 Retrofit**

OkHttp **已在依赖树里** —— RN 自己就用它，实测三个来源（3.14.9 / 3.9.1 / 4.9.2）
全部解析到 **4.12.0**。所以这不是新增依赖；坚持用 `HttpURLConnection` 也省不了体积。

且壳与 RN **共享同一个 `OkHttpClient`**（经反射取 `OkHttpClientProvider`，
失败则退化为自建、不抛）。各起一套会让连接池 / DNS 缓存 / TLS session 变成两份，
还会让「同一后端两条链路」的问题难查（如 RN 侧能连、原生页超时）。

不引 Retrofit 的理由：
1. **统一 envelope 与它的模型冲突** —— HTTP 200 + `code != 0` 是常见组合，
   接进去要写 `CallAdapter` + `Converter`，代码量不比手写少还多一层抽象
2. **三鉴权模式**的 `OPPORTUNISTIC` 语义要在 Interceptor 里做，与 Retrofit 无关
3. **标量漂移容错**要自定义反序列化，Retrofit 只是转交 Moshi/Gson

W1 只需一个 endpoint（`/auth/refresh_token` 已在 P1 写好且**刻意不走本层** ——
它是 auth 前置，走这里会形成「取 token → 刷新 → 取 token」循环）。
W3 若 API 面大到手写吃力，届时业务形态已清楚再评估。

#### ⚠️ `axiosPublic` 不等于「永不带 token」

三模式存在的**唯一理由**。iOS 把 `/search/character_search` 实现成
`authorized: false`，结果**最近搜索历史永久为空** —— 那个接口带 token 才会
把搜索词记入历史。不报错、不崩溃，功能静默失效。

正确映射：`axiosPublic` → `OPPORTUNISTIC`（**有 token 就带，没有也照发**），
不是 `NONE`。已用 `ApiClientTest` 的真实 HTTP 往返钉死「实际发出了什么 header」。

#### 逐条对齐 RN 的实测细节

- **token 走 `token` header**，不是 `Authorization: Bearer`
- header 大小写在 RN 内部就不一致：`axios.ts:116` 是 `Platform`（大写），
  `apis/auth.ts:55` 是 `platform`（小写）。两者都在现网跑 → 后端不区分大小写。
  壳照抄主路径（`axios.ts`）的写法
- **业务码 9（角色卡超限）不在 RN 的 `AppRespCode` 枚举里** ——
  它是 `axios.ts:221` 的字面量。别因为「枚举里没有」就当它不存在
- `REQUIRED` 无 token 时**不发请求**（对齐 `axiosAuth`）：发一个必然 401 的
  请求毫无意义，还会触发 auth 兜底造成误登出路径

#### lane header 的白名单是**安全约束**

`lane.ts:43-68` 的判定要全部满足：https + 无 userInfo + 端口 443/空 + host 白名单。
目的是**防 lane 值泄漏到第三方域**（lane 名暴露内部测试环境标识）。

⚠️ **两个 host 的匹配规则不对称**（实测 `lane.ts:59-63`）：
`api.dev.fantacy.live` 含子域，`api-studio.infra.fantacy.live` **仅精确匹配**。
统一成「都允许子域」会扩大泄漏面；统一成「都精确」会让 API 子域静默失去泳道。

（写这段时我一开始把 studio host 猜成 `studio.dev.fantacy.live`，实际是
`api-studio.infra.` —— **不要凭 base URL 推断常量**。）

#### 401/402 汇聚与防抖

两个入口（原生页经本层 / RN Surface 经桥）现在由 Application 注入同一个
`ApiErrorGate`。401 按 token 指纹区分会话：同 token 的错误浪潮去重，A 的窗口不吞 B；
旧 token 的终端处理返回 false，不占当前会话窗口。402 独立防抖并在终端显式切主线程。
这些 closeout 改动已写测试，但组合 Gradle 验证尚未运行，见 §2.18。

- **不带 token 的 401 不得触发登出**（对齐 `axios.ts:32-33`）：
  无法判断会话归属，登出会踢掉刚登录的新账号
- **401 与 402 各自独立防抖**：合用一个窗口会让 401 后 3 秒内的付费墙不弹

#### P6 接线时踩到并修掉的一个真 bug

`notifyServerPaymentRequired()` 原本标着「W1-P6 未实现」，
而 `notImplemented` 在 debug 下**会抛** —— 一旦接上 `ApiErrorGate`，
**每次收到 402 都会让 App 崩**。已实现为经 Router 导航（目标 Surface 属 W4，
Router 会明确拒绝并记日志，不静默）。`ShellAuthProviderTest` 加了一条
在 `isDebug = true` 下的断言防止改回去。

#### 本步同时收口的

`apiBaseURL()` 从返回 null 改为**壳侧真值**（`BuildConfig.API_BASE_URL`）。
RN 的 `constants/api.ts` 会优先用它 —— 保证原生页与 Surface 命中同一后端。

新增测试依赖 `okhttp3:mockwebserver:4.12.0`（版本与 RN 解析出的 okhttp 对齐）。
**用真实 HTTP 往返而非 mock OkHttp 接口** —— 后者只会验到自己写的 stub，
验不了「实际发出了什么 header」。

### 2.15 W1-§12：RNSurfaceFragment 四项机制（主体已落地，实例关闭链待收口）

`RNSurfaceFragment` 从 36 行 stub 补齐了 UUID、首帧宿主、reappear 与 props builder。
这些是生产 Surface 的前置机制，但当前业务接线尚不能据此标 production-ready。

单测 13 条（`SurfaceContractTest` 7 + `ReappearPolicyTest` 6）。该 checkpoint 当时
app 单测共 **169 条**、skipped=0；这是历史 gate，不代表当前合并 worktree 已验证。

| 要求 | 实现 |
| --- | --- |
| §12.1 `surfaceInstanceId` | `SurfaceContract.newInstanceId()`，每次打开新 UUID |
| §12.2 首帧协议 | 对齐 iOS：不透明 Native wrapper + 透明 RN Root，首帧直接覆盖，不猜 ready |
| §12.3 `onSurfaceReappeared` | 非首次 `onResume` 发射，payload 是**组件名** |
| §12.4 capability handshake | `SurfaceContract.buildInitialProps()` |

#### 真机验证（API 37）

- Surface 挂载正常（`RN Surface OK` 渲染出来），无崩溃
- 切后台再回前台 → 实测日志 `发射 onSurfaceReappeared: surface=DebugSurface`

⚠️ **payload 必须是组件名，不是 instanceId**。RN 侧
`useShellSurfaceRefocus.ts:39` 比对的是 `payload.surface !== surface`，
传 instanceId 会让 hook **永不匹配** —— 表现为「事件发了但页面不刷新」，
而且两边都不报错。该事件的去重粒度是 Surface **类型**。

#### `popSurface` 改为按实例判定（§12.1）

Activity 已具备 `surfaceInstanceId` 比对，不符则忽略并记日志；但真实 TS
`popSurface()` 无参数，Android bridge 固定传 `null`，会绕过这层比对。
因此“迟到旧实例不得关闭新实例”仍未闭环，留给下一 closeout packet。

iOS 的闸是**类型判定**，于是「迟到的旧实例事件弹掉了新打开的同类型页」——
用户点返回后又被弹掉一层，后来靠 `closingRef` 补。Android 从第一天按实例判定。

#### 首帧不猜 ready（§12.2，2026-08-19 对齐 iOS）

早期实现用「RN root view 有了非零尺寸子节点」启动 180ms cover 淡出。warm Runtime
二次进入时，子节点已布局不等于新 Surface 像素已经提交；cover 先变透明会把 sibling
层级下方的 Native Tab 泄露出来，看起来像路由先 pop 再 push。

当前改为与 iOS 普通全屏 Surface 相同的层级不变量：Native wrapper 始终使用不透明
`app_background`，RN Root 保持透明，内容首帧直接覆盖。没有 cover、fade、固定延时或
布局 ready 猜测，也不修改两端共用的 RN Surface。特殊交叉淡入若后续需要，应单独接
平台 RN 内容出现信号，而不是恢复通用定时/布局猜测。

#### ⚠️ 一处自我订正：旋转不重建 Fragment

实现时我写了「旋转会重建 Fragment，所以标记要存 saved state」——**该前提不成立**：
`MainActivity` 的 `configChanges` 已含 `orientation|screenSize`（manifest:52），
转屏不重建 Activity/Fragment。

saved state 仍然需要，但理由是**进程重建**（App 后台被杀、用户从最近任务返回）。
注释与测试名都已改准。若照原来的错理由写，日后有人删掉 `configChanges`
就会失去这层保护而无人察觉。

#### token 不进 initial props（§12.4）

`SurfaceContractTest` 有一条断言 key 常量里不含 `token`/`auth`/`jwt` 字样。
挡不住硬编码字符串，但能挡住「顺手加个 KEY_TOKEN」。
initial props 会进 `Bundle`，可能落入 saved instance state、ANR trace、崩溃日志。

### 2.16 W1-P5：i18n（已完成）

`shell/i18n/` 六个类 + 26 份词条资源。该落地 checkpoint 新增并验证
**42 条** i18n 单测，当时 app 单测共 **211 条**、skipped=0；这是 P5 的历史 gate，
不代表合入 §2.18 后的 244 条组合结果已执行。

| 类 | 职责 |
| --- | --- |
| `LanguageCodes` | **两条** normalize 规则 + 26 个 supported 码 |
| `L10n` | 查表 + fallback 链 + 语言状态 + 广播收口 |
| `LocaleTable` | 一个语言的词条表（宽松逐值解析） |
| `AssetLocaleLoader` | 从 `assets/locales/<code>.json` 读 |
| `AccountLanguageReader` | 从 `user-storage` 信封读账号语言（只读） |
| `LocalizedText` / `rememberLocalizedString` | Compose 自订阅组件 |

#### ⚠️ `normalizeLanguageCode` 实际是**两条**规则，方案文档记漏了

方案 §4.8 与 W1 计划 §7.2 都只提了一个函数，但 `i18n-index.ts` 里有两条对
**同一输入给不同答案**的规则：

| 场景 | RN 出处 | 简体 `zh` 的结果 |
| --- | --- | --- |
| 账号语言 / 任意码 | `normalizeLanguageCode`（`:64-75`） | **`zh-tw`** |
| 启动读设备 locale | `defaultLanguage`（`:118-135`） | **`en`** |

即：账号 `language_code` 存 `zh` 的用户看繁体，设备语言是简体的新用户看英文。
**这不是 bug，是两个不同场景的产品决策**（iOS 的 `L10n.swift:56-79` 同样拆两个函数）。
只实现一条的后果是简体设备用户看到繁体 —— 而这在英文环境测试里看不出来。
`LanguageCodesTest` 有一条专门的**对照测试**钉死这个差异。

#### `en` 也必须查表

实测 `en.json` 的 1838 个 key 里 **94 个 key ≠ value**（如 `Currently unavailable`
→ `More to come`）。拿 key 当英文文案会让这批词条显示错文案，且因为
「看起来像正常英文」而不会被发现。`LocaleAssetsTest` 断言导出产物里
确实存在这类词条 —— 否则这条约束会被悄悄绕过。

#### 导出脚本双壳共用（2026-08-11 决定）

`tipsy-app/scripts/export-shell-locales.mjs` 改为按**探测仓库标记目录**决定输出：
iOS → `Tipsy-iOS/Resources/Locales/`，Android → `app/src/main/assets/locales/`。
`SHELL_KEYS` **不按平台分叉** —— 分叉的代价是「某平台非英文用户静默看到英文」，
多导出几条未用词条只是几 KB。实测 26 个语言 × 180 条，0 缺失。

**为什么用 assets 而不是 `res/raw` / `strings.xml`**：资源名不允许连字符
（`zh-tw`/`pt-br` 要改名再映射），而 key 是含空格标点的英文原文，做不成合法资源名
（方案 §4.8 已明确排除）。也不用 `values-<qualifier>`：壳的语言真值来自**账号**，
让系统按设备 locale 挑资源会与账号语言打架。

#### 语言真值链与一处已知缺口

真值在**后端**，本地是镜像：设置页 → `POST /user/set_language` → `updateUserInfo()`
重拉 → `user.language_code` → `user-storage.state.languageCode`
（`useChangeLanguage.ts:57-72` + `store/user.ts:187`）。

⚠️ **本段原文有一处错**（2026-08-14 订正）：曾写「语言设置页刻意不迁，
**仍在 `SettingsSurface` 里**」—— 后半句不成立。已核实
`SettingsSurface.tsx:34-44` 的 `KNOWN_SCREENS` **刻意不含 `Language`**，
注释原文：「`'Language'`（语言页**原生**：壳是语言唯一写入者，
onLanguageChanged 单向广播）」。iOS 侧对应物是
`Tipsy-iOS/Tipsy-iOS/Pages/Profile/LanguageViewController.swift`（原生）。

正确表述：**语言页要原生实现**（方案 §1.3 归属表写的 `Native / W3` 才是对的），
它不是「不迁」而是「壳侧新写」。方案 §8.1 那行「刻意不迁」指的是
**不由 RN 承载**，措辞容易被读反。壳当前**只读** `user-storage` 的
`languageCode` 镜像、不写信封 —— 写 Zustand 信封必须 merge（§4.6），那属 P2；
但语言的真值链是「设置页 → `POST /user/set_language` → 重拉 `/user/info`」，
原生语言页走这条，不经过信封写入。

⚠️ **桥契约没有 JS→壳 的语言通知方法**（已核实 `modules/tipsy-auth/src/index.ts`
只有壳→JS 的 `onLanguageChanged`）。当前处理：`MainActivity` 挂
`OnBackStackChangedListener`，Surface 容器出栈时重读。
**用 listener 而不是在 `popSurface()` 里调** —— 返回键有两条路径（桥的 popSurface /
系统返回键直接走 FragmentManager），只挂前者会漏掉「按系统返回键退出设置页」。
若将来该时机不够（如设置页不关就切 Tab），再考虑给桥加可选方法，
**不要为了「更干净」提前改跨仓契约。**

#### Compose 组件从第一天就做

方案 §4.8 与 W1 计划 §7.3 都要求「不让每个页面手挂 listener，iOS 后期才补，
Android 第一天就做」。已提供 `LocalizedText` 与 `rememberLocalizedString`。

⚠️ **不要写 `Text(L10n.t(key))`** —— 那是普通函数调用，Compose 不知道它读了
可变状态，语言切换后已组合的文本**不会重组**，表现为「切了语言当前页没变，
退出重进才变」。这类 bug 在切完立刻返回的路径下很容易漏测。

#### 两阶段初始化，且语言**不**作为缓存闸

`TipsyApplication.bootstrapI18n()` 先按设备 locale 起步，再按账号语言覆盖
（对齐 RN 两段式）。必然结果是**首屏可能读到过渡语言** ——
方案 §4.6 与 W1 计划 §7.6 明确：**不要拿语言当缓存闸**（iOS 那样做导致
「第二次启动永远没有种子」）。壳当前无缓存层，只在代码里留了约束注释，
**没有造一个没人用的抽象**。

**合并复核发现的后续风险**：全新安装时 `bootstrapI18n()` 会先打开
`LegacyMmkvStore`；若 MMKV 目录尚不存在，它会把“不可用”实例缓存到进程结束，
而随后的 token persistence 才创建目录。这可能让同一进程首次写入账号语言后仍读不到，
需另包让 legacy store 可重试或先统一初始化 MMKV。此项不由冲突解决顺手改行为。

### 2.17 P7 Qt / P8 Sentry 推迟到业务迁移后（2026-08-11 决定）

> **决策**：Qt 埋点接线与 Sentry 原生实例推迟到业务代码迁移（W2/W3）完成之后。
> **决策人**：项目 owner（用户）。**风险 owner 同上。**

**进入 W2 的判据不受影响**：三条判据里的「root side-effect 表零 `UNKNOWN`」
—— **已决策推迟不等于 UNKNOWN**，按 W1 计划 §5.6 的格式写成显式推迟即可。
故 P7 收窄为「填表 + 记两条决策」，不是删除。

**两项的推迟成本不对称（重要）**：

| | Sentry | Qt |
| --- | --- | --- |
| 接入形态 | 单点（一个 `init`） | 埋点调用点散在**每个**业务页 |
| 推迟成本 | 干净，迁完补即可 | 现在不定调用点写法 → 迁完要回头改几十个页面 |

**对冲**：Qt 需要现在就定一个薄 `Analytics` facade，业务页照常调用，
Qt 接上前只在 debug 打日志。⚠️ **这一处刻意不遵循「未实现项 debug 抛异常」的
纪律**（§2.11 那两条实现纪律）—— 埋点每次事件都抛会让 debug 不可用。
~~**facade 尚未落地**，W2 第一个业务页开工前必须建。~~ ✅ **已落地**（§2.23）。

**两处已存在的静默洞（不是「还没做的功能」，2026-08-11 实测）**：

1. **Qt 的 `preInit` 在壳里一次都不会调。** `QtPackage` 只实现
   `createReactActivityLifecycleListeners`，而该回调**只由 expo 的
   `ReactActivityDelegateWrapper` 分发**（`ReactActivityDelegateWrapper.kt:53-54`）。
   壳没有 `ReactActivity` —— 用的是 `ReactFragment` + 裸 `ReactDelegate`
   （`ReactFragment.kt:47`），壳侧也搜不到任何 `ReactActivityLifecycleListener` 分发点。
   **所以开放问题 §12.1 的前提不成立**：不是「保留 listener vs 排除模块」二选一，
   而是「Qt 目前完全没初始化」。这正是方案 §4.2 拿 iOS 的 AppsFlyer 事故举例的
   那类失败模式。
2. **Sentry 的 JS 事件交给了一个从未 init 的原生 SDK。** 壳没有任何 Sentry 依赖
   （`app/build.gradle` 与 catalog 均无），但 `sentry_react-native` 被 autolink 进来，
   JS 侧 `src/surfaces/sentry.ts` 写 `autoInitializeNativeSdk: false` 且注释说
   「原生层由壳自持」。按 wrapper 实现（`wrapper.js:132-137`）这一支把
   `enableNative` 置 true 后返回 —— **Surface 里的 JS 报错既不上报也不报错。**

**已告知的代价**：W2/W3 那 32.6k 行迁移期间远端崩溃证据缺位，只能靠 logcat
与本机复现。**Sentry 的价值恰在迁移过程中最高**，而不是迁完之后。

### 2.18 W1-CLOSEOUT-1：实现完成（组合验证当时 NOT RUN，**已由 §2.22 兑现**）

执行包：[`../architecture/android-w1-closeout-ready.md`](../architecture/android-w1-closeout-ready.md)。

已实现：

- P9 前移除 ChatDetail 的 runtime enable，命中时明确拒绝且零 Surface 导航。
- refresh 的所有成功/失败/空值路径都受 auth generation + 当前 token 约束；迟到 A
  不得覆盖、返回或清掉 B；single-flight slot 在替换前会重验会话。生产 refresh
  使用独立 Main.immediate scope，HTTP 内部再切 IO，避免自动失效从后台线程改 Router；
  单个 waiter 取消不会清掉仍在飞的共享 refresh，生命周期取消也不会被当作刷新失败。
- token-aware 401 用原子 compare-and-clear，清理与收栈位于同一主线程顺序段；
  Native/RN 401/402 共用一个进程级 gate。
- bridge 对 expired/malformed token 统一返回 null，保护不经过 axios 的 WebView/SSE；
  Native HTTP 在发送前再过滤 expired/malformed/stale token，REQUIRED 零请求失败，
  OPPORTUNISTIC 省略 token 后继续。
- token clear 事件由唯一 Application listener 同步 RN Registry 与 AuthStateHub；bridge
  `clearToken()` 保持静默，完整 logout/自动失效各广播一次。

静态守卫覆盖 tracked diff、冲突标记、submodule pin/worktree 与 RN delta；
`a4eb9055d..95760a662` 只包含 locale exporter 变化，auth/network 契约未变。

**未执行组合验证**：Gradle、Kotlin/Java 编译、JVM 单测、lint、assemble、设备验证。
当时本包是“实现完成、组合验证待跑”。**该状态已被 §2.22 取代** —— main 上的 G1 已 22 步全绿，
这批实现现有 CI 层面的组合证据。

当前合并 worktree 静态可见 `app/src/test` 有 **244** 个 `@Test`，`tipsy-auth`
Android 子模块有 **15** 个；这里只是声明数量，**不等于执行通过**。

本包后仍保留的已知契约债：§3.5 目标顺序是 `clear → pop → emit loggedOut`，当前为
`clear → emit → pop`（listener 在 token 状态临界区同步分发）。当前 listener 只做
RN/AuthStateHub 的有界状态通知且整段位于主线程，未发现跨账号破坏；精确顺序放入下一
closeout，不得据此把 P1 整体标绿。另一个债务是 `notifyServerPaymentRequired()` 的
Promise 会在异步 gate/导航完成前 resolve；当前 RN 只消费 rejection，终端副作用仍切到
主线程，但后续若调用方依赖 Promise 完成语义，必须补成可等待链路。

### 2.19 W1-CLOSEOUT-2：Surface 上线前置（已完成，2026-08-11）

P9 的三层前置。**单测 17 条**（连同既有共 **228 条**，skipped=0）。

#### ⚠️ 比 initial props 形状更靠前的一层：组件不在包里

`app/build.gradle` 的 `entryFile` 原先指向 `index.surfaces.debug.js`，而那个文件
**只注册 `DebugSurface`**（`:59`）。所以任何指向业务 Surface 的路由都会去挂一个
**包里不存在的组件**。这是 W0 刻意的隔离（方案 §5.2「由所属 packet 切回」），
到这一步才该切。

已切到 `index.surfaces.js`，**两处同时改**：`app/build.gradle` 的 `entryFile`
（离线内嵌包）与 `TipsyApplication.getJSMainModuleName()`（Metro 直连）。
⚠️ 只改一处会出现「Metro 加载业务包、离线包却是自检包」的错配，
**debug 下看不出来**（Metro 那份是对的），只有 release 或关掉 Metro 才暴露。

实测切换后 bundle 从 27MB / 426 asset 起步，13 个业务 Surface 的组件名与业务入口
独有标记（`index.surfaces.js evaluated` / `align i18n to shell language`）都在包里。

⚠️ **风险面随之变大**：`index.surfaces.js` 顶层会跑 sentry init、i18n 初始化，
以及 `hydrateTags` / `hydrateCharacterBadgeConfigs` / `hydrateAvatarDecorationConfigs`
三个网络引导。**这三个内部都静默捕获失败** —— 失败不报错，只表现为标签行 /
角色徽章 / 头像框空掉（全新安装必现，升级安装因 MMKV 残留会被掩蔽）。
真机验收时要专门看这三条。

#### initial props 从嵌套 `route` 改为**平铺**

原实现把业务参数塞进嵌套的 `route` Bundle，而 RN 侧 **13 个 Surface 无一读
`props.route`**（全仓搜零命中）。它们一律读平铺的顶层 props：

| Surface | 必需 props（实测） |
| --- | --- |
| `ChatDetailSurface` | `characterId`（**非可选**，`:75`） |
| `CommentsSurface` | `targetType` + `targetId`（`:16-24`） |
| `SettingsSurface` | `initialScreen?` |
| `NotificationSurface` | `tab?` |

iOS 的 `makeInitialProperties()` 产出的正是平铺形状。**嵌套形状会让
`characterId` 恒为 `undefined`**，而 RN 侧不报错，只走「无参进入」兜底 ——
表现为「点某个角色却进了上次的会话」。

`CONTRACT_VERSION` **未递增**：嵌套形状从未被任何 bundle 消费过，
这是修正一个从未生效的字段布局，不是契约变更。

新增 `SurfaceProps`（route → 业务 props 映射）。**刻意返回 `Map` 而非 `Bundle`** ——
`Bundle` 在 JVM 单测里是抛异常的 stub，而这层映射正是最该被测的部分
（key 拼错、漏必填参数，两边都不报错）。撞名守卫也抽成不依赖 Bundle 的
`assertNoShellKeyClash`，**撞名直接抛**而不是静默覆盖。

#### `SurfaceDependencyChecklist`（P9 第一个交付物）

`ChatDetailSurface` 的 18 项微根 + 5 个微栈目标，每项标注**缺失后果** ——
缺项的共同症状是「点了没反应」（事件进 store 无人渲染，不报错不崩溃）。

配套测试**双向断言**「清单 ⊆ RN 源码」与「RN 源码 ⊆ 清单」——
只有前者时，RN 侧新增一个 `PortalHost` 清单仍会全绿，那是虚假的安心感。
另有一条钉死 `SurfaceToastHost` 必须在具名 `PortalHost` 群之前（顺序反了
表现为「弹窗被 toast 盖住」，测试很难抓）。

⚠️ 核对时发现一处**双端不一致**：`ChatDetailSurface.tsx:628` 是
`PortalHost name="MayBallSplashPV"`，而 `App.tsx:478` 是 `"SplashPV"`。
全仓搜下来**两个名字都没有对应的 `Portal hostName` 消费方**，
且 `components/animations/SplashPV.tsx` 根本不用 Portal —— 看起来两侧都是休眠遗留。
**但这是推断，不是实测结论**：真机验收若发现活动开屏不弹，先查这里。
**别"顺手统一"名字** —— 改 `index.surfaces.js` 系文件需要双壳回归。

#### 仍未做（明确边界）

- ChatDetail **未**放回生产白名单 —— 等 §9.1 矩阵填满（与并行的 PR #16 一致）
- 真机验收未跑：本包所有验证都是单测 + bundle 内容核对，按 §5.4 纪律
  「Surface 能否真的跑起来」当前状态是 `NOT RUN`

### 2.20 原生登录页：邮箱验证码链路（真机已验，2026-08-11）

首个原生业务页。`/login/email/send_code` + `/login/email` 全链路接通：发码、
60s 冷却、验码、成功后 `tokenStore.onLoggedIn` + `authStateHub.notifyDidLogin`。
状态收在 `EmailLoginViewModel`（跨重组/配置变更存活）。

**Google / Apple 登录仍未接**（社交按钮在位但无实现）—— `/login/firebase` 受
§12.8 签名指纹阻塞，**无法真机验证**，不是漏实现。`/login/password` 与
`/login/email/did_not_get_code` 未做。年龄验证 / 资料补全 / 账号合并弹窗属 W4。

#### 静默失败：`errorMessage = null` 等于不弹 toast

网络失败时原先把 `errorMessage` 置 `null`，本意「让 UI 用默认文案」，但 UI 是
`errorMessage?.let { toast }` —— **null 等于什么都不弹**，真机表现为「点发送完全
没反应」。API 24 模拟器 TLS 握手失败时踩到（那一档是 CI/冒烟矩阵里的真实环境）。
现回落到 `FALLBACK_ERROR_KEY`；后端 `code≠0` 但 `msg` 为空时同样回落。

⚠️ 兜底文案用 `Please try again later` 而**不是** RN 的 `Something went wrong`：
后者**不在 26 个 locale 文件里任何一个**，`L10n.t` 找不到会回落到 key 本身，
结果所有语言都显示英文（正是 §4.8 那条「非英文用户静默看英文」）。前者 26 个
locale 均已有翻译，已逐一校验。

#### 与 RN 的一处刻意偏离

RN 的发码/登录**不检查 envelope 的 `code`**（`auth.ts:126-143`），后端限流返回
HTTP 200 + `code≠0` 时 RN 静默当成功、倒计时照走，用户等一封永不到的邮件。
壳这里检查 `code` 并把后端 `msg` 抛给 UI。

#### 测试与验证

app 单测 **49 条**覆盖本页（ViewModel 编排 / envelope 契约 / 状态机 / `X-Client-ID`
加密），skipped=0。契约测试用 `MockWebServer` 验实际发出的 header。

⚠️ `android.util.Log` 在 JVM 单测里是抛异常的 stub，故 ViewModel 的失败日志经
`logWarn` 注入（默认参数给生产实现，同 `nowMs` 的处理）。**没有**开
`returnDefaultValues` —— 那正是 §5.4 点名的假绿色，且会掩盖上面那个 null 静默。

原有「网络失败不启动倒计时」用例只断言状态、断言不到「用户被告知」，所以漏掉了
这个 bug。新增用例直接断言用户可见文案。

真机（API 36，`ai.lightspeed.tipsy`）：正确码登录成功并落地 token；错误码弹
「验证码错误」且停在原页；倒计时到 0 恢复「重新发送」；断网点发送弹
「Please try again later」且不启动倒计时（可立即重试）。

**未验**：API 24 真机/模拟器（该档 TLS 连不上本后端，是发现此 bug 的环境但未跑
通完整链路）；三个 applicationId 的覆盖升级。RN 侧 `onAuthStateChanged` 目前
**只有类型声明、无 JS 订阅方**，所以登录只发 `authStateHub`、未发 `TipsyAuthRegistry`；
接 Surface 前需补齐对称性。

> ⚠️ **本节原写「`didLogin` 广播的下游消费（W2 五 Tab 尚不存在）」—— 该前提已失效**：
> §2.23 的 `HomeFragment` 已订阅 `AuthStateHub`（登录/登出都重拉列表 + 绑定/解绑
> 埋点 uid）。也就是说登录链现在**有真实下游**了，但那条链本身仍未真机验证。

### 2.21 CI 挂死：`runTest` 里嵌 `runBlocking`（2026-08-11 修复）

`ApiClientTest.store 返回后恰好过期 REQUIRED 仍不得起飞` 会**永久死锁**，
表现为 G1 Fast Gate 在「单元测试」步骤耗到 **job 60 分钟超时被 cancel**，
后续「桥单测」与「skipped=0 校验」两步直接 skipped。

PR #16 的 G1 记录是 `fail 1h0m15s`，PR #17 首跑是 `cancelled 1h0m15s`
—— **同一个签名**。该 PR 描述里也写明「未执行组合验证」，所以这条是带着红 CI
合进 main 的，不是本次合并引入。

机制：`fixture` 传 `scope = this`（TestScope），该用例的 token 落在 refresh
窗口内（`exp = now+1`、`requestNow = now+2`），于是 `getValidToken()` 走到
`refreshSingleFlight`，那里 `scope.async` 把 refresh 排到**虚拟时间调度器**上，
随后 `deferred.await()` 等它。而外层 `assertThrows { runBlocking { ... } }` 已经
占住唯一的 test 线程 —— 调度器永远拿不到执行机会。

同文件另有九处 `runBlocking` 侥幸不死锁：它们的 token 无效或不在 refresh 窗口内，
`getValidToken()` 在真正 suspend 前就 return 了。**别以为那个写法是安全的。**

修法：直接在 `runTest` 协程里 `try/catch` 调 suspend 函数，不嵌 `runBlocking`
（本仓未依赖 kotlin-test，故不用 `assertFailsWith`）。

⚠️ **这个坑的二次伤害是「报告看起来是绿的」**：测试 task 挂死时不产生新报告，
`build/reports/tests/**/index.html` 还是上一次成功运行的内容。排查期间据此读到
过「303 条全绿」，而那是挂死前的旧产物 —— 真实数字是 **336**。
判据：**先看报告 mtime，再看数字**；挂死的 task 没有 mtime 更新。

修复后实测：`:app:test{DirectApk,GooglePlay}DebugUnitTest --rerun-tasks`
→ 各 **336 条**、failures=0、ignored=0，全程 2m35s（此前是无限挂）；
`:tipsy-auth:testDebugUnitTest --rerun-tasks` → 15 条、ignored=0，
`LiveAppSafetyTest` 已执行；`:app:lintDirectApkDebug` 过。

### 2.22 W1 组合验证已在 CI 真绿（2026-08-11）

§2.18 曾把 W1-CLOSEOUT-1 记为「实现完成、组合验证 NOT RUN」。**那条已由 CI 兑现**：
`main` 上 §2.21 修复后的第一次 push run
（[31490358140](https://github.com/Lightspeed-Intelligence/tipsy-Android/actions/runs/31490358140)）
**22 步全过、32m27s**，含 lint 硬门 → assembleGooglePlayDebug → release manifest →
app 单测 → `:tipsy-auth` 桥单测 → `skipped=0` 守卫。

也就是说 §2.18 / §2.13 / §2.14 里那批 closeout 实现现在有 CI 层面的组合证据，
不再是「只跑过静态守卫」。**仍未覆盖的**：三 flavor 全量、release 打包（属 G3
nightly，未建）、真机验收（§2.19 的 `NOT RUN` 依然成立）。

### 2.23 W2 第一刀：五 Tab shell + Home 首屏（2026-08-11）

第一个 W2 工作包。**壳从「自检根」变成真实首页**：启动进 Home，底部五 Tab 可切。

落地的模块：

| 目录 | 内容 |
| --- | --- |
| `shell/analytics/` | `Analytics` facade（Qt 前置，见下） |
| `shell/tabs/` | `ShellTab` / `ShellTabBar` / `TabHostFragment` / `TabPlaceholderFragment` |
| `shell/pages/home/` | 系列与性别枚举、`HomeApi`、解析、`HomeViewModel`、`HomeScreen` 与卡片、`HomeFilterStore` |

**tabbar 对齐 RN Android 现网**（owner 2026-08-11 决定）：实心 `#341F1D`、无圆角、
无模糊、无选中胶囊。⚠️ RN 侧 iOS 分支是**另一套**（悬浮胶囊 + BlurView + 200ms
滑动胶囊，即 iOS 壳 `FloatingTabBarView.swift` 那套）—— 照 iOS 做会与现网 Android
用户看到的界面明显不同。

**未登录冷启动直接弹登录页**（owner 决定，对齐 RN `restoreSession`）：无 token /
已过期 → `requestLogin`。Tab 骨架先建好、登录页盖在其上。

#### Home 做到哪（明确边界）

已做：6 个系列（**含 World** —— 见下）、真实分页、下拉刷新（系统控件，对齐 RN 的
Android 分支）、性别筛选与持久化、session 语义、翻页去重 + 限次续拉、5 个页面级埋点。

未做（下一包）：banner（946 行，方案 §8.1 评估留 RN Surface）、每日彩蛋弹窗、
mp4 动图封面。

✅ **标签筛选抽屉与 For You 冷启动种子已做**（§2.24）。

⚠️ **「可见性驱动的曝光去重」这条原是误记，已核实不存在**（2026-08-12）：
`character_page_exposure` 在 RN 侧由 **mount `useEffect`** 发出
（`HomeCard.tsx:182-196`），**不经 viewability**。壳侧 `LaunchedEffect(item.stableKey)`
已是同一语义，且 `LazyVerticalGrid` 的 `key = stableKey` 保证复用 slot 不重报
（正是方案 §8.4「曝光去重集合与列表更新解耦」要求的）—— **这条已满足，不是待办**。

`home.tsx` 里那两套 `viewabilityConfig` 各有别的用途，别误当成本事件的门禁：
- `itemVisiblePercentThreshold: 1` → `VisibleItemsContext`，管 **mp4 封面播放**
  （`AnimatedCoverMedia.tsx`）
- `itemVisiblePercentThreshold: 50` + 连续可见 ≥100ms → **另一条批量上报管道**，
  POST `/recommend_report/tracking/report_batch` 报停留时长
  （`lib/recommendTracking/`），不是埋点事件。这条属推荐反馈，未迁，也不在 W2 范围

#### ✅ 开放问题 §12.4 可以关闭：Android **显示** World

方案 §12.4 问「Home 是否包含 World 系列」—— 代码里早有答案，不需要产品决策：
`home.tsx:505-511` 的 filter 是
`series !== 'Multi-character' && (Platform.OS === 'android' || series !== 'World')`。

即 **Multi-character 两端都隐藏，World 只在 Android 显示**（iOS 壳的 `HomeAPI.swift`
因此只有 5 个 case，Android 是 6 个）。World 列表走 `/game/public/projects`
（每页 **20**，不是 21），点进去是 SimulatorGame WebView —— 方案 §8.1 已定不迁，
本包点击落明确日志而非静默。

#### Qt facade 已落地（§2.17 的对冲条件解除）

§2.17 写明「facade 尚未落地，W2 第一个业务页开工前必须建」—— **本包已建**。
业务页调 `Analytics.track`，Qt 接上前只在 debug 落日志，接线时只改
`TipsyApplication.installAnalytics()` 一处。

uid 排队语义照搬 RN（`QtAnalytics.ts:404-420`）：四个 uid-required 事件在用户 id
绑定前排队（上限 50，超出丢**最旧**），绑定后补 `uid` 冲出。方案 §8.1 记的
「`character_page_exposure` 需手动补 uid」由 facade 统一处理，业务页不必各自记得。

⚠️ **Qt 本身仍未初始化**（§2.17 的两处静默洞依旧）：facade 的存在不等于埋点在上报。

#### 顺带修掉的真实缺陷：`LegacyMmkvStore` 全新安装永久不可用

§2.16 末尾记的「后续风险」在本包变成真 bug —— 因为有了写入点（gender）。

`LegacyMmkvStore.open` 在 MMKV 目录不存在时直接返回**不可用实例**，而
`TipsyApplication` 用 `by lazy` 把它缓存到**进程结束**。全新安装时
`bootstrapI18n()` 先打开它（目录还不存在 → 缓存成不可用），随后
`MmkvTokenPersistence` 才建目录。结果整个进程内：首次登录写入账号语言后仍读不到，
且 gender **永久写不进去**。现改为目录不存在时 `mkdirs()`（与
`MmkvTokenPersistence.open` 一致）。

#### `config-persist-storage` 的写入是本包破坏性最大的一处

它是 Zustand persist 信封 `{state, version}`，整体覆盖会丢掉同一信封里其余二十多个
字段（模型选择、上下文长度、已点击标签…）→ **用户一堆设置被重置且不报错**。
故写入走纯函数 `mergeGenderIntoEnvelope`（只 put 一个 key）并有专门单测。

⚠️ **`nsfw` 只读不写**：它的真值在后端 `user.nsfw`，由 RN 的 store 底部订阅单向
镜像（`config_persist.ts` 末尾）。壳写它会破坏单向流，表现为「关了 NSFW 过一会儿
自己开回来」。所以 `HomeFilters` 接口**刻意没有 `writeNsfw`** —— 别为了对称补一个。

#### 依赖：coil3 **不是新增依赖**

`io.coil-kt.coil3:coil 3.0.4` 已由 `react-native-screens` 引入（已核实其
`android/build.gradle:249-253`）。壳显式声明**同一版本**，与 mmkv / coroutines
同性质 —— 版本是与 RN 侧的耦合约束，声明更高版本会经 Gradle 冲突解析把 RN 那份
也顶上去。必须同时引 `coil-network-okhttp`：不引则任何 http(s) URL **静默不加载**
（只报一句 "no fetcher"，图片位置空白）。

#### 位图资源放 `drawable-nodpi`

RN 侧这些图**只有一份**（无 `@2x`/`@3x`），像素恰好是设计稿的 3 倍。
RN bundler 自己把它们打进 `drawable-mdpi` —— 说明 RN 完全不参与 Android 密度分档。
故放 `nodpi` 并由使用点显式给 dp（漏给会按原始像素铺开，40dp 图标变 120dp）。
`IconMissingDensityFolder` 已显式 disable 并写明理由，详见
[`android-bitmap-assets.md`](android-bitmap-assets.md)。

#### 验证

- **app 单测 431 条**（新增 95 条）、failures=0、**skipped=0**；报告 mtime 已核对
  （§2.21 的判据：先看 mtime 再看数字）
- lint 硬门通过：**no new issues**，baseline 仍 5 条
- `assembleGooglePlayDebug` + `:tipsy-auth` 桥单测：与 G1 同序列本机跑过
- **真机验收已跑**（2026-08-12，emulator-5554 / Android 16 / googlePlayDebug）：
  启动进首页、五 Tab 切换与选中态、五个 series tab（For You / Trending / World /
  Latest / Popular）各自加载真实列表、滚动续拉翻页、World tab 隐藏筛选图标 ——
  **均通过，无崩溃**。埋点 `discover_page_tab_click` + `discover_subpage_exposure`
  在每次切 tab 时按对出现（`tab_type` 正确）。
  - ⚠️ 观察到 `page=null`：facade 的 page 字段在 tab 事件上没填。不影响本包验收
    结论（Qt 上报本身推迟，§2.17），但**接 Qt 前要确认 RN 侧该字段是否也为空**，
    否则会是一处静默的埋点字段回归。
  - 标签筛选抽屉点击**按预期无反应** —— `HomeFragment.onFilterClick` 仍是 stub
    （`HomeFilterDrawer` 382 行，下一包）。点击链路本身已接通（日志可见）。
  - World 卡片的 `∞ 0` 是**真实数据**不是 bug：图标按 `character_type == 9` 分流
    走 `ic_card_world_interaction`，计数取 `stats.studio_chat_count`
    （`HomeFeedParserTest` 断言 42），测试环境多数 world 该字段确为 0。
  - ✅ **下拉刷新 / 性别筛选 / 进程重建恢复已补验**（2026-08-12，Pixel 10 模拟器 /
    Android 17）—— 详见 §2.23.1。**性别筛选查出一处真实缺陷**（持久化静默失效）。

新增测试按「错了不报错」的风险点组织：`HomeTextTest`(19，逐条对着 RN 实现取真值)、
`HomeViewModelTest`(19，session 语义/去重续拉/失败不清列表)、
`HomeApiContractTest`(10，真实 HTTP 验实际请求体)、`HomeFeedParserTest`(15)、
`ShellTabBarTest`(16)、`AnalyticsTest`(12，含"sink 内再次 track 不死锁")、
`HomeFilterEnvelopeTest`(4)。

#### 2.23.1 补验三项真机（2026-08-12，Pixel 10 模拟器 / Android 17）

§2.23 遗留的三项。**下拉刷新与进程重建通过；性别筛选查出一处真实缺陷。**

**✅ 下拉刷新** —— 刷新前首屏 Elara / Niko / Ben，下拉后换成 Emi / test，
一批新 `characterId` 重新曝光。种子信封同步被**覆盖**而非叠加：
14:22:55 存 `[Elara, Niko, Kai, Ben, Dylan]` → 14:23:32 存 `[test, Emi, ...]`，
与两次首屏一一对应，证明走的是 `isRefresh && nextPage == 0` 清 `lockedHead` 的路径。

⚠️ 手势前提：列表**必须在顶部**下拉才触发。我第一次在滑到中段时下拉，无任何反应
也无日志 —— 不是缺陷，但会让人误判成刷新没接线。

**✅ 进程重建恢复** —— `KEYCODE_HOME` 后台化 + `kill -9`（保留 task，比
`force-stop` 更接近系统回收），PID 13267 → 14089，恢复后**无 FATAL / ANR**，
首屏渲染 Emi / test 即 14:23:32 那份种子，说明冷启动读种子在进程重建路径同样成立。

⚠️ **series 选择不恢复是设计如此，不是缺陷**：kill 前停在 Trending，恢复回 For You。
已核实 `selectedSeries` 无任何持久化、也不进 `SavedStateHandle`（全仓 grep 无命中），
RN 侧同样不持久化 series。

**🔴 性别筛选：内存态正确，持久化静默失效**

内存态没问题：选 Female 后顶部标签变 `Female`、列表换成 Esmeralda / Iris，
新种子信封也正确记为 `gender: 'Female'`（14:26:50）。

但 `config-persist-storage` 这个 key 在设备上**始终不存在**（dump `mmkv.default`
确认 0 命中），于是 `kill -9` 重启后性别**退回 All**。

根因是 `mergeGenderIntoEnvelope` 的刻意设计：信封缺 `state` 子对象就
`return null` → 调用方不写（`HomeFilterStore.kt:109-117`）。这个保守策略本身是对的
（§2.23 记了整体覆盖会重置用户二十多项设置），**但它假设信封已由 RN 建好**。

已核实这不是壳的路径写错：RN 的 `zustandStorage` 用 `createMMKV()` **无参数**，
即默认实例 `mmkv.default`（`tipsy-app/src/store/mmkv.ts:4`），与壳读写同一个 store。
信封不存在只是因为这台模拟器上 RN 的 config store 从未初始化过。

**所以缺陷是真实的**：全新安装的用户，在 RN 侧首次初始化该 store 之前，
改性别**永远不持久化且无任何提示** —— 每次冷启动都退回 All。
`writeGender` 的返回值虽然是 `false`，但调用方按注释刻意不回滚 UI、也不告警，
于是本地完全看不出异常（与 §2.24 种子那处同类的"静默"缺陷）。

⚠️ **修法不能是"信封不存在就建一个"** —— 壳凭空造 Zustand 信封要猜 `version` 和
其余二十多个字段的默认值，猜错等于给 RN 侧一个结构不对的信封，
比不持久化更糟。合理方向是二者之一，需 owner 定：
1. 只在信封缺失时写一个**仅含 `{state:{gender}}` + 正确 `version`** 的最小信封，
   靠 Zustand persist 的 merge 语义补齐其余字段（要先核实 RN 的 `version` 与
   `merge` 配置，否则可能触发 migrate 分支）
2. 判定为"可接受"：等 W3 迁 Settings 时 RN store 必然已初始化，届时自愈

**未验**：上述任一修法都没做，本次只定位。也没验"信封已存在时 merge 是否只动
`gender`"—— 设备上无信封可比对，该行为目前只有 `HomeFilterEnvelopeTest`(4) 的
单测覆盖。

### 2.24 W2 第二刀：标签筛选抽屉 + For You 冷启动种子（2026-08-12）

W2 的 Home 收尾。**单测与构建全绿**（476 条）。真机**已全验**
（Pixel 10 模拟器 / Android 17，2026-08-12 复验）：

- ✅ 抽屉拉取并渲染真实标签、选中高亮、关抽屉后列表收敛到筛选结果
- ✅ **选了标签不写种子**：勾 Anime 后 feed 收敛到 1 条，dump `mmkv.default`
  确认**没有**新信封落地；取消勾选再确认，14:03:09 立刻落一份 5 条的新信封
  —— 证明「不写」只发生在有筛选时，无筛选路径正常
- ✅ **离线冷启动渲染种子**：断网 + `force-stop` 后冷启，首屏渲染出信封里的
  33 / Luciano / 111 / Evelyn Sharp（第 5 条在屏外）。**无全屏 spinner**
  —— UI 树里唯一的 `ProgressBar` 是 42x42、y≈213 在搜索栏内，种子之上没有遮挡层，
  §2.24 的「有种子时先显示种子且不显示 spinner」在真机成立
- ✅ **种子与真实数据衔接**：恢复网络后冷启，首屏仍是同 4 个角色**且无重复卡片**
  （去重按 stableKey 生效）；下滑出现 Tomboy Lena / Yuto / Luna / Sylvan
  —— 种子在前、真实数据追加在后

⚠️ 我第一次报的「已在真机确认」是**无效的** —— 为清种子删了 `mmkv.default`，
那是 app 共用的 MMKV store（`token-storage` 也在里面），会话被清空、应用重启到
登录页，后续盲点坐标全落在登录页上，而我把登出后写的 `guest` 信封读成了通过。
**清种子要用下拉刷新**（`isRefresh && nextPage == 0` 会清 `lockedHead`），
不要删共享 store。

真机操作两条经验（下次省时间）：
- launcher activity 是 `ai.lightspeed.tipsy/.shell.MainActivity`（**不是** `.MainActivity`，
  用后者 `am start` 会静默落到桌面）；`adb shell monkey -p ... -c LAUNCHER 1` 最省事
- `adb screencap` 拉回的 PNG 多次为空，**改用 `uiautomator dump` 读 UI 树**取控件
  文本与坐标，比截图可靠

#### 标签筛选抽屉（`HomeFragment.onFilterClick` 的 stub 已删除）

- `HomeTag` + `HomeTagParser`：`POST /character/tags`（**只发 `{nsfw}`**，不带
  `language_code`）。按 `sort_order` 稳定排序；**`show_in_filter !== false`** 才进
  筛选（不是 `== true` —— 字段缺失时要显示，写反会让标签集体消失）；
  label 取 `desc` 回落 `alias`
- `HomeFilterDrawer`：`Dialog` + 底部面板。高 630 / 圆角 20 / header 49 /
  chip 30 高 18 内距 / 选中色 `#AD403B`（品牌主色）
- ✓ 图标用 `Canvas` 两条线段画 —— RN 用 AntDesign 字体图标，壳没有那套字体，
  为一个对勾引 `material-icons-extended`（约 2MB）不值得

⚠️ **应用时机是「关闭抽屉」而不是「点确认按钮」**（`TipsyDrawer.tsx:338` 的 ✓ 调的
就是 `handleClose`，`onClose` 回调里才 `setSelectedTags`）：点 ✓ / 点遮罩 / 按返回键
**三者都应用**。照「确认才生效、点外面丢弃」实现与现网相反。

**两处按系列分流**（都容易漏）：
- Following / World 请求**不带 `tag_ids`**（`useHomeCharacterLists.ts:89`）
- 它们的 `filterKey` 也**不含标签**，且 `onTagsApplied` 只清受影响系列的游标。
  第一版写 `cursors.clear()`，被 `改标签不作废 World 已缓存的列表` 这条测试挡下

#### For You 冷启动种子（方案 §4.6 的信封）

`HomeForYouCache`：`{version, authScope, gender, savedAt, items}`，
authScope 门禁（`guest` / `user:<id>`）+ 7 天 TTL + 只存**前 5 条**
（`LOCKED_HOME_CACHE_SIZE = 5`）。**语言刻意不做门禁**（§4.6 的反直觉修正）。

⚠️ **壳写自己的 key（`shell-for-you-seed`），不读也不写 RN 的 `for-you-cache`**。
RN 那份是**裸数组**（`JSON.stringify(items)`，无信封）—— 已核实它因此
**不按账号隔离 / 无 TTL / logout 不清**（全仓没有清该 key 的代码）。§4.6 要求壳不
继承这三点，所以两份并存：读 RN 的等于继承跨账号复用，写 RN 的会让 RN 侧解析到
非预期结构。代价是首次装壳版没有种子。

存**原始响应片段**而非解析后模型 —— 读写都复用 `HomeFeedParser`，
不必再写一套序列化（那会是第二个真值来源）。

⚠️ **信封刻意不含 tags，代价是「选了标签时不写种子」**（2026-08-12 自测发现，
PR #20 修）。写进去也能当门禁，但那样带标签这一次的种子对下次无标签的冷启动
永远失效，等于白存 —— 所以选择不写。

这条不是洁癖，缺了它是真缺陷：标签勾选存在**无 persist 的 session store**，
杀进程后归零，于是「筛选出的 2 条」会被当作未筛选的 For You 首屏渲染，
**三道门禁全过、本地完全看不出异常**。改前真机抓到两份信封作证 ——
12:52 未筛选 5 条，12:57 应用 Action 后只剩 2 条却仍标着 `gender: All`。

同一处还有第二个缺陷：`onTagsApplied` 里必须丢掉 `lockedHead`。合并列表时读
`lockedHead` **早于**第 0 页落地后清空它，所以首屏失败（种子保留 —— 失败不清
列表是对的）之后改标签，那几条未筛选的角色会混进筛选结果，用户无从分辨。

**RN 侧同样有这两个缺陷**：`getForYouListReq` 带 `tag_ids`
（`useHomeCharacterLists.ts:59`），而 cache 写入 effect 只看 `forYouFirstPage`
变化、不看筛选状态（同文件 `163-169`）。按 §4.6 壳不继承缓存缺陷。

种子与真实数据的衔接（两条都是被测试逼出来的）：
- 种子**不写进 `loaded`** —— `loadIfNeeded` 靠它判「已有数据就不拉」，
  写进去会让首屏永远停在种子上、真实数据一次都不拉
- `loadIfNeeded` **不能无条件置 `isInitialLoading = true`** —— 会在种子之上再盖
  全屏 spinner，种子白读。第一版就是这么写的，`有种子时先显示种子且不显示 spinner`
  这条挡下了

真实第 0 页到达后种子作为**锁定头**在前、真实数据去重追加
（对齐 `home.tsx:711` 的 `unionBy(cachedList, flatList)`）；下拉刷新丢弃种子
（RN 的 `setShowForYouCache(false)` 同义）。

#### 顺带修的两处文档失真

1. 方案 §8.1「筛选持久化」称含 `tags` —— 实际 `config-persist-storage` 的 `tags` 是
   **标签目录**，用户勾选存在**无 persist 中间件**的 `session.ts`
   （已核实 `grep -c persist` = 0）。**杀进程后勾选归零，只有 gender 存活**。
   照文档实现会让原生版比 RN 多记住筛选
2. 「可见性驱动的曝光去重」是误记，见 §2.23 的更正 —— 该条已满足，不是待办

#### 验证

- app 单测 **476 条**（新增 43）、failures=0、**skipped=0**（2026-08-12 复跑确认）
- lint 无新增（baseline 仍 5 条）、`assembleGooglePlayDebug` 通过
- **真机 `PASS`**（2026-08-12，Pixel 10 模拟器 / Android 17）：抽屉打开/勾选/应用、
  「选了标签不写种子」、离线冷启动渲染种子、种子与真实数据衔接**四项全过**，
  详见本节开头。种子这项尤其需要真机：它依赖 MMKV 实际可读写，而 §2.23 刚修过
  `LegacyMmkvStore` 全新安装不可用的缺陷 —— 已确认冷启动读得到信封

新增测试：`HomeTagParserTest`(11)、`HomeForYouCacheTest`(16，三道门禁 + 坏数据)、
`HomeViewModelTest` +16（标签分流 9 + 种子 7）。

### 2.25 W3 第一刀：Profile 自己视角（资料头部 + 创作/记忆两 tab，2026-08-12）

W3 开工，Profile Tab 从占位换真页。新增 `pages/profile/`（16 文件）与 `user/`
（`CurrentUser` / `CurrentUserStore` / `UserInfoApi`，进程内用户信息，**刻意不持久化**
—— 壳只读不写 RN 的 `user-storage` 信封，冷启动首进 Profile 多一次 loading 是
记录在案的代价，要消掉走「读信封作种子」而不是壳写信封）。

> 2026-08-19 订正：这是本刀当时的分期边界，已由 §2.46 的完整会话发布取代；
> Android 现在会 merge 写入 `user-storage`，同时保留 `CurrentUser` 作为 Native 内存模型。

**范围**：`/user/info` 资料头部、`/user/stats_info` 四统计、`/user/created/list`
创作三列网格、`/plot/list/self` 记忆单列大卡、五图标 tab 栏（含滚出屏顶后浮出）、
按 tab 分表的分页壳、四个出口路由类型（Settings / EditProfile / Follow / UserCoins，
全部**未启用**，点击走 Router 明确拒绝）。

**未做**（后续包）：角色卡/收藏/点赞三 tab、钱包区、卡片菜单与编辑/删除/置顶动作、
记忆卡点击进 ChatMemory（属 ChatDetail 深栈）、他人主页（stats 走 OPPORTUNISTIC）、
创作列表首屏缓存（`profileCreatedListCache` 的 key 带维度设计值得单独一刀，
顺带印证 §2.23.1 的修法方向）、NSFW 封面模糊（等 Compose 模糊方案，两处卡片一起做）、
头像框（配置源 hydrate 会静默失败，§2.19）、`onFirstTabDataReady` 一族性能埋点。

#### 实测抓出的对等陷阱（写码前逐条核 RN 源码）

- **`/user/stats_info` 字段与标签交叉**：Followers 标签下是 `followees_count`、
  Following 标签下是 `followers_count`（`FollowInfo.tsx:52,66`，两行是反的）。
  照字段名直译会标反且**本地几乎测不出来**（测试账号两数常相等）。
  命名收口在 `ProfileStats.followersLabelCount / followingLabelCount`。
- **`/plot/list/self` 是关系型响应**：`plots` + `characters`/`creators` 两个
  id→对象 map 靠 join（`apis/plot.ts:86-88`），与创作列表的内联嵌套形状完全不同。
  TS 类型与实测出入：`created_at` 声明 `string` 实为 Unix 秒**数字** → 走
  `ScalarCoercion`。背景图用 `image_url`、头像位用 `face_url`，两个字段别混。
- **同路径/同能力自己与他人各一条**：stats 是同路径不同鉴权（axiosAuth vs
  axiosPublic）；记忆是不同路径（`/plot/list/self` vs `/plot/list/creator`）。
  本刀只接自己那条（REQUIRED）；接他人主页时是 **OPPORTUNISTIC 不是 NONE**（§4.5）。
- **整页 loading 不能照抄 RN**：RN 的 `isLoading` 接的是**不上屏的死请求**
  `/character/list/self`（`useProfile.tsx:165-186` 注释确认）。壳直接接各 tab 列表请求。
- **创作 tab 空态文案是 `No Character`**：空态分流 `tabIndex===0 → 'story'`
  （`CharacterGrid.tsx:1063-1074`），`EMPTY_TEXT_MAP.story = 'No Character'`。
  第一版杜撰过 "No creations yet"，已按实测改。
- **分页 size 按 tab 配**：创作/角色卡 10、记忆/收藏/点赞 20、他人主页 200。
  「5 tab 共用一个分页壳」指壳复用，size 不统一。
- **`UID:` 前缀不进 i18n**：RN 是裸文本（`user-profile.tsx:665` 不走 `t()`），翻了反而不对等。
- **下拉刷新 RN 把五个 tab 全 mutate**（`CharacterGrid.tsx:252-262`
  `Promise.allSettled`）：壳的对应物 = 当前 tab 立即重拉 + 其它 tab 复位待重拉（见下）。
- **记忆卡时间是恒 en-US 的 `h:mm a`**（`formatTimestampToAMPMTime` 调用点不传
  locale），且创建时间只显示时:分不显示日期 —— 别顺手"修"。

#### 并发模型：单在飞分页链（与 RN 的刻意差异）

RN 每 tab 独立 `useSWRInfinite` 并行；壳采用 Home 同款**单 inFlight 链**：任一时刻
至多一条分页链在飞且必属当前 tab，切 tab / 刷新 / 语言 settle / 登录态变化都先
cancel。被打断且未完成首屏的 tab **整体复位**（否则 `isInitialLoading` 卡 true，
`loadFirstPageIfNeeded` 永远跳过它）。代价是切 tab 偶尔废弃一个在飞请求；换来页级
`isRefreshing` 无归属歧义、跨 tab 响应竞态整类消失。分页游标（`nextPage`/`total`/
空页续拉 streak）按 tab 分表存 `ProfileState.paging` —— 裸字段会让切 tab 后
从对方页码继续拉，首屏缺前 N 页。

#### 顺带修掉的真实缺陷：`CurrentUserStore.refresh` 吞 CancellationException

`runCatching` 不分流会让登出瞬间在飞的 `/user/info` 响应把旧账号资料写回已清空的
状态（「登出串上一账号数据」的 Profile 变体：取消发生在挂起点，吞掉异常后非挂起
代码照常执行到写状态）。已改为 rethrow，且 `ProfileViewModel.onAuthChanged` 同时
取消 `userStatsJob`。JVM 测试用 gate 挂起在飞请求验证了取消语义
（`切走打断在飞首屏后切回能重拉`）。

#### i18n 与 testTag

- 全部对等词条**已在 SHELL_KEYS**（Settings / Edit Profile / 四统计标签 /
  No Character / No memories / Private / Failed / Pending / Passed / messages /
  Please try again later），**本刀不动 tipsy-app、不 bump submodule**；ja 抽查有译文。
- `Coming soon`（未接 tab 的壳专属占位）**刻意不进 SHELL_KEYS**：RN 无此词条，
  加了也没有任何语言的译文，fallback 链（当前语言 → en → key）走到 key，
  与加了行为一致；三个 tab 落地即删。
- testTag 按 Login 的 snake_case 现行约定（方案 §9.4 的 `android.` 点分风格是
  草案，代码先例是 `login_*`）：`profile_{avatar,uid,edit,settings,grid,loading,
  empty,error,tab_placeholder}`、`profile_stat_*`、`profile_tab_*`、
  `profile_created_card_<id>`、`profile_memory_card_<id>`。动态段只用服务端 id。
- 审核状态徽标是**值与文案不同轴**：`un_reviewed/pass/failed`（`types/review.ts`）
  → Pending/Passed/Failed，原始值不上屏，认不出的值不显示。

#### 两个 owner 决策记录（Follow 仍阻塞，EditProfile 已解决）

1. **Follow 出口无处可去**：方案 §8.1 把 `follow`（445 行）列为「不迁、走 Surface」，
   但 RN **不存在 FollowSurface**（已核实 `src/surfaces/` 无此文件，`follow.tsx`
   是 ProfileStack 普通页）。要么 RN 侧新建 Surface、要么原生实现。
   `AppRoute.Follow(userId, type)` 与 props 形状已按 `FollowInfo.tsx:57,71` 备好。
   **仍未决，但已确认不阻塞他人主页那一刀**（2026-08-14，§2.32）：他人主页
   本体不渲染关注按钮，本刀不产生新的 Follow 入口。当前唯一的 Follow 入口
   仍是自己视角的统计数字点击。
2. **EditProfileSurface 过矩阵在 W3 还是 W4**：这里保留 2026-08-12 的历史疑问；
   owner 已于 2026-08-18 按 **W3 做预接**（§2.43），production 启用仍只认
   §9.1，故不因预接而从“其余未验 Surface”中移除。方案文档
   §9.1 已同步订正为“W3 预接、生产关闭”。

#### 验证

- app 单测 **563 条**（新增 87：`ProfileViewModelTest` 30 含多 tab 游标隔离 /
  切走取消在飞链 / 刷新复位其它 tab / 语言 settle / 占位 tab 刷新收圈；
  `ProfileParserTest` 27；`ProfileMemoryParserTest` 15；`ProfileTextTest` 15）、
  failures=0、**skipped=0**
- `:tipsy-auth:testDebugUnitTest` 15 条全绿（含 `LiveAppSafetyTest`）
- lint 无新增（baseline 仍 5 条）；`assembleGooglePlayDebug` 与
  `assembleDirectApkDebug` 通过
- **未动** manifest / RN 依赖 / flavor → release manifest diff 本刀不适用；
  RuStore flavor 未单独 assemble（flavor 相关零改动）
- **真机冒烟 `PASS`**（2026-08-12，Pixel 模拟器 / Android 17，directApk 覆盖安装
  保留登录态）：① 头部 + 统计真实数据（0/1/0/70，交叉映射下 Following=1 与账号
  实况一致）② 创作网格 6 卡全渲染 ③ 记忆卡关系 join 生效（角色名 Emi 来自
  `characters` map）+ 三条预览气泡按角色/用户分流 + Pending 徽标 + `3 messages` +
  `5:31 PM` ④ ROLE_CARD 显示 "Coming soon" 占位 ⑤ 切回创作即时显示不重拉
  ⑥ Settings 点击明确拒绝（`拒绝导航：Settings —— 该目标在当前波次尚未启用`）
  ⑦ 占位 tab 下拉刷新不卡圈不崩溃
- **真机 NOT RUN**（数据或后续包具备时再验）：翻页触底续拉（账号仅 6 条创作，
  不足一页）、tab 栏滚动浮出（列表不够长）、语言切换全 tab 复位重拉、
  首屏错误态展示、进程重建恢复

### 2.26 W3 Profile P2：头部视觉对齐（渐隐背景 + bio + 顶栏，2026-08-12）

对照线上截图补齐头部三大块，头部结构与现网一致（仍差钱包卡 = P3、
头像框与渠道图标 = P7、卡片角标 = P4）。

- **渐隐背景图**（`ProfileBackground.tsx`）：宽 = 屏宽、1:1，三段 alpha 遮罩
  `locations [0.36, 0.9, 1]` × `alpha [1, 0.1, 0]`。Compose 用
  `CompositingStrategy.Offscreen` + `drawWithContent` + `BlendMode.DstIn`
  **精确复刻**（不是叠底色渐变的近似）。URL 空走内置默认图
  （`user-profile.tsx:418-423` fallback `profile_bg.png`，已搬为
  `ic_profile_bg_default`）。背景 absolute 垫底、列表滚在上面（对齐 RN）。
- **⚠️ 订正第一刀的一处位置错**：UID **不在昵称下方** —— RN 把它放在悬浮
  顶栏**左侧**（`user-profile.tsx:656-674`，带 `popover_copy` 复制图标、
  alpha 0.8）。已挪到顶栏左，右侧同时把 "Settings" 文字占位换成
  `profile_setting.png` 图标资产。
- **头像行锚定屏顶 250dp**：RN 是「悬浮 header 高 `top+50`」+「header 内
  `paddingTop: 250 - headerOffset`」（`ProfileHeader.tsx:173`）的配合。
  壳顶栏在布局流里，等价换算 = 250 − statusBar − 顶栏高（44）。
- **bio 区**（`renderBio.tsx`）：白 8% 圆角容器 / marginH 10 / padding 10 /
  一行截断 + 右侧铅笔（仅铅笔可点，与 RN 一致），点击与 Edit Profile 同走
  `AppRoute.EditProfile`（当前明确拒绝）。空态文案
  `No bio yet. Add one now.` **已在 SHELL_KEYS** —— 本包仍零 submodule 改动。
- `CurrentUser` 补 `bio` 字段（带默认值，既有构造点不受影响）；
  统计排版经实测核对**无需改**（16/12sp、边距 15/10/8 与 `FollowInfo.tsx`
  styles 一致，第一刀就抄对了）。
- 新资产 4 个：`ic_profile_setting` / `ic_profile_uid_copy` /
  `ic_profile_bio_edit` / `ic_profile_bg_default`（均单文件直搬 RN assets）。

#### 验证

- app 单测 **568 条**（新增 `CurrentUserParserTest` 5：残缺响应作废 / 可选字段
  归一 null / bio 空串走空态 / 数字 id 容错）、failures=0、skipped=0；
  lint 无新增；`assembleGooglePlayDebug` + `assembleDirectApkDebug` 通过
- **真机截图对照 `PASS`**（同 §2.25 环境）：背景图渐隐上屏且与线上同构、
  顶栏 UID+复制图标 / 设置齿轮、头像行落点与线上一致、bio 空态条完整渲染、
  统计与网格不回归
- **NOT RUN**：背景 URL 为空的默认图分支（测试账号有背景图；分支只是
  painterResource 换 AsyncImage，风险低）、非空 bio 的显示（账号无 bio；
  同一 `Text` 只是数据分支）

### 2.27 W3 Profile P3：钱包三栏卡（2026-08-12）

头部最后一块大件。`ProfileWalletApi` + `ProfileWalletCard`，接进 header
（bio 之下、tab 栏之上，`CharacterGrid.tsx:1434` 的位置）。

- **数据 = 两接口合成**：`/wallet/info`（宝石/免费条数/金币，`subscribe.ts:91`）
  + `/subscription/get/active`（档位 → 中栏标题与配色，`subscribe.ts:102`），
  都是 `axiosAuth` → REQUIRED。**各自失败各自保留旧值**（同 stats 纪律），
  两个都失败整块不动。`membership_rights/info` **刻意不拉** —— 卡片不消费
  权益字段，RN 在此组件只用它做预取。
- **只解析上屏的五个字段**：`useUserWallet` 派生的十几个值（汇率/提现/佣金/
  生图余量）消费方是 UserCoins/提现页，都不迁（§8.0）。
- **两套新数字规则**（本页第四、五套，`ProfileWalletTest` 13 条锁死）：
  钱包整数 = 裸千分位**无 K/M 换算**（`formatMessageAmount`）；
  金币 = 去尾一位小数 + 千分位 + **恒带 `.0`**（`formatCoinAmount` 的
  `floor(x*10+1e-8)/10`，0.19 → `0.1`、0 → `0.0`）。
- **⚠️ 反直觉但对等的两处**：`has_inf_msg=true` 时中栏显示**硬编码 100**
  （`UserProfileGems.tsx:371` 的三元，不是 Unlimited —— 现网行为，别修）；
  RN 整栏和胶囊按钮**同一动作都可点**（外层 TouchableOpacity + 内层按钮
  都调 `onButtonPress`）—— 壳整栏可点。
- **三出口对齐 RN 三个 handler**：宝石+ → `GemsPurchase(initialTab=buy_gems)`、
  升级 → `GemsPurchase(initialTab=subscription)`、金币→ → `UserCoins`。
  真机逐个点过，全部走 Router 明确拒绝日志（`GemsPurchase`×2 + `UserCoins`）。
- **档位名映射**（`MemberShipTierName`）：0-5 → Free/Get a Taste/Standard/
  Premium/Deluxe/On Trial（key = 英文原文**全在 SHELL_KEYS**，含宝石 ⓘ 的整段
  说明文案 —— P2/P3 连续三包零 submodule 改动）。未知档位回落 Free。
- **刻意不做**：会员栏 ⓘ 的到期/续费信息（要 `expires_date` + 日期格式化，
  Free 账号不可见）、金币 USD 汇率首次引导气泡（依赖 guide-status store，
  属 Onboarding 域）。
- 资产：`gem_{red,blue,coin}` + `info` 直搬 PNG；`plus`/`arrow` 是 RN 内联
  SVG，转写成 vector drawable（两笔描边，逐 path 对照）。

#### 验证

- app 单测 **581 条**（+13：`ProfileWalletTest` 9 解析/档位/两套格式化 +
  `ProfileViewModelTest` 4 合成/失败保留/单独失败/登出清空）、failures=0、
  skipped=0；lint 无新增；`assembleDirectApkDebug` 通过
- **真机 `PASS`**（同 §2.25 环境）：三栏卡上屏（真实数据 1,234,567 千分位 /
  Free 0 + Upgrade / Coins `0.0` 恒一位小数）、三个出口点击均落明确拒绝日志、
  无崩溃
- **NOT RUN**：`has_inf_msg` 显示 100 的分支、非 Free 档位的蓝色数字与档位名
  （测试账号 Free 且无订阅 —— 纯数据分支，解析侧已有 JVM 覆盖）、ⓘ 气泡的
  Popup 视觉（点击路径无真机截图，组件为标准 Compose Popup）

### 2.28 W3 Profile P4：卡片角标 + 封面模糊（2026-08-12）

创作网格与线上的最后一块显著差距。`ProfileCreatedItem` 补 9 个字段与 5 个
派生判定，`ProfileGridItem` 重写为五层结构，`CoverBlurTransformation` 落地。

- **⚠️ 订正第一刀的一处解析错**：`review_stage` 等状态字段 RN 从**嵌套对象**取
  （`character.review_stage`），第一刀解析的顶层同名字段实测不总在 ——
  已改为嵌套优先、顶层兜底（`nestedThenTop`）。
- **左上角标三选一**（优先级 = RN 的三元链，`CharacterGridItem.tsx:780-812`）：
  审核角标（rejected/pending，approved **不渲染**）＞ 私密锁（`!is_public`）＞
  story/18+ 标签。18+ 标签只在**审核通过**时出现（待审时位置属于审核角标）。
  右上：置顶 Pin。rejected 判定并合 `minor_review_status`（rejected/
  final_rejected）与 `review_stage=failed`，rejected 优先于 pending。
- **封面模糊三条件**（`CharacterGridItem.tsx:571-577` 注释照录）：
  ① nsfw（壳内偏好恒 false → **18+ 一律模糊**）② `final_hit & 8`
  ③ 未成年审核拦截。记忆卡（`plot.nsfw`）同一套变换复用。
- **模糊选型：Coil 位图变换，不是 `Modifier.blur`** —— 后者 RenderEffect
  只在 API 31+ 生效、**低版本静默不模糊**，而 minSdk 24 是冒烟矩阵真实一档，
  18+ 封面在低版本露出是内容合规问题。实现走了三版：一步 16× 上采样有块状
  锯齿、两段式仍不够 → **渐进 2× 逐级上采样**（叠加双线性近似高斯）真机
  对照与 BlurView 磨砂观感一致。cacheKey 带版本号，模糊与原图各占缓存。
- **`final_hit < 2` 整卡不可用遮罩**：锁 + `Currently unavailable`。
  ⚠️ 该词条是 key≠value 实例（en 值 "More to come"）——正好验证「运行时
  不得拿 key 当英文文案」。**缺失不算不可用**（RN 是 `!= null && < 2`，
  反过来会把老数据整页蒙掉）。
- **计数行**：曝光数仅 character 卡且 `is_public`（`stats.exposure_count`）；
  消息数 character 卡走 `formatCountMaxThreeDigits`（**第六套数字规则**：
  三位有效数字 K/M/B/T/Q，`4730 → 4.73K` 两位小数、`999950 → 1M` 晋位，
  行为对齐 RN 自带单测），story/game 卡走 `formatNumber`（= Home 的
  `formatMessageCount`，直接复用）。`stats.total_messages` 优先于顶层。
- **`is_public` 缺失按 true**（不画锁）：多画锁比漏画显眼，方向刻意。
- 资产 8 个：pending/fail/lock/Pin/message/tag_story/tag_18_plus/exposure 直搬。
- **仍不做**：⋮ 菜单与动作（P5，届时菜单按钮必须可点击组件吃事件——iOS 的
  点击穿透坑）、卡片点击进详情（目标页未启用）、winner 徽章与水印（运营
  配置源）。

#### 验证

- app 单测 **590 条**（+9：角标优先级/模糊三条件/final_hit 边界/18+ 仅过审/
  嵌套层取值/is_public 缺省 + 计数格式化 3）、failures=0、skipped=0；
  lint 无新增（`Bitmap.scale` KTX 替换后）；googlePlay + directApk assemble 通过
- **真机截图对照 `PASS`**（同 §2.25 环境，账号 6 创作含全部形态）：
  Pending 徽标（黑胶囊+沙漏）、置顶 Pin 右上、私密锁 + 封面磨砂模糊
  （Leeke 卡与线上观感一致）、评论数、story 卡创作者名注①，五层结构与线上同构
- **NOT RUN**：rejected 徽标（账号无被驳内容）、`final_hit` 遮罩与 &8 模糊
  （无命中数据）、game 卡（账号无 game）—— 判定全部有 JVM 覆盖，纯数据分支
- 注①：story 卡的创作者名是 P4 前已有的底行内容，本包未动它

#### 2026-08-20：封面模糊视觉收尾

模拟器复查发现 P4 的低版本兼容方案被所有系统版本共用：封面先缩到 1/16 再逐级
放大，虽然不会 fail-open，但人物轮廓出现明显糊块，与 RN/iOS 的实时材质模糊不同。
RN `BlurView intensity=40` 在 Android 经默认 reduction factor 4 后实际约 10px；
iOS 当前 `CoverBlurView` 使用 `systemUltraThinMaterialDark`，另叠 8% 黑色安全盖板。

现改为双轨：API 31+ 用 Compose `RenderEffect` 实时模糊，并把 RN 的约 10px 半径
按设备 density 换算为 dp；API 24–30 继续用 Coil 位图变换保证合规不露图，但降采样
由 1/16 调整为 1/8、cache key 升 v4，减少像素块。两轨统一叠 iOS 同款 8% 暗色
fail-safe。没有照搬 RN 各卡片 Android 样式里的 50%–90% 黑底；放进 Compose
会让暗色层主导、几乎看不出实时模糊，不是 iOS 当前的轻磨砂目标观感。

新增 JVM 断言钉死 API 30 走位图降级、API 31 起走实时模糊，但**本刀未跑
Gradle / 真机**；`git diff --check` 通过，现代/低版本两轨均待设备截图验收。

### 2.29 W3 Profile P6：角色卡/收藏/点赞三 tab（2026-08-12）

五个内容 tab 全部接通真实数据源，"Coming soon" 占位与 `isImplemented`
语义整体删除。新增 `ProfileRoleCardItem`/`ProfileFavoriteItem` 两个模型
（收藏与点赞**同响应形状共用模型**，RN 侧也是共用 `FavoriteCharacterCard`）
与两个卡片组件。

- **接口**（全部 REQUIRED）：角色卡 `/user/profile_card/list`（size 10）；
  收藏 `/user/followed/character/list` 与点赞 `/user/likes/character_list`
  （size 20 + **`is_reverse: true` 硬编码**，两 hook 同款请求体只差路径）。
- **⚠️ 到底判定出现第二条轨**：收藏/点赞的响应给 **`total_pages`（页数）**，
  判定是 `已拉页数 >= total_pages`（`useProfileFavorites.ts:63-66`）——
  与创作/记忆/角色卡的「累计条数 >= total」不同量纲。`ProfileTabPaging`
  加 `totalIsPages` 标记分轨，拿条数比页数会**第一页就误判到底**
  （JVM 测试 `收藏 tab 按 total_pages 判到底` 锁死两页场景）。
- **角色卡默认卡置顶**：`sortRoleCardsWithDefaultFirst` 在**派生层**复刻
  （`ProfileState.roleCardItems` 的 stable sort），分页累计保持接口顺序 ——
  排序是显示规则不是数据规则。
- **role_pic 三段解析**（`RoleCard.tsx:31-44`）：`role_pic_url` → `role_pic`
  （http 直用 / 相对路径拼 `https://img.tipsy.chat/`）→ 占位。⚠️ 该 CDN 前缀
  是 RN **组件里的硬编码**（两处重复定义）—— 照抄不改，与创作卡「不许拼
  域名」不冲突（那边顶层相对路径无约定前缀）。
- **`message_num` 是 TS 声明 string 的字段**：实测可返 number，走
  ScalarCoercion 双形态容错（§4.5 标量漂移的又一实例）。
- 收藏/点赞卡 = 创作卡同构减角标层；nsfw 模糊复用 `CoverBlurTransformation`
  （RN 这里 intensity 25 与创作卡 40 不同，壳统一一档 —— 视觉 diff 属验收）。
  角色卡横条：白 8% 底 / 64 圆头像 / Default 橙标 / meta `性别 | 年龄 | 标签`
  全空显 None；性别 male/female 之外**全归 Other**（`RoleCard.tsx:68-73`）。
- **仍不做**：角色卡 ⋮ 菜单（设默认/编辑/删除，编辑目标 `EditRoleCard` 是
  不迁的 RN Surface）、超限提示（`isOverRoleCardLimit` 依赖 `RoleCardLimit`
  全局弹窗，属 Surface 微根件）、收藏卡取消收藏/批量管理、卡片点击进详情。
- 词条零新增（Default/None/Male/Female/Other 及三个空态 key 全在 SHELL_KEYS）；
  **连续第四包零 submodule 改动**。

#### 验证

- app 单测 **604 条**（+14：`ProfileTabParserTest` 10（role_pic 三段/性别归一/
  message_num 双形态/total_pages 语义/characters null）+ `ProfileViewModelTest`
  净增 4（页数轨两条/点赞收藏分流/默认卡置顶；两条旧「占位 tab」测试改写为
  真实数据链语义））、failures=0、**skipped=0**；lint 无新增；双 flavor
  assemble 通过
- **真机 `PASS`**（同 §2.25 环境）：五 tab 逐个切换 —— 角色卡横条（Lee +
  Default 橙标 + `Male | 18`）、收藏网格（5555555）、点赞网格（Haruka /
  Fire Mage / Marbles + 消息数），切换往返数据不重拉、无崩溃
- **NOT RUN**：收藏/点赞翻页续拉与页数轨真机验证（账号数据不足一页；
  判定有 JVM 两页场景覆盖）、角色卡多页（同）、收藏 nsfw 模糊（列表无
  18+ 内容；变换与创作卡同一实现已真机验过）

### 2.30 W3 ChatList P1：Grid 主链路（2026-08-12）

ChatList Tab 从占位换真页。新增 `pages/chatlist/`（10 文件）：
Grid 视图全链路 —— 分页列表、LV 徽章、草稿混排、左滑 pin/delete、
推送红点、铃铛未读、Grid/Map 偏好持久化、冷启动种子缓存。
**Map「時光長廊」是 P2**（562+297 行重视觉自绘，Map 按钮切到 Coming soon 占位）。

开工前按纪律先修方案 §8.1 ChatList 行（三处偏差：草稿展示「iOS 未做」已过时、
操作接口清单漏项、convEpoch 契约未记录；铃铛端点笔误——RN SWR key
`/system_message_notification/read_status` 是缓存键，真实端点是
`/message/notification/get_unread_status` 带 `platform` 参数）。

- **接口**（全部 REQUIRED，`apis/chat.ts`/`relationship.ts`/`letter.ts` 逐个核实）：
  `/user/chatted/list`（page/size 50/language_code/need_total）、
  `/user/character/relationship/batch_get`（LV 徽章，id 去重排序后发）、
  `/user/chatted/{pin,unpin}`（game 用 `{item_type,game_id}`，其余带
  `chat_mode`+`conversation_id` 小手机对话级定位）、三个 delete
  （**plot 走 character 端点**，RN else 分支语义）、消红点、铃铛未读。
- **双 generation 的 mutation 轨第一个实战用例**（§4.4）：删除乐观移除同帧
  `bumpMutation()` + 分页链每页回写前 `isValid` 双轨校验 —— JVM 测试
  `删除期间在飞的旧响应不得复活已删行` 用 gate 挂起响应锁死该时序。
  pin/unpin 是**成功后**本地重排（非乐观，对齐 RN），重排的插入位置循环
  照 `ChatListItem.tsx:175-226` 逐行移植并单测锁死。
- **convEpoch 共享键契约落地**：character 会话删除成功后写
  `multi-cinema-conv-epoch:<characterId>`（RN `multi_cinema_round_cache.ts:52-60`
  已就绪的壳侧契约，iOS 壳同款；不写则原生删会话后重进多角色影院假命中旧剧情）。
  story/game 不写；失败路径不写（JVM 测试覆盖三种情形）。
- **草稿只读混排**：解 `chat_draft_lru` 的 lru-cache dump（`[[key,{value}],...]`
  两层包装 + legacy 纯字符串条目读时兼容）。排序照 `ChatGrid.tsx:99-121`：
  **无草稿时保持接口原序**（RN 的 `draftMap.size===0` 捷径是行为对等不是优化）、
  有草稿时 pinned 恒前 + 草稿 `updatedAt`/`latest_time*1000` 混排降序；
  **mini_phone 行不吃同角色草稿**（草稿键是角色 id 会串显）。
  排序在 `ChatListState.sortedThreads` 派生层，`threads` 保持接口序。
- **stableKey 刻意不对等**：RN FlatList 的 key 掺 index 与 latest_time
  （对 key 冲突宽容的历史妥协）；LazyColumn 遇重复 key 直接崩，改用业务四元组
  `item_type:id:chat_mode:conversation_id`（mini_phone 同角色多入口靠
  conversation_id 区分，JVM 测试锁死）。
- **徽章四条件**（`ChatListItem.tsx:423-426`）：`sub_level>0` && 账号
  `relationship_switch`（`CurrentUser` 新增该字段）&& 角色 `is_relationship_open`
  && 非 mini_phone。徽章批拉是独立旁路任务，晚到只更新徽章 map 不触列表
  （§8.4「晚到 banner」同型）；只走 auth 轨校验（mutation 轨会被本地删行误废）。
- **种子缓存**：壳自己的 key `shell-chat-list-seed`（信封 version/authScope/
  savedAt/TTL 7 天），**不读不写** RN 的 `chat-list-cache` 实例（裸数组无门禁，
  会话列表全是账号私有数据，跨账号泄漏比 Home 严重）；**登出清**。
  语言刻意不做门禁（§4.6 反直觉条款）。只存第一页。
- **跨容器刷新**：`CHATTED_LIST_REFRESH` 事件（发送方全在 ChatDetail 深栈）
  跨不过 Surface→原生页边界 —— 原生对应 `markStale()` + 下次 onAppear 重拉，
  接线属 P9（ChatDetailSurface 启用时）。
- **cinema XML 剥离**：`convertCinemaXmlToMarkdown` 移植（image_prompt/options
  整块删、dialog 四种冒号支持 + 标准冒号引号输出、异常回退原文）。
- **时间格式恒数字不走 locale**：今天 `H:mm` 小时**不补零**、今年 `MM/DD`、
  跨年 `MM/DD/YY`（RN 裸 `getHours()`，别顺手本地化）。
- **出口现状**：点会话行 → `AppRoute.ChatDetail`/`MiniPhoneChat`、铃铛 →
  `AppRoute.Letter`，P9 前全部被 Router 明确拒绝（§8.3 形态，与 Home 卡同型）；
  game 条目无路由（SimulatorGame WebView 不迁），埋点后 `Log.w`。
  点击判定素材（isStory/characterType/contentType）**暂不透传** ——
  `AppRoute.ChatDetail` 扩参属 P9 包。
- **埋点**：`page_exposure`（chat_list）、simulator 卡曝光/点击
  （`time_corridor`，2s 节流照 `simulatorGameTracking.ts:59`；曝光停留 1s
  近似 RN 的 `minimumViewTime`，95% 可见精确判定后置）。
- **左滑操作自绘**：M3 `SwipeToDismissBox` 是滑走删除语义，不合 iOS 风格
  stay-open —— `detectHorizontalDragGestures` + 148dp 双键（Delete 红 +
  Pin 橙）、40dp 阈值、同表单行互斥、滑开点行主体收起（对齐 RN Swipeable）。
- **基建顺带改动**：`TipsyApplication.generations` 从局部变量提升为属性
  （页面级消费者第一次出现）；`LocalizedText` 加 `fontSize` 参数；
  `CurrentUser` 加 `relationshipSwitch` 字段。位图移植 18 张
  （chatlist 12 + relationship 徽章 6，`drawable-nodpi` 命名规范化 `ic_*`）。
- **仍不做**（P2/P9/后置）：Map 廊道视图、启动后台预取 page 0、
  `firstInteractive` 性能埋点族、simulator 曝光 95% 精确判定、
  批量 relationship 徽章的 `RELATIONSHIP_LEVEL_UPDATED` 事件重拉
  （发送方在 ChatDetail 深栈，同跨容器刷新一并属 P9）。
- **词条零新增**（Time Corridor / Draft / Image / Pinned× 4 / Delete failed /
  空态长句等 14 个 key 全在 SHELL_KEYS 且 26 locale 已导出——iOS 壳先迁时加过）；
  **连续第五包零 submodule 改动**。

#### 验证

- app 单测 **649 条**（+45：`ChatThreadParserTest` 12（标量漂移/未知类型丢弃/
  game id 归属/mini_phone 三元组/creator 三级兜底/坏条目跳过）+
  `ChatListTextTest` 14（时间三段/cinema 剥离含全角冒号/排序派生含无草稿
  原序捷径/徽章四条件/信封 merge 只改一键）+ `ChatDraftStoreTest` 5
  （现行/图片/legacy 字符串/空草稿跳过/坏 JSON）+ `ChatListViewModelTest` 14
  （分页续拉限次/失败不清列表/**mutation 闸门防复活**/convEpoch 三情形/
  pin 重排两方向/auth 闸门丢在飞/登出只清不拉/未登录不发/徽章过滤/偏好写入））、
  failures=0、**skipped=0**
- `:tipsy-auth:testDebugUnitTest` 15 条全绿（含 `LiveAppSafetyTest`）
- lint 无新增（baseline 仍 5 条）；`assembleGooglePlayDebug` 与
  `assembleDirectApkDebug` 通过
- **未动** manifest / RN 依赖 / flavor → release manifest diff 本刀不适用
- **模拟器冒烟 `PASS`**（2026-08-13，API 36 arm64 模拟器，directApk）：
  列表真实数据渲染（LV 徽章双开关/streak `1d`/pin 灰标/时间三段格式/
  繁中最后消息）、左滑 pin/unpin、删除确认与对账、Grid/Map 切换、铃铛。
  冒烟抓出并修掉一个布局缺陷：**左滑操作键恒挂底层会从透明行主体透出**
  （每行 Delete/Pin 常驻盖住时间栏）——改为只在滑动中/滑开态组合，对齐
  RN Swipeable `renderRightActions` 仅滑动期间渲染的语义（`084c158`）。
  ⚠️ 排查期间发现模拟器残留全局代理 `10.0.2.2:8888`（Charles 遗留，宿主
  无监听）致所有请求 ConnectException——症状像断网/掉登录，实际 token 完好；
  清法 `settings put global http_proxy :0` + 重启 App（OkHttp 缓存代理配置）
- **NOT RUN**：GooglePlay 打码行为（冒烟账号会话名无词表命中项）；
  RuStore flavor 未单独 assemble（flavor 相关零改动）

### 2.31 W3 Search P1：搜索主链路（2026-08-13）

Home 顶栏搜索框换真页。新增 `pages/search/`（8 文件 2,526 行）：
输入防抖、建议词、最近/热门搜索、角色+创作者双 tab 结果、翻页、
敏感词空态、清空历史。**`FilterDrawer`/`SearchTagBar` 是 P2**
（筛选按钮不渲染，性别不发、排序恒 `Recommended`、分级恒 `All`）。

`AppRoute.Search` 是 **Router 白名单里第一个被放开的目标** —— 此前所有
目标都在 P9 前被明确拒绝。放开的理由写在 `enabledRouteTypes` 注释里：
§9.1 验收矩阵管的是 RN Surface 的桥/生命周期风险，`Search` 是纯原生
Fragment 不开 Surface，那套矩阵对它不适用。**不能据此推论「原生页都能加」**。

- **接口**（六个，`src/apis/character.ts` 逐个核实）：`character_search`、
  `user_search`、`character/suggest`、`popular_search_terms/app` 走
  **`OPPORTUNISTIC`**；`recent_history`、`clear_history` 走 **`REQUIRED`**。
  前四个**刻意不用 `NONE`** —— 方案 §8.1 记的 iOS 事故点：`character_search`
  带 token 才会把词记入最近搜索，iOS 错用 `authorized:false` 致历史恒空。
  游客可搜索，所以 `AppRoute.Search.requiresAuth = false`（写 true 会把
  游客挡在登录页后，RN 侧没有这个门）。
- **防抖只管「查」不管「清」**（`useSearch.ts:284-333`）：`submitQuery` 同帧
  清结果 + 置 loading + 切角色 tab，请求延后 500ms。顺序反了的表现是
  连打字时看到上一个词的结果。
- **两个查询的并发形态刻意不对称**（`useSearch.ts:298-299`）：创作者查询
  fire-and-forget，角色查询 await —— loading 态由角色查询关闭，创作者晚到
  不该让 Characters tab 一直转圈。单测 `创作者查询未返回也不阻塞角色结果展示`
  用挂起的创作者响应锁死。
- **回写由 auth generation + 本地搜索序号共同守卫**：登出/换号会丢弃旧账号
  响应；提交 B 后，即使 A 的 HTTP 回调不响应取消而更晚到，也不能覆盖 B。
  Search 不拥有乐观列表 mutation，故只校验 auth 轨，不能被 ChatList 的全局
  mutation bump 误作废。`CancellationException` 必须继续抛出，不能当请求失败
  写 Toast；翻页游标也只在请求成功后递增，失败重试不会跳页。
- **Search 必须订阅 AuthStateHub**：logout 取消所有在飞工作并清掉查询、结果、
  session 与账号私有 recent history，且不发 REQUIRED 请求；login/换号才重拉。
  否则 token refresh 失败广播 logout 后页面仍会展示旧账号的搜索历史。
- **`search_trigger_page_exposure` 的 `session_id` 恒为空串**：RN 的
  `handleChange` 先 `initState()` 把 `sessionIdRef` 清成 `''` 再发事件
  （`useSearch.ts:322-331`）。看着像「带上了 session」其实永远是空的，
  **照抄，不要顺手修正成上一次的 id**。
- **首查失败或 `data == null` 必须回 `IDLE` 而不是「搜到 0 条」**（`useSearch.ts:142-177`）：
  否则空态把请求失败当成无结果，诱导用户去创建角色。空态判定收在
  `CharacterSearchOutcome` 四态（IDLE/SAFE/DIRECT/RELATED）：直接命中敏感词
  不给创建按钮、**关联命中仍给**、缺敏感类型字段视为 SAFE 不是 IDLE。
- **翻页三重守卫**（`useSearch.ts:219-234`）：`loadingMore` 在途、`refreshing`
  在途、**当前列表为空**。第三条防 LazyColumn 空列表也触发 onEndReached
  导致 page1/page2 并发；第二条防筛选重查期间基于旧数据错页。结果页按
  `stableKey` / `userId` 去重，建议词按大小写不敏感值去重，避免后端跨页或列表
  内重复导致 Compose duplicate-key 崩溃。去重后整页无新增时主动续拉，连续最多
  3 页；少量新条目后若仍在触底阈值内，按新 `itemCount` 继续填满视口。
  同一条目数只自动请求一次，避免翻页失败后因 loading 回落形成网络风暴；
  滚出再进入阈值仍可手动重试。
- **每页非空 search session 都覆盖旧值**（RN 两个 query 都在 page 分支之前更新
  ref）：否则 page2+ 新 session 返回后，后续曝光/点击仍会错误归因到 page1。
- **曝光去重集合新搜索时清空**，且 `isRefreshing` 期间不报 —— 此刻列表还是
  旧结果而集合已清空，会把旧卡当新卡重报（`useSearch.ts:337`）。创作者行发
  **两个**事件（`character_page_exposure` 不去重 + `search_content_exposure`
  去重），别合并，前者是通用主页曝光口径。**P1 仍有已知口径差异**：
  Native 在条目进入 composition 时上报，RN 的 search exposure 要求条目至少
  50% 可见；Lazy 预组合可能让 Native 早报/多报，后续独立收口。
- **角色卡点击由搜索页接管时要补回 HomeCard 内部埋点**：先发带完整筛选参数的
  `character_page_click`，有 `search_session_id` 时再发带 1-based 位置的
  `search_content_click`；只做路由会让搜索点击归因静默缺失。
- **建议词与 RN 的一处有意分歧**：RN 走 SWR（key 变化即重发 + 10s
  `dedupingInterval` + `keepPreviousData`）。壳用「取消旧任务 + 回写前比对
  query」等效实现前两者，**没做 10s 去重缓存** —— 逐字输入会比 RN 多发几个
  suggest 请求。该接口轻量无副作用，暂按可接受；真机观察到请求量问题再补
  短 TTL 缓存。失败**静默**（RN 侧 SWR 也不弹 Toast）。
- **角色结果复用 `HomeFeedItem.Character`**：搜索接口返回的是扁平结构，
  解析时翻译成 Home 的模型以复用 `HomeCard`。`watermark_url` 恒空串的已知
  缺陷 **P1 不跟进**（方案 §8.1 该行已订正）—— 壳侧 Home/Search 都还没有
  水印渲染，跟进 iOS 的 tag_id 回填是独立包；**与 RN 行为对等**，非新增缺陷。
- **热门词读大写 `Member` 字段**（接口是 C# 风格 PascalCase），小写读不到
  视为格式不符丢弃，单测两向锁死。
- **计数缩写是本项目第七套数字规则**：`formatCountMaxFourDigits` 的缩写门槛
  是 **10000 不是 1000**（`1000` → `1000`，`12345` → `12.35K`），与 Profile
  卡片的三位数规则**不可复用**。写错的表现是「粉丝 1200 显示成 1.2K」——
  与线上不一致但看着合理，极易漏过 review。
- **`openSearch` 必须幂等**（同 `openLogin`）：入口在 Home 顶栏，连点两次会
  叠两层 Fragment、返回要按两次。Fragment tag 挡在栈重入；退出 Search 时
  Activity 还必须通知 Router 解除 `lastHandled`，否则返回 Home 后第二次点击会在
  整个 Activity 生命周期内被永久吞掉（提交前审查补回归测试）。
- **词条**：复用 8 个既有 SHELL_KEYS，另把 placeholder
  `Search characters or creators...` 与无障碍返回文案 `Back` 加入导出清单，
  26 locale 全部命中；位图移植 5 张（`ic_search_*`，`drawable-nodpi`）。
  submodule pin 到 `5a58be9d1`；父仓对应 locale + pin 提交是 `9209b0b`。

#### 验证

- 提交前实现快照的 DirectApk app 单测 **695 条**（新增 46 条），
  failures=0、**skipped=0**。最终源码相对 main 共有 **+59**：
  `SearchParserTest` 12、`SearchEmptyStateTest` 11、`SearchViewModelTest` 34、
  `AppRouterTest` 2，另扩展既有 parser/Surface props 断言。审查新增的 13 条
  锁定 A/B 角色与创作者乱序、mutation bump 不误伤 Search、角色点击两类归因、
  翻页重复 key 去重、退出后同一路由可重开；
  auth 失效用例也加强为角色/创作者均丢弃且 loading 收尾，另锁定翻页在途重搜
  不泄漏 `loadingMore`，并覆盖 auth 清理/重拉、空页限次续拉、失败不重试风暴与
  原始 query 归因一致性。最终 head 因此预计 **708 条**；遵守本仓本机验证边界，
  **未重跑 Gradle，交 PR G1 验证**
- `:tipsy-auth:testDebugUnitTest` 15 条全绿
- lint 无新增（baseline 仍 5 条，未改 baseline 文件）；
  `assembleGooglePlayDebug` 与 `assembleDirectApkDebug` 通过
- **未动** manifest / RN 依赖 / flavor → release manifest diff 本刀不适用
- **真机冒烟 `PASS`**（2026-08-13，directApk）：输入建议词、回车搜索、
  角色/创作者 tab 切换、翻页、最近搜索写入与点击回填、热门词点击、
  清空历史确认弹窗 → 列表清空，**force-stop 冷启动后历史仍为空**（清空
  真落库，不是只清了内存）。
  ⚠️ 冒烟坑：设备上装了两个 Tipsy（`ai.lightspeed.tipsy` 本迁移壳 +
  `com.tipsyturbo.app` 线上包），**深链 `adb shell am start` 会弹 Open with
  选择器**并可能把截图打到线上包上（一度 `topResumedActivity` 是线上包）。
  自动化一律用 `monkey -p <pkg>` 按显式包名启动，别走深链。
  另：清空历史的落库校验**不能用 grep MMKV 文件**判定 —— MMKV 是 append-only，
  删除后旧字节仍在文件里（实测被清的词 id 仍能 grep 命中），冷启动后的 UI
  才是判据。
- **NOT RUN**：GooglePlay 打码行为（搜索结果无词表命中项）；RuStore flavor
  未单独 assemble（flavor 零改动）；P2 筛选器相关全部未测（未实现）

### 2.32 W3 他人主页：第一条端到端可用路径（2026-08-14）

Search 的创作者点击换真页。新增 6 文件 1,469 行（`PublicProfileApi` /
`PublicUserProfile` / `PublicProfileState` / `PublicProfileViewModel` /
`PublicProfileScreen` / `PublicProfileFragment`），**`AppRoute.UserProfile`
进生产白名单**。

选它的理由：此前**四个原生页的主操作点全部点不动** —— `enabledRouteTypes`
只有 `Search`，Home/ChatList/Search 的卡片点击（`ChatDetail`）与 Profile
五个出口全走 `rejectNotEnabled`。他人主页是**唯一不依赖 Surface 矩阵就能
打通真实出口的包**（纯原生 Fragment 不开 Surface），解锁 ChatDetail 仍要先收 P9。
**这是壳的第一条端到端可用路径**：搜索 → 创作者 → 他人主页 → 返回。

开工前审计推翻了「复用自己视角基础设施」这个直觉前提 —— 七处偏差
（1–4 是最初审计，5–7 是写码时二次核实补的），全部已按实测落地：

> ⚠️ **第八处偏差后来在 P7 补出**（2026-08-19，§2.44）：RN 的 `ProfileHeader`
> 自己/他人两个视角都渲染昵称下方的社交渠道图标（数据他人视角来自
> `/user/get/public` 的 `display_urls`），本节的头部实现**没有**这排图标。
> 展示层可直接复用 `ProfileSocialLinks`，留待他人主页后续包。

1. **他人主页只有 1 个 tab，不是 5 个**（`CharacterGrid.tsx:980-1005`）：
   `isSelf` 否分支的 `return [...]` 里只有一个元素。该分支上方注释写
   「他人主页显示角色和视频两个tab」，**注释与代码不符，代码是真值**。
   照注释或照自己视角做，会多出一到四个无数据源的空 tab。
2. **数据源与自己视角几乎无一条相同**，且 `size` 是 **200**（`useProfile.tsx:30`）
   而非自己视角的 10/20：头部 `/user/get/public`、统计 `/user/stats_info` 的
   **另一个函数** `getPublicFollowerInfo`、列表 `/character/list/creator`
   **与** `/character/list/creator/v2` **两个都发**、记忆 `/plot/list/creator`。
   v2 非空时用 v2（含 game）、空则回落 v1，**壳要实现这条优先级**而不是二选一。
3. **⚠️ 头部接口不是公开的**：`/user/get/public` 走 **`axiosAuth`**
   （`apis/user.ts:49`，名字里的 public 指的是「查他人公开资料」不是免鉴权），
   而 `axiosAuth` 无有效 token 时会 `requestLogin('axios-auth')` 并 reject
   （`utils/axios.ts:148-175`，壳宿主 `isShellAuthHost()` 为真）。这与
   `AppRoute.UserProfile.requiresAuth = false`（游客可浏览）**存在张力**：
   游客点创作者会得到登录页。**这是 RN 现网行为，壳按 REQUIRED 接线即对等**；
   改成 OPPORTUNISTIC 会让 401 与登录弹窗时序偏离现网，**不要顺手"修正"**。
   要真游客可浏览需后端换实例，属独立决策。
4. **关注按钮要做，且是 toggle 单端点**（⚠️ 本条订正了 §2.32 初稿）：按钮在
   **`ProfileHeader.tsx:200-225`**，不在 `user-profile.tsx` —— 初稿只搜了后者
   就断言「不做关注按钮」，是错的。`POST /user/follow/user`（REQUIRED）同一
   路径既关注也取关，靠后端翻转 `status`；`isFollow` 来自
   `/user/get/public` 的 `is_followed`。**成功后重拉 `/user/get/public` + stats**
   （`useProfile.tsx:241-243` 两个 mutate），不是本地翻转 —— 本地翻转会让
   followers 计数不动。`is_deleted` 用户整块不渲染按钮。
   §2.25 的 owner 决策点 1（Follow **列表**出口）仍不阻塞本刀 —— 那是点
   followers 数字进列表页，与这个按钮是两件事。

5. **他人主页的列表实际上翻不了页**。`onEndReached` 的 `tabIndex === 0` 分支只调
   `loadMoreCreated()`（`CharacterGrid.tsx:1398-1401`），而 `useCreatedList()`
   是**无参调用**、内部读 `useUserStore` 的自己 uid、打 `/user/created/list`。
   他人主页的两条 creator 列表都**没有 `setSize` 出口**（`useProfile.tsx` 只导出
   `selfCharSetSize`）。所以他人主页看到的恒是**首页 200 条**，触底调的是
   自己那条列表的翻页。**壳按「单页 200、不翻页」实现即对等**；照「三列网格
   就该能翻页」补分页会比 RN 多拉数据。（`size: 200` 的选择本身就说明了
   RN 侧是拿单页当全量用。）
6. **记忆 tab 在他人主页拿不到**：`useProfileMemories(userId)` 全仓唯一调用点
   （`CharacterGrid.tsx:250`）**不传第二参**，而 `isPersonal` 默认 `true`
   （`useProfileMemories.ts:19`）—— 所以 `/plot/list/creator` 那条 SWR key
   恒为 `undefined`，**该端点在现网从未被调用**。这与第 1 条（他人只有 1 个 tab）
   自洽：没有记忆 tab，自然不需要 creator 记忆。**壳不要实现
   `/plot/list/creator`**，方案 §8.1 之前把它列进他人主页数据源是照 API 层
   推的，不是照调用链核的。
7. **他人主页头部与自己视角有四处结构差异**（`CharacterGrid.tsx:1422-1445`）：
   ① 关注按钮取代 Edit Profile（见第 4 条）② **无钱包卡**（`isSelf &&
   UserProfileGems`）③ bio 走另一个组件 `UserBio`（198 行，maxLines 3）而不是
   `RenderBio` ④ **`FollowInfo` 两端都渲染**（四统计不是自己独有）。
   另：`isDeleted` 时连下拉刷新都禁用（`refreshControl={isDeleted ? undefined : ...}`）。

埋点：**事件名与自己视角相同**（`page_exposure` + `page_name: profile`，
RN 两处都发同一个，不按视角分流），区分在参数 `entry_type`（他人 `stack` /
自己 `tab`）与 `is_self`（`user-profile.tsx:201-203`）。别新造
`other_profile_exposure` —— 那会让同一漏斗两端对不上。

#### 实现上与 RN 的两处刻意差异

- **v2/v1 改成串行**：RN 两个 `useSWRInfinite` 并发发，靠三元择一
  （`CharacterGrid.tsx:980-983`）。壳先发 v2，**有货就不发 v1** ——
  v1 结果在 v2 非空时永不上屏，并发只为 SWR 的缓存形状。行为对等，
  代价是 v2 空时多一个串行 RTT。⚠️ **v2 失败也必须回落 v1**，不只是空：
  只在空时回落会让 v2 故障时壳空白，而 RN 那边照样有内容（SWR 给
  `undefined`，三元同样落 v1）。单测两条分别锁死。
- **v1 扁平元素补 `item_type`**（`CreatorListPage.parseV1`）：v1 元素没有
  该字段，而 `ProfileCreatedItem.parse` 认不出会整条返回 null ——
  不补的表现是**回落路径恒空**，而回落只在 v2 缺数据时才走，联调极难发现。
  包装时**复制而非原地 put**，否则会污染 `rawJson`（编辑入口要原封透传那份）。

#### auth 轨闸门：自查时发现的真实缺陷

首版只靠 `job.cancel()` 防登出串号，**不够** —— 取消只在挂起点生效，
取消发生后、协程抛 `CancellationException` 之前，写 `_state` 的非挂起代码
照常执行。具体后果：登出瞬间在飞的 `/user/get/public` 带着旧账号
`is_followed = true` 回来，把刚清掉的关注态写回去，表现正是
**「登出后仍显示 Following」**—— 本刀专门要避免的那一条。
§2.25 已在 `CurrentUserStore.refresh` 上踩过同型（`runCatching` 吞取消）。

已改为**发请求前捕获 auth 快照、每个回写点前校验**（`Generations.isAuthValid`，
**只校验 auth 轨** —— 他人主页不拥有乐观列表变更，全量 `isValid` 会被
ChatList 的 mutation bump 误作废，同 Search 的推理）。且资料与统计**逐步校验**：
两者之间还有一个挂起点，中途换号会得到「A 的资料配 B 的数字」。

另一条同类：**换目标（bind 新 userId）也要取消在飞的关注链** ——
它成功后会重拉**上一个** userId 的资料，把 A 的昵称头像写进 B 的页面。
auth 轨挡不住（没换号），故回写前额外比对 `userId`。三条时序各有单测锁死。

#### Router：`onDestinationClosed` 加谓词版

`UserProfile` 有第二字段 `recommendationContextJSON`，Activity 侧拿不到当初那条
路由的归因参数 —— 相等判定永远匹配不上，去重会一直挂着，表现为
**「从某人主页返回后再点同一个人永远打不开」**（§2.31 Search 那条坑的带参版）。
按类型清又太粗：A → B 的合法叠栈里 B 出栈会误清 A。故加
`onDestinationClosed { predicate }`，Activity 按 userId 匹配。

⚠️ **他人主页的幂等判定按 userId 分，不是只按 tag**：它**可以合法叠栈**
（A 的主页 → A 的角色 → 另一个创作者 B 的主页）。只按 tag 判会让
「从 A 点进 B」被当成重复请求静默丢弃 —— 正是 §8.3 禁止的 silent no-op。
故 Fragment tag 带 userId（`user_profile:<id>`）。

#### 未做（明确边界）

- **bio 的展开/收起**：`UserBio` 超 3 行时右下角有放大镜按钮，依赖
  `onTextLayout` 测实际行数才决定按钮是否出现（`UserBio.tsx:56-72`）。
  本刀只做折叠态 3 行截断 —— 先保证 bio 能看到，展开属独立视觉增强。
- **他人 UID 不显示**：RN 的条件是 `!isSelf && publicUserIdText && !isDeleted`
  （`user-profile.tsx:162`），但那个 `publicUserIdText` 是自己那份 UID 文本的
  复用，在他人分支上是否真上屏未在真机核实。宁可少一个元素，
  也不显示一个**可能是自己 UID** 的字符串。
- **四统计不可点**：Follow 列表出口尚未定案（本节 owner 决策点，RN 无
  FollowSurface）。自己视角那两个数字可点是因为 `AppRoute.Follow` 已备好
  （当前也被拒绝）。
- 卡片点击进详情（`ChatDetail` 仍在 P9 前 disabled）、⋮ 菜单与批量选择
  （`isSelf=false` 的那两处差异，两端本刀都没做，故直接复用 `ProfileGridItem`）。

#### 验证

- app 单测 **754 条**（+46：`PublicProfileParserTest` 13 + `PublicProfileViewModelTest`
  30 + `AppRouterTest` 净增 3），failures=0、**skipped=0**
- `:tipsy-auth:testDebugUnitTest` 15 条全绿
- lint 无新增（baseline 仍 5 条，**未改 baseline 文件**）；
  `assembleGooglePlayDebug` 与 `assembleDirectApkDebug` 通过
- **词条零新增**：复用 `Follow` / `Following` / `Back` / `No Character` /
  四统计标签，**26 locale 全部命中**（脚本核实，ja/en 抽查有译文）。
  **连续第五包零 submodule 改动**，pin 仍 `5a58be9d1`
- **未动** manifest / RN 依赖 / flavor → release manifest diff 本刀不适用
- **真机冒烟 NOT RUN** —— 待跑：搜索 → 创作者 → 主页主链路、关注/取关往返与
  粉丝数变化、注销用户态（无按钮 + 无下拉刷新）、v1 回落路径（需构造 v2 空的账号）、
  A → B 叠栈与返回后重开同一人、登出时在飞响应不写回关注态

### 2.33 W3 Settings 列表 + 语言页（2026-08-14）

新增 `pages/settings/`（8 文件 1,501 行）：原生设置列表 + **原生语言页**。
`AppRoute.Settings` 进生产白名单；新增 `AppRoute.SettingsSubScreen`
（7 个 Surface 子屏，**刻意不启用**）。

补的是一个**真实的功能缺失** —— 此前壳内**没有任何入口能改语言**。
i18n 机制 W1 就完成了（§2.16），但那只是「能显示各语言」，不是「用户能选」。

开工前审计订正了三处文档错误（方案 §8.1 两行、本文 §2.16 与横切表 i18n 行），
以下审计结论全部已按实测落地。

#### ⚠️ 订正一处读反了的归属：语言页要**原生实现**

本文 §2.16 曾写「语言设置页刻意不迁，**仍在 `SettingsSurface` 里**」——
后半句不成立。三处独立证据：

1. `SettingsSurface.tsx:34-44` 的 `KNOWN_SCREENS` **刻意不含 `Language`**，
   注释原文：「`'Language'`（语言页**原生**：壳是语言唯一写入者，
   onLanguageChanged 单向广播）」。
2. `index.surfaces.js:84-85` **刻意不调** `hydrateSupportedLanguages`，
   注释原文：「消费页（语言设置）壳内为**原生**」—— 所以壳里
   `config-persist` 的 `supportedLanguages` **恒为空**，壳必须自己拉。
3. iOS 侧对应物是原生 `Tipsy-iOS/Tipsy-iOS/Pages/Profile/LanguageViewController.swift`。

方案 §1.3 归属表写的 `Native / W3` 才是对的；§8.1 那行「刻意不迁」指的是
**不由 RN 承载**，措辞容易被读成「壳也不做」。按错的理解会把语言页整个漏掉，
而**漏掉不报错**：机制在、只是没入口，本地测试一切正常（设备语言恰好合适）。
这正是 §4.8 记的 iOS 教训类型「英文环境测试看不出来」。

#### 语言页的实测契约

- **可选集合要壳自己拉**：`POST /supported_languages`（`axiosPublic` →
  **OPPORTUNISTIC**）。RN 侧由 `hydrateSupportedLanguages` 填 store，但壳内
  那个 hydrate 刻意不跑（见上）。⚠️ 拉不到时 RN 的表现是**空列表**
  （`isLoading = languages.length === 0` 恒 true）—— 壳要给错误态，
  不能照抄成永久 loading。
- **写入 `POST /user/set_language`（REQUIRED）→ 再 `updateUserInfo()` 重拉
  `/user/info`**（`useChangeLanguage.ts:64-67`）。**不经 Zustand 信封** ——
  所以不受 §2.23.1 那个信封缺失缺陷影响。
- **⚠️ 是「先应用后保存」的乐观流，且失败不回滚**（`useChangeLanguage.ts:60-72`
  + `language.tsx:29-40`）：点 Done → **立即 `goBack()`** → 后台 `handleDone()`
  → `i18n.changeLanguage` 先本地切、再打接口。接口失败只弹
  `Save failed` Toast，**本地语言已经切了不还原**。壳照此实现；
  写成「等接口成功再切」会让用户感到明显卡顿（与现网体感不同）。
- **两段选择态**：`tempSelectedLanguage`（点行只改这个）vs `selectedLanguage`
  （Done 才提交）。Done 按钮在两者相等时**不可点**（`isDoneActive`）。
- **⚠️ 设备 locale 兜底那条规则与账号码不同**：`languageCode` 为空时取设备语言，
  且 **`zh` 开头一律映射成 `en`**（`useChangeLanguage.ts:31-33`）——
  与账号码走的 `normalizeLanguageCode`（`zh` 系归 `zh-tw`）**给出不同答案**。
  壳已有两条独立规则（`LanguageCodes.normalize` / `fromDeviceLocale`），
  别在这里混用。

#### Settings 列表的渠道 gating（逐行核实）

列表本体 `page.tsx` 430 行。**九处 `!isGooglePlay` 门控**：Subscription、
Security、Community Guidelines、Terms of Service、Official Website 等。
另两个独立条件：`isAndroidWidgetSupported` 控 Add Widget、
`shouldShowNsfwSetting(isAndroidAPK)` 控 Limitless 开关。

⚠️ **`isAndroidAPK` 是 directApk 一个渠道**（`Application.applicationId ===
PACKAGE_NAME_APK`），不是「所有 Android」—— GooglePlay 与 **RuStore 都不显示**
Limitless。壳侧要按 flavor 判，别写成 `Build.VERSION` 或平台判定。

**Limitless 开关是 `nsfw` 的唯一写方**（订正方案 §8.1 Home 行的
「App 不回写后端」）：`POST /user/nsfw` 成功后才写本地镜像并重拉
`hydrateTags`。正确表述是「Home/筛选侧不回写，写入只在 Settings 一处」。

`Account & Security` 行是**本地展开/收起**（不是导航），展开后才出现
Security / Blocked / Delete Account 三行。

#### 子页出口：7 屏走 `SettingsSurface`，本刀先明确拒绝

`KNOWN_SCREENS` = Security / Blacklist / Feedback / About / ContactUs /
Delete / Widget。该 Surface **未过 §9.1 矩阵**，故本刀按 Profile 第一刀同款
做法：出口类型定义好、点击走 `rejectNotEnabled` 记明确错误，**不留 TODO
让点击无反应**（§8.3）。Community Guidelines / Terms / Official Website 三行
是**外部链接**（`WebBrowser` / `handleLink`），不经 Surface，可本刀就通。

登出按钮走已有的 `AuthStateHub` 链路（壳已是 auth owner），确认弹窗照抄
`Are you sure you want to log out?`。

#### 词条：bump submodule（打破连续五包零改动）

逐个核实 22 个词条，**20 个已在 SHELL_KEYS**，缺 **2 个**：
`Limitless` 与 `New Version`。两者在 RN 侧**有译文**
（`ja` 分别是「無制限」「新しいバージョン」），只是没进导出清单 ——
即典型的 §4.8 iOS 教训①：「新增原生页文案必须加入词条白名单并重跑导出，
否则非英文用户静默看英文，**英文环境测试看不出来**」。

已加入 `SHELL_KEYS` 并重跑导出：**26 语言全命中，en 184/184 条，0 missing**。
submodule pin `5a58be9d1` → **`017e142ac`**（已推远端，CI 可拉）。
`Limitless` 只在 directApk 可见，但词条不按渠道分表 —— 26 locale 都要有。

#### 实现要点

- **渠道 gating 收在 `SettingsRow`，不散在 Compose 里**。RN 那 9 处
  `!isGooglePlay` 抄成 9 个 Compose `if`，漏一处的表现是
  **「GooglePlay 版多出一行不该有的入口」** —— 会被商店审核抓，
  且本地跑 directApk 完全看不出来（那个渠道所有行都显示）。
  行是数据（带 `visibleIn` 谓词），UI 只 filter 一次；`SettingsRowTest`
  因此能对**三个渠道各断言一遍**，含「RuStore 不显示 Limitless」那条。
- **两个页面共享一个 ViewModel**（Activity 作 `ViewModelStoreOwner`）：
  可选语言列表与当前语言两份数据跨页复用，拆开会让语言页每次打开都重拉
  （RN 侧那个列表在 store 里也是跨页的）。
- **URL 常量必须在文件作用域**，不能放 enum 的 companion —— enum entries
  先于 companion 初始化，entry 构造里引用会编译失败
  （`Companion object is uninitialized here`）。三个 URL 逐字核实自
  `page.tsx:292,303,313`，单测锁死。
- **`AppRoute.SettingsSubScreen` 与 `AppRoute.Settings` 是两个目标**：
  前者是 7 个 Surface 子屏（未启用），后者是列表本体（已启用）。
  子页传成 `Settings` 会「点子页又打开一层设置列表」。
  `SurfaceProps` 的穷尽 `when` 在编译期强制处理了新 route ——
  这正是当初不写 `else -> null` 的收益。
- **外部链接要捕获 `ActivityNotFoundException`**：设备可能没有浏览器
  （精简 ROM / 企业设备），不捕获会直接崩，而 RN 侧那个 await 不会崩 App。
- **语言保存失败的 Toast 挂 Activity 而非本页面**：点 Done 立即出栈，
  Toast 弹出时本 Fragment 的 view 已销毁 —— 挂在页面上会静默丢失，
  表现为「保存失败但用户完全不知道」。

#### 与 RN 的一处刻意视觉差异

Done 不可点时给一档 alpha。RN 的 `doneText` 恒白、不可点时**无任何视觉反馈**
（`onDone` 开头直接 return）—— 「按钮在那儿但点不动且看不出为什么」是
可发现性问题，属可接受的视觉 diff（记在验收里）。

#### 验证

- app 单测 **792 条**（+38：`SettingsRowTest` 12 + `SupportedLanguageParserTest` 4
  + `SettingsViewModelTest` 19 + `AppRouterTest`/`SurfacePropsTest` 净增 3），
  failures=0、**skipped=0**
- `:tipsy-auth:testDebugUnitTest` 15 条全绿
- lint 无新增（baseline 仍 5 条**未改**）。⚠️ lint 硬门抓到一处真实问题：
  `Uri.parse` 应用 KTX `String.toUri`，已改 —— 这类问题本机不跑 lint 就会带到 CI
- `assembleGooglePlayDebug` 与 `assembleDirectApkDebug` 通过
- **未动** manifest / RN 依赖 / flavor → release manifest diff 本刀不适用
- **真机冒烟 NOT RUN** —— 待跑：三渠道行序差异（尤其 GooglePlay 少五行、
  RuStore 无 Limitless）、语言切换真机往返（切完 RN Surface 侧是否同步）、
  Done 乐观流与失败 Toast、账号安全展开、7 个子页被拒绝、三个外链能打开、
  登出链路、分级开关（仅 directApk）

### 2.34 W3 Search P2 筛选器（2026-08-14）

新增 4 文件 723 行（`SearchFilter` / `SearchTagOrder` / `SearchFilterDrawer` /
`SearchTagBar`），扩 `HomeTag` 三字段。Search 从 P1 补到**完整对等**：
性别/排序/分级筛选抽屉 + 二级横滑标签栏。

Search P1 主链路已由 owner 在模拟器验过（2026-08-14）。

#### ✅ 好消息：P1 已把管线铺好，本刀是纯 UI + 状态

`SearchApi` 的 `CharacterSearchQuery` **早就有** `gender` / `sorting` /
`contentRating` / `tagIds` 四个字段，且 `gender` 为 null 时**整键不发**
（对齐 RN 的 `delete params.gender`）。P1 只是在调用点把它们**硬编码**成
`null` / `Recommended` / `All` / `emptyList()`（`SearchViewModel.kt:276-282`）。
所以本刀不动 API 层，只把真实状态串进去。

#### 三组筛选值（逐个核实 `constants/common.ts`）

- **`SexList`** = `All` / `Female` / `Male` / **`Non-binary`**（⚠️ 带连字符，
  不是 `NonBinary` —— Home 侧的枚举是另一套写法，别复用）。
  映射：`Female`→`female`、`Male`→`male`、`Non-binary`→`other`、
  **`All`→ 整键不发**（`useSearch.ts:104-121` 的 `default: undefined`
  加 `delete params.gender`）。
- **`SearchSortingList`** 五值，**UI 文案 ≠ 后端枚举**
  （`SearchSortingValueMap`）：`Most Interacted`→`MostInteracted`、
  `Most Liked`→`MostLiked`、`Most Favorited`→`MostFavorited`，
  `Recommended` / `Latest` 两端同名。**认不出的值回落 `Recommended`**
  （`?? 'Recommended'`）。
- **`ContentRatingList`** = `All` / `SFW` / `NSFW`，**值即契约**。
  ⚠️ **三重 gating**：`Platform.OS === 'android' && !isGooglePlay && nsfw`
  （`FilterDrawer.tsx:55-57`）—— 侧载渠道**且**全局 nsfw 开关打开才显示。
  不显示的渠道**固定提交 `All`**（`:75-79` 注释「与线上一致」），
  不是不发这个键。

⚠️ 底部按钮是 **`Reset` + `Done`**，不是我先前计划里写的 `Apply`
（`FilterDrawer.tsx:193-203` 实测）。抽屉标题是 **`Sort by`**，不是 `Filter`。

#### ⚠️ 标签栏排序规则与壳现有 `HomeTag` **不兼容**

`deriveResultTagOrder`（`searchTagOrder.ts` 81 行，配 144 行单测）的排序是
四层优先级：① 选中项按**选择顺序**置前 ② 「特殊呈现」标签优先
③ 有 `sort_order` 时按它排、否则按**配置顺序** ④ 同序按 `tag_aggs`
的**聚合顺序**兜底。

壳的 `HomeTagParser` **把 `sort_order` 排完就丢掉了**
（`HomeTag.kt:47` 注释「只用于排序，不进模型」）——
而这里需要**原始值**（判「有没有 sort_order」这一层），
且需要「配置顺序」与「特殊呈现」两个额外信息。所以本刀要么扩 `HomeTag`
带上这些字段，要么给 Search 单独一个标签模型。**倾向扩 `HomeTag`**：
两处标签目录来自同一个 `/character/tags`，两套模型会漂移。

**「特殊呈现」的判定**（`hasSpecialPresentation` + `resolveTagDisplay`）：
`isEvent || iconRenderKind !== 'none' || watermarkRenderKind !== 'none' ||
textColor` 任一为真。落到 API 字段是 `is_event` / `icon_type` + `icon_value`
/ `watermark_url` / `text_color`，**外加一张 6 条的 legacy 名称回落表**
（`tagDisplay.ts:73`：Halloween 2025 / Christmas 2025 / Valentines2026 /
Under The Mask / Brewing & Coding / NewStart —— 按 id 或 alias 匹配）。

⚠️ `resolveTagDisplay` 本体 441 行是**活动标签的图标/水印呈现配置**，
本刀**不迁**：壳的标签行还没有 lottie / 水印渲染。只取
`hasSpecialPresentation` 需要的那个布尔判定 —— 但**必须含 legacy 表**，
否则万圣节这类历史活动标签的排序位置与现网不同。

#### 标签数据源：`tag_aggs` 不是标签目录

横滑栏的 id 列表来自**搜索响应的 `tag_aggs`**（`useSearch.ts:148-149`），
是「本次全部命中结果的标签聚合」；标签的**展示信息**才来自
`config-persist` 的标签目录（壳侧 = `/character/tags`）。
两者是「哪些标签有结果」与「这个标签长什么样」的关系。

⚠️ **选中标签后 `tag_aggs` 保持不变**（后端聚合时剔除 tags 筛选条件，
`SearchTagBar.tsx:21-23` 注释）—— 壳不要在选中后重算这个列表，
否则选一个标签就会让其余标签消失。

#### 词条：bump submodule

18 个词条里 8 个已在 SHELL_KEYS，缺 **10 个**：`Sort by`、`Content Rating`、
`Close`、五个 sorting 文案、`SFW` / `NSFW`。全部在 RN 侧有译文，同 §2.33 的情形。
已导出：**26 语言全命中，en 194/194 条，0 missing**。
pin `017e142ac` → **`a6b9fc56a`**（已推远端）。

⚠️ 顺带记一处**两壳文案不同轴**：导出清单里原有的 `Sort & Filter` / `Apply`
是 **iOS 壳**的筛选器文案，而 RN 的 `FilterDrawer` 实测是标题 `Sort by` +
底部 `Reset` / `Done`。两者都保留（SHELL_KEYS 不按平台分叉）——
但**Android 要用 RN 那套**，照 iOS 壳的 key 做会与现网 Android 用户看到的不同
（index.md 硬性纪律的「UI 照 RN 的对应平台分支」同型）。

#### 实现要点

- **P1 的管线直接可用**：`CharacterSearchQuery` 四个筛选字段早就在，
  P1 只是把它们硬编码成默认值。本刀不动 API 层。
- **`HomeTag` 扩三字段**（`sortOrder` / `configIndex` / `hasSpecialPresentation`）
  而不是给 Search 建第二个标签模型 —— 两处目录同源，两套模型必然漂移。
  ⚠️ `sortOrder` **必须保留 null 与 0 的区别**：`hasKnownSortOrder` 是
  集合级判定，存成 0 会让「全无 sort_order」那条分支永远走不到
  （单测 `全无 sort_order 时按配置数组顺序` 锁死）。
- **`configIndex` 取排序后的下标**：RN 遍历的目录数组本身已按 `sort_order`
  排过（`config_persist.ts:297`），所以对等的是排序后序不是接口返回序。
- **筛选重查走 `isRefreshing` 不走 `isLoading`**：保留旧列表 + 不切 tab +
  不发 `search_trigger_page_exposure`（那是「提交了新搜索词」的事件）。
  清空会让筛选时列表闪空，RN 专门为此拆了 `refreshing`。
- **标签点击立即重查**（无需 Done），而抽屉三项要点 Done 才生效 ——
  两种交互刻意不同，对齐 RN。
- **展开按钮用近似判定**：RN 靠 `onLayout` 测真实溢出
  （`contentWidth > containerWidth + 1`），壳用「标签数 > 6」近似。
  **已知偏差**：标签少但文案极长时壳可能不给展开按钮，反之多给一个。
  只影响换行显示、不影响筛选结果，记在验收里。

#### 验证

- app 单测 **833 条**（+41：`SearchTagOrderTest` 8 + `SearchFilterTest` 21 +
  `SearchViewModelTest` 净增 12），failures=0、**skipped=0**
- ⭐ `SearchTagOrderTest` **逐条对拍 RN 的 `searchTagOrder.test.ts`**
  （六个用例名与断言照抄，方案 §8.2 的用法）—— 四层优先级任一层写错都会被抓
- `:tipsy-auth:testDebugUnitTest` 15 条全绿
- lint 无新增（baseline 仍 5 条未改）；googlePlay + directApk assemble 通过
- **未动** manifest / RN 依赖 / flavor
- **真机冒烟 NOT RUN** —— 待跑：抽屉三段渲染与 Done/Reset、性别 All 不发键、
  排序枚举生效（结果顺序真的变）、**分级三重 gating**（directApk+nsfw 开才出现，
  GooglePlay 一定不出现）、标签 toggle 与选中置前、展开/收起、
  筛选重查不闪空、翻页带筛选条件

### 2.35 W4 Screen（Tab1 大屏页）P1（2026-08-14）

新增 `pages/screen/` **10 文件 2,131 行**：AB 端点分流 + 竖向全屏翻页 +
归因 + 首屏缓存 + 会话埋点 + 静态图/GIF 两形态。
**Screen Tab 从占位页换成真实页面 —— 五个 Tab 至此全部有真实实现。**

⚠️ **P1 不引 Media3、不播视频**：`showcase` 形态显示 `thumbnailUrl` 静态封面。
OOM 风险因此为零。owner 2026-08-14 决定：
真机冒烟推迟到功能全部完成后统一做，所以本刀起**待验清单只累积不清空**
（当前已累积三刀 22 项，见 §2.32 / §2.33 / §2.34 的 NOT RUN 段）。

Screen 是方案 §8.1 标「**放最后**」的一块，也是唯一**首要风险是 OOM**
而不是数据正确性的页面。它同时是 **W4 的第一块** —— W3 只剩 ChatList P2 Map
与 Profile P5（后者一半被未启用 Surface 阻塞）。

#### 三处方案订正（都已改方案 §8.1 Screen 行）

1. **`page_size` 读反了**。原文写「`page_size` 参数名与 Home 的 `size` **不同**，
   勿混」—— 实际 `page_size` 只是 `getScreenList` 的 **TS 形参名**，
   请求体里发的是 **`size`**（`screen.ts:36` `size: params.page_size ?? 20`）。
   两个端点线上**同名**。照原文实现会发一个后端不认的 `page_size`，
   而后端很可能回落默认页大小 —— **不报错**，只是分页边界与现网不同。
2. **AB 分流是 Android 专属，且要求已登录**（`screen.tsx:191-197` +
   `abConfig/service.ts:23-27`）：`Platform.OS !== 'android'` 恒走 distribution；
   `ownerUserId` 为空（游客）时 `resolveConfigsForCurrentOwner` 直接返回 `{}`，
   `?? false` → **也恒走 distribution**。所以壳侧：**游客与未 settle 的登录态
   都必须走 distribution**，只有已登录且 flag 为真才走 recommendation。
   flag key `enable_recsys_in_home_show_case`，bundle 名 `tipsy-chat-app`。
3. **CTA 不是「恒普通聊天页」**。原文写「进聊天恒普通聊天页（不走 html 分流）」
   —— 实测走 `resolveChatEntryScreen` **四路分流**（`screen.tsx:655-704`）：
   `ChatDetailPage` / **`ChatDetailHtml`**（`characterType===1 && contentType===2`）/
   `Interactive` / `MultiCinema`（`characterType===2`）。Screen 传
   `chatMode: INTERACTIVE` + `isStory: false`，四路都可达。
   ⚠️ 这条与 ChatList 的纪律**相反**：ChatList 侧壳**刻意不复刻**
   `resolveChatEntryMode`（§2.30：只透传判定素材，由 ChatDetailSurface 自决），
   而 Screen 的 RN 代码是在**页面内**分流的。壳侧仍应按 ChatList 那条做
   （透传素材、不复刻分流）—— 但要知道这是**有意偏差**，不是照抄。

#### OOM 是首要风险，且约束是可量化的

现网已有崩溃（`withAndroidLargeHeap.js` 注释：`OutOfMemoryError @
ExoPlayerImplInternal.shouldContinueLoading`，多个 ExoPlayer 并存时
默认堆 192/256MB 不够）。RN 侧的三道闸都已实测到具体值：

| 闸 | 实测值 | 出处 |
| --- | --- | --- |
| `largeHeap` | `true` | ✅ **壳 manifest 已有**（`AndroidManifest.xml:33`，W0 移植） |
| 播放窗口 | **`abs(index - currentIndex) <= 1`** —— 只挂当前 ±1 三个播放器 | `FeedMediaItem.tsx:594` |
| buffer 上限 | min 2500 / max **5000** / forPlayback 500 / afterRebuffer 1500 / backBuffer 2000 / 磁盘缓存 **50MB** | `FeedMediaItem.tsx:600-609` |

⚠️ **`largeHeap` 已在但 Media3 依赖还没加** —— `app/build.gradle` 与
`libs.versions.toml` 都搜不到 media3/exoplayer。加依赖时注意方案 §8.1 的
「**三件套必须同时到位**」：largeHeap + 有界池 + 图片内存上限。
只加播放器不设上限就是复现现网崩溃。

#### 现成 fixture（方案 §8.2）

- `recommendationAttribution.test.ts`(92) + `showcaseFirstScreenFeed.test.ts`(53)
- `lib/screenRecommendationTracking/` **1,040 行**测试（manager 384 + models 214
  + homeTracking 215 + exposureTracker 110 + queue 97 + retry 20）
- `chat_mode_lru.test.ts`(143) —— CTA 四路分流的判定

#### 两处容易写错的实测细节

- **`position` 用去重**前**的下标**（`recommendationAttribution.ts:55`
  `page * pageSize + rawIndex`）—— `rawIndex` 来自 `list.entries()`，
  而同一循环里 `seenCharacterIds` 会 `continue` 掉重复项。所以去重后
  第 3 条的 position 可能是 4。**照抄** —— 用去重后下标会让归因位置与后端对不上。
- **首屏合并的 `slice(1)` 只在冷启动路径生效，且与「缓存第 0 条」配对**
  （`screen.tsx:826-838`）：`pageNum === 0` 时先把 `nextMediaList[0]`
  **写进缓存**，再走 `mergeShowcaseFirstScreenFeed` —— 那里
  `networkItems.slice(1)` 丢掉的正是刚被缓存的那条，改由 `cachedHeadItem`
  顶到列表头。所以**不丢数据**：缓存命中时头是上次的缓存卡，
  未命中时头是本次网络第 2 条（第 1 条进了缓存、下次冷启动才上屏）。
  **下拉刷新走另一条路**（`isRefresh` → 直接用 `nextMediaList` 全量，不 slice）。
  ⚠️ 壳必须把「写缓存」与「合并」当成一个原子步骤实现 —— 只抄 `slice(1)`
  不抄写缓存，会让首屏真的少一条。

#### 分包（owner 2026-08-14 已定：先做 P1，不引 Media3）

`screen.tsx` **1,492 行** + `FeedMediaItem.tsx` 982 + 五个支撑文件，
且带 Media3 依赖引入。建议：
- **P1（本刀，owner 选定）**：数据层 + 列表骨架 + 静态图/GIF 两形态 +
  埋点会话 —— **不引 Media3**，`showcase`（视频）形态先显示
  `thumbnail_url` 静态封面。这样 **OOM 风险为零**，先把 AB 分流、归因、
  分页、会话埋点这些**数据正确性**问题解决。
  ⚠️ 理由与真机验证推迟有关：OOM **只能真机暴露**，单测与 mock 都抓不到。
  在冒烟推迟的前提下，把 Media3 与数据层放同一刀会让"绿的 CI + 未验的 OOM"
  同时压在一个包里。
- **P2**：Media3 + 有界播放器池 + ±1 窗口 + buffer 三件套。
- **P3**：二期项（动图 WebP 动画、fade 转场预载、点赞增强、分享）——
  方案已标「iOS 至今仍在二期清单」。

#### 两份 RN fixture 抓到我自己两个设计缺陷

写码时按 §8.2 先对拍现成单测，两条都在提交前被抓：

1. **`position` 必须用原始下标**。`recommendationAttribution.test.ts:33`
   钉死 `[无id, a, a, b]` + page1/size10 → **a=11、b=13**（不是 10、11）：
   `rawIndex` 对「无 character_id」与「重复 id」两种跳过**都照样递增**。
   我最初在 `ScreenPage.parse` 就把无效条目过滤掉了 —— 那会让下游下标整体
   前移，**所有归因位置偏移**，而后端按 position 算 CTR、两端都不报错。
   已改成 `parse` **保留 null 占位**（`items: List<ScreenFeedItem?>`），
   过滤与去重统一在 `ScreenAttribution.attribute` 里做。
2. **首屏缓存必须在发请求前读**。RN 的 `cachedFirstScreenMedia` 是
   `useMemo`（`screen.tsx:237`），求值在请求**之前**；写缓存在响应之后
   （`:826`）。我最初写成「先 put 再 get」—— 冷启动时第 0 条既进缓存又被
   `merge` 的 `drop(1)` 丢掉，等价于缓存无效，且首屏顺序与现网不同
   （现网首次从第 2 条开始）。**不报错**。已改成请求前读快照并透传。

另核实 `parseABConfigBoolean` 接受 **`true` / `1` / `yes` / `on`** 四种真值
（`abConfig/value.ts:5-8`）—— 我一开始只认 `"true"`，那会让运营在后台
填 `1` 时 AB 静默失效。

#### 实现要点

- **AB flag 拉取走 `axiosAuth` → REQUIRED**（`abConfig.ts:10`），
  所以游客根本拿不到配置 —— 与「游客恒走 distribution」自洽，未登录时
  不发这个请求。按 owner 缓存（对齐 `service.ts:30-32`）。
- **会话埋点接两条轴**：焦点轴用 Fragment 的 `onStart/onStop`，
  前台轴用 **`ProcessLifecycleOwner`**。只挂 Fragment 生命周期会漏掉
  「按 Home 键出去再回来」，表现为一个跨越数小时的畸形长会话。
- **`VerticalPager` 的 `beyondViewportPageCount` 保持默认 0** ——
  P2 接播放器后它直接决定同时存活的播放器数，也就是 OOM 的来源。
  P1 虽不播视频，先把这个默认坐实。
- **缓存存「接口同形」JSON**，读路径复用 `ScreenFeedItem.parse` ——
  存读走同一个解析器，少一类「存得下但读不回」的 bug。三形态各有往返单测。
  **归因不进缓存**（请求级数据，存了读出就是过期归因）。
- **复用 `HomeCacheStorage` 接缝**而不是直接吃 `LegacyMmkvStore`
  （后者是 final class，测试无法替身）。两处需求相同，没必要造第二个接口。
- **CTA 不复刻 `resolveChatEntryScreen`** —— 与 ChatList 同一条纪律（§2.30：
  由 `ChatDetailSurface` 自决入口屏）。2026-08-20 首进影院黑屏修复后，Screen
  会同时透传列表已有的 `ChatDetailPreload`（见 §2.36），但那仍是判定/首帧
  **素材**，不是壳侧指定目标屏。这是相对 Screen 的 RN 代码（页面内四路分流）
  的**有意偏差**。
- **`TabPlaceholderFragment` 现已无调用方**，但刻意保留：W4 还有页面要接，
  届时目标未就绪时它比空白或崩溃都好。

#### P1 明确未做

视频播放（Media3 + 有界池 + buffer 三件套）、动图真动画（缺 `coil-gif`
artifact，当前只显示首帧）、tagline 展开（`FeedMediaTaglineOverlay` 531 行）、
真输入框（`AppChatBar` 1,400 行，P1 简化成按钮）、点赞写入与 echo 对账、
分享、`layout.ts` 的 iOS inset 数学（Android 不适用）。

⚠️ CTA 仍是接真输入框前的过渡形态；视觉收尾时已对齐 RN/iOS，改回既有词条
`Let Your Story Begin`，并采用 40 高、20 圆角、15% 白色材质底。该词条已随后续
shell locale 导出进入 26 个语言，故本次无需新增词条或 bump submodule。

#### 验证

- app 单测 **876 条**（+43：`ScreenAttributionTest` 22 + `ScreenViewModelTest` 21，
  含**两份 RN fixture 逐条对拍**），failures=0、**skipped=0**
- `:tipsy-auth:testDebugUnitTest` 15 条全绿
- lint 无新增（baseline 仍 5 条未改）；googlePlay + directApk assemble 通过
- **未动** manifest（`largeHeap` W0 就在）/ RN 依赖 / flavor / submodule
- **真机冒烟 NOT RUN** —— 按 owner 决定统一推迟到功能完成后。本刀待验：
  AB 三种组合（游客/登录+flag 开/登录+flag 关各走对端点）、竖向翻页与预拉、
  首屏缓存冷启动秒开、会话埋点四个时机（切 Tab / 按 Home 键往返）、
  归因诊断事件、三形态渲染（含 GIF 只显首帧这个已知偏差）

### 2.36 W1-P9：ChatDetailSurface gate（2026-08-17）

`AppRoute.ChatDetail` 与 `MiniPhoneChat` 进生产白名单 —— **四个原生列表页的
卡片点击第一次真的有下一屏**。此前壳唯一可用路径是「搜索 → 创作者 →
他人主页」（§2.32）；Home/ChatList/Search/Screen 的数据链全通但主操作点
一律 `rejectNotEnabled`。

放开依据：微根 18 项已核对 `ChatDetailSurface.tsx:546-631`（顺序一致）、
5 个微栈目标均在栈内无死链、三个过期桥桩已回填（见下）。
⚠️ 微根核对**不是人工的**：`SurfaceDependencyChecklistTest` 7 条直接读
RN 源文件断言（组件在场 / PortalHost 无遗漏 / SurfaceToastHost 在具名群之前 /
刻意不挂的池初始化器确实不在），改 RN 微根时会红。

#### 开工前查出三个过期桥桩（P9 的真实前置）

`ShellAuthProvider` 里三个 override 的波次标签早已过期、代码却还指向
`notImplemented`（debug **throw**，release 记 error 后继续）：

| 方法 | 标的波次 | 能力实际落地于 |
| --- | --- | --- |
| `requestLogin` | W2（原生 Login 页） | §2.20 |
| `openUserProfile` | W1-P4（Router） | §2.32 |
| `openUserProfileWithRecommendation` | W1-P4 | §2.32 |

- `requestLogin` 是 `axiosAuth` 未登录路径的终点（`axios.ts:160`），
  **每个未登录请求都会打到它**。
- `openUserProfile` 在 ChatDetail 深栈有三个调用点（`comments.tsx:2012`、
  `CharacterProfile.tsx:1291,1294`）。JS 只有 `?.()` **方法存在性**守卫，
  而方法在 Android 模块里注册着（`TipsyAuthModule.kt:123`）—— 守卫过得去，
  throw 变成 promise rejection，且 `:1294` 那处**没接 `.catch`**。
  表现是「聊天页点头像 debug 崩、release 静默没反应」。

根因是**桥能力回填此前零单测覆盖**（`app/src/test/` 里搜不到任何
`openUserProfile` 断言），所以桩能在白名单放开后静默留三天。已补 5 条
断言逐个钉死转发目标，并反向验证过（改回 notImplemented 时 4 条会红）。
两条「未实现项必须可见」的纪律测试主体也从已接通的 `requestLogin` 换成
`notifyOnboardingCompleted`（W4，当前唯一真正未实现的桥方法）。

⚠️ `notifyChatBackgroundReady` **不在**此列 —— Android 模块根本没注册它，
JS 的 `?.()` 直接短路，属预期降级，**别去"修"**。

#### 判定素材：两个平铺 + 两个必须进嵌套 `preload`

壳**不复刻** `resolveChatEntryScreen`，只透传素材由 Surface 自决初始屏
（§2.30 纪律；与 Screen 的 RN 页内分流是有意偏差，§2.35 已记）。
但素材的**通道不同**，逐行核实后确认：

| 素材 | 消费方 | 形状 |
| --- | --- | --- |
| `chatEnterSource` | `props.chatEnterSource`（`:356`） | 平铺 |
| `isStory` | `props.isStory`（`:378`、`:424`） | 平铺 |
| `characterType` | **`preloadState.characterType`**（`:377`） | 嵌套 `preload` |
| `contentType` | **`preloadState.contentType`**（`:378`） | 嵌套 `preload` |

`props.characterType` 在 `resolveInitialParams` 里**全仓零命中** —— 它读
`getChatPreloadCache().getState()`，那份 state 由
`seedChatPreloadFromShell(props)`（`:496`，跑在 resolve 之前）从
**`props.preload`** 灌进去。平铺的表现是 **html 富文本与多角色影院一律落
普通聊天页**，两端都不报错。

故 `SurfaceProps.forRoute` 与 `RNSurfaceFragment.newInstance` 的值类型从
`String` 放宽到 `Any`，`buildInitialProps` 新增 `putRouteParams` 按类型分派
（String/Int/Long/Double/Boolean/嵌套 Map，遇未知类型**抛**而不是跳过）。
**不能全塞字符串**：RN 按 `characterType === 1 && contentType === 2` 严格
比较（`chat_mode_lru.ts:77`），`"1" === 1` 在 JS 里是 `false`；
`isStory` 走 `?? false`，字符串 `"false"` 会被当成真。

#### 顺带修掉一个真实缺陷：`openSurface` 从来没传业务参数

P9 前 `openSurface(componentName)` 只传组件名，**`SurfaceProps.forRoute`
的产出根本没接到调用链上** —— 等于 `characterId` 恒 undefined、聊天页
永远恢复上次会话。正是 `SurfaceProps` 类注释里警告的那型漂移（原实现把
参数塞进无人读的 `route` 子 Bundle），只是此前 ChatDetail 未启用没人踩到。
同时补上 `languageCode`（壳是语言唯一 writer，§2.16）。
`openDebugSurface` 刻意不传（W0 管线 gate，零业务依赖）。

#### 两处容易写错的实测细节

- **入口来源没有 `search`**。`ChatEnterSource` 联合类型只有
  home / big_screen / chat_list / profile / unknown（`navigation/type.ts:21-26`）。
  搜索页复用 `HomeCard`（`CharacterResultList.tsx:88`），传的是 **`'home'`**
  （`HomeCard.tsx:178` 硬编码）。编个 `'search'` 不报错，只是入口模式判定
  落到 else 分支。Screen 必须是 `big_screen` —— RN 靠它把影院 `sourceType`
  判成 `first_tab`（`useChatNavigation.ts:59`）。
- **ChatList 的 `isStory` 不是角标那个判定**。壳原有 `showStoryTag` 多带一条
  `characterType == 2`（角标把多角色也画成 story 样式），而
  `ChatListItem.tsx:286` 写入 preload 的 `isStory` **只看
  `item_type === 'story'`**。复用角标判定会让多角色角色从聊天列表进去
  **看不到影院**（`isStory` 在 `resolveChatEntryScreen` 里优先级最高，
  `chat_mode_lru.ts:74`）。已新增 `isStoryEntry` 与角标分开。

#### 去重必须用谓词版

`ChatDetail` 带参（characterId + 入口/判定/preload 素材），相等判定拿不到那些值就永远
不成立 —— 表现是「退出聊天后再点同一个角色永远打不开」（`UserProfile`
踩过同型，§2.32）。判据用「栈里已无 `ChatDetailSurface` 容器」而不是比对
characterId：两个 route 共用同一容器（同 componentName），壳内不叠两层聊天页。

#### ⚠️ §12 实例关闭链：记为已接受偏差（owner 2026-08-17 决定）

TS 侧 `popSurface()` **无参**，Android 桥固定传 `null`
（`TipsyAuthModule.kt:120`），所以 `MainActivity.popSurface` 的实例比对
**恒短路**成「栈里有 Surface 就 pop」。壳侧其实已把 `surfaceInstanceId`
下发进 initial props（`SurfaceContract.kt:83`），但**RN 侧无人读**（已核实）。

当前只有单层 Surface 容器，弹不错，故按可接受偏差记录。**真出现多层容器
（ChatDetail 内再开 Comments）之前必须根治** —— 根治要改 RN 侧 9 个
`popSurface()` 调用点让它们带上 instanceId，且 `index.surfaces.js` 系文件
是双壳共用，需 iOS 回归。

#### 2026-08-20：Screen 首进影院黑屏根治（完整 preload 契约第一处接入）

现象：从大屏 CTA 第一次进入同一多角色影院，UI/顶栏已出现但背景全黑；退出后
第二次进入正常。根因不在图片组件或 Surface 转场，而在首帧数据契约：Android
此前只把 `characterType/contentType` 放进 `preload`，没有把 Screen 列表已有的
`character.image_url` 带过去。RN `ChatDetailSurface` 会在子树首渲前同步 seed
preload，`MultiCinema` 又只在组件初始化时用 route `imageUrl` 创建
`initialBackground`；首次为空便先渲黑底。第一次 mount 后的
`getCharacterAuth` 把 runtime store / 图片缓存预热，因此第二次看似自愈。

已按 RN `ChatDetailSurfacePreload` 与 iOS `ChatDetailPreload` 的同一 14 字段契约
新增壳侧类型，并让 `SurfaceProps` 继续以嵌套 `initialProperties.preload` 透传：
`nickname/gender/imageUrl/faceUrl/imgPrimaryColor/nsfw/greeting/introduction/
isTranslated/lang/characterType/contentType/greetingVideoUrl/
greetingVideoCoverUrl`。Screen CTA 现在传完整列表子集；其中 `imageUrl` 单独保留
`character.image_url`，不能复用 showcase 的 `backgroundUrl`（视频）或
`thumbnailUrl`（视频 cover）。首屏缓存也按该语义写回，避免冷启动把 cover
误当角色静态图。

这次没有加延时、二次刷新或 RN 页面特判：详情请求仍作为 `isPartial` 的全量回填，
首次背景则由入口已有数据直接满足。Home / ChatList / Search 仍只传现有分流子集，
后续可复用同一 `ChatDetailPreload` 类型逐入口补齐，不影响本次 Screen 根修。

回归断言已补两层：`SurfacePropsTest` 钉死 14 字段全部位于嵌套 `preload` 且
Boolean/Int 类型不丢；`ScreenAttributionTest` 钉死 showcase 的角色静态图、视频 URL、
视频 cover 三者映射及首屏缓存往返。**本刀按 owner 既定节奏未跑 Gradle / 真机**，
仅完成 `git diff --check`；影院首进背景仍待设备冒烟确认。

#### 未做（明确边界）

其余仍未接：`CHATTED_LIST_REFRESH` 跨容器刷新（发送方在 ChatDetail 深栈，
现在才第一次有真实发送方）、`RELATIONSHIP_LEVEL_UPDATED` 徽章重拉、
ChatList 点击的 `recommendTracking` 透传。

#### P9 原始验证（2026-08-17；非本次回归）

- app 单测 **891 条**（+15：`SurfacePropsTest` +9 含 preload 嵌套/数字类型/
  入口来源取值对拍 RN 联合类型；`AppRouterTest` +2；`ShellAuthProviderTest`
  +5 桥回填），failures=0、**skipped=0**
- **两组测试都做过反向验证**：把 override 改回 notImplemented → 4 条红；
  把 preload 改成平铺字符串 → 3 条红。不是"写了就绿"的测试
- `:tipsy-auth:testDebugUnitTest` 15 条全绿
- lint 无新增（baseline 仍 5 条）；googlePlay + directApk assemble 通过
- **未动** manifest / RN 依赖 / flavor / submodule（pin 仍 `a6b9fc56a`）
- **真机冒烟已部分兑现，见 §2.37**（本节写的是 P9 实现当时的状态）。
  §9.1 十项 **7 过 / 3 未跑**（语言那列 §2.39 已复跑）；下列 ①–⑥ 的业务分流项**仍未跑**
  （本轮只走了 Home 入口，未验 html 富文本 / 多角色 / story / mini phone
  / 点头像进他人主页）。原始待跑清单：①四个入口各进一次聊天
  （Home/ChatList/Search/Screen 卡片点击）②html 富文本角色落
  `ChatDetailHtml`、多角色落 `MultiCinema`（这两条正是 preload 嵌套那个
  缺陷的暴露点）③story 卡恒普通聊天页 ④mini phone 会话行落对初始屏
  ⑤聊天页点头像进他人主页（桥回填的验证点）⑥退出后再点同一角色能重开
  ⑦§9.1 十项：未登录 / 登录切换 / 语言切换 / Back 栈底 / 旋转进程恢复 /
  首帧 / 50 次开关泄漏（Runtime 数应恒 1）/ Embedded

### 2.37 P9 冒烟：§9.1 部分兑现 + 抓到语言回退缺陷（2026-08-17）

按 owner「P9 插一次冒烟」的决定跑了 §9.1。**十项里 6 项过、1 项 FAIL、
3 项未跑**，并撞出一个与 ChatDetail 无关的壳缺陷（语言回退，见 §5）。
故 §4 矩阵那一行**仍不得标 production-ready**。

**跑在 commit `caf0cbe`**（即 PR #34 的内容，现已随 #34 并入 main —— 当时
它还在 PR 分支上）。刻意不用当时的纯 main：#34 修掉了 autolinking exclude
静默失效（见下），它改变 RN Surface 的运行环境（dev-launcher 不再链接），
在旧环境上验出的结论对合并后的 main 不成立。
Pixel_10 模拟器 / API 36 / directApk debug / Metro 8083。

| §9.1 项 | 结果 | 证据 |
| --- | --- | --- |
| 未登录 | 🟢 PASS | Surface 挂载、零 crash、零 JS rejection、`requestLogin` **未**被误触（0 次） |
| 登录切换 | 🟢 PASS | 登录态（UID `178...003`）下聊天页内容 + 输入框在场，props 完整 |
| Back / 栈底 | 🟢 PASS | 容器移除、进程存活（PID 不变） |
| 首帧 | 🟢 PASS | 挂载即渲染，无白屏 |
| Embedded（单层容器） | 🟢 PASS | `RNSurfaceFragment` 唯一实例，HomeFragment 在其下未销毁 |
| 50 次泄漏 | 🟢 PASS | 见下表 |
| **语言切换** | 🟢 **PASS**（模拟器） | 壳缺陷已修（§2.38）并复跑（§2.39）：往返 5 次语言稳定，`context.languageCode` 实测 `zh-tw`。⚠️ 模拟器，非真机 |
| 旋转 / 进程恢复 | ⬜ NOT RUN | 模拟器中途退出 |
| OTA N/N-1 | — | 属 W4 |

50 次开关（原生 Home ⇄ ChatDetailSurface）：

| 指标 | 前 | 后 | 判定 |
| --- | --- | --- | --- |
| TOTAL PSS | 948 MB | 984 MB | +3.7%，可接受 |
| Java Heap | 48.3 MB | 47.8 MB | 持平 |
| Views | 1397 | 1018 | 下降（无累积） |
| 容器唯一实例 | 1 | 1 | **恒为 1** |
| 进程 PID | 16320 | 16320 | 未重启 |
| OOM / FATAL | — | 0 / 0 | — |

无泄漏迹象。**去重谓词版工作正常** —— 退出后能重开同一角色，§2.36 要防的
「永远打不开」未复现。props 逐字段核对无误：`characterId` 在场、
`chatEnterSource: "home"`、嵌套 `preload` 里 `contentType` / `characterType`
是**数字**（正是 §2.36 强调不能塞字符串的两个，`putRouteParams` 类型分派生效）。

顺带兑现两项本不在清单里的：**后台恢复**（回桌面再进，PID 未变）、
**登录态持久化**（`force-stop` 后重启 UID 仍在，MMKV 互操作生效）。

#### 抓到一个 50 次压力下的竞态（RN 侧，双壳共有）

50 轮里出现 1 次 Render Error：

```
Call to function 'VideoPlayer.constructor' has been rejected.
→ Caused by: The current activity is no longer available
  GeneralMediaViewer.tsx:68  useVideoPlayer(activeMediaUrl || '', ...)
  ← RelationshipRewardModalHost ← AllChatModal
```

时序对得上：错误落在挂载与下次挂载之间，即**按 Back 拆容器那一刻**。
根因是 `GeneralMediaViewer.tsx:68` 的 `useVideoPlayer` **无条件调用**
（`visible` 之前无早退），组件一挂载就构造播放器，容器拆除后 activity
失效被 reject。

⚠️ **不是** §4.2 已接受的那条取舍 —— 那条讲 `VideoPlayerPoolInitializer`
刻意不挂载、有 `fallbackPlayer` 兜底；这里是组件直接调 Hook，不同的东西。
也**不是壳引入的**：`index.surfaces.js` 双壳共用，iOS 同样会挂这条链，
只是 iOS 用户少在媒体查看器构造的瞬间退出。1/50 频率，正常节奏碰不到。
**待 owner 定**：记为可接受偏差，还是在 RN 侧加 `visible` 早退（修法很小，
但按纪律要双壳回归）。

#### 前置：autolinking exclude 一直静默失效（PR #34）

冒烟前发现本地 icon 渲染不出来，根因不在图片：`settings.gradle` 那份
exclude（`expo-updates` / `expo-splash-screen` / `expo-dev-client` 系）
**从 W0 起就没生效过**。`expo-modules-autolinking` 的 gradle 插件把多值
选项 `joinToString(" ")` 压成单个 argv，而 CLI 按精确包名匹配 `Set`，
Set 里只有 `"expo-updates expo-splash-screen …"` 一个永不匹配的条目。

后果链：dev-client 照样链接 → `USE_DEV_CLIENT=true` → 装
`UpdatesDevLauncherController` → 它硬编码 `isEnabled=true` →
expo-asset 的 `IS_ENV_WITH_LOCAL_ASSETS=true` → `Asset.fx` 抢注 transformer
顶掉 RN 原生资源解析 → 壳既无 OTA 的 `fileUris` 也无 Expo Go 的
`debuggerHost` → `selectAssetSource()` 落到末尾 `return { uri: '', hash }`。

iOS 无此问题：CocoaPods 侧 `autolinking_manager.rb` 用
`args.concat(['--exclude'], exclude)` 分开传，本来就是对的。修复是
patch-package 补丁（tipsy-app `da4f65a`），让 Android 与之对齐。
⚠️ 补丁绑定 `expo-modules-autolinking@3.0.23`，升级该包时需重做。
⚠️ 意味着**此前所有构建都带着 dev-launcher 在跑**，W0 写下的隔离意图从未落地。

#### 两条给后续冒烟的操作教训

- **`Log.i` 取不到**。debug 包的 logcat 级别把 `Log.i` 过滤了，Router 的
  `logger = { Log.i(TAG, it) }` 一条都拿不到。判据改用 **Fragment 栈变化**
  （`dumpsys activity | grep tag=`），别依赖日志。
- **坐标必须每次实时 dump**。Profile 顶栏 Settings 图标的 y 随滚动位置
  在 58 / 197 之间变，写死坐标会点空且**看起来像功能坏了**。本轮在这上面
  反复试了七八次才发现。另：app 被系统标 cached 时（`caps=---------`、
  `curProcState=19`）UI 仍渲染但输入不被处理，`force-stop` 重启即恢复。

### 2.38 语言回退修复 + 共享 MMKV 键读写方向系统扫描（2026-08-18）

修掉 §2.37 抓到的语言倒灌（§9.1 唯一的 FAIL 项），并按 §5 那条建议
**把所有共享键的读写方向系统扫了一遍** —— 不是逐个碰到再修。

owner 2026-08-18 在三条路里选**路 1**：语言页确认时一并回写 `user-storage` 信封。

#### 前置顾虑已核实排除（这是路 1 能落地的原因）

原记录写「须先核实 RN 的 `version` / `merge` 配置，与性别筛选同一顾虑」。
核实结果：`user-storage` 的 persist 配置**只有 `name` + `storage`**
（`store/user.ts:286-289`）—— 无 `version`、无 `migrate`、无 `partialize`、
无 `merge`。对照 zustand 源码（`zustand@5` `middleware.js`）：

- 默认 `version: 0`（`:333`）→ 壳写 `version: 0` 与之一致
- migrate 分支要求 `version` 不等**且**配了 `migrate`（`:389`）→ **永不触发**
- 默认 merge 是浅展开 `{...currentState, ...persistedState}`（`:334-337`）
  → 可以造只含一个字段的最小信封，缺的字段回落 store 默认值

⚠️ **这个结论只对 `user-storage` 成立，不要套到性别筛选那个 key** ——
`config-persist-storage` 有自定义 `merge`，差异见 §5 那条的对照表。

#### 跨仓契约是现成的（本刀零 RN 改动、零 submodule bump）

查出三件此前没接上的事实：

1. `index.surfaces.js:73-76` **已有 `onUserStoreChanged` 监听**，注释写明
   「壳每次 merge `user-storage`（restore/refresh/login）后通知长驻 JS runtime 重读」
2. 桥方法 `TipsyAuthRegistry.notifyUserStoreChanged` **两端都在**，KDoc 甚至
   写了「壳写 Zustand persist 信封必须 merge」—— **只是 Android 壳从没调过**
3. iOS 侧早有对应实现：`AuthSession.syncUserToSharedStore` →
   `SharedMMKV.mergePersistState`，且**信封缺失时造新的**（默认值
   `["state": [:], "version": 0]`）

所以这条链是照 iOS 补齐，不是新设计。**`tipsy-app` 零改动，pin 仍 `da4f65a`。**

#### 落地

新增 2 文件：`i18n/AccountLanguageWriter.kt`（纯函数 merge，可单测）+
`i18n/AccountLanguageMirror.kt`（写 MMKV → 发 `onUserStoreChanged` 的接缝）。
`SettingsViewModel` 注入 `languageMirror`，在 `:157` **紧跟本地切**回写。

⚠️ **镜像跟着本地切、不跟着接口结果** —— 与 RN 的一处刻意差异，已写进代码注释：
RN 的镜像是 `updateUserInfo()` 的副产物（接口失败就不镜像），而它的 i18next
活在同一 runtime 内存里，陈旧信封要到下次冷启动才有影响；壳不同 ——
`refreshAccountLanguage()` 在**每次 Surface 容器出栈**时读信封覆盖当前语言
（`MainActivity.kt:102` → `TipsyApplication.kt:259`），所以「本地切了但没镜像」
的窗口里语言就退回去了。既然本地语言**失败也不回滚**，镜像必须与它同步落地。

⚠️ **两个字段以外一律不写**：只 put `languageCode`（RN 侧唯一写方是
`updateUserInfo()` 的 `user.language_code || null`，`store/user.ts:187`）。
信封里 `nsfw` 那类字段各有所有权，顺手写会破坏单向镜像流。

#### 顺带订正三处过期注释（前提变了，结论跟着变）

`AccountLanguageReader` / `UserInfoApi` 都写着「壳**只读** `user-storage`」，
理由是「语言设置页刻意不迁移，留在 `SettingsSurface` 里」。
**该前提在 §2.33 语言页原生化之后已不成立** —— 现在壳是语言唯一写入者，
信封镜像也必须由壳维护。三处都已改并写明「不是当初记错，是前提变了」。

（`UserInfoApi` **不碰语言的结论不变**，但理由换成「不要第二个 writer」，
不再是「壳不写这个 key」。）

#### 共享键读写方向扫描结果

结论表落在 **`LegacyMmkvStore` 类注释**（代码里，随读写点一起改，不放本文
避免两处漂移）。9 个 key/字段全扫，方向不成对的只有已知那两例：
**语言（本刀已修）** 与 **性别筛选（仍待 owner，§5）**。其余方向正确：
`nsfw` 刻意无 `writeNsfw`、`chat_draft_lru` 只读、`multi-cinema-conv-epoch`
只写、`chat-persist-storage` 系两不碰。

#### 验证

- app 单测 **907 条**（+13：`AccountLanguageWriterTest` 10 +
  `SettingsViewModelTest` 5 新增／1 修正），failures=0、**skipped=0**
  （⚠️ skipped=0 需先 assemble —— `MergedManifestTest` 那 3 条断言的是
  merged manifest 产物，无产物会 `assumeTrue` 跳过，而**跳过在 JUnit 里算通过**）
- **三组测试都做过反向验证**（不是「写了就绿」）：注掉
  `languageMirror.writeLanguage` → 3 条红；把「信封缺失时造新的」改回
  `return null` → 3 条红；把 merge 改成整体覆盖 → 2 条红（正是「静默清掉
  二十多个兄弟字段」那条）
- `:tipsy-auth:testDebugUnitTest` 15 条全绿、skipped=0
- `lintDirectApkDebug` **无新增**（baseline 仍 5 条）
- `assembleGooglePlayDebug` + `assembleDirectApkDebug` +
  `processGooglePlayReleaseMainManifest` 均通过
- **未动** manifest / RN 依赖 / flavor / submodule（pin 仍 `da4f65a`）→
  无需复查 release manifest diff
- ~~**冒烟 NOT RUN**~~ ✅ **已复跑 PASS**（§2.39，2026-08-18，⚠️ 模拟器）：
  语言页选繁中 → 开 `ChatDetailSurface` → 返回 → 语言仍是繁中，且 Surface 收到的
  `context.languageCode` 实测 `zh-tw`（此前 `en`）。单测证据确实不能替代这一条 ——
  同轮还查出了单测完全看不见的 `/user/nsfw` 404（§2.39）。
  ⚠️ **但这是模拟器**（`emulator-5554` / `ro.kernel.qemu=1`）—— §2.5 已定模拟器
  不作覆盖升级证据，真机复跑仍在待验清单里

#### 顺带补一条环境记录（不是代码问题）

本机 `tipsy-app/node_modules` 缺失 + 仓库根缺 `node_modules` 软链，
两者都表现为 §2.2.2 那句无用报错 `Process 'command 'node'' finished with
non-zero exit value 1`。软链是 ADR-004 要求的（`.gitignore` 已写明「由开发者/CI
各自创建，不入库」）。⚠️ **第六处**「RN 假设 Gradle root = `<rn-project>/android`」：
`ExpoAutolinkingSettingsPlugin.getExpoGradlePluginsFile` 硬编码
`workingDir(settings.rootDir)` 且无覆盖入口 —— 这正是那条软链存在的原因，
§2.2.2 的五处之外再加一处。排查方法仍是「在报错任务的 workingDir 手工复现
那条 node 命令」，本轮两次都是这样定位的。

### 2.39 语言复跑 PASS + 分级开关 404（2026-08-18）

#### 语言那列：FAIL → PASS

§2.38 只有单测证据，本轮冒烟复跑补齐（⚠️ **模拟器** `sdk_gphone16k_arm64`，
非真机 —— §2.5 已定模拟器不作覆盖升级证据）：切 繁中 → 开 `ChatDetailSurface` →
Back，**语言不再回落英文**，再连跑 4 次往返稳定；反向（繁中 → English）同样生效。
直接证据是 Surface 的入参 —— `initialProps.context.languageCode` 实测 `zh-tw`
（§2.37 时是 `en`），JS 侧同步打出 `align i18n to shell language: 'zh-tw'`。
顺带确认 `preload` 的嵌套数字字段（`contentType:1`/`characterType:1`）
按 §2.36 的要求以**数字**而非字符串到达。

⚠️ **读共享 MMKV 必须先解析头部 `actualSize`**：文件预分配 512KB 且**追加写**，
`strings | grep | tail` 会读到**上一代的残留记录**，方向正好相反。
本轮第一次就被误导（末尾是 `en`，看着像没修好），按 `actualSize` 过滤后
最后一条活记录才是 `zh-tw`。同理别用 4000 字节窗口做「就近匹配」——
要按 `{}` 配平取出整条信封再 `json.loads`。

#### 🔴 分级开关（Limitless）路径 404 —— 已修

`POST /user/nsfw` **少了 `/update`**：真值是 `/user/nsfw/update`
（`apis/user.ts:133` `updateUserNsfw`）。表现极具欺骗性 ——
`onNsfwToggle` 是**刻意的非乐观更新**（接口成功才改本地值，§2.33），
失败自动回滚，于是 404 表现为「**开关点了自己弹回去**」，
与「没点到」完全无法区分。日志里只有一行 `写 /user/nsfw 失败`，
`-v long` 才看得到 `ApiException$Http: HTTP 404`。

**为什么单测全过却没拦住**：`SettingsViewModelTest` 用的是 fake API，
验不到真实路径。已补 `SettingsApiContractTest` —— MockWebServer 真往返，
断言 path == `/api/v1/user/nsfw/update` 且 body 只含 `nsfw` 一个字段
（同 `HomeApiContractTest` 的理由）。已反向验证：改回旧路径该测试立刻失败。

**顺带修一处静默失败**：`onNsfwToggle` 失败写的是 `languageError`，
但订阅那个字段弹 Toast 的代码**只在 `LanguageFragment` 里**，
`SettingsFragment` 从不观察 → 写失败**零提示**。已在 `SettingsFragment`
补常驻收集器。⚠️ 挂 `onViewCreated` 而非 `onStart` —— 后者每次前后台切换
都会再注册一个收集器，同一个错误会被弹好几遍。**已实测**：先做 3 次前后台切换
（若挂 `onStart` 会堆出 4 个收集器）再断网点开关 → 失败日志 1 条、
`VRI-Toast` **恰好 1 次**、开关保持原值；恢复网络后翻转成功、零失败。

⚠️ **`svc wifi disable` 不等于断网**：模拟器的 CELLULAR 走 eth0 且仍
`VALIDATED`，只关 WiFi 时请求照样成功，看着像「修复没生效」。要 `svc data disable`
一起关，以 `dumpsys connectivity | grep 'Active default network'` 出现 `none` 为准。

⚠️ **改完必须重装再验**：本轮曾在 `compileKotlin` + 单测通过后就去点真机，
而设备上跑的仍是改动前的 APK（`dumpsys package | grep lastUpdateTime` 早于改动
时间），于是「Toast 不弹」被误当成代码问题排查了一轮。

#### 冒烟操作纪律：坐标必须每次现取

§2.37 记的「硬编码坐标看起来像功能坏了」本轮又踩了三次，成因各不相同，
值得记清楚：

1. **同一控件的 y 会变**：设置页语言行在两次进入时分别是 y≈200 和 y≈350。
2. **控件被状态栏吞掉点击**：Profile 齿轮某次 bounds 为 `[943,0][1069,116]`，
   而 `mDisplayCutout` 顶部 inset 是 **142** —— 点击落在状态栏上，
   连点 4 次「无反应」。正常态是 `[943,137][1069,258]`。
   ⚠️ 首轮约 10 次尝试（tab 切换 / Surface 往返 / 设置页往返 / 滚动 / 后台恢复 /
   冷启）**未能复现**；后来在「**别的 App 抢到前台后再冷启 Tipsy**」的情况下
   **复现且稳定**（连点 3 次全部无效，bounds 持续为 `[943,0][1069,116]`），
   而同条件下普通冷启 4/5 次正常（y=137）。根因仍**未定**：`mDisplayCutout`
   顶部 inset 始终是 142，说明是 `WindowInsets.statusBars` 在该次组合里取到 0 ——
   正是 `ProfileFragment.kt:133-137` 注释警告的现象，但那条注释断言「Compose inset
   首帧就有值」，在这个竞态下**不成立**。**需 owner 定**是否加 inset 兜底。
3. **重叠控件抢点击**：搜索页 `Filter`（`[928,269][1054,395]`）与
   `Clear`（`[965,307][1080,433]`）大面积重叠，取「中心点」会落进公共区被
   `Clear` 抢走 —— 实测弹出了**清空搜索记录的确认框**（已取消，记录未丢）。
   重叠控件要取**该控件独有**的区域，别取中心。

4. **UI dump 里的 `checkable` 节点不一定是你要的开关**：本轮有一次取到的是
   角色创建页的 `create.profile.ageConfirmButton`（整行 `[26,2025][1054,2151]`），
   在它上面做的「开关状态」判断全部无效。要**先按文本锚点定位**（`text="Limitless"`
   在 `[52,1688]`）再取同行的开关（`[891,1656][1028,1782]`），
   并顺手确认 `dumpsys activity | grep -c 'tag=settings'` 非 0（页面真在栈上）。
5. **模拟器上的第三方 App 会抢前台**：本轮 YouTube 两次抢焦点（含一个权限弹窗），
   导致一批「点击无反应」的观测不可信。冒烟前先 `pm disable-user --user 0` 掉它。

搜索页的 `Filter` 图标还有个前提：**只在 Characters tab 且有结果时才出现**
（`SearchScreen.kt:383`），空态页那个同名节点是另一个控件。

另：`Content Rating` 分区在本轮**始终不出现**，这是**正确行为**而非缺陷 ——
三重 gating 要求 `!isGooglePlay && nsfwEnabled`，而 nsfw 读的是
`HomeFilterStore.readNsfw()`（config-persist 镜像）。壳**刻意没有 `writeNsfw`**
（镜像由 RN 单向接力），故开关打开后镜像仍是 `false`，重启后开关也回到 `false`。
⚠️ **这条不算已验**：§2.46 后 `/user/info` 的完整 `user-storage` 已包含 `nsfw`，
RN runtime 启动时可按现有订阅接力到 `config-persist`；但纯 Native 路径在 RN 尚未启动时
仍没有直接写 `config-persist.nsfw`。因此「打开任一 Surface 后自愈」不等于该 Native
分级筛选已闭环，仍需单独定所有权并做设备验证。

### 2.40 W4：Tab3 创建入口接 CreateSurface（2026-08-18）

**五个 Tab 至此全部可用** —— Tab3 的 ➕ 此前只打一行
`Log.w("CreateSurface 未接入")`，现在点它挂 `CreateSurface`，直达创建表单
（Create Avatar / Name / Type）。5 文件 + 2 测试文件，219 行。

`AppRoute.Create(enterSource)` 进生产白名单，走 `openSurface` 的通用链
（幂等判定、平铺 props、popSurface 收口都与 ChatDetail 同一条，未新增机制）。

#### 壳**刻意不复刻** RN tabPress 的四个参数

RN 侧 `TabNavigator.tsx:425-430` 的 tabPress 带
`screen: 'ProfileDetail'` + `from` / `triggerSource` / `operationType`，
但那是**完整 App 内**跳 `CreateTabStack` 的形状。壳只传一个
`createEnterSource`：`CreateSurface` 自己就是那层微容器，它按 `isEdit`
自决 `initialParams`（`CreateSurface.tsx:113-135`），并把 `createEnterSource`
过 `normalizeCharacterTriggerSource` 得出 `triggerSource`。

壳再传一份就是把分流复刻成两份（§2.30 定的纪律，ChatDetail 同理）。
`SurfacePropsTest` 有一条专门断言那四个 key **不出现**。

#### ⚠️ 无参路由的去重洞：Tab3 只能用一次（真机实测）

第一版漏了去重解除，表现是**关掉创建页后再点 ➕ 永远打不开**，
logcat 是 `重复路由，已去重：Create`。

根因：`AppRoute.Create()` 参数固定（`tab_bar_plus`），每次点击产出的实例
**完全相等**，`lastHandled` 不解除时去重永久命中。

**这是类别性的，不是个例**：ChatDetail 躲过它纯属侥幸 —— 那条路由每次带
不同 `characterId`，天然不相等。`Create` 是壳里**第一个无参 Surface 路由**，
所以第一个踩到。后续每加一个无参路由（`Letter` / `EditProfile` /
`UserCoins` 都是 data object）都必须在 `onBackStackChanged` 里配一条解除，
否则同样「只能用一次」。

修法与 ChatDetail 同构：按 `TAG_CREATE_SURFACE` 判容器已出栈后调
`onDestinationClosed`（谓词版）。`AppRouterTest` 加了回归测试锁语义。

#### 真机验证（模拟器 / Pixel_10 / API 36 / directApk debug）

- 挂载 props 形状正确：`createEnterSource=tab_bar_plus` **平铺在顶层**，
  与壳自有字段无撞名（`dumpsys` 读 `mArguments` 确认）
- 三轮开→关→再开：每轮恰好 **1 层容器 / 1 个实例**，返回后归零
- 连点三次：仍只有 1 层容器（`openSurface` 的 tag 幂等生效）
- 返回后回到**点 ➕ 之前那个 Tab**（在 Profile 上测），不是空的 Create tab
  —— 对齐 RN 的 `e.preventDefault()`；`selected` 不变
- ⚠️ 跑在**模拟器**上，§2.5 已定不作覆盖升级证据

#### 两条测量教训（补 §2.37 那两条）

- **`grep -c "tag=CreateSurface"` 会给出假象**。同一实例在 `dumpsys` 里
  出现多行，我一度读到「容器数 5」以为叠了五层。数实例要用
  `grep -oE 'RNSurfaceFragment\{[0-9a-f]+\}' | sort -u | wc -l`，
  或数 `BackStackEntry` 条目。
- **uiautomator 会自己崩**（`RuntimeException: Bad file descriptor`），
  崩的是 dump 进程不是 app。当时 tap 没送达、看起来像「第三次点击打不开」，
  实际是测量工具挂了。判据：`adb shell pidof` 确认 app 仍在，重跑 dump。

#### 未做

- **编辑模式**（`editCharacter` / `editCharacterId`）：属 Profile 创作卡片
  ⋮ 菜单那条入口，要**原封透传原始角色对象**才不丢字段（方案 §8.1 记的
  iOS 坑：by-id 重拉会导致保存时字段重置 = 数据损坏），应单开 route。
- 创建页的标签抽屉依赖 `hydrateTags`（`index.surfaces.js` 入口已调），
  本轮只验到表单首屏，**抽屉内容未点进去看** —— 若发现标签为空先查那条。

#### ⚠️ 合规缺口：CreateSurface 无微根机器断言（待 owner）

`SurfaceDependencyChecklist` **只覆盖 ChatDetail**（已核实）。而 §2.36 里
ChatDetail 进白名单前，是先有 `SurfaceDependencyChecklistTest` 对 RN 源码
做了微根 18 项 + 5 个微栈目标的机器断言的。

我手工比对了两者 provider 树，**同构**：SafeArea / Keyboard / SWR /
GestureHandler / Portal / NavigationContainer + `SurfaceToastHost`，
且 CreateSurface 更简单（无 game 分支）。所以风险不高 ——
**但这是读代码的判断，不是机器断言**，而这类缺口的表现是静默的
（§2.19 的 `hydrateTags` 缺口就是全新安装才必现）。

owner 需定：补一份 CreateSurface 依赖清单再合（与 ChatDetail 同等待遇），
还是先合、清单与 §9.1 填表作为独立包跟上。本刀按后者提交 ——
**白名单里因此多了一个未经机器断言的 Surface**，这条必须显式记着，
  且 §9.1 的 `CreateSurface` 行仍有 8 个设备/生命周期验收格全 `✎`，
  **不得标 production-ready**。

> **后续结论（§2.41）**：上面保留的是 §2.40 合入时的历史状态；静态清单欠账
> 已在下一节关闭，但 §9.1 设备矩阵仍未填，production-ready 判断不变。

### 2.41 Surface 静态 gate：补 Create 欠账，预接 Settings（2026-08-18）

这一刀先修 §2.40 明记的合规欠账：`CreateSurface` 已进生产白名单，却只有人工
provider 比对；同时给 W3 的 `SettingsSurface` 建好强类型入口与静态 gate，
但**不提前放开生产路由**。Android 架构先对照 iOS，再以固定 RN Android pin
`da4f65a04f50bc098c2df3bd9f8fbcc13018f7a5` 的真实 JSX/注册为行为真值。

#### iOS-first 对照审计

| 审计项 | iOS 先例/路径 | RN Android 实测形态/路径 | Android Native 目标映射/路径 | 偏离理由 | 验收证据 |
| --- | --- | --- | --- | --- | --- |
| Surface 身份/容器 | `RNHost/CreateSurfaceViewController.swift`、`SettingsSurfaceViewController.swift` 各自持有 module name；`RNSurfaceContainer.swift` 统一身份门 | `index.surfaces.js` 注册 `CreateSurface` / `SettingsSurface` | `surface/CreateSurfaceContract.kt`、`SettingsSurfaceContract.kt` 保存 component name；仍复用通用 `RNSurfaceFragment` | Fragment 是 Android 已定的共享宿主，不为每个 Surface 复制空壳 subclass；专属 Contract 对齐 iOS 的显式身份边界 | 两个 ContractTest 双向核注册语句 |
| Settings 直达入口 | `SettingsSurfaceViewController.InitialScreen` 7 个强类型 case | `SettingsSurface.tsx` 的 `KNOWN_SCREENS` 7 项，非法值静默回退 `Feedback` | `AppRoute.SettingsSubScreen.Screen` 7 值 enum；`SettingsRow` 不再传任意 String | Kotlin enum 名遵平台习惯，`rnName` 保留 JS 原值 | RN 白名单与 enum 顺序/集合双向相等；`initialScreen` 从 Android key 到 RN `initialParams.screen` 全链断言 |
| 微根 | 两个 iOS VC 均先铺不透明 `#34212A` 再挂单一 Surface | 两个 RN root 都是 SafeArea → Keyboard → SWR → Gesture → Portal → Navigation → Stack，Toast 在 Portal 内、Navigation 后 | `SurfaceDependencyChecklist.CREATE/SETTINGS` 各 8 项 | 具体 provider 由 RN Android 真值决定，不照搬 UIKit view 层 | ContractTest 精确比较全部大写 JSX 开标签与顺序，新增/删减 provider 都会红；底色同测 |
| 微栈 | iOS 只传初始意图，不复制 RN 子导航 | Create root=`CreateStack`，当前实际 10 目标；Settings root=`SettingStack`，实际 12 目标 | `CREATE_STACK_TARGETS` / `SETTINGS_STACK_TARGETS` | RN 注释/类型有漂移：Create 注释写 9 页，`type.ts` 还有未注册 `Upscale`；只认实际 navigator JSX | 根栈与内部目标均双向精确比较 |
| Back/去重 | `TipsyRouter.popSurfaceContainer()` 只弹当前符合身份的容器 | RN 栈底 `popSurface()`；Android 当前仍是单层已接受偏差 | Settings 容器退栈后按 `SettingsSubScreen` 类型解除 `lastHandled`；Create 沿用同纪律 | Android FragmentManager 负责 native back stack；多层 Surface 仍须先补 instanceId | `AppRouterTest` 锁同一 Settings 子屏关闭后可重开；生产 policy 仍关闭 Settings |
| Create 入口副作用 | iOS `CreateSurfaceViewController` **不** hydrate tags；两壳共用 RN `index.surfaces.js` bootstrap | `index.surfaces.js` 顶层 `hydrateTags()`；全新安装缺它时标签抽屉静默为空 | 不在 Activity 复刻；静态 gate 锁 `hydrateTags()` 先于 Create 注册 | 这是共享 RN runtime bootstrap 所有权，不是任一平台页面/路由职责 | `CreateSurfaceContractTest` 锁调用存在与执行顺序 |

#### 落地边界

- `CreateSurface`：补齐 8 项微根、1 个 root stack、10 个实际微栈目标、注册名、
  `createEnterSource` 消费链、`hydrateTags` 顺序和底色机器断言。§2.40 的“无机器
  清单”欠账关闭。
- `SettingsSurface`：`MainActivity` 的通用 `openSurface` 分支、平铺
  `initialScreen`、共享 component contract 与退栈去重解除都已接好；
  `ProductionRoutePolicy` **仍不含** `SettingsSubScreen`，点击继续明确 reject。
- iOS 的专属 ViewController → Android 的通用 Fragment + 专属 Contract 是明确的
  平台映射，不是漏建目录。业务微根、屏名与参数不从 iOS RN pin 抄：iOS checkout
  当前 RN 已漂到 `f20ffb`，Create 多了屏与 prop，Android bump pin 时双向测试应先红。

#### 验证与未做

- `git diff --check`：PASS。
- 对固定 Android RN pin 做了只读正则抽取：Create 微根 9 个开标签（含 root
  `Stack.Screen`）/ 10 个内部目标；Settings 9 个开标签 / 12 个内部目标，与清单一致。
- 独立 reviewer 两轮静态复核：无剩余可执行发现；确认生产 policy 与 §9.1 声明诚实。
- Android Gradle build / unit tests / 真机：**NOT RUN**（遵守本项目慢任务授权边界）。
- `CreateSurface` 仍未填满 §9.1 真机矩阵，**不得标 production-ready**；
  `SettingsSurface` 更未进入生产白名单。静态 gate 不是设备生命周期证据。

### 2.42 W4 Screen P2：Media3 有界播放器池 + 视频生命周期（2026-08-18）

PR #39（最终 head `13cc6330009dfefa4e395d9c927f1fa404578d63`，merge
`6084df0d401e610d6fbcf26ce88c2bc494025927`）让 `showcase` 形态第一次从静态
封面进入真实视频播放：新增 `ScreenPlayerPool` / `ScreenPlayerLedger` /
`ScreenVideoHost` / `ScreenVisibility` / `ScreenSoundPreference`，并把播放器、
封面、声音与 Fragment 三轴可见性接回 `ScreenScreen` / `ScreenFragment`。

本刀固定对照 Android RN pin
`da4f65a04f50bc098c2df3bd9f8fbcc13018f7a5`，零 RN 改动、零 submodule bump。
全量 16 文件（11 新增 / 5 修改），不是 Screen P1 数据层重做。

#### iOS-first 对照审计

| iOS 先例 | RN Android 实测真值 | Android Native 映射 | 偏离理由 |
| --- | --- | --- | --- |
| `Pages/Screen/AVPlayerPool.swift` 的显式 borrow/recycle、当前项 ±1 窗口 | `FeedMediaItem.tsx` 只在 `abs(index-currentIndex)<=1` 挂播放器 | `ScreenPlayerPool` + 泛型 `ScreenPlayerLedger<T>`；`VerticalPager.beyondViewportPageCount=1` | 结构对齐；默认 page count=0 时邻页根本不组合，“±1 预热”会是空话 |
| iOS 按设备 `physicalMemory` 分档 3～5 | Android 真实资源上界是 per-app heap | 按 `ActivityManager.largeMemoryClass`：**≥512MB→5 / ≥256MB→4 / 否则 3** | 必要偏离；8GB 设备的应用堆上限仍可能只有 128MB，照物理内存开 5 个 player 会放大 OOM |
| iOS `assetCache` 复用 `AVURLAsset` | RN Android 用 Media3 自己的数据源/磁盘缓存链 | 不跨 player 复用 `MediaSource`，改为独立 `SimpleCache` | `MediaSource` 消费后与 player 绑定，跨 player 复用未定义 |
| `actionAtItemEnd=.pause` | RN `repeat={false}`，结束后回首帧并重显封面 | `REPEAT_MODE_OFF` + pause + `seekTo(0)` + cover reset | 行为对等 |
| iOS 刻意不接管 AudioSession | RN Android 未传 `disableFocus`，默认申请 `AUDIOFOCUS_GAIN` | `setAudioAttributes(..., handleAudioFocus=true)` | **不是 iOS 等价映射**；写 false 会成为未经批准的 Android 行为变更 |
| iOS/RN 都要求有真实画面后再撤封面 | RN 用 `currentTime>0` | Media3 `onRenderedFirstFrame` 后移除上层 cover overlay | 信号等价；本刀直接移除，**没有 fade，也没有接 firstInteractive** |

Media3 `1.8.0` 已由 `react-native-video` 带入依赖树；壳侧 catalog 是与 RN
版本的显式对齐，不应描述成独立升级或 strict pin。`media3-session` 也已由
`react-native-video` 传递引入，真实边界是：**壳不实例化、不使用
`MediaSession`**，不是依赖树里不存在它。

#### Android buffer 与动态 50MB cache

iOS 没有 buffer 配置可抄；六个值以 RN Android
`FeedMediaItem.tsx:602-608` 为真值：

- `minBufferMs=2500`
- `maxBufferMs=5000`
- `bufferForPlaybackMs=500`
- `bufferForPlaybackAfterRebufferMs=1500`
- `backBufferDurationMs=2000`
- `cacheSizeMB=50`

另设 `setPrioritizeTimeOverSizeThresholds(true)`，避免触及字节阈值后忽略时长窗口。

50MB cache 是进程级 `SimpleCache` 单例，使用独立目录 `ScreenVideoCache`，不与
RN 的 `RNVCache` 共目录。构造与 `checkInitialization()` 全部放低优先级后台线程；
主线程不等待目录扫描。失败时 release cache、close database provider、记为
FAILED，之后永久降级 upstream。

`DataSource.Factory` 是**动态工厂**：每次 `createDataSource()` 才读取
`@Volatile` 的已就绪 cache。这样缓存就绪前创建并留在池中的 player，在下一次
打开媒体数据源时也能开始命中缓存；若在 player 构造时静态解析，50MB cache
会对整批长寿命 player 永久失效。

#### 有界池、播放门与三轴可见性

- 池容量硬上限为 3～5；±1 窗口同时最多组合 3 个 player。
- `ScreenPlayerLedger<T>` 在 JVM 上钉住池满降级、外来/重复归还、release
  覆盖借出、坏实例 discard、shutdown 后迟到归还不 double-release。
- player 创建、装载或 prepare 抛 `Exception` 时降级封面；不捕获
  `OutOfMemoryError`。
- 借不到 player 的卡片保留封面，并在之后真正成为当前页时做事件驱动重试；
  不因每次 current 状态变化 recycle 已预热 player。
- 卡片划离：pause + seek(0) + 重显封面；Tab/Surface/App 失焦：只 pause，
  保留进度和缓冲。播放门收在单一
  `LaunchedEffect(current,isCurrent,isActive)`，任一轴变化都会取消迟到起播。
- `SurfaceView` 始终铺底，封面作为上层 overlay 按是否组合来遮挡；不依赖
  API 24～33 不可靠的 View alpha。

播放条件为 `started && !hidden && !covered`：

1. `started`：Fragment `onStart/onStop`，覆盖 App 前后台；
2. `hidden`：`TabHostFragment` 的 `show/hide`，因为切 Tab 不触发生命周期；
3. `covered`：`surface_container` back stack；Surface 与 Native 根是 sibling，
   打开 Surface 时 Screen 既不会 hidden 也不会 stop。

三条轴统一进入幂等 `applyVisible`。back-stack listener 与
`onHiddenChanged` 即使同次触发，也不会重复开合 session 埋点。

#### 声音开关与 MMKV 所有权例外

RN 的 `videoSoundEnabled` 位于 `chat-persist-storage`，默认 `true`。此前
`LegacyMmkvStore` 所有权表把该信封定为 Native 读❌写❌；原生 Screen 要播视频
必须知道初值，本刀采用最保守的临时方案：

- Native **只读 `videoSoundEnabled`，仍不写**；
- 每次页面从三轴意义上真正可见时重读，不用 `lazy`；
- 页内按钮只改内存态，离开再回来会回落 RN 真值。

这是待 owner 结论的所有权例外。独立 Native key 会造成双真值；允许 Native 写
RN 私有信封则须先核该信封的 merge/migrate 协议，不能从别的 MMKV key 外推。

#### 验证、明确未做与 NOT RUN

- app 单测 **957 条，failures=0 / skipped=0**；
- 最终 head 的 `lintDirectApkDebug` 通过，未新增 baseline；
- 最终 head 的 G1 run `32132910697`：**SUCCESS**；
- Pixel_10 / API 36 **模拟器**：声音初值→页内切换→切 Tab 后重读；
  Tab/Surface 往返 session 事件严格成对；快划 8 次、切 Tab、盖 Surface无崩溃。

⚠️ 模拟器 feed 没有任何 `showcase`，所以快划与内存观测不构成播放器链路验证。

| 项 | 状态 |
| --- | --- |
| next-item MMKV 预缓存 | **未实现，另包**；需要写入时机、筛选签名及与 `ScreenFirstScreenCacheStore` 的关系。此前“纳入 P2”口头承诺已撤回 |
| 封面淡出 | **未实现**；当前首帧后直接移除 overlay |
| Screen `firstInteractive` | **未接线**；`onFirstFrame` 只控制封面 |
| P3 状态机 | **未实现**；自动 tagline / 双击预览及 §2.35 其余二期项继续后置 |

🔴 **NOT RUN**：

1. 真实 showcase 视频：起播、首帧时序、同时存活数不超 capacity、播完回首帧、
   反复进出 20 次解码器不耗尽；
2. cache 构造失败降级路径；
3. API 24～33 的 SurfaceView/封面 overlay 层序；
4. audio focus loss：来电与后台音乐交互。

没有 showcase 数据时启动 API24 AVD 也不会组合 `PlayerView/SurfaceView`，不能把
“静态图能显示”包装成层序已验。四项闭环前，**Screen P2 不得标
production-ready**。

### 2.43 Surface 批次 3：EditProfile 预接、账号隔离与 Profile 刷新接力（生产仍关闭，2026-08-18）

这一刀继承 iOS 已验证的产品边界：编辑资料继续走 RN Surface，不复活另一套
Native 表单。Android 只补宿主契约、账号安全门与修改成功后的原生 Profile
刷新接力。固定 RN Android pin 为
`4ae2ebc667cf1801a09457156493a9eda7bf887e`；资料字段与 API 行为以该 pin 为
真值，Native 不通过 initial props 携带用户资料。

最重要的威胁不是普通“未登录”，而是：

1. 全新安装先走 Native Login，RN runtime 尚未启动，`loggedIn` 事件没有消费者；
2. 账号 A 使用过 RN 后退出，登出发生时 runtime 不在线，持久化
   `user-storage` 仍可能留有 A 的资料；
3. Native 登录账号 B 后第一次打开 EditProfile，若直接挂旧 Drawer，会先展示
   A 的 PII，慢请求、系统相册或旧保存链还可能在 B 登录后继续发布或写入。

本包不依赖一次性 auth 事件修上述问题，而是在每次进入页面时重新建立
“精确 token + JWT sub”作用域。

#### iOS-first 对照审计

| 审计项 | iOS 先例/路径 | RN Android 实测形态 | Android Native 映射 | 偏离理由/证据 |
| --- | --- | --- | --- | --- |
| 页面归属与宿主 | `RNHost/EditProfileSurfaceViewController.swift` 挂 `EditProfileSurface`、空 initial props；旧 Native EditProfile 已休眠 | `index.surfaces.js` 注册；`EditProfileSurface.tsx` 为微根 + 单屏 stack | `EditProfileSurfaceContract.kt` 固定身份；复用 `RNSurfaceFragment`；`SurfaceProps.forRoute(EditProfile)` 为空 | Android 复用通用 Fragment，不复制空壳 subclass；资料由 Surface 按当前账号拉取 |
| fresh-login 与账号边界 | iOS `AuthSession` 在换号窗口清 store，用 auth scope 隔离账号 | runtime 未启动时事件不可补发，persist 可能仍是旧账号 | Login 成功按“落 token→RN loggedIn→Native didLogin”广播；进入页仍独立 bootstrap | 事件不是持久真值；测试源码锁旧 attempt/错账号响应不得发布 |
| 精确账号作用域 | iOS 身份真值集中在 AuthSession/token store，generation 丢旧结果 | 资料/头像框/上传链原会在各时点重读当前 token | `EditProfileAuthScope={token,userId}`；frozen token 请求，发布前后重验 token、JWT sub、响应 user_id、attempt 与 snapshot identity | JS 异步链可跨卸载/换号，mounted 状态不足 |
| mutation 串行 | iOS 活跃页同样是 RN Surface | 资料、头像 confirm、头像框是独立 server commit，旧 Drawer 可与新实例并发 | 进程级、按 userId FIFO；头像与保存共用队列 | 组件内 ref 卸载后失效，无法阻止新实例越过旧实例 |
| RN→Native Profile 刷新 | iOS `viewWillAppear` 返回时刷新用户与用户态 | Android Surface 与 Profile 是 sibling，Profile 可始终 STARTED，pop 不触发 `onStart` | 可选零参 `notifyProfileChanged?()`；Native 从 token 解析 owner；`ProfileRefreshHub` 用账号+revision 持有 dirty，关闭沿做最终校准 | iOS push/pop 天然有返回生命周期；Android 必须显式接力 |
| Back/去重 | iOS Router 只弹匹配身份的容器 | RN `closingRef` + unmount 封闸 | data-object `EditProfile` 退栈后按类型解除 Router 去重 | 不解除会“只能打开一次”；多层 instanceId 仍是已接受偏差 |

#### 账号安全、提交与刷新链

- `EditProfileSurface` 与 standalone `user-profile.tsx` 两入口都先过同一
  auth-scoped loading/error/retry gate；scope ready 前不挂旧资料表单。
- 全新安装 Native Login 不依赖错过的事件：打开页面仍从 Native 取 token 并拉
  `/user/info`。A→logout（runtime off）→B 时，A 快照先在 gate 后清掉，只有 token、
  JWT sub、响应 user_id 与当前 attempt 全属 B 才允许展示。
- bootstrap/refresh 发布前后做 exact-token + active-attempt 双检；旧 scope 回滚只清
  自己写入的 snapshot identity，不能误删同 user 新 token 或账号 B 的新快照。
- 资料、头像 confirm、头像框的 server commit 在进程级按 userId 串行；三条
  commit 共用的因果顺序是「server commit → exact-scope 复核 → awaited
  best-effort 通知 Native」，随后仍在同一 scope 发布已提交快照并做
  `/user/info` 校准/reassert。通知或普通校准失败都不得把已成功的
  业务提交改判为失败。
- Native dirty 只有“请求绑定账号、当前 Native 账号、响应账号、最新 revision”全相等
  才 ack。失败、换号、旧请求迟到或请求期间再 mutation 都保留 dirty；每 revision
  自动重试有界，登录/登出清旧账号状态。
- `MainActivity` 以 EditProfile Surface 真实 visible→hidden 关闭沿触发最终刷新；
  close 后晚到 mutation 仍会 wake，不依赖底层 Profile 重走 `onStart`。

#### 落地边界

- `EditProfileSurface` 的组件身份、微根、单屏 root、空业务 route props
  （壳元数据仍由通用 builder 注入）、返回封闸与无参路由
  去重解除已预接；`ProductionRoutePolicy` **仍不含** `AppRoute.EditProfile`。
- `tipsy-auth` Android 注册方法数随 `notifyProfileChanged` 从 16 增至 **17**；TS
  方法 optional，旧 iOS/旧壳没有实现也不能把已成功保存转成失败。
- Profile P5 的 `editCharacter` 需要完整角色对象传输与独立 route，不混入 EditProfile。
- Profile P7 的 Native 头像框渲染与 `coil-svg` 依赖不在本包；RN 能编辑 decoration
  不代表 Native Profile 已完成 P7。
- 不修全局无 instanceId 的 Surface close 已接受偏差；本页只用 JS 实例封闸降低
  迟到双 pop 风险。

#### 验证与未做

- RN/submodule commit：`4ae2ebc667cf1801a09457156493a9eda7bf887e`；Android
  预接线 commit：`4a69cfe3c7f75e48de78f12df0cf575d443d456a`，最终 target SHA
  以父仓合并 commit 为准。
- 已增加 auth-scope、跨账号 publication、进程级 mutation 队列、commit/notify
  顺序、Profile dirty revision、关闭/晚到 mutation、无参路由去重与微根契约的
  确定性测试源码；两轮独立静态复审无剩余 P0/P1。
- `git diff --check`：RN 与 Android 均 PASS。
- RN Vitest、TypeScript compile、Android unit tests、Gradle build/lint：**NOT RUN**。
- §9.1 真机/模拟器：**NOT RUN**。尤其是全新安装 Native Login、
  A→logout（RN runtime 不在线）→B、token rotation、系统相册期间换号、慢 commit
  后退栈、Back/旋转/进程恢复、反复进出泄漏均没有设备证据。

因此 `EditProfileSurface` 的 §9.1 行仍有 8 个设备/生命周期验收格全 `✎`，
生产 policy 必须保持关闭；本结论是
“可提交预接线”，不是 production-ready。

### 2.44 W3 Profile P7：头像框 + 渠道图标（2026-08-19）

P7 的定义在 §2.26：**头像框与渠道图标两半**。PR #41（merge `cc97de0`）只落了
头像框渲染缝且零测试；收尾 PR 修正其两处偏差、补齐渠道图标半边并补测试。

#### 第一笔：头像框渲染缝（PR #41，7 文件 +79 行）

- `CurrentUser` 增 `avatar_decoration_code`（`/user/info`，`ScalarCoercion` +
  空串归 null）；`AvatarDecorationApi` 拉公开目录
  `/avatar_decoration/config/list` 按 code 解析 `image_url`；
  `ProfileViewModel` 刷新链解析后经 `ProfileState.avatarDecorationImageUrl`
  下发；`ProfileHeaderSection` 把框画成头像上层的**不可点 overlay**
  （`ContentScale.Fit`，testTag `profile_avatar_decoration`）。
- **尺寸对等已核实**：RN 是 58dp 头像内嵌 65dp 容器、框画满 65dp
  （`TipsyAvatar.tsx:87-92`）；壳的头像本体本就简化为满 65dp 圆（P2 已记），
  框取同一 65dp 盒即对等。
- **SVG 解码能力当前是传递依赖**：`react-native-screens` 显式引了
  `coil-svg:3.0.4`（其 `android/build.gradle:249-253`），coil3 靠
  ServiceLoader 自动注册 decoder。显式声明已在 ChatMap 分支 commit
  `4acf6cd`（「两轨共享前置」，随 PR #42 入 main）—— 在那之前若 RN 侧
  移除该依赖，框会**静默不渲染**。

#### 第二笔：收尾修正（本节 PR）

1. **鉴权模式契约偏差**：首版用 `AuthMode.NONE`，RN 真值是
   **`axiosPublic`**（`apis/avatarDecoration.ts:16`）→ 正确映射是
   `OPPORTUNISTIC`。NONE 恰是 `AuthMode` 类注释点名的 iOS「搜索历史恒空」
   bug 形状 —— 带不带 token 的行为差异只在服务端，客户端与单测都看不出来。
   已修并补 `AvatarDecorationApiContractTest`（MockWebServer 真往返，5 条：
   路径、有 token 必带、无 token 照发、code 查无/`image_url` 空串按无框、
   空 code 零请求）。
2. **失败语义对齐 RN**：首版目录拉取失败会把已显示的框清空（日志却写着
   「保留」），且目录往返**垫在**用户资料落地与 stats/钱包链前面。RN 真值：
   `useAvatarDecorationConfig` 读 MMKV 持久化的 `config-persist` 目录，
   瞬时网络失败不掉框。已改为：user 先落 state；框在 `userStatsJob`
   **子协程**独立解析（登出/新刷新随 job 取消，旧账号在飞解析不残留写回，
   账号边界仍由 `onAuthChanged` 整表复位兜底）；**拉取失败保留上次 URL**
   （同钱包/统计「一次网络抖动不清屏」纪律）；code 为空立即清
   （EditProfile 取消佩戴 → `notifyProfileChanged` 刷新后旧框不能留）；
   目录返回但查无此 code 也清（目录是真值）。`ProfileViewModelTest`
   +5 条（合计 46 条）。
3. 顺带订正 `ProfileHeaderSection` KDoc 里过期的「头像框未做」标题
   （PR #41 改了正文没改标题）。

#### 第三笔：渠道图标（本节 PR，P7 的另一半）

昵称下方的社交平台图标行（RN `SocialLinksDisplay.tsx`，Discord/Instagram/
TikTok… 点击开外部浏览器）：

- `CurrentUser` 增 `display_urls` 逐条容错解析（单条残缺跳过不弃整表；
  `display_status` 缺失按**不可见** —— 状态未知宁可不展示，也不把用户设为
  HIDDEN 的链接放出来）。
- `ProfileSocialLinks` 收展示层三层过滤，**顺序照 RN**：
  ① `display_status == VISIBLE(1)` ② 平台在 9 枚举支持名单（未知平台静默
  跳过）③ 不在隐藏名单 `[kofi, patreon]`（`socialPlatforms.ts:53-56`，
  RN 注释"暂时隐藏"—— 名单变了两端一起改）。图标 20dp、行 gap 12、
  昵称列 gap 6（`ProfileHeader.tsx` styles）；9 个 png 资产已按
  `ic_profile_social_<platform>` 搬入 `drawable-nodpi`。
- 点击对齐 RN `WebBrowser.openBrowserAsync`：`ACTION_VIEW` + 捕获
  `ActivityNotFoundException`（照 `SettingsFragment.openExternalUrl` 先例）。
- `ProfileSocialLinksTest` 5 条对拍三层过滤 + 原序 + 九平台资产映射完整性；
  `CurrentUserParserTest` +4 条（合计 9 条）。
- ⚠️ **新识别偏差（未修）**：RN 的 `ProfileHeader` 自己/他人两个视角**都**渲染
  这排图标（`useProfile.tsx:223-225`：`isSelf ? store : publicUser.display_urls`），
  §2.32 的七处偏差审计没覆盖这一条 —— 壳的他人主页头部（`PublicProfile*`）
  目前**没有**渠道图标。留待他人主页后续包（数据从 `/user/get/public` 的
  `display_urls` 来，展示层可直接复用 `ProfileSocialLinks`）。

#### 与 RN 的刻意差异

壳不复刻 config-persist 的 hydrate 持久层（§2.19 记过那三个 hydrate
**静默吞失败**的坑，全新安装必现空框），每次 Profile 刷新链解析一次目录 ——
代价是每次刷新多一个轻量请求，换来失败可见且不背 MMKV 残留的历史包袱。

#### 验证

- 本机：`ProfileViewModelTest` 46 + `AvatarDecorationApiContractTest` 5 +
  `ProfileSocialLinksTest` 5 + `CurrentUserParserTest` 9；全套件
  `testGooglePlayDebugUnitTest` **1005 条 failures=0 / skipped=0** +
  `:tipsy-auth:testDebugUnitTest` 过 + `lintDirectApkDebug` 过 +
  `assembleGooglePlayDebug` 过；完整 G1 交 CI（PR #41 自身的 G1 在 PR 上已绿）。
- 真机/模拟器 **NOT RUN**（按 owner 2026-08-14 决定累积）：待验清单加两条 ——
  「Profile 头像框实际渲染（含 SVG 图源，ServiceLoader 注册只有设备能证）」、
  「渠道图标行渲染 + 点击开浏览器」。

### 2.45 W3 Profile P5：创作卡 ⋮ 菜单 —— 编辑/删除/置顶（2026-08-19）

**Profile 的最后一块业务**（方案 §8.1「卡片菜单」行）。动作矩阵与 iOS 壳
一致：**角色=编辑/删除/置顶、故事=删除/置顶、游戏=置顶**。

#### RN 真值审计（实现前逐条核实）

- **动作与端点**：置顶是**单端点 toggle** `/character/toggle_pin`
  （`apis/character.ts:461-470`，`item_id` + `item_type` 三类共用，服务端
  翻转并回 `is_pinned`）；删除分两端点两字段 —— `/character/delete`
  （`character_id`）与 `/story/delete`（`story_id`），不可归并。
- **id 全在嵌套层**：RN 传给卡片的是 `cellItem.character || cellItem`
  （`CharacterGrid.tsx:568-641`），删除/置顶用的 id 是嵌套对象的
  `character_id` / `story_id`，**不是顶层 `item_id`**。story 置顶是
  `item_id || story_id` 兜底链（`StoryItem.tsx:463`）。
- **语义**：pin/delete 都**非乐观** —— 成功后 `createdMutate` 整列表重拉
  对账（置顶影响排序，服务端才知道 pinned 组终序）；pin 单飞（`isPinning`
  门）；成功 Toast 按**响应**的 `is_pinned` 分流；失败按
  `up to 3 pins allowed` 子串分流上限文案。
- **遮罩卡也显示 ⋮**（`isSelf &&` 块在 `isMasked` 三元**之外**）——
  这是用户删除不可用角色的唯一途径，做反的话这类角色永远删不掉。
- **编辑**（仅 character；story 编辑暂缓、game 无编辑）：
  `initCharStateUpdate(嵌套对象)` 全量预填 → `CreateSurface` 编辑态。
  iOS 契约 §3 的硬教训照单全收：**by-id 重拉会在保存时把
  `conversation_style`/`custom_prompt`/`show_background`/`animated_image`
  重置（= 数据损坏）**，必须原始 JSON 原封透传。

#### 落地（14 文件）

- **模型**：`ProfileCreatedItem` 增 `deleteId`/`pinId`（嵌套优先、顶层兜底，
  按类型分流）与 `editPayloadJson()`（取嵌套 `character` 对象**原文**，
  非 character 返回 null）。
- **API**：`ProfileApi` 增 `togglePin`/`deleteCharacter`/`deleteStory`
  （全 REQUIRED）。
- **ViewModel**：菜单单开互斥（`openMenuKey` 单字段，RN 那套
  `closeOtherMenu` ref 表不需要）、删除确认二段式（`pendingDelete`）、
  pin 单飞（`pinningKey`）、成功重拉对账（`reloadCreatedTab` 只动创作 tab）、
  Toast key 复用 ChatList 的五个词条 + 上限词条（全在 SHELL_KEYS）。
  ⚠️ **壳补了 RN 没有的失败提示**：RN 删除的 onConfirm 无 try/catch，
  失败表现为「点了删除、卡片还在、零提示」—— §2.39 那类静默失败不继承。
- **编辑链**：新 route `AppRoute.EditCharacter(characterJson, characterId)`
  → 生产白名单 + `ShellNavigator` 分支（**复用 CreateSurface 容器**，
  与 Create 共用幂等判定与退栈去重解除谓词）→ `SurfaceProps` 产出
  `editCharacter`（结构化嵌套对象）+ `editCharacterId`（有损兜底；
  坏 JSON 退化成仅 id —— 仍是编辑态，**绝不静默落创建态**）。
  props 侧新增 `JsonRouteParams`（JSON→Map 结构转换，显式 null 保留为
  NULL 哨兵 —— zustand 里「键为 null」与「键缺失」语义不同，丢 null 会让
  清空过的字段旧值复活；大整数走 Long 不丢精度）＋ `SurfaceContract.putRouteParams`
  扩 List/null 支持（对象数组走 `putParcelableArrayList`，Bundle 不是
  Serializable —— 走错 API 平时正常、**进程重建 parcel 时才崩**）。
- **UI**：⋮ 触发钮（右下 4/12、24dp，**clickable 吃掉事件** —— iOS 装饰
  View 穿透教训）；卡内浮层（黑 60% scrim 点击关闭 + 两列动作网格照
  `menuGrid` 布局；**圆形模糊揭开动画刻意不迁**，纯视觉增强）；删除确认
  弹窗照 `TipsyModal.tsx` Android 分支（80% 宽/圆角 10/三段深色渐变/
  底部两键等分横排 + 1px 分割线 —— 不是 Material 右对齐按钮）。
  **Share 刻意不迁**（`ShareCharacter` 427 行 + 分享基建，iOS 壳同样未迁）。
  他人主页复用 `ProfileGridItem` 不传 `menu` → 无菜单（RN 的 `isSelf &&`）。
- **埋点**：删除成功发 `delete_character`（camelCase `characterId`，
  仅 character —— story 的 onConfirm 实测无埋点）。

#### 验证

- 本机：`ProfileViewModelTest` +9（55 条）、`ProfileParserTest` +6、
  `SurfacePropsTest` +3、`AppRouterTest` +1、新 `JsonRouteParamsTest` 4 条；
  全套件 **1028 条 failures=0 / skipped=0** + `:tipsy-auth` 过 +
  `lintDirectApkDebug` 过 + `assembleGooglePlayDebug` 过；G1 交 CI。
- ⚠️ lint 硬门实测抓出 **IconDuplicates**：RN 的 `card_delete.png` 与
  Search 已入库的清除图标是同一张图 —— 已复用 `ic_search_history_clear`
  不重复入库。搬 RN 资产前先查重是个可复用的教训。
- ✅ **模拟器轻量冒烟 PASS**（owner 2026-08-19 目测，directApk）：菜单开合/
  删除/置顶/编辑主链路无异常。⚠️ 目测不等于逐项验收，且模拟器不作
  覆盖升级证据（§2.5）。
- 真机 **NOT RUN**（累积）：待验清单仍留三条 ——「菜单开合与动作
  （含遮罩卡的 ⋮）」「删除/置顶真实往返 + 上限文案」「编辑态进
  CreateSurface 的字段保真（改保存后 `conversation_style` 等不被重置）」。
  编辑保真只有真机能证 —— 单测只能钉 props 形状，钉不了 RN store 预填。

### 2.46 Profile 滚动 + 完整用户会话 + Surface 稳定性（2026-08-19）

本轮由设备回归连续抓到四类互相关联的问题，并按 iOS 宿主边界收口：

- Profile 网格在短列表下不能继续向下滚、末行会被覆盖式 TabBar 挡住：列表底部余量
  对齐 RN 的 `s(bottom + 400)`，再叠加 Native TabBar 的真实内容高度与安全区。
- Android Native 登录此前只落 token 与 JWT subject；首次进入 ChatDetail Surface 时，
  RN `user-storage` 没有完整用户，聊天历史请求缺账号上下文。现在 Application 持有唯一
  `CurrentUserStore`，登录顺序固定为「清旧账号 → 落 token → 拉 `/user/info` → merge
  完整非敏感快照 → 广播 loggedIn」。共享快照写入失败会回滚 token，不发布半登录态；
  普通后台刷新写镜像失败仍保留 Native 网络结果。
- logout、401 清 token 与直接换号都会同步删除旧 `user-storage` 和
  `chat_history_first_page_lru`；RN runtime 在线时也在 loggedOut/userId 切换事件清同一 LRU。
  完整快照含 RN `setUser` 的 camelCase 字段集，未知未来字段与 envelope version 均保留。
- RN Surface 返回后 Native 各 Tab header 偏移，根因是 KeyboardProvider 把共享 Activity
  子树的 status bar inset 改成 0；依赖补丁改为原样继续派发 WindowInsets。Surface 二次
  进入闪 Native 页则是旧 cover 用“已布局”误判新 RN 像素 ready；宿主改成不透明 Native
  wrapper + 透明 RN Root，与 iOS 一致，不再使用 placeholder/fade/布局时机猜测。

RN 提交已直接推 `tipsy-app/feat/android-native`：
`364ee638c`（账号边界清聊天首页缓存）与 `5ba22c8bb`（KeyboardProvider inset patch）；
Android submodule 固定到 `5ba22c8bbbade8d726ef2c5921b76a221a50be79`。

验证：用户已在模拟器确认 Profile 滚动与 Surface 往返不再闪回；RN Prettier 与补丁
反向应用检查 PASS。Android 新增静态/单元测试源码覆盖 Profile padding、inset 补丁、
完整字段/信封、generation/subject 闸门、登录必需镜像与 token 删除回调；PR #44 首轮
G1 Fast Gate 已 PASS，解决 main 冲突后的 merge head 仍以新一轮 G1 为准。

### 2.47 W3 ChatList P2：ChatMap「時光長廊」生产接线（2026-08-20）

接手 PR #42 draft（23 commit 的解算层/静态铺排/横滑，另一条线 8-19 停更，
owner 确认无人推进）收尾成完整可用：**ChatList 的 Map 按钮从 Coming soon
换成真廊道，W3 最后一块业务落地**。

#### 接手时的处置

- rebase 到最新 main（此前基线 `c8fea47` 落后五个合并），剔掉分支上
  已随 PR #41 单独入 main 的一对 P7 commit+revert（--skip 两次）。
- **四词条补齐**（方案 §8.1 早点名的欠账）：`Today`/`Yesterday`/`Chats`/
  `Story` 进 `SHELL_KEYS`（RN 侧 `93c8647f3`），重跑导出 26 语言全命中
  （en 198/198），**bump pin `5ba22c8bb` → `93c8647f3`**（前者即 §2.46
  那笔，四词条 commit 直接基于其上，pin 单调前进无跳跃）。

#### 本包新落的（draft 只有横滑，纵向/入口/点击全缺）

- **纵向滚动**：符号对齐 iOS（`scrollY = 0` 初始、下拉看更早 → 变负，
  `ChatMapView.swift:283`）；行程 `rowHeight*(N-3)`（contentSize − 视口
  —— 按 N-1 算会滚出全空屏，模拟器实测）；`snapToInterval` 对等 =
  惯性 `calculateTargetValue` 投影后取整到档位再 `animateTo`。
- **楼层连续滚动**：物理位置 `offset(-delta)`（iOS 楼层是 scrollView
  cell 随滚动真实移动；draft 是固定铺位 + transform 跳档，表现为
  **拖动画面纹丝不动**，实测）；三条样条曲线接 `graphicsLayer`，
  平移**预乘 scale**（RN transform 数组左乘序，iOS 端口同结论）；
  可见范围 `[-1,3]` 外整层不 compose；标题 `currIndex>=2` 淡出。
- **卡片点击**：与 Grid 完全同链路（判定素材透传 `onThreadClick` →
  Router → ChatDetailSurface，模拟器实测直达真会话）。⚠️ RN 的
  `'corridor'` 参数只是**返回视图标记**（RN 自持的 `chatListEntryType`），
  `chatEnterSource` 两视图**同为 `chat_list`**（`useChatNavigation.ts:47-50`
  归一）—— 壳不需要新枚举值。
- **卡片文案**：`ChatThread` 补 `message_num`（wire 类型是 **string**，
  `types/chat.ts:281`，走 ScalarCoercion）；`formatMapCardTime` 三分支
  （今天 = 相对时间、今年 `d MMM`、跨年 `MMM d, yyyy`）——
  ⚠️ 与 Grid 行尾 `formatRowTime`（恒数字 `03/07`）**不是同一个函数**，
  RN 就是两个；相对时间用 `android.icu.text.RelativeDateTimeFormatter`
  （API 24+ 随 locale 本地化 —— dayjs 的相对文案来自它自带 locale 包，
  26 语言词表里没有，手工拼会漏 25 种语言）。楼层日期标题
  `formatMapDateTitle`（同年 `D MMMM` / 跨年 `MMM D, YYYY`）。

#### 两个实测才抓到的 Compose 陷阱（记下来防复发）

1. **垫底 sibling 收不到手势**：纵向手势层做成 zIndex 0 的 sibling，
   楼层（zIndex 96~100）全覆盖时 hit-test 不共享指针 —— 表现「怎么拖
   都不滚」。手势必须挂**楼层的祖先容器**（hit path 上游）。
2. **曲线横轴是窗口高不是容器高**：`useWindowDimensions().height` 是
   全窗口（差一个顶栏 + tabbar）；且 `Configuration.screenHeightDp`
   被 lint 硬门拦（insets 语义随 targetSdk 变 + 取整），
   用 `LocalWindowInfo.containerSize`。

#### 验证

- 模拟器（Pixel 10 / API 37，directApk）实测：Grid↔Map 切换、三层透视
  廊道渲染、纵向滚动 + 档位吸附 + 边界、楼层标题（Yesterday/11 July
  等四分支都出现）、剪影补位卡、卡片点击直达 ChatDetail、返回不崩。
- 本机门禁：全套件 **1101 条 failures=0 / skipped=0**（ChatListTextTest
  +3）+ `:tipsy-auth` 过 + `lintDirectApkDebug` 过（顺带抓了
  ConfigurationScreenWidthHeight 一条真问题）+ assemble 过；G1 交 CI。
- **NOT RUN / 后续**：横滑吸附的视觉细校（solver 值已有 380 条对拍，
  但滑动手感只有真机能证）；动图按可见性开关（阶段三）；楼层虚拟化
  （>50 天会话的性能缺口，draft 类注释已记）；真机冒烟按 owner 决定
  累积。§9.1 不适用（纯原生页非 Surface）。

### 2.48 W4 批次 3 第一刀：SettingsSurface 七子屏放行（2026-08-20）

`AppRoute.SettingsSubScreen` 进生产白名单 —— Settings 列表的 7 个 Surface
子屏（Security/Blocked/Delete Account/Feedback/About/ContactUs/Add Widget）
第一次能点开。**改动极小**（白名单 1 行 + 测试翻转 + KDoc）：§2.41 已把
静态 gate、`initialScreen` 平铺 prop、`MainActivity` 导航分支与退栈按类型
解除去重全部预接，本刀只是放行 + 补设备证据。

选它先于 EditProfile/Comments 的理由：**零跨仓改动、零新机制** ——
SettingsSurface 的 12 个微栈目标全是页内导航（不触发 §12.1 多层容器），
7 个子屏共用一个容器（单层纪律与 ChatDetail 同链）。

#### §9.1 矩阵（模拟器 Pixel 10 / API 37，directApk；⚠️ 不作覆盖升级证据）

| 项 | 结果 |
| --- | --- |
| 初始 route fixture | ✅ 7/7 子屏逐个打开并截图核对：Feedback（单选组/输入框/Submit）、About（版本/Update）、ContactUs（support 邮箱）、Add Widget（角色选择/Apply）、Security（加密说明）、Blocked（双 tab 空态）、Delete Account（完整问卷）—— `initialScreen` 分流全部正确，无 `normalizeScreen` 兜底错屏 |
| Back/栈底 | ✅ 每个子屏 Back 回列表；栈底不退出 App |
| 关闭重开（去重解除） | ✅ 同一子屏（Blocked）关闭后立即重开成功 —— `SettingsSubScreen` 按类型解除 lastHandled 生效 |
| 25 次挂载/卸载 | ✅ Security 开关 25 轮：Activities=1、ViewRootImpl=1、Views=91（无累积）、无崩溃 |
| 旋转 | ✅ Security 开着横↔竖往返，Surface 存活内容完整 |
| 进程恢复 | ✅ force-stop 冷启动回 Home 正常，重开子屏成功 |
| 未登录 | ✅ 游客机（5554）Settings 入口本身在 Profile tab 内，`requiresAuth=true` 由 Router 的 auth gate 排队 —— 未登录根本到不了子屏，路径不可达即安全 |
| 登录切换 / 语言切换 | ✎ 未跑（Settings 子屏内容与账号/语言的耦合弱；语言切换的壳级机制已由 §2.39 复跑 PASS 覆盖） |
| OTA N/N-1 | W4 OTA 接入后统一跑 |

#### 落地边界

- 生产白名单从 7 类扩到 **8 类**（+`SettingsSubScreen`）。
- `AppRouterTest` 翻转旧的反向断言（「子屏被拒绝」→「列表与子屏各自导航」），
  +1 白名单断言；`SettingsFragment` KDoc 同步。
- ⚠️ 模拟器证据按 §2.5 纪律**不作真机结论**；真机冒烟继续累积。
  Widget 子屏的「Apply 后桌面真出现 widget」属 Widget 系统能力（W4 横切，
  🔴 未开始），本刀只验到 Surface 页本身可用。

### 2.49 W4 批次 3 第二刀：EditProfile 放行（2026-08-20）

`AppRoute.EditProfile` 进生产白名单（第 **9** 类）—— Profile 的
「Edit Profile」按钮从 reject 变成真编辑页。§2.43 预接的全部机制随本行
生效：auth-scoped bootstrap、精确 token + JWT sub 账号闸、进程级 mutation
串行、`notifyProfileChanged` → `ProfileRefreshHub` 刷新接力、无参路由
退栈解除。改动同 §2.48 形态：白名单 1 行 + 测试翻转。

#### §9.1 矩阵（模拟器 Pixel 10 / API 37，directApk；⚠️ 不作覆盖升级证据）

| 项 | 结果 |
| --- | --- |
| 初始 route fixture | ✅ 表单完整打开：头像/邮箱/Name（预填当前昵称）/Gender 三选/Bio/Find Me Elsewhere 全渲染 |
| **保存 → 原生 Profile 刷新接力** | ✅ **核心链路实测**：改名 `Lee → LeeMap` 点 Done → Surface 关闭 → **原生 Profile 头部立即显示 `LeeMap`**（无手动刷新）——`notifyProfileChanged` → dirty → `/user/info` 校准整链生效。再改回 `Lee` 二次验证，编辑页重开时预填的也是新值 |
| Back/栈底 | ✅ Back 回 Profile，不退出 App |
| 关闭重开（无参解除） | ✅ 保存关闭后立即重开成功（连续三次往返）—— 无参 data object 的退栈解除生效，没有「只能编辑一次」 |
| 旋转 | ✅ 表单开着横↔竖往返，字段存活 |
| 15 次挂载/卸载 | ✅ Activities=1、ViewRootImpl=1，无累积泄漏 |
| 进程恢复 | ✅ 表单开着 force-stop → 冷启动 → 重开编辑页成功 |
| 未登录 | ✅ 同 §2.48：游客机冷启动即登录页，入口路径不可达 |
| **登录切换（A→logout→B 换号）** | ✎ **NOT RUN —— 需第二个测试账号**（§2.43 的账号闸/快照隔离是本 Surface 最重的威胁模型，静态测试源码已覆盖但无设备证据）。待 owner 提供账号或明确接受带痕放行 |
| 语言切换 / OTA N/N-1 | ✎ 未跑（语言机制 §2.39 已覆盖；OTA 等 W4 接入） |

#### 落地边界

- 生产白名单 8 → **9** 类。`AppRouterTest` 翻转「预接后仍不在白名单」
  断言为「在白名单且可导航」。
- ⚠️ **换号格是唯一的缺口**且是刻意标注：§2.43 单列的三条威胁
  （runtime 未启动的事件丢失、旧账号 persist 残留、慢请求跨账号发布）
  只有真实双账号切换能证。生产 policy 已开，但这条欠账在真机冒烟
  清单里**置顶**。
- 头像上传（系统相册链）未验 —— 模拟器相册无素材，属真机冒烟项。

### 2.50 W4 批次 3 第三刀：CommentsSurface 启用 —— Screen 评论出口解锁（2026-08-20）

`AppRoute.Comments` 进生产白名单（第 **10** 类），Screen 大屏页的评论按钮
从「只发埋点」变成打开真评论页 —— **批次 3 的三个 Surface 全部启用**。

#### 前提修正解锁了这一刀（§12.1 核实，见「未决问题」）

原以为 Comments 要先根治实例关闭链（多层容器），核实后不成立：
`ChatDetailSurface` 微栈**本就含 Comments 屏**（微栈内导航不叠层）；
本 route 只服务 **Surface 外**入口（Screen 评论按钮、将来的互动通知
评论卡），单层容器纪律不变。iOS 同构先例：`.comments` 路由 →
`CommentsSurfaceViewController`（复用整条 ChatDetail 栈、初始屏
Comments，CommentReport 等二级页同栈可跳）。

#### 落地（零 RN 改动 —— Surface root/注册/微栈全是现成的）

- `AppRoute.Comments(targetType/targetId/creatorId/commentId?/rootId?)`
  照 iOS `TipsyRoute.comments` 五参；props 形状照 iOS 容器（camelCase、
  targetType **Int**、定位参数仅有值时下发）。
- `CommentsSurfaceContract` + checklist `COMMENTS` 八项微根 +
  `CommentsSurfaceContractTest` 5 条静态 gate（注册语句、微根开标签
  双向序比对、**复用 ChatDetailStackNavigator + 初始屏 Comments** 的
  结构断言、props 键与 RN root 消费一一对应）。
- `MainActivity`：导航分支走通用 `openSurface` 链 + 带参谓词版退栈
  解除（同 ChatDetail）。
- Screen 侧：`onCardEvent` 上移到 Fragment —— 埋点仍全走 ViewModel
  （会话内去重在 tracker），`COMMENT_CLICK` 额外发 `AppRoute.Comments`
  （iOS `ScreenViewController:835-845` 同序：先埋点后路由）。
  targetType 恒 `character`（iOS 硬编码，Screen feed 全角色卡）。
  Surface 盖住时视频自动暂停 —— `hasVisibleSurface()` 是容器级通用判定，
  无需新逻辑。

#### §9.1 矩阵（模拟器 Pixel 10 / API 37，directApk；⚠️ 不作覆盖升级证据）

| 项 | 结果 |
| --- | --- |
| 初始 route fixture | ✅ Screen 点评论 → 评论页完整打开：标题计数与卡片一致（Comments(2)）、真实评论数据（文本 + 图片）、翻译/回复/点赞/输入框全渲染 |
| Back/栈底 | ✅ Back 回 Screen，视频恢复播放 |
| 关闭重开（带参解除） | ✅ 同一作品评论页关闭后立即重开成功 |
| 旋转 | ✅ 横↔竖往返评论页存活 |
| 15 次挂载/卸载 | ✅ Activities=1、ViewRootImpl=1，无累积泄漏 |
| 进程恢复 | ✅ 评论页开着 force-stop → 冷启动 → 重开成功 |
| 未登录 | ✅ 同 §2.48/49：游客机冷启动即登录页，入口路径不可达 |
| 语言切换 / OTA N/N-1 | ✎ 未跑（同前两刀的理由） |
| 发评论/删评论真实往返 | ✎ 真机冒烟项（不污染测试账号的评论区） |

#### 未做

- 互动通知评论卡入口（`NotificationSurface` 未启用，届时 commentId/rootId
  定位参数才有消费方）；`openComments` 桥方法（Surface 内跨栈出口）——
  当前无调用场景，等 NotificationSurface 那刀一起。
  ✅ **两条都已由 §2.51 兑现**（2026-08-20 同日）。

### 2.51 W4 批次 4 第一刀：NotificationSurface + 跨栈桥三件套（2026-08-20）

`AppRoute.Letter` 进生产白名单（第 **11** 类）—— ChatList 铃铛从 reject
变成打开三 tab 站内信。**这刀带 RN 侧改动**（桥模块，pin bump）：
`LetterItem`/`letter-detail` 的壳内分流早就在调四个可选桥方法
（iOS 壳先行，`?.()` 方法级守卫），Android 桥此前只有 `openUserProfile` ——
缺的三个在 RN 侧的表现是「互动通知点了没反应」且不报错。

#### 落地

- **桥（RN 仓 `18e6c10b5` + 测试修复 `f4fe474d2`，pin bump）**：
  `openComments`（键 **snake_case**，对齐 RN Comments 路由参数 ——
  与 `openChatDetail` 的 **camelCase** 不同轴，各照各的 RN 真值，
  别"统一风格"）、`openChatDetail`（分流素材透传 + 数字转型，
  `resolveInitialParams` 自决初始屏）、`openFeedback`（落 SettingsSurface
  直达 Feedback 屏）。三个都触碰导航 → 全走 `onMain`；
  `MainThreadDispatchTest` 覆盖表 9 → 12。Android 注册方法数 17 → 20。
- **壳 provider**：`openComments` guard 缺 target 不跳（iOS 同义）；
  `openChatDetail` 空 characterId 不跳；`ShellAuthProviderTest` +5。
- **路由**：`Letter` 从 data object 改 **data class（可选 tab）**——
  `NotificationSurface.tsx` 的 `tab` prop（System/Personal/Engagement，
  缺省 System）；铃铛入口不传。SurfaceProps 只在有值时放键。
- **静态 gate**：`NotificationSurfaceContract` + checklist 八项微根 +
  ContractTest 6 条 —— 含**跨栈出口双向锁**（RN `?.()` 调用的方法名
  必须与 Android 桥 `AsyncFunction` 注册名一致，桥方法名拼错的表现
  是静默降级）。World 作品图（`openSimulatorGame`）壳侧无路由，
  RN 注释已明确降级不响应 —— 刻意不实现。
- 「未启用样本」测试主体 Letter → GemsPurchase（下一刀启用时再换；
  样本耗尽时改为断言白名单 = route 全集）。

#### §9.1 矩阵（模拟器 Pixel 10 / API 37，directApk；⚠️ 不作覆盖升级证据）

| 项 | 结果 |
| --- | --- |
| 初始 route fixture | ✅ 铃铛 → 三 tab 站内信打开（System 默认选中、Personal/Engagement 可切、All Read 在位、空态插画正常） |
| Back/栈底 | ✅ 回 ChatList |
| 关闭重开 | ✅ 谓词解除生效 |
| 旋转 | ✅ 往返存活 |
| 15 次挂载/卸载 | ✅ Activities=1、ViewRootImpl=1 无泄漏 |
| 进程恢复 | ✅ force-stop 冷启动后重开成功 |
| 未登录 | ✅ 同前三刀：游客机即登录页，路径不可达 |
| **Engagement 跨栈出口实操** | ✎ **测试账号无互动通知**（评论卡/作品图/头像无真实数据可点）—— 桥语义由 5 条单测 + 双向静态锁覆盖，端到端点击留真机冒烟（用有互动记录的账号） |
| 语言切换 / OTA | ✎ 同前 |

#### 兑现 §2.50 的两条欠账

互动通知评论卡的 commentId/rootId 定位参数与 `openComments` 桥方法
都随本刀落地（此前记「等 NotificationSurface 那刀一起」）。

### 2.52 W4 批次 4 第二刀：Gems/UserCoins 双放行（2026-08-20）

`AppRoute.GemsPurchase` + `AppRoute.UserCoins` 进生产白名单（12、13 类）——
Profile 钱包卡的三个出口（宝石 +/Upgrade/Coins →）与 **402 付费墙兜底**
全部有下一屏。批次 4 的三个 Surface（Notification/Gems/UserCoins）收口。

#### 落地（零 RN 改动 —— 两个 Surface root 都是现成的）

- **Gems props 六个 + snake_case 别名归一**：iOS 容器
  （`TipsyRouter.swift:349-363`）对每个键做 camelCase/snake 双读，
  壳照做 —— 宝石页任务入口传 snake 键时不归一的表现是初始 tab 静默
  回落 subscription。`preferNextPlan` 按 iOS `boolParam`（1/true/yes，
  大小写不敏感）转 **Boolean**（RN props 是 boolean，字符串 "false"
  会被真值判定当 true）。空值键不放。
- **三入口早已汇到同一 route**：Profile 钱包卡（W3 预定义）、桥
  `openGemsPurchase`（W1 实现）、402 兜底（`ApiErrorGate` 防抖后）——
  本刀只是放行 + props 映射，`onNavigateGemsPurchaseRequested` 装配链
  一行没动。
- UserCoins 无 props（页面自取 user store）；微栈是 ProfileStack 三页
  子集（UserCoins/WithdrawExplain/WithdrawStatus，RN 刻意不复用全栈）。
- 「未启用样本」测试主体 GemsPurchase → `DailyGemEntry`（深链目标）。

#### §9.1 矩阵（模拟器 Pixel 10 / API 37，directApk；⚠️ 不作覆盖升级证据）

| 项 | 结果 |
| --- | --- |
| 初始 route fixture | ✅ 三出口逐个实测：宝石 + → Buy Gems tab（`initialTab` 分流对，余额 1234158.9 与 Profile 卡一致，五档价目 + 邀请码 + 任务区全渲染）；Upgrade → Subscription tab（Standard 档卡片 + Yearly/Monthly 价格）；Coins → 金币兑换页（Withdraw Balance + Coins to Gems 表单，「提现仅 Web」提示在位） |
| Back/栈底 | ✅ 各自回 Profile |
| 关闭重开 | ✅ 谓词解除生效 |
| 旋转 | ✅ Gems 开着往返存活 |
| 12 轮交替开关 | ✅ Gems/Coins 交替 6 轮：Activities=1、ViewRootImpl=1 无泄漏 |
| 进程恢复 | ✅ Gems 开着 force-stop → 冷启动 → 重开 Coins 成功 |
| 未登录 | ✅ 同前：游客机即登录页，路径不可达 |
| **真实购买/订阅** | ✎ **模拟器无 Play Billing，不可测** —— 支付闭环（拉起收银台/回执/余额入账）是真机冒烟**置顶项**，且三渠道各验（GooglePlay=Billing、directApk/RuStore=网页或三方支付的渠道分流） |
| 402 付费墙实弹 | ✎ 需构造宝石不足的真实请求，留真机 |
| 语言切换 / OTA | ✎ 同前 |

#### 渠道分流现状说明

矩阵跑在 directApk。`GemsSubscriptionSurface` 内部的渠道分流（Play
Billing vs 网页支付）由 RN 按构建渠道自决 —— 壳侧无渠道分流代码，
`X-Download-Channel` header 与 `DOWNLOAD_CHANNEL` BuildConfig 已在
W1 就位。googlePlay flavor 的同页渲染留真机冒烟一并验。









## 3. 横切能力


| 能力 | 状态 | 落地处 |
| --- | --- | --- |
| Auth 所有权 | 🟡 **closeout 已实现且 CI 已验**（§2.22），完整用户会话待 merge-head CI | `shell/auth/` + `shell/user/`（§2.13 / §2.18 / §2.46）。single-flight/generation/原子条件清理已收口；Application 统一发布 Native store 与 RN `user-storage`，登录要求完整快照成功后才广播；历史 token 迁移未完（P2） |
| `tipsy-auth` Android 实现 | 🟡 **桥已注册、能力 PARTIAL** | `modules/tipsy-auth/android/` + `ShellAuthProvider`；主线程约束已落地。§2.36 回填了 `requestLogin` / `openUserProfile` 系三个**标签过期的桩**（debug 会抛）—— ⚠️ **能力落地后必须回来改 override**，且现由 5 条单测钉死；§2.43 新增可选零参 `notifyProfileChanged`，Android 注册方法数 **17**，旧 iOS/旧壳无需同步实现；仍未实现的只有 `notifyOnboardingCompleted`（W4） |
| 网络层 | 🟡 **closeout 已实现且 CI 已验**（§2.22） | `shell/network/`（§2.14 / §2.18）。过期 token 发送守门与双入口共享 gate 已实现。**未引 Retrofit** |
| i18n | 🟢 **已完成**（含语言设置页 + 信封回写） | `shell/i18n/`（§2.16）。壳是唯一 writer；key-based 查表 + 两条 normalize 规则 + Compose 自订阅组件。**原生语言设置页已实现**（§2.33）—— RN 的 `SettingsSurface` 白名单刻意不含 `Language`，iOS 侧也是原生 `LanguageViewController`。写入走 `POST /user/set_language` **+ 回写 `user-storage` 信封**（§2.38，2026-08-18）—— ⚠️ 原记「不经 Zustand 信封」，**那正是 §2.37 语言倒灌的根因**，已修：`AccountLanguageWriter` merge + `notifyUserStoreChanged`。真机冒烟仍未跑（§9.1「语言切换」列待复跑） |
| Router / 深链 | 🟡 parser/router 机制已落地，**生产白名单六个目标** | `shell/router/`；三个纯原生（`Search` §2.31 / `UserProfile` §2.32 / `Settings` §2.33）+ 三个 Surface 目标（`ChatDetail` / `MiniPhoneChat` §2.36 + `Create` §2.40）。带参路由用谓词解除去重；无参 data-object 路由必须在退栈后按类型解除，否则只能打开一次。`EditProfile` 已预接解除但 policy 仍关闭（§2.43） |
| RN Surface 宿主 | 🟡 机制已落地；**业务参数通道 P9 才真正接上** | `RNSurfaceFragment`（共享单 ReactHost）；UUID/首帧/reappear/props builder 已有。§2.46 首帧宿主已对齐 iOS（不透明 Native wrapper + 透明 RN Root，无 cover/时机猜测），并修复 KeyboardProvider 污染共享 Activity inset；真实 instance-aware close **记为已接受偏差**（§2.36） |
| Media3 / Screen 视频 | 🟡 **P2 机制已落地，验收未闭环** | `pages/screen/ScreenPlayerPool` / `ScreenPlayerLedger` / `ScreenVideoHost` / `ScreenVisibility`（§2.42）：`largeMemoryClass` 3～5 有界池、±1 窗口、RN Android buffer、动态 50MB `SimpleCache`、三轴播放门与 audio focus=true。最终 head G1 全绿；真实视频/cache 失败/API24–33 层序/audio focus 四项仍 NOT RUN，next-item/fade/firstInteractive/P3 未做，故不 production-ready |
| Push | 🔴 未开始 | — |
| Analytics（Qt） | ⏸️ **推迟，但 facade 已落地** | `shell/analytics/Analytics`（§2.23）：业务页照常调用、uid 排队语义照搬 RN，debug 落日志。Qt 接线本身仍推迟（§2.17）—— ⚠️ **`preInit` 一次都不会调**，facade 存在 ≠ 埋点在上报 |
| 营销 SDK（ATT/AppsFlyer/FB/TikTok） | 🔴 未开始 | iOS 事故点，方案 §4.2 |
| Sentry | ⏸️ **已决定推迟** | 同上（§2.17）。⚠️ JS 侧 `autoInitializeNativeSdk: false` 已把事件交给一个从未 init 的原生 SDK |
| Widget | 🔴 未开始 | — |
| OTA | 🔴 未开始 | 隔离方案见 §5.3。W0 已**显式禁用** expo-updates 资源任务（原因见 §2.2.2），W4 接入时需先解决其 projectRoot 推导 |
| CI | 🟡 **G1 已激活** | `.github/workflows/android-ci.yml`（§2.10）。**G3 nightly 未建** —— 三 flavor 全量与 release 打包无自动防线 |

## 4. Surface 验收矩阵

`DebugSurface` 已完成 W0 的宿主机制验证，但它是自检入口，不代表生产 Surface
通过 §9.1。

**`ChatDetailSurface` 是第一个进生产白名单的业务 Surface**（§2.36，P9）：
微根 18 项与 5 个微栈目标已由 `SurfaceDependencyChecklistTest` 对 RN 源码
机器断言，桥依赖已回填。

**§9.1 冒烟已部分兑现**（§2.37，2026-08-17）：未登录 / 登录切换 / Back 栈底 /
首帧 / Embedded / 50 次泄漏 **6 项 PASS**（无泄漏，容器实例恒为 1）；
**语言切换**当轮 FAIL —— 但那是壳缺陷（`refreshAccountLanguage` 倒灌，§5）
**不是 ChatDetail 的问题**，已修并于 §2.39 模拟器复跑 **PASS**（合计 **7 过**）；
旋转/进程恢复 NOT RUN（模拟器中途退出）。

⚠️ **这一行仍不得标 production-ready**：
- 语言那列 ✅ **已复跑并 PASS**（§2.39，2026-08-18）：模拟器切 繁中 → 开
  `ChatDetailSurface` → Back，语言**不再回落英文**，再连跑 4 次往返稳定；
  `initialProps.context.languageCode` 实测为 `zh-tw`（此前是 `en`），
  JS 侧同步打出 `align i18n to shell language: 'zh-tw'`。反向（繁中 → English）
  同样生效，信封最后一条**活记录**为 `en`。
- 旋转那列要补。
- 本轮跑在**模拟器**上 —— §2.5 已定模拟器不作覆盖升级证据，
  50 次泄漏与首帧值得真机复跑。

**`CreateSurface` 是第二个进生产白名单的业务 Surface**（§2.40，2026-08-18，
Tab3 的 ➕）。§2.41 已补齐微根、root stack、10 个实际微栈目标、注册名、
`createEnterSource` 消费链与 `hydrateTags` 前置的机器断言，关闭 §2.40 的静态
gate 欠账。⚠️ §9.1 的 8 个设备/生命周期验收格仍全 `✎`，
**不得标 production-ready**；机器清单
不替代真机生命周期证据。

`EditProfileSurface` 已预接静态契约/测试源码、账号隔离与
RN→Native Profile 刷新接力（§2.43），但相关测试并未执行，
`ProductionRoutePolicy` 仍关闭，§9.1 的 8 个设备/生命周期验收格仍全 `✎`。
**预接线不是验收通过。**

> Screen 是纯原生页，不属于 §9.1 Surface 矩阵；其 P2 四项 NOT RUN 单独记在
> §2.42，不能用 Screen 的模拟器证据改变任何 Surface 行状态。

除上面单列的 EditProfile 外，其余 9 个生产 Surface 均未验收：

`CommentsSurface` / `OnboardingSurface` / `DeleteAccountSurface` / `GemsSubscriptionSurface` / `NotificationSurface` / `RoleCardSurface` / `SettingsSurface` / `UserCoinsSurface` / `WidgetSurface`

矩阵表格见方案 §9.1。**未填满的行不得标 production-ready。**

## 5. 未决问题

方案 §12 是开放问题登记，不再能写成“10 项全部未决”：

- **§12.1 Qt lifecycle** 已按 §2.17 决策推迟；原“保留 listener / 排除模块”
  二选一前提也已被源码证据推翻。
- **§12.5 `AuthBootstrapSurface`** 随 P2 剩余/P3 合并推迟到上线前。
- **§12.3 QA 分发形态**仍需发布阶段定案，但 W0 已完成，不能继续写成“阻塞 W0”。
- **§12.1 Surface 实例关闭链**（`popSurface` 的 instanceId）✅ **已定为可接受偏差**
  （owner 2026-08-17，§2.36）：TS 契约无参、Android 桥固定传 `null`，实例比对
  恒短路。**单层容器下弹不错**，故不改跳仓。⚠️ **多层容器出现前必须根治**。
  ⚠️ **两条前提已重新核实**（2026-08-20）：
  1. 原记「第一个触发场景是 ChatDetail 内再开 CommentsSurface」**不成立** ——
     `ChatDetailSurface` 微栈挂的是完整 `ChatDetailStackNavigator`，
     **`Comments` 屏就在栈内**（`ChatDetailStackNavigator.tsx:130`），
     ChatDetail 内点评论是微栈内导航、不出容器不叠层。iOS 的 `openComments`
     桥（TS 已声明可选方法）服务的是 **Surface 外**入口：互动通知评论卡 →
     单层 CommentsSurface、Screen 评论按钮 → 同前。∴ **启用 CommentsSurface
     不触发多层容器**，§12.1 不是它的前置；真正会叠层的场景（Surface 内经桥
     再开 Surface）当前一个都没有。
  2. 原记「RN 侧 9 个调用点」**已过期** —— 当前 **29 文件 / 38 处**无参
     `TipsyAuth?.popSurface()` 直调（无 JS 封装层）。将来根治时逐点带参
     不再可行，应走集中封装（JS 包装 or 新桥方法 `.v2`）+ 双壳回归。

当前仍需 owner 结论的 W1 项：

- **§12.7 凭据分类与轮换**（安全 owner 结论）。

阻塞 W2 的：

- **§12.8 Google/Firebase 的 Android 签名指纹**（三 flavor × debug/release，**没有它 Firebase 登录无法真机验证**）
- ~~**§12.4 Home 是否包含 World 系列**~~ ✅ **已关闭**（2026-08-11，§2.23）：
  不需要产品决策 —— `home.tsx:505-511` 的 filter 已给出答案，**Android 显示 World、
  Multi-character 两端都隐藏**。World 点进去是 SimulatorGame WebView，方案 §8.1 已定不迁。
- **§12.9 Apple 登录按钮在 Android 是否展示**、**§12.10 `/login/password` 是否对外**

P9 冒烟新增的两项（2026-08-17，§2.37）：

- ~~🔴 **原生语言页的选择会被静默覆盖回英文**~~ ✅ **已修**（2026-08-18，§2.38）：
  owner 选**路 1**（语言页确认时回写 `user-storage` 信封）。前置顾虑已核实排除：
  该 key **无 `version`/`migrate`**，不存在性别筛选那条担心的 migrate 分支。
  §9.1 的「语言切换」列 ✅ **已复跑 PASS**（2026-08-18，§2.39，⚠️ 模拟器）。
- ~~🔴 **分级开关（Limitless）点了没反应**~~ ✅ **已修**（2026-08-18，§2.39）：
  `POST /user/nsfw` 少了 `/update`（真值 `/user/nsfw/update`）→ 404，因失败自动回滚
  而伪装成「没点到」。已补 MockWebServer 契约测试 + 失败 Toast。
- 🟡 **壳内 nsfw 镜像谁来接力**（§2.39 新增，**需 owner 定**）：§2.46 已让
  `/user/info` 完整写 `user-storage.nsfw`，RN runtime 启动后会按现有订阅接力到
  `config-persist.nsfw`；但纯 Native 路径在 RN 未启动时仍不会写 config 信封。
  后果从「永远不出现」缩小为「首次 Surface 前可能不出现」，仍需定所有权与验收。
- **`GeneralMediaViewer` 的 `useVideoPlayer` 在容器拆除瞬间构造失败**（RN 侧，
  双壳共有，1/50 频率）—— 详见 §2.37。**需 owner 定**：可接受偏差，还是加
  `visible` 早退（要双壳回归）。

> ⚠️ **共享 MMKV 信封的读写方向已系统扫过一遍**（2026-08-18，§2.38）——
> 不再是「建议扫」。结论表落在 `LegacyMmkvStore` 类注释（代码里，随读写点一起改），
> 本文只记结论：**语言那例已修，性别筛选那例仍待 owner**，其余键方向正确。
> 这类缺陷的共同表现是「改了、看起来生效了、过一会儿又回去了」，用户不会报。

W2 真机验证新增的一项（2026-08-12，§2.23.1）：

- **性别筛选持久化在信封缺失时静默失效** —— `config-persist-storage` 不存在时
  `mergeGenderIntoEnvelope` 刻意 `return null` 不写，导致全新安装用户改性别
  永不持久化、每次冷启动退回 `All`，且 UI 无任何提示。已核实壳读写路径正确
  （RN 的 `zustandStorage` 也是默认 MMKV 实例），根因是信封尚未被 RN 初始化。
  **需 owner 在两条路里定**：(1) 缺失时写仅含 `{state:{gender}}` 的最小信封；
  (2) 判为可接受，等 W3 迁 Settings 时 RN store 必然已初始化而自愈。

  ⚠️ **原写的前置顾虑「须先核实 RN 的 `version`/`merge` 配置，否则可能触发
  migrate 分支」已在 §2.38 核实完毕，但结论对这个 key 是「顾虑成立」** ——
  与语言那个 key 相反，别把 §2.38 的结论直接套过来：

  | | `user-storage`（语言） | `config-persist-storage`（性别） |
  | --- | --- | --- |
  | `version` | 无（= 默认 0） | 无（= 默认 0） |
  | `migrate` | **无** | **无** |
  | `merge` | 无（默认浅展开） | **有自定义 `merge`**（`config_persist.ts:447-476`） |

  所以 migrate 分支两边都不会触发（zustand 只在 `version` 不等**且**配了
  `migrate` 时走那条，`middleware.js:389`）—— 真正的差异是那个自定义 `merge`：
  它会对 `tags` / `autoConsumeGemCallByUser` / `characterBadgeConfigs` 做
  normalize，且**刻意丢弃 legacy `autoConsumeGemCall`**。造最小信封本身安全
  （缺字段回落 store 默认值），但**要确认那个 merge 对只含 `gender` 的
  state 不产生副作用**才能落地路 1。这一步尚未做。

## 6. 已废弃的历史尝试

`migration/android-native-p00-bootstrap` 分支（P00 文档基线 + Gradle 脚手架尝试）
**已于 2026-08-08 废弃并删除远端**，其上工作作废，**不作为任何决策依据、不要去恢复参考**。

其中仍然有效的知识已全部吸收进当前两份文档：

| 原分支上的内容 | 现在在哪 |
|---|---|
| iOS 迁移复盘（时间线 / 十条经验 / 反模式） | 方案 §1.3 归属表、§3.2 各 ADR、§1.2.1 十条经验与反模式、§8.4 列表纪律、§10 风险登记 |
| Node 可执行文件解析（fnm/nvm 下 GUI 启动 sync 失败） | 方案 ADR-004 第 3 条（四级解析优先级 + launchd GUI 域 PATH 两层，含 2026-08-10 对「PATH 前置」的订正） |
| 三渠道 / config plugin / 桥模块等硬约束 | 方案 §2（**已在 pin `93d2c5551` 重新核实过源码**，不依赖旧报告） |
| CNG prebuild 审计报告（基线 `cbd521f02`） | 不再引用。其结论中可核实的部分已重新核实；**RN lint/test/doctor 的具体红项数量待 W0 实跑** |

**纪律**：本仓不再有"去某个分支恢复内容"的路径。方案与本文是唯一依据。

## 7. 状态更新纪律

1. 每个波次开始时把 `source_rn_sha` / `target_android_sha` 填成完整 40 位 SHA。
2. 波次结束跑 RN delta 审计，把变化映射到对等矩阵。
3. 发现文档与代码不一致时，**先修文档再继续实现**。
4. 不在其他文档里复制状态快照——重复的「当前进度」是 iOS 侧真实发生过的漂移源（同一文档记过不同的 submodule pin）。
