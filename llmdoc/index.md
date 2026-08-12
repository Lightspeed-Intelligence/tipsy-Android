# Tipsy Android 壳工程 - LLM 文档索引

> Kotlin/Compose 原生壳，以 integrated brownfield 方式托管 `tipsy-app`（Expo React Native，
> git submodule）。核心页面逐步原生化，其余以 RN Surface 运行。
>
> **当前状态只看** [android-native-progress.md](reference/android-native-progress.md)。
> 本索引不复制状态快照，避免与唯一进度真值漂移。

**目标技术栈：** Kotlin, Compose Material 3, OkHttp + 显式 envelope/标量容错层
（**已决定不引 Retrofit**，见进度文档 §2.14），
MMKV, Coroutines/StateFlow, Media3 ExoPlayer, Coil, Expo `ReactHost`（RN 宿主）。

**架构：** Fragment 宿主 + ComposeView（原生页）/ ReactFragment（RN Surface）；单 React
Runtime 多 Surface；显式 AppContainer（首轮不引 DI 框架）。

RN 侧文档见 `tipsy-app/llmdoc/`；iOS 壳的同构实践见 `../Tipsy-iOS/llmdoc/`。

---

## 架构

| 文档 | 描述 |
| --- | --- |
| [android-native-migration-plan.md](architecture/android-native-migration-plan.md) | **迁移技术方案**：架构决策（5 个 ADR）、Android 四条硬约束、跨界契约、构建/渠道/OTA、波次计划、风险登记 |
| [android-w1-closeout-ready.md](architecture/android-w1-closeout-ready.md) | W1 auth/network correctness 工作包的边界、验收与停止条件（**已完成并经 CI 验证**，见进度文档 §2.22） |

## 参考

| 文档 | 描述 |
| --- | --- |
| [android-native-progress.md](reference/android-native-progress.md) | **状态权威**：波次进度、工程实况、与目标基线的偏差、未决问题 |
| [android-bitmap-assets.md](reference/android-bitmap-assets.md) | 从 RN 移植的位图为何放 `drawable-nodpi`，以及 `IconMissingDensityFolder` 为何显式 disable |

---

## 读文档的顺序

方案的 **§8 是主体**（业务迁移范围：29k 行 RN 要重写，逐页规格 + 现成 fixture 来源），
§2-§7 是让 §8 能安全执行的前置约束。按你要做的事挑着读：

| 你要做的事 | 读哪里 |
| --- | --- |
| 写业务页面（**最常见**） | **§8.1 对应页面的规格表** + §8.2 fixture + §8.3 Surface 顺序 + §8.4 列表纪律 + §4.5 网络契约 |
| 搭工程 / 改 Gradle | §3.3 + ADR-004 |
| 动 auth / 存储 | §2.1 + §2.4 + §4.4 + §4.6 |
| 打包 / 发渠道 / 发 OTA | §2.2 + §2.3 + §5 + §6 |
| 判断某功能迁不迁 | §1.3 归属表 |
| 现在做到哪了 | 进度文档（不在方案里） |

无论做什么，先扫一遍方案 **§2（Android 四条硬约束）**——那是照抄 iOS 会静默出错的四处。

## 硬性纪律

- **RN 侧改动提交到 `tipsy-app` 的 `feat/android-native` 分支**（2026-08-11 owner 决定：
  Android 迁移相关的 RN 改动**不走 PR**，直接提交该分支）。本仓 PR 中的 submodule
  变更仍**只允许是指针 bump** —— 改动本身要在 `tipsy-app` 的历史里可追溯，
  不能以「本仓顺手改了 submodule 工作树」的形式存在。
  ⚠️ 该分支**未合进 `main`/`release`**，靠子模块指针引用；bump pin 前确认目标 commit
  **已推到远端**，否则 CI 拉不到（`--depth 1` 也拉不到，见进度文档 §2.10）。
- **`index.surfaces.js` 是 iOS 壳与 Android 壳共用入口**，改动需双壳回归。
- 状态只写在进度文档一处，不在别处复制快照。
- 不继承 RN 侧的弱化质量配置（`lintOptions.abortOnError false`、`passWithNoTests`）。
- OTA 发布、签名、真实版本号递增都需要独立明确授权。
- **UI 照 RN 的对应平台分支实现，不照另一端**。`TabNavigator.tsx` 这类文件里
  iOS 与 Android 是两套不同实现（tabbar：悬浮胶囊 vs 通栏实心；下拉刷新：自绘
  动画 vs 系统控件）。照 iOS 壳的同名组件做会与现网 Android 用户看到的界面不同，
  而这类偏差没人会报（用户不会同时装两个版本）。
