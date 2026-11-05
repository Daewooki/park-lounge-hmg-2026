#!/usr/bin/env bash
# Offline build using the installed Android SDK. Gradle is also supported.
# No daemon sockets, network downloads or changes to other projects are required.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:-debug}"
case "$MODE" in debug|release-candidate|release) ;; *) echo 'Usage: scripts/build-apk.sh [debug|release-candidate|release]' >&2; exit 2;; esac
SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
BUILD_TOOLS="${VARCO_BUILD_TOOLS:-$SDK/build-tools/36.0.0}"
PLATFORM="$SDK/platforms/android-34/android.jar"
CAR="$SDK/platforms/android-34/optional/android.car.jar"
if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in "/Applications/Android Studio 2.app/Contents/jbr/Contents/Home" "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
    if [ -x "$candidate/bin/java" ]; then export JAVA_HOME="$candidate"; break; fi
  done
fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"
for tool in aapt2 d8 zipalign apksigner; do
  if [ ! -x "$BUILD_TOOLS/$tool" ]; then echo "Missing Android build tool: $tool" >&2; exit 1; fi
done
if [ "$MODE" != debug ]; then
  python3 "$ROOT/scripts/preflight.py" "--$MODE"
  : "${VARCO_KEYSTORE:?Set the release signing keystore path}"
  : "${VARCO_KEY_ALIAS:?Set the release signing key alias}"
  : "${VARCO_STORE_PASSWORD:?Provide the keystore password through the environment}"
  : "${VARCO_KEY_PASSWORD:?Provide the key password through the environment}"
else
  python3 "$ROOT/scripts/preflight.py"
fi
WORK="$ROOT/.build/$MODE"
# MODE is validated above. Remove only this mode's generated class/DEX trees so
# renamed or deleted Java classes cannot survive into the next APK.
rm -rf -- "$WORK/classes" "$WORK/dex"
mkdir -p "$WORK/classes" "$WORK/dex" "$ROOT/dist"
python3 - "$ROOT" "$WORK" "$MODE" <<'PY'
from pathlib import Path
import re, sys, xml.etree.ElementTree as ET
root, work, mode = map(str, sys.argv[1:])
ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
ns = '{http://schemas.android.com/apk/res/android}'
tree = ET.parse(Path(root)/'app/src/main/AndroidManifest.xml')
manifest = tree.getroot()
gradle = (Path(root)/'app/build.gradle.kts').read_text()
application_ids = re.findall(r'^\s*applicationId\s*=\s*"([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+)"', gradle, re.MULTILINE)
if len(application_ids) != 1:
    raise SystemExit('app/build.gradle.kts must declare exactly one literal applicationId.')
# AGP 8 owns the namespace via Gradle. aapt2 still needs a package in its
# generated temporary manifest; keep the source manifest package-free.
manifest.set('package', application_ids[0])
version_codes = re.findall(r'^\s*versionCode\s*=\s*([1-9][0-9]*)\s*$', gradle, re.MULTILINE)
version_names = re.findall(r'^\s*versionName\s*=\s*"([A-Za-z0-9][A-Za-z0-9._-]*)"\s*$', gradle, re.MULTILINE)
if len(version_codes) != 1 or len(version_names) != 1:
    raise SystemExit('Gradle must declare one literal versionCode and versionName.')
manifest.set(ns+'versionCode', version_codes[0])
manifest.set(ns+'versionName', version_names[0])
(Path(work)/'version-name.txt').write_text(version_names[0])
manifest.find('application').set(ns+'debuggable', 'true' if mode == 'debug' else 'false')
tree.write(Path(work)/'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
sources = sorted((Path(root)/'app/src/main/java').rglob('*.java'))
(Path(work)/'sources.txt').write_text('\n'.join('"'+str(p)+'"' for p in sources)+'\n')
PY
"$BUILD_TOOLS/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$WORK/resources.zip"
"$BUILD_TOOLS/aapt2" link -o "$WORK/resources.apk" --manifest "$WORK/AndroidManifest.xml" \
  -I "$PLATFORM" -A "$ROOT/app/src/main/assets" -0 wav -0 ogg -0 mp3 -0 glb \
  --min-sdk-version 29 --target-sdk-version 34 "$WORK/resources.zip"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$BUILD_TOOLS/core-lambda-stubs.jar:$PLATFORM" \
  -classpath "$CAR:$ROOT/app/libs/zxing-core-3.5.3.jar" -d "$WORK/classes" "@$WORK/sources.txt"
jar cf "$WORK/classes.jar" -C "$WORK/classes" .
"$BUILD_TOOLS/d8" --min-api 29 --lib "$PLATFORM" --lib "$CAR" --output "$WORK/dex" \
  "$WORK/classes.jar" "$ROOT/app/libs/zxing-core-3.5.3.jar"
python3 - "$WORK" <<'PY'
from pathlib import Path
import sys, zipfile, shutil
work=Path(sys.argv[1]); shutil.copyfile(work/'resources.apk',work/'unsigned.apk')
with zipfile.ZipFile(work/'unsigned.apk','a',compression=zipfile.ZIP_DEFLATED) as z:
    for dex in sorted((work/'dex').glob('*.dex')): z.write(dex,dex.name)
PY
"$BUILD_TOOLS/zipalign" -f -p 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
VERSION_NAME="$(cat "$WORK/version-name.txt")"
OUTPUT="$ROOT/dist/park-lounge-$VERSION_NAME-$MODE.apk"
if [ "$MODE" = debug ]; then
  KEYSTORE="$ROOT/.build/debug.keystore"
  if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
  fi
  "$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out "$OUTPUT" "$WORK/aligned.apk"
else
  "$BUILD_TOOLS/apksigner" sign --ks "$VARCO_KEYSTORE" --ks-key-alias "$VARCO_KEY_ALIAS" \
    --ks-pass env:VARCO_STORE_PASSWORD --key-pass env:VARCO_KEY_PASSWORD --out "$OUTPUT" "$WORK/aligned.apk"
fi
"$BUILD_TOOLS/apksigner" verify "$OUTPUT"
"$BUILD_TOOLS/zipalign" -c -p 4 "$OUTPUT"
printf 'Built and signature-verified: %s\n' "$OUTPUT"
if [ "$MODE" = release-candidate ]; then
  printf 'Release candidate only; submission evidence and rights remain recorded separately. Run preflight.py --release before submission.\n'
fi
