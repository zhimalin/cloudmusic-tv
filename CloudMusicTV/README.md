# CloudMusicTV — Android TV 应用

纯电视端（遥控器操作、无触摸）Android TV 应用。
- 包名 / applicationId：`com.cloudmusic.tv`
- 版本：`versionCode 1` / `versionName 0.1.0`
- 目标设备：雷鸟鹏 7 Plus 26 款（75S78A Plus），Android 14，4K，2GB RAM，无 GMS

**当前进度：登录、导航、设置、首页、每日推荐、私人 FM、我的音乐、播放页、收藏与播放器懒创建均已实现；JVM/Robolectric 测试与 debug/release 构建通过。**
**真机尚未验证**（TCL 包安装限制，见第 14 节）。

> 说明：第 1–12 节保留 M1/M2 时期的历史记录，其中「播放页尚未开始」「测试：无」、APK 大小、adb 安装命令等已过时。
> 最新、可执行的构建/安装/验证信息以 **第 13–15 节** 为准。

---

## 1. 版本矩阵

所有版本都是本机实际解析出来的结果（`gradlew :app:dependencies --configuration debugRuntimeClasspath` 核对）。

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Gradle | **8.9** | 手动安装，非 wrapper 下载的副本也验证过 |
| Android Gradle Plugin | **8.7.3** | 要求 Gradle ≥ 8.9，与 8.9 是官方配套组合 |
| Kotlin | **2.0.21** | Kotlin 2.x，Compose 编译器随 Kotlin 发布 |
| Compose 编译器插件 | **2.0.21** | `org.jetbrains.kotlin.plugin.compose`，版本必须与 Kotlin 一致 |
| Compose BOM | **2024.10.01** | 统一 compose 库版本 |
| compose-ui / compose-foundation | **1.7.5**（实际解析） | BOM 对齐后 tv-material 里的 1.6.8 被升到 1.7.5 |
| androidx.tv:tv-material | **1.0.0** | Compose for TV 的 Material3 组件 |
| androidx.activity:activity-compose | **1.9.3** | 提供 `setContent` 与 `BackHandler` |
| androidx.core:core-ktx | **1.13.1** | |
| androidx.lifecycle:lifecycle-runtime-ktx | **2.8.7** | |
| androidx.media3:media3-exoplayer / media3-session | **1.4.1** | M2 播放核心；aar metadata `minCompileSdk=34` |
| JDK | **Temurin 17.0.21** | AGP 8.7 要求 JDK 17 |
| compileSdk / targetSdk / minSdk | **34 / 34 / 26** | |
| Build Tools | **34.0.0** | |
| Android SDK Platform | **android-34** | |

M1 没有引入任何新依赖：WebView 用系统实现，存储用 SharedPreferences，
`ContextCompat`/协程都来自已有依赖的传递依赖。

M2 新增 media3 1.4.1（exoplayer + session）及其传递依赖（Guava 33.0.0-android 等），
没有 GMS/play-services。APK 由 9,524,675 字节增至 13,019,551 字节（+3.33 MiB）。

---

## 2. 工具链位置（均在本机 Desktop 下，未改注册表、未 setx）

| 项 | 路径 |
| --- | --- |
| JDK 17 | `C:\Users\艾莉丝喵\Desktop\tv\.toolchain\jdk17` |
| Android SDK | `C:\Users\艾莉丝喵\Desktop\tv\.toolchain\android-sdk` |
| Gradle 8.9 | `C:\Users\艾莉丝喵\Desktop\tv\.toolchain\gradle-8.9` |
| Gradle 启动脚本 | `C:\Users\艾莉丝喵\Desktop\tv\.toolchain\gradle-8.9\bin\gradle.bat` |
| Gradle 分发包（留档） | `C:\Users\艾莉丝喵\Desktop\tv\.toolchain\gradle-8.9-bin.zip` |
| 环境脚本 | `C:\Users\艾莉丝喵\Desktop\tv\.toolchain\env.ps1`（**未修改**） |

Gradle 分发包校验（已与官方 sha256 比对一致）：

```
gradle-8.9-bin.zip
  size   136114148 bytes
  sha256 d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab
```

> 每个新开的 PowerShell 窗口先执行：`. .\env.ps1`（在 `C:\Users\艾莉丝喵\Desktop\tv` 下）。

---

## 3. 构建

```powershell
cd C:\Users\艾莉丝喵\Desktop\tv
. .\.toolchain\env.ps1          # 设置 JAVA_HOME / ANDROID_HOME，把工具加进 PATH（仅当前会话）
cd .\CloudMusicTV

.\gradlew.bat assembleDebug     # 推荐：用 wrapper
```

