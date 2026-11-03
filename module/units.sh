# The config's services (the last build's systemd.services units), by hand, as systemctl does
# on NixOS: what they are doing, and a start, stop or restart, which lasts until the next build
# or restart (they start the services as the config says). The engine does it (see Units.kt).
# Runs as root. Arguments: who started it (app, shell, ...), then what to do:
#   list [--json]                    each service and how it is (running, stopped, done, failed);
#                                    --json: as one line of JSON, for the app, not logged
#   start | stop | restart <service> starts, stops or restarts it
#   retry <service>                  tries to start it again in a while, as after a boot it did not
#                                    start at (after 30 s, 1.5 and 4 minutes; told if it never does)
#   log <service> [lines]            the end of its log (200 lines)
MODDIR=${0%/*}
BY=${1:-manual}
MODE=${2:-list}
[ $# -ge 2 ] && shift 2
. "$MODDIR/common.sh"

case "$MODE" in
  list) CLASSPATH="$MODDIR/app.apk" exec /system/bin/app_process /system/bin dev.seraph.manager.engine.Main units "$@" ;;
  start | stop | restart | retry)
    [ -n "$1" ] || { log "units.sh $MODE: which service?"; exit 2; }
    engine unit "$MODE" "$1" ;;
  log)
    case "$1" in
      "" | */* | .*) log "units.sh log: which service?"; exit 2 ;;
    esac
    lines=${2:-200}
    case "$lines" in *[!0-9]* | "") lines=200 ;; esac
    [ -f "$DATA/units/$1/log" ] || { echo "$1 has no log yet"; exit 0; }
    tail -n "$lines" "$DATA/units/$1/log" ;;
  *) log "units.sh: no such thing to do: $MODE (list, start, stop, restart, retry, log)"; exit 2 ;;
esac
