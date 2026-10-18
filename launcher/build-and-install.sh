#!/usr/bin/env bash
# Build the launcher APK and install it on the TV as the default home screen.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.tvlauncher}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug -q --console=plain
PY=../tools/venv/bin/python
APK=app/build/outputs/apk/debug/app-debug.apk
"$PY" -I ../tools/b64push.py "$APK" /data/local/tmp/vibelauncher.apk
printf '%s\n' \
  "pm install -r /data/local/tmp/vibelauncher.apk" \
  "rm -f /data/local/tmp/vibelauncher.apk" \
  "pm grant com.maydings.vibelauncher android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted" \
  "cmd package set-home-activity --user 0 com.maydings.vibelauncher" \
  "pm disable-user --user 0 $STOCK_LAUNCHER" \
  | "$PY" -I ../tools/tv.py --batch
echo "Installed. Press Home on the remote to see it."
