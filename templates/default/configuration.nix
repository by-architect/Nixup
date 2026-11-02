# Edit this configuration file to define what should be on your phone, its apps and
# its settings, as /etc/nixos/configuration.nix does for NixOS. Then press Build in the
# Seraph app (or the module's Action button in Magisk): Nix on the phone checks it and
# applies it. A setting left out is not managed, and one taken out again gets back the
# value it had before.
#
#   android.packages.fdroid = with pkgs.fdroid; [    apps from F-Droid
#     org.example.app
#     "in.example.app"                               a name with a Nix keyword (in, if, ...) in quotes
#   ];
#
#   android.settings.repo.fdroid.autoupdatePackages = true;   those apps update by themselves
#                                                    (every 6 hours on Wi-Fi; false: only a notification)
#
#   android.repos.mine.url = "https://example.org/fdroid/repo?fingerprint=…";
#                                                    another F-Droid repository, named mine
#   android.packages.mine = with pkgs.mine; [ org.example.other ];   its apps
#   android.settings.repo.mine.autoupdatePackages = true;
#
#   android.packages.obtainium = [                   apps from their developers' releases, as
#     { id = "org.example.app";                      Obtainium gets them (GitHub, GitLab, Codeberg);
#       url = "https://github.com/example/app"; }    Obtainium, when installed, lists them too
#     { id = "org.example.other";
#       url = "https://codeberg.org/example/other";
#       apkFilter = "^(?!.*debug)";                  picks one APK when a release has several
#       signer = "AB:CD:…";                          its key's SHA-256: an APK with another is refused
#       prerelease = true; }                         takes pre-releases too
#   ];
#   android.settings.repo.obtainium.autoupdatePackages = true;
#
#   android.settings.display = {                     the phone's own settings (nix/system.nix has all;
#     darkMode = true;                               one this phone cannot have is told when building)
#     fontScale = 1.15;
#     density = 420;                                 display size, in dpi
#     screenTimeout = 60;                            seconds
#   };
#   android.settings.navigation.mode = "gestures";   "gestures", "threeButton"
#   android.settings.language = [ "tr-TR" "en-US" ];   several languages, in order
#   android.settings.network.privateDns = "dns.quad9.net";   "off", "automatic", or a provider
#   android.settings.lockScreen.sensitiveNotifications = false;
#   android.settings.raw.global.stay_on_while_plugged_in = 7;   any of Android's settings
#
#   android.settings.lineage.statusBar.batteryPercent = "nextTo";   LineageOS's own settings, on
#   android.settings.lineage.chargingControl = {     ROMs based on it (nix/lineage.nix has all)
#     enable = true; mode = "limit"; limit = 80;     stop charging at 80%
#   };
#
#   android.apps."org.example.app" = {               an installed app's settings
#     permissions.camera = false;                    true, false (denied), "ask"
#     permissions.location = "whileUsing";           "always", "whileUsing", false, "ask"
#     battery = "restricted";                        "unrestricted", "optimized", "restricted"
#     network.backgroundData = false;
#     access.displayOverOtherApps = true;
#     language = "tr-TR";
#     defaultFor = [ "browser" ];
#   };
#   android.apps."org.lineageos.jelly".debloat = true;   removes an app that came with the phone
#                                                    (its data kept: taken out again, it comes back)
#
#   networking.hostName = "phone";                   the phone's name (its device name, and its
#   time.timeZone = "Europe/Istanbul";               name on the tailnet), time zone and language,
#   i18n.defaultLocale = "tr_TR.UTF-8";              with NixOS's names: a module shared with a
#                                                    NixOS machine sets them on both
#
#   services.tailscale.enable = true;                Tailscale, run by root, with NixOS's
#                                                    services.tailscale options (the build shows a
#                                                    link to log in with; it takes no VPN slot)
#   services.syncthing = {                           Syncthing, with NixOS's options
#     enable = true;
#     settings.devices.laptop.id = "ABCDEFG-…";
#     settings.folders.documents = { path = "/storage/emulated/0/Documents"; devices = [ "laptop" ]; };
#   };
#
#   systemd.services.web = {                         your own service, as on NixOS: programs from
#     wantedBy = [ "multi-user.target" ];            Nix packages, which the phone fetches from
#     serviceConfig.ExecStart =                      cache.nixos.org and runs as root
#       "''${pkgs.python3}/bin/python3 -m http.server 8000";
#   };
#   environment.systemPackages = with pkgs; [ htop rsync ];   Nix packages for root's shell: the
#                                                    app's terminal, ssh, su -c seraph-shell
#
#   services.openssh = {                             SSH, with NixOS's options: logins get root's
#     enable = true;                                 shell with those packages, as the terminal has
#     listenAddresses = [ { addr = "127.0.0.1"; port = 22; } ];   tailnet only (through Tailscale)
#   };
#   users.users.root.openssh.authorizedKeys.keys = [ "ssh-ed25519 AAAA… you@laptop" ];
#
#   android.adb = {                                  adb: over the network too, from boot on, and
#     port = 5555;                                   computers let in without asking (each one's
#     trustedKeys = [ "QAAAA…= you@laptop" ];        ~/.android/adbkey.pub)
#     forgetUnused = false;
#   };
#
#   services.tailscale.authKeyFile = ./tailscale.key;    a secret file from the config's folder
#                                                    (no quotes): each build puts it on the phone,
#                                                    readable by root alone, never in the profile
#
# Built on the phone (the app's Build), or on a PC and applied on the phone over adb or ssh, as
# nixos-rebuild --target-host: nix run github:by-architect/Seraph -- switch --flake .#phone
#
# Nix packages come from nixos-unstable (a flake can give its own nixpkgs); the phone fetches them,
# and builds nothing.
#
# Every option, with what it does: nix/options.nix, nix/system.nix, nix/lineage.nix,
# nix/app.nix, nix/global.nix, nix/units.nix, nix/services.nix, nix/syncthing.nix,
# nix/openssh.nix, nix/adb.nix and nix/nixpkgs.nix in the project.
{ config, lib, pkgs, ... }:
{
  android.packages.fdroid = with pkgs.fdroid; [
  ];
}
