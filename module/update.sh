# Updates the config's apps outside of a build: the indexes of the last build's
# repositories are brought up to date (each only downloaded when the repository
# has a newer one), and the releases of its apps from releases; then its apps
# that have a newer version are listed, or updated, with notifications; a new
# generation keeps the versions they are at.
# Runs as root. Arguments: who started it (app, job, network, adb, action), then what to do:
#   check            lists the updates (and keeps them in updates.json for the app)
#   now [apps...]    updates all of them, or the apps named
#   auto             the phone's own check, every 6 hours (job: the app's) and once it joins
#                    a network (network: the engine's network watch): updates the apps of
#                    the repositories whose android.settings.repo.<repo>.autoupdatePackages
#                    is true, and only tells about the others in a notification. It runs
#                    on Wi-Fi (on mobile data too with android.updates.onlyOnUnmetered =
#                    false), with the battery not low, not within an hour of the last one
#   self             looks for a newer Nixup (its module's update.json, as the Magisk
#                    app does), which check and auto do too
#   self-install     installs the newer Nixup, once it is checked (Nixup's module,
#                    newer, its app signed with the installed app's key): from the next restart
MODDIR=${0%/*}
BY=${1:-manual}
MODE=${2:-check}
[ $# -ge 2 ] && shift 2
. "$MODDIR/common.sh"

# Nixup itself, which needs no build.
case "$MODE" in
  self) run_open update "Nixup's update check"; engine self-update; exit $? ;;
  self-install) run_open update "Nixup's update"; event update start "Updating Nixup"; engine self-update --install; exit $? ;;
esac

[ -f "$PROFILE" ] || { log "No build yet: nothing to update"; exit 0; }
# The phone's own check leaves it to a build or an update that runs now, and runs when the engine says so (update-due:
# on a network it may use, ...); else it only says why, here and in $LOG.
if [ "$MODE" = auto ] && { [ "$BY" = job ] || [ "$BY" = network ]; }; then
  for other in build update; do
    running_of "$other" > /dev/null && { log "A $other runs now: the automatic update check is left to it"; exit 0; }
  done
  engine update-due
  [ $? = 2 ] && exit 0
fi
# The engine tells what it found and did (see Updates.kt); a check that fails is told here.
case "$MODE" in
  check) run_open update "Update check" ;;
  now) run_open update "Update"; event update start "Updating the config's apps"; resumable update update.sh now "$@" ;;
  auto) run_open update "Automatic update"; resumable update update.sh auto ;;
esac
log "== Repositories"
engine index --force "$PROFILE" || log "Looking for updates with the indexes there are"

log "== Updates"
case "$MODE" in
  check) engine updates ;;
  now) engine update "$@" ;;
  auto) engine update --auto ;;
  *) fail "update.sh: no such thing to do: $MODE (check, now, auto)" ;;
esac
status=$?
# (Not after one that did not run: a build had the apps' lock, and makes its own generation.)
[ "$MODE" != check ] && [ "$status" = 0 ] && [ -f "$PROFILE" ] && new_generation
# And Nixup itself; one that cannot be checked now (offline, no release yet) only says so.
if [ "$MODE" != now ]; then
  log "== Nixup"
  engine self-update || log "Nixup's own update could not be checked (see above)"
fi
exit $status
