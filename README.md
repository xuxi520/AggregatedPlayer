# 站点聚合播放器 · AggregatedPlayer

把常用的影视 / 视频站点聚合到一处，在应用内直接浏览、播放与下载的跨端播放器。
基于 **Kotlin Multiplatform + Compose Multiplatform**，一套共享代码同时产出 **Android 应用** 与 **Windows 桌面应用**。

An aggregator player that brings your frequently-used video sites together into one place,
with in-app browsing, playback and download.
Built on **Kotlin Multiplatform + Compose Multiplatform**: one shared codebase produces both
the **Android app** and the **Windows desktop app**.

> 应用只提供「导航 + 播放 + 下载」能力，**不存储、不分发**任何站点内容；
> 所有内容与版权均归原站及著作权人所有。内置站点目录是可替换的配置文件（见下方「内置站点配置」一节）。
>
> The app only provides **navigation + playback + download** capabilities; it does **not store or
> distribute** any site content. All content and copyrights belong to the original sites and their
> rights holders. The built-in site catalog is a replaceable config file (see the Built-in Site
> Configuration section below).

---

## 功能特性 · Features

### 跨端共享（Android / Windows） · Cross-platform (Android / Windows)

| 能力 Capability | 说明 Description |
| --- | --- |
| 多标签页 Tabs | 站点实例池管理，标签保活、后台不销毁 / Site instance pool; tabs stay alive and are not destroyed in the background. |
| 广告拦截 Ad-block | 网络层拦截 + 元素隐藏 + 弹窗守卫 + 点击拾取，内置 EasyList 子集 + 用户自定义规则 / Network-level blocking + element hiding + popup guard + click picking; built-in EasyList subset + custom rules. |
| 媒体嗅探 Sniffing | 识别页面里的直链 / HLS / DASH 媒体资源，可单独下载 / Detect direct / HLS / DASH media resources on a page and download them individually. |
| 增强播放 Enhanced play | 嗅探到可播放资源时用覆盖层接管，提供快进 / 暂停 / 快退、倍速、静音、循环 / Overlay takeover when playable media is found: FF / pause / RW, speed, mute, loop. |
| 下载 Download | 直链下载 + HLS/DASH 离线缓存（边播边缓存，可离线复用）/ Direct download + HLS/DASH offline cache (stream-as-you-cache, reusable offline). |
| UA 模式 UA mode | 按站点切换 UA（移动 / 桌面），桌面端同时改写视口宽度 / Per-site UA switching (mobile / desktop); desktop also rewrites the viewport width. |
| 播放偏好 Playback pref | 全局默认播放引擎 + 单站点覆盖（跟随全局 / 网页原生 / 增强引擎 / 每次询问）/ Global default engine + per-site override (global / native web / enhanced / ask every time). |
| 外部推送 External push | 把媒体地址推送到系统上的第三方播放器 / Push a media URL to a third-party player on the system. |
| 本地持久化 Persistence | 站点表、拦截规则、设置、标签会话全部落盘，JSON 通道，跨端同构 / Sites, rules, settings and tab sessions persisted to disk via JSON, identical across platforms. |
| 沉浸式界面 Immersive UI | 悬浮触发器、边缘手势、闲置淡化、点级标记 / Floating trigger, edge gestures, idle dimming, point-level markers. |

### Android 端 · Android

- 多标签 WebView 实例池 + 后台保活 / Multi-tab WebView instance pool with background keep-alive.
- Media3 (ExoPlayer) 播放直链 / HLS / DASH / Play direct / HLS / DASH via Media3 (ExoPlayer).
- `DownloadManager` 离线缓存（前台服务保活，Android 14+ 声明 `dataSync` 类型）/ Offline cache via `DownloadManager` (foreground service; `dataSync` type declared on Android 14+).
- 崩溃现场回收：无 adb 环境也能一键复制崩溃日志 / Crash recovery: copy the crash log with one tap even without an adb environment.

### Windows 桌面端 · Windows Desktop

