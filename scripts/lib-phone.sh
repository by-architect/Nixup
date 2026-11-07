# The phone nixup-rebuild and nixup-config work on (loaded by them, not run): through adb, over USB or the network, or
# through ssh. The script that loads it has die and say.
#
#   phone_pick <target>       target: adb (the phones adb sees are listed and one is asked for; with none, one is
#                             connected over the network), adb:<serial> (a host:port adb has not got is connected),
#                             or [user@]host for ssh. Sets PHONE_SERIAL (adb) or PHONE_HOST (ssh), PHONE_BY (adb or
#                             ssh) and PHONE_LABEL (the phone's model and serial, for messages)
#   phone_run <command>       a command on the phone, as adb's shell user or the ssh user; what it says comes here
#   phone_root <command>      the same, as root (su -c)
#   phone_root_out <command>  as root, its output as it is (for files: tar)
#   phone_send <file> <path>  a file from here into a file on the phone that root alone can read
# Asked on the terminal (/dev/tty); with none, the only phone adb sees is taken, and several stop it.

phone_state="${XDG_CONFIG_HOME:-$HOME/.config}/nixup"

# A word for the phone's shell, quoted.
phone_quote() { local s=${1//\'/\'\\\'\'}; printf "'%s'" "$s"; }
phone_tty() { { : < /dev/tty; } 2> /dev/null; }
# The answer typed on the terminal, or the default for an empty one.
phone_ask() {
  local answer=""
  printf '%s' "$1" > /dev/tty
  IFS= read -r answer < /dev/tty || answer=""
  printf '%s\n' "${answer:-${2:-}}"
}

# Connects the phone at host[:port] (5555 when no port is given: Nixup's android.adb.port, often) with adb connect,
# pairing first when the phone asks for it (Android's Wireless debugging); kept for the next time.
phone_connect() {
  local addr=$1 out pair code
  [[ $addr == *:* ]] || addr=$addr:5555
  out="$(adb connect "$addr" 2>&1 || true)"
  if [[ $out != *"connected to"* || $out == *fail* ]]; then
    phone_tty || die "could not connect to $addr: $out"
    say "Could not connect to $addr: $out"
    [[ $(phone_ask "Pair with the phone first (Wireless debugging → Pair device with pairing code)? [Y/n] " y) == [Yy]* ]] \
      || die "no phone to work on"
    pair="$(phone_ask "Pairing address (the ip:port shown with the code): ")"
    code="$(phone_ask "Pairing code: ")"
    adb pair "$pair" "$code" >&2 || die "pairing with $pair failed"
    out="$(adb connect "$addr" 2>&1 || true)"
    [[ $out == *"connected to"* && $out != *fail* ]] || die "could not connect to $addr: $out"
  fi
  # adb names it as it was connected to.
  timeout 30 adb -s "$addr" wait-for-device < /dev/null || die "$addr connected, but adb does not list it under that name (adb devices names it)"
  say "Connected to $addr"
  mkdir -p "$phone_state" && printf '%s\n' "$addr" > "$phone_state/address"
  PHONE_SERIAL=$addr
}

phone_pick() {
  local target=$1 s id model how reply last n i waiting
  local -a seen=() serials=() labels=()
  local -A by_id=()
  PHONE_SERIAL="" PHONE_HOST="" PHONE_BY=adb PHONE_LABEL=""
  case $target in
    adb:*)
      PHONE_SERIAL=${target#adb:}
      if ! adb devices | awk 'NR > 1 && $2 == "device" { print $1 }' | grep -qxF "$PHONE_SERIAL"; then
        if [[ $PHONE_SERIAL == *:* ]]; then phone_connect "$PHONE_SERIAL"; else die "adb does not see $PHONE_SERIAL (adb devices lists the phones it sees)"; fi
      fi ;;
    adb)
      command -v adb > /dev/null || die "there is no adb here (android-tools)"
      mapfile -t seen < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
      # One phone seen several ways (USB, and the network) is one phone, taken by USB.
      for s in "${seen[@]}"; do
        id="$(adb -s "$s" shell getprop ro.serialno < /dev/null 2> /dev/null | tr -d '\r')"
        id=${id:-$s}
        [[ -n ${by_id[$id]:-} && ${by_id[$id]} != *:* ]] || by_id[$id]=$s
      done
      mapfile -t serials < <(printf '%s\n' "${by_id[@]}" | grep . | sort)
      for s in "${serials[@]}"; do
        model="$(adb -s "$s" shell getprop ro.product.model < /dev/null 2> /dev/null | tr -d '\r')"
        if [[ $s == *:* ]]; then how=network; else how=USB; fi
        labels+=("$s  (${model:-a phone}, $how)")
      done
      if ! phone_tty; then
        ((${#serials[@]})) || die "adb sees no phone: connect it (USB debugging on), or give --target-host"
        ((${#serials[@]} == 1)) || die "adb sees ${#serials[@]} phones (${serials[*]}): pick one with --target-host adb:<serial>"
        PHONE_SERIAL=${serials[0]}
      else
        waiting="$(adb devices | awk 'NR > 1 && $2 == "unauthorized" { print $1 }' | tr '\n' ' ')"
        [[ -z $waiting ]] || say "Waiting for you on the phone's screen ($waiting): allow USB debugging there, then run this again to see it."
        if ((${#serials[@]})); then say "Phones adb sees:"; else say "adb sees no phone."; fi
        for i in "${!labels[@]}"; do say "  $((i + 1))  ${labels[i]}"; done
        n=$((${#serials[@]} + 1))
        last="$(cat "$phone_state/address" 2> /dev/null || true)"
        say "  $n  Connect a phone over the network (adb connect${last:+; last: $last})"
        while true; do
          reply="$(phone_ask "Phone [1-$n, Enter: 1, q: stop]: " 1)"
          [[ $reply != q ]] || die "stopped"
          [[ $reply =~ ^[0-9]+$ ]] && ((reply >= 1 && reply <= n)) && break
          say "Type a number from 1 to $n."
        done
        if ((reply == n)); then
          say "The phone's address: Nixup's adb port (android.adb.port, often 5555), or Developer options → Wireless debugging."
          reply="$(phone_ask "Address (host or ip, with :port)${last:+ [$last]}: " "$last")"
          [[ -n $reply ]] || die "no address given"
          phone_connect "$reply"
        else
          PHONE_SERIAL=${serials[reply - 1]}
        fi
      fi ;;
    *) PHONE_HOST=$target PHONE_BY=ssh ;;
  esac
  if [[ -n $PHONE_SERIAL ]]; then
    model="$(adb -s "$PHONE_SERIAL" shell getprop ro.product.model < /dev/null 2> /dev/null | tr -d '\r')"
    PHONE_LABEL="${model:-the phone} ($PHONE_SERIAL)"
  else
    PHONE_LABEL="the phone ($PHONE_HOST, over ssh)"
  fi
}

phone_run() {
  if [[ -n $PHONE_SERIAL ]]; then adb -s "$PHONE_SERIAL" shell -T "$1" < /dev/null
  else ssh -o BatchMode=yes "$PHONE_HOST" "$1" < /dev/null
  fi
}
phone_root() {
  if [[ -n $PHONE_SERIAL ]]; then adb -s "$PHONE_SERIAL" shell -T "su -c $(phone_quote "$1")" < /dev/null
  else ssh -o BatchMode=yes "$PHONE_HOST" "su -c $(phone_quote "$1")" < /dev/null
  fi
}
phone_root_out() {
  if [[ -n $PHONE_SERIAL ]]; then adb -s "$PHONE_SERIAL" exec-out "su -c $(phone_quote "$1")" < /dev/null
  else ssh -o BatchMode=yes "$PHONE_HOST" "su -c $(phone_quote "$1")" < /dev/null
  fi
}
phone_send() {
  local command="umask 077; cat > $(phone_quote "$2")"
  if [[ -n $PHONE_SERIAL ]]; then adb -s "$PHONE_SERIAL" shell -T "su -c $(phone_quote "$command")" < "$1"
  else ssh -o BatchMode=yes "$PHONE_HOST" "su -c $(phone_quote "$command")" < "$1"
  fi
}
