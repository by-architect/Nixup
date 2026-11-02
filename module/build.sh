# Builds the phone config and makes the phone match it, in nixos-rebuild's steps: the
# indexes of its repositories brought up to date (as channels are), the config evaluated
# by Nix on the phone (types, assertions), the apps planned and checked, then downloaded
# and installed, the settings applied, and a new generation kept. When a step before the
# apps fails, nothing changes.
# Runs as root. Arguments: who started it (app, action, adb), then what to do:
#   switch   all of the above (the default)
#   dry      up to the plan and its checks: says what would change, changes nothing
#   update   switch, with the indexes (and the releases of the apps from releases)
#            fetched again even when they are new enough, and a flake's inputs at their
#            newest (a flake on the phone has its flake.lock updated; one fetched from its
#            address is fetched again, at its newest commit); a configuration.nix alone
#            gets the newest nixpkgs too
# The config's Nix packages (its services' programs, environment.systemPackages) are
# fetched from cache.nixos.org before anything changes (fetch_packages in common.sh).
#   again    the last profile applied again as it is, nothing evaluated: after a build
#            on a PC (seraph-rebuild), whose config the phone may not have
# The config is what the app's Config picked (config_source in common.sh): a
# configuration.nix, built alone; a flake.nix, whose folder is the flake (as
# nixos-rebuild --flake reads /etc/nixos); or a flake's address, like github:owner/repo,
# fetched. Seraph keeps no config folder of its own.
MODDIR=${0%/*}
BY=${1:-manual}
MODE=${2:-switch}
. "$MODDIR/common.sh"

case "$MODE" in
  switch | dry | update) ;;
  again)
    [ -f "$PROFILE" ] || fail "there is no build to apply again"
    log "== The last build again"
    cp "$PROFILE" "$PROFILE.new"
    switch_to "$PROFILE.new" switch
    exit 0 ;;
  *) fail "build.sh: no such thing to do: $MODE (switch, dry, update, again)" ;;
esac

SRC=$(config_source)
[ -n "$SRC" ] || fail "there is no config yet: in the Seraph app, Config picks your configuration.nix or flake.nix (New makes one), or takes a flake's address, like github:you/phone"
# A phone set up before the config could be picked keeps the one it had, now as picked.
if [ -z "$SERAPH_SOURCE" ] && [ ! -s "$SOURCE" ]; then
  printf '%s\n' "$SRC" > "$SOURCE"
  log "The config is $SRC, as before (the app's Config picks another)"
fi

# What the source is: a file on the phone, or a flake's address; either may name the
# flake's configuration after a #.
WHERE=${SRC%%#*}
NAME=
[ "$WHERE" = "$SRC" ] || NAME=${SRC#*#}
CONFIG= FLAKE= DIR= REFRESH= LOCKED=
case "$WHERE" in
  /*)
    file=$(readable_path "$WHERE") \
      || fail "the config $WHERE is not there (moved, renamed, or its SD card out?): pick it again in the app's Config"
    case "${file##*/}" in
      flake.nix)
        # A flake is its whole folder, which Nix copies into its store: it must be a
        # folder of the config's own, not Download or the whole storage.
        DIR=${file%/*}
        kb=$(du -sk "$DIR" 2> /dev/null | cut -f1)
        [ "${kb:-0}" -le 51200 ] \
          || fail "$WHERE: its folder is the flake, and Nix copies all of it ($((kb / 1024)) MB): give flake.nix a folder of its own"
        # As a flake's address, where a space or a % would end or change it.
        FLAKE="path:$(printf '%s' "$DIR" | sed 's/%/%25/g; s/ /%20/g')" ;;
      *.nix)
        [ -z "$NAME" ] || fail "$SRC: a configuration.nix has no configurations to pick with #$NAME (a flake.nix has)"
        CONFIG=$file ;;
      *) fail "the config $WHERE is not a .nix file: pick a configuration.nix or a flake.nix" ;;
    esac ;;
  *)
    # A fetched flake's lock is its own, in its repository: Nix cannot write one there.
    FLAKE=$WHERE LOCKED=--no-write-lock-file
    [ "$MODE" = update ] && REFRESH=--refresh ;;
esac

# Nix keeps its caches and settings in the module's data.
nix_env

# configuration.nix alone: Nix evaluates it with nixpkgs' lib, packed in the module, and
# for a config that uses Nix packages, with the phone's copy of nixpkgs (NIXPKGS). It
# needs no store (dummy://): read-only, it works out the packages' store paths without
# writing any, and the phone fetches them after.
NIXPKGS=
nix_eval() {
  "$MODDIR/bin/nix-instantiate" --eval --strict --json --readonly-mode --store dummy:// -I nixpkgs="$MODDIR/nixpkgs" \
    "$MODDIR/nix/profile.nix" --argstr configFile "$CONFIG" --argstr nixpkgs "$NIXPKGS" "$@"
}

# The phone's copy of nixpkgs, as a NixOS channel: nixos-unstable (or the one
# $DATA/channel names), fetched the first time a config uses Nix packages, and again in an
# update. It is kept in Seraph's store, as a root of it.
channel() {
  url=$(cat "$DATA/channel" 2> /dev/null)
  [ -n "$url" ] || url=https://channels.nixos.org/nixos-unstable/nixexprs.tar.xz
  link=$DATA/channels/nixpkgs
  if [ ! -d "$link/" ] || [ "$MODE" = update ]; then
    log "== nixpkgs ($url)"
    nix_etc > /dev/null || { log "Nix could not get its /etc (the network's DNS servers): is the phone online?"; return 1; }
    path=$(nix_run "$MODDIR/bin/nix" --store "$DATA/nix" eval --raw --impure --option tarball-ttl 0 \
      --expr "builtins.fetchTarball \"$url\"") || return 1
    mkdir -p "$DATA/channels" "$DATA/nix/nix/var/nix/gcroots"
    ln -sfn "$path" "$DATA/nix/nix/var/nix/gcroots/seraph-channel"
    ln -sfn "$DATA/nix$path" "$link"
  fi
  NIXPKGS=$(readlink -f "$link")
}

# A flake, as `nixos-rebuild --flake`: Nix fetches its inputs (Seraph's options,
# nixpkgs' lib) as flake.lock pins them, and a flake named by its address itself, into a
# store of its own ($DATA/nix), in a namespace with its own /etc (nix_run). The
# repositories' package names come from the indexes here (SERAPH_REPOS, so --impure):
# always the latest, not pinned. A flake on the phone is named by its real folder: Nix
# takes no path through a symlink, and /sdcard is one (to /storage/emulated/0).
nix_flake() { nix_run "$MODDIR/bin/nix" --store "$DATA/nix" "$@" $LOCKED; }

# The configuration to build: the one named after the #, else the flake's only
# seraphConfigurations, or the one named after the phone (its device name), as NixOS
# takes the hostname.
flake_name() {
  [ -n "$NAME" ] && echo "$NAME" && return 0
  names=$(nix_flake eval --json $REFRESH "$FLAKE#seraphConfigurations" --apply builtins.attrNames) || return 1
  set -- $(echo "$names" | tr -d '[]"' | tr ',' ' ')
  [ $# -eq 1 ] && echo "$1" && return 0
  device=$(settings get global device_name < /dev/null)
  for name; do [ "$name" = "$device" ] && echo "$name" && return 0; done
  log "The flake has $# seraphConfigurations ($*), and none is named after this phone ($device): name one after a # in the app's Config"
  return 1
}

eval_repos() {
  if [ -n "$FLAKE" ]; then nix_flake eval --json --impure "$BUILT.repos"; else nix_eval --arg reposOnly true; fi
}
eval_profile() {
  if [ -n "$FLAKE" ]; then SERAPH_REPOS=$DATA/repos nix_flake eval --json --impure "$BUILT.profile"
  else nix_eval --argstr reposDir "$DATA/repos"; fi
}

GEN_SOURCE=$SRC GEN_LOCK=
if [ -n "$FLAKE" ]; then
  log "== Flake $WHERE"
  nix_etc || fail "Nix could not get its /etc (the network's DNS servers): is the phone online?"
  if [ "$MODE" = update ] && [ -n "$DIR" ]; then
    nix_flake flake update --flake "$FLAKE" || fail "flake.lock could not be updated (Nix says why above); nothing was changed"
  fi
  name=$(flake_name) || fail "the flake did not build (Nix says why above); nothing was changed"
  BUILT="$FLAKE#seraphConfigurations.$name.config.system.build"
  CONFIG="$WHERE#$name"
  GEN_SOURCE="$WHERE#$name"
  if [ -n "$DIR" ]; then
    GEN_LOCK=$DIR/flake.lock
  else
    # A fetched flake: the commit it was built at, and its lock, for the generation.
    meta=$DATA/tmp/flake-metadata.json GEN_LOCK=$DATA/tmp/flake.lock
    if nix_flake flake metadata --json $REFRESH "$FLAKE" > "$meta" 2> /dev/null; then
      metadata() { "$MODDIR/bin/nix" --store dummy:// eval --raw --impure --expr "let m = builtins.fromJSON (builtins.readFile $meta); in $1"; }
      revision=$(metadata 'm.revision or m.locked.rev or ""' 2> /dev/null)
      [ -n "$revision" ] && GEN_SOURCE="$WHERE#$name (commit $revision)"
      metadata 'builtins.toJSON m.locks' > "$GEN_LOCK" 2> /dev/null || rm -f "$GEN_LOCK"
    fi
  fi
fi

# The repositories are in the config: they come first (they need no package
# names), so their indexes are here when the whole config is evaluated.
log "== Repositories"
eval_repos > "$DATA/tmp/repos.json" \
  || fail "the config did not build (Nix says why above); nothing was changed"
if [ "$MODE" = update ]; then engine index --force "$DATA/tmp/repos.json"; else engine index "$DATA/tmp/repos.json"; fi \
  || log "Building with the indexes there are (a repository without one: its apps cannot be named)"

# A configuration.nix alone that uses Nix packages needs the phone's copy of nixpkgs (a
# flake has its own, as an input).
if [ -z "$FLAKE" ] && grep -q '"needsNixpkgs":true' "$DATA/tmp/repos.json"; then
  channel || fail "nixpkgs could not be fetched (Nix says why above); nothing was changed"
fi

log "== Evaluating $CONFIG"
profile=$(eval_profile) \
  || fail "the config did not build (Nix says why above); nothing was changed"
log "Profile: $profile"
# printf, not echo: Android's echo turns the profile's \\ and \n into other characters.
printf '%s\n' "$profile" > "$PROFILE.new"

switch_to "$PROFILE.new" "$MODE"
