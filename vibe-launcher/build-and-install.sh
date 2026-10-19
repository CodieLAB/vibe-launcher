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
# com.google.android.apps.tv.launcherx on newer Google TV), so ask the TV instead of guessing. Only a
# launcher we recognise is disabled: the answer can also be Android's chooser ("android"), the setup
# wizard, or another third-party launcher, none of which should be disabled by accident.
# Pass STOCK_LAUNCHER=<package> to name it yourself, or STOCK_LAUNCHER=none to disable nothing.
# (Approach from upstream ed06e6d and 1fa7479.)
KNOWN_STOCK_LAUNCHERS="com.google.android.tvlauncher com.google.android.apps.tv.launcherx com.google.android.leanbacklauncher"
TV(){ "$PY" -I ../debloat/tools/tv.py "$@"; }
if [ -z "${STOCK_LAUNCHER:-}" ]; then
  HOME_PKG=$(TV 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' \
    | tail -n 1 | tr -d '\r' | cut -d/ -f1) || {
    echo "ERROR: could not ask the TV which launcher owns Home. Check TV_HOST and the debugging prompt on the TV," >&2
    echo "or pass STOCK_LAUNCHER=<package> yourself." >&2; exit 1; }
  case " $KNOWN_STOCK_LAUNCHERS " in
    *" $HOME_PKG "*) STOCK_LAUNCHER=$HOME_PKG ;;
    *)
      if [ "$HOME_PKG" = com.maydings.vibelauncher ]; then
        STOCK_LAUNCHER=none; echo "Home already belongs to Vibe Launcher; nothing will be disabled."
      elif [ "$HOME_PKG" = android ]; then
        # Android's chooser: two or more launchers tie and no default is set. Take the one known stock
        # launcher among the apps claiming Home, if there is exactly one.
        CLAIMANTS=$(TV 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME' \
          | tr -d '\r' | grep -o '[A-Za-z0-9_.]*/' | tr -d / | sort -u)
        FOUND=""; N=0
        for k in $KNOWN_STOCK_LAUNCHERS; do
          # A here-string, not "printf | grep -q": with pipefail, grep quitting early fails the pipe.
          if grep -qx "$k" <<< "$CLAIMANTS"; then FOUND=$k; N=$((N + 1)); fi
        done
        if [ "$N" -eq 1 ]; then
          STOCK_LAUNCHER=$FOUND; echo "Home is Android's chooser; the stock launcher among the candidates is $STOCK_LAUNCHER."
        else
          echo "ERROR: Home is Android's launcher chooser and $N known stock launchers claim Home: pick a default on the TV first," >&2
          echo "or run again with STOCK_LAUNCHER=<package to disable> or STOCK_LAUNCHER=none." >&2; exit 1
        fi
      else
        echo "ERROR: Home currently resolves to '${HOME_PKG:-nothing}', which is not a stock launcher this script knows." >&2
        echo "  android                                  = Android's launcher chooser (two launchers tie); pick a default on the TV first" >&2
        echo "  com.google.android.tungsten.setupwraith  = Google TV's setup wizard" >&2
        echo "  anything else                            = another launcher is the default" >&2
        echo "Run again with STOCK_LAUNCHER=<package to disable> or STOCK_LAUNCHER=none." >&2; exit 1
      fi ;;
  esac
fi
echo "Stock launcher: $STOCK_LAUNCHER"
if [ "$STOCK_LAUNCHER" = none ]; then DISABLE_STOCK="echo stock launcher left alone"
else DISABLE_STOCK="pm disable-user --user 0 $STOCK_LAUNCHER"; fi
APK=app/build/outputs/apk/debug/app-debug.apk
"$PY" -I ../debloat/tools/b64push.py "$APK" /data/local/tmp/vibelauncher.apk
printf '%s\n' \
  "pm install -r /data/local/tmp/vibelauncher.apk" \
  "rm -f /data/local/tmp/vibelauncher.apk" \
  "pm grant com.maydings.vibelauncher android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted" \
  "pm grant com.maydings.vibelauncher android.permission.ACCESS_COARSE_LOCATION 2>/dev/null; echo coarse-granted" \
  "pm grant com.maydings.vibelauncher android.permission.READ_TV_LISTINGS 2>/dev/null; echo cards-granted" \
  "am start -W -n com.maydings.vibelauncher/.MainActivity | grep -E '^(Status|Error)'" \
  "$DISABLE_STOCK" \
  "cmd package set-home-activity --user 0 com.maydings.vibelauncher/.MainActivity" \
  "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1" \
  | "$PY" -I ../debloat/tools/tv.py --batch
# Order matters. Android forgets the chosen home app whenever the set of home apps changes, so it is
# set after the stock launcher is disabled; on Google TV 14 the next candidate would otherwise be the
# setup wizard's recovery screen, which crash-loops the system. Vibe Launcher is started first so
# Home is never left without a running launcher. The last line above must print
# com.maydings.vibelauncher/.MainActivity.
echo "Installed. Press Home on the remote to see it."
