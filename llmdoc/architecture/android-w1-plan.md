# W1 细化方案：契约层 + ChatDetailSurface gate

> 派生自 `android-native-migration-plan.md`（下称「方案」）。本文只做 W1 的**执行级**细化，
> 不重复方案的论证。方案是真值，本文与之冲突时以方案为准。
> 更新：2026-08-10 ｜ 状态：**待评审，未开工**

## 0. W1 的目标与不做什么

**一句话**：让壳成为 auth / 语言 / 网络 / 导航的 owner，并用 `ChatDetailSurface`
这个最重的真实业务页证明宿主可用。

**进入 W2 的判据**（方案 §8.5，三条全绿才走）：
1. auth 不掉登录 —— 含**三渠道真机覆盖升级**
2. `ChatDetailSurface` 过 §9.1 矩阵
3. root side-effect 表（§4.2）**零 `UNKNOWN`**

**W1 明确不做**（方案 §8.5 时间盒纪律，写下来防止范围膨胀）：
- ❌ 完整存储 registry（按页面波次增量填）
- ❌ 完整对等矩阵
- ❌ 五 Tab shell / Login / Home（那是 W2）
- ❌ DI 框架（ADR-005：W1/W2 手写 `AppContainer`。不要把「引入 DI」和「首次
  brownfield 集成」混在一起 —— 两个都失败时无法二分定位）
- ❌ OTA 发布（方案 §5.3：W1~W3 只用内嵌 bundle，发 OTA 需单独授权）
- ❌ 其余 12 个 Surface（W4）

**W1 停止条件**：覆盖升级 auth 失败或出现跨账号数据；`AuthBootstrapSurface`
无法可靠读出历史 token。触发即停下来复审,不要绕过。

---

## 1. 任务分解与依赖顺序

W1 有一条**硬依赖链**,不能并行:桥不通 → 所有 RN 侧适配都不激活 → 什么都验不了。

```
P0  tipsy-auth Android 桥骨架（isShellHost() 返回 true）
     └─ 这一步通了，RN 侧 55 个文件的壳适配「自动激活」（方案 §7.2）
        ↓
P1  auth 契约实现（13 个必须方法 + 双 generation + single-flight refresh）
        ↓
P2  token 迁移（MMKV 直读 → AuthBootstrapSurface 兜底）
        ↓
P3  三渠道真机覆盖升级验证  ←── W1 最大的不确定性，尽早做
        ↓
P4  Router + 返回栈接管（含 scheme 安全审计）
P5  i18n（Native 唯一 writer + Compose 本地化组件）     ← P4/P5/P6 可并行
P6  network 三鉴权模式 + 统一 envelope/容错反序列化
        ↓
P7  root side-effect 清单逐行填证据（含 Qt 冲突决策）
P8  Sentry 原生实例
        ↓
P9  ChatDetailSurface gate（SurfaceDependencyChecklist + §9.1 矩阵一行）
```

**排序理由**:P3 放在前四分之一,因为它是 W1 唯一可能**推翻整个迁移路径**的环节
(方案 §6.1:"不要把它留到最后 —— 它是 §2.4 迁移算法唯一的正确性证据")。
P9 放最后,因为它依赖 P0-P8 全部就位才有意义。

---

## 2. P0：tipsy-auth Android 桥骨架

### 2.1 要改 `tipsy-app`（本仓无权限,需单独 PR）

`modules/tipsy-auth/` 现状**只有 iOS**:

```
expo-module.config.json   → {"platforms":["apple"],"apple":{"modules":["TipsyAuthModule"]}}
index.ts / src/index.ts   → TS 契约（两侧共用）
ios/TipsyAuthModule.swift → 513 行，Android 侧的实现参照
```

W1 要加:
- `expo-module.config.json` 增 `android` 段与 `platforms` 增 `"android"`
- `modules/tipsy-auth/android/` Kotlin 模块 + provider 注册模式

