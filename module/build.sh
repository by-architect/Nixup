# Builds the phone config (/sdcard/Andronix/phone.nix) and makes the phone match
# it, in nixos-rebuild's steps: the F-Droid index brought up to date (as a channel
# is), the config evaluated by Nix on the phone (types, assertions), the apps
# planned and checked, then downloaded and installed, the settings applied, and
# a new generation kept. When a step before the apps fails, nothing changes.
# Runs as root. Arguments: who started it (app, action, adb), then what to do:
#   switch   all of the above (the default)
#   dry      up to the plan and its checks: says what would change, changes nothing
#   update   switch, with the F-Droid index fetched again even when it is new enough
MODDIR=${0%/*}
BY=${1:-manual}
MODE=${2:-switch}
. "$MODDIR/common.sh"

mkdir -p "$DATA/home"

seed_config || fail "cannot create $CONFIG"

log "== F-Droid index"
if [ "$MODE" = update ]; then engine index --force; else engine index; fi \
  || log "Building with the F-Droid index there is (none: no F-Droid apps can be named)"
TREE=$DATA/fdroid/tree.json
[ -f "$TREE" ] || TREE=

# Nix keeps its caches in the module's data; it evaluates only, so it needs no
# store (dummy://) and nothing but nixpkgs' lib, packed in the module.
log "== Evaluating $CONFIG"
export HOME=$DATA/home TMPDIR=$DATA/tmp USER=root NIX_CONF_DIR=$DATA/home XDG_CACHE_HOME=$DATA/home/.cache
profile=$("$MODDIR/bin/nix-instantiate" --eval --strict --json --store dummy:// \
  -I nixpkgs="$MODDIR/nixpkgs" "$MODDIR/nix/profile.nix" --argstr configFile "$CONFIG" --argstr fdroidTree "$TREE") \
  || fail "the config did not build (Nix says why above); nothing was changed"
log "Profile: $profile"
echo "$profile" > "$PROFILE.new"

log "== Apps"
if [ "$MODE" = dry ]; then engine apply "$PROFILE.new" --dry-run; else engine apply "$PROFILE.new"; fi
case $? in
  0) ;;
  2) rm -f "$PROFILE.new"; fail "a check failed (see above); nothing was changed" ;;
  *) rm -f "$PROFILE.new"; fail "stopped partway (see above); build again to carry on" ;;
esac
if [ "$MODE" = dry ]; then
  rm -f "$PROFILE.new"
  log "Dry run: nothing was changed"
  exit 0
fi

log "== Activating"
mv -f "$PROFILE.new" "$PROFILE"
sh "$MODDIR/apply.sh" "$BY" || exit 1

new_generation
# The app schedules its update check, now that the config may have changed.
tell_app schedule &
