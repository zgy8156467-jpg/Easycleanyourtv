# 用电脑通过 ADB 安装/升级「电视桌面」——绕开电视自带安装器的「应用未安装 / 已阻止」。
# 用法: powershell -File install-tvhome.ps1 [-Apk 路径] [-Host 电视IP]
param(
    [string]$Apk = "",
    [string]$HostName = ""
)
$ErrorActionPreference = "Continue"

if (-not $Apk) {
    $latest = @(
        Get-ChildItem -Path $PSScriptRoot -Filter "*.apk" -ErrorAction SilentlyContinue
        Get-ChildItem -Path (Join-Path $PSScriptRoot "..\dist") -Recurse -Filter "tvhome-zh-*.apk" -ErrorAction SilentlyContinue
    ) | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($latest) { $Apk = $latest.FullName }
}
if (-not $Apk -or -not (Test-Path $Apk)) {
    Write-Host "找不到 apk。用法: install-tvhome.ps1 -Apk 电视桌面.apk -HostName 192.168.1.23"
    exit 1
}
if (-not $HostName) {
    $HostName = Read-Host "电视 IP（设置 → 关于 → 网络信息）"
}
$target = $HostName.Split(":")[0] + ":5555"

Write-Host "连接 $target ..."
adb connect $target | Out-Null
$ready = $false
foreach ($i in 1..15) {
    if ((adb -s $target shell echo ok 2>$null) -match "ok") { $ready = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $ready) {
    Write-Host "连不上电视。确认电视已打开 ADB 调试（设置 → 账号与安全），IP 没写错。"
    exit 1
}

Write-Host "安装 $Apk ..."
$out = (adb -s $target install -r $Apk 2>&1) -join "`n"
Write-Host $out

switch -Wildcard ($out) {
    "*Success*"             { Write-Host "装好了。电视上按 Home，或者在应用列表里打开「电视桌面」。" }
    "*UPDATE_INCOMPATIBLE*" { Write-Host "电视上已有另一个签名的旧版。到「应用」里卸载电视桌面，再跑一次。" }
    "*VERSION_DOWNGRADE*"   { Write-Host "电视上装的版本更高（beta）。卸载后再装。" }
    "*OLDER_SDK*"           { Write-Host "这台电视系统太老，换 tvhome-zh-*-legacy.apk。" }
    "*INSUFFICIENT_STORAGE*"{ Write-Host "电视存储空间不够，先在设置里清理。" }
    default                 { Write-Host "安装失败，把上面 Failure 那一行发群里。" }
}
