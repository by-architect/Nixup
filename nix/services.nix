# Services with NixOS's names (services.<name>), so a module that sets one works on a NixOS machine
# too: each is a module that makes systemd.services units, as NixOS's do, which the phone runs from
# Nix packages (see nix/units.nix). Here: Tailscale; Syncthing is in nix/syncthing.nix.
#
# Each has all of NixOS's options for it, with the same names, types and meanings. One the phone
# cannot do is there too, so a module shared with NixOS builds, but setting it to anything but its
# default stops the build and says why; one with nothing to do on a phone (a firewall to open)
# takes its value and does nothing.
#
# Tailscale, as NixOS's services.tailscale: tailscaled, run by root in userspace mode (it takes no
# VPN slot and changes no routes, so the Tailscale app can stay), and tailscaled-up, which gives it
# the config's settings with `tailscale up --reset` at each build: what the config leaves out goes
# back to Tailscale's default. Its name on the tailnet is networking.hostName, or the phone's device
# name. Its first build shows a link to log in with, unless authKeyFile is set.
{ config, lib, pkgs, ... }:
let
  inherit (lib) mkOption types;
  cfg = config.services.tailscale;
  secretFiles = import ./secret-files.nix { inherit lib; };
  # The auth key's file on the phone: put there by the build, when it is a file from the config's folder.
  keyFile = secretFiles.path config "services.tailscale.authKeyFile" cfg.authKeyFile;

  socket = "/run/tailscale/tailscaled.sock";
  # `tailscale set`'s flags: the config's, and the peer relay's port.
  setFlags = cfg.extraSetFlags ++ lib.optional (cfg.relayServerPort != null) "--relay-server-port=${toString cfg.relayServerPort}";
  params =lib.concatStringsSep "&" (lib.mapAttrsToList (key: value:
    "${key}=${if builtins.isBool value then lib.boolToString value else toString value}"
  ) (lib.filterAttrs (_: value: value != null) (removeAttrs cfg.authKeyParameters [ "_module" ])));
  # Its name on the tailnet: the config's, or the phone's device name as the tailnet takes names.
  hostName =
    if config.networking.hostName != "" then lib.escapeShellArg config.networking.hostName
    else ''"$(/system/bin/settings get global device_name | tr 'A-Z' 'a-z' | tr -cs 'a-z0-9-' '-' | sed 's/^-*//; s/-*$//')"'';
