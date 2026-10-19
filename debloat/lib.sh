# Shared by debloat.sh and restore.sh: talking to the TV, reading lists, asking questions.
# Plain bash 3.2 (the version macOS ships), no other dependencies besides adb.

LAUNCHER=com.maydings.vibelauncher
LAUNCHER_ACTIVITY=$LAUNCHER/.MainActivity
APK_URL=https://github.com/maydings/vibe-launcher/releases/latest/download/vibe-launcher.apk
STOCK_LAUNCHERS="com.google.android.tvlauncher com.google.android.apps.tv.launcherx com.google.android.leanbacklauncher"
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

say()  { printf '%s\n' "$*"; }
step() { printf '\n== %s\n' "$*"; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# ask "Question?" yes|no -> returns 0 for yes. With ASSUME_DEFAULTS=1 the default is taken silently.
ask() {
  local q=$1 def=$2 hint answer
  [ "$def" = yes ] && hint="[Y/n]" || hint="[y/N]"
  if [ "${ASSUME_DEFAULTS:-0}" = 1 ]; then
    say "$q $hint ${def} (default)"
    [ "$def" = yes ]; return
  fi
  while true; do
    read -r -p "$q $hint " answer </dev/tty || answer=""
    case "$answer" in
      "") [ "$def" = yes ]; return ;;
      y|Y|yes|YES) return 0 ;;
      n|N|no|NO) return 1 ;;
    esac
  done
}

# ---- connection -----------------------------------------------------------------------------------
# Two ways to reach the TV: Google's adb, or, where macOS blocks adb on the local network, the
# pure-Python client in tools/ (set up on first use).

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
    PY=$HERE/tools/venv/bin/python
    if [ ! -x "$PY" ]; then
      command -v python3 >/dev/null 2>&1 || die "Install adb (Android platform-tools) or Python 3, then run this again."
      say "Setting up tools/venv (one time)..."
      python3 -m venv "$HERE/tools/venv" && "$PY" -m pip -q install -r "$HERE/tools/requirements.txt" \
        || die "Could not set up the Python client. Install adb (Android platform-tools) instead."
    fi
    export TV_HOST=${TV%:*} TV_PORT=${TV##*:}
    say "If the TV asks \"Allow USB debugging?\", accept it (tick \"Always allow\")."
    "$PY" -I "$HERE/tools/tv.py" echo ok >/dev/null 2>&1 \
      || die "Can't connect to $TV. Is the TV on, on this network, with USB/network debugging turned on?"
    BACKEND=python
  fi
  say "Connected to $TV ($BACKEND)."
}

# One shell command on the TV, output with Windows line endings removed.
tv() {
  if [ "$BACKEND" = adb ]; then adb -s "$TV" shell "$*" 2>&1 | tr -d '\r'
  else "$PY" -I "$HERE/tools/tv.py" "$*" 2>&1 | tr -d '\r'; fi
}

# Installs an APK with its permissions granted; prints the installer's last line.
tv_install() {
  if [ "$BACKEND" = adb ]; then
    adb -s "$TV" install -r -g "$1" 2>&1 | tr -d '\r' | tail -n 1
  else
    "$PY" -I "$HERE/tools/b64push.py" "$1" /data/local/tmp/vibe-launcher.apk >/dev/null || { echo "upload failed"; return; }
    tv "pm install -r -g /data/local/tmp/vibe-launcher.apk; rm -f /data/local/tmp/vibe-launcher.apk" | tail -n 1
  fi
}

prop() { tv "getprop $1"; }

home_app() {
  tv "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME" | tail -n 1
}

# ---- lists ----------------------------------------------------------------------------------------
# A list is lists/<name>.txt. "# device: X" lines name the ro.product.device values it is for.
# Packages sit under section lines:
#   [always] What this group is          disabled without asking
#   [ask:yes] Question?                  asked; Enter means yes (disable)
#   [ask:no] Question?                   asked; Enter means no (keep)
# Anything after a # is a comment.

list_for_device() {
  local device=$1 f
  for f in "$HERE"/lists/*.txt; do
    grep -qx "# device: $device" "$f" && { echo "$f"; return; }
  done
}

list_title() { sed -n 's/^# title: //p' "$1" | head -n 1; }

# Prints "section-kind<TAB>section text<TAB>package" for every package line.
list_entries() {
  awk '
    /^\[always\]/ { kind="always"; text=substr($0, 10); next }
    /^\[ask:yes\]/ { kind="ask:yes"; text=substr($0, 11); next }
    /^\[ask:no\]/ { kind="ask:no"; text=substr($0, 10); next }
    { sub(/#.*/, ""); gsub(/^[ \t]+|[ \t]+$/, "") }
    $0 != "" { print kind "\t" text "\t" $1 }
  ' "$1"
}
