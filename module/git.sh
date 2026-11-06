# The config from a git repository (GitHub's, or any other's): cloned onto the phone, into
# /data/adb/nixup/git/<repository>, with its flake.nix (or configuration.nix) as the config the
# build reads, as /etc/nixos is a clone on many NixOS machines; then brought up to date from the
# repository (pull: its new commits merged with what was changed here), or cloned again. build.sh
# builds a flake there as Nix builds one in a git repository: the files git tracks, with their
# changes, and its submodules when they were cloned too. Git is nixpkgs' (gitMinimal), fetched from cache.nixos.org into
# Nixup's store the first time, and run where /nix exists (the services' view).
# Runs as root. Arguments: who started it (app, shell, ...), then what to do:
#   clone <address> [--branch=<branch>] [--dir=<folder in it>] [--name=<configuration>] [--submodules]
#                     clones it (and removes a clone made before), and makes its config the one the
#                     build reads; --submodules: its submodules (the repositories in it) too, which
#                     its pulls then bring up to date, and the build reads. The address: https://github.com/you/phone, github:you/phone,
#                     you/phone (GitHub), gitlab:you/phone, or any git address
#   pull              merges the repository's new commits (local changes are put aside, then back)
#   reclone           clones the same repository again, from scratch (local changes go)
#   status [--json]   the clone: its address, branch, commit and local changes (--json: for the app)
# A private repository's token is the one Nix takes for it: /data/adb/nixup/secrets/
# nix-access-tokens, a line like github.com=ghp_…; git gets it for each command, never in a file.
MODDIR=${0%/*}
BY=${1:-manual}
MODE=${2:-status}
[ $# -ge 2 ] && shift 2
. "$MODDIR/common.sh"

CLONES=$DATA/git
STATE=$CLONES/clone
GITROOT=$DATA/nix/nix/var/nix/gcroots/nixup-git

# What the clone is (key=value lines: url, branch, dir, name, submodules, path, pulled).
value() { sed -n "s/^$1=//p" "$STATE" 2> /dev/null | head -n 1; }
# Whether its submodules were cloned too: a clone made before the choice (no submodules= line)
# always had them.
submodules_of() { grep -q '^submodules=' "$STATE" 2> /dev/null && [ "$(value submodules)" != 1 ] && return 1; return 0; }
option() { for arg in $ARGS; do case "$arg" in "--$1="*) printf '%s' "${arg#--$1=}"; return 0 ;; esac; done; }

# The address git clones: github:you/phone and you/phone are GitHub's.
address_of() {
  case "$1" in
    github:*) printf 'https://github.com/%s\n' "${1#github:}" ;;
    gitlab:*) printf 'https://gitlab.com/%s\n' "${1#gitlab:}" ;;
    sourcehut:* | srht:*) printf 'https://git.sr.ht/%s\n' "${1#*:}" ;;
    *://* | *@*:*) printf '%s\n' "$1" ;;
    */*) printf 'https://github.com/%s\n' "$1" ;;
    *) return 1 ;;
  esac
}

# nixpkgs' git, in Nixup's store (a root of it, so it stays): GIT is its program.
git_package() {
  path=$(readlink "$GITROOT" 2> /dev/null)
  if [ -z "$path" ] || [ ! -x "$DATA/nix$path/bin/git" ]; then
    log "== Git (nixpkgs' gitMinimal, from cache.nixos.org; once)"
    channel || return 1
    nixpkgs=$(readlink "$DATA/nix/nix/var/nix/gcroots/nixup-channel")
    path=$(nix_store build --no-link --print-out-paths --impure \
      --expr "(import $nixpkgs { config = { }; overlays = [ ]; }).gitMinimal") || return 1
    mkdir -p "${GITROOT%/*}"
    ln -sfn "$path" "$GITROOT"
  fi
  GIT=$path/bin/git
}

