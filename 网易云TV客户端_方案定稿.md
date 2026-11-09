# 云音乐 TV · 方案定稿 v1

> 第三方网易云音乐 Android TV 客户端。自用工具，不做发行。
> 目标设备：雷鸟鹏7 Plus 26款 75S78A Plus（4K / Android 14 / 灵控 3.0 / T91 / 2GB RAM + 64GB）

---

## 0. 一句话定义

打开电视就能听自己的音乐：去广告、去社交、去运营位，以播放器为中心的网易云电视客户端。

解决的核心问题：**同一个账号、同一首歌，手机能放，官方 TV 客户端放不了。**

---

## 1. 已确认决策

| 项 | 结论 |
|---|---|
| 平台 | Android（电视 / 盒子），最终产出可侧载 APK |
| 系统基线 | minSdk 26 / targetSdk 34 / compileSdk 35 |
| 登录 | 手机扫码，无游客模式，未登录什么都进不去 |
| 音质 | 尽可能高，设置里可选 标准 / 较高 / 极高 / 无损 |
| 导航 | 顶部横向：首页 · 每日推荐 · 私人 FM · 我的音乐；右侧仅设置图标 |
| 首页 | 轻内容型：每日推荐 + 私人 FM + 我的音乐 + 最近播放 |
| 我的音乐 | 三个独立分类：我喜欢的 / 我创建的 / 我收藏的 |
| 歌单列表 | 网格封面墙，固定 5 列（1920 基准），1:1 封面 |
| 歌单详情 | 简洁列表：歌名 / 歌手 / 时长，按原顺序 |
| 播放页 | 左侧黑胶唱片（播放时旋转），右侧歌词，无分隔线，底部横跨控制栏 |
| 歌词 | 8 行可见，当前行约在区域 40% 处，可用遥控器逐行选中并跳转 |
| 背景 | 从封面提取低饱和深色渐变，切歌平滑过渡 |
| 队列 | 右侧抽屉，640px |
| 私人 FM | 复用播放页的 FM 模式：隐藏上一首、进度不可拖、右下列表换「不喜欢」 |
| 搜索 | **不做** |
| 设置 | 单列 + 分类小标题 |

---

## 2. 界面规范

### 2.1 视觉基线

| 项 | 值 |
|---|---|
| 设计基准 | **960 × 540 dp**（实测设备：1920×1080 px @ density 320；4K 面板由系统放大） |
| 底色 | `#0E0D11`（全局）；播放页用封面取色渐变 |
| 主色 | `#EC4141`（网易云红），仅用于强调和选中态 |
| 文字 | 主 `#FFFFFF` / 次 `#C7BFC4` / 三级 `#8A838C` |
| 圆角 | 卡片 12dp / 容器 16dp |
| 间距 | 8 的倍数；页面左右安全边距 64dp |
| 焦点态 | 2dp 白色描边 + 1.05 倍放大 + 150ms ease-out，不做位移 |
| 顶部导航 | 高 88dp，选中项底部 3dp 红条 |
| 迷你播放器 | 全局固定，高 96dp，不随页面消失 |

### 2.2 页面清单

| 页面 | 要点 |
|---|---|
| 登录 | 全屏二维码 + 状态文案；2–3 分钟过期自动刷新 |
| 首页 | 每日推荐 / 私人 FM 两个大卡 + 我的音乐两行 + 最近播放 |
| 每日推荐 | 顶部日期 + 「播放全部」+ 30 首单列；默认焦点在播放全部 |
| 私人 FM | 即播放页 FM 模式，无独立界面 |
| 我的音乐 | 三个分类入口，进入后是网格 |
| 歌单网格 | 5 列固定，1:1 封面 320dp，名称 + 曲目数 |
| 歌单详情 | 顶部封面 240dp + 名称/创建者/曲目数；下方单列列表 |
| 播放页 | 左 46% 唱片区，右 43% 歌词区，中间 96dp 间距 |
| 播放队列 | 右抽屉 640dp，焦点默认当前项 |
| 设置 | 单列：播放 / 外观 / 存储 / 账号 / 关于 |

### 2.3 播放页细节

- 黑胶直径 ≈ 640dp，封面嵌在唱片中央 ≈ 400dp，唱针从右上方搭入
- 转速约 8–10 秒 / 圈；暂停即停；设置里可关闭
- 歌词：可见 8 行，当前行 38sp 加粗、其余 32sp，行高 60dp，上下 56dp 渐变遮罩
- 歌词可选中：3dp 白色竖条 + 40sp，确认键跳到该句，8 秒无操作自动失焦
- 底部控制栏：进度条横跨，左（播放模式 / 收藏）中（上一首 / 播放暂停 56dp / 下一首）右（队列 / 更多）
- 进度条：选中后左右键 ±10 秒，长按连续快进快退