⚠️ **对现网三个 RN 包有影响**(方案 §7.3):模块会被 autolink 进现网包。
**必须保证无 provider 时 `isShellHost()` 返回 false**,与今天(模块为 null)等价。
这是 W1 唯一会碰到现网 RN 包的改动,需要 RN 侧回归。

### 2.2 契约面（实测数字）

`modules/tipsy-auth/src/index.ts` 里:

| 类别 | 数量 | W1 处理 |
| --- | --- | --- |
| **必须实现** | **12** | 全部实现,少一个就有 Surface 跑不起来 |
| 可选（`?.()`） | **18** | 按 Surface 启用增量补;W1 只补 ChatDetail 需要的 |

必须实现的 12 个:`isShellHost`、`getCurrentLanguageCode`、`getValidToken`、
`requestLogin`、`logout`、`clearToken`、`notifyOnboardingCompleted`、`popSurface`、
`openUserProfile`、`notifyServerAuthRejected`、`notifyServerPaymentRequired`、
`openGemsPurchase`。

### 2.3 内部必须拆接口（方案 §2.1）

Expo 模块类**只做参数校验、线程切换、委派**,不放业务逻辑,否则长成不可测的巨类。
拆成:

```
SurfaceAuthContract       ── getValidToken / requestLogin / logout / clearToken
SurfaceNavigationContract ── popSurface / openUserProfile / openGemsPurchase / open*
SurfaceLifecycleContract  ── notifyOnboardingCompleted / onSurfaceReappeared 发射
SurfaceEnvContract        ── getCurrentLanguageCode / getAPIBaseURL / getBOELane
SurfaceErrorContract      ── notifyServerAuthRejected(ForToken) / PaymentRequired
```

### 2.4 桥方法演进纪律（**代永久生效**,方案 §5.3）

JS 调新桥方法**永远**写 `TipsyAuth.xxx?.()`。一个 runtime generation 跨多个 store 版本,
OTA 会把新 JS 推给该 generation 里**最老的 binary**(没有该方法)——
**这个状态不会随发版自愈**。模块级 null 检查不解决问题。要无条件调用只能升 generation。

---

## 3. P1：auth 契约

### 3.1 Native owner 模式

`isShellHost()` 为 true 后,**JS 不得读取/刷新/持久化 token**。
- `getValidToken()` 由 Native 做 **single-flight refresh**;临过期阈值以 RN 为准
  (`isJwtExpiringSoon`,审计值 **5 分钟**)
- token **绝不**写 log / Sentry breadcrumb / analytics,也**绝不**经 initial props 透传

### 3.2 `notifyServerAuthRejectedForToken(token)` 的坑

**只有被拒 token 仍是当前 token 才登出**。禁止回退到无参版本 ——
否则旧账号迟到的 401 会误登出新账号(TS 注释明确写了)。

### 3.3 双 generation（**两轨,互不替代**）

| 轨 | 何时自增 | 防什么 |
| --- | --- | --- |
| `authGeneration` | login / logout / 换号 | 在飞响应写错账号的 token/user/缓存/埋点 |
| `mutationGeneration` | 本地乐观变更（删除/置顶） | 在飞旧响应**复活已删行** |

Repository 发请求前捕获 generation,回写前校验匹配才允许写 token/user、写账号缓存、
更新 UI state、发用户归属埋点。

### 3.4 常驻页必须订阅登录态（iOS 踩过）

iOS 的 `MainTabBarController` 缓存 Tab VC、只在首次加载拉一次且永不销毁 ——
登录/登出只广播给 RN 桥,就出现「登录后无人重拉」「登出串上一账号数据」。
Android Fragment 同样常驻。**约定**:
- `didLogin` → 重拉身份相关数据
- `didLogout` → **只清账号私有数据、不发请求**(authorized 此刻必被前置拒绝)

W1 虽然还没有五 Tab,但**订阅机制要在 W1 建好**,W2 加 Tab 时直接用。

### 3.5 `logout()` 的完整语义

