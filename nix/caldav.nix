# Calendars, contacts and tasks from CalDAV/CardDAV servers, synced into Android's own Calendar and
# Contacts (and a tasks app): services.caldav, Nixup's own (NixOS has none: on a computer, a mail or
# calendar program syncs by itself). Nixup's app syncs them itself, with DAVx⁵'s sync (davx5-ose
# 4.5.21, its code in the app: app/src/main/kotlin/dev/nixup/manager/dav), and needs no DAVx⁵ app.
#
#   services.caldav = {
#     enable = true;
#     accounts.home = {
#       url = "https://dav.example.org/";
#       userName = "neo";
#       passwordFile = ./secrets/dav-password;
#     };
#   };
#
# Each account is an Android account of Nixup's app (Settings → Accounts), added by the build with
# no taps (see the engine's Caldav.kt): its calendars show in every calendar app, its address books
# in the contacts (each one an account of its own, "Nixup address book", as DAVx⁵ has them), and its
# task lists in Tasks.org, OpenTasks or jtx Board when one is installed. The app finds the server's
# collections as DAVx⁵'s login does (the address given, its /.well-known/caldav and carddav
# addresses), looks them up again at least once an hour of syncing, and syncs those the config
# selects (syncCollections, collections): both ways, as DAVx⁵ does, on each change made on the phone
# and every syncInterval. An account that leaves the config is removed with what it put on the
# phone (the server keeps it); one with changes not synced yet waits for a later build. Android's
# Settings → Accounts shows each, and "Sync now" there syncs it. services.davx5 is this option's
# old name (nix/davx5.nix).
{ config, lib, ... }:
let
  inherit (lib) mkOption types;
  cfg = config.services.caldav;
  secretFiles = import ./secret-files.nix { inherit lib; };
  app = "dev.nixup.manager";

  # An account's password, as a secret: android.secrets takes names of letters, digits, _ . and -.
  secretName = name:
    let safe = lib.concatMapStrings (c: if builtins.match "[A-Za-z0-9_.-]" c != null then c else "_") (lib.stringToCharacters name);
    in "caldav-${safe}" + lib.optionalString (safe != name) "-${builtins.substring 0 8 (builtins.hashString "sha256" name)}";
  optionName = name: "services.caldav.accounts.${lib.strings.escapeNixIdentifier name}";

  accountModule = { ... }: {
    options = {
      url = mkOption {
        type = types.str;
        example = "https://dav.example.org/";
        description = ''
          The server's address, as DAVx⁵'s "Login with URL and user name" takes it: the server itself
          (the sync finds the rest, by its /.well-known/caldav and /.well-known/carddav addresses), or
          the address of the user's calendars or address books (for Nextcloud,
          https://cloud.example.org/remote.php/dav). http:// or https:// (an https server's own
          certificate is trusted when it is installed in Android's settings).
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
          full path of a file already on the phone. Its line break at the end is left out. The account
          keeps it as Android keeps an account's password (a server that needs none: null).
        '';
      };

      accountName = mkOption {
        type = types.nullOr types.str;
        default = null;
        example = "Home calendar";
        description = ''
          The account's name in Android's accounts, and the calendars' account in calendar apps (which
          take it as the owner's address for invitations). null: as DAVx⁵'s login names it, the email
          address the server has for the user (else the user name), found when it is added. A name
          that changes makes another account: the old one goes with what it put on the phone, and the
          new one syncs it again from the server.
        '';
      };

      collections = mkOption {
        type = types.nullOr (types.listOf types.str);
        default = null;
        example = [ "Personal" "Work" "contacts" ];
        description = ''
          The calendars, address books and task lists to sync, each by its name as the server shows it,
          the last part of its address, or its address (whole, or its path): those alone. null: those
          syncCollections selects.
        '';
      };

      exclude = mkOption {
        type = types.listOf types.str;
        default = [ ];
        example = [ "Birthdays" ];
        description = "Collections never synced, named as in collections, whatever selects them.";
      };
    };
  };

  # Nixup's own app's settings the sync needs (see android.apps).
  names = lib.filter (name: name != null) (lib.mapAttrsToList (_: account: account.accountName) cfg.accounts);
  twice = lib.unique (lib.filter (name: lib.count (other: other == name) names > 1) names);
