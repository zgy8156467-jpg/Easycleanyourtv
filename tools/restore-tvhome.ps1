# 一键恢复原厂桌面：关掉接管、撤销授权、卸载「电视桌面」。系统本身没被改过，卸载完原厂桌面自动回来。
# 用法: restore-tvhome.bat [电视IP]   或在 PowerShell 里: .\restore-tvhome.ps1 192.168.1.23
param([string]$TvHost = $env:TV_HOST)
$pkg = 'com.gathin.tvhome'

if (-not $TvHost) { $TvHost = Read-Host '电视 IP（电视 设置 → 关于 → 网络信息 里看）' }
if ($TvHost -notmatch ':') { $TvHost = "${TvHost}:5555" }

$adb = $env:ADB
if (-not $adb) {
    if (Get-Command adb -ErrorAction SilentlyContinue) { $adb = 'adb' }
    elseif (Test-Path (Join-Path $PSScriptRoot 'adb.exe')) { $adb = Join-Path $PSScriptRoot 'adb.exe' }
    elseif (Test-Path (Join-Path $PSScriptRoot 'platform-tools\adb.exe')) { $adb = Join-Path $PSScriptRoot 'platform-tools\adb.exe' }
}
if (-not $adb) {
    Write-Host '找不到 adb。把 platform-tools 放到脚本旁边，或加进 PATH。' -ForegroundColor Red
    exit 1
}

function Adb([string]$Cmd) {
    & $adb -s $TvHost shell $Cmd 2>$null
}

Write-Host "连接 $TvHost ..."
& $adb connect $TvHost | Out-Null
$ready = $false
for ($i = 0; $i -lt 15; $i++) {
    if ((Adb 'echo ok') -match 'ok') { $ready = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $ready) {
    Write-Host '连不上电视。确认电视已打开 ADB 调试（设置 → 账号与安全），IP 没写错。' -ForegroundColor Red
    exit 1
}

Write-Host '1/4 关闭无障碍接管'
$cur = ((Adb 'settings get secure enabled_accessibility_services') -join '').Trim()
$rest = @($cur -split ':' | Where-Object { $_ -and $_ -ne 'null' -and $_ -notlike "$pkg/*" })
if ($rest.Count -eq 0) {
    Adb 'settings delete secure enabled_accessibility_services' | Out-Null
    Adb 'settings put secure accessibility_enabled 0' | Out-Null
} else {
    Adb ('settings put secure enabled_accessibility_services ' + ($rest -join ':')) | Out-Null
}

Write-Host '2/4 撤销授权'
Adb "appops set $pkg GET_USAGE_STATS deny" | Out-Null
Adb "appops set $pkg WRITE_SETTINGS deny" | Out-Null
$sc = ((Adb 'settings get secure screensaver_components') -join '')
if ($sc -like "*$pkg*") { Adb 'settings delete secure screensaver_components' | Out-Null }

Write-Host '3/4 恢复原厂桌面'
Adb 'pm enable com.mitv.tvhome' | Out-Null

Write-Host '4/4 卸载电视桌面'
& $adb -s $TvHost shell pm uninstall $pkg 2>&1 | ForEach-Object { Write-Host $_ }

Write-Host ''
Write-Host '完成。重启电视就回到原厂。' -ForegroundColor Green
