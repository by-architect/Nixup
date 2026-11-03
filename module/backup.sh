# Backs up the last build's apps and folders (android.backup) into its restic repository, or
# brings an app or a folder back; the engine does it (see Backup.kt), with restic from Nix's store.
# Runs as root. Arguments: who started it (job, shell, app, ...), then what to do:
#   auto                     what the phone does every 15 minutes: backs up what is due
#   now [--parts=<parts>] [apps or folders]
#                            backs up all of them now, or the ones named: any installed app (with
#                            android.backup.defaults) or folder of the phone; --parts: what to take
#                            of each app (apk,data,deviceData,storage,media,obb), not its settings
#   list [app or folder]     lists the backups
#   status                   each app and folder: when it was backed up last, and whether it is due
#   restore <app> [snapshot] [--parts=<parts>]
#                            brings the app's data back from its newest backup (or the one named),
#                            over what it has, and its APK when it is not installed; --parts: the
#                            parts named (apk installs it from the backup)
#   restore <folder> [snapshot] [--to=<folder>]
#                            brings the folder's files back, into it or into a new folder
#   parts <app> <snapshot>   what one backup of the app has
#   restic <arguments>       restic itself, with the repository and its password
# status, list and parts with --json say it as one line of JSON, for the app, without logging it.
# (SERAPH_PROFILE: another profile's backups for one command, with its own record of what was
# backed up when, next to it: tests.)
MODDIR=${0%/*}
BY=${1:-manual}
MODE=${2:-now}
[ $# -ge 2 ] && shift 2
. "$MODDIR/common.sh"
[ -n "$SERAPH_PROFILE" ] && PROFILE=$SERAPH_PROFILE

# Straight to the engine: what the app reads, and restic, which may ask or show more than lines.
direct() { CLASSPATH="$MODDIR/app.apk" exec /system/bin/app_process /system/bin dev.seraph.manager.engine.Main "$@"; }
case " $* " in
  *" --json "*)
    case "$MODE" in
      status) direct backup-status "$@" ;;
      list) direct backups "$@" ;;
      parts) direct backup-parts "$@" ;;
    esac ;;
esac

[ -f "$PROFILE" ] || { log "No build yet: nothing to back up"; exit 0; }
# Without android.backup in the last build the job has nothing to do, quietly; asked by hand, it
# says so (and leaves the module's status in Magisk as the last build left it).
if ! grep -q '"backup":{' "$PROFILE"; then
  [ "$MODE" = auto ] && exit 0
  log "The last build has no backups: set android.backup.repository in the config, then build"
  exit 2
fi
# The engine tells when a backup starts and how it ended (see Backup.kt); a restore is told here.
case "$MODE" in
  auto) run_open backup "Automatic backup"; resumable backup-auto backup.sh auto; engine backup --auto ;;
  now) run_open backup "Backup"; resumable backup-now backup.sh now "$@"; engine backup "$@" ;;
  list) engine backups "$@" ;;
  status) engine backup-status ;;
  restore)
    what=
    for arg; do case "$arg" in --*) ;; *) what=$arg; break ;; esac; done
    run_open backup "Restore of $what"
    resumable backup-restore backup.sh restore "$@"
    event backup start "Restoring $what"
    if engine backup-restore "$@"; then run_end ok "Restored $what" "From its backup"; else exit $?; fi ;;
  parts) engine backup-parts "$@" ;;
  restic) direct restic "$@" ;;
  *) log "backup.sh: no such thing to do: $MODE (auto, now, list, status, restore, parts, restic)"; exit 2 ;;
esac
