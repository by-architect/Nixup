# Services: programs that root keeps running on the phone, as NixOS's services.*.
# Each starts with the phone, starts again when it stops, and is gone again (with
# what it kept) when its option leaves the config or the module is removed.
{ config, lib, ... }:
let
  inherit (lib) mkOption types;
  cfg = config.android.services.tailscale;

  # Tailscale's own build for Linux, one for each kind of processor, as pkgs.tailscale.com
  # has it: static, so it runs on Android as it is.
  version = "1.104.4";
  tarball = arch: sha256: { url = "https://pkgs.tailscale.com/stable/tailscale_${version}_${arch}.tgz"; inherit sha256; };
in
{
  options.android.services.tailscale = {
    enable = lib.mkEnableOption ''
      Tailscale, run by root as a service (tailscaled), as NixOS's services.tailscale.
      It runs in userspace mode: it takes no VPN slot and changes no routes, so the
      Tailscale app can stay. The tailnet reaches the phone's own services through it
      (adb, ssh). Its first build shows a link to log in with, unless authKeyFile is set'';

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
      description = "The Tailscale to run: its static Linux builds (tailscale and tailscaled in a .tgz), with their checksums.";
    };

    hostname = mkOption {
      type = types.nullOr types.str;
      default = null;
      example = "phone";
      description = "The phone's name on the tailnet; null: the phone's own name.";
    };

    port = mkOption {
      type = types.port;
      default = 41641;
      description = "The UDP port for direct connections to other devices; 0: any free port.";
    };

    authKeyFile = mkOption {
      type = types.nullOr types.str;
      default = null;
      example = "/data/adb/seraph/secrets/tailscale.key";
      description = ''
        A file on the phone with an auth key (from the admin console: Settings, Keys),
        to log in without a link. Keep it out of the config folder: give its path.'';
    };

    extraUpFlags = mkOption {
      type = types.listOf types.str;
      default = [ ];
      example = [ "--ssh" "--advertise-exit-node" ];
      description = "More flags for `tailscale up` (it runs with --reset, so the config says everything).";
    };

    extraDaemonFlags = mkOption {
      type = types.listOf types.str;
      default = [ ];
      example = [ "--no-logs-no-support" ];
      description = "More flags for tailscaled.";
    };
  };

  config.assertions = lib.optionals cfg.enable [
    {
      assertion = cfg.authKeyFile == null || lib.hasPrefix "/" cfg.authKeyFile;
      message = "android.services.tailscale.authKeyFile: give the file's full path on the phone, like /data/adb/seraph/secrets/tailscale.key.";
    }
    {
      assertion = cfg.hostname == null || builtins.match "[A-Za-z0-9][A-Za-z0-9-]*" cfg.hostname != null;
      message = "android.services.tailscale.hostname: \"${toString cfg.hostname}\" is not a name like \"phone\" (letters, digits and -).";
    }
    {
      assertion = !lib.any (flag: builtins.match "--?(tun|statedir|state|socket)(=.*)?" flag != null) cfg.extraDaemonFlags;
      message = "android.services.tailscale.extraDaemonFlags: Seraph sets --tun (userspace mode), --statedir and --socket itself.";
    }
    {
      assertion = cfg.package.tarballs != { };
      message = "android.services.tailscale.package: it has no build (tarballs) for any kind of processor.";
    }
  ];
}
