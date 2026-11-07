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
#            build's packages, nixpkgs and git stay, and what the users installed with nix profile
#            or nix-env; an older generation fetches its own again)
#   generation <n> [dry]
#            generation n's profile applied again, nothing evaluated, as nix-env
#            --switch-generation and nixos-rebuild --rollback go back to one: its settings,
#            apps (at their newest versions) and services; it becomes the newest generation
#            (dry: only says what would change)
# and, after any of switch, dry, update, again and generation:
#   --specialisation <name>
#            applies the build's specialisation <name> (nix/specialisation.nix) instead of the
#            config itself, as nixos-rebuild --specialisation; the phone keeps it, across
#            restarts too, until a build picks another. Without it, switch, dry and update apply
#            the config itself, and again and generation the specialisation they had
#   --no-specialisation
#            the config itself, for again and generation
# switch, update and dry take these too (system.autoUpgrade's timer gives them, and nixup-rebuild's --file and
# --flake; an automatic upgrade keeps the specialisation in use, unless one is asked for):
#   --source=<config>   another config than the one picked, for this build (a flake's address)
#   --channel=<url>     the nixexprs.tar.xz a configuration.nix alone gets its nixpkgs from
#   --pull              a picked config in a git clone (the app's Git) gets its repository's new
#                       commits first (git.sh pull; not with dry, which changes nothing)
#   --gc                once the build has worked, as gc (not with dry)
# The config is what the app's Config picked (config_source in common.sh): a
# configuration.nix, built alone; a flake.nix, whose folder is the flake (as
# nixos-rebuild --flake reads /etc/nixos); or a flake's address, like github:owner/repo,
# fetched. Nixup keeps no config folder of its own.
MODDIR=${0%/*}
# The specialisation asked for (--specialisation <name>, --no-specialisation), wherever it is among the arguments; the
# others stay in their order, each one whole (a --source= path may have spaces in it): taken from the front, put back
# at the end.
SPECIALISATION= SPECIALISATION_GIVEN= SPECIALISATION_ARGS=
left=$#
while [ "$left" -gt 0 ]; do
  arg=$1
  shift
  left=$((left - 1))
  case "$arg" in
    --specialisation)
      SPECIALISATION= SPECIALISATION_GIVEN=1
      if [ "$left" -gt 0 ]; then SPECIALISATION=$1; shift; left=$((left - 1)); fi ;;
    --specialisation=*) SPECIALISATION=${arg#*=} SPECIALISATION_GIVEN=1 ;;
    --no-specialisation) SPECIALISATION= SPECIALISATION_GIVEN=1 ;;
    *) set -- "$@" "$arg" ;;
  esac
done
BY=${1:-manual}
MODE=${2:-switch}
. "$MODDIR/common.sh"
case "$SPECIALISATION" in
  *[!A-Za-z0-9_.-]* | [!A-Za-z0-9]*) fail "--specialisation: \"$SPECIALISATION\" is not a specialisation's name (letters, digits and _ . -)" ;;
esac
[ -n "$SPECIALISATION_GIVEN" ] && SPECIALISATION_ARGS=${SPECIALISATION:+--specialisation $SPECIALISATION}
[ -n "$SPECIALISATION_GIVEN" ] && [ -z "$SPECIALISATION" ] && SPECIALISATION_ARGS=--no-specialisation

# switch's and update's options (see above), kept for a run picked up after a restart (resumable).
OPTIONS= PULL= THEN_GC=
case "$MODE" in
  switch | update | dry)
    if [ $# -ge 2 ]; then shift 2; else set --; fi
    for option; do
      case "$option" in
        --source=*) NIXUP_SOURCE=${option#--source=} ;;
        --channel=*) NIXUP_CHANNEL=${option#--channel=} ;;
        --pull) [ "$MODE" != dry ] || fail "build.sh dry: --pull changes the clone, and a dry run changes nothing"; PULL=1 ;;
        --gc) [ "$MODE" != dry ] || fail "build.sh dry: --gc frees up space, and a dry run changes nothing"; THEN_GC=1 ;;
        *) fail "build.sh $MODE: no such option: $option (--source=, --channel=, --pull, --gc)" ;;
      esac
      OPTIONS="$OPTIONS $option"
    done
    export NIXUP_SOURCE NIXUP_CHANNEL
    # An automatic upgrade (system.autoUpgrade) keeps the specialisation in use, as again does: a phone put in
    # another mode by hand stays in it. A run picked up after a restart has it among its arguments already.
    if [ -n "$OPTIONS" ] && [ "$BY" = timer ] && [ -z "$SPECIALISATION_GIVEN" ] && [ -s "$SPECIALISATION_IN_USE" ] && system_current; then
      SPECIALISATION=$(head -n 1 "$SPECIALISATION_IN_USE") SPECIALISATION_GIVEN=1
      SPECIALISATION_ARGS="--specialisation $SPECIALISATION"
    fi ;;
esac

# The run: its log, and what the app is told (see run_open in common.sh).
case "$MODE" in
  switch | update) if [ -n "$OPTIONS" ] && { [ "$BY" = timer ] || [ "$BY" = resume ]; }; then
      run_open build "Automatic upgrade"; event build start "Automatic upgrade (system.autoUpgrade)" "$(config_source)"
    elif [ "$MODE" = switch ]; then
      run_open build "Build"; event build start "Building the config" "$(config_source)"
    else
      run_open build "Upgrade"; event build start "Upgrading: a build with everything at its newest" "$(config_source)"
    fi ;;
  dry) run_open build "Dry run"; event build start "Dry run: what a build would change" "$(config_source)" ;;
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
  # (Its options as they were given, "$@", each one whole: a --source= path may have spaces in it.)
  switch | update) resumable build build.sh "$MODE" "$@" $SPECIALISATION_ARGS ;;
  again) resumable build build.sh "$MODE" $SPECIALISATION_ARGS ;;
  generation) resumable build build.sh generation "$3" ${4:+"$4"} $SPECIALISATION_ARGS ;;
esac

case "$MODE" in
  switch | dry | update) ;;
  again)
    [ -f "$PROFILE" ] || fail "there is no build to apply again"
    log "== The last build again"
    # The whole of it (with its specialisations), and the specialisation in use unless another is asked for.
    if system_current; then
      cp "$SYSTEM" "$NEW"
      [ -n "$SPECIALISATION_GIVEN" ] || SPECIALISATION=$(head -n 1 "$SPECIALISATION_IN_USE" 2> /dev/null)
    else
      cp "$PROFILE" "$NEW"
    fi
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
    GEN_LOCK= GEN_CONFIG=
    [ -f "$g/flake.lock" ] && GEN_LOCK=$g/flake.lock
    [ -f "$g/configuration.nix" ] && GEN_CONFIG=$g/configuration.nix
    [ -n "$GEN_SOURCE" ] || GEN_SOURCE="generation $n"
    # The specialisation it had, unless another is asked for.
    [ -n "$SPECIALISATION_GIVEN" ] || SPECIALISATION=$(head -n 1 "$g/specialisation" 2> /dev/null)
    cp "$g/profile.json" "$NEW"
    switch_to "$NEW" "$how"
    exit 0 ;;
  gc)
    # As nix-collect-garbage: what no root keeps goes (the last build's packages, nixpkgs and git stay).
    [ -d "$DATA/nix/nix/store" ] || { log "Nixup's store is empty: nothing to free"; run_end info "Nothing to free up" "Nixup's store is empty"; exit 0; }
    # Not while a build runs (a timer's, the app's, a PC's): what it fetched has no root until it applies. A build's
    # own --gc comes from it, once it is done.
    if running=$(running_of build) && [ "$running" != "$PPID" ]; then
      fail "a build is running (process $running): free up space once it is done"
    fi
    nix_env
    before=$(du -sk "$DATA/nix" 2> /dev/null | cut -f1)
    log "== Freeing up space: the Nix packages the last build does not use"
    # In Nixup's view, as its shells see the store: there the users' own profiles (nix profile, in
    # their /home) are roots, which Nix would take for links to nothing outside of it, and remove.
    nix_view nix store gc || fail "Nix could not free up space (it says why above)"
    after=$(du -sk "$DATA/nix" 2> /dev/null | cut -f1)
    freed=$(( (${before:-0} - ${after:-0}) / 1024 ))
    log "Freed $freed MB; Nixup's store has $(( ${after:-0} / 1024 )) MB"
    run_end ok "Freed up $freed MB" "Nixup's store has $(( ${after:-0} / 1024 )) MB left"
    exit 0 ;;
  *) fail "build.sh: no such thing to do: $MODE (switch, dry, update, again, generation, gc)" ;;
esac

# --pull: a picked config in a git clone gets its repository's new commits first (as the app's Pull and build).
# (Its real folder: a config picked before the rename names it through /data/adb/seraph, a link to $DATA.)
if [ -n "$PULL" ] && [ -z "$NIXUP_SOURCE" ]; then
  picked=$(config_source)
  case "$(readlink -f "${picked%%#*}" 2> /dev/null)" in
    "$DATA/git/"*) sh "$MODDIR/git.sh" "$BY" pull < /dev/null || fail "the repository's new commits could not be pulled (see above); nothing was changed" ;;
  esac
fi

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

GEN_SOURCE=$SRC GEN_LOCK= GEN_CONFIG=
# A configuration.nix alone: the file, which the generation keeps with system.copySystemConfiguration.
[ -z "$FLAKE" ] && [ -f "$SRC" ] && GEN_CONFIG=$SRC
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
    meta=$RUN_TMP/flake-metadata.json GEN_LOCK=$RUN_TMP/flake.lock
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
if ! eval_repos > "$RUN_TMP/repos.json" 2> "$RUN_TMP/repos.err"; then
  if [ -z "$FLAKE" ] && grep -q "file 'home-manager[/']" "$RUN_TMP/repos.err"; then
    channel "$MODE" || fail "nixpkgs could not be fetched (Nix says why above); nothing was changed"
    home_manager_channel "$MODE" || fail "Home Manager could not be fetched (Nix says why above); nothing was changed"
    eval_repos > "$RUN_TMP/repos.json" \
      || fail "the config did not build (Nix says why above); nothing was changed"
  elif [ -z "$FLAKE" ] && grep -q 'nixup:needs-nixpkgs' "$RUN_TMP/repos.err"; then
    channel "$MODE" || fail "nixpkgs could not be fetched (Nix says why above); nothing was changed"
    eval_repos > "$RUN_TMP/repos.json" \
      || fail "the config did not build (Nix says why above); nothing was changed"
  else
    cat "$RUN_TMP/repos.err" >&2
    fail "the config did not build (Nix says why above); nothing was changed"
  fi
else
  cat "$RUN_TMP/repos.err" >&2
fi
if [ "$MODE" = update ]; then engine index --force "$RUN_TMP/repos.json"; else engine index "$RUN_TMP/repos.json"; fi \
  || log "Building with the indexes there are (a repository without one: its apps cannot be named)"

# A configuration.nix alone that uses Nix packages needs the phone's copy of nixpkgs (a
# flake has its own, as an input); with update, the newest, and Home Manager's with it.
if [ -z "$FLAKE" ] && grep -q '"needsNixpkgs":true' "$RUN_TMP/repos.json"; then
  channel "$MODE" || fail "nixpkgs could not be fetched (Nix says why above); nothing was changed"
  if [ -n "$HOME_MANAGER" ] && [ "$MODE" = update ]; then
    home_manager_channel update || fail "Home Manager could not be fetched (Nix says why above); nothing was changed"
  fi
fi

log "== Evaluating $CONFIG"
# Into its file at once: a profile is past what one argument can hold (128 KiB) with a few specialisations or Home
# Manager users, and Android's printf is a program of its own.
eval_profile > "$NEW" \
  || fail "the config did not build (Nix says why above); nothing was changed"
log_file "Profile: " "$NEW"

# The secret files the config's options name (files from its folder, or text): put apart from the
# profile, in a folder only root can read, for switch_to to put in place, as when a PC sends them. This
# run's own (not incoming, where a PC's build arrives meanwhile).
if grep -q '"secrets":\["' "$NEW"; then
  IN=$RUN_TMP/in
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
# --gc: what no build keeps goes, now that this one has worked.
[ -n "$THEN_GC" ] && sh "$MODDIR/build.sh" "$BY" gc < /dev/null
exit 0