失效 auth generation → 取消/废弃在飞 refresh → 清 Native 与兼容共享态 →
**收敛返回栈** → 发**一次** `loggedOut`。

---

## 4. P2：token 迁移

### 4.1 读链与信封规则（方案 §2.4 实测）

MMKV 目录 `context.filesDir.absolutePath + "/mmkv"`,壳用**同版本 MMKV 直读**。

| key | 信封 | 注意 |
| --- | --- | --- |
| `token-storage` | **裸字符串** token | 但 `parseLegacyPersistedToken` 说明历史上可能是 `{state:{token}}` 或 `{token}` —— **读取必须兼容三种形态** |
| `user-storage` / `auth-storage` 等 | Zustand persist `{state, version}` | **原生写入必须 merge,不得整体覆盖破坏信封** |

### 4.2 迁移算法（五步,幂等）

1. 同 applicationId 覆盖升级设备读 MMKV `token-storage`(兼容三形态)
2. 能解析 → 验证 JWT → 写入 Native versioned token store,**保留兼容读**
3. MMKV 缺失 → 启动一次隐藏 `AuthBootstrapSurface`,用 RN 侧原有 SecureStore JS 逻辑
   读出 token 经**内存桥**交给 Native
4. Native 验证并持久化后写迁移标志;**重复启动幂等**
5. 迁移失败**不清空旧值**,回退未登录 UI + 上报**不含 token** 的 error code

### 4.3 绝对不要做的事

**不要在 Kotlin 里重新实现 SecureStore 的密文格式。** 实测它是
`SharedPreferences` + `AndroidKeyStore`,值是加密后的 JSON,带 keychainService、
`AESEncryptor`/`HybridAESEncryptor` 两套 encryptor、legacy key entry 兼容,
以及 keystore 与 prefs 失步时的清除逻辑。猜不出来,也不该猜 ——
这正是 `AuthBootstrapSurface` 存在的唯一理由。

> 与 iOS 的差异:iOS 四级读链的第四级能直读 legacy secure-store,因为 Keychain
> access group 语义简单。**Android 第四级读不了,所以必须多一个 Bootstrap Surface。
> 这是 Android 独有的一步。**

---

## 5. P3：三渠道真机覆盖升级（W1 最大风险）

### 5.1 为什么必须早做

方案 §6.1:「不要把它留到最后 —— 它是 §2.4 迁移算法唯一的正确性证据。」
iOS 至今未完成真机覆盖升级验证(只在模拟器验过),Android 有**三倍**验证面。

### 5.2 三渠道各跑一遍,结果不可外推

| flavor | applicationId | 签名 |
| --- | --- | --- |
| `googlePlay` | `com.tipsyturbo.app` | 需现网匹配签名 |
| `directApk` | `ai.lightspeed.tipsy` | 需现网匹配签名 |
| `ruStore` | `com.tipsytavern.app` | 需现网匹配签名 |

**⚠️ 三个 applicationId、三个签名、三条渠道 → 3×N 次,`com.tipsyturbo.app` 的结果
不能外推到 APK 与 RuStore。**

### 5.3 `adb install -r` 不构成证据

方案写明:**必须用真实匹配签名。debug 签名重装不构成覆盖升级证据**
(签名不同、数据目录不继承)。

W0 已实测印证:模拟器上装了 `com.tipsyturbo.app` 1.4.4,壳的 debug 签名与它不匹配,
直接覆盖装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。

**➜ 这是 W1 的第一个外部阻塞项:需要向发布 owner 取得三渠道的真实签名材料
(或由其代跑)。建议 W1 开工第一天就提出,不要等到 P3 才发现拿不到。**

### 5.4 ⚠️ 没有现成 fixture（P1 实测订正）

原以为 `Pixel_10` 上那个 `com.tipsyturbo.app` 1.4.4 可作 fixture。**实测不成立**：
它是 **Expo dev build**（`CN=Android Debug` 签名、无内嵌 bundle、dex 里有 DevLauncher、
无 launcher activity），且**数据目录根本不存在**（`run-as` 报 `couldn't stat`），
没有任何 MMKV 数据。详见进度文档 §2.5 的订正表。

