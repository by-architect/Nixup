# DAVx⁵, Android's calendar and contacts sync (CalDAV and CardDAV), with its accounts in the config:
# services.davx5, Nixup's own (NixOS has no DAVx⁵: it is an Android app, which puts a server's
# calendars and address books into Android's own Calendar and Contacts). Nixup's engine sets its
# accounts at each build, after the apps (see Davx5.kt): no service runs for it, and the phone needs
# no Nix packages for it.
#
#   services.davx5 = {
#     enable = true;
#     accounts.home = {
#       url = "https://dav.example.org/";
#       userName = "neo";
#       passwordFile = ./secrets/dav-password;
#     };
#   };
#
# Android lets only DAVx⁵ add its accounts. One the phone has not got yet gets DAVx⁵'s own login
# screen, opened already filled in (the server, the user name, the password): a tap on Login, then on
# Finish, adds it, and DAVx⁵ finds its calendars and address books itself, with those
# syncCollections says selected for syncing. With automatic = true it is added with no taps: the
# engine asks the server for them itself, writes them into DAVx⁵'s own database, adds the account as
# DAVx⁵ and starts its first sync. That database changes with DAVx⁵'s versions, and DAVx⁵ removes all
# of its accounts on finding one it cannot read: Nixup writes only the version it knows (DAVx⁵ 4.5.17
# to 4.5.21's); with another, the login screen opens instead. An account there already gets the
# config's user name and password at each build; one that leaves the config stays in DAVx⁵, as do the
# accounts added by hand.
{ config, lib, ... }:
let
  inherit (lib) mkOption types;
  cfg = config.services.davx5;
  secretFiles = import ./secret-files.nix { inherit lib; };
  app = "at.bitfire.davdroid";

  # An account's password, as a secret: android.secrets takes names of letters, digits, _ . and -.
  secretName = name:
    let safe = lib.concatMapStrings (c: if builtins.match "[A-Za-z0-9_.-]" c != null then c else "_") (lib.stringToCharacters name);
    in "davx5-${safe}" + lib.optionalString (safe != name) "-${builtins.substring 0 8 (builtins.hashString "sha256" name)}";
  optionName = name: "services.davx5.accounts.${lib.strings.escapeNixIdentifier name}";

  accountModule = { ... }: {
    options = {
      url = mkOption {
        type = types.str;
        example = "https://dav.example.org/";
        description = ''
          The server's address, as DAVx⁵'s "Login with URL and user name" takes it: the server itself
          (DAVx⁵ finds the rest, by its /.well-known/caldav and /.well-known/carddav addresses), or
          the address of the user's calendars or address books (for Nextcloud,
          https://cloud.example.org/remote.php/dav). http:// or https://.
        '';
      };

      userName = mkOption {
        type = types.str;
        example = "neo";
        description = "The user name on the server.";
      };

      passwordFile = mkOption {
        type = secretFiles.type;
        default = null;
        example = lib.literalExpression "./secrets/dav-password";
        description = ''
          The password, from a file: one from the config's folder (./secrets/dav-password, a path,
          without quotes), which each build puts on the phone readable by root alone, never in the
          profile or Nix's store; a file made with pkgs.writeText (by its text); or, as a string, the
          full path of a file already on the phone. Its line break at the end is left out. Each build
          gives it to the account, as DAVx⁵'s account settings would. null: the login screen asks for
          it (automatic needs it).
        '';
      };

      accountName = mkOption {
        type = types.nullOr types.str;
        default = null;
        example = "Home calendar";
        description = ''
          The account's name, in DAVx⁵ and in Android's accounts. null: the one DAVx⁵'s login gives it
          (the email address the server has for the user, else the user name). Nixup finds the account
          by it, or by its user name on its server (one renamed in DAVx⁵ is still found); at the login
          screen, a name given here is typed in its place.
        '';
      };

      automatic = mkOption {
        type = types.bool;
        default = false;
        description = ''
          Add the account with no taps: the build asks the server for its calendars and address books
          itself (as DAVx⁵'s login does, with Basic authentication), writes them into DAVx⁵'s database
          with those syncCollections says selected, adds the account as DAVx⁵ and starts its first sync;
          DAVx⁵ plans the syncs after it. Only with DAVx⁵ 4.5.17 to 4.5.21, whose database Nixup knows
          (a newer DAVx⁵ changes it): with another version, the login screen opens instead. false: the
          login screen, filled in, adds it with two taps.
        '';
      };
    };
  };

  # Where android.packages lists DAVx⁵, and how many times in each list (install adds it once).
  isApp = entry: (if builtins.isString entry then entry else entry._id or entry.id or null) == app;
  listedIn = lib.attrNames (lib.filterAttrs (_: list: lib.any isApp list) config.android.packages);
  listed = listedIn != [ ];
  timesIn = repo: lib.count isApp (config.android.packages.${repo} or [ ]);
  names = lib.filter (name: name != null) (lib.mapAttrsToList (_: account: account.accountName) cfg.accounts);
  twice = lib.unique (lib.filter (name: lib.count (other: other == name) names > 1) names);
