#!/bin/bash
# 自动发现电视 → 备份旧包 → 装定制版（--flavor live）→ 打印客观验收日志。
#
# 电视 IP 由 DHCP 分配、会变，所以扫描网段后用机型验身，绝不写死地址，
# 也不会误装到局域网里别的 Android 设备上。
#
#   ./install_live.sh
#   TV_NET=192.168.2 TV_MODEL=LED50EC280JD ./install_live.sh
#   APK=/path/to/karaoke.apk ./install_live.sh      # 改装开源版也可以
#
# 装完 U 盘要插在电视上；插在电脑上时日志「扫描到 0 首歌」是正常的。
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
APK="${APK:-${HERE}/karaoke-live.apk}"
ADB="${ADB_BIN:-/opt/homebrew/bin/adb}"
NET="${TV_NET:-192.168.2}"
MODEL="${TV_MODEL:-LED50EC280JD}"
PKG="com.xinmin.karaoke"
ACT="$PKG/.KaraokeActivity"

[ -f "$APK" ] || { echo "缺少 $APK，先跑: ./build.sh --flavor live" >&2; exit 1; }

export no_proxy="${NET}.0/24,localhost,127.0.0.1"
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY

# 这台老电视的固件会在每条 shell 命令前吐一行 "kmem_init(L:562) ..." 噪音，
# 且覆盖安装后 apk 路径的 -1/-2 后缀会变，所以解析一律走 grep 取 'package:' 行。
apk_path() {
    "$ADB" -s "$1" shell pm path "$PKG" 2>/dev/null | tr -d '\r' \
        | grep -o 'package:[^[:space:]]*' | head -1 | sed 's/^package://'
}
remote_md5() {
    "$ADB" -s "$1" shell md5sum "$2" 2>/dev/null | tr -d '\r' | awk '{print $1}' | tail -1
}

echo "=== 扫描 ${NET}.1-254:5555 ==="
CANDS=$(/usr/bin/python3 - "$NET" <<'PY'
import socket, concurrent.futures, sys
net = sys.argv[1]
def probe(i):
    ip = "%s.%d" % (net, i)
    s = socket.socket(); s.settimeout(0.35)
    try:
        s.connect((ip, 5555)); return ip
    except Exception:
        return None
    finally:
        s.close()
with concurrent.futures.ThreadPoolExecutor(max_workers=128) as ex:
    hits = [x for x in ex.map(probe, range(1, 255)) if x]
print(" ".join(sorted(hits, key=lambda x: int(x.rsplit('.', 1)[1]))))
PY
)
echo "   候选: ${CANDS:-（无）}"

TV=""
for ip in $CANDS; do
    "$ADB" connect "$ip:5555" >/dev/null 2>&1 || true
    M=$("$ADB" -s "$ip:5555" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)
    echo "   $ip → ${M:-连不上}"
    if [ "$M" = "$MODEL" ]; then TV="$ip:5555"; break; fi
done
[ -n "$TV" ] || { echo "没找到机型 $MODEL：电视可能没开机或没开 ADB 网络调试" >&2; exit 1; }
echo "=== 目标电视: $TV ($MODEL) ==="

echo "=== 备份旧 APK ==="
OLD=$(apk_path "$TV")
BK="/tmp/karaoke_installed_old_$(date +%Y%m%d_%H%M%S).apk"
if [ -n "$OLD" ]; then
    if "$ADB" -s "$TV" pull "$OLD" "$BK" 2>&1 | tail -1 | grep -q .; then
        echo "   旧包: $OLD"
        echo "   备份: $BK ($(stat -f%z "$BK" 2>/dev/null || stat -c%s "$BK") B, md5 $(md5 -q "$BK"))"
    else
        echo "   备份失败（不影响安装）: $OLD"
    fi
else
    echo "   电视上还没装过 $PKG，跳过备份"
fi

echo "=== 安装 ==="
LOCAL_MD5=$(md5 -q "$APK")
BEFORE=$(apk_path "$TV")
BEFORE_MD5=$(remote_md5 "$TV" "$BEFORE")
if [ -n "$BEFORE_MD5" ] && [ "$BEFORE_MD5" = "$LOCAL_MD5" ]; then
    echo "   电视上已是同一版本（md5 $LOCAL_MD5），跳过重装"
else
    "$ADB" -s "$TV" install -r "$APK" 2>&1 | grep -v kmem_init | tail -3
fi
NEW=$(apk_path "$TV")
REMOTE_MD5=$(remote_md5 "$TV" "$NEW")
echo "   装后路径: $NEW"
echo "   装后 md5: $REMOTE_MD5"
echo "   本地 md5: $LOCAL_MD5"
if [ -n "$REMOTE_MD5" ] && [ "$REMOTE_MD5" = "$LOCAL_MD5" ]; then
    echo "   ✓ 一致"
else
    echo "   ✗ 不一致！以本地为准重装" >&2
    "$ADB" -s "$TV" install -r "$APK" 2>&1 | grep -v kmem_init | tail -2
    NEW=$(apk_path "$TV")
    REMOTE_MD5=$(remote_md5 "$TV" "$NEW")
    echo "   重装后 md5: $REMOTE_MD5"
fi

echo "=== 重启并读日志 ==="
"$ADB" -s "$TV" logcat -c 2>/dev/null || true
"$ADB" -s "$TV" shell am force-stop "$PKG" >/dev/null 2>&1 || true
"$ADB" -s "$TV" shell am start -n "$ACT" 2>&1 | tail -2
sleep 4
"$ADB" -s "$TV" logcat -d 2>/dev/null | grep -i "Karaoke" | tail -30 || true
echo "=== 客观验收 ==="
echo "   崩溃/ANR 计数: $("$ADB" -s "$TV" logcat -d 2>/dev/null | grep -c -E 'FATAL EXCEPTION|Force finishing|ANR' || true)"
"$ADB" -s "$TV" shell dumpsys activity 2>/dev/null | grep -E "mResumedActivity|mFocusedActivity" | head -2 || true

echo "=== U 盘状态 ==="
# 这台电视的 U 盘根目录挂载在 /mnt/sdcard（vold/sda1, vfat）。
# 注意：固件会在命令末尾追加一行 kmem_init 噪音，所以不能用 tail -1 取输出，
# 改用 test -f 让电视自己判断，只认 YES / NO。
usb_has() {
    [ "$("$ADB" -s "$TV" shell "test -f '$1' && echo YES" 2>/dev/null | tr -d '\r' | grep -c '^YES$')" -ge 1 ]
}
if usb_has /mnt/sdcard/karaoke_program.txt; then
    echo "   ✓ 电视已读到节目单文件"
else
    echo "   ✗ 电视上读不到 U 盘（/mnt/sdcard/karaoke_program.txt 不存在）"
    echo "     → 把 U 盘从电脑上拔下来插到电视，App 会自动重扫，不用重装"
fi
if usb_has /mnt/sdcard/karaoke_openings.txt; then
    echo "   ✓ 电视已读到报幕词文件"
fi
"$ADB" -s "$TV" logcat -d 2>/dev/null | grep -i "Karaoke" | grep -E "节目单|台词|扫描到" | tail -4

echo
echo "完成。双击遥控器 # 进入直播模式；按 3 看节目单，弹奏节目按 OK 继续。"
