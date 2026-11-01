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
#   android.packages.debloat = [ "org.lineageos.jelly" ];   apps that came with the phone, removed
#                                                    (their data kept: out of the list, they come back)
#
#   android.settings.display = {                     the phone's own settings (nix/system.nix has all;
#     darkMode = true;                               one this phone cannot have is told when building)
#     fontScale = 1.15;
#     density = 420;                                 display size, in dpi
#     screenTimeout = 60;                            seconds
#   };
#   android.settings.navigation.mode = "gestures";   "gestures", "threeButton"
#   android.settings.language = [ "tr-TR" "en-US" ];
#   android.settings.time.timeZone = "Europe/Istanbul";
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
#
# Every option, with what it does: nix/options.nix, nix/system.nix, nix/lineage.nix and
# nix/app.nix in the project.
{ config, lib, pkgs, ... }:
{
  android.packages.fdroid = with pkgs.fdroid; [
  ];
}
