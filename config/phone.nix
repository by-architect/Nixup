# This phone's config. Edit it, then press Build in the Andronix app (or the
# module's Action button in Magisk): Nix on the phone checks it and applies it.
#
# Options:
#   android.darkMode         true (on), false (off), or null (left as you set it)
#   android.packages.fdroid  apps from F-Droid, by package name:
#                              with pkgs.fdroid; [ org.example.app ]
#                            a name with a Nix keyword in it (in, if, ...) goes in quotes:
#                              [ "in.example.app" ]
{ pkgs, ... }:
{
  android.darkMode = false;

  android.packages.fdroid = with pkgs.fdroid; [
  app.pagedrop
  ];
}
