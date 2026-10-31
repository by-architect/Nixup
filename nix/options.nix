# The options a phone config can set. A config that sets anything else, or a
# value of the wrong type, stops the build with an error; so does a failed
# assertion, and warnings are shown, as in NixOS.
{ config, lib, ... }:
let
  inherit (lib) mkOption types;

  # An app picked from pkgs.fdroid (with pkgs.fdroid; [ org.example.app ]): a
  # node of the package name tree that holds the full name in _id.
  fdroidApp = types.addCheck types.attrs (app: app ? _id) // {
    description = "F-Droid app (from pkgs.fdroid)";
  };
  fdroidIds = map (app: if builtins.isString app then app else app._id) config.android.packages.fdroid;
  twice = lib.unique (lib.filter (id: lib.count (other: other == id) fdroidIds > 1) fdroidIds);
  notNames = lib.filter (id: builtins.match "[A-Za-z][A-Za-z0-9_]*([.][A-Za-z][A-Za-z0-9_]*)+" id == null) fdroidIds;
in
{
  options = {
    assertions = mkOption {
      type = types.listOf (types.submodule {
        options.assertion = mkOption { type = types.bool; };
        options.message = mkOption { type = types.str; };
      });
      default = [ ];
      internal = true;
      description = "Checks that must hold, or the build stops with their messages.";
    };

    warnings = mkOption {
      type = types.listOf types.str;
      default = [ ];
      internal = true;
      description = "Messages shown while the config builds.";
    };

    android.darkMode = mkOption {
      type = types.nullOr types.bool;
      default = null;
      description = "Dark mode on (true) or off (false); null leaves it as the user sets it.";
    };

    android.packages.fdroid = mkOption {
      type = types.listOf (types.either types.str fdroidApp);
      default = [ ];
      example = lib.literalExpression "with pkgs.fdroid; [ com.byarchitect.dejavu ]";
      description = ''
        Apps installed from F-Droid: names from pkgs.fdroid, or package names as
        strings (a name with a Nix keyword in it, like "in.example.app", needs one).
      '';
    };
  };

  config.assertions = [
    {
      assertion = twice == [ ];
      message = "android.packages.fdroid lists ${lib.concatStringsSep ", " twice} more than once: list each app once.";
    }
    {
      assertion = notNames == [ ];
      message = "android.packages.fdroid: ${lib.concatStringsSep ", " notNames} is not a package name (like org.example.app).";
    }
  ];
}
