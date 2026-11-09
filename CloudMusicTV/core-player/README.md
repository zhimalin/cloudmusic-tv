# core-player — 播放核心

ExoPlayer 播放、MediaSession 后台播放、队列、LRC 歌词解析、播放地址降级。
**本轮不做播放页 UI**；UI 只通过下面第 2 节的契约接入。

> **验证状态**：JVM 单测与 `:app:assembleDebug` 已实际跑通；
> **真机播放未验证**（本机无 Android TV / 模拟器），详见第 7 节。

---

## 1. 文件清单

| 文件 | 作用 |
| --- | --- |
| `Track.kt` | 播放层曲目模型 `Track(id, title, artist, album, coverUrl, durationMs)` + `Song.toTrack()` |
| `PlaybackQueue.kt` | 纯 Kotlin 队列：当前下标、顺序/随机/单曲循环、随机历史栈 |
| `LrcParser.kt` | 纯 Kotlin LRC 解析：多时间戳、翻译合并、offset、非法行 |
| `PlaybackUrlResolver.kt` | 调 `NeteaseClient.songUrl`，按档位降级，标记试听 |
| `PlaybackState.kt` | `PlaybackStatus` / `PlaybackState` / `PlaybackError`（保留 ExoPlayer `errorCodeName`） |
| `PlaybackQuality.kt` | 设置页文案（「极高」等）→ `SongLevel` |
| `AudioFocusController.kt` | `AudioFocusRequest` 封装：打断暂停、GAIN 后按需续播 |
| `QueuePlayer.kt` | `ForwardingPlayer`：把通知/媒体键的上一首/下一首转发到外部队列 |
| `MusicPlayer.kt` | ExoPlayer 封装 + 队列 + 地址解析 + 状态流 + 释放 |
| `PlaybackRuntime.kt` | 进程内唯一 `MusicPlayer`（UI 与 Service 共用） |
| `PlaybackService.kt` | `MediaSessionService`、默认媒体通知、通知渠道、生命周期释放 |
| `src/main/AndroidManifest.xml` | 前台服务类型/权限声明（merge 进 app） |
| `src/main/res/values/strings.xml` | 通知渠道名称/描述 |

---

## 2. 给下一轮播放页 UI 的接口契约

### 2.1 创建 / 获取播放器

```kotlin
// 1) 在 Activity 处于前台时启动后台 Service（点歌/点播放时调用，可重复调用）
PlaybackService.start(context)

// 2) 获取进程内唯一播放器；UI 和 Service 拿到的是同一个实例
val player: MusicPlayer = PlaybackRuntime.player(context)
```

- 两个方法都必须在**主线程**调用。
- `PlaybackRuntime.player()` 第一次调用会创建 ExoPlayer；Service 尚未启动也可以创建，但**没有后台通知**，所以 UI 要先 `PlaybackService.start()`。
- Service 被 `onTaskRemoved` / `onDestroy` 释放后，旧 `MusicPlayer` 已不可用；重新调用 `PlaybackRuntime.player(context)` 会拿到新实例。

### 2.2 传队列 / 切歌

```kotlin
fun MusicPlayer.play(track: Track)                          // 单曲（队列变成只有这一首）
fun MusicPlayer.play(tracks: List<Track>, startIndex: Int = 0) // 整队播放

fun MusicPlayer.pause()
fun MusicPlayer.resume()
fun MusicPlayer.toggle()
fun MusicPlayer.next()       // 手动下一首，总是开始播放
fun MusicPlayer.previous()   // 手动上一首，总是开始播放
fun MusicPlayer.seekTo(positionMs: Long)
fun MusicPlayer.setMode(mode: PlaybackMode) // SEQUENTIAL / SHUFFLE / SINGLE_REPEAT
```

- `play(tracks, startIndex)` 会替换队列并重新解析地址；`startIndex` 越界会被夹到合法范围。
- `next()` / `previous()` 在顺序和单曲循环模式下尾部/首部都会回绕；随机模式见第 3 节。
- 空列表调用 `play` 会打 `Log.w` 并忽略，不抛异常。

### 2.3 订阅状态

```kotlin
val state: StateFlow<PlaybackState> = player.state
```

```kotlin
data class PlaybackState(
    val track: Track?,
    val status: PlaybackStatus,       // IDLE / BUFFERING / PLAYING / PAUSED / ENDED / ERROR
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val bufferedPositionMs: Long,
    val bufferedPercent: Int,
    val mode: PlaybackMode,
    val queueIndex: Int,
    val queueSize: Int,
    val isTrialOnly: Boolean,         // UI 显示「试听」
    val level: String?,               // 实际档位：standard/higher/exhigh/lossless
    val error: PlaybackError?,       // 失败时非 null
)
```

### 2.4 错误码

```kotlin
data class PlaybackError(
    val errorCodeName: String,  // ExoPlayer 的 PlaybackException.errorCodeName 原样保留
    val errorCode: Int?,        // ExoPlayer 整型错误码 / HTTP 状态码 / API code
    val message: String?,
    val causeClass: String?,
)
```