不想用 wrapper（等价）：

```powershell
& "C:\Users\艾莉丝喵\Desktop\tv\.toolchain\gradle-8.9\bin\gradle.bat" -p "C:\Users\艾莉丝喵\Desktop\tv\CloudMusicTV" assembleDebug --console=plain
```

常用其它命令：

```powershell
.\gradlew.bat clean assembleDebug          # 清理后重建
.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath   # 看真实依赖版本
```

**实测耗时（本机，SSD，2GB 堆）**

| 场景 | 耗时 |
| --- | --- |
| 首次成功构建（含下载全部 AGP / Kotlin / AndroidX / Compose 构件） | **1m 43s** |
| M1 第一次编译新代码的 `clean assembleDebug` | **15s** |
| `clean assembleDebug`（build cache 命中） | **5s** |
| `clean assembleDebug --no-build-cache`（全量重编） | **10s** |

---

## 4. 产物位置

| 产物 | 路径 |
| --- | --- |
| **debug APK** | `C:\Users\艾莉丝喵\Desktop\tv\CloudMusicTV\app\build\outputs\apk\debug\app-debug.apk` |
| 大小 | **8,219,770 字节（7.84 MB）** |
| 各 library 的 AAR | `<module>\build\outputs\aar\<module>-debug.aar` |
| 合并后的 Manifest | `app\build\intermediates\merged_manifests\debug\processDebugManifest\AndroidManifest.xml` |

M1 前后对比：**8,136,600 → 8,219,770 字节，+83,170 字节（+81.2 KB，+1.02%）**。
增量全部来自新页面 Kotlin 代码 + 齿轮图标 vector，没有新增依赖。

安装到设备：

```powershell
adb install -r "C:\Users\艾莉丝喵\Desktop\tv\CloudMusicTV\app\build\outputs\apk\debug\app-debug.apk"
```

> **历史命令，当前目标 TCL 电视不可用**：`ro.feature.packageinstaller_install_control=true` 会拦截 adb 安装；
> 实际安装通道见第 14 节。这里保留旧命令只为记录。

---

## 5. 验证命令

```powershell
. ..\.toolchain\env.ps1
$bt = "$env:ANDROID_HOME\build-tools\34.0.0"
$apk = ".\app\build\outputs\apk\debug\app-debug.apk"

& "$bt\aapt2.exe" dump badging $apk
& "$bt\aapt2.exe" dump permissions $apk
& "$bt\apksigner.bat" verify --print-certs $apk
```

实测结果：

- `package: name='com.cloudmusic.tv' versionCode='1' versionName='0.1.0' compileSdkVersion='34'`
- `sdkVersion:'26'` / `targetSdkVersion:'34'`
- `uses-permission: name='android.permission.INTERNET'`
- `uses-permission: name='com.cloudmusic.tv.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'`（AndroidX 默认注入，见 7.3）
- `launchable-activity: name='com.cloudmusic.tv.MainActivity'`
- `leanback-launchable-activity: name='com.cloudmusic.tv.MainActivity'`（桌面图标靠它）
- `apksigner` → `Signer #1 certificate DN: C=US, O=Android, CN=Android Debug`，退出码 0

---

## 6. 模块结构

```
CloudMusicTV/
├─ settings.gradle.kts          # 4 个模块 + 仓库声明（FAIL_ON_PROJECT_REPOS）
├─ build.gradle.kts             # 只声明插件，不 apply
├─ gradle.properties            # -Xmx2g、AndroidX、overridePathCheck
├─ local.properties             # sdk.dir（\u 转义，见 7.2）
├─ gradle/libs.versions.toml    # 版本目录，所有版本号集中在这里
├─ gradle/wrapper/              # Gradle 8.9 wrapper
├─ app/                         # 壳、导航、主题、各页面
├─ core-network/                # 网络与加解密（已完成）
├─ core-data/                   # 仓库与存储（登录态 + 设置已落地）
└─ core-player/                 # 播放核心：ExoPlayer / MediaSession / 队列 / 歌词 / 地址降级
```

依赖方向：`app → core-data → core-network`，`app → core-player → core-data`，
`app → core-player → core-network`。`core-*` 之间没有环，UI 层只允许依赖 core 模块暴露的接口。
播放核心的接口契约见 `core-player/README.md`。

### 文件清单

