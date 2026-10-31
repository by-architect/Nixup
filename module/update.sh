# Updates the config's apps outside of a build: the F-Droid index is brought up to
# date (only downloaded when f-droid.org has a newer one), then the apps of the
# last build that have a newer version are listed, or updated, with notifications;
# a new generation keeps the versions they are at. Runs as root. Arguments: who
# started it (app, job, adb, action), then what to do:
#   check            lists the updates (and keeps them in updates.json for the app)
#   now [apps...]    updates all of them, or the apps named
#   auto             what the phone does every 6 hours: updates them when the
#                    config's android.settings.fdroid.autoupdatePackages is true,
#                    else only tells in a notification that there are updates
MODDIR=${0%/*}
BY=${1:-manual}
MODE=${2:-check}
[ $# -ge 2 ] && shift 2
. "$MODDIR/common.sh"

log "== F-Droid index"
engine index --force || log "Looking for updates with the F-Droid index there is"

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
