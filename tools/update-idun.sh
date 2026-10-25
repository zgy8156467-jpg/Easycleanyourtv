#!/usr/bin/env bash
# 小米 HyperOS 3（MFFU0 这类）装不了 APK：系统安装器在安卓 14 上会崩，应用内更新只能把包存到「下载」。
# 这个脚本走 adb：查 /update-idun.json、比版本、下载、安装。
# 用法: tools/update-idun.sh [电视IP]      默认 10.212.108.152
set -euo pipefail

HOST="${1:-${TV_HOST:-10.212.108.152}}"
BASE=http://114.55.39.150
PKG=com.thunderstone.idun.dev
ADB="${ADB:-adb}"

command -v "$ADB" >/dev/null 2>&1 || {
    echo "找不到 adb。用 ADB=/Users/rock/android-sdk/platform-tools/adb 指定路径。"
    exit 1
}

here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

echo "查更新清单 ..."
curl -fsS -m 10 "$BASE/update-idun.json" -o "$tmp/update.json"
code=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("versionCode",0))' "$tmp/update.json")
name=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("versionName",""))' "$tmp/update.json")
[ "$code" -gt 0 ] 2>/dev/null || { echo "清单里没有 versionCode，服务器那边还没发布 idun 版本？"; exit 1; }

host="${HOST%%:*}:5555"
"$ADB" connect "$host" >/dev/null 2>&1 || true
installed=$("$ADB" -s "$host" shell dumpsys package "$PKG" 2>/dev/null |
    grep -m1 versionCode | sed 's/.*versionCode=\([0-9]*\).*/\1/' | tr -d '\r')
echo "电视上 ${installed:-没装}，服务器 ${code}（${name}）"
if [ -n "$installed" ] && [ "$installed" -ge "$code" ]; then
    echo "已经是最新，不用装。"
    exit 0
fi

echo "下载 idun-$code.apk ..."
curl -fsS -m 180 "$BASE/apk/idun-$code.apk" -o "$tmp/idun-$code.apk"

# 复用现成的连接/报错处理：它只把 HOST 补成 :5555 后 adb install -r。
sh "$here/install-tvhome.sh" "$tmp/idun-$code.apk" "$host"