| 模块 | 文件 | 内容 |
| --- | --- | --- |
| `app` | `MainActivity.kt` | 单 Activity 宿主，只负责 `setContent { CloudMusicTvTheme { Surface { CloudMusicApp() } } }` |
| `app` | `ui/CloudMusicApp.kt` | 根状态机（LOGIN / MAIN / SETTINGS）+ 四个 tab 的演示数据 + 退出登录的 cookie 清理 |
| `app` | `ui/nav/TopNavBar.kt` | 顶部导航：4 个 tab + 右侧齿轮入口 |
| `app` | `ui/login/LoginScreen.kt` | 全屏 WebView 二维码登录 + 500ms cookie 轮询 + 5 分钟超时 + 5 类兜底 |
| `app` | `ui/settings/SettingsScreen.kt` | 设置页：播放 / 外观 / 存储 / 账号 / 关于 |
| `app` | `ui/home/HomeScreen.kt` | `TileRowScreen`：标题 + 一行可聚焦卡片，四个 tab 复用 |
| `app` | `ui/components/TvButton.kt` | D-pad 按钮（无 ripple，焦点描边 + 放大） |
| `app` | `ui/theme/Theme.kt` | tv-material `MaterialTheme` + 深色配色 + 共享尺寸常量 |
| `core-data` | `SessionStore.kt` | 登录 cookie 的 SharedPreferences 读写 + 账号掩码 |
| `core-data` | `SettingsStore.kt` | 设置的 SharedPreferences 读写 |
| `core-data` | `DataPlaceholders.kt` | `MusicRepository` 等接口（仍为占位） |
| `core-network` | `NetworkPlaceholders.kt` | `HttpEngine` / `CryptoProvider` 等接口（仍为占位） |
| `core-player` | `PlaybackQueue.kt` / `LrcParser.kt` / `PlaybackUrlResolver.kt` | 纯 Kotlin 队列、LRC 解析、地址降级（JVM 单测） |
| `core-player` | `MusicPlayer.kt` / `QueuePlayer.kt` / `AudioFocusController.kt` | ExoPlayer 封装、外部队列、音频焦点、`StateFlow` 状态 |
| `core-player` | `PlaybackService.kt` / `PlaybackRuntime.kt` | `MediaSessionService`、通知渠道、进程内单例与释放 |

---

## 7. 页面、焦点与返回键

### 7.1 状态机

`CloudMusicApp` 里一个 `enum class RootScreen { LOGIN, MAIN, SETTINGS }` + `enum class MainTab`，
用 `rememberSaveable` 保存。**没有引入 Navigation-Compose**（本轮不需要）。

`MainTab` 顺序即导航顺序：首页 / 每日推荐 / 私人 FM / 我的音乐。

### 7.2 返回键

| 页面 | 行为 | 实现 |
| --- | --- | --- |
| 登录页 | 退出应用 | 不注册 `BackHandler` → Activity 默认 `finish()` |
| 首页（及另外三个 tab） | 退出应用 | 同上 |
| 设置页 | 回首页 | `BackHandler { root = MAIN }` |
| 播放页 | 未定义 | 还没做 |

### 7.3 焦点策略

TV 上「焦点丢了」等于应用卡死，所以每个页面都必须有可聚焦元素：

- **主界面**：`TopNavBar` 进入组合时用 `FocusRequester` + `withFrameNanos { }` 落到第一个 tab。
  这样从登录成功、或从设置页返回，焦点一定落在「首页」上。
- **tab 切换不会丢焦点**：切换动作本身就是「在导航项上按确认」，焦点始终留在导航项上。
- **四个 tab 都有可聚焦卡片**：`TileRowScreen` 每页至少 3 张卡。如果某页没有可聚焦元素，焦点会掉到根节点。
- **设置页**：进入时落到第一个可操作项（「唱片旋转」开关）；`LazyColumn` 负责把焦点项滚进可视区。
  信息行（音质 / 缓存 / 账号 / 版本）**刻意不可聚焦**——TV 上让焦点停在静态文本上是坏体验。
- **登录页的错误 / 超时页**：「重试」按钮进入组合即自动聚焦。

卡片焦点态（沿用上一轮规格，视觉常量集中在 `CloudMusicDimens`）：

```kotlin
scale  = CardDefaults.scale(scale = 1f, focusedScale = 1.05f)   // 1.05 倍放大
border = CardDefaults.border(
    border = Border.None,
    focusedBorder = Border(BorderStroke(width = 2.dp, color = Color.White), shape = TileShape),
)                                                                // 2dp 白描边
```

导航项：选中 → 底部 3dp `#EC4141` 红条；被聚焦但未选中 → 白色 3dp 条 + 半透明白底。
未选中文字 `#C7BFC4`。导航高 88dp，页面左右安全边距 64dp。

---