**所以 P3 必须另行取得三渠道的真实 release 产物 + 匹配签名**，
且 **P2 的 MMKV 直读路径当前无真实数据可验** —— 需要一个真登录过的现网包，
或由发布 owner 提供脱敏 MMKV 样本。

这不改变方案结论（§6.1 早写明「debug 签名重装不构成证据」），
但**把外部阻塞项前置了**：没有真实产物，P3 一步也做不了。

### 5.5 覆盖升级矩阵（方案 §6.1,每个 distribution 各跑一遍）

| 旧包 | 新包 | 验什么 |
| --- | --- | --- |
| 现网 RN release,已登录 | Native | token / user / 语言 / 筛选 / 会话列表 / 钱包余额连续;深链可用 |
| 现网 RN release,游客 | Native | anonymous installation id / 推荐连续性 |
| 现网 RN release,历史多次升级过 | Native | SecureStore/MMKV fallback 与**迁移幂等** |
| Native last-known-good | 候选 Native | schema 前向兼容 |
| 候选 + embedded JS | 同 binary + OTA N/N-1 | W4 再做 |

---

## 6. P4：Router + 返回栈

### 6.1 单一入口

```
Intent / Push / Widget / Compose 点击 / RN 桥
        ↓  typed AppRoute parser
        ↓  auth gate + 去重 + source attribution
   Native destination | RN Surface
```

### 6.2 七条外部路径（实测 `src/App.tsx:445-465`）

`profile/daily-gem-entry`、`profile/user-balance`、`subscribe/page`、`chat/detail`、
`chat/mini-phone`、`chat/letter`、`create/profile-detail`

### 6.3 ⚠️ scheme 安全审计（W1 必做）

`app.config.js:147-171` 给 Android 注册了 **5 个通用外部 scheme** 的 `intentFilters`:
`fb` / `twitter` / `discord` / `instagram` / `tiktok`,带 VIEW + BROWSABLE。

原意是"跳出到社交 App",但**把它们注册成自己的 intent filter 等于声明本应用能打开这些
scheme → intent 劫持面**。**不要照搬**,逐个审计必要性。
(风险登记 severity 中。)

### 6.4 返回栈接管（改 W0 的占位实现）

`MainActivity.invokeDefaultOnBackPressed()` 现在是 `super.onBackPressed()` 占位。
W1 改成:**先给当前 RN 微栈,到栈底才 pop 原生**。

RN 侧已就绪:栈底 `popSurface` 兜底已内建在 `TipsyHeader`,自绘返回按钮的页面也已加分支。
`popSurface` 必须**幂等、每实例最多消费一次**(ADR-003)。

### 6.5 `.userProfile` 路由分流

self → Profile Tab;others → 原生他人主页。
**必须在 Router 集中判定**(iOS 实践,含 MMKV 冷启动兜底防止"关注自己"),
不要在每个调用点各判一次。

### 6.6 每条路由的测试矩阵

冷启/热启/后台、未登录排队、auth ready 后**恰好执行一次**、畸形输入、
同 Intent 去重、返回目标、source attribution。

---

## 7. P5：i18n

### 7.1 四套集合不能混（每项实测）

| 层 | 实测值 | 说明 |
| --- | --- | --- |
| 磁盘 locale JSON | **28** 个 | `src/i18n/locales/*.json` |
| `i18n-index.ts` 实际 import | **27** | `ar.json` **未** import |
| `SUPPORTED_LANGUAGES` 客户端码 | **26** | `zh` 有 import 但不在 supported 里 |
| 设置页可选列表 | 服务端 `/supported_languages` | **≠** 以上任何一个 |

### 7.2 `normalizeLanguageCode` 必须逐行对齐

