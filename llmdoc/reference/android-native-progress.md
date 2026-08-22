# Tipsy Android 原生化迁移：现状（唯一状态真值）

> 更新：2026-08-22（§2.58 双桥补齐 + §2.59 CreateSurface 矩阵与 tracking 红屏修复，pin `77b567cff`）
>
> **状态只有一份**：速览见 §0，波次见 §1，横切能力见 §3，Surface 验收见 §4，
> 未决问题见 §5。逐刀记录（§2.x）在
> [android-packet-log.md](android-packet-log.md)（最新 §2.59），本文 §2 只留指针。
> 本头部不再复制状态快照 —— 此前头部与 §0/§1 三重记录同一状态，
> 已实际发生漂移（ChatMap/分享/EditProfile 三处头部滞后于正文），2026-08-22 收敛。
>
> 真机冒烟纪律：owner 2026-08-14 决定**统一推迟到功能完成后**（待验清单只累积
> 不清空）；owner 2026-08-17 追加 **P9 与 Screen P2 各插一次冒烟**（已跑，模拟器，
> §2.37 / §2.39 / §2.42）—— 这两类失败模式（Surface 泄漏、OOM）只能设备暴露。
> ⚠️ 模拟器证据一律不作覆盖升级结论（§2.5）。
>
> 配套决策方案：[android-native-migration-plan.md](../architecture/android-native-migration-plan.md)
> **本文是状态权威。** 方案文档只写决策不写状态；任何「进度/是否已实现」的问题一律以本文为准。

## 0. 三十秒速览