# Git's settings, from its environment (never a file): the token of each host in Nix's
# access-tokens as its password, GitHub's ssh addresses (a submodule's) as https, and a name for
# the merges it makes.
git_environment() {
  export HOME=$DATA/home GIT_TERMINAL_PROMPT=0 GIT_ASKPASS=/system/bin/false
  export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt GIT_SSL_CAINFO=/etc/ssl/certs/ca-certificates.crt
  export GIT_AUTHOR_NAME=Nixup GIT_COMMITTER_NAME=Nixup GIT_AUTHOR_EMAIL=nixup@localhost GIT_COMMITTER_EMAIL=nixup@localhost
  n=0
  set_config() { export "GIT_CONFIG_KEY_$n=$1" "GIT_CONFIG_VALUE_$n=$2"; n=$((n + 1)); }
  set_config url.https://github.com/.insteadOf git@github.com:
  set_config url.https://github.com/.insteadOf ssh://git@github.com/
  set_config safe.directory '*'
  tokens=$DATA/secrets/nix-access-tokens
  if [ -s "$tokens" ]; then
    for pair in $(tr -s ' \n' '\n\n' < "$tokens"); do
      host=${pair%%=*} token=${pair#*=}
      [ -n "$host" ] && [ -n "$token" ] && [ "$host" != "$pair" ] || continue
      set_config "http.https://$host/.extraheader" "Authorization: Basic $(printf 'x-access-token:%s' "$token" | base64 | tr -d '\n')"
    done
  fi
  export GIT_CONFIG_COUNT=$n
}

# Runs git in the services' view (where /nix is), with Nixup's /etc (DNS, certificates).
git_run() {
  script=$DATA/tmp/git-$$.sh
  {
    printf 'exec %s' "$(quote "$GIT")"
    for arg; do printf ' %s' "$(quote "$arg")"; done
    printf '\n'
  } > "$script"
  "$BUSYBOX" sh "$DATA/units/view.sh" "$script"
  status=$?
  rm -f "$script"
  return $status
}
quote() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }

# Git and what it needs, ready: its package, Nixup's /etc and view, its settings.
git_ready() {
  [ -n "$BUSYBOX" ] || { log "There is no busybox (Magisk's or KernelSU's) to run git with"; return 1; }
  nix_env
  nix_etc > /dev/null || { log "Nixup's /etc could not be written (the network's DNS servers): is the phone online?"; return 1; }
  git_package || { log "Git could not be fetched (Nix says why above)"; return 1; }
  git_environment
}

# The config in a clone, as the build reads it: flake.nix in its folder (with #name), else
# configuration.nix.
config_in() {
  folder=$1${2:+/$2}
  if [ -f "$folder/flake.nix" ]; then printf '%s\n' "$folder/flake.nix${3:+#$3}"
  elif [ -f "$folder/configuration.nix" ] && [ -z "$3" ]; then printf '%s\n' "$folder/configuration.nix"
  else return 1; fi
}

