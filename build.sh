#!/bin/sh
# Build the custom launcher with plain SDK tools. No Gradle, no libraries.
# Needs: ANDROID_HOME set, platform android-28, build-tools 34.
set -e
HERE="$(dirname "$0")"
cd "$HERE"
PKG=home.custom.tv
OUT=out
[ -z "$ANDROID_HOME" ] && { echo "set ANDROID_HOME first"; exit 1; }
P=$ANDROID_HOME/platforms/android-28/android.jar
BT=$ANDROID_HOME/build-tools/34.0.0
rm -rf $OUT
mkdir -p $OUT/classes $OUT/dex $OUT/gen
# 1. resources -> R.java + packaged res
"$BT/aapt" package -f -M AndroidManifest.xml \
  -S res \
  -I "$P" \
  -J $OUT/gen -F $OUT/unsigned.apk
# 2. compile (Java 8 source level: Android 9 device)
javac -source 8 -target 8 -bootclasspath "$P" \
  -d $OUT/classes $(find src $OUT/gen -name '*.java')
# 3. dex
"$BT/d8" --lib "$P" --output $OUT/dex $(find $OUT/classes -name '*.class')
# 4. add dex + align + sign (fresh debug-style key, created once)
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore \
  -alias tvkey -keyalg RSA -keysize 2048 -validity 3650 \
  -storepass tvhome -keypass tvhome -dname "CN=TVHome" 2>/dev/null
cp $OUT/unsigned.apk $OUT/app.apk
(cd $OUT && unzip -o -q app.apk -d apkx && cp dex/classes.dex apkx/classes.dex \
  && rm app.apk && (cd apkx && zip -qr ../app.apk .) && rm -rf apkx)
"$BT/zipalign" -f 4 $OUT/app.apk $OUT/Home.apk
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:tvhome \
  --out $OUT/Home-signed.apk $OUT/Home.apk
echo "BUILT: $OUT/Home-signed.apk"
"$BT/apksigner" verify --print-certs $OUT/Home-signed.apk | head -n 5
