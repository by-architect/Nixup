# Makes the phone match the profile (/data/adb/andronix/profile.json): reads each
# setting, changes only what differs, reads it back, and logs what it did. An
# option that left the config gets its value from before the module back.
# Runs as root; the first argument says who started it (boot, action, app, adb).
MODDIR=${0%/*}
BY=${1:-manual}
. "$MODDIR/common.sh"
. "$MODDIR/restore.sh"

# The profile the module was built with, until one is built on the phone.
[ -f "$PROFILE" ] || cp "$MODDIR/profile.json" "$PROFILE"

# The profile is compact JSON from nix-instantiate, so a value can be picked out
# by its key until the module has a real JSON reader.
grep -q '"schemaVersion":1[,}]' "$PROFILE" || fail "the profile is not schemaVersion 1"

dark=$(grep -o '"darkMode":[a-z]*' "$PROFILE" | cut -d: -f2)
case "$dark" in
  true) want=yes ;;
  false) want=no ;;
  null | "") want= ;;
  *) fail "darkMode is '$dark', not true, false or null" ;;
esac

night() { cmd uimode night < /dev/null | sed -n 's/^Night mode: //p'; }

if [ -z "$want" ]; then
  # Not in the config (any more): back to what it was before the module.
  restore_dark_mode | while read -r line; do log "$line"; done
  log "Dark mode: not managed"
  set_status "Dark mode: not managed ($(date '+%F %H:%M'), $BY)"
  exit 0
fi

now=$(night)
remember darkMode "$now"
if [ "$now" = "$want" ]; then
  log "Dark mode: $want (already)"
else
  cmd uimode night "$want" < /dev/null > /dev/null
  [ "$(night)" = "$want" ] || fail "dark mode stayed '$(night)', wanted '$want'"
  log "Dark mode: $now -> $want"
fi
set_status "✔ Dark mode: $want ($(date '+%F %H:%M'), $BY)"
