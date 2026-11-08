#!/bin/bash
# 为 Android 4.0.4 / API 15 构建电视端离线点歌台 APK。
# 仓库自带 debug.keystore，保证 adb install -r 不换签名；仅用于自娱自乐，
# 正式发布请换成自己的密钥（这把钥匙是公开的）。
# 双轨混音用 JLayer（LGPL）纯 Java 解 MP3，见 THIRD_PARTY_NOTICES.md。
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
# 下面 4 个路径都可以用环境变量覆盖，见 README「自己编译」一节
JH="${KARAOKE_JAVA_HOME:-/opt/homebrew/rea-engines/jdk-21.jdk/Contents/Home}"
BT="${ANDROID_BUILD_TOOLS:-/opt/homebrew/opt/android-legacy/android-9}"
PLAT="${ANDROID_JAR:-/opt/homebrew/opt/android-legacy/android-4.0.4/android.jar}"
AAPT2="${AAPT2_BIN:-/opt/homebrew/bin/aapt2}"
APKSIGNER="${BT}/lib/apksigner.jar"
OUT="${HERE}/karaoke.apk"
KS="${HERE}/debug.keystore"
JLAYER="${HERE}/lib/jlayer-1.0.1.4.jar"
RES="${HERE}/lib/res"

[ -f "${JLAYER}" ] || { echo "缺少 ${JLAYER}（JLayer 解码库）"; exit 1; }

echo "[1/5] 编译 Java（含 JLayer）"
rm -rf "${HERE}/build"
mkdir -p "${HERE}/build/classes"
"${JH}/bin/javac" --release 8 -nowarn -encoding UTF-8 -cp "${PLAT}:${JLAYER}" \
    -d "${HERE}/build/classes" \
    "${HERE}/src/com/xinmin/karaoke/KaraokeActivity.java" \
    "${HERE}/src/com/xinmin/karaoke/MixEngine.java"

echo "[2/5] 生成 classes.dex（合入 JLayer）"
"${JH}/bin/java" -cp "${BT}/lib/dx.jar" com.android.dx.command.Main \
    --dex --min-sdk-version=15 --output="${HERE}/build/classes.dex" \
    "${HERE}/build/classes" "${JLAYER}"

echo "[3/5] 编译启动图标并打包 Manifest + 资源"
# 图标由 make_icon.swift 生成到 res/drawable-*；aapt2 先编译成 .flat 再 link，
# Manifest 里的 @drawable/ic_launcher 才能解析成真正的资源 ID。
mkdir -p "${HERE}/build/compiled"
"${AAPT2}" compile --dir "${HERE}/res" -o "${HERE}/build/compiled"
"${AAPT2}" link -o "${HERE}/build/base.apk" -I "${PLAT}" \
    --manifest "${HERE}/AndroidManifest.xml" \
    --min-sdk-version 15 --target-sdk-version 15 \
    --no-compile-sdk-metadata \
    "${HERE}"/build/compiled/*.flat

echo "[4/5] 合入 dex 与 JLayer 系数表"
/usr/bin/python3 - "${HERE}/build/base.apk" "${HERE}/build/classes.dex" "${RES}" <<'PY'
import sys, zipfile, os
apk, dex, res = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
    # dx 只搬 class，JLayer 的滤波器系数表是 .ser 资源，必须手动补进 APK
    for n in ('sfd.ser', 'l3reorder.ser', 'lin2au.ser', 'au2lin.ser'):
        z.write(os.path.join(res, 'javazoom', 'jl', 'decoder', n),
                'javazoom/jl/decoder/' + n)
print('   APK 条目:', zipfile.ZipFile(apk).namelist())
PY
cp "${HERE}/build/base.apk" "${HERE}/build/unsigned.apk"

echo "[5/5] v1 签名（兼容 Android 4.0.4）"
"${JH}/bin/java" -jar "${APKSIGNER}" sign \
    --ks "${KS}" --ks-key-alias tvcast \
    --ks-pass pass:tvcast123 --key-pass pass:tvcast123 \
    --min-sdk-version 15 \
    --v1-signing-enabled true --v2-signing-enabled false \
    --out "${OUT}" "${HERE}/build/unsigned.apk"
"${JH}/bin/java" -jar "${APKSIGNER}" verify --min-sdk-version 15 --verbose "${OUT}" | head -8

echo
echo "完成: ${OUT}  ($(stat -f%z "${OUT}") bytes)"
