# Used by build-and-install.sh: reaching the TV and installing on it.
# Plain bash 3.2 (the version macOS ships), no other dependencies besides adb.

LAUNCHER=com.maydings.vibelauncher
LAUNCHER_ACTIVITY=$LAUNCHER/.MainActivity
STOCK_LAUNCHERS="com.google.android.tvlauncher com.google.android.apps.tv.launcherx com.google.android.leanbacklauncher"
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

say()  { printf '%s\n' "$*"; }
step() { printf '\n== %s\n' "$*"; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# ---- connection -----------------------------------------------------------------------------------
# Two ways to reach the TV: Google's adb, or, where macOS blocks adb on the local network, the
# pure-Python client next to this file (set up on first use).

connect() {
  local host=$1
  case "$host" in *:*) TV=$host ;; *) TV=$host:5555 ;; esac
  BACKEND=""
  if command -v adb >/dev/null 2>&1; then
    adb connect "$TV" >/dev/null 2>&1 || true
    local i state
    for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30; do
      state=$(adb -s "$TV" get-state 2>&1 | tr -d '\r')
      case "$state" in
        device) BACKEND=adb; break ;;
        *unauthorized*)
          [ "$i" = 1 ] && say "Accept the \"Allow USB debugging?\" prompt on the TV (tick \"Always allow\")."
          sleep 2 ;;
        *) [ "$i" -ge 3 ] && break; adb connect "$TV" >/dev/null 2>&1 || true; sleep 1 ;;
      esac
    done
  fi
  if [ -z "$BACKEND" ]; then
    say "adb can't reach $TV; trying the built-in Python client (tools/tv.py)."
    PY=$HERE/venv/bin/python
    if [ ! -x "$PY" ]; then
      command -v python3 >/dev/null 2>&1 || die "Install adb (Android platform-tools) or Python 3, then run this again."
      say "Setting up tools/venv (one time)..."
      python3 -m venv "$HERE/venv" && "$PY" -m pip -q install -r "$HERE/requirements.txt" \
        || die "Could not set up the Python client. Install adb (Android platform-tools) instead."
    fi
    export TV_HOST=${TV%:*} TV_PORT=${TV##*:}
    say "If the TV asks \"Allow USB debugging?\", accept it (tick \"Always allow\")."
    "$PY" -I "$HERE/tv.py" echo ok >/dev/null 2>&1 \
      || die "Can't connect to $TV. Is the TV on, on this network, with USB/network debugging turned on?"
    BACKEND=python
  fi
  say "Connected to $TV ($BACKEND)."
}

# One shell command on the TV, output with Windows line endings removed.
tv() {
  if [ "$BACKEND" = adb ]; then adb -s "$TV" shell "$*" 2>&1 | tr -d '\r'
  else "$PY" -I "$HERE/tv.py" "$*" 2>&1 | tr -d '\r'; fi
}

# Installs an APK with its permissions granted; prints the installer's last line.
tv_install() {
  if [ "$BACKEND" = adb ]; then
    adb -s "$TV" install -r -g "$1" 2>&1 | tr -d '\r' | tail -n 1
  else
    "$PY" -I "$HERE/b64push.py" "$1" /data/local/tmp/vibe-launcher.apk >/dev/null || { echo "upload failed"; return; }
    tv "pm install -r -g /data/local/tmp/vibe-launcher.apk; rm -f /data/local/tmp/vibe-launcher.apk" | tail -n 1
  fi
}

prop() { tv "getprop $1"; }

home_app() {
  tv "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME" | tail -n 1
}