in
{
  options.services.caldav = {
    enable = lib.mkEnableOption ''
      the calendars, contacts and tasks of the CalDAV/CardDAV servers of services.caldav.accounts,
      synced by Nixup's app (with DAVx⁵'s sync) into Android's own Calendar and Contacts'';

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
            collections = [ "Work" "Contacts" ];
          };
        }
      '';
      description = "The accounts, by a name of your choosing (each one's accountName is its name on the phone).";
    };

    syncCollections = mkOption {
      type = types.enum [ "all" "personal" "none" ];
      default = "all";
      description = ''
        The collections an account syncs when its collections option is null, as DAVx⁵'s "preselect
        collections": "all" (the user's own and those shared with them), "personal" (those in the
        user's own home sets alone), or "none". Nextcloud's "Recently contacted" address book is
        left out but by name.
      '';
    };

    syncInterval = mkOption {
      type = types.nullOr types.ints.positive;
      default = 240;
      example = 60;
      description = ''
        How often each account syncs, in minutes (15 at least, Android's shortest), besides syncing each
        change made on the phone. null: only when asked (a build, a calendar app's refresh, "Sync now"
        in Android's account settings), and no change is synced by itself. DAVx⁵'s default: 4 hours.
      '';
    };

    wifiOnly = mkOption {
      type = types.bool;
      default = false;
      description = "Sync only over Wi-Fi (or Ethernet): over mobile data, no sync runs but those asked for by hand.";
    };

    pastDays = mkOption {
      type = types.nullOr types.ints.unsigned;
      default = 90;
      description = "Events further in the past than these days are not synced (DAVx⁵'s default: 90). null: all of them.";
    };

    defaultAlarm = mkOption {
      type = types.nullOr types.ints.unsigned;
      default = null;
      example = 15;
      description = "A reminder this many minutes before each synced event that has none (and is not all day). null: none.";
    };

    manageCalendarColors = mkOption {
      type = types.bool;
      default = true;
      description = "The calendars' colors are the server's at each sync (false: set in the calendar app, they stay).";
    };

    eventColors = mkOption {
      type = types.bool;
      default = false;
      description = "Events may have colors of their own (iCalendar's COLOR), which the calendar app may offer to set.";
    };

    contactGroupMethod = mkOption {
      type = types.enum [ "groupVCards" "categories" ];
      default = "groupVCards";
      description = ''
        How contact groups are kept on the server: "groupVCards", each group a vCard of its own (vCard 4,
        as Nextcloud and most servers have them), or "categories", each contact's CATEGORIES (as Apple's
        servers do). A change syncs the address books again from the server.
      '';
    };

    tasksApp = mkOption {
      type = types.nullOr (types.enum [ "org.tasks" "org.dmfs.tasks" "at.techbee.jtx" ]);
      default = null;
      example = "org.tasks";
      description = ''
        The tasks app the task lists (VTODO) sync into: "org.tasks" (Tasks.org), "org.dmfs.tasks"
        (OpenTasks) or "at.techbee.jtx" (jtx Board, which also takes journals and notes). null: the first
        of them installed. The app is not installed for it (android.packages does that); without one,
        no tasks sync.
      '';
    };
  };

  config = lib.mkMerge [
    {
      # For the profile (nix/build.nix): null without services.caldav.
      system.build.caldav = if !cfg.enable then null else {
        settings = {
          inherit (cfg) syncCollections syncInterval wifiOnly pastDays defaultAlarm manageCalendarColors eventColors tasksApp;
          contactGroupMethod = { groupVCards = "GROUP_VCARDS"; categories = "CATEGORIES"; }.${cfg.contactGroupMethod};
        };
        accounts = lib.mapAttrsToList (name: account: {
          inherit name;
          inherit (account) accountName url userName collections exclude;
          passwordFile = if account.passwordFile == null then null
            else secretFiles.path config (secretName name) account.passwordFile;
        }) cfg.accounts;
      };
    }

    (lib.mkIf cfg.enable {
      android.secrets = lib.mkMerge (lib.mapAttrsToList (name: account:
        secretFiles.secrets (secretName name) account.passwordFile
      ) cfg.accounts);

      # Nixup's app syncs: with Android's calendar and contacts (without them it syncs nothing), in the background,
      # which Android's battery saving would hold back, and never paused for being unused, which would take its
      # permissions away (it is seldom opened: it syncs by itself). Given back when services.caldav goes.
      android.apps.${app} = {
        permissions.calendar = lib.mkDefault true;
        permissions.contacts = lib.mkDefault true;
        battery = lib.mkDefault "unrestricted";
        pauseIfUnused = lib.mkDefault false;
      };

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
          assertion = account.collections == null || lib.all (c: builtins.match "[[:space:]]*" c == null) account.collections;
          message = "${optionName name}.collections: an empty name names no collection.";
        }
      ]) cfg.accounts)
      ++ [
        {
          assertion = twice == [ ];
          message = "services.caldav.accounts: more than one account is named ${lib.concatStringsSep ", " twice} (accountName): give each its own.";
        }
        {
          assertion = cfg.syncInterval == null || cfg.syncInterval >= 15;
          message = "services.caldav.syncInterval = ${toString cfg.syncInterval}: Android syncs every 15 minutes at the most often.";
        }
      ];

      warnings = lib.optional (cfg.accounts == { })
        "services.caldav is on with no accounts (services.caldav.accounts): it syncs nothing."
        ++ lib.concatLists (lib.mapAttrsToList (name: account:
          lib.optional (account.passwordFile == null)
            "${optionName name}.passwordFile is null: the account logs in with no password (a server that asks for one refuses it)."
          ++ lib.optional (account.collections == [ ])
            "${optionName name}.collections is empty: the account syncs no collection."
        ) cfg.accounts)
        ++ lib.optional (cfg.syncCollections == "none" && lib.any (account: account.collections == null) (lib.attrValues cfg.accounts))
          "services.caldav.syncCollections = \"none\": the accounts without collections sync nothing (name them in services.caldav.accounts.<name>.collections).";
    })
  ];
}