in
{
  options.services.davx5 = {
    enable = lib.mkEnableOption ''
      DAVx⁵'s accounts from the config (services.davx5.accounts), and its setting of the collections it
      selects for syncing (syncCollections), set at each build. DAVx⁵ itself comes from android.packages
      (or install)'';

    install = mkOption {
      type = types.nullOr types.str;
      default = null;
      example = "fdroid";
      description = ''
        The repository to install DAVx⁵ from (android.packages.<repository>): fdroid (f-droid.org),
        izzyondroid, ... null: it is listed in android.packages already, or installed by hand; a build
        without it on the phone stops before changing anything.
      '';
    };

    syncCollections = mkOption {
      type = types.enum [ "all" "none" ];
      default = "all";
      description = ''
        The calendars and address books DAVx⁵ selects for syncing when it finds them (its setting
        "preselect collections"), at an account's login and when the server has new ones later: "all"
        (the user's own and those shared with them), or "none" (each one picked in DAVx⁵, as without
        Nixup). It is given back when services.davx5 leaves the config. (DAVx⁵ also takes "personal",
        the user's own alone, which DAVx⁵ 4.5 never matches: it would select none.)
      '';
    };

    accounts = mkOption {
      type = types.attrsOf (types.submodule accountModule);
      default = { };
      example = lib.literalExpression ''
        {
          home = {
            url = "https://dav.example.org/";
            userName = "neo";
            passwordFile = ./secrets/dav-password;
          };
          work = {
            url = "https://cloud.example.com/remote.php/dav";
            userName = "neo@example.com";
            passwordFile = ./secrets/work-dav-password;
            automatic = true;
          };
        }
      '';
      description = "DAVx⁵'s accounts, by a name of your choosing (each one's accountName is its name in DAVx⁵).";
    };
  };

  config = lib.mkMerge [
    {
      # For the profile (nix/build.nix): null without services.davx5.
      system.build.davx5 = if !cfg.enable then null else {
        preselect = { none = 0; all = 1; }.${cfg.syncCollections};
        accounts = lib.mapAttrsToList (name: account: {
          inherit name;
          inherit (account) accountName url userName automatic;
          passwordFile = if account.passwordFile == null then null
            else secretFiles.path config (secretName name) account.passwordFile;
        }) cfg.accounts;
      };
    }

    (lib.mkIf cfg.enable {
      android.secrets = lib.mkMerge (lib.mapAttrsToList (name: account:
        secretFiles.secrets (secretName name) account.passwordFile
      ) cfg.accounts);

      # What it syncs with: the contacts and the calendar (without them it syncs nothing), and its notifications (a sync
      # that fails, a login that ends); in the background, which Android's battery saving would hold back; and never
      # paused for being unused, which would take its permissions away (it is seldom opened: it syncs by itself).
      android.apps.${app} = {
        permissions.contacts = lib.mkDefault true;
        permissions.calendar = lib.mkDefault true;
        permissions.notifications = lib.mkDefault true;
        battery = lib.mkDefault "unrestricted";
        pauseIfUnused = lib.mkDefault false;
      };

      android.packages = lib.mkIf (cfg.install != null) { ${cfg.install} = [ app ]; };

      assertions = lib.concatLists (lib.mapAttrsToList (name: account: [
        {
          assertion = builtins.match "https?://.+" account.url != null;
          message = "${optionName name}.url: \"${account.url}\" is not an http:// or https:// address.";
        }
        {
          assertion = secretFiles.valid account.passwordFile;
          message = if builtins.isString account.passwordFile && !(builtins.hasContext account.passwordFile)
            then "${optionName name}.passwordFile: a string is the full path of a file on the phone (/data/…); a file from the config's folder is a path, written without quotes, like ./secrets/dav-password (a password itself would be in Nix's store, which every user of the machine can read)."
            else secretFiles.problem "${optionName name}.passwordFile" account.passwordFile;
        }
        {
          assertion = account.automatic -> account.passwordFile != null;
          message = "${optionName name}.automatic: the account is added with no taps from its passwordFile, which is not set.";
        }
      ]) cfg.accounts)
      ++ [
        {
          assertion = twice == [ ];
          message = "services.davx5.accounts: more than one account is named ${lib.concatStringsSep ", " twice} (accountName): give each its own.";
        }
        {
          assertion = cfg.install == null || (lib.all (repo: repo == cfg.install) listedIn && timesIn cfg.install == 1);
          message = "services.davx5.install = \"${toString cfg.install}\" lists DAVx⁵ in android.packages.${toString cfg.install}, and the config lists it in android.packages.${lib.concatStringsSep ", android.packages." (lib.filter (repo: repo != cfg.install || timesIn repo > 1) listedIn)} itself: leave install null to keep it there, or take it out of that list.";
        }
      ];

      warnings = lib.optional (cfg.install == null && !listed)
        "services.davx5: DAVx⁵ (${app}) is in no list of android.packages, and services.davx5.install is null: a build sets its accounts only when it is on the phone already (installed by hand).";
    })
  ];
}
