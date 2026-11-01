# Services: programs that root keeps running on the phone, with NixOS's names (services.<name>), so a
# module that sets one works on a NixOS machine too. Each starts with the phone, starts again when it
# stops, and is gone again (with what it kept) when it leaves the config or the module is removed.
#
# Each has all of NixOS's options for it, with the same names, types and meanings. One the phone
# cannot do (yet) is there too, so a module shared with NixOS builds, but setting it to anything but
# its default stops the build and says why; one with nothing to do on a phone (a firewall to open)
# takes its value and does nothing. package is the one that differs: on NixOS it is a Nix package,
# which the phone has not got yet, so here it is the program's own static build (a module shared
# with NixOS leaves it out).
{ config, lib, ... }:
let
  inherit (lib) mkOption types;
  cfg = config.services.tailscale;

  # Tailscale's own build for Linux, one for each kind of processor, as pkgs.tailscale.com
  # has it: static, so it runs on Android as it is.
  version = "1.104.4";
  tarball = arch: sha256: { url = "https://pkgs.tailscale.com/stable/tailscale_${version}_${arch}.tgz"; inherit sha256; };

  # NixOS's options the phone has not got (yet): each one's value that leaves it unused, and what to
  # do instead.
  notHere = [
    { name = "interfaceName"; unused = cfg.interfaceName == "userspace-networking";
      why = "on a phone Tailscale runs in userspace mode only (\"userspace-networking\"): a TUN interface would need the phone's VPN slot, which the Tailscale app has."; }
    { name = "permitCertUid"; unused = cfg.permitCertUid == null; why = "not on the phone yet."; }
    { name = "disableTaildrop"; unused = !cfg.disableTaildrop; why = "not on the phone yet."; }
    { name = "authKeyParameters"; unused = lib.all (value: value == null) (lib.attrValues (removeAttrs cfg.authKeyParameters [ "_module" ]));
      why = "not on the phone yet; an OAuth client secret takes them after it in authKeyFile, like tskey-client-…?ephemeral=false&preauthorized=true."; }
    { name = "extraSetFlags"; unused = cfg.extraSetFlags == [ ];
      why = "`tailscale set` is not on the phone yet; the flags `tailscale up` takes too go in extraUpFlags."; }
  ];
in
{
  options.services.tailscale = {
    enable = lib.mkEnableOption ''
      Tailscale's daemon (tailscaled), as NixOS's services.tailscale: run by root, in userspace mode
      (interfaceName), so it takes no VPN slot and changes no routes, and the Tailscale app can stay.
      The tailnet reaches the phone's own services through it (adb, ssh). Its name there is
      networking.hostName, or the phone's device name. Its first build shows a link to log in with,
      unless authKeyFile is set'';

    package = mkOption {
      type = types.submodule {
        options = {
          version = mkOption { type = types.str; description = "Tailscale's version."; };
          tarballs = mkOption {
            type = types.attrsOf (types.submodule {
              options = {
                url = mkOption { type = types.str; description = "Where the build is."; };
                sha256 = mkOption { type = types.str; description = "Its SHA-256, checked before it is used."; };
              };
            });
            description = "The build for each kind of processor: arm64, arm, amd64, 386.";
          };
        };
      };
      default = {
        inherit version;
        tarballs = {
          arm64 = tarball "arm64" "a1c249ce6bdebef62e1ae3b997595555bce2ae4709bf95a718e9ca7a129dad7e";
          arm = tarball "arm" "7412390619633d040dbb030c92626d10f3d056e2d9087fc1026556881bbe0de3";
          amd64 = tarball "amd64" "624ea093d47d62cf56f17b8332d74b8decda4a1faa610ebc60a1de334b2b3dc9";
          "386" = tarball "386" "462e367bb965f6db8044f78c96ba5e9101f8d5ad91506b4a0f33a96912e63573";
        };
      };
      defaultText = lib.literalMD "Tailscale ${version}, from pkgs.tailscale.com";
      description = ''
        The Tailscale to run: its static Linux builds (tailscale and tailscaled in a .tgz), with their
        checksums. On NixOS this is a Nix package (pkgs.tailscale), which the phone has not got yet,
        so a module shared with NixOS leaves it out.
      '';
    };

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
      description = "The user allowed to fetch the node's TLS certificates, as on NixOS. Not on the phone yet.";
    };

    disableTaildrop = mkOption {
      type = types.bool;
      default = false;
      description = "Turn Taildrop (sending files between devices) off, as on NixOS. Not on the phone yet.";
    };

    disableUpstreamLogging = mkOption {
      type = types.bool;
      default = false;
      description = "Send no debug logs to Tailscale (tailscaled --no-logs-no-support), as on NixOS.";
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
      type = types.nullOr types.str;
      default = null;
      example = "/data/adb/seraph/secrets/tailscale.key";
      description = ''
        A file on the phone with an auth key (from the admin console: Settings, Keys), to log in
        without a link. Give its full path, as a string: keep it out of the config folder, which a
        path like ./tailscale.key would copy into Nix's store.
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
      description = ''
        What NixOS adds after an OAuth client secret in authKeyFile. Not on the phone yet: write it
        after the secret in the file, like tskey-client-…?ephemeral=false&preauthorized=true.
      '';
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
      description = "Flags for `tailscale set`, as on NixOS. Not on the phone yet; the flags `tailscale up` takes too go in extraUpFlags.";
    };

    extraDaemonFlags = mkOption {
      type = types.listOf types.str;
      default = [ ];
      example = [ "--no-logs-no-support" ];
      description = "More flags for tailscaled.";
    };
  };

  config.assertions = lib.optionals cfg.enable ([
    {
      assertion = cfg.authKeyFile == null || lib.hasPrefix "/" cfg.authKeyFile;
      message = "services.tailscale.authKeyFile: give the file's full path on the phone, like /data/adb/seraph/secrets/tailscale.key.";
    }
    {
      assertion = !lib.any (flag: builtins.match "--?(tun|statedir|state|socket)(=.*)?" flag != null) cfg.extraDaemonFlags;
      message = "services.tailscale.extraDaemonFlags: Seraph sets --tun (userspace mode), --statedir and --socket itself.";
    }
    {
      assertion = cfg.package.tarballs != { };
      message = "services.tailscale.package: it has no build (tarballs) for any kind of processor.";
    }
  ] ++ map (option: {
    assertion = option.unused;
    message = "services.tailscale.${option.name}: ${option.why}";
  }) notHere);
}
