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

### 跨端对齐与根因修复（强制）

遇到 bug、兼容性问题或架构问题时，按以下顺序决策，不能直接从现象出发加补偿逻辑：

1. 先沿实际调用链定位能力 owner 和出错边界（Native、bridge、共享 RN Surface、服务端），
   再查看 `Tipsy-iOS` 对应实现的代码、生命周期和文件归属；不能只对截图或只看同名文件。
2. Android 原生层默认对齐 iOS 的**职责边界、生命周期、bridge 契约、领域命名和目录组织**。
   新增文件应尽量能映射到 iOS 的对应领域；不要为掩盖问题新增 Android 独有的 `Manager`、
   `Helper` 或补偿状态层。对齐是语义对齐，不是机械翻译 Swift，也不能忽略 §2 的 Android
   平台硬约束和 RN 现网 Android 的产品行为。
3. RN Surface 是 iOS 与 Android 共用的同一套代码，且 iOS 已上线验证。若问题只在 Android
   出现，默认先修 Android Native host、bridge、生命周期、insets 或数据交接，不能先改 RN
   来适配壳。只有确认问题属于共享实现或共享契约本身缺失时才改 RN；改动必须最小、向后兼容，
   并同时回归 iOS 壳、Android 壳以及受影响的独立 RN App 路径。
4. 方案必须消除根因和错误状态来源。固定延时、进入后再纠正 offset/insets、重复导航、先闪回
   Native 再重开 Surface、复制状态掩盖 owner 冲突等都视为 workaround，不得作为最终修复。
   如果当前阶段确实只能临时绕过，必须先获得明确批准，并写清适用范围、风险和删除条件。
5. iOS 与 Android 确有平台差异时可以不一致，但必须给出代码或平台约束证据，说明为什么不能
   共用同一方案。只在本次范围内调整相关结构，不借修 bug 顺手做无关的大规模目录搬迁。

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
