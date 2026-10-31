# The phone's settings. scripts/build-magisk-debug.sh turns this file into the
# profile the Magisk module applies; the options are listed in nix/options.nix.
{ ... }:
{
  android.darkMode = true;
}
