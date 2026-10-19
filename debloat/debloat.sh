#!/usr/bin/env bash
# Debloat a tested Android TV over the network and make Vibe Launcher its home screen.
#
#   ./debloat.sh <tv-ip> [options]
#
#   --dry-run        show what would happen; change nothing on the TV
#   --defaults       don't ask, take the default answer to every question (the tested setup)
#   --list <file>    use this list instead of the one matched to the TV's model
#   --apk <file>     install this Vibe Launcher APK instead of downloading the latest release
#   --skip-launcher  don't install Vibe Launcher (the stock launcher is then left enabled)
#
# Packages are disabled, never uninstalled, and everything is recorded under backups/ so
# ./restore.sh can put the TV back the way it was.
set -uo pipefail
. "$(dirname "$0")/lib.sh"

HOST=""; DRY=0; LIST=""; APK=""; SKIP_LAUNCHER=0; ASSUME_DEFAULTS=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1 ;;
    --defaults) ASSUME_DEFAULTS=1 ;;
    --list) LIST=${2:?--list needs a file}; shift ;;
    --apk) APK=${2:?--apk needs a file}; shift ;;
    --skip-launcher) SKIP_LAUNCHER=1 ;;
    -h|--help) awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
    -*) die "unknown option $1 (see --help)" ;;
    *) HOST=$1 ;;
  esac
  shift
done
[ -n "$HOST" ] || die "usage: ./debloat.sh <tv-ip> [--dry-run] [--defaults] (see --help)"
[ "$DRY" = 1 ] && say "Dry run: nothing on the TV will be changed."

connect "$HOST"