规则(`i18n-index.ts:64-75`):精确匹配 → 主语言码匹配(`es-CR`→`es`)→
所有 `zh` 变体 → `zh-tw` → 兜底 `en`。

**包括「简体 `zh` 映射到 `zh-tw`」这个产品决策** —— 看着像 bug,是决策,照抄。

### 7.3 实现要求

- 生成脚本审计全部 28 个文件,但**不得**把休眠文件(`ar`)自动提升为产品可选语言
- 运行期可选集 = 服务端列表 ∩ 当前已批准的客户端 supported 码;未知/无资源的码安全兜底
- RN 的 key **含空格与符号**(key 就是英文原文)—— **不要硬映射成 Android 资源名**,
  Native 提供 key-based `L10n` + 可观察 locale 状态
- 兜底链:当前语言 → en → key
- **原生页文案必须组件化**:提供自订阅语言变化的 Compose 文本组件,而不是每个页面手挂
  listener。iOS 是后期才补 `LocalizedLabel`/`LocalizedButton` 的 —— **Android 第一天就做**

### 7.4 两个 iOS 教训

1. 新原生页文案**必须**加进术语白名单并重跑导出,否则非英文用户静默看到英文 ——
   **英文环境测试发现不了**,Search 页出过这个 bug
2. 术语量随波次增长,导出脚本必须支持增量重跑

### 7.5 语言是 Native 唯一 writer

RN 经 initial props + `onLanguageChanged` 同步。
**语言设置页刻意不迁移**(方案 §8.1,与 iOS 同边界)。

### 7.6 ⚠️ 反直觉:语言**不**作为缓存闸

方案 §4.6:两段式 i18n 初始化下,首屏读到的是**过渡语言**,拿它当缓存闸会永久拒绝缓存
(iOS 踩过"第二次启动永远没有种子")。真实语言变化经 `languageDidChange` 重取自愈。

---

## 8. P6：network 三鉴权模式

### 8.1 三模式（对应 RN 两个 axios 实例）

| 模式 | 语义 | RN 对应 |
| --- | --- | --- |
| `REQUIRED` | 必带 token;缺失立即 auth error;401 → 一次 single-flight refresh + 重试 | `axiosAuth` |
| `OPPORTUNISTIC` | **有 token 就带,没有也照发** | `axiosPublic` |
| `NONE` | 永不带用户 token | 无 RN 对应,仅给明确禁止携带身份的端点 |

### 8.2 ⚠️ iOS 踩过的坑：别把 `axiosPublic` 实现成"永不带 token"

很多"公开"端点**带 token 行为不同** —— `/search/character_search` 只在有 token 时
才记录最近搜索。iOS 实现成 `authorized:false`,导致**搜索历史永久为空**。

**➜ 每个用 `axiosPublic` 的端点都必须是 `OPPORTUNISTIC`,并逐个核对 RN 侧用的哪个实例。**

### 8.3 统一 envelope 与业务码

`{ code, msg, data? }`,`code != 0` 为业务错误。**已知业务码不得压平成 IOException**:
`0` 成功、`6` gems 不足、`9` 角色卡上限、`16` clover 分支。

HTTP `401` → token-aware auth reject;HTTP `402` → paywall 路由带防抖。
**两个入口(原生页 + Surface 经桥)必须汇聚到同一个 handler**:
401 → 登出 + 显示登录(防自触发环),402 → gems 页 + 防抖。

### 8.4 公共 header（实测 `axios.ts:116-118`）

`Platform`、`X-App-Version`、`X-Download-Channel`(按 flavor 取
`GooglePlay`/`RuStore`/`APK`,实测 `src/constants/common.ts:28-33`),
以及端点要求的反作弊标识如 `X-Client-ID`。

### 8.5 标量漂移容错（iOS 静默失败点）

dev/prod 会把 TS 里声明为 `string` 的字段返成 JSON number。iOS 上**一个字段不匹配就
整个响应解码失败**,而列表加载路径**静默吞掉了错误**(空列表,伪装成"没有结果")。