- **波次进度**：W0 完成；**W1 完成**（契约层已在 CI 组合验证（§2.22）；**P9 已完成**（§2.36）；§12 关闭链记为已接受偏差）；P2 剩余/P3/P7/P8 均已决策推迟。W2 主体已落地：五 Tab + Home + Login（§2.23/§2.24，PR #20 已并，剩 banner / 彩蛋 / mp4 封面且倾向留 RN Surface）。**W3 业务面全部落地**：Profile 全部批次完成（P1–P7，§2.25–§2.29、§2.44、§2.45）；ChatList P1（§2.30）+ **P2 ChatMap 已并入 main**（§2.47，PR #42，模拟器全链路实测）；Search P1/P2（§2.31/§2.34，完整对等）；他人主页（§2.32）；Settings 列表/语言（§2.33）；**EditProfile 预接（§2.43）并已放行**（§2.49，模拟器 §9.1 矩阵已跑，换号格与真机项累积）。**W4 进行中**：Screen P1/P2 + 卡片交互 + 原生分享（§2.35/§2.42/§2.55/§2.57）；Surface 批次 3–5 已全部放行（§2.48–§2.53）；G3 nightly 已建（§2.54）。剩余见「不存在 / 未验」。
- **代码现状**：`ai.lightspeed.tipsy.shell` 下有 `TipsyApplication`（单 ReactHost + Analytics facade）+ `MainActivity`（Tab 根 + Router/i18n 接线）+ `RNSurfaceFragment` + `auth/` + `network/` + `router/` + `surface/` + `i18n/` + `bridge/` + `analytics/` + `tabs/` + `user/` + `share/`（§2.57）+ **`pages/login/`、`pages/home/`、`pages/profile/`、`pages/chatlist/`、`pages/search/`、`pages/screen/`、`pages/settings/`**；EditProfile 刷新接力落在 `pages/profile/ProfileRefreshHub` 与 `tipsy-auth.notifyProfileChanged`。
- **submodule**：pin **`77b567cff`**（§2.56 合流基线 `86191c090` → §2.57 分享词条 `1f018aee6` → §2.58 双桥 `9d9240143` → §2.59 tracking 红屏修复）。⚠️ `da4f65a` 与 PR #34 的三处壳改动**必须同时存在**：指针回退则 exclude 仍失效，而 styles/lifecycle 两处已让构建变绿 —— 会得到「构建通过但图片仍坏」的假绿。⚠️ §2.59 教训：**pin bump 后至少在模拟器挂一次任意 Surface** —— 静态 gate 与 G1 都拦不住「顶层 import 在原生模块缺席时求值抛」。
- **已验证**：main 最新 merge（PR #60，2026-08-22）的 G1 Fast Gate 全绿；**G3 nightly 已建且首跑三 job 全绿**（§2.54：三 flavor debug 全量、googlePlayRelease R8 打包 + manifest 产物 grep、API 24/36 双档 instrumentation）。逐刀的本机门禁与单测数见对应 §2.x（最近一次全套件快照：§2.56，app 单测 1153 条 failures=0 / skipped=0）。
- **他人主页已实现**（§2.32，2026-08-14）：6 文件 1,469 行，`AppRoute.UserProfile` 进白名单 —— **搜索 → 创作者 → 他人主页是壳的第一条端到端可用路径**。审计推翻了「复用自己视角」的前提（七处偏差）：只有 **1 个 tab**（RN 注释说两个，代码是一个）、数据源另有四条、`size` **200 且不翻页**、`/user/get/public` 走 `axiosAuth` 会对游客弹登录页、`/plot/list/creator` **现网从未被调用**、关注按钮在 `ProfileHeader.tsx` 而非 `user-profile.tsx`。真机冒烟 **NOT RUN**。
- **Settings 列表 + 语言页已实现**（§2.33，8 文件 1,501 行）：补上了真实功能缺失 —— 此前**壳内没有任何入口能改语言**。审计订正三处：语言页**要原生实现**（RN 与 iOS 双证据）、`supportedLanguages` 壳内**恒为空**必须自己拉、Limitless 开关是 `nsfw` 的**唯一写方**且仅 directApk 可见。渠道 gating 收在 `SettingsRow` 并对三渠道各有单测。7 个 Surface 子屏（`AppRoute.SettingsSubScreen`）已于 §2.48 放行并跑过模拟器 §9.1 矩阵。
- **P9 冒烟部分兑现**（§2.37，2026-08-17）：§9.1 十项里 **6 项 PASS**（未登录/登录切换/Back 栈底/首帧/Embedded/50 次泄漏 —— 无泄漏、容器实例恒为 1、PSS +3.7%）、**语言切换**当轮 FAIL（壳缺陷倒灌，非 ChatDetail 问题；已修并于 §2.39 复跑 **PASS**）、旋转恢复 NOT RUN。⚠️ 跑在**模拟器**上，§2.5 已定不作覆盖升级证据。同时查出 **autolinking exclude 从 W0 起一直静默失效**（PR #34）—— 此前所有构建都带着 dev-launcher 在跑。
- **语言倒灌已修 + 共享键系统扫描**（§2.38，2026-08-18）：owner 选路 1 —— 语言页确认时回写 `user-storage` 信封（`AccountLanguageWriter` merge + `notifyUserStoreChanged`）。**跨仓契约是现成的**：RN 侧监听、桥方法、iOS 实现三者都在，只是 Android 壳从没调过 → **零 RN 改动、零 submodule bump**。前置顾虑核实排除（该 key 无 `version`/`migrate`，⚠️ 但性别筛选那个 key 有自定义 `merge`，结论**不可外推**）。9 个共享键的读写方向已全扫，表落在 `LegacyMmkvStore` 类注释。app 单测 **907 条** skipped=0，三组测试均做过反向验证。✅ **§9.1 语言那列已复跑 PASS（模拟器，非真机）**（§2.39）。
- **语言复跑 PASS + 分级开关 404**（§2.39，2026-08-18）：语言那列 FAIL → **PASS**（`initialProps.context.languageCode` 实测 `zh-tw`，往返 5 次稳定）。同轮查出**`POST /user/nsfw` 少了 `/update` → 404**：因 `onNsfwToggle` 是刻意的非乐观更新（失败自动回滚），404 表现为「开关点了自己弹回去」，与「没点到」无法区分；fake-API 单测验不到真实路径，已补 `SettingsApiContractTest`（MockWebServer 真往返，已反向验证）。顺带修 `SettingsFragment` 从不观察 `languageError` 导致的**零提示**（收集器挂 `onViewCreated`，不是 `onStart`）。⚠️ 读共享 MMKV 必须先解析头部 `actualSize` —— 追加写会让 `tail` 读到上一代残留，方向正好相反。
- **2026-08-19 Surface/会话稳定性收口**（§2.46）：Profile 短列表恢复滚动；Android 原生登录把 token、完整 `/user/info` 快照与 loggedIn 收成一个事务；登出/换号同步清 `user-storage` 与无账号维度聊天 LRU；Surface 首帧宿主对齐 iOS；修复 KeyboardProvider 污染共享 Activity 状态栏 inset。RN pin 已推 `feat/android-native`。
- **✅ 卡片点击已解锁**（§2.36，2026-08-17）：`ChatDetail` / `MiniPhoneChat` 进白名单，Home/ChatList/Search/Screen 四个页面的卡片点击**第一次有下一屏**。此后批次 3–5 陆续放行 Settings 七子屏 / EditProfile / Comments / Notification / Gems / UserCoins / RoleCard（§2.48–§2.53）—— **仍点不动的只剩 Follow**（RN 无 FollowSurface，出口无处可去，待 owner，§2.25）。§12 实例关闭链**记为已接受偏差**（单层容器弹不错；根治要走集中封装 + 双壳回归，见 §5）。
- **Tab3 创建入口已接通**（§2.40，2026-08-18，219 行）：`AppRoute.Create` 进白名单，Tab3 的 ➕ 从「只打一行日志」变成挂 `CreateSurface` 直达创建表单 —— **五个 Tab 全部可用**。壳只传 `createEnterSource` 一个 prop，**刻意不复刻** RN tabPress 那四个参数（Surface 自决落地页，§2.30 纪律）。⚠️ 真机抓到**类别性**缺陷并已修：`AppRoute.Create()` 无参 ⇒ 实例恒相等 ⇒ 去重不解除就「只能用一次」，ChatDetail 因每次带不同 characterId 而侥幸未暴露；后续每个无参路由都要配解除。✅ §2.41 已补微根/微栈/注册/bootstrap 机器断言；✅ **§9.1 模拟器矩阵已跑**（§2.59，2026-08-22：8 格 PASS，未登录格含 API 24/37 双档；登录切换格待双账号）。
- **Screen P1 + P2 代码已实现**（§2.35 / §2.42）：P1 落 AB 端点分流、归因、首屏缓存与会话埋点；P2 让 `showcase` 首次接入 Media3、有界播放器池、±1 窗口、RN Android buffer、动态 50MB cache、三轴播放门与声音开关。PR #39 最终 head `13cc633` 的 G1 全绿；⚠️ feed 无 showcase，真实视频/cache 失败/API24–33 层序/audio focus 四项仍 NOT RUN，故**不是 production-ready**。
- **Screen 原生分享已并入 main**（§2.57，2026-08-22，PR #60 G1 全绿）：按 RN Android 产品语义 + iOS 原生职责边界落同页全屏 Dialog、即时审核、相册保存、Copy/Discord/Instagram/TikTok/X/Facebook、最近使用排序与可靠推荐 share outbox。Reel 路径暂沿用 RN 的 character-backed id，但明确保持 `videoId=null`，不伪造视频 id 调分享计数接口。单测/lint/assemble 已由 G1 组合验证；模拟器/真机和社交 App 矩阵 **NOT RUN**（清单见 §2.57）。
- **Search P2 筛选器已实现**（§2.34，4 文件 723 行）：性别/排序/分级抽屉 + 二级标签栏，Search 达成完整对等。`SearchTagOrderTest` **逐条对拍 RN 的 144 行现成单测**。⚠️ 分级筛选的门是「非 GooglePlay && nsfw 开」，与 Settings 的 Limitless（只有 directApk）**不同轴** —— RuStore 在这里算可选。
- **不存在 / 未验**：Screen P2 已落代码但四项核心验收未跑，next-item/fade/firstInteractive/P3 仍无（卡片四类交互已对齐，§2.55）；Sentry、Qt 实际上报、core/feature 模块均无。生产路由白名单 **15 类**（§2.55 后）：三纯原生 + 十二 Surface/带参目标（+RoleCard、+CharacterDetail）。注册组件 13 个（含 Debug）；**12 个业务 Surface = 9 启用**（ChatDetail/Create/Settings/EditProfile/Comments/Notification/GemsSubscription/UserCoins/RoleCard）**+ 2 并入 Settings**（DeleteAccount/Widget，§2.53 判定，注册仅为 OTA 偏斜保留）**+ 1 待评估**（Onboarding，需新注册链路）。各 Surface 的真机格仍在累积清单。⚠️ 待 owner：**性别筛选持久化静默失效**（§2.23.1）与 **Follow 出口无 Surface 可用**。

