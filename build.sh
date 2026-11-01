#!/bin/zsh
# Builds Focus Highlighter without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
set -euo pipefail
cd "${0:A:h}"
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
SDK=${ANDROID_SDK:-/opt/homebrew/share/android-commandlinetools}
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
rm -rf build && mkdir -p build/classes build/gen
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -I $JAR --manifest AndroidManifest.xml --java build/gen \
  --min-sdk-version 30 --target-sdk-version 34 -o build/unsigned.apk build/res.zip
$JAVA_HOME/bin/javac -source 8 -target 8 -bootclasspath $JAR -Xlint:-options \
  -d build/classes $(find src build/gen -name '*.java')
$BT/d8 --lib $JAR --min-api 30 --output build $(find build/classes -name '*.class')
(cd build && zip -q unsigned.apk classes.dex)
$BT/zipalign -f 4 build/unsigned.apk build/aligned.apk
# Signing key for this app only, kept beside the source.
if [[ ! -f focushl.keystore ]]; then
  $JAVA_HOME/bin/keytool -genkeypair -keystore focushl.keystore -alias focushl \
    -keyalg RSA -keysize 2048 -validity 10000 -storepass focushl -keypass focushl \
    -dname "CN=Focus Highlighter" >/dev/null 2>&1
fi
$BT/apksigner sign --ks focushl.keystore --ks-pass pass:focushl --out focus-highlighter.apk build/aligned.apk
echo "built: $(ls -la focus-highlighter.apk | awk '{print $5}') bytes"
