# Options that moved, as NixOS's rename.nix keeps its own: a config with an old name still builds,
# and the build says the name to use instead.
{ lib, ... }:
let
  # Tailscale took NixOS's name, services.tailscale (nix/services.nix).
  old = name: [ "android" "services" "tailscale" name ];
  new = name: [ "services" "tailscale" name ];
in
{
  imports = map (name: lib.mkRenamedOptionModule (old name) (new name))
    [ "enable" "package" "port" "authKeyFile" "extraUpFlags" "extraDaemonFlags" ]
  ++ [
    # The name on the tailnet is networking.hostName now, which names the phone too. The old option
    # named it on the tailnet only, which `tailscale up --hostname` still does.
    (lib.mkChangedOptionModule (old "hostname") (new "extraUpFlags") (config:
      let name = lib.getAttrFromPath (old "hostname") config;
      in lib.optional (name != null) "--hostname=${name}"))
  ];
}
