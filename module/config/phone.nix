# This phone's config. Edit it, then press Build in the Andronix app (or the
# module's Action button in Magisk): Nix on the phone checks it and applies it.
# A setting left out is not managed, and one taken out again gets back the value
# it had before.
#
#   android.darkMode = true;                         dark mode on (false: off)
#
#   android.packages.fdroid = with pkgs.fdroid; [    apps from F-Droid
#     org.example.app
#     "in.example.app"                               a name with a Nix keyword (in, if, ...) in quotes
#   ];
#
#   android.settings.fdroid.autoupdatePackages = true;   those apps update by themselves
#                                                    (every 6 hours on Wi-Fi; false: only a notification)
#
#   android.repos.mine.url = "https://example.org/fdroid/repo?fingerprint=…";
#                                                    another F-Droid repository, named mine
#   android.packages.mine = with pkgs.mine; [ org.example.other ];   its apps
#   android.settings.mine.autoupdatePackages = true;
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
#   android.settings.obtainium.autoupdatePackages = true;
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
# Every option, with what it does: nix/options.nix and nix/app.nix in the project.
{ pkgs, ... }:
{
  android.packages.fdroid = with pkgs.fdroid; [
  ];
}