## 8. 登录（WebView 二维码）

**不自己实现登录协议**，全部交给网易云的网页：

1. 全屏 WebView 打开 `https://music.163.com/login`；
2. 开 JS / DOM storage / database，`CookieManager.setAcceptThirdPartyCookies(webView, true)`；
3. 二维码由页面自己生成，用户手机扫码确认；
4. 每 **500ms** 轮询 `CookieManager.getInstance().getCookie("https://music.163.com")`，
   出现 `MUSIC_U=` 即判定成功；
5. 成功后把整串 cookie 写进 SharedPreferences（`SessionStore`），销毁 WebView，进首页；
6. **5 分钟**超时 → 显示「二维码已过期」+「重试」，重试会重新 `loadUrl` 并重启轮询；
7. 退出登录：清 SharedPreferences + `CookieManager.removeAllCookies()` + `flush()`，回登录页。

### 8.1 User-Agent：固定桌面 Chrome UA

`music.163.com/login` 对 WebView 默认 UA（带 `; wv`、移动端形态）很可能返回移动版页面，
那样可能压根不出二维码，而是引导下载 App / 手机号登录。所以加载前写死一条桌面 Chrome UA：

```kotlin
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Safari/537.36"

webView.settings.apply { userAgentString = DESKTOP_USER_AGENT }   // 必须在 loadUrl() 之前
```

**不要拼接 `WebSettings.getDefaultUserAgent()`** —— 那样会把 `; wv` 和移动端 token 带回来。

### 8.2 登录等待期间屏幕常亮

电视的休眠策略比手机激进得多：5 分钟等扫码期间一旦黑屏，用户必须重新扫码。
`LoginScreen` 用一个 `DisposableEffect` 成对加/清
`WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON`：

- **在哪加**：`LoginScreen` 进入组合时，对 `context.findActivity()?.window` 调 `addFlags`
- **在哪清**：同一个 `DisposableEffect` 的 `onDispose`（登录成功 / 退出 / 离开登录页都会触发）
- **为什么不用 onResume/onPause**：成对放在 DisposableEffect 里才能保证「离开即清」，不会漏到别的页面
- **怎么保证不覆盖别人**：进入前先读 `window.attributes.flags` 记住 `alreadySetBeforeEnter`；
  只有原本**没置位**才在 dispose 时 `clearFlags`，原本已置位就保持不动。
  以后播放页也要用这个 flag 时不会被登录页踩掉。

### 8.3 页面状态日志（排障用，长期保留）

`onPageFinished` 时通过 `evaluateJavascript` 把三样东西打到 logcat（TAG = `LoginScreen`）：

```
I/LoginScreen: onPageFinished url=...
I/LoginScreen:   location.href="..."
I/LoginScreen:   document.title="..."
I/LoginScreen:   viewport=1920x1080 dpr=2.0
```

真机上第一次跑的时候，靠这几行就能判断页面到底渲染成了什么（登录页 / 被重定向 / 桌面版被挤到看不见的地方）。
`viewport` 是额外加的，不属于最初需求——它**就是下一个怀疑点**。

### 8.4 WebView 兜底（本轮无法真机验证，但路径可审计）

`createLoginWebView()`（`LoginScreen.kt`）把所有「WebView 不可用」的情况变成异常，
外层 `runCatching` 转成 `WebViewFailure`，渲染成全屏错误页（错误码 + 重试按钮），
**不会白屏、也不会崩进程**。

| 触发条件 | 代码位置 | 错误码 |
| --- | --- | --- |
| `WebView.getCurrentWebViewPackage()` 返回 `null`（ROM 根本没装 WebView） | `createLoginWebView` 第一行 | `WEBVIEW_INIT_FAILED` |
| WebView provider 包被系统禁用（`applicationInfo.enabled == false`） | 同上，第二段 | `WEBVIEW_INIT_FAILED` |
| `WebView(context)` 构造抛异常（provider 损坏 / so 缺失 / ClassNotFound） | `runCatching` 包住整个工厂 | `WEBVIEW_INIT_FAILED` |
| 主框架加载失败（离线 / DNS / TLS） | `WebViewClient.onReceivedError` 且 `request.isForMainFrame` | `PAGE_LOAD_FAILED(<errCode>)` |
| 渲染进程被杀 / 崩溃 | `WebViewClient.onRenderProcessGone` 返回 `true`（阻止系统连坐杀进程） | `RENDER_PROCESS_GONE` |