## 1. 波次状态

| 波次 | 内容 | 业务量 | 状态 | source_rn_sha | target_android_sha |
| --- | --- | --- | --- | --- | --- |
| W0 | 工程地基 + brownfield DebugSurface | 基建 | 🟢 完成 | `93d2c5551` | `4f191e8` |
| W1 | 平台契约 + auth + ChatDetailSurface gate | 基建 | 🟢 **完成**：契约层已收口且 CI 已验；**P9 已完成**（§2.36，白名单放开 + 桥桩回填）；§12 关闭链记为**已接受偏差**（owner 2026-08-17）。**冒烟部分兑现**（§2.37 + §2.39）：§9.1 **7 过 / 3 未跑**，业务分流项未验。语言那项的壳缺陷已修（§2.38）且**复跑 PASS（模拟器）**（§2.39） | `95760a6622424bc9be238e7790fdbf38fe7c7fb2` | —（PR #16/#17/#33 已并） |
| W2 | Bootstrap + 五 Tab shell + **Login** + **Home** | 约 10k 行 RN | 🟡 **主体已落地**：Login 邮箱链路已验、五 Tab + Home 首屏、筛选抽屉 + 冷启动种子均已并入 main（§2.20 / §2.23 / §2.24）。剩 banner / 彩蛋 / mp4 封面（banner 与彩蛋倾向留 RN Surface，方案 §8.1） | `95760a6622424bc9be238e7790fdbf38fe7c7fb2` | —（PR #19 / #20 已并） |
| W3 | **Profile** + **ChatList** + **Search** + Settings 列表/语言 | 约 19k 行 RN（最大） | 🟡 **业务面全部落地**：**Profile 全部批次完成**（P1–P7，§2.25–§2.29、§2.44、§2.45）；ChatList P1 + **P2 ChatMap**（§2.47，PR #42 已并入）、Search P1/P2、他人主页、Settings 列表/语言均已落；**EditProfile 预接（§2.43）→ 已放行**（§2.49，模拟器 §9.1 矩阵已跑），会话/Surface 稳定性收口见 §2.46。剩：累积真机冒烟（EditProfile 换号格置顶） | `93c8647f3`（§2.47 bump，含 §2.46 的 `5ba22c8bb`） | —（PR #21–#30、#41–#44、#46 已并；#45 是 #46 的前身，因叠栈 base 删除被自动关闭） |
| W4 | **Screen/Media3** + 12 个 Surface + 系统能力 + OTA | 约 5.3k 行 RN + 系统 | 🟡 **进行中**：Screen P1 数据链（§2.35）、P2 Media3 有界播放机制（§2.42）、卡片交互对齐（§2.55）、**原生分享已并入**（§2.57，PR #60）；Surface 批次 3–5 已全部放行（§2.48–§2.53）。P2 仍有真实视频/cache 失败/API24–33 层序/audio focus 四项 NOT RUN，故不 production-ready。剩：Screen next-item/fade/firstInteractive/P3 其余项、Onboarding 评估、系统能力（Push/营销 SDK/Widget/Qt/Sentry）、OTA、真机冒烟批次 | `da4f65a04f50bc098c2df3bd9f8fbcc13018f7a5`（波次开始时；当前 pin 见 §0） | —（进行中；每刀的 merge sha 见对应 §2.x） |
| W5 | 对等 / 性能 / 三渠道发布切换 | 发布 | ⬜ 阻塞于 W4 | — | — |

