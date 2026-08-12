# 从 RN 移植的位图资源（`app/src/main/res/drawable-nodpi/`）

这里的 PNG 全部从 `tipsy-app/src/assets/images/` 原样拷来，**不做重采样**。

## 为什么是 `nodpi` 而不是 `xxhdpi`

RN 侧这些资源**只有一份**（无 `@2x`/`@3x` 变体，已核实），实际像素恰好是设计稿
数值的 3 倍（如 tab 图标显示 40dp、文件 120×120）。

两种放法的区别：

| 目录 | 效果 |
| --- | --- |
| `drawable-xxhdpi` | Android 按 3× 解释；在 xxxhdpi(4×) 设备上会**放大**采样，比 RN 版略模糊 |
| **`drawable-nodpi`**（当前） | 不做密度换算，按 Compose 里显式给的 dp 尺寸缩放 —— 与 RN 的 `expo-image` 行为一致 |

RN 的 bundler 把它们打进 `drawable-mdpi`（实测 `app/build/generated/res/.../drawable-mdpi/`），
也就是说 **RN 侧根本不参与 Android 的密度分档**，尺寸完全由 JS 的 style 决定。
`nodpi` 是与之最接近的语义。

⚠️ 因此**每个使用点都必须显式给尺寸**（`Modifier.size(...)`）。漏了会按位图原始
像素铺开 —— 40dp 的图标变成 120dp，一眼可见。

## `IconMissingDensityFolder` 是刻意不满足的

lint 建议补 `drawable-hdpi/mdpi/xhdpi` 三档。**不采纳**：源图只有一份，
补的只能是重采样产物 —— 那不是新信息，只是把同一张图存四遍（包体 ×4），
且各档之间的缩放误差会让不同设备上的图标细节不一致。

该规则已在 `app/build.gradle` 的 lint 配置里显式 `disable` 并写明理由。
**若将来设计提供了多档原图，应当撤销那个 disable 并按档放置。**
