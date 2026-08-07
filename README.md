# Tipsy Android Native

本仓库是 Tipsy 从 React Native 迁移到 Android Native 的目标仓库。目前阶段是 **迁移执行基线**：先固化事实、边界、质量门禁和可回滚的任务包，再按任务包实现代码。

## 给 Codex 的最短启动方式

在本仓库根目录启动 Codex，然后发送：

> 阅读 `AGENTS.md` 和 `llmdoc/index.md`，核对 `llmdoc/reference/android-native-progress.md` 的 SHA。只执行 progress 中唯一 READY work unit 对应的 execution packet 小节；不要执行发布、上传、生产 OTA、签名或包名变更。完成后按 packet 的回报格式更新唯一进度文档。

Codex 必读顺序：

1. `AGENTS.md`
2. `llmdoc/index.md`
3. `llmdoc/overview/project-overview.md`
4. `llmdoc/reference/android-native-progress.md`
5. 当前唯一 READY 的 `llmdoc/execution/*.md`

不要把 architecture 文档中的目标状态误当成已实现状态；已完成情况只以 progress 文档和当前代码为准。

> 初始交接说明：目标仓目前是 unborn `main`，所以本次生成的 `README.md`、`AGENTS.md` 与整个 `llmdoc/` 会显示为未跟踪文件。它们是经过授权的 P00 文档基线，不是未知用户改动。建议用户先单独形成一个 docs 初始提交再让 Codex 写代码；若尚未提交，P00 也只可编辑这些明确路径，不能触碰同时出现的 `.idea/` 或其他未跟踪内容。

## 当前冻结基线

- RN 产品真值仓库：`git@github.com:Lightspeed-Intelligence/tipsy-app.git`
- RN 基线 commit：`cbd521f02972933c21f90c01787ea5c11200875e`
- iOS 经验参考仓库：`git@github.com:Lightspeed-Intelligence/tipsy-iOS.git`
- iOS 审计 HEAD：`4b42d8d`
- Android 目标仓：当前为 unborn `main`，尚无 Gradle 工程

若实际 SHA 与上述值不同，先执行 delta audit 并更新 progress；禁止静默混用多个 RN 版本。

## 文档入口

- [项目现状与目标](llmdoc/overview/project-overview.md)
- [iOS 迁移复盘](llmdoc/retrospective/ios-native-migration-retrospective.md)
- [Android 目标架构](llmdoc/architecture/android-native-migration-blueprint.md)
- [RN/Native 边界契约](llmdoc/architecture/android-rn-boundary-contract.md)
- [构建、发布与 OTA 架构](llmdoc/architecture/android-build-release-ota-architecture.md)
- [功能对等矩阵](llmdoc/reference/rn-parity-contract-matrix.md)
- [质量门禁](llmdoc/reference/android-quality-gates.md)
- [唯一进度状态](llmdoc/reference/android-native-progress.md)
- [可执行任务包](llmdoc/execution/00-repository-bootstrap-and-baseline.md)