**W0+W1 时间盒**：这两波不产出用户可见价值，目标是"够用就往下走"。若超过总工期 1/4,停下复审是否过度设计（方案 §8.5）。

## 2. 逐刀工程记录（已拆分至日志文件）

§2 的逐刀记录（§2.1 起：每刀的实测约束、踩坑与验证证据）已于 2026-08-22
整体拆至 [android-packet-log.md](android-packet-log.md)，**小节编号原文保留**。

- **本文其余章节引用的 §2.x，一律指日志文件的同号小节**；代码注释 / 技能 /
  记忆 / PR 里的历史引用「进度文档 §2.x」同样在日志文件里找，编号永不复用。
- 新工作包在日志文件末尾追加 `### 2.<下一号>`（编号继续单调递增），并同步更新
  本文 §0/§1/§3/§4/§5 —— **状态仍只写本文一处**，日志只记过程与证据。

## 3. 横切能力


| 能力 | 状态 | 落地处 |
| --- | --- | --- |
| Auth 所有权 | 🟡 **closeout 已实现且 CI 已验**（§2.22），完整用户会话待 merge-head CI | `shell/auth/` + `shell/user/`（§2.13 / §2.18 / §2.46）。single-flight/generation/原子条件清理已收口；Application 统一发布 Native store 与 RN `user-storage`，登录要求完整快照成功后才广播；历史 token 迁移未完（P2） |
| `tipsy-auth` Android 实现 | 🟡 **桥已注册、能力 PARTIAL** | `modules/tipsy-auth/android/` + `ShellAuthProvider`；主线程约束已落地。§2.36 回填了 `requestLogin` / `openUserProfile` 系三个**标签过期的桩**（debug 会抛）—— ⚠️ **能力落地后必须回来改 override**，且现由 5 条单测钉死；§2.43 新增可选零参 `notifyProfileChanged`；**§2.58 补齐 `notifyChattedListChanged` + `notifyCreatedCharactersChanged`**（建群/创建角色后的原生列表刷新，双向静态锁 `BridgeSignalContractTest`），Android 注册方法数 **22**；仍未实现的桩只剩 `notifyOnboardingCompleted`（W4，Onboarding 评估时回填） |
| 网络层 | 🟡 **closeout 已实现且 CI 已验**（§2.22） | `shell/network/`（§2.14 / §2.18）。过期 token 发送守门与双入口共享 gate 已实现。**未引 Retrofit** |
| i18n | 🟢 **已完成**（含语言设置页 + 信封回写） | `shell/i18n/`（§2.16）。壳是唯一 writer；key-based 查表 + 两条 normalize 规则 + Compose 自订阅组件。**原生语言设置页已实现**（§2.33）—— RN 的 `SettingsSurface` 白名单刻意不含 `Language`，iOS 侧也是原生 `LanguageViewController`。写入走 `POST /user/set_language` **+ 回写 `user-storage` 信封**（§2.38，2026-08-18）—— ⚠️ 原记「不经 Zustand 信封」，**那正是 §2.37 语言倒灌的根因**，已修：`AccountLanguageWriter` merge + `notifyUserStoreChanged`。§9.1「语言切换」列已复跑 **PASS**（§2.39，⚠️ 模拟器）；真机仍在累积清单 |
| Router / 深链 | 🟡 parser/router 机制已落地，**生产白名单 15 类**（§2.55 后） | `shell/router/`（真值 = `AppRouter.kt` 的 `ProductionRoutePolicy`）；3 纯原生（`Search` §2.31 / `UserProfile` §2.32 / `Settings` §2.33）+ 12 个 Surface/带参目标（`ChatDetail`/`MiniPhoneChat`/`CharacterDetail`、`Create`/`EditCharacter`、`SettingsSubScreen`、`EditProfile`、`Comments`、`Letter`、`GemsPurchase`/`UserCoins`、`RoleCard`，§2.36–§2.55）。带参路由用谓词解除去重；无参 data-object 路由必须在退栈后按类型解除，否则只能打开一次。深链 parser 有了但 push/深链入口本身仍未接（Push 🔴） |
| RN Surface 宿主 | 🟡 机制已落地；**业务参数通道 P9 才真正接上** | `RNSurfaceFragment`（共享单 ReactHost）；UUID/首帧/reappear/props builder 已有。§2.46 首帧宿主已对齐 iOS（不透明 Native wrapper + 透明 RN Root，无 cover/时机猜测），并修复 KeyboardProvider 污染共享 Activity inset；真实 instance-aware close **记为已接受偏差**（§2.36） |
| Media3 / Screen 视频 | 🟡 **P2 机制已落地，验收未闭环** | `pages/screen/ScreenPlayerPool` / `ScreenPlayerLedger` / `ScreenVideoHost` / `ScreenVisibility`（§2.42）：`largeMemoryClass` 3～5 有界池、±1 窗口、RN Android buffer、动态 50MB `SimpleCache`、三轴播放门与 audio focus=true。最终 head G1 全绿；真实视频/cache 失败/API24–33 层序/audio focus 四项仍 NOT RUN，next-item/fade/firstInteractive/P3 未做，故不 production-ready |
| Push | 🔴 未开始 | — |
| Analytics（Qt） | ⏸️ **推迟，但 facade 已落地** | `shell/analytics/Analytics`（§2.23）：业务页照常调用、uid 排队语义照搬 RN，debug 落日志。Qt 接线本身仍推迟（§2.17）—— ⚠️ **`preInit` 一次都不会调**，facade 存在 ≠ 埋点在上报 |
| 营销 SDK（ATT/AppsFlyer/FB/TikTok） | 🔴 未开始 | iOS 事故点，方案 §4.2 |
| Sentry | ⏸️ **已决定推迟** | 同上（§2.17）。⚠️ JS 侧 `autoInitializeNativeSdk: false` 已把事件交给一个从未 init 的原生 SDK |
| Widget | 🔴 未开始 | — |
| OTA | 🔴 未开始 | 隔离方案见 §5.3。W0 已**显式禁用** expo-updates 资源任务（原因见 §2.2.2），W4 接入时需先解决其 projectRoot 推导 |
| CI | 🟡 **G1 + G3 nightly 均已激活** | G1：`.github/workflows/android-ci.yml`（§2.10，PR 门禁）。G3：`android-nightly.yml`（§2.54，2026-08-21 建，首跑三 job 全绿）—— 三 flavor 全量 debug、googlePlayRelease R8 打包 + manifest 产物 grep、API 24/36 双档 instrumentation。nightly 红不阻塞 PR 但要当天处理。Macrobenchmark 与全链路 instrumentation 仍缺（§2.54 刻意留白） |

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
Tab3 的 ➕）。§2.41 已补齐微根、root stack、微栈目标（§2.56 后 13 个）、注册名、
`createEnterSource` 消费链与 `hydrateTags` 前置的机器断言。
✅ **§9.1 模拟器矩阵已跑**（§2.59，2026-08-22）：初始 fixture / 未登录
（API 24+37 双档）/ Back / 关闭重开 / 旋转 / 15 次泄漏 / Embedded / 进程恢复
**8 格 PASS**；登录切换格待双账号（真机批次），语言切换由 §2.39 壳级复跑覆盖。
⚠️ 首格撞出并修掉 §2.56 合流引入的 tracking 顶层 import 红屏（P0，详见 §2.59）。
模拟器证据不作真机结论（§2.5）。