- 内嵌浏览器内核（JCEF / CEF 152，Chromium 152）/ Embedded browser engine (JCEF / CEF 152, Chromium 152).
- 应用内浏览：站点页面与站点自带播放器都在应用窗口内 / In-app browsing: both the site page and its native player live inside the app window.
- 网络层拦截、媒体嗅探、per-tab UA、增强播放（应用内）/ Network blocking, media sniffing, per-tab UA and in-app enhanced playback.
- 直链下载落 `~/Downloads/AggregatedPlayer`；外部播放器推送 / Direct downloads go to `~/Downloads/AggregatedPlayer`; external player push supported.

---

## 技术栈 · Tech Stack

- **Kotlin Multiplatform** 2.4.21 · **Compose Multiplatform** 1.12.2（Material 3）
- **kotlinx.serialization**（JSON 持久化通道）/ JSON persistence channel · **kotlinx.coroutines**
- **Media3 / ExoPlayer** 1.11.1（Android 播放与离线缓存）/ Android playback & offline cache
- **JCEF**（`dev.datlag:jcef`，桌面端内嵌浏览器内核）/ Desktop embedded browser engine
- **Gradle** 9.x · **AGP** 9.1.1 · **Detekt** 1.23.8（静态检查）/ Static analysis
- Android 端 release 开启 **R8 混淆 + 资源裁剪** / Android release build enables **R8 obfuscation + resource shrinking**

---

## 环境要求 · Requirements

| 依赖 Dependency | 版本 Version |
| --- | --- |
| JDK | 21（由 Gradle toolchain 自动获取，无需手动安装）/ Auto-provisioned by the Gradle toolchain, no manual install. |
| Android SDK | compileSdk / targetSdk 37，minSdk 24 |
| Gradle | 随 wrapper（`./gradlew`）/ Bundled wrapper (`./gradlew`). |
| JCEF natives | **仅桌面端需要**，见下 / **Desktop only**, see below. |

---

## 快速开始 · Quick Start

```bash
# Android：调试包 / Android debug build
./gradlew :androidApp:installDebug

# Android：混淆 + 资源裁剪后的 release 包 / Obfuscated + shrunk release build
./gradlew :androidApp:assembleRelease
#   产物：androidApp/build/outputs/apk/release/androidApp-release.apk
#   R8 产物：androidApp/build/outputs/mapping/release/（mapping.txt 必须随版本归档）
#   R8 outputs: androidApp/build/outputs/mapping/release/ (archive mapping.txt with each release)

# 桌面端：原生安装包（需本机有 jpackage；MSI 另需 WiX）/ Desktop native package
# (requires a local jpackage; MSI also needs WiX)
./gradlew :desktopApp:packageDistribution
```

### 桌面端构建：先放置 JCEF natives · Desktop build: provide JCEF natives first

CEF 的原生二进制**不在任何 Maven 构件里**，且必须与 `jcef.jar` 同版本，
因此仓库不携带 natives（体积约 376 MB）。clone 后自行准备：

CEF native binaries are **not shipped in any Maven artifact** and must match the `jcef.jar`
version, so the repo does not carry them (~376 MB). Prepare them after cloning:

1. 下载与 `shared/libs/desktop/cef/bin/jcef.jar` **同版本**的 JCEF 发行包；
   Download the JCEF distribution that matches the version of `shared/libs/desktop/cef/bin/jcef.jar`.
2. 解压到 `shared/libs/desktop/cef/`，使目录结构形如 / Extract into `shared/libs/desktop/cef/` so the layout looks like:

   ```
   shared/libs/desktop/cef/bin/
   ├── jcef.jar
   ├── jogl-all.jar
   ├── gluegen-rt.jar
   ├── lib/win64/            # libcef.dll、chrome_elf.dll、jcef_helper.exe、locales/
   └── ...
   ```

3. 重新构建。**natives 缺失时桌面端仍可编译**，只是运行时退回「系统浏览器打开」模式
   （构建脚本会整块跳过 CEF 依赖，见 `shared/build.gradle.kts`）。
   Rebuild. **The desktop module still compiles without natives** — it just falls back to the
   "open in system browser" mode at runtime (the build script skips the CEF dependency wholesale;
   see `shared/build.gradle.kts`).

