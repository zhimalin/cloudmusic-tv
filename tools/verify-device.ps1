<#
.SYNOPSIS
    云音乐 TV · 真机验证采集脚本

.DESCRIPTION
    电视上装好 com.cloudmusic.tv 之后跑这个脚本，它会一次性采集：
      - 设备与 WebView 环境（WebView 是否存在，这是登录方案的前提）
      - 包是否安装、版本、安装来源
      - 冷启动 → 截图 → 前台 Activity
      - 启动期间的 logcat（过滤本应用 + 关键 TAG）
      - 屏幕参数（wm size / wm density，用来核对 960×540dp 设计基准）

    所有产物写到 tools\device-verify\<时间戳>\ 下，便于事后比对。

.PARAMETER Package
    应用包名，默认 com.cloudmusic.tv

.PARAMETER WaitSeconds
    启动后等待多少秒再截图，默认 6 秒（要等 WebView 加载登录页）

.EXAMPLE
    .\tools\verify-device.ps1
#>
[CmdletBinding()]
param(
    [string]$Package = 'com.cloudmusic.tv',
    [int]$WaitSeconds = 6,
    [string]$Adb = "C:\Users\艾莉丝喵\Desktop\tv\.toolchain\android-sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $Adb)) { throw "找不到 adb：$Adb" }

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$out = Join-Path $PSScriptRoot "device-verify\$stamp"
New-Item -ItemType Directory -Force -Path $out | Out-Null

function Save-Text([string]$name, [scriptblock]$block) {
    $path = Join-Path $out $name
    try { & $block 2>&1 | Out-String | Set-Content -LiteralPath $path -Encoding utf8 }
    catch { "采集失败：$($_.Exception.Message)" | Set-Content -LiteralPath $path -Encoding utf8 }
    Write-Host ("  {0,-28} -> {1}" -f $name, $path)
}

Write-Host "== 1. 设备 ==" -ForegroundColor Cyan
$devices = & $Adb devices -l 2>&1 | Out-String
$devices | Write-Host
if ($devices -notmatch '\bdevice\b') { throw "没有在线设备，先确认电视 ADB 已连接（scrcpy 能连说明通了）" }

Save-Text '01-device.txt' {
    & $Adb shell getprop ro.product.model
    & $Adb shell getprop ro.build.version.release
    & $Adb shell getprop ro.build.version.sdk
    & $Adb shell wm size
    & $Adb shell wm density
    & $Adb shell dumpsys webviewupdate
}

Write-Host "== 2. 安装状态 ==" -ForegroundColor Cyan
$installed = (& $Adb shell pm list packages 2>&1) -match [regex]::Escape($Package)
Save-Text '02-package.txt' {
    if ($installed) {
        & $Adb shell dumpsys package $Package | Select-String -Pattern 'versionName|versionCode|installerPackageName|firstInstallTime|enabled='
    } else {
        "未安装：$Package（先用「超级大屏」或 U 盘 → 安全卫士 → 应用管理 → 应用安装 装一次）"
    }
}
if (-not $installed) {
    Write-Host "未安装 $Package，跳过启动与日志采集。" -ForegroundColor Yellow
    Write-Host "产物目录：$out"
    exit 2
}

Write-Host "== 3. 冷启动 ==" -ForegroundColor Cyan
& $Adb shell am force-stop $Package | Out-Null
& $Adb logcat -c | Out-Null
Start-Sleep -Milliseconds 500
& $Adb shell am start -W -n "$Package/.MainActivity" 2>&1 | Write-Host
Start-Sleep -Seconds $WaitSeconds

Write-Host "== 4. 截图 / 前台 / 日志 ==" -ForegroundColor Cyan
$shot = Join-Path $out '03-launch.png'
cmd /c "`"$Adb`" exec-out screencap -p > `"$shot`""
Write-Host ("  {0,-28} -> {1}" -f '03-launch.png', $shot)

Save-Text '04-foreground.txt' {
    & $Adb shell dumpsys activity activities | Select-String -Pattern 'mResumedActivity|topResumedActivity'
}

# 注入在 onPageFinished 之后 300/800/1500/2500ms 重试，等它跑完再截一张，
# 用来对比「注入前」和「注入后」的登录页。
Write-Host "  等待注入完成（再 10 秒）..."
Start-Sleep -Seconds 10
$shot2 = Join-Path $out '03b-after-inject.png'
cmd /c "`"$Adb`" exec-out screencap -p > `"$shot2`""
Write-Host ("  {0,-28} -> {1}" -f '03b-after-inject.png', $shot2)

Save-Text '05-logcat.txt' {
    & $Adb logcat -d -v time | Select-String -Pattern 'cloudmusic|LoginScreen|inject|Playback|ExoPlayer|MediaSession|AndroidRuntime'
}

Save-Text '06-window-flags.txt' {
    # 登录页要求保持屏幕常亮，这里能间接看到 window flags
    & $Adb shell dumpsys window | Select-String -Pattern 'KEEP_SCREEN_ON|mCurrentFocus|mFocusedApp'
}

Save-Text '07-inject.txt' {
    # 单独把注入结果捞出来，方便一眼看出是 OK / NO_WRAPPER / XORIGIN / NO_IFRAME
    & $Adb logcat -d -v time -s LoginScreen:V
}

Write-Host ""
Write-Host "采集完成：$out" -ForegroundColor Green
Write-Host "重点看 07-inject.txt 里 inject -> 的返回值，以及 03b-after-inject.png。"
