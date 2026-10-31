# Turns a phone config into the profile, the JSON the Magisk module applies:
#   nix-instantiate --eval --strict --json nix/profile.nix --argstr configFile "$PWD/config/phone.nix"
# Only nixpkgs' lib is used, so evaluating it builds and downloads nothing.
{ configFile, lib ? import <nixpkgs/lib> }:
let
  eval = lib.evalModules {
    modules = [ ./options.nix (/. + configFile) ];
  };
in
{
  # The module refuses a profile whose schemaVersion it does not know.
  schemaVersion = 1;
  inherit (eval.config) android;
}
