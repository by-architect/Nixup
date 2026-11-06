# NixOS's own service modules, from the config's nixpkgs, on the phone as they are: services.ntfy-sh.enable = true;
# runs nixpkgs' nixos/modules/services/misc/ntfy-sh.nix, which makes its systemd.services units, its users and its
# /etc files, as on NixOS. Nixup runs those (nix/units.nix, nix/users.nix, nix/etc.nix), with NixOS's other options
# such a module sets or reads (nix/nixos-compat.nix).
#
# The ones below are those Nixup has tried: they build with its options (scripts/test-nix-services.sh), and ask for
# nothing the build knows the phone has not got (a few ran on a phone; ~/Projects/Andronix/tests/nixos-bridge.md
# says which). Some are here as other modules use their options (nginx, PostgreSQL, Redis, ...). Another of
# nixpkgs' modules can be added by hand, as on NixOS (imports = [ "${modulesPath}/services/web-apps/foo.nix" ];):
# what it needs that the phone has not got stops the build and says so. One of the list can be left out the way
# NixOS leaves out its own (disabledModules = [ "services/misc/ntfy-sh.nix" ];), for a module of one's own that
# declares the same options.
#
# A configuration.nix built alone (no flake) has no nixpkgs until it uses a Nix package: a first look at it has these
# services' options as placeholders, which take anything, so that look can say whether it needs nixpkgs (one is set).
{ options, lib, _nixup, ... }:
let
  inherit (lib) mkOption types;
  nixpkgs = _nixup.nixpkgs;
  source = if nixpkgs == null then null else nixpkgs.outPath or nixpkgs;

  # Each service's option (services.<name>), and its module in nixpkgs' nixos/modules.
  services = {
    adguardhome = "services/networking/adguardhome.nix";
    aria2 = "services/networking/aria2.nix";
    atd = "services/scheduling/atd.nix";
    audiobookshelf = "services/web-apps/audiobookshelf.nix";
    bazarr = "services/misc/bazarr.nix";
    blocky = "services/networking/blocky.nix";
    caddy = "services/web-servers/caddy/default.nix";
    calibre-web = "services/web-apps/calibre-web.nix";
    code-server = "services/web-apps/code-server.nix";
    coredns = "services/networking/coredns.nix";
    couchdb = "services/databases/couchdb.nix";
    cron = "services/scheduling/cron.nix";
    deluge = "services/torrent/deluge.nix";
    dnscrypt-proxy = "services/networking/dnscrypt-proxy.nix";
    dnsmasq = "services/networking/dnsmasq.nix";
    endlessh = "services/security/endlessh.nix";
    filebrowser = "services/web-apps/filebrowser.nix";
    forgejo = "services/misc/forgejo.nix";
    freshrss = "services/web-apps/freshrss.nix";
    gitea = "services/misc/gitea.nix";
    gonic = "services/audio/gonic.nix";
    gotify = "services/web-apps/gotify-server.nix";
    gotosocial = "services/web-apps/gotosocial.nix";
    grafana = "services/monitoring/grafana.nix";
    harmonia = "services/networking/harmonia.nix";
    headscale = "services/networking/headscale.nix";
    homepage-dashboard = "services/misc/homepage-dashboard.nix";
    httpd = "services/web-servers/apache-httpd/default.nix";
    i2pd = "services/networking/i2pd.nix";
    influxdb2 = "services/databases/influxdb2.nix";
    jellyfin = "services/misc/jellyfin.nix";
    kavita = "services/web-apps/kavita.nix";
    komga = "services/web-apps/komga.nix";
    lidarr = "services/misc/servarr/lidarr.nix";
    lighttpd = "services/web-servers/lighttpd/default.nix";
    linkding = "services/web-apps/linkding.nix";
    llama-cpp = "services/misc/llama-cpp.nix";
    loki = "services/monitoring/loki.nix";
    matrix-conduit = "services/matrix/conduit.nix";
    mealie = "services/web-apps/mealie.nix";
    minidlna = "services/networking/minidlna.nix";
    mosquitto = "services/networking/mosquitto.nix";
    mysql = "services/databases/mysql.nix";
    navidrome = "services/audio/navidrome.nix";
    nginx = "services/web-servers/nginx/default.nix";
    node-red = "services/web-apps/node-red.nix";
    ntfy-sh = "services/misc/ntfy-sh.nix";
    ollama = "services/misc/ollama.nix";
    phpfpm = "services/web-servers/phpfpm/default.nix";
    postgresql = "services/databases/postgresql.nix";
    privoxy = "services/networking/privoxy.nix";
    prometheus = "services/monitoring/prometheus/default.nix";
    prowlarr = "services/misc/servarr/prowlarr.nix";
    qbittorrent = "services/torrent/qbittorrent.nix";
    radarr = "services/misc/servarr/radarr.nix";
    radicale = "services/networking/radicale.nix";
    readarr = "services/misc/servarr/readarr.nix";
    redis = "services/databases/redis.nix";
    soft-serve = "services/misc/soft-serve.nix";
    sonarr = "services/misc/servarr/sonarr.nix";
    stirling-pdf = "services/web-apps/stirling-pdf.nix";
    tor = "services/security/tor.nix";
    transmission = "services/torrent/transmission.nix";
    ttyd = "services/web-servers/ttyd.nix";
    unbound = "services/networking/unbound.nix";
    uptime-kuma = "services/monitoring/uptime-kuma.nix";
    vaultwarden = "services/security/vaultwarden/default.nix";
    vnstat = "services/monitoring/vnstat.nix";
    webdav = "services/network-filesystems/webdav.nix";
  };

  # NixOS's modules of other options (networking.<name>): stevenblack's hosts blocklist, in networking.hostFiles
  # (nix/nixos-compat.nix), so it is in the view's /etc/hosts.
  networking = {
    stevenblack = "config/stevenblack.nix";
  };

  # What NixOS's modules take for granted: NixOS's fixed uids and gids (ids), the modules' maintainers and docs
  # (meta), and the `utils` argument.
  support = [ "misc/ids.nix" "misc/meta.nix" "misc/extra-arguments.nix" "security/dhparams.nix" ];

  placeholder = prefix: name: mkOption {
    type = types.lazyAttrsOf types.anything;
    default = { };
    internal = true;
    description = "${prefix}.${name}, from NixOS's module, once nixpkgs is there.";
  };
  set = option: option.highestPrio < (lib.mkOptionDefault null).priority;
in
{
  imports = lib.optionals (source != null)
    (map (file: source + "/nixos/modules/${file}") (support ++ lib.attrValues services ++ lib.attrValues networking));

  options = lib.optionalAttrs (source == null) {
    services = lib.mapAttrs (name: _: placeholder "services" name) services;
    networking = lib.mapAttrs (name: _: placeholder "networking" name) networking;
  };

  config.system.build.nixosServices = {
    # For a configuration.nix's first look: whether it sets one of them (so it needs nixpkgs).
    used = source == null && (lib.any (name: set options.services.${name}) (lib.attrNames services)
      || lib.any (name: set options.networking.${name}) (lib.attrNames networking));
    files = services;
  };
}
