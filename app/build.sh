#!/bin/sh
# ============================================================
#  JeTeezNtmax 壳 App · 构建脚本
#  不依赖 Gradle，直接用 SDK 的原始工具链拼一个 APK
#    aapt2 compile/link → javac → d8 → zip → zipalign → apksigner
# ============================================================
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
SDK=/workspace/.sdk
AJAR=$SDK/a25.jar
R8=$SDK/r8.jar
BT=/usr/lib/android-sdk/build-tools/29.0.3
OUT=$HERE/out

[ -f "$AJAR" ] || { echo "缺 android.jar：$AJAR"; exit 1; }
[ -f "$R8" ]   || { echo "缺 r8.jar：$R8"; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/6] 编译资源"
$BT/aapt2 compile --dir "$HERE/res" -o "$OUT/res.zip" >/dev/null

echo "[2/6] link 资源 + 清单"
$BT/aapt2 link \
    -o "$OUT/base.apk" \
    -I "$AJAR" \
    --manifest "$HERE/AndroidManifest.xml" \
    -R "$OUT/res.zip" \
    --java "$OUT/gen" \
    --min-sdk-version 21 \
    --target-sdk-version 30 \
    --version-code 1 --version-name 1.0 \
    --auto-add-overlay

echo "[3/6] javac"
find "$HERE/java" "$OUT/gen" -name '*.java' > "$OUT/srcs.txt"
javac -nowarn -Xlint:-options -source 8 -target 8 \
      -bootclasspath "$AJAR" -cp "$AJAR" \
      -d "$OUT/classes" @"$OUT/srcs.txt"

echo "[4/6] d8 → classes.dex"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -cp "$R8" com.android.tools.r8.D8 \
     --lib "$AJAR" --min-api 21 --release \
     --output "$OUT/dex" @"$OUT/classes.txt" >/dev/null 2>&1

echo "[5/6] 打包"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
python3 - "$OUT" <<'PY'
import zipfile, sys, os, shutil
out = sys.argv[1]
src = os.path.join(out, "unsigned.apk")
dst = os.path.join(out, "packed.apk")
zin = zipfile.ZipFile(src, 'r')
zout = zipfile.ZipFile(dst, 'w', zipfile.ZIP_STORED)
for it in zin.infolist():
    if it.filename == 'classes.dex':
        continue
    zout.writestr(it, zin.read(it.filename))
zin.close()
zout.write(os.path.join(out, "dex", "classes.dex"), "classes.dex")
zout.close()
shutil.move(dst, src)
print("      classes.dex 已写入（未压缩）")
PY

echo "[6/6] 对齐 + 签名"
if [ ! -f "$HERE/debug.keystore" ]; then
    keytool -genkeypair -keystore "$HERE/debug.keystore" -alias jeteez \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -storepass android -keypass android \
        -dname "CN=JeTeeZnTmax, O=JeTeeZnTmax" >/dev/null 2>&1
fi
$BT/zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
$BT/apksigner sign \
    --ks "$HERE/debug.keystore" --ks-pass pass:android --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out "$OUT/JeTeeZnTmax.apk" "$OUT/aligned.apk"

echo
echo "验证："
$BT/apksigner verify -v "$OUT/JeTeeZnTmax.apk" | sed 's/^/  /'
echo
ls -la "$OUT/JeTeeZnTmax.apk"
