#!/usr/bin/env bash
# 用电脑通过 ADB 安装/升级「电视桌面」——绕开电视自带安装器的「应用未安装 / 已阻止」。
# 用法: tools/install-tvhome.sh [apk] [电视IP]      不传 apk 就用 dist 里最新的包。
set -euo pipefail

ADB="${ADB:-adb}"
APK="${1:-}"
HOST="${2:-${TV_HOST:-}}"
PKG=com.gathin.tvhome

if [ -z "$APK" ]; then
  here=$(cd "$(dirname "$0")" && pwd)
  APK=$(ls -t "$here"/*.apk dist/*/tvhome-zh-*.apk 2>/dev/null | head -1 || true)
fi
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "找不到 apk。把 apk 路径作为第一个参数：tools/install-tvhome.sh 电视桌面.apk 192.168.1.23"
  exit 1
fi
if ! command -v "$ADB" >/dev/null 2>&1; then
  echo "找不到 adb。先装 platform-tools，或用 ADB=/path/to/adb 指定路径。"
  exit 1
fi
if [ -z "$HOST" ]; then
  printf '电视 IP（电视 设置 → 关于 → 网络信息 里看）: '
  read -r HOST
fi
HOST="${HOST%%:*}:5555"

echo "连接 $HOST ..."
"$ADB" connect "$HOST" >/dev/null 2>&1 || true
ready=
for _ in $(seq 1 15); do
  if [ "$("$ADB" -s "$HOST" shell echo ok 2>/dev/null | tr -d '\r')" = ok ]; then ready=1; break; fi
  sleep 1
done
if [ -z "$ready" ]; then
  echo "连不上电视。确认电视已打开 ADB 调试（设置 → 账号与安全），IP 没写错。"
  exit 1
fi

echo "安装 $APK ..."
out=$("$ADB" -s "$HOST" install -r "$APK" 2>&1 || true)
echo "$out"

case "$out" in
  *Success*) echo "装好了。电视上按 Home，或者在应用列表里打开「电视桌面」。";;
  *UPDATE_INCOMPATIBLE*) echo "电视上已有另一个签名的旧版。到「应用」里卸载电视桌面，再跑一次。";;
  *VERSION_DOWNGRADE*) echo "电视上装的版本更高（beta）。卸载后再装。";;
  *OLDER_SDK*) echo "这台电视系统太老，换 tvhome-zh-*-legacy.apk。";;
  *INSUFFICIENT_STORAGE*) echo "电视存储空间不够，先在设置里清理。";;
  *) echo "安装失败，把上面 Failure 那一行发群里。";;
esac
