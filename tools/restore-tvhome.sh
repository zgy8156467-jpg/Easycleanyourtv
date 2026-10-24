#!/usr/bin/env bash
# 一键恢复原厂桌面：关掉接管、撤销授权、卸载「电视桌面」。系统本身没被改过，卸载完原厂桌面自动回来。
# 用法: tools/restore-tvhome.sh [电视IP]     或   TV_HOST=192.168.1.23 tools/restore-tvhome.sh
set -euo pipefail

ADB="${ADB:-adb}"
HOST="${1:-${TV_HOST:-}}"
PKG=com.gathin.tvhome

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

run() { "$ADB" -s "$HOST" shell "$@" >/dev/null 2>&1 || true; }

echo "1/4 关闭无障碍接管"
cur=$("$ADB" -s "$HOST" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
rest=$(printf '%s' "$cur" | tr ':' '\n' | grep -v "^$PKG/" | grep -v '^null$' | paste -sd: - || true)
if [ -z "$rest" ]; then
  run settings delete secure enabled_accessibility_services
  run settings put secure accessibility_enabled 0
else
  run settings put secure enabled_accessibility_services "$rest"
fi

echo "2/4 撤销授权"
run appops set "$PKG" GET_USAGE_STATS deny
run appops set "$PKG" WRITE_SETTINGS deny
sc=$("$ADB" -s "$HOST" shell settings get secure screensaver_components 2>/dev/null | tr -d '\r')
case "$sc" in *"$PKG"*) run settings delete secure screensaver_components ;; esac

echo "3/4 恢复原厂桌面"
run pm enable com.mitv.tvhome

echo "4/4 卸载电视桌面"
"$ADB" -s "$HOST" shell pm uninstall "$PKG" 2>&1 | tr -d '\r' || true

echo
echo "完成。重启电视就回到原厂：adb -s $HOST reboot"
