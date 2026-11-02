# Secrets: files that programs of the config read on the phone (a login key, a token), which never go
# into the profile or Nix's store, as agenix's age.secrets does for NixOS. Each is a file on the
# machine the config is built on, which seraph-rebuild sends to the phone with the profile; there it
# is /data/adb/seraph/secrets/<name>, which root alone can read, and the options that need it take
# that path: services.tailscale.authKeyFile = config.android.secrets.tailscale.path;
#
# A build on the phone itself sends nothing: a secret must be there already (one a PC sent stays,
# until it leaves the config). The profile has each secret's name only, and a build stops before
# changing anything when one is missing. A secret can also be text the config reads from elsewhere
# (a JSON file of secrets): seraph-rebuild writes it to a file only you can read, and sends that.
{ config, lib, ... }:
let
  inherit (lib) mkOption types;

  dir = "/data/adb/seraph/secrets";

  secretModule = { name, ... }: {
    options = {
      file = mkOption {
        type = types.nullOr types.path;
        default = null;
        example = lib.literalExpression "./secrets/tailscale.key";
        description = ''
          The file on the machine the config is built on, which seraph-rebuild sends to the phone. A
          path (./secrets/tailscale.key) is read where it is: Seraph never puts it in Nix's store or
          the profile (a flake's whole folder is in the store anyway, so keep the file out of a flake
          that others can read). null: it gets to the phone another way, and a build only checks that
          it is there.
        '';
      };

      text = mkOption {
        type = types.nullOr types.str;
        default = null;
        example = lib.literalExpression ''lib.concatLines secrets.syncthing.devices.phone.cert'';
        description = ''
          The secret itself, as text the config reads from elsewhere (a JSON file of secrets, which
          stays out of the profile too), for seraph-rebuild to send; instead of file.
        '';
      };

      path = mkOption {
        type = types.str;
        default = "${dir}/${name}";
        defaultText = lib.literalExpression ''"${dir}/<name>"'';
        readOnly = true;
        description = "Where the secret is on the phone, for the options that need it, as agenix's age.secrets.<name>.path.";
      };
    };
  };
in
{
  options.android.secrets = mkOption {
    type = types.attrsOf (types.submodule secretModule);
    default = { };
    example = lib.literalExpression ''
      {
        tailscale.file = ./secrets/tailscale.key;
      }
    '';
    description = ''
      Secrets, by a name of your choosing: files that programs of the config read on the phone,
      which seraph-rebuild sends there from the machine the config is built on. On the phone each is
      /data/adb/seraph/secrets/<name> (android.secrets.<name>.path), readable by root alone.
    '';
  };

  config.assertions =
    let
      badNames = lib.filter (name: builtins.match "[A-Za-z0-9][A-Za-z0-9_.-]*" name == null) (lib.attrNames config.android.secrets);
      both = lib.attrNames (lib.filterAttrs (_: secret: secret.file != null && secret.text != null) config.android.secrets);
    in
    [
      {
        assertion = badNames == [ ];
        message = "android.secrets: ${lib.concatStringsSep ", " badNames} is not a name of letters, digits, _ . and - (like tailscale-key).";
      }
      {
        assertion = both == [ ];
        message = "android.secrets: ${lib.concatStringsSep ", " both} has both file and text: give one.";
      }
    ];
}
