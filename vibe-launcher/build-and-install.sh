#!/usr/bin/env bash
# Build the launcher APK and install it on the TV as the default home screen.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug -q --console=plain
PY=../debloat/tools/venv/bin/python   # the ADB client from debloat/tools
# Which app owns Home differs by build (com.google.android.tvlauncher on Android TV,
# com.google.android.apps.tv.launcherx on newer Google TV), so ask the TV instead of guessing.
# Ignore an answer of our own launcher, left over from a previous run of this script.
if [ -z "${STOCK_LAUNCHER:-}" ]; then
  STOCK_LAUNCHER=$("$PY" -I ../debloat/tools/tv.py \
    'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' \
    | tail -n 1 | cut -d/ -f1 || true)
  case "$STOCK_LAUNCHER" in
    com.maydings.vibelauncher | *[!a-zA-Z0-9._]* | "") STOCK_LAUNCHER=com.google.android.tvlauncher ;;
  esac
  echo "Stock launcher: $STOCK_LAUNCHER"
fi
APK=app/build/outputs/apk/debug/app-debug.apk
"$PY" -I ../debloat/tools/b64push.py "$APK" /data/local/tmp/vibelauncher.apk
printf '%s\n' \
  "pm install -r /data/local/tmp/vibelauncher.apk" \
  "rm -f /data/local/tmp/vibelauncher.apk" \
  "pm grant com.maydings.vibelauncher android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted" \
  "pm grant com.maydings.vibelauncher android.permission.ACCESS_COARSE_LOCATION 2>/dev/null; echo coarse-granted" \
  "pm grant com.maydings.vibelauncher android.permission.READ_TV_LISTINGS 2>/dev/null; echo cards-granted" \
  "am start -W -n com.maydings.vibelauncher/.MainActivity | grep -E '^(Status|Error)'" \
  "pm disable-user --user 0 $STOCK_LAUNCHER" \
  "cmd package set-home-activity --user 0 com.maydings.vibelauncher/.MainActivity" \
  "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1" \
  | "$PY" -I ../debloat/tools/tv.py --batch
# Order matters. Android forgets the chosen home app whenever the set of home apps changes, so it is
# set after the stock launcher is disabled; on Google TV 14 the next candidate would otherwise be the
# setup wizard's recovery screen, which crash-loops the system. Vibe Launcher is started first so
# Home is never left without a running launcher. The last line above must print
# com.maydings.vibelauncher/.MainActivity.
echo "Installed. Press Home on the remote to see it."
