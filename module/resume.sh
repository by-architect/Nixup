# Picks up what a restart (or a kill) broke off: a build, an update, a backup or a restore (see
# resumable in common.sh), each run again from its start (what was done goes fast: the apps installed
# are up to date, restic skips what it has), once the phone can: unlocked (the apps' data is out of
# reach until then), and online for a build or an update. One that was broken off again after it was
# picked up (a build once, the others 3 times) is only told about: a build that restarts the phone
# would only do it again. One broken off more than 3 days ago is let go.
# Runs as root. Argument: who started it: boot (service.sh: it waits as long as it takes), or job
# (the app's 15-minute check: it does not wait, and leaves what cannot run now for the next one).
MODDIR=${0%/*}
WHO=${1:-boot}
BY=resume
. "$MODDIR/common.sh"

ls "$PENDING" 2> /dev/null | grep -q . || exit 0
# One at a time: a boot's and the job's could meet.
lock=$DATA/tmp/resume.lock
mkdir "$lock" 2> /dev/null || { [ -d "/proc/$(cat "$lock/pid" 2> /dev/null)" ] && exit 0; rm -rf "$lock"; mkdir "$lock"; }
echo $$ > "$lock/pid"
trap 'rm -rf "$lock"' EXIT

until [ "$(getprop sys.user.0.ce_available)" = true ]; do
  [ "$WHO" = boot ] || exit 0
  sleep 10
done

# Online: Seraph's /etc gets the network's DNS servers (the engine says so when there are none).
online() { nix_etc > /dev/null 2>&1; }

for name in build update backup-restore backup-now backup-auto; do
  file=$PENDING/$name
  [ -f "$file" ] || continue
  started=$(sed -n 1p "$file")
  tries=$(sed -n 2p "$file")
  profile=$(sed -n 3p "$file")
  sed -n '4,$p' "$file" > "$file.args"
  set --
  while IFS= read -r arg; do set -- "$@" "$arg"; done < "$file.args"
  rm -f "$file.args"
  script=$1
  shift
  case "$name" in
    build) what="the build" most=1 kind=build ;;
    update) what="the update" most=3 kind=update ;;
    backup-restore) what="the restore" most=3 kind=backup ;;
    *) what="the backup" most=3 kind=backup ;;
  esac
  case "$script" in build.sh | remote.sh | update.sh | backup.sh) ;; *) rm -f "$file"; continue ;; esac
  if [ "${tries:-0}" -ge "$most" ]; then
    rm -f "$file"
    event "$kind" warn "${what#the } was broken off again" "Started $(date -d "@$started" '+%F %H:%M' 2> /dev/null); it is not started again by itself: start it by hand"
    continue
  fi
  if [ $(( $(date +%s) - ${started:-0} )) -gt 259200 ]; then
    rm -f "$file"
    SERAPH_QUIET=1 event "$kind" info "${what#the } broken off long ago: let go" "Started $(date -d "@$started" '+%F %H:%M' 2> /dev/null)"
    continue
  fi
  case "$name" in
    build | update)
      waited=0
      until online; do
        [ "$WHO" = boot ] && [ "$waited" -lt 3600 ] || continue 2
        sleep 30
        waited=$((waited + 30))
      done ;;
  esac
  # Its run writes it again, with this try counted, until it ends.
  rm -f "$file"
  event "$kind" info "Picking up $what a restart broke off" "Started $(date -d "@$started" '+%F %H:%M' 2> /dev/null); try $((tries + 1))"
  SERAPH_PROFILE=$profile SERAPH_RESUMED=$((tries + 1)) sh "$MODDIR/$script" resume "$@" < /dev/null
done
exit 0