- ExoPlayer 失败：`errorCodeName` 就是 `ERROR_CODE_IO_BAD_HTTP_STATUS` 等原值。
- 地址解析失败：`URL_RESOLVE_FAILED` 或 `URL_RESOLVE_API_<code>` / `URL_RESOLVE_HTTP_<status>` / `URL_RESOLVE_TRANSPORT` / `URL_RESOLVE_PARSE`。
- 音频焦点被拒：`AUDIO_FOCUS_DENIED`。

### 2.5 释放

**UI 不要调用 `player.release()`**。生命周期归 Service：

- 用户划掉任务：`PlaybackService.onTaskRemoved` → `release()` 并 `stopSelf()`；
- Service 销毁：`onDestroy` → `release()`（幂等，不会重复释放 ExoPlayer）。

需要显式停止时用：

```kotlin
PlaybackService.stop(context)
```

### 2.6 线程 / 生命周期

- `MusicPlayer` 所有公开方法只允许主线程。
- `state` 可以从任意线程 `collect`。
- 核心层不持有 bitmap；封面只有 `coverUrl` 字符串。

---

## 3. 队列行为（`PlaybackQueueTest` 写死）

| 场景 | 顺序 `SEQUENTIAL` | 随机 `SHUFFLE` | 单曲循环 `SINGLE_REPEAT` |
| --- | --- | --- | --- |
| 自动播完 `next(manual=false)` | 下一首；队尾返回 `null`（停止） | 历史栈有“未来”就沿历史前进，否则随机未播曲目 | 当前曲重播 |
| 手动下一首 `next(manual=true)` | 下一首；队尾回绕第一首 | 丢弃“未来”历史后随机未播曲目 | 下一首；队尾回绕第一首 |
| `previous()` | 上一首；首部回绕最后一首 | 历史栈回退；栈底返回 `null`（播放器重播当前曲） | 上一首；首部回绕最后一首 |
| 清空队列 | 当前曲 null、下标 -1、next/previous null | 同左 | 同左 |

随机模式的额外约定：

- `setQueue` / `setMode(SHUFFLE)` 把当前曲压入历史栈作为一轮起点；
- 一轮内不重复；一轮播完自动开新一轮，新一轮第一首不会等于上一轮最后一首（队列 >1）；
- 手动下一首和自动下一首的**唯一差异**：如果用户先按过上一首，自动播完会沿历史向前回放，手动下一首则丢弃“未来”重新随机；
- 一轮结束时清空历史栈，防止长时间播放时历史无限增长。

---

## 4. LRC 解析（`LrcParserTest` 写死）

- 支持 `[mm:ss]` / `[mm:ss.x]` / `[mm:ss.xx]` / `[mm:ss.xxx]`；
- 一行多个时间标签 → 产出多行相同文本；
- 翻译按**时间戳完全相等**合并；
- `[offset:+500]` 加到所有时间戳上（正值歌词延后），负数最小夹到 0；翻译没有自己的 offset 时继承原文 offset；
- 空行、`[ti:]`/`[ar:]`/`[al:]`/`[by:]`/`[length:]`、非法行跳过；
- 只有时间戳没有文本的行保留为 `text = ""`（前奏/间奏空档）；
- 输出按 `timeMs` 升序，相同时间戳保持文件顺序（稳定排序）。

```kotlin
data class LrcLine(val timeMs: Long, val text: String, val translation: String? = null)

val lines = LrcParser.parse(lyric.text, lyric.translation)
// 或 LrcParser.parse(lyric)
```

---

## 5. 播放地址降级

`PlaybackUrlResolver.resolve(songId, preferred)`：

1. 按 `lossless → exhigh → higher → standard` 从目标档位开始向下试；
2. `SongUrl == null` / `url` 为空 / `isTrialOnly` 都算“这一档拿不到”，继续下一档；
3. 拿到完整地址立即返回，`usedFallback = resolvedLevel != requestedLevel`；
4. 所有档位都只有试听 → 返回最高档的试听地址，`isTrialOnly = true`；
5. 全部为空 → `PlaybackUrlException(songId, requestedLevel, attempts)`，`attempts` 带每一档的原因（`MISSING` / `EMPTY_URL` / `TRIAL_ONLY`）。

**异常策略**：`NeteaseClient.songUrl` 抛出的 `NeteaseException` 不捕获、不降级，直接向上抛；
`MusicPlayer` 把它写进 `state.error` 并打完整 `Log.e`，不会把网络失败伪装成“没有版权”。

---

## 6. Android 集成

### 6.1 Manifest（由 core-player 合并进 app）

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<service
    android:name=".PlaybackService"
    android:exported="true"
    android:foregroundServiceType="mediaPlayback">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService" />
        <action android:name="android.media.browse.MediaBrowserService" />
    </intent-filter>
