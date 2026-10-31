# Turns a phone config into the profile, the JSON the Magisk module applies:
#   nix-instantiate --eval --strict --json nix/profile.nix --argstr configFile "$PWD/config/phone.nix"
# Only nixpkgs' lib is used, so evaluating it builds and downloads nothing.
# fdroidTree is the tree of F-Droid package names the module makes from the
# index on the phone, seen by the config as pkgs.fdroid; without it, it is empty.
{ configFile, fdroidTree ? "", lib ? import <nixpkgs/lib> }:
let
  pkgs.fdroid = if fdroidTree == "" then { } else lib.importJSON (/. + fdroidTree);

  eval = lib.evalModules {
    modules = [ ./options.nix (/. + configFile) ];
    specialArgs = { inherit pkgs; };
  };
  cfg = eval.config;

  failed = map (check: check.message) (lib.filter (check: !check.assertion) cfg.assertions);
in
if failed != [ ] then
  throw "\nFailed assertions:\n${lib.concatMapStrings (message: "- ${message}\n") failed}"
else
  lib.showWarnings cfg.warnings {
    # The module refuses a profile whose schemaVersion it does not know.
    schemaVersion = 1;
    android = {
      inherit (cfg.android) darkMode;
      packages.fdroid = map (app: if builtins.isString app then app else app._id) cfg.android.packages.fdroid;
    };
  }
