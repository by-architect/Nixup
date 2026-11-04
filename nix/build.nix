# What a build makes of a configuration, as NixOS has config.system.build.toplevel:
#   config.system.build.repos     the repositories, whose indexes the phone fetches first: it
#                                 needs no package names, so it evaluates with pkgs empty; and
#                                 whether the config needs nixpkgs
#   config.system.build.profile   the profile, the JSON the module applies; evaluating it checks
#                                 the assertions and shows the warnings, as nixos-rebuild does
#   config.system.build.secrets   how each secret gets to the phone: { file = <its path where the
#                                 config is built>; } or { text = true; } (secretTexts has it), or
#                                 neither, which seraph-rebuild sends; never in the profile
{ config, options, lib, pkgs, ... }:
let
  # What a shell has besides environment.systemPackages, as on NixOS.
  shellPackages = lib.optionals (config.environment.systemPackages != [ ]) (with pkgs; [ bashInteractive coreutils findutils gnugrep gnused less ]);

  repos = lib.mapAttrs (_: repo: { url = repo.address; fingerprint = repo.signingKey; }) config.android.repos;

  failed = map (check: check.message) (lib.filter (check: !check.assertion) config.assertions);

  # The phone's own settings: the settings tables (Android's system, secure and global, and
  # LineageOS's as lineage.system, ...; true and false as 1 and 0), with the plain option that set
  # each, and those that take a command; a table or option that is not set is left out.
  settings = config.android.settings;
  tables = removeAttrs settings.raw [ "lineage" ]
    // lib.mapAttrs' (name: lib.nameValuePair "lineage.${name}") settings.raw.lineage;
  system = lib.filterAttrs (_: value: value != null && value != { }) {
    settings = lib.filterAttrs (_: table: table != { }) (lib.mapAttrs (_: lib.mapAttrs (_: value:
      if builtins.isBool value then (if value then 1 else 0) else value
    )) tables);
    labels = config.android.settingLabels;
    keyboard = settings.keyboard.default;
    inherit (settings.display) density;
    navigation = if settings.navigation.mode == null then null
      else { gestures = "gestural"; threeButton = "threebutton"; twoButton = "twobutton"; }.${settings.navigation.mode};
    language = if settings.language == null then null
      else lib.concatStringsSep "," (lib.toList settings.language);
    inherit (settings.time) timeZone;
    inherit (settings.network) dataSaver;
    location = settings.location.enable;
  };

  # An app's settings without the ones left null (not managed); an app with none is left out.
  managed = value:
    if builtins.isAttrs value then
      lib.filterAttrs (_: v: v != null && v != { } && v != [ ]) (lib.mapAttrs (_: managed) value)
    else
      value;
in
{
  options.system.build = lib.mkOption {
    type = lib.types.lazyAttrsOf (lib.types.uniq lib.types.unspecified);
    default = { };
    internal = true;
    description = "What a build makes of the configuration (repos, profile).";
  };

  config.system.build = {
    # needsNixpkgs: whether the config uses Nix packages, which a configuration.nix alone gets from
    # the phone's copy of nixpkgs (fetched the first time one does). Asked without the packages
    # themselves, which need it: whether a module sets environment.systemPackages or the
    # terminal's font, whether a unit is there, and whether there are backups (restic).
    repos = {
      android = { inherit repos; };
      needsNixpkgs = options.environment.systemPackages.highestPrio < (lib.mkOptionDefault null).priority
        || options.terminal.font.highestPrio < (lib.mkOptionDefault null).priority
        || lib.filterAttrs (_: unit: unit.enable) config.systemd.services != { }
        || config.android.backup.repository != null;
    };

    # toString: a path stays where it is, where interpolating it would copy it into the store.
    secrets = lib.mapAttrs (_: secret: {
      file = if secret.file == null then null else toString secret.file;
      text = secret.text != null;
    }) config.android.secrets;
    secretTexts = lib.mapAttrs (_: secret: secret.text) (lib.filterAttrs (_: secret: secret.text != null) config.android.secrets);
    # The same for a build on the phone, which puts them in place itself: each file's path (in
    # the config's folder, or a flake's copy of it) or text.
    secretsToDeliver = lib.mapAttrs (_: secret: {
      file = if secret.file == null then null else toString secret.file;
      inherit (secret) text;
    }) config.android.secrets;

    profile =
      if failed != [ ] then
        throw "\nFailed assertions:\n${lib.concatMapStrings (message: "- ${message}\n") failed}"
      else
        lib.showWarnings config.warnings {
          # The module refuses a profile whose schemaVersion it does not know.
          schemaVersion = 1;
          android = {
            inherit (settings.display) darkMode;
            inherit repos;
            # An F-Droid app (or one from Google Play) as its package name; an app from its own releases
            # with where they are.
            packages = lib.mapAttrs (_: map (app:
              if builtins.isString app then app
              else if !(app ? _id) then {
                inherit (app) id url apkFilter prerelease;
                signer = if app.signer == null then null else lib.toLower (lib.replaceStrings [ ":" ] [ "" ] app.signer);
              }
              else app._id
            )) config.android.packages;
            settings = lib.mapAttrs (name: _: { autoupdatePackages = settings.repo.${name}.autoupdatePackages or false; })
              (repos // { obtainium = { }; playstore = { }; });
            # The Google account Google Play's apps come with, and where its token is (nix/playstore.nix).
            playstore = config.system.build.playstore;
            # debloat only when true: false is the same as not managed; backup goes in backup.
            apps = lib.filterAttrs (_: settings: settings != { }) (lib.mapAttrs (_: app:
              managed (removeAttrs app [ "backup" ] // { debloat = if app.debloat == true then true else null; })
            ) config.android.apps);
            # The backups (nix/backup.nix): the repository, and each app and folder backed up.
            backup = config.system.build.backup;
            inherit system;
            # The view's users and groups (nix/users.nix), and what each session runs first.
            users = config.system.build.users;
            inherit (config.environment) extraInit;
            # The app's terminal: its font and colors (nix/terminal.nix).
            terminal = config.system.build.terminal;
            # adb's port and the computers it trusts (nix/adb.nix).
            adb = config.system.build.adb;
            # The secrets' names: the phone checks each is there, and removes one that left.
            secrets = lib.attrNames config.android.secrets;
            # The services (systemd.services, Seraph's own among them) as the engine runs them, and
            # every store path the phone needs for them, environment.systemPackages and the
            # terminal's font: it fetches those from cache.nixos.org before the build changes anything.
            units = config.system.build.units;
            storePaths = lib.unique (config.system.build.unitStorePaths ++ config.system.build.backupStorePaths
              ++ config.system.build.terminalStorePaths
              ++ map (package: builtins.unsafeDiscardStringContext "${package}") (config.environment.systemPackages ++ shellPackages));
            # seraph-shell's PATH (environment.systemPackages first) and its bash, as a shell on
            # NixOS has them; none without Nix packages.
            shell = if config.environment.systemPackages == [ ] then null else {
              path = builtins.unsafeDiscardStringContext
                "${lib.makeBinPath (config.environment.systemPackages ++ shellPackages)}:/system/bin";
              bash = builtins.unsafeDiscardStringContext "${pkgs.bashInteractive}/bin/bash";
            };
          };
        };
  };
}