`EditProfileSurface`：§2.43 预接（静态契约、账号隔离、RN→Native Profile 刷新
接力）→ **§2.49 已放行并跑过模拟器 §9.1 矩阵**（保存→刷新接力、Back、重开、
旋转、15 次泄漏、进程恢复、未登录全过）。⚠️ **换号格（A→logout→B）是唯一
缺口且置顶** —— §2.43 单列的三条威胁只有真实双账号切换能证，需第二个测试账号。

**批次 3–5 的七个 Surface 已放行并各自跑过模拟器 §9.1 矩阵**：

| Surface | 放行 + 矩阵 | 模拟器未覆盖的专项 |
| --- | --- | --- |
| `SettingsSurface`（七子屏） | §2.48 | Widget 子屏 Apply 后桌面真出现 widget（Widget 系统能力 🔴） |
| `EditProfileSurface` | §2.49 | **换号格**（需双账号）、头像上传（相册无素材） |
| `CommentsSurface` | §2.50 | 发/删评论真实往返 |
| `NotificationSurface` | §2.51 | Engagement 跨栈出口实操（测试账号无互动通知） |
| `GemsSubscriptionSurface` / `UserCoinsSurface` | §2.52 | **真实购买/订阅**（模拟器无 Play Billing，真机冒烟置顶项，三渠道各验）、402 实弹 |
| `RoleCardSurface` | §2.53 | 换头像子流程（CreateStack 实操）、保存真实往返、超限 code=9 |

