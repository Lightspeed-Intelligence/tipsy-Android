# Tipsy Android 壳工程 - LLM 文档索引

> Kotlin/Compose 原生壳，以 integrated brownfield 方式托管 `tipsy-app`（Expo React Native，
> git submodule）。核心页面逐步原生化，其余以 RN Surface 运行。
>
> **当前状态只看** [android-native-progress.md](reference/android-native-progress.md)。
> 本索引不复制状态快照，避免与唯一进度真值漂移。

**目标技术栈：** Kotlin, Compose Material 3, OkHttp + 显式 envelope/标量容错层，
MMKV, Coroutines/StateFlow, Media3 ExoPlayer, Coil, Expo `ReactHost`（RN 宿主）。

**架构：** Fragment 宿主 + ComposeView（原生页）/ ReactFragment（RN Surface）；单 React
Runtime 多 Surface；显式 AppContainer（首轮不引 DI 框架）。

RN 侧文档见 `tipsy-app/llmdoc/`；iOS 壳的同构实践见 `../Tipsy-iOS/llmdoc/`。

---

## 架构

| 文档 | 描述 |
| --- | --- |
| [android-native-migration-plan.md](architecture/android-native-migration-plan.md) | **迁移技术方案**：架构决策（5 个 ADR）、Android 四条硬约束、跨界契约、构建/渠道/OTA、波次计划、风险登记 |
| [android-w1-closeout-ready.md](architecture/android-w1-closeout-ready.md) | **当前 closeout 工作包**：W1 auth/network correctness 的边界、验收、验证与停止条件 |

## 参考

| 文档 | 描述 |
| --- | --- |
| [android-native-progress.md](reference/android-native-progress.md) | **状态权威**：波次进度、工程实况、与目标基线的偏差、未决问题 |

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

- **禁止在本仓直接改 `tipsy-app/` 内部文件**。RN 侧改动走 tipsy-app 自己的 PR 流程，
  合入后回本仓 bump submodule pin。本仓 PR 中的 submodule 变更只允许是指针 bump。
- **`index.surfaces.js` 是 iOS 壳与 Android 壳共用入口**，改动需双壳回归。
- 状态只写在进度文档一处，不在别处复制快照。
- 不继承 RN 侧的弱化质量配置（`lintOptions.abortOnError false`、`passWithNoTests`）。
- OTA 发布、签名、真实版本号递增都需要独立明确授权。
