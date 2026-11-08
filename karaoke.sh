#!/bin/bash
# 电视端最小入口：编译 / 安装 / 启动 / 推歌。
# 电视地址一律由 TV_HOST 传入，仓库里不写死任何内网地址。
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
TV="${TV_HOST:-}"
ADB="${ADB_BIN:-/opt/homebrew/bin/adb}"
PKG="com.xinmin.karaoke"
ACT="$PKG/.KaraokeActivity"

need_tv() {
  [ -n "$TV" ] || {
    echo "先设置 TV_HOST，例如：TV_HOST=<电视IP>:5555 ./karaoke.sh $1"
    exit 1
  }
}

case "${1:-}" in
  build)
    bash "$HERE/build.sh"
    ;;
  install)
    need_tv install
    bash "$HERE/build.sh" >/dev/null
    "$ADB" connect "$TV" >/dev/null
    "$ADB" -s "$TV" install -r "$HERE/karaoke.apk" | tail -1
    "$ADB" -s "$TV" shell am force-stop "$PKG" >/dev/null 2>&1 || true
    "$ADB" -s "$TV" shell am start -n "$ACT" | tail -1
    ;;
  start)
    need_tv start
    "$ADB" connect "$TV" >/dev/null
    "$ADB" -s "$TV" shell am start -n "$ACT" | tail -1
    ;;
  push)
    need_tv push
    shift
    [ $# -gt 0 ] || { echo "用法: TV_HOST=<电视IP>:5555 ./karaoke.sh push 歌曲.mp3 [更多.mp3]"; exit 1; }
    "$ADB" -s "$TV" shell 'mkdir -p /mnt/sdcard/karaoke' >/dev/null
    for f in "$@"; do
      "$ADB" -s "$TV" push "$f" /mnt/sdcard/karaoke/ | tail -1
    done
    ;;
  *)
    echo "用法: ./karaoke.sh build | install | start | push 歌曲.mp3 ..."
    echo "需要连电视的子命令请先设置 TV_HOST=<电视IP>:5555"
    exit 1
    ;;
esac