> **未验证**：以上五条路径都没有在真机或模拟器上实际触发过（本机无设备、无 system-images）。
> 目前只有「能编译、类在 dex 里」这一层证据。
>
> 同样未验证：桌面 UA 之后登录页到底渲染成什么样、`FLAG_KEEP_SCREEN_ON` 在真实电视上的表现。
> 如果 UA 改了还是不显示二维码，**下一个怀疑点是视口宽度**：电视 WebView 的 density 通常 ~2.0，
> 1920 物理宽的屏在桌面版 CSS 里只有 ~960 CSS px，登录卡片可能被挤出可视化区域。
> 先看 8.3 里那行 `viewport=` 日志确认，再考虑 `setInitialScale` 或注入 viewport meta 修正。

---

## 9. 设置页

单列 + 分类小标题，值全部从 `SettingsStore`（SharedPreferences）读：

| 分类 | 行 | 交互 |
| --- | --- | --- |
| 播放 | 默认音质：极高 | 只读展示 |
| 外观 | 唱片旋转 开/关 | 可聚焦，切换即写入 SharedPreferences |
| 外观 | 背景取色 开/关 | 同上 |
| 存储 | 缓存：0 B | 只读展示 |
| 存储 | 清理缓存 | 可聚焦，占位（把缓存计数写回 0） |
| 账号 | 当前账号：`MUSIC_U ****xxxx` | 只读展示 |
| 账号 | 退出登录 | 可聚焦，清 cookie 回登录页 |
| 关于 | 版本 0.1.0 | 只读展示，取自 `BuildConfig.VERSION_NAME` |

「当前账号」目前只能显示掩码后的 `MUSIC_U`——真实昵称需要调用户信息接口，
`core-network` 还没实现，**这是刻意的占位**。

---

## 10. 关键配置，以及为什么这么写

### 10.1 `android.overridePathCheck=true`

工程路径里带中文用户名。AGP 在 apply 阶段会直接抛：

```
Caused by: org.gradle.api.tasks.StopExecutionException: Your project path contains non-ASCII
characters. This will most likely cause the build to fail on Windows. Please move your project
to a different directory. See http://b.android.com/95744 for details. This warning can be
disabled by adding the line 'android.overridePathCheck=true' to gradle.properties file in the
project directory.
```

工程位置是需求指定的，不能挪，所以用 AGP 自己文档里给的开关关掉这个检查。
副作用：每次构建会打印 `WARNING: The option setting 'android.overridePathCheck=true' is experimental.`，无实际影响。

### 10.2 `local.properties` 里的 `\u` 转义

`local.properties` 是 `java.util.Properties` 读的（ISO-8859-1 + `\uXXXX`），直接写中文路径会被解成乱码，
构建报 `SDK location not found`。所以写成：

```properties
sdk.dir=C:/Users/\u827E\u8389\u4E1D\u55B5/Desktop/tv/.toolchain/android-sdk
```

（`艾`=827E、`莉`=8389、`丝`=4E1D、`喵`=55B5。）

### 10.3 权限：保留 AndroidX 默认行为

`androidx.core:core-ktx` 通过 manifest 合并注入一条 signature 级的**应用自签名权限**
`com.cloudmusic.tv.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`。

**这条权限是保留的**（曾用 `tools:node="remove"` 删过，后来回退了）：
- 它不占运行时权限、不弹窗、不影响安装；
- 删掉是偏离上游默认行为，以后任何库在 API ≤ 32 上调
  `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` 会变成极难查的崩溃点。

所以 `aapt2 dump permissions` 里有两条 uses-permission 是**预期结果**，不是配置错误。

### 10.4 Gradle 配置

- `org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8`
  堆大小按需求固定 2g；`-Dfile.encoding=UTF-8` 是为了中文路径 / 中文日志不乱码。
- `android.useAndroidX=true`、`android.enableJetifier=false`
- `android.nonTransitiveRClass=true`
- Compose 走 `buildFeatures { compose = true }` + Kotlin 2.x 的 `plugin.compose`，**不需要** `kotlinCompilerExtensionVersion`。
- `buildFeatures { buildConfig = true }`：设置页的「版本」行用 `BuildConfig.VERSION_NAME`，
  避免手写版本号与 `versionName` 不同步。

### 10.5 Manifest 的两个要点

- `LAUNCHER` 和 `LEANBACK_LAUNCHER` 各写在一个独立 `<intent-filter>` 里，两个都要有。
  目标机没有 GMS、不是认证 Android TV，少了 `LEANBACK_LAUNCHER` 可能桌面不显示图标。
- `android:banner` / `android:icon` 目前是纯色 vector 占位。

---

## 11. 哪些是占位，下一步填什么

