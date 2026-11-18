#!/bin/bash
# 电视端最小入口：编译 / 安装 / 启动 / 推歌。
# 电视地址一律由 TV_HOST 传入，仓库里不写死任何内网地址。
# 第二个参数写 live 就走定制版，否则是开源版：
#   ./karaoke.sh build live
#   TV_HOST=<电视IP>:5555 ./karaoke.sh install live
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
TV="${TV_HOST:-}"
ADB="${ADB_BIN:-/opt/homebrew/bin/adb}"
PKG="com.xinmin.karaoke"
ACT="$PKG/.KaraokeActivity"
FLAVOR="${2:-core}"
case "$FLAVOR" in
    core) APK="$HERE/karaoke.apk";      BUILD_ARGS="" ;;
    live) APK="$HERE/karaoke-live.apk"; BUILD_ARGS="--flavor live" ;;
    *) echo "flavor 只能是 core 或 live（默认 core）" >&2; exit 1 ;;
esac

need_tv() {
  [ -n "$TV" ] || {
    echo "先设置 TV_HOST，例如：TV_HOST=<电视IP>:5555 ./karaoke.sh $1 $FLAVOR"
    exit 1
  }
}

case "${1:-}" in
  build)
    # shellcheck disable=SC2086
    bash "$HERE/build.sh" $BUILD_ARGS
    ;;
  install)
    need_tv install
    # shellcheck disable=SC2086
    bash "$HERE/build.sh" $BUILD_ARGS >/dev/null
    "$ADB" connect "$TV" >/dev/null
    "$ADB" -s "$TV" install -r "$APK" | tail -1
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
    echo "用法: ./karaoke.sh build|install|start [core|live] | push 歌曲.mp3 ..."
    echo "需要连电视的子命令请先设置 TV_HOST=<电视IP>:5555"
    exit 1
    ;;
esac