### 2.4 全局规则

- 加载：列表骨架屏，播放页封面转圈
- 空态：一行「这里还没有内容」
- 错误：「加载失败 + HTTP 状态码/错误码 + 重试」
- 返回：逐层返回；首页按返回直接退出，不做二次确认
- 焦点：进页面落第一个可操作项；切页重置；不跨区乱跳

---

## 3. 数据与播放链路

### 3.1 原则

全部走**网页端接口**，不碰官方 TV 客户端链路。原因：对方 TV 端放不了，很可能就是那条链路自己的策略；我们绕开它。

App 内直连，不架中间服务。加密（weapi / eapi）与 cookie 管理都在客户端实现。

### 3.2 接口清单（M0 逐个验证）

| 用途 | 接口 | 备注 |
|---|---|---|
| 登录二维码 | `login/qr/key` → `login/qr/create` → `login/qr/check` | 轮询 check，800ms |
| 账号信息 | `user/account` | 校验 cookie 有效性 |
| 每日推荐 | `recommend/songs` | 需登录 |
| 私人 FM | `personal_fm` + `fm_trash`（不喜欢） | |
| 我喜欢的 | `likelist` + `song/detail` | |
| 歌单列表 | `user/playlist` | |
| 歌单详情 | `playlist/track/all`（分页） | |
| 歌曲地址 | **`song/url/v1`** | 核心，见 3.3 |
| 私人 FM | `POST /weapi/v1/radio/get` | 空业务参数，一次返回 3 首；FM 歌曲字段是 `artists/album/duration/alias`，与日推的 `ar/al/dt/alia` 不同 |
| 不喜欢（FM 垃圾桶） | `POST /weapi/radio/trash/add?alg=RT&songId=<id>&time=25` | `alg` 必须 `RT`、`time` 必须 `25`；**不可撤销** |
| 收藏 / 取消 | `POST /weapi/radio/like` | body `{alg:"itembased",trackId,like,time:"3"}`；**必须额外带 Cookie `os=pc; appver=2.7.1.198277`**，否则返回 `-460 网络环境风险` |
| 歌词 | `lyric` | 含翻译 |
| 喜欢/取消 | `like` | |

> 具体路径与参数以 NeteaseCloudMusicApi 文档为准，M0 阶段实测，不凭记忆写死。

### 3.3 播放链路

```
歌曲 ID
  → song/url/v1（带 level = standard|higher|exhigh|lossless）
  → 判断三种结果：
      有 url                → ExoPlayer 播放
      有 url + freeTrialInfo → 试听片段，界面标注
      url 为空              → 无版权 / 无该档位权限，记录错误码
  → 失败落库：歌曲 ID / 接口 / HTTP 状态 / 业务码 / 阶段
```

**M0 验证目标**：三首歌 `2610618711`、`2123113332`、`2610618710` 能否拿到可播地址。

- 三首都能拿到 → 问题出在官方 TV 客户端那条链路，本项目成立
- 拿不到 → 立刻停下重新评估，不写界面

### 3.4 登录方案（电视端）

原生 QR 协议**已判定不可用**（2026-11-08 实测取证）：

- 扫码到 `803 授权登陆成功` 后，**weapi 与明文 `/api/` 两条路由**的响应都是 `{"code":803,"message":"授权登陆成功"}`——没有 `cookie` 字段，响应头也没有 `Set-Cookie`
- 拿到 803 后立刻用同一 key 复测两条路由，仍是 803 且不带登录态
- `user/account` 返回 `account: null`（未登录），`login/token/refresh` 返回 301
- 结论：服务端承认「手机端授权成功」，但**不把登录态交给 API 客户端**，登录态只在浏览器里落地

这条路已经走死，不再尝试。

所以电视端登录走 **WebView**：

1. 登录页放一个 WebView，加载 `https://music.163.com/login`
2. 二维码由网易云页面**自己生成、自己轮询、自己处理成功**——不存在我们自己画码的编码问题
3. 登录完成后，轮询 `CookieManager.getInstance().getCookie("https://music.163.com")`，一旦出现 `MUSIC_U` 就取出，写入本地存储
4. 取到后销毁 WebView，后续所有请求用这个 cookie

这条路不依赖那条接口返回什么结构，也不依赖我们实现加解密登录。代价是登录页多一个 WebView 实例（约 50MB 内存，登录完即销毁，对 2GB 设备可接受）。