| 位置 | 现在 | 下一步 |
| --- | --- | --- |
| 播放页 | **完全没有** | M1 的下一块 |
| `app/ui/home` 四个 tab | 3 张写死的纯色卡片，中文文案硬编码 | 接 `MusicRepository`；文案进 `strings.xml` |
| `core-network` | 只有接口，`BASE_URL` 是 `music.example.invalid` | OkHttp/Ktor + kotlinx-serialization；`CryptoProvider` 真正实现 |
| `core-data` | `MusicRepository` 仍是接口 | 接真实首页接口；列表数据缓存 |
| `core-player` | 已实现 ExoPlayer + MediaSessionService + 队列/歌词/地址降级；真机播放未验证 | 下一轮播放页 UI 接入（契约见 `core-player/README.md`） |
| 设置项 | 开关能写进 SharedPreferences，但没人消费 | 唱片旋转接到播放页；背景取色接到图片取色 |
| 当前账号 | 只显示 `MUSIC_U ****xxxx` 掩码 | 调用户信息接口拿昵称/头像 |
| 清理缓存 | 占位（把计数写回 0） | 接真实缓存目录统计与清理 |
| 登录 | WebView + cookie 轮询 + 桌面 UA + 常亮 + 页面状态日志 | 真机验证；若仍无二维码，查 8.3 的 `viewport=` 日志 |
| 主题 | 只有深色配色 | 补 Typography / Shapes；按 4K 安全区复核留白 |
| 图标 / banner | 纯色 vector 占位 | 换真图；banner 建议 320×180 PNG；图标补 `roundIcon` |
| 签名 | debug 签名 | 配 release keystore，开 R8 |
| 测试 | 无 | 至少补焦点遍历的 `androidTest` |

---

## 12. 踩坑记录（都已在仓库里修掉）

1. **AGP 拒绝中文路径** → `android.overridePathCheck=true`（见 10.1）。
2. **`local.properties` 中文路径乱码** → `\uXXXX` 转义（见 10.2）。
3. **`WebView.getCurrentWebViewPackage()` 不接收 Context**：
   最初写成 `WebView.getCurrentWebViewPackage(context)`，Kotlin 直接报
   `e: LoginScreen.kt:292:53 Too many arguments for 'static fun getCurrentWebViewPackage(): PackageInfo?'`。
   这个静态方法就是无参的，改成 `WebView.getCurrentWebViewPackage()`。
4. **aapt2 的良性告警**：
   `W aapt2: AssetsProvider.cpp:114 Failed to stat file '.../platforms/android-34/android.jar': No such file or directory`，
   路径里的中文在控制台显示成乱码。资源链接实际成功（`aapt2 dump resources` 2876 行，图标/banner/字符串都在），未做处理。
5. **`libandroidx.graphics.path.so` 无法 strip** → 本机没装 NDK，AGP 原样打包，仅告警。
6. **wrapper 首次运行会再下一份 8.9** → 正常，`gradlew.bat` 与手动装的 Gradle 是两个副本。
7. Kotlin 源码放在 `src/main/java/`（AGP 默认 source set），不是 `src/main/kotlin/`，避免
   source set 注册差异导致「编译成功但类没进包」这种静默失败。

---

## 13. 构建

### 13.1 环境准备

每个新 PowerShell 会话先执行（不写系统环境变量）：

```powershell
cd C:\Users\艾莉丝喵\Desktop\tv
. .\.toolchain\env.ps1
cd .\CloudMusicTV
```

脚本会设置当前会话的 `JAVA_HOME` / `ANDROID_HOME`，并把 JDK 17、Android SDK、
Gradle 8.9、adb 等工具加入 `PATH`。

### 13.2 命令

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
.\gradlew.bat :app:assembleRelease --console=plain

# 一起构建
.\gradlew.bat :app:assembleDebug :app:assembleRelease --console=plain

