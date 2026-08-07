# RN 功能对等与归属矩阵

基线：`tipsy-app@cbd521f02972933c21f90c01787ea5c11200875e`。

状态含义：`AUDIT_REQUIRED`、`NATIVE_PLANNED`、`RN_SURFACE_PLANNED`、`IN_PROGRESS`、`PARITY_VERIFIED`、`OBSOLETE`。当前均未实现；“planned”只表示边界决策。

## 1. Root 与横向能力

| ID | 领域 | RN 真值路径/符号 | Android 归属 | 波次 | 不可漏行为 | 状态 |
|---|---|---|---|---|---|---|
| X01 | App bootstrap | `src/App.tsx` | Native owner + Surface wrapper | P00/P01 | fonts/assets、providers、root effects inventory | AUDIT_REQUIRED |
| X02 | 五 Tab | `src/navigation/TabNavigator.tsx` | Native | P02 | Screen/Home/Create/ChatList/Profile；Create 是伪 tab；tab state/back | NATIVE_PLANNED |
| X03 | Root routes | `RootNavigator.tsx`、实际 `<Stack.Screen>` | Native Router + RN targets | P01 | type 文件含陈旧 route，必须以注册项为真 | AUDIT_REQUIRED |
| X04 | Auth/login | `src/login`、`src/lib/auth`、`src/store/auth.ts`、`src/apis/auth.ts` | Native | P01/P02 | Firebase、Google、Apple Web/email、refresh 5min、logout | NATIVE_PLANNED |
| X05 | Network | `src/utils/axios.ts`、`src/types/api.ts` | Native 页面 Native client；Surface Axios | P01 | required/opportunistic/none、401/402、headers、SSE | NATIVE_PLANNED |
| X06 | Persistence | `src/store/mmkv.ts`、Zustand stores | 分 owner | P01 | raw token 与 `{state,version}` 信封、account/env scope | AUDIT_REQUIRED |
| X07 | i18n | `src/i18n`：28 JSON / 27 import / 26 supported codes；服务端可选列表 | Native locale owner + 生成 catalog | P01 | 三层集合不混写、normalize、fallback、plural/interpolation、实时同步 | NATIVE_PLANNED |
| X08 | Deep link | navigation linking config、root hook | Native Router | P01/P04 | cold/warm/background、auth queue、dedupe、scheme audit | NATIVE_PLANNED |
| X09 | Push | `src/hooks/root/useNotifications.ts` | Native | P04 | permission、token rotate、backend register、viewed、routes | NATIVE_PLANNED |
| X10 | Analytics/AB | root hooks、`modules/qt` | Native session owner + Surface events | P01/P04 | QT、AB、PostHog、推荐 outbox、无双埋点 | AUDIT_REQUIRED |
| X11 | Attribution | AppsFlyer/Meta/TikTok root effects | Native | P04 | 按 flavor 启用、install/session/deep-link context | AUDIT_REQUIRED |
| X12 | Crash/ANR | Sentry init | Native + RN runtime | P01/P04 | 同 build ID/user/env、mapping/symbol/source map | NATIVE_PLANNED |
| X13 | Remote config | App root/config store | Native owner 或明确 transitional host | P01/P04 | App.tsx 不挂载仍初始化 | AUDIT_REQUIRED |
| X14 | Permissions | `app.config.js`、plugins、业务调用 | Native | P00/P04 | 最终 merged manifest 为真；Photo Picker/SAF/scoped storage | AUDIT_REQUIRED |

## 2. 页面与业务域

