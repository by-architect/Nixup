#!/system/bin/sh
# Tells what happened: a line in Seraph's journal of events (/data/adb/seraph/events, which the app's
# Logs tab shows), and, unless it is quiet, the app's notification of it. The module's scripts, the
# engine, the services' loops and the services themselves (as $SERAPH_NOTIFY) run it, as root.
# Arguments:
#   kind    what it is about: build, update, backup, service, connection, git
#   level   how it goes: start (it began: shown until it ends), ok, info, warn, error
#   title   what happened, in a few words
#   text    more about it (optional)
#   key     what a newer notification of the same thing takes the place of (optional; the kind)
# SERAPH_BY: who started it (app, job, boot, adb, ssh, ...); SERAPH_RUN: the run it is part of, whose
# log is /data/adb/seraph/logs/<run>.log; SERAPH_QUIET=1: into the journal only.
kind=$1 level=$2 title=$3 text=$4 key=${5:-$1}
events=/data/adb/seraph/events

# As a JSON string's inside: tabs and line breaks as spaces, \ and " escaped.
esc() { printf '%s' "$1" | tr '\t\n\r' '   ' | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }
quiet=
[ "$SERAPH_QUIET" = 1 ] && quiet=',"quiet":true'
line="{\"time\":$(date +%s),\"kind\":\"$(esc "$kind")\",\"level\":\"$(esc "$level")\",\"title\":\"$(esc "$title")\",\"text\":\"$(esc "$text")\",\"key\":\"$(esc "$key")\",\"by\":\"$(esc "$SERAPH_BY")\",\"run\":\"$(esc "$SERAPH_RUN")\"$quiet}"

mkdir -p "${events%/*}"
printf '%s\n' "$line" >> "$events"
# The newest 1000 stay.
if [ "$(stat -c %s "$events" 2> /dev/null || echo 0)" -gt 262144 ]; then
  tail -n 1000 "$events" > "$events.new" && mv -f "$events.new" "$events"
fi
[ -n "$quiet" ] && exit 0
# The app's receiver is not exported: only root and the system reach it. In the background, so
# nothing waits for am.
am broadcast --user current -f 0x20 -n dev.seraph.manager/.EventsReceiver -a dev.seraph.manager.action.EVENT \
  --es json "$line" < /dev/null > /dev/null 2>&1 &
exit 0
