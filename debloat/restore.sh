#!/usr/bin/env bash
# Undo ./debloat.sh: re-enable what it disabled and give Home back to the stock launcher.
#
#   ./restore.sh <tv-ip> [options]
#
#   --dry-run          show what would happen; change nothing on the TV
#   --defaults         don't ask, take the default answer to every question
#   --backup <dir>     use this backup instead of the newest one for this TV
#   --list <file>      no backup: re-enable everything on this list instead (this can also turn on
#                      apps that were already off before debloating)
#
# With a backup (made by debloat.sh in backups/), exactly the packages that run disabled are turned
# back on; anything that was already off at the factory stays off.
set -uo pipefail
. "$(dirname "$0")/lib.sh"

HOST=""; DRY=0; BACKUP=""; LIST=""; ASSUME_DEFAULTS=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1 ;;
    --defaults) ASSUME_DEFAULTS=1 ;;
    --backup) BACKUP=${2:?--backup needs a folder}; shift ;;
    --list) LIST=${2:?--list needs a file}; shift ;;
    -h|--help) awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
    -*) die "unknown option $1 (see --help)" ;;
    *) HOST=$1 ;;
  esac
  shift
done
[ -n "$HOST" ] || die "usage: ./restore.sh <tv-ip> [--dry-run] (see --help)"
[ "$DRY" = 1 ] && say "Dry run: nothing on the TV will be changed."

connect "$HOST"
DEVICE=$(prop ro.product.device)
SERIAL=$(prop ro.serialno)

# ---- what to re-enable ----------------------------------------------------------------------------
PREVIOUS_HOME=""
if [ -z "$BACKUP" ] && [ -z "$LIST" ]; then
  BACKUP=$(ls -d "$HERE"/backups/"$DEVICE-$SERIAL"-* 2>/dev/null | sort | tail -n 1)
  [ -n "$BACKUP" ] || die "no backup for this TV in backups/. Pass --list lists/<file> to re-enable a whole list instead."
fi
if [ -n "$BACKUP" ]; then
  [ -f "$BACKUP/disabled-by-debloat.txt" ] || die "$BACKUP is not a debloat.sh backup"
  say "Backup: $BACKUP"
  PACKAGES=$(cat "$BACKUP/disabled-by-debloat.txt")
  PREVIOUS_HOME=$(tail -n 1 "$BACKUP/home.txt" 2>/dev/null)
else
  [ -f "$LIST" ] || die "no such list: $LIST"
  say "No backup: re-enabling everything on $(basename "$LIST")."
  PACKAGES=$(list_entries "$LIST" | cut -f 3)
fi

DISABLED=$(tv "pm list packages -d" | sed -n 's/^package://p')
step "Re-enabling"
COUNT=0
for p in $PACKAGES; do
  grep -Fqx -- "$p" <<< "$DISABLED" || { say "$p: not disabled, skipped"; continue; }
  if [ "$DRY" = 1 ]; then say "would enable $p"; COUNT=$((COUNT + 1)); continue; fi
  out=$(tv "pm enable $p")
  if [[ "$out" == *enabled* ]]; then say "enabled $p"; COUNT=$((COUNT + 1)); else say "FAILED $p: $out"; fi
done

if [ -n "$BACKUP" ] && [ -s "$BACKUP/uninstalled-by-debloat.txt" ]; then
  step "Apps debloat.sh uninstalled"
  for p in $(cat "$BACKUP/uninstalled-by-debloat.txt"); do
    if [ "$DRY" = 1 ]; then say "would try to bring back $p"; continue; fi
    out=$(tv "cmd package install-existing $p")
    case "$out" in *installed*) say "restored $p" ;; *) say "$p: install it again from the Play Store" ;; esac
  done
fi

# ---- Vibe Launcher --------------------------------------------------------------------------------
if [ -n "$(tv "pm path $LAUNCHER")" ]; then
  step "Vibe Launcher"
  if ask "Uninstall Vibe Launcher as well? (Its keys and tile layout are lost.)" no; then
    if [ "$DRY" = 1 ]; then say "would uninstall $LAUNCHER"; else tv "pm uninstall $LAUNCHER" | tail -n 1; fi
  fi
fi

# ---- home -----------------------------------------------------------------------------------------
# Last: Android forgets the chosen home app whenever a home app is enabled, disabled or removed.
step "Home screen"
TARGET=""
case "$PREVIOUS_HOME" in
  */*) case "$PREVIOUS_HOME" in android/*|"$LAUNCHER"/*) ;; *) TARGET=$PREVIOUS_HOME ;; esac ;;
esac
if [ -z "$TARGET" ]; then
  # The first stock launcher this TV has that answers the Home button.
  CANDIDATES=$(tv "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
  for s in $STOCK_LAUNCHERS; do
    TARGET=$(grep -o "^ *$s/[A-Za-z0-9_.]*" <<< "$CANDIDATES" | tr -d ' ' | head -n 1)
    [ -n "$TARGET" ] && break
  done
fi
if [ -z "$TARGET" ]; then
  say "No stock launcher is enabled, so Home stays as it is: $(home_app)"
elif [ "$DRY" = 1 ]; then
  say "would set $TARGET as home"
else
  tv "cmd package set-home-activity --user 0 $TARGET" >/dev/null
  say "Home: $(home_app)"
fi

step "Done"
say "Re-enabled $COUNT packages.$( [ "$DRY" = 1 ] && echo ' (dry run)')"
