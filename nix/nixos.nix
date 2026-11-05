# NixOS's own service modules, from the config's nixpkgs, on the phone as they are: services.ntfy-sh.enable = true;
# runs nixpkgs' nixos/modules/services/misc/ntfy-sh.nix, which makes its systemd.services units, its users and its
# /etc files, as on NixOS. Seraph runs those (nix/units.nix, nix/users.nix, nix/etc.nix), with NixOS's other options
# such a module sets or reads (nix/nixos-compat.nix).
#
# The ones below are those Seraph has tried: they build, and they run on a phone with what the phone has. Another of
# nixpkgs' modules can be added by hand, as on NixOS (imports = [ "${modulesPath}/services/web-apps/foo.nix" ];):
# what it needs that the phone has not got stops the build and says so. One of the list can be left out the way
# NixOS leaves out its own (disabledModules = [ "services/misc/ntfy-sh.nix" ];), for a module of one's own that
# declares the same options.
#
# A configuration.nix built alone (no flake) has no nixpkgs until it uses a Nix package: a first look at it has these
# services' options as placeholders, which take anything, so that look can say whether it needs nixpkgs (one is set).
{ options, lib, _seraph, ... }:
let
  inherit (lib) mkOption types;
  nixpkgs = _seraph.nixpkgs;
  source = if nixpkgs == null then null else nixpkgs.outPath or nixpkgs;

  # Each service's option (services.<name>), and its module in nixpkgs' nixos/modules.
  services = {
    adguardhome = "services/networking/adguardhome.nix";
    aria2 = "services/networking/aria2.nix";
    atd = "services/scheduling/atd.nix";
    blocky = "services/networking/blocky.nix";
    code-server = "services/web-apps/code-server.nix";
    cron = "services/scheduling/cron.nix";
    dnscrypt-proxy = "services/networking/dnscrypt-proxy.nix";
    endlessh = "services/security/endlessh.nix";
    filebrowser = "services/web-apps/filebrowser.nix";
    forgejo = "services/misc/forgejo.nix";
    gonic = "services/audio/gonic.nix";
    gotify = "services/web-apps/gotify-server.nix";
    i2pd = "services/networking/i2pd.nix";
    llama-cpp = "services/misc/llama-cpp.nix";
    minidlna = "services/networking/minidlna.nix";
    mosquitto = "services/networking/mosquitto.nix";
    navidrome = "services/audio/navidrome.nix";
    node-red = "services/web-apps/node-red.nix";
    ntfy-sh = "services/misc/ntfy-sh.nix";
    ollama = "services/misc/ollama.nix";
    radicale = "services/networking/radicale.nix";
    soft-serve = "services/misc/soft-serve.nix";
    ttyd = "services/web-servers/ttyd.nix";
    unbound = "services/networking/unbound.nix";
    uptime-kuma = "services/monitoring/uptime-kuma.nix";
    vnstat = "services/monitoring/vnstat.nix";
    webdav = "services/network-filesystems/webdav.nix";
  };

  # What NixOS's modules take for granted: NixOS's fixed uids and gids (ids), the modules' maintainers and docs
  # (meta), and the `utils` argument.
  support = [ "misc/ids.nix" "misc/meta.nix" "misc/extra-arguments.nix" ];

  placeholder = name: mkOption {
    type = types.lazyAttrsOf types.anything;
    default = { };
    internal = true;
    description = "services.${name}, from NixOS's module, once nixpkgs is there.";
  };
in
{
  imports = lib.optionals (source != null) (map (file: source + "/nixos/modules/${file}") (support ++ lib.attrValues services));

  options = lib.optionalAttrs (source == null) {
    services = lib.mapAttrs (name: _: placeholder name) services;
  };

  config.system.build.nixosServices = {
    # For a configuration.nix's first look: whether it sets one of them (so it needs nixpkgs).
    used = source == null && lib.any (name: options.services.${name}.highestPrio < (lib.mkOptionDefault null).priority) (lib.attrNames services);
    files = services;
  };
}
