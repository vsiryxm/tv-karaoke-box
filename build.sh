#!/bin/bash
# 为 Android 4.0.4 / API 15 构建电视端离线 K 歌机 APK（单主干双 flavor）。
#
#   ./build.sh                     开源版 → karaoke.apk
#   ./build.sh --flavor live      定制版 → karaoke-live.apk
#
# 唯一真源是 src/.../KaraokeActivity.java。开源版在**编译之前**由 flavor.py
# 把所有 LIVE 标记块整块剥离，因此开源 APK 的 dex 里没有一行演出模式代码，
# 也没有任何一个演出模式配置键；构建末尾会做客观自检，不通过就失败。
#
# 仓库自带 debug.keystore，保证 adb install -r 不换签名；仅用于自娱自乐，
# 正式发布请换成自己的密钥（这把钥匙是公开的）。
# 4 个构建工具路径都可以用环境变量覆盖，见 README「自己编译」一节。
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
FLAVOR=core
if [ $# -gt 0 ]; then
    if [ "$1" = "--flavor" ] && [ "${2:-}" = "live" ]; then FLAVOR=live; shift 2
    elif [ "$1" = "live" ]; then FLAVOR=live; shift
    else
        echo "用法: ./build.sh [--flavor live]" >&2
        exit 1
    fi
fi
[ $# -eq 0 ] || { echo "多余参数: $*" >&2; exit 1; }

JH="${KARAOKE_JAVA_HOME:-/opt/homebrew/rea-engines/jdk-21.jdk/Contents/Home}"
BT="${ANDROID_BUILD_TOOLS:-/opt/homebrew/opt/android-legacy/android-9}"
PLAT="${ANDROID_JAR:-/opt/homebrew/opt/android-legacy/android-4.0.4/android.jar}"
AAPT2="${AAPT2_BIN:-/opt/homebrew/bin/aapt2}"
APKSIGNER="${BT}/lib/apksigner.jar"
KS="${HERE}/debug.keystore"
SRC="${HERE}/src/com/xinmin/karaoke/KaraokeActivity.java"
BUILD="${HERE}/build"

if [ "$FLAVOR" = "live" ]; then
    OUT="${HERE}/karaoke-live.apk"
    JAVASRC="${SRC}"
else
    OUT="${HERE}/karaoke.apk"
    JAVASRC="${BUILD}/src/com/xinmin/karaoke/KaraokeActivity.java"
fi

echo "=== flavor: ${FLAVOR} → $(basename "$OUT") ==="

echo "[1/7] 生成 flavor 源码、strings.xml 与 assets/karaoke.conf"
rm -rf "${BUILD}"
mkdir -p "${BUILD}/classes" "${BUILD}/assets" "${BUILD}/compiled" \
         "${BUILD}/src/com/xinmin/karaoke" "${HERE}/res/values"
if [ "$FLAVOR" = "core" ]; then
    /usr/bin/python3 "${HERE}/flavor.py" strip "${SRC}" "${JAVASRC}"
else
    /usr/bin/python3 "${HERE}/flavor.py" check "${SRC}"
fi

/usr/bin/python3 - "${FLAVOR}" "${HERE}/karaoke.conf" \
                  "${HERE}/res/values/strings.xml" "${BUILD}/assets/karaoke.conf" <<'PY'
import sys, os
from xml.sax.saxutils import escape

flavor, conf, strings_out, assets_out = sys.argv[1:5]
CORE = ("app_name", "welcome")
LIVE = ("app_name", "welcome", "opening_secs", "opening_hold",
        "live_start", "live_end", "live_end_program",
        "live_start_text", "live_break_text", "live_warn_text", "live_end_text")
DEFAULTS = {
    "core": {"app_name": "K歌台"},
    "live": {"app_name": "K歌台",
             "opening_secs": "30", "opening_hold": "0",
             "live_start": "18:30", "live_end": "20:00",
             "live_start_text": "直播开始啦", "live_break_text": "中场休息一下",
             "live_warn_text": "还有 %d 个节目", "live_end_text": "直播结束啦"},
}
# 开源版不读任何本地 conf：连台名都用通用默认值，确保私人信息不会混进开源产物。
kv = dict(DEFAULTS[flavor])
if flavor == "live" and os.path.isfile(conf):
    for line in open(conf, encoding="utf-8-sig"):
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        k = k.strip().lower()
        v = v.strip().strip('"').strip("'").strip()
        if k in LIVE and v:
            kv[k] = v

whitelist = CORE if flavor == "core" else LIVE
with open(strings_out, "w", encoding="utf-8") as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- 由 build.sh 生成，勿手改；两个 flavor 的台名在这里定 -->\n'
            '<resources>\n'
            '    <string name="app_name">%s</string>\n'
            '</resources>\n' % escape(kv.get("app_name", "K歌台"), {'"': '&quot;'}))

with open(assets_out, "w", encoding="utf-8", newline="\n") as f:
    f.write("# 由 build.sh 生成（flavor=%s，只收白名单键）；U 盘根目录同名文件可覆盖\n" % flavor)
    for k in whitelist:
        if k in kv:
            f.write("%s=%s\n" % (k, kv[k]))

print("   台名=%s，assets 写入 %d 个键：%s" % (
    kv.get("app_name"), sum(1 for k in whitelist if k in kv),
    ", ".join(k for k in whitelist if k in kv)))
PY

echo "[2/7] 编译 Java"
"${JH}/bin/javac" --release 8 -nowarn -encoding UTF-8 -cp "${PLAT}" \
    -d "${BUILD}/classes" "${JAVASRC}"

echo "[3/7] 生成 classes.dex"
"${JH}/bin/java" -cp "${BT}/lib/dx.jar" com.android.dx.command.Main \
    --dex --min-sdk-version=15 --output="${BUILD}/classes.dex" \
    "${BUILD}/classes"

echo "[4/7] 编译图标并打包 Manifest + 资源 + NOTICE"
/bin/cp "${HERE}/NOTICE" "${BUILD}/assets/NOTICE"
"${AAPT2}" compile --dir "${HERE}/res" -o "${BUILD}/compiled"
"${AAPT2}" link -o "${BUILD}/base.apk" -I "${PLAT}" \
    --manifest "${HERE}/AndroidManifest.xml" \
    -A "${BUILD}/assets" \
    --min-sdk-version 15 --target-sdk-version 15 \
    --no-compile-sdk-metadata \
    "${BUILD}"/compiled/*.flat

echo "[5/7] 合入 dex"
/usr/bin/python3 - "${BUILD}/base.apk" "${BUILD}/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print('   APK 条目:', zipfile.ZipFile(apk).namelist())
PY
/bin/cp "${BUILD}/base.apk" "${BUILD}/unsigned.apk"

echo "[6/7] v1 签名（兼容 Android 4.0.4）"
"${JH}/bin/java" -jar "${APKSIGNER}" sign \
    --ks "${KS}" --ks-key-alias tvcast \
    --ks-pass pass:tvcast123 --key-pass pass:tvcast123 \
    --min-sdk-version 15 \
    --v1-signing-enabled true --v2-signing-enabled false \
    --out "${OUT}" "${BUILD}/unsigned.apk"
"${JH}/bin/java" -jar "${APKSIGNER}" verify --min-sdk-version 15 "${OUT}"

echo "[7/7] flavor 隔离自检（dex 字符串 + assets 配置键）"
/usr/bin/python3 - "${FLAVOR}" "${OUT}" <<'PY'
import sys, zipfile
flavor, apk = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(apk)
dex = z.read('classes.dex')
conf = z.read('assets/karaoke.conf').decode('utf-8')

def has(s):
    return s.encode('utf-8') in dex

# 只有定制版才该出现的字符串
private = ('karaoke_program.txt', 'karaoke_openings.txt', 'live_start',
           'live_end', 'programIdx', 'liveMode', 'program_done',
           'live_started_at', '主播辛苦了',
           '直播模式', '节目单', '报幕词', '中场休息', '下播预告')
hits = [s for s in private if has(s)]
if flavor == 'core':
    assert not hits, '开源版 dex 混入演出模式代码: %s' % ', '.join(hits)
    assert not any(k in conf for k in ('live_', 'opening_')), \
        '开源版 assets/karaoke.conf 混入专属键: %s' % conf
    assert has('karaoke_counts'), '开源版核心疑似缺失'
    print('   开源版: 演出模式字符串命中 0，assets 只含通用键 ✓')
else:
    must = ('karaoke_program.txt', 'live_start', '节目单', '下播预告')
    missing = [s for s in must if not has(s)]
    assert not missing, '定制版缺少功能字符串: %s' % ', '.join(missing)
    assert 'live_start=' in conf, '定制版 assets 缺 live_start'
    print('   定制版: %d 个专属字符串全部命中，assets 含直播配置键 ✓' % len(hits))
print('   assets/karaoke.conf:')
for line in conf.strip().splitlines():
    print('     ' + line)
PY

echo
echo "完成: ${OUT}  ($(stat -f%z "${OUT}") bytes, flavor=${FLAVOR})"
