# Backs up the last build's apps and folders (android.backup) into its restic repository, or
# brings an app's data back; the engine does it (see Backup.kt), with restic from Nix's store.
# Runs as root. Arguments: who started it (job, shell, ...), then what to do:
#   auto                     what the phone does every 15 minutes: backs up what is due
#   now [apps or folders]    backs up all of them now, or the ones named
#   list [app or folder]     lists the backups
#   status                   each app and folder: when it was backed up last, and whether it is due
#   restore <app> [snapshot] brings the app's data back from its newest backup (or the one
#                            named), over what it has
#   restic <arguments>       restic itself, with the repository and its password
MODDIR=${0%/*}
BY=${1:-manual}
MODE=${2:-now}
[ $# -ge 2 ] && shift 2
. "$MODDIR/common.sh"

[ -f "$PROFILE" ] || { log "No build yet: nothing to back up"; exit 0; }
# Without android.backup in the last build the job has nothing to do, quietly.
if ! grep -q '"backup":{' "$PROFILE"; then
  [ "$MODE" = auto ] && exit 0
  fail "the last build has no backups: set android.backup.repository in the config, then build"
fi
case "$MODE" in
  auto) engine backup --auto ;;
  now) engine backup "$@" ;;
  list) engine backups "$@" ;;
  status) engine backup-status ;;
  restore) engine backup-restore "$@" ;;
  # Straight to the terminal: restic may ask or show more than lines.
  restic) CLASSPATH="$MODDIR/app.apk" exec /system/bin/app_process /system/bin dev.seraph.manager.engine.Main restic "$@" ;;
  *) fail "backup.sh: no such thing to do: $MODE (auto, now, list, status, restore, restic)" ;;
esac
