# Other versions of the config, as NixOS's specialisation: each build makes them too, next to the config itself, and
# the phone can switch to one and back without building again (build.sh again --specialisation work, or the app's
# Specialisations; a build picks one with --specialisation, as nixos-rebuild does):
#
#   specialisation.work.configuration = {
#     services.syncthing.enable = false;
#     android.apps."org.example.game".debloat = true;
#   };
#
# A specialisation is the whole config with its own lines over it (inheritParentConfig = false: Nixup's options with
# its lines alone). Its profile goes into the config's, under specialisation.<name> (nix/build.nix), so a generation
# keeps all of them, and the phone applies the one picked as it applies a config: its apps, settings and services.
# Unlike NixOS, which starts the config itself again after a restart (its boot entry), the phone keeps the one picked
# across restarts, until a build or a switch picks another (or none: the config itself).
{ config, lib, extendModules, _nixup, ... }:
let
  inherit (lib) mkOption types;

  # What NixOS's no-clone.nix says to a specialisation: it has none of its own, and it is one.
  noClone = { lib, ... }: {
    specialisation = lib.mkOverride 0 { };
    isSpecialisation = lib.mkOverride 0 true;
  };
in
{
  options = {
    isSpecialisation = mkOption {
      type = types.bool;
      internal = true;
      default = false;
      description = "Whether this config is a specialisation of another, as on NixOS.";
    };

    specialisation = mkOption {
      default = { };
      example = lib.literalExpression ''{ work.configuration = { services.syncthing.enable = false; }; }'';
      description = ''
        Other versions of the config, as NixOS's specialisation: each build makes them too, and the phone switches to
        one (build.sh again --specialisation <name>, nixup-rebuild switch --specialisation <name>, or the app's
        Specialisations) and back without building again. A specialisation is the config with its own lines over it,
        or (inheritParentConfig = false) its own lines alone. The phone keeps the one picked across restarts, until
        a build picks another, or none.
      '';
      type = types.attrsOf (types.submodule (local@{ ... }:
        let
          extend = if local.config.inheritParentConfig then extendModules else _nixup.noUserModules.extendModules;
        in
        {
          options.inheritParentConfig = mkOption {
            type = types.bool;
            default = true;
            description = "Whether it is the whole config with its own lines over it (false: Nixup's options and its lines alone).";
          };

          options.configuration = mkOption {
            default = { };
            visible = "shallow";
            description = ''
              The specialisation's lines, as a configuration.nix has them (imports too); a specialisation of its own in
              it is left out, as on NixOS.
            '';
            inherit (extend { modules = [ noClone ]; }) type;
          };
        }));
    };
  };

  config = {
    # The phone's scripts and the app take a specialisation's name as one word.
    assertions = lib.mapAttrsToList (name: _: {
      assertion = builtins.match "[A-Za-z0-9][A-Za-z0-9_.-]*" name != null;
      message = "specialisation.\"${name}\": a specialisation's name is letters, digits and _ . - (starting with a letter or digit).";
    }) config.specialisation;
  };
}
