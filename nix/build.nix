# What a build makes of a configuration, as NixOS has config.system.build.toplevel:
#   config.system.build.repos     the repositories, whose indexes the phone fetches first: it
#                                 needs no package names, so it evaluates with pkgs empty; and
#                                 whether the config needs nixpkgs
#   config.system.build.profile   the profile, the JSON the module applies; evaluating it checks
#                                 the assertions and shows the warnings, as nixos-rebuild does
#   config.system.build.secrets   how each secret gets to the phone: { file = <its path where the
#                                 config is built>; } or { text = true; } (secretTexts has it), or
#                                 neither, which nixup-rebuild sends; never in the profile
{ config, options, lib, pkgs, ... }:
let
  # Whether there are Nix packages for a shell: environment.systemPackages, or a user's (its profile: nix/users.nix).
  anyPackages = config.environment.systemPackages != [ ] || lib.any (user: user.packages != [ ]) (lib.attrValues config.users.users);
  # What a shell has besides environment.systemPackages, as on NixOS.
  shellPackages = lib.optionals anyPackages (with pkgs; [ bashInteractive coreutils findutils gnugrep gnused less ]);
  # Whether a user has packages (users.users.<name>.packages), asked of the definitions alone: their packages need
  # nixpkgs, which a configuration.nix's first look has not got.
  userPackagesGiven = lib.any (def: builtins.isAttrs def && lib.any (user:
    builtins.isAttrs user && (user ? packages || (user.content or { }) ? packages)) (lib.attrValues def)) options.users.users.definitions;

  repos = lib.mapAttrs (_: repo: { url = repo.address; fingerprint = repo.signingKey; }) config.android.repos;

  failedOf = config: map (check: check.message) (lib.filter (check: !check.assertion) config.assertions);
  failed = failedOf config;

  # The specialisations (nix/specialisation.nix), each a config of its own: what each says that the config itself does
  # not (a failed check, a warning) is told with its name, and their repositories and secrets come with the config's.
  children = lib.mapAttrs (_: specialisation: specialisation.configuration) config.specialisation;
  ofChildren = what: lib.concatLists (lib.mapAttrsToList (name: child:
    map (message: "specialisation.${name}: ${message}") (lib.subtractLists (what config) (what child))) children);
  allFailed = failed ++ ofChildren failedOf;
  allWarnings = config.warnings ++ ofChildren (config: config.warnings);
  # The config's own wins over a specialisation's of the same name.
  withChildren = part: lib.foldl' (all: child: all // child.system.build.${part}) { } (lib.attrValues children)
    // config.system.build.${part};

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

  # What the profile needs of Nix's store: the services' programs and the files the config makes for them, /etc's
  # files and environment.systemPackages (the backups' restic and the terminal's font come as paths alone). As Nix
  # finds a build's references, by their names in the text (storePaths); and, from their strings' context, how each
  # comes (derivations): a derivation's outputs, fetched from cache.nixos.org, or built on the phone when it is a
  # small file the config makes (a unit's settings file); or a file the config put in the store itself.
  needed = builtins.toJSON {
    inherit (config.system.build) units etc;
    packages = map (package: "${package}") (config.environment.systemPackages ++ shellPackages);
    # The ssh Nix reaches nix.buildMachines with (nix/nix-builds.nix).
    builders = if config.system.build.nixBuilders == null then null else config.system.build.nixBuilders.sshPath;
    # The scripts (system.activationScripts, preSwitchChecks: nix/activation.nix), with the bash they run with.
    inherit (config.system.build) activation;
  };
  storePathsIn = text: map builtins.head (lib.filter builtins.isList
    (builtins.split "(/nix/store/[a-z0-9]{32}-[A-Za-z0-9+._?=-]+)" (builtins.unsafeDiscardStringContext text)));
  derivations = lib.mapAttrsToList (path: how:
    if how.allOutputs or false then "${path}^*"
    else if how ? outputs then "${path}^${lib.concatStringsSep "," how.outputs}"
    else path
  ) (builtins.getContext needed);

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
    # themselves, which need it: whether there is a user of Home Manager, whether a module sets
    # environment.systemPackages, a user's packages or the terminal's font, whether a unit is
    # there, and whether there are backups (restic).
    repos = {
      android.repos = lib.foldl' (all: child: all // child.system.build.repos.android.repos) { } (lib.attrValues children) // repos;
      needsNixpkgs = lib.any (child: child.system.build.repos.needsNixpkgs) (lib.attrValues children)
        || (options ? home-manager.users && config.home-manager.users != { })
        || options.environment.systemPackages.highestPrio < (lib.mkOptionDefault null).priority
        || userPackagesGiven
        || options.terminal.font.highestPrio < (lib.mkOptionDefault null).priority
        || lib.filterAttrs (_: unit: unit.enable) config.systemd.services != { }
        || config.android.backup.repository != null
        || (config.nix.distributedBuilds && config.nix.buildMachines != [ ])
        || config.system.build.nixosServices.used
        || config.system.build.activationUsed;
    };

    # toString: a path stays where it is, where interpolating it would copy it into the store. With the
    # specialisations' (withChildren below), so whichever the phone switches to finds its own.
    ownSecrets = lib.mapAttrs (_: secret: {
      file = if secret.file == null then null else toString secret.file;
      text = secret.text != null;
    }) config.android.secrets;
    ownSecretTexts = lib.mapAttrs (_: secret: secret.text) (lib.filterAttrs (_: secret: secret.text != null) config.android.secrets);
    # The same for a build on the phone, which puts them in place itself: each file's path (in
    # the config's folder, or a flake's copy of it) or text.
    ownSecretsToDeliver = lib.mapAttrs (_: secret: {
      file = if secret.file == null then null else toString secret.file;
      inherit (secret) text;
    }) config.android.secrets;

    secrets = withChildren "ownSecrets";
    secretTexts = withChildren "ownSecretTexts";
    secretsToDeliver = withChildren "ownSecretsToDeliver";

    # The profile, with each specialisation's under specialisation.<name> (when it has some): the phone applies the
    # config's own part, or the one picked (build.sh --specialisation). The checks and warnings are its own and the
    # specialisations', told once.
    profile =
      if allFailed != [ ] then
        throw "\nFailed assertions:\n${lib.concatMapStrings (message: "- ${message}\n") allFailed}"
      else
        lib.showWarnings allWarnings (config.system.build.profileData
          // lib.optionalAttrs (children != { }) {
            specialisation = lib.mapAttrs (_: child: child.system.build.profileData) children;
          });

    # The profile itself, unchecked (profile checks it, and a specialisation's is checked by its config's).
    profileData = {
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
        # The version and the config's revision, which each generation keeps (nix/version.nix).
        version = config.system.build.version;
        # adb's port and the computers it trusts (nix/adb.nix).
        adb = config.system.build.adb;
        # The scripts (nix/activation.nix): the activation script (and a dry run's), each script's
        # undo, and the pre-switch checks, with their bash; null without any.
        activation = config.system.build.activation;
        # The secrets' names: the phone checks each is there, and removes one that left.
        secrets = lib.attrNames config.android.secrets;
        # The services (systemd.services, Nixup's own and NixOS's modules' among them) as the
        # engine runs them, with the view's /etc files (environment.etc), hosts and DNS servers
        # (networking.hosts, networking.nameservers) and the folders systemd.tmpfiles makes; and
        # every store path the phone needs for them, environment.systemPackages and the
        # terminal's font: it fetches those from cache.nixos.org (and builds the small files the
        # config makes, as derivations says) before the build changes anything.
        units = config.system.build.units;
        # Their timers (systemd.timers, startAt), which the engine keeps, with system.autoUpgrade's, which runs
        # the module's build.sh at its times (nix/auto-upgrade.nix).
        timers = config.system.build.timers // config.system.build.autoUpgrade;
        # Their path units (systemd.paths).
        paths = config.system.build.paths;
        etc = config.system.build.etc;
        inherit (config.system.build) network tmpfiles;
        # (Without the paths the phone keeps but no cache has: the registry's nixpkgs, which /etc's
        # registry.json names. See nix below.)
        storePaths = lib.subtractLists (if config.system.build.nix == null then [ ] else config.system.build.nix.kept)
          (lib.unique (storePathsIn needed ++ config.system.build.backupStorePaths ++ config.system.build.terminalStorePaths));
        inherit derivations;
        # The machines that build what cache.nixos.org has not got (nix.buildMachines, with
        # nix.distributedBuilds: nix/nix.nix), and the ssh Nix reaches them with; null: none.
        builders = if config.system.build.nixBuilders == null then null
          else { inherit (config.system.build.nixBuilders) machines ssh; };
        # Nix on the shells (nix/nix.nix): its commands, and the paths the phone keeps (the
        # registry's flakes, the config's nixpkgs among them); null with nix.enable = false.
        inherit (config.system.build) nix;
        # nixup-shell's PATH (environment.systemPackages first; a user's profile goes before it) and
        # its bash, as a shell on NixOS has them; none without Nix packages.
        shell = if !anyPackages then null else {
          path = builtins.unsafeDiscardStringContext
            "${lib.makeBinPath (config.environment.systemPackages ++ shellPackages)}:/system/bin";
          bash = builtins.unsafeDiscardStringContext "${pkgs.bashInteractive}/bin/bash";
        };
      };
    };
  };
}