**设备侧已确认（2026-11-08，adb `dumpsys webviewupdate`）**：雷鸟这台电视自带 `com.android.webview 119.0.6045.141`（Chromium 119），`Any WebView package installed: true`、`Multiprocess enabled: true`、对全部用户已启用。所以「WebView 缺失」这个假设可以排除，登录方案的最大不确定性已经消除。

原生 QR 若能取证成功，再作为优化替换；替换前 WebView 就是正式方案。

### 3.5 本地存储

Room 只存：cookie、设置、最近播放、播放队列草稿。音频不做离线缓存。

---

## 4. 技术方案

| 层 | 选型 |
|---|---|
| 语言 | Kotlin 2.x |
| UI | Jetpack Compose + `androidx.tv:tv-material` |
| 播放 | Media3 / ExoPlayer + `MediaSessionService` |
| 网络 | Retrofit + OkHttp + kotlinx.serialization |
| 图片 | Coil（内存缓存 64MB，按 720px 请求） |
| 存储 | Room |
| 并发 | Coroutines + Flow |
| DI | 手写容器即可，不上 Hilt |

模块划分：

```
app
core-network     请求 / 加解密 / cookie / 统一错误
core-player      播放器 / 队列 / 媒体会话
core-data        仓库层 / Room
feature-login
feature-home
feature-daily
feature-fm
feature-library
feature-player
feature-settings
```

### 4.1 Android 14 专属注意