> Android 端**不需要** natives，直接 `./gradlew :androidApp:assembleRelease` 即可。
> The Android side **does not need** natives; just run `./gradlew :androidApp:assembleRelease`.

### 部署前自检 · Pre-deploy self-check

```bash
JCEF_SMOKE=1 ./gradlew :desktopApp:run    # 只初始化 CEF 并打印版本，不开窗口 / init CEF and print version only, no window
```

---

## 内置站点配置 · Built-in Site Configuration

内置站点目录**不在代码里**，而是一个可替换的 JSON 配置文件：

The built-in site catalog lives **outside the code** as a replaceable JSON config file:

```
shared/src/commonMain/composeResources/files/builtin_sites.json
```

```json
{
  "sites": [
    { "name": "示例站点 Example", "url": "https://example.com/" }
  ]
}
```

- **换站点只改这个文件**，不用改 Kotlin、不用动同步逻辑；
  **To change sites, edit only this file** — no Kotlin changes, no sync logic changes.
- 应用启动时以 URL 为键，把本地站点表对齐到该目录（增删内置站、保留用户自建站，详见 `BuiltInSiteSync`）；
  On launch the app aligns the local site table to this catalog keyed by URL (adds/removes built-in
  sites, keeps user-created sites — see `BuiltInSiteSync`).
- 文件缺失或格式错误时会回退到代码内的兜底目录，并在日志中给出告警。
  If the file is missing or malformed, it falls back to the in-code default catalog and logs a warning.

---

## 项目结构 · Project Structure

```
AggregatedPlayer/
├── shared/                     # 跨端共享代码 / cross-platform shared code
│   ├── src/commonMain/         #   领域模型 / 用例 / 数据仓库 / Compose UI
│   ├── src/androidMain/        #   Android 平台实现（WebView、Media3、下载、推送）
│   ├── src/jvmMain/            #   桌面端平台实现（JCEF、拦截、嗅探、下载）
│   └── src/commonTest/         #   纯逻辑单测 / pure-logic unit tests
├── androidApp/                 # Android 应用模块（清单、图标、R8 规则）
└── desktopApp/                 # 桌面应用模块（入口、原生分发配置）
```

分层约定 / Layering conventions:

- `domain/model` 领域模型 · `domain/usecase` 纯逻辑用例（可单测） · `domain/repository` 仓储接口
- `data` JSON 仓储实现 · `platform` 平台能力抽象（`expect`/`actual`） · `ui` Compose 界面
- 平台真实行为用 instrumented / 手工验证清单覆盖，不写脆弱单测
  Platform-real behavior is covered by instrumented / manual verification checklists; no brittle unit tests.

---

## 安全与加固 · Security & Hardening

- **R8 混淆 + 资源裁剪**（release）：类名 / 方法名混淆、无用代码与资源裁剪。
  **R8 obfuscation + resource shrinking** (release): class/method name obfuscation, dead code and
  resource removal.
- **放行规则**（`androidApp/proguard-rules.pro`，每条都有对应的线上失效场景）/ **Keep rules**
  (`androidApp/proguard-rules.pro`, each tied to a specific production-failure scenario):
  - kotlinx.serialization 的 `Companion` / `serializer(...)` / `$serializer` —— 否则反序列化抛异常、本地数据读不出来；
    kotlinx.serialization `Companion` / `serializer(...)` / `$serializer` — otherwise deserialization
    throws and local data can't be read.
  - 序列化模型与枚举的**字段名 / 常量名** —— 它们就是 JSON key，改名会导致旧版本数据不兼容；
    Serialized models' and enums' **field / constant names** — they are the JSON keys; renaming breaks
    backward compatibility with older data.
  - WebView `@JavascriptInterface` 桥接方法与桥接类 —— 页面 JS 按名字调用，改名后嗅探回传静默失效。
    WebView `@JavascriptInterface` bridge methods and bridge class — page JS calls by name; renaming
    silently breaks sniffing callbacks.
