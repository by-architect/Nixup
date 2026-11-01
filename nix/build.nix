# What a build makes of a configuration, as NixOS has config.system.build.toplevel:
#   config.system.build.repos     the repositories, whose indexes the phone fetches first: it
#                                 needs no package names, so it evaluates with pkgs empty
#   config.system.build.profile   the profile, the JSON the module applies; evaluating it checks
#                                 the assertions and shows the warnings, as nixos-rebuild does
#   config.system.build.secrets   each secret's file where the config is built (or null), which
#                                 seraph-rebuild sends to the phone; never in the profile
{ config, lib, ... }:
let
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
    repos = { android = { inherit repos; }; };

    # toString: a path stays where it is, where interpolating it would copy it into the store.
    secrets = lib.mapAttrs (_: secret: if secret.file == null then null else toString secret.file) config.android.secrets;

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
            # An F-Droid app as its package name; an app from its own releases with where they are.
            packages = lib.mapAttrs (_: map (app:
              if builtins.isString app then app
              else if !(app ? _id) then {
                inherit (app) id url apkFilter prerelease;
                signer = if app.signer == null then null else lib.toLower (lib.replaceStrings [ ":" ] [ "" ] app.signer);
              }
              else app._id
            )) config.android.packages;
            settings = lib.mapAttrs (name: _: { autoupdatePackages = settings.repo.${name}.autoupdatePackages or false; })
              (repos // { obtainium = { }; });
            # debloat only when true: false is the same as not managed.
            apps = lib.filterAttrs (_: settings: settings != { }) (lib.mapAttrs (_: app:
              managed (app // { debloat = if app.debloat == true then true else null; })
            ) config.android.apps);
            inherit system;
            # The secrets' names: the phone checks each is there, and removes one that left.
            secrets = lib.attrNames config.android.secrets;
            # The services that run, with what each needs; one that left the config is stopped.
            services = lib.optionalAttrs config.services.tailscale.enable {
              tailscale = let ts = config.services.tailscale; in {
                inherit (ts) package port authKeyFile;
                # Its name on the tailnet; null: the phone's device name.
                hostname = if config.networking.hostName == "" then null else config.networking.hostName;
                upFlags = ts.extraUpFlags;
                daemonFlags = ts.extraDaemonFlags
                  ++ lib.optional (ts.disableUpstreamLogging && !lib.elem "--no-logs-no-support" ts.extraDaemonFlags) "--no-logs-no-support";
              };
            };
          };
        };
  };
}