# Release 的 lint 门禁
.\gradlew.bat :app:lintRelease --console=plain
```

Release 逻辑：

- `isMinifyEnabled = true` + `isShrinkResources = true`；
- 存在 `keystore.properties` 时使用真实 release keystore；不存在时退回 debug key；
- `keystore.properties` 与 keystore 文件都不要提交；
- kotlinx.serialization 的 keep 规则已在 `app/proguard-rules.pro` 中，新增 FM DTO
  的 serializer 在 R8 mapping 中可见。

### 13.3 产物路径与当前大小

| 产物 | 路径 | 大小 |
|---|---|---:|
| debug APK | `app\build\outputs\apk\debug\app-debug.apk` | **13,410,280 字节** |
| release APK | `app\build\outputs\apk\release\app-release.apk` | **2,444,225 字节** |
| debug AAR | `<module>\build\outputs\aar\<module>-debug.aar` | — |
| release mapping | `app\build\outputs\mapping\release\mapping.txt` | — |

最近一次本机结果：

```text
:app:assembleDebug :app:assembleRelease
BUILD SUCCESSFUL in 41s
247 actionable tasks: 14 executed, 233 up-to-date
```

本机没有新增运行时依赖；`player` 采用懒创建，登录页/无播放的 MAIN 不实例化 ExoPlayer。

---

## 14. 安装（TCL 包校验限制）

### 14.1 为什么 `adb install` 不可用

目标 TCL / 雷鸟电视的包安装器带：

```text
ro.feature.packageinstaller_install_control=true
```

即使 `adb devices` 能看到 `192.168.1.83:5555`，`adb install -r ...` 也会被系统安装器拦截。
这不是 APK 签名或架构问题，是厂商安装策略。

### 14.2 实际安装通道

1. 手机端「超级大屏」小程序 → 应用安装 → 选择 APK；或
2. APK 拷到 U 盘 → 插电视 → 打开「安全卫士」→「应用管理」→「应用安装」→ 选择 U 盘里的 APK。

### 14.3 用 debug 还是 release

- **联调/首轮真机验证：先装 `app-debug.apk`**。保留完整日志与调试信息，便于抓
  ExoPlayer、焦点、WebView 的问题。
- **功能确认后的验收/交付：装 `app-release.apk`**。R8 已开启、资源已压缩、体积最小，
  当前用真实 release keystore 签名。

两个 APK 的路径见 13.3。安装后首次启动如果没有 cookie，会进入登录页；扫码登录成功后
cookie 写入 `cloudmusic_session` SharedPreferences。

> 以上安装路径来自目标设备的既有使用方式，**本轮没有实际执行安装**（见 15.5）。

---

## 15. 验证（116 个测试）

### 15.1 测试构成

当前四个模块共 **116** 个 JVM/Robolectric 测试：

| 模块 | 数量 | 明细 |
|---|---:|---|
| `core-network` | 32 | 18 个 `NeteaseClientTest` + 5 个 `WeapiCryptoTest` + 9 个 `NeteaseLiveTest` |
| `core-data` | 26 | 13 个 `MusicStateTest` + 9 个 `HomeFmStateTest` + 4 个 `LikedIdsCacheTest` |
| `core-player` | 30 | 11 个 `LrcParserTest` + 12 个 `PlaybackQueueTest` + 7 个 `PlaybackUrlResolverTest` |
| `app` | 28 | 3 个 `MainActivityLaunchTest` + 25 个 `PlayerLogicTest` |
| **合计** | **116** | 全部为 JVM；`MainActivityLaunchTest` 跑在 Robolectric 里 |

`MainActivityLaunchTest` 覆盖：未登录首帧、已登录首帧、登录页 WebView provider 路径。
未登录和“已登录但无播放动作”两个场景都断言 `PlaybackRuntime.isCreated() == false`，
即首帧没有创建 ExoPlayer。

### 15.2 全量测试命令

先按 13.1 配环境，然后在 `CloudMusicTV` 目录执行。

全量 116 个测试（包含 9 个真实网络测试）：

```powershell
.\gradlew.bat :core-network:testDebugUnitTest :core-data:testDebugUnitTest :core-player:testDebugUnitTest :app:testDebugUnitTest -DprobeCookie="C:\Users\艾莉丝喵\Desktop\tv\tools\probe\cookie.json" --console=plain
```

说明：

- `-DprobeCookie` 指向 `tools/probe/cookie.json`；`NeteaseLiveTest` 会用它连真实
  `music.163.com`，不打印完整 cookie；
- **不传 `-DprobeCookie` 时**，9 个 live 测试通过 JUnit `Assume` 跳过，不是失败；
- 真实网络测试会动账号：like 用例会恢复原收藏状态；**dislike 用例会把当次 FM 首曲真正
  丢进垃圾桶，不可撤销**。不想动账号时不要带 cookie 跑。
- XML 结果在 `<module>\build\test-results\testDebugUnitTest\*.xml`。

只跑 app（含 Robolectric 启动测试）：

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
```