# ---- which TV, which list -------------------------------------------------------------------------
DEVICE=$(prop ro.product.device)
MODEL="$(prop ro.product.manufacturer) $(prop ro.product.model)"
ANDROID=$(prop ro.build.version.release)
SERIAL=$(prop ro.serialno)
if [ -z "$LIST" ]; then
  LIST=$(list_for_device "$DEVICE")
  if [ -z "$LIST" ]; then
    say "This TV is a $MODEL (device \"$DEVICE\", Android $ANDROID). There is no tested list for it."
    say "Tested lists:"; for f in "$HERE"/lists/*.txt; do say "  $(basename "$f"): $(list_title "$f")"; done
    die "Pick one with --list lists/<file> only if your TV is the same platform, and use --dry-run first."
  fi
fi
[ -f "$LIST" ] || die "no such list: $LIST"
say "TV: $MODEL, Android $ANDROID (device \"$DEVICE\")"
say "List: $(basename "$LIST") ($(list_title "$LIST"))"

ENABLED=$(tv "pm list packages -e" | sed -n 's/^package://p' | sort)
INSTALLED=$(tv "pm list packages" | sed -n 's/^package://p' | sort)
[ -n "$INSTALLED" ] || die "could not read the TV's package list"
has() { grep -Fqx -- "$2" <<< "$1"; }

# ---- backup ---------------------------------------------------------------------------------------
BACKUP=$HERE/backups/$DEVICE-$SERIAL-$(date +%Y%m%d-%H%M%S)
if [ "$DRY" = 0 ]; then
  step "Backing up the TV's current state to $BACKUP"
  mkdir -p "$BACKUP" || die "can't create $BACKUP"
  tv "getprop" > "$BACKUP/getprop.txt"
  echo "$INSTALLED" > "$BACKUP/packages-installed.txt"
  echo "$ENABLED" > "$BACKUP/packages-enabled.txt"
  tv "pm list packages -d" | sed -n 's/^package://p' | sort > "$BACKUP/packages-disabled.txt"
  tv "pm list packages -3" | sed -n 's/^package://p' | sort > "$BACKUP/packages-third-party.txt"
  home_app > "$BACKUP/home.txt"
  for ns in global secure system; do tv "settings list $ns" > "$BACKUP/settings-$ns.txt"; done
  printf '%s\n' "$SERIAL" > "$BACKUP/serial.txt"
  : > "$BACKUP/disabled-by-debloat.txt"
  : > "$BACKUP/uninstalled-by-debloat.txt"
  say "Saved. ./restore.sh uses it to undo exactly what this run changes."
fi

# ---- what to disable ------------------------------------------------------------------------------
step "Choosing what to disable"
PLAN=""; ANSWERED=""; DECISION=no
TAB=$(printf '\t')
while IFS="$TAB" read -r kind text pkg; do
  [ -n "$kind" ] || die "$LIST: package $pkg is not under an [always] or [ask:...] section"
  if ! has "$ANSWERED" "$kind|$text"; then
    ANSWERED="$ANSWERED"$'\n'"$kind|$text"
    case "$kind" in
      always) say "- $text"; DECISION=yes ;;
      ask:yes) if ask "- $text" yes; then DECISION=yes; else DECISION=no; fi ;;
      ask:no) if ask "- $text" no; then DECISION=yes; else DECISION=no; fi ;;
    esac
  fi
  [ "$DECISION" = yes ] || continue
  if ! has "$INSTALLED" "$pkg"; then
    say "    $pkg: not on this TV, skipped"
  elif ! has "$ENABLED" "$pkg"; then
    say "    $pkg: already disabled"
  else
    PLAN="$PLAN $pkg"
  fi
done <<< "$(list_entries "$LIST")"
COUNT=$(echo $PLAN | wc -w | tr -d ' ')
say "$COUNT packages to disable."

# ---- Vibe Launcher --------------------------------------------------------------------------------
if [ "$SKIP_LAUNCHER" = 0 ]; then
  step "Installing Vibe Launcher"
  if [ -z "$APK" ]; then
    APK_DIR=$(mktemp -d "${TMPDIR:-/tmp}/vibe-launcher.XXXXXX") || die "can't create a temporary folder"
    trap 'rm -rf "$APK_DIR"' EXIT
    APK=$APK_DIR/vibe-launcher.apk
    say "Downloading $APK_URL"
    curl -fsSL "$APK_URL" -o "$APK" || die "download failed; check your internet connection or pass --apk <file>"
  fi
  [ "$(head -c 2 "$APK")" = PK ] || die "$APK is not an APK"
  if [ "$DRY" = 1 ]; then
    say "Would install $(basename "$APK") and open it once."
  else
    RESULT=$(tv_install "$APK")
    if [[ "$RESULT" == *INSTALL_FAILED_UPDATE_INCOMPATIBLE* ]]; then
      say "A Vibe Launcher signed with a different key (for example a build of your own) is installed."
      if ask "Uninstall it and install the release? Its keys and tile layout are lost." no; then
        tv "pm uninstall $LAUNCHER" >/dev/null
        RESULT=$(tv_install "$APK")
      else
        RESULT="kept"
      fi
    fi
    case "$RESULT" in
      *Success*) say "Installed." ;;
      kept) say "Keeping the Vibe Launcher that is already installed." ;;
      *) die "install failed: $RESULT (nothing has been disabled yet)" ;;
    esac
    tv "am start -n $LAUNCHER_ACTIVITY" >/dev/null
    sleep 2
  fi
fi
LAUNCHER_READY=0
if has "$INSTALLED" "$LAUNCHER" || { [ "$DRY" = 0 ] && [ -n "$(tv "pm path $LAUNCHER")" ]; } \
   || { [ "$DRY" = 1 ] && [ "$SKIP_LAUNCHER" = 0 ]; }; then
  LAUNCHER_READY=1
fi
if [ "$LAUNCHER_READY" = 0 ]; then
  # Never leave the TV without a home screen.
  KEEP=""
  for p in $PLAN; do case " $STOCK_LAUNCHERS " in *" $p "*) KEEP="$KEEP $p" ;; esac; done
  if [ -n "$KEEP" ]; then
    say "Vibe Launcher isn't installed, so the stock launcher stays enabled:$KEEP"
    NEW=""; for p in $PLAN; do case " $KEEP " in *" $p "*) ;; *) NEW="$NEW $p" ;; esac; done; PLAN=$NEW
  fi
fi

# ---- other launchers ------------------------------------------------------------------------------
# Third-party launchers that take the Home button back after a reboot.
for p in com.spocky.projengmenu; do
  has "$INSTALLED" "$p" || continue
  step "Other launchers"
  if ask "Projectivy Launcher ($p) takes the Home button back after a reboot. Uninstall it?" yes; then
    if [ "$DRY" = 1 ]; then say "Would uninstall $p."
    else tv "pm uninstall --user 0 $p" | tail -n 1; echo "$p" >> "$BACKUP/uninstalled-by-debloat.txt"; fi
  fi
done

# ---- disable --------------------------------------------------------------------------------------
step "Disabling"
FAILED=0
for p in $PLAN; do
  if [ "$DRY" = 1 ]; then say "would disable $p"; continue; fi
  out=$(tv "pm disable-user --user 0 $p")
  if [[ "$out" == *disabled-user* ]]; then
    say "disabled $p"; echo "$p" >> "$BACKUP/disabled-by-debloat.txt"
  else
    say "FAILED $p: $out"; FAILED=$((FAILED + 1))
  fi
done
[ -z "$PLAN" ] && say "Nothing to disable."

# ---- home -----------------------------------------------------------------------------------------
# Last: Android forgets the chosen home app whenever a home app is enabled or disabled.
if [ "$LAUNCHER_READY" = 1 ]; then
  step "Making Vibe Launcher the home screen"
  if [ "$DRY" = 1 ]; then say "would set $LAUNCHER_ACTIVITY as home"
  else
    tv "cmd package set-home-activity --user 0 $LAUNCHER_ACTIVITY" >/dev/null
    NOW=$(home_app)
    if [ "$NOW" = "$LAUNCHER_ACTIVITY" ]; then say "Home: Vibe Launcher."
    else say "Home is still $NOW. Press Home on the remote and pick Vibe Launcher if the TV asks."; fi
  fi
fi

step "Done"
if [ "$DRY" = 1 ]; then
  say "Dry run finished: $COUNT packages would be disabled. Run again without --dry-run to do it."
else
  say "Disabled $(wc -l < "$BACKUP/disabled-by-debloat.txt" | tr -d ' ') packages ($FAILED failed)."
  say "To undo: ./restore.sh $HOST"
fi
