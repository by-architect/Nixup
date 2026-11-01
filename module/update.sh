# Updates the config's apps outside of a build: the indexes of the last build's
# repositories are brought up to date (each only downloaded when the repository
# has a newer one), and the releases of its apps from releases; then its apps
# that have a newer version are listed, or updated, with notifications; a new
# generation keeps the versions they are at.
# Runs as root. Arguments: who started it (app, job, adb, action), then what to do:
#   check            lists the updates (and keeps them in updates.json for the app)
#   now [apps...]    updates all of them, or the apps named
#   auto             what the phone does every 6 hours: updates the apps of the
#                    repositories whose android.settings.<repo>.autoupdatePackages
#                    is true, and only tells about the others in a notification
MODDIR=${0%/*}
BY=${1:-manual}
MODE=${2:-check}
[ $# -ge 2 ] && shift 2
. "$MODDIR/common.sh"

[ -f "$PROFILE" ] || { log "No build yet: nothing to update"; exit 0; }
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
[ "$MODE" != check ] && [ -f "$PROFILE" ] && new_generation
exit $status
