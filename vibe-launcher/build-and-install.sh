#!/usr/bin/env bash
# Build the launcher from source and install it on a TV as the home screen.
#
#   ./build-and-install.sh <tv-ip>        (or TV_HOST=<tv-ip> ./build-and-install.sh)
#
# Connects with adb, or with the Python client in tools/ where macOS blocks adb (tools/lib.sh). Pass
# STOCK_LAUNCHER=<package> to name the launcher to disable yourself, or STOCK_LAUNCHER=none to disable
# nothing.
set -uo pipefail
cd "$(dirname "$0")"
. tools/lib.sh
HOST=${1:-${TV_HOST:-}}
[ -n "$HOST" ] || die "usage: ./build-and-install.sh <tv-ip>"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
step "Building"
./gradlew assembleDebug -q --console=plain || die "build failed"
APK=app/build/outputs/apk/debug/app-debug.apk

connect "$HOST"

# Which app owns Home differs by build (com.google.android.tvlauncher on Android TV,
# com.google.android.apps.tv.launcherx on newer Google TV), so ask the TV instead of guessing. Only a
# launcher we recognise is disabled: the answer can also be Android's chooser ("android"), the setup
# wizard, or another third-party launcher, none of which should be disabled by accident.
if [ -z "${STOCK_LAUNCHER:-}" ]; then
  HOME_PKG=$(home_app | cut -d/ -f1)
  case " $STOCK_LAUNCHERS " in
    *" $HOME_PKG "*) STOCK_LAUNCHER=$HOME_PKG ;;
    *)
      if [ "$HOME_PKG" = "$LAUNCHER" ]; then
        STOCK_LAUNCHER=none; say "Home already belongs to Vibe Launcher; nothing will be disabled."
      elif [ "$HOME_PKG" = android ]; then
        # Android's chooser: two or more launchers tie and no default is set. Take the one known stock
        # launcher among the apps claiming Home, if there is exactly one.
        CLAIMANTS=$(tv "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME" \
          | grep -o '[A-Za-z0-9_.]*/' | tr -d / | sort -u)
        FOUND=""; N=0
        for k in $STOCK_LAUNCHERS; do
          if grep -qx "$k" <<< "$CLAIMANTS"; then FOUND=$k; N=$((N + 1)); fi
        done
        [ "$N" -eq 1 ] || die "Home is Android's launcher chooser and $N known stock launchers claim it: pick a default on the TV first, or run again with STOCK_LAUNCHER=<package> or STOCK_LAUNCHER=none."
        STOCK_LAUNCHER=$FOUND; say "Home is Android's chooser; the stock launcher among the candidates is $STOCK_LAUNCHER."
      else
        die "Home is '${HOME_PKG:-nothing}', not a stock launcher this script knows (com.google.android.tungsten.setupwraith is Google TV's setup wizard). Run again with STOCK_LAUNCHER=<package to disable> or STOCK_LAUNCHER=none."
      fi ;;
  esac
fi
say "Stock launcher: $STOCK_LAUNCHER"

# Order matters: install, start Vibe Launcher (so Home is never left without a running launcher),
# disable the stock launcher, and set Home last. Android forgets the chosen home app whenever the set
# of home apps changes; on Google TV 14 the next candidate would be the setup wizard's recovery
# screen, which crash-loops the system.
step "Installing"
RESULT=$(tv_install "$APK")
[[ "$RESULT" == *Success* ]] || die "install failed: $RESULT (a release build installed? uninstall it first: debug and release builds are signed with different keys)"
# Started as Home (the HOME action and category), not as an app: "am start -n" alone opens it in an ordinary
# task, and the next Home press starts a second copy beside it.
tv "am start -a android.intent.action.MAIN -c android.intent.category.HOME -n $LAUNCHER_ACTIVITY" >/dev/null
sleep 2
[ "$STOCK_LAUNCHER" = none ] || tv "pm disable-user --user 0 $STOCK_LAUNCHER"
tv "cmd package set-home-activity --user 0 $LAUNCHER_ACTIVITY" >/dev/null
NOW=$(home_app)
[ "$NOW" = "$LAUNCHER_ACTIVITY" ] || die "Home is $NOW instead of Vibe Launcher"
say "Installed and set as home. Press Home on the remote to see it."
