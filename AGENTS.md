# Codex 工作规则

本文件适用于整个 `tipsy-Android` 仓库。更深目录若有新的 `AGENTS.md`，只可增加局部约束，不得放宽这里的安全与发布规则。

## 1. 语言与事实来源

- 文档、计划、提交说明和完成回报使用中文；代码标识符使用英文。
- RN 产品行为的第一真值是本波次固定 SHA 下的 `tipsy-app` 源码、真实接口 fixture 和线上旧版本，不是 iOS 实现，也不是 TypeScript 类型声明。
- iOS 仓只提供已验证的架构经验、失败模式与交互参考；Android 必须补齐平台差异。
- 架构文档和 execution packet 描述“应然、依赖与验收”；`llmdoc/reference/android-native-progress.md` 是阶段、work unit 与发布状态的唯一事实源。packet 不保存 READY/BLOCKED 快照。
- 源码引用写“路径 + 符号名”；行号只能辅助，不能作为唯一定位。

## 2. 每次任务的强制阅读顺序

1. 本文件。
2. `llmdoc/index.md`。
3. `llmdoc/overview/project-overview.md`。
4. `llmdoc/reference/android-native-progress.md`。
5. 当前 execution packet 及其明确列出的 architecture/reference 文档。
6. packet 列出的 RN 源文件与测试。

只执行 progress 中唯一 `READY` work unit 所属的 packet。若 progress 有多个未明确允许并行的 READY，先报告冲突，由 orchestrator 指定；不得自行并行修改共享热点。

## 3. 开工前检查

开始任何写操作前必须记录：

```bash
git status --short --branch
git rev-parse HEAD
git submodule status --recursive
git -C tipsy-app rev-parse HEAD
```

- 若仓库还没有 HEAD，记录为 `UNBORN`。
- 在初始 unborn 交接中，根 `README.md`、`AGENTS.md`、`llmdoc/**` 是本次授权生成的 P00 文档基线，即使未跟踪也允许 P00 按任务更新；`.idea/` 和其他未跟踪路径仍视为用户内容。建议写代码前由用户先提交 docs，但 Codex 不自行 commit。
- 若 `tipsy-app` 不存在，只允许在 packet 00 中按固定 commit 添加 submodule。
- 若 RN SHA 与 progress/packet 不一致，停止功能实现：先生成 parity delta，更新 packet 的基线后再继续。
- 工作树中已有的改动属于用户。不要覆盖、重置、清理或混入本任务。

## 4. 仓库边界

- `tipsy-app` 是独立 Git 仓库/子模块。禁止在 Android superproject 的提交中夹带其未提交修改。
- 需要 RN 改动时：在 `tipsy-app` 独立分支完成向后兼容 PR并先合入，再由 Android 仓单独 bump submodule pin。
- 禁止修改 `tipsy-app/android/` 生成目录来实现 Native App；Android Native 代码只存在本仓。
- 同一迁移波次固定 RN SHA。产品新增进入 delta queue，波次结束后集中对账。

## 5. 永久禁止的隐式操作

没有用户在当前任务中的明确授权，不得：

- 发布、上传商店、提交审核或发送生产 OTA。
- 修改三个生产 application id、签名证书/keystore、生产 versionCode/versionName、EAS project、OTA channel/runtimeVersion。唯一例外是 P00 可初始化明确标记 `NOT_FOR_STORE` 的 `versionCode=1`、`versionName=0.0.0-dev`；这不授权任何生产版本操作。
- 读取、打印、提交或复制 keystore、密码、服务账号、API token 等凭据。
- 升级 Expo、React Native、AGP、Kotlin、Gradle、NDK 或关键 SDK；依赖升级必须是独立 packet。
- 删除旧存储字段、旧 bridge 方法或 N-1 兼容逻辑。
- 把 lint/test 失败改成忽略、`passWithNoTests`、宽泛 exclude 或空实现。
- 执行破坏性 Git/文件命令，或推送/合并/打 tag。

遇到凭据、商店后台、生产环境或不可逆数据迁移时，完成所有本地可验证工作后停止，并明确列出人工步骤。

## 6. 架构硬约束

- Native 是 shell 模式下应用生命周期、导航、auth refresh/logout、语言、push、deep link、analytics session 和营销 SDK 的单一所有者。
- RN 以 Surface 微根运行，不假设完整 `App.tsx` 被挂载；每个 Surface 必须显式补齐 provider、hydrate、首帧和关闭协议。
- 全 App 只允许一个 React Runtime。不得为每个 Surface 新建 Runtime。
- Bridge 按 Auth、Navigation、Lifecycle、Analytics/Context、Storage Migration 分契约；新增方法只能 additive，JS 调用必须可选或有 capability gate。
- 所有账号相关异步结果必须带 auth generation/epoch；退出或换号后旧结果不得回写 token、缓存或 UI。
- API 区分 `required`、`opportunistic`、`none` 三种鉴权语义；“public”不等于永远不带 token。
- 数据迁移必须 versioned、幂等、先兼容读新旧格式；确认覆盖率前不得删除旧格式。
- testTag/contentDescription 从首个页面开始稳定复用语义 ID，禁止无迁移方案地改名或删除。

## 7. 实现风格

- Kotlin + Jetpack Compose；在 brownfield 导航期以 Fragment/ComposeView 与 ReactFragment 互操作，优先保证生命周期和返回栈正确。
- ViewModel + StateFlow；只为真实数据源建立 Repository，不为每个 API 创建形式化 UseCase。
- 首阶段使用显式 `AppContainer` 依赖注入，避免在 bootstrap 同时引入大型 DI 迁移。
- 网络采用 OkHttp + Retrofit + kotlinx.serialization；对后端 number/string/null 漂移使用集中 tolerant serializer，并以真实 fixture 测试。
- 媒体使用 Media3；播放器、预加载和图片缓存必须有上限，不允许页面各自创建无界资源。
- 任何共享 Gradle、Manifest、根导航、Application、bridge schema、CI、发布脚本改动都属于 hotspot，只能由 packet 指定 owner 修改。

## 8. 验证与状态更新

- 每个 packet 的自动命令必须实际执行；命令不存在或 variant 名不对即表示 packet 未完成，先修正文档/工程。
- “能编译”不是页面完成。页面还需状态、异常、登录切换、生命周期、埋点、accessibility/test ID 与手工 QA 证据。
- 只有所有 Definition of Done 都满足后，才能把 packet 标为 `DONE` 并更新 progress。
- feature agent 不直接并发编辑 progress；由 orchestrator 汇总更新。
- 不得声称未执行的测试已通过。受环境限制的测试列入 `NOT RUN`，说明原因、风险和复现命令。

完成回报固定包含：

1. 实际 Android base SHA 与 RN SHA。
2. 修改文件清单。
3. 逐条验收命令、退出码和关键结果。
4. 手工/真机证据；未执行项及原因。
5. 已知风险与回滚方式。
6. progress/矩阵中更新的行。
7. 需要人工授权或 progress 中下一个 READY work unit。
