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
#            on a PC (nixup-rebuild), whose config the phone may not have
#   gc       frees up space, as nix-collect-garbage: the Nix packages no root keeps go (the last
#            build's packages, nixpkgs and git stay; an older generation fetches its own again)
#   generation <n> [dry]
#            generation n's profile applied again, nothing evaluated, as nix-env
#            --switch-generation and nixos-rebuild --rollback go back to one: its settings,
#            apps (at their newest versions) and services; it becomes the newest generation
#            (dry: only says what would change)
# The config is what the app's Config picked (config_source in common.sh): a
# configuration.nix, built alone; a flake.nix, whose folder is the flake (as
# nixos-rebuild --flake reads /etc/nixos); or a flake's address, like github:owner/repo,
# fetched. Nixup keeps no config folder of its own.
MODDIR=${0%/*}
BY=${1:-manual}
MODE=${2:-switch}
. "$MODDIR/common.sh"

# The run: its log, and what the app is told (see run_open in common.sh).
case "$MODE" in
  switch) run_open build "Build"; event build start "Building the config" "$(config_source)" ;;
  dry) run_open build "Dry run"; event build start "Dry run: what a build would change" "$(config_source)" ;;
  update) run_open build "Upgrade"; event build start "Upgrading: a build with everything at its newest" "$(config_source)" ;;
  again) run_open build "Build"; event build start "Applying the last build again" ;;
  generation)
    run_open build "Generation $3"
    if [ "$4" = dry ]; then event build start "Trying generation $3"; else event build start "Going back to generation $3"; fi ;;
  gc) run_open build "Freeing up space"; event build start "Freeing up space" ;;
esac
# The profile it builds, its own (a dry run next to a build does not touch the build's), until
# switch_to makes it the phone's.
NEW=$PROFILE.new-$$
at_exit 'rm -f "$NEW"'
# A restart (or a kill) in the middle: it starts again after (see resume.sh).
case "$MODE" in
  switch | update | again) resumable build build.sh "$MODE" ;;
  generation) resumable build build.sh generation "$3" ${4:+"$4"} ;;
esac

case "$MODE" in
  switch | dry | update) ;;
  again)
    [ -f "$PROFILE" ] || fail "there is no build to apply again"
    log "== The last build again"
    cp "$PROFILE" "$NEW"
    switch_to "$NEW" switch
    exit 0 ;;
  generation)
    n=$3 how=${4:-switch}
    case "$n" in "" | *[!0-9]*) fail "build.sh generation: which one (its number)?" ;; esac
    case "$how" in switch | dry) ;; *) fail "build.sh generation $n: switch or dry, not $how" ;; esac
    g=$DATA/generations/$n
    [ -f "$g/profile.json" ] || fail "there is no generation $n"
    log "== Generation $n again"
    # The new generation says what generation n was built from, and keeps its flake's lock.
    GEN_SOURCE=$(head -n 1 "$g/source" 2> /dev/null)
    GEN_BUILT_ON=$(head -n 1 "$g/built-on" 2> /dev/null)
    GEN_LOCK=
    [ -f "$g/flake.lock" ] && GEN_LOCK=$g/flake.lock
    [ -n "$GEN_SOURCE" ] || GEN_SOURCE="generation $n"
    cp "$g/profile.json" "$NEW"
    switch_to "$NEW" "$how"
    exit 0 ;;
  gc)
    # As nix-collect-garbage: what no root keeps goes (the last build's packages, nixpkgs and git stay).
    [ -d "$DATA/nix/nix/store" ] || { log "Nixup's store is empty: nothing to free"; run_end info "Nothing to free up" "Nixup's store is empty"; exit 0; }
    nix_env
    before=$(du -sk "$DATA/nix" 2> /dev/null | cut -f1)
    log "== Freeing up space: the Nix packages the last build does not use"
    nix_store store gc || fail "Nix could not free up space (it says why above)"
    after=$(du -sk "$DATA/nix" 2> /dev/null | cut -f1)
    freed=$(( (${before:-0} - ${after:-0}) / 1024 ))
    log "Freed $freed MB; Nixup's store has $(( ${after:-0} / 1024 )) MB"
    run_end ok "Freed up $freed MB" "Nixup's store has $(( ${after:-0} / 1024 )) MB left"
    exit 0 ;;
  *) fail "build.sh: no such thing to do: $MODE (switch, dry, update, again, generation, gc)" ;;
esac

SRC=$(config_source)
[ -n "$SRC" ] || fail "there is no config yet: in the Nixup app, Config picks your configuration.nix or flake.nix (New makes one), or takes a flake's address, like github:you/phone"
# A phone set up before the config could be picked keeps the one it had, now as picked.
if [ -z "$NIXUP_SOURCE" ] && [ ! -s "$SOURCE" ]; then
  printf '%s\n' "$SRC" > "$SOURCE"
  log "The config is $SRC, as before (the app's Config picks another)"
fi

# A path in a flake's address, where a space or a % would end or change it.
address() { printf '%s' "$1" | sed 's/%/%25/g; s/ /%20/g'; }

# The clone (git.sh's, in $DATA/git) that a folder is in, if it is in one.
clone_of() {
  case "$1/" in
    "$DATA/git/"?*/*)
      rest=${1#"$DATA/git/"}
      top=$DATA/git/${rest%%/*}
      [ -d "$top/.git" ] && echo "$top" ;;
    *) return 1 ;;
  esac
}

# A clone's commit (its HEAD), and the address it was cloned from, read without git.
clone_commit() {
  head=$(cat "$1/.git/HEAD" 2> /dev/null) || return 1
  case "$head" in
    "ref: "*)
      ref=${head#ref: }
      cat "$1/.git/$ref" 2> /dev/null || grep " $ref\$" "$1/.git/packed-refs" 2> /dev/null | cut -d' ' -f1 ;;
    *) echo "$head" ;;
  esac
}
clone_url() { sed -n '/^\[remote "origin"\]/,/^\[/s/^[[:space:]]*url = //p' "$1/.git/config" 2> /dev/null | head -n 1; }

# What the source is: a file on the phone, or a flake's address; either may name the
# flake's configuration after a #.
WHERE=${SRC%%#*}
NAME=
[ "$WHERE" = "$SRC" ] || NAME=${SRC#*#}
CONFIG= FLAKE= DIR= REFRESH= LOCKED= CLONE=
case "$WHERE" in
  /*)
    file=$(readable_path "$WHERE") \
      || fail "the config $WHERE is not there (moved, renamed, or its SD card out?): pick it again in the app's Config"
    case "${file##*/}" in
      flake.nix)
        DIR=${file%/*}
        if CLONE=$(clone_of "$DIR"); then
          # A clone the app made (git.sh): built as Nix builds a flake in a git repository, from
          # the files git tracks (with their changes), and its submodules when they were cloned too.
          sub=${DIR#"$CLONE"}
          sub=${sub#/}
          query=
          # (A clone made before that choice, with no submodules= line, always had them.)
          if [ -f "$CLONE/.gitmodules" ] && { grep -qx 'submodules=1' "$DATA/git/clone" 2> /dev/null || ! grep -q '^submodules=' "$DATA/git/clone" 2> /dev/null; }; then
            query=submodules=1
          fi
          [ -n "$sub" ] && query="${query:+$query&}dir=$(address "$sub")"
          FLAKE="git+file://$(address "$CLONE")${query:+?$query}"
          # Nix writes no flake.lock into it: it would `git add` it, with a git it cannot run. Its
          # lock is the repository's (a pull brings a newer one); an update finds the newest inputs.
          LOCKED=--no-write-lock-file
          [ "$MODE" = update ] && REFRESH="--refresh --recreate-lock-file"
        else
          # A flake is its whole folder, which Nix copies into its store: it must be a
          # folder of the config's own, not Download or the whole storage.
          kb=$(du -sk "$DIR" 2> /dev/null | cut -f1)
          [ "${kb:-0}" -le 51200 ] \
            || fail "$WHERE: its folder is the flake, and Nix copies all of it ($((kb / 1024)) MB): give flake.nix a folder of its own"
          FLAKE="path:$(address "$DIR")"
        fi ;;
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
# for a config that uses Nix packages, with the phone's copy of nixpkgs (NIXPKGS); one that
# imports Home Manager's NixOS module (<home-manager/nixos>) has the phone's copy of Home
# Manager (HOME_MANAGER) as <home-manager>. It needs no store (dummy://): read-only, it
# works out the packages' store paths without writing any, and the phone fetches them after.
nix_eval() {
  # With nixpkgs, in Nixup's store: the small files the config makes (a service's settings)
  # are derivations the phone builds, which the evaluation writes there.
  store="--readonly-mode --store dummy://"
  [ -n "$NIXPKGS" ] && [ "$1" != --arg ] && store="--read-write-mode --store $DATA/nix"
  "$MODDIR/bin/nix-instantiate" --eval --strict --json $store -I nixpkgs="$MODDIR/nixpkgs" \
    ${HOME_MANAGER:+-I home-manager="$HOME_MANAGER"} \
    "$MODDIR/nix/profile.nix" --argstr configFile "$CONFIG" --argstr nixpkgs "$NIXPKGS" "$@"
}

# A flake, as `nixos-rebuild --flake`: Nix fetches its inputs (Nixup's options,
# nixpkgs' lib) as flake.lock pins them, and a flake named by its address itself, into a
# store of its own ($DATA/nix), in a namespace with its own /etc (nix_run). The
# repositories' package names come from the indexes here (NIXUP_REPOS, so --impure):
# always the latest, not pinned. A flake on the phone is named by its real folder: Nix
# takes no path through a symlink, and /sdcard is one (to /storage/emulated/0).
nix_flake() { nix_run "$MODDIR/bin/nix" --store "$DATA/nix" "$@" $LOCKED; }

# Where the flake keeps its configurations: nixupConfigurations, or seraphConfigurations in a
# flake made when Nixup was called Seraph, which builds as it is.
flake_configs() {
  if ! nix_flake eval $REFRESH "$FLAKE#nixupConfigurations" --apply 'c: true' > /dev/null 2>&1 \
    && nix_flake eval "$FLAKE#seraphConfigurations" --apply 'c: true' > /dev/null 2>&1; then
    echo seraphConfigurations
  else
    echo nixupConfigurations
  fi
}

# The configuration to build: the one named after the #, else the flake's only
# nixupConfigurations, or the one named after the phone (its device name), as NixOS
# takes the hostname.
flake_name() {
  [ -n "$NAME" ] && echo "$NAME" && return 0
  names=$(nix_flake eval --json $REFRESH "$FLAKE#$CONFIGS" --apply builtins.attrNames) || return 1
  set -- $(echo "$names" | tr -d '[]"' | tr ',' ' ')
  [ $# -eq 1 ] && echo "$1" && return 0
  device=$(settings get global device_name < /dev/null)
  for name; do [ "$name" = "$device" ] && echo "$name" && return 0; done
  # Said on stderr: what this says (its stdout) is the name.
  if [ $# -eq 0 ]; then
    log "The flake has no $CONFIGS (one made by a file it imports, from a submodule not cloned? Clone it again with its submodules)" >&2
  else
    log "The flake has $# $CONFIGS ($*), and none is named after this phone ($device): name one after a # in the app's Config" >&2
  fi
  return 1
}

eval_repos() {
  if [ -n "$FLAKE" ]; then nix_flake eval --json --impure "$BUILT.repos"; else nix_eval --arg reposOnly true; fi
}
eval_profile() {
  # SERAPH_REPOS too, for a flake.lock that still pins Nixup from when it was called Seraph.
  if [ -n "$FLAKE" ]; then NIXUP_REPOS=$DATA/repos SERAPH_REPOS=$DATA/repos nix_flake eval --json --impure "$BUILT.profile"
  else nix_eval --argstr reposDir "$DATA/repos"; fi
}
eval_secrets() {
  if [ -n "$FLAKE" ]; then NIXUP_REPOS=$DATA/repos SERAPH_REPOS=$DATA/repos nix_flake eval --json --impure "$BUILT.secretsToDeliver"
  else nix_eval --argstr reposDir "$DATA/repos" --argstr part secretsToDeliver; fi
}

GEN_SOURCE=$SRC GEN_LOCK=
if [ -n "$FLAKE" ]; then
  log "== Flake $WHERE"
  nix_etc || fail "Nix could not get its /etc (the network's DNS servers): is the phone online?"
  if [ "$MODE" = update ] && [ -n "$DIR" ] && [ -z "$CLONE" ]; then
    nix_flake flake update --flake "$FLAKE" || fail "flake.lock could not be updated (Nix says why above); nothing was changed"
  fi
  CONFIGS=$(flake_configs)
  name=$(flake_name) || fail "the flake did not build (Nix says why above); nothing was changed"
  BUILT="$FLAKE#$CONFIGS.$name.config.system.build"
  CONFIG="$WHERE#$name"
  GEN_SOURCE="$WHERE#$name"
  if [ -n "$DIR" ] && [ -z "$CLONE" ]; then
    GEN_LOCK=$DIR/flake.lock
  else
    # A fetched flake or a clone: the commit it was built at, and the lock its build used, for the
    # generation.
    meta=$DATA/tmp/flake-metadata.json GEN_LOCK=$DATA/tmp/flake.lock
    if nix_flake flake metadata --json $REFRESH "$FLAKE" > "$meta" 2> /dev/null; then
      metadata() { "$MODDIR/bin/nix" --store dummy:// eval --raw --impure --expr "let m = builtins.fromJSON (builtins.readFile $meta); in $1"; }
      revision=$(metadata 'm.revision or m.locked.rev or ""' 2> /dev/null)
      [ -n "$revision" ] && GEN_SOURCE="$WHERE#$name (commit $revision)"
      metadata 'builtins.toJSON m.locks' > "$GEN_LOCK" 2> /dev/null || rm -f "$GEN_LOCK"
    fi
    if [ -n "$CLONE" ]; then
      # A clone's generation says the repository and its commit, rather than where the clone is.
      commit=$(clone_commit "$CLONE" | cut -c1-12)
      sub=${DIR#"$CLONE"}
      GEN_SOURCE="$(clone_url "$CLONE")${sub:+ (${sub#/})}#$name${commit:+ (commit $commit)}"
    fi
  fi
fi

# The repositories are in the config: they come first (they need no package
# names), so their indexes are here when the whole config is evaluated. A
# configuration.nix alone gets the phone's copy of nixpkgs when it has it; one that
# imports NixOS's modules (modulesPath) needs it, and has it fetched first; one that
# imports Home Manager's NixOS module (<home-manager/nixos>) has Home Manager fetched
# first too, with nixpkgs (its users' modules need it).
log "== Repositories"
[ -z "$FLAKE" ] && [ -d "$DATA/channels/nixpkgs/" ] && NIXPKGS=$(readlink -f "$DATA/channels/nixpkgs")
[ -z "$FLAKE" ] && [ -d "$DATA/channels/home-manager/" ] && HOME_MANAGER=$(readlink -f "$DATA/channels/home-manager")
if ! eval_repos > "$DATA/tmp/repos.json" 2> "$DATA/tmp/repos.err"; then
  if [ -z "$FLAKE" ] && grep -q "file 'home-manager[/']" "$DATA/tmp/repos.err"; then
    channel "$MODE" || fail "nixpkgs could not be fetched (Nix says why above); nothing was changed"
    home_manager_channel "$MODE" || fail "Home Manager could not be fetched (Nix says why above); nothing was changed"
    eval_repos > "$DATA/tmp/repos.json" \
      || fail "the config did not build (Nix says why above); nothing was changed"
  elif [ -z "$FLAKE" ] && grep -q 'nixup:needs-nixpkgs' "$DATA/tmp/repos.err"; then
    channel "$MODE" || fail "nixpkgs could not be fetched (Nix says why above); nothing was changed"
    eval_repos > "$DATA/tmp/repos.json" \
      || fail "the config did not build (Nix says why above); nothing was changed"
  else
    cat "$DATA/tmp/repos.err" >&2
    fail "the config did not build (Nix says why above); nothing was changed"
  fi
else
  cat "$DATA/tmp/repos.err" >&2
fi
if [ "$MODE" = update ]; then engine index --force "$DATA/tmp/repos.json"; else engine index "$DATA/tmp/repos.json"; fi \
  || log "Building with the indexes there are (a repository without one: its apps cannot be named)"

# A configuration.nix alone that uses Nix packages needs the phone's copy of nixpkgs (a
# flake has its own, as an input); with update, the newest, and Home Manager's with it.
if [ -z "$FLAKE" ] && grep -q '"needsNixpkgs":true' "$DATA/tmp/repos.json"; then
  channel "$MODE" || fail "nixpkgs could not be fetched (Nix says why above); nothing was changed"
  if [ -n "$HOME_MANAGER" ] && [ "$MODE" = update ]; then
    home_manager_channel update || fail "Home Manager could not be fetched (Nix says why above); nothing was changed"
  fi
fi

log "== Evaluating $CONFIG"
profile=$(eval_profile) \
  || fail "the config did not build (Nix says why above); nothing was changed"
log "Profile: $profile"
# printf, not echo: Android's echo turns the profile's \\ and \n into other characters.
printf '%s\n' "$profile" > "$NEW"

# The secret files the config's options name (files from its folder, or text): put apart from the
# profile, in a folder only root can read, for switch_to to put in place, as when a PC sends them.
if grep -q '"secrets":\["' "$NEW"; then
  IN=$DATA/incoming
  rm -rf "$IN" && mkdir -p "$IN" && chmod 700 "$IN"
  at_exit 'rm -rf "$IN"'
  (umask 077; eval_secrets > "$IN/secrets.json") \
    || fail "the config's secret files could not be read (Nix says why above); nothing was changed"
  engine secrets "$IN/secrets.json" "$IN/secrets" ${FLAKE:+--store=$DATA/nix} \
    || fail "a secret file of the config could not be had (see above); nothing was changed"
  rm -f "$IN/secrets.json"
  SECRETS_IN=$IN/secrets
fi

switch_to "$NEW" "$MODE"