</service>
```

- `POST_NOTIFICATIONS` 只声明；运行时申请在 app 层（`CloudMusicApp.kt` 在首次点歌/整队播放时发起）。
- 通知渠道 `cloudmusic_playback` 在 `PlaybackService.onCreate` 里显式创建；
  通知本身用 media3 `DefaultMediaNotificationProvider`（系统默认媒体样式，没有自绘布局）。

### 6.2 音频焦点

- `AudioFocusRequest(AUDIOFOCUS_GAIN)`，`willPauseWhenDucked = true`；
- 任何 `LOSS` / `LOSS_TRANSIENT` / `CAN_DUCK` 都先暂停；
- 只有“丢失前确实在播”且是 `TRANSIENT` 丢失，`GAIN` 后才自动续播；
- 用户自己按暂停会 `abandonAudioFocusRequest`，不会被系统“帮”恢复；
- ExoPlayer 自带焦点处理关闭（`setAudioAttributes(attrs, false)`），只有一套焦点逻辑。

### 6.3 内存 / 释放

- 整个进程只有一个 `MusicPlayer` / ExoPlayer；
- 单曲模式：ExoPlayer 永远只有一个 `MediaItem`，不把整队 URL 预解析进内存；
- `release()`：取消地址解析协程、取消 500ms 进度 ticker、`exoPlayer.release()`、归还音频焦点；
- `onTaskRemoved` 和 `onDestroy` 都会调用 `release()`，重复调用幂等；
- 核心层不缓存 bitmap（`Track` 只有 `coverUrl`）。

### 6.4 通知 / 媒体键

- `QueuePlayer` 把 `COMMAND_SEEK_TO_NEXT(_MEDIA_ITEM)` / `..._PREVIOUS...` 声明为可用，
  并转发到 `MusicPlayer.next()/previous()`；
- 因此通知栏、系统媒体面板、遥控器/蓝牙媒体键的上一首/下一首能真正切换外部队列，
  而不是只在 ExoPlayer 的单条 MediaItem 内找“上一首”。

---

## 7. 验证记录

### 7.1 单测

```powershell
. C:\Users\艾莉丝喵\Desktop\tv\.toolchain\env.ps1
cd C:\Users\艾莉丝喵\Desktop\tv\CloudMusicTV
.\gradlew.bat :core-player:testDebugUnitTest --console=plain
```

真实结果（`core-player/build/test-results/testDebugUnitTest/*.xml`）：

| 测试类 | tests | failures | errors | skipped | time |
| --- | ---: | ---: | ---: | ---: | ---: |
| `LrcParserTest` | 11 | 0 | 0 | 0 | 0.058s |
| `PlaybackQueueTest` | 12 | 0 | 0 | 0 | 0.021s |
| `PlaybackUrlResolverTest` | 7 | 0 | 0 | 0 | 0.868s |
| **合计** | **30** | **0** | **0** | **0** | **0.947s** |

`BUILD SUCCESSFUL`。

### 7.2 app 构建

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
```

- `BUILD SUCCESSFUL`
- APK：`app/build/outputs/apk/debug/app-debug.apk`
- 大小：**13,019,551 字节（12.42 MiB）**
- 本轮开始时基线：9,524,675 字节（9.08 MiB）
- 增量：**+3,494,876 字节（+3.33 MiB，+36.7%）**

增量来自 media3-exoplayer/session 1.4.1 及其传递依赖（media3-common/datasource/extractor、
Guava 33.0.0-android 等）。media3 1.4.1 的 `aar-metadata.properties` 是 `minCompileSdk=34`，
与工程 compileSdk 34 对齐；没有引入 GMS/play-services。

### 7.3 打包产物检查

`aapt2 dump permissions` / `dump xmltree` 实测：

- `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK`、`POST_NOTIFICATIONS`、`WAKE_LOCK` 均在；
- `PlaybackService` 在 manifest 中：`exported=true`、`foregroundServiceType=0x00000002`（mediaPlayback）；
- intent-filter 含 `androidx.media3.session.MediaSessionService` 与 `android.media.browse.MediaBrowserService`；
- 额外出现 `ACCESS_NETWORK_STATE`：media3 传输层需要，正常。

---

## 8. 未验证项（只有真机才能验）

以下全部**未在真机验证**（本机没有 Android TV 设备，也没有 system-image）：

1. 网易云 CDN 的音频 URL 在雷鸟鹏 7 Plus 上能否直接播放（URL 时效、403/地域、重定向）；
2. ExoPlayer 对 `mp3` / `flac` 的实际解码与 gapless/切歌表现；
3. 音频焦点与电视系统声音、其他播放器、HDMI-CEC 的真实交互；
4. Android 14 `mediaPlayback` 前台服务、`POST_NOTIFICATIONS` 弹窗在 TV 桌面上的实际行为；
5. 通知栏默认媒体样式、遥控器媒体键/播放暂停键/上一首/下一首的实际可用性；
6. 关屏后 `WAKE_LOCK` + `WAKE_MODE_NETWORK` 是否真的继续播放；
7. 2GB RAM 设备上长时间播放/频繁切歌的内存曲线（核心层不缓存 bitmap，但 ExoPlayer 解码器与通知封面仍有内存开销）；
8. `onTaskRemoved` 划掉任务后 Service 释放、再次进入 App 重新获取播放器的完整链路；
9. 歌词 offset 与真实歌曲的逐行对齐（不同歌曲的 LRC 质量差异很大）；
10. 地址解析失败（断网/版权下架）时通知栏与 UI 错误展示的实际效果。
