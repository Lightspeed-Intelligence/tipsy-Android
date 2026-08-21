# Tipsy Android Native Shell

Tipsy 的 Android 原生壳工程。原生页面位于 `app/`，RN 业务以 Surface 的形式从
git submodule `tipsy-app/` 嵌入。Gradle 工程根目录是本仓库根目录，打开项目、执行
脚本和 Gradle 命令时都不要进入 `app/` 或 `tipsy-app/`。

架构与开发纪律见 [llmdoc/index.md](llmdoc/index.md)。

## 环境要求

| 工具           | 项目基线                                                     |
| -------------- | ------------------------------------------------------------ |
| Android Studio | 当前稳定版，使用原版 `/Applications/Android Studio.app`      |
| Node.js        | 22.x（与 CI 一致）                                           |
| JDK            | 17（CI/编译基线）；Android Studio 自带的 JBR 21 也已验证可用 |
| Android SDK    | Platform 36、Build Tools 36.0.0、Platform Tools              |
| Android NDK    | 27.1.12297006（Side by side）                                |
| CMake          | 3.22.1                                                       |

本文的本地开发流程以 macOS 为准，bootstrap 同时用于 Linux CI；当前尚未提供 Windows
等价脚本，不要直接把 POSIX 路径写法替换成 Windows 路径后使用。

在 Android Studio 的 **Settings → Languages & Frameworks → Android SDK** 中安装
上述 SDK、NDK 和 CMake。命令行还需要能找到 Android SDK：设置 `ANDROID_HOME`，
或在仓库根的 `local.properties` 中保留正确的 `sdk.dir`。该文件只属于本机，不要提交。

日常建议使用 API 36、ARM64 的 Google APIs 模拟器或 ARM64 真机。当前 debug 构建
只编译 `arm64-v8a`；x86/x86_64 模拟器不是 Android Studio 的默认开发路径。

## 首次启动

### 1. 拉取仓库

需要先配置好公司 GitHub 仓库及私有 `tipsy-app` 子模块的 SSH 权限。

```bash
git clone --recurse-submodules git@github.com:Lightspeed-Intelligence/tipsy-Android.git
cd tipsy-Android
```

如果已经 clone，但子模块目录为空：

```bash
git submodule update --init tipsy-app
```

### 2. 初始化本机环境

确保当前终端能执行 `node` 和 `java`，然后在仓库根运行：

```bash
./scripts/bootstrap-android.sh
```

脚本会完成以下工作：

- 校验并初始化仓库固定的 `tipsy-app` commit；
- 在 `tipsy-app/` 中执行锁版本的 `npm ci`；
- 创建根目录 `node_modules -> tipsy-app/node_modules` 软链；
- 将稳定的 Node 绝对路径写入本机 `local.properties`；
- 在 `PATH` 无法找到裸 `node` 的条件下执行 Gradle/Expo/RN 校验。

成功时末尾会看到：

```text
Node contract passed: Gradle did not need node on PATH.
Android bootstrap complete.
```