Android 要求:**在统一序列化层做容错**,不在业务模型里散落 `Any`/try-catch;
每个观察到的变体都留一份脱敏 fixture。

---

## 9. P7：root side-effect 清单（W1 交付物：零 UNKNOWN）

方案 §4.2 的表逐行填「已验证」证据。W1 归属的行:

| 能力 | owner | W1 要做的 |
| --- | --- | --- |
| **Qt / QuickTracking** | Native | ⚠️ 见下方冲突决策 |
| AB Test 初始化 | Native | `initABTest(url, 10min)`,appKey 按平台取,壳自己拉 |
| auth restore | Native | 见 §3 |
| Sentry | Native + RN runtime | 见 §10 |
| deep link | Native Router | 见 §6 |
| remote config / tags / badges / avatar decorations | **RN 入口已镜像** | 复用 `index.surfaces.js` 即自动获得,**不用做** |
| **splash 隐藏** | Native | ⚠️ Android 12~13 后台隐藏 splash 触发 `SurfaceControl.checkNotReleased` NPE(Play Console 崩溃榜)。**必须保留「仅前台时隐藏」语义** |
| 字体 / asset 预载 | 分侧 | 原生页用原生字体;Surface 侧由入口保证 |

### 9.1 ⚠️ Qt 冲突（开放问题 #1,W1 必须决策）

`modules/qt` 的 `QtPackage.createReactActivityLifecycleListeners()` 会在
**Activity onCreate 就 `QtConfigure.preInit`**(已核实 `QtReactActivityLifecycleListener.kt`)。

这与「壳是 analytics 单一 owner」**冲突**。壳必须**二选一并写下来**:
- (a) 保留该 listener,承认 Qt 初始化不由壳控制
- (b) 把模块从 autolinking 排除、壳自管

### 9.2 判据（iOS 总结,直接采用）

**命令式 store populate → 必须镜像进 surfaces 入口;
SWR mutate 且消费面已原生化 → 可跳过。**

### 9.3 已决策不做的（**别"顺手修复"**）

视频播放器池:`ChatDetailSurface` 挂了 `GreetingVideoPortal` 却**刻意不挂**
`VideoPlayerPoolInitializer`,因 `GreetingVideoPlayer` 对 `preloadedPlayer` 空值有
`fallbackPlayer` 兜底、池仅为预加载优化(`ChatDetailSurface.tsx:609-614` 注释)。
Android 复用同一入口即继承该决策。

---

## 10. P8：Sentry

- **双 Runtime**:同 release / env / user;**各自上传自己的 mapping / source map**
- RN 侧**挂靠原生实例,不重复 init**。JS 侧已就绪:`index.surfaces.js:17` 首先
  import `./src/surfaces/sentry`(全局 error hook 必须在所有模块之前安装)
- **token 绝不写 Sentry breadcrumb**
- W1 的活:建原生实例 + 保证 release/env/user 一致

---

## 11. P9：ChatDetailSurface gate（W1 的业务验收）

### 11.1 为什么选它

方案 §8.3:「**第一个真实业务 Surface,也是最重的一个**(SSE、WebView DOM、媒体、
深微栈)。它过了,宿主就被证明可用。」

### 11.2 微根清单（实测,W1 要建 `SurfaceDependencyChecklist` 逐行核对）

`ChatDetailSurface.tsx` 的实际挂载顺序:

```
SafeAreaProvider (546) → KeyboardProvider (547) → SWRConfig (548)
  → GestureHandlerRootView (549) → PortalProvider (550)
      → NavigationContainer (560) → Stack.Navigator (561)
            ChatDetail / ProfileStack / CreateStack /
            SimulatorGameDetail / SimulatorGameProfile      ← 5 个微栈目标
          → RoleCardLimit (606)
      → GreetingVideoPortal (615)
      → SurfaceToastHost (617)        ← 必须在具名 PortalHost 组之前
      → 8 个具名 PortalHost (623-630)
```