# A fresh clone of $url, into $CLONES/<repository>: made apart first, so a clone that fails
# (offline, a wrong address) leaves the one there as it was.
clone() {
  name=${url%/}
  name=${name##*/}
  name=${name##*:}
  name=${name%.git}
  case "$name" in "" | . | .. | *[!A-Za-z0-9._-]*) fail "$url: no repository name in it" ;; esac
  fresh=$CLONES/.new
  rm -rf "$fresh"
  mkdir -p "$CLONES"
  log "Cloning $url${branch:+ ($branch)}${submodules:+, with its submodules}"
  git_run clone --filter=blob:none ${submodules:+--recurse-submodules --also-filter-submodules} ${branch:+--branch "$branch"} -- "$url" "$fresh" \
    || { rm -rf "$fresh"; fail "$url could not be cloned (git says why above; a private repository, or submodule, needs its token in $DATA/secrets/nix-access-tokens)"; }
  src=$(config_in "$fresh" "$dir" "$cname") \
    || { rm -rf "$fresh"; fail "$url${dir:+ ($dir)} has no flake.nix${cname:+ to build #$cname from} or configuration.nix: say its folder"; }
  # Only one clone: the one before goes, with what was changed in it.
  for old in "$CLONES"/*; do [ -d "$old" ] && rm -rf "$old"; done
  mv "$fresh" "$CLONES/$name"
  src=$CLONES/$name${src#"$fresh"}
  printf '%s\n' "url=$url" "branch=$branch" "dir=$dir" "name=$cname" "submodules=$submodules" "path=$CLONES/$name" "pulled=$(date +%s)" > "$STATE"
  printf '%s\n' "$src" > "$SOURCE"
  rm -f "$DATA/build-from"
  commit=$(git_run -C "$CLONES/$name" log -1 --format='%h %s' 2> /dev/null)
  log "The config is $src"
  run_end ok "Cloned ${url#https://}" "Commit $commit; the config is ${src#"$CLONES/"}"
}

ARGS="$*"
case "$MODE" in
  clone)
    first=
    for arg; do case "$arg" in --*) ;; *) first=$arg; break ;; esac; done
    [ -n "$first" ] || fail "git.sh clone: which repository?"
    url=$(address_of "$first") || fail "$first: give a repository's address, like https://github.com/you/phone or github:you/phone"
    branch=$(option branch) dir=$(option dir) cname=$(option name) submodules=
    case " $ARGS " in *" --submodules "*) submodules=1 ;; esac
    dir=${dir#/}
    dir=${dir%/}
    case "$dir" in *..*) fail "--dir: a folder inside the repository" ;; esac
    run_open git "Clone"
    event git start "Cloning ${url#https://}"
    git_ready || fail "git is not ready (see above); nothing was changed"
    clone ;;
  reclone)
    url=$(value url) branch=$(value branch) dir=$(value dir) cname=$(value name) submodules=
    submodules_of && submodules=1
    [ -n "$url" ] || fail "there is no clone to make again: clone a repository first"
    run_open git "Reclone"
    event git start "Cloning ${url#https://} again"
    git_ready || fail "git is not ready (see above); nothing was changed"
    clone ;;
  pull)
    top=$(value path) url=$(value url)
    [ -n "$top" ] && [ -d "$top/.git" ] || fail "there is no clone to pull into: clone a repository first"
    run_open git "Pull"
    event git start "Pulling ${url#https://}"
    git_ready || fail "git is not ready (see above); nothing was changed"
    before=$(git_run -C "$top" rev-parse HEAD)
    log "Pulling $url"
    # Submodules are left to the update below, which fetches only the commits the merged branch
    # names: a pull's own --recurse-submodules fetches those every branch's new commits name, and
    # one no longer on the submodule's server (an old branch's) fails the whole pull.
    if ! git_run -C "$top" pull --no-rebase --autostash --no-edit --recurse-submodules=no; then
      # A merge that cannot be made goes back to how it was, with the local changes.
      git_run -C "$top" merge --abort > /dev/null 2>&1
      fail "the repository's new commits and the changes made here do not merge (git says where above): nothing was changed; Reclone takes the repository's as they are"
    fi
    after=$(git_run -C "$top" rev-parse HEAD)
    sed -i "s/^pulled=.*/pulled=$(date +%s)/" "$STATE"
    # Submodules the new commits added (or moved): cloned, at the commits they name.
    if submodules_of && [ -f "$top/.gitmodules" ]; then
      git_run -C "$top" submodule update --init --recursive --filter=blob:none \
        || fail "the submodules could not be brought up to date (git says why above; a private one needs its token)"
    fi
    if [ "$before" = "$after" ]; then
      run_end info "Already up to date" "${url#https://}: no new commits"
    else
      count=$(git_run -C "$top" rev-list --count "$before..$after")
      last=$(git_run -C "$top" log -1 --format='%h %s')
      log "$count new commits; now at $last"
      run_end ok "Pulled $count new commit$([ "$count" = 1 ] || echo s)" "${url#https://}: now at $last"
    fi ;;
  status)
    top=$(value path)
    if [ -z "$top" ] || [ ! -d "$top/.git" ]; then
      case " $* " in *" --json "*) echo '{"clone":false}' ;; *) echo "No clone: git.sh clone <address> makes one" ;; esac
      exit 0
    fi
    # Without a network: git and the view are there once a clone was made.
    path=$(readlink "$GITROOT" 2> /dev/null)
    GIT=$path/bin/git
    commit= subject= time= changes=
    if [ -n "$path" ] && [ -x "$DATA/nix$GIT" ] && [ -f "$DATA/units/view.sh" ] && [ -n "$BUSYBOX" ]; then
      git_environment
      line=$(git_run -C "$top" log -1 --format='%h%x09%ct%x09%s' 2> /dev/null)
      commit=$(printf '%s' "$line" | cut -f1) time=$(printf '%s' "$line" | cut -f2) subject=$(printf '%s' "$line" | cut -f3-)
      changes=$(git_run -C "$top" status --porcelain 2> /dev/null | grep -c .)
      branch=$(git_run -C "$top" rev-parse --abbrev-ref HEAD 2> /dev/null)
    fi
    case " $* " in
      *" --json "*)
        esc() { printf '%s' "$1" | tr '\t\n\r' '   ' | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }
        pulled=$(value pulled)
        printf '{"clone":true,"url":"%s","branch":"%s","dir":"%s","name":"%s","submodules":%s,"path":"%s","commit":"%s","subject":"%s","time":%s,"changes":%s,"pulled":%s}\n' \
          "$(esc "$(value url)")" "$(esc "${branch:-$(value branch)}")" "$(esc "$(value dir)")" "$(esc "$(value name)")" "$(submodules_of && echo true || echo false)" "$(esc "$top")" \
          "$(esc "$commit")" "$(esc "$subject")" "${time:-0}" "${changes:-0}" "${pulled:-0}" ;;
      *)
        echo "Clone of $(value url) in $top$(submodules_of && echo ', with its submodules')"
        echo "Branch ${branch:-$(value branch)}, commit $commit $subject"
        echo "${changes:-0} files changed here; pulled $(date -d "@$(value pulled)" '+%F %T' 2> /dev/null)" ;;
    esac ;;
  *) fail "git.sh: no such thing to do: $MODE (clone, pull, reclone, status)" ;;
esac