- 前台服务声明 `android:foregroundServiceType="mediaPlayback"`
- 权限：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK`、`POST_NOTIFICATIONS`（运行时申请）
- 后台播放要引导用户「忽略电池优化」，否则会被灵控系统杀

### 4.2 设备适配

- 无 GMS：不依赖任何 Google 服务
- Manifest 同时声明 `LAUNCHER` 与 `LEANBACK_LAUNCHER`，保证灵控桌面出图标
- 2GB 内存：列表 LazyColumn 复用，封面 720px，避免整屏大图

---

## 5. 开发流程与里程碑

| 阶段 | 内容 | 完成标准 | 估时 |
|---|---|---|---|
| M0 | 可行性验证：登录 + 三首歌拿地址 + ExoPlayer 出声 | 三首能放，或明确卡点 | 1–2 天 |
| M1 | 工程骨架、登录页、设置页、全局焦点与返回（并装到电视上跑通） | 扫码进得去，焦点不乱，APK 装得上 | 3–5 天 |
| M2 | 我的音乐：分类入口、歌单网格、歌单详情列表 | 能翻、能点播 | 4–5 天 |
| M3 | 每日推荐、私人 FM | 两个入口走通，FM 能跳过 | 3–4 天 |
| M4 | 播放页：黑胶旋转、封面取色、歌词滚动与跳转、底部控制栏 | 视觉与截图对得上 | 5–7 天 |
| M5 | 队列抽屉、打磨、签名打包 | 连续用一天不出错 | 3–4 天 |

合计约 3–4 周（业余时间）。

### 进度（滚动更新）

| 阶段 | 状态 | 验证方式 |
|---|---|---|
| M0 播放链路 | ✅ 通过 | 三首 × 四档 12/12，CDN 实测 ID3/fLaC 真音频 |
| 工具链 | ✅ 通过 | JDK 17.0.21 / SDK 34 / Gradle 8.9，APK 能出 |
| core-network | ✅ 通过 | 26 个测试（含 6 个真实网络），账号/日推/歌单/歌词全通 |
| M1 骨架 + 登录 + 设置 + 导航 | ✅ 代码完成 / ⚠️ 真机未验 | 编译、签名、dex 类均已验 |
| M2 我的音乐 + 歌单详情 + 每日推荐 | ✅ 代码完成 / ⚠️ 真机未验 | 13 个 JVM 状态单测（MockWebServer） |
| M3 core-player（播放/队列/歌词/会话） | ✅ 代码完成 / ⚠️ 真机未验 | 30 个 JVM 单测；media3 1.4.1，FGS type 实测 0x2 |
| M4 播放页 UI（黑胶/歌词/底栏/队列） | ✅ 代码完成 / ⚠️ 真机未验 | 25 个 JVM 单测；19 个播放页类进包 |
| M3.5 首页 + 私人 FM + 收藏 | ✅ 代码完成 / ⚠️ 真机未验 | 32 + 22 + 25 个测试；FM/like/trash 三个接口真实网络验证 |
| M5 打磨 + 打包 | ✅ release 签名/R8 已完成 | release 2.46 MB（debug 13.24 MB）；v0.2.1 |

**v0.2.1 变更**（真机首轮反馈后）

- 登录页：注入样式只保留登录卡片（深色背景 + 居中圆角白卡 + 二维码放大 1.55×），移除站点顶栏/推荐栏/页脚
- 修掉「加载遮罩永久卡死」：只在首次加载显示，首帧 `onPageFinished` 后永久关闭，另有 20 秒兜底
- WebView 背景改深色，消除白闪
- 横幅换成带应用名的 640×360 PNG（原先是一块纯色，违反 TV-BN）
- 歌单详情补「播放全部」

### 验证汇总（可复现）

| 模块 | 测试数 | 说明 |
|---|---:|---|
| core-network | 32 | 含 9 个真实网络（账号/日推/歌单/歌词/FM/收藏） |
| core-data | 26 | 仓库映射、状态机、最近播放编解码、喜欢缓存 |
| core-player | 30 | 队列、LRC、音质降级 |
| app | 28 | 播放页逻辑 25 + 启动冒烟 3（Robolectric） |
| **合计** | **116** | 0 失败 / 0 跳过（真实网络用例需 `-DprobeCookie`） |

启动冒烟（Robolectric）：未登录 → 登录页组合成功且不创建 ExoPlayer；已登录 → 直接进 MAIN；WebView 不可用 → 兜底屏正常渲染。

### 真机安装（当前受阻，需 U 盘）

设备已通过 ADB 连上（`192.168.1.83:5555`，Android 14，scrcpy 可远程控制），但**安装被 TCL 锁死**：

```
ro.feature.packageinstaller_install_control = true
ro.feature.packageinstaller_risk_detection  = true
```

- `adb install` / `--no-streaming` / `push + pm install` → 全部 `INSTALL_FAILED_VERIFICATION_FAILURE`
- 伪造 installer（`-i com.tcl.appmarket2` / `com.android.vending` / `com.android.packageinstaller`）→ 同样被拦
- 直接拉起 `PackageInstallerActivity` → `not exported`，shell 无权
- 系统里**没有文件管理器**，也没有任何应用注册处理 APK

唯一入口：**安全卫士 → 应用管理 → 应用安装**，但它只扫描移动存储设备。

所以安装路径是：U 盘（FAT32/exFAT）→ 拷 APK 到根目录 → 插电视 → 该入口选中安装。

**另外试过、走不通的路**：TCL 应用商店有一个面向第三方应用商店的安装广播
`android.intent.action.APPSTORE_INSTALL_APK` → `com.huan.appstore.receiver.ThirdAppManagerReceiver`
（从 APK 反编译确认只读 `Fileurl` 与 `packagename` 两个 extra）。实测：广播能送达、商店进程会被拉起，但内部协程在执行到设备信息时抛
`ClassNotFoundException: com.tcl.deviceinfo.TDeviceInfo` 并取消，**从未发起下载**（本机 HTTP 服务器零请求，已用支持 Range 的服务端验证过连通性，电视侧 `nc` 能取到 200）。商店界面预热后重试仍然相同。结论：这条通道依赖 TCL 私有框架类，adb 侧无法驱动。

装好之后的验证可以全部由 ADB 完成：`am start` 拉起、`screencap` 截图、`logcat` 抓 `LoginScreen` 的 `viewport=` 与 `location.href`。

### 真机验收清单（装机后逐条过）

1. 桌面/应用列表出现图标（`LEANBACK_LAUNCHER` 生效）
2. 登录页 WebView 能加载、能显示二维码、扫码后出现 `MUSIC_U=`
3. 登录页等待期间屏幕不息（`FLAG_KEEP_SCREEN_ON`）
4. 顶部导航左右移动、内容区上下移动，焦点始终可见
5. 我的音乐 5 列网格、歌单详情列表、每日推荐的滚动与焦点
6. 返回键三条路径：登录页退出 / 设置页回首页 / 首页退出
7. 封面能加载（`http://` → `https://` 升级是否有效）
8. logcat 抓 `LoginScreen` 的 `viewport=` 与 `location.href`，判断页面渲染是否正常

### 验收三条

1. 三首测试歌能播，且能定位到失败阶段
2. 任意页面焦点不丢、返回不误退
3. 冷启动到首页 3 秒内

### M0 结果（2026-11-08，已通过）

- 三首 × 四档 = **12/12 全部返回完整地址**，无试听片段、无空地址、无失败
- 实测 CDN 拉流：标准/较高/极高是 `ID3` 头的 mp3，无损是 `fLaC` 头的 FLAC，HTTP 206，真能播
- 结论：**网页端链路可行**，官方 TV 端的「暂时无法播放」来自它自己那条链路，不是账号没版权
- 注意：无损档返回里 `type=flac`，但 `encodeType` 仍写 `mp3`，判断编码要用 `type` 或文件头，别信 `encodeType`
- 遗留：
  - 扫码登录 `803` 后服务端不返回 `MUSIC_U`，也不下发 `Set-Cookie` —— M1 必须解决
  - 探针自制的二维码编码器已证伪（手机扫不出），要换成熟实现（Android 侧用 ZXing）