- **日志** / **Logs**：release 移除 `Log.v/d`，保留 `Log.w/e`（崩溃诊断依赖）。
  Release strips `Log.v/d` but keeps `Log.w/e` (crash diagnostics depend on them).
- **备份** / **Backup**：`android:allowBackup="false"`，避免应用私有数据被系统或 `adb backup` 拷走。
  Prevents app-private data from being copied away by the system or `adb backup`.
- **签名** / **Signing**：release 签名证书通过 `APK_STORE_FILE` / `APK_STORE_PASSWORD` / `APK_KEY_ALIAS` /
  `APK_KEY_PASSWORD` 提供（gradle 属性或环境变量），**未配置时回退到 debug 证书**，仅供本地验证构建链路。
  正式分发请务必配置正式证书（密钥文件已在 `.gitignore` 中排除）。
  Release signing reads `APK_STORE_FILE` / `APK_STORE_PASSWORD` / `APK_KEY_ALIAS` / `APK_KEY_PASSWORD`
  (gradle properties or env vars); **falls back to the debug cert if unset** (local verification only).
  Configure a real cert before distribution (key files are excluded in `.gitignore`).

---

## 常见问题 · FAQ

**桌面端点击站点没反应 / 提示用系统浏览器打开？ / Desktop: clicking a site does nothing, or it says open in system browser?**
本机 JOGL 无法初始化图形设备时，内核挂载会失败并自动降级为「系统浏览器打开」模式（日志中有对应告警）。
此时请用系统浏览器访问；应用其余能力（站点管理、拦截规则、下载、设置）不受影响。
When JOGL can't initialize the graphics device on the host, the engine mount fails and the app
auto-degrades to "open in system browser" mode (a warning appears in the log). Use the system browser
in that case; all other capabilities (site management, rules, download, settings) are unaffected.

**改了 `builtin_sites.json` 但站点没变？ / Edited `builtin_sites.json` but sites didn't change?**
确认文件放在 `shared/src/commonMain/composeResources/files/` 下且 JSON 格式正确；
若加载失败，日志中会出现 `读取 builtin_sites.json 失败` 的告警（此时生效的是代码内兜底目录）。
Make sure the file is under `shared/src/commonMain/composeResources/files/` and is valid JSON. On load
failure the log shows `读取 builtin_sites.json 失败` (the in-code fallback catalog is then active).

**`./gradlew` 卡在下载依赖？ / `./gradlew` hangs downloading dependencies?**
国内网络下 Maven Central 直连可能超时。仓库的 `settings.gradle.kts` 已把阿里云镜像放在前面。
On networks where Maven Central is slow, the repo's `settings.gradle.kts` already puts the Aliyun
mirror first.

---

## 免责声明与合规声明 · Disclaimer & Compliance

1. 本项目是**工具**，不存储、分发、缓存任何站点内容，仅提供导航与播放能力。
   This project is a **tool**; it does not store, distribute or cache any site content, and only
   provides navigation and playback capability.
2. 内置站点目录指向第三方站点，其内容与版权均归原站及著作权人所有；内置列表仅为默认配置，
   **可由使用者自行替换或清空**。
   The built-in catalog points to third-party sites; their content and copyrights belong to the
   original sites and rights holders. The built-in list is only a default configuration and **can be
   replaced or cleared by the user**.
3. 使用者需自行确保其使用行为符合所在地法律法规；若权利人有异议或要求下架，请通过项目维护者联系方式处理。
   Users must ensure their usage complies with local laws and regulations; if a rights holder objects
   or requests takedown, contact the maintainer via the contact info in the app.
4. 项目按「现状」提供，不对任何使用后果作担保。
   Provided "as is", with no warranty of any kind for any consequence of use.

## 许可证 · License

本项目以 **MIT 许可证** 开源。详见 [LICENSE](LICENSE) 文件。
Released under the **MIT License**. See the [LICENSE](LICENSE) file for details.

---

## 维护 · Maintenance

内部设计决策、踩坑记录与阶段进展记录在 `README.internal.md`（仅本地保留，不随仓库分发）。
Internal design decisions, pitfalls and milestone notes are kept in `README.internal.md`
(local only, not distributed with the repository).