如果命令行找不到 JDK，可临时使用 Android Studio 自带 JDK 后重试：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./scripts/bootstrap-android.sh
```

以下情况需要重新运行 bootstrap：首次 clone、Node 安装位置或版本改变、
`tipsy-app` pin/依赖发生变化、删除过 `node_modules` 或 `local.properties`。

## 使用 Android Studio 运行

1. 打开正常的 `/Applications/Android Studio.app`。不再需要
   `Android Studio (Tipsy).app`、`launchctl setenv` 或从终端启动 Studio。
2. 选择 **File → Open**，打开仓库根目录，不要单独打开 `app/` 或 `tipsy-app/`。
3. 在 **Settings → Build, Execution, Deployment → Build Tools → Gradle** 检查
   **Gradle JDK**。推荐 JDK 17；使用 Android Studio 自带 JBR 21 也可以。
4. 执行 **File → Sync Project with Gradle Files**。Gradle 输出应包含类似：

   ```text
   [Tipsy] node v22.x.x（来源：local.properties 的 tipsy.node.executable）
   ```

5. 打开 **View → Tool Windows → Build Variants**，将 `app` 选择为
   `directApkDebug`。这是日常本地开发默认使用的 variant。
6. 启动 ARM64 模拟器或连接 ARM64 真机，在顶部选择 `app` 和目标设备，点击
   **Run ▶**。

如果顶部没有 `app`：打开 **Run → Edit Configurations… → + → Android App**，
设置 Module 为 `app`、Launch 为 `Default Activity`。

### Build Variant 说明

| Variant           | Application ID        | 用途                     |
| ----------------- | --------------------- | ------------------------ |
| `directApkDebug`  | `ai.lightspeed.tipsy` | 日常本地开发，默认推荐   |
| `googlePlayDebug` | `com.tipsyturbo.app`  | Google Play 渠道兼容验证 |
| `ruStoreDebug`    | `com.tipsytavern.app` | RuStore 渠道兼容验证     |

三个 debug variant 都连接 development API，并使用 Metro 8083。它们的 Application
ID 与对应渠道包一致，可能与设备上已安装的 Tipsy 冲突；建议使用专用开发模拟器，
不要为了安装 debug 包而删除含有重要本地数据的正式 App。

普通 Native 页面不依赖 Metro。debug 构建也带有 embedded RN bundle，因此不启动
Metro 时 RN Surface 仍可能打开；但此时修改 JS/TS 不会实时生效。调试 RN 必须按下一节
启动 Metro，并以 Metro 的 bundle 请求作为是否生效的判断依据。

## 启动 Metro 调试 RN Surface

### 1. 启动 Android 壳专用 Metro

另开一个终端，从 `tipsy-app/` 启动：

```bash
cd tipsy-app
npm start -- --port 8083
```

端口不能省略。三个本地入口约定如下：

| 端口 | 使用方           |
| ---- | ---------------- |
| 8081 | 独立 `tipsy-app` |
| 8082 | iOS 原生壳       |
| 8083 | Android 原生壳   |

Android 壳的入口是 `tipsy-app/index.surfaces.js`，不是独立 Expo App 的完整入口。
不要在这里执行 `npm run android`，它会构建并启动另一套独立 Expo Android App；壳
始终由 Android Studio 安装和启动。

Metro 缓存异常时可以清缓存重启：

```bash
npm start -- --port 8083 --clear
```

### 2. 连接设备

标准 Android Emulator 会自动通过 `10.0.2.2:8083` 访问宿主机，无需执行
`adb reverse`。

USB 真机需要先转发端口：

```bash
adb devices
adb -s <device-serial> reverse tcp:8083 tcp:8083
adb -s <device-serial> reverse --list
```

只有一台设备时，也可以省略 `-s <device-serial>`。

### 3. 从 Android Studio 运行并进入 RN 页面

保持 Metro 终端运行，在 Android Studio 中 Run `directApkDebug`。App 首屏主要是
Native 页面，首次进入 Create、ChatDetail 等 RN Surface 时才会明显触发 RN bundle。

最好在首次进入 RN Surface 前启动 Metro。如果 App 已经加载了 embedded bundle，
启动 Metro 后重新 Run App；也可以先强制停止再打开：

```bash
adb shell am force-stop ai.lightspeed.tipsy
```

连接 Metro 后，修改 RN 的 JS/TS 文件并保存即可使用 Fast Refresh；修改 Kotlin、
Android 资源或原生依赖仍需重新 Build/Run。

## 验证 Metro 确实生效

先验证宿主机上的 Metro：

```bash
curl -fsS http://127.0.0.1:8083/status
```

预期输出：

```text
packager-status:running
```

再进入一个 RN Surface，并同时检查：

1. Metro 终端出现 `index.surfaces` 的 Android bundle 请求；
2. Android Studio Logcat，或下面的命令，出现 Metro 与 Surface 日志：

   ```bash
   adb logcat -s BridgelessReact ReactNativeJS
   ```

   ```text
   isMetroRunning(): Async result = true
   [Surfaces] index.surfaces.js evaluated
   [Surfaces] components registered
   ```

如果 `isMetroRunning` 是 `false`，页面可能仍会从 embedded bundle 打开，但 JS 改动
不会来自 Metro。此时检查端口、真机 reverse，并在 Metro 已启动后重新运行 App。

## 常见问题

| 现象                                                  | 处理                                                                                                                          |
| ----------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| Studio Sync 报 `Cannot run program "node"`、`error=2` | 在仓库根重跑 `./scripts/bootstrap-android.sh`，然后重新 Sync。不要恢复包装 App 或 `launchctl` 方案。                          |
| Node 升级或切换 fnm/nvm 版本后 Sync 失败              | 重跑 bootstrap，让 `local.properties` 记录新的稳定绝对路径。                                                                  |
| bootstrap 报找不到 SDK/NDK/CMake                      | 在 SDK Manager 安装本文固定版本，确认 `ANDROID_HOME` 或 `local.properties` 的 `sdk.dir`，再重跑。                             |
| bootstrap 报 `tipsy-app` pin 不一致                   | 先保存 `tipsy-app` 中的工作，再执行 `git submodule update --init tipsy-app`；不要把 `--allow-submodule-mismatch` 当默认解法。 |
| App 能打开，但 JS 修改不生效                          | 确认 Metro 监听 8083、终端收到 `index.surfaces` 请求；清缓存并在 Metro 启动后重新 Run App。                                   |
| 真机无法连接 Metro                                    | 重新执行 `adb reverse tcp:8083 tcp:8083`；多设备时显式指定 serial。                                                           |
| `INSTALL_FAILED_NO_MATCHING_ABIS`                     | 换用 ARM64 模拟器或 ARM64 真机。                                                                                              |
| 安装时报签名或包冲突                                  | 使用专用模拟器，或选择未安装对应正式包的 flavor；删除 App 会同时删除其本地数据。                                              |
| 登录发码被后端拒绝                                    | 本地未配置对应渠道的 device-id key 时 UI 可以运行，但发码会被风控拒绝；向项目 owner 获取本机配置，凭据不要提交。              |

只想复验 Android Studio 使用的 Node 契约，可以执行：

```bash
./scripts/check-node-contract.sh --use-local-properties
```

## 常用命令

均在仓库根执行：

```bash
# 构建日常 debug APK
./gradlew :app:assembleDirectApkDebug

# 构建并安装到当前 adb 设备
./gradlew :app:installDirectApkDebug

# 运行 Android lint
./gradlew :app:lintDirectApkDebug
```

RN 依赖、`index.surfaces.js` 或 Surface 契约有改动时，请同时遵守
[`tipsy-app/AGENTS.md`](tipsy-app/AGENTS.md)。`index.surfaces.js` 是 iOS/Android
双壳共用入口，改动后必须做双壳回归。
