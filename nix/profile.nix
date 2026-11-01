# Turns a phone config into the profile, the JSON the Magisk module applies:
#   nix-instantiate --eval --strict --json nix/profile.nix --argstr configFile "$PWD/config/phone.nix"
# Only nixpkgs' lib is used, so evaluating it builds and downloads nothing.
#
# reposDir holds, for each repository the module has an index of, <name>/tree.json:
# the tree of its package names, seen by the config as pkgs.<name> (without it, pkgs
# is empty). The repositories are in the config itself, so the module first
# evaluates it with reposOnly = true: that needs no package names, and gives the
# repositories to fetch the indexes of before the whole config is evaluated.
{ configFile, reposDir ? "", reposOnly ? false, lib ? import <nixpkgs/lib> }:
let
  hasTree = name: type: type == "directory" && builtins.pathExists "${reposDir}/${name}/tree.json";
  pkgs =
    if reposDir == "" || !builtins.pathExists reposDir then { }
    else lib.mapAttrs (name: _: lib.importJSON "${reposDir}/${name}/tree.json") (lib.filterAttrs hasTree (builtins.readDir reposDir));

  eval = lib.evalModules {
    modules = [ ./options.nix (/. + configFile) ];
    specialArgs = { inherit pkgs; };
  };
  cfg = eval.config;

  repos = lib.mapAttrs (_: repo: { url = repo.address; fingerprint = repo.signingKey; }) cfg.android.repos;

  failed = map (check: check.message) (lib.filter (check: !check.assertion) cfg.assertions);

  # The phone's own settings: the settings tables (Android's system, secure and global, and
  # LineageOS's as lineage.system, ...; true and false as 1 and 0), with the plain option that set
  # each, and those that take a command; a table or option that is not set is left out.
  settings = cfg.android.settings;
  tables = removeAttrs settings.raw [ "lineage" ]
    // lib.mapAttrs' (name: lib.nameValuePair "lineage.${name}") settings.raw.lineage;
  system = lib.filterAttrs (_: value: value != null && value != { }) {
    settings = lib.filterAttrs (_: table: table != { }) (lib.mapAttrs (_: lib.mapAttrs (_: value:
      if builtins.isBool value then (if value then 1 else 0) else value
    )) tables);
    labels = cfg.android.settingLabels;
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
if reposOnly then
  { android = { inherit repos; }; }
else if failed != [ ] then
  throw "\nFailed assertions:\n${lib.concatMapStrings (message: "- ${message}\n") failed}"
else
  lib.showWarnings cfg.warnings {
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
      )) cfg.android.packages;
      settings = lib.mapAttrs (name: _: { autoupdatePackages = settings.repo.${name}.autoupdatePackages or false; })
        (repos // { obtainium = { }; });
      apps = lib.filterAttrs (_: settings: settings != { }) (lib.mapAttrs (_: managed) cfg.android.apps);
      inherit system;
    };
  }