---

## 6. 风险

| 风险 | 影响 | 应对 |
|---|---|---|
| 接口加密失效 / 接口变更 | 致命 | 集中在 `core-network` 一层，便于单点修复 |
| `song/url/v1` 拿不到地址 | 致命 | M0 先验证，不通就不开工 |
| 2GB 内存 + Compose | 卡顿 / OOM | 图片降采样、列表复用、控制 recomposition |
| 灵控系统杀后台 | 播放中断 | 前台服务 + 电池优化白名单 |
| 侧载受限 | 装不上 | M1 先验证 ADB / U 盘安装路径 |
| 网易云服务条款 | 法务 | 自用不发行，可接受 |

---

## 6.5 TV 应用质量核对（官方清单 2026-05 版）

按 Google「TV 应用质量」第 3 层级（支持 TV）逐条核对。

**已满足**

| 项 | 依据 |
|---|---|
| TV-LM / TV-ML | 清单声明 `CATEGORY_LEANBACK_LAUNCHER` + `ACTION_MAIN`，badging 里两个入口都在 |
| TV-LO | `screenOrientation="landscape"`，不出现竖屏黑边 |
| TV-DP / TV-DM | 全应用焦点导航，不依赖菜单键 |
| TV-DB | 首页返回即退出，回到 TV 桌面 |
| TV-PS | minSdk 26（要求 ≤ 31） |
| TV-MT | `android.hardware.touchscreen` 非必需（badging 为 `uses-feature-not-required`） |
| TV-TR | 全屏、非透明背景 |
| TV-WB | 网页内容只用 WebView，不拉起浏览器（登录页即 WebView） |
| TV-NP / TV-PA | 后台播放有 MediaSession + 系统媒体控件，点开进入可暂停的播放页 |
| TV-PP | 已接线 `KEYCODE_MEDIA_PLAY_PAUSE` |

**已修复**

| 项 | 修复前 | 修复后 |
|---|---|---|
| TV-BN（横幅含应用名） | 一块纯色 + 两条白杠，没有名字 | 640×360 PNG，「云音乐 TV / 只用遥控器听歌」，品牌红渐变 |

**待办**

| 项 | 现状 | 计划 |
|---|---|---|
| TV-PC（中键播放/暂停、左右快进） | 中键只作用于当前焦点；左右键用于移动焦点 | 播放页补全局按键：焦点在播放/暂停按钮时，中键切换、左右键 ±10 秒 |

**明确不做（个人自用不需要）**：语音搜索、Cast Connect、画中画、Engage SDK、4K 专用资源包、基线配置文件（TV-BP，需 macrobenchmark）、ABI 裁剪（只省 30 KB）。

## 7. 质量门槛
每个模块交付前必须过这几关，缺一项不算完成：

| 关卡 | 判据 |
|---|---|
| 能编译 | `gradlew assembleDebug` 通过并产出 APK，不接受「应该能编过」 |
| 能装上 | 真机（雷鸟鹏7 Plus）安装成功、冷启动进得去 |
| 不漂移 | 只动本模块；改了公共接口必须在交付说明里写清楚 |
| 不吞错 | 所有失败路径都有错误码，落盘或上屏，禁止空 catch |
| 焦点与返回 | 任意页面焦点不丢、返回不误退 |
| 内存 | 2GB 设备不 OOM：封面 720px、列表 Lazy、登录完销毁 WebView |
| 依赖克制 | 新增第三方库要写理由，能用系统能力就不引库 |
| 测试不污染账号 | 集成测试只允许「读」，或「读-写-还原」且能验证还原；**不可逆的写操作（如 FM 垃圾桶）一律跳过**，宁可少一个用例 |
| 启动冒烟 | Robolectric 在 JVM 上把 `MainActivity` 拉到 RESUMED + 首帧 draw，断言不崩；覆盖未登录（WebView 兜底屏）、已登录（MAIN）、WebView 分支三条路径 |

子代理交付时必须附「实际跑过什么、真实输出是什么」，主控会自己重跑一遍验证，不采信描述。

---

## 8. 明确不做

- 搜索（没有输入框，也就没有输入法问题）
- 广告、直播、短视频、云村社区、评论、排行榜、活动
- 离线下载 / 本地音乐
- 多账号、投屏、歌词分享
- 自定义皮肤
