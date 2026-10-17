#!/bin/bash
# Builds Bits2VideoPlayable.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Separate package name (app.bits2video.playable) and signing key from the original
# Bits2Video app, so both install side by side on the same device.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-/home/user/android-sdk}
BT=$SDK/build-tools/36.0.0
JAR=$SDK/platforms/android-36/android.jar
rm -rf build && mkdir -p build/gen build/classes build/dex
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -I $JAR --manifest AndroidManifest.xml -o build/base.apk --java build/gen \
    --min-sdk-version 24 --target-sdk-version 36 build/res.zip
javac --release 11 -classpath $JAR -encoding UTF-8 -d build/classes \
    $(find src build/gen -name '*.java')
$BT/d8 --release --min-api 24 --lib $JAR --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unaligned.apk
(cd build/dex && zip -q ../unaligned.apk classes.dex)
$BT/zipalign -f -p 4 build/unaligned.apk build/aligned.apk
[ -f release.keystore ] || keytool -genkeypair -keystore release.keystore -storepass bits2video \
    -alias b2vplayable -keypass bits2video -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=Bits2Video Playable"
$BT/apksigner sign --ks release.keystore --ks-pass pass:bits2video --ks-key-alias b2vplayable \
    --out Bits2VideoPlayable.apk build/aligned.apk
$BT/apksigner verify --print-certs Bits2VideoPlayable.apk | head -3
ls -l Bits2VideoPlayable.apk