| ID | 页面/域 | RN 真值路径 | 首轮归属 | 波次 | 关键对等/Android 差异 | 状态 |
|---|---|---|---|---|---|---|
| F01 | Screen feed | `src/app/screen`、`src/apis/screen.ts` | Native | P04 | 视频三形态、预加载、曝光/推荐 outbox、前后台、OOM/ANR | NATIVE_PLANNED |
| F02 | Home | `src/app/home`、`src/hooks/home`、character APIs | Native | P02/P03 | tags/filter/paging/cache/banner/activity；Android World 系列 | NATIVE_PLANNED |
| F03 | Create | `src/app/create`、Create navigator/store | RN `CreateSurface` | P04 | 10 页、上传/裁剪/AI image/voice/story/worldbook；字段不丢失 | RN_SURFACE_PLANNED |
| F04 | ChatList | `src/app/chatList`、`src/hooks/chatList` | Native | P03 | Grid/Map、unread、pin/delete/draft/cache、通知入口 | NATIVE_PLANNED |
| F05 | Profile self | `src/app/profile`、profile APIs | Native | P03 | counts、created/favorite、daily tasks、settings routes | NATIVE_PLANNED |
| F06 | Public profile | profile/social hooks | Native | P03 | follow/favorite、同用户重入、推荐上下文 | NATIVE_PLANNED |
| F07 | Search | `src/app/search`、search hooks | Native | P03 | character/creator filter；Content Rating 只对批准的 side-load 渠道 | NATIVE_PLANNED |
| F08 | Settings list/language | `src/app/settings`、Setting navigator、`/supported_languages` | Native | P03 | 入口完整；服务端列表与 26 客户端支持码/资源取交集；动态切换 | NATIVE_PLANNED |
| F09 | Settings children | Setting navigator 注册子页 | RN `SettingsSurface` | P04 | security/blacklist/feedback/about/delete 等微栈路由 | RN_SURFACE_PLANNED |
| F10 | ChatDetail + 37 routes | `src/app/chat`、`src/components/chat`、ChatDetail navigator | RN `ChatDetailSurface` | P01/P04 | SSE/WebView DOM/media/memory/mini-phone；Native 返回栈 | RN_SURFACE_PLANNED |
| F11 | Comments | comments UI/APIs | RN `CommentsSurface` | P04 | pagination/report/profile/target/root 定位 | RN_SURFACE_PLANNED |
| F12 | EditProfile | profile edit flow | RN `EditProfileSurface` | P04 | 表单字段持续变化；不做 Native 双实现 | RN_SURFACE_PLANNED |
| F13 | Onboarding | onboarding flow | RN `OnboardingSurface` | P04 | auth completion 回执、只执行一次 | RN_SURFACE_PLANNED |
| F14 | Notifications | Notification navigator/letter APIs | RN 首轮 | P04 | push cold/warm routes、read state、comments/chat routes | RN_SURFACE_PLANNED |
| F15 | Gems/subscription | subscribe/balance APIs/pages | RN Surfaces | P04/P06 | Play/RuStore/Direct 三支付、server verify、402 | RN_SURFACE_PLANNED |
| F16 | UserCoins | profile coins flow | RN `UserCoinsSurface` | P04 | 钱包真值来自服务端；覆盖升级状态 | RN_SURFACE_PLANNED |
| F17 | RoleCard | role card flow | RN `RoleCardSurface` | P04 | create/edit route、业务 code=9 | RN_SURFACE_PLANNED |
| F18 | SimulatorGame/WebView | simulator pages | RN via ChatDetail Surface | P04 | WebView bridge、process/back、外部链接安全 | RN_SURFACE_PLANNED |
| F19 | Voice call | voice pages/hooks、Agora | RN UI + Native service | P04 | audio focus、FGS、permission、hangup/mute、process death | RN_SURFACE_PLANNED |
| F20 | Widget | `modules/widget/android`、`useWidget.tsx` | Native system + transitional RN config | P04 | 2x2/4x4、Glance、midnight refresh、old keys/deep link | NATIVE_PLANNED |
| F21 | Share/download/upload | media utils/components | 分页面迁移 | P03/P04 | Photo Picker、SAF、MediaStore、presign PUT、share sheet | AUDIT_REQUIRED |
| F22 | 活动类功能 | tarot/polaroid/cinema/multi-cinema | RN | P04 | SSE、media、运营快速变化 | RN_SURFACE_PLANNED |

## 3. Surface 注册表

| Component | Native route/入口 | Initial props schema | Owner | 启用阶段 | 状态 |
|---|---|---|---|---|---|
| DebugSurface | debug-only | P00 固化 | rn-host | P00 | AUDIT_REQUIRED |
| ChatDetailSurface | chat detail/simulator | P01 固化 | chat | P01/P04 | AUDIT_REQUIRED |
| CommentsSurface | comments | P04 固化 | chat | P04 | AUDIT_REQUIRED |
| OnboardingSurface | auth onboarding | P04 固化 | auth | P04 | AUDIT_REQUIRED |
| CreateSurface | Create fake tab/routes | P04 固化 | create | P04 | AUDIT_REQUIRED |
| DeleteAccountSurface | settings delete | P04 固化 | settings | P04 | AUDIT_REQUIRED |
| EditProfileSurface | profile edit | P04 固化 | profile | P04 | AUDIT_REQUIRED |
| GemsSubscriptionSurface | 402/subscribe | P04 固化 | payment | P04 | AUDIT_REQUIRED |
| NotificationSurface | push/inbox | P04 固化 | notification | P04 | AUDIT_REQUIRED |
| RoleCardSurface | role card | P04 固化 | profile/chat | P04 | AUDIT_REQUIRED |
| SettingsSurface | settings child | P04 固化 | settings | P04 | AUDIT_REQUIRED |
| UserCoinsSurface | wallet/coins | P04 固化 | payment | P04 | AUDIT_REQUIRED |
| WidgetSurface | widget settings | P04 固化 | widget | P04 | AUDIT_REQUIRED |

## 4. 每行完成所需证据

页面/横向能力只有同时满足以下项才可改成 `PARITY_VERIFIED`：

1. 固定 RN SHA 的源文件、实际 route registration、API 和隐藏 side effect 审计。
2. Native/Surface 归属与明确非目标。
3. 脱敏 API fixtures、decoder/error policy tests。
4. loading/empty/error/offline/pagination/refresh/auth-switch/process lifecycle。
5. route/deep link/push/back 的 Given/When/Then。
6. analytics event 名、触发次数、payload 与 source/session context 对拍。
7. stable testTag/contentDescription 与自动化映射。
8. screenshot/视觉验收和 TalkBack/字体缩放/触控区域。
9. 性能、内存、crash/ANR 结果与旧 RN baseline。
10. rollback/kill switch 或保留 RN route。

## 5. RN delta audit

每个波次结束执行：

```bash
git -C tipsy-app log --oneline <wave-source-sha>..<candidate-sha>
git -C tipsy-app diff --name-status <wave-source-sha>..<candidate-sha> -- \
  src/App.tsx src/navigation src/app src/apis src/hooks src/store src/i18n \
  index.surfaces.js modules plugins app.config.js eas.json
```

将变化映射到本矩阵 ID；不相关变化进入下一 pin，影响当前契约的变化先更新任务和测试再 bump。