in
{
  options.services.tailscale = {
    enable = lib.mkEnableOption ''
      Tailscale's daemon (tailscaled), as NixOS's services.tailscale: run by root, in userspace mode
      (interfaceName), so it takes no VPN slot and changes no routes, and the Tailscale app can stay.
      The tailnet reaches the phone's own services through it (adb, ssh). Its name there is
      networking.hostName, or the phone's device name. Its first build shows a link to log in with,
      unless authKeyFile is set'';

    package = lib.mkPackageOption pkgs "tailscale" { };

    port = mkOption {
      type = types.port;
      default = 41641;
      description = "The UDP port for tunnel traffic (direct connections to other devices); 0: any free port.";
    };

    interfaceName = mkOption {
      type = types.str;
      default = "userspace-networking";
      description = ''
        The interface for tunnel traffic, as on NixOS. On a phone only "userspace-networking", the
        default here (NixOS's is "tailscale0"): a TUN interface would need the phone's VPN slot and
        routes, which the Tailscale app has.
      '';
    };

    permitCertUid = mkOption {
      type = types.nullOr types.nonEmptyStr;
      default = null;
      description = "The user allowed to fetch the node's TLS certificates, as on NixOS.";
    };

    disableTaildrop = mkOption {
      type = types.bool;
      default = false;
      description = "Turn Taildrop (sending files between devices) off, as on NixOS.";
    };

    disableUpstreamLogging = mkOption {
      type = types.bool;
      default = false;
      description = "Send no debug logs to Tailscale, as on NixOS.";
    };

    openFirewall = mkOption {
      type = types.bool;
      default = false;
      description = "Open the firewall for port, as on NixOS. A phone has no firewall in its way, so here it does nothing.";
    };

    useRoutingFeatures = mkOption {
      type = types.enum [ "none" "client" "server" "both" ];
      default = "none";
      example = "server";
      description = ''
        As on NixOS, where it sets what the kernel needs for subnet routers and exit nodes (loose
        reverse path filtering, IP forwarding). In userspace mode tailscaled forwards by itself, so
        here it sets nothing; the flags themselves (--advertise-exit-node, --advertise-routes=…,
        --exit-node=…) go in extraUpFlags.
      '';
    };

    authKeyFile = mkOption {
      type = secretFiles.type;
      default = null;
      example = lib.literalExpression "./tailscale.key";
      description = ''
        An auth key (from the admin console: Settings, Keys), to log in without a link: a file from
        the config's folder (./tailscale.key), or the key itself read from elsewhere
        (hostConfig.tailscale.key, tskey-…), or a file a module shared with NixOS makes with
        pkgs.writeText (by its text), which each build puts on the phone as a file readable by root
        alone, never in the profile or Nix's store; or, as a string, the full path of a file already
        on the phone. (On NixOS it is a file only; the key's text is Seraph's.)
      '';
    };

    authKeyParameters = mkOption {
      type = types.submodule {
        options = {
          ephemeral = mkOption {
            type = types.nullOr types.bool;
            default = null;
            description = "Whether to register as an ephemeral node.";
          };
          preauthorized = mkOption {
            type = types.nullOr types.bool;
            default = null;
            description = "Whether to skip manual device approval.";
          };
          baseURL = mkOption {
            type = types.nullOr types.str;
            default = null;
            description = "Base URL for the Tailscale API.";
          };
        };
      };
      default = { };
      description = "What goes after an OAuth client secret in authKeyFile, as on NixOS.";
    };

    extraUpFlags = mkOption {
      type = types.listOf types.str;
      default = [ ];
      example = [ "--ssh" "--advertise-exit-node" ];
      description = ''
        More flags for `tailscale up`. Each build runs it with --reset, so the config says
        everything: what it leaves out goes back to Tailscale's default (NixOS uses them only with
        authKeyFile).
      '';
    };

    extraSetFlags = mkOption {
      type = types.listOf types.str;
      default = [ ];
      example = [ "--advertise-exit-node" ];
      description = "Flags for `tailscale set`, run after `tailscale up`, as on NixOS.";
    };

    extraDaemonFlags = mkOption {
      type = types.listOf types.str;
      default = [ ];
      example = [ "--no-logs-no-support" ];
      description = "More flags for tailscaled.";
    };

    relayServerPort = mkOption {
      type = types.nullOr (types.either types.port (types.enum [ "" ]));
      default = null;
      example = 40000;
      description = ''
        The UDP port of a peer relay (other devices of the tailnet reach each other through this one),
        as on NixOS: a port turns it on (with `tailscale set` at each build), "" turns it off, null
        leaves it unmanaged (a port set before may stay: "" first, to be sure it is off).
      '';
    };
  };

  config = lib.mkIf cfg.enable {
    android.secrets = secretFiles.secrets "services.tailscale.authKeyFile" cfg.authKeyFile;

    systemd.services.tailscaled = {
      description = "Tailscale's daemon";
      wantedBy = [ "multi-user.target" ];
      environment = {
        TS_PERMIT_CERT_UID = cfg.permitCertUid;
        TS_DISABLE_TAILDROP = if cfg.disableTaildrop then "true" else null;
        TS_NO_LOGS_NO_SUPPORT = if cfg.disableUpstreamLogging then "true" else null;
      };
      serviceConfig = {
        ExecStart = "${cfg.package}/bin/tailscaled --tun=${cfg.interfaceName} --statedir=/var/lib/tailscale"
          + " --socket=${socket} --port=${toString cfg.port} ${lib.escapeShellArgs cfg.extraDaemonFlags}";
        StateDirectory = "tailscale";
        RuntimeDirectory = "tailscale";
        Restart = "on-failure";
        RestartSec = 10;
      };
    };

    # The config's settings, with `tailscale up --reset`; logged in with the auth key when it is not
    # yet, else it waits for its login, and says the link to log in with.
    systemd.services.tailscaled-up = {
      description = "Tailscale's settings, and its login";
      wantedBy = [ "multi-user.target" ];
      after = [ "tailscaled.service" ];
      wants = [ "tailscaled.service" ];
      path = [ cfg.package pkgs.jq ];
      serviceConfig.Type = "oneshot";
      script = ''
        ts() { tailscale --socket=${socket} "$@"; }
        state=
        for i in $(seq 150); do
          state=$(ts status --json --peers=false 2> /dev/null | jq -r .BackendState) || state=
          [ -n "$state" ] && [ "$state" != NoState ] && break
          sleep 0.2
        done
        [ -n "$state" ] || { echo "tailscaled does not answer (its log: /data/adb/seraph/units/tailscaled/log)"; exit 1; }
        host=${hostName}
        login=()
        ${lib.optionalString (keyFile != null) ''
          case $state in
            NeedsLogin | NeedsMachineAuth | Stopped)
              login=(--auth-key="$(cat ${lib.escapeShellArg keyFile})${lib.optionalString (params != "") "?${params}"}") ;;
          esac
        ''}
        # Without a login a phone not logged in yet waits for one: up gives up after its timeout,
        # and the link to log in with is in its status.
        ts up --reset --timeout=20s --hostname="''${host:-android}" "''${login[@]}" ${lib.escapeShellArgs cfg.extraUpFlags} \
          || [ "$state" = NeedsLogin ]
        ${lib.optionalString (setFlags != [ ]) "ts set ${lib.escapeShellArgs setFlags}"}
        status=$(ts status --json --peers=false)
        case $(echo "$status" | jq -r .BackendState) in
          Running) echo "Tailscale: running as $(echo "$status" | jq -r '.Self.DNSName | rtrimstr(".")') ($(echo "$status" | jq -r '.Self.TailscaleIPs[0]'))" ;;
          NeedsLogin) echo "! Tailscale: waiting for its login: open $(echo "$status" | jq -r .AuthURL) (on any device) and log in" ;;
          *) echo "Tailscale: $(echo "$status" | jq -r .BackendState)" ;;
        esac
      '';
    };

    # Seraph's own: tells, in a notification, when Tailscale connects or its connection drops (and
    # when it needs a login), from what tailscaled says every 20 seconds. A change counts once it
    # holds for two looks in a row, so a switch between Wi-Fi and mobile data tells nothing; its
    # first state after a start is told only when it needs a login.
    systemd.services.tailscaled-watch = {
      description = "Tells when Tailscale connects or drops";
      wantedBy = [ "multi-user.target" ];
      after = [ "tailscaled-up.service" ];
      wants = [ "tailscaled.service" ];
      path = [ cfg.package pkgs.jq ];
      serviceConfig = {
        Restart = "always";
        RestartSec = 30;
      };
      script = ''
        ts() { tailscale --socket=${socket} "$@"; }
        # (It runs with bash -e: a notification that cannot go out stops nothing.)
        tell() { if [ -n "$SERAPH_NOTIFY" ]; then sh "$SERAPH_NOTIFY" connection "$1" "$2" "$3" connection:tailscale || true; fi; }
        told= seen= times=0
        while :; do
          status=$(ts status --json --peers=false 2> /dev/null) || status=
          state=$(printf '%s' "$status" | jq -r '.BackendState // "NoState"' 2> /dev/null) || state=
          online=$(printf '%s' "$status" | jq -r '.Self.Online // false' 2> /dev/null) || online=false
          case "$state" in
            "") now=down why="tailscaled does not answer" ;;
            Running) if [ "$online" = true ]; then now=up why=; else now=down why="No connection to Tailscale's servers (is the phone online?)"; fi ;;
            NeedsLogin | NeedsMachineAuth) now=login why="Tailscale is waiting for its login (the Services tab: tailscaled-up's log has the link)" ;;
            *) now=down why="Tailscale is $state" ;;
          esac
          if [ "$now" = "$seen" ]; then times=$((times + 1)); else seen=$now times=1; fi
          if [ "$times" = 2 ] && [ "$now" != "$told" ]; then
            case "$now" in
              up) [ -n "$told" ] && tell ok "Tailscale connected" "As $(printf '%s' "$status" | jq -r '.Self.DNSName | rtrimstr(".")') ($(printf '%s' "$status" | jq -r '.Self.TailscaleIPs[0]'))" ;;
              login) tell warn "Tailscale needs a login" "$why" ;;
              down) [ -n "$told" ] && tell warn "Tailscale connection dropped" "$why" ;;
            esac
            told=$now
          fi
          sleep 20
        done
      '';
    };

    assertions = [
      {
        assertion = cfg.interfaceName == "userspace-networking";
        message = "services.tailscale.interfaceName: on a phone Tailscale runs in userspace mode only (\"userspace-networking\"): a TUN interface would need the phone's VPN slot, which the Tailscale app has.";
      }
      {
        assertion = secretFiles.valid cfg.authKeyFile;
        message = secretFiles.problem "services.tailscale.authKeyFile" cfg.authKeyFile;
      }
      {
        assertion = !lib.any (flag: builtins.match "--?(tun|statedir|state|socket)(=.*)?" flag != null) cfg.extraDaemonFlags;
        message = "services.tailscale.extraDaemonFlags: Seraph sets --tun (userspace mode), --statedir and --socket itself.";
      }
    ];
  };
}
