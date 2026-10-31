# Makes the phone match the profile (/data/adb/andronix/profile.json): reads each
# setting, changes only what differs, reads it back, and logs what it did.
# Runs as root; the first argument says who started it (boot, action, adb).
MODDIR=${0%/*}
DATA=/data/adb/andronix
PROFILE=$DATA/profile.json
LOG=$DATA/apply.log
BY=${1:-manual}

mkdir -p "$DATA"
# The profile the module was built with, until another one is put in its place.
[ -f "$PROFILE" ] || cp "$MODDIR/profile.json" "$PROFILE"
# Keep the log small: past 64 KB the old one is set aside.
[ -f "$LOG" ] && [ "$(stat -c %s "$LOG")" -gt 65536 ] && mv -f "$LOG" "$LOG.old"

log() {
  echo "$*"
  echo "$(date '+%F %T') [$BY] $*" >> "$LOG"
}

# Shown under the module's name in the Magisk app.
set_status() {
  sed -i "s|^description=.*|description=$1|" "$MODDIR/module.prop"
}

fail() {
  log "FAILED: $*"
  set_status "✘ $* (log: $LOG)"
  exit 1
}

# The profile is compact JSON from nix-instantiate, so a value can be picked out
# by its key until the module has a real JSON reader.
grep -q '"schemaVersion":1[,}]' "$PROFILE" || fail "the profile is not schemaVersion 1"

dark=$(grep -o '"darkMode":[a-z]*' "$PROFILE" | cut -d: -f2)
case "$dark" in
  true) want=yes ;;
  false) want=no ;;
  null | "")
    log "Dark mode: not managed"
    set_status "Dark mode: not managed ($(date '+%F %H:%M'), $BY)"
    exit 0 ;;
  *) fail "darkMode is '$dark', not true, false or null" ;;
esac

night() { cmd uimode night | sed -n 's/^Night mode: //p'; }

now=$(night)
if [ "$now" = "$want" ]; then
  log "Dark mode: $want (already)"
else
  cmd uimode night "$want" > /dev/null
  [ "$(night)" = "$want" ] || fail "dark mode stayed '$(night)', wanted '$want'"
  log "Dark mode: $now -> $want"
fi
set_status "✔ Dark mode: $want ($(date '+%F %H:%M'), $BY)"
