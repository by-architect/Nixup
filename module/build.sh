# Builds the phone config (/sdcard/Seraph/configuration.nix) and makes the phone match
# it, in nixos-rebuild's steps: the indexes of its repositories brought up to date
# (as channels are), the config evaluated by Nix on the phone (types, assertions),
# the apps planned and checked, then downloaded and installed, the settings
# applied, and a new generation kept. When a step before the apps fails, nothing
# changes.
# Runs as root. Arguments: who started it (app, action, adb), then what to do:
#   switch   all of the above (the default)
#   dry      up to the plan and its checks: says what would change, changes nothing
#   update   switch, with the indexes (and the releases of the apps from releases)
#            fetched again even when they are new enough, and a flake's
#            flake.lock updated (its inputs' newest versions)
# The config is /sdcard/Seraph/flake.nix when there is one (as nixos-rebuild --flake),
# else /sdcard/Seraph/configuration.nix; the app's Build from can choose either.
MODDIR=${0%/*}
BY=${1:-manual}
MODE=${2:-switch}
. "$MODDIR/common.sh"

mkdir -p "$DATA/home"

seed_config || fail "cannot create $CONFIG"

# Nix keeps its caches and settings in the module's data.
export HOME=$DATA/home TMPDIR=$DATA/tmp USER=root NIX_CONF_DIR=$DATA/home XDG_CACHE_HOME=$DATA/home/.cache
nix_conf

# configuration.nix alone: Nix evaluates it with nixpkgs' lib, packed in the module;
# it needs no store (dummy://).
nix_eval() {
  "$MODDIR/bin/nix-instantiate" --eval --strict --json --store dummy:// -I nixpkgs="$MODDIR/nixpkgs" \
    "$MODDIR/nix/profile.nix" --argstr configFile "$CONFIG" "$@"
}

# A flake.nix, as `nixos-rebuild --flake`: Nix fetches its inputs (Seraph's options,
# nixpkgs' lib) as flake.lock pins them, into a store of its own ($DATA/nix), in a
# namespace with its own /etc (nix_run). The repositories' package names come from
# the indexes here (SERAPH_REPOS, so --impure): always the latest, not pinned.
# The flake is named by its real folder: Nix takes no path through a symlink, and
# /sdcard is one (to /storage/emulated/0).
FLAKE=
if [ "$(build_from)" = flake ]; then
  [ -f "$CONFIG_DIR/flake.nix" ] || fail "Build from is flake, and $CONFIG_DIR has no flake.nix (the app's Build from: configuration.nix builds that alone)"
  FLAKE="path:$(readlink -f "$CONFIG_DIR")"
else
  [ -f "$CONFIG" ] || fail "Build from is configuration.nix, and $CONFIG_DIR has none"
fi
nix_flake() { nix_run "$MODDIR/bin/nix" --store "$DATA/nix" "$@"; }

# The configuration to build: the flake's only seraphConfigurations, or the one
# named after the phone (its device name), as NixOS takes the hostname.
flake_name() {
  names=$(nix_flake eval --json "$FLAKE#seraphConfigurations" --apply builtins.attrNames) || return 1
  set -- $(echo "$names" | tr -d '[]"' | tr ',' ' ')
  [ $# -eq 1 ] && echo "$1" && return 0
  device=$(settings get global device_name < /dev/null)
  for name; do [ "$name" = "$device" ] && echo "$name" && return 0; done
  log "flake.nix has $# seraphConfigurations ($*), and none is named after this phone ($device)"
  return 1
}

eval_repos() {
  if [ -n "$FLAKE" ]; then nix_flake eval --json --impure "$BUILT.repos"; else nix_eval --arg reposOnly true; fi
}
eval_profile() {
  if [ -n "$FLAKE" ]; then SERAPH_REPOS=$DATA/repos nix_flake eval --json --impure "$BUILT.profile"
  else nix_eval --argstr reposDir "$DATA/repos"; fi
}

if [ -n "$FLAKE" ]; then
  log "== Flake $CONFIG_DIR/flake.nix"
  nix_etc || fail "Nix could not get its /etc (the network's DNS servers): is the phone online?"
  if [ "$MODE" = update ]; then
    nix_flake flake update --flake "$FLAKE" || fail "flake.lock could not be updated (Nix says why above); nothing was changed"
  fi
  name=$(flake_name) || fail "the flake did not build (Nix says why above); nothing was changed"
  BUILT="$FLAKE#seraphConfigurations.$name.config.system.build"
  CONFIG="$CONFIG_DIR/flake.nix#$name"
fi

# The repositories are in the config: they come first (they need no package
# names), so their indexes are here when the whole config is evaluated.
log "== Repositories"
eval_repos > "$DATA/tmp/repos.json" \
  || fail "the config did not build (Nix says why above); nothing was changed"
if [ "$MODE" = update ]; then engine index --force "$DATA/tmp/repos.json"; else engine index "$DATA/tmp/repos.json"; fi \
  || log "Building with the indexes there are (a repository without one: its apps cannot be named)"

log "== Evaluating $CONFIG"
profile=$(eval_profile) \
  || fail "the config did not build (Nix says why above); nothing was changed"
log "Profile: $profile"
echo "$profile" > "$PROFILE.new"

log "== Apps"
case "$MODE" in
  dry) engine apply "$PROFILE.new" --dry-run ;;
  update) engine apply "$PROFILE.new" --force ;;
  *) engine apply "$PROFILE.new" ;;
esac
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
