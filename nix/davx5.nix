# services.davx5: the old name of services.caldav (nix/caldav.nix), from when Nixup set up the DAVx⁵
# app's accounts. Nixup's app syncs the calendars and contacts itself now, with DAVx⁵'s sync and no
# DAVx⁵ app: a config that still says services.davx5 builds as before, its options taken as
# services.caldav's (the same ones), and each build warns with what to write instead, as NixOS's
# renamed options do. Its two options that only DAVx⁵ had (install, automatic) do nothing now.
#
#   services.davx5 = { enable = true; accounts.home = { url = …; userName = …; passwordFile = …; }; };
#   # is now
#   services.caldav = { enable = true; accounts.home = { url = …; userName = …; passwordFile = …; }; };
{ config, lib, ... }:
let
  inherit (lib) mkOption types;
  cfg = config.services.davx5;
  secretFiles = import ./secret-files.nix { inherit lib; };
  app = "at.bitfire.davdroid";

  accountModule = { ... }: {
    options = {
      url = mkOption {
        type = types.str;
        description = "Old name of services.caldav.accounts.<name>.url.";
      };
      userName = mkOption {
        type = types.str;
        description = "Old name of services.caldav.accounts.<name>.userName.";
      };
      passwordFile = mkOption {
        type = secretFiles.type;
        default = null;
        description = "Old name of services.caldav.accounts.<name>.passwordFile.";
      };
      accountName = mkOption {
        type = types.nullOr types.str;
        default = null;
        description = "Old name of services.caldav.accounts.<name>.accountName.";
      };
      automatic = mkOption {
        type = types.bool;
        default = false;
        description = ''
          Does nothing now: every account of services.caldav is added with no taps. (It wrote the
          account into the DAVx⁵ app's database.)
        '';
      };
    };
  };

  # Where android.packages lists DAVx⁵ (the user's own: it stays installed, with its settings as before).
  isApp = entry: (if builtins.isString entry then entry else entry._id or entry.id or null) == app;
  listed = lib.any (list: lib.any isApp list) (lib.attrValues config.android.packages);
  automatic = lib.attrNames (lib.filterAttrs (_: account: account.automatic) cfg.accounts);
in
{
  options.services.davx5 = {
    enable = lib.mkEnableOption "services.caldav, by its old name (see nix/davx5.nix)";

    install = mkOption {
      type = types.nullOr types.str;
      default = null;
      description = ''
        Does nothing now: the sync needs no DAVx⁵ app. (It installed DAVx⁵ from this repository; an
        app installed so leaves with its data kept, as any app that leaves the config.)
      '';
    };

    syncCollections = mkOption {
      type = types.enum [ "all" "none" ];
      default = "all";
      description = "Old name of services.caldav.syncCollections.";
    };

    accounts = mkOption {
      type = types.attrsOf (types.submodule accountModule);
      default = { };
      description = "Old name of services.caldav.accounts.";
    };
  };

  config = lib.mkIf cfg.enable {
    services.caldav = {
      enable = true;
      syncCollections = cfg.syncCollections;
      accounts = lib.mapAttrs (_: account: { inherit (account) url userName passwordFile accountName; }) cfg.accounts;
    };

    # The DAVx⁵ app the config itself lists keeps the settings services.davx5 gave it (its sync of its own accounts).
    android.apps = lib.mkIf listed {
      ${app} = {
        permissions.contacts = lib.mkDefault true;
        permissions.calendar = lib.mkDefault true;
        permissions.notifications = lib.mkDefault true;
        battery = lib.mkDefault "unrestricted";
        pauseIfUnused = lib.mkDefault false;
      };
    };

    warnings = [
      ("services.davx5 is services.caldav now: Nixup's app syncs the calendars and contacts itself, with DAVx⁵'s sync and no DAVx⁵ app. "
        + "Write services.caldav in its place (its options are the same); this build takes services.davx5's as services.caldav's.")
    ]
    ++ lib.optional (cfg.install != null)
      ("services.davx5.install = \"${cfg.install}\" does nothing now: no DAVx⁵ app is needed. The DAVx⁵ an earlier build installed for it "
        + "leaves (its data kept), with its accounts' calendars and contacts on the phone: Nixup's own accounts take their place.")
    ++ lib.optional (automatic != [ ])
      "services.davx5.accounts.<name>.automatic (${lib.concatStringsSep ", " automatic}) does nothing now: every account is added with no taps."
    ++ lib.optional (cfg.syncCollections == "none")
      ("services.davx5.syncCollections = \"none\": the calendars and address books picked in DAVx⁵ by hand are not known to Nixup's sync, which then syncs none: "
        + "name them in services.caldav.accounts.<name>.collections.")
    ++ lib.optional listed
      ("services.davx5: DAVx⁵ is in android.packages, and its own accounts sync the same calendars and contacts as Nixup's: "
        + "each shows twice. Take DAVx⁵ out of android.packages (its accounts go with it), or its accounts out of DAVx⁵.");
  };
}