### 11.3 ⚠️ 微根缺项的共同症状：「点了没反应」

方案 §4.3:`src/App.tsx` 在 Surface 模式不挂载,每个 Surface 根必须自备全局件。
**缺项的共同症状是"点击无反应"** —— 事件进了 store 但没人渲染,
**不报错、不崩溃,只能靠用户反馈发现**。逐项后果:

| 缺什么 | 后果 |
| --- | --- |
| `GestureHandlerRootView` / `SafeAreaProvider` / `KeyboardProvider` | 手势 / 安全区 / 键盘避让失效 |
| `NavigationContainer` + 微栈 | 页内导航不可用 |
| `SWRConfig` | 缓存/revalidate 与现网不一致 |
| `PortalProvider` + 8 个 host | portal 投递的弹窗/抽屉存进 state 无宿主 → **点了没反应** |
| `SurfaceToastHost` | **所有 toast 丢失**(iOS 在 ChatDetail 与 Comments 真实发生过) |
| `RoleCardLimit` | 超限弹窗只写 session store |
| `GreetingVideoPortal` | 招呼视频卡只写 store |

**未过清单的 Surface 不得注册。**

### 11.4 §9.1 矩阵一行（10 项验收）

| 项 | W1 目标 |
| --- | --- |
| 初始路由 fixture | ✎ |
| 未登录 | ✎ |
| 登录切换 | ✎ |
| 语言切换 | ✎ |
| Back / 栈底 | ✎ |
| 旋转 / 进程恢复 | ✎ |
| 首帧 | ✎ |
| 50 次泄漏 | ✎ |
| Embedded | ✎ |
| OTA N/N-1 | W4（不在 W1） |

> DebugSurface 那行 W0 已实测填完(进度文档 §2.4),可作为方法参照。

### 11.5 内部 navigate 目标要枚举

方案 §8.3:启用每个 Surface 时枚举其内部所有 `navigate` 目标,确认**要么在微栈里、
要么有桥出口**。ChatDetail 注册了 5 个目标,不存在 `RoleCardSurface` 那种死链
(缺 `CreateStack`),但仍需逐个确认。

---

## 12. RNSurfaceFragment 要补的四件事

W0 的 `RNSurfaceFragment.kt` 是 36 行 stub,KDoc 已预先标注 W1 缺口:

### 12.1 `surfaceInstanceId`（ADR-003）

每次打开生成唯一 id,ready/close/reappear 事件都带上。
**迟到的旧实例事件不得关掉新实例。** iOS 的 `popSurface` 闸是**类型判定**,
迟到事件弹错了同类型页(后来用 `closingRef` 补的)。**Android 从第一天就按实例判定。**

### 12.2 首帧协议

ready 前显示原生占位,ready 后**单次淡出**。**不用固定延时猜**(iOS `b2773e1` 处理过同一问题)。
G3 Macrobenchmark 会测,§9.1 第 7 列也要它。

### 12.3 `onSurfaceReappeared`

**问题**:壳内经桥跳出(在当前 Surface 上盖新容器)再返回,**不产生 RN 的 blur/focus** ——
`useFocusEffect` 与 SWR `revalidateOnFocus` 都不触发,于是"去做任务、回来领取"类页面不刷新
(写完评论后按钮仍显示 Comment 而不是 Claim)。

**Android 只需在容器非首次 `onResume` 时发射**,payload `{ surface: string }` ——
⚠️ **是组件名,不是 instanceId**;该事件的去重粒度是 Surface **类型**。
**不要用旧壳那种壳侧 flag 方案。**

RN 侧已就绪:`src/hooks/useShellSurfaceRefocus.ts`(45 行)把 `useFocusEffect` 与
`onSurfaceReappeared` 收敛成一个回调,老壳没有该事件时订阅空转、降级为仅 `useFocusEffect`。

### 12.4 capability handshake / initial props

