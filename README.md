# 站点聚合播放器 · AggregatedPlayer

把常用的影视/视频站点聚合到一处，在应用内直接浏览、播放与下载的跨端播放器。
基于 **Kotlin Multiplatform + Compose Multiplatform**，一套共享代码同时产出 **Android 应用** 与 **Windows 桌面应用**。

> 应用只提供「导航 + 播放 + 下载」能力，**不存储、不分发**任何站点内容；
> 所有内容与版权均归原站及著作权人所有。内置站点目录是可替换的配置文件（见 [内置站点配置](#内置站点配置)）。

---

## 功能特性

### 跨端共享（Android / Windows）

| 能力 | 说明 |
| --- | --- |
| 多标签页 | 站点实例池管理，标签保活、后台不销毁 |
| 广告拦截 | 网络层拦截 + 元素隐藏 + 弹窗守卫 + 点击拾取，内置 EasyList 子集 + 用户自定义规则 |
| 媒体嗅探 | 识别页面里的直链 / HLS / DASH 媒体资源，可单独下载 |
| 增强播放 | 嗅探到可播放资源时用覆盖层接管，提供快进 / 暂停 / 快退、倍速、静音、循环 |
| 下载 | 直链下载 + HLS/DASH 离线缓存（边播边缓存，可离线复用） |
| UA 模式 | 按站点切换 UA（移动 / 桌面），桌面端同时改写视口宽度 |
| 播放偏好 | 全局默认播放引擎 + 单站点覆盖（跟随全局 / 网页原生 / 增强引擎 / 每次询问） |
| 外部推送 | 把媒体地址推送到系统上的第三方播放器 |
| 本地持久化 | 站点表、拦截规则、设置、标签会话全部落盘，JSON 通道，跨端同构 |
| 沉浸式界面 | 悬浮触发器、边缘手势、闲置淡化、点级标记 |

### Android 端

- 多标签 WebView 实例池 + 后台保活
- Media3 (ExoPlayer) 播放直链 / HLS / DASH
- `DownloadManager` 离线缓存（前台服务保活，Android 14+ 声明 `dataSync` 类型）
- 崩溃现场回收：无 adb 环境也能一键复制崩溃日志

### Windows 桌面端

- 内嵌浏览器内核（JCEF / CEF 152，Chromium 152）
- 应用内浏览：站点页面与站点自带播放器都在应用窗口内
- 网络层拦截、媒体嗅探、per-tab UA、增强播放（应用内）
- 直链下载落 `~/Downloads/AggregatedPlayer`；外部播放器推送

---

## 技术栈

- **Kotlin Multiplatform** 2.4.21 · **Compose Multiplatform** 1.12.2（Material 3）
- **kotlinx.serialization**（JSON 持久化通道） · **kotlinx.coroutines**
- **Media3 / ExoPlayer** 1.11.1（Android 播放与离线缓存）
- **JCEF**（`dev.datlag:jcef`，桌面端内嵌浏览器内核）
- **Gradle** 9.x · **AGP** 9.1.1 · **Detekt** 1.23.8（静态检查）
- Android 端 release 开启 **R8 混淆 + 资源裁剪**

---

## 环境要求

| 依赖 | 版本 |
| --- | --- |
| JDK | 21（由 Gradle toolchain 自动获取，无需手动安装） |
| Android SDK | compileSdk / targetSdk 37，minSdk 24 |
| Gradle | 随 wrapper（`./gradlew`） |
| JCEF natives | **仅桌面端需要**，见下 |

---

## 快速开始

```bash
# Android：调试包
./gradlew :androidApp:installDebug

# Android：混淆 + 资源裁剪后的 release 包
./gradlew :androidApp:assembleRelease
#   产物：androidApp/build/outputs/apk/release/androidApp-release.apk
#   R8 产物：androidApp/build/outputs/mapping/release/（mapping.txt 必须随版本归档）

# 桌面端：原生安装包（需本机有 jpackage；MSI 另需 WiX）
./gradlew :desktopApp:packageDistribution
```

### 桌面端构建：先放置 JCEF natives

CEF 的原生二进制**不在任何 Maven 构件里**，且必须与 `jcef.jar` 同版本，
因此仓库不携带 natives（体积约 376 MB）。clone 后自行准备：

1. 下载与 `shared/libs/desktop/cef/bin/jcef.jar` **同版本**的 JCEF 发行包；
2. 解压到 `shared/libs/desktop/cef/`，使目录结构形如：

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

> Android 端**不需要** natives，直接 `./gradlew :androidApp:assembleRelease` 即可。

### 部署前自检

```bash
JCEF_SMOKE=1 ./gradlew :desktopApp:run    # 只初始化 CEF 并打印版本，不开窗口
```

---

## 内置站点配置

内置站点目录**不在代码里**，而是一个可替换的 JSON 配置文件：

```
shared/src/commonMain/composeResources/files/builtin_sites.json
```

```json
{
  "sites": [
    { "name": "示例站点", "url": "https://example.com/" }
  ]
}
```

- **换站点只改这个文件**，不用改 Kotlin、不用动同步逻辑；
- 应用启动时以 URL 为键，把本地站点表对齐到该目录（增删内置站、保留用户自建站，详见 `BuiltInSiteSync`）；
- 文件缺失或格式错误时会回退到代码内的兜底目录，并在日志中给出告警。

---

## 项目结构

```
AggregatedPlayer/
├── shared/                     # 跨端共享代码
│   ├── src/commonMain/         #   领域模型 / 用例 / 数据仓库 / Compose UI
│   ├── src/androidMain/        #   Android 平台实现（WebView、Media3、下载、推送）
│   ├── src/jvmMain/            #   桌面端平台实现（JCEF、拦截、嗅探、下载）
│   └── src/commonTest/         #   纯逻辑单测
├── androidApp/                 # Android 应用模块（清单、图标、R8 规则）
└── desktopApp/                 # 桌面应用模块（入口、原生分发配置）
```

分层约定：

- `domain/model` 领域模型 · `domain/usecase` 纯逻辑用例（可单测） · `domain/repository` 仓储接口
- `data` JSON 仓储实现 · `platform` 平台能力抽象（`expect`/`actual`） · `ui` Compose 界面
- 平台真实行为用 instrumented / 手工验证清单覆盖，不写脆弱单测

---

## 安全与加固

- **R8 混淆 + 资源裁剪**（release）：类名 / 方法名混淆、无用代码与资源裁剪。
- **放行规则**（`androidApp/proguard-rules.pro`，每条都有对应的线上失效场景）：
  - kotlinx.serialization 的 `Companion` / `serializer(...)` / `$serializer` —— 否则反序列化抛异常、本地数据读不出来；
  - 序列化模型与枚举的**字段名 / 常量名** —— 它们就是 JSON key，改名会导致旧版本数据不兼容；
  - WebView `@JavascriptInterface` 桥接方法与桥接类 —— 页面 JS 按名字调用，改名后嗅探回传静默失效。
- **日志**：release 移除 `Log.v/d`，保留 `Log.w/e`（崩溃诊断依赖）。
- **备份**：`android:allowBackup="false"`，避免应用私有数据被系统或 `adb backup` 拷走。
- **签名**：release 签名证书通过 `APK_STORE_FILE` / `APK_STORE_PASSWORD` / `APK_KEY_ALIAS` / `APK_KEY_PASSWORD`
  提供（gradle 属性或环境变量），**未配置时回退到 debug 证书**，仅供本地验证构建链路。
  正式分发请务必配置正式证书（密钥文件已在 `.gitignore` 中排除）。

---

## 常见问题

**桌面端点击站点没反应 / 提示用系统浏览器打开？**
本机 JOGL 无法初始化图形设备时，内核挂载会失败并自动降级为「系统浏览器打开」模式（日志中有对应告警）。
此时请用系统浏览器访问；应用其余能力（站点管理、拦截规则、下载、设置）不受影响。

**改了 `builtin_sites.json` 但站点没变？**
确认文件放在 `shared/src/commonMain/composeResources/files/` 下且 JSON 格式正确；
若加载失败，日志中会出现 `读取 builtin_sites.json 失败` 的告警（此时生效的是代码内兜底目录）。

**`./gradlew` 卡在下载依赖？**
国内网络下 Maven Central 直连可能超时。仓库的 `settings.gradle.kts` 已把阿里云镜像放在前面。

---

## 免责声明与合规声明

1. 本项目是**工具**，不存储、不分发、不缓存任何站点内容，仅提供导航与播放能力。
2. 内置站点目录指向第三方站点，其内容与版权均归原站及著作权人所有；内置列表仅为默认配置，**可由使用者自行替换或清空**。
3. 使用者需自行确保其使用行为符合所在地法律法规；若权利人有异议或要求下架，请通过项目维护者联系方式处理。
4. 项目按「现状」提供，不对任何使用后果作担保。

## 许可证

本项目暂未选定开源许可证。**如需二次分发或用于商业用途，请先联系作者确认授权范围。**

---

## 维护

内部设计决策、踩坑记录与阶段进展记录在 `README.internal.md`（仅本地保留，不随仓库分发）。