只跑 3 个启动场景：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.cloudmusic.tv.MainActivityLaunchTest"
```

### 15.3 Lint

```powershell
.\gradlew.bat :app:lintRelease --console=plain
```

最近一次结果：**0 errors，7 warnings**，`BUILD SUCCESSFUL`。

保留的 warning 及原因：

| warning | 处理 |
|---|---|
| `VectorRaster` banner 320dp | **保留 320×180dp**：这是 Android TV 桌面 banner 的标准尺寸，lint 的 200×200 规则面向普通图标，不适用于 TV banner。 |
| `GradleDependency` coroutines 1.7.3→1.8.1（两条） | **不升级 catalog**。当前 app 实际解析已经是 1.8.1（AndroidX Lifecycle / media3 传递），catalog 的 1.7.3 只是 core-data API 的声明；为了不牵动 Compose/media3 传递依赖保持现状。 |
| `DiscouragedApi` 固定横屏 | **保留**：这是 TV 应用，产品要求固定 landscape。 |
| `SetJavaScriptEnabled` | **保留**：WebView 登录网易云网页必须启用 JS；页面 URL 固定为 `https://music.163.com/login`。 |
| `ObsoleteSdkInt` `mipmap-anydpi-v26` | **保留 v26**。实测把 adaptive-icon XML 移到 `mipmap-anydpi` 会导致 `resource mipmap/ic_launcher not found`，AAPT2 链接失败；已改回并加 `<monochrome>`（API 33 主题图标）。 |
| `VectorPath` `ic_settings.xml` 904 字符 | 本轮未改既有图标；后续可用 Android Studio 的 Vector Asset 工具简化路径。 |

本轮已消除的 warning：`ModifierParameter` ×2、`MonochromeLauncherIcon`。

### 15.4 尚未在真机验证

TCL 包安装限制导致 **App 没有在真机或模拟器上运行过**。以下均未验证：

1. 登录页真实 WebView / 二维码渲染、5 分钟超时与保持常亮。
2. 首页、每日推荐、我的音乐、歌单详情、播放页在 4K/1080p 电视上的布局与 D-pad 焦点。
3. `PlaybackRuntime` 懒创建在真机上的启动时间/内存收益，以及 Service 与 UI 并发创建时的单例接管。
4. 播放、FM 自动推进、通知栏/媒体键下一首、FM「不喜欢」的真实行为。
5. 播放页收藏初始心形、成功更新缓存、失败回滚的视觉表现。
6. Coil 封面加载、360dp 请求、64MB 内存缓存/128MB 磁盘缓存及 2GB 内存压力。
7. Release APK 在真实设备上的 R8 运行态（仅验证了构建、mapping 与 apksigner）。
8. 真实网络测试虽然连了 `music.163.com`，但运行环境是开发机 JVM，不是电视。

---

## 16. 真机首轮记录（v0.2.1）

### 16.1 安装通道（已验证可行）

`adb install` 被 TCL 的受控安装器拦死（`INSTALL_FAILED_VERIFICATION_FAILURE`），U 盘通道也未用；**实际可行的是手机「超级大屏」小程序**：把 APK 传到手机 → 小程序里推送到电视 → 电视端确认安装。已用该通道装过 debug 包（versionCode 2）。

### 16.2 首轮实测到的现象

| 现象 | 原因 | 处理 |
|---|---|---|
| 冷启动约 4.6 秒 | 启动即创建 WebView 并加载网易云登录页 | 登录是一次性成本，后续进入 MAIN 不再加载网页 |
| 登录页长得像浏览器 | WebView 加载了整个桌面站（顶栏/推荐/页脚） | v0.2.1 注入 CSS 只保留登录卡片 |
| 「正在加载登录页…」永久盖住页面 | SPA 路由再次触发 `onPageStarted`，把 `pageLoaded` 重置 | v0.2.1 首次 `onPageFinished` 后永久关闭 + 20 秒兜底 |
| 桌面横幅是一块纯色 | 占位 banner 没有应用名（违反 TV-BN） | v0.2.1 换成 640×360 的 PNG |

### 16.3 首轮已确认的事实

- 设备可通过 ADB 连接（`192.168.1.83:5555`），scrcpy 可远程操作
- 系统 WebView 存在：`com.android.webview 119.0.6045.141`，全用户启用、多进程
- 应用能安装、能启动，`KEEP_SCREEN_ON` 在登录页生效（`dumpsys window` 已确认）
- 网页确实会跳到 `https://music.163.com/#/login`，`onPageFinished` 约 5.5 秒

### 16.4 仍未验证

- v0.2.1 的注入是否返回 `OK`、卡片视觉是否如预期
- 扫码后能否拿到 `MUSIC_U`（登录是否真正完成）
- 各页面焦点、播放出声、FM、收藏、通知 / 媒体键
- 2GB 内存下的长时播放与封面缓存压力

验证入口：`tools\verify-device.ps1`（截图 + logcat + 注入结果一并落盘）。