```json
{
  "surfaceContractVersion": 1,
  "surfaceInstanceId": "uuid",
  "componentName": "ChatDetailSurface",
  "capabilities": ["auth.valid-token.v1", "navigation.open-chat.v1", "lifecycle.reappeared.v1"],
  "route": {},
  "context": { "languageCode": "en", "environment": "production", "distribution": "googlePlay" }
}
```

规则:老 bundle 必须忽略新字段;新 bundle 调新原生方法前查 capability 或用 `?.()`。
字段/方法**只增不改**——改语义、变必填、删字段都要升 `surfaceContractVersion`
**并**升 OTA runtime generation。Native 收到未知 route/字段要**可诊断地拒绝或忽略,绝不崩**。
**token 绝不经 initial props / log / analytics** —— JS 按需调 `getValidToken()`。

---

## 13. 外部阻塞项（**建议 W1 第一天就提出**）

| # | 阻塞项 | 影响 | 需要谁 |
| --- | --- | --- | --- |
| 1 | **三渠道真实 release 产物 + 匹配签名** ⬆️ | P3 覆盖升级验证**一步也做不了**。原以为模拟器上有现成 fixture，实测那是 dev build 且无数据（§5.4），所以本项从「需要签名」升级为「需要完整产物」 | 发布 owner |
| 1b | **一份真登录过的现网包数据（或脱敏 MMKV 样本）** | P2 的 MMKV 直读路径无真实数据可验，只能靠构造样本自测 | 发布 owner / QA |
| 2 | `tipsy-app` 的 PR 权限（加 `tipsy-auth` android 段） | P0 做不了,整个 W1 卡住 | RN 仓 owner |
| 3 | **Qt 冲突决策**（§9.1 二选一） | root side-effect 表无法零 UNKNOWN | 产品 / analytics owner |
| 4 | AB Test 的 Android appKey | AB 初始化做不了 | 后端 / 产品 |
| 5 | `PAT_TOKEN`（遗留自 W0） | G1 CI 仍未激活,W1 期间只能人工跑门禁 | org 内 PAT 持有者 |

## 14. 与 W0 的衔接：现在就成立的前提

W0 已验证、W1 可直接依赖:
- ✅ 单 ReactHost + Surface 可挂载/卸载(两种 bundle 来源)
- ✅ **单 Runtime 不变量**:50 次开关后 GC,`Activities=1`/`ViewRootImpl=1`,无滞留
- ✅ API 24 与 API 37 双端可跑
- ✅ merged manifest 断言(5 条)—— **W1 起每次新增依赖都看一眼它的 diff**
- ✅ lint 硬门
- ⚠️ **G1 CI 已写但未激活**(缺 `PAT_TOKEN`)—— W1 期间合并前需**人工**跑:
  ```
  ./gradlew :app:lintDirectApkDebug :app:assembleGooglePlayDebug \
    :app:processGooglePlayReleaseMainManifest :app:testGooglePlayDebugUnitTest
  ```
  按方案 §5.4 纪律,这属 `NOT RUN`,**不等于通过**

## 15. W1 期间要守的既有纪律

- **`NOT RUN` 不等于通过**（§5.4）
- **命令名不靠猜** —— Gradle task 名按 `./gradlew :app:tasks` 实际结果写
- **lint 不得继承 RN 侧的弱化配置** —— `modules/qt`、`modules/widget`、
  `modules/voice-call-system-session` 都有 `lintOptions.abortOnError false`,
  RN 侧 `vitest.config.ts` 有 `passWithNoTests: true`。**不复制这种假绿色**,
  也**不在 Android packet 里顺手修 RN 的既存红项或加 ignore**
- **`index.surfaces.js` 现在是双壳共用** —— 改它需要 **iOS 壳回归**,
  PR 模板要带跨侧关联(风险登记 中高)
- **无障碍与 testTag 从第一个组件就做,不后补** —— iOS 后期批量补了约 295 个
  accessibility ID(`51eba61`/`a0a1502`)。动态 ID 要脱敏且稳定,
  **绝不把用户文本拼进 tag**