⚠️ 以上全部是**模拟器**证据（Pixel 10 / API 37 / directApk），按 §2.5 纪律
不作真机结论，真机格与语言切换 / OTA N/N-1 列继续累积。

> Screen 是纯原生页，不属于 §9.1 Surface 矩阵；其 P2 四项 NOT RUN 单独记在
> §2.42，不能用 Screen 的模拟器证据改变任何 Surface 行状态。

`DeleteAccountSurface` / `WidgetSurface` **不再是独立待验 Surface** —— 已并入
SettingsSurface 的 Delete/Widget 屏（§2.53 判定，注册仅为 OTA 偏斜保留），
其页面本体已随 §2.48 七子屏矩阵验过。`OnboardingSurface` 未启用（待评估，
§2.53：需「auth 完成回执 + 只执行一次」语义与新注册链路）。

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

阻塞原生社交登录收尾（Login 完整对等；W2 主体已落地，此组不再阻塞整个 W2）：

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
| Node 可执行文件解析（fnm/nvm 下 GUI 启动 sync 失败） | 方案 ADR-004 第 3 条（2026-08-21 已由绝对 Node + dependency patch + 无 PATH CI gate 取代 GUI PATH / 包装 App） |
| 三渠道 / config plugin / 桥模块等硬约束 | 方案 §2（**已在 pin `93d2c5551` 重新核实过源码**，不依赖旧报告） |
| CNG prebuild 审计报告（基线 `cbd521f02`） | 不再引用。其结论中可核实的部分已重新核实；**RN lint/test/doctor 的具体红项数量待 W0 实跑** |

**纪律**：本仓不再有"去某个分支恢复内容"的路径。方案与本文是唯一依据。

## 7. 状态更新纪律

1. 每个波次开始时把 `source_rn_sha` / `target_android_sha` 填成完整 40 位 SHA。
2. 波次结束跑 RN delta 审计，把变化映射到对等矩阵。
3. 发现文档与代码不一致时，**先修文档再继续实现**。
4. 不在其他文档里复制状态快照——重复的「当前进度」是 iOS 侧真实发生过的漂移源（同一文档记过不同的 submodule pin）。
5. 新工作包的逐刀记录追加到 [android-packet-log.md](android-packet-log.md)（编号继续 `§2.x` 单调递增、永不复用），并同步更新本文 §0/§1/§3/§4/§5 —— 日志记过程与证据，状态仍只写本文一处。
